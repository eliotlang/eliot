package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of a guarded return type — a return whose *type* is a compile-time computation that may reject
  * — written with the inline guard vocabulary `if(cond, T) else raise(msg)` (plus a bare `raise(msg)` for an
  * unconditional rejection), compiled through the real pipeline with the compile-track overlay's `Abort` and `Throw`.
  *
  * The signature's control effects run on frames (`docs/effects.md` §3.4): an `else` catches the `abort` written inside
  * it and nothing else, and a `raise` nothing in the signature discharges is performed on the frame the guarded-return
  * read gives, which reads it as the rejection. So a satisfied guard compiles and runs as if the return were the bare
  * type, an `else` arm is the guard's fallback type, and an unsatisfied guard fails the build with its author's
  * message.
  */
class GuardSignatureIntegrationTest extends FullIntegrationTest {

  /** A rejecting guard fails the build at the use site with the message its author wrote. */
  private def rejectsWith(message: String)(errors: String): org.scalatest.Assertion = errors should include(message)

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

  "an unsatisfied inline `if..else..raise` guard" should "fail the build with its author's message" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def greeting[COND: Bool]: if(COND, String[]) else raise("greeting unavailable") = "hello"
        |
        |def main uses Console: Unit = printLine(greeting[false])""".stripMargin
    ).asserting(rejectsWith("greeting unavailable"))
  }

  "a bare `raise(msg)` guard" should "fail the build with its author's message" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def unavailable: raise("not available") = "x"
        |
        |def main uses Console: Unit = printLine(unavailable)""".stripMargin
    ).asserting(rejectsWith("not available"))
  }

  // An `else` arm is the guard's fallback *type*. The raw read of a signature dispatches no ability, and it once
  // answered the undispatched `abort` itself (`abort(Default)`), so every unsatisfied guard lost its fallback.
  "an unsatisfied `if..else` guard" should "type as its fallback" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[C: Bool]: if(C, String[]) else Bool[] = true
        |
        |def main uses Console: Unit = printLine(if(pick[false], "fallback") else "payload")""".stripMargin
    ).asserting(_ shouldBe "fallback")
  }

  "a satisfied `if..else` guard" should "type as its payload" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[C: Bool]: if(C, String[]) else Bool[] = "payload"
        |
        |def main uses Console: Unit = printLine(pick[true])""".stripMargin
    ).asserting(_ shouldBe "payload")
  }

  // A helper's own discharge catches only what is written inside it: `tryOr`'s `else` cannot see the `abort` its caller
  // wrote at `body`, a `uses *` slot that lists nothing, so the caller's `else` takes it. Before the compile track
  // threaded its frames this compiled with `pick[false]: Int` (`docs/effects.md` §8 item 26).
  "a helper's own `else` in a signature" should "leave the `abort` its caller wrote to the caller's `else`" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def tryOr[A](body uses *: A, fallback: A) uses *: A = body else fallback
        |
        |def pick[C: Bool]: tryOr(if(C, String[]), Int[]) else Bool[] = true
        |
        |def main uses Console: Unit = printLine(if(pick[false], "the caller's") else "the helper's")""".stripMargin
    ).asserting(_ shouldBe "the caller's")
  }

  "an unsatisfied `if` guard with no `else`" should "fail the build" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def pick[C: Bool]: if(C, String[]) = "payload"
        |
        |def main uses Console: Unit = printLine(pick[false])""".stripMargin
    ).asserting(_.trim should not be empty)
  }

  "a satisfied `if` guard with no `else`" should "type as its payload" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[C: Bool]: if(C, String[]) = "payload"
        |
        |def main uses Console: Unit = printLine(pick[true])""".stripMargin
    ).asserting(_ shouldBe "payload")
  }

  "a guard whose `abort` is reached through a callee's body" should "type as its fallback" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def pick[C: Bool]: orAbort(fold(C, Some(String[]), None)) else Bool[] = true
        |
        |def main uses Console: Unit = printLine(if(pick[false], "fallback") else "payload")""".stripMargin
    ).asserting(_ shouldBe "fallback")
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

  "an unsatisfied guard written through a user pipe" should "fail the build with its author's message" in {
    compileForErrors(pipedGuard + "\ndef main uses Console: Unit = printLine(greeting[false])")
      .asserting(rejectsWith("greeting unavailable"))
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

  "an unsatisfied `MIN > 0` inline guard" should "fail the build with its author's message" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.Abort
        |
        |def positive[MIN: BigInteger]: if(MIN > 0, String[]) else raise("must be positive") = "ok"
        |
        |def main uses Console: Unit = printLine(positive[0])""".stripMargin
    ).asserting(rejectsWith("must be positive"))
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
