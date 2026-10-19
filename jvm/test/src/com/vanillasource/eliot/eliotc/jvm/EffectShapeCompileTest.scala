package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.Compiler
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.util.zip.ZipInputStream

/** The **effect-shape corpus**: the full spread of shapes an effectful program can take must compile end to end over
  * the whole base layer (`lang` + `stdlib` + `jvm`). Each program pulls in the base and is compiled to a jar; a clean
  * compile (classes produced, no errors) is the assertion, plus one program that must *not* compile.
  *
  * The programs, not the machinery under them, are the durable content: the suite has outlived several effect
  * mechanisms, and every shape here still has to compile under whichever one decides it.
  */
class EffectShapeCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  // A pure value return (`label`), a pure argument into `printLine`, and an *effectful* argument (`label(readLine)`):
  // `readLine` performs `Console` at the call site and its `Option[String]` is what `label` receives.
  private val source =
    """def label(line: Option[String]): String = line.orAbort else ""
      |
      |def main uses Console: Unit = printLine(label(readLine))
      |""".stripMargin

  // The whole conditional surface (`if`/`else`/`fold` are ordinary functions, never hardcoded): an `if..else` in a
  // pure definition, its `Abort` discharged by `else` (`sign`), an effectful `if..else` whose `Abort` is discharged by
  // `else` while `Console` is `report`'s own (`report`), a `fold` over two effectful code arms, only the selected one
  // run (`pick`), and a `val`-bound discharged chain (`describe`).
  private val conditionalSource =
    """def line uses Console: String = readLine.orAbort else ""
      |
      |def sign(flag: Bool): String = if(flag, "+") else "-"
      |
      |def describe(a: Bool, b: Bool): String = {
      |   val category = if(a, "first") else if(b, "second") else "third"
      |   category
      |}
      |
      |def report(flag: Bool) uses Console: Unit = if(flag, printLine("on")) else printLine("off")
      |
      |def pick(flag: Bool) uses Console: Unit = fold(flag, printLine("a"), printLine("b"))
      |
      |def main uses Console: Unit = {
      |   printLine(sign(line == "yes"))
      |   printLine(describe(line == "a", line == "b"))
      |   report(line == "y")
      |   pick(line == "z")
      |}
      |""".stripMargin

  // An *effectful* argument at a generic parameter the result discards (`first[A, B](a: A, b: B): A`, called as
  // `first("x", readLine)`): the effect still runs at the call site, strict call-by-value.
  private val genericBindSource =
    """def first[A, B](a: A, b: B): A = a
      |
      |def main uses Console: Unit = printLine(first("x", readLine))
      |""".stripMargin

  // An effectful definition passed whole to a discharger: `parseOk`, declared `uses Throw[String]`, at `catch`'s
  // `computation uses *, Throw[E]: A` slot, whose run hands it the `Throw[String]` handle `catch` makes.
  private val captureSource =
    """def parseOk uses Throw[String]: String = fold(true, "parsed-value", raise("unparseable"))
      |
      |def main uses Console: Unit = printLine(parseOk catch (err -> err))
      |""".stripMargin

  // A fully-polymorphic aborting actual (`abort` stands in for a value of any type) delivered into `printLine`'s
  // `String` parameter, with the `Abort` it performs discharged at the caller: `runAbort` has to reach the frame
  // `abort` exits to.
  private val abortingActualSource =
    """import eliot.effect.Console
      |import eliot.effect.Abort
      |
      |def demo uses Abort, Console: Unit = printLine(abort)
      |
      |def main uses Console: Unit = printLine(foldOption("done", s -> "got", runAbort(demo)))
      |""".stripMargin

  // A rich stateful program (the `EffectsState` example, inlined): a `State[String]` computation in direct style
  // (`val old = state; putState(next); old`), discharged by `runStateToPair` — several statements sequencing one
  // control effect, read back as data.
  private val stateSource =
    """import eliot.effect.Console
      |import eliot.effect.State
      |
      |def swap(next: String) uses State[String]: String = {
      |   val old = state
      |   putState(next)
      |   old
      |}
      |
      |def demo: Pair[String, String] = runStateToPair("first", swap("second"))
      |
      |def main uses Console: Unit = {
      |   printLine(demo.first)
      |   printLine(demo.second)
      |}
      |""".stripMargin

  // Two `Dep` entries of distinct types in one clause (`Dep[Database], Dep[Logger]`): each `dependency` is answered by
  // the handle whose entry its use's type decides, and two nested `provide`s discharge them.
  private val twoDepsSource =
    """import eliot.effect.Console
      |import eliot.effect.Dep
      |
      |data Database(url: String)
      |data Logger(name: String)
      |
      |def firstDep uses Dep[Database], Dep[Logger]: String = pick(url(dependency), name(dependency))
      |def pick(a: String, b: String): String = a
      |
      |def main uses Console: Unit = printLine(provide(Logger("the-logger"), provide(Database("the-db"), firstDep)))
      |""".stripMargin

  "an effectful program" should "compile a pure value return + pure and effectful arguments over the whole base" in {
    compileClasses(source).asserting(_ should not be empty)
  }

  it should "compile the conditional surface (if/else/fold, discharge-to-pure, capture)" in {
    compileClasses(conditionalSource).asserting(_ should not be empty)
  }

  it should "compile an effectful argument into a discarded type-parameter slot" in {
    compileClasses(genericBindSource).asserting(_ should not be empty)
  }

  it should "compile an effectful definition passed whole to a discharger" in {
    compileClasses(captureSource).asserting(_ should not be empty)
  }

  it should "compile a fully-polymorphic aborting actual at a String parameter" in {
    compileClasses(abortingActualSource).asserting(_ should not be empty)
  }

  it should "compile a direct-style State program discharged at its caller" in {
    compileClasses(stateSource).asserting(_ should not be empty)
  }

  it should "compile two distinct-typed Dep entries in one clause" in {
    compileClasses(twoDepsSource).asserting(_ should not be empty)
  }

  // A member of a **parameterised** ability declaring effects of its own: `Describe[T]`'s binding sits at type-argument
  // index 0, the block's `T` at 1, and the member's own `{Console}` binding at 2. This is the shape the prefix write
  // could not express at all — it was rejected at the declaration — and the merge (`docs/effects.md` §9.3 step 3)
  // writes both bindings around whatever the call determines for `T`.
  private val parameterisedAbilityMemberRow =
    """ability Describe[T] {
      |   def describe(t: T) uses Console: String
      |}
      |
      |implement Describe[String] {
      |   def describe(t: String) uses Console: String = {
      |      printLine("describing")
      |      t
      |   }
      |}
      |
      |def main uses Console: Unit = printLine(describe[String]("x"))
      |""".stripMargin

  it should "compile a parameterised ability's member declaring a row of its own" in {
    compileClasses(parameterisedAbilityMemberRow).asserting(_ should not be empty)
  }

  // The other half of that shape (`docs/effects.md` §8 item 15): a call that leaves `T` to inference. The write puts
  // the checker's hole at index 1 (`eliot.lang.Implementation::Inferred`), so the `Console` binding at index 2 is still
  // written, and the checker infers `T` as it infers a binder left off the end of the list.
  it should "compile a call to such a member that leaves its type argument to inference" in {
    compileClasses(parameterisedAbilityMemberRow.replace("describe[String](", "describe("))
      .asserting(_ should not be empty)
  }

  it should "report a pure argument of the wrong type" in {
    // `printLine(true)` — a `Bool` where a `String` is expected — is a plain type mismatch. The reported errors must be
    // non-empty.
    compileErrors("def main uses Console: Unit = printLine(true)\n").asserting(_ should not be empty)
  }

  /** Compile the program (module `Test`) over the base layer roots and return each generated class's name → bytes. */
  private def compileClasses(source: String): IO[Map[String, Seq[Byte]]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-uc-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-uc-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      _          <- IO.raiseWhen(result.errors.nonEmpty)(
                      new IllegalStateException(s"Compilation errors: ${result.errors.map(_.message).mkString(", ")}")
                    )
      classes    <- readClasses(targetDir.resolve("Test.jar"))
    } yield classes

  /** Compile the program and return its sorted error messages (never raising) — for a program expected NOT to compile. */
  private def compileErrors(source: String): IO[Seq[String]] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-uc-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-uc-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
    } yield result.errors.map(_.message).sorted

  private def readClasses(jar: Path): IO[Map[String, Seq[Byte]]] = IO.blocking {
    val in = new ZipInputStream(Files.newInputStream(jar))
    try
      Iterator
        .continually(in.getNextEntry)
        .takeWhile(_ != null)
        .filter(_.getName.endsWith(".class"))
        .map(entry => entry.getName -> in.readAllBytes().toSeq)
        .toMap
    finally in.close()
  }

  /** The base-layer `eliot/src` source roots handed to the compiler as filesystem roots (CP1.5), exactly as
    * [[FullIntegrationTest]] does — the repo root is passed to the forked test JVM via `ELIOT_REPO_ROOT`.
    */
  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
