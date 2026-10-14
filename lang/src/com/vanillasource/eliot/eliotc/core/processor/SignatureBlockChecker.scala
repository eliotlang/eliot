package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.Expression
import com.vanillasource.eliot.eliotc.core.fact.Expression.*
import com.vanillasource.eliot.eliotc.core.fact.NamedValue
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Rejects a `{ … }` block anywhere in a value's **signature**, with a located error.
  *
  * `BlockDesugaringProcessor` lowers blocks in a value's *runtime body* and nowhere else, so a block written in a type
  * position survived un-lowered all the way to match desugaring and took the compiler down with
  * `IllegalStateException: BlockExpression should not exist after block desugaring`. The shape that reaches it in
  * practice is an effect row written where no row may go:
  *
  * {{{
  * def gathered: List[{Console} Unit] = empty
  * }}}
  *
  * A type argument is parsed by the *value* expression parser (types are values), where a leading `{` is always a
  * block — so `{Console}` is read as a block and juxtaposed with `Unit`, and the effect-row parser never sees it.
  *
  * **It is reported rather than lowered, and that is the point.** Desugaring signatures too would make this compile:
  * `{Console} Unit` would lower to the immediately-applied lambda `(_ -> Unit)(Console)`, which evaluates to `Unit`,
  * so `List[{Console} Unit]` would silently mean `List[Unit]` — an effect annotation quietly discarded. A row belongs
  * on a definition's return type or on a parameter or field type, where the desugar gives it a meaning
  * ([[EffectSugarDesugarer]]); anywhere else there is no binding site for it, and saying so is the only fail-safe
  * answer (`docs/effects.md` §5 rule 8).
  */
object SignatureBlockChecker {

  /** One sourced error per block found in the value's signature; empty for every signature that holds none. */
  def check(value: NamedValue): Seq[Sourced[String]] =
    blocksIn(value.signature).map(
      _.as(
        "A `{ … }` block may not appear in a type. An effect row is written on a definition's return type, or on a " +
          "parameter's or field's type — never inside a type argument."
      )
    )

  private def blocksIn(expr: Sourced[Expression]): Seq[Sourced[Expression]] = expr.value match {
    case BlockExpression(_)                          => Seq(expr)
    case NamedValueReference(_, _, typeArgs)         => typeArgs.flatMap(blocksIn)
    case FunctionApplication(target, argument)       => blocksIn(target) ++ blocksIn(argument)
    case FunctionLiteral(_, parameterType, body)     => parameterType.toSeq.flatMap(blocksIn) ++ blocksIn(body)
    case FlatExpression(parts)                       => parts.flatMap(blocksIn)
    case MatchExpression(scrutinee, cases)           => blocksIn(scrutinee) ++ cases.flatMap(c => blocksIn(c.body))
    case WithBinding(subject, _, _, arguments, _)    => blocksIn(subject) ++ arguments.flatMap(blocksIn)
    case IntegerLiteral(_) | StringLiteral(_)        => Seq.empty
  }
}
