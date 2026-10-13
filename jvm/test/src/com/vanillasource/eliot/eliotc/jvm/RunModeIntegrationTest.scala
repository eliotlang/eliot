package com.vanillasource.eliot.eliotc.jvm

import cats.effect.{ExitCode, IO}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The `run` mode through the compiler's own entry point: compile, then execute what came out, and exit with the
  * program's exit code — named with its backend, and without one, the way a platform-independent package's `compiler`
  * line writes it.
  *
  * Driven through [[Compiler.runCompiler]] rather than the shared resident session, because the exit code under test is
  * what `runCompiler` answers and the session never executes anything. Each case is a cold compile of the base layers,
  * so there are few of them.
  */
class RunModeIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  "the run mode" should "exit with the code the program registered" in {
    runCompiled(List("jvm", "run"), exitingWith(3)).asserting(_ shouldBe ExitCode(3))
  }

  it should "find the jvm backend when the line names none" in {
    runCompiled(List("run"), exitingWith(0)).asserting(_ shouldBe ExitCode.Success)
  }

  it should "fail without running anything when the program does not compile" in {
    runCompiled(List("run"), "def main: Unit = noSuchThing").asserting(_ shouldBe ExitCode.Error)
  }

  it should "start the program with the arguments after `--`" in {
    runCompiled(List("run"), exitingWithArgumentCount, List("a", "b", "c")).asserting(_ shouldBe ExitCode(3))
  }

  it should "start the program with no arguments when none follow" in {
    runCompiled(List("run"), exitingWithArgumentCount).asserting(_ shouldBe ExitCode.Success)
  }

  it should "pass the program's options and a second `--` through untouched" in {
    runCompiled(List("run"), exitingWithArgumentCount, List("--format=plain", "--", "-o")).asserting(_ shouldBe ExitCode(3))
  }

  "the exe-jar mode" should "only produce the jar, whatever the program would exit with" in {
    runCompiled(List("exe-jar"), exitingWith(3)).asserting(_ shouldBe ExitCode.Success)
  }

  it should "refuse program arguments, which it has nowhere to send" in {
    runCompiled(List("exe-jar"), exitingWith(0), List("a")).asserting(_ shouldBe ExitCode.Error)
  }

  private def runCompiled(mode: List[String], source: String, programArgs: List[String] = Nil): IO[ExitCode] =
    for {
      sourceDir <- IO.blocking(Files.createTempDirectory("eliot-run-src"))
      targetDir <- IO.blocking(Files.createTempDirectory("eliot-run-target"))
      _         <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      exitCode  <- Compiler.runCompiler(
                     mode ++ List("-m", "Test", sourceDir.toString, "-o", targetDir.toString) ++ layerPathArgs ++
                       (if (programArgs.isEmpty) Nil else "--" :: programArgs)
                   )
    } yield exitCode

  /** Exits with the number of arguments the program was started with. */
  private val exitingWithArgumentCount: String =
    """
      |import eliot.system.Process
      |import eliot.system.Environment
      |import eliot.collection.List
      |
      |def main uses Process, Environment: Unit = registerExitCode(arguments.size)
      |""".stripMargin

  private def exitingWith(code: Int): String =
    s"""
       |import eliot.system.Process
       |
       |def main uses Process: Unit = registerExitCode($code)
       |""".stripMargin

  private def layerPathArgs: List[String] = {
    val repoRoot             = Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
