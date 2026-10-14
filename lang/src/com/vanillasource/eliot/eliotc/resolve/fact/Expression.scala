package com.vanillasource.eliot.eliotc.resolve.fact

import cats.Applicative
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced

sealed trait Expression

object Expression {
  case class FunctionApplication(
      target: Sourced[Expression],
      argument: Sourced[Expression]
  ) extends Expression
  case class IntegerLiteral(integerLiteral: Sourced[BigInt])    extends Expression
  case class StringLiteral(stringLiteral: Sourced[String])      extends Expression
  case class ParameterReference(parameterName: Sourced[String]) extends Expression
  case class ValueReference(valueName: Sourced[ValueFQN], typeArgs: Seq[Sourced[Expression]] = Seq.empty)
      extends Expression
  case class FunctionLiteral(
      parameterName: Sourced[String],
      parameterType: Option[Sourced[Expression]],
      body: Sourced[Expression]
  ) extends Expression
  case class FlatExpression(parts: Seq[Sourced[Expression]]) extends Expression
  case class MatchExpression(
      scrutinee: Sourced[Expression],
      cases: Seq[MatchCase]
  ) extends Expression
  // A `{ … }` block, lowered to immediately-applied lambdas by BlockDesugaringProcessor; gone before matchdesugar.
  case class BlockExpression(lines: Seq[BlockLine]) extends Expression

  /** `subject with implementation` — effects v6's binding of a named implementation for the calls lexically inside
    * `subject` (`docs/effects.md` §9.3), with the name already resolved to the implementation's **marker**
    * ([[ImplementationNameResolver]]). Consumed by the `row` phase, which writes the binding into every phantom
    * binder inside the subject and erases the node.
    */
  case class WithBinding(subject: Sourced[Expression], implementation: Sourced[ValueFQN], entry: Option[Int] = None)
      extends Expression

  case class MatchCase(
      pattern: Sourced[Pattern],
      body: Sourced[Expression]
  )

  /** One line of a [[BlockExpression]]: an optional binder (name plus optional resolved type) and the line's resolved
    * flat expression. References to the binder name in later lines resolve to a [[ParameterReference]].
    */
  case class BlockLine(
      binderName: Option[Sourced[String]],
      binderType: Option[Sourced[Expression]],
      expression: Sourced[Expression]
  )

  def mapChildrenM[F[_]: Applicative](f: Expression => F[Expression])(expr: Expression): F[Expression] =
    expr match {
      case FunctionApplication(target, arg)                =>
        (f(target.value).map(target.as), f(arg.value).map(arg.as)).mapN(FunctionApplication.apply)
      case FunctionLiteral(paramName, paramType, body)     =>
        (paramType.traverse(pt => f(pt.value).map(pt.as)), f(body.value).map(body.as))
          .mapN(FunctionLiteral(paramName, _, _))
      case FlatExpression(parts)                           =>
        parts.traverse(p => f(p.value).map(p.as)).map(FlatExpression.apply)
      case MatchExpression(scrutinee, cases)               =>
        (
          f(scrutinee.value).map(scrutinee.as),
          cases.traverse(mc => f(mc.body.value).map(e => MatchCase(mc.pattern, mc.body.as(e))))
        ).mapN(MatchExpression.apply)
      case ValueReference(name, typeArgs)                  =>
        typeArgs.traverse(ta => f(ta.value).map(ta.as)).map(ValueReference(name, _))
      case BlockExpression(lines)                          =>
        lines
          .traverse(line =>
            (line.binderType.traverse(bt => f(bt.value).map(bt.as)), f(line.expression.value).map(line.expression.as))
              .mapN((bt, e) => BlockLine(line.binderName, bt, e))
          )
          .map(BlockExpression.apply)
      case WithBinding(subject, implementation, entry)     =>
        f(subject.value).map(s => WithBinding(subject.as(s), implementation, entry))
      case _: IntegerLiteral | _: StringLiteral | _: ParameterReference => expr.pure[F]
    }

  extension (self: Expression)
    def render: String = self match {
      case IntegerLiteral(Sourced(_, _, value))                               => value.toString()
      case StringLiteral(Sourced(_, _, value))                                => s"\"$value\""
      case FunctionApplication(Sourced(_, _, target), Sourced(_, _, argument)) =>
        s"${target.render}(${argument.render})"
      case FunctionLiteral(param, paramType, body)                            =>
        s"(${paramType.map(_.value.render).getOrElse("<n/a>")} :: ${param.value}) -> ${body.value.render}"
      case ParameterReference(name)                => name.value
      case ValueReference(name, typeArgs)          =>
        name.value.show +
          (if (typeArgs.isEmpty) "" else typeArgs.map(ta => ta.value.render).mkString("[", ", ", "]"))
      case FlatExpression(parts)                   => parts.map(_.value.render).mkString(" ")
      case MatchExpression(scrutinee, cases)       =>
        s"${scrutinee.value.render} match { ${cases.map(c => s"case ${c.pattern.value.render} -> ${c.body.value.render}").mkString(" ")} }"
      case BlockExpression(lines)                  =>
        lines
          .map(l => l.binderName.map(n => s"val ${n.value} = ").getOrElse("") + l.expression.value.render)
          .mkString("{ ", "; ", " }")
      case WithBinding(subject, implementation, _) => s"${subject.value.render} with ${implementation.value.show}"
    }
}
