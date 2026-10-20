package com.vanillasource.eliot.eliotc.jvm.classgen.processor

import cats.data.StateT
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.ability.util.ImplementationMarkerUtils
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.CommonPatterns.{mangledMethodName, valueType}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{IntRepresentation, NativeType}
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.NativeType.convertToNestedClassName
import com.vanillasource.eliot.eliotc.jvm.classgen.asm.{ClassGenerator, JvmIdentifier, MethodGenerator}
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.ClassFile
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.TypeState.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}
import com.vanillasource.eliot.eliotc.uncurry.fact.*
import com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression
import com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.*
import org.objectweb.asm.{Label, MethodVisitor, Opcodes}

object ExpressionCodeGenerator {

  /** Emit an expression, leaving its value on the stack at the width the channel stamped on the node itself. */
  def createExpressionCode(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      uncurriedExpression: ReconciledMonomorphicExpression
  ): CompilationTypesIO[Seq[ClassFile]] =
    createExpressionCodeAt(
      moduleName,
      outerClassGenerator,
      methodGenerator,
      uncurriedExpression,
      atBoundaryWidth = false
    )

  /** Emit an expression for a consumer that reads it at the **⊤/bignum boundary width** rather than at the node's
    * own — a call argument, an `apply` bridge argument, a method return. The value is guaranteed to be left at that
    * width.
    *
    * Every ordinary call / constructor / apply-bridge parameter and every method return is a bignum — a concrete
    * `Int` descriptor, or a generic slot erased to `Object` but read back at a concrete `Int` — so a narrow integer
    * must be a bignum on the heap or the reader's `CHECKCAST` fails. The backend derives this from the expression's
    * own rep; there is no explicit reconcile node. An intrinsic's *operand* never comes here — an intrinsic adapts to
    * whatever width its operand arrives at ([[createExpressionCodeUnconverted]]).
    *
    * This exists so a narrowing whose only consumer immediately widens is never emitted. A call boundary already hands
    * its result back at the boundary width, and [[convertResultFromBoundary]] narrows it to the node's stamped meta;
    * where the consumer is one of these, that narrowing and the widening behind it are a round trip computing the same
    * number (`Ranges`'s `count() + count() + count()` was three `BigInteger` ⤳ `Byte` ⤳ `BigInteger` trips), so
    * both halves are skipped. The one shape that still pays is an inline intrinsic, which genuinely emits at the
    * node's own width — it is widened here, exactly as before.
    */
  def createExpressionCodeAtBoundaryWidth(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      uncurriedExpression: ReconciledMonomorphicExpression
  ): CompilationTypesIO[Seq[ClassFile]] =
    createExpressionCodeAt(
      moduleName,
      outerClassGenerator,
      methodGenerator,
      uncurriedExpression,
      atBoundaryWidth = true
    )

  /** The common emission. `atBoundaryWidth` is the *consumer's* demand, not a property of the expression: each arm
    * either already leaves the boundary width on the stack (a call boundary, a parameter — whose meta is ⊤ — a
    * non-integer) or converts to it here.
    */
  private def createExpressionCodeAt(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      uncurriedExpression: ReconciledMonomorphicExpression,
      atBoundaryWidth: Boolean
  ): CompilationTypesIO[Seq[ClassFile]] =
    uncurriedExpression.expression match {
      case FunctionApplication(target, arguments)           =>
        generateFunctionApplication(
          moduleName,
          outerClassGenerator,
          methodGenerator,
          target.value,
          arguments.map(_.value),
          uncurriedExpression.expressionType,
          uncurriedExpression.meta,
          atBoundaryWidth
        )
      case IntegerLiteral(integerLiteral)                   =>
        // A constant is materialised at whatever width is asked for, so it is pushed at the consumer's width directly:
        // at the node's own pinned range in general, and at the ⊤/bignum boundary where that is what will be read.
        // Pushing it narrow only to widen it back was the oldest round trip in the channel (`ldc 21L; l2i; i2b;
        // Byte.valueOf; Byte.longValue; BigInteger.valueOf` for a literal argument), and it is a re-encode of a number
        // the compiler is writing itself.
        methodGenerator
          .runNative[CompilationTypesIO](
            pushIntegerConstant(
              integerLiteral.value,
              repInternalNameOf(
                uncurriedExpression.expressionType,
                if (atBoundaryWidth) None else uncurriedExpression.meta
              )
            )
          )
          .as(Seq.empty)
      case StringLiteral(stringLiteral)                     =>
        methodGenerator.addLdcInsn[CompilationTypesIO](stringLiteral.value).as(Seq.empty)
      case ParameterReference(sourcedParameterName)         =>
        for {
          index         <- getParameterIndex(sourcedParameterName.value)
          parameterType <- getParameterType(sourcedParameterName.value)
          _             <- compilerAbort(sourcedParameterName.as("Could not find in scope.")).liftToTypes
                             .whenA(index.isEmpty || parameterType.isEmpty)
          _             <- methodGenerator.addLoadVar[CompilationTypesIO](valueType(parameterType.get.parameterType), index.get)
        } yield Seq.empty
      case MonomorphicValueReference(sourcedVfqn, typeArgs) =>
        generateFunctionApplication(
          moduleName,
          outerClassGenerator,
          methodGenerator,
          uncurriedExpression,
          Seq.empty,
          uncurriedExpression.expressionType,
          uncurriedExpression.meta,
          atBoundaryWidth
        )
      case FunctionLiteral(parameters, body)                =>
        LambdaGenerator.generateLambda(
          moduleName,
          outerClassGenerator,
          methodGenerator,
          parameters,
          body,
          createExpressionCode
        )
    }

