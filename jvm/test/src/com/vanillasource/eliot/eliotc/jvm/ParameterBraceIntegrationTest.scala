package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of **parameter braces** (`docs/total-meta-transfers.md` §8.9): `x: Int {closed(0, 255)}` states the
  * meta-information of the value at that parameter. Whoever supplies the value proves it, so every full call demands
  * that its argument's meta fits the stated one. Inside the body a demand is checked at what each call supplies
  * (`docs/total-meta-transfers.md` P5), which every call has proven fits.
  */
class ParameterBraceIntegrationTest extends FullIntegrationTest {
  private val asByte =
    "import eliot.effect.Console\ndef asByte(x: Int {closed(0, 255)}): Int = x\n"

  private val useByte =
    "def useByte(x: Int) where rangeWithin[0, 255](range(x)): Int = x\n"

  "a parameter brace" should "accept a call whose argument fits" in {
    compileAndRun(asByte + "def main uses Console: Unit = printLine(show(asByte(200)))").asserting(_ shouldBe "200")
  }

  it should "reject a call whose argument does not fit" in {
    compileForErrors(asByte + "def main uses Console: Unit = printLine(show(asByte(300)))")
      .asserting(_ should include("which the parameter 'x' of 'Test::asByte' states"))
  }

  it should "accept a forwarded parameter at a call that fits" in {
    compileAndRun(
      asByte + "def relay(y: Int): Int = asByte(y)\ndef main uses Console: Unit = printLine(show(relay(42)))"
    ).asserting(_ shouldBe "42")
  }

  it should "reject a forwarded parameter at the call that does not fit" in {
    compileForErrors(
      asByte + "def relay(y: Int): Int = asByte(y)\ndef main uses Console: Unit = printLine(show(relay(300)))"
    ).asserting(_ should (include("which the parameter 'x' of 'Test::asByte' states") and include("In 'Test::relay'")))
  }

  it should "reject a call whose argument's meta is unknown, rather than silently accept" in {
    compileForErrors(
      asByte + "def applyTo(f: Int => Int, v: Int): Int = f(v)\n" +
        "def main uses Console: Unit = printLine(show(applyTo(y -> asByte(y), 42)))"
    ).asserting(_ should include("Cannot prove what the parameter 'x' of 'Test::asByte' states"))
  }

  it should "satisfy a demand inside the body with what its call supplies" in {
    compileAndRun(
      "import eliot.effect.Console\n" + useByte +
        "def asByte(x: Int {closed(0, 255)}): Int = useByte(x)\n" +
        "def main uses Console: Unit = printLine(show(asByte(7)))"
    ).asserting(_ shouldBe "7")
  }

  it should "prove a narrower brace from a wider one inside a body" in {
    compileAndRun(
      asByte + "def asPercent(p: Int {closed(0, 100)}): Int = asByte(p)\n" +
        "def main uses Console: Unit = printLine(show(asPercent(42)))"
    ).asserting(_ shouldBe "42")
  }

  it should "check a narrower brace inside a wider one's body at what its call supplies" in {
    compileForErrors(
      asByte + "def asWord(w: Int {closed(0, 65535)}): Int = asByte(w)\n" +
        "def main uses Console: Unit = printLine(show(asWord(300)))"
    ).asserting(_ should include("which the parameter 'x' of 'Test::asByte' states"))
  }

  it should "let a lambda's own binder shadow a stated parameter" in {
    compileForErrors(
      "import eliot.effect.Console\n" + useByte +
        "def call(f: Int => Int, v: Int): Int = f(v)\n" +
        "def shadowing(x: Int {closed(0, 255)}): Int = call(x -> useByte(x), 1000)\n" +
        "def main uses Console: Unit = printLine(show(shadowing(1)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  it should "place the stated parameter past the handles a definition takes" in {
    compileAndRun(
      "import eliot.effect.Console\n" + useByte +
        "def printByte(label: String, x: Int {closed(0, 255)}) uses Console: Unit = printLine(label ++ show(useByte(x)))\n" +
        "def main uses Console: Unit = printByte(\"byte \", 9)"
    ).asserting(_ shouldBe "byte 9")
  }

  it should "state the same meta when the parameter's type is an alias" in {
    compileForErrors(
      "import eliot.effect.Console\ntype Byte = Int\ndef asByte(x: Byte {closed(0, 255)}): Int = x\n" +
        "def main uses Console: Unit = printLine(show(asByte(300)))"
    ).asserting(_ should include("which the parameter 'x' of 'Test::asByte' states"))
  }

  it should "reject a brace on a parameter whose type carries no meta" in {
    compileForErrors(
      "import eliot.effect.Console\ndef flag(b: Bool {closed(0, 1)}): Bool = b\n" +
        "def main uses Console: Unit = printLine(show(fold(flag(true), 1, 0)))"
    ).asserting(_ should include("carries none"))
  }

  it should "reject the def passed as a bare value" in {
    compileForErrors(
      asByte + "def call(f: Int => Int, v: Int): Int = f(v)\n" +
        "def main uses Console: Unit = printLine(show(call(asByte, 1000)))"
    ).asserting(_ should include("cannot be passed as a value"))
  }

  it should "reject a partial application of the def" in {
    compileForErrors(
      "import eliot.effect.Console\ndef firstByte(x: Int {closed(0, 255)}, y: Int): Int = x\n" +
        "def apply1(g: Int => Int): Int = g(5)\n" +
        "def main uses Console: Unit = printLine(show(apply1(firstByte(1))))"
    ).asserting(_ should include("cannot be passed as a value"))
  }
}
