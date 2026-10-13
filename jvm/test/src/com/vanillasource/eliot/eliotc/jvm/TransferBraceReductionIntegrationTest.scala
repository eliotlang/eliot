package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof that a **stated** transfer brace reduces through the refinement channel at the call site — the
  * payoff of the transfer-reduction linker fix and the resolution of its "5b" inline-brace diagnosis (both recorded in
  * `docs/refinement-channel-follow-ups.md` §1). A brace desugars to a `^Meta` companion whose body is ordinary
  * compiler-track Eliot, so reducing it has to go through ability calls, generic constructor helpers and literal
  * endpoints; the escalating executor links only monomorphized callees, so an ability reference in a brace resolves
  * instead of sticking at ⊤ and yielding a wide result.
  *
  * Each case pairs an accepting and a rejecting program against the same `where` precondition, and the pair is the
  * point: accepting alone would also pass if the transfer widened to ⊤ and the precondition were discharged elsewhere,
  * rejecting alone would also pass if the meta were simply unknown — which is a different diagnostic ("Cannot prove" is
  * an absent bound, "not satisfied" a violated one), pinned separately below.
  *
  * **Why these programs no longer state their own braces.** This suite used to put the brace on an ordinary bodied test
  * def (`def f(a: Int, b: Int): Int {range(a) + range(b)} = a + b`). **R3** (`docs/total-meta-transfers.md` §3/§P3)
  * outlaws that shape: a bodied value *derives* its meta from its body, so only a native leaf may state one — and a
  * user module declares no natives. Each shape is therefore exercised through the layer brace that actually ships it
  * (`Numeric[Int]::add`, `Runtime::integerLiteral`, `Show[Int]::show`), and the last case pins R3 itself. Nothing is
  * lost by the move: the caveat the old suite carried — that lowering a brace-narrowed return across a *user* def's
  * call boundary is a deferred backend representation matter (`docs/refinement-channel-follow-ups.md` §4), since the
  * callee's body is compiled once with ⊤ parameters — was exactly a symptom of the shape R3 removes.
  *
  * The arguments are **direct calls** (`add(60, 60)`) rather than infix `60 + 60`: an infix spine reaches the channel
  * through a position it drops to ⊤, which is the intra-procedural limit `docs/string-length-meta.md` §9 already
  * describes and is not what these cases are about.
  */
class TransferBraceReductionIntegrationTest extends FullIntegrationTest {
  // `withinByte` is a test-local predicate (it deliberately lives only where a test needs it, not in any layer).
  private val useByte =
    """|import eliot.effect.Console
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def useByte(x: Int): Int where withinByte(range(x)) = x
       |""".stripMargin

  private def accepts(expression: String, output: String) =
    compileAndRun(useByte + s"def main uses Console: Unit = printLine(show(useByte($expression)))").asserting(_ shouldBe output)

