package com.vanillasource.eliot.eliotc.jvm

/** Part 1 of 4 of the end-to-end example-program suite (see [[FullIntegrationTest]]). The 88 cases are split across
  * four classes so Mill runs them in four parallel test-worker JVMs — each warming its own resident compilation
  * session — instead of one worker compiling all 88 serially. Keep each part self-contained: a case that shares a
  * class-level helper (e.g. `orderingPrelude`) must stay in the same part as that helper. */
class ExamplesIntegrationTest1 extends FullIntegrationTest {

  "hello world" should "print a string" in {
    compileAndRun("""import eliot.effect.Console
def main uses Console: Unit = printLine("Hello World!")""")
      .asserting(_ shouldBe "Hello World!")
  }

  // --- Effects M2: library spine (Effect/Suspend/Console) + the Console -> Suspend -> IO layering, run end-to-end ---

  // The headline M2 acceptance: a hand-monadic `{Console}` computation reading a line and echoing it. `flatMap` is an
  // `Effect[IO]` op resolved at the concrete use site (the carrier is not in `echo`'s declared effect set); `readLine`
  // and `printLine` resolve through the constrained HKT instance `implement[F[_] ~ Suspend] Console[F]` at `F := IO`, which
  // in turn discharges `Suspend[IO]`. `main` commits to the concrete runnable carrier `IO[Unit]` (Decision 8).
  "console effect" should "read a line and echo it" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def echo uses Console: Unit = printLine(orEmpty(readLine))
        |
        |def main uses Console: Unit = echo""".stripMargin,
      stdin = "echoed line\n"
    ).asserting(_ shouldBe "echoed line")
  }

  // `printLine` is the `Console` effect's operation, so the original HelloWorld is a `{Console}` program and keeps
  // working unchanged.
  it should "still print a literal via the Console effect" in {
    compileAndRun("""import eliot.effect.Console
def main uses Console: Unit = printLine("Hello World!")""")
      .asserting(_ shouldBe "Hello World!")
  }

  // A `{Console}` business function reached from `main`: both operations run on the implementation the boundary binds.
  it should "run a {Console} function reached from main" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def greet uses Console: Unit = {
        |   printLine("a")
        |   printLine("b")
        |}
        |
        |def main uses Console: Unit = greet""".stripMargin
    ).asserting(_ shouldBe "a\nb")
  }

  // The `private` leaf native behind `printLine` is unreachable from application code: naming it across the module
  // boundary is refused by the resolver (the fail-safe that keeps untracked I/O impossible).
  "the private I/O leaf" should "be unreachable from application code" in {
    compileForErrors("""def main uses Console: Unit = eliot.effect.Console::printLineInternal("x")""")
      .asserting(_ should include("Name is private."))
  }

  // --- Effects M3: body auto-lift (the headline) — direct-style code, no hand-written flatMap ---

  // THE headline: a direct-style program. `readLine` is effectful (`F[Option[String]]`) but flows into `orEmpty`, which
  // expects a plain `Option[String]`; the checker's effect lift binds it, producing `flatMap(x -> printLine(orEmpty(x)),
  // readLine)`, with the carrier pinned to `IO` by `main`'s return. No `import eliot.carrier.Effect`, no hand-written
  // `flatMap`. (`orEmpty` discharges `readLine`'s end-of-input `Option`; the effect machinery under test is the same
  // either way, so every program below carries it rather than complicating what it is probing.)
  "effect auto-lift" should "sequence a direct-style printLine(readLine) at a concrete IO main" in {
    compileAndRun(
      """import eliot.effect.Console
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def main uses Console: Unit = printLine(orEmpty(readLine))""".stripMargin,
      stdin = "echoed line\n"
    ).asserting(_ shouldBe "echoed line")
  }

  // The same direct-style body in a carrier-polymorphic `{Console}` business function, pinned to `IO` at the call site.
  it should "sequence a direct-style {Console} business function pinned to IO" in {
    compileAndRun(
      """import eliot.effect.Console
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def echo uses Console: Unit = printLine(orEmpty(readLine))
        |
        |def main uses Console: Unit = echo""".stripMargin,
      stdin = "carrier line\n"
    ).asserting(_ shouldBe "carrier line")
  }

  // Two cases stood here and have no v6 subject: hand-written `flatMap` code left untouched by the auto-lift (there
  // is no auto-lift and no `flatMap`), and the rejection of `readLine.flatMap(f)` — a computation passed through the
  // dot's plain-generic subject slot. Neither shape can be written any more; what the second one guarded, that an
  // effect only passes through a position declaring one, is rule 4 and is guarded by the scope check.

  // The dual case: an effectful subject dotted into a *plain-value* function (`readLine.shout`, `shout(s: Option[String])`)
  // must still bind — the inlined `shout(readLine)` sequences `readLine` into `shout`'s plain slot. Proves the inlining
  // restores the ordinary bind decision rather than blanket-suppressing it for every dotted subject.
  it should "bind an effectful subject dotted into a plain-value function" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def shout(s: Option[String]): String = s.orAbort else ""
        |
        |def echo uses Console: Unit = printLine(readLine.shout)
        |
        |def main uses Console: Unit = echo""".stripMargin,
      stdin = "loud\n"
    ).asserting(_ shouldBe "loud")
  }

  // --- Effectful subjects at a generic slot. The decision is read from *declarations* before checking (effects as
  // rows v3), not per instantiation in the checker: a computation at a rowless slot is run where it is written, and
  // may never pass through — the pass-through arm these cases were written for was v2's, and is deleted. ---

  // An effectful subject dotted into a *function-typed parameter*: `readLine` is run where written and `f` receives
  // its payload, `.`'s `A` being instantiated at a plain value.
  // The handler is a named pure function rather than an inline `s -> s.orAbort else ""`: a lambda body at a
  // *rowless* arrow slot is still elaborated in the enclosing region, so an inline discharge there lands on the
  // caller's carrier (`IO[String]`) instead of reaching the `Id` boundary, and no longer matches the slot's declared
  // `String`. That is a live gap, unrelated to the dotted subject this case is about.
  it should "bind an effectful subject dotted into a function-typed parameter" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def call(f: Option[String] => String) uses Console: Unit = printLine(readLine.f)
        |
        |def main uses Console: Unit = call(s -> orEmpty(s))""".stripMargin,
      stdin = "through f\n"
    ).asserting(_ shouldBe "through f")
  }

  // A user-defined pipe with `.`'s shape, but declaring no row anywhere (`|>[A, B](a: A, f: A => B): B`): handing it
  // a computation is the same §1 rule-4 violation as the dot above, and here the elaborator names the slot itself
  // rather than leaving a type mismatch downstream. Rule 4 is what makes the two spellings agree — the decision is
  // The two pipe rejections that stood here — `readLine |> flatMap(f)` through a user-defined operator and through a
  // plain function — went with `flatMap`. The rule they guarded (an effect passes through a position only if that
  // position declares one) is unchanged and is what the scope check reports; the shape they used to write it is gone.

  // flex slot rigidifies and the subject is sequenced.
  it should "bind an effectful subject piped into a concrete slot" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |infix left below apply def |>[A, B](a: A, f: A => B): B = f(a)
        |
        |def shout(s: Option[String]): String = s.orAbort else ""
        |
        |def echo uses Console: Unit = printLine(readLine |> shout)
        |
        |def main uses Console: Unit = echo""".stripMargin,
      stdin = "piped loud\n"
    ).asserting(_ shouldBe "piped loud")
  }

  // Author-written machinery into a pure slot is a §1 rule-4 violation, and has been rejected since A.11.7-X:
  // `printLine`'s parameter is a plain `String`, which declares no effect row, so a computation may not land there.
  // The tree used to accept it — the checker hoisted it into a real `IO.pure` + `IO.flatMap` round trip for a string
  // "Author-written machinery flowing into a pure slot" (`printLine(pure("lifted"))`) has no v6 subject: there is no
  // machinery to write. `pure`/`flatMap`/`map` were the carrier's, and nothing replaces them.

  // with a row, and `Ctr` declares none.
  "a constructor-class ability" should "perform no effect, and drop into pure code" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |ability Ctr[F[_]] {
        |   def wrap[A](a: A): F[A]
        |   def unwrap[A](fa: F[A]): A
        |}
        |
        |data Bx[A](content: A)
        |
        |implement Ctr[Bx] {
        |   def wrap[A](a: A): Bx[A] = Bx(a)
        |   def unwrap[A](fa: Bx[A]): A = fa.content
        |}
        |
        |def rebox(b: Bx[String]): Bx[String] = wrap(unwrap(b))
        |
        |def unboxed(b: Bx[String]): String = unwrap(b)
        |
        |def main uses Console: Unit = printLine(unboxed(rebox(Bx("boxed"))))""".stripMargin
    ).asserting(_ shouldBe "boxed")
  }

  // Fail-safe: a value that performs an effect but is declared with a non-carrier (pure) return type is rejected by the
  // per-definition row verification, not silently miscompiled.
  "an effectful body under a pure return" should "be rejected" in {
    compileForErrors(
      """import eliot.effect.Console
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def helper: String = printLine(orEmpty(readLine))
        |
        |def main uses Console: Unit = printLine(helper)""".stripMargin
    ).asserting(_ should include("performs the effect 'Console' but does not declare it"))
  }

  // --- Effects M4: multi-effect composition + propagation + Dep ---

  // The `Log` effect mirrors `Console` (a fine effect riding the `Suspend` base): `log` writes a tagged line. A `{Log}`
  // business function pinned to `IO` at the call site runs through the JVM `Log` instance.
  "log effect" should "emit a tagged diagnostic line" in {
    compileAndRun(
      """import eliot.effect.Log
        |def announce uses Log: Unit = log("starting up")
        |
        |def main uses Log: Unit = announce""".stripMargin
    ).asserting(_ shouldBe "[LOG] starting up")
  }

  // Multiple effects in one signature, carrier-unified across callees: `log` (Log) and `readLine` (Console) share the
  // one carrier `F`, auto-lifted into a single `flatMap` chain.
  "multiple effects in one signature" should "run both in a direct-style body" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Log
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def echoLog uses Log, Console: Unit = log(orEmpty(readLine))
        |
        |def main uses Log, Console: Unit = echoLog""".stripMargin,
      stdin = "from stdin\n"
    ).asserting(_ shouldBe "[LOG] from stdin")
  }

  // Effect propagation is a plain set-subset check: a body may only perform effects it declares. Calling a `{Log}`
  // function from a `{Console}`-only function leaks `Log`, rejected at the definition with a precise message.
  "an undeclared effect" should "be rejected with a precise propagation error" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Log
        |def doLog uses Log: Unit = log("hi")
        |
        |def caller uses Console: Unit = doLog
        |
        |def main uses Console: Unit = caller""".stripMargin
    ).asserting(_ should include("performs the effect 'Log' but does not declare it"))
  }

  // The headline M4 program: three effects (`Dep[Database]`, `Log`, `Console`) composed in one direct-style body, run
  // end to end. `dependency` reads the environment; the Database is injected at the discharge site by `provide`.
  "a multi-effect Dep/Log/Console program" should "compile and run end to end" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Log
        |import eliot.effect.Dep
        |
        |data Database(url: String)
        |
        |def orEmpty(o: Option[String]): String = o.orAbort else ""
        |
        |def run uses Dep[Database], Log, Console: Unit = andThen(log(dependency.url), printLine(orEmpty(readLine)))
        |
        |def andThen(first: Unit, second: Unit): Unit = second
        |
        |def main uses Log, Console: Unit = provide(Database("jdbc://app-db"), run)""".stripMargin,
      stdin = "echoed\n"
    ).asserting(_ shouldBe "[LOG] jdbc://app-db\nechoed")
  }

  // Two distinct-typed `Dep`s in one body each read their own environment and yield the correct distinct value (the
  // first dependency's url, then the second's name) — proving by-type dispatch does not collapse the two. Each is
  // supplied by its own chained `.provide` (fully discharged to a pure result — the flex-flex carrier-alias case).
  "two distinct-typed Deps" should "each resolve dependency to its own value in one body" in {
    val program =
      """import eliot.effect.Console
        |import eliot.effect.Dep
        |
        |data Database(url: String)
        |data Logger(name: String)
        |
        |def firstDep uses Dep[Database], Dep[Logger]: String = pick(url(dependency), name(dependency))
        |
        |def main uses Console: Unit = printLine(provide(Logger("the-logger"), provide(Database("the-db"), firstDep)))""".stripMargin
    compileAndRun(program + "\n\ndef pick(a: String, b: String): String = a")
      .asserting(_ shouldBe "the-db")
  }

  it should "resolve the second distinct Dep to its own value" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Dep
        |
        |data Database(url: String)
        |data Logger(name: String)
        |
        |def secondDep uses Dep[Database], Dep[Logger]: String = pick(url(dependency), name(dependency))
        |
        |def pick(a: String, b: String): String = b
        |
        |def main uses Console: Unit = printLine(provide(Logger("the-logger"), provide(Database("the-db"), secondDep)))""".stripMargin
    ).asserting(_ shouldBe "the-logger")
  }

  // --- Testing strategy: a fake implementation supplied by the test, with production code untouched ---

  /** The substitution mechanism (`docs/effects.md` §9.3, `examples/src/EffectsNamedEffect.els`): `greet` is ordinary
    * production code declaring `{Terminal}` and nothing else, and the test binds a **named implementation** to it with
    * `with`. Under v5 the same claim needed a fake *carrier* — a `Session` data type, an `Effect[Session]` instance and
    * a `Terminal[Session]` one — because what a test substituted was the thing the effects ran in. Under v6 it
    * substitutes a name, and the double reaches nothing it does not declare: a user module declares no natives, so
    * `session` can only reach the world through effects its own clauses declare, which the binding site is charged for.
    */
  private val namedImplementation =
    """effect Terminal {
      |   def write(line: String): Unit
      |
      |   def read: String
      |}
      |
      |def greet uses Terminal: Unit = {
      |   val name = read
      |   write("Hello, " ++ name ++ "!")
      |}
      |
      |implement session: Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
      |
      |   def read: String = "Bob"
      |}
      |""".stripMargin

  "a named implementation" should "let a test interpret an effect without changing the production code" in {
    compileAndRun(
      namedImplementation +
        """
          |def greetTranscript: String = runWriterToLog(greet with session)
          |
          |def main uses Console: Unit = printLine(greetTranscript)""".stripMargin
    ).asserting(_ shouldBe "Hello, Bob!;")
  }

  // A harness taking the program at a slot and returning a nullary `data TestResult`. The `with` on the *slot's type*
  // is the only way a callee binds calls it cannot see, and the effects the double's clauses perform are supplied and
  // discharged inside the callee — `runWriterToLog` here — which is why the caller writes nothing but the program.
  it should "let a harness take a program at a with-bound slot and return a nullary data type" in {
    compileAndRun(
      namedImplementation +
        """
          |data TestResult(label: String, failure: Option[String])
          |
          |def transcriptOf(program uses *, Terminal with session: Unit): String = runWriterToLog(program)
          |
          |def expect(label: String, expected: String, actual: String): TestResult =
          |   if(expected == actual, TestResult(label, None))
          |   else TestResult(label, Some("expected '" ++ expected ++ "' but was '" ++ actual ++ "'"))
          |
          |def greetTest: TestResult = expect("greet", "Hello, Bob!;", transcriptOf(greet))
          |
          |def main uses Console: Unit =
          |   printLine(failure(greetTest).foldOption("PASS " ++ label(greetTest), f -> "FAIL " ++ f))""".stripMargin
    ).asserting(_ shouldBe "PASS greet")
  }

  // The same double consumed by a test framework. Under v5 a `TestCase` carried the assertion as a **pinned-row
  // field** and the framework ran it later; v6's `eliot.test` carries no body at all (D13) — `in` runs the case in
  // place and discharges what it declares — so the framework shape here is the assertion run where it is written and
  // its `Throw` discharged around it.
  // Storing a computation in a `data` field is not what replaced it: a field holds a value (`docs/effects.md` D20
  // rule 6), so a row on one is rejected.
  private val namedImplementationFramework =
    """import eliot.effect.Throw
      |
      |""".stripMargin + namedImplementation +
      """
      |data AssertionError(reason: String)
      |
      |def assertEquals(expected: String, actual: String) uses Throw[AssertionError]: Unit =
      |   if(expected == actual, unit) else raise(AssertionError("expected '" ++ expected ++ "' but was '" ++ actual ++ "'"))
      |
      |def greetTranscript: String = runWriterToLog(greet with session)
      |
      |def runCase(name: String, assertion uses *, Throw[AssertionError]: Unit): String =
      |   foldEither(e -> "FAIL " ++ name ++ ": " ++ reason(e), u -> "PASS " ++ name, runThrow(assertion))
      |
      |""".stripMargin

  it should "carry the double's transcript into a test-framework assertion" in {
    compileAndRun(
      namedImplementationFramework +
        """def main uses Console: Unit =
          |   printLine(runCase("greet", assertEquals("Hello, Bob!;", greetTranscript)))""".stripMargin
    ).asserting(_ shouldBe "PASS greet")
  }

  it should "report a failed assertion against the double's transcript" in {
    compileAndRun(
      namedImplementationFramework +
        """def main uses Console: Unit =
          |   printLine(runCase("greet", assertEquals("Hello, Alice!;", greetTranscript)))""".stripMargin
    ).asserting(_ shouldBe "FAIL greet: expected 'Hello, Alice!;' but was 'Hello, Bob!;'")
  }
}
