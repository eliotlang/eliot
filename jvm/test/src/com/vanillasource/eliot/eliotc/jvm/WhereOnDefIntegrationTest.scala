package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of `where`-on-defs — a refinement precondition on an ordinary `def`, verified at each use site by
  * the refinement channel (`docs/bounds-as-refinements.md` §4.3). `def useByte(x: Int): Int where withinByte(range(x))`
  * demands that every call's argument have a value range provably within `[0, 127]`; the channel reduces the
  * generated `^Where` companion over the argument's computed interval and rejects a caller whose range is out of bounds
  * or unknown (⊤). This is the use-site verification that closes the Step-6 enforcement gap (out-of-range `Int` values
  * had no JVM-backed rejection until now).
  */
class WhereOnDefIntegrationTest extends FullIntegrationTest {
  // `withinByte` is a test-local predicate (it deliberately lives only where a test needs it, not in any layer).
  private val withinByte =
    """|def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |""".stripMargin

  private val useByte =
    "import eliot.effect.Console\n" + withinByte +
      "def useByte(x: Int): Int where withinByte(range(x)) = x\n"

  "a where precondition" should "accept a call whose argument range provably fits" in {
    compileAndRun(useByte + "def main uses Console: Unit = printLine(show(useByte(42)))")
      .asserting(_ shouldBe "42")
  }

  it should "accept an in-range literal even inside a parametered def's body" in {
    compileAndRun(useByte + "def wrap(ignored: Int): Int = useByte(127)\ndef main uses Console: Unit = printLine(show(wrap(0)))")
      .asserting(_ shouldBe "127")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(useByte + "def main uses Console: Unit = printLine(show(useByte(1000)))")
      .asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))
  }

  it should "reject a call whose argument range is unknown (top), rather than silently accept" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\ndef main uses Console: Unit = printLine(show(relay(42)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  // Higher-order escape (`docs/refinement-channel-follow-ups.md` §2.1): passing a `where`-bearing def as a *value*
  // rides a function value whose eventual call the channel never sees, so the precondition would be silently skipped.
  // Reject any reference to a `where`-bearing def that is not the head of a full application.
  it should "reject a where-bearing def passed as a bare value, not silently bypass the precondition" in {
    compileForErrors(
      useByte +
        "def call(f: Int => Int, x: Int): Int = f(x)\n" +
        "def main uses Console: Unit = printLine(show(call(useByte, 1000)))"
    ).asserting(_ should include("cannot be passed as a value"))
  }

  // A pure `val` block lowers to `(x -> rest)(e)` (`BlockDesugaringProcessor`), so the block's continuation is the
  // *head* of an application, not one of its arguments. The channel walked only the arguments, so every call after a
  // pure binding escaped the use-site demand entirely — a violated precondition compiled clean and ran. The walk must
  // reach an unrecognised head too, or `where` means nothing past the first `val`.
  it should "reject a call whose argument range exceeds the bound after a pure val binding" in {
    compileForErrors(
      useByte +
        "def compute: Int = {\n  val ignored = 1\n  useByte(1000)\n}\n" +
        "def main uses Console: Unit = printLine(show(compute))"
    ).asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))
  }

  it should "reject a where-bearing def passed as a bare value after a pure val binding" in {
    compileForErrors(
      useByte +
        "def call(f: Int => Int, x: Int): Int = f(x)\n" +
        "def compute: Int = {\n  val ignored = 1\n  call(useByte, 1000)\n}\n" +
        "def main uses Console: Unit = printLine(show(compute))"
    ).asserting(_ should include("cannot be passed as a value"))
  }

  it should "accept an in-range call after a pure val binding" in {
    compileAndRun(
      useByte +
        "def compute: Int = {\n  val ignored = 1\n  useByte(127)\n}\n" +
        "def main uses Console: Unit = printLine(show(compute))"
    ).asserting(_ shouldBe "127")
  }

  it should "reject a partial application of a where-bearing def passed as a value" in {
    compileForErrors(
      "import eliot.effect.Console\n" + withinByte +
        "def clampFirst(a: Int, b: Int): Int where withinByte(range(a)) = a\n" +
        "def apply1(g: Int => Int): Int = g(5)\n" +
        "def main uses Console: Unit = printLine(show(apply1(clampFirst(200))))"
    ).asserting(_ should include("cannot be passed as a value"))
  }
}
