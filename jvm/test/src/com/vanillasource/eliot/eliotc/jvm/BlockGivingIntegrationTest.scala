package com.vanillasource.eliot.eliotc.jvm

/** A definition gives its block only what it has — D20 rule 5 (`docs/effects.md` §11), closing §8 item 13.
  *
  * A row-typed parameter's declaration promises its block the entries the slot *supplies*, and the caller writes the
  * block's bindings from that promise. So running the block is checked like a call: each promised entry must be given
  * where it runs — by the slot's own `with`, or by a further slot that supplies it, which ends at a body-less
  * declaration (a platform primitive). A body that gives nothing launders the effect behind a pure signature; a body
  * that gives a different implementation makes the declaration a lie, since the block already runs on the promised one.
  */
class BlockGivingIntegrationTest extends FullIntegrationTest {

  private val recordingConsole =
    """implement recordingConsole: Console {
      |   def printLine(s: String): {Writer[String]} Unit = tell(s ++ ";")
      |
      |   def readLine: Option[String] = None
      |}
      |""".stripMargin

  "a block run by a definition that gives it nothing" should "be rejected, rather than launder the effect" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def launder(body: {Console} Unit): Unit = body
        |def looksPure(name: String): Unit = launder(printLine(name))
        |def main: {Console} Unit = looksPure("Bob")""".stripMargin
    ).asserting(_ should include("is promised the effect 'Console' by this definition's declaration, but nothing here gives it"))
  }

  it should "be rejected when passed on to a slot that gives nothing either" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def launder(body: {Console} Unit): Unit = fold(true, body, unit)
        |def main: {Console} Unit = launder(printLine("Bob"))""".stripMargin
    ).asserting(_ should include("nothing here gives it"))
  }

  "a block given a different implementation than its declaration promised" should "be rejected" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def rebind(body: {Console} Unit): String = runWriterToLog(body with recordingConsole)
         |def main: {Console} Unit = printLine(rebind(printLine("Bob")))""".stripMargin
    ).asserting(_ should include("is given the implementation 'recordingConsole' here"))
  }

  "a block passed on to a discharger" should "be given what the discharger supplies" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def guarded(c: {Abort} String): String = c else "fallback"
        |def main: {Console} Unit = printLine(guarded(abort))""".stripMargin
    ).asserting(_ shouldBe "fallback")
  }

  it should "be given it through a user-written discharger that passes it on" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def runTest(test: {Throw[String]} Unit): {Console} Unit = test catch (e -> printLine("failed: " ++ e))
        |def main: {Console} Unit = runTest(raise("boom"))""".stripMargin
    ).asserting(_ shouldBe "failed: boom")
  }

  "a block whose slot names its implementation" should "be given it by that declaration" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def transcriptOf(program: {Console} Unit with recordingConsole): String = runWriterToLog(program)
         |def main: {Console} Unit = printLine(transcriptOf(printLine("Bob")))""".stripMargin
    ).asserting(_ shouldBe "Bob;")
  }

  "a block whose slot names an implementation performing an effect" should "be rejected where nothing gives that effect" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def transcriptOf(program: {Console} Unit with recordingConsole): String = {
         |   program
         |   "ran"
         |}
         |def main: {Console} Unit = printLine(transcriptOf(printLine("Bob")))""".stripMargin
    ).asserting(
      _ should include(
        "'program' is promised the effect 'Writer' by this definition's declaration (its implementation " +
          "'recordingConsole' performs it), but nothing here gives it"
      )
    )
  }

  it should "be rejected rather than perform it into a caller's unrelated frame" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def transcriptOf(program: {Console} Unit with recordingConsole): String = {
         |   program
         |   "ran"
         |}
         |def outer: {Console, Writer[String]} Unit = printLine(transcriptOf(printLine("Bob")))
         |def main: {Console} Unit = printLine(runWriterToLog(outer))""".stripMargin
    ).asserting(_ should include("(its implementation 'recordingConsole' performs it), but nothing here gives it"))
  }

  it should "be given this definition's own frame when its row has the effect" in {
    compileAndRun(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def transcriptOf(program: {Console} Unit with recordingConsole): {Writer[String]} Unit = {
         |   tell("before;")
         |   program
         |}
         |def main: {Console} Unit = printLine(runWriterToLog(transcriptOf(printLine("Bob"))))""".stripMargin
    ).asserting(_ shouldBe "before;Bob;")
  }

  it should "not count as performing an effect this definition declares but runs it under its own discharge of" in {
    compileForErrors(
      s"""import eliot.effect.Console
         |$recordingConsole
         |def transcriptOf(program: {Console} Unit with recordingConsole): {Writer[String]} String = runWriterToLog(program)
         |def main: {Console} Unit = printLine(runWriterToLog(tell(transcriptOf(printLine("Bob")))))""".stripMargin
    ).asserting(_ should include("declares the effect 'Writer' but does not perform it"))
  }
}
