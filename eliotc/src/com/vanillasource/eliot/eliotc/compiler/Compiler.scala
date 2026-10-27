package com.vanillasource.eliot.eliotc.compiler

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.cache.CacheFingerprint
import com.vanillasource.eliot.eliotc.feedback.{Logging, User}
import com.vanillasource.eliot.eliotc.plugin.Configuration.{diagnosticKey, namedKey}
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration}
import com.vanillasource.eliot.eliotc.progress.{
  ProgressDescriber,
  ProgressLineWriter,
  ProgressMeasure,
  ProgressPhase,
  ProgressProfile,
  ProgressStyle,
  ProgressTracker
}
import com.vanillasource.eliot.eliotc.statistics.ProcessorStatistics
import com.vanillasource.eliot.eliotc.visualization.FactVisualizationTracker
import scopt.{DefaultOEffectSetup, OParser, OParserBuilder}

import java.nio.file.Path
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

object Compiler extends Logging {
  val targetPathKey: Configuration.Key[Path]     = namedKey[Path]("targetPath")
  // Diagnostic-only: these turn on observation (a fact-flow graph, per-processor timing) without changing any fact, so
  // they must not enter the cache identity — otherwise a `--statistics` run could never observe a warm build.
  val visualizeFactsKey: Configuration.Key[Path] = diagnosticKey[Path]("visualizeFacts")
  val statisticsKey: Configuration.Key[Unit]     = diagnosticKey[Unit]("statistics")
  val progressKey: Configuration.Key[Unit]       = diagnosticKey[Unit]("progress")

  /** The arguments a target hands to the program it runs: everything after the first `--` on the command line, absent
    * when there are none. Diagnostic in the key's sense — the program's own command line changes no fact — so it
    * never enters the cache identity, and a run with other arguments shares the jar of one without.
    */
  val programArgumentsKey: Configuration.Key[Seq[String]] = diagnosticKey[Seq[String]]("programArguments")

  /** What separates the compiler's own command line from the program's: `run -m Main src/ -- a b` runs `Main` with the
    * arguments `a b`. Everything after the first separator is the program's, verbatim, a second `--` included.
    */
  val programArgumentsSeparator: String = "--"

  /** The compiler's own arguments and the program's, split at the first [[programArgumentsSeparator]]. A line without
    * one is all the compiler's.
    */
  def splitProgramArguments(args: List[String]): (List[String], List[String]) =
    args.span(_ != programArgumentsSeparator) match {
      case (own, _ :: program) => (own, program)
      case (own, _)            => (own, Nil)
    }

  /** Run the compiler, returning the process's exit code: an error when the compilation *failed* — it produced errors,
    * or its target produced no artefact — and otherwise whatever the selected target's
    * [[com.vanillasource.eliot.eliotc.plugin.CompilerPlugin.execute]] answers, which is success for everything that only
    * produces an artefact. The CLI ([[Main]]) exits with it so callers (scripts, CI, a build tool, the IntelliJ before-run
    * build task) can gate on it. `--help` exits 0; a command line the parser *refuses* does not, or a build tool
    * running a compiler that rejects its line would read the refusal as a green build. A missing target plugin is a
    * failure too.
    */
  def runCompiler(args: List[String]): IO[ExitCode] =
    for {
      plugins   <- allLayers()
      // The program's arguments are cut off before parsing: the parser's unbounded `<path>...` would take them.
      (ownArgs, programArgs) = splitProgramArguments(args)
      // Run command line parsing with all options from all layers
      parsed    <- parseCommandLine(
                     withDefaultBackend(ownArgs, plugins),
                     plugins.map(_.commandLineParser()),
                     programArgs
                   )
      exitCode  <- parsed match {
                     case Left(code)          => IO.pure(code)
                     case Right(configuration) =>
                       runWithConfiguration(configuration, plugins)
                   }
    } yield exitCode

