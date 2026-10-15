package com.vanillasource.eliot.eliotc.jvm

/** A named implementation of an **ability** takes parameters as one of an effect does, supplied by the `with` that binds
  * it (`docs/effects.md` §11, "Decided, not built", now built): `people.sorted[Person] with byKey(p -> p.age)`. Its
  * parameters are its environment, threaded after monomorphization to every operation dispatched to it, including one
  * reached through another implementation's `~` constraint.
  */
class ParameterisedAbilityImplementationIntegrationTest extends FullIntegrationTest {

  private val people =
    """import eliot.collection.List
      |
      |data Person(name: String, age: Int)
      |
      |implement[T] byKey(key: T => Int): Compare[T] {
      |   def lessThanOrEqual(a: T, b: T): Bool = key(a) <= key(b)
      |}
      |
      |def names(people: List[Person]): String = people.map(p -> p.name).joined(",")
      |
      |def everyone: List[Person] = append(append(append(empty, Person("a", 30)), Person("b", 20)), Person("c", 25))
      |""".stripMargin

  private val lists =
    """import eliot.collection.List
      |
      |implement[T ~ Compare[T]] byLast: Compare[List[T]] {
      |   def lessThanOrEqual(a: List[T], b: List[T]): Bool = a.last.foldOption(true, x -> b.last.foldOption(false, y -> x <= y))
      |}
      |
      |implement reversed: Compare[Int] {
      |   def lessThanOrEqual(a: Int, b: Int): Bool = b <= a
      |}
      |
      |implement natural: Compare[Int] {
      |   def lessThanOrEqual(a: Int, b: Int): Bool = a <= b
      |}
      |
      |implement[T] byKey(key: T => Int): Compare[T] {
      |   def lessThanOrEqual(a: T, b: T): Bool = key(a) <= key(b)
      |}
      |
      |def render(xs: List[List[Int]]): String = xs.map(l -> l.joined(".")).joined(",")
      |
      |def singletons: List[List[Int]] = append(append(append(empty, append(empty, 3)), append(empty, 1)), append(empty, 2))
      |""".stripMargin

  "a named ability implementation with parameters" should "run on the arguments its `with` supplies" in {
    compileAndRun(
      s"""$people
         |def main uses Console: Unit = printLine(names(everyone.sorted[Person] with byKey(p -> p.age)))""".stripMargin
    ).asserting(_ shouldBe "b,c,a")
  }

  it should "run on each `with`'s own arguments in one program" in {
    compileAndRun(
      s"""$people
         |def main uses Console: Unit = {
         |   val offset = 100
         |   printLine(names(everyone.sorted[Person] with byKey(p -> p.age)))
         |   printLine(names(everyone.sorted[Person] with byKey(p -> offset - p.age)))
         |}""".stripMargin
    ).asserting(_ shouldBe "b,c,a\na,c,b")
  }

  it should "reach an operation through a definition passing its constraint on" in {
    compileAndRun(
      s"""$people
         |def youngest[A ~ Compare](list: List[A]): Option[A] = list.sorted.head
         |def main uses Console: Unit =
         |   printLine(youngest[Person](everyone).foldOption("none", p -> p.name) with byKey(p -> p.age))""".stripMargin
    ).asserting(_ shouldBe "b")
  }

  it should "be kept by a function value closing over its constraint" in {
    compileAndRun(
      s"""$people
         |def comparator[A ~ Compare]: A => A => Bool = a -> b -> a <= b
         |def main uses Console: Unit = {
         |   val older = comparator[Person] with byKey(p -> 0 - p.age)
         |   printLine(fold(older(Person("x", 1))(Person("y", 2)), "le", "gt"))
         |}""".stripMargin
    ).asserting(_ shouldBe "gt")
  }

  it should "reach the constraint of another implementation bound inside its `with`" in {
    compileAndRun(
      s"""$lists
         |def main uses Console: Unit = {
         |   printLine(render(singletons.sorted with byLast) with byKey((x: Int) -> 0 - x))
         |   printLine(render(singletons.sorted with byLast) with byKey((x: Int) -> x))
         |}""".stripMargin
    ).asserting(_ shouldBe "3,2,1\n1,2,3")
  }

  it should "be rejected where a nearer binding of its ability stands between the `with` and a use it reaches" in {
    compileForErrors(
      """import eliot.collection.List
        |
        |implement prefixed(lead: String): Show[Int] {
        |   def show(i: Int): String = lead ++ show(i)
        |}
        |
        |implement[T ~ Show[T]] byShown: Compare[T] {
        |   def lessThanOrEqual(a: T, b: T): Bool = length(show(a)) <= length(show(b))
        |}
        |
        |def main uses Console: Unit = {
        |   val xs = append(append(append(empty, 3), 10), 2)
        |   printLine((((xs.sorted.joined(",")) with prefixed("<")) with byShown) with prefixed(">>"))
        |}""".stripMargin
    ).asserting(
      _ should include(
        "The implementation of 'Compare' bound by the `with` around this uses 'Show', and a nearer binding of 'Show' " +
          "stands between them."
      )
    )
  }

  "a named implementation constrained on its own ability" should "take its constraint from outside its `with`" in {
    compileAndRun(
      s"""$lists
         |def main uses Console: Unit = {
         |   printLine(render(singletons.sorted with byLast))
         |   printLine(render(singletons.sorted with byLast) with reversed)
         |}""".stripMargin
    ).asserting(_ shouldBe "1,2,3\n3,2,1")
  }

  "two named implementations of one ability at one type" should "each be specialised in one program" in {
    compileAndRun(
      s"""$lists
         |def main uses Console: Unit = {
         |   val xs = append(append(append(empty, 3), 1), 2)
         |   printLine(xs.sorted.joined(",") with reversed)
         |   printLine(xs.sorted.joined(",") with natural)
         |   printLine(render(singletons.sorted with byLast) with reversed)
         |   printLine(render(singletons.sorted with byLast) with natural)
         |}""".stripMargin
    ).asserting(_ shouldBe "3,2,1\n1,2,3\n3,2,1\n1,2,3")
  }

  "two named implementations of one effect" should "each be specialised in one program" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |implement loud: Console {
        |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ "!")
        |   def readLine: Option[String] = None
        |}
        |
        |implement quiet: Console {
        |   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ".")
        |   def readLine: Option[String] = None
        |}
        |
        |def greet uses Console: Unit = printLine("hi")
        |
        |def main uses Console: Unit = printLine(runWriterToLog(greet with loud) ++ runWriterToLog(greet with quiet))""".stripMargin
    ).asserting(_ shouldBe "hi!hi.")
  }
}
