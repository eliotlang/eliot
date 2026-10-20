package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import cats.data.StateT
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.{Expression => CoreExpression}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{ClassGenerator, JvmIdentifier}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.ClassGenerator.createClassGenerator
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.CommonPatterns.{
  constructorArityValue,
  constructorDataTypeValue,
  mangledMethodName,
  valueType
}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType.{convertToNestedClassName, systemAnyValue, systemFunctionValue, systemUnitValue}
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.{ClassFile, GeneratedModule}
import com.vanillasource.eliot.eliotc.jvm.jargen.SyntheticMainSourceProcessor
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.DataClassGenerator.isConstructor
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.ExpressionCodeGenerator.{createExpressionCodeAtBoundaryWidth, patternMatchSingletonName}
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.NativeImplementation.implementations
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.TypeState.*
import com.vanillasource.eliot.eliotc.ability.util.ImplementationMarkerUtils
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleConstructors,
  ModuleName,
  UnifiedModuleValue,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicValue
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort
import com.vanillasource.eliot.eliotc.uncurry.fact.*
import com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.*
import com.vanillasource.eliot.eliotc.used.UsedNames
import com.vanillasource.eliot.eliotc.used.UsedNames.UsageStats
import org.objectweb.asm.{Label, Opcodes}

class JvmClassGenerator extends SingleKeyTypeProcessor[GeneratedModule.Key] with Logging {

