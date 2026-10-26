package com.vanillasource.eliot.eliotc.statistics

import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.processor.CompilerIO.{abort, recoverWithAborted, withProcess, CompilerIO}
import com.vanillasource.eliot.eliotc.processor.{CompilerFactKey, CompilerProcessor}

/** Wraps a processor to measure how often and for how long it runs. Intercepts the compilation process (see
  * [[TimedCompilationProcess]]) so the time the processor spends *waiting for facts* can be subtracted from its wall
  * time.
  *
  * The remainder is the processor's own time, and it is CPU time in practice: the compiler does no parallel work, and
  * the engine generates a fact inside the read that asks for it — so at most one processor is running at any instant.
  * There is also no double counting: a nested generation is measured by its own wrapper, and its full duration is
  * subtracted from the generation that asked for it.
  *
  * A generation that *aborts* — the sanctioned way for a processor to decline, and how an error propagates — would
  * short-circuit past the measurement, so the generation is run through `recoverWithAborted` and the abort re-raised
  * afterwards. That is exactly what the surrounding
  * [[com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors]] already does with every processor
  * it invokes, so the semantics are unchanged — and it keeps declines, which are frequent and not free, from silently
  * accumulating in the report's unaccounted remainder.
  */
final class TimedCompilerProcessor(underlying: CompilerProcessor, counters: ProcessorCounters)
    extends CompilerProcessor {

  /** Delegated, so that measuring a processor does not make it look like it handles every key. */
  override def handles(factKey: CompilerFactKey[?]): Boolean = underlying.handles(factKey)

  override def generate(factKey: CompilerFactKey[?]): CompilerIO[Unit] =
    withProcess(TimedCompilationProcess(_)) { timed =>
      Clock[CompilerIO]
        .timed(recoverWithAborted(underlying.generate(factKey))(()))
        .flatMap { case (elapsed, (_, aborted)) =>
          Sync[CompilerIO].delay(counters.record(elapsed - timed.waited, timed.wasActive, timed.factsRegistered)) >>
            abort[Unit].whenA(aborted)
        }
    }
}
