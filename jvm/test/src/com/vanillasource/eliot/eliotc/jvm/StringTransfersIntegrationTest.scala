package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the **stated transfers on the `String` leaves** (`docs/string-length-meta.md` §6, stage S4):
  * the rest of `String.els`'s body-less definitions now say what their result's size is, so a size no longer stops at
  * the first call the way it did when only `length` stated one.
  *
  * Every leaf gets a *pair* of cases against the same eight-wide display, and the pair is the point. Accepting alone
  * would also pass if a transfer widened its result to ⊤ and the `where` were somehow discharged elsewhere; rejecting
  * alone would also pass if the size were simply unknown — which is a different diagnostic, and the suite pins that
  * difference too ("not satisfied" is a violated bound, "Cannot prove" is an absent one). Only both together show the
  * bound arriving at the call site with the precision the brace claims.
  *
  * Each program is a single expression rather than a block, and calls its leaf directly rather than through a dot. Both
  * are the intra-procedural limits `docs/string-length-meta.md` §9 already describes and neither is new here: a bodied
  * wrapper (`++`, `take`, `indexOf`) derives nothing until the meta interpretation lands, and the dot's
  * `Function.apply` bridge is one of the aliased positions the channel drops to ⊤ — the shipped `length` transfer
  * behaves the same way, so this suite would fail identically against `"abc".length`.
  */
class StringTransfersIntegrationTest extends FullIntegrationTest {
  // A display eight code points wide, exactly as `StringSizeIntegrationTest`'s is four: an ordinary `rangeWithin` over
  // an `Interval[BigInteger]`, with nothing string-specific about it.
  private val banner =
    """|import eliot.effect.Console
       |def fitsDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 8](i)
       |def banner(text: String): String where fitsDisplay(size(text)) = text
       |""".stripMargin

  private def accepts(expression: String, output: String) =
    compileAndRun(banner + s"def main uses Console: Unit = printLine(banner($expression))").asserting(_ shouldBe output)

  private def rejects(expression: String) =
    compileForErrors(banner + s"def main uses Console: Unit = printLine(banner($expression))")
      .asserting(_ should include("precondition of 'Test::banner' is not satisfied"))

  // Concatenation is the one row stated *exactly*: the result is every code point of both operands and nothing else.
  "the size of a concatenation" should "be the sum of its operands' sizes" in {
    accepts("""combine("abcd", "efgh")""", "abcdefgh")
  }

  it should "reject one code point over the display" in {
    rejects("""combine("abcd", "efghi")""")
  }

  // A slice is bounded by what it was cut from. The indices are deliberately *not* consulted, which the reject case
  // documents: two code points out of nine is a two-code-point string, and this still refuses it.
  "the size of a slice" should "be bounded by the string it is cut from" in {
    accepts("""substring(0, 3, "abcdefgh")""", "abc")
  }

  it should "reject a slice of an over-long string however few code points it takes" in {
    rejects("""substring(0, 2, "abcdefghi")""")
  }

  "the size of a trimmed string" should "be bounded by the untrimmed one" in {
    accepts("""trim("  abcd  ")""", "abcd")
  }

  it should "reject when the untrimmed string is over the display" in {
    rejects("""trim("  abcdefg  ")""")
  }

  // Case conversion is not length-preserving, so the upper bound triples. Three code points in may be nine out, which
  // the display refuses — and that refusal is the bound doing its job rather than being conservative for its own
  // sake.
  "the size of an upper-cased string" should "keep its lower bound and triple its upper one" in {
    accepts("""toUpperCase("ab")""", "AB")
  }

  it should "reject three code points, since each may become three" in {
    rejects("""toUpperCase("abc")""")
  }

  // The case the tripling exists for: `ß` upper-cases to `SS`, so one code point in is genuinely two out. A
  // length-preserving bound would have accepted this against a one-wide display and been wrong at runtime.
  it should "refuse to promise that one code point stays one" in {
    compileForErrors(
      """|import eliot.effect.Console
         |def fitsNarrow(i: Interval[BigInteger]): Bool = rangeWithin[0, 1](i)
         |def initial(text: String): String where fitsNarrow(size(text)) = text
         |def main uses Console: Unit = printLine(initial(toUpperCase("ß")))""".stripMargin
    ).asserting(_ should include("precondition of 'Test::initial' is not satisfied"))
  }

  it should "state the same bound for lower-casing" in {
    accepts("""toLowerCase("AB")""", "ab")
  }

  // The other direction of cross-domain flow: an `Int`'s range drives a `String`'s size.
  "the size of a repetition" should "be its count times its subject's size" in {
    accepts("""repeat(4, "ab")""", "abababab")
  }

  it should "reject one repetition too many" in {
    rejects("""repeat(5, "ab")""")
  }

  // The worst case is every original code point kept plus a replacement at each of the `size(s) + 1` positions an
  // empty target would occupy: 3 + (3 + 1) * 1 = 7 here, which fits, and 3 + (3 + 1) * 2 = 11, which does not.
  "the size of a replacement" should "allow for a replacement between every pair of characters" in {
    accepts("""replace("-", "+", "a-b")""", "a+b")
  }

  it should "reject when the replacement could outgrow the display" in {
    rejects("""replace("-", "++", "a-b")""")
  }

  // `parseIntInternal` states `whole` — the interval open at both ends. That is a *stated* top rather than an
  // omission, so the program compiles and runs, and a `where` over the parsed number is refused for want of a bound
  // rather than silently discharged. The two diagnostics differ, which is the whole of R4.
  "a leaf that states the domain's top" should "still compile and run" in {
    compileAndRun(
      """|import eliot.effect.Console
         |def main uses Console: Unit = printLine(show(parseInt("42") else 0))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "leave a where over the parsed number unprovable rather than satisfied" in {
    compileForErrors(
      """|import eliot.effect.Console
         |def withinDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 8](i)
         |def column(x: Int): Int where withinDisplay(range(x)) = x
         |def main uses Console: Unit = printLine(show(column(parseInt("42") else 0)))""".stripMargin
    ).asserting(_ should include("Cannot prove the precondition of 'Test::column'"))
  }

  // Nothing above changes what any of these leaves compute; the transfers are meta-information only.
  "the stated transfers" should "leave every leaf's runtime result untouched" in {
    compileAndRun(
      """|import eliot.effect.Console
         |def main uses Console: Unit =
         |   printLine(combine(substring(1, 4, "xabcy"), combine(trim("  d  "), repeat(2, replace("-", "", "e-")))))
         |""".stripMargin
    ).asserting(_ shouldBe "abcdee")
  }
}
