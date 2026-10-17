package com.vanillasource.eliot.eliotc.jvm

/** A named implementation's own type parameters are solved where it is bound, from the entry it is bound for: its
  * pattern is matched against the entry's arguments, as the two-site search matches an anonymous implementation's
  * (`docs/effects.md` §8 item 30).
  */
class NamedImplementationTypeParametersIntegrationTest extends FullIntegrationTest {

  private val source =
    """import eliot.effect.Console
      |
      |effect Source[T] {
      |   def describe(t: T): String
      |}
      |
      |implement[T ~ Show[T]] showing: Source[T] {
      |   def describe(t: T): String = show(t)
      |}
      |
      |implement[T ~ Show[T]] constant(value: T): Source[T] {
      |   def describe(t: T): String = show(t) ++ "/" ++ show(value)
      |}
      |
      |implement[T] none: Source[Option[T]] {
      |   def describe(t: Option[T]): String = "none"
      |}
      |
      |def pick[A](a: A) uses Source[A]: String = describe(a)
      |""".stripMargin

  "a named implementation's type parameters" should "be what its pattern takes at the entry it is bound for" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(pick(42) with showing)""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "be solved at each entry a `with` binds" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine({ pick(1) ++ pick("s") } with showing)""".stripMargin
    ).asserting(_ shouldBe "1s")
  }

  it should "be solved inside a pattern" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = printLine(pick(Some("x")) with none)""".stripMargin
    ).asserting(_ shouldBe "none")
  }

  it should "type the parameters a `with` supplies" in {
    compileAndRun(
      s"""$source
         |def main uses Console: Unit = {
         |   printLine(pick(1) with constant(2))
         |   printLine(pick("a") with constant("b"))
         |}""".stripMargin
    ).asserting(_ shouldBe "1/2\na/b")
  }

  it should "reach an effect the implementation's clause performs" in {
    compileAndRun(
      s"""$source
         |implement[T ~ Show[T]] logged(lead: String): Source[T] {
         |   def describe(t: T) uses Writer[String]: String = {
         |      tell(lead ++ show(t))
         |      show(t)
         |   }
         |}
         |
         |def main uses Console: Unit = printLine(runWriterToLog(printLine(pick(7) with logged("> "))))""".stripMargin
    ).asserting(_ shouldBe "7\n> 7")
  }

  it should "be solved around a run, whose implementation's constraint is an ability and needs nothing given" in {
    compileAndRun(
      s"""$source
         |def shown[A](body uses *, Source[A]: String) uses *: String = body with showing
         |def main uses Console: Unit = printLine(shown[Int](pick(1)) ++ " " ++ shown[String](pick("a")))""".stripMargin
    ).asserting(_ shouldBe "1 a")
  }

  it should "type the parameters a `with` supplies around a run of a code parameter" in {
    compileAndRun(
      s"""$source
         |def against[A](other: A, body uses *, Source[A]: String) uses *: String = body with constant(other)
         |def main uses Console: Unit = printLine(against(2, pick(1)) ++ " " ++ against("b", pick("a")))""".stripMargin
    ).asserting(_ shouldBe "1/2 a/b")
  }

  "a named implementation bound for an entry its pattern does not match" should "be an error at the use" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine(pick(1) with none)""".stripMargin
    ).asserting(_ should include("This performs 'Source[Int]' on the implementation 'none', which does not implement it."))
  }

  "a `with` supplying arguments its implementation does not take at the entry" should "be an error at the `with`" in {
    compileForErrors(
      s"""$source
         |def main uses Console: Unit = printLine({ pick(2) ++ pick("a") } with constant(1))""".stripMargin
    ).asserting(
      _ should include("The implementation 'constant' takes (String) where it runs 'Source[String]', but this `with` supplies (Int).")
    )
  }
}
