package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{DataDefinition, Expression}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Strict-positivity check — termination precondition #2 (the negative-recursive-datatype route to `Y`).
  *
  * A recursion-free typed core is strongly normalizing, but a *nominally* introduced negative recursive datatype lets
  * the user manufacture recursion that bypasses platform loop natives entirely. The classic example is `data Loop(f:
  * Function[Loop, A])`: with the defined type appearing left of an arrow, `Loop` is equivalent to `Loop -> A`, which is
  * exactly the typing the Y-combinator's self-application `x x` needs (Pierce, *TAPL*: recursive types derive `fix`).
  * The occurs-check in the unifier blocks the *inferred* infinite type `x x`; this blocks the *declared* one.
  *
  * The rule (Coquand–Paulin strict positivity, the same obligation Coq/Agda enforce): the data type's own type
  * constructor may not occur in a **contravariant** position — to the left of an odd number of function arrows — of any
  * constructor field. Polarity flips at each `Function` domain. Positivity is *not* about making any fold total (folds
  * are platform natives, trusted to terminate); it purely stops the user manufacturing recursion via a
  * negative-recursive datatype.
  *
  * Accepted (covariant self-reference is structural recursion in data, harmless): `data Tree(left: Tree, right: Tree)`,
  * `data Box(content: Function[A, B])`. Rejected: `data Loop(f: Function[Loop, A])`.
  *
  * Scope: self-reference within a single `data` declaration. Mutual negative recursion across two data types forms a
  * cycle in the resolved value-reference graph and is caught by the no-recursion rule (M1); other type constructors are
  * conservatively treated as covariant in their arguments (only `Function` flips polarity), which is sound — it can
  * only reject more, never fewer, programs — given Eliot's only Π-former is `Function`.
  */
object StrictPositivityChecker {

  /** One sourced error message per offending occurrence of the data type's own name in a contravariant position; empty
    * when the definition is strictly positive. The source position points at the offending occurrence.
    */
  def check(definition: DataDefinition): Seq[Sourced[String]] = {
    val dataName = definition.name.value
    definition.constructors
      .getOrElse(Seq.empty)
      .flatMap(_.fields)
      .flatMap(field => negativeOccurrences(dataName, field.typeExpression, positive = true))
  }

  /** Collect occurrences of `dataName` (an unqualified self-reference) in a contravariant position of `expr`.
    * `positive` tracks the current polarity; it flips on each `Function` domain. Recursion always descends into
    * sub-expressions so a nested arrow (`List[Function[Loop, A]]`) is reached.
    */
  private def negativeOccurrences(
      dataName: String,
      expr: Sourced[Expression],
      positive: Boolean
  ): Seq[Sourced[String]] = expr.value match {
    case Expression.FunctionApplication(module, name, genericArguments, arguments) =>
      val selfHit   =
        if (!positive && module.isEmpty && name.value == dataName)
          Seq(expr.as(s"Recursive type '$dataName' may not appear in a contravariant position (left of '->')."))
        else Seq.empty
      val args      = genericArguments.getOrElse(Seq.empty) ++ arguments
      val childHits =
        if (module.isEmpty && name.value == "Function" && args.nonEmpty)
          // `Function[Dom…, Cod]`: every argument but the last is a domain (contravariant — flip polarity); the final
          // argument is the codomain (covariant — keep polarity).
          args.init.flatMap(negativeOccurrences(dataName, _, !positive)) ++
            negativeOccurrences(dataName, args.last, positive)
        else
          // Any other type constructor is treated as covariant in all of its arguments.
          args.flatMap(negativeOccurrences(dataName, _, positive))
      selfHit ++ childHits

    case Expression.FunctionLiteral(_, body)             => negativeOccurrences(dataName, body, positive)
    case Expression.FlatExpression(parts)                => parts.flatMap(negativeOccurrences(dataName, _, positive))
    case Expression.MatchExpression(scrutinee, cs)       =>
      negativeOccurrences(dataName, scrutinee, positive) ++
        cs.flatMap(c => negativeOccurrences(dataName, c.body, positive))
    case Expression.EffectfulType(effects, result)       =>
      effects.flatMap(_.typeArgs.flatMap(negativeOccurrences(dataName, _, positive))) ++
        negativeOccurrences(dataName, result, positive)
    case Expression.WithBinding(subject, _)              => negativeOccurrences(dataName, subject, positive)
    case Expression.IntegerLiteral(_)                    => Seq.empty
    case Expression.StringLiteral(_)                     => Seq.empty
    case Expression.BlockExpression(_)                   => Seq.empty // blocks are runtime values, never type expressions
  }
}
