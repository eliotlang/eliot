package com.vanillasource.eliot.eliotc.processor

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import cats.data.Chain
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.processor.CompilerIO as CompilerIOs
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

/** Base class for tests that use CompilerIO and need test fixtures for facts and compilation process.
  */
abstract class ProcessorTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  /** Helper method to run a CompilerIO computation with a test compilation process.
    *
    * @param value
    *   The CompilerIO computation to run
    * @return
    *   An IO containing either errors or the result
    */
  protected def runCompilerIO[T](value: CompilerIO[T]): IO[Either[Chain[CompilerError], T]] =
    IO.delay(CompilerIOs.runCompilerIO(new ProcessorTest.TestCompilationProcess())(value).map(_._2))
}

object ProcessorTest {

  /** Test fact implementation for testing purposes.
    */
  case class TestFact(value: String) extends CompilerFact {
    override def key(): CompilerFactKey[TestFact] = TestFactKey(value)
  }

  /** Test fact key for TestFact.
    */
  case class TestFactKey(value: String) extends CompilerFactKey[TestFact] {
    override def valueCodec: Option[FactCodec[TestFact]] = None // a test double is never persisted
  }

  /** Different fact type for testing type-specific processors.
    */
  case class DifferentFact(value: String) extends CompilerFact {
    override def key(): CompilerFactKey[DifferentFact] = DifferentKey(value)
  }

  /** Key for DifferentFact.
    */
  case class DifferentKey(value: String) extends CompilerFactKey[DifferentFact] {
    override def valueCodec: Option[FactCodec[DifferentFact]] = None // a test double is never persisted
  }

  /** Helper to create a test compiler error.
    */
  def error(msg: String): CompilerError = CompilerError(msg, Seq.empty, "", "", PositionRange.zero)

  /** Test implementation of CompilationProcess that stores facts in memory.
    */
  class TestCompilationProcess extends CompilationProcess {
    var facts: Map[CompilerFactKey[?], CompilerFact] = Map.empty

    def registerFactSync(fact: CompilerFact): Unit = {
      facts = facts.updated(fact.key(), fact)
    }

    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
        key: K,
        ancestors: List[CompilerFactKey[?]]
    ): Option[V] =
      facts.get(key).map(_.asInstanceOf[V])

    override def registerFact(value: CompilerFact): Unit =
      registerFactSync(value)
  }
}
