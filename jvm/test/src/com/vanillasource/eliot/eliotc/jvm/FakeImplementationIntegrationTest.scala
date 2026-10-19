package com.vanillasource.eliot.eliotc.jvm

/** **Substituting a fake implementation** for a test, without the production code knowing — the testing strategy of
  * `docs/effects.md` §6.
  *
  * The mechanism is a **named implementation** plus a `with`. A named `implement` is its effect's instance at its own
  * handle's type, so it is never a default and overlaps nothing — the platform's `standardConsole` included. Production
  * code is untouched: it declares `uses Console`, names no implementation, and which implementation answers is decided
  * entirely at the test site.
  *
  * Run end to end over the real base layer, because the point is precisely how the *stdlib's* instances behave.
  */
class FakeImplementationIntegrationTest extends FullIntegrationTest {

  /** Production code, identical in every program here: it declares `uses Console`, names no implementation, and is
    * never edited for the test — the whole claim of the strategy.
    */
  private val production =
    """def greeting(name: String) uses Console: Unit = printLine("Hello, " ++ name ++ "!")
      |""".stripMargin

  /** The test double. It needs no type of its own and no colocation with `Console`. Its clauses declare what the
    * double itself performs — `Writer[String]` — and that is captured wherever the name is bound, which is what makes
    * the transcript come out. It cannot cheat: a user module declares no natives and the platform's are private to its
    * layer, so a double reaches the outside world only through effects its own clauses declare.
    */
  private val recordingConsole =
    """implement recordingConsole: Console {
      |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";")
      |
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  "a named implementation of a standard-library effect" should "interpret untouched production code" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |$production
         |def transcript: String = runWriterToLog(greeting("Bob") with recordingConsole)
         |
         |def main uses Console: Unit = printLine(transcript)
         |""".stripMargin
    ).asserting(_ shouldBe "Hello, Bob!;")
  }

  it should "satisfy the value's declared effect row, so nothing is reported undeclared" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |$production
         |def transcript: String = runWriterToLog(greeting("Bob") with recordingConsole)
         |
         |def main uses Console: Unit = printLine(transcript)
         |""".stripMargin
    ).asserting(_ should not include "performs the effect 'Console'")
  }

  // The other half of the claim: nothing changes in production. The same double is declared in the same program and
  // is simply not bound here, so `greeting` runs on the platform's `Console` — a named implementation is never a
  // default and never competes with one.
  it should "leave the platform's implementation resolving where nothing binds the double" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |$production
         |def main uses Console: Unit = greeting("World")
         |""".stripMargin
    ).asserting(_ shouldBe "Hello, World!")
  }

  // A `with` binds the calls lexically inside its subject, so a **block** of several statements is the same shape as
  // one call. This is what a test framework wants, and it needs no region of its own, so it may be written inside an
  // effectful body, as `main` is here.
  it should "bind a multi-statement block written inline inside an effectful body" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |$production
         |def main uses Console: Unit = printLine(runWriterToLog({
         |   greeting("Ann")
         |   greeting("Bob")
         |} with recordingConsole))
         |""".stripMargin
    ).asserting(_ shouldBe "Hello, Ann!;Hello, Bob!;")
  }

  // The double's own clause effects are charged at the **binding site**, which is what stops a double laundering an
  // effect: binding `recordingConsole` performs the `Writer[String]` its clauses declare, so a binding site that
  // neither declares nor discharges it is rejected there.
  it should "charge the double's own clause effects at the binding site" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |$production
         |def transcript: String = greeting("Bob") with recordingConsole
         |
         |def main uses Console: Unit = printLine(transcript)
         |""".stripMargin
    ).asserting(_ should include("performs the effect 'Writer'"))
  }

  // An effect with no implementation at all is the honest state of one a program has not decided how to run: reaching
  // the entry point, it fails at `main` naming the effect rather than silently picking something.
  it should "fail at the entry point for an effect nothing implements" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |effect Terminal {
        |   def write(line: String): Unit
        |}
        |
        |def greet uses Terminal: Unit = write("hi")
        |
        |def main uses Terminal: Unit = greet
        |""".stripMargin
    ).asserting(_ should include("'main' uses the effect 'Terminal', which the platform has no standard implementation of."))
  }

  // And a use that does not declare it at all is rejected earlier still, at the reference — the ordinary scope check,
  // with no special case for an unimplemented effect.
  it should "reject a use that declares no row for the effect" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |effect Terminal {
        |   def write(line: String): Unit
        |}
        |
        |def greet uses Terminal: Unit = write("hi")
        |
        |def main uses Console: Unit = greet
        |""".stripMargin
    ).asserting(_ should include("performs the effect 'Terminal' but does not declare it"))
  }

  /** A named implementation colocated with its ability sits exactly where the two-site search looks, and must still
    * not answer it: `with` is the only way to reach one (§2). Nor is it what the entry point binds, which is the
    * effect's `standard…` implementation alone (D26), so this program fails at `main` — as if the double were not
    * written at all.
    * Until this was measured the search collected *every* implementation in a candidate module, so a `{Terminal}` row
    * silently picked the double up and the "never searched" rule held only by the accident of where doubles usually
    * live.
    */
  private val colocatedDouble =
    """effect Terminal {
      |   def write(line: String): Unit
      |}
      |
      |implement recordingTerminal: Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
      |}
      |
      |def greet uses Terminal: Unit = write("hi")
      |""".stripMargin

  it should "not be bound at the entry point, even colocated with its own ability" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$colocatedDouble
         |def main uses Terminal: Unit = greet
         |""".stripMargin
    ).asserting(_ should include("'main' uses the effect 'Terminal', which the platform has no standard implementation of."))
  }

  it should "bind that same colocated double where a `with` names it" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$colocatedDouble
         |def main uses Console: Unit = printLine(runWriterToLog(greet with recordingTerminal))
         |""".stripMargin
    ).asserting(_ shouldBe "hi;")
  }

  // An effect a member declares is the call's (`docs/effects.md` §3.4): dispatch hands the clause the binding at the
  // call, so the `with` binding the implementation answers nothing for it. Here the `Throw[String]` is `catch`'s, inside
  // the `with`'s subject, and nothing outside it has one — exactly the entry point binding `main`'s `FileSystem`.
  it should "need nothing around its `with` for an effect its member declares" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |effect Source {
        |   def next uses Throw[String]: String
        |}
        |
        |implement exhausted: Source {
        |   def next uses Throw[String]: String = raise("exhausted")
        |}
        |
        |def read uses Source, Throw[String]: String = next
        |
        |def main uses Console: Unit = printLine(catch(read, e -> "caught " ++ e) with exhausted)
        |""".stripMargin
    ).asserting(_ shouldBe "caught exhausted")
  }
}
