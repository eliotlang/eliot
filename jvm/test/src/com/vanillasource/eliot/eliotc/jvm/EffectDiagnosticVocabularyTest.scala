package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The gate on the *diagnostic* side: an error a user reads speaks payload and effect-row vocabulary, never compiler
  * machinery.
  *
  * One case is *positive*: two control effects discharged at one run site compile and run, because each discharger
  * installs its own frame there and the nesting the user writes is the whole of the interaction. The last case is the
  * standing net that no message names the retired effect machinery.
  *
  * The failing programs here are compiled over the real base layer (`lang` + `stdlib` + `jvm`), because the shapes
  * only arise against the platform's own implementations.
  */
class EffectDiagnosticVocabularyTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  /** An effect performed in a definition that declares none — the everyday "I forgot the `uses` clause" mistake. The
    * scope check decides it from declarations and reports it at `helper`, naming the effect.
    */
  private val pureReturnLeak =
    """def orEmpty(o: Option[String]): String = o.orAbort else ""
      |
      |def helper: String = printLine(orEmpty(readLine))
      |
      |def main uses Console: Unit = printLine(helper)
      |""".stripMargin

  /** Two control effects, discharged at one run site. Written without dot-chaining on purpose, so what is exercised is
    * the nesting itself.
    */
  private val stackedControlEffects =
    """def counted uses State[String], Throw[String]: String = {
      |   putState("seen")
      |   raise("boom")
      |}
      |
      |def main uses Console: Unit =
      |   printLine(foldEither(e -> e, s -> s, runStateToValue("i", runThrow(counted))))
      |""".stripMargin

  // Two control effects discharged at one run site: each discharger installs its own frame there, so the program
  // needs no instance per pair of effects and simply compiles and runs.
  "two control effects discharged at one run site" should "need no cross-lift and run" in {
    compileToRun(stackedControlEffects).asserting(_ shouldBe "boom")
  }

  "an undeclared effect under a pure return" should "read as an undeclared effect at the definition" in {
    compileErrors(pureReturnLeak).asserting(
      _.mkString should include("performs the effect 'Console' but does not declare it")
    )
  }

  // The standing net: it names the retired effect machinery, and is what would notice it re-entering user-facing text.
  it should "name no machinery" in {
    compileErrors(pureReturnLeak).asserting(errors =>
      errors.mkString should (not include "Carrier" and not include "ability 'Suspend'" and not include "Id[")
    )
  }

  /** Compile and run the program (module `Test`) over the base layer roots, returning its standard output. The one
    * program here that is *expected to compile* needs this; every other asserts on [[compileErrors]].
    */
  private def compileToRun(source: String): IO[String] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-diag-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-diag-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      _          <- IO.raiseWhen(result.errors.nonEmpty)(
                      new IllegalStateException(s"Expected the program to compile: ${result.errors.map(_.message)}")
                    )
      output     <- runJar(targetDir.resolve("Test.jar"))
    } yield output

  private def runJar(jar: Path): IO[String] =
    IO.blocking {
      val out     = new java.io.ByteArrayOutputStream()
      val process = new ProcessBuilder("java", "-jar", jar.toString).redirectErrorStream(true).start()
      process.getInputStream.transferTo(out)
      process.waitFor()
      out.toString(java.nio.charset.StandardCharsets.UTF_8).trim
    }

  /** Compile the program (module `Test`) over the base layer roots and return everything the user is shown for each
    * error — its message *and* its description lines, which is where the `Expected:` / `Actual:` types live. Never
    * raises on the errors themselves, since every program here is expected to fail.
    */
  private def compileErrors(source: String): IO[Seq[String]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-diag-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-diag-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      _          <- IO.raiseWhen(result.errors.isEmpty)(new IllegalStateException("Expected the program not to compile."))
    } yield result.errors.flatMap(error => error.message +: error.description)

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