  override protected def generateFact(key: GeneratedModule.Key): CompilerIO[Unit] =
    for {
      _                      <- debug[CompilerIO](s"Generating ${key.moduleName.show} (from ${key.vfqn.show}).")
      usedNames              <- getFactOrAbort(UsedNames.Key(key.vfqn))
      usedValues              = usedNames.usedNames.filter((vfqn, _) => vfqn.moduleName === key.moduleName)
      mainClassGenerator     <- createClassGenerator[CompilerIO](key.moduleName)
      // Group ALL value constructors by data type, from the shared module constructor index. The JVM keys a data type
      // by its `Qualifier.Default` FQN (its class-naming identity), and each group entry pairs a constructor with that
      // data-type FQN — the shape the constructor-class generation below consumes.
      moduleConstructors     <- getFactOrAbort(ModuleConstructors.Key(key.moduleName))
      allCtorGroups           = moduleConstructors.byDataType.map { (dataType, ctors) =>
                                  val dataTypeVfqn = ValueFQN(key.moduleName, QualifiedName(dataType.name, Qualifier.Default))
                                  dataTypeVfqn -> ctors.map(ctor => (ctor, dataTypeVfqn))
                                }
      // Collect used constructors
      usedCtorVfqns           = usedValues.filter((vfqn, _) => isConstructor(vfqn)).keySet
      // Find PatternMatch handleCases impls and map to their data types
      handleCasesByDataType  <- usedValues.toSeq.traverseFilter { (vfqn, stats) =>
                                  if (WellKnownTypes.isPatternMatchHandleCases(vfqn)) {
                                    ImplementationMarkerUtils
                                      .firstPatternTypeConstructorName(vfqn, WellKnownTypes.patternMatchAbilityName)
                                      .map {
                                        case Some(typeName) =>
                                          val dataTypeVfqn = allCtorGroups.keys
                                            .find(_.name.name === typeName)
                                            .getOrElse(ValueFQN(key.moduleName, QualifiedName(typeName, Qualifier.Default)))
                                          Some((dataTypeVfqn, (vfqn, stats)))
                                        case None           => Option.empty
                                      }
                                  } else {
                                    Option.empty.pure[CompilerIO]
                                  }
                                }
      handleCasesMap          = handleCasesByDataType.toMap
      _                      <- debug[CompilerIO](s"Module ${key.moduleName.show}: handleCasesMap=${handleCasesMap.keys.map(_.show).mkString(", ")}")
      // Collect ALL PatternMatch impl VFQNs to exclude from normal processing
      allPatternMatchVfqns    = usedValues.keys.filter(WellKnownTypes.isPatternMatchImplementation).toSet
      // Collect ALL TypeMatch impl VFQNs to exclude from normal processing
      allTypeMatchVfqns       = usedValues.keys.filter(WellKnownTypes.isTypeMatchImplementation).toSet
      // Map each typeMatch method VFQN to its corresponding type constructor name (via the marker signature)
      typeMatchByConstructor <- allTypeMatchVfqns
                                  .filter(WellKnownTypes.isTypeMatchTypeMatch)
                                  .toSeq
                                  .traverseFilter { tmVfqn =>
                                    ImplementationMarkerUtils
                                      .firstPatternTypeConstructorName(tmVfqn, WellKnownTypes.typeMatchAbilityName)
                                      .map(_.map(_ -> tmVfqn))
                                  }
                                  .map(_.toMap)
      // Process each data type: check handleCases usage, merge constructors, generate classes. In name order: the
      // groups come off a `Map`, whose iteration order is the key's hash, and a hash is not a stable identity — a
      // field added to a name's case class reorders every member emitted into the module class, which is exactly what
      // the byte-identity oracle must not report (see `functionFiles` below).
      dataResults            <- allCtorGroups.toSeq.sortBy(_._1.show).traverse { (typeVFQ, allTypeCtors) =>
                                  val handleCasesUsed = handleCasesMap.contains(typeVFQ)
                                  val usedFromType    = allTypeCtors.filter((vfqn, _) => usedCtorVfqns.contains(vfqn))
                                  if (!handleCasesUsed && usedFromType.isEmpty) {
                                    (Seq.empty[ClassFile], Seq.empty[ValueFQN]).pure[CompilerIO]
                                  } else {
                                    // When handleCases is used, include ALL constructors for virtual dispatch
                                    val ctorVfqns       = if (handleCasesUsed) allTypeCtors.map(_._1) else usedFromType.map(_._1)
                                    val defaultTypeArgs  = usedFromType
                                      .flatMap((v, _) => usedValues.get(v))
                                      .flatMap(_.monomorphicTypeParameters.headOption)
                                      .headOption
                                      .getOrElse(Seq.empty)
                                    for {
                                      // Fetch UncurriedMonomorphicValue for each constructor. The constructor is emitted
                                      // once (one class + factory per data type), so bare type-parameter fields are erased
                                      // to the opaque `Any` carrier — matching every monomorphic call site, which erases
                                      // the same way (DataClassGenerator.erasePolymorphicFields). A field whose carrier
                                      // still varies across instantiations after erasure is not representable by the single
                                      // shared descriptor; verify they agree and error rather than silently miscompile.
                                      ctorsWithInfo   <- ctorVfqns.traverse { vfqn =>
                                                           val stats        = usedValues.getOrElse(vfqn, UsageStats(Seq(defaultTypeArgs), Map(0 -> 1)))
                                                           val typeArgsList = stats.monomorphicTypeParameters.distinct match {
                                                             case Seq() => Seq(defaultTypeArgs)
                                                             case xs    => xs
                                                           }
                                                           for {
                                                             resolved  <- getFactOrAbort(OperatorResolvedValue.Key(vfqn))
                                                             // Always the full field count: a constructor used only
                                                             // under-applied (`map(Box)`) must still be emitted whole.
                                                             arity      = SignatureView.of(resolved.signature).parameters.length
                                                             instances <- typeArgsList.traverse(ta =>
                                                                            getFactOrAbort(UncurriedMonomorphicValue.Key(vfqn, ta, arity))
                                                                          )
                                                             erasedAll  = instances.map(umv =>
                                                                            DataClassGenerator.erasePolymorphicFields(resolved, umv.parameters)
                                                                          )
                                                             _         <- compilerAbort[Unit](
                                                                            resolved.name.as(
                                                                              "Constructor has a field whose machine representation differs across instantiations and cannot be erased to a single representation."
                                                                            )
                                                                          ).whenA(erasedAll.map(_.map(p => valueType(p.parameterType))).distinct.size > 1)
                                                           } yield (vfqn, stats, instances.head.copy(parameters = erasedAll.head))
                                                         }
                                      classes          <- if (ctorsWithInfo.size === 1) {
                                                            val (vfqn, _, umv) = ctorsWithInfo.head
                                                            DataClassGenerator.createSingleConstructorData[CompilerIO](
                                                              mainClassGenerator, vfqn, typeVFQ, umv.parameters, handleCasesUsed
                                                            )
                                                          } else {
                                                            // The index is the constructor's handler position, which
                                                            // `DataMatchDesugarer` takes from the same declaration
                                                            // order: `ModuleConstructors`, never re-sorted here.
                                                            DataClassGenerator.createMultiConstructorData[CompilerIO](
                                                              mainClassGenerator, typeVFQ,
                                                              ctorsWithInfo.zipWithIndex.map { case ((v, _, umv), idx) => (v, umv.parameters, idx) },
                                                              handleCasesUsed
                                                            )
                                                          }
                                      singletonClasses <- if (handleCasesUsed) {
                                                            generatePatternMatchSingleton(
                                                              mainClassGenerator, typeVFQ,
                                                              ctorsWithInfo.size
                                                            ).map(Seq(_))
                                                          } else {
                                                            Seq.empty[ClassFile].pure[CompilerIO]
                                                          }
                                      generatedFunctions = ctorsWithInfo.map(_._1)
                                    } yield (classes ++ singletonClasses, generatedFunctions)
                                  }
                                }
      partialCtorClasses     <- usedValues.toSeq
                                  .filter((vfqn, _) => usedCtorVfqns.contains(vfqn))
                                  .sortBy(_._1.show)
                                  .flatTraverse((vfqn, stats) => NativePartialApplication.generate(mainClassGenerator, vfqn, stats))
      dataClasses             = dataResults.flatMap(_._1) ++ partialCtorClasses
      dataGeneratedFunctions  = dataResults.flatMap(_._2)
      // Get all type constructors at arity 0 (for identification and arity computation), from the shared index
      allTypeCtorsArityZero  <- moduleConstructors.typeConstructors.traverseFilter { vfqn =>
                                  val stats    = usedValues.get(vfqn)
                                  val typeArgs = stats.flatMap(_.monomorphicTypeParameters.headOption).getOrElse(Seq.empty)
                                  getFactIfProduced(UncurriedMonomorphicValue.Key(vfqn, typeArgs, 0)).map(_.map(umv => (vfqn, umv)))
                                }
      // Determine which type constructors need data classes (used directly or via typeMatch)
      neededTypeCtors         = allTypeCtorsArityZero.filter { (vfqn, _) =>
                                  usedValues.contains(vfqn) || typeMatchByConstructor.contains(vfqn.name.name)
                                }
      // Get each type constructor at its proper arity
      usedTypeCtorsWithUmv   <- neededTypeCtors.traverse { (vfqn, umvZero) =>
                                  val stats    = usedValues.get(vfqn)
                                  val typeArgs = stats.flatMap(_.monomorphicTypeParameters.headOption).getOrElse(Seq.empty)
                                  val arity    = stats match {
                                    case Some(s) => s.highestArity.getOrElse(constructorArityValue(umvZero.returnType))
                                    case None    => constructorArityValue(umvZero.returnType)
                                  }
                                  getFactOrAbort(UncurriedMonomorphicValue.Key(vfqn, typeArgs, arity)).map((vfqn, _))
                                }
      // Generate type constructor data classes and factory methods
      typeCtorClasses        <- usedTypeCtorsWithUmv.flatTraverse { (vfqn, umv) =>
                                  DataClassGenerator.createTypeConstructorData[CompilerIO](mainClassGenerator, vfqn, umv.parameters)
                                }
      // Generate typeMatch methods for used typeMatch functions
      typeMatchGenerated     <- usedTypeCtorsWithUmv.traverseFilter { (vfqn, umv) =>
                                  typeMatchByConstructor.get(vfqn.name.name) match {
                                    case Some(tmVfqn) =>
                                      val tmStats    = usedValues(tmVfqn)
                                      val tmTypeArgs = tmStats.monomorphicTypeParameters.headOption.getOrElse(Seq.empty)
                                      for {
                                        tmUmv <- getFactOrAbort(
                                                   UncurriedMonomorphicValue.Key(tmVfqn, tmTypeArgs, tmStats.highestArity.getOrElse(0))
                                                 )
                                        _     <- DataClassGenerator.generateTypeMatch[CompilerIO](
                                                   mainClassGenerator, tmUmv.parameters, tmUmv.returnType, vfqn, umv.parameters
                                                 )
                                      } yield Some(tmVfqn)
                                    case None         => Option.empty[ValueFQN].pure[CompilerIO]
                                  }
                                }
      // Generate Type marker interface if any type constructors are used
      typeInterfaceClass     <- if (usedTypeCtorsWithUmv.nonEmpty) {
                                  DataClassGenerator.generateTypeInterface[CompilerIO]().map(Seq(_))
                                } else Seq.empty[ClassFile].pure[CompilerIO]
      typeCtorGeneratedFns    = usedTypeCtorsWithUmv.map(_._1) ++ typeMatchGenerated
      allGeneratedFunctions   = dataGeneratedFunctions ++ typeCtorGeneratedFns ++ allPatternMatchVfqns ++ allTypeMatchVfqns
      // The module class's methods are emitted in **name order**. `usedValues` is a `Map`, and a `Map`'s iteration order
      // is its keys' hash order — a `ValueFQN`'s hash changes whenever a field is added to any case class it contains
      // (a qualifier component, say), which silently reordered every method of every module class and renumbered
      // their constant pools, making the example jars differ from their baseline with no semantic change at all. A
      // jar's members must depend on the program, not on a hash, for byte-identity to mean anything.
      _                      <- usedValues.toSeq.sortBy(_._1.show).traverse_(verifyIntrinsicSaturation)
      functionFiles          <-
        usedValues.view
          // Intrinsics (`+`/`-`/`*`, `show`, `nativeWiden`) are emitted inline at the call site by
          // ExpressionCodeGenerator, so they have no generated method — skip them rather than hitting the body-less
          // "Function not implemented." abort. (Integer literals are not here: `integerLiteral` is rewritten to a plain
          // literal node in `PostDrainQuoter`, so it never reaches codegen as a value at all.)
          .filterKeys(k => !allGeneratedFunctions.contains(k) && !Intrinsics.isIntrinsic(k))
          .toSeq
          .sortBy(_._1.show)
          .flatTraverse { case (vfqn, stats) =>
            createModuleMethod(mainClassGenerator, vfqn, stats)
          }
      mainClass              <- mainClassGenerator.generate[CompilerIO]()
      _                      <- registerFactIfClear(
                                  GeneratedModule(
                                    key.moduleName,
                                    key.vfqn,
                                    functionFiles ++ dataClasses ++ typeCtorClasses ++ typeInterfaceClass ++ Seq(mainClass)
                                  )
                                )
    } yield ()

