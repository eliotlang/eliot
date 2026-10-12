package com.vanillasource.eliot.eliotc.jvm

/** A block is used, never kept — D20 rules 3 and 4 (`docs/effects.md` §11), closing §8 items 10 and 11.
  *
  * A function parameter of a definition is a **block**: code its caller wrote, which sees the caller's effects. The
  * callee may call it or pass it on to another definition's function parameter, and nothing else, because anything
  * else keeps it — and a kept block runs after the frames its effects were bound under are gone. A lambda written in a
  * **value** position (a field, a generic slot, a result, a `val`) is kept by construction, so it may use no effect
  * from around it; written directly at a definition's function parameter it is a block and may.
  */
class BlockKeepingIntegrationTest extends FullIntegrationTest {

  private val prelude =
    """import eliot.effect.Console
      |
      |data Holder(act: String => Unit)
      |
      |def fire(h: Holder): Unit = act(h)("fired")
      |
      |def callIt(f: String => Unit): Unit = f("called")
      |
      |""".stripMargin

  private val kept = "it can be called or passed on, not kept"

  "a function parameter stored in a data field" should "be rejected" in {
    compileForErrors(prelude + """
      |def capture(f: String => Unit): Holder = Holder(f)
      |def main: {Console} Unit = fire(capture(s -> printLine(s)))""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter returned as the result" should "be rejected" in {
    compileForErrors(prelude + """
      |def same(f: String => Unit): String => Unit = f
      |def main: {Console} Unit = same(s -> printLine(s))("x")""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter passed to a generic value slot" should "be rejected" in {
    compileForErrors(prelude + """
      |def ident[A](a: A): A = a
      |def same(f: String => Unit): String => Unit = ident(f)
      |def main: {Console} Unit = same(s -> printLine(s))("x")""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter bound to a val" should "be rejected" in {
    compileForErrors(prelude + """
      |def twice(f: String => Unit): Unit = {
      |   val g = f
      |   g("once")
      |}
      |def main: {Console} Unit = twice(s -> printLine(s))""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter passed at a suspended slot" should "be rejected, since the slot hands it back as a value" in {
    compileForErrors(prelude + """
      |def lazily[A](a: {} A): A = a
      |def same(f: String => Unit): String => Unit = lazily(f)
      |def main: {Console} Unit = same(s -> printLine(s))("x")""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter captured by a lambda in a value position" should "be rejected" in {
    compileForErrors(prelude + """
      |def capture(f: String => Unit): Holder = Holder(s -> f(s))
      |def main: {Console} Unit = fire(capture(s -> printLine(s)))""".stripMargin)
      .asserting(_ should include(kept))
  }

  // Functions are curried, so the lambda `compose` returns is its own last parameter: called with all three arguments
  // it keeps nothing. What keeps a block is a call applied to fewer, which is a function still holding its arguments.
  private val compose = """
      |def compose[A, B, C](f: B => C, g: A => B): A => C = a -> f(g(a))
      |""".stripMargin

  "a definition returning a lambda over its function parameters" should "run when applied in full" in {
    compileAndRun(prelude + compose + """
      |def main: {Console} Unit = compose(s -> printLine(s), s -> s ++ "!")("x")""".stripMargin)
      .asserting(_ shouldBe "x!")
  }

  it should "not be partially applied to a block and kept" in {
    compileForErrors(prelude + compose + """
      |def loud(f: String => Unit): String => Unit = compose(f, s -> s ++ "!")
      |def main: {Console} Unit = loud(s -> printLine(s))("x")""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a function parameter captured by a partial application in a value position" should "be rejected" in {
    compileForErrors(prelude + """
      |def both(f: String => Unit, s: String): Unit = f(s)
      |def capture(f: String => Unit): Holder = Holder(both(f))
      |def main: {Console} Unit = fire(capture(s -> printLine(s)))""".stripMargin)
      .asserting(_ should include(kept))
  }

  "a suspended parameter captured by a lambda in a value position" should "be rejected" in {
    compileForErrors(prelude + """
      |def capture(body: {} Unit): Holder = Holder(s -> body)
      |def main: {Console} Unit = fire(capture(printLine("late")))""".stripMargin)
      .asserting(_ should include("it can be run or passed on, not kept"))
  }

  "a function parameter" should "be callable" in {
    compileAndRun(prelude + """
      |def main: {Console} Unit = callIt(s -> printLine(s))""".stripMargin)
      .asserting(_ shouldBe "called")
  }

  it should "be passable on to another definition's function parameter" in {
    compileAndRun(prelude + """
      |def passOn(f: String => Unit): Unit = callIt(f)
      |def main: {Console} Unit = passOn(s -> printLine(s))""".stripMargin)
      .asserting(_ shouldBe "called")
  }

  it should "be callable through the dot" in {
    compileAndRun(prelude + """
      |def viaDot(f: String => Unit): Unit = "dotted".f
      |def main: {Console} Unit = viaDot(s -> printLine(s))""".stripMargin)
      .asserting(_ shouldBe "dotted")
  }

  it should "be callable from a lambda passed on as a block" in {
    compileAndRun(prelude + """
      |def wrapped(f: String => Unit): Unit = callIt(s -> f(s ++ "!"))
      |def main: {Console} Unit = wrapped(s -> printLine(s))""".stripMargin)
      .asserting(_ shouldBe "called!")
  }

  private val usesAround = "cannot use the effect 'Console' from around it"

  "a lambda in a value position" should "not use an effect from around it" in {
    compileForErrors(prelude + """
      |def built: {Console} Holder = Holder(s -> printLine(s))
      |def main: {Console} Unit = fire(built)""".stripMargin)
      .asserting(_ should include(usesAround))
  }

  it should "not use an effect from around it when bound to a val" in {
    compileForErrors(prelude + """
      |def main: {Console} Unit = {
      |   val g = (s -> printLine(s))
      |   callIt(g)
      |}""".stripMargin)
      .asserting(_ should include(usesAround))
  }

  it should "be free to bind and discharge an effect inside itself" in {
    compileAndRun(prelude + """
      |data Render(render: String => String)
      |def logged: Render = Render(s -> runWriterToLog(tell(s ++ ";")))
      |def main: {Console} Unit = printLine(render(logged)("kept"))""".stripMargin)
      .asserting(_ shouldBe "kept;")
  }

  "a lambda passed directly to a definition's function parameter" should "use the effects around it" in {
    compileAndRun(prelude + """
      |def main: {Console} Unit = callIt(s -> printLine(s ++ " here"))""".stripMargin)
      .asserting(_ shouldBe "called here")
  }
}
