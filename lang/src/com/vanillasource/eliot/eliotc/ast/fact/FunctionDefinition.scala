package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import cats.Eq
import ASTComponent.component
import Primitives.*
import com.vanillasource.eliot.eliotc.ast.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token
import Parser.*

case class FunctionDefinition(
    name: Sourced[QualifiedName],
    genericParameters: Seq[GenericParameter],
    args: Seq[ArgumentDefinition],
    typeDefinition: Sourced[Expression],
    body: Option[Sourced[Expression]], // Can be empty for abstract functions
    fixity: Fixity = Fixity.Application,
    precedence: Seq[PrecedenceDeclaration] = Seq.empty,
    visibility: Visibility = Visibility.Public,
    // The `/** ... */` documentation comment preceding this definition, if any. Attached by source-position adjacency
    // in `ASTParser` and consumed only by the apidoc tooling; never read by the compiler proper, never part of
    // `signatureEquality` (layers may document the same name differently), and dropped at the core boundary.
    doc: Option[Sourced[String]] = None,
    // The named meta *slots* of a type declared with a `{slots}` brace (bounds-as-refinements §4.2), e.g.
    // `type Int {range: Interval[BigInteger, BigInteger]}` carries one slot `range: Interval[…]`. Each slot is
    // name+domain, so an `ArgumentDefinition` is the right shape. Populated only by `TypeAliasDefinition`; empty for
    // every ordinary `def`/alias. Consumed at the core boundary by `MetaConstructorDesugarer`, which emits the type's
    // `^Meta` constructor from it, then dropped (never part of `NamedValue`/`signatureEquality`).
    metaSlots: Seq[ArgumentDefinition] = Seq.empty,
    // The return-position **transfer brace** of a def (bounds-as-refinements §4.2), e.g. the `{a.range + b.range}` in
    // `def add(a: Int, b: Int): Int {a.range + b.range}` — the slot-value expression(s) (one per slot, comma-separated)
    // that build the result's meta value. Populated only when a def carries a return brace; empty otherwise. Consumed
    // at the core boundary by `MetaTransferDesugarer`, which emits the def's `^Meta` transfer companion from it, then
    // dropped (never part of `NamedValue`/`signatureEquality`).
    returnMeta: Seq[Sourced[Expression]] = Seq.empty,
    // The `where <predicate>` **refinement precondition** of a def (bounds-as-refinements §4.3), e.g.
    // `def useByte(x: Int): Int where withinByte(range(x))` — a `Bool` predicate over the parameters' *meta* values
    // (each parameter `p: T` is seen as its meta `p: T$Meta`, so `range(p)` projects the tracked range). Populated only
    // when a def carries a `where`; empty otherwise. Consumed at the core boundary by `MetaWhereDesugarer`, which emits
    // the def's `^Where` companion (a `T$Meta… -> Bool` function in `Qualifier.Meta`) the refinement channel evaluates
    // at each call site to demand the precondition; then dropped (never part of `NamedValue`/`signatureEquality`).
    whereClause: Option[Sourced[Expression]] = None,
    // The effects-as-channel **declared effect row** (docs/effects-as-channel.md §4, Phase 1): the open effect rows of
    // this signature, position-attributed, captured by `EffectSugarDesugarer.desugar` before the carrier rewrite erases
    // them. Unlike the fields above it is *not* dropped at the core boundary — it forwards down the value fact chain to
    // the monomorphize phase (see [[EffectRow]]). Dark/inert in Phase 1 and never part of `signatureEquality`.
    effectRow: EffectRow[UnresolvedAbilityConstraint[Sourced[Expression]]] = EffectRow.empty,
    // The entries of an `implement` clause's row it only **carries** for its siblings (`ImplementationRows.carrying`),
    // by `UnresolvedAbilityConstraint.key`. Each still gets its binder, so every clause takes the marker's type arguments
    // positionally, but no constraint: the constraint on a binding binder is what says the definition receives that
    // entry's environment (effects D20c option 5), and a clause receives none for an effect it does not perform.
    // Dropped at the core boundary with the row itself; never part of `signatureEquality`.
    carriedEffects: Set[String] = Set.empty
)

object FunctionDefinition {
  val signatureEquality: Eq[FunctionDefinition] = (x: FunctionDefinition, y: FunctionDefinition) =>
    x.genericParameters.length === y.genericParameters.length &&
      x.args.length === y.args.length &&
      (x.genericParameters zip y.genericParameters).forall(GenericParameter.signatureEquality.eqv) &&
      (x.args zip y.args).forall(ArgumentDefinition.signatureEquality.eqv) &&
      x.typeDefinition.value.render === y.typeDefinition.value.render

  extension (self: FunctionDefinition)
    def render: String =
      s"${self.name.show}(${self.args.map(_.render).mkString(", ")}): ${self.body.show}"

  private val targetName: Parser[Sourced[Token], Sourced[String]] =
    sourced(
      (acceptIfAll(isIdentifier, isLowerCase)("operator name") or acceptIf(isUserOperator, "operator name"))
        .map(_.value.content)
    )

  private val precedenceTargets: Parser[Sourced[Token], Seq[Sourced[String]]] =
    bracketedCommaSeparatedItems("(", targetName, ")") or targetName.map(Seq(_))

  private val precedenceRelation: Parser[Sourced[Token], PrecedenceDeclaration.Relation] =
    identifierWith("above").as(PrecedenceDeclaration.Relation.Above: PrecedenceDeclaration.Relation) or
      identifierWith("below").as(PrecedenceDeclaration.Relation.Below: PrecedenceDeclaration.Relation) or
      identifierWith("at").as(PrecedenceDeclaration.Relation.At: PrecedenceDeclaration.Relation)

