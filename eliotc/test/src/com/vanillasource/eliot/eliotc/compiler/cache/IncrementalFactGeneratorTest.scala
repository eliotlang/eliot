package com.vanillasource.eliot.eliotc.compiler.cache

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey, CompilerProcessor}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

/** Exhaustive behavioral tests for the incremental, backward-pull fact generator. Each test drives one or more
  * compilation "runs" — a run is a fresh [[IncrementalFactGenerator]] seeded with the previous run's persisted cache —
  * over a small graph of synthetic facts, asserting on generation counts (how many times a processor actually ran),
  * returned values, and the persisted cache.
  */
class IncrementalFactGeneratorTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import IncrementalFactGeneratorTest.*

  "incremental generation" should "generate every fact once on a cold start" in {
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "derived")
      proc      = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      r        <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
      leafN    <- counts("leaf").get
      derivedN <- counts("derived").get
    } yield (r._1, leafN, derivedN)
    test.asserting(_ shouldBe (Some(NumberFact("derived", 20)), 1, 1))
  }

  it should "re-run leaves but accept derived facts on an unchanged rerun" in {
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "derived")
      proc      = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      run1     <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
      run2     <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("derived")))
      leafN    <- counts("leaf").get
      derivedN <- counts("derived").get
    } yield (run2._1, leafN, derivedN)
    // leaf re-runs each run (it is a starting point); derived is served from cache the second time
    test.asserting(_ shouldBe (Some(NumberFact("derived", 20)), 2, 1))
  }

  it should "regenerate a derived fact when its leaf value changes" in {
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "derived")
      proc      = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      run1     <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
      _        <- src.set(15)
      run2     <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("derived")))
      leafN    <- counts("leaf").get
      derivedN <- counts("derived").get
    } yield (run2._1, leafN, derivedN)
    test.asserting(_ shouldBe (Some(NumberFact("derived", 30)), 2, 2))
  }

  it should "cut off propagation when a changed leaf does not change a derived value" in {
    // src -> sig (= src % 10) -> compiled (= sig * 100). Changing src 5 -> 15 leaves sig unchanged (both 5).
    val test = for {
      src         <- Ref.of[IO, Int](5)
      counts      <- counters("src", "sig", "compiled")
      proc         = graph(
                       Map(
                         "src"      -> Leaf(src),
                         "sig"      -> Derived("src", _ % 10),
                         "compiled" -> Derived("sig", _ * 100)
                       ),
                       counts
                     )
      run1        <- runBuild(proc, None)(_.getFact(NumberKey("compiled")))
      _           <- src.set(15)
      run2        <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("compiled")))
      srcN        <- counts("src").get
      sigN        <- counts("sig").get
      compiledN   <- counts("compiled").get
    } yield (run2._1, srcN, sigN, compiledN)
    // src + sig recompute (2 each), but compiled is cut off (still 1) because sig's value is unchanged
    test.asserting(_ shouldBe (Some(NumberFact("compiled", 500)), 2, 2, 1))
  }

  it should "propagate through the cutoff point when the derived value does change" in {
    val test = for {
      src       <- Ref.of[IO, Int](5)
      counts    <- counters("src", "sig", "compiled")
      proc       = graph(
                     Map("src" -> Leaf(src), "sig" -> Derived("src", _ % 10), "compiled" -> Derived("sig", _ * 100)),
                     counts
                   )
      run1      <- runBuild(proc, None)(_.getFact(NumberKey("compiled")))
      _         <- src.set(6) // 6 % 10 = 6, so sig changes
      run2      <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("compiled")))
      compiledN <- counts("compiled").get
    } yield (run2._1, compiledN)
    test.asserting(_ shouldBe (Some(NumberFact("compiled", 600)), 2))
  }

  it should "compute a shared dependency only once within a run (diamond)" in {
    // a -> {b, c} -> d. 'a' must be computed exactly once despite two paths into it.
    val test = for {
      src   <- Ref.of[IO, Int](10)
      counts <- counters("a", "b", "c", "d")
      proc   = graph(
                 Map(
                   "a" -> Leaf(src),
                   "b" -> Derived("a", _ + 1),
                   "c" -> Derived("a", _ + 2),
                   "d" -> Derived2("b", "c", _ + _)
                 ),
                 counts
               )
      run   <- runBuild(proc, None)(_.getFact(NumberKey("d")))
      aN    <- counts("a").get
    } yield (run._1, aN)
    test.asserting(_ shouldBe (Some(NumberFact("d", 23)), 1)) // b=11, c=12, d=23; a computed once
  }

  it should "record direct dependencies in the built cache" in {
    val test = for {
      src   <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "derived")
      proc   = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      run   <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
    } yield run._2.entries
    test.asserting { entries =>
      entries(NumberKey("derived")) shouldBe CacheEntry(Some(NumberFact("derived", 20)), Set(NumberKey("leaf")))
    }
  }

  it should "mark a leaf with an empty dependency set in the built cache" in {
    val test = for {
      src   <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "derived")
      proc   = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      run   <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
    } yield run._2.entries(NumberKey("leaf"))
    test.asserting(_ shouldBe CacheEntry(Some(NumberFact("leaf", 10)), Set.empty))
  }

  it should "keep accepting a derived fact across many unchanged runs (deps carried forward)" in {
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "derived")
      proc      = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      run1     <- runBuild(proc, None)(_.getFact(NumberKey("derived")))
      run2     <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("derived")))
      run3     <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("derived")))
      derivedN <- counts("derived").get
    } yield derivedN
    // accepted in run2 and run3 ⇒ generated exactly once across three runs
    test.asserting(_ shouldBe 1)
  }

  it should "always regenerate a fact whose cached entry has no recorded dependencies" in {
    // A hand-built prior in which 'derived' has empty deps must be treated as a leaf (always regenerated),
    // never accepted — this is the conservative safety rule for side-effect-registered facts.
    val staleEntry = CacheEntry(Some(NumberFact("derived", 999)), Set.empty)
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "derived")
      proc      = graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), counts)
      prior     = FactCacheData(Map(NumberKey("derived") -> staleEntry))
      run      <- runBuild(proc, Some(prior))(_.getFact(NumberKey("derived")))
      derivedN <- counts("derived").get
    } yield (run._1, derivedN)
    test.asserting(_ shouldBe (Some(NumberFact("derived", 20)), 1)) // recomputed fresh, not the stale 999
  }

  it should "validate a dependent through a value-less dependency without materialising it" in {
    // top -> mid -> leaf, where 'mid' is value-less in the prior (models a dropped, non-serializable SemValue fact).
    // With the leaf unchanged, 'top' is accepted without 'mid' ever being regenerated, and 'mid' is carried forward
    // (its edges kept) so it stays drillable next run.
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "mid", "top")
      proc    = graph(Map("leaf" -> Leaf(src), "mid" -> Derived("leaf", _ + 1), "top" -> Derived("mid", _ + 1)), counts)
      prior   = Map[CompilerFactKey[?], CacheEntry](
                  NumberKey("leaf") -> CacheEntry(Some(NumberFact("leaf", 10)), Set.empty),
                  NumberKey("mid")  -> CacheEntry(None, Set(NumberKey("leaf"))),
                  NumberKey("top")  -> CacheEntry(Some(NumberFact("top", 12)), Set(NumberKey("mid")))
                )
      run    <- runBuild(proc, Some(FactCacheData(prior)))(_.getFact(NumberKey("top")))
      midN   <- counts("mid").get
      topN   <- counts("top").get
    } yield (run._1, midN, topN, run._2.entries.get(NumberKey("mid")))
    // top served from cache, mid and top never recomputed, mid carried forward as edges-only
    test.asserting(_ shouldBe (Some(NumberFact("top", 12)), 0, 0, Some(CacheEntry(None, Set(NumberKey("leaf"))))))
  }

  it should "not cache a failed fact and re-run it (re-emitting its error) on the next run" in {
    val test = for {
      counts  <- counters("fail")
      proc     = graph(Map("fail" -> Failing("boom")), counts)
      run1    <- runBuild(proc, None) { g => g.getFact(NumberKey("fail")) *> g.currentErrors() }
      run2    <- runBuild(proc, Some(run1._2)) { g => g.getFact(NumberKey("fail")) *> g.currentErrors() }
      failN   <- counts("fail").get
    } yield (run1._1.map(_.message), run2._1.map(_.message), run1._2.entries.contains(NumberKey("fail")), failN)
    test.asserting { case (errors1, errors2, cachedFailure, failN) =>
      errors1 shouldBe Seq("boom")
      errors2 shouldBe Seq("boom") // error re-surfaces on the unchanged rerun
      cachedFailure shouldBe false // failure is never persisted
      failN shouldBe 2             // processor ran on both runs
    }
  }

  it should "regenerate (and re-error) a fact whose failing dependency is not cached" in {
    val test = for {
      counts    <- counters("fail", "consumer")
      proc       = graph(Map("fail" -> Failing("boom"), "consumer" -> Derived("fail", _ + 1)), counts)
      run1      <- runBuild(proc, None)(_.getFact(NumberKey("consumer")))
      run2      <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("consumer")))
      consumerN <- counts("consumer").get
    } yield (run1._1, run2._1, run1._2.entries.keySet, consumerN)
    test.asserting { case (r1, r2, cachedKeys, consumerN) =>
      r1 shouldBe None
      r2 shouldBe None
      cachedKeys shouldBe Set.empty // neither the failure nor its dependent is cached
      consumerN shouldBe 2          // dependent re-ran on the second run
    }
  }

  it should "retain facts not reached this run (the cache accumulates, it does not replace)" in {
    // Two independent subgraphs, a/da and b/db, model two configurations (e.g. two examples) sharing one cache. A run
    // that demands only 'da' must keep b and db from the prior — untouched facts survive so switching back stays warm.
    val test = for {
      srcA  <- Ref.of[IO, Int](1)
      srcB  <- Ref.of[IO, Int](2)
      counts <- counters("a", "b", "da", "db")
      proc   = graph(
                 Map(
                   "a"  -> Leaf(srcA),
                   "b"  -> Leaf(srcB),
                   "da" -> Derived("a", _ * 10),
                   "db" -> Derived("b", _ * 10)
                 ),
                 counts
               )
      run1  <- runBuild(proc, None)(g => g.getFact(NumberKey("da")) *> g.getFact(NumberKey("db")))
      run2  <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("da"))) // only 'da' requested now
      dbN   <- counts("db").get
    } yield (run1._2.entries.keySet, run2._2.entries.keySet, dbN)
    test.asserting { case (keys1, keys2, dbN) =>
      keys1 shouldBe Set(NumberKey("a"), NumberKey("b"), NumberKey("da"), NumberKey("db"))
      keys2 shouldBe Set(NumberKey("a"), NumberKey("b"), NumberKey("da"), NumberKey("db")) // b and db retained
      dbN shouldBe 1 // db was never regenerated in run2 — it survived untouched from the prior
    }
  }

  it should "not retain a stale value for a fact that regenerated to a failure this run" in {
    // 'checked' succeeds while its leaf is non-negative and fails (errors + declines) once it goes negative. The failure
    // run must drop 'checked' entirely — never keep its earlier success — or a later run whose leaf is stable at the bad
    // value would accept the stale value and swallow the error. This is the soundness guard on accumulation.
    val test = for {
      src     <- Ref.of[IO, Int](10)
      counts  <- counters("leaf", "checked")
      proc     = graph(Map("leaf" -> Leaf(src), "checked" -> Checked("leaf")), counts)
      run1    <- runBuild(proc, None)(_.getFact(NumberKey("checked")))
      _       <- src.set(-1)
      run2    <- runBuild(proc, Some(run1._2))(g => g.getFact(NumberKey("checked")).product(g.currentErrors()))
      run3    <- runBuild(proc, Some(run2._2))(g => g.getFact(NumberKey("checked")).product(g.currentErrors()))
    } yield (run1._1, run2._1._1, run2._1._2.map(_.message), run3._1._1, run3._1._2.map(_.message))
    test.asserting { case (r1, r2, errs2, r3, errs3) =>
      r1 shouldBe Some(NumberFact("checked", 10))
      r2 shouldBe None                 // fails on the negative leaf
      errs2 shouldBe Seq("negative")
      r3 shouldBe None                 // still fails; the stale 10 was never retained
      errs3 shouldBe Seq("negative")   // and the error re-surfaces rather than being masked
    }
  }

  it should "not accept a fact against a dependency that moved while it was not looking" in {
    // Three runs, and the middle one is the trap: it demands 'm' (which moves) but never 'w', so 'w' is retained from
    // run 1 while its dependency's cache entry advances to run 2's value. Run 3 then validates 'w' against the *entry*,
    // not against what 'w' actually consumed — the two agree, and a stale 'w' is served for a program it never saw.
    // This is the mixed-generation hazard the accumulation policy opens (docs/incremental-compilation.md §26): a run
    // that updates a fact without recomputing its dependents must not leave those dependents behind as if unchanged.
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "m", "w")
      proc    = graph(Map("leaf" -> Leaf(src), "m" -> Derived("leaf", identity), "w" -> Derived("m", _ * 10)), counts)
      run1   <- runBuild(proc, None)(_.getFact(NumberKey("w")))
      _      <- src.set(20)
      run2   <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("m"))) // 'm' moves; 'w' is never demanded
      run3   <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("w")))
    } yield (run1._1, run2._1, run3._1)
    test.asserting(_ shouldBe (Some(NumberFact("w", 100)), Some(NumberFact("m", 20)), Some(NumberFact("w", 200))))
  }

  it should "keep the dependents of a value-less fact the drill proved unchanged, even when it is regenerated" in {
    // 'mid' is value-less (a SemValue fact), so a run that needs its value regenerates it. Here 'outer' is validated
    // through 'top' and 'mid' by the drill, which proves 'mid' unchanged; then 'user', new this run, demands 'mid'
    // and regenerates it. A value-less entry cannot be compared, but a pure function of inputs the same run proved
    // unchanged has not moved — so 'top' must be retained. Dropping it made every warm build rebuild what the one
    // before it dropped, alternating fast and slow forever.
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "mid", "top", "outer", "user")
      proc    = graph(
                  Map(
                    "leaf"  -> Leaf(src),
                    "mid"   -> Derived("leaf", _ + 1),
                    "top"   -> Derived("mid", _ + 1),
                    "outer" -> Derived("top", _ + 1),
                    "user"  -> Derived("mid", _ * 2)
                  ),
                  counts
                )
      prior   = Map[CompilerFactKey[?], CacheEntry](
                  NumberKey("leaf")  -> CacheEntry(Some(NumberFact("leaf", 10)), Set.empty),
                  NumberKey("mid")   -> CacheEntry(None, Set(NumberKey("leaf"))),
                  NumberKey("top")   -> CacheEntry(Some(NumberFact("top", 12)), Set(NumberKey("mid"))),
                  NumberKey("outer") -> CacheEntry(Some(NumberFact("outer", 13)), Set(NumberKey("top")))
                )
      run    <- runBuild(proc, Some(FactCacheData(prior)))(g =>
                  g.getFact(NumberKey("outer")) *> g.getFact(NumberKey("user"))
                )
      midN   <- counts("mid").get
      topN   <- counts("top").get
    } yield (run._1, midN, topN, run._2.entries.get(NumberKey("top")))
    test.asserting { case (result, midN, topN, topEntry) =>
      result shouldBe Some(NumberFact("user", 22))
      midN shouldBe 1 // regenerated, because 'user' needed its value
      topN shouldBe 0 // never recomputed
      topEntry shouldBe Some(CacheEntry(Some(NumberFact("top", 12)), Set(NumberKey("mid")))) // and still cached
    }
  }

  it should "drop the dependents of a value-less fact that regenerated because its inputs moved" in {
    // The same shape with the leaf changed: the drill cannot prove 'mid' unchanged, so its regeneration is a move and
    // 'top', never demanded this run, must not survive to be accepted against a 'mid' it never saw.
    val test = for {
      src    <- Ref.of[IO, Int](20)
      counts <- counters("leaf", "mid", "top", "user")
      proc    = graph(
                  Map(
                    "leaf" -> Leaf(src),
                    "mid"  -> Derived("leaf", _ + 1),
                    "top"  -> Derived("mid", _ + 1),
                    "user" -> Derived("mid", _ * 2)
                  ),
                  counts
                )
      prior   = Map[CompilerFactKey[?], CacheEntry](
                  NumberKey("leaf") -> CacheEntry(Some(NumberFact("leaf", 10)), Set.empty),
                  NumberKey("mid")  -> CacheEntry(None, Set(NumberKey("leaf"))),
                  NumberKey("top")  -> CacheEntry(Some(NumberFact("top", 12)), Set(NumberKey("mid")))
                )
      run2   <- runBuild(proc, Some(FactCacheData(prior)))(_.getFact(NumberKey("user")))
      run3   <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("top")))
    } yield (run2._1, run2._2.entries.contains(NumberKey("top")), run3._1)
    test.asserting(_ shouldBe (Some(NumberFact("user", 42)), false, Some(NumberFact("top", 22))))
  }

  it should "not report a diagnostic from a fact regenerated only to answer whether it changed" in {
    // The previous run's 'top' read 'old' before 'src'; this run's program has no 'old' at all — asking for it now
    // fails. Validation still walks the *prior* edges (that is how it learns 'top' moved), so 'old' is regenerated and
    // errors — but that failure is the answer "changed", not a diagnostic about the program being compiled. Reporting
    // it is what made a rename surface as "Could not find '<the old name>'." and fail an otherwise correct build.
    val test = for {
      src     <- Ref.of[IO, Int](10)
      counts  <- counters("src", "old", "top")
      before   = graph(Map("src" -> Leaf(src), "old" -> Derived("src", identity), "top" -> Derived2("old", "src", _ + _)), counts)
      after    = graph(Map("src" -> Leaf(src), "old" -> Failing("gone"), "top" -> Derived("src", _ + 1)), counts)
      run1    <- runBuild(before, None)(_.getFact(NumberKey("top")))
      _       <- src.set(20)
      run2    <- runBuild(after, Some(run1._2))(g => g.getFact(NumberKey("top")).product(g.currentErrors()))
      oldN    <- counts("old").get
    } yield (run2._1._1, run2._1._2.map(_.message), oldN)
    // 'old' did run (validation demanded it), and its error is not this compilation's
    test.asserting(_ shouldBe (Some(NumberFact("top", 21)), Seq.empty, 2))
  }

  it should "report the diagnostic of a failing fact this run's graph does reach" in {
    // Same shape, except 'top' still depends on 'old' — so the failure is genuinely on the path of the program being
    // compiled, and must be reported (and 'top' declines).
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("src", "old", "top")
      before  = graph(Map("src" -> Leaf(src), "old" -> Derived("src", identity), "top" -> Derived2("old", "src", _ + _)), counts)
      after   = graph(Map("src" -> Leaf(src), "old" -> Failing("gone"), "top" -> Derived("old", _ + 1)), counts)
      run1   <- runBuild(before, None)(_.getFact(NumberKey("top")))
      _      <- src.set(20)
      run2   <- runBuild(after, Some(run1._2))(g => g.getFact(NumberKey("top")).product(g.currentErrors()))
    } yield (run2._1._1, run2._1._2.map(_.message))
    test.asserting(_ shouldBe (None, Seq("gone")))
  }

  it should "generate a newly requested fact fresh against an existing cache" in {
    val test = for {
      srcA  <- Ref.of[IO, Int](1)
      srcB  <- Ref.of[IO, Int](2)
      counts <- counters("a", "b", "da", "db")
      proc   = graph(
                 Map("a" -> Leaf(srcA), "b" -> Leaf(srcB), "da" -> Derived("a", _ * 10), "db" -> Derived("b", _ * 10)),
                 counts
               )
      run1  <- runBuild(proc, None)(_.getFact(NumberKey("da")))
      run2  <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("db"))) // new fact this run
      dbN   <- counts("db").get
    } yield (run2._1, dbN)
    test.asserting(_ shouldBe (Some(NumberFact("db", 20)), 1))
  }

  it should "maintain the ancestor request chain in activeFactKeys during generation" in {
    // chain d -> b -> a; when generating 'a' the chain is [a, b, d] (innermost first).
    val test = for {
      src     <- Ref.of[IO, Int](10)
      counts  <- counters("a", "b", "d")
      capture <- Ref.of[IO, Map[String, List[CompilerFactKey[?]]]](Map.empty)
      proc     = graph(
                   Map("a" -> Leaf(src), "b" -> Derived("a", _ + 1), "d" -> Derived("b", _ + 1)),
                   counts,
                   Some(capture)
                 )
      _       <- runBuild(proc, None)(_.getFact(NumberKey("d")))
      chains  <- capture.get
    } yield chains
    test.asserting { chains =>
      chains("a") shouldBe List(NumberKey("a"), NumberKey("b"), NumberKey("d"))
      chains("d") shouldBe List(NumberKey("d"))
    }
  }

  it should "error on a self-cyclic fact demand instead of hanging" in {
    val test = for {
      counts <- counters("a")
      proc    = graph(Map("a" -> Derived("a", identity)), counts)
      run    <- runBuild(proc, None)(g => g.getFact(NumberKey("a")).product(g.currentErrors()))
    } yield (run._1._1, run._1._2.map(_.message))
    test.asserting(_ shouldBe (None, Seq("Cyclic fact demand: NumberKey(a) <- NumberKey(a)")))
  }

  it should "error on a mutual cyclic fact demand instead of hanging" in {
    val test = for {
      counts <- counters("a", "b")
      proc    = graph(Map("a" -> Derived("b", identity), "b" -> Derived("a", identity)), counts)
      run    <- runBuild(proc, None)(g => g.getFact(NumberKey("a")).product(g.currentErrors()))
    } yield (run._1._1, run._1._2.map(_.message))
    test.asserting(_ shouldBe (None, Seq("Cyclic fact demand: NumberKey(a) <- NumberKey(b) <- NumberKey(a)")))
  }

  it should "give a pushed fact the pushing generation's dependency set in the cache" in {
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "pusher", "sibling")
      proc    = graph(
                  Map("leaf" -> Leaf(src), "pusher" -> Pushing("leaf", "sibling", _ * 2), "sibling" -> Derived("leaf", _ * 2)),
                  counts
                )
      run    <- runBuild(proc, None)(_.getFact(NumberKey("pusher")))
    } yield run._2.entries.get(NumberKey("sibling"))
    test.asserting(_ shouldBe Some(CacheEntry(Some(NumberFact("sibling", 20)), Set(NumberKey("leaf")))))
  }

  it should "regenerate a pushed fact when its inherited dependencies change" in {
    // Run 1 demands only 'pusher', which pushes 'sibling'. Run 2 changes the leaf and demands only 'sibling':
    // the pushed entry must be invalidated through its inherited deps and regenerated fresh — never served stale.
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "pusher", "sibling")
      proc      = graph(
                    Map("leaf" -> Leaf(src), "pusher" -> Pushing("leaf", "sibling", _ * 2), "sibling" -> Derived("leaf", _ * 2)),
                    counts
                  )
      run1     <- runBuild(proc, None)(_.getFact(NumberKey("pusher")))
      _        <- src.set(15)
      run2     <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("sibling")))
    } yield run2._1
    test.asserting(_ shouldBe Some(NumberFact("sibling", 30)))
  }

  it should "accept a pushed fact from cache when its inherited dependencies are unchanged" in {
    val test = for {
      src      <- Ref.of[IO, Int](10)
      counts   <- counters("leaf", "pusher", "sibling")
      proc      = graph(
                    Map("leaf" -> Leaf(src), "pusher" -> Pushing("leaf", "sibling", _ * 2), "sibling" -> Derived("leaf", _ * 2)),
                    counts
                  )
      run1     <- runBuild(proc, None)(_.getFact(NumberKey("pusher")))
      run2     <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("sibling")))
      siblingN <- counts("sibling").get
    } yield (run2._1, siblingN)
    test.asserting(_ shouldBe (Some(NumberFact("sibling", 20)), 0))
  }

  it should "not accept a fact against a dependency that was dropped and then recreated without it" in {
    // Run 2 moves 'a', which drops its direct dependent 'm' — but not 'w', which was recorded against 'm' and is only
    // safe while 'm' has no entry. Run 3 recreates 'm' from nothing and never reaches 'w'. Run 4 then finds 'm''s new
    // entry intact and must not serve run 1's 'w' for an 'm' it never consumed.
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "a", "m", "w")
      proc    = graph(
                  Map("leaf" -> Leaf(src), "a" -> Derived("leaf", _ + 1), "m" -> Derived("a", _ * 2), "w" -> Derived("m", _ * 10)),
                  counts
                )
      run1   <- runBuild(proc, None)(_.getFact(NumberKey("w")))
      _      <- src.set(20)
      run2   <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("a"))) // 'a' moves; 'm' is dropped, 'w' is kept
      run3   <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("m"))) // 'm' is recreated; 'w' is never demanded
      run4   <- runBuild(proc, Some(run3._2))(_.getFact(NumberKey("w")))
    } yield (run1._1, run4._1)
    test.asserting(_ shouldBe (Some(NumberFact("w", 220)), Some(NumberFact("w", 420))))
  }

  it should "not accept a fact against a pushed dependency that moved while it was not looking" in {
    // The pushed twin of the three-run trap above. Run 2 changes the leaf and demands only 'pusher', which *pushes*
    // 'sibling' — so 'sibling' advances without ever being resolved, and 'w', recorded against it in run 1, is never
    // reached. Run 3 validates 'w' through 'sibling''s new entry, whose inherited dependencies hold, and must not serve
    // run 1's 'w' for a 'sibling' it never consumed.
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "pusher", "sibling", "w")
      proc    = graph(
                  Map(
                    "leaf"    -> Leaf(src),
                    "pusher"  -> Pushing("leaf", "sibling", _ * 2),
                    "sibling" -> Derived("leaf", _ * 2),
                    "w"       -> Derived("sibling", _ * 10)
                  ),
                  counts
                )
      run1   <- runBuild(proc, None)(g => g.getFact(NumberKey("pusher")) >> g.getFact(NumberKey("w")))
      _      <- src.set(15)
      run2   <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("pusher"))) // pushes 'sibling'; 'w' never demanded
      run3   <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("w")))
    } yield (run1._1, run3._1)
    test.asserting(_ shouldBe (Some(NumberFact("w", 200)), Some(NumberFact("w", 300))))
  }

  it should "persist a directly registered out-of-generation fact as a leaf entry" in {
    // No dependency record exists for it, so it gets leaf semantics: always regenerated next run, never accepted
    // blindly (covered by "always regenerate a fact whose cached entry has no recorded dependencies").
    val test = for {
      counts <- counters("derived")
      proc    = graph(Map("derived" -> Derived("direct", _ + 1)), counts)
      run    <- runBuild(proc, None)(g => g.registerFact(NumberFact("direct", 1)) *> g.getFact(NumberKey("derived")))
    } yield (run._1, run._2.entries.get(NumberKey("direct")))
    test.asserting(_ shouldBe (Some(NumberFact("derived", 2)), Some(CacheEntry(Some(NumberFact("direct", 1)), Set.empty))))
  }

  it should "report an internal error when no processor produces a fact, an error, or a decline" in {
    val test = for {
      counts <- counters()
      proc    = graph(Map.empty, counts)
      run    <- runBuild(proc, None)(g => g.getFact(UnhandledKey("mystery")).product(g.currentErrors()))
    } yield (run._1._1, run._1._2.map(_.message))
    test.asserting(
      _ shouldBe (None, Seq("Internal error: no processor produced a fact, an error, or a decline for UnhandledKey(mystery)."))
    )
  }

  it should "not report an internal error for an explicit decline" in {
    // GraphProcessor declines unknown names via abort — the sanctioned decline; no diagnostic, no error.
    val test = for {
      counts <- counters()
      proc    = graph(Map.empty, counts)
      run    <- runBuild(proc, None)(g => g.getFact(NumberKey("undefined")).product(g.currentErrors()))
    } yield (run._1._1, run._1._2)
    test.asserting(_ shouldBe (None, Seq.empty))
  }

  it should "report an internal error when a key is produced twice with different values" in {
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "p1", "p2")
      proc    = graph(
                  Map("leaf" -> Leaf(src), "p1" -> Pushing("leaf", "shared", _ * 2), "p2" -> Pushing("leaf", "shared", _ * 3)),
                  counts
                )
      run    <- runBuild(proc, None)(g => g.getFact(NumberKey("p1")) *> g.getFact(NumberKey("p2")) *> g.currentErrors())
    } yield run._1.map(_.message)
    test.asserting(
      _ shouldBe Seq("Internal error: fact NumberKey(shared) was produced twice with different values.")
    )
  }

  it should "stay silent when a key is re-registered with an equal value" in {
    val test = for {
      src    <- Ref.of[IO, Int](10)
      counts <- counters("leaf", "p1", "p2")
      proc    = graph(
                  Map("leaf" -> Leaf(src), "p1" -> Pushing("leaf", "shared", _ * 2), "p2" -> Pushing("leaf", "shared", _ * 2)),
                  counts
                )
      run    <- runBuild(proc, None)(g => g.getFact(NumberKey("p1")) *> g.getFact(NumberKey("p2")) *> g.currentErrors())
    } yield run._1
    test.asserting(_ shouldBe Seq.empty)
  }

  it should "rebuild a deleted output and then stabilize (presence-leaf behavior)" in {
    // Models OutputFileStat: 'present' leaf reads a 0/1 presence flag; 'jar' depends on it and 'writes'
    // (sets the flag to 1 and bumps the write counter). Deleting the output (flag -> 0) forces a rebuild.
    val test = for {
      present <- Ref.of[IO, Int](0) // 0 = output missing
      writes  <- Ref.of[IO, Int](0)
      counts  <- counters("present", "jar")
      proc     = graph(
                   Map("present" -> Leaf(present), "jar" -> Writer("present", present.set(1) >> writes.update(_ + 1))),
                   counts
                 )
      run1    <- runBuild(proc, None)(_.getFact(NumberKey("jar")))
      w1      <- writes.get
      run2    <- runBuild(proc, Some(run1._2))(_.getFact(NumberKey("jar")))
      w2      <- writes.get // echo rebuild: presence flipped 0 -> 1 across the first write
      run3    <- runBuild(proc, Some(run2._2))(_.getFact(NumberKey("jar")))
      w3      <- writes.get // stable now
      _       <- present.set(0) // delete the output
      run4    <- runBuild(proc, Some(run3._2))(_.getFact(NumberKey("jar")))
      w4      <- writes.get // deletion forces a rebuild
    } yield (w1, w2, w3, w4)
    test.asserting(_ shouldBe (1, 2, 2, 3))
  }
}

