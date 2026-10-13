package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap

/** The **seam-groundness** measurement, re-pointed at bindings for effects v6 (`docs/effects.md` §9.5, F2's "kept"
  * list — it was `EffectsV4SeamGroundnessTest`, which asked the same question of carriers).
  *
  * §9.5 says every binding is decided from `main` inward, so at the `WovenValue` codegen seam every operation
  * reference resolves to exactly one implementation. That claim has a mechanical form the compiler can be held to: a
  * definition's implementations are *phantom binders*, so they are ordinary type arguments of its mono key, and the
  * claim is that at the seam **no key argument is still a parameter**. Nothing there is waiting to be solved.
  *
  * The second half is the other side of the same coin: because a binding is part of the key, binding the *same*
  * definition to two different implementations yields **two instances**. Specialisation is not an optimisation added
  * later; it is what a phantom binder does.
  *
  * Two shapes, over the real `lang`/`stdlib`/`jvm` layers:
  *
  *   - **S1, a `{Console}` def** — one operation, one implementation, one instance;
  *   - **S2, a program that binds one definition twice** — the production default and a named `with`, which is the
  *     fake-implementation testing strategy in its smallest form.
  */
class EffectsSeamGroundnessTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import EffectsSeamGroundnessTest.*

  "a {Console} def (S1)" should "reach the seam with one instance" in {
    keysOf(consoleBlock, "echo").asserting(_.size shouldBe 1)
  }

  it should "key that instance by its implementation, not by nothing" in {
    keysOf(consoleBlock, "echo").asserting(_.head should not be empty)
  }

  "a definition bound twice (S2)" should "reach the seam as two instances, one per implementation" in {
    keysOf(boundTwice, "greet").asserting(_.size shouldBe 2)
  }

  "every instance at the seam" should "carry only ground type arguments" in {
    Seq(consoleBlock, boundTwice)
      .traverse(shape => monos(shape).map(_.flatMap(_.typeArguments).filter(hasParam)))
      .asserting(_.flatten shouldBe Seq.empty)
  }

  /** The mono keys of every instance of the value with this simple name — one entry per instance, so two instances of
    * the same definition at different implementations stay distinguishable.
    */
  private def keysOf(source: String, name: String): IO[Set[Seq[GroundValue]]] =
    monos(source).map(_.filter(_.vfqn.name.name === name).map(_.typeArguments).toSet)

  private def monos(source: String): IO[Seq[MonomorphicValue]] =
    compileFacts(source).map(_.values.collect { case mv: MonomorphicValue => mv }.toSeq)

  private def hasParam(value: GroundValue): Boolean = value match {
    case GroundValue.Param(_, _, _)      => true
    case GroundValue.Structure(_, as, _) => as.exists(hasParam)
    case _                               => false
  }

  /** Compile the program (as module `Test`) over the real base layers and return its fact map, caching per source so
    * the several assertions about one shape share a single compilation.
    */
  private def compileFacts(source: String): IO[Map[CompilerFactKey[?], CompilerFact]] =
    cache.get(source) match {
      case Some(facts) => IO.pure(facts)
      case None        => compile(source).flatTap(facts => IO.delay(cache.put(source, facts)).void)
    }

  private def compile(source: String): IO[Map[CompilerFactKey[?], CompilerFact]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-seam-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-seam-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      _          <- IO.raiseWhen(result.errors.nonEmpty)(
                      new IllegalStateException(s"Compilation errors: ${result.errors.map(_.message).mkString(", ")}")
                    )
      facts      <- result.generator.currentFacts()
    } yield facts

  /** The base-layer `eliot/src` source roots handed to the compiler as filesystem roots — the repo root reaches the
    * forked test JVM via `ELIOT_REPO_ROOT`.
    */
  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}

object EffectsSeamGroundnessTest {

  /** One compilation per shape, shared by that shape's assertions. */
  private val cache = TrieMap[String, Map[CompilerFactKey[?], CompilerFact]]()

  /** S1 — a `{Console}` def: `echo` names no implementation, so the one it runs on is the one `main`'s boundary binds. */
  private val consoleBlock =
    """def echo uses Console: Unit = {
      |   printLine("a")
      |   printLine("b")
      |}
      |
      |def main uses Console: Unit = echo
      |""".stripMargin

  /** S2 — one definition reached at two implementations of its own effect: the platform's default (through `main`'s
    * boundary) and a named one bound by `with`. Two bindings, therefore two mono keys, therefore two instances.
    */
  private val boundTwice =
    """effect Terminal {
      |   def write(line: String): Unit
      |}
      |
      |implement standard: Terminal {
      |   def write(line: String) uses Console: Unit = printLine(line)
      |}
      |
      |implement quiet: Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line)
      |}
      |
      |def greet uses Terminal: Unit = write("hello")
      |
      |def main uses Console: Unit = {
      |   greet with standard
      |   printLine(runWriterToLog(greet with quiet))
      |}
      |""".stripMargin
}
