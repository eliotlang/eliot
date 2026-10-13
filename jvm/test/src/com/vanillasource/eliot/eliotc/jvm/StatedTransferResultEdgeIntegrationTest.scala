package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the **result-edge re-encode** (`docs/string-length-meta.md` §8, stage S3) and of the first
  * *stated* meta transfer that needs it: `def length(s: String): Int {size(s)}`.
  *
  * A leaf's stated transfer pins a narrow range on the **call node** — `length("hello")` is `[5, 5]`, which the backend
  * lays out as a `java.lang.Byte`. But `String::length` is emitted as a generated native static method whose return
  * descriptor is the ⊤ bignum, so without a conversion on the result edge the class verifier rejects the caller
  * (`VerifyError: 'java/math/BigInteger' is not assignable to 'java/lang/Byte'`). The backend already widens *arguments*
  * to the bignum boundary; this is the mirror of that on the way out.
  *
  * The suite asserts both halves, because either alone would pass while the feature was broken: the program must
  * **run** (the verifier accepts the class), and the transfer must actually reach the channel — which is what the
  * rejecting case pins, since an unknown bound can only make a precondition *unprovable*, never *violated*.
  *
  * What the re-encode is *not* is an obligation to materialise the narrow width wherever the channel stamped one. Since
  * the round-trip peephole (`docs/total-meta-transfers.md` §P4) a consumer that reads the ⊤ width anyway — an argument
  * slot, a method return — gets the boundary result unconverted, and a consumer that adapts to any width — an
  * intrinsic's operand — reads it as it arrives. So the re-encode is the *fallback* for a consumer that genuinely
  * demands the node's width, and the cases below pin both sides of that: what is emitted, and what is not.
  */
class StatedTransferResultEdgeIntegrationTest extends FullIntegrationTest with EmittedInstructions {
  // The `docs/string-length-meta.md` §4.4 program: a literal's size flows through `length`'s stated transfer into the
  // `Int` domain, where an ordinary value-range `where` is discharged by it. Two domains, one `Interval` predicate.
  private val crossDomain =
    """|import eliot.effect.Console
       |def withinFive(i: Interval[BigInteger]): Bool = rangeWithin[0, 5](i)
       |def small(x: Int): Int where withinFive(range(x)) = x
       |""".stripMargin

  "a call whose stated transfer narrows its result" should "run rather than fail class verification" in {
    compileAndRun(crossDomain + """def main uses Console: Unit = printLine(show(small(length("hello"))))""")
      .asserting(_ shouldBe "5")
  }

  // `[5, 5]` lays out as a `java.lang.Byte`, but nothing here reads a `Byte`: `small`'s parameter is an ordinary
  // ⊤/bignum slot, so narrowing the native's result only for the argument edge to widen it straight back would compute
  // the same number twice. The transfer is still doing its work — the two cases either side of this one are its
  // observable — and it costs no instruction to do it.
  it should "leave its result unconverted where the consumer reads the boundary width anyway" in {
    compileAndRun(crossDomain + """def main uses Console: Unit = printLine(show(small(length("hello"))))""") >>
      instructionsFollowing("eliot/lang/String.length", 1).asserting(_ shouldBe Seq("INVOKESTATIC Test.small"))
  }

  // The size a literal seeds is carried into the `Int` domain exactly, not merely widened into it: an eleven-code-point
  // literal fails the same precondition the five-code-point one discharges. Without the transfer this call is ⊤ and the
  // precondition can only be *unprovable*, never *violated*.
  it should "carry the literal's size into the Int domain precisely enough to reject" in {
    compileForErrors(crossDomain + """def main uses Console: Unit = printLine(show(small(length("hello world"))))""")
      .asserting(_ should include("precondition of 'Test::small' is not satisfied"))
  }

  // The re-encode is derived from the node's own meta, so a result the channel could not pin stays at the bignum
  // boundary and nothing is emitted at all — the ⊤ case. Finding one now takes a *higher-order* call: since the meta
  // interpretation landed (`docs/total-meta-transfers.md` §P4), an ordinary bodied callee no longer leaves ⊤ behind —
  // it derives its result from its own body, which is what the earlier version of this case (`def twice(x: Int): Int =
  // x + x`, called at a literal) now exercises instead. A lambda still carries no meta of its own, so `applyTo`'s
  // result is genuinely unknown and its call emits nothing.
  "a call whose result the channel cannot pin" should "leave its result at the boundary width" in {
    compileAndRun(
      """|import eliot.effect.Console
         |def applyTo(f: Function[Int, Int], x: Int): Int = f(x)
         |def main uses Console: Unit = printLine(show(applyTo(y -> y + y, 3)))""".stripMargin
    ).asserting(_ shouldBe "6") >>
      instructionsFollowing("Test.applyTo", 1).asserting(_ shouldBe Seq("INVOKEVIRTUAL java/math/BigInteger.toString"))
  }
}