object IncrementalFactGeneratorTest {
  case class NumberFact(name: String, value: Int) extends CompilerFact {
    override def key(): CompilerFactKey[NumberFact] = NumberKey(name)
  }

  case class NumberKey(name: String) extends CompilerFactKey[NumberFact] {
    override def valueCodec: Option[FactCodec[NumberFact]] = None // a test double is never persisted
  }

  /** A key type no processor in these tests handles — for exercising the missing-producer diagnostic. */
  case class UnhandledKey(name: String) extends CompilerFactKey[NumberFact] {
    override def valueCodec: Option[FactCodec[NumberFact]] = None // a test double is never persisted
  }

  sealed trait Node
  case class Leaf(source: Ref[IO, Int])                                extends Node
  case class Derived(dep: String, f: Int => Int)                       extends Node
  case class Derived2(dep1: String, dep2: String, f: (Int, Int) => Int) extends Node
  case class Writer(dep: String, sideEffect: IO[Unit])                 extends Node
  case class Failing(message: String)                                  extends Node
  case class Pushing(dep: String, sibling: String, f: Int => Int)      extends Node
  /** Succeeds echoing its dependency's value while it is non-negative; errors and declines once it goes negative. */
  case class Checked(dep: String)                                      extends Node

  private def err(message: String): CompilerError =
    CompilerError(message, Seq.empty, "", "", PositionRange.zero)

