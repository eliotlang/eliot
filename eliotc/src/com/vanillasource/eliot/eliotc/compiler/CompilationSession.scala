package com.vanillasource.eliot.eliotc.compiler

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactKeyCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.{CacheFingerprint, ContentAddressedCacheBackend, FactCacheData}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerProcessor}
import com.vanillasource.eliot.eliotc.processor.common.NullProcessor
import com.vanillasource.eliot.eliotc.progress.{ProgressMeasure, ProgressPhase, ProgressTracker}
import com.vanillasource.eliot.eliotc.statistics.{PhaseTimings, ProcessorStatistics}

import scala.concurrent.duration.FiniteDuration
import com.vanillasource.eliot.eliotc.visualization.{FactVisualizationTracker, TrackedCompilerProcessor}

import java.nio.file.Path

/** A resident compilation context. The expensive, run-independent setup — plugin configuration, the processor graph,
  * cache fingerprints — is done once in [[CompilationSession.create]]; each call to [[compileOnce]] then runs a full
  * compilation against the in-memory fact cache and folds the result back in as the next run's `prior`. The on-disk
  * cache is touched only at the edges (seed in `create`, flush in [[persist]]), never per compile — so the rich
  * in-memory facts (typed `SemValue`s with their native closures, dropped on serialization) survive across runs.
  *
  * This is the single seam the CLI and a long-running (LSP) server share: the CLI calls `compileOnce` once and
  * `persist`s; a server loops `compileOnce` and `persist`s on shutdown.
  */
final class CompilationSession private (
    targetPlugin: CompilerPlugin,
    processors: CompilerProcessor,
    val effectiveConfiguration: Configuration,
    targetPath: Path,
    compilerFingerprint: String,
    configFingerprint: String,
    backend: ContentAddressedCacheBackend,
    cache: Ref[IO, Option[FactCacheData]],
    writing: Ref[IO, Option[ContentAddressedCacheBackend.WriteBehind]],
    compileLock: Mutex[IO],
    phaseTimings: PhaseTimings
) extends Logging {

  /** Run one full compilation against the current in-memory cache, then store the resulting cache for the next run.
    *
    * Serialized via the lock so overlapping requests cannot corrupt the hand-off. The cache is updated as the *last*
    * step, so a run cancelled mid-flight leaves the previous good cache untouched — cancel-restart on a newer edit is
    * safe. Failures are never cached (they are not materialised), so a broken fact re-runs next time and re-surfaces
    * its error.
    *
    * @param tracker
    *   optional fact-flow visualization; omit in server mode.
    * @param statistics
    *   optional per-processor timing; omit in server mode.
    * @param progress
    *   optional progress counting, for `--progress`; omit in server mode. The run is `working` until its cache graph is
    *   built, which belongs to `saving cache`.
    * @param writeBehind
    *   encode the cache on another core while the compilation runs, for the [[persist]] that follows it. Only for a
    *   compilation that is persisted next, as the CLI's is: a server persists once, on shutdown, and would encode every
    *   run for the last one's save.
    * @param parallel
    *   start the facts a generation hints on threads of their own ([[IncrementalFactGenerator]]); the run is
    *   sequential without it.
    * @return
    *   the live generator (queryable for LSP features) plus the run's diagnostics.
    */
  def compileOnce(
      tracker: Option[FactVisualizationTracker] = None,
      statistics: Option[ProcessorStatistics] = None,
      progress: Option[ProgressTracker] = None,
      writeBehind: Boolean = false,
      parallel: Boolean = false
  ): IO[CompilationResult] =
    compileLock.lock.surround {
      for {
        prior     <- cache.get
        wrapped   <- IO.delay(wrap(processors, tracker, statistics))
        behind    <- Option.when(writeBehind)(backend.writeBehind()).sequence
        generator <- IncrementalFactGenerator.create(
                       wrapped,
                       prior,
                       strictAccounting = true,
                       progress,
                       behind.fold((_: CompilerFact) => ())(_.offer),
                       parallel
                     )
        _         <- progress.traverse_(_.enter(ProgressPhase.Working))
        produced  <- targetPlugin
                       .run(effectiveConfiguration, generator.demand)
                       .guaranteeCase(outcome => behind.traverse_(_.finish).unlessA(outcome.isSuccess))
        _         <- progress.traverse_(_.enter(ProgressPhase.SavingCache))
        nextCache <- phaseTimings.time(PhaseTimings.buildCache)(generator.buildCacheData())
        _         <- writing.getAndSet(behind).flatMap(_.traverse_(_.finish)) // one no save will use stops
        _         <- cache.set(Some(nextCache)) // last effect ⇒ a cancelled run keeps the old cache
        errors    <- generator.currentErrors()
      } yield CompilationResult(generator, errors, produced)
    }

  /** Apply all requested instrumentation in a *single* pass, because `wrapWith` hands each wrapper the leaf processor
    * and both wrappers need the leaf's class name to label it — wrapping a wrapper would name every processor after the
    * instrumentation instead. Timing goes innermost, so the tracking wrapper's own overhead falls inside a measured
    * fact wait rather than being charged to the processor.
    */
  private def wrap(
      processor: CompilerProcessor,
      tracker: Option[FactVisualizationTracker],
      statistics: Option[ProcessorStatistics]
  ): CompilerProcessor =
    if (tracker.isEmpty && statistics.isEmpty) processor
    else {
      val instrumented = processor.wrapWith { leaf =>
        val name  = leaf.getClass.getName
        val timed = statistics.fold(leaf)(_.wrap(leaf, name))

        tracker.fold(timed)(TrackedCompilerProcessor(timed, _, name))
      }

      statistics.fold(instrumented)(_.wrapTree(instrumented))
    }

  /** Flush the in-memory cache to disk so the next *process* start is warm. Call from the CLI after its single compile,
    * and from a server on shutdown. Fail-safe: the save warns rather than failing. What the last compilation encoded
    * behind itself is finished rather than encoded again.
    */
  def persist(): IO[Unit] =
    (cache.get, writing.getAndSet(None)).flatMapN((data, behind) =>
      data.traverse_(data => phaseTimings.time(PhaseTimings.cacheSave)(backend.save(data, behind)))
    )

  /** Hand what the last compilation produced to the target plugin, and answer the exit code it reports (see
    * [[CompilerPlugin.execute]]). The CLI calls it once, after a compilation that succeeded; a resident host never does.
    */
  def execute(): IO[Int] = targetPlugin.execute(effectiveConfiguration)

  /** What the target plugin measures of what it produced, for `--progress`. */
  def progressMeasures(): IO[Seq[ProgressMeasure]] = targetPlugin.progressMeasures(effectiveConfiguration)

  /** The coarse cache-phase timings (fingerprint, load, build, save) recorded across this session so far, by phase id.
    * `--statistics` reports them as explicit lines so the cache load/store cost — invisible to the per-processor total,
    * since it falls outside the compile window — is accounted rather than lost.
    */
  def phaseSnapshot: IO[Map[String, FiniteDuration]] = phaseTimings.snapshot
}

