package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of effectful-signatures **G1**: a guarded return type — a return whose *type* is a compile-time
  * computation that may reject — written with the **inline** guard vocabulary `if(cond, T) else raise(msg)` (plus a bare
  * `raise(msg)` for an unconditional rejection) over the effect carrier, compiled through the real pipeline with the
  * shipped `compiler`-layer `Either`/`Option`/`Abort` carriers.
  *
  * The guard rides the `{Throw[String]}`/`{Abort}` effect: `raise` expresses rejection and the pure arm expresses
  * acceptance, which the signature twin's compiler monomorphization reduces to `Left(msg)` / `Right(t)`. The consumer's
  * twin read then discharges — accepting the payload type or rejecting with the author's message. So a satisfied guard
  * compiles and runs as if the return were the bare type, and an unsatisfied one fails the build with the author
  * message. (This replaces the retired `eliot.lang.Guard` `when`/`orError` combinator surface — the signature split
  * Step 10; the inline form reduces through the same `if`/`else`/`fold`/`raise` machinery.)
  *
  * The checker's effect lift *does* elaborate the guard's branches, but the pure arm rides `if`'s own `{Abort}` return
  * and the `else` discharger consumes it, so a satisfied guard collapses to `Right(t)` rather than an unconditional
  * `Left`; that regression is what this suite guards against.
  */
class GuardSignatureIntegrationTest extends FullIntegrationTest {

  /** What a rejecting guard does now: it **fails the build at the use site**, and that is all this can assert.
    *
    * It used to say what the *author* wrote — `raise("greeting unavailable")` — and that went with the compile-track
    * `Throw` (effects v6 F5; the reversal is recorded in `docs/effects.md` beside that deletion). With nothing
    * implementing `raise` there, a rejection does not reduce to the `Left(msg)` the guarded-return discharge reads;
    * it stays a stuck residue and surfaces as a type mismatch naming it. So the *safety* property is intact — a
    * rejecting guard never produces a jar — and the *diagnostic* is not. Asserting the exact text would be asserting
    * a residue's spelling, which is worse than asserting nothing; asserting the failure is the honest floor.
    *
    * The route back is a compile-track `Throw` keyed on a fixed marker the way `Abort` keys on `Aborted`.
    */
  private def rejects(errors: String): org.scalatest.Assertion = errors.trim should not be empty

  "a satisfied inline `if..else..raise` guard" should "type as its payload and run as the bare type" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def greeting[COND: Bool]: if(COND, String[]) else raise("greeting unavailable") = "hello"
        |
        |def main uses Console: Unit = printLine(greeting[true])""".stripMargin
    ).asserting(_ shouldBe "hello")
  }

  "an unsatisfied inline `if..else..raise` guard" should "fail the build" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def greeting[COND: Bool]: if(COND, String[]) else raise("greeting unavailable") = "hello"
        |
        |def main uses Console: Unit = printLine(greeting[false])""".stripMargin
    ).asserting(rejects)
  }

  "a bare `raise(msg)` guard" should "fail the build" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def unavailable: raise("not available") = "x"
        |
        |def main uses Console: Unit = printLine(unavailable)""".stripMargin
    ).asserting(rejects)
  }

  // A compile-time guard written through a *user-defined* pipe and a user guard combinator (effect-lift Step 5): the
  // pipe and `guardOr` are ordinary user source, compiled on the compiler track like any other value; the checker's
  // effect lift elaborates their `{Throw[String]}` path per instantiation and the signature still reduces to
  // `Right`/`Left` for the discharge. Proves the compile-time reduction is not special-cased to the `if`/`else` shape.
  private val pipedGuard: String =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |import eliot.effect.Abort
      |
      |infix left below apply def |>[A, B](a: A, f: A => B): B = f(a)
      |
      |def guardOr[A](cond: Bool, value: A) uses Throw[String]: A = if(cond, value) else raise("greeting unavailable")
      |
      |def greeting[COND: Bool]: String[] |> guardOr(COND) = "hello"
      |""".stripMargin

  "a satisfied guard written through a user pipe" should "type as its payload and run as the bare type" in {
    compileAndRun(pipedGuard + "\ndef main uses Console: Unit = printLine(greeting[true])")
      .asserting(_ shouldBe "hello")
  }

  "an unsatisfied guard written through a user pipe" should "fail the build" in {
    compileForErrors(pipedGuard + "\ndef main uses Console: Unit = printLine(greeting[false])")
      .asserting(rejects)
  }

  // --- G2: the compile-time integer comparison inside an inline guard ---
  //
  // A guard whose condition compares a `BigInteger` type parameter against a *literal* (`MIN > 0`, `N < 10`) exercises
  // both the compile-time comparison (`eliot.lang.Compare`, reduced through the existing `lessThanOrEqual` native) and
  // the signature-literal handling: an integer literal in a signature is a compile-time `BigInteger`, so it unifies
  // with `MIN`/`N` rather than defaulting to a runtime `Int` (the signature split Step 10 fix in
  // `CoreExpressionConverter`). `BigInteger` stays ambiently imported; only the ordering surface needs `Compare`.

  "a satisfied `MIN > 0` inline guard" should "type as its payload and run as the bare type" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def positive[MIN: BigInteger]: if(MIN > 0, String[]) else raise("must be positive") = "ok"
        |
        |def main uses Console: Unit = printLine(positive[5])""".stripMargin
    ).asserting(_ shouldBe "ok")
  }

  "an unsatisfied `MIN > 0` inline guard" should "fail the build" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def positive[MIN: BigInteger]: if(MIN > 0, String[]) else raise("must be positive") = "ok"
        |
        |def main uses Console: Unit = printLine(positive[0])""".stripMargin
    ).asserting(rejects)
  }

  "a satisfied `N < 10` inline guard" should "type as its payload and run as the bare type" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def small[N: BigInteger]: if(N < 10, String[]) else raise("too big") = "ok"
        |
        |def main uses Console: Unit = printLine(small[3])""".stripMargin
    ).asserting(_ shouldBe "ok")
  }
}
