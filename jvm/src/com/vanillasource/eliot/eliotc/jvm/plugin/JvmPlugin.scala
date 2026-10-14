package com.vanillasource.eliot.eliotc.jvm.plugin

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactKeyCodecs
import com.vanillasource.eliot.eliotc.jvm.codec.JvmFactCodecs
import com.vanillasource.eliot.eliotc.plugin.LangPlugin
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import com.vanillasource.eliot.eliotc.jvm.jargen.{
  GenerateExecutableJar,
  JvmProgramGenerator,
  EntryPointTargetProcessor,
  SyntheticMainMount,
  SyntheticMainSourceProcessor
}
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.compiler.cache.OutputFileStatProcessor
import com.vanillasource.eliot.eliotc.jvm.classgen.processor.JvmClassGenerator
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.Configuration.{demandScopedKey, diagnosticKey}
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration}
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerProcessor}
import com.vanillasource.eliot.eliotc.progress.{ProgressDescriber, ProgressMeasure}
import com.vanillasource.eliot.eliotc.source.scan.PathScanner
import scopt.{OParser, OParserBuilder}

import java.nio.file.{Files, Path}

class JvmPlugin extends CompilerPlugin {
  private val cmdLineBuilder: OParserBuilder[Configuration] = OParser.builder[Configuration]
  import cmdLineBuilder.*

  // Demand-scoped, not contributing: `-m` selects the monomorphization root, so two examples over the same roots and
  // backend compute identical values for every shared fact and may share one accumulating cache file. The main enters
  // the fact graph only through the `EntryPointTarget` leaf, which is always regenerated and equality-checked, so a
  // shared cache self-heals per main (see Configuration.demandScopedKey).
  private val mainKey = demandScopedKey[ValueFQN]("mainFunction")

  // Diagnostic in the key's sense — it steers what happens *after* the jar exists and changes no fact — so `run` and
  // `exe-jar` over the same roots and main share one cache and one jar.
  private val runKey = diagnosticKey[Unit]("runProduced")

  private def mainOption =
    opt[String]('m', "main-module")
      .required()
      .text("module that has a suitable main method")
      .action((moduleName, config) => config.set(mainKey, ValueFQN(ModuleName.parse(moduleName), QualifiedName("main", Qualifier.Default))))

  override def commandLineParser(): OParser[?, Configuration] = OParser.sequence(
    cmd("jvm")
      .text("target the jvm backend")
      .children(
        cmd("exe-jar")
          .text("generate executable jar")
          .children(mainOption),
        cmd("run")
          .text("generate executable jar, then run it, exiting with its exit code")
          .action((_, config) => config.set(runKey, ()))
          .children(mainOption)
      ),
    // Program arguments (`… -- a b`) belong to the program the `run` mode starts; `exe-jar` starts none, and arguments
    // it silently dropped would look like they had been honoured. Guarded by `mainKey` so it speaks only for this target.
    checkConfig(config =>
      if (config.contains(mainKey) && config.contains(Compiler.programArgumentsKey) && !config.contains(runKey))
        failure("program arguments (after `--`) are only passed by the `run` mode; `exe-jar` starts no program")
      else success
    )
  )

  override def backendWord: Option[String] = Some("jvm")

  override def backendModes: Seq[String] = Seq("exe-jar", "run")

  override def progressDescriber: Option[ProgressDescriber] = Some(JvmProgressDescriber)

  override def progressTarget(configuration: Configuration): Seq[String] =
    Seq(if (configuration.contains(runKey)) "jvm run" else "jvm exe-jar") ++
      configuration.get(mainKey).map(_.moduleName.show)

  /** The size of the jar, named by its file. */
  override def progressMeasures(configuration: Configuration): IO[Seq[ProgressMeasure]] =
    configuration
      .get(mainKey)
      .map(JvmProgramGenerator.jarFilePath(configuration.get(Compiler.targetPathKey).get, _))
      .toSeq
      .traverse(jar =>
        IO.blocking(ProgressMeasure(jar.getFileName.toString, Files.size(jar), ProgressMeasure.Quantity.Bytes))
      )

  /** Mount the synthesized `main.els` entry-point module into the runtime scan pool. It runs the user's `main` with a
    * `with` per effect of its clause ([[com.vanillasource.eliot.eliotc.row.EntryPoint]]), so it is an ordinary
    * definition: nothing about it is exempt from the scope check.
    */
  override def configure(): StateT[IO, Configuration, Unit] =
    StateT.modify(configuration =>
      (if (configuration.contains(mainKey))
        configuration
          .updatedWith(
            PathScanner.extraRuntimeMountsKey,
            mounts => (mounts.getOrElse(Seq.empty) :+ new SyntheticMainMount).some
          )
      else configuration)
        .updatedWith(
          FactKeyCodecs.configKey,
          codecs => (codecs.getOrElse(Map.empty) ++ JvmFactCodecs.keyCodecs).some
        )
    )

  override def initialize(configuration: Configuration): StateT[IO, CompilerProcessor, Unit] =
    StateT
      .modify(superProcessor =>
        SequentialCompilerProcessors(
          Seq(
            superProcessor,
            OutputFileStatProcessor(),
            JvmClassGenerator(),
            JvmProgramGenerator(configuration.get(Compiler.targetPathKey).get)
          ) ++ configuration
            .get(mainKey)
            .toSeq
            .flatMap(main => Seq(EntryPointTargetProcessor(main), SyntheticMainSourceProcessor()))
        )
      )

  override def isSelectedBy(configuration: Configuration): Boolean = configuration.contains(mainKey)

  override def pluginDependencies(configuration: Configuration): Seq[Class[? <: CompilerPlugin]] = Seq(
    classOf[LangPlugin],
    classOf[StdlibPlugin]
  )

  override def run(configuration: Configuration, compilation: CompilationProcess): IO[Boolean] =
    compilation.getFact(GenerateExecutableJar.Key(configuration.get(mainKey).get)).map(_.isDefined)

  /** The `run` mode: `java -jar` on the jar this compilation produced, with this process's streams and working
    * directory, answering the program's own exit code. The JVM is the one running the compiler, so a program is run by
    * the same runtime its compiler was, with nothing looked up on the `PATH`. The program receives the command line's
    * arguments after `--` ([[Compiler.programArgumentsKey]]), which is what `Environment.arguments` reads back.
    */
  override def execute(configuration: Configuration): IO[Int] =
    (configuration.get(runKey), configuration.get(mainKey)) match {
      case (Some(_), Some(main)) =>
        val jar     = JvmProgramGenerator.jarFilePath(configuration.get(Compiler.targetPathKey).get, main)
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
        val command = Seq(javaBin, "-jar", jar.toString) ++ configuration.get(Compiler.programArgumentsKey).getOrElse(Seq.empty)
        IO.interruptible(new ProcessBuilder(command*).inheritIO().start().waitFor())
      case _                     => IO.pure(0)
    }
}

object JvmPlugin
