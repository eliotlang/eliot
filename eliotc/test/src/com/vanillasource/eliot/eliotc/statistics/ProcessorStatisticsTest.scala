package com.vanillasource.eliot.eliotc.statistics

import cats.Monad
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, LiftIO}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.processor.CompilerIO.{getFactOrAbort, registerFactIfClear, CompilerIO}
import com.vanillasource.eliot.eliotc.processor.ProcessorTest.{TestFact, TestFactKey}
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey, CompilerProcessor}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class ProcessorStatisticsTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  "statistics" should "not count the time a processor waits for another fact into its own time" in {
    runWith(ProcessorA(100.millis, Some(TestFactKey("b"))), ProcessorB(300.millis))
      .asserting(_("A").selfTime should be < 200.millis)
  }

  it should "count the time a processor spends working" in {
    runWith(ProcessorA(100.millis, Some(TestFactKey("b"))), ProcessorB(300.millis))
      .asserting(_("B").selfTime should be >= 300.millis)
  }

  it should "count all invocations, including for keys the processor does not handle" in {
    runWith(ProcessorA(1.millis), ProcessorB(1.millis))
      .asserting(_.view.mapValues(_.invocations).toMap shouldBe Map("A" -> 1L, "B" -> 1L))
  }

  it should "count an invocation as active only if the processor used the compilation process" in {
    runWith(ProcessorA(1.millis), ProcessorB(1.millis))
      .asserting(_.view.mapValues(_.activeInvocations).toMap shouldBe Map("A" -> 1L, "B" -> 0L))
  }

  it should "count the facts each processor produced" in {
    runWith(ProcessorA(1.millis, Some(TestFactKey("b"))), ProcessorB(1.millis))
      .asserting(_.view.mapValues(_.factsProduced).toMap shouldBe Map("A" -> 1L, "B" -> 1L))
  }

  "the report" should "account each cache phase as its own explicit line" in {
    ProcessorStatistics
      .create()
      .flatMap(_.report(1.second, Map(PhaseTimings.cacheLoad -> 200.millis, PhaseTimings.cacheSave -> 100.millis)))
      .asserting(_ should (include("(loading the incremental cache)") and include("(persisting the incremental cache)")))
  }

  it should "subtract the cache phases from the engine remainder so every millisecond lands in one row" in {
    // 1000 ms total, no processors, 200 ms load + 100 ms save ⇒ the engine remainder must be exactly 700 ms.
    ProcessorStatistics
      .create()
      .flatMap(_.report(1.second, Map(PhaseTimings.cacheLoad -> 200.millis, PhaseTimings.cacheSave -> 100.millis)))
      .asserting(_ should include regex """(?m)^\s*700\s.*\(compiler engine, plugin setup and i/o\)""")
  }

  /** Runs the given processors under measurement, asking for the fact of the first one, and returns the collected
    * statistics keyed by the processor's distinguishing last character.
    *
    * The two processors are distinct classes so each gets its own counters, exactly as in a real compilation.
    *
    * The settle delay before snapshotting is what makes this deterministic: a generation completes its fact — waking
    * everyone waiting for it — from *inside* the processor, so the generating fiber still has its own tail, including
    * the measurement, to run afterwards. Statistics are therefore complete only once a run has quiesced.
    */
  private def runWith(processors: CompilerProcessor*): IO[Map[String, ProcessorStatistic]] =
    for {
      statistics <- ProcessorStatistics.create()
      wrapped     = SequentialCompilerProcessors(processors).wrapWith(p => statistics.wrap(p, p.getClass.getName))
      generator  <- IncrementalFactGenerator.create(wrapped, None)
      _          <- generator.demand.getFact(TestFactKey("a").asInstanceOf[CompilerFactKey[CompilerFact]])
      _          <- IO.sleep(100.millis)
      snapshot   <- statistics.snapshot
    } yield snapshot.map(statistic => statistic.processorName.takeRight(1) -> statistic).toMap

  /** Optionally requests another fact, then spends `duration` doing "work", then produces its own fact. */
  private abstract class SlowProcessor(value: String, duration: FiniteDuration, dependency: Option[TestFactKey])
      extends CompilerProcessor {
    override def generate(factKey: CompilerFactKey[?]): CompilerIO[Unit] =
      if (factKey == TestFactKey(value)) {
        for {
          _ <- dependency.traverse_(getFactOrAbort(_))
          _ <- LiftIO[CompilerIO].liftIO(IO.sleep(duration))
          _ <- registerFactIfClear(TestFact(value))
        } yield ()
      } else {
        Monad[CompilerIO].unit
      }
  }

  private class ProcessorA(duration: FiniteDuration, dependency: Option[TestFactKey] = None)
      extends SlowProcessor("a", duration, dependency)

  private class ProcessorB(duration: FiniteDuration, dependency: Option[TestFactKey] = None)
      extends SlowProcessor("b", duration, dependency)
}
