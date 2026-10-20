package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import cats.effect.Sync
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.{
  ParameterReference,
  SignatureView,
  spine
}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{ClassGenerator, JvmIdentifier}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.CommonPatterns.{addMonomorphicDataFieldsAndCtor, valueType}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.ClassGenerator.createInterfaceGenerator
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType.{
  convertToNestedClassName,
  systemAnyValue,
  systemFunctionValue,
  systemTypeValue
}
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.compilerPackage
import com.vanillasource.eliot.eliotc.uncurry.fact.MonomorphicParameterDefinition

object DataClassGenerator {

  def isConstructor(valueFQN: ValueFQN): Boolean =
    valueFQN.name.qualifier === Qualifier.Default && valueFQN.name.name.charAt(0).isUpper

  def isTypeConstructor(valueFQN: ValueFQN): Boolean =
    valueFQN.name.qualifier === Qualifier.Type && valueFQN.name.name.charAt(0).isUpper

  /** The opaque `Any` carrier (`java.lang.Object`) a polymorphic data field erases to. */
  private val erasedFieldCarrier: GroundValue =
    GroundValue.Structure(WellKnownTypes.anyFQN, Seq.empty, GroundValue.Type)

  /** Replace the type of every field whose *declared* type is **headed by a type-parameter** with the opaque `Any`
    * carrier (`java.lang.Object`).
    *
    * A data constructor is emitted exactly once — one JVM class plus one factory method per data type, shared by every
    * monomorphic instantiation (constructors are intentionally not name-mangled, so there is a single class per data
    * type). A field whose declared type is *headed by a type-parameter binder* therefore has a JVM carrier that differs
    * per instantiation but is *always a reference type*, so the single shared descriptor must erase it to `Object` —
    * exactly as the JVM erases Java generics. Two shapes qualify:
    *   - a **bare** binder (`first: A`): `Pair[String, Box]` stores `String`/`Box`, `Pair[Box, String]` the reverse;
    *   - a binder **applied** to arguments (`data Wrap[F[_], A](value: F[A])`): `Wrap[List, Int]` stores a `List`,
    *     `Wrap[Option, Int]` an `Option`. A higher-kinded binder applied to a `Type` argument is always a reference
    *     type (never a primitive, which would need `BigInteger` arguments), so erasing it to `Object` is sound.
    *
    * Concrete-headed fields (`String`, `Int[..]`, `List[A]`, whose head is a named type whose carrier does not depend
    * on the type arguments) keep their precise type. Both the definition side and every call site read this same
    * generic signature, so the descriptors always agree.
    *
    * A field whose carrier still varies after this erasure (e.g. `Int[0, N]` — concrete-headed by `Int` but a
    * representation that depends on the bound `N`) is not representable by a single descriptor; [[JvmClassGenerator]]
    * verifies the instantiations agree and errors rather than miscompiling.
    */
  def erasePolymorphicFields(
      resolvedConstructor: OperatorResolvedValue,
      fields: Seq[MonomorphicParameterDefinition]
  ): Seq[MonomorphicParameterDefinition] = {
    val view        = SignatureView.of(resolvedConstructor.signature)
    val binderNames = view.binders.map(_.name.value).toSet
    val polymorphic = view.parameters.map(param =>
      spine(param.value)._1 match {
        case ParameterReference(name) => binderNames.contains(name.value)
        case _                        => false
      }
    )
    fields.zipWithIndex.map { case (field, index) =>
      if (polymorphic.lift(index).contains(true)) field.copy(parameterType = erasedFieldCarrier)
      else field
    }
  }

  /** Single-constructor data: generates a concrete class, factory method, and optional handleCases.
    *
    * The class is named after the *data type* ([[typeVfqn]]), not the constructor, so a single-constructor union whose
    * constructor name differs from the type (`data Unit2 = MkUnit`) is handled as a record (where the two names
    * coincide). The rest of the backend — the factory's declared return, the accessor/match parameter types, and the
    * `PatternMatch$<Type>$impl` singleton — all refer to the data type, so the class must too, or the factory and call
    * sites disagree (a runtime `NoSuchMethodError`). The factory method itself keeps the constructor's name.
    */
  def createSingleConstructorData[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      constructorVfqn: ValueFQN,
      typeVfqn: ValueFQN,
      parameters: Seq[MonomorphicParameterDefinition],
      generateHandleCases: Boolean
  ): F[Seq[ClassFile]] =
    for {
      cs <- createDataClass(
              outerClassGenerator,
              typeVfqn.name.name,
              parameters,
              Seq.empty,
              if (generateHandleCases) Some((0, 1)) else None
            )
      _  <- createFactoryMethod(outerClassGenerator, constructorVfqn, typeVfqn, parameters, typeVfqn)
    } yield cs