  def counters(names: String*): IO[Map[String, Ref[IO, Int]]] =
    names.toList.traverse(name => Ref.of[IO, Int](0).map(name -> _)).map(_.toMap)

  def graph(
      defs: Map[String, Node],
      counts: Map[String, Ref[IO, Int]],
      capture: Option[Ref[IO, Map[String, List[CompilerFactKey[?]]]]] = None
  ): CompilerProcessor = new GraphProcessor(defs, counts, capture)

  def runBuild[A](processor: CompilerProcessor, prior: Option[FactCacheData])(
      body: IncrementalFactGenerator => IO[A]
  ): IO[(A, FactCacheData)] =
    for {
      generator <- IncrementalFactGenerator.create(processor, prior, strictAccounting = true)
      a         <- body(generator)
      cache     <- generator.buildCacheData()
    } yield (a, cache)

  class GraphProcessor(
      defs: Map[String, Node],
      counts: Map[String, Ref[IO, Int]],
      capture: Option[Ref[IO, Map[String, List[CompilerFactKey[?]]]]]
  ) extends SingleFactProcessor[NumberKey] {
    override protected def generateSingleFact(key: NumberKey): CompilerIO[NumberFact] = {
      val tick = counts.get(key.name).fold(().pure[CompilerIO])(_.update(_ + 1).to[CompilerIO])
      val rec  = capture.fold(().pure[CompilerIO])(ref =>
        activeFactKeys.flatMap(keys => ref.update(_ + (key.name -> keys)).to[CompilerIO])
      )
      tick >> rec >> produce(key)
    }

    private def produce(key: NumberKey): CompilerIO[NumberFact] =
      defs.get(key.name) match {
        case Some(Leaf(src))           => src.get.to[CompilerIO].map(NumberFact(key.name, _))
        case Some(Derived(dep, f))     => getFactOrAbort(NumberKey(dep)).map(d => NumberFact(key.name, f(d.value)))
        case Some(Derived2(d1, d2, f)) =>
          for {
            a <- getFactOrAbort(NumberKey(d1))
            b <- getFactOrAbort(NumberKey(d2))
          } yield NumberFact(key.name, f(a.value, b.value))
        case Some(Writer(dep, eff))    => getFactOrAbort(NumberKey(dep)) >> eff.to[CompilerIO].as(NumberFact(key.name, 1))
        case Some(Failing(message))    => registerCompilerError(err(message)) >> abort[NumberFact]
        case Some(Pushing(dep, sib, f)) =>
          getFactOrAbort(NumberKey(dep)).flatMap(d =>
            registerFactIfClear(NumberFact(sib, f(d.value))).as(NumberFact(key.name, f(d.value)))
          )
        case Some(Checked(dep))        =>
          getFactOrAbort(NumberKey(dep)).flatMap(d =>
            if (d.value >= 0) NumberFact(key.name, d.value).pure[CompilerIO]
            else registerCompilerError(err("negative")) >> abort[NumberFact]
          )
        case None                      => abort[NumberFact]
      }
  }
}
