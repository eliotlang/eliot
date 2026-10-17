package com.vanillasource.eliot.eliotc.jvm

/** A run of a code parameter passed on to a callee's slot is given what that callee's runs give (`docs/effects.md`
  * D35): the evidence installed around the callee's own run, read off its body after monomorphization. That is what
  * lets a pass-through discharger write no `with` of its own.
  */
class PassedRunIntegrationTest extends FullIntegrationTest {

  private val terminal =
    """import eliot.effect.Console
      |
      |effect Terminal {
      |   def write(line: String): Unit
      |   def read: String
      |}
      |
      |def greet uses Terminal: Unit = {
      |   val name = read
      |   write("Hello, " ++ name ++ "!")
      |}
      |
      |implement scripted(answer: String): Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
      |   def read: String = answer
      |}
      |
      |implement fixed: Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
      |   def read: String = "fixed"
      |}
      |""".stripMargin

  "a run passed to a callee installing an implementation around its run" should "run on that implementation" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;")
  }

  it should "run on the arguments the callee's `with` supplies" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("a"))
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, a!;")
  }

  it should "run on it along a chain of pass-throughs" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("a"))
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def outer(body uses *, Terminal: Unit) uses *: String = passing(body)
         |def main uses Console: Unit = printLine(outer(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, a!;")
  }

  it should "agree with a `with` around another run of the same implementation" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def twice(body uses *, Terminal: Unit) uses *: String = run(body) ++ runWriterToLog(body with fixed)
         |def main uses Console: Unit = printLine(twice(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;Hello, fixed!;")
  }

  it should "not override a `with` around the run inside the block" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def passing(body uses *, Terminal: Unit) uses *: String = runWriterToLog(run(body with scripted("own")))
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, own!;")
  }

  "runs of one parameter given different implementations" should "each run the block on its own" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def twice(body uses *, Terminal: Unit) uses *: String = run(body) ++ runWriterToLog(body with scripted("b"))
         |def main uses Console: Unit = printLine(twice(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;Hello, b!;")
  }

  it should "keep the parameters after the slot where they are" in {
    compileAndRun(
      s"""$terminal
         |def twice(body uses *, Terminal: Unit, separator: String) uses *: String =
         |   runWriterToLog(body with fixed) ++ separator ++ runWriterToLog(body with scripted("b"))
         |def main uses Console: Unit = printLine(twice(greet, "|"))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;|Hello, b!;")
  }

  it should "give one block per distinct implementation" in {
    compileAndRun(
      s"""$terminal
         |def thrice(body uses *, Terminal: Unit) uses *: String =
         |   runWriterToLog(body with fixed) ++ runWriterToLog(body with scripted("b")) ++ runWriterToLog(body with fixed)
         |def main uses Console: Unit = printLine(thrice(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;Hello, b!;Hello, fixed!;")
  }

  it should "give a block passed on to two callees what each of them installs" in {
    compileAndRun(
      s"""$terminal
         |def first(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def second(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("b"))
         |def both(body uses *, Terminal: Unit) uses *: String = first(body) ++ second(body)
         |def outer(body uses *, Terminal: Unit) uses *: String = both(body)
         |def main uses Console: Unit = printLine(outer(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;Hello, b!;")
  }

  it should "each run the block on its own in an implementation's clause taking parameters" in {
    compileAndRun(
      s"""$terminal
         |effect Runner {
         |   def go(body uses *, Terminal: Unit) uses *: String
         |}
         |implement twoWays(separator: String): Runner {
         |   def go(body uses *, Terminal: Unit) uses *: String =
         |      runWriterToLog(body with fixed) ++ separator ++ runWriterToLog(body with scripted("b"))
         |}
         |def main uses Console: Unit = printLine(go(greet) with twoWays("|"))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;|Hello, b!;")
  }

  "a run of an entry the definition also declares" should "ride its caller's binding, its frame given where it runs" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with fixed)
         |def passing(body uses *, Terminal: Unit) uses *, Terminal: String = {
         |   write("<")
         |   run(body)
         |}
         |def main uses Console: Unit = printLine(runWriterToLog(printLine(passing(greet) with fixed)))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;\n<;")
  }

  // `recording` performs `State[String]` in its clauses, which the slot does not list; the `with` stands inside
  // `runStateToFinalState`'s block, which gives it.
  "a clause entry of an implementation installed around a run" should "be answered where the `with` stands" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |effect Mocking {
        |   def note(s: String): Unit
        |}
        |
        |implement recording: Mocking {
        |   def note(s: String) uses State[String]: Unit = putState(state ++ s)
        |}
        |
        |def twice(body uses *: Unit) uses *: Unit = {
        |   body
        |   body
        |}
        |
        |def mocked(body uses *, Mocking: Unit) uses *: String = runStateToFinalState("", twice(body with recording))
        |
        |def main uses Console: Unit = printLine(mocked(note("b")))""".stripMargin
    ).asserting(_ shouldBe "bb")
  }

  // eliot-test's `mocked`: `catch` gives the supplied `Throw[Int]`, and the declared `Throw[String]` rides to the
  // caller's `catch`.
  "a run given one entry of an ability" should "leave a declared entry of the same ability riding" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def guarded(body uses *, Throw[Int], Throw[String]: Unit) uses *, Throw[String]: Unit =
        |   catch[Int, Unit](body, e -> raise("caught " ++ show(e)))
        |
        |def failing uses Throw[Int]: Unit = raise(7)
        |
        |def asserting uses Throw[String]: Unit = raise("passed through")
        |
        |def outcome(body uses *, Throw[Int], Throw[String]: Unit) uses *: String =
        |   catch[String, String]({
        |      guarded(body)
        |      "none"
        |   }, e -> e)
        |
        |def main uses Console: Unit = {
        |   printLine(outcome(failing))
        |   printLine(outcome(asserting))
        |}""".stripMargin
    ).asserting(_ shouldBe "caught 7\npassed through")
  }

  "a run passed on in a cycle" should "be reported as recursion" in {
    compileForErrors(
      s"""$terminal
         |def ping(body uses *, Terminal: Unit) uses *: String = pong(body)
         |def pong(body uses *, Terminal: Unit) uses *: String = ping(body)
         |def main uses Console: Unit = printLine(ping(greet))""".stripMargin
    ).asserting(_ should include("Value 'ping' is defined recursively."))
  }
}
