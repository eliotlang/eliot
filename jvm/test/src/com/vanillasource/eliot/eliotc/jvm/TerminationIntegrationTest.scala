package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of the termination preconditions and the no-recursion rule as they reach a real compile.
  *
  * M0 (preconditions): the strict-positivity check runs in `CoreProcessor` for every loaded file; the occurs-check half
  * is exercised at the unit level (`OccursCheckTest`). Here we confirm the declared negative-recursive-datatype route is
  * rejected through the full pipeline, while covariant self-reference (structural recursion in data) still compiles.
  *
  * M1 (the no-recursion rule): a self or mutual cycle in the body-level value-reference graph is rejected (the
  * `RecursionCheckProcessor`), while a non-recursive helper chain — however deep — compiles and runs, and the
  * monad-transformer lifting pattern (an impl method calling the same-named abstract method on an inner carrier) is not
  * mistaken for recursion.
  *
  * M2 (higher-order propagation, the function-coloring piece): because `Inf` is an ordinary effect and a suspended
  * slot supplies nothing, a single effect-transparent higher-order combinator is `Inf`-iff-its-step-is — terminating
  * over a terminating step, looping over an `Inf` step — with no separate termination lattice and no change to the
  * combinator. The step's own capability effects union with `Inf` at the caller that binds both, an `Inf` action
  * survives a round-trip through a data field, and the same subset check governs propagation through a higher-order
  * driver.
  */
class TerminationIntegrationTest extends FullIntegrationTest {

  "a data type referencing itself left of an arrow" should "be rejected as not strictly positive" in {
    compileForErrors(
      """import eliot.effect.Console
        |data Loop(f: Function[Loop, String])
        |
        |def main uses Console: Unit = printLine("unreachable")""".stripMargin
    ).asserting(_ should include("contravariant position"))
  }

