package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** A **compile-succeeds** gate for the shapes where a slot meets something carrier-headed and the carrier must
  * neither be stolen nor lost. All three were regressions once, under successive mechanisms; the programs are what
  * must keep working, whichever mechanism decides them (today: the row elaborator, from the slot's declaration).
  *
  *   - **mixed conditional arms** — `if(c, None) else Some(x)` puts a pure `None : Option[?E]` beside a
  *     `Some(x) : Option[Int]`. The pure arm has to lift into the carrier's *payload* slot so the sibling decides the
  *     element type; unifying the whole type instead stole the carrier (`?G := Option`) and failed.
  *   - **an effectful value into a data slot** — `items : {Console} List[String]` passed to `foldLeft`'s
  *     `list : List[A]`. The effect runs at the call site and the `List[String]` payload fills `List[A]`
  *     (`A := String`); stealing the carrier here (`?F := List`) left `Effect[List]` with no instance and forced a
  *     `val` workaround.
  *   - **a `where` precondition over a carried argument** — the refinement must see the argument's range *through*
  *     the carrier, so an out-of-range literal is still rejected (this one asserts the error, not a clean compile).
  */
class CarrierSlotCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

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

  // Effects-as-channel §6/§10 (U4-e prerequisite): a post-mono MonomorphicValue consumer must see through the uniform
  // path's pervasive `Id`. `useByte(1000)`'s argument range `[1000,1000]` sits inside `pure@Effect[Id]( 1000 )`; before
  // the refinement channel normalized `Id` away it read "meta-information is not known here" (a wrong diagnostic) instead of
  // the bound violation. This pins that the range survives the `Id` wrapper under the uniform-carrier checker.
  private val refinementSource =
    """import eliot.effect.Console
      |
      |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
      |def useByte(x: Int): Int where withinByte(range(x)) = x
      |
      |def main uses Console: Unit = printLine(show(useByte(1000)))
      |""".stripMargin

  "a where-precondition over a uniform-carriered argument" should "see the argument's range through the Id wrapper" in {
    compileErrors(refinementSource)
      .asserting(_.map(_.message).mkString should include("is not satisfied"))
  }

  "if(c, None) else Some(x)" should "compile (the CarrierSlot arm pure-wraps instead of stealing the carrier)" in {
    compileErrors(conditionalSource).asserting(_ shouldBe empty)
  }

  "an effectful list into foldLeft's List[A]" should "compile (the payload binds, the carrier is split off first)" in {
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
