package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import cats.Eq
import ASTComponent.component
import Primitives.{isIdentifier, keyword, sourced, symbol}
import com.vanillasource.eliot.eliotc.ast.fact.Expression.{EffectfulType, FlatExpression, FunctionApplication}
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token
import Parser.*

/** A value-argument binder, e.g. `x: Int` in `def f(x: Int)`.
  *
  * @param closedRow
  *   Whether the parameter's `uses` clause is **closed** — it names effects and no `*` (`body uses Throw[E]: A`), so
  *   the argument's text may use what the slot supplies and nothing from around it (`docs/effects.md` D21 rule 4).
  *   The row itself is written into [[typeExpression]] in today's spelling; this is the one bit that spelling cannot
  *   carry.
  */
case class ArgumentDefinition(
    name: Sourced[String],
    typeExpression: Sourced[Expression],
    closedRow: Boolean = false
)

object ArgumentDefinition {
  val signatureEquality: Eq[ArgumentDefinition] = (x: ArgumentDefinition, y: ArgumentDefinition) =>
    x.name.value === y.name.value && x.typeExpression.value.render === y.typeExpression.value.render &&
      x.closedRow === y.closedRow

  extension (self: ArgumentDefinition) def render: String = self.name.show

  /** A field's or a meta slot's binder: `name: Type`, with an optional trailing `with` chain on the type. A field holds
    * a value, so it takes no `uses` clause (`docs/effects.md` D20 rule 6) — the parser stops at the keyword — and no
    * row (`Expression.fieldTypeRunParser`).
    */
  given ASTComponent[ArgumentDefinition] = new ASTComponent[ArgumentDefinition] {
    // The argument type uses `typeRunParser` (the shared type-position parser), so an infix type operator reads bare
    // here — `f: A => B`. The run stops at the `,` separator and the closing `)` of the argument list (both reserved,
    // non-type-atom tokens), so a single-atom type is still returned verbatim and the list structure is unaffected.
    // A trailing `with name` binds a named implementation to the slot (effects v6, landed dark — see
    // [[Expression.WithBinding]]). Only a parameter's or a field's type admits it, which is why it is read here and not
    // in `typeRunParser`: a def's own return type stops at the `with` keyword and fails to parse.
    override def parser: Parser[Sourced[Token], ArgumentDefinition] =
      acceptIf(isIdentifier, "argument name").flatMap(name => plainRest(name.map(_.content), Expression.fieldTypeRunParser))
  }

  /** A definition's parameter: a field's binder, or one carrying a **`uses` clause** (`docs/effects.md` D21) — the
    * parameter is then *code*, the caller's text run by the callee:
    *
    * {{{
    * value uses *: T                       // ⤳ value: {} T            the caller's code, open to its effects
    * body uses *, Throw[E]: A              // ⤳ body: {Throw[E]} A     the same, plus Throw[E] the callee gives
    * body uses Throw[E]: A                 // ⤳ body: {Throw[E]} A     closed: Throw[E] and nothing else
    * action uses *: A => Unit              // ⤳ action: A => {} Unit   code of function type: the row on its codomain
    * body uses *, Console with fake: Unit  // ⤳ body: {Console} Unit with fake
    * }}}
    *
    * The clause is written onto the spelling the rest of the compiler already reads — a row on the slot's type, and its
    * `with` chain after it — so no phase past this one learns the new surface; the one thing that spelling cannot say,
    * that the row is closed, is [[ArgumentDefinition.closedRow]]. `*` comes first and at most once. A type that already
    * carries a row as well is refused by the parser, as a row in any type is: the clause is the only spelling.
    */
  val parameter: Parser[Sourced[Token], ArgumentDefinition] = for {
    name     <- acceptIf(isIdentifier, "argument name")
    argument <- clauseRest(name.map(_.content)) or plainRest(name.map(_.content), Expression.typeRunParser)
  } yield argument

  private def clauseRest(name: Sourced[String]): Parser[Sourced[Token], ArgumentDefinition] = for {
    clause <- usesClause
    _      <- symbol(":")
    typed  <- sourced(Expression.typeRunParser)
  } yield ArgumentDefinition(name, clause.applyTo(typed), closedRow = !clause.open)

  private def plainRest(
      name: Sourced[String],
      typeRunParser: Parser[Sourced[Token], Expression]
  ): Parser[Sourced[Token], ArgumentDefinition] = for {
    _              <- symbol(":")
    typeRun        <- sourced(typeRunParser)
    typeExpression <- Expression.typeWithBindings(typeRun)
  } yield ArgumentDefinition(name, typeExpression)

  /** `uses [*] [, entry]*` on a parameter, each entry an ability reference with an optional `with` chain. */
  private val usesClause: Parser[Sourced[Token], UsesClause] =
    keyword("uses") *> symbol("*").optional().flatMap {
      case Some(_) => (symbol(",") *> usesEntry).anyTimes().map(UsesClause(true, _))
      case None    => usesEntry.atLeastOnceSeparatedBy(symbol(",")).map(UsesClause(false, _))
    }

  private type Entry = (UnresolvedAbilityConstraint[Sourced[Expression]], Seq[Sourced[Expression]])

  private val usesEntry: Parser[Sourced[Token], Entry] =
    for {
      entry    <- component[UnresolvedAbilityConstraint[Sourced[Expression]]]
      bindings <- Expression.withBindings
    } yield (entry, bindings)

  /** A parsed `uses` clause: whether it is open (`*`), and its entries with the implementations named on each. */
  private case class UsesClause(open: Boolean, entries: Seq[Entry]) {

    /** The slot's type in today's spelling: the row on the codomain of an arrow type, on the type itself otherwise, and
      * every named implementation as a `with` after it, in the order written.
      */
    def applyTo(typed: Sourced[Expression]): Sourced[Expression] =
      Expression.applyWithBindings(rowed(typed), entries.flatMap(_._2))

    /** The row goes on the final codomain of the run as written, never inside it: `String => (String => Unit)` is code
      * taking one argument and handing back a function value, so it is `String => {} (String => Unit)`. A nested run
      * can only be a parenthesized one, since an unparenthesized `A => B => C` is a single flat run.
      */
    private def rowed(typed: Sourced[Expression]): Sourced[Expression] = typed.value match {
      case FlatExpression(parts) if parts.exists(isArrow) => typed.as(FlatExpression(parts.init :+ onRow(parts.last)))
      case _                                              => onRow(typed)
    }

    private def onRow(typed: Sourced[Expression]): Sourced[Expression] =
      typed.as(EffectfulType(entries.map(_._1), typed))
  }

  private def isArrow(part: Sourced[Expression]): Boolean = part.value match {
    case FunctionApplication(None, name, None, Seq()) => name.value === "=>"
    case _                                            => false
  }
}