  /** An intrinsic is emitted **inline** at each call site, which only a *saturated* call can be: the emission indexes
    * its operands directly, and it has no static method for a closure chain to end at
    * ([[NativePartialApplication]]'s levels call one). So `digits.map(show)` — handing `show` on as a function — has
    * nowhere to link, and before this it took the compiler down with a `NoSuchElementException` off an empty argument
    * list, or (once that was guarded) linked to a method never emitted and died at runtime with `NoSuchMethodError`.
    *
    * It is reported here, at the definition, naming the fix: wrap it in a lambda (`digits.map(n -> show(n))`), which
    * makes the call saturated and costs nothing. The same shape for an ordinary native is *supported* — that is what
    * [[NativePartialApplication]] is — so this is a gap in the backend rather than a rule of the language; it is a hard
    * error rather than a silent miscompile until the inline emission can also be reached from a closure frame.
    */
  private def verifyIntrinsicSaturation(entry: (ValueFQN, UsageStats)): CompilerIO[Unit] = {
    val (vfqn, stats) = entry
    Intrinsics.arity(vfqn).flatMap(full => stats.directCallApplications.keys.filter(_ < full).toSeq.minOption.map(full -> _)) match {
      case None                => ().pure[CompilerIO]
      case Some((full, used)) =>
        getFactOrAbort(UnifiedModuleValue.Key(vfqn)).flatMap(umv =>
          compilerAbort[Unit](
            umv.namedValue.qualifiedName.as(
              s"'${vfqn.name.name}' is a backend intrinsic emitted inline, so it must be applied to all $full of its " +
                s"arguments; here it is used with $used. Wrap it in a lambda at the use site, as " +
                s"`x -> ${vfqn.name.name}(x)`."
            )
          )
        )
    }
  }

