package com.vanillasource.eliot.eliotc.jvm

/** Regression coverage for two specialisations of one generic value that differ only in a **value-level** type
  * argument: `pick[false]` and `pick[true]` of `def pick[C: Bool]`. Both have the same JVM descriptor, so their method
  * names are what keeps them apart — and the backend's mangling erased a constant argument to its carrier, which for a
  * `Bool` is `Any`, so both were emitted as `pick$Any` and one silently replaced the other: the program printed `1 1`.
  */
class ValueTypeArgumentIntegrationTest extends FullIntegrationTest {

  "two specialisations differing in a Bool type argument" should "both be emitted" in {
    compileAndRun(
      """import eliot.effect.Console
        |def pick[C: Bool]: Int = if(C, 1) else 2
        |
        |def main uses Console: Unit = printLine(show(pick[false]) ++ " " ++ show(pick[true]))""".stripMargin
    ).asserting(_ shouldBe "2 1")
  }

  "two specialisations differing in a String type argument" should "both be emitted" in {
    compileAndRun(
      """import eliot.effect.Console
        |def greeting[S: String]: String = S
        |
        |def main uses Console: Unit = printLine(greeting["a$b"] ++ " " ++ greeting["a"])""".stripMargin
    ).asserting(_ shouldBe "a$b a")
  }
}
