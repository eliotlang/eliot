package com.vanillasource.eliot.eliotc.progress

import cats.effect.IO
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey
import com.vanillasource.eliot.eliotc.progress.ProgressTracker.{Generation, TypeCounter}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicLong, AtomicReference, LongAdder}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The mutable core of `--progress`: which phase the run is in, how many facts it has delivered out of how many it is
  * expected to, and what it is doing (`docs/progress-indication.md` §3.2, §3.5). Written by the session and the fact
  * engine, read by the [[ProgressLineWriter]]; created only when `--progress` is asked for.
  *
  * A fact counts **once**, at whichever comes first: a demand for it was answered, it was accepted from the cache, or it
  * was proven unchanged by the cache's validation drill. So a cold build and a warm one count towards the same figure,
  * and nothing about generation order matters — the set is concurrent, and each counter only ever grows.
  *
  * What the run is doing is kept as the set of **generations in flight**, each with its parent and whether it is
  * waiting for a fact. One that waits for nothing is doing the work; its time is charged to its **owner**, the nearest
  * described fact up its parents (itself, if it is described). The activity shown is the owner of the longest-running
  * such generation — a sample of where the time goes, which stays meaningful when several generations run at once.
  *
  * The same events time the run for its estimate (§3.4): a generation's own time — while it waits for nothing — is added
  * to its key type's cost when it ends, and an owner's time to its verb. The union of a generation's waits is what is
  * left out, never their sum, so facts asked for in parallel cannot drive a time negative.
  *
  * @param profile
  *   what the previous runs of this configuration learned: the total to measure against, absent on a first build, and
  *   the history the estimate of the time left comes from
  */
final class ProgressTracker private (
    profile: ProgressProfile,
    describer: ProgressDescriber,
    clock: () => Long,
    phases: AtomicReference[Vector[(ProgressPhase, Long)]],
    runClassRef: AtomicReference[Option[ProgressRunClass]],
    keys: java.util.Set[CompilerFactKey[?]],
    deliveredCount: AtomicLong,
    fromCacheCount: AtomicLong,
    inFlight: ConcurrentHashMap[CompilerFactKey[?], Generation],
    changedRef: AtomicReference[Vector[String]],
    slowStepsRef: AtomicReference[Vector[ProgressStep]],
    typeCosts: ConcurrentHashMap[Class[?], TypeCounter],
    verbNanos: ConcurrentHashMap[String, LongAdder],
    unowned: LongAdder
) {

  /** Record that the run has moved on to `phase`. */
  def enter(phase: ProgressPhase): IO[Unit] = IO.delay(phases.updateAndGet(_ :+ (phase, clock()))).void

  /** Record that the cache is loaded, and whether it held anything, which is what decides the class of the run. */
  def cacheLoaded(present: Boolean): IO[Unit] =
    IO.delay(runClassRef.set(Some(if (present) ProgressRunClass.Unchanged else ProgressRunClass.Cold)))

  /** Record that the run has the fact of `key`; a key it already had is not counted again. */
  def delivered(key: CompilerFactKey[?], fromCache: Boolean): Unit = {
    if (keys.add(key)) {
      deliveredCount.incrementAndGet()
      if (fromCache) fromCacheCount.incrementAndGet(): Unit
    }
  }

  /** Record that the run started working out `key`'s fact — from the cache or by generating it — for `parent`, the
    * generation that asked for it, if any. The parent waits for it until it [[ended]].
    */
  def started(key: CompilerFactKey[?], parent: Option[CompilerFactKey[?]]): Unit = {
    val now        = clock()
    val parentNode = parent.flatMap(p => Option(inFlight.get(p)))

    parentNode.foreach(_.waitFrom(now))
    inFlight.put(key, new Generation(key.getClass, describer.describe(key), parentNode, now, unowned)): Unit
  }

  /** Record that the run finished working out `key`'s fact, so the generation that asked for it waits no more. Its own
    * time is added to its key type's cost, and a described fact's time to its verb; one that took
    * [[ProgressTracker.slowStep]] or more becomes a slow step.
    */
  def ended(key: CompilerFactKey[?]): Unit = {
    Option(inFlight.remove(key)).foreach { generation =>
      val now  = clock()
      val self = generation.synchronized(generation.selfAt(now, charged = true))

      generation.parent.foreach(_.resumeAt(now))
      typeCosts.computeIfAbsent(generation.keyType, _ => TypeCounter()).record(self)
      generation.activity.foreach { activity =>
        val took = generation.spent.get()

        verbNanos.computeIfAbsent(activity.verb, _ => LongAdder()).add(took)
        if (took.nanos >= ProgressTracker.slowStep) slowStepsRef.updateAndGet(_ :+ ProgressStep(activity, took.nanos)): Unit
      }
    }
  }

  /** Record that the generation of `key` is waiting for a fact it did not start, so its time is not its own until
    * [[resumed]].
    */
  def waiting(key: CompilerFactKey[?]): Unit =
    Option(inFlight.get(key)).foreach(_.waitFrom(clock()))

  /** Record that a fact the generation of `key` waited for, and did not start, has arrived. */
  def resumed(key: CompilerFactKey[?]): Unit =
    Option(inFlight.get(key)).foreach(_.resumeAt(clock()))

  /** Record that the fact of `key` came out different from what the cache held. Only an [[ProgressActivity.input]] is
    * worth telling the user about, and each subject once.
    */
  def changed(key: CompilerFactKey[?]): Unit = {
    runClassRef.updateAndGet {
      case Some(ProgressRunClass.Unchanged) => Some(ProgressRunClass.Changed)
      case other                            => other
    }
    describer
      .describe(key)
      .filter(_.input)
      .foreach(activity =>
        changedRef.updateAndGet(seen => if (seen.contains(activity.subject)) seen else seen :+ activity.subject)
      )
  }

  /** The progress so far. `fromCache` is read before `delivered`, and incremented after it, so a snapshot never shows
    * more facts from the cache than facts delivered. A run that has grown past the expected total is measured against
    * what it delivered — `[28,901/28,901]` and still working is honest, 103 % is not.
    */
  def snapshot: IO[ProgressSnapshot] =
    IO.delay {
      val entered   = phases.get()
      val fromCache = fromCacheCount.get()
      val delivered = deliveredCount.get()
      val now       = clock()
      val runClass  = runClassRef.get()
      val flying    = inFlight.values().asScala.toSeq
      val working   = flying.filter(generation => generation.synchronized(generation.waits == 0))
      val sampled   = working.minByOption(_.startedAt).flatMap(leaf => leaf.owner.map(owner => (leaf, owner)))
      val base      = ProgressSnapshot(
        entered.last._1,
        delivered,
        fromCache,
        profile.total.map(_ max delivered),
        sampled.flatMap(_._2.activity),
        sampled.fold(Duration.Zero) { case (leaf, owner) =>
          (owner.spent.get() + leaf.synchronized((now - leaf.leafSince) max 0)).nanos
        },
        changedRef.get(),
        slowStepsRef.get(),
        runClass,
        entered
          .zip(entered.drop(1).map(_._2) :+ now)
          .groupMapReduce(_._1._1) { case ((_, from), to) => (to - from).nanos }(_ + _),
        typeCosts.asScala.map((keyType, counter) => keyType.getName -> counter.cost).toMap,
        flying.map(generation => generation.keyType.getName -> generation.synchronized(generation.selfAt(now)).nanos),
        verbNanos.asScala.map((verb, nanos) => verb -> nanos.sum().nanos).toMap.updated("working", unowned.sum().nanos)
      )

      base.copy(remaining = for {
        total    <- profile.total
        history  <- runClass.flatMap(profile.runs.get)
        estimate <- ProgressEstimator.remaining(total, history, base)
      } yield estimate)
    }
}

