package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the refinement channel's **second domain**: a `String`'s tracked `size`
  * (`docs/string-length-meta.md`). `type String {size: Interval[BigInteger]}` declares the slot; a string
  * literal *seeds* the singleton size of its own length through the same literal-protocol `^Meta` path the integer
  * seed uses (`eliot.lang.Runtime::stringLiteral`'s `{closed(N, N)}` brace).
  *
  * The point of the suite is that nothing between the seed and the diagnostic is `Int`-shaped: the meta structure, the
  * derived lattice and the `where` demand are all the shipped machinery, reached by declaring one slot on a second
  * type. So the cases mirror [[WhereOnDefIntegrationTest]]'s, and the code-point cases pin the one claim that is new
  * here — that a size counts what `String::length` counts, on every target.
  *
  * What the suite deliberately does *not* claim is precision beyond a literal: the channel is intra-procedural, so a
  * string arriving through a parameter or a call is ⊤ and a `where` over it is a hard error rather than a silent pass
  * (`docs/string-length-meta.md` §9). That is the same limitation the `Int` domain has, and the same fix retires both.
  */
class StringSizeIntegrationTest extends FullIntegrationTest {
  // A display four code points wide. `size` is `String`'s meta slot exactly as `range` is `Int`'s, so the predicate is
  // the ordinary `rangeWithin` over an `Interval[BigInteger]` — no string-specific machinery.
  private val banner =
    """|import eliot.effect.Console
       |def fitsDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 4](i)
       |def banner(text: String): String where fitsDisplay(size(text)) = text
       |""".stripMargin

  "a where precondition over a string's size" should "accept a literal that provably fits" in {
    compileAndRun(banner + """def main uses Console: Unit = printLine(banner("abcd"))""")
      .asserting(_ shouldBe "abcd")
  }

  it should "accept the empty literal" in {
    compileAndRun(banner + """def main uses Console: Unit = printLine(banner(""))""")
      .asserting(_ shouldBe "")
  }

  it should "reject a literal longer than the bound" in {
    compileForErrors(banner + """def main uses Console: Unit = printLine(banner("abcde"))""")
      .asserting(_ should include("precondition of 'Test::banner' is not satisfied"))
  }

  it should "reject a string whose size is unknown, rather than silently accept" in {
    compileForErrors(
      banner + """def relay(s: String): String = banner(s)
                 |def main uses Console: Unit = printLine(relay("ab"))""".stripMargin
    ).asserting(_ should include("Cannot prove the precondition of 'Test::banner'"))
  }

  // A def's own parameters are leading lambdas in the monomorphized body, whose *records* the channel drops — but the
  // walk still happens, so the demand on a literal inside such a body is made rather than skipped.
  it should "demand the precondition on a literal inside a parametered def's body" in {
    compileForErrors(
      banner + """def wrap(ignored: Int): String = banner("abcde")
                 |def main uses Console: Unit = printLine(wrap(0))""".stripMargin
    ).asserting(_ should include("precondition of 'Test::banner' is not satisfied"))
  }

  // The unit is the **code point**, which is what `String::length` counts on every target — so a supplementary
  // character counts once, not as the two UTF-16 units the JVM stores it in. Four of them are exactly the four-wide
  // display; measuring storage instead would make this a compile error, and the `where` would mean something different
  // on a UTF-8 target than on this one.
  it should "count a supplementary character as one code point, not as its two UTF-16 units" in {
    compileAndRun(banner + """def main uses Console: Unit = printLine(banner("𝕏𝕏𝕏𝕏"))""")
      .asserting(_ shouldBe "𝕏𝕏𝕏𝕏")
  }

  it should "still reject a supplementary-character literal that is too long in code points" in {
    compileForErrors(banner + """def main uses Console: Unit = printLine(banner("𝕏𝕏𝕏𝕏𝕏"))""")
      .asserting(_ should include("precondition of 'Test::banner' is not satisfied"))
  }

  // A string's size and an integer's range are separate domains over the same `Interval` lattice, and a `where` over
  // one must not be dischargeable by the other's meta. Both preconditions hold here for their own reasons.
  it should "keep the string and integer domains apart in one program" in {
    compileAndRun(
      """|import eliot.effect.Console
         |def fitsDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 4](i)
         |def banner(text: String): String where fitsDisplay(size(text)) = text
         |def asDigit(x: Int): Int where fitsDisplay(range(x)) = x
         |def main uses Console: Unit = printLine(banner("ab") ++ show(asDigit(3)))""".stripMargin
    ).asserting(_ shouldBe "ab3")
  }
}
