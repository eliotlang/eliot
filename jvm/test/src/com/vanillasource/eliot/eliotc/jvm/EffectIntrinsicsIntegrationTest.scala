package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.{CompilationResult, Compiler}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.nio.file.{Files, Path}

/** End-to-end proof of the compile track's escape and cell intrinsics (effects v6 §10.1 step 7, `EffectIntrinsics`)
  * through the real evaluator paths: an ability guard is decided by a pure def that escapes, exits and threads a cell
  * — reduced on the compiler track through its own `Id`-elaborated body, the overlay's `foldEither`/`foldPair` and
  * the `Bool` `fold` twin — so each `handler[..]` site is bound to one implementation in the emitted bytecode.
  *
  * Until the flag day nothing in the layers declares the primitives, so each program declares them itself as body-less
  * defs in the modules the intrinsics are keyed on (`eliot.compiler.Escape` / `eliot.compiler.Cell`): the native
  * category answers for those FQNs regardless of which root declares them. **The instantiation is passed as a type
  * value** (`escape(String[], …)`), the one way the evaluator can key a frame, and the conditional arms are thunks
  * applied after selection — a native fires the moment it is applied, so a bare `exit` in a strict arm would fire
  * before `fold` chose, which is exactly why a post-flag-day row arm is a thunk. The cell holds a `Bool`: a body-less
  * leaf whose bare generic result is instantiated at a meta-carrying type (`read[S]` at `String`) must state a
  * transfer (R2), a question the flag day's overlay has to answer for these primitives.
  *
  * As in `ListGuardReductionIntegrationTest`, a pair of guarded implementations selected from one program is the
  * point: a guard that could not reduce would fail to select, not select wrongly.
  */
class EffectIntrinsicsIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import EffectIntrinsicsIntegrationTest.*

  private val primitives = Map(
    "eliot/compiler/Escape" ->
      """def escape[E, A](key: Type, body uses *: A): Either[E, A]
        |def exit[E, A](key: Type, e: E): A
        |""".stripMargin,
    "eliot/compiler/Cell"   ->
      """def withCell[S, A](key: Type, initial: S, body uses *: A): Pair[A, S]
        |def read[S](key: Type): S
        |def write[S](key: Type, s: S): Unit
        |""".stripMargin
  )

  /** A conditional arm as data, so `fold` selects between plain values and the chosen thunk is applied afterwards: a
    * native fires the moment it is applied, so `exit` may only sit in the arm that is *run*, never in one merely
    * *passed*. (A function-typed `Id` payload — `val f = fold(c, thunk1, thunk2)` — trips a compile-track mismatch of
    * its own, `Unit -> Bool` against `Function[Unit, Bool]` at the overlay `Id`'s accessor; unrelated to the primitives.)
    */
  private val prelude =
    """import eliot.effect.Console
      |import eliot.compiler.Escape
      |import eliot.compiler.Cell
      |ability Route[S: String] { def handler: String }
      |data Arm(run: Function[Unit, Bool])
      |def pick(c: Bool, whenTrue: Arm, whenFalse: Arm): Bool = {
      |   val arm = fold(c, whenTrue, whenFalse)
      |   run(arm)(unit)
      |}
      |def keep[A, B](a: A, b: B): B = b
      |""".stripMargin

  private def program(body: String): Map[String, String] = primitives + ("Test" -> (prelude + body))

  "an ability guard that escapes" should "select by whether the body exited" in {
    runProject(
      program(
        """def accepted(s: String): Bool =
          |   foldEither(_ -> false, b -> b, escape(String[], pick(s == "/api", Arm(_ -> true), Arm(_ -> exit(String[], "no")))))
          |implement[S: String] Route[S] where accepted(S) { def handler: String = "hit" }
          |implement[S: String] Route[S] where !accepted(S) { def handler: String = "miss" }
          |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"])
          |""".stripMargin
      )
    ).asserting(_ shouldBe "hit/miss")
  }

  it should "let an exit pass through a frame of another instantiation to its own" in {
    runProject(
      program(
        """def inner(s: String): Bool =
          |   foldEither(_ -> false, b -> b, escape(Unit[], pick(s == "/api", Arm(_ -> exit(String[], "outer")), Arm(_ -> true))))
          |def accepted(s: String): Bool =
          |   foldEither(e -> e == "outer", _ -> false, escape(String[], inner(s)))
          |implement[S: String] Route[S] where accepted(S) { def handler: String = "outer" }
          |implement[S: String] Route[S] where !accepted(S) { def handler: String = "inner" }
          |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"])
          |""".stripMargin
      )
    ).asserting(_ shouldBe "outer/inner")
  }

  "an ability guard over a cell" should "thread a written content to a read and out of the frame" in {
    runProject(
      program(
        """def accepted(s: String): Bool =
          |   foldPair(a -> b -> a && b, withCell(Bool[], false, keep(write(Bool[], s == "/api"), read(Bool[]))))
          |implement[S: String] Route[S] where accepted(S) { def handler: String = "hit" }
          |implement[S: String] Route[S] where !accepted(S) { def handler: String = "miss" }
          |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"])
          |""".stripMargin
      )
    ).asserting(_ shouldBe "hit/miss")
  }
}

object EffectIntrinsicsIntegrationTest {

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }

  /** Compile `modules` (module path -> source, `a/b/C` for module `a.b.C`) as one program with main module `Test`, in
    * a fresh temp directory and session.
    */
  private def build(modules: Map[String, String]): IO[(CompilationResult, Path)] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-ei-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-ei-target"))
      _          <- modules.toList.traverse_ { (path, source) =>
                      IO.blocking {
                        val file = sourceDir.resolve(s"$path.els")
                        Files.createDirectories(file.getParent)
                        Files.writeString(file, source)
                      }
                    }
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
    } yield (result, targetDir.resolve("Test.jar"))

  private def runProject(modules: Map[String, String]): IO[String] =
    build(modules).flatMap { case (result, jarPath) =>
      if (result.errors.isEmpty) runJar(jarPath)
      else IO.raiseError(new IllegalStateException(s"compile failed:\n${result.errors.map(_.message).mkString("\n")}"))
    }

  private def runJar(jarPath: Path): IO[String] = IO.blocking {
    val classLoader = new URLClassLoader(Array(jarPath.toUri.toURL), ClassLoader.getPlatformClassLoader)
    try {
      val mainMethod  = classLoader.loadClass("main").getMethod("main", classOf[Array[String]])
      val baos        = new ByteArrayOutputStream()
      val printStream = new PrintStream(baos)
      val oldOut      = System.out
      try {
        System.setOut(printStream)
        Console.withOut(printStream)(mainMethod.invoke(null, Array.empty[String]))
      } finally System.setOut(oldOut)
      printStream.flush()
      baos.toString.stripLineEnd
    } finally classLoader.close()
  }
}
