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
}