object ProgressTracker {

  /** How long a described fact must take to be shown as a step of its own. */
  val slowStep: FiniteDuration = 1.second

  /** A tracker for a run measured against `profile`, whose facts read the way `describer` says. The run is
    * [[ProgressPhase.Starting]] from now. `clock` answers nanoseconds, and is only replaced by tests.
    */
  def create(
      profile: ProgressProfile = ProgressProfile.empty,
      describer: ProgressDescriber = ProgressDescriber.none,
      clock: () => Long = () => System.nanoTime()
  ): IO[ProgressTracker] =
    IO.delay(
      new ProgressTracker(
        profile,
        describer,
        clock,
        AtomicReference(Vector((ProgressPhase.Starting, clock()))),
        AtomicReference(None),
        ConcurrentHashMap.newKeySet(),
        AtomicLong(),
        AtomicLong(),
        ConcurrentHashMap(),
        AtomicReference(Vector.empty),
        AtomicReference(Vector.empty),
        ConcurrentHashMap(),
        ConcurrentHashMap(),
        LongAdder()
      )
    )

  /** The facts of one key type worked out so far, and their own time. */
  private[progress] final class TypeCounter {
    private val count = LongAdder()
    private val nanos = LongAdder()

    def record(self: Long): Unit = {
      count.increment()
      nanos.add(self)
    }

    def cost: ProgressCost = ProgressCost(count.sum().toDouble, nanos.sum().toDouble)
  }

  /** One generation in flight.
    *
    * @param keyType
    *   the class of the key it works out
    * @param activity
    *   its description, if it has one
    * @param parent
    *   the generation that asked for it, if that is still in flight
    * @param startedAt
    *   when it started
    * @param unowned
    *   where time goes that no described generation owns
    */
  private[progress] final class Generation(
      val keyType: Class[?],
      val activity: Option[ProgressActivity],
      val parent: Option[Generation],
      val startedAt: Long,
      unowned: LongAdder
  ) {

    /** The time charged to this generation as an owner, in nanoseconds. */
    val spent: AtomicLong = AtomicLong()

    /** Its own time up to its last wait, in nanoseconds; guarded by this object. */
    private var self: Long = 0

    /** How many facts it is waiting for; guarded by this object. */
    var waits: Int = 0

    /** Since when it has been waiting for nothing; guarded by this object. */
    var leafSince: Long = startedAt

    /** The described generation its time is charged to. */
    val owner: Option[Generation] = if (activity.isDefined) Some(this) else parent.flatMap(_.owner)

    /** Charge `nanos` of its own time, to itself and to its owner; guarded by this object. */
    def charge(nanos: Long): Unit = {
      val time = nanos max 0

      self += time
      owner match {
        case Some(described) => described.spent.addAndGet(time): Unit
        case None            => unowned.add(time)
      }
    }

    /** Its own time at `now`, charging what it has been working since its last wait when `charged`; guarded by this
      * object.
      */
    def selfAt(now: Long, charged: Boolean = false): Long =
      if (waits > 0) self
      else if (charged) {
        charge(now - leafSince)
        leafSince = now
        self
      } else self + ((now - leafSince) max 0)

    /** Start waiting for one more fact at `now`. */
    def waitFrom(now: Long): Unit =
      synchronized {
        if (waits == 0) charge(now - leafSince)
        waits += 1
      }

    /** Stop waiting for one fact at `now`. */
    def resumeAt(now: Long): Unit =
      synchronized {
        waits -= 1
        if (waits == 0) leafSince = now
      }
  }
}
