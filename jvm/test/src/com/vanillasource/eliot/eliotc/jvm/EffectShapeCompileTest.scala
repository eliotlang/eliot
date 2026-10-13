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
  * The programs, not the machinery under them, are the durable content — which is why the suite has outlived two of
  * those machineries. It began as a byte-identity oracle comparing the v2 uniform-carrier path against the
  * pre-uniform fallback, was kept as a compile-success gate when that fallback went, and is kept again now that the
  * bridge itself is gone (effects-as-rows A.11.7): the same shapes are placed by the row elaborator from declarations,
  * and every one of them still has to compile. The per-program comments name the mechanism each shape exercised when
  * it was added; that history is why the shape is here, not a claim about the current path.
  */
class EffectShapeCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  // Exercises the shapes the uniform gate routes: a pure value return (`label`'s `line`), a pure argument into a payload
  // slot (`printLine(<pure>)`), and an *effectful* argument into a payload slot (`label(readLine)` — `readLine` is
  // `{Console} Option[String]`, bound at the call site).
  private val source =
    """def label(line: Option[String]): String = line.orAbort else ""
      |
      |def main uses Console: Unit = printLine(label(readLine))
      |""".stripMargin

  // Exercises the whole conditional surface (`if`/`else`/`fold` are ordinary functions, never hardcoded): a
  // discharge-to-pure `if..else` whose residual carrier defaults to `Id` and unwraps with `runId` (`sign`), an
  // effectful `if..else` whose `Abort` is discharged by `else` while `Console` rides the ambient (`report`), a
  // multi-arm `fold` (both bare-`A` Generic arms, only the selected one run — `pick`), and a `val`-bound discharged
  // chain (`describe`).
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

  // Exercises the Generic-arm BIND case (U4-a(i)): a generic callee whose type parameter is *discarded* from the result
  // (`first[A, B](a: A, b: B): A` — `B` absent from `A`) receiving an *effectful* argument in the discarded slot
  // (`first("x", readLine)`). The domain meta `B` does not ride the result, so the effect cannot ride up as a first-class
  // value — it must be *sequenced* (bound) at the call site, exactly as the Phase-B `tryBindLift` does.
  private val genericBindSource =
    """def first[A, B](a: A, b: B): A = a
      |
      |def main uses Console: Unit = printLine(first("x", readLine))
      |""".stripMargin

  // Exercises the payload-slot CAPTURE case (U4-a(ii)): an effectful actual captured *whole* into a carrier-stack /
  // pinned domain. `parseOk : {Throw[String]} String` (desugars to a role-carrier `?F[String]`) is passed to `catch`'s
  // `computation: {Throw[E] | G} A` slot (a pinned `ThrowCarrier[E, G, A]`); its payload `String` does not fit the
  // domain, but the whole `?F[String]` pass-through-unifies (`?F := ThrowCarrier[E, G]`, `A := String`), storing the
  // computation — the uniform ladder's arm-1 whole-type pass-through.
  private val captureSource =
    """def parseOk uses Throw[String]: String = fold(true, "parsed-value", raise("unparseable"))
      |
      |def main uses Console: Unit = printLine(parseOk catch (err -> err))
      |""".stripMargin

  // A fully-polymorphic aborting actual (`abort` stands in for a value of any type) delivered into `printLine`'s
  // `String` domain, with the `Abort` it performs discharged at the caller. Under v5 this was the "doomed under-applied
  // bind" case, named for a whole-type unify (`?F[?A] ~ String`) with no injective solution; there is no carrier to
  // unify now, so what is left is the shape itself — which still has to compile, and whose `runAbort` still has to
  // reach the frame `abort` exits to.
  private val abortingActualSource =
    """import eliot.effect.Console
      |import eliot.effect.Abort
      |
      |def demo uses Abort, Console: Unit = printLine(abort)
      |
      |def main uses Console: Unit = printLine(foldOption("done", s -> "got", runAbort(demo)))
      |""".stripMargin

  // A rich stateful program (the `EffectsState` example, inlined): a `{State[String]}` computation in direct style
  // (`val old = state; putState(next); old`), discharged by `runStateToPair`. Under v5 this needed a hand-written `Id`
  // carrier and an `eliot.carrier.Effect` instance to discharge *into*; a discharge is a runtime frame now, so the
  // program is what a user would actually write and the shape it covers — several statements sequencing one control
  // effect, read back as data — is the same.
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

  // Exercises NESTED effect-carrier stacks (the `CarrierJoin` prefix-unify fix): `grade`'s `if..else if..else`
  // monomorphizes the `Effect[AbortCarrier[G]]` instance at `AbortCarrier[AbortCarrier[IO]]`, whose inner binder `G` the
  // uniform join must solve (it dropped the `Con` prefix before, leaving `G` unsolved → "contains unresolved variable").
  private val nestedAbortSource =
    """import eliot.effect.Console
      |import eliot.effect.Abort
      |
      |def grade(s: String) uses Abort: String = if(s == "A", "excellent") else if(s == "B", "good")
      |
      |def main uses Console: Unit = {
      |   printLine(grade("A") else "?")
      |   printLine(if(true, "taken") else "skipped")
      |}
      |""".stripMargin

  // Exercises two distinct-typed nested `Dep` carriers (`DepCarrier[Database, DepCarrier[Logger, IO]]`): the second
  // dep's lift instance (`Dep[X2, DepCarrier[X1, G]] where X1 != X2`) resolves only if the uniform join solves the inner
  // carrier prefix — before the fix it reported "No ability implementation found for ability 'Dep' with type arguments
  // [Logger]".
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

  "the uniform-carrier checker" should "compile a pure value return + payload slots over the whole base" in {
    compileClasses(source).asserting(_ should not be empty)
  }

  it should "compile the conditional surface (if/else/fold, discharge-to-pure, capture)" in {
    compileClasses(conditionalSource).asserting(_ should not be empty)
  }

  it should "compile the Generic-arm bind case (effectful arg into a discarded type-param slot)" in {
    compileClasses(genericBindSource).asserting(_ should not be empty)
  }

  it should "compile the payload-slot capture case (effectful computation captured by a discharger)" in {
    compileClasses(captureSource).asserting(_ should not be empty)
  }

  it should "compile a fully-polymorphic aborting actual at a payload slot" in {
    compileClasses(abortingActualSource).asserting(_ should not be empty)
  }

  it should "compile a direct-style State program discharged at its caller" in {
    compileClasses(stateSource).asserting(_ should not be empty)
  }

  it should "compile a NESTED AbortCarrier stack (if..else if..else at two carrier depths)" in {
    compileClasses(nestedAbortSource).asserting(_ should not be empty)
  }

  it should "compile two distinct-typed nested Dep carriers" in {
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

  // The other half of that shape, and why the declaration-level rejection became a call-level one: a type-argument
  // list has no hole, so a call that leaves `T` to inference leaves the `{Console}` binding at index 2 unreachable.
  // Dropping it silently would run the member on the platform's default with no error at all.
  it should "reject a call to such a member that leaves its type argument to inference" in {
    compileErrors(parameterisedAbilityMemberRow.replace("describe[String](", "describe("))
      .asserting(_.mkString should include("Cannot pass the implementation of 'Console' to 'describe'"))
  }

  it should "report a payload-slot mismatch (pure actual not fitting, no capture)" in {
    // `printLine(true)` — `Bool` into the `String` domain — reaches `uniformCaptureSlot`'s mismatch leaf (not doomed, no
    // whole-type capture), which commits the mismatch directly. The reported errors must be non-empty.
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
