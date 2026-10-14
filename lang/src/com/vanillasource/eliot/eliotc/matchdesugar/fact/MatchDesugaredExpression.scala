package com.vanillasource.eliot.eliotc.matchdesugar.fact

import cats.Applicative
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.resolve.fact.Expression
import com.vanillasource.eliot.eliotc.source.content.Sourced

sealed trait MatchDesugaredExpression

object MatchDesugaredExpression {
  case class FunctionApplication(
      target: Sourced[MatchDesugaredExpression],
      argument: Sourced[MatchDesugaredExpression]
  ) extends MatchDesugaredExpression
  case class IntegerLiteral(integerLiteral: Sourced[BigInt])                          extends MatchDesugaredExpression
  case class StringLiteral(stringLiteral: Sourced[String])                            extends MatchDesugaredExpression
  case class ParameterReference(parameterName: Sourced[String])                       extends MatchDesugaredExpression
  case class ValueReference(
      valueName: Sourced[ValueFQN],
      typeArgs: Seq[Sourced[MatchDesugaredExpression]] = Seq.empty
  ) extends MatchDesugaredExpression
  case class FunctionLiteral(
      parameterName: Sourced[String],
      parameterType: Option[Sourced[MatchDesugaredExpression]],
      body: Sourced[MatchDesugaredExpression]
  ) extends MatchDesugaredExpression
  case class FlatExpression(parts: Seq[Sourced[MatchDesugaredExpression]]) extends MatchDesugaredExpression

  /** `subject with implementation` — effects v6's binding of a named implementation for the calls lexically inside
    * `subject` (`docs/effects.md` §9.3). Consumed and erased by the `row` phase.
    */
  case class WithBinding(
      subject: Sourced[MatchDesugaredExpression],
      implementation: Sourced[ValueFQN],
      entry: Option[Int] = None
  ) extends MatchDesugaredExpression

  def mapChildrenM[F[_]: Applicative](f: MatchDesugaredExpression => F[MatchDesugaredExpression])(
      expr: MatchDesugaredExpression
  ): F[MatchDesugaredExpression] =
    expr match {
      case FunctionApplication(target, arg)                             =>
        (f(target.value).map(target.as), f(arg.value).map(arg.as)).mapN(FunctionApplication.apply)
      case FunctionLiteral(paramName, paramType, body)                  =>
        (paramType.traverse(pt => f(pt.value).map(pt.as)), f(body.value).map(body.as))
          .mapN(FunctionLiteral(paramName, _, _))
      case FlatExpression(parts)                                        =>
        parts.traverse(p => f(p.value).map(p.as)).map(FlatExpression.apply)
      case ValueReference(name, typeArgs)                               =>
        typeArgs.traverse(ta => f(ta.value).map(ta.as)).map(ValueReference(name, _))
      case WithBinding(subject, implementation, entry)                  =>
        f(subject.value).map(s => WithBinding(subject.as(s), implementation, entry))
      case _: IntegerLiteral | _: StringLiteral | _: ParameterReference => expr.pure[F]
    }

  def fromExpression(expr: Expression): MatchDesugaredExpression = expr match {
    case Expression.FunctionApplication(target, arg)            =>
      FunctionApplication(target.map(fromExpression), arg.map(fromExpression))
    case Expression.IntegerLiteral(v)                           => IntegerLiteral(v)
    case Expression.StringLiteral(v)                            => StringLiteral(v)
    case Expression.ParameterReference(v)                       => ParameterReference(v)
    case Expression.ValueReference(name, typeArgs)              =>
      ValueReference(name, typeArgs.map(ta => ta.map(fromExpression)))
    case Expression.FunctionLiteral(paramName, paramType, body) =>
      FunctionLiteral(paramName, paramType.map(_.map(fromExpression)), body.map(fromExpression))
    case Expression.FlatExpression(parts)                       =>
      FlatExpression(parts.map(_.map(fromExpression)))
    case Expression.WithBinding(subject, implementation, entry) =>
      WithBinding(subject.map(fromExpression), implementation, entry)
    case Expression.MatchExpression(_, _)                       =>
      throw IllegalStateException("MatchExpression should not exist after match desugaring")
    case Expression.BlockExpression(_)                          =>
      throw IllegalStateException("BlockExpression should not exist after block desugaring")
  }

  extension (self: MatchDesugaredExpression)
    def render: String = self match {
      case IntegerLiteral(Sourced(_, _, value))                                => value.toString()
      case StringLiteral(Sourced(_, _, value))                                 => s"\"$value\""
      case FunctionApplication(Sourced(_, _, target), Sourced(_, _, argument)) =>
        s"${target.render}(${argument.render})"
      case FunctionLiteral(param, paramType, body)                             =>
        s"(${paramType.map(_.value.render).getOrElse("<n/a>")} :: ${param.value}) -> ${body.value.render}"
      case ParameterReference(name)                                            => name.value
      case ValueReference(name, typeArgs)                                      =>
        name.value.show +
          (if (typeArgs.isEmpty) "" else typeArgs.map(ta => ta.value.render).mkString("[", ", ", "]"))
      case FlatExpression(parts)                                               => parts.map(_.value.render).mkString(" ")
      case WithBinding(subject, implementation, _)                             =>
        s"${subject.value.render} with ${implementation.value.show}"
    }
}
