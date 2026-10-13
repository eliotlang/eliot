package com.vanillasource.eliot.eliotc.jvm

/** The catch/Throw **shape matrix** (docs/effects-as-channel.md, finding 13 / step 2 of the mandated §7 sequence).
  *
  * A systematic grid over the four dimensions the effects-as-channel history found each recurrence hiding in:
  *
  *   - **structure**: a single-statement discharge vs. one inside a `{ … }` block
  *   - **carrier context**: the discharge sits under a pure (`Id`-defaulted) return, a concrete `IO` carrier, or an
  *     ambient effect-row (`{Console}`) meta carrier
  *   - **handler**: an identity handler (`err -> err`, which pins `E := A` through itself and so masked the finding-7
  *     bug), a non-identity handler (`err -> "fallback"`, which does NOT pin `E`), and an effectful handler
  *     (`err -> printLine(err)`, which itself performs an effect on the same carrier)
  *   - **dischargers**: one `catch`, or two chained `catch`es over two distinct error types (the guarded `where E1 !=
  *     E2` self-lift)
  *
  * This is the **net** that catches an insufficient capture-arm rewire *in the session that writes it* (§7 step 4).
  *
  * **Result (2026-07-25, post-U4-g):** the entire well-typed grid — Groups A–D below — **compiles and runs green**.
  * U4-f (row-argument type-pinning) and U4-g (the effectful-handler delta + row-directed pin at elaboration) closed the
  * residual seam for every *well-typed* catch shape, so there is no well-typed expected-fail to record. The finding-13
  * junk-ground seam that remains is (i) *ill-typed* over-discharge, mapped in Group E as a labelled boundary — NOT a §7
  * target — and (ii) the hypothetical mode where a guard would reduce over a junk-grounded operand, now netted loud by
  * the guard-on-junk fail-safe (`AbilityImplementationProcessor.dischargeGuard`, unit-tested in
  * `AbilityGuardDischargeTest`). So if the step-4 rewire ever junk-grounds a carrier slot again, it fails loud/located
  * here or at the guard, never silently.
  */
class CatchShapeMatrixTest extends FullIntegrationTest {

  // Shared throwing sources; `bad`/`ok` carry a `String` payload, `badUnit` a `Unit` payload (so an effectful handler
  // returning `Unit` type-matches). Kept identical across cases so only the discharge site varies.
  private val throwPrelude =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |
      |def ok uses Throw[String]: String = "ok-value"
      |def bad uses Throw[String]: String = raise("boom")
      |def badUnit uses Throw[String]: Unit = raise("boom")
      |
      |""".stripMargin

  private val twoThrowPrelude =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |
      |data NetError(netReason: String)
      |data ParseError(parseReason: String)
      |
      |def fetch(url: String) uses Throw[NetError]: String = raise(NetError("net-down"))
      |def parse(raw: String) uses Throw[ParseError]: String = raise(ParseError("parse-bad"))
      |def loadConfig(url: String) uses Throw[NetError], Throw[ParseError]: String = parse(fetch(url))
      |
      |""".stripMargin

  // ============================================================================================================
  // Group A — identity handler (err -> err): recovers the raised message unchanged. Masks finding-7 by pinning E := A.
  // ============================================================================================================

  "identity handler, single statement, pure Id carrier" should "recover the raised value" in {
    compileAndRun(throwPrelude + """
      |def r: String = bad catch (err -> err)
      |def main uses Console: Unit = printLine(r)""".stripMargin).asserting(_ shouldBe "boom")
  }

  "identity handler, single statement, ambient Console carrier" should "recover the raised value" in {
    compileAndRun(throwPrelude + """
      |def show uses Console: Unit = printLine(bad catch (err -> err))
      |def main uses Console: Unit = show""".stripMargin).asserting(_ shouldBe "boom")
  }