  /** Fail-safe enforcement of the I/O boundary: an impure leaf native (e.g. `printLineInternal`) must be declared
    * `private` so no application module can name it and perform untracked I/O. The compiler cannot detect a native's
    * impurity from its bytecode, so the registry is the source of truth and this asserts the resolved def's visibility
    * matches — a forgotten `private` is caught at build time, never a silent pure-typed-impure hole. Pure natives are
    * unconstrained and skip the fact lookup entirely.
    */
  private def verifyNativeVisibility(vfqn: ValueFQN, native: NativeImplementation): CompilerIO[Unit] =
    if (!native.impure) ().pure[CompilerIO]
    else
      getFactOrAbort(UnifiedModuleValue.Key(vfqn)).flatMap { umv =>
        NativeImplementation.visibilityViolation(vfqn, native.impure, umv.namedValue.visibility) match {
          case Some(message) => compilerAbort[Unit](umv.namedValue.qualifiedName.as(message))
          case None          => ().pure[CompilerIO]
        }
      }

  private def createModuleMethod(
      mainClassGenerator: ClassGenerator,
      vfqn: ValueFQN,
      stats: UsageStats
  ): CompilerIO[Seq[ClassFile]] =
    ControlNatives.perInstantiation.get(vfqn) match {
      case Some(make) => generatePerInstantiationNative(mainClassGenerator, vfqn, stats, make)
      case None       => createErasedModuleMethod(mainClassGenerator, vfqn, stats)
    }