  /** The command line with the backend word filled in, when it was left out and only one backend could have been meant.
    *
    * A line that starts with a mode word (`run -m Main`, `exe-jar -m Main`) rather than a backend word names no
    * backend, and it is the one backend on the classpath accepting that mode which runs it. That is what lets a
    * platform-independent package — a test framework — say "compile with this main and run it" without naming a
    * platform, and leaves the platform to whichever backend the consumer's closure put on the classpath. Two backends
    * accepting the mode, or none, leave the line as it is, and the parser then reports it the way it reports any line
    * it does not understand; a line that does name its backend is never touched.
    */
  def withDefaultBackend(args: List[String], plugins: Seq[CompilerPlugin]): List[String] =
    args match {
      case first :: _ if !plugins.flatMap(_.backendWord).contains(first) =>
        plugins.filter(_.backendModes.contains(first)).flatMap(_.backendWord) match {
          case Seq(backend) => backend :: args
          case _            => args
        }
      case _                                                              => args
    }

  /** Build a resident [[CompilationSession]] for `args` without running it: discover plugins, parse the command line,
    * select the target plugin, and do the one-time session setup (configuring the processor graph, seeding the cache
    * from disk). Returns `None` when the arguments do not select a runnable compilation — help/parse termination, or no
    * target plugin (the latter already reported here as a global error).
    *
    * This is the seam a long-running host drives directly: a server (or the integration-test harness) calls
    * [[CompilationSession.compileOnce]] on the returned session repeatedly, reusing the warm in-memory fact cache
    * instead of paying a cold start — plugin discovery plus a full base-layer recompile — on every compilation. The CLI
    * keeps using [[runCompiler]], which drives one compile then persists the cache and prints diagnostics.
    */
  def createSession(args: List[String]): IO[Option[CompilationSession]] =
    for {
      plugins   <- allLayers()
      parsed    <- parseCommandLine(args, plugins.map(_.commandLineParser()))
      session   <- parsed.toOption.flatTraverse(sessionFor(_, plugins))
    } yield session

  private def sessionFor(
      configuration: Configuration,
      plugins: Seq[CompilerPlugin],
      progress: Option[ProgressTracker] = None
  ): IO[Option[CompilationSession]] =
    // Select active plugins
    plugins.find(_.isSelectedBy(configuration)) match {
      case None               =>
        User.compilerGlobalError("No target plugin selected.").as(None)
      case Some(targetPlugin) =>
        val activatedPlugins = collectActivatedPlugins(targetPlugin, configuration, plugins)
        for {
          _       <- debug[IO](s"Selected target plugin: ${targetPlugin.getClass.getSimpleName}")
          _       <-
            debug[IO](s"Selected active plugins: ${activatedPlugins.map(_.getClass.getSimpleName).mkString(", ")}")
          // One-time setup: configure plugins, collect processors, seed the cache from disk
          session <- CompilationSession.create(targetPlugin, activatedPlugins, configuration, progress)
        } yield Some(session)
    }