  private def generateFunctionApplication(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      typedTarget: ReconciledMonomorphicExpression,
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue,
      expectedResultMeta: Option[GroundValue],
      atBoundaryWidth: Boolean
  ): CompilationTypesIO[Seq[ClassFile]] =
    typedTarget.expression match {
      // A backend intrinsic is emitted inline *at this node's own stamped width*, so it has no boundary to re-encode.
      // It is the one such shape, and the only reason the two are told apart here rather than inside the emission.
      // Only a **saturated** one: an inline emission indexes its operands directly, so an under-applied intrinsic
      // (`digits.map(show)`, handing `show` on as a function) falls through to the generic call path, which builds the
      // partial application the ordinary way.
      case MonomorphicValueReference(sourcedCalledVfqn, typeArgs)
          if Intrinsics.isSaturatedIntrinsic(sourcedCalledVfqn.value, arguments.size) =>
        for {
          classes <- generateIntrinsic(
                       moduleName,
                       outerClassGenerator,
                       methodGenerator,
                       sourcedCalledVfqn,
                       typeArgs,
                       arguments,
                       expectedResultType,
                       expectedResultMeta
                     )
          // Emitted at the node's own width, so a boundary-width consumer widens it — the one shape that still pays a
          // conversion, since here the narrow value is what the intrinsic actually computes rather than a re-encode.
          _       <- convertNodeToBoundary(methodGenerator, expectedResultType, expectedResultMeta)
                       .whenA(atBoundaryWidth)
        } yield classes
      case _                                                                                                         =>
        // Every other application ends at a *call boundary*, which leaves the ⊤ width on the stack. Re-encoding it to
        // the node's stamped width is skipped outright when the consumer reads the boundary width anyway: the
        // conversion pair would compute the same number (see [[createExpressionCodeAtBoundaryWidth]]).
        for {
          classes <- generateBoundaryApplication(
                       moduleName,
                       outerClassGenerator,
                       methodGenerator,
                       typedTarget,
                       arguments,
                       expectedResultType
                     )
          _       <- convertResultFromBoundary(methodGenerator, expectedResultType, expectedResultMeta)
                       .unlessA(atBoundaryWidth)
        } yield classes
    }

  /** Emit an application whose result arrives at a call boundary — a direct call, a `Function.apply` bridge, a `match`
    * dispatch — as opposed to an inline intrinsic. The node's meta is deliberately absent from this signature: what a
    * boundary leaves on the stack is the ⊤ width whatever the channel pinned, and re-encoding it is the caller's job.
    */
  private def generateBoundaryApplication(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      typedTarget: ReconciledMonomorphicExpression,
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue
  ): CompilationTypesIO[Seq[ClassFile]] =
    typedTarget.expression match {
      case IntegerLiteral(integerLiteral)                         => ???
      case StringLiteral(stringLiteral)                           => ???
      case ParameterReference(parameterName)                      =>
        // Function application on a parameter reference, so this needs to be a Function
        for {
          parameterIndex <- getParameterIndex(parameterName.value)
          parameterType  <- getParameterType(parameterName.value)
          _              <- compilerAbort(parameterName.as("Could not find parameter in scope.")).liftToTypes
                              .whenA(parameterIndex.isEmpty || parameterType.isEmpty)
          _              <- methodGenerator
                              .addLoadVar[CompilationTypesIO](valueType(parameterType.get.parameterType), parameterIndex.get)
          classes        <- applyArgumentsToFunctionValue(
                              moduleName,
                              outerClassGenerator,
                              methodGenerator,
                              arguments,
                              expectedResultType
                            )
        } yield classes
      case MonomorphicValueReference(sourcedCalledVfqn, typeArgs) =>
        val calledVfqn = sourcedCalledVfqn.value
        if (WellKnownTypes.isPatternMatchHandleCases(calledVfqn))
          generatePatternMatchCall(
            moduleName,
            outerClassGenerator,
            methodGenerator,
            sourcedCalledVfqn,
            calledVfqn,
            typeArgs,
            arguments,
            expectedResultType
          )
        else if (WellKnownTypes.isTypeMatchTypeMatch(calledVfqn))
          generateTypeMatchCall(
            moduleName,
            outerClassGenerator,
            methodGenerator,
            sourcedCalledVfqn,
            calledVfqn,
            typeArgs,
            arguments,
            expectedResultType
          )
        else
          generateNormalFunctionCall(
            moduleName,
            outerClassGenerator,
            methodGenerator,
            sourcedCalledVfqn,
            calledVfqn,
            typeArgs,
            arguments,
            expectedResultType
          )
      case FunctionLiteral(parameters, body)                      =>
        // An immediately-applied lambda `(x -> body)(arg)` — a `let`, the shape a non-effectful block `val`/statement
        // lowers to. Generate the lambda as an ordinary closure value, then apply the argument(s) to it exactly as a
        // function-valued parameter is applied. (An effectful block binding is rewritten to `flatMap`/`map` earlier and
        // never reaches here.)
        for {
          lambdaClasses <- LambdaGenerator.generateLambda(
                             moduleName,
                             outerClassGenerator,
                             methodGenerator,
                             parameters,
                             body,
                             createExpressionCode
                           )
          argClasses    <- applyArgumentsToFunctionValue(
                             moduleName,
                             outerClassGenerator,
                             methodGenerator,
                             arguments,
                             expectedResultType
                           )
        } yield lambdaClasses ++ argClasses
      case FunctionApplication(_, _)                              =>
        // Applying the result of another application: the inner application leaves a function value on the stack
        // (its own final cast is to its Function-carrier expression type), the arguments are then applied to it.
        for {
          targetClasses <- createExpressionCode(moduleName, outerClassGenerator, methodGenerator, typedTarget)
          argClasses    <- applyArgumentsToFunctionValue(
                             moduleName,
                             outerClassGenerator,
                             methodGenerator,
                             arguments,
                             expectedResultType
                           )
        } yield targetClasses ++ argClasses
    }