  /** A native emitted **once per instantiation**, under the mangled name its call sites resolve to and with its ground
    * type arguments in hand — effects v6's control-flow leaves ([[ControlNatives]]), whose frame identity *is* the
    * instantiation. The shape mirrors [[generateAbilityImplNative]]; what differs is that the maker needs the type
    * arguments, not only the name.
    */
  private def generatePerInstantiationNative(
      mainClassGenerator: ClassGenerator,
      vfqn: ValueFQN,
      stats: UsageStats,
      make: (JvmIdentifier, Seq[GroundValue]) => NativeImplementation
  ): CompilerIO[Seq[ClassFile]] =
    stats.monomorphicTypeParameters.distinct
      .traverse_ { typeArgs =>
        ControlNatives.unsupportedInstantiation(vfqn, typeArgs) match {
          case Some(message) =>
            getFactOrAbort(UnifiedModuleValue.Key(vfqn)).flatMap(umv =>
              compilerAbort[Unit](umv.namedValue.qualifiedName.as(message))
            )
          case None          =>
            val native = make(JvmIdentifier.encode(mangledMethodName(vfqn, typeArgs)), typeArgs)
            verifyNativeVisibility(vfqn, native) >> native.generateMethod(mainClassGenerator)
        }
      }
      .as(Seq.empty)

  private def createErasedModuleMethod(
      mainClassGenerator: ClassGenerator,
      vfqn: ValueFQN,
      stats: UsageStats
  ): CompilerIO[Seq[ClassFile]] =
    implementations.get(vfqn) match {
      case Some(nativeImplementation) =>
        // The native itself is emitted at its declared arity; a native used *under*-applied also needs the
        // partial-arity variants an Eliot-bodied definition gets from `createModuleMethodBody` below. See
        // [[NativePartialApplication]] — without them the call links to a method that was never emitted.
        verifyNativeVisibility(vfqn, nativeImplementation) >>
          nativeImplementation.generateMethod(mainClassGenerator) >>
          NativePartialApplication.generate(mainClassGenerator, vfqn, stats)
      case None                       =>
        abilityImplNativeMaker(vfqn).flatMap {
          case Some(makeNative) => generateAbilityImplNative(mainClassGenerator, vfqn, stats, makeNative)
          case None             => createModuleMethodBody(mainClassGenerator, vfqn, stats)
        }
    }

  /** The maker for a native attached directly to `vfqn` when it is a recognised ability-implementation method (e.g.
    * `Eq[String]::equals`), else `None`. Recognised index-free through the impl marker
    * ([[ImplementationMarkerUtils.isImplementationMethodFor]]) on the runtime track, where the `stdlib` `implement
    * Eq[String]` is defined. The qualifier gate inside the marker check makes this a cheap no-op for every non-impl name.
    */
  private def abilityImplNativeMaker(vfqn: ValueFQN): CompilerIO[Option[JvmIdentifier => NativeImplementation]] =
    NativeImplementation.abilityImplementations.toList
      .traverse { case (ability, method, dispatchType, make) =>
        ImplementationMarkerUtils.isImplementationMethodFor(vfqn, ability, method, dispatchType).map(Option.when(_)(make))
      }
      .map(_.flatten.headOption)

  /** Emit an ability-impl native under each distinct instantiation's *mangled* method name — the name call sites resolve
    * to (`equals$Eq$impl$…`), not the native's own local name. The bytecode is identical across instantiations, and the
    * impl method has no generic params, so this is normally the single empty-type-argument name; deduped by name so two
    * ranges lowering to one name generate the method once.
    *
    * It needs [[NativePartialApplication]] for exactly the reason a module-level native does: the method is emitted at
    * the declared arity alone, while a call site emits at the spine's arity, so `paths.map(show)` linked to a
    * `show$Show$impl$…()` that was never emitted and died at runtime with `NoSuchMethodError`. The levels are named off
    * the same mangled name, so the two branches share one generator.
    */
  private def generateAbilityImplNative(
      mainClassGenerator: ClassGenerator,
      vfqn: ValueFQN,
      stats: UsageStats,
      makeNative: JvmIdentifier => NativeImplementation
  ): CompilerIO[Seq[ClassFile]] = {
    val typeArgsList = stats.monomorphicTypeParameters.distinct match {
      case Seq() => Seq(Seq.empty[GroundValue])
      case xs    => xs
    }
    typeArgsList
      .map(typeArgs => mangledMethodName(vfqn, typeArgs))
      .distinct
      .traverse_ { name =>
        val native = makeNative(JvmIdentifier.encode(name))
        verifyNativeVisibility(vfqn, native) >> native.generateMethod(mainClassGenerator)
      } >> NativePartialApplication.generate(mainClassGenerator, vfqn, stats)
  }

