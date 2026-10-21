package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import cats.Eq
import cats.data.StateT
import ASTComponent.component
import Primitives.{isIdentifier, keyword, optionalBracketedCommaSeparatedItems, sourced, symbol}
import com.vanillasource.eliot.eliotc.ast.fact.Expression.{EffectfulType, FlatExpression, FunctionApplication}
import com.vanillasource.eliot.eliotc.ast.parser.{InputStream, Parser, ParserResult}
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
  * @param openRow
  *   Whether the parameter's `uses` clause is **open** — it starts with `*` (`f uses *: A => B`), so the argument's
  *   text may use whatever is in scope where it is written. Read only by the definition's own parser, which then
  *   requires `uses *` on the definition too (`docs/effects.md` D24); nothing past the parser reads it.
  * @param meta
  *   The parameter's **meta brace** — the slot values in `b: Int {byte}`, one per slot of the type's meta, which state
  *   the meta-information of the value at this position (`docs/meta.md` §4.4), or on a `data` field
  *   the value it holds (§4.5). Empty for an ordinary parameter. Consumed at the core boundary by `MetaParameterDesugarer`, which emits the parameter's statement
  *   companion from it, and never part of [[signatureEquality]], so the layer rule for a return brace applies unchanged.
  * @param handed
  *   A code parameter's **arrow-domain braces** — `{body}` in `onValue uses *: A {body} => R` — each stating what the
  *   definition hands its code as one argument when it runs it (`docs/meta.md` §14). Empty for every other parameter.
  *   Consumed at the core boundary by `MetaParameterDesugarer`, which emits a handed statement from each, and never part
  *   of [[signatureEquality]].
  */
case class ArgumentDefinition(
    name: Sourced[String],
    typeExpression: Sourced[Expression],
    closedRow: Boolean = false,
    openRow: Boolean = false,
    meta: Seq[Sourced[Expression]] = Seq.empty,
    handed: Seq[ArgumentDefinition.Handed] = Seq.empty
)

object ArgumentDefinition {

  /** The brace after the domain of the `argument`-th arrow of a code parameter's type: `brace` states what the
    * definition hands the code as that argument, of type `domain`.
    */
  case class Handed(argument: Int, domain: Sourced[Expression], brace: Seq[Sourced[Expression]])

  val signatureEquality: Eq[ArgumentDefinition] = (x: ArgumentDefinition, y: ArgumentDefinition) =>
    x.name.value === y.name.value && x.typeExpression.value.render === y.typeExpression.value.render &&
      x.closedRow === y.closedRow

  extension (self: ArgumentDefinition) def render: String = self.name.show

  /** A field's or a meta slot's binder: `name: Type`. A field holds a value, so it takes no `uses` clause
    * (`docs/effects.md` D20 rule 6) — the parser stops at the keyword — and no row (`Expression.fieldTypeRunParser`).
    */
  given ASTComponent[ArgumentDefinition] = new ASTComponent[ArgumentDefinition] {
    // The argument type uses `typeRunParser` (the shared type-position parser), so an infix type operator reads bare
    // here — `f: A => B`. The run stops at the `,` separator and the closing `)` of the argument list (both reserved,
    // non-type-atom tokens), so a single-atom type is still returned verbatim and the list structure is unaffected.
    override def parser: Parser[Sourced[Token], ArgumentDefinition] =
      acceptIf(isIdentifier, "argument name").flatMap(name =>
        plainRest(name.map(_.content), Expression.fieldTypeRunParser, metaBrace = false)
      )
  }

  /** A `data` field's binder: a field's or meta slot's binder that may also carry a **meta brace** after its type,
    * `r: Int {byte}` (`docs/meta.md` §4.5). The field is the constructor's parameter, so the brace is a
    * parameter brace on the constructor — every construction proves it — and the `match` eliminator states it of what
    * it hands its handlers.
    */
  val field: Parser[Sourced[Token], ArgumentDefinition] =
    acceptIf(isIdentifier, "argument name").flatMap(name =>
      plainRest(name.map(_.content), Expression.fieldTypeRunParser, metaBrace = true)
    )

  /** A definition's parameter: a field's binder, or one carrying a **`uses` clause** (`docs/effects.md` D21) — the
    * parameter is then *code*, the caller's text run by the callee:
    *
    * {{{
    * value uses *: T                       // ⤳ value: {} T            the caller's code, open to its effects
    * body uses *, Throw[E]: A              // ⤳ body: {Throw[E]} A     the same, plus Throw[E] the callee gives
    * body uses Throw[E]: A                 // ⤳ body: {Throw[E]} A     closed: Throw[E] and nothing else
    * action uses *: A => Unit              // ⤳ action: A => {} Unit   code of function type: the row on its codomain
    * }}}
    *
    * The clause is written onto the spelling the rest of the compiler already reads — a row on the slot's type — so no
    * phase past this one learns the new surface; the one thing that spelling cannot say, that the row is closed, is
    * [[ArgumentDefinition.closedRow]]. `*` comes first and at most once. A type that already carries a row as well is
    * refused by the parser, as a row in any type is: the clause is the only spelling.
    *
    * A parameter names no implementation: `body uses *, Console with fake: Unit` is refused ([[slotWith]]), since the
    * code's effects are bound where it runs, by a `with` around the run (`body with fake`, `docs/effects.md` D35).
    *
    * A value parameter may carry a **meta brace** after its type, `b: Int {byte}` ([[ArgumentDefinition.meta]]) — the
    * adjacency rule a return brace already follows: a brace *before* a type is an effect row, one *after* it is its meta.
    * The type run leaves it unconsumed, since it is followed by no type atom.
    */
  val parameter: Parser[Sourced[Token], ArgumentDefinition] = for {
    name     <- acceptIf(isIdentifier, "argument name")
    argument <- clauseRest(name.map(_.content)) or
                  plainRest(name.map(_.content), Expression.typeRunParser, metaBrace = true)
  } yield argument

