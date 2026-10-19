package com.vanillasource.eliot.eliotc.jvm

/** A `match` pairs each arm with its constructor by the constructor's position in its `data` declaration: the desugarer
  * passes `handleCases` one handler per constructor in that order, and each constructor class selects the handler at
  * its own index. Both sides must read the same order. The backend once re-sorted constructors by their monomorphic
  * name's position, which is sourced at the *signature* — for a nullary constructor that is the data type's name,
  * ahead of every other constructor — so a nullary constructor declared after another one took the first handler.
  */
class ConstructorOrderIntegrationTest extends FullIntegrationTest {

  "a match over a data whose nullary constructor is declared last" should "dispatch each constructor to its own arm" in {
    compileAndRun(
      """data Shape = Circle(radius: String) | Dot
        |
        |def describe(s: Shape): String = s match {
        |   case Circle(r) -> r
        |   case Dot -> "dot"
        |}
        |
        |def main uses Console: Unit = {
        |   printLine(describe(Dot))
        |   printLine(describe(Circle("circle")))
        |}
        |""".stripMargin
    ).asserting(_ shouldBe "dot\ncircle")
  }

  "a match over a data whose nullary constructor is declared in the middle" should "dispatch each constructor to its own arm" in {
    compileAndRun(
      """data Shape = Circle(radius: String) | Dot | Square(side: String)
        |
        |def describe(s: Shape): String = s match {
        |   case Circle(r) -> r
        |   case Dot -> "dot"
        |   case Square(s) -> s
        |}
        |
        |def main uses Console: Unit = {
        |   printLine(describe(Circle("circle")))
        |   printLine(describe(Dot))
        |   printLine(describe(Square("square")))
        |}
        |""".stripMargin
    ).asserting(_ shouldBe "circle\ndot\nsquare")
  }
}
