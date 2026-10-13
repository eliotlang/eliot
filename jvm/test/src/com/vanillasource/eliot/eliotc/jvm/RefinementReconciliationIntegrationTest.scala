package com.vanillasource.eliot.eliotc.jvm

/** End-to-end regression for the refinement reconciliation
  * ([[com.vanillasource.eliot.eliotc.monomorphize.refine.RefinementSolver.reconcileRefinements]]): a coercion that a
  * post-drain resolution — a `Combine` join or a deferred upper bound — only *verified* used to reach the backend
  * without its `nativeWiden` payload, so the value arrived at the contributor's own machine representation and the
  * generated class failed verification (`VerifyError`: a boxed `Short` on the stack where the join's `Integer` is
  * expected). These programs must not only compile but run.
  */
class RefinementReconciliationIntegrationTest extends FullIntegrationTest {

  "a Combine-joined generic slot" should "run with each contributor widened to the join" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[A](a: A, b: A): A = a
        |
        |def main uses Console: Unit = printLine(show(pick(3, 700)))""".stripMargin
    ).asserting(_ shouldBe "3")
  }

  "a picked second argument" should "run and return that argument" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[A](a: A, b: A): A = b
        |
        |def wide: Int = pick(3, 700)
        |
        |def main uses Console: Unit = printLine(show(wide))""".stripMargin
    ).asserting(_ shouldBe "700")
  }

  // The channel's table is keyed by source position, but desugaring makes positions non-unique: a pure `val` block
  // lowers to `(x -> rest)(e)` with the synthesized lambda *and* application both anchored on the bound expression's own
  // range (`BlockDesugaringProcessor.buildTower`), so three nodes share one position. The bound literal's `[1, 1]` was
  // therefore stamped on the application too — whose value is the continuation, not the literal — and the backend
  // re-encoded the `Function.apply` result as a `Byte`: `VerifyError: 'java/math/BigInteger' is not assignable to
  // 'java/lang/Byte'` at class load. A position with disagreeing verdicts must stay ⊤ for every node sharing it.
  "an integer literal bound by a pure val block" should "not leak its range onto the block's application node" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def compute: Int = {
        |  val ignored = 1
        |  42
        |}
        |
        |def main uses Console: Unit = printLine(show(compute))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "run when the binding is the block's result" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def compute: Int = {
        |  val v = 1
        |  v
        |}
        |
        |def main uses Console: Unit = printLine(show(compute))""".stripMargin
    ).asserting(_ shouldBe "1")
  }

  "an integer literal as an effect-discharge fallback" should "run widened into the discharged slot" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |import eliot.effect.Abort
        |
        |def parsePort(raw: String) uses Abort: Int = abort
        |
        |def nextPort uses Console, State[String], Abort: Int = {
        |  val raw = readLine.orAbort else ""
        |  putState(raw)
        |  parsePort(raw)
        |}
        |
        |def main uses Console: Unit = {
        |  val result = runStateToPair("<none>", nextPort else 8080)
        |  printLine(show(result.first))
        |}""".stripMargin,
      stdin = "not-a-number\n"
    ).asserting(_ shouldBe "8080")
  }
}
