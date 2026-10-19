package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** A **compile-succeeds** gate for the shapes where an argument meets a parameter slot whose type it must neither
  * take over nor lose. All three were regressions once, under successive mechanisms; the programs are what must keep
  * working, whichever mechanism decides them.
  *
  *   - **mixed conditional arms** — `if(c, None) else Some(x)` puts a `None : Option[?E]` beside a
  *     `Some(x) : Option[Int]`; the sibling arm decides the element type.
  *   - **an effectful value into a data slot** — `items`, declared `uses Console` with a `List[String]` result, passed
  *     to `foldLeft`'s `list : List[A]`. The effect runs at the call site (strict call-by-value) and the `List[String]`
  *     fills `List[A]` (`A := String`), with no `val` needed to separate them.
  *   - **a `where` precondition over an argument in an effectful body** — the refinement must see the literal
  *     argument's range, so an out-of-range literal is still rejected (this one asserts the error, not a clean
  *     compile).
  */
class ArgumentSlotCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val conditionalSource =
    """def choose(c: Bool, x: Int): Option[Int] = if(c, None) else Some(x)
      |
      |def main uses Console: Unit = printLine(foldOption("none", v -> "some", choose(true, 5)))
      |""".stripMargin

  private val compoundStateSource =
    """import eliot.collection.List
      |
      |def items uses Console: List[String] = {
      |   printLine("loading...")
      |   empty
      |}
      |
      |def summary uses Console: String = foldLeft("start", x -> acc -> acc, items)
      |
      |def main uses Console: Unit = printLine(summary)
      |""".stripMargin

  // `useByte(1000)`'s argument range `[1000,1000]` must reach the `where` check as the bound violation, not as
  // "meta-information is not known here" (a wrong diagnostic it once read, when a wrapper hid the literal).
  private val refinementSource =
    """import eliot.effect.Console
      |
      |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
      |def useByte(x: Int): Int where withinByte(range(x)) = x
      |
      |def main uses Console: Unit = printLine(show(useByte(1000)))
      |""".stripMargin

  "a where-precondition over a literal argument in an effectful body" should "see the argument's range" in {
    compileErrors(refinementSource)
      .asserting(_.map(_.message).mkString should include("is not satisfied"))
  }

  "if(c, None) else Some(x)" should "compile (the sibling arm decides the element type)" in {
    compileErrors(conditionalSource).asserting(_ shouldBe empty)
  }

  "an effectful list into foldLeft's List[A]" should "compile (the effect runs at the call, the value binds A)" in {
    compileErrors(compoundStateSource).asserting(_ shouldBe empty)
  }

  /** Compile the program (module `Test`) over the base layer roots, returning the compilation diagnostics
    * (empty = success).
    */
  private def compileErrors(source: String): IO[Seq[CompilerError]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-cond-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-cond-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
    } yield result.errors

  /** The base-layer `eliot/src` source roots handed to the compiler as filesystem roots, exactly as
    * [[EffectShapeCompileTest]] does — the repo root reaches the forked test JVM via `ELIOT_REPO_ROOT`.
    */
  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
