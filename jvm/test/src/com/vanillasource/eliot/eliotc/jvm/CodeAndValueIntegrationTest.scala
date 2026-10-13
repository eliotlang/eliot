package com.vanillasource.eliot.eliotc.jvm

/** Code and values (`docs/effects.md` D21, rules 2 and 3, in today's spelling). A parameter whose type carries a row —
  * `body: {} A`, `action: A => {} Unit` — takes **code**: the caller's text, run by the callee, which sees the caller's
  * bindings and may be called or passed on but never kept. Every other position takes a **value**, and a value is
  * pure. The witnesses for §8 items 9, 10 and 11 are here, each now rejected.
  */
class CodeAndValueIntegrationTest extends FullIntegrationTest {

  // §8 item 9: a lambda at a rowless arrow reached the enclosing bindings and printed.
  "an effectful lambda at a function parameter with no row" should "be rejected at the effect" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def applyTwice(f: String => Unit): Unit = {
        |   f("a")
        |   f("b")
        |}
        |
        |def main uses Console: Unit = applyTwice(s -> printLine(s))
        |""".stripMargin
    ).asserting(
      _ should include(
        ":8:47:This uses the effect 'Console' inside a function written where a value is expected"
      )
    )
  }

  "an effectful lambda at a function parameter whose codomain carries a row" should "run in the caller's scope" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def applyTwice(f uses *: String => Unit): Unit = {
        |   f("a")
        |   f("b")
        |}
        |
        |def main uses Console: Unit = applyTwice(s -> printLine(s))
        |""".stripMargin
    ).asserting(_ shouldBe "a\nb")
  }

  "a pure lambda at a function parameter with no row" should "be accepted" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def twice(f: String => String, s: String): String = f(f(s))
        |
        |def main uses Console: Unit = printLine(twice(s -> s ++ "!", "hi"))
        |""".stripMargin
    ).asserting(_ shouldBe "hi!!")
  }

  // §8 item 10: a callback was stored, and its lambda ran after the call that bound it.
  "a callback stored in a field" should "be rejected as kept" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Holder(run: String => Unit)
        |
        |def capture(f uses *: String => Unit): Holder = Holder(f)
        |
        |def main uses Console: Unit = run(capture(s -> printLine(s)))("hi")
        |""".stripMargin
    ).asserting(_ should include(":5:56:'f' is code its caller wrote: it can be called or passed on, not kept."))
  }

  "a callback returned as a result" should "be rejected as kept" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def keep(f uses *: String => Unit): String => Unit = f
        |
        |def main uses Console: Unit = keep(s -> printLine(s))("hi")
        |""".stripMargin
    ).asserting(_ should include("'f' is code its caller wrote: it can be called or passed on, not kept."))
  }

  "a callback passed on to another code parameter" should "be accepted" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def each(action uses *: String => Unit): Unit = {
        |   action("a")
        |   action("b")
        |}
        |def relay(action uses *: String => Unit): Unit = each(action)
        |
        |def main uses Console: Unit = relay(s -> printLine(s))
        |""".stripMargin
    ).asserting(_ shouldBe "a\nb")
  }

  "a lazy argument captured by a function value" should "be rejected" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Holder(run: String => String)
        |
        |def defer(body uses *: String): Holder = Holder(s -> body)
        |
        |def main uses Console: Unit = printLine(run(defer("x"))("y"))
        |""".stripMargin
    ).asserting(_ should include(":5:54:'body' is code its caller wrote, and a function value cannot capture it."))
  }

  // §8 item 11: a lambda written in a value position captured the enclosing bindings.
  "an effectful lambda stored in a field" should "be rejected at the effect" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |data Holder(run: String => Unit)
        |
        |def built uses Console: Holder = Holder(s -> printLine(s))
        |
        |def main uses Console: Unit = run(built)("hi")
        |""".stripMargin
    ).asserting(_ should include(":5:46:This uses the effect 'Console' inside a function written where a value is expected"))
  }

  "an effectful lambda bound by a val" should "be rejected at the effect" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def main uses Console: Unit = {
        |   val shout = (s -> printLine(s))
        |   shout("hi")
        |}
        |""".stripMargin
    ).asserting(_ should include("This uses the effect 'Console' inside a function written where a value is expected"))
  }

  "a lambda in a value position discharging its own effect" should "be accepted" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |data Holder(run: String => String)
        |
        |def lookup(key: String) uses Abort: String = if(false, key)
        |
        |def main uses Console: Unit = printLine(run(Holder(s -> lookup(s) else "none"))("k"))
        |""".stripMargin
    ).asserting(_ shouldBe "none")
  }

  "an ability received by the enclosing definition" should "stay reachable from a function value" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |data Holder(run: String => String)
        |
        |def describe[T ~ Show](t: T): Holder = Holder(s -> s ++ show(t))
        |
        |def main uses Console: Unit = printLine(run(describe(42))("answer: "))
        |""".stripMargin
    ).asserting(_ shouldBe "answer: 42")
  }

  "a match whose arms perform effects" should "keep its arms in the caller's scope" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def report(o: Option[String]) uses Console: Unit = o match {
        |   case Some(s) -> printLine(s)
        |   case None    -> printLine("none")
        |}
        |
        |def main uses Console: Unit = {
        |   report(Some("one"))
        |   report(None)
        |}
        |""".stripMargin
    ).asserting(_ shouldBe "one\nnone")
  }

  "a lambda returned by a callback's code" should "be a value" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def produce(f uses *: String => (String => Unit)): String => Unit = f("x")
        |
        |def main uses Console: Unit = produce(s -> t -> printLine(t))("y")
        |""".stripMargin
    ).asserting(_ should include("This uses the effect 'Console' inside a function written where a value is expected"))
  }
}