  private def runWithConfiguration(
      configuration: Configuration,
      plugins: Seq[CompilerPlugin]
  ): IO[ExitCode] =
    for {
      // Start the clock before session setup so the total spans the whole lifecycle: the cache load and the
      // fingerprint digest happen inside `sessionFor` (before any compile), and `--statistics` accounts for them.
      started  <- IO.monotonic
      // The profile is keyed by the command line's configuration, which is known before any plugin configures a session
      profile   = Option.when(configuration.contains(progressKey))(
                    ProgressProfile.fileIn(configuration.get(targetPathKey).get, CacheFingerprint.config(configuration))
                  )
      previous <- profile.traverse(ProgressProfile.read)
      describer = ProgressDescriber.combined(plugins.flatMap(_.progressDescriber))
      progress <- previous.traverse(ProgressTracker.create(_, describer))
      style    <- ProgressStyle.detect
      writer   <- progress.traverse(ProgressLineWriter.create(_, style))
      target    = plugins.find(_.isSelectedBy(configuration)).toSeq.flatMap(_.progressTarget(configuration))
      // Progress lines are printed from session setup until the cache is persisted, and stop before the diagnostics
      compiled <- writer
                    .traverse_(_.lines(target))
                    .surround(sessionFor(configuration, plugins, progress).flatMap(_.traverse(compile(_, progress))))
      finished <- IO.monotonic
      exitCode <- compiled match {
                    case None           => IO.pure(ExitCode.Error) // no target plugin — reported by `sessionFor`
                    case Some(compiled) =>
                      import compiled.*
                      val visualizationPath = session.effectiveConfiguration.get(visualizeFactsKey)

                      for {
                        _        <- debug[IO]("Compiler exiting normally.")
                        // Print the compiler errors
                        _        <- result.errors.traverse_(_.print())
                        // Generate visualization if requested
                        _        <- (tracker, visualizationPath).tupled.traverse_(_.generateVisualization(_))
                        // Print where the time went in this run, if requested — including the coarse cache phases
                        phases   <- session.phaseSnapshot
                        _        <- statistics.traverse_(_.report(finished - started, phases).flatMap(Console[IO].println))
                        measures <- if (result.succeeded && progress.isDefined) measuresOf(session)
                                    else IO.pure(Seq.empty)
                        // The closing progress line comes last, so a `run` mode's program output follows a finished log
                        _        <- writer.traverse_(
                                      _.close(
                                        target,
                                        result.errors,
                                        result.targetProduced,
                                        measures,
                                        previous.fold(Map.empty)(_.measures)
                                      )
                                    )
                        // Only a run that succeeded teaches the next one: a failed one stops short of its total
                        _        <- (profile, previous, progress).tupled
                                      .traverse_(saveProfile(_, _, _, measures))
                                      .whenA(result.succeeded)
                        _        <- progress.traverse_(_.enter(ProgressPhase.Running))
                        // Only a compilation that produced what it was asked for gets to do anything with it
                        exitCode <- if (result.succeeded) session.execute().map(ExitCode(_))
                                    else IO.pure(ExitCode.Error)
                      } yield exitCode
                  }
    } yield exitCode

  /** Record in the profile what this run delivered, as the total the next run is measured against, and where its time
    * went, in the history of its class.
    */
  private def saveProfile(
      file: Path,
      previous: ProgressProfile,
      progress: ProgressTracker,
      measures: Seq[ProgressMeasure]
  ): IO[Unit] =
    progress.snapshot.flatMap(snapshot =>
      snapshot.runClass.traverse_(runClass =>
        ProgressProfile.write(file, previous.including(snapshot.delivered, runClass, snapshot.history, measures))
      )
    )

  /** What the target measures of what it produced. Fail-safe: a measure that cannot be taken costs the closing line its
    * sizes, never the run its result.
    */
  private def measuresOf(session: CompilationSession): IO[Seq[ProgressMeasure]] =
    session
      .progressMeasures()
      .handleErrorWith(t => warn[IO]("Could not measure what the run produced.", t).as(Seq.empty))

  /** Whether the compilation starts the facts its generations hint on threads of their own
    * (`docs/compiler-performance.md` §15). It does by default. `-Deliot.parallel=false` runs it sequentially, the run
    * every parallel step is measured against; it is a system property and not a flag, because a flag is a promise of
    * the command line. `--statistics` always runs sequentially, since the self time it reports is CPU time only when
    * nothing else runs.
    */
  private def parallel(statisticsAsked: Boolean): Boolean =
    !statisticsAsked && !"false".equalsIgnoreCase(System.getProperty("eliot.parallel"))

  /** A session's single compilation, with the instrumentation it ran under. */
  private case class Compiled(
      session: CompilationSession,
      result: CompilationResult,
      tracker: Option[FactVisualizationTracker],
      statistics: Option[ProcessorStatistics]
  )