object CompilationSession {

  /** One-time setup: let the activated plugins configure each other, collect their processors, compute fingerprints,
    * and seed the in-memory cache from disk. The returned session is then re-runnable.
    *
    * @param progress
    *   told when the cache starts loading and whether it held anything, for `--progress`.
    */
  def create(
      targetPlugin: CompilerPlugin,
      activatedPlugins: Seq[CompilerPlugin],
      configuration: Configuration,
      progress: Option[ProgressTracker] = None
  ): IO[CompilationSession] =
    for {
      effectiveConfig <- activatedPlugins.traverse_(_.configure()).runS(configuration)
      processors      <- activatedPlugins.traverse_(_.initialize(effectiveConfig)).runS(NullProcessor())
      targetPath       = effectiveConfig.get(Compiler.targetPathKey).get
      phaseTimings    <- PhaseTimings.create()
      compilerFp      <- phaseTimings.time(PhaseTimings.fingerprint)(CacheFingerprint.compiler)
      configFp         = CacheFingerprint.config(effectiveConfig)
      backend         <- ContentAddressedCacheBackend.create(
                           targetPath,
                           compilerFp,
                           configFp,
                           effectiveConfig.get(FactKeyCodecs.configKey).getOrElse(Map.empty)
                         )
      _               <- progress.traverse_(_.enter(ProgressPhase.LoadingCache))
      seeded          <- phaseTimings.time(PhaseTimings.cacheLoad)(backend.load())
      _               <- progress.traverse_(_.cacheLoaded(seeded.exists(_.entries.nonEmpty)))
      cache           <- Ref.of[IO, Option[FactCacheData]](seeded)
      writing         <- Ref.of[IO, Option[ContentAddressedCacheBackend.WriteBehind]](None)
      lock            <- Mutex[IO]
    } yield new CompilationSession(
      targetPlugin,
      processors,
      effectiveConfig,
      targetPath,
      compilerFp,
      configFp,
      backend,
      cache,
      writing,
      lock,
      phaseTimings
    )
}
