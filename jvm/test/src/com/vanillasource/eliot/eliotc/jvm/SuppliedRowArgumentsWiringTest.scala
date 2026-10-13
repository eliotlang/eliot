package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The **supplied-row-arguments check** wired as a codegen precondition (`docs/effects.md` §2.2, §3.3):
  * `WovenValueProcessor` demands `SuppliedRowArguments` via `getFactOrAbort`, so a call supplying a row entry nothing
  * determines aborts the check and its abort blocks codegen — a discharger never reaches bytecode installing a frame
  * the computation it discharges will not exit to.
  *
  * The suite is what is left of `EffectAccountingWiringTest` after D7 (`docs/effects.md` §11) retired this seam's
  * post-mono effect verifier. Its two "performs the effect 'X' but does not declare it" cases were never this
  * processor's: they are the pre-mono scope check's, reported at the reference, and are covered where that check is —
  * `EffectDiagnosticVocabularyTest` (Console) and `TerminationIntegrationTest` (Inf).
  */
class SuppliedRowArgumentsWiringTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  // A valid effectful program: `catch` discharges `{Throw[String]}` at its own slot, and the write takes `E := String`
  // from `parseOk`/`parseBad`'s own declared row, so every supplied entry is determined. The check must accept it.
  private val validSource =
    """def parseOk uses Throw[String]: String = fold(true, "ok", raise("bad"))
      |def parseBad uses Throw[String]: String = raise("bad")
      |def main uses Console: Unit = {
      |   printLine(parseOk catch (err -> err))
      |   printLine(parseBad catch (err -> err))
      |}
      |""".stripMargin

  // An over-discharge: the inner `catch` already discharged `Throw[String]`, so the outer one's computation declares
  // no row at all — and its handler ignores the error, so nothing else determines what it supplies either. Rejected
  // at the call rather than compiled into a frame keyed on the universe.
  private val overDischargedSource =
    """def parseBad uses Throw[String]: String = raise("bad")
      |def main uses Console: Unit = printLine((parseBad catch (err -> "inner")) catch (err -> "outer"))
      |""".stripMargin

  "the wired supplied-row-arguments check" should "not block a valid effectful program (parity)" in {
    compileErrors(validSource).asserting(_ shouldBe empty)
  }

  it should "reject a call supplying a row entry nothing determines" in {
    compileErrors(overDischargedSource)
      .asserting(_.exists(_.contains("Cannot tell which 'Throw' this call supplies")) shouldBe true)
  }

  /** Compile the program (module `Test`) over the base layer and return the diagnostic messages. */
  private def compileErrors(source: String): IO[Seq[String]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("supplied-row-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("supplied-row-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        =
        List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
    } yield result.errors.map(_.message)

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
