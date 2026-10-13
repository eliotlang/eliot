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
  * Under effects v6 there is no carrier to leak into a message — the machinery the v5 version of this suite guarded
  * against (`AbortCarrier`, `ThrowCarrier`, the identity carrier `Id`, and the one inverter that rendered a stack back
  * as a pinned row) is deleted, and with it the two shapes that used to *need* the inverter: a carrier-stack ability
  * demand, and a pure-base `Id` row a side effect could not run on. Those cases are gone rather than rewritten,
  * because their subject is; what is left is the claim itself, which outlives the mechanism, plus the standing net
  * that no message names machinery.
  *
  * One of them is now a *positive* case: the `State`-over-`Throw` program that used to fail for want of a cross-lift
  * compiles and runs, because frames nest at the run site rather than needing an instance per layer pair.
  *
  * The failing programs here are compiled over the real base layer (`lang` + `stdlib` + `jvm`), because the shapes
  * only arise against the platform's own implementations.
  */
class EffectDiagnosticVocabularyTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  /** An effect performed under a return that cannot host a carrier — the everyday "I forgot the effect set" mistake.
    * The per-definition row verification decides it from declarations and reports it at `helper`, in effect
    * vocabulary; before A.11.6 it surfaced only after a failed monomorphization, as "performs an effect but is
    * declared pure", without naming the effect.
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

  // Two control effects stacked. Under v5 this failed for want of a cross-lift instance (`State` over a `Throw`
  // layer), and its diagnostic was the everyday way a *carrier stack* reached a user. There is no stack now: each
  // discharger installs its own frame at the run site, and the nesting the user writes there is the whole of the
  // interaction — so the program simply compiles and runs.
  "two control effects discharged at one run site" should "need no cross-lift and run" in {
    compileToRun(stackedControlEffects).asserting(_ shouldBe "boom")
  }

  "an undeclared effect under a pure return" should "read as an effect leak at the definition, in row vocabulary" in {
    compileErrors(pureReturnLeak).asserting(
      _.mkString should include("performs the effect 'Console' but does not declare it")
    )
  }

  // The standing net. It is trivially satisfied now that no carrier exists, and it stays exactly for that reason: it
  // is what would notice machinery re-entering user-facing text.
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