  /** Run the (single, for the CLI) compilation and flush the resulting cache back to disk. */
  private def compile(session: CompilationSession, progress: Option[ProgressTracker]): IO[Compiled] = {
    val visualizationPath = session.effectiveConfiguration.get(visualizeFactsKey)
    val statisticsAsked   = session.effectiveConfiguration.contains(statisticsKey)

    for {
      // Both observe every processor invocation and every fact read, so neither is created unless
      // asked for: an ordinary build should not pay for a diagnostic it discards.
      tracker    <- visualizationPath.traverse(_ => FactVisualizationTracker.create())
      statistics <- Option.when(statisticsAsked)(ProcessorStatistics.create()).sequence
      _          <- debug[IO]("Compiler starting...")
      result     <- session.compileOnce(tracker, statistics, progress, writeBehind = true, parallel = parallel(statisticsAsked))
      _          <- session.persist()
    } yield Compiled(session, result, tracker, statistics)
  }

  private def collectActivatedPlugins(
      initialPlugin: CompilerPlugin,
      configuration: Configuration,
      all: Seq[CompilerPlugin]
  ): Seq[CompilerPlugin] =
    LazyList.unfold(Seq(initialPlugin))(ps =>
      ps.headOption.map(plugin => plugin -> (ps.tail ++ resolvePlugins(plugin.pluginDependencies(configuration), all)))
    )

  private def resolvePlugins(classes: Seq[Class[? <: CompilerPlugin]], all: Seq[CompilerPlugin]): Seq[CompilerPlugin] =
    all.filter(p => classes.contains(p.getClass))

  private def allLayers(): IO[Seq[CompilerPlugin]] = IO.blocking {
    ServiceLoader
      .load(classOf[CompilerPlugin])
      .iterator()
      .asScala
      .toSeq
  }

  private def baseOptions() = {
    val cmdLineBuilder: OParserBuilder[Configuration] = OParser.builder[Configuration]
    import cmdLineBuilder.*

    OParser.sequence(
      programName("eliotc"),
      help("help").text("prints this help text"),
      opt[Path]('o', "output-dir")
        .text("the directory any output should be written")
        .action((path, config) => config.set(targetPathKey, path)),
      opt[Path]("visualize-facts")
        .text("generate an HTML visualization of fact generation flow")
        .action((path, config) => config.set(visualizeFactsKey, path)),
      opt[Unit]("statistics")
        .text("print how much time each processor took after the run")
        .action((_, config) => config.set(statisticsKey, ())),
      opt[Unit]("progress")
        .text("print how far along the run is while it works, to stderr")
        .action((_, config) => config.set(progressKey, ()))
    )
  }

  /** The configuration the command line asks for, or the exit code the parser's answer is: 0 where it terminated on
    * its own terms (`--help`), and 1 where it refused the line.
    */
  private def parseCommandLine(
      args: Seq[String],
      options: Seq[OParser[?, Configuration]],
      programArgs: Seq[String] = Seq.empty
  ): IO[Either[ExitCode, Configuration]] = IO.blocking {
    // Seeded before parsing, so a plugin's `checkConfig` can refuse a program argument its mode has nowhere to send.
    val initial           = Configuration().set(targetPathKey, Path.of("target"))
    val (result, effects) = OParser.runParser(
      OParser.sequence(baseOptions(), options*),
      args,
      if (programArgs.isEmpty) initial else initial.set(programArgumentsKey, programArgs)
    )

    var terminated: Option[ExitCode] = None

    OParser.runEffects(
      effects,
      new DefaultOEffectSetup {
        override def terminate(exitState: Either[String, Unit]): Unit =
          terminated = Some(exitState.fold(_ => ExitCode.Error, _ => ExitCode.Success))
      }
    )

    terminated.toLeft(()).flatMap(_ => result.toRight(ExitCode.Error))
  }

}
