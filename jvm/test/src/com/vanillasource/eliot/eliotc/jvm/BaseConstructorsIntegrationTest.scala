package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of the base layer's **abstract value constructors** — `eliot.lang.Option`'s `some`/`none` and
  * `eliot.lang.Pair`'s `pair`.
  *
  * A `def` name may not be upper-case, so a platform's generated `Some`/`None`/`Pair` constructors are not names the
  * abstract base can declare: without a lower-case abstract declaration bound to them by the platform layer, base-layer
  * code can take an `Option` or a `Pair` apart (`foldOption`, `foldPair` are declared there) but can never build one.
  * That is what these bind, and it is what lets `List.find` and `List.groupBy` live in the base layer at all.
  *
  * `some`/`none` were *declared* in the base long before they were implemented, so any use of them was a hard "Function
  * not implemented" — which is the regression this pins.
  */
class BaseConstructorsIntegrationTest extends FullIntegrationTest {

  "some and none" should "build an Option from base-layer code" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |
        |def label(o: Option[String]): String = o.foldOption("absent", s -> s)
        |
        |def main uses Console: Unit = printLine(label(some("here")) ++ ":" ++ label(none))""".stripMargin
    ).asserting(_ shouldBe "here:absent")
  }

  "pair" should "build a Pair from base-layer code" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |
        |def main uses Console: Unit = printLine(pair("left", "right").foldPair(a -> b -> a ++ "|" ++ b))""".stripMargin
    ).asserting(_ shouldBe "left|right")
  }
}