  "identity handler, single statement, concrete IO carrier" should "recover the raised value" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = printLine(bad catch (err -> err))""".stripMargin).asserting(_ shouldBe "boom")
  }

  "identity handler, block, pure Id carrier" should "recover the raised value" in {
    compileAndRun(throwPrelude + """
      |def r: String = {
      |   val note = "unused"
      |   bad catch (err -> err)
      |}
      |def main uses Console: Unit = printLine(r)""".stripMargin).asserting(_ shouldBe "boom")
  }

  "identity handler, block, ambient Console carrier" should "sequence then recover" in {
    compileAndRun(throwPrelude + """
      |def show uses Console: Unit = {
      |   printLine("pre")
      |   printLine(bad catch (err -> err))
      |}
      |def main uses Console: Unit = show""".stripMargin).asserting(_ shouldBe "pre\nboom")
  }

  "identity handler, block, concrete IO carrier" should "sequence then recover" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = {
      |   printLine("pre")
      |   printLine(bad catch (err -> err))
      |}""".stripMargin).asserting(_ shouldBe "pre\nboom")
  }

  // ============================================================================================================
  // Group B — non-identity handler (err -> "fallback"): does NOT pin E := A.
  //
  // Under effects v6 a parameter row lowers to a thunk, which erases the entry's own arguments from the type, so
  // `catch[E, A](computation: {Throw[E]} A, onError: E => {} A)` leaves `E` to the handler alone — and a handler
  // that ignores its error determines nothing. The write fills it from the actual's own declared row instead
  // (A6, `docs/effects.md` §3.1), which is what keeps this corner spelled the way a user writes it.
  // ============================================================================================================

  "non-identity handler, single statement, pure Id carrier" should "recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def r: String = bad catch (err -> "fallback")
      |def main uses Console: Unit = printLine(r)""".stripMargin).asserting(_ shouldBe "fallback")
  }

  "non-identity handler, single statement, ambient Console carrier" should "recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def show uses Console: Unit = printLine(bad catch (err -> "fallback"))
      |def main uses Console: Unit = show""".stripMargin).asserting(_ shouldBe "fallback")
  }

  "non-identity handler, single statement, concrete IO carrier" should "recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = printLine(bad catch (err -> "fallback"))""".stripMargin).asserting(_ shouldBe "fallback")
  }

  "non-identity handler, block, pure Id carrier" should "recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def r: String = {
      |   val note = "unused"
      |   bad catch (err -> "fallback")
      |}
      |def main uses Console: Unit = printLine(r)""".stripMargin).asserting(_ shouldBe "fallback")
  }

  "non-identity handler, block, ambient Console carrier" should "sequence then recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def show uses Console: Unit = {
      |   printLine("pre")
      |   printLine(bad catch (err -> "fallback"))
      |}
      |def main uses Console: Unit = show""".stripMargin).asserting(_ shouldBe "pre\nfallback")
  }

  "non-identity handler, block, concrete IO carrier" should "sequence then recover to the fallback" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = {
      |   printLine("pre")
      |   printLine(bad catch (err -> "fallback"))
      |}""".stripMargin).asserting(_ shouldBe "pre\nfallback")
  }

  // ============================================================================================================
  // Group C — effectful handler (err -> printLine(err)): the handler performs an effect on the same carrier.
  // Pure Id is excluded — printLine cannot run on Id (no Suspend[Id]).
  // ============================================================================================================

  "effectful handler, single statement, ambient Console carrier" should "run the handler's effect" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = badUnit catch (err -> printLine(err))""".stripMargin).asserting(_ shouldBe "boom")
  }

  "effectful handler, single statement, concrete IO carrier" should "run the handler's effect" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = badUnit catch (err -> printLine(err))""".stripMargin).asserting(_ shouldBe "boom")
  }

  "effectful handler, block, ambient Console carrier" should "sequence then run the handler's effect" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = {
      |   printLine("pre")
      |   badUnit catch (err -> printLine(err))
      |}""".stripMargin).asserting(_ shouldBe "pre\nboom")
  }

  "effectful handler, block, concrete IO carrier" should "sequence then run the handler's effect" in {
    compileAndRun(throwPrelude + """
      |def main uses Console: Unit = {
      |   printLine("pre")
      |   badUnit catch (err -> printLine(err))
      |}""".stripMargin).asserting(_ shouldBe "pre\nboom")
  }

  // ============================================================================================================
  // Group D — two dischargers over two distinct error types (the guarded `where E1 != E2` self-lift). fetch raises
  // NetError first, so the outer NetError catch recovers.
  // ============================================================================================================

  "two dischargers, single statement, concrete IO carrier" should "catch each error by its type" in {
    compileAndRun(twoThrowPrelude + """
      |def main uses Console: Unit =
      |   printLine(loadConfig("u") catch ((n: NetError) -> n.netReason) catch ((p: ParseError) -> p.parseReason))""".stripMargin)
      .asserting(_ shouldBe "net-down")
  }

  "two dischargers, single statement, pure Id carrier" should "catch each error by its type" in {
    compileAndRun(twoThrowPrelude + """
      |def r: String = loadConfig("u") catch ((n: NetError) -> n.netReason) catch ((p: ParseError) -> p.parseReason)
      |def main uses Console: Unit = printLine(r)""".stripMargin).asserting(_ shouldBe "net-down")
  }

  "two dischargers, single statement, ambient Console carrier" should "catch each error by its type" in {
    compileAndRun(twoThrowPrelude + """
      |def show uses Console: Unit =
      |   printLine(loadConfig("u") catch ((n: NetError) -> n.netReason) catch ((p: ParseError) -> p.parseReason))
      |def main uses Console: Unit = show""".stripMargin).asserting(_ shouldBe "net-down")
  }

  "two dischargers, block, concrete IO carrier" should "sequence then catch each error by its type" in {
    compileAndRun(twoThrowPrelude + """
      |def main uses Console: Unit = {
      |   printLine("pre")
      |   printLine(loadConfig("u") catch ((n: NetError) -> n.netReason) catch ((p: ParseError) -> p.parseReason))
      |}""".stripMargin).asserting(_ shouldBe "pre\nnet-down")
  }

  // ============================================================================================================
  // Group F — a `val` **runs** its computation.
  //
  // Under v5 a `val` bound the *reified* computation as data, so a discharger could reach it through the binder, and
  // that was recorded as a capability. Effects v6 reverses it: effects run where they are written, a `val` is an
  // ordinary binding of a value, and a discharger takes the call. The row therefore belongs to the *enclosing*
  // definition, which is what these two now pin — one where the binding's effect is declared and discharged around
  // the whole block, one where each call is discharged in place.
  // ============================================================================================================

  "a val-bound Throw computation" should "run where it is bound, its effect declared by the enclosing definition" in {
    compileAndRun(throwPrelude + """
      |def show uses Throw[String]: String = {
      |   val outcome = bad
      |   outcome
      |}
      |def main uses Console: Unit = printLine(show catch (err -> err))""".stripMargin).asserting(_ shouldBe "boom")
  }

  "a val discharged in place" should "take the fallback when it aborts and its value when it does not" in {
    compileAndRun("""def setting(key: String) uses Abort: String = if(key == "host", "example.org") else abort
      |
      |def main uses Console: Unit = {
      |   val host = setting("host") else "localhost"
      |   val port = setting("port") else "8080"
      |   printLine(host)
      |   printLine(port)
      |}""".stripMargin).asserting(_ shouldBe "example.org\n8080")
  }

  // ============================================================================================================
  // Group E — the ILL-TYPED boundary (NOT a §7 target). Two `catch`es of the SAME error type over a single
  // `{Throw[String]}` layer is an over-discharge: same-typed rows collapse to one carrier, so the second `catch` has
  // nothing left to discharge. These cases document the finding-13 identity-vs-non-identity ASYMMETRY at that boundary
  // — an identity handler pins `E := A` through itself and lets the over-discharge slip through (silently absorbed),
  // while a non-identity handler leaves the second layer's error slot free, so it junk-grounds and fails. §7's rewire
  // does not (and should not) make an over-discharge compile; these are here to pin the current behaviour and the
  // asymmetry, not as expected-fails to flip.
  // ============================================================================================================

  private val sameTwoPrelude =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |
      |def raiseFirst uses Throw[String]: String = raise("first")
      |def keepSecond(prev: String) uses Throw[String]: String = fold(true, prev, raise("second"))
      |def combined uses Throw[String]: String = keepSecond(raiseFirst)
      |
      |""".stripMargin

  "over-discharge with identity handlers" should "be absorbed (the second catch is a no-op on the recovered value)" in {
    // Both handlers are identity; the second catch's free error slot pins `E := A` through the identity handler, so the
    // program compiles and the already-recovered "first" flows through. A quirk of the current typing, documented here.
    compileAndRun(sameTwoPrelude + """
      |def main uses Console: Unit = printLine(combined catch (e1 -> e1) catch (e2 -> e2))""".stripMargin)
      .asserting(_ shouldBe "first")
  }

  "over-discharge with non-identity handlers" should "fail (the second catch's error slot junk-grounds)" in {
    // The same over-discharge with non-identity handlers: the second catch's error slot is not pinned by the handler,
    // junk-grounds, and the `Throw` machinery cannot resolve — a cryptic diagnostic today (mode #1: an abstract
    // carrier reference reaches codegen). Asserted only as "fails", not on the exact text, so it documents the
    // asymmetry without enshrining the current wording. §7 does not make this compile (it is genuinely over-discharge).
    compileForErrors(sameTwoPrelude + """
      |def main uses Console: Unit = printLine(combined catch (e1 -> "fb1") catch (e2 -> "fb2"))""".stripMargin)
      .asserting(_ should not be empty)
  }
}
