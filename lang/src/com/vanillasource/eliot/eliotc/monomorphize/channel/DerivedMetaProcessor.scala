package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.monomorphize.channel.MetaInterpreter.Value
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValueRenderer, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Answers one call to the meta interpretation ([[DerivedMeta]], `docs/meta.md` §17.3) by interpreting it
  * ([[MetaInterpreter.answerOf]]): a callee stating its transfer reduces its companion, any other interprets its body.
  * Every call that answer makes is another [[DerivedMeta]], read for one step of this one's budget, so the budget bounds
  * what one definition does in one context, never what a program does.
  *
  * **Running out of the budget is reported here**, at the callee's definition, once per fact (decision I, §5.3; Q9 of
  * §17.3), naming the argument metas it ran out at; the fact is then declined, and everything reading it declines in
  * turn, reporting nothing more, so a build that ran out leaves no jar. Reporting it at each call node instead would
  * name one cause at every caller above it.
  *
  * {{{
  *   def long(x: Int): Int = add(add(…), add(…))   // 511 calls of `add` in its own body
  *     error at `long`: Computing the meta-information of 'long' takes more than 1024 steps of its own.
  *                      Its arguments: Int$Meta(Interval(Bounded(5), Bounded(5))).
  * }}}
  *
  * It reads its own fact type only along the call graph, which is acyclic because user code cannot recurse, as
  * [[MetaDemandsProcessor]] reads its own.
  */
class DerivedMetaProcessor extends SingleKeyTypeProcessor[DerivedMeta.Key] {

  override protected def generateFact(key: DerivedMeta.Key): CompilerIO[Unit] =
    MetaInterpreter.answerOf(key).flatMap {
      case Some(answer) =>
        registerFactIfClear(DerivedMeta(key.callee, key.typeArguments, key.resultType, key.arguments, key.store, answer))
      case None         => ranOut(key) >> abort
    }

  private def ranOut(key: DerivedMeta.Key): CompilerIO[Unit] = {
    val message     =
      s"Computing the meta-information of '${key.callee.name.name}' takes more than " +
        s"${MetaInterpreter.interpretationBudget} steps of its own."
    val description =
      Option.when(key.arguments.nonEmpty)(s"Its arguments: ${key.arguments.map(rendered).mkString(", ")}.").toSeq :+
        "The compiler computes the meta-information of each call in a fixed number of steps, each call it makes one."
    getFactIfProduced(MonomorphicValue.Key(key.callee, key.typeArguments)).flatMap {
      case Some(value) => Sourced.compilerError(value.name.as(message), description)
      case None        => registerCompilerError(CompilerError.global((message +: description).mkString(" ")))
    }
  }

  /** What the interpretation knew of one argument, as the error names it. */
  private def rendered(value: Value): String = value match {
    case Value.Closure(_, _)     => "a function"
    case Value.Frame(_)          => "a frame"
    case Value.Cell(_)           => "a cell"
    case Value.Never             => "nothing"
    case other                   => other.known.fold("not computed")(GroundValueRenderer.render)
  }
}
