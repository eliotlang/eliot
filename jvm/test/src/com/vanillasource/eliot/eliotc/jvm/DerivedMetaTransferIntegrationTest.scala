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
  * data type's `match` eliminator's, which states the join of its handlers. A value of a `data` type carries its
  * fields' metas and which constructors can have built it (structural meta, §13), so a field read across a call is
  * the field, and an arm no constructor reaches does not run. What stays ⊤ is a demand inside a lambda body, whose
  * binder only the callee applying it knows. A callee's own `where` is demanded at what each use site supplies (§6),
  * never over ⊤ parameters.
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

  // A data type's eliminator states the join of its handlers, exactly as `fold` does, so a `match` on a value either
  // constructor may have built is the join of what its arms yield.
  private def pick(b: Int) = s"data AB = A | B\ndef pick(x: AB): Int = x match { case A -> 10 case B -> $b }\n"

  "a match" should "derive the join of what its arms yield" in {
    accepts(pick(20), "pick(fold(true, A, B))", "10")
  }

  it should "reject when one arm's yield is too large" in {
    rejects(pick(200), "pick(fold(true, A, B))")
  }

  // A value's meta says which constructors can have built it (`docs/meta.md` §13.1), so an arm none of them selects
  // is handed ⊥ and does not run: `pick(B)` is `[20, 20]` though its other arm yields 200.
  it should "run only the arms the constructors that can have built its value select" in {
    accepts(pick(20).replace("case A -> 10", "case A -> 200"), "pick(B)", "20")
  }

  // ⊥ (`docs/meta.md` §12): `abort` reaches `exitInternal`, which states `{never}`, so an arm that aborts adds nothing to
  // the join and `fold(c, 5, abort)` is `[5, 5]`. The fallback is printed when the arm aborts.
  private def aborting(body: String, main: String) =
    useByte + s"def g(c: Bool) uses Abort: Int = $body\n" + s"def main uses Console: Unit = printLine(show($main))"

  "an arm that does not return" should "add nothing to the join of the arms" in {
    compileAndRun(aborting("useByte(fold(c, 5, abort))", "g(true) else 0")).asserting(_ shouldBe "5")
  }

  it should "leave the other arm's meta to be checked" in {
    compileForErrors(aborting("useByte(fold(c, 200, abort))", "g(true) else 0"))
      .asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))
  }

  it should "add nothing through `if`, whose body is derived" in {
    compileAndRun(aborting("useByte(if(c, 5))", "g(false) else 7")).asserting(_ shouldBe "7")
  }

  it should "add nothing when it raises" in {
    compileAndRun(
      useByte + "def g(c: Bool) uses Throw[String]: Int = useByte(fold(c, 5, raise(\"no\")))\n" +
        "def main uses Console: Unit = printLine(show(catch(g(false), (e: String) -> 9)))"
    ).asserting(_ shouldBe "9")
  }

  // Within one derived call a value built there keeps its fields (`{built}`, `fieldOf`, `docs/meta.md` §13.4), so a
  // `match` on it runs only the arm its constructor selects, handed the very field.
  private val unbox = "data Box(v: Int)\ndef unbox(x: Int): Int = Box(x) match { case Box(y) -> y }\n"

  "a field read of a value built in the same derived call" should "be the field itself" in {
    accepts(unbox, "unbox(5)", "5")
  }

  it should "reject a field that does not fit" in {
    rejects(unbox, "unbox(200)")
  }

  private def picked(b: Int) = s"data AB = A | B\ndef pick(x: AB): Int = x match { case A -> 10 case B -> $b }\ndef pickA: Int = pick(A)\n"

  "a match on a value built in the same derived call" should "run only the arm its constructor selects" in {
    accepts(picked(200), "pickA", "10")
  }

  // A cell is followed through the one call that made it (`docs/meta.md` §11.1): `putState` writes it and `state`
  // reads it, each through the field of the handle `runStateToPair` built over it, and the discharger hands back what it
  // held when its code returned.
  private val counter =
    """|def counter uses State[Int]: Int = {
       |  putState(add(state, 1))
       |  state
       |}
       |""".stripMargin

  "a cell" should "hold what was last written into it" in {
    accepts(counter, "runStateToFinalState(3, counter)", "4")
  }

  it should "reject what was written into it when that does not fit" in {
    rejects(counter, "runStateToFinalState(127, counter)")
  }

  it should "hand a read what the write before it wrote" in {
    accepts(counter, "runStateToValue(3, counter)", "4")
  }

  it should "join what each arm wrote" in {
    accepts("", "runStateToFinalState(0, fold(true, putState(5), putState(9)))", "5")
  }

  it should "reject when one arm wrote what does not fit" in {
    rejects("", "runStateToFinalState(0, fold(true, putState(5), putState(200)))")
  }

  // A frame collects what leaves through it: `raise` exits through the frame `runThrow` made, reached through the field
  // of the handle `escaping`, and `catch`'s handler is handed what the exits carried — or is not run at all.
  "a frame" should "hand the handler what was raised" in {
    accepts("", "catch(raise(\"abc\"), (e: String) -> length(e))", "3")
  }

  it should "reject what the handler makes of a raise when that does not fit" in {
    rejects("", "catch(raise(\"abc\"), (e: String) -> add(length(e), 200))")
  }

  it should "leave a handler nothing exits to unrun" in {
    accepts("", "catch(5, (e: String) -> 200)", "5")
  }

  // Where the body may both return and raise, the escape's result is a `Left` or a `Right`, which an `Either`'s meta
  // joins slot by slot (`docs/meta.md` §13, §15): the result is the join of the handler's yield and the body's.
  it should "join a body that may both return and raise with what the handler makes of the raise" in {
    accepts("", "catch(fold(true, 5, raise(\"no\")), (e: String) -> 6)", "5")
  }

  it should "reject a body that may both return and raise when what it returns does not fit" in {
    rejects("", "catch(fold(true, 200, raise(\"no\")), (e: String) -> 6)")
  }

  // `runStateToPair(s, runThrow(c))` keeps the state across a `raise`: the frame is left with the cell as it was.
  private val raising =
    """|def raising uses State[Int], Throw[String]: Int = {
       |  putState(STATE)
       |  raise("no")
       |}
       |""".stripMargin

  "a cell around a frame" should "keep what was written before the exit" in {
    accepts(raising.replace("STATE", "5"), "runStateToFinalState(0, catch(raising, (e: String) -> 1))", "5")
  }

  it should "reject what was written before the exit when that does not fit" in {
    rejects(raising.replace("STATE", "200"), "runStateToFinalState(0, catch(raising, (e: String) -> 1))")
  }

  // A loop's accumulator is its fixpoint (`docs/meta.md` §11.2): the code a loop runs is run from its initial accumulator
  // until nothing it changes changes any more — joined after the first run, widened after every later one, so an `Int`
  // that keeps moving opens at once — and the call is what the loop may end with, after any number of runs. What a
  // `List`'s elements are it does not carry yet (T6), so these loops ignore their element.
  private def looping(definitions: String, expression: String, effects: String = "Console") =
    "import eliot.collection.List\n" + useByte + definitions +
      "def two: List[String] = append(append(empty, \"a\"), \"b\")\n" +
      s"def main uses $effects: Unit = printLine(show(useByte($expression)))"

  private val notSatisfied = "precondition of 'Test::useByte' is not satisfied"

  "a loop" should "be what every run yields when that is the same" in {
    compileAndRun(looping("", "foldLeft(5, (e: String) -> (acc: Int) -> 7, two)")).asserting(_ shouldBe "7")
  }

  it should "be widened when what a run yields keeps growing, since how many runs there are is not known" in {
    compileForErrors(looping("", "foldLeft(5, (e: String) -> (acc: Int) -> add(acc, 1), two)"))
      .asserting(_ should include(notSatisfied))
  }

  it should "join what each run writes into a cell" in {
    compileAndRun(looping("", "runStateToFinalState(0, foldLeft(unit, (e: String) -> (u: Unit) -> putState(5), two))"))
      .asserting(_ shouldBe "5")
  }

  it should "widen a cell each run writes more into" in {
    compileForErrors(
      looping("", "runStateToFinalState(0, foldLeft(unit, (e: String) -> (u: Unit) -> putState(add(state, 1)), two))")
    ).asserting(_ should include(notSatisfied))
  }

  // An endless loop does not return, so a frame is the only way out of it, and what leaves through the frame is
  // collected over every run: each starts with the cells the run before it left.
  private val ticking =
    """|def tick(stop: Bool) uses State[Int], Throw[Int]: Unit = {
       |  putState(add(state, STEP))
       |  fold(stop, raise(state), unit)
       |}
       |def ticks(stop: Bool) uses State[Int], Throw[Int], Inf: Int = {
       |  forever(tick(stop))
       |  0
       |}
       |""".stripMargin

  "an endless loop" should "hand a frame what every run left through it" in {
    compileAndRun(
      looping(ticking.replace("STEP", "0"), "runStateToValue(0, catch(ticks(true), (e: Int) -> e))", "Console, Inf")
    ).asserting(_ shouldBe "0")
  }

  it should "hand a frame what a later run left through it, after the runs before it wrote a cell" in {
    compileForErrors(
      looping(ticking.replace("STEP", "1"), "runStateToValue(0, catch(ticks(true), (e: Int) -> e))", "Console, Inf")
    ).asserting(_ should include(notSatisfied))
  }

  // A file fold's accumulator is its loop's too, now that the fold returns it rather than a holder of any type
  // (§11.3): a line is a JVM `String`, so a step yielding its length reaches the representation's limit. Its error path
  // raises, so it adds nothing. The demand sits in the reading def, so it is checked whether or not the file exists.
  private def folding(step: String) =
    s"""|import eliot.effect.Console
        |import eliot.file.Path
        |import eliot.file.File
        |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
        |def useByte(x: Int) where withinByte(range(x)): Int = x
        |def lines(p: Path) uses FileSystem, Throw[IoError]: Int =
        |  useByte(foldLines(5, (acc: Int) -> (line: String) -> $step, p))
        |def main uses Console, FileSystem: Unit =
        |  printLine(show(catch(lines(path("/nonexistent/eliot")), (e: IoError) -> 6)))
        |""".stripMargin

  "a file fold" should "be its loop's accumulator" in {
    compileAndRun(folding("7")).asserting(_ shouldBe "6")
  }

  it should "reach what a line's size may be" in {
    compileForErrors(folding("length(line)")).asserting(_ should include(notSatisfied))
  }

  // A call one of whose arguments does not return is never made, so what it demands is vacuous there (§12, decision C).
  "a demand on a call that is never made" should "not be checked" in {
    compileAndRun(aborting("useByte(add(1000, abort))", "g(true) else 3")).asserting(_ shouldBe "3")
  }

  // A value of a `data` type carries its fields' metas across every call (`docs/meta.md` §13): `Some(7)` is a
  // separate call node, whose meta is all `orZero` receives, and the arm reads its field out of it.
  private val orZero = "def orZero(o: Option[Int]): Int = o match { case Some(x) -> x case None -> 0 }\n"

  "a field read in a match arm" should "be what the value was built with, across a call" in {
    accepts(orZero, "orZero(Some(7))", "7")
  }

  it should "reject what the value was built with when that does not fit" in {
    rejects(orZero, "orZero(Some(200))")
  }

  it should "be what a value built in `main` was built with, for a demand inside the arm" in {
    compileAndRun(
      useByte + "def main uses Console: Unit = Some(7) match { case Some(x) -> printLine(show(useByte(x))) case None -> printLine(\"none\") }"
    ).asserting(_ shouldBe "7")
  }

  it should "reject a demand inside the arm on what a value built in `main` does not fit" in {
    compileForErrors(
      useByte + "def main uses Console: Unit = Some(700) match { case Some(x) -> printLine(show(useByte(x))) case None -> printLine(\"none\") }"
    ).asserting(_ should include("precondition of 'Test::useByte' is not satisfied"))
  }

  // `if … else` reads its result out of the `Option` the escape built (`docs/meta.md` §15): `Some` of the value when the
  // guard holds, `None` when it aborts, so the result is the join of the guarded value and the fallback.
  "if … else" should "be the join of the guarded value and the fallback" in {
    accepts("def g(c: Bool): Int = if(c, 5) else 7\n", "g(true)", "5")
  }

  it should "reject when the guarded value does not fit" in {
    rejects("def g(c: Bool): Int = if(c, 300) else 5\n", "g(true)")
  }

  "a pair" should "carry each component's meta" in {
    accepts("", "first(Pair(5, \"abc\"))", "5")
  }

  it should "reject a component that does not fit" in {
    rejects("", "second(Pair(\"abc\", 500))")
  }

  "an either" should "carry the meta of the side it was built with" in {
    accepts("def orNine(e: Either[String, Int]): Int = e match { case Left(s) -> 9 case Right(v) -> v }\n", "orNine(Right(5))", "5")
  }

  // A recursive `data` folds into its own summary (`docs/meta.md` §13.3): every node's fields are in it, but not which
  // node holds what, so a field read anywhere in a tree is the join over all of them.
  private val tree =
    "data Tree = Leaf | Node(left: Tree, value: Int, right: Tree)\n" +
      "def rootValue(t: Tree): Int = t match { case Leaf -> 0 case Node(l, v, r) -> v }\n"

  "a recursive data value" should "carry the meta of every node's fields" in {
    accepts(tree, "rootValue(Node(Leaf, 7, Node(Leaf, 9, Leaf)))", "7")
  }

  it should "reject a field read whose join over the nodes does not fit, though the node read holds what fits" in {
    rejects(tree, "rootValue(Node(Leaf, 7, Node(Leaf, 200, Leaf)))")
  }

  // The gaps this stage keeps, stated as tests so that closing one is a visible change. "Cannot prove" — an absent
  // bound — is the right diagnostic for each, and it is *not* "not satisfied": the interpretation declined.
  // Recursion other than through a field typed by the data itself — through another type, or across a group of types
  // — has no finite meta type yet (`docs/meta.md` §8), so such a value's meta is not computed, and the program still
  // compiles and runs.
  private val rose =
    "data Rose(kids: Option[Rose], value: Int)\n" +
      "def rootOf(r: Rose): Int = r match { case Rose(k, v) -> v }\n"

  "a value recursive through another type" should "stay not computed, and still compile and run" in {
    compileAndRun(
      "import eliot.effect.Console\n" + rose + "def main uses Console: Unit = printLine(show(rootOf(Rose(Some(Rose(None, 9)), 7))))"
    ).asserting(_ shouldBe "7")
  }

  it should "leave a demand on what it holds unproven" in {
    compileForErrors(useByte + rose + "def main uses Console: Unit = printLine(show(useByte(rootOf(Rose(None, 7)))))")
      .asserting(_ should include("Cannot prove the precondition of 'Test::useByte'"))
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
