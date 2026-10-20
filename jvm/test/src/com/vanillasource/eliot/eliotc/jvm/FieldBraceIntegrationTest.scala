package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of **field braces** (`docs/meta.md` §4.5): `r: Int {closed(0, 255)}` on a `data`
  * field states the meta-information of the value the field holds. The constructor is its only supplier, so every
  * construction proves it, and the `match` eliminator states it of what it hands a handler — which is what an accessor
  * and every `match` arm read the field through.
  */
class FieldBraceIntegrationTest extends FullIntegrationTest {
  private val prelude =
    "import eliot.effect.Console\n" +
      "def useByte(x: Int) where rangeWithin[0, 255](range(x)): Int = x\n"

  private val pixel = prelude + "data Pixel(r: Int {closed(0, 255)}, g: Int)\n"

  "a field brace" should "accept a construction whose argument fits" in {
    compileAndRun(pixel + "def main uses Console: Unit = printLine(show(Pixel(200, 1000).g))")
      .asserting(_ shouldBe "1000")
  }

  it should "reject a construction whose argument does not fit" in {
    compileForErrors(pixel + "def main uses Console: Unit = printLine(show(Pixel(300, 0).g))")
      .asserting(_ should include("which the parameter 'r' of 'Test::Pixel' states"))
  }

  it should "give an accessor the stated meta, whatever the value it reads" in {
    compileAndRun(
      pixel + "def red(p: Pixel): Int = useByte(p.r)\ndef main uses Console: Unit = printLine(show(red(Pixel(7, 0))))"
    ).asserting(_ shouldBe "7")
  }

  it should "leave an unbraced field unknown, rather than silently accept" in {
    compileForErrors(
      pixel + "def green(p: Pixel): Int = useByte(p.g)\ndef main uses Console: Unit = printLine(show(green(Pixel(7, 0))))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  it should "reject a read whose stated meta is wider than a demand" in {
    compileForErrors(
      prelude + "data Wide(w: Int {closed(0, 300)})\n" +
        "def main uses Console: Unit = printLine(show(useByte(Wide(7).w)))"
    ).asserting(_ should include("The precondition of 'Test::useByte' is not satisfied"))
  }

  it should "satisfy a demand inside a match arm on the field's binder" in {
    compileAndRun(
      pixel + "def red(p: Pixel): Int = p match {\n   case Pixel(r, g) -> useByte(r)\n}\n" +
        "def main uses Console: Unit = printLine(show(red(Pixel(9, 0))))"
    ).asserting(_ shouldBe "9")
  }

  it should "join a braced field across the arms of a type with several constructors" in {
    compileAndRun(
      prelude + "data Reading = Celsius(t: Int {closed(0 - 50, 60)}) | Missing\n" +
        "def toByte(r: Reading): Int = r match {\n   case Celsius(t) -> t + 50\n   case Missing -> 0\n}\n" +
        "def main uses Console: Unit = printLine(show(useByte(toByte(Celsius(20)))))"
    ).asserting(_ shouldBe "70")
  }

  it should "state a field at its data type's type arguments" in {
    compileAndRun(
      prelude + "data Capped[Hi: BigInteger](v: Int {closed(0, Hi)})\n" +
        "def main uses Console: Unit = printLine(show(useByte(Capped[200](7).v)))"
    ).asserting(_ shouldBe "7")
  }

  it should "reject a brace on a field whose type carries no meta" in {
    compileForErrors(
      prelude + "data Flagged(flag: Bool {closed(0, 1)})\n" +
        "def main uses Console: Unit = printLine(fold(Flagged(true).flag, \"y\", \"n\"))"
    ).asserting(_ should include("its type 'Bool' carries none"))
  }
}
