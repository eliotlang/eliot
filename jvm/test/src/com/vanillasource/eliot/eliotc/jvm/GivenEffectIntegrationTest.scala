package com.vanillasource.eliot.eliotc.jvm

/** A definition gives a computation it was handed only what it has (`docs/effects.md` D20 rule 5). A row-typed slot's
  * caller writes the actual's bindings from the declaration, and an entry the slot supplies by `Default` is a promise
  * that a frame for it is entered before the actual runs. Only a callee's slot that supplies the entry — a discharger
  * the computation is passed on to — keeps that promise; a platform primitive, being body-less, is where every such
  * frame starts. The witness for §8 item 13 is the first case.
  */
class GivenEffectIntegrationTest extends FullIntegrationTest {

  private val recording =
    """import eliot.effect.Console
      |import eliot.effect.Writer
      |
      |implement recordingConsole: Console {
      |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";")
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  // §8 item 13: the slot supplied the platform console by `Default`, so a pure signature performed real I/O.
  "a definition running code whose listed effect it neither handles nor declares" should "be rejected" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def launder(body uses *, Console: Unit): Unit = body
        |
        |def main uses Console: Unit = launder(printLine("hi"))
        |""".stripMargin
    ).asserting(
      _ should include(
        ":3:49:'body' may perform the effect 'Console' here, which this definition neither handles nor declares."
      )
    )
  }

  "a definition passing the computation on to a discharger" should "give that discharger's frame" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def attempt(body uses *, Abort: String): String = body else "none"
        |
        |def main uses Console: Unit = printLine(attempt(if(false, "some")))
        |""".stripMargin
    ).asserting(_ shouldBe "none")
  }

  "a `with` in the body" should "not give the computation anything" in {
    compileForErrors(
      recording +
        """
          |def launder(body uses *, Console: Unit): String = runWriterToLog(body with recordingConsole)
          |
          |def main uses Console: Unit = printLine(launder(printLine("hi")))
          |""".stripMargin
    ).asserting(_ should include("'body' may perform the effect 'Console' here"))
  }

  "a slot's named implementation" should "be given the effects its clauses perform" in {
    compileAndRun(
      recording +
        """
          |def transcriptOf(program uses *, Console with recordingConsole: Unit): String = runWriterToLog(program)
          |
          |def main uses Console: Unit = printLine(transcriptOf(printLine("hi")))
          |""".stripMargin
    ).asserting(_ shouldBe "hi;")
  }

  it should "be rejected when the definition does not give them" in {
    compileForErrors(
      recording +
        """
          |def silently(program uses *, Console with recordingConsole: Unit): Unit = program
          |
          |def main uses Console: Unit = silently(printLine("hi"))
          |""".stripMargin
    ).asserting(_ should include("'program' may perform the effect 'Writer' here"))
  }

  // docs/effects.md D20c, decided (b): an effect the named implementation performs that the definition also declares
  // is the caller's code, and its frame is the one where the block runs.
  it should "be given this definition's own frame when its row has the effect" in {
    compileAndRun(
      recording +
        """
          |def transcriptOf(program uses *, Console with recordingConsole: Unit) uses Writer[String]: Unit = {
          |   tell("before;")
          |   program
          |}
          |
          |def main uses Console: Unit = printLine(runWriterToLog(transcriptOf(printLine("Bob"))))
          |""".stripMargin
    ).asserting(_ shouldBe "before;Bob;")
  }

  it should "not count as performing an effect this definition declares but runs it under its own discharge of" in {
    compileForErrors(
      recording +
        """
          |def transcriptOf(program uses *, Console with recordingConsole: Unit) uses Writer[String]: String =
          |   runWriterToLog(program)
          |
          |def main uses Console: Unit = printLine(runWriterToLog(tell(transcriptOf(printLine("Bob")))))
          |""".stripMargin
    ).asserting(_ should include("declares the effect 'Writer' but does not perform it"))
  }

  "a computation promised the default" should "not be passed to a slot binding another implementation" in {
    compileForErrors(
      recording +
        """
          |def transcriptOf(program uses *, Console with recordingConsole: Unit): String = runWriterToLog(program)
          |def relay(program uses *, Console: Unit): String = transcriptOf(program)
          |
          |def main uses Console: Unit = printLine(relay(printLine("hi")))
          |""".stripMargin
    ).asserting(
      _ should include(
        "'program' was promised the default 'Console', but runs where a slot binds another implementation for it."
      )
    )
  }
}