  /** Emit an expression for a consumer that reads it at **whatever width it comes out at** — an intrinsic's operand,
    * a branch arm. Those consumers adapt to the value ([[unboxToLong]], [[pushAsBigInteger]] and
    * [[convertRepresentation]] all take any integer width), so the value need not adapt to them: a result coming from a
    * call boundary is left at the ⊤ width rather than re-encoded to the node's narrower one first, which would be a
    * round trip the consumer immediately undoes. [[unconvertedRepOf]] names the width this leaves.
    */
  private def createExpressionCodeUnconverted(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      uncurriedExpression: ReconciledMonomorphicExpression
  ): CompilationTypesIO[Seq[ClassFile]] =
    createExpressionCodeAt(
      moduleName,
      outerClassGenerator,
      methodGenerator,
      uncurriedExpression,
      atBoundaryWidth = isCallBoundary(uncurriedExpression)
    )

  /** The representation [[createExpressionCodeUnconverted]] leaves on the stack for `expression`. */
  private def unconvertedRepOf(expression: ReconciledMonomorphicExpression): String =
    repInternalNameOf(expression.expressionType, if (isCallBoundary(expression)) None else expression.meta)

  /** The representation the *channel* stamped on a node — what it knows about the value's range, as opposed to
    * [[unconvertedRepOf]]'s "how it happens to arrive". This is what a compute-domain decision reads: whether an
    * operand fits a primitive `long` is a property of its range, not of the box it came in.
    */
  private def nodeRepOf(expression: ReconciledMonomorphicExpression): String =
    repInternalNameOf(expression.expressionType, expression.meta)

  /** Does this expression's value arrive from a **call boundary** — a direct call, an `apply` bridge, a `match` or
    * `typeMatch` dispatch — rather than being materialised inline? Such a value is handed back at the ⊤/bignum
    * width whatever the channel stamped on the node (see [[convertResultFromBoundary]]). Everything else — a literal,
    * an inline intrinsic — is materialised at the node's own width. Mirrors the dispatch of
    * [[generateFunctionApplication]], which is the one place that decides which of the two an application is.
    */
  private def isCallBoundary(expression: ReconciledMonomorphicExpression): Boolean =
    expression.expression match {
      case FunctionApplication(target, _)  => !isIntrinsicApplication(target.value)
      case MonomorphicValueReference(_, _) => !isIntrinsicApplication(expression)
      case _                               => false
    }

  private def isIntrinsicApplication(target: ReconciledMonomorphicExpression): Boolean =
    target.expression match {
      case MonomorphicValueReference(vfqn, _) => Intrinsics.isIntrinsic(vfqn.value)
      case _                                  => false
    }

  /** Widen a value emitted at its node's stamped width to the ⊤/bignum boundary width — the inverse of
    * [[convertResultFromBoundary]]. A no-op for a non-integer value, and for a node already at the boundary width.
    */
  private def convertNodeToBoundary(
      methodGenerator: MethodGenerator,
      nodeType: GroundValue,
      nodeMeta: Option[GroundValue]
  ): CompilationTypesIO[Unit] = {
    val nodeRep     = repInternalNameOf(nodeType, nodeMeta)
    val boundaryRep = repInternalNameOf(nodeType, None)
    methodGenerator.runNative[CompilationTypesIO] { mv =>
      if (isIntegerRep(nodeRep) && isIntegerRep(boundaryRep)) convertRepresentation(nodeRep, boundaryRep)(mv)
    }
  }

  /** Re-encode a call's *result* edge: convert the value a call boundary leaves on the stack to the width the channel
    * stamped on the call node. The mirror of [[convertNodeToBoundary]], which is what an argument or a method
    * return — the callee's own side of the same edge — converts with.
    *
    * Every call boundary hands back an integer at the ⊤/bignum layout — a generated native's declared return
    * descriptor, an Eliot method's return (emitted at that width by [[createExpressionCodeAtBoundaryWidth]]), or the
    * `Function.apply` bridge's erased `Object` read at a concrete `Int`. When a *stated meta transfer* pins a narrower range on the call
    * node (`def length(s: String): Int {size(s)}` ⤳ `[5,5]` at `length("hello")`), the node's consumers — an argument
    * widening, a branch merge, a `CHECKCAST` — read that narrow width off the node, so without this conversion the
    * bignum on the stack meets a `java.lang.Byte` operand and the class verifier rejects the method
    * (`docs/meta.md` §7). A no-op for a non-integer result, and for the ⊤ node meta that every call
    * without a stated transfer carries.
    *
    * Narrowing is what the transfer *asserts*: the leaf that states the bound is the one answering for it, exactly as
    * an arithmetic leaf answers for the range it computes. An untruthful transfer truncates here rather than being
    * caught, which is the R2 contract (`docs/meta.md` §3.2).
    */
  private def convertResultFromBoundary(
      methodGenerator: MethodGenerator,
      expectedResultType: GroundValue,
      expectedResultMeta: Option[GroundValue]
  ): CompilationTypesIO[Unit] = {
    val boundaryRep = repInternalNameOf(expectedResultType, None)
    val nodeRep     = repInternalNameOf(expectedResultType, expectedResultMeta)
    methodGenerator.runNative[CompilationTypesIO] { mv =>
      if (isIntegerRep(boundaryRep) && isIntegerRep(nodeRep)) convertRepresentation(boundaryRep, nodeRep)(mv)
    }
  }

  /** Apply arguments one at a time to the function *value* on top of the stack (a `java.util.function.Function`), then
    * cast the final result to the expected type. `apply` returns `Object`, so every intermediate result is cast back to
    * the function interface before absorbing the next argument.
    */
  private def applyArgumentsToFunctionValue(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue
  ): CompilationTypesIO[Seq[ClassFile]] =
    for {
      classes <- arguments.zipWithIndex.flatTraverse { (expression, idx) =>
                   for {
                     // The `apply` bridge takes `Object`, but the value is read back at a concrete `Int`, so a narrow
                     // integer argument is widened to bignum here.
                     cs <- createExpressionCodeAtBoundaryWidth(
                             moduleName,
                             outerClassGenerator,
                             methodGenerator,
                             expression
                           )
                     _  <- methodGenerator.addCallToApply[CompilationTypesIO]()
                     _  <- methodGenerator
                             .addCastTo[CompilationTypesIO](NativeType.systemFunctionValue)
                             .whenA(idx < arguments.size - 1)
                   } yield cs
                 }
      _       <- methodGenerator.addCastTo[CompilationTypesIO](valueType(expectedResultType))
    } yield classes

