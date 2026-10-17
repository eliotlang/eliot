package com.vanillasource.eliot.eliotc.jvm

/** A `with` binds every effect of its ability that escapes its subject, and nothing discharged further down
  * (`docs/effects.md` D23). Around a run of a code parameter it binds what the slot lists, even an entry the definition
  * declares (D35); and one that nothing escapes to is an error, never a silent no-op (§8 items 1 and 20).
  */
class WithEscapeIntegrationTest extends FullIntegrationTest {

  private val recording =
    """import eliot.effect.Console
      |import eliot.effect.Writer
      |
      |implement rec: Console {
      |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";")
      |   def readLine: Option[String] = None
      |}
      |
      |implement loud: Console {
      |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ "!")
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  "a with around a run of a code parameter" should "bind the effect its slot lists" in {
    compileAndRun(
      recording +
        """
          |def capture(body uses *, Console: Unit) uses *: String = runWriterToLog(body with rec)
          |
          |def main uses Console: Unit = printLine("log=" ++ capture(printLine("hi")))
          |""".stripMargin
    ).asserting(_ shouldBe "log=hi;")
  }

  it should "bind nothing for an effect its slot does not list" in {
    compileForErrors(
      recording +
        """
          |def capture(body uses *: Unit) uses *: String = runWriterToLog(body with rec)
          |
          |def main uses Console: Unit = printLine(capture(printLine("hi")))
          |""".stripMargin
    ).asserting(
      _ should include(
        "This `with` binds nothing: 'body' is code its caller wrote, and its use of 'Console' was bound where it was " +
          "written."
      )
    )
  }

  it should "bind only the run it is around" in {
    compileForErrors(
      recording +
        """
          |def capture(body uses *, Console: Unit) uses *: String = runWriterToLog({
          |   body with rec
          |   body
          |})
          |
          |def main uses Console: Unit = printLine(capture(printLine("hi")))
          |""".stripMargin
    ).asserting(
      _ should include("'body' may perform the effect 'Console' here, which this definition neither handles nor declares.")
    )
  }

  "a with around a run" should "handle its entry even when the definition declares the effect" in {
    compileAndRun(
      recording +
        """
          |def capture(body uses *, Console: Unit) uses *, Console: String = {
          |   printLine("own")
          |   runWriterToLog(body with rec)
          |}
          |
          |def main uses Console: Unit = printLine("log=" ++ capture(printLine("hi")))
          |""".stripMargin
    ).asserting(_ shouldBe "own\nlog=hi;")
  }

  it should "leave the declaration over-declared when nothing else performs the effect" in {
    compileForErrors(
      recording +
        """
          |def capture(body uses *, Console: Unit) uses *, Console: String = runWriterToLog(body with rec)
          |
          |def main uses Console: Unit = printLine(capture(printLine("hi")))
          |""".stripMargin
    ).asserting(_ should include("This value declares the effect 'Console' but does not perform it."))
  }

  "a with nothing escapes to" should "be rejected" in {
    compileForErrors(
      recording +
        """
          |def quiet: String = "x" with rec
          |
          |def main uses Console: Unit = printLine(quiet)
          |""".stripMargin
    ).asserting(_ should include("This `with` binds nothing: no use of 'Console' in its subject escapes into it."))
  }
}