  private def createModuleMethodBody(
      mainClassGenerator: ClassGenerator,
      vfqn: ValueFQN,
      stats: UsageStats
  ): CompilerIO[Seq[ClassFile]] = {
        val distinctTypeArgs = stats.monomorphicTypeParameters.distinct
        val arities          = stats.directCallApplications.keys.toSeq.sorted
        for {
          // Distinct ranges that lower to the same representation (e.g. `Int[0,3]` and `Int[0,5]` -> `Byte`) yield the
          // SAME method name + descriptor with byte-identical bodies; generate each such signature only once. Different
          // representations give different descriptors and so legitimately overload the same mangled name.
          result <- distinctTypeArgs.foldLeftM((Seq.empty[ClassFile], 0, Set.empty[String])) {
                      case ((accFiles, lambdaCount, seen), typeArgs) =>
                        instanceArities(vfqn, typeArgs, arities).flatMap(_.foldLeftM((accFiles, lambdaCount, seen)) {
                          case ((innerAccFiles, innerLambdaCount, innerSeen), arity) =>
                            for {
                              uncurriedValue <- getFactOrAbort(UncurriedMonomorphicValue.Key(vfqn, typeArgs, arity))
                              key             = methodSignatureKey(uncurriedValue, typeArgs)
                              next           <-
                                if (innerSeen.contains(key))
                                  (innerAccFiles, innerLambdaCount, innerSeen).pure[CompilerIO]
                                else
                                  createModuleMethod(mainClassGenerator, uncurriedValue, typeArgs, innerLambdaCount)
                                    .map { case (files, newLambdaCount) =>
                                      (innerAccFiles ++ files, newLambdaCount, innerSeen + key)
                                    }
                            } yield next
                        })
                    }
          (classFiles, _, _) = result
          // Check if this is the main function. The widest recorded arity is capped at the probed instance's own natural
          // arity, since another instance may be the one that takes it (a frame parameter, `instanceArities`).
          highestTypeArgs    = distinctTypeArgs.headOption.getOrElse(Seq.empty)
          highestArity      <- instanceArities(vfqn, highestTypeArgs, stats.highestArity.toSeq)
          highestUncurried  <- getFactOrAbort(
                                 UncurriedMonomorphicValue.Key(vfqn, highestTypeArgs, highestArity.headOption.getOrElse(0))
                               )
          _                 <- createApplicationMain(vfqn, mainClassGenerator).whenA(isMain(highestUncurried))
        } yield classFiles
  }

  /** The arities one instance is emitted at: the value's recorded direct-call arities, each capped at **this
    * instance's** natural arity — the same cap a call site applies (`ExpressionCodeGenerator`'s direct-call arity).
    * Instances of one value may differ in natural arity: a specialisation whose binding carries an environment takes the
    * frame as a leading parameter (`docs/effects.md` §3.4), so a cap shared across instances would emit the wider one
    * below the arity its call sites use. A body-less value has no natural arity and keeps every recorded arity.
    */
  private def instanceArities(vfqn: ValueFQN, typeArgs: Seq[GroundValue], arities: Seq[Int]): CompilerIO[Seq[Int]] =
    getFactIfProduced(WovenValue.Key(vfqn, typeArgs)).map(woven =>
      woven.flatMap(_.naturalArity).fold(arities)(cap => arities.map(_ min cap).distinct)
    )

  /** A stable key for a generated method's emitted signature: the mangled name plus the lowered parameter and return
    * representations (i.e. its JVM descriptor). Two monomorphic instantiations sharing this key are byte-identical and
    * must be generated once; instantiations with the same name but a different key are valid overloads.
    */
  private def methodSignatureKey(uncurriedValue: UncurriedMonomorphicValue, typeArgs: Seq[GroundValue]): String = {
    val name       = mangledMethodName(uncurriedValue.vfqn, typeArgs)
    val paramTypes = uncurriedValue.parameters.map(p => valueType(p.parameterType).show).mkString(",")
    s"$name($paramTypes):${valueType(uncurriedValue.returnType).show}"
  }