  private def generatePatternMatchCall(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      sourcedCalledVfqn: Sourced[ValueFQN],
      calledVfqn: ValueFQN,
      typeArgs: Seq[GroundValue],
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue
  ): CompilationTypesIO[Seq[ClassFile]] =
    for {
      typeName             <- ImplementationMarkerUtils
                                .firstPatternTypeConstructorName(calledVfqn, WellKnownTypes.patternMatchAbilityName)
                                .liftToTypes
      dataTypeVfqn          = typeName
                                .map(n => ValueFQN(calledVfqn.moduleName, QualifiedName(n, Qualifier.Default)))
                                .getOrElse(NativeType.systemAnyValue)
      singletonName         = patternMatchSingletonName(dataTypeVfqn)
      singletonVfqn         = ValueFQN(calledVfqn.moduleName, QualifiedName(singletonName, Qualifier.Default))
      singletonInternalName = convertToNestedClassName(singletonVfqn)
      _                    <- methodGenerator.addGetStaticInstance[CompilationTypesIO](
                                singletonInternalName,
                                "L" + singletonInternalName + ";"
                              )
      classes              <- arguments.flatTraverse(expression =>
                                createExpressionCodeAtBoundaryWidth(
                                  moduleName,
                                  outerClassGenerator,
                                  methodGenerator,
                                  expression
                                )
                              )
      // The scrutinee, then one handler per constructor.
      _                    <- methodGenerator.addCallToVirtualMethod[CompilationTypesIO](
                                singletonInternalName,
                                JvmIdentifier.encode("handleCases"),
                                NativeType.systemAnyValue +: Seq.fill(arguments.size - 1)(NativeType.systemFunctionValue),
                                NativeType.systemAnyValue
                              )
      _                    <- methodGenerator.addCastTo[CompilationTypesIO](valueType(expectedResultType))
    } yield classes

  def patternMatchSingletonName(dataTypeVfqn: ValueFQN): String =
    WellKnownTypes.patternMatchAbilityName + "$" + dataTypeVfqn.name.name + "$impl"

