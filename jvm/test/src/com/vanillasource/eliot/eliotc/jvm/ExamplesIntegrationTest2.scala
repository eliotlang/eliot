package com.vanillasource.eliot.eliotc.jvm

/** Part 2 of 4 of the end-to-end example-program suite (see [[FullIntegrationTest]]). The 88 cases are split across
  * four classes so Mill runs them in four parallel test-worker JVMs — each warming its own resident compilation session
  * — instead of one worker compiling all 88 serially. Keep each part self-contained: a case that shares a class-level
  * helper (e.g. `orderingPrelude`) must stay in the same part as that helper.
  */
class ExamplesIntegrationTest2 extends FullIntegrationTest {

  // Coherence: two implementations of an ability for the same type overlap and are rejected (at most one instance per
  // type combination), via the ordinary ability overlap check.
  "two overlapping ability implementations" should "be rejected as overlapping" in {
    // A generic `Display[A]` and a specific `Display[Database]` are distinct implementations (different `(ability, pattern)`
    // identities) whose patterns unify, so the definition-time overlap lint rejects the pair. (Two *identical*
    // `Display[Database]` would instead be the same identity and collide as a duplicate name.)
    compileForErrors(
      """import eliot.effect.Console
        |
        |ability Display[A] {
        |   def display(a: A): String
        |}
        |
        |data Database(url: String)
        |
        |implement[A] Display[A] { def display(a: A): String = "one" }
        |implement Display[Database] { def display(d: Database): String = "two" }
        |
        |def useDb: String = display(Database("x"))
        |
        |def main uses Console: Unit = printLine(useDb)""".stripMargin
    ).asserting(_ should include("Overlapping ability implementation"))
  }

  // --- Structural-effect discharge: Abort -> Option ---