  private def createModuleMethod(
      classGenerator: ClassGenerator,
      uncurriedValue: UncurriedMonomorphicValue,
      typeArgs: Seq[GroundValue],
      initialLambdaCount: Int = 0
  ): CompilerIO[(Seq[ClassFile], Int)] =
    // The body is generated from the *reconciled* form (each node stamped with its refinement-channel meta,
    // `docs/generic-refinement-merges.md`); the value-level structure (descriptors from `parameters`/`returnType`, the
    // method name) stays from the uncurried value. The backend selects each `Int`'s width from the stamped metas and
    // derives every re-encode (argument boundary, `fold` arm, method return) itself. Every uncurried value has a
    // reconciled counterpart (the reconcile pass is a transformation over `UncurriedMonomorphicValue`), so producible.
    getFactOrAbort(
      ReconciledMonomorphicValue.Key(uncurriedValue.vfqn, typeArgs, uncurriedValue.arity)
    ).flatMap { reconciledValue =>
      reconciledValue.body match {
        case Some(body) =>
          val methodName = mangledMethodName(uncurriedValue.vfqn, typeArgs)
          classGenerator
            .createMethod[CompilerIO](
              JvmIdentifier.encode(methodName),
              uncurriedValue.parameters.map(p => valueType(p.parameterType)),
              valueType(uncurriedValue.returnType)
            )
            .use { methodGenerator =>
              val bodyExpression = body.value
              val program        = for {
              _       <- uncurriedValue.parameters.traverse_(addParameterDefinition)
              // The method's return descriptor is the ⊤/bignum boundary, so the body is emitted at that width rather
              // than at whatever the channel narrowed it to — derived from the ranges, replacing the reconcile pass's
              // explicit return re-encode node.
              classes <-
                createExpressionCodeAtBoundaryWidth(
                  uncurriedValue.vfqn.moduleName,
                  classGenerator,
                  methodGenerator,
                  bodyExpression
                )
              _       <-
                debug[CompilationTypesIO](
                  s"From function ${uncurriedValue.vfqn.show}, created: ${classes.map(_.fileName).mkString(", ")}"
                )
              state   <- StateT.get[CompilerIO, TypeState]
            } yield (classes, state.lambdaCount)

            // The closure-class prefix must be the *mangled* method name, not the bare local name: two
            // implementations of one ability share the local name (`wrap`) but get distinct mangled names
            // (`wrap$Wrap$impl$0…`/`wrap$Wrap$impl$1…`). Using the bare name here collided their lambda classes
            // (`Test$wrap$lambda$1` twice → a duplicate-entry ZipException at JAR time) whenever both were
            // co-located in one module.
            program
              .run(TypeState(methodName = methodName, lambdaCount = initialLambdaCount))
              .map(_._2)
          }
      case None       =>
        compilerAbort(uncurriedValue.name.as(s"Function not implemented."))
    }
  }

