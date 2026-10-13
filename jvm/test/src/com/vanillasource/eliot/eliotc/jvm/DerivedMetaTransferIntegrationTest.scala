package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the **meta interpretation** (`docs/total-meta-transfers.md` §6.2/P4): a value with a body does
  * not *state* what it does to meta-information — R3 forbids it — it **derives** it, by having its body interpreted
  * under the caller's argument metas. Before this, a call to a bodied callee was a ⊤ boundary, so precision was a
  * property of *where code sits relative to a native* rather than of what the code *is*: `def label(s: String): String
  * = s` lost its argument's size at the call, and every helper a program factored out lost the bound it preserved.
  *
  * Each case is an accept/reject **pair** against the same `where` precondition, and the pair is the point: accepting
  * alone would also pass if the derivation widened to ⊤ and the precondition were discharged elsewhere, rejecting
  * alone would also pass if the meta were merely unknown — which is a *different* diagnostic ("Cannot prove" is an
  * absent bound, "not satisfied" a violated one). The accepting half additionally asserts the program's **output**,
  * which is what pins that the derived bound is not merely believed but true: the backend narrows the call node's
  * representation from it, so a bound that is too tight truncates the printed value rather than being caught
  * (`docs/string-length-meta.md` §8).
  *
  * The last two cases pin the boundaries this stage deliberately keeps: a **higher-order** call is still ⊤ (a lambda
  * carries no meta of its own — §5's table, which additionally waits on the `List` size domain), and a callee's own
  * `where` is still demanded at its *definition* over ⊤ parameters rather than re-demanded per use site (§P5).
  */
class DerivedMetaTransferIntegrationTest extends FullIntegrationTest {
  // `withinByte`/`useByte` are test-local, exactly as in [[TransferBraceReductionIntegrationTest]]: the precondition is
  // the observable, and `add` is called directly because an infix spine reaches the channel through a position it
  // drops to ⊤ (`docs/string-length-meta.md` §9).
  private val useByte =
    """|import eliot.effect.Console
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def useByte(x: Int): Int where withinByte(range(x)) = x
       |""".stripMargin

  private def accepts(definitions: String, expression: String, output: String) =
    compileAndRun(useByte + definitions + s"def main uses Console: Unit = printLine(show(useByte($expression)))")
      .asserting(_ shouldBe output)

  private def rejects(definitions: String, expression: String) =
    compileForErrors(useByte + definitions + s"def main uses Console: Unit = printLine(show(useByte($expression)))")
      .asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))

  private val double = "def double(x: Int): Int = add(x, x)\n"

  "a bodied def" should "derive its result meta from its body under the argument's meta" in {
    accepts(double, "double(60)", "120")
  }

  it should "derive a range that violates the precondition, rather than widening to ⊤" in {
    rejects(double, "double(100)")
  }

  // Derivation recurses: `quadruple`'s body calls `double`, whose own body is interpreted in turn. This is the
  // induction of §2 — every call has a meta because its callee's body does — walked over a DAG the recursion gate
  // guarantees is acyclic.
  private val quadruple = double + "def quadruple(x: Int): Int = double(double(x))\n"

  "a chain of bodied defs" should "derive through every hop" in {
    accepts(quadruple, "quadruple(30)", "120")
  }

  it should "keep the derived range exact enough to reject at the last hop" in {
    rejects(quadruple, "quadruple(33)")
  }

  // A nullary def is a saturated call spelled without parentheses, so a bare reference derives on the same terms.
  private val limit = "def limit: Int = add(60, 60)\n"

  "a bare reference to a nullary bodied def" should "derive its body's meta" in {
    accepts(limit, "limit", "120")
  }

  it should "reject when the nullary def's own body exceeds the bound" in {
    rejects("def limit: Int = add(100, 100)\n", "limit")
  }

  // A pure `val` block lowers to `(x -> rest)(e)`, so a block's bindings are ordinary parameter bindings to the
  // interpretation and carry their metas into the rest of the block.
  private val scaled =
    """|def scaled(x: Int): Int = {
       |  val doubled = add(x, x)
       |  add(doubled, doubled)
       |}
       |""".stripMargin

  "a val block in a derived body" should "carry each binding's meta into the rest of the block" in {
    accepts(scaled, "scaled(30)", "120")
  }

  it should "reject when the block's result exceeds the bound" in {
    rejects(scaled, "scaled(33)")
  }

  // The `String` domain derives through exactly the same walk — nothing in the interpretation is `Int`-shaped.
  private val display =
    """|def fitsDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 4](i)
       |def display(text: String): String where fitsDisplay(size(text)) = text
       |def label(s: String): String = s
       |""".stripMargin

  "a bodied def over strings" should "derive its argument's size through the call" in {
    compileAndRun(
      "import eliot.effect.Console\n" + display +
        """def main uses Console: Unit = printLine(display(label("abcd")))"""
    ).asserting(_ shouldBe "abcd")
  }

  it should "reject a derived size that is too large" in {
    compileForErrors(
      "import eliot.effect.Console\n" + display +
        """def main uses Console: Unit = printLine(display(label("abcde")))"""
    ).asserting(_ should include("precondition of 'Test::display' is not satisfied"))
  }

  // The first-order limit, stated as a test so that closing it later is a visible change: a lambda is a function value
  // and carries no meta, so a callee applying one of its own parameters derives nothing. "Cannot prove" — an absent
  // bound — is the right diagnostic, and it is *not* "not satisfied": the interpretation declined, it did not widen.
  "a higher-order call" should "stay ⊤ rather than derive through a lambda argument" in {
    compileForErrors(
      useByte + "def applyTo(f: Function[Int, Int], x: Int): Int = f(x)\n" +
        "def main uses Console: Unit = printLine(show(useByte(applyTo(y -> add(y, 1), 10))))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  // A callee's own `where` is demanded once, at its definition, over ⊤ parameters — the interpretation computes metas
  // but demands no precondition, so it neither repeats nor relaxes that check. Re-demanding it per use site with the
  // caller's metas is §P5, and until then this program is rejected even though the use site would satisfy it.
  "a where precondition inside a bodied def" should "still be demanded at the definition over ⊤ parameters" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\n" +
        "def main uses Console: Unit = printLine(show(relay(10)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }
}
