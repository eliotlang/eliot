package com.vanillasource.eliot.eliotc.plugin

import cats.data.StateT
import cats.effect.IO
import com.vanillasource.eliot.eliotc.processor.{CompilerProcessor, FactDemand}
import com.vanillasource.eliot.eliotc.progress.{ProgressDescriber, ProgressMeasure}
import scopt.{OParser, OParserBuilder}

import scala.annotation.unused

trait CompilerPlugin {
  def commandLineParser(): OParser[?, Configuration] = OParser.builder.programName("eliotc")

  def isSelectedBy(configuration: Configuration): Boolean = false

  /** The word this plugin's command line starts with when it is a backend (`jvm`), or `None` for a plugin that is not
    * one. Together with [[backendModes]] it is what lets a command line leave the backend out: see
    * [[com.vanillasource.eliot.eliotc.compiler.Compiler.withDefaultBackend]].
    */
  def backendWord: Option[String] = None

  /** The mode words this backend accepts right after [[backendWord]] (`exe-jar`, `run`). A command line that *starts*
    * with one of them, and names no backend, is this backend's when no other backend accepts the same word.
    */
  def backendModes: Seq[String] = Seq.empty

  /** How `--progress` names what a run of this target builds, as the header's parts after the compiler's own name —
    * `Seq("jvm exe-jar", "HelloWorld")` reads `eliot · jvm exe-jar · HelloWorld`. Only the selected target is asked.
    */
  def progressTarget(@unused configuration: Configuration): Seq[String] = backendWord.toSeq

  /** How `--progress` names the work on the fact keys this plugin owns (`checking eliot.lang.String`). Every discovered
    * plugin is asked, so a plugin describes only its own keys.
    */
  def progressDescriber: Option[ProgressDescriber] = None

  /** What `--progress` reports of the artefact this target produced (`HelloWorld.jar 412 KB`), once a compilation
    * succeeded and before [[execute]]. Only the selected target is asked; each measure's name must be unique among its
    * measures, since the next run's change is worked out by name.
    */
  def progressMeasures(@unused configuration: Configuration): IO[Seq[ProgressMeasure]] = IO.pure(Seq.empty)

  def pluginDependencies(@unused configuration: Configuration): Seq[Class[? <: CompilerPlugin]] = Seq.empty

  def configure(): StateT[IO, Configuration, Unit] = StateT.empty

  def initialize(@unused configuration: Configuration): StateT[IO, CompilerProcessor, Unit]

  /** Drive one compilation by demanding whatever this plugin was selected to produce, returning whether that actually
    * came out. A target that declines without erroring (an abort with no errors) produces nothing and must not read as
    * a successful build — so this, not just the error count, is what decides the compiler's exit code. Plugins with no
    * single artefact to produce (a whole-workspace check) always succeed.
    */
  def run(@unused configuration: Configuration, @unused compilation: FactDemand): IO[Boolean] = IO.pure(true)

  /** What the selected target does with what it produced, once the compilation has succeeded and its diagnostics and
    * cache are written — the compiler's exit code is its result. A backend's `run` mode executes its own artefact here,
    * which is what makes "compile, then run what came out" one invocation rather than two steps somebody has to chain.
    * Everything that only produces an artefact has nothing left to do and succeeds.
    */
  def execute(@unused configuration: Configuration): IO[Int] = IO.pure(0)
}
