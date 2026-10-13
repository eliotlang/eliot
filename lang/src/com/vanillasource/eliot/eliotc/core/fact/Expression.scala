package com.vanillasource.eliot.eliotc.core.fact

import cats.syntax.all.*
import cats.Eq
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

sealed trait Expression

object Expression {

  // Reference to a named value, with optional explicit type arguments
  case class NamedValueReference(
      valueName: Sourced[QualifiedName],
      moduleName: Option[Sourced[String]] = None,
      typeArgs: Seq[Sourced[Expression]] = Seq.empty
  ) extends Expression
  // Apply an argument to an expression (assumed to be a function)
  case class FunctionApplication(
      target: Sourced[Expression],
      argument: Sourced[Expression]
  ) extends Expression
  // Function literal, i.e. a lambda expression, i.e. an ad-hoc function, i.e. an unnamed function
  case class FunctionLiteral(
      parameterName: Sourced[String],
      parameterType: Option[Sourced[Expression]],
      body: Sourced[Expression]
  ) extends Expression
  // Integer literal
  case class IntegerLiteral(integerLiteral: Sourced[String]) extends Expression
  // String literal
  case class StringLiteral(stringLiteral: Sourced[String])   extends Expression
  // Flat sequence of expression parts, to be resolved by OperatorResolverProcessor
  case class FlatExpression(parts: Seq[Sourced[Expression]]) extends Expression
  // Match expression with scrutinee and cases, to be desugared by MatchDesugaringProcessor
  case class MatchExpression(
      scrutinee: Sourced[Expression],
      cases: Seq[MatchCase]
  ) extends Expression
  // A `{ … }` block, to be lowered to immediately-applied lambdas by BlockDesugaringProcessor (after resolution).
  case class BlockExpression(lines: Seq[BlockLine]) extends Expression

  /** `subject with implementation` — effects v6's binding of a named implementation for the calls lexically inside
    * `subject` (`docs/effects.md` §9.3). The name is carried as written (plus its optional module qualifier) and
    * resolved at [[com.vanillasource.eliot.eliotc.resolve.processor.ImplementationNameResolver]] to the
    * implementation's marker; the node itself is consumed by the `row` phase, which writes the binding into every
    * phantom binder inside the subject and erases the node, so nothing from saturation onwards ever sees it.
    *
    * `arguments` are what a `with` supplies to an implementation that takes parameters (`greet with
    * scripted(List("Bob"))`); the resolver lowers them to a call of the implementation's binder declaration around the
    * resolved `with` ([[com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters]]), so no later phase's `with`
    * carries any.
    */
  case class WithBinding(
      subject: Sourced[Expression],
      implementationName: Sourced[String],
      moduleName: Option[Sourced[String]] = None,
      arguments: Seq[Sourced[Expression]] = Seq.empty
  ) extends Expression

  case class MatchCase(
      pattern: Sourced[Pattern],
      body: Sourced[Expression]
  )

  /** One line of a [[BlockExpression]]: an optional binder (name plus optional type) and the line's flat expression. */
  case class BlockLine(
      binderName: Option[Sourced[String]],
      binderType: Option[Sourced[Expression]],
      expression: Sourced[Expression]
  )

  /** Structural equality means that the expression contains the same building blocks in the same order / structure. No
    * type information is used, i.e. not higher levels of expressions.
    */
  def structuralEquality: Eq[Expression] = (x: Expression, y: Expression) =>
    (x, y) match {
      case (NamedValueReference(n1, q1, _), NamedValueReference(n2, q2, _)) =>
        n1.value == n2.value && q1.map(_.value) == q2.map(_.value)
      case (FunctionApplication(t1, a1), FunctionApplication(t2, a2))       =>
        structuralEquality.eqv(t1.value, t2.value) &&
        structuralEquality.eqv(a1.value, a2.value)
      case (FunctionLiteral(p1, pt1, b1), FunctionLiteral(p2, pt2, b2))     =>
        p1.value == p2.value && // Leave the type here, it does not contribute to structure (?)
        structuralEquality.eqv(b1.value, b2.value)
      case (IntegerLiteral(i1), IntegerLiteral(i2))                         => i1.value == i2.value
      case (StringLiteral(s1), StringLiteral(s2))                           => s1.value == s2.value
      case (FlatExpression(p1), FlatExpression(p2))                         =>
        p1.length == p2.length && (p1 zip p2).forall { case (a, b) =>
          structuralEquality.eqv(a.value, b.value)
        }
      case (MatchExpression(s1, c1), MatchExpression(s2, c2))              =>
        structuralEquality.eqv(s1.value, s2.value) &&
        c1.length == c2.length && (c1 zip c2).forall { case (a, b) =>
          structuralEquality.eqv(a.body.value, b.body.value)
        }
      // A slot's `with` is part of the signature: a layer's copy binds the same implementation or it is a different
      // definition. Without this arm two identical copies fall to `false` below and the merge rejects them, which is
      // what a control effect's discharger (`obj: {Abort} A with abortByEscape`) is spelled with.
      case (WithBinding(s1, n1, m1, a1), WithBinding(s2, n2, m2, a2))       =>
        structuralEquality.eqv(s1.value, s2.value) &&
        n1.value == n2.value &&
        m1.map(_.value) == m2.map(_.value) &&
        a1.length == a2.length && (a1 zip a2).forall { case (a, b) => structuralEquality.eqv(a.value, b.value) }
      case _                                                                => false
    }

  extension (self: Expression)
    def render: String = self match {
      case IntegerLiteral(Sourced(_, _, value))                               => value
      case StringLiteral(Sourced(_, _, value))                                => s"\"$value\""
      case FunctionApplication(Sourced(_, _, target), Sourced(_, _, argument)) =>
        s"${target.render}(${argument.render})"
      case FunctionLiteral(param, _, body)                                    => s"${param.value} -> ${body.value.render}"
      case NamedValueReference(valueName, qualifier, typeArgs)                =>
        qualifier.map(q => s"${q.value}::").getOrElse("") + valueName.value +
          (if (typeArgs.isEmpty) "" else typeArgs.map(ta => ta.value.render).mkString("[", ", ", "]"))
      case FlatExpression(parts)                                              => parts.map(_.value.render).mkString(" ")
      case MatchExpression(scrutinee, cases)                                  =>
        s"${scrutinee.value.render} match { ${cases.map(c => s"case ${c.pattern.value.render} -> ${c.body.value.render}").mkString(" ")} }"
      case BlockExpression(lines)                                             =>
        lines
          .map(l => l.binderName.map(n => s"val ${n.value} = ").getOrElse("") + l.expression.value.render)
          .mkString("{ ", "; ", " }")
      case WithBinding(subject, implementationName, moduleName, arguments)    =>
        s"${subject.value.render} with " + moduleName.map(m => s"${m.value}::").getOrElse("") +
          implementationName.value +
          (if (arguments.isEmpty) "" else arguments.map(_.value.render).mkString("(", ", ", ")"))
    }
}
