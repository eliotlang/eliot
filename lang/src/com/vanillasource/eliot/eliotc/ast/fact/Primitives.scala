package com.vanillasource.eliot.eliotc.ast.fact

import cats.data.StateT
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.parser.{InputStream, Parser, ParserResult}
import com.vanillasource.eliot.eliotc.pos.{Position, PositionRange}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token
import com.vanillasource.eliot.eliotc.token.Token.{Identifier, Keyword, Symbol}
import Parser.*

object Primitives {

  /** Peek the 1-based source line of the next token without consuming any input, or `None` at end of input. Used by the
    * block parser to keep an atom run on one source line (a line is the maximal atom run whose successor starts on the
    * same line the previous atom ended).
    */
  val peekTokenLine: Parser[Sourced[Token], Option[Int]] =
    StateT.inspect[ParserResult, InputStream[Sourced[Token]], Option[Int]](_.headOption.map(_.range.from.line))

  /** Peek the start [[Position]] of the next token without consuming any input, or `None` at end of input. Used to
    * detect token *adjacency* (no intervening whitespace): the next token is adjacent to a preceding token iff its
    * start equals that token's end. This is what lets the return-type parser tell a value application `f(x)` apart from
    * an infix operator followed by a parenthesized operand `f (x)` (effectful-signatures G2 — see [[Expression]]).
    */
  val peekTokenStart: Parser[Sourced[Token], Option[Position]] =
    StateT.inspect[ParserResult, InputStream[Sourced[Token]], Option[Position]](_.headOption.map(_.range.from))

  /** Parse the maximal run of `atom`s that stay on one source line: the first atom is always parsed, then each
    * subsequent atom is parsed only while it starts on the same line the previous atom ended (so a multi-line atom like
    * `( … )` keeps the run going up to its closing line — the "force-join"). Stops without consuming as soon as the
    * next token is on a later line, or is not a valid `atom` start (e.g. the block's closing `}`). This is the only
    * line-awareness in the block parser; the over-separated lines it yields are re-joined later by fixity.
    */
  def lineBoundedAtoms[A](atom: Parser[Sourced[Token], Sourced[A]]): Parser[Sourced[Token], Seq[Sourced[A]]] = {
    val empty: Parser[Sourced[Token], Seq[Sourced[A]]]                  =
      StateT.pure[ParserResult, InputStream[Sourced[Token]], Seq[Sourced[A]]](Seq.empty)
    def more(prevEndLine: Int): Parser[Sourced[Token], Seq[Sourced[A]]] =
      peekTokenLine.flatMap {
        case Some(line) if line == prevEndLine =>
          atom.optional().flatMap {
            case Some(a) => more(a.range.to.line).map(a +: _)
            case None    => empty
          }
        case _                                 => empty
      }
    atom.flatMap(first => more(first.range.to.line).map(first +: _))
  }

  def optionalArgumentListOf[A](item: Parser[Sourced[Token], A]): Parser[Sourced[Token], Seq[A]] =
    optionalBracketedCommaSeparatedItems("(", item, ")")

  def bracketedCommaSeparatedItems[A](
      bracketStartSymbol: String,
      item: Parser[Sourced[Token], A],
      bracketEndSymbol: String
  ): Parser[Sourced[Token], Seq[A]] =
    item
      .atLeastOnceSeparatedBy(symbol(","))
      .between(symbol(bracketStartSymbol), symbol(bracketEndSymbol))

  def optionalBracketedCommaSeparatedItems[A](
      bracketStartSymbol: String,
      item: Parser[Sourced[Token], A],
      bracketEndSymbol: String
  ): Parser[Sourced[Token], Seq[A]] =
    item
      .atLeastOnceSeparatedBy(symbol(","))
      .between(symbol(bracketStartSymbol), symbol(bracketEndSymbol))
      .optional()
      .map(_.getOrElse(Seq.empty))

