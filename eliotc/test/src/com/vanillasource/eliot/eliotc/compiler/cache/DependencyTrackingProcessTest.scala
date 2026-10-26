package com.vanillasource.eliot.eliotc.compiler.cache

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class DependencyTrackingProcessTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import DependencyTrackingProcessTest.*

  private val owner = Key("owner")

  "DependencyTrackingProcess" should "return the underlying fact for a hit" in {
    tracked(Map(Key("a") -> Fact("a"))) { ctx =>
      ctx.process.getFact(Key("a"))
    }.asserting(_ shouldBe Some(Fact("a")))
  }

  it should "record a read key as the owner's dependency" in {
    tracked(Map(Key("a") -> Fact("a"))) { ctx =>
      ctx.process.getFact(Key("a"))
      ctx.deps.toSet
    }.asserting(_ shouldBe Set(Key("a")))
  }

  it should "record the dependency even when the read is a miss" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(Key("missing"))
      ctx.deps.toSet
    }.asserting(_ shouldBe Set(Key("missing")))
  }

  it should "accumulate every read key under the owner" in {
    tracked(Map(Key("a") -> Fact("a"), Key("b") -> Fact("b"))) { ctx =>
      ctx.process.getFact(Key("a"))
      ctx.process.getFact(Key("b"))
      ctx.deps.toSet
    }.asserting(_ shouldBe Set(Key("a"), Key("b")))
  }

  it should "delegate registerFact to the underlying process without recording a dependency" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("x"))
      (ctx.deps.toSet, ctx.registered.toList)
    }.asserting(_ shouldBe (Set.empty, List(Fact("x"))))
  }

  it should "expose its own key prepended to the ancestor chain as the active fact keys" in {
    tracked(Map.empty, ancestors = List(Key("ancestor"))) { ctx =>
      ctx.process.activeFactKeys
    }.asserting(_ shouldBe List(owner, Key("ancestor")))
  }

  it should "refuse a read of a key already on the request chain with None" in {
    tracked(Map(owner -> Fact("owner")), ancestors = List(Key("ancestor"))) { ctx =>
      ctx.process.getFact(Key("ancestor"))
    }.asserting(_ shouldBe None)
  }

  it should "record a cyclic fact demand error when refusing an on-chain read" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(owner)
      ctx.process.errors.toList.map(_.message)
    }.asserting(_ shouldBe List(s"Cyclic fact demand: $owner <- $owner"))
  }

  it should "not record the refused cyclic read as a dependency" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(owner)
      ctx.deps.toSet
    }.asserting(_ shouldBe Set.empty)
  }

  it should "attribute a pushed fact to the generating key" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("pushed"))
      ctx.producedDuring.toMap
    }.asserting(_ shouldBe Map(Key("pushed") -> owner))
  }

  it should "not attribute a fact registered for the generated key itself" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("owner"))
      ctx.producedDuring.toMap
    }.asserting(_ shouldBe Map.empty)
  }

  it should "note a missing read in sawMissing" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(Key("missing"))
      ctx.process.sawMissing
    }.asserting(_ shouldBe true)
  }

  it should "note a refused cyclic read in sawMissing" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(owner)
      ctx.process.sawMissing
    }.asserting(_ shouldBe true)
  }

  it should "not note a successful read in sawMissing" in {
    tracked(Map(Key("a") -> Fact("a"))) { ctx =>
      ctx.process.getFact(Key("a"))
      ctx.process.sawMissing
    }.asserting(_ shouldBe false)
  }

  it should "forward its own key and ancestor chain as the ancestors of reads on the underlying process" in {
    IO.delay {
      var seen: List[CompilerFactKey[?]] = Nil
      val underlying                     = new CompilationProcess {
        override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
            key: K,
            ancestors: List[CompilerFactKey[?]]
        ): Option[V] = {
          seen = ancestors
          None
        }
        override def registerFact(value: CompilerFact): Unit = ()
      }
      val process                        =
        new DependencyTrackingProcess(underlying, owner, mutable.Set.empty, mutable.Map.empty, List(Key("ancestor")))
      process.getFact(Key("a"))
      seen
    }
  }.asserting(_ shouldBe List(owner, Key("ancestor")))

  /** Build a [[DependencyTrackingProcess]] for `owner` (requested under `ancestors`) over a stub process with the given
    * facts, exposing all recording state.
    */
  private def tracked[A](facts: Map[CompilerFactKey[?], CompilerFact], ancestors: List[CompilerFactKey[?]] = Nil)(
      body: TrackedContext => A
  ): IO[A] =
    IO.delay {
      val deps           = mutable.Set.empty[CompilerFactKey[?]]
      val producedDuring = mutable.Map.empty[CompilerFactKey[?], CompilerFactKey[?]]
      val registered     = mutable.ListBuffer.empty[CompilerFact]
      val underlying     = new CompilationProcess {
        override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
            key: K,
            ancestors: List[CompilerFactKey[?]]
        ): Option[V]                                         =
          facts.get(key).map(_.asInstanceOf[V])
        override def registerFact(value: CompilerFact): Unit = registered += value
      }
      val process        = new DependencyTrackingProcess(underlying, owner, deps, producedDuring, ancestors)
      body(TrackedContext(process, deps, producedDuring, registered))
    }

  private case class TrackedContext(
      process: DependencyTrackingProcess,
      deps: mutable.Set[CompilerFactKey[?]],
      producedDuring: mutable.Map[CompilerFactKey[?], CompilerFactKey[?]],
      registered: mutable.ListBuffer[CompilerFact]
  )
}

object DependencyTrackingProcessTest {
  case class Fact(name: String) extends CompilerFact {
    override def key(): CompilerFactKey[Fact] = Key(name)
  }
  case class Key(name: String) extends CompilerFactKey[Fact] {
    override def valueCodec: Option[FactCodec[Fact]] = None // a test double is never persisted
  }
}
