package com.vanillasource.eliot.eliotc.compiler.cache

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import cats.data.Chain
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

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
      ctx.process.getFact(Key("a")) >> ctx.deps.get
    }.asserting(_ shouldBe Set(Key("a")))
  }

  it should "record the dependency even when the read is a miss" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(Key("missing")) >> ctx.deps.get
    }.asserting(_ shouldBe Set(Key("missing")))
  }

  it should "accumulate every read key under the owner" in {
    tracked(Map(Key("a") -> Fact("a"), Key("b") -> Fact("b"))) { ctx =>
      ctx.process.getFact(Key("a")) >> ctx.process.getFact(Key("b")) >> ctx.deps.get
    }.asserting(_ shouldBe Set(Key("a"), Key("b")))
  }

  it should "delegate registerFact to the underlying process without recording a dependency" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("x")) >> (ctx.deps.get, ctx.registered.get).tupled
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
      ctx.process.getFact(owner) >> ctx.errors.get.map(_.toList.map(_.message))
    }.asserting(_ shouldBe List(s"Cyclic fact demand: $owner <- $owner"))
  }

  it should "not record the refused cyclic read as a dependency" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(owner) >> ctx.deps.get
    }.asserting(_ shouldBe Set.empty)
  }

  it should "attribute a pushed fact to the generating key" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("pushed")) >> ctx.producedDuring.get
    }.asserting(_ shouldBe Map(Key("pushed") -> owner))
  }

  it should "not attribute a fact registered for the generated key itself" in {
    tracked(Map.empty) { ctx =>
      ctx.process.registerFact(Fact("owner")) >> ctx.producedDuring.get
    }.asserting(_ shouldBe Map.empty)
  }

  it should "note a missing read in sawMissing" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(Key("missing")) >> ctx.sawMissing.get
    }.asserting(_ shouldBe true)
  }

  it should "note a refused cyclic read in sawMissing" in {
    tracked(Map.empty) { ctx =>
      ctx.process.getFact(owner) >> ctx.sawMissing.get
    }.asserting(_ shouldBe true)
  }

  it should "not note a successful read in sawMissing" in {
    tracked(Map(Key("a") -> Fact("a"))) { ctx =>
      ctx.process.getFact(Key("a")) >> ctx.sawMissing.get
    }.asserting(_ shouldBe false)
  }

  it should "forward its own key and ancestor chain as the ancestors of reads on the underlying process" in {
    for {
      seen          <- Ref.of[IO, List[CompilerFactKey[?]]](Nil)
      underlying     = new CompilationProcess {
                         override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
                             key: K,
                             ancestors: List[CompilerFactKey[?]]
                         ): IO[Option[V]]                                          = seen.set(ancestors).as(None)
                         override def registerFact(value: CompilerFact): IO[Unit] = IO.unit
                       }
      deps          <- Ref.of[IO, Set[CompilerFactKey[?]]](Set.empty)
      producedDuring <- Ref.of[IO, Map[CompilerFactKey[?], CompilerFactKey[?]]](Map.empty)
      errors        <- Ref.of[IO, Chain[CompilerError]](Chain.empty)
      sawMissing    <- Ref.of[IO, Boolean](false)
      process        = new DependencyTrackingProcess(underlying, owner, deps, producedDuring, errors, sawMissing, List(Key("ancestor")))
      _             <- process.getFact(Key("a"))
      result        <- seen.get
    } yield result
  }.asserting(_ shouldBe List(owner, Key("ancestor")))

  /** Build a [[DependencyTrackingProcess]] for `owner` (requested under `ancestors`) over a stub process with the given
    * facts, exposing all recording state.
    */
  private def tracked[A](facts: Map[CompilerFactKey[?], CompilerFact], ancestors: List[CompilerFactKey[?]] = Nil)(
      body: TrackedContext => IO[A]
  ): IO[A] =
    for {
      deps           <- Ref.of[IO, Set[CompilerFactKey[?]]](Set.empty)
      producedDuring <- Ref.of[IO, Map[CompilerFactKey[?], CompilerFactKey[?]]](Map.empty)
      errors         <- Ref.of[IO, Chain[CompilerError]](Chain.empty)
      registered     <- Ref.of[IO, List[CompilerFact]](List.empty)
      sawMissing     <- Ref.of[IO, Boolean](false)
      underlying      = new CompilationProcess {
                          override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
                              key: K,
                              ancestors: List[CompilerFactKey[?]]
                          ): IO[Option[V]]                                                  =
                            IO.pure(facts.get(key).map(_.asInstanceOf[V]))
                          override def registerFact(value: CompilerFact): IO[Unit] = registered.update(_ :+ value)
                        }
      process         = new DependencyTrackingProcess(underlying, owner, deps, producedDuring, errors, sawMissing, ancestors)
      result         <- body(TrackedContext(process, deps, producedDuring, errors, registered, sawMissing))
    } yield result

  private case class TrackedContext(
      process: DependencyTrackingProcess,
      deps: Ref[IO, Set[CompilerFactKey[?]]],
      producedDuring: Ref[IO, Map[CompilerFactKey[?], CompilerFactKey[?]]],
      errors: Ref[IO, Chain[CompilerError]],
      registered: Ref[IO, List[CompilerFact]],
      sawMissing: Ref[IO, Boolean]
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
