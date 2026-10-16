package com.vanillasource.eliot.eliotc.jvm

/** What separates an effect from an ability at their declarations (`docs/effects.md` §8 items 50 and 53): an anonymous
  * implementation of an ability is found by its type and never bound, so its clauses may perform only what their
  * members declare; and an effect is recorded as one by its operations, so it must declare one.
  */
class AbilityKindIntegrationTest extends FullIntegrationTest {

  private def describing(member: String, clause: String) =
    s"""import eliot.effect.Console
       |
       |ability Describe[T] {
       |   def describe(t: T)$member: String
       |}
       |
       |data Box(n: Int)
       |
       |implement Describe[Box] {
       |   def describe(t: Box)$clause: String = {
       |      printLine("describing")
       |      "box"
       |   }
       |}
       |
       |def main uses Console: Unit = printLine(describe(Box(1)))""".stripMargin

  // §8 item 50: the clause's `Console` got `Default`, and failed only at monomorphization, at its `printLine`.
  "an anonymous implementation's clause performing an effect its member does not declare" should
    "be refused at the clause" in {
      compileForErrors(describing("", " uses Console")).asserting(
        _ should include(
          ":10:8:This implementation of 'Describe' performs the effect 'Console' in 'describe', which the ability's " +
            "'describe' does not declare"
        )
      )
    }

  "an anonymous implementation's clause performing what its member declares" should "run on the call's binding" in {
    compileAndRun(describing(" uses Console", " uses Console")).asserting(_ shouldBe "describing\nbox")
  }

  // §8 item 53: with no operation, nothing records that it is an effect, and nothing could perform it.
  "an effect with no operation" should "be refused at its declaration" in {
    compileForErrors(
      """import eliot.effect.Console
        |effect Network
        |
        |def main uses Console: Unit = printLine("x")""".stripMargin
    ).asserting(_ should include(":2:8:The effect 'Network' declares no operation, so nothing could perform it."))
  }
}