  /** Like [[optionalBracketedCommaSeparatedItems]], but tracks whether the brackets were present and permits an *empty*
    * bracket pair. Returns `None` when no brackets were written, `Some(Seq())` for an empty `[]`, and `Some(items)` for
    * `[items]`. Used for type arguments, where an explicit empty `[]` carries meaning (it forces the Type namespace).
    */
  def presenceTrackingBracketedCommaSeparatedItems[A](
      bracketStartSymbol: String,
      item: Parser[Sourced[Token], A],
      bracketEndSymbol: String
  ): Parser[Sourced[Token], Option[Seq[A]]] =
    item
      .atLeastOnceSeparatedBy(symbol(","))
      .optional()
      .map(_.getOrElse(Seq.empty))
      .between(symbol(bracketStartSymbol), symbol(bracketEndSymbol))
      .optional()

  def sourced[A](p: Parser[Sourced[Token], A]): Parser[Sourced[Token], Sourced[A]] =
    p.withBounds.map { case (result, first, last) =>
      Sourced(first.uri, PositionRange(first.range.from, last.range.to), result)
    }

  def symbol(s: String) = acceptIfAll(isSymbol, hasContent(s))(s"symbol '$s'")

  def keyword(word: String) =
    acceptIfAll(isKeyword, hasContent(word))(s"keyword '$word'")

  def hasContent(content: String)(st: Sourced[Token]) = st.value.content === content

  def isKeyword(st: Sourced[Token]): Boolean = st match {
    case Sourced(_, _, Keyword(_)) => true
    case _                         => false
  }

  /** Where error recovery may restart after a failed top-level item: any keyword but `uses`, which only ever stands
    * inside a signature (`def f(body uses *: A) uses Console: A`) and so is never where the next item begins.
    */
  def isItemBoundary(st: Sourced[Token]): Boolean = isKeyword(st) && !hasContent("uses")(st)

  def isUpperCase(st: Sourced[Token]) = st.value.content.charAt(0).isUpper

  def isLowerCase(st: Sourced[Token]) = st.value.content.charAt(0).isLower

  def isIdentifier(st: Sourced[Token]): Boolean = st match {
    case Sourced(_, _, Identifier(_)) => true
    case _                            => false
  }

  def isIntegerLiteral(st: Sourced[Token]): Boolean = st match {
    case Sourced(_, _, Token.IntegerLiteral(_)) => true
    case _                                      => false
  }

  def isStringLiteral(st: Sourced[Token]): Boolean = st match {
    case Sourced(_, _, Token.StringLiteral(_)) => true
    case _                                     => false
  }

  def isSymbol(st: Sourced[Token]): Boolean = st match {
    case Sourced(_, _, Symbol(_)) => true
    case _                        => false
  }

  /** Symbols the ordinary user-operator surface may not claim, because a parser matches them structurally.
    *
    * `&` is deliberately **not** here: it is the ability-constraint combinator, declared in the standard library as
    * `infix left type &[A, B]` (`eliot.lang.Ability`) and resolved through the ordinary dictionary like any other
    * name (`docs/effects-syntax-userspace.md` §4 stage 1). `~` stays reserved — it is a binder marker like `:`, not
    * a name — until the constraint channel itself becomes an expression (that document's stage 4).
    */
  private val reservedSymbols = Set("(", ")", "[", "]", "{", "}", ",", "->", "_", "::", ":", "~", "=")

  def isUserOperator(st: Sourced[Token]): Boolean = isSymbol(st) && !reservedSymbols.contains(st.value.content)

  def isIdentifierOrSymbol(st: Sourced[Token]): Boolean = isIdentifier(st) || isUserOperator(st)

  def identifierWith(name: String) = acceptIfAll(isIdentifier, hasContent(name))(s"identifier '$name'")

  /** A segment of a dotted package path (`import eliot.effect.Console`, `eliot.effect.Console::printLine`): an
    * identifier, or the keyword `effect`. The `eliot.effect` package predates `effect` becoming a declaration keyword
    * (effects v6, `docs/effects.md` §9.3), and a path segment is never ambiguous — it is always followed by `.` or
    * `::` — so the keyword is admitted there and nowhere else.
    */
  def isPackageSegment(st: Sourced[Token]): Boolean = isIdentifier(st) || (isKeyword(st) && hasContent("effect")(st))
}
