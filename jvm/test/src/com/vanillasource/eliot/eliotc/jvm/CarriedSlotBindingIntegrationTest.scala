package com.vanillasource.eliot.eliotc.jvm

/** A run of a code parameter passed to a callee's slot that binds one of its listed entries is a run under that
  * slot's `with`, and the binding moves onto the definition's own slot as a written one would
  * (`docs/effects.md` D25, step 3). That is what lets a pass-through discharger write no `with` of its own.
  */
class CarriedSlotBindingIntegrationTest extends FullIntegrationTest {

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

  "a run passed to a slot that binds its entry" should "be bound as that slot binds" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;")
  }

  it should "carry a `with` around the callee's run, whose arguments stay there" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("a"))
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, a!;")
  }

  it should "carry it along a chain of pass-throughs" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("a"))
         |def passing(body uses *, Terminal: Unit) uses *: String = run(body)
         |def outer(body uses *, Terminal: Unit) uses *: String = passing(body)
         |def main uses Console: Unit = printLine(outer(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, a!;")
  }

  it should "agree with a `with` around another run" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |def twice(body uses *, Terminal: Unit) uses *: String = run(body) ++ runWriterToLog(body with fixed)
         |def main uses Console: Unit = printLine(twice(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;Hello, fixed!;")
  }

  it should "not bind a run under a `with` of its own" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |def passing(body uses *, Terminal: Unit) uses *: String = runWriterToLog(run(body with scripted("own")))
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(_ shouldBe "Hello, own!;")
  }

  it should "bind the same implementation as the definition's other runs" in {
    compileForErrors(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |def twice(body uses *, Terminal: Unit) uses *: String = run(body) ++ runWriterToLog(body with scripted("b"))
         |def main uses Console: Unit = printLine(twice(greet))""".stripMargin
    ).asserting(_ should include("'body' runs here with a different implementation of 'Terminal' than at its other runs."))
  }

  it should "bind the implementation the definition's slot names" in {
    compileForErrors(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |implement other: Terminal {
         |   def write(line: String): Unit = unit
         |   def read: String = "other"
         |}
         |def passing(body uses *, Terminal with other: Unit) uses *: String = run(body)
         |def main uses Console: Unit = printLine(passing(greet))""".stripMargin
    ).asserting(
      _ should include("The slot of 'body' names another implementation of 'Terminal' than the `with` around its runs.")
    )
  }

  "a run of an entry the definition also declares" should "ride its caller's binding, its frame given where it runs" in {
    compileAndRun(
      s"""$terminal
         |def run(body uses *, Terminal with fixed: Unit) uses *: String = runWriterToLog(body)
         |def passing(body uses *, Terminal: Unit) uses *, Terminal: String = {
         |   write("<")
         |   run(body)
         |}
         |def main uses Console: Unit = printLine(runWriterToLog(printLine(passing(greet) with fixed)))""".stripMargin
    ).asserting(_ shouldBe "Hello, fixed!;\n<;")
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
