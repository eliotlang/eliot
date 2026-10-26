package com.vanillasource.eliot.eliotc.progress

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.compiler.cache.FactCacheData
import com.vanillasource.eliot.eliotc.compiler.cache.IncrementalFactGeneratorTest.*
import com.vanillasource.eliot.eliotc.processor.CompilerProcessor
import com.vanillasource.eliot.eliotc.progress.ProgressRunClass.*
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

class ProgressTrackerTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  "the progress tracker" should "count every demanded fact of a cold build as generated" in {
    chain(10).flatMap(proc => countBuild(proc, None)).asserting(_._1 shouldBe (2L, 0L))
  }

  it should "count the same facts on an unchanged rerun, the derived ones from the cache" in {
    val test = for {
      proc <- chain(10)
      cold <- countBuild(proc, None)
      warm <- countBuild(proc, Some(cold._2))
    } yield warm._1
    // the leaf is re-read from the world, the derived fact is accepted
    test.asserting(_ shouldBe (2L, 1L))
  }

  it should "count a fact proven unchanged by the drill as from the cache" in {
    val test = for {
      src  <- Ref.of[IO, Int](10)
      proc  = graph(
                Map("src" -> Leaf(src), "mid" -> Derived("src", _ + 1), "top" -> Derived("mid", _ * 2)),
                Map.empty
              )
      cold <- countBuild(proc, None, "top")
      warm <- countBuild(proc, Some(cold._2), "top")
    } yield warm._1
    // `mid` is never materialised on the rerun: the drill proves it unchanged on the way to accepting `top`
    test.asserting(_ shouldBe (3L, 2L))
  }

  it should "not count a pushed fact that nothing asked for" in {
    val test = for {
      src  <- Ref.of[IO, Int](10)
      proc  = graph(Map("src" -> Leaf(src), "owner" -> Pushing("src", "sibling", _ + 1)), Map.empty)
      run  <- countBuild(proc, None, "owner")
    } yield run._1
    test.asserting(_ shouldBe (2L, 0L))
  }

  it should "count a fact once however often it is asked for" in {
    val test = for {
      src <- Ref.of[IO, Int](10)
      proc = graph(
               Map(
                 "a" -> Leaf(src),
                 "b" -> Derived("a", _ + 1),
                 "c" -> Derived("a", _ + 2),
                 "d" -> Derived2("b", "c", _ + _)
               ),
               Map.empty
             )
      run <- countBuild(proc, None, "d")
    } yield run._1
    test.asserting(_ shouldBe (4L, 0L))
  }

  it should "report the phase it was last told" in {
    val test = for {
      tracker  <- ProgressTracker.create()
      _        <- tracker.enter(ProgressPhase.LoadingCache)
      _        <- tracker.enter(ProgressPhase.Working)
      snapshot <- tracker.snapshot
    } yield snapshot.phase
    test.asserting(_ shouldBe ProgressPhase.Working)
  }

  it should "measure the run against the expected total" in {
    ProgressTracker.create(ProgressProfile(Some(3989))).flatMap(_.snapshot).asserting(_.total shouldBe Some(3989))
  }

  it should "let the total follow a run that delivered more than expected" in {
    val test = for {
      tracker  <- ProgressTracker.create(ProgressProfile(Some(1)))
      _        <- IO.delay(tracker.delivered(NumberKey("a"), fromCache = false))
      _        <- IO.delay(tracker.delivered(NumberKey("b"), fromCache = false))
      snapshot <- tracker.snapshot
    } yield snapshot.total
    test.asserting(_ shouldBe Some(2))
  }

  it should "have no total on a first build" in {
    ProgressTracker.create().flatMap(_.snapshot).asserting(_.total shouldBe None)
  }

  it should "show the described fact being worked out as the activity" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- IO.delay(clock.set(2.seconds.toNanos))
      snapshot <- tracker.snapshot
    } yield (snapshot.activity, snapshot.activityTime)
    test.asserting(_ shouldBe (Some(ProgressActivity("making", "top")), 2.seconds))
  }

  it should "show the nearest described fact that asked for an undescribed one" in {
    val test = for {
      tracker  <- ProgressTracker.create(describer = describeAll)
      _        <- IO.delay(tracker.started(top, None))
      _        <- IO.delay(tracker.started(hidden, Some(top)))
      snapshot <- tracker.snapshot
    } yield snapshot.activity
    test.asserting(_ shouldBe Some(ProgressActivity("making", "top")))
  }

  it should "show the fact being waited for, not the one waiting" in {
    val test = for {
      tracker  <- ProgressTracker.create(describer = describeAll)
      _        <- IO.delay(tracker.started(top, None))
      _        <- IO.delay(tracker.started(inner, Some(top)))
      snapshot <- tracker.snapshot
    } yield snapshot.activity
    test.asserting(_ shouldBe Some(ProgressActivity("making", "inner")))
  }

  it should "record a slow step with the undescribed work it asked for, but not the described work" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- advance(clock, 400.millis)
      _        <- child(tracker, clock, "hidden", 700.millis)
      _        <- child(tracker, clock, "inner", 5.seconds)
      _        <- IO.delay(tracker.ended(top))
      snapshot <- tracker.snapshot
    } yield snapshot.slowSteps
    test.asserting(
      _ shouldBe Seq(
        ProgressStep(ProgressActivity("making", "inner"), 5.seconds),
        ProgressStep(ProgressActivity("making", "top"), 1100.millis)
      )
    )
  }

  it should "not charge a fact's time while it waits for a fact another generation works out" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- IO.delay(tracker.waiting(top))
      _        <- advance(clock, 5.seconds)
      _        <- IO.delay(tracker.resumed(top))
      _        <- advance(clock, 200.millis)
      snapshot <- tracker.snapshot
    } yield snapshot.activityTime
    test.asserting(_ shouldBe 200.millis)
  }

  it should "not record a described fact that took less than a second" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- child(tracker, clock, "inner", 5.seconds)
      _        <- advance(clock, 900.millis)
      _        <- IO.delay(tracker.ended(top))
      snapshot <- tracker.snapshot
    } yield snapshot.slowSteps.map(_.activity.subject)
    test.asserting(_ shouldBe Seq("inner"))
  }

  it should "name each input the drill found changed" in {
    val test = for {
      src       <- Ref.of[IO, Int](10)
      proc       = threeLevels(src)
      cold      <- countBuild(proc, None, "top")
      _         <- src.set(20)
      tracker   <- ProgressTracker.create(describer = describeInputs("src", "mid"))
      generator <- IncrementalFactGenerator.create(proc, Some(cold._2), strictAccounting = true, Some(tracker))
      _         <- generator.demand.getFact(top)
      snapshot  <- tracker.snapshot
    } yield snapshot.changed
    test.asserting(_ shouldBe Seq("src", "mid"))
  }

  it should "not name a changed fact that is not an input" in {
    val test = for {
      src       <- Ref.of[IO, Int](10)
      proc       = threeLevels(src)
      cold      <- countBuild(proc, None, "top")
      _         <- src.set(20)
      tracker   <- ProgressTracker.create(describer = describeInputs("mid"))
      generator <- IncrementalFactGenerator.create(proc, Some(cold._2), strictAccounting = true, Some(tracker))
      _         <- generator.demand.getFact(top)
      snapshot  <- tracker.snapshot
    } yield snapshot.changed
    test.asserting(_ shouldBe Seq("mid"))
  }

  it should "add a generation's own time to its key type's cost" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- advance(clock, 400.millis)
      _        <- child(tracker, clock, "hidden", 700.millis)
      _        <- IO.delay(tracker.ended(top))
      snapshot <- tracker.snapshot
    } yield snapshot.worked
    test.asserting(_ shouldBe Map(classOf[NumberKey].getName -> ProgressCost(2, 1100.millis.toNanos.toDouble)))
  }

  it should "add a described fact's time to its verb" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- advance(clock, 400.millis)
      _        <- child(tracker, clock, "hidden", 700.millis)
      _        <- child(tracker, clock, "inner", 5.seconds)
      _        <- IO.delay(tracker.ended(top))
      snapshot <- tracker.snapshot
    } yield snapshot.verbTimes
    test.asserting(_ shouldBe Map("making" -> 6100.millis, "working" -> Duration.Zero))
  }

  it should "add time no described fact owns to working" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(describer = describeAll, clock = () => clock.get())
      _        <- IO.delay(tracker.started(hidden, None))
      _        <- advance(clock, 300.millis)
      _        <- IO.delay(tracker.ended(hidden))
      snapshot <- tracker.snapshot
    } yield snapshot.verbTimes
    test.asserting(_ shouldBe Map("working" -> 300.millis))
  }

  it should "show a fact in flight with its own time so far" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(clock = () => clock.get())
      _        <- IO.delay(tracker.started(top, None))
      _        <- advance(clock, 2.seconds)
      _        <- IO.delay(tracker.started(inner, Some(top)))
      _        <- advance(clock, 1.second)
      snapshot <- tracker.snapshot
    } yield snapshot.inFlight.toSet
    test.asserting(_ shouldBe Set(classOf[NumberKey].getName -> 2.seconds, classOf[NumberKey].getName -> 1.second))
  }

  it should "time each phase, the current one so far" in {
    val test = for {
      clock    <- IO.delay(AtomicLong())
      tracker  <- ProgressTracker.create(clock = () => clock.get())
      _        <- advance(clock, 1.second) >> tracker.enter(ProgressPhase.LoadingCache)
      _        <- advance(clock, 2.seconds) >> tracker.enter(ProgressPhase.Working)
      _        <- advance(clock, 1.second)
      snapshot <- tracker.snapshot
    } yield snapshot.phaseTimes
    test.asserting(
      _ shouldBe Map(
        ProgressPhase.Starting     -> 1.second,
        ProgressPhase.LoadingCache -> 2.seconds,
        ProgressPhase.Working      -> 1.second
      )
    )
  }

  it should "be a cold run when the cache held nothing" in {
    ProgressTracker.create().flatTap(_.cacheLoaded(false)).flatMap(_.snapshot).asserting(_.runClass shouldBe Some(Cold))
  }

  it should "stay an unchanged run while the drill finds nothing changed" in {
    runClassAfter(src => IO.unit).asserting(_ shouldBe Some(Unchanged))
  }

  it should "become a changed run when the drill finds a fact changed" in {
    runClassAfter(src => src.set(20)).asserting(_ shouldBe Some(Changed))
  }

  it should "estimate the time left from the history of its class" in {
    val test = for {
      tracker  <- ProgressTracker.create(savingProfile)
      _        <- tracker.cacheLoaded(false)
      _        <- tracker.enter(ProgressPhase.Working)
      snapshot <- tracker.snapshot
    } yield snapshot.remaining
    test.asserting(_ shouldBe Some(1.second))
  }

  it should "not estimate a run whose class has no history" in {
    ProgressTracker
      .create(savingProfile)
      .flatTap(_.cacheLoaded(true))
      .flatMap(_.snapshot)
      .asserting(_.remaining shouldBe None)
  }

  /** A profile whose cold runs spent a second saving the cache, and nothing else. */
  private val savingProfile =
    ProgressProfile(Some(10), Map(Cold -> ProgressHistory(10, Map(ProgressPhase.SavingCache -> 1e9), Map.empty)))

  /** The class of a run over a cache of `threeLevels`, after `change` to its source. */
  private def runClassAfter(change: Ref[IO, Int] => IO[Unit]): IO[Option[ProgressRunClass]] =
    for {
      src       <- Ref.of[IO, Int](10)
      proc       = threeLevels(src)
      cold      <- countBuild(proc, None, "top")
      _         <- change(src)
      tracker   <- ProgressTracker.create()
      _         <- tracker.cacheLoaded(true)
      generator <- IncrementalFactGenerator.create(proc, Some(cold._2), strictAccounting = true, Some(tracker))
      _         <- generator.demand.getFact(top)
      snapshot  <- tracker.snapshot
    } yield snapshot.runClass

  private val top    = NumberKey("top")
  private val hidden = NumberKey("hidden")
  private val inner  = NumberKey("inner")

  /** Describes every key but `hidden`, as `making <name>`. */
  private val describeAll: ProgressDescriber = {
    case NumberKey(name) if name != "hidden" => Some(ProgressActivity("making", name))
    case _                                   => None
  }

  /** Describes the keys named as inputs. */
  private def describeInputs(names: String*): ProgressDescriber = {
    case NumberKey(name) => Some(ProgressActivity("making", name, input = names.contains(name)))
    case _               => None
  }

  private def advance(clock: AtomicLong, by: FiniteDuration): IO[Unit] = IO.delay(clock.addAndGet(by.toNanos)).void

  /** `top` asking for `name`, which takes `took` to work out. */
  private def child(tracker: ProgressTracker, clock: AtomicLong, name: String, took: FiniteDuration): IO[Unit] = {
    val key = NumberKey(name)

    IO.delay(tracker.started(key, Some(top))) >> advance(clock, took) >> IO.delay(tracker.ended(key))
  }

  private def threeLevels(src: Ref[IO, Int]): CompilerProcessor =
    graph(Map("src" -> Leaf(src), "mid" -> Derived("src", _ + 1), "top" -> Derived("mid", _ * 2)), Map.empty)

  private def chain(value: Int): IO[CompilerProcessor] =
    Ref.of[IO, Int](value).map(src => graph(Map("leaf" -> Leaf(src), "derived" -> Derived("leaf", _ * 2)), Map.empty))

  /** One run demanding `root`, answering the (delivered, from cache) counts and the cache for the next run. */
  private def countBuild(
      processor: CompilerProcessor,
      prior: Option[FactCacheData],
      root: String = "derived"
  ): IO[((Long, Long), FactCacheData)] =
    for {
      tracker   <- ProgressTracker.create()
      generator <- IncrementalFactGenerator.create(processor, prior, strictAccounting = true, Some(tracker))
      _         <- generator.demand.getFact(NumberKey(root))
      cache     <- generator.buildCacheData()
      snapshot  <- tracker.snapshot
    } yield ((snapshot.delivered, snapshot.fromCache), cache)
}
