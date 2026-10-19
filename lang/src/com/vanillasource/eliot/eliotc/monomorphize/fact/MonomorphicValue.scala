package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.module.fact.QualifiedName
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.annotation.tailrec

/** A monomorphized (specialized) value with all type parameters instantiated to concrete types via NbE.
  *
  * @param vfqn
  *   The fully qualified name of the original value
  * @param typeArguments
  *   The concrete type arguments used for specialization
  * @param name
  *   The sourced name of the value
  * @param signature
  *   The concrete ground type of this specialized instance
  * @param runtime
  *   The optional monomorphized runtime body
  * @param handed
  *   The types of the handles this value's body hands the code of each of its code parameters, for its handed binders
  *   its key leaves open (`docs/effects.md` D36 point 6) — what a caller reads them off.
  *
  * The handles an instantiation received are ordinary type arguments, already here in [[typeArguments]], and the
  * checker dispatched every effect operation by its handle's type, so nothing about effects is left to record.
  */
case class MonomorphicValue(
    vfqn: ValueFQN,
    typeArguments: Seq[GroundValue],
    name: Sourced[QualifiedName],
    signature: GroundValue,
    runtime: Option[Sourced[MonomorphicExpression.Expression]],
    handed: Seq[GroundValue] = Seq.empty
) extends CompilerFact {
  override def key(): CompilerFactKey[MonomorphicValue] =
    MonomorphicValue.Key(vfqn, typeArguments)

  /** The number of arguments a direct call to this instance can absorb: the count of leading
    * [[MonomorphicExpression.FunctionLiteral]]s of the runtime body. An application spine longer than this is an
    * over-application — the excess arguments must be applied one at a time to the function *value* the direct call
    * returns — so both the `used` arity statistics and the backend call sites cap the direct-call arity here. `None`
    * for a body-less (native) value, whose emitted arity is the platform's decision.
    */
  def naturalArity: Option[Int] = runtime.map(body => MonomorphicValue.countLeadingLambdas(body.value, 0))
}

object MonomorphicValue {

  /** Composite key that uniquely identifies a monomorphic specialization. The same generic function with different type
    * arguments produces different keys.
    */
  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue]) extends CompilerFactKey[MonomorphicValue] {
    override def valueCodec: Option[FactCodec[MonomorphicValue]] = Some(LangFactCodecs.monomorphicValueCodec)
  }

  @tailrec
  private def countLeadingLambdas(expression: MonomorphicExpression.Expression, count: Int): Int =
    expression match {
      case MonomorphicExpression.FunctionLiteral(_, _, body) => countLeadingLambdas(body.value.expression, count + 1)
      case _                                                 => count
    }
}