  /** The synthesized entry point: stash the program arguments where `eliot.system.Environment` can read them, call the
    * woven `main`, and report the exit code the program registered.
    *
    * The arguments arrive as the parameter of a `static main(String[])`, which is a stack slot no leaf native in another
    * class can reach, so they are copied into a public static field of this very class and
    * `Environment.argumentsInternal` reads that field (see [[SystemNatives]]). This class — module `main` — is the one
    * class an executable jar always has, which is why the stash lives here and not on `Environment`'s own class: a
    * program that never mentions `Environment` never generates that class at all.
    *
    * `Arrays.asList` wraps the array without copying it; nothing mutates it afterwards, and Eliot's `List` is immutable
    * by contract (`append` builds a fresh list), so the wrapper is safe to hand out as an ordinary `List`.
    *
    * The exit code travels the same way in reverse: `eliot.system.Process.registerExitCode` writes
    * [[SystemNatives.exitCodeField]] on this class, and the tail below reads it once `main` has returned and hands it
    * to `System.exit`. Reading it *after* the call is the whole of "the code is registered, not acted on" — every
    * registration the program made has already happened, the last one won, and nothing the program was in the middle
    * of was cut short. A program that dies instead of returning never reaches the tail at all, and the JVM reports
    * the uncaught failure itself.
    *
    * **Zero returns rather than exits.** The field defaults to `0`, so the guard makes a program that reports success
    * end exactly as it did before this existed. That is invisible from outside the process — returning from
    * `main(String[])` *is* exit `0` — but not from inside one: an in-process host that invokes this method
    * reflectively (the integration harness here, which is why `runJarBounded` exists for `{Inf}` programs) does not
    * survive a `System.exit`, and the overwhelmingly common program has nothing to report. A program that does
    * register a non-zero code still terminates such a host, which is what a real exit code means.
    */
  private def createApplicationMain(mainVfqn: ValueFQN, generator: ClassGenerator): CompilerIO[Unit] =
    for {
      _ <- generator.createPublicStaticField[CompilerIO](SystemNatives.argumentsField, SystemNatives.argumentsFieldDescriptor)
      _ <- generator.createPublicStaticField[CompilerIO](SystemNatives.exitCodeField, SystemNatives.exitCodeFieldDescriptor)
      _ <- generator.createMainMethod[CompilerIO]().use { methodGenerator =>
             methodGenerator.runNative[CompilerIO] { mv =>
               mv.visitVarInsn(Opcodes.ALOAD, 0)
               mv.visitMethodInsn(
                 Opcodes.INVOKESTATIC,
                 "java/util/Arrays",
                 "asList",
                 "([Ljava/lang/Object;)Ljava/util/List;",
                 false
               )
               mv.visitFieldInsn(
                 Opcodes.PUTSTATIC,
                 SystemNatives.argumentsHolderClass,
                 SystemNatives.argumentsField,
                 SystemNatives.argumentsFieldDescriptor
               )
             } >> methodGenerator.addCallTo(mainVfqn, Seq.empty, systemUnitValue) >>
             methodGenerator.runNative[CompilerIO] { mv =>
               val reportedZero = new Label()

               mv.visitInsn(Opcodes.POP) // the `Void` main answered with
               mv.visitFieldInsn(
                 Opcodes.GETSTATIC,
                 SystemNatives.argumentsHolderClass,
                 SystemNatives.exitCodeField,
                 SystemNatives.exitCodeFieldDescriptor
               )
               mv.visitJumpInsn(Opcodes.IFEQ, reportedZero)
               mv.visitFieldInsn(
                 Opcodes.GETSTATIC,
                 SystemNatives.argumentsHolderClass,
                 SystemNatives.exitCodeField,
                 SystemNatives.exitCodeFieldDescriptor
               )
               mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false)
               mv.visitLabel(reportedZero)
             }
           }
    } yield ()

  /** Whether this is the synthesized entry point the jar's `Main-Class` bootstraps. Only that one: a user `main` that
    * takes nothing — every `main` once its handles are erased — is an ordinary method the entry point calls.
    */
  private def isMain(uncurriedValue: UncurriedMonomorphicValue): Boolean =
    uncurriedValue.vfqn === SyntheticMainSourceProcessor.syntheticMainVfqn && uncurriedValue.parameters.isEmpty

  private def generatePatternMatchSingleton(
      mainClassGenerator: ClassGenerator,
      dataTypeVfqn: ValueFQN,
      constructorCount: Int
  ): CompilerIO[ClassFile] = {
    val isSingleConstructor = constructorCount === 1
    val handlers            = Seq.fill(constructorCount)(systemFunctionValue)
    val innerClassName = patternMatchSingletonName(dataTypeVfqn)
    val singletonVfqn = ValueFQN(mainClassGenerator.moduleName, QualifiedName(innerClassName, Qualifier.Default))
    val dataTypeName   = convertToNestedClassName(dataTypeVfqn)

    for {
      singletonCg <- mainClassGenerator.createInnerClassGenerator[CompilerIO](
                        JvmIdentifier.encode(innerClassName), Seq.empty
                      )
      _           <- singletonCg.createStaticFinalField[CompilerIO](JvmIdentifier("INSTANCE"), singletonVfqn)
      _           <- singletonCg.createCtor[CompilerIO](Seq.empty).use { ctor =>
                       ctor.addLoadThis[CompilerIO]() >> ctor.addCallToObjectCtor[CompilerIO]()
                     }
      _           <- singletonCg.createStaticInit[CompilerIO]().use { clinit =>
                       clinit.addNew[CompilerIO](singletonVfqn) >>
                         clinit.addCallToCtor[CompilerIO](singletonVfqn, Seq.empty) >>
                         clinit.addPutStaticField[CompilerIO](JvmIdentifier("INSTANCE"), singletonVfqn)
                     }
      _           <- singletonCg
                       .createPublicInstanceMethod[CompilerIO](
                         JvmIdentifier.encode("handleCases"),
                         systemAnyValue +: handlers,
                         systemAnyValue
                       )
                       .use { bridge =>
                         for {
                           _ <- bridge.addLoadVar[CompilerIO](systemAnyValue, 1)
                           _ <- bridge.addCastTo[CompilerIO](dataTypeVfqn)
                           _ <- handlers.indices.toList.traverse_(index =>
                                  bridge.addLoadVar[CompilerIO](systemFunctionValue, index + 2)
                                )
                           _ <-
                             if (isSingleConstructor) {
                               bridge.addCallToVirtualMethod[CompilerIO](
                                 dataTypeName,
                                 JvmIdentifier.encode("handleCases"),
                                 handlers,
                                 systemAnyValue
                               )
                             } else {
                               bridge.addCallToAbilityMethod[CompilerIO](
                                 dataTypeName,
                                 JvmIdentifier.encode("handleCases"),
                                 handlers,
                                 systemAnyValue
                               )
                             }
                         } yield ()
                       }
      classFile   <- singletonCg.generate[CompilerIO]()
    } yield classFile
  }

}
