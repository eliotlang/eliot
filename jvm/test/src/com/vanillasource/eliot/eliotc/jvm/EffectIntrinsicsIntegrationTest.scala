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

/** End-to-end proof of the compile track's escape intrinsic (`EffectIntrinsics`, `docs/effects.md` §3.4) through the
  * real evaluator paths: an ability guard is decided by a pure def that discharges a control effect, reduced on the
  * compiler track, so each `handler[..]` site is bound to one implementation in the emitted bytecode. The escape is the
  * real one: the compile-track overlay's `runThrow`/`else` over its `escapeIntrinsic`, with each frame handed along
  * the declared chain.
  *
  * As in `ListGuardReductionIntegrationTest`, a pair of guarded implementations selected from one program is the
  * point: a guard that could not reduce would fail to select, not select wrongly.
  */
class EffectIntrinsicsIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import EffectIntrinsicsIntegrationTest.*

  private val prelude =
    """import eliot.effect.Console
      |ability Route[S: String] { def handler: String }
      |""".stripMargin

  private def program(body: String): Map[String, String] = Map("Test" -> (prelude + body))

  "an ability guard that escapes" should "select by whether the body exited" in {
    runProject(
      program(
        """def accepted(s: String): Bool =
          |   foldEither(_ -> false, b -> b, runThrow[String, Bool](if(s == "/api", true) else raise("no")))
          |implement[S: String] Route[S] where accepted(S) { def handler: String = "hit" }
          |implement[S: String] Route[S] where !accepted(S) { def handler: String = "miss" }
          |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"])
          |""".stripMargin
      )
    ).asserting(_ shouldBe "hit/miss")
  }

  it should "let an exit pass through a discharger of another effect to its own frame" in {
    runProject(
      program(
        """def inner(s: String) uses Throw[String]: Bool = if(s == "/api", raise("outer")) else true
          |def accepted(s: String): Bool =
          |   foldEither(e -> e == "outer", _ -> false, runThrow[String, Bool](inner(s)))
          |implement[S: String] Route[S] where accepted(S) { def handler: String = "outer" }
          |implement[S: String] Route[S] where !accepted(S) { def handler: String = "inner" }
          |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"])
          |""".stripMargin
      )
    ).asserting(_ shouldBe "outer/inner")
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