  "a data type referencing itself covariantly (structural recursion)" should "compile and run" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Tree(left: Tree, right: Tree)
        |
        |def main uses Console: Unit = printLine("ok")""".stripMargin
    ).asserting(_ shouldBe "ok")
  }

  "a directly self-recursive value" should "be rejected as recursion" in {
    compileForErrors(
      """import eliot.effect.Console
        |def loop(x: String): String = loop(x)
        |
        |def main uses Console: Unit = printLine(loop("unreachable"))""".stripMargin
    ).asserting(_ should include("recursively"))
  }

  "a mutually-recursive pair of values" should "be rejected as recursion" in {
    compileForErrors(
      """import eliot.effect.Console
        |def ping(x: String): String = pong(x)
        |def pong(x: String): String = ping(x)
        |
        |def main uses Console: Unit = printLine(ping("unreachable"))""".stripMargin
    ).asserting(_ should include("recursively"))
  }

  // Signature split, Step 8: a recursive type *alias* is caught by the same no-recursion gate. A `Type`-qualified value's
  // runtime body *is* its alias RHS, so `type Foo = Foo` is a self-cycle in the body-reference graph exactly as a
  // recursive `def` is — a fail-safe property (a recursive alias cannot terminate the type-level reduction it drives).
  "a directly self-recursive type alias" should "be rejected as recursion" in {
    compileForErrors(
      """import eliot.effect.Console
        |type Foo = Foo
        |def useFoo(x: Foo): Foo = x
        |
        |def main uses Console: Unit = printLine("unreachable")""".stripMargin
    ).asserting(_ should include("recursively"))
  }

  "a mutually-recursive pair of type aliases" should "be rejected as recursion" in {
    compileForErrors(
      """import eliot.effect.Console
        |type A = B
        |type B = A
        |def useA(x: A): A = x
        |
        |def main uses Console: Unit = printLine("unreachable")""".stripMargin
    ).asserting(_ should include("recursively"))
  }

  // Signature split, Step 8: an implementation method calling the *same-named* abstract ability method is not
  // recursion. `{Abort}` + `else` forces the platform's `Abort` implementation, whose `abort` body reaches the
  // primitive through names that are a different FQN (`Qualifier.Ability`) from the implementation methods
  // (`Qualifier.AbilityImplementation`) containing them, so the no-recursion gate — running on both twins of each
  // such value — does not mistake the call for a cycle.
  "an implementation method calling its own ability's method" should "not be mistaken for recursion" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def lookup uses Abort: String = abort
        |
        |def main uses Console: Unit = printLine(lookup else "fallback")""".stripMargin
    ).asserting(_ shouldBe "fallback")
  }

  "a deep non-recursive helper chain" should "compile and run" in {
    compileAndRun(
      """import eliot.effect.Console
        |def a(x: String): String = b(x)
        |def b(x: String): String = c(x)
        |def c(x: String): String = x
        |
        |def main uses Console: Unit = printLine(a("ok"))""".stripMargin
    ).asserting(_ shouldBe "ok")
  }

  // --- M1 (the `Inf` effect): declare, propagate, run ---

  // Propagation: calling `forever` performs the `Inf` effect, so a carrier-polymorphic value that uses it must declare
  // `{Inf}`. A `{Console}`-only value that loops is rejected by the same subset check that governs every effect — `Inf`
  // rides the ordinary effect pipeline, it is not a special termination lattice.
  "a {Console} value that calls forever without declaring Inf" should "be rejected (Inf propagation)" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def bad uses Console: Unit = forever(printLine("x"))
        |
        |def main uses Console: Unit = bad""".stripMargin
    ).asserting(_ should include("performs the effect 'Inf'"))
  }

  // Run, don't discharge: an `{Inf}` program reaches `main` undischarged and is bound to the platform's `Inf`
  // implementation at the run boundary, whose `forever` loops the step's thunk endlessly — the loop never returns, so
  // the test bounds it and confirms the step ran many times (not just once).
  "a main built from forever over a terminating step" should "run the step endlessly" in {
    compileAndRunBounded(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def main uses Inf, Console: Unit = forever(printLine("tick"))""".stripMargin,
      timeoutMillis = 400
    ).asserting(_.linesIterator.count(_ == "tick") should be > 5)
  }

  // The same loop reached through an `{Inf, Console}` value: `main` receives both effects, binds each to the
  // platform's default implementation at the run boundary, and runs end-to-end. `Inf` is the one effect that may
  // legitimately reach `main` undischarged — it denotes a deliberately non-terminating program.
  "an {Inf, Console} super-loop reached from main" should "run endlessly" in {
    compileAndRunBounded(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def serve uses Inf, Console: Unit = forever(printLine("serving"))
        |
        |def main uses Inf, Console: Unit = serve""".stripMargin,
      timeoutMillis = 400
    ).asserting(_.linesIterator.count(_ == "serving") should be > 5)
  }

  // --- M2 (higher-order propagation: the function-coloring piece) ---

  // Function-coloring, terminating side. The effect-transparent combinator `runStep` declares no effect of its own; its
  // result effect is exactly its step's. Over a terminating step it terminates with finite output. The combinator's
  // definition is byte-for-byte the one used in the `Inf` case below — only the supplied step differs.
  "a higher-order combinator over a terminating step" should "itself terminate" in {
    compileAndRun(
      """import eliot.effect.Console
        |def runStep(step uses *: Unit): Unit = step
        |
        |def main uses Console: Unit = runStep(printLine("done"))""".stripMargin
    ).asserting(_ shouldBe "done")
  }

  // Function-coloring, Inf side. The *same* `runStep` definition over an `Inf` step loops endlessly: the step's `Inf`
  // reaches the result through the shared carrier with no change to the combinator — one combinator serves both colours
  // (Nystrom's function-coloring win), because `Inf` is a carrier effect, not a separate lattice slot.
  "the same higher-order combinator over an Inf step" should "loop endlessly" in {
    compileAndRunBounded(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def runStep(step uses *: Unit): Unit = step
        |
        |def main uses Inf, Console: Unit = runStep(forever(printLine("loop")))""".stripMargin,
      timeoutMillis = 400
    ).asserting(_.linesIterator.count(_ == "loop") should be > 5)
  }

  // The step's own effect is bound where the step is written: a `{Console}` step handed to an `{Inf}` driver through a
  // `{}` slot is charged to `main`, which writes it, and the driver declares only the `Inf` it performs itself — a
  // driver declaring the step's `Console` too would declare an effect it does not perform, which is rejected.
  "an {Inf} driver over a {Console} step" should "charge the step's effect where it is written and loop endlessly" in {
    compileAndRunBounded(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def driver(step uses *: Unit) uses Inf: Unit = forever(step)
        |
        |def main uses Inf, Console: Unit = driver(printLine("tick"))""".stripMargin,
      timeoutMillis = 400
    ).asserting(_.linesIterator.count(_ == "tick") should be > 5)
  }

  // Propagation is the same used-subset-of-declared check through a higher-order driver: the driver above declaring only
  // `{Console}` (omitting `Inf`) is rejected — calling `forever` performs `Inf`, which must be declared.
  "a driver that calls forever while declaring only {Console}" should "be rejected (Inf not declared)" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Inf
        |
        |def driver(step uses *: Unit) uses Console: Unit = forever(step)
        |
        |def main uses Console: Unit = driver(printLine("tick"))""".stripMargin
    ).asserting(_ should include("performs the effect 'Inf'"))
  }
}