  /** Multi-constructor (union) data: generates an interface + implementation classes + factory methods. */
  def createMultiConstructorData[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      typeVFQ: ValueFQN,
      ctors: Seq[(ValueFQN, Seq[MonomorphicParameterDefinition], Int)],
      generateHandleCases: Boolean
  ): F[Seq[ClassFile]] =
    for {
      // Create the interface for the data type
      interfaceGen   <- outerClassGenerator.createInnerInterfaceGenerator[F](JvmIdentifier.encode(typeVFQ.name.name))
      // Add abstract handleCases method to the interface if used: one handler per constructor
      _              <- interfaceGen
                          .createAbstractMethod[F](
                            JvmIdentifier.encode("handleCases"),
                            Seq.fill(ctors.size)(systemFunctionValue),
                            systemAnyValue
                          )
                          .whenA(generateHandleCases)
      interfaceClass <- interfaceGen.generate[F]()
      interfaceName   = convertToNestedClassName(typeVFQ)
      // Sort constructors by source position index to match definition order (= handler order)
      sortedCtors     = ctors.sortBy(_._3)
      ctorIndexMap    = sortedCtors.zipWithIndex.map { case ((vfqn, _, _), idx) => vfqn -> idx }.toMap
      totalCtors      = ctors.size
      // Create implementation classes and factory methods for each constructor
      implClasses    <- ctors.flatTraverse { case (vfqn, params, _) =>
                          val ctorIndex = ctorIndexMap.getOrElse(vfqn, 0)
                          for {
                            cs <- createDataClass(
                                    outerClassGenerator,
                                    vfqn.name.name,
                                    params,
                                    Seq(interfaceName),
                                    if (generateHandleCases) Some((ctorIndex, totalCtors)) else None
                                  )
                            _  <- createFactoryMethod(outerClassGenerator, vfqn, vfqn, params, typeVFQ)
                          } yield cs
                        }
    } yield Seq(interfaceClass) ++ implClasses

  /** Emit the static factory method for a constructor.
    *
    * The three class identities are kept separate because they only coincide for records: [[constructorVfqn]] names the
    * static method (the constructor's source name, the call-site name), [[instantiatedClassVfqn]] is the concrete class
    * actually `new`-ed and `<init>`-ed, and [[returnType]] is the declared result. For a single-constructor union
    * (`data Unit2 = MkUnit`) the constructor name (`MkUnit`) differs from the data type, so the instantiated class and
    * the return are the *type* class (`Unit2`), not a `MkUnit` class; for a multi-constructor union the instantiated
    * class is the per-constructor impl class while the return is the data-type interface.
    */
  private def createFactoryMethod[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      constructorVfqn: ValueFQN,
      instantiatedClassVfqn: ValueFQN,
      parameters: Seq[MonomorphicParameterDefinition],
      returnType: ValueFQN
  ): F[Unit] =
    outerClassGenerator
      .createMethod[F](
        JvmIdentifier.encode(constructorVfqn.name.name),
        parameters.map(_.parameterType).map(valueType),
        returnType
      )
      .use { methodGenerator =>
        for {
          _ <- methodGenerator.addNew[F](instantiatedClassVfqn)
          _ <- parameters.zipWithIndex.traverse_ { (fieldDef, index) =>
                 methodGenerator.addLoadVar[F](valueType(fieldDef.parameterType), index)
               }
          _ <- methodGenerator.addCallToCtor[F](
                 instantiatedClassVfqn,
                 parameters.map(_.parameterType).map(valueType)
               )
        } yield ()
      }

  /** Create a data class with fields, constructor, and optionally the `handleCases` instance method a `match`
    * dispatches to: it takes one handler per constructor and applies its own constructor's handler to its fields.
    *
    * @param handleCasesInfo
    *   If present, (constructorIndex, totalConstructors) for generating handleCases.
    */
  private def createDataClass[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      innerClassName: String,
      fields: Seq[MonomorphicParameterDefinition],
      javaInterfaces: Seq[String] = Seq.empty,
      handleCasesInfo: Option[(Int, Int)] = None
  ): F[Seq[ClassFile]] =
    for {
      innerClassWriter <-
        outerClassGenerator.createInnerClassGenerator[F](JvmIdentifier.encode(innerClassName), javaInterfaces)
      _                <- innerClassWriter.addMonomorphicDataFieldsAndCtor[F](fields)
      _                <- handleCasesInfo.traverse_ { (ctorIndex, totalCtors) =>
                            val ctorVfqn =
                              ValueFQN(outerClassGenerator.moduleName, QualifiedName(innerClassName, Qualifier.Default))
                            innerClassWriter
                              .createPublicInstanceMethod[F](
                                JvmIdentifier.encode("handleCases"),
                                Seq.fill(totalCtors)(systemFunctionValue),
                                systemAnyValue
                              )
                              .use(mg => applyHandlerToFields(mg, ctorVfqn, ctorIndex, fields))
                          }
      classFile        <- innerClassWriter.generate[F]()
    } yield Seq(classFile)

