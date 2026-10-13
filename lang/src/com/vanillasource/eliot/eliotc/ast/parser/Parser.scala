package com.vanillasource.eliot.eliotc.ast.parser

import cats.data.StateT
import cats.syntax.all.*
import cats.{Eq, Show}
import com.vanillasource.eliot.eliotc.ast.parser.ParserResult.Consume.*

/** A parser combinator that consumes items of type [[I]] and produces results of some type [[O]].
  *
  * Foundational parsers are:
  *   - [[acceptIf]], which consumed a single token if the supplied predicate holds
  *
  * Foundational combinators are:
  *   - [[lookahead]], which is successful if the given parser can be matched, but does not consume any input
  *   - [[atomic]], which makes the parser fail without consuming any input
  *   - [[optional]], which makes a parser return None, if it failed without consuming input
  *
  * All other parsers are based on these foundational parsers or are combinations thereof, based on the foundational
  * combinators.
  */
type Parser[I, O] = StateT[ParserResult, InputStream[I], O]

object Parser {
  extension [I, O](p: Parser[I, O]) {

    /** Parse the given input elements with this parser.
      */
    def parse(input: Seq[I]): ParserResult[O] = p.runA(InputStream.of(input))

    /** Run the parser and capture the first and last consumed input items alongside the result.
      */
    def withBounds: Parser[I, (O, I, I)] = StateT { input =>
      p.run(input) match
        case ParserResult(consume, error, None)                      =>
          ParserResult(consume, error, None)
        case ParserResult(consume, error, Some((nextInput, result))) =>
          val firstItem = input.headOption.get
          val lastItem  = input.lastBefore(nextInput.pos)
          ParserResult(consume, error, Some((nextInput, (result, firstItem, lastItem))))
    }

    /** Fully read the input with the given parser. This means after the parser completes, the input should be empty.
      */
    def fully(): Parser[I, O] = p <* endOfInput()

    /** Match the given parser optionally. The parser returns None, if the given parser can be skipped, i.e., it
      * consumes no input.
      */
    def optional(): Parser[I, Option[O]] = StateT { input =>
      p.run(input) match
        case ParserResult(NotConsumed, expected, None) =>
          ParserResult(NotConsumed, expected, Some((input, None)))
        case other                                     => other.map((input, a) => (input, Some(a)))
    }

    /** Match the given parser zero or more times. */
    def anyTimes(): Parser[I, Seq[O]] = anyTimesWhile(().pure)

    /** Match this parser any times while the given parser matches.
      * @param n
      *   The parser that should match before applying this parser.
      */
    private def anyTimesWhile(n: Parser[I, ?]): Parser[I, Seq[O]] =
      Seq.empty[O].tailRecM { acc =>
        (n.lookahead() *> p).optional().map {
          case Some(value) => Left(acc.appended(value))
          case None        => Right(acc)
        }
      }

    def atLeastOnce(): Parser[I, Seq[O]] = for {
      head <- p
      tail <- anyTimes()
    } yield head +: tail

    /** Accept this parser at least once separated by another parser. If this parser only matches once, the separator is
      * not used. Otherwise, it is.
      */
    def atLeastOnceSeparatedBy(sep: Parser[I, ?]): Parser[I, Seq[O]] = for {
      head <- p
      tail <- (sep *> p).anyTimes()
    } yield head +: tail

    /** Accept this parser when between the given parsers.
      */
    def between(begin: Parser[I, ?], end: Parser[I, ?]): Parser[I, O] = for {
      _      <- begin
      result <- p
      _      <- end
    } yield result

    /** Parses if this parser is followed by the given parser. No input is consumed on the given parser.
      */
    def followedBy(n: Parser[I, ?]): Parser[I, O] = p <* n.lookahead()

    /** Make the whole parser a single transaction. Which means that if it fails, it will always fail without consuming
      * any input.
      */
    def atomic(): Parser[I, O] = StateT { input =>
      p.run(input) match
        case ParserResult(_, _, None) =>
          ParserResult(NotConsumed, ParserError.noError, None)
        case other                    => other
    }

    /** Recognise a match of this parser only to refuse it: where this parser matches, fail *after consuming*, at the
      * position the match starts, with `expected` as the one thing expected there; where it does not match, fail without
      * consuming, as [[atomic]] would. For a retired spelling that must be reported as itself rather than as whatever
      * the alternatives after it happen to expect — `expected` names what to write instead.
      */
    def refusedAs[R](expected: String): Parser[I, R] = StateT { input =>
      p.run(input) match
        case ParserResult(_, _, Some(_)) => ParserResult(Consumed, ParserError(input.pos, Set(expected)), None)
        case _                           => ParserResult(NotConsumed, ParserError.noError, None)
    }

