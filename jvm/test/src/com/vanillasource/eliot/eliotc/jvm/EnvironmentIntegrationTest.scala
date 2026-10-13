package com.vanillasource.eliot.eliotc.jvm

/** An implementation's runtime environment is passed, never searched for — effects D20c option 5 (`docs/effects.md`
  * §11). A frame reaches exactly the operations its discharger gave it to, so a helper's own discharger cannot intercept
  * its caller's effects, and an effect whose implementation needs a frame cannot run where nothing installed one.
  */
class EnvironmentIntegrationTest extends FullIntegrationTest {

  private val recordingConsole =
    """implement recordingConsole: Console {
      |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";")
      |
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  "a named implementation bound by a body `with`" should "write into the discharge in scope at the `with`" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def greet uses Console: Unit = printLine("Bob")
         |def main uses Console: Unit = printLine(runWriterToLog(greet with recordingConsole))""".stripMargin
    ).asserting(_ shouldBe "Bob;")
  }

  it should "be rejected where a nearer discharge of an effect it performs stands between it and its use" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def main uses Console: Unit =
         |   printLine(runWriterToLog(printLine(runWriterToLog(printLine("Bob"))) with recordingConsole))""".stripMargin
    ).asserting(_ should include("and a nearer binding of 'Writer' stands between them"))
  }

  it should "not count a nearer discharge of the same effect at other arguments as standing between" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |implement refusingConsole: Console {
        |   def printLine(s: String) uses Throw[String]: Unit = raise("refused " ++ s)
        |
        |   def readLine: Option[String] = None
        |}
        |
        |def counted(body uses *, Throw[Bool]: Unit): String = catch[Bool, String]({
        |   body
        |   "finished"
        |}, b -> "bool")
        |
        |def greet uses Console: Unit = printLine("Bob")
        |
        |def main uses Console: Unit =
        |   printLine(catch[String, String](counted(greet) with refusingConsole, e -> e))""".stripMargin
    ).asserting(_ shouldBe "refused Bob")
  }

  it should "keep writing into that discharge through a definition that receives it" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def inner uses Console: Unit = printLine("[" ++ runWriterToLog(printLine("Bob")) ++ "]")
         |def main uses Console: Unit = printLine(runWriterToLog(inner with recordingConsole))""".stripMargin
    ).asserting(_ shouldBe "Bob;[];")
  }

  "a caller's raise" should "reach the caller's catch, not a helper's own (docs/effects.md §8 item 16)" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def guarded(body uses *: Unit) uses Console: Unit =
        |   catch[String, Unit]({
        |      body
        |      printLine("helper: body finished")
        |   }, e -> printLine("helper caught: " ++ e))
        |
        |def main uses Console: Unit =
        |   catch[String, Unit]({
        |      guarded(raise("boom"))
        |      printLine("caller: after guarded")
        |   }, e -> printLine("caller caught: " ++ e))""".stripMargin
    ).asserting(_ shouldBe "caller caught: boom")
  }

  "a caller's putState" should "write the caller's state, not a helper's own (docs/effects.md §8 item 17)" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def counted(body uses *: Unit) uses Console: Unit =
        |   printLine("helper's state: " ++ runStateToFinalState("helper", body))
        |
        |def main uses Console: Unit =
        |   printLine("caller's state: " ++ runStateToFinalState("caller", counted(putState("written by caller"))))""".stripMargin
    ).asserting(_ shouldBe "helper's state: helper\ncaller's state: written by caller")
  }

  "two frames of one effect" should "each be reached by its own instantiation" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def both(flag: Bool) uses Throw[String], Throw[Bool]: Unit = if(flag, raise("text")) else raise(true)
        |def outcome(flag: Bool): String =
        |   catch[Bool, String](catch[String, String]({
        |      both(flag)
        |      "none"
        |   }, s -> "string " ++ s), b -> "bool")
        |def main uses Console: Unit = {
        |   printLine(outcome(true))
        |   printLine(outcome(false))
        |}""".stripMargin
    ).asserting(_ shouldBe "string text\nbool")
  }

  "a control effect nothing discharges" should "not compile (D19)" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def main uses Console, Throw[String]: Unit = {
        |   printLine("start")
        |   raise("never caught")
        |}""".stripMargin
    ).asserting(_ should include("The effect 'Throw' is performed here with nothing to discharge it"))
  }

  "a definition running its block inside its own discharger of an effect it also declares" should
    "catch the block's raise and re-raise a better one" in {
      compileAndRun(
        """import eliot.effect.Console
          |
          |def describedAs(body uses *, Throw[String]: Unit, message: String) uses Throw[String]: Unit =
          |   catch[String, Unit](body, cause -> raise(message ++ " (" ++ cause ++ ")"))
          |def main uses Console: Unit = catch[String, Unit](describedAs(raise("boom"), "described"), e -> printLine(e))""".stripMargin
      ).asserting(_ shouldBe "described (boom)")
    }

  "a block whose slot rides the definition's own entry" should "be given the definition's own frame where it runs" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def twice(body uses *, Throw[String]: Unit) uses Throw[String]: Unit = {
        |   body
        |   body
        |}
        |def main uses Console: Unit = catch[String, Unit](twice(raise("once")), e -> printLine(e))""".stripMargin
    ).asserting(_ shouldBe "once")
  }

  // eliot-test's `mocked`: its slot binds `mockFileSystem`, whose clauses perform `Throw[IoError]`, while `mocked`
  // itself declares only `Throw[AssertionError]`. The clause entry is supplied by the callee, not ridden from the caller.
  "a slot's named implementation performing an effect the definition declares at other arguments" should
    "be given that effect by the definition" in {
      compileAndRun(
        """import eliot.effect.Console
          |
          |implement refusingConsole: Console {
          |   def printLine(s: String) uses Throw[String]: Unit = raise("refused " ++ s)
          |
          |   def readLine: Option[String] = None
          |}
          |
          |def guarded(body uses *, Console with refusingConsole, Throw[String]: Unit) uses Throw[Bool]: String = {
          |   val outcome = catch[String, String]({
          |      body
          |      "finished"
          |   }, e -> e)
          |   if(outcome == "", raise(true)) else outcome
          |}
          |
          |def main uses Console: Unit = printLine(catch[Bool, String](guarded(printLine("Bob")), b -> "bool"))""".stripMargin
      ).asserting(_ shouldBe "refused Bob")
    }
}
