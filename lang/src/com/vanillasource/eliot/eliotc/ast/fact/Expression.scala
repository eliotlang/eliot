package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.*
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes
import com.vanillasource.eliot.eliotc.pos.Position
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

sealed trait Expression

object Expression {
  /** @param genericArguments
    *   The `[...]` type arguments. `None` means no brackets were written; `Some(Seq())` means an explicit empty `[]`
    *   (which forces the name into the Type namespace even in value position, the `[]` analogue of value-level `()`);
    *   `Some(xs)` means `[xs]`.
    */
  case class FunctionApplication(
      moduleName: Option[Sourced[String]],
      functionName: Sourced[String],
      genericArguments: Option[Seq[Sourced[Expression]]],
      arguments: Seq[Sourced[Expression]]
  ) extends Expression
  case class FunctionLiteral(parameters: Seq[LambdaParameterDefinition], body: Sourced[Expression]) extends Expression
  case class IntegerLiteral(integerLiteral: Sourced[String])                                        extends Expression
  case class StringLiteral(stringLiteral: Sourced[String])                                          extends Expression
  case class FlatExpression(parts: Seq[Sourced[Expression]])                                        extends Expression
  case class MatchExpression(scrutinee: Sourced[Expression], cases: Seq[MatchCase])                 extends Expression

  /** A `{ … }` block: a sequence of statement/binding lines ending in a result expression. Over-separated at parse time
    * (one [[BlockLine]] per source line); adjacent lines are re-joined by fixity and the whole block is lowered to a
    * tower of immediately-applied lambdas by `block.processor.BlockDesugaringProcessor`, so it never survives past
    * resolution.
    */
  case class BlockExpression(lines: Seq[BlockLine]) extends Expression

  /** One source line of a [[BlockExpression]]: an optional `val name [: type] =` binder (reusing the lambda-parameter
    * shape) and the line's flat expression. A line with no binder is a bare statement; the last line of a block (which
    * must have no binder) is the block's result.
    */
  case class BlockLine(binder: Option[LambdaParameterDefinition], expression: Sourced[Expression])

