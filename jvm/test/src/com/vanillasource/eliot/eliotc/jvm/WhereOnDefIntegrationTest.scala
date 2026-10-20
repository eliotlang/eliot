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

  // Demands are checked in context (`docs/total-meta-transfers.md` P5): every meta is known from `main`, so a demand on a
  // parameter is checked at what each call actually supplies, never once over a ⊤ parameter.
  it should "accept a forwarded parameter at a call that fits" in {
    compileAndRun(
      useByte + "def relay(y: Int): Int = useByte(y)\ndef main uses Console: Unit = printLine(show(relay(42)))"
    ).asserting(_ shouldBe "42")
  }

  it should "reject a forwarded parameter at the call that does not fit, naming where it is demanded" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\ndef main uses Console: Unit = printLine(show(relay(1000)))"
    ).asserting(_ should (include("precondition of 'Test::useByte' is not satisfied") and include("In 'Test::relay'")))
  }

  it should "check a demand through every body between it and main" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\ndef twice(z: Int): Int = relay(add(z, z))\n" +
        "def main uses Console: Unit = printLine(show(twice(100)))"
    ).asserting(_ should (include("In 'Test::relay'") and include("In 'Test::twice'")))
  }

  it should "not demand the meta of an argument its context does not decide" in {
    compileAndRun(
      useByte + "def pick(index: Int, fallback: Int): Int where withinByte(range(index)) = index\n" +
        "def relay(y: Int): Int = pick(5, y)\ndef main uses Console: Unit = printLine(show(relay(1000)))"
    ).asserting(_ shouldBe "5")
  }

  it should "read a code argument as what its code yields" in {
    compileAndRun(
      useByte + "def times(count: Int, body uses *: Int) uses *: Int where withinByte(range(count)) = body\n" +
        "def main uses Console: Unit = printLine(show(times(3, 7)))"
    ).asserting(_ shouldBe "7")
  }

  it should "reject a call whose argument range is unknown (top), rather than silently accept" in {
    compileForErrors(
      useByte + "def applyTo(f: Int => Int, x: Int): Int = f(x)\n" +
        "def main uses Console: Unit = printLine(show(applyTo(y -> useByte(y), 42)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  it should "reject a def whose demand its arguments decide, passed as a value" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\ndef call(f: Int => Int, x: Int): Int = f(x)\n" +
        "def main uses Console: Unit = printLine(show(call(relay, 5)))"
    ).asserting(_ should (include("Cannot prove the precondition of 'Test::useByte'") and include("'y' is not known")))
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

  // A predicate written over a control effect, or naming `rangeWithin` itself, could not be evaluated at all before the
  // compile track threaded its frames: "Cannot evaluate the `where` precondition" (`docs/effects.md` §8 item 28).
  private val inlineByte =
    "import eliot.effect.Console\ndef asByte(x: Int): Int where rangeWithin[0, 255](range(x)) = x\n"

  private val branchingByte =
    "import eliot.effect.Console\n" +
      "def asByte(x: Int): Int where (if(rangeWithin[0, 255](range(x)), true) else false) = x\n"

  "a where precondition naming rangeWithin inline" should "accept a call whose argument range fits" in {
    compileAndRun(inlineByte + "def main uses Console: Unit = printLine(show(asByte(200)))").asserting(_ shouldBe "200")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(inlineByte + "def main uses Console: Unit = printLine(show(asByte(300)))")
      .asserting(_ should include("precondition of 'Test::asByte' is not satisfied"))
  }

  "a where precondition written over if..else" should "accept a call whose argument range fits" in {
    compileAndRun(branchingByte + "def main uses Console: Unit = printLine(show(asByte(3)))").asserting(_ shouldBe "3")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(branchingByte + "def main uses Console: Unit = printLine(show(asByte(300)))")
      .asserting(_ should include("precondition of 'Test::asByte' is not satisfied"))
  }

  // A threaded predicate links each callee reduced at its instantiation, and those were keyed by FQN, so of `pickOf` at
  // `BigInteger` and at `String` the last linked answered both (`docs/effects.md` §8 item 49).
  private def picking(predicate: String) =
    """import eliot.effect.Console
      |ability Pick[T] {
      |   def picked(t: T): Bool
      |}
      |implement Pick[BigInteger] {
      |   def picked(t: BigInteger): Bool = true
      |}
      |implement Pick[String] {
      |   def picked(t: String): Bool = false
      |}
      |def pickOf[T ~ Pick[T]](t: T): Bool = picked(t)
      |""".stripMargin +
      s"def checked(x: Int): Int where $predicate = x\n" +
      "def main uses Console: Unit = printLine(show(checked(42)))"

  "a threaded where precondition calling one function at two instantiations" should "accept what it proves" in {
    compileAndRun(picking("""(if(pickOf("a"), false) else pickOf(1))""")).asserting(_ shouldBe "42")
  }

  it should "reject what it refutes" in {
    compileForErrors(picking("""(if(pickOf(1), pickOf("a")) else true)"""))
      .asserting(_ should include("precondition of 'Test::checked' is not satisfied"))
  }

  // A parameter's meta type is read from its *evaluated* type at each call, never named from how the type is spelled:
  // `Byte$Meta`, `T$Meta` or `Box$Meta` exist for nothing, and naming them failed every shape below.
  private def byteOf(declarations: String, parameterType: String, argument: String) =
    "import eliot.effect.Console\n" + declarations +
      s"def asByte(x: $parameterType): Int where rangeWithin[0, 255](range(x)) = x\n" +
      s"def main uses Console: Unit = printLine(show(asByte($argument)))"

  "a where precondition on a parameter typed by an alias" should "accept a call whose argument range fits" in {
    compileAndRun(byteOf("type Byte = Int\n", "Byte", "7")).asserting(_ shouldBe "7")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(byteOf("type Byte = Int\n", "Byte", "300"))
      .asserting(_ should include("precondition of 'Test::asByte' is not satisfied"))
  }

  "a where precondition on a parameter typed by an alias of an alias" should "accept a call whose argument range fits" in {
    compileAndRun(byteOf("type Number = Int\ntype Byte = Number\n", "Byte", "7")).asserting(_ shouldBe "7")
  }

  "a where precondition on a parameter typed by a computed type" should "accept a call whose argument range fits" in {
    compileAndRun(
      byteOf("data Box[A](content: A)\n", "(Box[Int] match {\n case Box[a] -> a\n case _ -> String\n})", "7")
    ).asserting(_ shouldBe "7")
  }

  "a where precondition naming its definition's type parameters" should "accept a call whose argument range fits" in {
    compileAndRun(
      "import eliot.effect.Console\n" +
        "def within[Lo: BigInteger, Hi: BigInteger](x: Int): Int where rangeWithin[Lo, Hi](range(x)) = x\n" +
        "def main uses Console: Unit = printLine(show(within[0, 9](7)))"
    ).asserting(_ shouldBe "7")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(
      "import eliot.effect.Console\n" +
        "def within[Lo: BigInteger, Hi: BigInteger](x: Int): Int where rangeWithin[Lo, Hi](range(x)) = x\n" +
        "def main uses Console: Unit = printLine(show(within[0, 5](7)))"
    ).asserting(_ should include("precondition of 'Test::within' is not satisfied"))
  }

  "a where precondition on a parameter typed by a type parameter" should "accept a call whose argument range fits" in {
    compileAndRun(
      "import eliot.effect.Console\n" +
        "def asByte[T](x: T): T where rangeWithin[0, 255](range(x)) = x\n" +
        "def main uses Console: Unit = printLine(show(asByte(7)))"
    ).asserting(_ shouldBe "7")
  }

  // A type that carries no meta has the meta `Unit`, which is known — so such a parameter beside a tracked one no longer
  // makes every call unprovable.
  private val pickByte =
    "import eliot.effect.Console\n" +
      "data Box(label: String)\n" +
      "def pick(x: Int, b: Box): Int where rangeWithin[0, 255](range(x)) = x\n"

  "a where precondition beside a parameter whose type carries no meta" should "accept a call whose argument range fits" in {
    compileAndRun(pickByte + """def main uses Console: Unit = printLine(show(pick(7, Box("b"))))""").asserting(_ shouldBe "7")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(pickByte + """def main uses Console: Unit = printLine(show(pick(300, Box("b"))))""")
      .asserting(_ should include("precondition of 'Test::pick' is not satisfied"))
  }

  "a where precondition reading the meta of a type that carries none" should "be rejected, not accepted" in {
    compileForErrors(
      "import eliot.effect.Console\n" +
        "data Box(label: String)\n" +
        "def pick(x: Int, b: Box): Int where rangeWithin[0, 255](range(b)) = x\n" +
        """def main uses Console: Unit = printLine(show(pick(7, Box("b"))))"""
    ).asserting(_ should include("Cannot evaluate the `where` precondition of 'Test::pick'"))
  }

  // The companion takes the callee's binders as the effect desugar leaves them, handles and handed binders included. A
  // code parameter is read as the value its code yields, whose meta an argument never carries, so the one here yields a
  // type that carries none.
  "a where precondition on a definition taking handles" should "accept a call whose argument range fits" in {
    compileAndRun(
      "import eliot.effect.Console\n" +
        "def loud(x: Int) uses Console: Int where rangeWithin[0, 255](range(x)) = {\n   printLine(\"loud\")\n   x\n}\n" +
        "def main uses Console: Unit = printLine(show(loud(7)))"
    ).asserting(_ shouldBe "loud\n7")
  }

  "a where precondition on a definition handing its code a handle" should "accept a call whose argument range fits" in {
    compileAndRun(
      "import eliot.effect.Console\n" +
        "def guarded(x: Int, body uses *, Abort: Unit) uses *: Unit where rangeWithin[0, 255](range(x)) = body else unit\n" +
        "def main uses Console: Unit = guarded(7, printLine(\"ran\"))"
    ).asserting(_ shouldBe "ran")
  }

  it should "reject a call whose argument range exceeds the bound" in {
    compileForErrors(
      "import eliot.effect.Console\n" +
        "def guarded(x: Int, body uses *, Abort: Unit) uses *: Unit where rangeWithin[0, 255](range(x)) = body else unit\n" +
        "def main uses Console: Unit = guarded(300, printLine(\"ran\"))"
    ).asserting(_ should include("precondition of 'Test::guarded' is not satisfied"))
  }
}
