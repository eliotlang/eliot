package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of `Eq[String]` — the string-equality instance that lives (body-less) in the base
  * `stdlib/.../String.els`, colocated with the `String` type, with its equality realised as a native attached *directly*
  * to the `equals` impl method (no `stringEquals` wrapper): the JVM backend emits `String.equals`
  * (`NativeImplementation`), and the compiler track folds two concrete literals (`StdlibNativesProcessor`).
  *
  * Two paths are exercised: a **runtime** comparison of a read line (the JVM `String.equals` native must fire),
  * and a **compile-time** comparison of two literals (the compiler-track native constant-folds the branch).
  */
class EqStringIntegrationTest extends FullIntegrationTest {

  "a runtime string comparison" should "select the matching branch via the JVM String.equals native" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def line uses Console: String = readLine.orAbort else ""
        |
        |def main uses Console: Unit = printLine(if(line == "yes", "matched") else "unmatched")""".stripMargin,
      stdin = "yes\n"
    ).asserting(_ shouldBe "matched")
  }

  it should "select the else branch when the runtime string differs" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def line uses Console: String = readLine.orAbort else ""
        |
        |def main uses Console: Unit = printLine(if(line == "yes", "matched") else "unmatched")""".stripMargin,
      stdin = "no\n"
    ).asserting(_ shouldBe "unmatched")
  }

  "a compile-time string comparison of two literals" should "constant-fold on the compiler track" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def label(s: String) uses Abort: String = if(s == "A", "first") else if(s == "B", "second")
        |
        |def main uses Console: Unit = printLine(label("B") else "?")""".stripMargin
    ).asserting(_ shouldBe "second")
  }
}
