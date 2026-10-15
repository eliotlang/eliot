package com.vanillasource.eliot.eliotc.jvm

/** A `with`'s arguments are typed from the entries it binds (`docs/effects.md` §8 items 31 and 36): the
  * implementation's pattern is matched against them, which decides its own type parameters — before the checker where
  * the entries are spelled, and by the checker, through one keyed hole, where only it reads them off the call.
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

  it should "be typed by an entry only the checker determines" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(pick(1) with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ shouldBe "1")
  }

  it should "be typed by an entry only the checker determines, through the implementation's pattern" in {
    compileAndRun(
      s"""$source
         |def pickAny[A](a: Option[A]) uses Source[Option[A]]: String = describe(a)
         |def main uses Console: Unit = printLine(pickAny(Some(1)) with defaulting(None))""".stripMargin
    ).asserting(_ shouldBe "defaulted")
  }

  it should "be typed by an entry only the checker determines, in a nested call" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(("a" ++ pick(2)) with mapping(x -> show(x + 1)))""".stripMargin
    ).asserting(_ shouldBe "a3")
  }

  it should "be refused where they do not fit the entry only the checker determines" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine(pick("s") with mapping(x -> show(x + 1)))""".stripMargin
    ).asserting(_ should include("Type mismatch."))
  }

  it should "still be typed from themselves where the entries disagree" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine({ pickInt(1) ++ pickString("s") } with mapping(x -> show(x)))""".stripMargin
    ).asserting(_ should include("this `with` supplies (Type -> String)"))
  }

  "an implementation with a type parameter its pattern does not take" should "be refused at its declaration" in {
    compileForErrors(
      s"""$source
         |implement[T, K ~ Show[K]] keyed(f: T => K): Source[T] {
         |   def describe(t: T): String = show(f(t))
         |}
         |
         |def main uses Console: Unit = printLine(pickInt(3) with keyed(x -> x + 1))""".stripMargin
    ).asserting(_ should include("The type parameter 'K' of the implementation 'keyed' is not taken by its pattern"))
  }
}
