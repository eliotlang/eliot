package com.vanillasource.eliot.eliotc.jvm

/** A data constructor used as a function value — bare, or handed to a combinator — must run, not merely compile. */
class ConstructorAsFunctionIntegrationTest extends FullIntegrationTest {

  "a unary constructor reference" should "be usable as a function value" in {
    compileAndRun(
      """data Box(value: String)
        |
        |def make: String => Box = Box
        |
        |def main uses Console: Unit = printLine(make("hi").value)""".stripMargin
    ).asserting(_ shouldBe "hi")
  }

  "a binary constructor reference" should "be a curried function value" in {
    compileAndRun(
      """data Pair2(a: String, b: String)
        |
        |def make: String => String => Pair2 = Pair2
        |
        |def main uses Console: Unit = printLine(make("x")("y").b)""".stripMargin
    ).asserting(_ shouldBe "y")
  }

  "a partially applied constructor" should "be usable as a function value" in {
    compileAndRun(
      """data Pair2(a: String, b: String)
        |
        |def make: String => Pair2 = Pair2("x")
        |
        |def main uses Console: Unit = printLine(make("y").b)""".stripMargin
    ).asserting(_ shouldBe "y")
  }

  "a constructor handed to map" should "link to an emitted method" in {
    compileAndRun(
      """import eliot.collection.List
        |
        |data Box(value: String)
        |
        |def main uses Console: Unit = printLine(singleton("a").map(Box).map(b -> b.value).joined(","))""".stripMargin
    ).asserting(_ shouldBe "a")
  }
}
