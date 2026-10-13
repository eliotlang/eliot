package com.vanillasource.eliot.eliotc.jvm

/** A field holds a value (`docs/effects.md` D20 rule 6). A row on a `data` field once stored a computation: a thunk
  * whose operations were bound where the value was constructed, read back later. That binding outlived what it named —
  * the constructor supplied the platform's default where a test's double was meant (§8 item 12), and a discharger's
  * frame was gone by the time the read ran it — so a row anywhere in a field's type is rejected, and the work is stored
  * as data and performed where the effects are in scope. Since D21 step 6 a row in a type does not parse at all, and a
  * field's refusal says why a field has none rather than naming a `uses` clause it cannot take.
  */
class FieldValueIntegrationTest extends FullIntegrationTest {

  private val fieldRow =
    "Expected a value type, since a data field holds a value and not a computation (store data describing the work, " +
      "and perform it where the effects are in scope), but encountered symbol '{'."

  // §8 item 12: the constructor supplied `Console` by `Default`, so the job printed to the real console under a double.
  "a field whose type carries a row" should "be rejected at the row" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Job(run: {Console} Unit)
        |
        |def makeJob: Job = Job(printLine("job"))
        |
        |def main uses Console: Unit = unit
        |""".stripMargin
    ).asserting(_ should include(s":3:15:$fieldRow"))
  }

  "a field whose type carries the empty row" should "be rejected the same way" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Lazy(value: {} String)
        |
        |def main uses Console: Unit = unit
        |""".stripMargin
    ).asserting(_ should include(s":3:18:$fieldRow"))
  }

  "a field whose function type carries a row in its codomain" should "be rejected the same way" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Handler(handle: String => {Console} Unit)
        |
        |def main uses Console: Unit = unit
        |""".stripMargin
    ).asserting(_ should include(s":3:32:$fieldRow"))
  }

  "a field storing a non-terminating computation" should "be rejected, not run" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |data Box(action: {Inf, Console} Unit)
        |
        |def main uses Inf, Console: Unit = action(Box(forever(printLine("boxed"))))
        |""".stripMargin
    ).asserting(_ should include(s":4:18:$fieldRow"))
  }

  "work stored as data" should "be performed where the effects are in scope" in {
    compileAndRun(
      """import eliot.collection.List
        |import eliot.effect.Console
        |
        |data Step = Skip | Print(line: String)
        |
        |def perform(step: Step) uses Console: Unit = step match {
        |   case Skip -> unit
        |   case Print(line) -> printLine(line)
        |}
        |
        |def main uses Console: Unit =
        |   foreach(s -> perform(s), prepend(prepend(prepend(empty, Print("b")), Skip), Print("a")))
        |""".stripMargin
    ).asserting(_ shouldBe "a\nb")
  }
}