  /** Emit a backend [[Intrinsics]] call inline. After Phase 3, an `Int[MIN, MAX]` value is carried at the *narrowest*
    * JVM wrapper its range fits (`java.lang.{Byte,Short,Integer,Long}` / `BigInteger`), and the operand/result
    * representations are read from the (already lowered) expression types:
    *   - the `Show[Int]` `show` leaf unboxes its operand to `long` and calls `Long.toString(long)`;
    *   - `nativeWiden` converts its operand from the source to the target representation (unbox/rebox, via `BigInteger`
    *     when the target is `BigInteger`);
    *   - an arithmetic leaf (the `Numeric[Int]` methods `add`/`subtract`/`multiply`) computes in primitive `long`
    *     (`LADD`/`LSUB`/`LMUL`, then rebox at the result representation) when operands and result fit `Long`, or in
    *     `java.math.BigInteger` (`add`/`subtract`/`multiply`) when anything overflows it — so a `Long`×`Long` product
    *     whose result range spills into `BigInteger` never truncates through a `long`.
    *
    * This is the JVM realisation of the width-agnostic arithmetic leaves; a microcontroller backend would instead pick
    * width-specific instructions from the same lowered representations.
    */
  private def generateIntrinsic(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      sourcedCalledVfqn: Sourced[ValueFQN],
      typeArgs: Seq[GroundValue],
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue,
      expectedResultMeta: Option[GroundValue]
  ): CompilationTypesIO[Seq[ClassFile]] = {
    val calledVfqn = sourcedCalledVfqn.value
    if (Intrinsics.showIntShow(calledVfqn)) {
      val operandRep = unconvertedRepOf(arguments.head)
      for {
        classes <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments.head)
        _       <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                     // A `BigInteger` operand renders at full precision via `BigInteger.toString`; any narrower wrapper
                     // is unboxed to `long` and rendered via `Long.toString`. Since the bounds-as-refinements flip
                     // (uniform bignum) most integers arrive as `BigInteger`, so this branch is the common path.
                     if (operandRep === bigIntegerInternalName)
                       mv.visitMethodInsn(
                         Opcodes.INVOKEVIRTUAL,
                         bigIntegerInternalName,
                         "toString",
                         "()Ljava/lang/String;",
                         false
                       )
                     else {
                       unboxToLong(operandRep)(mv)
                       mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "toString", "(J)Ljava/lang/String;", false)
                     }
                   }
      } yield classes
    } else if (Intrinsics.boolOps.contains(calledVfqn)) {
      generateBoolIntrinsic(
        moduleName,
        outerClassGenerator,
        methodGenerator,
        calledVfqn,
        arguments,
        expectedResultType,
        expectedResultMeta
      )
    } else if (Intrinsics.intComparison(calledVfqn)) {
      // The two comparison leaves (`Compare[Int]::lessThanOrEqual` and `Eq[Int]::equals`): compare in primitive `long`
      // (`LCMP`) when both operands fit it, else via `BigInteger.compareTo`; either way branch the comparison outcome
      // into a boxed `Boolean`. Both leaves reduce to the same three-way comparison and differ only in which outcomes
      // count as `true` — `<= 0` for the ordering, `== 0` for the equality — so they share one emission.
      val leftRep       = unconvertedRepOf(arguments(0))
      val rightRep      = unconvertedRepOf(arguments(1))
      val viaBigInteger =
        nodeRepOf(arguments(0)) === bigIntegerInternalName || nodeRepOf(arguments(1)) === bigIntegerInternalName
      val trueJump      = if (Intrinsics.eqIntEquality(calledVfqn)) Opcodes.IFEQ else Opcodes.IFLE
      val trueLabel     = new Label()
      val endLabel      = new Label()
      for {
        classes1 <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(0))
        _        <- methodGenerator.runNative[CompilationTypesIO](
                      if (viaBigInteger) pushAsBigInteger(leftRep) else unboxToLong(leftRep)
                    )
        classes2 <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(1))
        _        <- methodGenerator.runNative[CompilationTypesIO](
                      if (viaBigInteger) pushAsBigInteger(rightRep) else unboxToLong(rightRep)
                    )
        _        <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                      if (viaBigInteger)
                        mv.visitMethodInsn(
                          Opcodes.INVOKEVIRTUAL,
                          bigIntegerInternalName,
                          "compareTo",
                          "(Ljava/math/BigInteger;)I",
                          false
                        )
                      else mv.visitInsn(Opcodes.LCMP)
                      mv.visitJumpInsn(trueJump, trueLabel)
                      pushBoolConstant(false)(mv)
                      mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                      mv.visitLabel(trueLabel)
                      pushBoolConstant(true)(mv)
                      mv.visitLabel(endLabel)
                    }
      } yield classes1 ++ classes2
    } else {
      val resultRep     = repInternalNameOf(expectedResultType, expectedResultMeta)
      val leftRep       = unconvertedRepOf(arguments(0))
      val rightRep      = unconvertedRepOf(arguments(1))
      // `Long`-range operands and results compute in primitive `long`; anything that touches `BigInteger` (a
      // `BigInteger` operand, or a result that overflowed `Long` — e.g. a `Long`×`Long` product) computes in
      // `BigInteger` so no value is truncated through a `long` round-trip. Decided on the *node* reps, never on how an
      // operand arrives: a call boundary hands back a bignum whatever the range it carries, and unboxing that to `long`
      // is exact precisely when the range says it fits.
      val viaBigInteger = resultRep === bigIntegerInternalName ||
        nodeRepOf(arguments(0)) === bigIntegerInternalName || nodeRepOf(arguments(1)) === bigIntegerInternalName
      for {
        classes1 <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(0))
        _        <- methodGenerator.runNative[CompilationTypesIO](
                      if (viaBigInteger) pushAsBigInteger(leftRep) else unboxToLong(leftRep)
                    )
        classes2 <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(1))
        _        <- methodGenerator.runNative[CompilationTypesIO](
                      if (viaBigInteger) pushAsBigInteger(rightRep) else unboxToLong(rightRep)
                    )
        _        <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                      if (viaBigInteger) {
                        bigIntegerOp(calledVfqn)(mv)
                        // `bigIntegerOp` leaves a `BigInteger`; if the refinement channel narrowed the *result* below a
                        // bignum (reachable when an operand is a bignum but the result interval fits a narrower wrapper —
                        // e.g. `hugeLiteral * 0`), rebox it to the result representation. A no-op when the result is a
                        // bignum, which it always is until the channel's flow analysis narrows results (Step 6-iii).
                        convertRepresentation(bigIntegerInternalName, resultRep)(mv)
                      } else {
                        mv.visitInsn(longOpcode(calledVfqn))
                        boxFromLong(resultRep)(mv)
                      }
                    }
      } yield classes1 ++ classes2
    }
  }

  /** Emit a `Bool` primitive/operator inline over the `java.lang.Boolean` representation. Reached only for a genuinely
    * runtime `Bool` — a constant-operand expression is folded away by the compile-time native before codegen.
    *
    *   - `true`/`false` push `Boolean.TRUE`/`FALSE`;
    *   - `!a` unboxes and flips (`ICONST_1`/`IXOR`), reboxing;
    *   - `a && b` / `a || b` unbox both operands and `IAND`/`IOR`, reboxing (both operands are ordinary strict values,
    *     so there is nothing to short-circuit);
    *   - `fold(cond, whenTrue, whenFalse)` branches on `cond` and emits *only the taken arm's* code (an `IFEQ` skip),
    *     so the untaken branch is never evaluated — matching `fold`'s selecting semantics and avoiding the awkward
    *     three-deep stack a strict select would need.
    */
  private def generateBoolIntrinsic(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      calledVfqn: ValueFQN,
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue,
      expectedResultMeta: Option[GroundValue]
  ): CompilationTypesIO[Seq[ClassFile]] =
    if (calledVfqn === Intrinsics.boolTrueFQN || calledVfqn === Intrinsics.boolFalseFQN)
      methodGenerator
        .runNative[CompilationTypesIO](pushBoolConstant(calledVfqn === Intrinsics.boolTrueFQN))
        .as(Seq.empty)
    else if (calledVfqn === Intrinsics.boolNotFQN)
      for {
        classes <- createExpressionCode(moduleName, outerClassGenerator, methodGenerator, arguments(0))
        _       <- methodGenerator.runNative[CompilationTypesIO](notBool)
      } yield classes
    else if (calledVfqn === Intrinsics.boolAndFQN || calledVfqn === Intrinsics.boolOrFQN) {
      val opcode = if (calledVfqn === Intrinsics.boolAndFQN) Opcodes.IAND else Opcodes.IOR
      for {
        classes1 <- createExpressionCode(moduleName, outerClassGenerator, methodGenerator, arguments(0))
        _        <- methodGenerator.runNative[CompilationTypesIO](unboxBool)
        classes2 <- createExpressionCode(moduleName, outerClassGenerator, methodGenerator, arguments(1))
        _        <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                      unboxBool(mv)
                      mv.visitInsn(opcode)
                      boxBool(mv)
                    }
      } yield classes1 ++ classes2
    } else { // boolFoldFQN
      val elseLabel = new Label()
      val endLabel  = new Label()
      // `fold` selects one arm at runtime, so both branch frames must leave the *same* representation: the merged width
      // the channel pinned on the `fold` node (`expectedResultMeta`). An arm is a thunk, and a function value hands its
      // result back at the ⊤ boundary width whatever its body knew, so each integer arm is re-encoded from that width to
      // the merge's — a non-op for a non-integer arm. The channel provides no explicit arm edges; the backend owns the
      // merge because it already emits `fold` inline.
      val mergeRep  = repInternalNameOf(expectedResultType, expectedResultMeta)
      val armRep    = repInternalNameOf(expectedResultType, None)
      for {
        condClasses  <- createExpressionCode(moduleName, outerClassGenerator, methodGenerator, arguments(0))
        _            <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                          unboxBool(mv)
                          mv.visitJumpInsn(Opcodes.IFEQ, elseLabel)
                        }
        trueClasses  <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(1))
        _            <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                          runThunk(mv)
                          convertArm(armRep, mergeRep)(mv)
                          mv.visitJumpInsn(Opcodes.GOTO, endLabel)
                          mv.visitLabel(elseLabel)
                        }
        falseClasses <- createExpressionCodeUnconverted(moduleName, outerClassGenerator, methodGenerator, arguments(2))
        _            <- methodGenerator.runNative[CompilationTypesIO] { mv =>
                          runThunk(mv)
                          convertArm(armRep, mergeRep)(mv)
                          mv.visitLabel(endLabel)
                        }
        _            <- methodGenerator.addCastTo[CompilationTypesIO](castTargetFqn(expectedResultType, expectedResultMeta))
      } yield condClasses ++ trueClasses ++ falseClasses
    }

  /** Re-encode the result of a `fold` arm's thunk, an `Object` at `armRep`, to the merge's `mergeRep`. */
  private def convertArm(armRep: String, mergeRep: String)(mv: MethodVisitor): Unit =
    if (isIntegerRep(armRep) && isIntegerRep(mergeRep) && armRep =!= mergeRep) {
      mv.visitTypeInsn(Opcodes.CHECKCAST, armRep)
      convertRepresentation(armRep, mergeRep)(mv)
    }

  /** Run the arm a `fold` selected. A code parameter listing no effects is a thunk (`whenTrue uses *: A` is
    * `Unit => A`, `docs/effects.md` §3.1), so the arm on the stack is the *computation*, not its value — selecting it
    * and leaving it there would hand `fold`'s caller a lambda.
    */
  private def runThunk(mv: MethodVisitor): Unit = {
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      "java/util/function/Function",
      "apply",
      "(Ljava/lang/Object;)Ljava/lang/Object;",
      true
    )
  }

  private def unboxBool(mv: MethodVisitor): Unit =
    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)

  private def boxBool(mv: MethodVisitor): Unit =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)

  private def notBool(mv: MethodVisitor): Unit = {
    unboxBool(mv)
    mv.visitInsn(Opcodes.ICONST_1)
    mv.visitInsn(Opcodes.IXOR)
    boxBool(mv)
  }

  private def pushBoolConstant(value: Boolean)(mv: MethodVisitor): Unit =
    mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", if (value) "TRUE" else "FALSE", "Ljava/lang/Boolean;")

  /** The primitive `long` opcode for an arithmetic leaf FQN — the `Numeric[Int]` method name (`add`/`subtract`/
    * `multiply`).
    */
  private def longOpcode(leafVfqn: ValueFQN): Int =
    leafVfqn.name.name match {
      case "add"      => Opcodes.LADD
      case "subtract" => Opcodes.LSUB
      case _          => Opcodes.LMUL // multiply
    }

  /** The `java.math.BigInteger` instance method for an arithmetic leaf FQN; applied to the two `BigInteger`s on the
    * stack, it leaves the (already-boxed) `BigInteger` result. The `Numeric[Int]` method names (`add`/`subtract`/
    * `multiply`) coincide with `BigInteger`'s.
    */
  private def bigIntegerOp(leafVfqn: ValueFQN)(mv: org.objectweb.asm.MethodVisitor): Unit = {
    val method = leafVfqn.name.name // "add" / "subtract" / "multiply" — same as BigInteger's
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      bigIntegerInternalName,
      method,
      "(Ljava/math/BigInteger;)Ljava/math/BigInteger;",
      false
    )
  }

  private val bigIntegerInternalName = "java/math/BigInteger"

  /** The machine-representation internal name of a *reconciled node* — the width the JVM backend lays the value out at.
    * For an integer-typed node (the tracked `Int`, or a lowered `Jvm*`) the width is decoded from the node's refinement
    * channel meta ([[IntRepresentation]]); for any other type it is the ordinary lowered representation. This is the
    * successor to reading a lowered `Jvm*` type off `expressionType`: the interval→width policy now lives in the
    * backend (`docs/generic-refinement-merges.md` Step 6), not in an Eliot `Represent` instance.
    */
  private def repInternalNameOf(exprType: GroundValue, meta: Option[GroundValue]): String =
    if (IntRepresentation.isIntegerType(exprType))
      NativeType.javaInternalName(IntRepresentation.representationFor(meta))
    else NativeType.javaInternalName(valueType(exprType))

  /** As [[repInternalNameOf]] but the representation *type* FQN — for a `CHECKCAST` of an integer result to the width
    * its channel meta decodes to (a bare `Int` type would otherwise cast to the ⊤/bignum descriptor and fail on a
    * narrow value).
    */
  private def castTargetFqn(exprType: GroundValue, meta: Option[GroundValue]): ValueFQN =
    if (IntRepresentation.isIntegerType(exprType)) IntRepresentation.representationFor(meta)
    else valueType(exprType)

  /** Convert the boxed value on the stack from its source representation to its target representation, preserving the
    * logical integer (the caller guarantees it fits the target). Routes through `BigInteger` when the target is
    * `BigInteger`, otherwise through primitive `long`.
    */
  private def convertRepresentation(sourceRep: String, targetRep: String)(mv: org.objectweb.asm.MethodVisitor): Unit =
    if (sourceRep === targetRep) () // same representation: the value already has the right form
    else if (targetRep === bigIntegerInternalName) pushAsBigInteger(sourceRep)(mv)
    else {
      unboxToLong(sourceRep)(mv)
      boxFromLong(targetRep)(mv)
    }

  /** The five machine representations an `Int` node can carry — the boxed integer wrappers, narrowest to widest. A
    * value whose lowered type is one of these is *reconcilable* across a boundary by [[convertRepresentation]] (it
    * preserves the logical integer); a value of any other type (a `String`, a `data` value, an erased `Object` field)
    * is not an integer and must never be routed through the numeric converter.
    */
  private val integerRepInternalNames: Set[String] =
    Set("java/lang/Byte", "java/lang/Short", "java/lang/Integer", "java/lang/Long", bigIntegerInternalName)

  private def isIntegerRep(internalName: String): Boolean = integerRepInternalNames.contains(internalName)

  /** Leave a `java.math.BigInteger` on the stack from a boxed value of the given representation: a `BigInteger` is
    * already in the right form; any narrower wrapper is unboxed to `long` and lifted via `BigInteger.valueOf`.
    */
  private def pushAsBigInteger(repInternalName: String)(mv: org.objectweb.asm.MethodVisitor): Unit =
    if (repInternalName =!= bigIntegerInternalName) {
      unboxToLong(repInternalName)(mv)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, bigIntegerInternalName, "valueOf", "(J)Ljava/math/BigInteger;", false)
    }

  /** Unbox the boxed integer wrapper of the given representation on the top of the stack into a primitive `long`. All
    * the wrapper classes extend `java.lang.Number`, so `longValue()` widens uniformly.
    */
  private def unboxToLong(repInternalName: String)(mv: org.objectweb.asm.MethodVisitor): Unit =
    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, repInternalName, "longValue", "()J", false)

  /** Push an integer constant boxed at the given machine representation. A `BigInteger` representation goes through
    * [[boxFromLong]]'s `BigInteger.valueOf` where the constant fits a `long` — which is every constant a program
    * actually writes — and is built at full precision via `new BigInteger(decimalString)` only where it does not,
    * so a materialised constant beyond `Long` range is never truncated. Every narrower wrapper goes through a `long`
    * constant and [[boxFromLong]].
    */
  private def pushIntegerConstant(value: BigInt, repInternalName: String)(mv: org.objectweb.asm.MethodVisitor): Unit =
    if (repInternalName === bigIntegerInternalName && !value.isValidLong) {
      mv.visitTypeInsn(Opcodes.NEW, bigIntegerInternalName)
      mv.visitInsn(Opcodes.DUP)
      mv.visitLdcInsn(value.toString)
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, bigIntegerInternalName, "<init>", "(Ljava/lang/String;)V", false)
    } else {
      mv.visitLdcInsn(java.lang.Long.valueOf(value.toLong))
      boxFromLong(repInternalName)(mv)
    }

  /** Box the primitive `long` on the top of the stack into the wrapper of the given representation, narrowing first
    * (`l2i` + `i2b`/`i2s` for `Byte`/`Short`) so the boxed value matches the declared descriptor.
    */
  private def boxFromLong(repInternalName: String)(mv: org.objectweb.asm.MethodVisitor): Unit =
    repInternalName match {
      case "java/lang/Byte"       =>
        mv.visitInsn(Opcodes.L2I)
        mv.visitInsn(Opcodes.I2B)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)
      case "java/lang/Short"      =>
        mv.visitInsn(Opcodes.L2I)
        mv.visitInsn(Opcodes.I2S)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)
      case "java/lang/Integer"    =>
        mv.visitInsn(Opcodes.L2I)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
      case "java/math/BigInteger" =>
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigInteger", "valueOf", "(J)Ljava/math/BigInteger;", false)
      case _                      => // java/lang/Long
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
    }

  private def generateNormalFunctionCall(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      sourcedCalledVfqn: Sourced[ValueFQN],
      calledVfqn: ValueFQN,
      typeArgs: Seq[GroundValue],
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue
  ): CompilationTypesIO[Seq[ClassFile]] =
    for {
      // An application spine can be longer than the callee's natural arity (`unwrap(w)("x")` on a 1-parameter
      // accessor): the direct call absorbs `naturalArity` arguments, and the excess is applied one at a time to the
      // function value it returns. Body-less natives have no natural arity and keep the full spine. Read off the woven
      // value (the effects-as-channel codegen source; off the flag it is the identity image of the `MonomorphicValue`).
      wovenMaybe               <- getFactIfProduced(WovenValue.Key(calledVfqn, typeArgs)).liftToTypes
      directCallArity           = wovenMaybe.flatMap(_.naturalArity).fold(arguments.length)(_ min arguments.length)
      (directArgs, overApplied) = arguments.splitAt(directCallArity)
      uncurriedMaybe           <- getFactIfProduced(
                                    UncurriedMonomorphicValue.Key(calledVfqn, typeArgs, directArgs.length)
                                  ).liftToTypes
      resultClasses            <- uncurriedMaybe match
                                    case Some(uncurriedValue) =>
                                      // A generic native (e.g. `eliot.collection.List::append`) is emitted once, erased. The
                                      // front-end monomorphizes generics per element type, so a call site would otherwise link to a
                                      // per-instantiation mangled method (`append$Int`) that is never emitted; instead every
                                      // instantiation resolves to that one method by its plain name + erased signature (the
                                      // erased `Object` return is downcast to the concrete type below, as for any generic return).
                                      val genericNativeSig = NativeImplementation.genericNativeSignatures.get(calledVfqn)
                                      val returnType       =
                                        genericNativeSig.map(_.returnType).getOrElse(valueType(uncurriedValue.returnType))
                                      val methodName       =
                                        if (
                                          genericNativeSig.isDefined ||
                                          DataClassGenerator.isConstructor(calledVfqn) ||
                                          DataClassGenerator.isTypeConstructor(calledVfqn)
                                        )
                                          calledVfqn.name.name
                                        else
                                          mangledMethodName(calledVfqn, typeArgs)
                                      for {
                                        // A value constructor is emitted once and shared by every instantiation, so its bare
                                        // type-parameter fields erase to `Object` here exactly as on the definition side — the call
                                        // descriptor must match the single shared factory (DataClassGenerator.erasePolymorphicFields).
                                        parameters    <-
                                          if (DataClassGenerator.isConstructor(calledVfqn))
                                            getFactOrAbort(OperatorResolvedValue.Key(calledVfqn)).liftToTypes
                                              .map(DataClassGenerator.erasePolymorphicFields(_, uncurriedValue.parameters))
                                          else uncurriedValue.parameters.pure[CompilationTypesIO]
                                        parameterTypes = genericNativeSig
                                                           .map(_.parameterTypes)
                                                           .getOrElse(parameters.map(p => valueType(p.parameterType)))
                                        // Each direct argument crosses a parameter boundary, which the refinement channel treats as
                                        // ⊤ — a bignum (`docs/bounds-as-refinements.md` §7 Q4, "⊤ at parameter/return boundaries").
                                        // So a channel-narrowed integer argument is widened back to a bignum before the call.
                                        // Widening to the ⊤/bignum boundary (rather than the callee's declared parameter descriptor)
                                        // is what a generic slot needs: a type-parameter-typed parameter erases to `Object`, but the
                                        // value is read at a concrete `Int` (bignum), so it must be a bignum on the heap — a narrow
                                        // box would fail the reader's `CHECKCAST`. Derived from the argument's own rep here (no
                                        // reconcile node).
                                        classes       <-
                                          directArgs.flatTraverse { expression =>
                                            createExpressionCodeAtBoundaryWidth(
                                              moduleName,
                                              outerClassGenerator,
                                              methodGenerator,
                                              expression
                                            )
                                          }
                                        _             <- methodGenerator.addCallTo[CompilationTypesIO](
                                                           calledVfqn,
                                                           parameterTypes,
                                                           returnType,
                                                           Some(methodName)
                                                         )
                                        overClasses   <-
                                          if (overApplied.isEmpty)
                                            methodGenerator
                                              .addCastTo[CompilationTypesIO](valueType(expectedResultType))
                                              .whenA(valueType(expectedResultType) =!= returnType)
                                              .as(Seq.empty[ClassFile])
                                          else
                                            methodGenerator
                                              .addCastTo[CompilationTypesIO](NativeType.systemFunctionValue)
                                              .whenA(returnType =!= NativeType.systemFunctionValue) >>
                                              applyArgumentsToFunctionValue(
                                                moduleName,
                                                outerClassGenerator,
                                                methodGenerator,
                                                overApplied,
                                                expectedResultType
                                              )
                                      } yield classes ++ overClasses
                                    case None                 =>
                                      compilerError(
                                        sourcedCalledVfqn.as("Could not find uncurried function."),
                                        Seq(
                                          s"Looking for function: ${calledVfqn.show} with type args (${typeArgs.size} args)"
                                        )
                                      ).liftToTypes.as(Seq.empty)
    } yield resultClasses

  private def generateTypeMatchCall(
      moduleName: ModuleName,
      outerClassGenerator: ClassGenerator,
      methodGenerator: MethodGenerator,
      sourcedCalledVfqn: Sourced[ValueFQN],
      calledVfqn: ValueFQN,
      typeArgs: Seq[GroundValue],
      arguments: Seq[ReconciledMonomorphicExpression],
      expectedResultType: GroundValue
  ): CompilationTypesIO[Seq[ClassFile]] =
    for {
      constructorName <- ImplementationMarkerUtils
                           .firstPatternTypeConstructorName(calledVfqn, WellKnownTypes.typeMatchAbilityName)
                           .liftToTypes
      _               <- compilerAbort(
                           sourcedCalledVfqn.as("Could not determine type constructor name for typeMatch.")
                         ).liftToTypes.whenA(constructorName.isEmpty)
      uncurriedMaybe  <- getFactIfProduced(
                           UncurriedMonomorphicValue.Key(calledVfqn, typeArgs, arguments.length)
                         ).liftToTypes
      classes         <- uncurriedMaybe match {
                           case Some(uncurriedValue) =>
                             val parameterTypes = uncurriedValue.parameters.map(p => valueType(p.parameterType))
                             val returnType     = valueType(uncurriedValue.returnType)
                             for {
                               // Each integer argument crosses a ⊤/bignum parameter boundary, so it is widened to bignum.
                               classes <- arguments.flatTraverse { expression =>
                                            createExpressionCodeAtBoundaryWidth(
                                              moduleName,
                                              outerClassGenerator,
                                              methodGenerator,
                                              expression
                                            )
                                          }
                               _       <- methodGenerator.addCallTo[CompilationTypesIO](
                                            calledVfqn,
                                            parameterTypes,
                                            returnType,
                                            Some("typeMatch$" + constructorName.get)
                                          )
                               _       <- methodGenerator
                                            .addCastTo[CompilationTypesIO](valueType(expectedResultType))
                                            .whenA(valueType(expectedResultType) =!= returnType)
                             } yield classes
                           case None                 =>
                             compilerError(
                               sourcedCalledVfqn.as("Could not find uncurried typeMatch function."),
                               Seq(s"Looking for function: ${calledVfqn.show}")
                             ).liftToTypes.as(Seq.empty)
                         }
    } yield classes
}
