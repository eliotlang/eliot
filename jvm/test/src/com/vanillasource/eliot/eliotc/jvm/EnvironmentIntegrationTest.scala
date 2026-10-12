package com.vanillasource.eliot.eliotc.jvm

/** An implementation's runtime environment is passed, never searched for — effects D20c option 5 (`docs/effects.md`
  * §11). A frame reaches exactly the operations its discharger gave it to, so a helper's own discharger cannot intercept
  * its caller's effects, and an effect whose implementation needs a frame cannot run where nothing installed one.
  */
class EnvironmentIntegrationTest extends FullIntegrationTest {

  private val recordingConsole =
    """implement recordingConsole: Console {
      |   def printLine(s: String): {Writer[String]} Unit = tell(s ++ ";")
      |
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  "a named implementation bound by a body `with`" should "write into the discharge in scope at the `with`" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def greet: {Console} Unit = printLine("Bob")
         |def main: {Console} Unit = printLine(runWriterToLog(greet with recordingConsole))""".stripMargin
    ).asserting(_ shouldBe "Bob;")
  }

  it should "be rejected where a nearer discharge of an effect it performs stands between it and its use" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def main: {Console} Unit =
         |   printLine(runWriterToLog(printLine(runWriterToLog(printLine("Bob"))) with recordingConsole))""".stripMargin
    ).asserting(_ should include("and a nearer binding of 'Writer' stands between them"))
  }

  it should "keep writing into that discharge through a definition that receives it" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def inner: {Console} Unit = printLine("[" ++ runWriterToLog(printLine("Bob")) ++ "]")
         |def main: {Console} Unit = printLine(runWriterToLog(inner with recordingConsole))""".stripMargin
    ).asserting(_ shouldBe "Bob;[];")
  }

  "a caller's raise" should "reach the caller's catch, not a helper's own (docs/effects.md §8 item 14)" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def guarded(body: {} Unit): {Console} Unit =
        |   catch[String, Unit]({
        |      body
        |      printLine("helper: body finished")
        |   }, e -> printLine("helper caught: " ++ e))
        |
        |def main: {Console} Unit =
        |   catch[String, Unit]({
        |      guarded(raise("boom"))
        |      printLine("caller: after guarded")
        |   }, e -> printLine("caller caught: " ++ e))""".stripMargin
    ).asserting(_ shouldBe "caller caught: boom")
  }

  "a caller's putState" should "write the caller's state, not a helper's own (docs/effects.md §8 item 15)" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def counted(body: {} Unit): {Console} Unit =
        |   printLine("helper's state: " ++ runStateToFinalState("helper", body))
        |
        |def main: {Console} Unit =
        |   printLine("caller's state: " ++ runStateToFinalState("caller", counted(putState("written by caller"))))""".stripMargin
    ).asserting(_ shouldBe "helper's state: helper\ncaller's state: written by caller")
  }

  "two frames of one effect" should "each be reached by its own instantiation" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def both(flag: Bool): {Throw[String], Throw[Bool]} Unit = if(flag, raise("text")) else raise(true)
        |def outcome(flag: Bool): String =
        |   catch[Bool, String](catch[String, String]({
        |      both(flag)
        |      "none"
        |   }, s -> "string " ++ s), b -> "bool")
        |def main: {Console} Unit = {
        |   printLine(outcome(true))
        |   printLine(outcome(false))
        |}""".stripMargin
    ).asserting(_ shouldBe "string text\nbool")
  }

  "a control effect nothing discharges" should "not compile (D19)" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def main: {Console, Throw[String]} Unit = {
        |   printLine("start")
        |   raise("never caught")
        |}""".stripMargin
    ).asserting(_ should include("The effect 'Throw' is performed here with nothing to discharge it"))
  }
}
