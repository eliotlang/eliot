package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** A runtime expression annotated with concrete ground types. All types are fully evaluated GroundValues with no free
  * type variables or unsolved metas.
  */
case class MonomorphicExpression(
    expressionType: GroundValue,
    expression: MonomorphicExpression.Expression
)

object MonomorphicExpression {
  sealed trait Expression

  case class FunctionApplication(
      target: Sourced[MonomorphicExpression],
      argument: Sourced[MonomorphicExpression]
  ) extends Expression

  case class FunctionLiteral(
      parameterName: Sourced[String],
      parameterType: GroundValue,
      body: Sourced[MonomorphicExpression]
  ) extends Expression

  case class IntegerLiteral(value: Sourced[BigInt]) extends Expression

  case class StringLiteral(value: Sourced[String]) extends Expression

  case class ParameterReference(parameterName: Sourced[String]) extends Expression

  /** @param dispatchedFrom
    *   For a reference the checker dispatched from an ability member to an implementation's clause, the member's entry
    *   and binding ([[MonomorphicEffects.Dispatch]]); [[None]] for every other reference.
    */
  case class MonomorphicValueReference(
      valueName: Sourced[ValueFQN],
      typeArguments: Seq[GroundValue],
      dispatchedFrom: Option[MonomorphicEffects.Dispatch] = None
  ) extends Expression
}