  /** An effect row on a type, `{ E1, E2, … } A`: the unordered set of `effects` a computation *performs*, on
    * implementations its caller binds. Pure, type-information-free — it never survives past the core processor, where
    * [[core.processor.EffectSugarDesugarer]] turns each entry into one **binding binder** marked
    * `Implementation[Console]` and erases the row from the type (`docs/effects.md` §3.1).
    *
    * **A user writes it in one place only**, the body of a row alias (`type Git[A] = {Process, FileSystem} A`, see
    * [[rowAliasBodyParser]]); everywhere else the spelling is a `uses` clause (`docs/effects.md` D21), which the parser
    * writes onto this node — a definition's onto its return type, a parameter's onto the slot's type or its arrow
    * codomain — so no phase past `ast` learns the clause. A row written in any other type position is a parse error
    * naming the clause ([[typeRunAtom]]).
    */
  case class EffectfulType(
      effects: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]],
      resultType: Sourced[Expression]
  ) extends Expression

  /** `subject with implementation` — effects v6's binding of a named implementation to its subject
    * (`docs/effects.md` §9.3). Infix, subject-first, at the loosest precedence and left-associative: `c with a with b`
    * is `(c with a) with b`, and `xs.sort.render with reverseOrd` applies to the whole chain. The same node serves both
    * positions — an expression (`greeting("Bob") with recordingConsole`) and a parameter's or field's type
    * (`body: {Console} Unit with mockConsole`); a `with` on a def's own return type or inside a row is a parse error by
    * construction, since neither parser admits it. `implementation` is a bare, optionally module-qualified name.
    *
    * Landed dark (§10.1 step 4): parsed here, rejected at core as not supported yet.
    */
  case class WithBinding(subject: Sourced[Expression], implementation: Sourced[Expression]) extends Expression

  case class MatchCase(pattern: Sourced[Pattern], body: Sourced[Expression])

  /** A reference to the boolean literal `true` (`eliot.lang.Bool::true`), the default ability-implementation guard
    * (ability-guards §2.3): a synthesized `implement`/`data` marker's return-type slot carries its guard, and an
    * unguarded implementation gets this `true`. It is written *module-qualified* rather than as the bare name `true`
    * so it resolves in every module without that module importing `Bool` (the resolver's `module::name` path looks the
    * value up by FQN, bypassing import scope) — mirroring real builds, where `Bool` is always on the layer path.
    */
  def trueReference(at: Sourced[?]): Sourced[Expression] =
    at.as(
      FunctionApplication(
        Some(at.as(WellKnownTypes.boolTrueFQN.moduleName.show)),
        at.as(WellKnownTypes.boolTrueFQN.name.name),
        None,
        Seq.empty
      )
    )

  /** Render this expression back to concrete source-like syntax. A plain diagnostic/apidoc renderer (never the
    * `cats.Show` typeclass, so generic `Show`-constrained code cannot pick an internal AST node up by accident).
    */
  extension (self: Expression)
    def render: String = self match {
      case IntegerLiteral(Sourced(_, _, value))                                                 => value
      case StringLiteral(Sourced(_, _, value))                                                  => value
      case FunctionApplication(Some(Sourced(_, _, module)), Sourced(_, _, fn), ga, ns @ _ :: _) =>
        val gaStr = ga.fold("")(_.map(_.value.render).mkString("[", ", ", "]"))
        s"$module::$fn$gaStr(${ns.map(_.value.render).mkString(", ")})"
      case FunctionApplication(Some(Sourced(_, _, module)), Sourced(_, _, fn), ga, _)           =>
        val gaStr = ga.fold("")(_.map(_.value.render).mkString("[", ", ", "]"))
        s"$module::$fn$gaStr"
      case FunctionApplication(None, Sourced(_, _, value), ga, ns @ _ :: _)                     =>
        val gaStr = ga.fold("")(_.map(_.value.render).mkString("[", ", ", "]"))
        s"$value$gaStr(${ns.map(_.value.render).mkString(", ")})"
      case FunctionApplication(None, Sourced(_, _, value), ga, _)                               =>
        val gaStr = ga.fold("")(_.map(_.value.render).mkString("[", ", ", "]"))
        s"$value$gaStr"
      case FunctionLiteral(parameters, body)                                                    =>
        parameters.map(_.render).mkString("(", ", ", ")") + " -> " + body.show
      case FlatExpression(parts)                                                                => parts.map(_.value.render).mkString(" ")
      case MatchExpression(scrutinee, cases)                                                    =>
        s"${scrutinee.value.render} match { ${cases.map(c => s"case ${c.pattern.value.render} -> ${c.body.value.render}").mkString(" ")} }"
      case EffectfulType(effects, resultType)                                                   =>
        s"{${effects.map(renderAbilityConstraint).mkString(", ")}} ${resultType.value.render}"
      case BlockExpression(lines)                                                               =>
        lines.map(renderBlockLine).mkString("{ ", "; ", " }")
      case WithBinding(subject, implementation)                                                 =>
        s"${subject.value.render} with ${implementation.value.render}"
    }

  private def renderBlockLine(line: BlockLine): String =
    line.binder.map(b => s"val ${b.render} = ").getOrElse("") + line.expression.value.render

  private def renderAbilityConstraint(ac: UnresolvedAbilityConstraint[Sourced[Expression]]): String =
    ac.abilityName.value +
      (if (ac.typeArgs.isEmpty) "" else ac.typeArgs.map(_.value.render).mkString("[", ", ", "]"))

  // Shared sub-parsers, all using fullParser for inner expression positions

  private lazy val moduleParser: Parser[Sourced[Token], Sourced[String]] =
    for {
      moduleParts <- acceptIf(isPackageSegment, "module name").atLeastOnceSeparatedBy(symbol("."))
    } yield {
      val moduleString = moduleParts.map(_.value.content).mkString(".")
      val outline      = Sourced.outline(moduleParts)
      outline.as(moduleString)
    }

  private lazy val integerLiteralParser: Parser[Sourced[Token], Expression] = for {
    lit <- acceptIf(isIntegerLiteral, "integer literal")
  } yield IntegerLiteral(lit.map(_.content))

  private lazy val stringLiteralParser: Parser[Sourced[Token], Expression] = for {
    lit <- acceptIf(isStringLiteral, "string literal")
  } yield StringLiteral(lit.map(_.content))

  private lazy val parenthesizedExprParser: Parser[Sourced[Token], Expression] =
    for {
      _ <- symbol("(")
      result <- fullParser
      _ <- symbol(")")
    } yield result

  private lazy val functionLiteralParser: Parser[Sourced[Token], Expression] = for {
    parameters <-
      bracketedCommaSeparatedItems("(", component[LambdaParameterDefinition], ")") or
        component[LambdaParameterDefinition].map(Seq(_))
    _          <- symbol("->")
    body       <- sourced(fullParser)
  } yield FunctionLiteral(parameters, body)

  private lazy val matchCaseParser: Parser[Sourced[Token], MatchCase] = for {
    _       <- keyword("case")
    pattern <- sourced(component[Pattern])
    _       <- symbol("->")
    body    <- sourced(fullParser)
  } yield MatchCase(pattern, body)

  private lazy val matchExpressionParser: Parser[Sourced[Token], Seq[MatchCase]] =
    matchCaseParser.atLeastOnce().between(keyword("match") *> symbol("{"), symbol("}"))

  /** Atoms for type and value positions: named references (with optional generic and *adjacent* value arguments),
    * parenthesized expressions, literals. Excludes unparenthesized lambdas and match expressions to avoid ambiguity in
    * type annotations.
    *
    * Uses the adjacency-sensitive [[adjacentCallParser]], so a value-argument list `(…)` attaches only when its `(` is
    * adjacent (no intervening whitespace) to the name: `f(x)` is the call, while `f (x)` leaves `(x)` a separate atom.
    * A non-infix `f (x)` still reduces to the application `f(x)` through the operator phase's operand currying; the
    * distinction only matters for an *infix* operator, where `a op (x)` must read as `op(a, x)` rather than `a(op(x))`
    * — this is what lets an infix operator take a parenthesized operand, e.g. `result catch (err -> fallback(err))`.
    */
  private lazy val typeAtom: Parser[Sourced[Token], Expression] =
    parenthesizedExprParser.atomic() or
      adjacentCallParser or
      integerLiteralParser or
      stringLiteralParser

  /** The `val name [: type] =` binder prefix of a block line, reusing the lambda-parameter shape for `name [: type]`.
    * Atomic so a non-`val` (statement) line backtracks cleanly to a binder-less parse.
    */
  private lazy val blockBinderParser: Parser[Sourced[Token], LambdaParameterDefinition] =
    (keyword("val") *> component[LambdaParameterDefinition] <* symbol("=")).atomic()

  /** One source line of a block: an optional binder then the line's atom run (line-bounded), optionally followed by a
    * trailing `match { … }` whose scrutinee is that atom run. Over-separation happens here: the run stops at every
    * newline, so each source line becomes one [[BlockLine]].
    */
  private lazy val blockLineParser: Parser[Sourced[Token], BlockLine] = for {
    binder     <- blockBinderParser.optional()
    atoms      <- lineBoundedAtoms(sourced(fullAtom))
    matchBlock <- matchExpressionParser.optional()
    bindings   <- withBindingsParser
  } yield {
    val flat = Sourced.outline(atoms).as(FlatExpression(atoms))
    val expression = matchBlock match {
      case Some(cases) =>
        val scrutinee = if (atoms.size == 1) atoms.head else flat
        Sourced.outline(atoms).as(MatchExpression(scrutinee, cases))
      case None        => flat
    }
    BlockLine(binder, applyWithBindings(expression, bindings))
  }

  private lazy val blockParser: Parser[Sourced[Token], Expression] =
    blockLineParser.anyTimes().between(symbol("{"), symbol("}")).map(BlockExpression.apply)

  /** Full atoms including lambdas and `{ … }` blocks. The block alternative is first and atomic: a leading `{` in value
    * position is always a block (an effect row lives only in a row alias's body — see [[rowAliasBodyParser]] — never
    * here), and a non-`{` start backtracks to the lambda/type atoms.
    */
  private lazy val fullAtom: Parser[Sourced[Token], Expression] =
    blockParser.atomic() or functionLiteralParser.atomic() or typeAtom

  /** Full expression parser including lambdas and match expressions. */
  private lazy val fullParser: Parser[Sourced[Token], Expression] =
    for {
      parts      <- sourced(fullAtom).atLeastOnce()
      matchBlock <- matchExpressionParser.optional()
      bindings   <- withBindingsParser
    } yield {
      val subject = matchBlock match {
        case Some(cases) =>
          val scrutinee = if (parts.size == 1) parts.head else Sourced.outline(parts).as(FlatExpression(parts))
          Sourced.outline(parts).as(MatchExpression(scrutinee, cases))
        case None        => Sourced.outline(parts).as(FlatExpression(parts))
      }
      applyWithBindings(subject, bindings).value
    }

  /** The trailing `with name` chain of an expression or a parameter type — zero or more, read after everything else so
    * `with` sits at the loosest precedence (see [[WithBinding]]). Each name is a bare implementation reference: an
    * optionally module-qualified lower-case identifier, never applied.
    */
  private lazy val withBindingsParser: Parser[Sourced[Token], Seq[Sourced[Expression]]] =
    (keyword("with") *> sourced(implementationReferenceParser)).anyTimes()

  private lazy val implementationReferenceParser: Parser[Sourced[Token], Expression] = for {
    module <- (moduleParser <* symbol("::")).atomic().optional()
    name   <- acceptIfAll(isIdentifier, isLowerCase)("implementation name")
  } yield FunctionApplication(module, name.map(_.content), None, Seq.empty)

  /** Fold a `with` chain onto its subject left-associatively: `c with a with b` is `(c with a) with b`. */
  def applyWithBindings(subject: Sourced[Expression], bindings: Seq[Sourced[Expression]]): Sourced[Expression] =
    bindings.foldLeft(subject) { (acc, implementation) =>
      Sourced.outline(Seq(acc, implementation)).as(WithBinding(acc, implementation))
    }

  /** The `with name` chain on its own, for a `uses` clause entry (`body uses *, Console with fake: Unit`). */
  def withBindings: Parser[Sourced[Token], Seq[Sourced[Expression]]] = withBindingsParser

  /** [[withBindingsParser]] for a parameter's or field's type position (`x: T with name`). */
  def typeWithBindings(typeExpression: Sourced[Expression]): Parser[Sourced[Token], Sourced[Expression]] =
    withBindingsParser.map(applyWithBindings(typeExpression, _))

  /** An effect row, `{ Eff (, Eff)* } <type atom>`: each entry an ability reference (the same shape as a `~` ability
    * constraint), covering exactly the one type atom that follows. The brace may be empty, so that `{} A` is recognised
    * too — and refused, by [[typeRunAtom]]. The `}` must be followed by a type atom, which is what keeps the
    * return-position transfer brace (`: T {range(a)}`, nothing after it) and an `implement` body out of this parser.
    */
  private lazy val rowParser: Parser[Sourced[Token], Expression] = for {
    _          <- symbol("{")
    entries    <- component[UnresolvedAbilityConstraint[Sourced[Expression]]]
                    .atLeastOnceSeparatedBy(symbol(","))
                    .optional()
                    .map(_.getOrElse(Seq.empty))
    _          <- symbol("}")
    resultType <- sourced(typeAtom)
  } yield EffectfulType(entries, resultType)

  /** What a row written in a type is refused with: the `uses` clause that replaced it (`docs/effects.md` D21). */
  private val rowInTypeExpected: String =
    "a type, with its effects in a `uses` clause before the colon " +
      "(`def f uses Console: Unit`, `body uses *, Throw[E]: A`)"

  /** What a row written in a `data` field's type is refused with. A field takes no `uses` clause either, so the remedy is
    * not a respelling: a field holds a value (`docs/effects.md` D20 rule 6).
    */
  private val rowInFieldExpected: String =
    "a value type, since a data field holds a value and not a computation " +
      "(store data describing the work, and perform it where the effects are in scope)"

  /** A named reference with an optional generic argument list `[…]` (always attached) and a value-argument list `(…)`
    * attached *only* when its `(` is adjacent to the preceding token (no intervening whitespace). The one call parser
    * for both value and type positions. Adjacency is what tells a value application `f(x)` / `if(c, T)` apart from an
    * infix operator followed by a parenthesized operand: in `X else (raise("…"))` the space after `else` keeps
    * `(raise("…"))` a separate atom, so the operator phase reads it as the operand of the infix `else` rather than as the
    * call `else(raise("…"))`. Inside the `(…)`/`[…]` the ordinary [[fullParser]] runs, so nested calls keep their usual
    * form. A non-infix `f (x)` is harmless — the operator phase re-applies the two operands into `f(x)`.
    */
  private lazy val adjacentCallParser: Parser[Sourced[Token], Expression] = for {
    prefix <- sourced(for {
                module   <- (moduleParser <* symbol("::")).atomic().optional()
                name     <- acceptIf(isIdentifierOrSymbol, "name")
                typeArgs <- presenceTrackingBracketedCommaSeparatedItems("[", sourced(fullParser), "]")
              } yield (module, name.map(_.content), typeArgs))
    args   <- valueArgsIfAdjacentTo(prefix.range.to)
  } yield {
    val (module, name, typeArgs) = prefix.value
    FunctionApplication(module, name, typeArgs, args.getOrElse(Seq.empty))
  }

  private def valueArgsIfAdjacentTo(prevEnd: Position): Parser[Sourced[Token], Option[Seq[Sourced[Expression]]]] =
    peekTokenStart.flatMap {
      case Some(from) if from === prevEnd => bracketedCommaSeparatedItems("(", sourced(fullParser), ")").optional()
      case _                              => Option.empty[Seq[Sourced[Expression]]].pure
    }

  /** Type atoms for the type positions (the single per-atom parser [[typeRunParser]] consumes a greedy *run* of these):
    * [[typeAtom]], shared with value positions, behind one refusal. A `{` cannot start a type atom, so a type-position
    * brace is either an effect row — the retired spelling, refused here as itself so the error names the `uses` clause
    * rather than whatever the next alternative expects — or a brace that is not part of the type at all (the
    * return-position transfer brace `: T {range(a) + …}`, an `implement` body after a `where` guard), which the row
    * parser does not match, so the refusal fails without consuming and leaves the `{` for the enclosing parser.
    */
  private lazy val typeRunAtom: Parser[Sourced[Token], Expression] =
    rowParser.refusedAs(rowInTypeExpected) or typeAtom

  /** The parser for **every type position** — function argument and return types, lambda-parameter annotations,
    * generic-parameter bounds and ability type-parameters, `type`-alias bodies, and `implement` patterns. It admits a
    * greedy **flat run of type atoms**, so an infix *type* operator reads naturally without parentheses: `f: A => B`
    * parses as a [[FlatExpression]] that `resolve`/`operator` lower to `=>(A, B)` (`Function[A, B]`), and an inline guard
    * return type `if(MIN > 0, A) else raise("…")` lowers to `else(if(MIN > 0, A), raise("…"))`, exactly as a body would. A
    * single atom is returned verbatim, so a plain `Int[0, 255]` / `IO[Unit]` is unchanged.
    *
    * The greedy run stops cleanly at a body's `=`, a closing/separating delimiter (`)`/`]`/`}`/`,`/`:`/`~`/`->`), or the
    * next definition, because every definition-introducing token (`def`/`type`/`implement`/…/`private`/`opaque`) is a
    * hard keyword and those delimiters are reserved symbols — none is a type-atom start (see [[Primitives.isUserOperator]]
    * and [[FunctionDefinition]]'s keyword note). So inside a `[…]`/`(…)` list it stops at the `,` separator and the
    * closing bracket, and a lambda-parameter annotation stops at the `->`. Lambdas, `match`, and `{…}` blocks are
    * deliberately excluded: they are not part of the type-atom surface, and an effect row is refused ([[typeRunAtom]]).
    */
  lazy val typeRunParser: Parser[Sourced[Token], Expression] = typeRunOf(typeRunAtom)

  /** [[typeRunParser]] for a `data` field's type (and a meta slot's, which shares the binder), refusing a row with the
    * reason a field has none rather than with a `uses` clause it cannot take.
    */
  lazy val fieldTypeRunParser: Parser[Sourced[Token], Expression] =
    typeRunOf(rowParser.refusedAs(rowInFieldExpected) or typeAtom)

  private def typeRunOf(atom: Parser[Sourced[Token], Expression]): Parser[Sourced[Token], Expression] =
    sourced(atom).atLeastOnce().map {
      case Seq(single) => single.value
      case parts       => FlatExpression(parts)
    }

  /** A `type` alias's body: an ordinary [[typeRunParser]] run, or — the one place a row is still written — a **row
    * alias**, a row over one type atom and nothing else (`type Test = {Writer[List[TestResult]]} Unit`). An alias names
    * a set of effects for a definition to receive by naming it as its return type (`docs/effects.md` §2.4); it has no
    * `uses` spelling until D20a decides one.
    */
  lazy val rowAliasBodyParser: Parser[Sourced[Token], Expression] =
    rowParser.atomic() or typeRunParser

  given ASTComponent[Expression] = new ASTComponent[Expression] {
    override def parser: Parser[Sourced[Token], Expression] = fullParser
  }
}
