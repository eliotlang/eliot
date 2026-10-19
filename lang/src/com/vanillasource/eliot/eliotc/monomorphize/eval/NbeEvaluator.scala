package com.vanillasource.eliot.eliotc.monomorphize.eval

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue

/** The single, pure, syntax-directed NbE traversal shared by both expression evaluators. Evaluating an expression to a
  * [[SemValue]] is identical for every node *except* a value reference's type arguments: the ORE evaluator
  * ([[Evaluator]]) re-evaluates ORE sub-terms, the checker-output evaluator ([[SemExpressionEvaluator]]) reads the
  * already-evaluated [[SemValue]]s straight out of the node. That one difference is isolated in [[decompose]]; the
  * evaluation *logic* (constant construction, neutral fallback, application, lambda binding) lives here once.
  *
  * @param lookupTopDef
  *   Look up a top-level definition by [[ValueFQN]]. A missing binding becomes a stuck [[SemValue.VNeutral]] rather than
  *   a silent mis-evaluation.
  */
abstract class NbeEvaluator[E](lookupTopDef: ValueFQN => Option[SemValue]) {
  import NbeEvaluator.Term

  /** Evaluate an expression of the concrete IR to a semantic value under the given environment. */
  final def eval(env: Env, expr: E): SemValue = decompose(env, expr) match {
    case Term.IntegerLiteral(value) =>
      VConst(GroundValue.Direct(value, Evaluator.bigIntGroundType))

    case Term.StringLiteral(value) =>
      VConst(GroundValue.Direct(value, Evaluator.stringGroundType))

    case Term.ParameterReference(name) =>
      env.lookupByName(name).getOrElse(VNeutral(NeutralHead.Param(env.level, name), Spine.SNil))

    case Term.ValueReference(valueName, typeArguments) =>
      // A value reference names a top-level definition. With no binding it is a *stuck definition* — a body-less native
      // or runtime function the compiler does not reduce (e.g. a jvm leaf op, or the `some`/`none`
      // constructors) — so it falls back to its own FQN-preserving `VTopDef`, NOT a `VNeutral` (which is a bound
      // variable and would drop the FQN, corrupting read-back/codegen).
      val base = lookupTopDef(valueName).getOrElse(VTopDef(valueName, None, Spine.SNil))
      // Thread the (already-evaluated) type arguments into the value's spine. For a type-application scrutinee like
      // `Tag["hello"]` this keeps `"hello"` in the constructor's spine, so a type-match `case Tag[name] -> name` binds it.
      typeArguments.foldLeft(base)(applyTypeArgument)

    case Term.FunctionApplication(target, argument) =>
      Evaluator.applyValue(eval(env, target), eval(env, argument))

    case Term.FunctionLiteral(parameterName, body) =>
      VLam(parameterName, arg => eval(env.bind(parameterName, arg), body))
  }

  /** Apply one written type argument to a value's binding — ordinary application, *except* against a target that does
    * not model the argument. Two targets can fail to model one, for the same underlying reason: the binding's leading
    * lambdas are its *value* parameters, so an argument it cannot bind lands in the first value slot and shifts every
    * value argument one to the left, silently computing the wrong answer.
    *
    * **A native leaf.** A native's binding is the host-runnable function that remains once erased type arguments are
    * gone: `fold`'s takes its three value arguments and no type argument at all. A call's value reference still carries
    * its type arguments, written or appended by the checker at instantiation, so a native can meet a type argument it
    * has no parameter for. A few natives do model
    * leading arguments — `Function[A, B]`'s two types, `integerLiteral[128]`'s `BigInteger` *value* (types are values,
    * so a literal is an ordinary type argument) — and those must still receive them. The native's own declared
    * `paramType` decides.
    *
    * The native's unsure direction is *not to apply*: dropping an argument a native did need leaves it an unapplied
    * native that fails loudly at read-back; applying one it did not would silently compute the wrong answer.
    *
    * **A binding that states its type-argument fit** keeps the argument in its spine — a bodied definition that does not
    * unfold is *identified* by that spine, which the unifier decomposes and the printer renders — and records that one
    * more leading entry is a type argument. Whether the body can actually bind it is settled later, where it matters, by
    * [[SemValue.VTopDef.TypeArgFit]] at unfold time. See there for the miscompile this separates out.
    */
  private def applyTypeArgument(target: SemValue, typeArgument: SemValue): SemValue = target match {
    case VNative(paramType, _) if !modelsTypeArgument(paramType, typeArgument) => target
    case topDef @ VTopDef(_, _, spine, Some(fit))                              =>
      // Keep the argument in the spine (it *is* the type application for a definition that does not unfold) and record
      // that one more leading entry is a type argument, so unfolding can skip the ones the body cannot bind.
      topDef.copy(spine = spine :+ typeArgument, typeArgFit = Some(fit.copy(applied = fit.applied + 1)))
    case _                                                                     => Evaluator.applyValue(target, typeArgument)
  }

  private def modelsTypeArgument(paramType: SemValue, typeArgument: SemValue): Boolean = paramType match {
    case VType => true
    case _     =>
      typeArgument match {
        case VConst(_) => true
        case _         => false
      }
  }

  /** Project one node of the concrete IR onto the shared [[NbeEvaluator.Term]] shape. Type arguments of a value
    * reference are produced as [[SemValue]]s here — the ORE evaluator evaluates its ORE type-argument sub-terms (using
    * `env` and the shared [[eval]]), the checker-output evaluator passes through the ones it already holds. This is the
    * single point of variation between the two evaluators.
    */
  protected def decompose(env: Env, expr: E): Term[E]
}

object NbeEvaluator {

  /** The structural shape of an evaluable expression node, abstracting over the concrete IR. A value reference's type
    * arguments are carried already as [[SemValue]]s, so [[NbeEvaluator.eval]] is independent of how each IR obtains them.
    */
  enum Term[+E] {
    case IntegerLiteral(value: BigInt)
    case StringLiteral(value: String)
    case ParameterReference(name: String)
    case ValueReference(valueName: ValueFQN, typeArguments: Seq[SemValue])
    case FunctionApplication(target: E, argument: E)
    case FunctionLiteral(parameterName: String, body: E)
  }
}
