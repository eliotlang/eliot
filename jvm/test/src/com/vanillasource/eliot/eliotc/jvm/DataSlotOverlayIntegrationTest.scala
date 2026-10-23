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

/** A slotted `data` declared twice, by a library's runtime root and by its compile-time overlay (`docs/meta.md` §17.2,
  * O4b; decision H, §13.7): the two copies must agree on their slots as on their constructors, which the merge of the
  * two meta structures' constructors checks, their slots being its first fields.
  *
  * The library is a root of its own, `lib/src` beside `lib/compiler`, passed with `--path` as the layers are.
  */
class DataSlotOverlayIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import DataSlotOverlayIntegrationTest.*

  private val sign =
    """import eliot.compiler.Meta
      |data Sign = Positive | AnySign
      |implement Meta[Sign] {
      |   def join(a: Sign, b: Sign): Sign = a match {
      |      case Positive -> b
      |      case AnySign -> AnySign
      |   }
      |   def widen(previous: Sign, next: Sign): Sign = join(previous, next)
      |}
      |def isPositive(s: Sign): Bool = s match { case Positive -> true case AnySign -> false }
      |def signOf(i: Interval[BigInteger]): Sign = fold(rangeWithin[1, 1000000](i), Positive, AnySign)
      |""".stripMargin

  private def reading(slots: String, statement: String) =
    s"import lib.Sign\ndata Reading {$slots}(value: Int) {$statement}\n"

  private val program =
    """import eliot.effect.Console
      |import lib.Sign
      |import lib.Reading
      |def check(r: Reading) where isPositive(level(r)): Int = r match { case Reading(v) -> v }
      |def main uses Console: Unit = printLine(show(check(Reading(4))))
      |""".stripMargin

  "an overlay copy of a slotted data" should "compile when it agrees with the runtime copy on the slots" in {
    val copy = reading("level: Sign", "signOf(range(value))")
    build(program, Map("src/lib/Sign" -> sign, "src/lib/Reading" -> copy, "compiler/lib/Reading" -> copy))
      .flatMap(runIfClean)
      .asserting(_ shouldBe "4")
  }

  it should "be refused when it declares other slots" in {
    build(
      program,
      Map(
        "src/lib/Sign"         -> sign,
        "src/lib/Reading"      -> reading("level: Sign", "signOf(range(value))"),
        "compiler/lib/Reading" -> reading("level: Sign, spare: Sign", "signOf(range(value)), AnySign")
      )
    ).asserting((result, _) => result.errors.map(_.message).mkString("\n") should include("Has multiple different definitions."))
  }
}

object DataSlotOverlayIntegrationTest {

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }

  /** Compile `main` as module `Test` with the library `files` (a path under the library's directory, without `.els`, to
    * its source) on the path, in a fresh temp directory and session.
    */
  private def build(main: String, files: Map[String, String]): IO[(CompilationResult, Path)] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-ds-src"))
      libraryDir <- IO.blocking(Files.createTempDirectory("eliot-ds-lib"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-ds-target"))
      _          <- (files.toList.map((path, source) => libraryDir.resolve(s"$path.els") -> source) :+
                      (sourceDir.resolve("Test.els") -> main)).traverse_ { (file, source) =>
                      IO.blocking {
                        Files.createDirectories(file.getParent)
                        Files.writeString(file, source)
                      }
                    }
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs ++
                      List("--path", libraryDir.resolve("src").toString)
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
    } yield (result, targetDir.resolve("Test.jar"))

  private def runIfClean(result: CompilationResult, jarPath: Path): IO[String] =
    if (result.errors.isEmpty) runJar(jarPath)
    else IO.raiseError(new IllegalStateException(s"compile failed:\n${result.errors.map(_.message).mkString("\n")}"))

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
