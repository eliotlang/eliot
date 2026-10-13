package com.vanillasource.eliot.eliotc.token

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.pos.{Position, PositionRange}
import com.vanillasource.eliot.eliotc.source.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import parsley.errors.combinator.*
import parsley.position.pos
import parsley.token.Lexer
import parsley.token.descriptions.*
import parsley.token.descriptions.numeric.NumericDesc
import parsley.token.descriptions.text.{EscapeDesc, NumberOfDigits, NumericEscape, TextDesc}
import parsley.token.predicate.Basic
import parsley.{Parsley, character}

class TokenParser(sourced: Sourced[?]) {

  /** Escape sequences allowed inside string literals: the usual one-character control escapes (including `\e` for the
    * ESC / `0x1B` character), the self-escaping delimiters `\\`, `\"`, `\'`, and a Java-style four-hex-digit unicode
    * escape `\uXXXX`. Astral-plane characters are written as a UTF-16 surrogate pair of `\u` escapes, matching Java.
    */
  private val textDesc = TextDesc.plain.copy(
    escapeSequences = EscapeDesc(
      escBegin = '\\',
      literals = Set('\\', '"', '\''),
      singleMap = Map(
        '0' -> 0x00, // null
        'a' -> 0x07, // alert / bell
        'b' -> 0x08, // backspace
        't' -> 0x09, // horizontal tab
        'n' -> 0x0a, // line feed
        'v' -> 0x0b, // vertical tab
        'f' -> 0x0c, // form feed
        'r' -> 0x0d, // carriage return
        'e' -> 0x1b  // escape
      ),
      multiMap = Map.empty,
      decimalEscape = NumericEscape.Illegal,
      hexadecimalEscape =
        NumericEscape.Supported(prefix = Some('u'), numDigits = NumberOfDigits.Exactly(4), maxValue = 0xffff),
      octalEscape = NumericEscape.Illegal,
      binaryEscape = NumericEscape.Illegal,
      emptyEscape = None,
      gapsSupported = false
    )
  )

  private val lexer = new Lexer(
    LexicalDesc(
      NameDesc(
        identifierStart = Basic(_.isLetter),
        identifierLetter = Basic(c => c.isLetterOrDigit),
        operatorStart = Basic("!#$%&*+./<=>?@\\^|-~;".contains(_)),
        operatorLetter = Basic("!#$%&*+./<=>?@\\^|-~;".contains(_))
      ),
      SymbolDesc(
        hardKeywords = Set(
          "import",
          "data",
          "def",
          "ability",
          "implement",
          "match",
          "case",
          "type",
          "infix",
          "prefix",
          "postfix",
          "val",
          "private",
          "where",
          "effect",
          "with",
          "uses"
        ),
        hardOperators = Set("(", ")", "[", "]", "->", "_", "::", ":"),
        caseSensitive = true
      ),
      NumericDesc.plain,
      textDesc,
      SpaceDesc(
        commentStart = "/*",
        commentEnd = "*/",
        commentLine = "//",
        commentLineAllowsEOF = true,
        nestedComments = false,
        space = Basic(_.isWhitespace),
        whitespaceIsContextDependent = false
      )
    )
  )

  lazy val fullParser: Parsley[List[Sourced[Token]]] = lexer.fully(Parsley.many(tokenParser))

  private lazy val tokenParser: Parsley[Sourced[Token]] =
    identifier <|> standaloneSymbolParser <|> symbolParser <|> keyword <|> integerLiteral <|> stringLiteral

  private lazy val symbolParser: Parsley[Sourced[Token.Symbol]] = sourcedLexeme(
    lexer.nonlexeme.names.userDefinedOperator.map(Token.Symbol.apply)
  )

  private lazy val standaloneSymbolParser: Parsley[Sourced[Token.Symbol]] = sourcedLexeme(
    Parsley.atomic(character.strings("(", ")", "[", "]", "{", "}", ",", "->", "_", "::", ":")).map(Token.Symbol.apply)
  ).label("special operator")

  private lazy val keyword: Parsley[Sourced[Token.Keyword]] = sourcedLexeme(
    character
      .strings(
        "import",
        "data",
        "def",
        "ability",
        "implement",
        "match",
        "case",
        "type",
        "infix",
        "prefix",
        "postfix",
        "val",
        "private",
        "where",
        "effect",
        "with",
        "uses"
      )
      .map(Token.Keyword.apply)
  ).label("keyword")

  /** An integer literal: digits only, always non-negative. There is no negative-literal rule: a `-` is always an
    * ordinary operator, so `-` glued to digits (`a-1`, `1-2`) tokenizes as subtraction, exactly as spaced `a - 1` does.
    * A negative *value* is therefore produced by an operator (`0 - 128`, `3 - 10`), never by literal syntax.
    */
  private lazy val integerLiteral: Parsley[Sourced[Token.IntegerLiteral]] = sourcedLexeme(
    lexer.nonlexeme.integer.decimal.map(value => Token.IntegerLiteral(value.toString))
  ).label("integer literal")

  private lazy val stringLiteral: Parsley[Sourced[Token.StringLiteral]] = sourcedLexeme(
    lexer.nonlexeme.string.fullUtf16.map(Token.StringLiteral(_))
  ).label("string literal")

  private lazy val identifier: Parsley[Sourced[Token.Identifier]] = sourcedLexeme(
    lexer.nonlexeme.names.identifier.map(Token.Identifier.apply)
  )

  private lazy val position: Parsley[Position] = pos.map(Position.apply.tupled)

  private def sourcedLexeme[T](parser: Parsley[T]): Parsley[Sourced[T]] = for {
    from <- position
    t    <- markAsToken(parser)
    to   <- position
    _    <- lexer.space.whiteSpace
  } yield sourced.as(t).reFocus(PositionRange(from, to))
}
