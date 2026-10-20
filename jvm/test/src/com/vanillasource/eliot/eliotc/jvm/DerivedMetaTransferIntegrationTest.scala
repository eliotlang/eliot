package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the **meta interpretation** (`docs/meta.md` §5): a value with a body does
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
  * (`docs/meta.md` §7).
  *
  * A function value is a **closure** over the metas in scope where it was written, interpreted when something applies
  * it, and a stated transfer over code parameters reads each code argument as what it yields (§5.2) — `fold`'s, and a
  * data type's `match` eliminator's, which states the join of its handlers. What stays ⊤ is a field read back out of a
  * data value (structural meta) and a demand inside a lambda body, whose binder only the callee applying it knows. A
  * callee's own `where` is demanded at what each use site supplies (§6), never over ⊤ parameters.
  */
class DerivedMetaTransferIntegrationTest extends FullIntegrationTest {
  // `withinByte`/`useByte` are test-local, exactly as in [[TransferBraceReductionIntegrationTest]]: the precondition is
  // the observable. `add` is called directly to keep each case about one derivation, not because an infix spine loses
  // anything: since closures over metas (S10) it derives too.
  private val useByte =
    """|import eliot.effect.Console
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def useByte(x: Int) where withinByte(range(x)): Int = x
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
  // induction of §1 — every call has a meta because its callee's body does — walked over a DAG the recursion gate
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
       |def display(text: String) where fitsDisplay(size(text)): String = text
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

  // A lambda argument is a closure, interpreted where the callee applies it.
  private val applyTo = "def applyTo(f: Function[Int, Int], x: Int): Int = f(x)\n"

  "a higher-order call" should "derive through a lambda argument the callee applies" in {
    accepts(applyTo, "applyTo(y -> add(y, 1), 10)", "11")
  }

  it should "reject a derived range the lambda makes too large" in {
    rejects(applyTo, "applyTo(y -> add(y, 100), 100)")
  }

  it should "read what a lambda captures from where it was written" in {
    accepts(applyTo + "def shift(k: Int): Int = applyTo(y -> add(y, k), 10)\n", "shift(5)", "15")
  }

  it should "derive through a def passed as a value" in {
    accepts(applyTo + double, "applyTo(double, 10)", "20")
  }

  it should "derive through a lambda a callee returns and its caller applies" in {
    accepts(
      "def compose(f: Function[Int, Int], g: Function[Int, Int]): Function[Int, Int] = a -> f(g(a))\n",
      "compose(y -> add(y, 1), z -> add(z, 2))(10)",
      "13"
    )
  }

  // `fold` states `{join(whenTrue, whenFalse)}` over its code parameters, so each arm reaches the transfer as what its
  // thunk yields; the run pins that the backend re-encodes each arm to the merged width.
  "a stated transfer over code parameters" should "read each code argument as what it yields" in {
    accepts("", "fold(false, 10, 20)", "20")
  }

  it should "reject when one arm's yield is too large" in {
    rejects("", "fold(true, 10, 200)")
  }

  // A data type's eliminator states the join of its handlers, exactly as `fold` does, so a `match` is the join of what
  // its arms yield.
  private val pick = "data AB = A | B\ndef pick(x: AB): Int = x match { case A -> 10 case B -> 20 }\n"

  "a match" should "derive the join of what its arms yield" in {
    accepts(pick, "pick(B)", "20")
  }

  it should "reject when one arm's yield is too large" in {
    rejects("data AB = A | B\ndef pick(x: AB): Int = x match { case A -> 10 case B -> 200 }\n", "pick(A)")
  }

  // The gaps this stage keeps, stated as tests so that closing one is a visible change. "Cannot prove" — an absent
  // bound — is the right diagnostic for each, and it is *not* "not satisfied": the interpretation declined.
  "a field read in a match arm" should "stay ⊤, since a data value carries no meta of its fields" in {
    compileForErrors(
      useByte + "def orZero(o: Option[Int]): Int = o match { case Some(x) -> x case None -> 0 }\n" +
        "def main uses Console: Unit = printLine(show(useByte(orZero(Some(7)))))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  "a demand inside a lambda body" should "stay unknown, since only the callee applying it binds its binder" in {
    compileForErrors(
      useByte + applyTo + "def main uses Console: Unit = printLine(show(applyTo(y -> useByte(y), 10)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  // A `where` inside a bodied def is demanded at what each use site supplies (§6), with the meta the interpretation
  // derives for it there: `relay(10)` fits, `relay(1000)` does not.
  "a where precondition inside a bodied def" should "be demanded at what the use site supplies" in {
    compileAndRun(
      useByte + "def relay(y: Int): Int = useByte(y)\n" +
        "def main uses Console: Unit = printLine(show(relay(10)))"
    ).asserting(_ shouldBe "10")
  }

  it should "be rejected where the use site supplies a meta that does not fit" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(y)\n" +
        "def main uses Console: Unit = printLine(show(relay(1000)))"
    ).asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))
  }
}
