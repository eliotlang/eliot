package com.vanillasource.eliot.eliotc.jvm

/** The synthesized entry point runs `main` by explicit passing (`docs/effects.md` D26): one `with` per effect of its
  * clause, naming the `standard…` implementation the base declares and the platform bodies.
  */
class EntryPointIntegrationTest extends FullIntegrationTest {

  "the entry point" should "bind every effect of main's clause to its standard implementation" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Log
        |
        |def main uses Console, Log: Unit = {
        |   log("logged")
        |   printLine("printed")
        |}""".stripMargin
    ).asserting(_ should include("printed"))
  }

  it should "run a main that declares no effect" in {
    compileAndRunToCompletion("def main: Unit = unit").asserting(_ shouldBe ("", 0))
  }

  "a standard implementation" should "be bound by name like any named implementation" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def greet uses Console: Unit = printLine("hi")
        |
        |def main: Unit = greet with standardConsole""".stripMargin
    ).asserting(_ shouldBe "hi")
  }
}