    /** Skip to this parser.
      */
    def skipTo(): Parser[I, O] =
      (p.lookahead().map(Some.apply) or any().as(None)).iterateUntil(_.isDefined).map(_.get)

    /** Will match if this parser matches the input, but will not consume any input regardless of success or failure.
      */
    private def lookahead(): Parser[I, O] = StateT { input =>
      p.run(input).copy(consume = NotConsumed).map((_, o) => (input, o))
    }

    /** Returns the result of the first parser if it succeeds, or the second one if the first one fails without
      * consuming any input.
      */
    infix def or(p2: Parser[I, O]): Parser[I, O] =
      p.optional().flatMap {
        case Some(a) => a.pure
        case None    => p2
      }

    /** Parses either this or the given parser if this fails. This is like "or", but the two parsers may differ in type,
      * therefore, a pair is returned.
      */
    infix def xor[P](p2: Parser[I, P]): Parser[I, Either[O, P]] = p.map(Left(_)) or p2.map(Right(_))

    /** Try this parser, and if it fails after consuming input, recover by skipping to the next item matching the given
      * predicate. The error is captured in the result rather than causing the parser to fail.
      *
      *   - Success: Right(value), input consumed normally
      *   - Fail without consuming: parser fails without consuming (propagated to caller)
      *   - Fail after consuming: Left(error), input skipped to next span boundary
      */
    def recovering(spanStart: I => Boolean): Parser[I, Either[ParserError, O]] = StateT { input =>
      p.run(input) match
        case ParserResult(consume, error, Some((next, value))) =>
          ParserResult(consume, error, Some((next, Right(value))))
        case ParserResult(NotConsumed, error, None)            =>
          ParserResult(NotConsumed, error, None)
        case ParserResult(Consumed, error, None)               =>
          ParserResult(Consumed, ParserError.noError, Some((input.tail.dropWhile(i => !spanStart(i)), Left(error))))
    }

    /** Repeatedly apply this parser with error recovery. When the parser fails after consuming input, the error is
      * recorded and input is skipped to the next item matching the given predicate.
      */
    def recoveringAnyTimes(spanStart: I => Boolean): Parser[I, (Seq[ParserError], Seq[O])] =
      p.recovering(spanStart).anyTimes().map { results =>
        (results.collect { case Left(e) => e }, results.flatMap(_.toOption))
      }

    def recoveringAtLeastOnce(spanStart: I => Boolean): Parser[I, (Seq[ParserError], Seq[O])] =
      p.recovering(spanStart).atLeastOnce().map { results =>
        (results.collect { case Left(e) => e }, results.flatMap(_.toOption))
      }
  }

  /** Accept if the given predicate holds.
    */
  def acceptIf[I](predicate: I => Boolean, expected: String = ""): Parser[I, I] = StateT { input =>
    input.headOption match {
      case Some(nextI) if predicate(nextI) =>
        ParserResult(Consumed, ParserError.noError, Some((input.tail, nextI)))
      case _                               =>
        ParserResult(
          NotConsumed,
          ParserError(input.pos, if (expected.isBlank) Set.empty else Set(expected)),
          None
        )
    }
  }

  /** A parser that will consume exactly the given item, or fail without consuming input.
    */
  def literal[I](i: I)(using Eq[I], Show[I]): Parser[I, I] = acceptIf(_ === i, i.show)

  /** A parser that fails with the given "expected" message and does not consume any input.
    */
  def error[I](expected: String): Parser[I, Unit] = acceptIf[I](_ => false, expected).as(())

  /** A parser that matches the end of input. This does not consume input.
    */
  def endOfInput[I](): Parser[I, Unit] =
    acceptIf[I](_ => true)
      .optional()
      .lookahead()
      .flatMap {
        case Some(_) => error("end of input")
        case None    => ().pure
      }
      .void

  /** Match any input item. This will always succeed, except if there is no more input.
    */
  def any[I](): Parser[I, I] = acceptIf(_ => true, "input")

  /** Accept a single token if all the given predicates hold.
    */
  def acceptIfAll[I](predicates: (I => Boolean)*)(expected: String): Parser[I, I] =
    acceptIf(i => predicates.forall(_.apply(i)), expected)
}