  /** A code parameter's type may carry a meta brace after an arrow's domain, `onValue uses *: A {body} => R`
    * ([[ArgumentDefinition.handed]]): what the definition hands its code there. The brace is not part of the type.
    */
  private def clauseRest(name: Sourced[String]): Parser[Sourced[Token], ArgumentDefinition] = for {
    clause <- usesClause
    _      <- symbol(":")
    run    <- sourced(Expression.handedTypeRunParser)
  } yield {
    val atoms = run.value.map(_._1)
    val typed = run.as(atoms match {
      case Seq(single) => single.value
      case parts       => FlatExpression(parts)
    })
    ArgumentDefinition(
      name,
      clause.applyTo(typed),
      closedRow = !clause.open,
      openRow = clause.open,
      handed = handedBraces(run.value)
    )
  }

  /** Each brace of a run, with the index of the arrow whose domain it follows and that domain: the parts since the
    * previous arrow. The parser only accepts a brace that an arrow follows.
    */
  private def handedBraces(run: Seq[(Sourced[Expression], Seq[Sourced[Expression]])]): Seq[Handed] =
    run.indices.filter(index => run(index)._2.nonEmpty).map { index =>
      val before = run.take(index + 1).map(_._1)
      val domain = before.drop(before.lastIndexWhere(isArrow) + 1)
      Handed(
        before.count(isArrow),
        domain match {
          case Seq(single) => single
          case parts       => Sourced.outline(parts).as(FlatExpression(parts))
        },
        run(index)._2
      )
    }

  private def plainRest(
      name: Sourced[String],
      typeRunParser: Parser[Sourced[Token], Expression],
      metaBrace: Boolean
  ): Parser[Sourced[Token], ArgumentDefinition] = for {
    _              <- symbol(":")
    typeExpression <- sourced(typeRunParser)
    meta           <- if (metaBrace) metaBraceParser else noMetaBrace
    _              <- slotWith
  } yield ArgumentDefinition(name, typeExpression, meta = meta)

  private val metaBraceParser: Parser[Sourced[Token], Seq[Sourced[Expression]]] =
    optionalBracketedCommaSeparatedItems("{", sourced(component[Expression]), "}")

  private val noMetaBrace: Parser[Sourced[Token], Seq[Sourced[Expression]]] =
    StateT.pure[ParserResult, InputStream[Sourced[Token]], Seq[Sourced[Expression]]](Seq.empty)

  /** A `with` on a parameter, after a clause entry or its type: refused as itself, naming the `with` around the run that
    * replaced it (`docs/effects.md` D35), rather than whatever the parser would expect next.
    */
  private val slotWith: Parser[Sourced[Token], Unit] =
    keyword("with")
      .refusedAs[Unit](
        "no `with` on a parameter, since code's effects are bound where it runs, by a `with` around the run in the " +
          "body (`body with fake`)"
      )
      .optional()
      .void

  /** `uses [*] [, entry]*` on a parameter, each entry an ability reference. */
  private val usesClause: Parser[Sourced[Token], UsesClause] =
    keyword("uses") *> symbol("*").optional().flatMap {
      case Some(_) => (symbol(",") *> usesEntry).anyTimes().map(UsesClause(true, _))
      case None    => usesEntry.atLeastOnceSeparatedBy(symbol(",")).map(UsesClause(false, _))
    }

  private type Entry = UnresolvedAbilityConstraint[Sourced[Expression]]

  private val usesEntry: Parser[Sourced[Token], Entry] =
    component[UnresolvedAbilityConstraint[Sourced[Expression]]] <* slotWith

  /** A parsed `uses` clause: whether it is open (`*`), and its entries. */
  private case class UsesClause(open: Boolean, entries: Seq[Entry]) {

    /** The slot's type in today's spelling: the row on the codomain of an arrow type, on the type itself otherwise. */
    def applyTo(typed: Sourced[Expression]): Sourced[Expression] = rowed(typed)

    /** The row goes on the final codomain of the run as written, never inside it: `String => (String => Unit)` is code
      * taking one argument and handing back a function value, so it is `String => {} (String => Unit)`. A nested run
      * can only be a parenthesized one, since an unparenthesized `A => B => C` is a single flat run.
      */
    private def rowed(typed: Sourced[Expression]): Sourced[Expression] = typed.value match {
      case FlatExpression(parts) if parts.exists(isArrow) => typed.as(FlatExpression(parts.init :+ onRow(parts.last)))
      case _                                              => onRow(typed)
    }

    private def onRow(typed: Sourced[Expression]): Sourced[Expression] =
      typed.as(EffectfulType(entries, typed))
  }

  private def isArrow(part: Sourced[Expression]): Boolean = part.value match {
    case FunctionApplication(None, name, None, Seq()) => name.value === "=>"
    case _                                            => false
  }
}