  /** Apply this constructor's handler — parameter `ctorIndex + 1`, after `this` — to its fields, one `apply` each, or to
    * `null` for a field-less constructor, whose handler takes an ignored `Unit`.
    */
  private def applyHandlerToFields[F[_]: Sync](
      mg: com.vanillasource.eliot.eliotc.jvm.classgen.asm.MethodGenerator,
      ctorVfqn: ValueFQN,
      ctorIndex: Int,
      fields: Seq[MonomorphicParameterDefinition]
  ): F[Unit] =
    for {
      _ <- mg.addLoadVar[F](systemFunctionValue, ctorIndex + 1)
      _ <-
        if (fields.isEmpty) mg.addConstNull[F]() >> mg.addCallToApply[F]()
        else
          fields.zipWithIndex.traverse_ { (field, fieldIdx) =>
            for {
              _ <- mg.addLoadThis[F]()
              _ <- mg.addGetField[F](JvmIdentifier.encode(field.name.value), valueType(field.parameterType), ctorVfqn)
              _ <- mg.addCallToApply[F]()
              _ <- mg.addCastTo[F](systemFunctionValue).whenA(fieldIdx < fields.size - 1)
            } yield ()
          }
    } yield ()

  /** Create a type constructor data class that implements the external Type interface. */
  def createTypeConstructorData[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      constructorVfqn: ValueFQN,
      parameters: Seq[MonomorphicParameterDefinition]
  ): F[Seq[ClassFile]] = {
    val typeInterfaceName = convertToNestedClassName(systemTypeValue)
    for {
      cs <- createDataClass(
              outerClassGenerator,
              "type$" + constructorVfqn.name.name,
              parameters,
              Seq(typeInterfaceName),
              None
            )
      _  <- createFactoryMethod(outerClassGenerator, constructorVfqn, constructorVfqn, parameters, systemAnyValue)
    } yield cs
  }

  /** Generate a static typeMatch method that uses instanceof dispatch. */
  def generateTypeMatch[F[_]: Sync](
      outerClassGenerator: ClassGenerator,
      typeMatchParameters: Seq[MonomorphicParameterDefinition],
      typeMatchReturnType: GroundValue,
      constructorVfqn: ValueFQN,
      constructorFields: Seq[MonomorphicParameterDefinition]
  ): F[Unit] = {
    val allParamTypes     = typeMatchParameters.map(_.parameterType).map(valueType)
    val returnType        = valueType(typeMatchReturnType)
    val typeCtorClassName = convertToNestedClassName(constructorVfqn)
    outerClassGenerator
      .createMethod[F](
        JvmIdentifier.encode("typeMatch$" + constructorVfqn.name.name),
        allParamTypes,
        returnType
      )
      .use { mg =>
        val elseLabel = mg.createLabel()
        val endLabel  = mg.createLabel()
        for {
          _ <- mg.addLoadVar[F](allParamTypes.head, 0)
          _ <- mg.addInstanceOf[F](typeCtorClassName)
          _ <- mg.addIfEq[F](elseLabel)
          // Match branch: apply matchCase handler to extracted fields
          _ <- mg.addLoadVar[F](systemFunctionValue, 1)
          _ <-
            if (constructorFields.isEmpty) {
              for {
                _ <- mg.addConstNull[F]()
                _ <- mg.addCallToApply[F]()
              } yield ()
            } else {
              constructorFields.zipWithIndex.traverse_ { (fieldDef, fieldIndex) =>
                for {
                  _ <- mg.addLoadVar[F](allParamTypes.head, 0)
                  _ <- mg.addCastTo[F](constructorVfqn)
                  _ <- mg.addGetField[F](
                         JvmIdentifier.encode(fieldDef.name.value),
                         valueType(fieldDef.parameterType),
                         constructorVfqn
                       )
                  _ <- mg.addCallToApply[F]()
                  _ <- mg.addCastTo[F](systemFunctionValue).whenA(fieldIndex < constructorFields.size - 1)
                } yield ()
              }
            }
          _ <- mg.addGoto[F](endLabel)
          // Else branch: apply elseCase handler to null (Unit)
          _ <- mg.addLabel[F](elseLabel)
          _ <- mg.addLoadVar[F](systemFunctionValue, 2)
          _ <- mg.addConstNull[F]()
          _ <- mg.addCallToApply[F]()
          _ <- mg.addLabel[F](endLabel)
          _ <- mg.addCastTo[F](returnType).whenA(returnType =!= systemAnyValue)
        } yield ()
      }
  }

  /** Generate the Type marker interface class that all type constructor data classes implement. Its package must match
    * [[NativeType.systemTypeValue]] (the FQN type-constructor classes name in their `implements` clause) — both live in
    * `eliot.compiler` since `Type` moved out of the `eliot.lang` prelude.
    */
  def generateTypeInterface[F[_]: Sync](): F[ClassFile] =
    for {
      interfaceGen <- createInterfaceGenerator[F](ModuleName(compilerPackage, "Type$Type"))
      classFile    <- interfaceGen.generate[F]()
    } yield classFile
}