  private val precedenceDeclaration: Parser[Sourced[Token], PrecedenceDeclaration] = for {
    relation <- precedenceRelation
    targets  <- precedenceTargets
  } yield PrecedenceDeclaration(relation, targets)

  private val infixAssociativity: Parser[Sourced[Token], Fixity.Associativity] =
    (identifierWith("left").as(Fixity.Associativity.Left: Fixity.Associativity) or
      identifierWith("right").as(Fixity.Associativity.Right: Fixity.Associativity) or
      identifierWith("none").as(Fixity.Associativity.None: Fixity.Associativity))
      .optional()
      .map(_.getOrElse(Fixity.Associativity.Left))

  // `infix`/`prefix`/`postfix` (and likewise `private`/`opaque`, see `modifierPrefix`) are hard keywords (not
  // identifiers): besides marking fixity/visibility they are the only tokens that begin a (modified) definition, so
  // making them keywords lets a preceding greedily-parsed expression — a function *body*, or now a guarded *return
  // type* (effectful-signatures G2) — stop cleanly at the next definition rather than swallowing its modifier as an
  // application chain.
  private val fixityDecl: Parser[Sourced[Token], Fixity] =
    keyword("prefix").as(Fixity.Prefix: Fixity) or
      (keyword("infix") *> infixAssociativity).map(a => Fixity.Infix(a): Fixity) or
      keyword("postfix").as(Fixity.Postfix: Fixity)

  /** Parses the modifier prefix shared by `def` and `type` definitions: `[visibility] [fixity precedence*]
    * <definitionKeyword>`, returning `(visibility, fixity, precedence)`. Atomic, so it backtracks cleanly when
    * `definitionKeyword` does not follow the modifiers (letting the top-level `xor` dispatch try the next alternative).
    * Called with `"def"` for value definitions and `"type"` for type aliases — so a type alias can carry a fixity
    * exactly like a `def`, e.g. `infix right type =>[A, B] = Function[A, B]`.
    */
  def modifierPrefix(
      definitionKeyword: String
  ): Parser[Sourced[Token], (Visibility, Fixity, Seq[PrecedenceDeclaration])] = {
    val withFixity =
      (for {
        fixity <- fixityDecl
        prec   <- precedenceDeclaration.anyTimes()
        _      <- keyword(definitionKeyword)
      } yield (fixity, prec)).atomic()
    val plain      =
      keyword(definitionKeyword).map(_ => (Fixity.Application: Fixity, Seq.empty: Seq[PrecedenceDeclaration]))
    (for {
      vis            <- component[Visibility].optional().map(_.getOrElse(Visibility.Public))
      (fixity, prec) <- withFixity or plain
    } yield (vis, fixity, prec)).atomic()
  }

  given ASTComponent[FunctionDefinition] = new ASTComponent[FunctionDefinition] {
    private val functionBody =
      (symbol("=") *> sourced(component[Expression])).optional()

    private val functionName: Parser[Sourced[Token], Sourced[Token]] =
      acceptIfAll(isIdentifier, isLowerCase)("function name") or acceptIf(isUserOperator, "function name")

    /** A definition's own `uses` clause (`def greet(name: String) uses Console: Unit`, `docs/effects.md` D21): the
      * effects its caller hands it. Entries only — `*` is a parameter's, since a body has no caller's text to be open
      * to, and a `with` here would bind the definition's own row, which is rejected in either spelling.
      */
    private val usesClause: Parser[Sourced[Token], Seq[UnresolvedAbilityConstraint[Sourced[Expression]]]] =
      (keyword("uses") *>
        component[UnresolvedAbilityConstraint[Sourced[Expression]]].atLeastOnceSeparatedBy(symbol(",")))
        .optional()
        .map(_.getOrElse(Seq.empty))

    override val parser: Parser[Sourced[Token], FunctionDefinition] = for {
      (vis, fixity, prec) <- modifierPrefix("def")
      name                <- functionName
      genericParameters   <- component[Seq[GenericParameter]]
      args                <- optionalArgumentListOf(ArgumentDefinition.parameter)
      uses                <- usesClause
      _                   <- symbol(":")
      returnType          <- sourced(Expression.typeRunParser)
      // The clause's entries are the row on the return type, `{Console} Unit`, so no phase past this one learns the
      // clause. A row cannot be written on the return type itself: `typeRunParser` refuses one.
      typeExpression       =
        if (uses.isEmpty) returnType else returnType.as(Expression.EffectfulType(uses, returnType))
      // The return-position transfer brace `: T {expr, …}` (bounds-as-refinements §4.2). `typeRunParser`'s type-atom
      // run checks a `{` for the retired effect-row spelling but does not match a transfer brace (its entries are
      // arbitrary expressions, not ability references, and no type atom follows the closing `}` — only `=`, `where`,
      // or the next definition can), so the brace sits unconsumed here, between the return type and the optional
      // `= body`. Absent for an ordinary def.
      returnMeta          <- optionalBracketedCommaSeparatedItems("{", sourced(component[Expression]), "}")
      // The `where <predicate>` refinement precondition (bounds-as-refinements §4.3). `where` is a hard keyword, so the
      // return-type run above stops cleanly at it (as it does at `infix`/`def`); the predicate is a `typeRunParser` run
      // exactly like an `implement` guard, stopping at the `= body` (or the next definition). Absent for an ordinary def.
      whereClause         <- (keyword("where") *> sourced(Expression.typeRunParser)).optional()
      functionBody        <- functionBody
    } yield FunctionDefinition(
      name.map(m => QualifiedName(m.content, Qualifier.Default)),
      genericParameters,
      args,
      typeExpression,
      functionBody,
      fixity,
      prec,
      vis,
      returnMeta = returnMeta,
      whereClause = whereClause
    )
  }
}
