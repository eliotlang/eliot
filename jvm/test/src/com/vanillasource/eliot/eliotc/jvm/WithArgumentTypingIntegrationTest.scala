package com.vanillasource.eliot.eliotc.jvm

/** A `with`'s arguments are typed from the entries it binds, wherever those entries' arguments are spelled
  * (`docs/effects.md` §8 item 31): the implementation's pattern is matched against them, which decides its own type
  * parameters before the checker types the arguments.
  */
class WithArgumentTypingIntegrationTest extends FullIntegrationTest {

  private val source =
    """import eliot.effect.Console
      |
      |effect Source[T] {
      |   def describe(t: T): String
      |}
      |
      |implement[T] mapping(f: T => String): Source[T] {
      |   def describe(t: T): String = f(t)
      |}
      |
      |implement[T] defaulting(fallback: Option[T]): Source[Option[T]] {
      |   def describe(t: Option[T]): String = "defaulted"
      |}
      |
      |def pick[A](a: A) uses Source[A]: String = describe(a)
      |def pickInt(a: Int) uses Source[Int]: String = describe(a)
      |def pickString(a: String) uses Source[String]: String = describe(a)
      |""".stripMargin

  "a `with`'s arguments" should "be typed by an entry its subject's declaration spells" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(pickInt(42) with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "be typed by an entry the call spells" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(pick[Int](7) with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  it should "be typed by an entry spelled in the definition's own type parameters" in {
    compileAndRun(
      s"""$source
         |def shownAs[A ~ Show[A]](a: A): String = pick[A](a) with mapping(x -> show(x))
         |def main uses Console: Unit = printLine(shownAs(5))""".stripMargin
    ).asserting(_ shouldBe "5")
  }

  it should "be typed through the implementation's pattern" in {
    compileAndRun(
      s"""$source
         |def pickOption(a: Option[Int]) uses Source[Option[Int]]: String = describe(a)
         |def main uses Console: Unit = printLine(pickOption(Some(1)) with defaulting(None))""".stripMargin
    ).asserting(_ shouldBe "defaulted")
  }

  it should "be typed by the entry of a code parameter it binds" in {
    compileAndRun(
      s"""$source
         |def shown(body uses *, Source[Int]: String) uses *: String = body with mapping(x -> show(x))
         |def main uses Console: Unit = printLine(shown(pickInt(3)))""".stripMargin
    ).asserting(_ shouldBe "3")
  }

  it should "still be typed from themselves where the entry is not spelled" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine(pick(1) with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ should include("this `with` supplies (Type -> String)"))
  }

  it should "still be typed from themselves where the entries disagree" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine({ pickInt(1) ++ pickString("s") } with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ should include("this `with` supplies (Type -> String)"))
  }
}
