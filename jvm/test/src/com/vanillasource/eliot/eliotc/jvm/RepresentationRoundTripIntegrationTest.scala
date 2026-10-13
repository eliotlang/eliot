package com.vanillasource.eliot.eliotc.jvm

/** The backend's **round-trip peephole** (`docs/total-meta-transfers.md` §P4): a representation conversion whose only
  * consumer immediately undoes it is not emitted.
  *
  * The channel stamps a width on a node; the backend used to treat that as an obligation to *materialise* it, and then
  * to convert straight back wherever the consumer wanted something else. Both halves computed the same number. That was
  * invisible while a call boundary was ⊤ — nothing narrowed — and became common the moment the meta interpretation
  * (§P4's walk) started deriving a bound for every ordinary call: `Ranges`'s `count() + count() + count()` compiled to
  * three `BigInteger` ⤳ `Byte` ⤳ `BigInteger` trips around a sum it then computed at the wide width anyway.
  *
  * The rule that replaces it is one sentence: **a value is emitted at the width its consumer will read, and a consumer
  * that adapts to any width reads it as it arrives.** The cases below are the three edges that has to hold at — a
  * boundary result, a constant, an intrinsic's operand — and each asserts a *whole* method's instructions rather than a
  * window, because a conversion the peephole merely moved would still be absent from any one window.
  *
  * Each program also asserts its printed output, which is what makes assertions about *fewer* instructions safe: a
  * conversion wrongly dropped truncates the value or fails class verification, and both show up in what the program
  * prints.
  */
class RepresentationRoundTripIntegrationTest extends FullIntegrationTest with EmittedInstructions {

  // The `Ranges` example, which is where this was found. `count`'s body derives `[7, 7]`, so each of the three calls to
  // it carries a `Byte`-wide meta — and each of them feeds an ordinary ⊤/bignum argument slot.
  private val sum =
    """|import eliot.effect.Console
       |def count: Int = 7
       |def total: Int = count + count + count
       |def main uses Console: Unit = printLine(show(total))
       |""".stripMargin

  "a derived narrow result feeding a bignum slot" should "still print the sum" in {
    compileAndRun(sum).asserting(_ shouldBe "21")
  }

  it should "be passed on unconverted" in {
    compileAndRun(sum) >> instructionsOfMethod("total").asserting(
      _ shouldBe Seq(
        "INVOKESTATIC Test.count",
        "INVOKESTATIC Test.count",
        "INVOKESTATIC eliot/lang/Numeric._plus_$Default$Int",
        "INVOKESTATIC Test.count",
        "INVOKESTATIC eliot/lang/Numeric._plus_$Default$Int"
      )
    )
  }

  // A constant is materialised at whatever width is asked for, so it is pushed at the width its consumer reads. This is
  // the oldest of the three round trips and the one that predates the interpretation entirely: `count`'s body used to
  // box `7` as a `Byte` and unbox it again for the method's bignum return, and an argument literal has done the same
  // since the channel shipped.
  it should "not push a constant at a width nobody reads" in {
    compileAndRun(sum) >> instructionsOfMethod("count")
      .asserting(_ shouldBe Seq("INVOKESTATIC java/math/BigInteger.valueOf"))
  }

  // `show` is an inline intrinsic, so it reads its operand off the stack rather than through a call boundary — and it
  // renders a `BigInteger` and a `Byte` equally well. Narrowing the boundary result to a `Byte` just so the intrinsic
  // can unbox it again is the same round trip seen from the operand side.
  private val shown =
    """|import eliot.effect.Console
       |def double(x: Int): Int = x + x
       |def shown: String = show(double(21))
       |def main uses Console: Unit = printLine(shown)
       |""".stripMargin

  "an intrinsic's operand arriving from a call boundary" should "still print the value" in {
    compileAndRun(shown).asserting(_ shouldBe "42")
  }

  it should "be read at the width it arrives at" in {
    compileAndRun(shown) >> instructionsOfMethod("shown").asserting(
      _ shouldBe Seq(
        "INVOKESTATIC java/math/BigInteger.valueOf",
        "INVOKESTATIC Test.double",
        "INVOKEVIRTUAL java/math/BigInteger.toString"
      )
    )
  }
}