  // A completed `{Abort}` computation discharges, via `runAbort`, to `Some` — the `Option` is born only here, at the
  // discharge edge, not in the `{Abort} String` signature. Under effects v6 the discharge yields the `Option` itself
  // (a frame is installed and left), so the result is an ordinary value rather than a computation to bind.
  "the Abort effect" should "discharge a completed computation to Some via runAbort" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def safe uses Abort: String = if(true, "config-value")
        |
        |def main uses Console: Unit = printLine(foldOption("<absent>", s -> s, runAbort(safe)))""".stripMargin
    ).asserting(_ shouldBe "config-value")
  }

  // A short-circuiting `{Abort}` computation discharges to `None`: `abort` leaves through the frame `runAbort`
  // installed, and the discharge reflects that as the empty case.
  it should "discharge an aborted computation to None via runAbort" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def giveUp uses Abort: String = abort
        |
        |def main uses Console: Unit = printLine(foldOption("gave up!", s -> s, runAbort(giveUp)))""".stripMargin
    ).asserting(_ shouldBe "gave up!")
  }

  // A program declaring two effects at once: the print runs, then `abort` short-circuits the result to `None`. Two
  // effects in one row are two independent bindings under v6 — there is no stack to lift `Console` through.
  "a {Console, Abort} program" should "print, then short-circuit" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def andThen[A](first: Unit, second: A): A = second
        |
        |def loud uses Console, Abort: String = andThen(printLine("trying"), abort)
        |
        |def main uses Console: Unit = printLine(foldOption("stopped", s -> s, runAbort(loud)))""".stripMargin
    ).asserting(_ shouldBe "trying\nstopped")
  }

  // `Throw[E]` is the typed-error sibling of `Abort`, discharging to `Either[E, _]` — the same structural discharge at
  // a parameterised effect and a two-constructor result.
  "the Throw effect" should "discharge a completed computation to Right via runThrow" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def parseOk uses Throw[String]: String = fold(true, "parsed-value", raise("unparseable"))
        |
        |def main uses Console: Unit = printLine(foldEither(err -> err, v -> v, runThrow(parseOk)))""".stripMargin
    ).asserting(_ shouldBe "parsed-value")
  }

  it should "discharge a failed computation to Left, carrying the typed error" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def parseBad uses Throw[String]: String = raise("malformed input")
        |
        |def main uses Console: Unit = printLine(foldEither(err -> err, v -> v, runThrow(parseBad)))""".stripMargin
    ).asserting(_ shouldBe "malformed input")
  }

  // The everyday discharge: a SINGLE `import eliot.effect.Throw` brings in `raise` AND the `catch` utility, which
  // discharges `{Throw[E]}` and recovers a raised error to a value of the same type — no `Either`/`ThrowCarrier`/`Effect`
  // import, no transformer named. Written infix with a parenthesized lambda operand (`p catch (e -> …)`), which the
  // adjacency-sensitive call parser keeps separate from a call.
  it should "discharge-and-recover in one step via a single import and infix catch" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def parseOk uses Throw[String]: String = fold(true, "parsed-value", raise("unparseable"))
        |def parseBad uses Throw[String]: String = raise("malformed input")
        |
        |def main uses Console: Unit = {
        |   printLine(parseOk catch (err -> err))
        |   printLine(parseBad catch (err -> err))
        |}""".stripMargin
    ).asserting(_ shouldBe "parsed-value\nmalformed input")
  }

  // Row-argument type-pinning (docs/effects-as-channel.md §10 U4-f, pinned finding 7): a `catch` whose handler does NOT
  // itself pin the error type — a non-identity `err -> "default"` (identity handlers `err -> err` masked the bug by
  // pinning `E := A` through the handler). The open-row argument `parseBad : {Throw[String]}` captured into `catch`'s
  // pinned `{Throw[E] | G} A` parameter solved `?F := ThrowCarrier[?E, G]` structurally but left the error slot `?E`
  // disconnected from the constraint's `String`; it junk-grounded to `Type`, selecting the `where E1 != E2` lift whose
  // inner `raise` demanded the nonexistent `Throw[String, Id]`. The row-directed pin (`?E := String`) resolves the
  // native carrier instance instead, in both the pure-boundary `recovered` (the shape that also un-breaks the stdlib doc
  // idiom) and the ambient position.
  it should "discharge with a non-identity handler (row-argument type-pinning, finding 7)" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def parseOk uses Throw[String]: String = fold(true, "ok-value", raise("unparseable"))
        |def parseBad uses Throw[String]: String = raise("boom")
        |
        |def recovered: String = parseBad catch (err -> "recovered-default")
        |
        |def main uses Console: Unit = {
        |   printLine(recovered)
        |   printLine(parseOk catch (err -> "unused"))
        |   printLine(parseBad catch (err -> "ambient-default"))
        |}""".stripMargin
    ).asserting(_ shouldBe "recovered-default\nok-value\nambient-default")
  }

  // Effectful `catch` handler (docs/effects-as-channel.md §7): `catch`'s handler is `onError: E => G[A]`, so the
  // recovery may itself perform effects on the same carrier — `catch (err -> printLine(err))` logs the failure and
  // recovers to Unit. The row-directed-at-elaboration pin (finding 13 §4) keeps the discharged computation's error
  // slot from junk-grounding, and the single-node `pureLift` at the return boundary keeps a *pure* handler body from
  // double-wrapping into a `ClassCastException` — so both effectful and pure handlers compose.
  "an effectful catch handler" should "run its own effect while recovering the raised value" in {
    // A pure handler (`err -> err`, recovering to a value) and an effectful handler (`err -> printLine(err)`, logging on
    // the same `{Console}` carrier) compose in one block. `failUnit` succeeds with `Unit`, so its handler's `printLine`
    // (also `Unit`) matches the success type `A` — the handler's `G[A]` codomain is what makes the effect legal there.
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def parseOk uses Throw[String]: String = fold(true, "parsed-value", raise("unparseable"))
        |def failUnit uses Throw[String]: Unit = raise("boom")
        |
        |def main uses Console: Unit = {
        |   printLine(parseOk catch (err -> err))
        |   failUnit catch (err -> printLine(err))
        |}""".stripMargin
    ).asserting(_ shouldBe "parsed-value\nboom")
  }

  // Ability-implementation guards, the Throw client (ability-guards Stage 4): TWO distinct error types in one effect
  // row force the `ThrowCarrier` to nest, which needs both the native `Throw[E, ThrowCarrier[E, G]]` instance and the
  // lift `Throw[E2, ThrowCarrier[E1, G]] where E1 != E2`. Those structurally overlap on the diagonal `E1 = E2`; the
  // `where E1 != E2` guard (discharged at the concrete use site by reducing `Eq[Type]`) makes them disjoint, so the
  // lift routes the foreign error inward while the native owns its own slot. Here `fetch` raises first, so the
  // outer `NetError` catch recovers to its reason. This is the `examples/src/EffectsTwoThrows.els` probe end to end.
  "two distinct Throw error types in one row" should "compile via the guarded self-lift and catch each by its type" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |data NetError(netReason: String)
        |data ParseError(parseReason: String)
        |
        |def fetch(url: String) uses Throw[NetError]: String = raise(NetError("http 503"))
        |def parse(raw: String) uses Throw[ParseError]: String = raise(ParseError("unexpected token"))
        |
        |def loadConfig(url: String) uses Throw[NetError], Throw[ParseError]: String = parse(fetch(url))
        |
        |def main uses Console: Unit =
        |   printLine(loadConfig("https://cfg") catch ((netErr: NetError) -> netErr.netReason) catch ((parseErr: ParseError) -> parseErr.parseReason))""".stripMargin
    ).asserting(_ shouldBe "http 503")
  }

  it should "recover the inner error type when the outer computation succeeds" in {
    // `fetch` succeeds, `parse` raises: the residual row after the `NetError` catch is `{Throw[ParseError]}`, recovered
    // by the second catch — exercising the lift routing a *foreign* error inward (off the diagonal) and then its own
    // native discharge.
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |data NetError(netReason: String)
        |data ParseError(parseReason: String)
        |
        |def fetch(url: String) uses Throw[NetError]: String = fold(true, url, raise(NetError("unreachable")))
        |def parse(raw: String) uses Throw[ParseError]: String = raise(ParseError("unexpected token"))
        |
        |def loadConfig(url: String) uses Throw[NetError], Throw[ParseError]: String = parse(fetch(url))
        |
        |def main uses Console: Unit =
        |   printLine(loadConfig("https://cfg") catch ((netErr: NetError) -> netErr.netReason) catch ((parseErr: ParseError) -> parseErr.parseReason))""".stripMargin
    ).asserting(_ shouldBe "unexpected token")
  }

  // The degenerate diagonal: the *same* error type appears in both throwing functions, so the row collapses to one
  // `{Throw[String]}` on a single carrier. The guarded lift declines on the diagonal (`where String != String` = false),
  // so the native `Throw[String, ThrowCarrier[String, IO]]` wins deterministically — no "Multiple ability
  // implementations" ambiguity — and the single `catch` recovers the raised error.
  "two same-typed throws composed on one carrier" should "resolve to the native instance (the lift declines)" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def raiseFirst uses Throw[String]: String = raise("first failed")
        |def keepSecond(prev: String) uses Throw[String]: String = fold(true, prev, raise("second failed"))
        |
        |def combined uses Throw[String]: String = keepSecond(raiseFirst)
        |
        |def main uses Console: Unit = printLine(combined catch (err -> err))""".stripMargin
    ).asserting(_ shouldBe "first failed")
  }

  // The Abort analogue: a single `import eliot.effect.Abort` brings in `abort` AND the infix `else` utility, which
  // discharges `{Abort}` and supplies a fallback on short-circuit — no `Option`/`AbortCarrier` named.
  "the Abort effect's else" should "discharge-and-default in one step via a single import and infix else" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def safe uses Abort: String = if(true, "config-value")
        |def giveUp uses Abort: String = abort
        |
        |def main uses Console: Unit = {
        |   printLine(safe else "<fallback>")
        |   printLine(giveUp else "<fallback>")
        |}""".stripMargin
    ).asserting(_ shouldBe "config-value\n<fallback>")
  }

  // Regression for the carrier-depth mangling collision. `grade`'s `if..else if..else` chain monomorphizes `if` at the
  // nested carrier `AbortCarrier[AbortCarrier[IO]]`, while the plain `if(..) else ..` in `main` uses `AbortCarrier[IO]`.
  // Both once mangled to a single `if$AbortCarrier$String` method (head-only type-argument suffix), so the backend's
  // signature-dedup treated the two different bodies as byte-identical and dropped one; the surviving wrong-carrier body
  // then produced a value one nesting level off, crashing at runtime with a ClassCastException in the `runAbort`
  // accessor. Recursive type-argument mangling keeps the two depths distinct.
  "if..else used at two effect-carrier nesting depths" should "not collapse into one mangled JVM method" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def grade(s: String) uses Abort: String =
        |   if(s == "A", "excellent") else if(s == "B", "good")
        |
        |def main uses Console: Unit = {
        |   printLine(grade("A") else "?")
        |   printLine(if(true, "taken") else "skipped")
        |}""".stripMargin
    ).asserting(_ shouldBe "excellent\ntaken")
  }

  // `if`'s branch is a carrier value `F[T]`, so a branch may itself be effectful. Only the SELECTED branch's effect
  // runs — both branches are built, the unchosen one is never executed — matching an imperative `if`.
  "if..else with effectful branches" should "run only the selected branch's effect" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def main uses Console: Unit = {
        |   if(true, printLine("then")) else printLine("else")
        |   if(false, printLine("then")) else printLine("else")
        |}""".stripMargin
    ).asserting(_ shouldBe "then\nelse")
  }

  // Pure-boundary Id defaulting (the identity carrier): a fully-discharged `if..else` meets a PURE declared return
  // directly — the residual carrier defaults to the built-in `Id` (the lang layer's identity carrier) and the checker unwraps it with `runId`
  // (`EffectLifter.tryIdDefault`), so branching needs no carrier in the signature. Exercises the direct return, an
  // `else if` chain, a block `val` holding the discharged branch, and a genuinely runtime condition (from stdin).
  "if..else in a pure function" should "discharge to the Id carrier and unwrap automatically" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def sign(flag: Bool): String = if(flag, "+") else "-"
        |
        |def chain(a: Bool, b: Bool): String = if(a, "first") else if(b, "second") else "third"
        |
        |def line uses Console: String = readLine.orAbort else ""
        |
        |def viaBlock(flag: Bool): String = {
        |   val label = if(flag, "yes") else "no"
        |   label
        |}
        |
        |def main uses Console: Unit = {
        |   printLine(sign(true))
        |   printLine(sign(false))
        |   printLine(chain(false, true))
        |   val runtimeFlag = line == "y"
        |   printLine(sign(runtimeFlag))
        |   printLine(viaBlock(runtimeFlag))
        |}""".stripMargin,
      stdin = "y\n"
    ).asserting(_ shouldBe "+\n-\nsecond\n+\nyes")
  }

  // The other pure control effects discharge to a pure return the same way: `catch` fully discharges `{Throw}` and
  // `runStateToPair` fully discharges `{State}`, so both results land in bare pure types via the Id defaulting. No
  // `Suspend[Id]` instance exists, so a genuinely side-effecting body under a pure return still fails to resolve —
  // the defaulting can never smuggle real I/O.
  "catch and runStateToPair in a pure function" should "discharge to pure values via the Id carrier" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |import eliot.effect.State
        |
        |def parsed(raw: String) uses Throw[String]: String = raise("unparseable")
        |
        |def recovered: String = parsed("x") catch (err -> err)
        |
        |def counted: Pair[String, String] = runStateToPair("initial", state)
        |
        |def main uses Console: Unit = {
        |   printLine(recovered)
        |   printLine(counted.first)
        |}""".stripMargin
    ).asserting(_ shouldBe "unparseable\ninitial")
  }

  // An **effect row** means "a value or a computation" — the empty row is a legal row — so one position accepts both
  // a pure actual and an effectful one. Under v6 the row lowers the *slot* to a thunk, so what the declaration says is
  // "I decide when this runs", and the caller writes the same thing either way.
  "a parameter declared as an effect row" should "accept a pure and an effectful argument alike" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def echo[A](value uses *: A): A = value
        |
        |def main uses Console: Unit = {
        |   printLine(echo("hello"))
        |   printLine(echo(readLine.orAbort else ""))
        |}""".stripMargin,
      stdin = "typed\n"
    ).asserting(_ shouldBe "hello\ntyped")
  }

  // Static testability: the SAME `{Abort}` business logic a program runs is what a test runs, discharged to a plain
  // `Option` with no I/O anywhere. Under v6 there is no test *carrier* to substitute — `runAbort` installs a frame and
  // answers ordinary data — so the "pure test run" is just calling it from a pure function.
  "an {Abort} program" should "discharge to an Option in a pure function, with no I/O" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Abort
        |
        |def allowed uses Abort: String = if(true, "granted")
        |def denied uses Abort: String = abort
        |
        |def testAllowed: Option[String] = runAbort(allowed)
        |def testDenied: Option[String] = runAbort(denied)
        |
        |def main uses Console: Unit = {
        |   printLine(foldOption("DENIED", s -> s, testAllowed))
        |   printLine(foldOption("DENIED", s -> s, testDenied))
        |}""".stripMargin
    ).asserting(_ shouldBe "granted\nDENIED")
  }

  // A `{State[S]}` computation discharges to a `Pair[A, S]` (result + final state) via `runStateToPair`, and the
  // `Pair` is born only at that edge, not in the `{State[String]} String` signature. `swap` reads the state, installs
  // a new one and returns the previous value; a block is strict evaluation order, so it needs no combinator.
  "the State effect" should "thread state through a {State} computation and discharge to a Pair via runStateToPair" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |def swap(next: String) uses State[String]: String = {
        |   val old = state
        |   putState(next)
        |   old
        |}
        |
        |def prog: Pair[String, String] = runStateToPair("before", swap("after"))
        |
        |def main uses Console: Unit = {
        |   printLine(first(prog))
        |   printLine(second(prog))
        |}""".stripMargin
    ).asserting(_ shouldBe "before\nafter")
  }

  // The two projecting discharges: `runStateToValue` keeps only the result (dropping the final state) and
  // `runStateToFinalState` only the final state (dropping the result). `swap` returns the previous value and installs
  // `next`; from "before" the value is "before" and the final state is "after".
  "the projecting State discharges" should "keep only the value, or only the final state" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |def swap(next: String) uses State[String]: String = {
        |   val old = state
        |   putState(next)
        |   old
        |}
        |
        |def onlyValue: String = runStateToValue("before", swap("after"))
        |def onlyState: String = runStateToFinalState("before", swap("after"))
        |
        |def main uses Console: Unit = {
        |   printLine(onlyValue)
        |   printLine(onlyState)
        |}""".stripMargin
    ).asserting(_ shouldBe "before\nafter")
  }

  // Effect-accounting migration: a `{State}` computation discharged
  // via a DOT-chained `runStateToValue` inside a `{Console}`-declaring body compiles and runs. The pre-mono accounting
  // rejected this (a dot-chained discharger was not credited — "performs the effect 'State'"); the monomorphize-phase
  // residual check verifies exactly, so the discharged `State` (its ability method on the inner `StateCarrier`, not the
  // ambient carrier) is absent from `show`'s `{Console}` residual.
  "a dot-chained State discharge inside a {Console} body" should "compile and run, the State absent from the residual" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |def counter uses State[String]: String = {
        |  putState("done")
        |  state
        |}
        |
        |def show uses Console: Unit = printLine(runStateToValue("init", counter))
        |
        |def main uses Console: Unit = show""".stripMargin
    ).asserting(_ shouldBe "done")
  }

  // The derived `updateState(f)` = `putState(f(state))`: it reads the current state, applies `f`, and writes it back.
  // `flip` genuinely reads the current state (it matches on it), so from `Off` the final state is `On`.
  "the derived updateState" should "read the state, apply the function, and write the result back" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |data Toggle = Off | On
        |
        |def flip(t: Toggle): Toggle = t match {
        |   case Off -> On
        |   case On -> Off
        |}
        |
        |def describe(t: Toggle): String = t match {
        |   case Off -> "off"
        |   case On -> "on"
        |}
        |
        |def switch uses State[Toggle]: Unit = updateState(t -> flip(t))
        |def result: Toggle = runStateToFinalState(Off, switch)
        |
        |def main uses Console: Unit = printLine(describe(result))""".stripMargin
    ).asserting(_ shouldBe "on")
  }

  // A COMPOUND state type `State[List[String]]`, kept because it is the shape a state slot most easily mis-resolves
  // at: an equal-arity data-constructor application at the state position once unified the carrier with `List` and
  // resolved the `State` ability at `[String, List]`. With no carrier to unify there is nothing to get wrong, and the
  // case stays as the regression it was.
  "the derived updateState over a compound List state" should "resolve the State ability at the whole list type" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |import eliot.collection.List
        |
        |def pushName(n: String) uses State[List[String]]: Unit =
        |   updateState(names -> append(names, n))
        |
        |def collectNames uses State[List[String]]: Unit = {
        |   pushName("ada")
        |   pushName("bob")
        |}
        |
        |def main uses Console: Unit =
        |   foreach(printLine, runStateToFinalState(empty, collectNames))""".stripMargin
    ).asserting(_ shouldBe "ada\nbob")
  }

  // A BLOCK of `{State}` statements must SEQUENCE, threading the state through every statement rather than keeping
  // only the last. It is the plainest thing a block has to do, and it once failed for a whole class of returns whose
  // carrier the lowering did not recognise; under v6 a block is strict evaluation order and there is no carrier to
  // recognise, so this stands as the behavioural guard it always was.
  "a block of State statements" should "sequence, threading the state through all of them" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |import eliot.collection.List
        |
        |def pushName(n: String) uses State[List[String]]: Unit =
        |   updateState(names -> append(names, n))
        |
        |def collectNames uses State[List[String]]: Unit = {
        |   pushName("ada")
        |   pushName("bob")
        |}
        |
        |def main uses Console: Unit =
        |   foreach(printLine, runStateToFinalState(empty, collectNames))""".stripMargin
    ).asserting(_ shouldBe "ada\nbob")
  }

  // A stored computation. Under v5 this was a *pinned row* (`{State[String] | Id} Unit`) — the one place a type could
  // contain a computation — and three cases pinned how one reached a `List` element, an alias body and a block
  // statement. A pinned row has no v6 meaning and is rejected at core: a computation is a **thunk**, an ordinary arrow
  // type, so storing one needs no spelling of its own. Nothing here replaces those three: what a stored computation
  // has to do is covered where it is actually used, by `eliot-test`'s `List[TestCase]`.

  // A `{State, Console}` program: both effects in one row, the print running while the state threads through and
  // discharges to a `Pair`. Two effects are two independent bindings under v6 — there is no stack for one to ride.
  "a {State, Console} program" should "print and thread the state in one program" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |def step uses State[String], Console: String = {
        |   printLine("running step")
        |   val old = state
        |   putState("done")
        |   old
        |}
        |
        |def main uses Console: Unit = {
        |   val p = runStateToPair("start", step)
        |   printLine(first(p))
        |   printLine(second(p))
        |}""".stripMargin
    ).asserting(_ shouldBe "running step\nstart\ndone")
  }

  // Ordering at the edge: ONE `{State[String], Abort}` program — install a new state, then `abort` — discharged in the
  // TWO possible orders, giving two genuinely different results and two different result *types*. The interaction
  // "does the abort roll back the state?" is left open by the flat effect set and decided only by the order the
  // dischargers install their frames: a cell installed outside an escape frame survives leaving it, one installed
  // inside goes with it.
  private val orderingPrelude =
    """import eliot.effect.State
      |import eliot.effect.Abort
      |
      |def reject(value: String) uses Abort: String = abort
      |
      |def modifyThenAbort uses State[String], Abort: String = {
      |   putState("modified")
      |   reject("modified")
      |}
      |
      |""".stripMargin

  "ordering at the discharge edge" should "let state survive an abort when State is discharged outermost" in {
    compileAndRun(
      orderingPrelude +
        """import eliot.effect.Console
          |def stateSurvives: Pair[Option[String], String] =
          |   runStateToPair("initial", runAbort(modifyThenAbort))
          |
          |def main uses Console: Unit = {
          |   printLine(foldOption("<no value>", s -> s, first(stateSurvives)))
          |   printLine(second(stateSurvives))
          |}""".stripMargin
    ).asserting(_ shouldBe "<no value>\nmodified")
  }

  it should "discard state on an abort when Abort is discharged outermost" in {
    compileAndRun(
      orderingPrelude +
        """import eliot.effect.Console
          |def stateDiscarded: Option[Pair[String, String]] =
          |   runAbort(runStateToPair("initial", modifyThenAbort))
          |
          |def main uses Console: Unit = printLine(foldOption("<no state>", p -> second(p), stateDiscarded))""".stripMargin
    ).asserting(_ shouldBe "<no state>")
  }

  // --- Block syntax: `val` bindings and statement sequencing ---

  // The headline: a multi-step body written as a block instead of a hand-nested flatMap. Each bare statement is a `val`
  // with a discarded binder, so the steps are sequenced through the carrier automatically, in order.
  "a block of statements" should "sequence effectful steps in order" in {
    compileAndRun(
      """import eliot.effect.Console
        |def main uses Console: Unit = {
        |  printLine("first")
        |  printLine("second")
        |  printLine("third")
        |}""".stripMargin
    ).asserting(_ shouldBe "first\nsecond\nthird")
  }

  // A `val` binds the *carried* result of an effectful step (here the discharged `readLine`), so the body sees the
  // plain value; the block lowers to `flatMap(line -> printLine(line), <the read>)`.
  "a val binding an effectful result" should "bind the carried value and use it" in {
    compileAndRun(
      """import eliot.effect.Console
        |def echo uses Console: Unit = {
        |  val line = readLine.orAbort else ""
        |  printLine(line)
        |}
        |
        |def main uses Console: Unit = echo""".stripMargin,
      stdin = "typed line\n"
    ).asserting(_ shouldBe "typed line")
  }

  // A non-effectful `val` binds a plain value (no carrier), used twice in the body. This is the immediately-applied
  // lambda `let` form: the block lowers to `(msg -> …)(greeting)`, with `greeting` a pure value, not a carried action.
  "a non-effectful val binding" should "bind a plain value usable multiple times" in {
    compileAndRun(
      """import eliot.effect.Console
        |def greeting: String = "Hi"
        |
        |def main uses Console: Unit = {
        |  val msg = greeting
        |  printLine(msg)
        |  printLine(msg)
        |}""".stripMargin
    ).asserting(_ shouldBe "Hi\nHi")
  }

  // A pure `val` and an effectful `val` interleaved in one block: the pure binding inlines as a plain `let`, the
  // effectful one threads through `flatMap` — both in the same lowered tower.
  it should "interleave a pure binding with an effectful one" in {
    compileAndRun(
      """import eliot.effect.Console
        |def main uses Console: Unit = {
        |  val label = "echo:"
        |  val line = readLine.orAbort else ""
        |  printLine(label)
        |  printLine(line)
        |}""".stripMargin,
      stdin = "hello\n"
    ).asserting(_ shouldBe "echo:\nhello")
  }

  // The docs' headline: `swap` written as a block. `val old = state` binds the read; `putState(next)` is a bare
  // statement; `old` is the result expression — a block is strict evaluation order and nothing else.
  "a {State} computation in block form" should "thread state through its statements in order" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.State
        |
        |def swap(next: String) uses State[String]: String = {
        |  val old = state
        |  putState(next)
        |  old
        |}
        |
        |def main uses Console: Unit = {
        |   val p = runStateToPair("before", swap("after"))
        |   printLine(first(p))
        |   printLine(second(p))
        |}""".stripMargin
    ).asserting(_ shouldBe "before\nafter")
  }
  // A deferred Generic slot whose domain later rigidifies to a *generic data container* — the dot operator's own
  // `.[A, B](a: A, f: Function[A, B]): B`, whose `A` is bare when the effectful subject is checked and only becomes
  // `Either[?E, ?A]` once the function argument lands. The ladder's unify arm then decomposed `?F[T] ~ Either[?E, ?A]`
  // *successfully* — solving the ambient carrier meta to a partially applied data constructor (`?F := Either[?E]`) —
  // so bind-lift never ran and the whole chain came out typed `Either[String, String]` instead of `String`, while the
  // identical `foldEither(e -> e, s -> s, outcome)` compiled. A concrete `Either[String, String]` domain never showed
  // it (the same decomposition fails on the payload), which is why only the generic shape was broken. Phase B now
  // sequences before whole-unify against a rigid non-carrier domain (`Checker.sequenceBeforeUnify`).
  "a discharger dot-chained into a generic container fold" should "sequence the effect rather than steal the carrier" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def bad uses Throw[String]: String = raise("boom")
        |
        |def main uses Console: Unit = printLine(foldEither(e -> e, s -> s, runThrow(bad)))""".stripMargin
    ).asserting(_ shouldBe "boom")
  }

  it should "agree with the same call written subject-last" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def bad uses Throw[String]: String = raise("boom")
        |def outcome: Either[String, String] = runThrow(bad)
        |
        |def main uses Console: Unit = {
        |   printLine(outcome.foldEither(e -> e, s -> s))
        |   printLine(foldEither(e -> e, s -> s, outcome))
        |}""".stripMargin
    ).asserting(_ shouldBe "boom\nboom")
  }

  // The same shape with an effect riding alongside the discharged one: `Console` stays on the ambient carrier while
  // `Throw` is discharged, so the sequenced bind must run the print before folding the Either.
  it should "keep a co-riding effect running while the discharged one folds" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def noisy uses Console, Throw[String]: String = {
        |   printLine("working")
        |   raise("boom")
        |}
        |
        |def main uses Console: Unit = printLine(foldEither(e -> e, s -> s, runThrow(noisy)))""".stripMargin
    ).asserting(_ shouldBe "working\nboom")
  }
}
