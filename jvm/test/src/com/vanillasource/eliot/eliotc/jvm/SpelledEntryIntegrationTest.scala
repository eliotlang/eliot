package com.vanillasource.eliot.eliotc.jvm

/** Whether a slot's entry rides the definition's own row or is supplied by the slot, and whether an entry answers
  * another, is decided as the declarations spell them, never at the arguments a call or an instantiation determines
  * (`docs/effects.md` D30). So a slot means the same thing at every instantiation.
  */
class SpelledEntryIntegrationTest extends FullIntegrationTest {

  private val source =
    """import eliot.effect.Console
      |
      |effect Source[T] {
      |   def describe(t: T): String
      |}
      |
      |implement[T] tagged(tag: String): Source[T] {
      |   def describe(t: T): String = tag
      |}
      |
      |def pick[A](a: A) uses Source[A]: String = describe(a)
      |
      |def handle(body uses *, Source[Int]: String) uses *: String = body with tagged("inner")
      |""".stripMargin

  // §8 item 43: at `E := Int` the slot's `Source[E]` rode the definition's `Source[Int]` and the caller's `with` ran,
  // while at any other `E` it was supplied and the callee's `inner` ran. Spelled, `Source[E]` is not `Source[Int]`, so
  // it is supplied at every `E`, and handing it to a slot supplying `Source[Int]` gives it nothing.
  "a slot entry spelled apart from the definition's own" should "be supplied at every instantiation" in {
    compileForErrors(
      s"""$source
         |def handled[E](body uses *, Source[E]: String) uses *, Source[Int]: String = handle(body) ++ "|" ++ pick(1)
         |
         |def main uses Console: Unit = printLine(handled[Int](pick(5)) with tagged("outer"))""".stripMargin
    ).asserting(
      _ should include("'body' may perform the effect 'Source' here, which this definition neither handles nor declares.")
    )
  }

  // §2.2's own example: `Throw[E]` is supplied even where `E` is the declared `Throw[String]`'s argument, so the
  // definition's `catch` gets the raise and the caller's discharger never sees it.
  "a slot entry generic in a binder" should "be supplied where the binder is the declared entry's argument" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def failWith(message: String) uses Throw[String]: String = raise(message)
        |
        |def swallowing[E](body uses *, Throw[E]: Unit) uses *, Throw[String]: String = {
        |   catch[E, Unit](body, e -> unit)
        |   failWith("not swallowed")
        |}
        |
        |def main uses Console: Unit =
        |   printLine(foldEither(e -> "left " ++ e, a -> "right " ++ a, runThrow[String, String](swallowing[String](raise("x")))))
        |""".stripMargin
    ).asserting(_ shouldBe "left not swallowed")
  }

  // §8 item 46: a closed slot kept every outer binding of the ability it lists, so `Throw[Int]` reached the caller's
  // binding through a slot listing only `Throw[String]`.
  "a closed slot" should "keep only the outer bindings of the entries it rides, spelled the same" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def failing uses Throw[Int]: Unit = raise(0)
        |
        |def guarded(body uses Throw[String]: Unit) uses Throw[Int]: Unit = {
        |   runThrow[String, Unit](body)
        |   failing
        |}
        |
        |def main uses Console: Unit = printLine(foldEither(e -> "left", a -> "right", runThrow[Int, Unit](guarded(failing))))
        |""".stripMargin
    ).asserting(_ should include("This uses the effect 'Throw' inside an argument whose `uses` clause is closed"))
  }

  // §8 item 47: the over-declaration check was per ability, so a second entry of one ability passed for free.
  "a declared entry" should "be consumed by an entry spelled the same, not by another of its ability" in {
    compileForErrors(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def failing uses Throw[Int]: Unit = raise(1)
        |
        |def both uses Throw[Int], Throw[String]: Unit = failing
        |
        |def main uses Console: Unit = printLine(foldEither(e -> "left", a -> "right", runThrow[Int, Unit](runThrow[String, Unit](both).foldEither(e -> unit, a -> a))))
        |""".stripMargin
    ).asserting(_ should include("This value declares the effect 'Throw' but does not perform it."))
  }
}
