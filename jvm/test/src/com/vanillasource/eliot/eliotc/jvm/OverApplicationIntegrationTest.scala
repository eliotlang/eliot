package com.vanillasource.eliot.eliotc.jvm

/** End-to-end regression for over-application: an application spine longer than the callee's natural arity (the
  * leading-lambda count of its body), i.e. applying the function *value* a direct call returns — `unwrap(w)("x")` on a
  * 1-parameter accessor, or calling a def whose body merely computes a function. The spine used to be flattened into a
  * single direct call at full spine length everywhere (`used` statistics, uncurrying, codegen), which failed with
  * "Could not strip enough parameters from expression."; now the direct call is capped at the natural arity and the
  * excess arguments are applied to the returned function value one `apply` at a time. These programs must not only
  * compile but run.
  */
class OverApplicationIntegrationTest extends FullIntegrationTest {

  "an accessor result" should "be applicable by juxtaposition" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |data Wrapper(unwrap: Function[String, String])
        |
        |def use(w: Wrapper): String = unwrap(w)("x")
        |
        |def main uses Console: Unit = printLine(use(Wrapper(n -> "hi")))""".stripMargin
    ).asserting(_ shouldBe "hi")
  }

  "a def whose body computes a function" should "be applicable directly" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def compose[A, B, C](f: B => C, g: A => B): A => C = a -> f(g(a))
        |
        |def exclaim(s: String): String = "!"
        |
        |def shout: String => String = compose(exclaim, exclaim)
        |
        |def main uses Console: Unit = printLine(shout("hello"))""".stripMargin
    ).asserting(_ shouldBe "!")
  }

  "a curried field" should "absorb several over-applied arguments in one spine" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |data Curried(pick: String => String => String)
        |
        |def use(c: Curried): String = pick(c)("first")("second")
        |
        |def main uses Console: Unit = printLine(use(Curried(a -> b -> b)))""".stripMargin
    ).asserting(_ shouldBe "second")
  }
}
