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

/** End-to-end coverage of the compile-time reflection intrinsics (`eliot.compiler.Reflect`): `foldNamedValues`, which
  * reifies to a right fold handing every top-level value of a given name to a declared combining value, and its
  * `namedValues` sugar, which is that fold at the free monoid and reifies to a `List`.
  *
  * Unlike [[FullIntegrationTest]] (one `Test.els`), these tests need **several modules in one program** to exercise
  * cross-module collection. Each test compiles a *fresh* multi-module project in its own temp directory (a new session,
  * so the whole-pool enumeration is never served stale from a warm cache) and runs the resulting jar.
  */
class NamedValuesIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import NamedValuesIntegrationTest.*

  private val registryPrelude =
    """import eliot.effect.Console
      |import eliot.collection.List
      |import eliot.compiler.Reflect
      |""".stripMargin

  private def sumOfContributions(extra: String = ""): Map[String, String] =
    Map(
      "Test" ->
        (registryPrelude + extra +
          """def total: Int = namedValues[Int]("contribution").foldLeft(0, e -> acc -> add(e, acc))
            |def main uses Console: Unit = printLine(show(total))""".stripMargin)
    )

  "namedValues" should "gather same-named values from every module and collect them into a List" in {
    runProject(
      sumOfContributions() ++ Map(
        "PluginA" -> "def contribution: Int = 10",
        "PluginB" -> "def contribution: Int = 20",
        "PluginC" -> "def contribution: Int = 30"
      )
    ).asserting(_ shouldBe "60")
  }

  it should "collect a single entry when only one module declares the name" in {
    runProject(sumOfContributions("def contribution: Int = 42\n")).asserting(_ shouldBe "42")
  }

  it should "collect nothing (an empty List) when no module declares the name" in {
    runProject(
      Map(
        "Test" ->
          (registryPrelude +
            """def total: Int = namedValues[Int]("noSuchName").foldLeft(0, e -> acc -> add(e, acc))
              |def main uses Console: Unit = printLine(show(total))""".stripMargin)
      )
    ).asserting(_ shouldBe "0")
  }

  it should "reject a non-literal name at compile time" in {
    projectErrors(
      Map(
        "Test" ->
          (registryPrelude +
            """def dynamicName: String = "contribution"
              |def total: Int = namedValues[Int](dynamicName).foldLeft(0, e -> acc -> add(e, acc))
              |def main uses Console: Unit = printLine(show(total))""".stripMargin)
      )
    ).asserting(_.mkString("\n") should include("requires a literal String name"))
  }

  it should "collect the entries in qualified-name order" in {
    runProject(
      Map(
        "Test"    ->
          (registryPrelude +
            """def labels: String = namedValues[String]("label").joined(",")
              |def main uses Console: Unit = printLine(labels)""".stripMargin),
        "PluginB" -> """def label: String = "b"""",
        "PluginA" -> """def label: String = "a""""
      )
    ).asserting(_ shouldBe "a,b")
  }

  it should "reject a gathered value that does not have the claimed type" in {
    // `item` is a String, but the claim is `namedValues[Int]`; the emitted `append(_, item)` violates the Int element
    // type, so the ordinary checker rejects it — the claim is enforced, never silently dropped.
    projectErrors(sumOfContributions("def item: String = \"not an int\"\n").map { case (n, s) =>
      n -> s.replace("\"contribution\"", "\"item\"")
    }).asserting(_ should not be empty)
  }

  private val suitePrelude =
    """import eliot.effect.Console
      |import eliot.compiler.Reflect
      |""".stripMargin

  /** The `eliot-test` shape: each gathered test is handed to a slot the runner *declares*, so the runner supplies and
    * discharges its `Throw` one by one while everything else it performs stays the runner's own. No list could hold
    * them — an element of a list is a payload, and a payload may not be a computation.
    */
  private def suite(step: String, extra: Map[String, String]): Map[String, String] =
    Map(
      "Test" ->
        (suitePrelude + step +
          """
            |def main uses Console: Unit = foldNamedValues("test", printLine("done"), step)""".stripMargin)
    ) ++ extra

  private val runningStep =
    """def step(name: String, test uses *, Throw[String]: Unit, rest uses *: Unit) uses Console: Unit = {
      |   printLine(name)
      |   test catch (e -> printLine("FAILED " ++ e))
      |   rest
      |}""".stripMargin

  "foldNamedValues" should "run gathered effectful values on the caller's carrier, in qualified-name order" in {
    runProject(
      suite(
        runningStep,
        Map(
          "AlphaTest" -> """def test uses Console: Unit = printLine("alpha ok")""",
          "BetaTest"  -> """def test uses Throw[String]: Unit = raise("beta broke")"""
        )
      )
    ).asserting(
      _ shouldBe "AlphaTest::test\nalpha ok\nBetaTest::test\nFAILED beta broke\ndone"
    )
  }

  it should "fold to just the initial value when no module declares the name" in {
    runProject(suite(runningStep, Map.empty).map { case (n, s) => n -> s.replace("\"test\"", "\"noSuchName\"") })
      .asserting(_ shouldBe "done")
  }

  it should "gather values of different types through one ability-constrained combining value" in {
    runProject(
      Map(
        "Test" ->
          (suitePrelude +
            """def render[V ~ Show](name: String, value: V, acc: String): String =
              |   name ++ "=" ++ show(value) ++ ";" ++ acc
              |def main uses Console: Unit = printLine(foldNamedValues("setting", "", render))""".stripMargin),
        "Host" -> """def setting: String = "localhost"""",
        "Port" -> """def setting: Int = 8080"""
      )
    ).asserting(_ shouldBe "Host::setting=localhost;Port::setting=8080;")
  }

  it should "reject a lambda as the combining value" in {
    projectErrors(
      Map(
        "Test" ->
          (suitePrelude +
            """def main uses Console: Unit =
              |   printLine(foldNamedValues("test", "", n -> t -> acc -> acc ++ n))""".stripMargin),
        "AlphaTest" -> """def test: String = "alpha""""
      )
    ).asserting(_.mkString("\n") should include("requires a declared value as its combining argument"))
  }
}

object NamedValuesIntegrationTest {

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }

  /** Compile `modules` (name -> source) as one program with main module `Test`, in a fresh temp directory + session. */
  private def build(modules: Map[String, String]): IO[(CompilationResult, Path)] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-nv-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-nv-target"))
      _          <- modules.toList.traverse_ { (name, source) =>
                      IO.blocking(Files.writeString(sourceDir.resolve(s"$name.els"), source))
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

  private def projectErrors(modules: Map[String, String]): IO[Seq[String]] =
    build(modules).map(_._1.errors.map(_.message))

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
