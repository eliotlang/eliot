package com.vanillasource.eliot.eliotc.monomorphize.eval

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression

/** Pure, synchronous NbE evaluator over a *reduced* [[MonomorphicExpression]] — the compiler backend's output. Shares
  * the [[NbeEvaluator]] traversal with the ORE [[Evaluator]] and the [[SemExpressionEvaluator]]; it differs only in
  * [[decompose]].
  *
  * It is how the runtime track *consumes* a compiler-platform value's reduced native (compiler-as-platform, Increment
  * E): a compiler-track value whose body performs ability dispatch (a guard combinator's `raise`/`pure`) cannot be
  * re-reduced by the pure evaluator against the abstract ability method, so the compiler track reduces it once
  * ([[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue]], the concrete-impl form) and this
  * evaluator turns that reduced tree back into an evaluable [[SemValue]] binding.
  *
  * '''Type arguments are dropped.''' A [[MonomorphicExpression]]'s value-reference type arguments are *erased*
  * annotations — they are never applied to the referenced binding, whose closure carries only its reified
  * value-parameter lambdas (see `BindingClosure.reifyingWrap`). Applying them here would mis-bind a value parameter to
  * an erased type argument (e.g. bind `Either::pure`'s value parameter `a` to the type argument `A`). Dropping them is
  * therefore both correct — value evaluation does not depend on erased type arguments, exactly as the backend erases
  * them — and required.
  *
  * @param lookupTopDef
  *   Resolve a top-level definition by [[ValueFQN]]. A missing binding becomes a stuck [[SemValue.VNeutral]] rather
  *   than a silent mis-evaluation.
  */
class MonomorphicEvaluator(lookupTopDef: ValueFQN => Option[SemValue])
    extends NbeEvaluator[MonomorphicExpression](lookupTopDef) {

  override protected def decompose(env: Env, expr: MonomorphicExpression): NbeEvaluator.Term[MonomorphicExpression] =
    expr.expression match {
      case MonomorphicExpression.IntegerLiteral(value) =>
        NbeEvaluator.Term.IntegerLiteral(value.value)

      case MonomorphicExpression.StringLiteral(value) =>
        NbeEvaluator.Term.StringLiteral(value.value)

      case MonomorphicExpression.ParameterReference(name) =>
        NbeEvaluator.Term.ParameterReference(name.value)

      case MonomorphicExpression.MonomorphicValueReference(vfqn, _) =>
        NbeEvaluator.Term.ValueReference(vfqn.value, Seq.empty)

      case MonomorphicExpression.FunctionApplication(target, argument) =>
        NbeEvaluator.Term.FunctionApplication(target.value, argument.value)

      case MonomorphicExpression.FunctionLiteral(paramName, _, body) =>
        NbeEvaluator.Term.FunctionLiteral(paramName.value, body.value)
    }
}