  private def rejects(expression: String) =
    compileForErrors(useByte + s"def main uses Console: Unit = printLine(show(useByte($expression)))")
      .asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))

  // `Numeric[Int]::add` states `{range(a) + range(b)}`, whose `+` is an *ability* call (`Numeric[Interval]`), not a
  // native. Before the linker fix the channel spliced raw callee bodies into its post-mono reduction, so that reference
  // stuck and the transfer yielded ⊤; the escalating executor now resolves it through the instance's own
  // monomorphization instead of sticking on the abstract ability method.
  "an ability call in a transfer brace" should "sum the operand ranges" in {
    accepts("add(60, 60)", "120")
  }

  it should "narrow an out-of-range sum so the where precondition is rejected" in {
    rejects("add(100, 100)")
  }

  it should "stay ⊤ for an unknown operand rather than bogus-narrow (soundness)" in {
    compileForErrors(
      useByte + "def relay(y: Int): Int = useByte(add(y, y))\ndef main uses Console: Unit = printLine(show(relay(10)))"
    ).asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
  }

  // A brace may call an ordinary generic helper — including one whose body applies a data constructor to its own
  // parameter (`closed[T](s, e) = Interval(Bounded(s), Bounded(e))`), which is exactly what `Runtime::integerLiteral[V]
  // : Int {closed(V, V)}` does at every integer literal. Such a helper reifies no binder, so `BindingClosure` wraps its
  // body in no type-argument lambda; the checker's own body reduction nevertheless writes the solved type argument, and
  // applying it used to land `BigInteger` in `s`'s slot and shift every value argument one left — reducing a well-typed
  // brace to an ill-typed range with nothing downstream in a position to notice (`SemValue.VTopDef.typeBinders`). These
  // two pin both directions of the reduced range, so an endpoint carrying merely *some* value fails here.
  "a generic constructor helper in a transfer brace" should "build the range the helper states" in {
    accepts("100", "100")
  }

  it should "carry the literal's actual value, not merely some endpoint" in {
    rejects("200")
  }

  // A negative endpoint has no literal syntax (`-` is always an operator), so a brace writes it as a subtraction, which
  // the compile-time `Numeric[BigInteger]` reduces — the shape `String::indexOfInternal` states for its not-found
  // sentinel. Here the *runtime* `subtract` leaf's own brace (`{range(a) - range(b)}`) produces the same `[-1, -1]`,
  // which must not fit a byte.
  it should "reduce a subtraction into a negative endpoint" in {
    rejects("subtract(0, 1)")
  }

  // `Show[Int]::show` states `{atLeast(1)}` — an interval open at its upper end, because the ceiling is a digit count
  // no arithmetic leaf can compute (`docs/total-meta-transfers.md` decision 8). An open end must survive the reduction
  // as an open end: no fixed-width precondition can be discharged by a bound that does not exist, however tight the
  // other side is. This is also the one case with no accepting counterpart — that is what half-open means.
  "a half-open endpoint" should "stay half-open, so a fixed-width precondition is rejected" in {
    compileForErrors(
      """|import eliot.effect.Console
         |def fitsDisplay(i: Interval[BigInteger]): Bool = rangeWithin[0, 8](i)
         |def banner(text: String): String where fitsDisplay(size(text)) = text
         |def main uses Console: Unit = printLine(banner(show(5)))""".stripMargin
    ).asserting(_ should include("precondition of 'Test::banner' is not satisfied"))
  }

  // `.` is `below apply`, so it shares `apply`'s precedence island and binds tighter than the floating `+`; being
  // subject-last, `x.range` ≡ `range(x)`, so a dotted meta expression reads exactly as the dotless one and reduces
  // identically — where before it aborted at precedence resolution with "no defined relative precedence". A `where`
  // predicate is the surviving user-authored meta companion (R3 forbids a user *transfer*, never a `where`, which is a
  // stated contract by design), and it is the same `^Meta`-namespace compiler-track code, so it pins the same rule.
  private def doubled(argument: String) =
    """|import eliot.effect.Console
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def doubleFitsByte(x: Int): Int where withinByte(x.range + x.range) = x
       |""".stripMargin + s"def main uses Console: Unit = printLine(show(doubleFitsByte($argument)))"

  "a dotted `x.range + x.range` meta expression" should "reduce via subject-last `.` binding tighter than `+`" in {
    compileAndRun(doubled("60")).asserting(_ shouldBe "60")
  }

  it should "reject when the doubled range leaves the bound" in {
    compileForErrors(doubled("70"))
      .asserting(_ should include("precondition of 'Test::doubleFitsByte' is not satisfied"))
  }

  // R3 itself (`docs/total-meta-transfers.md` §3/§P3), and the reason every case above goes through a layer brace: a
  // value with an Eliot body derives its meta-information from that body, so a brace on it is a stated summary the
  // compiler would silently prefer to the code — the one shape in which the channel can be *wrong* rather than merely
  // imprecise, since `RefinementChannelProcessor.metaViaCompanion` reads a companion wherever one exists.
  "a transfer brace on a bodied value" should "be rejected, naming the fix" in {
    compileForErrors(
      useByte + "def f(a: Int, b: Int): Int {range(a) + range(b)} = add(a, b)\n" +
        "def main uses Console: Unit = printLine(show(useByte(f(100, 100))))"
    ).asserting(_ should include("remove the { ... } return brace"))
  }
}
