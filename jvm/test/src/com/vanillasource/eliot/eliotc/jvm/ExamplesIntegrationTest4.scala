package com.vanillasource.eliot.eliotc.jvm

/** Part 4 of 4 of the end-to-end example-program suite (see [[FullIntegrationTest]]). The 88 cases are split across
  * four classes so Mill runs them in four parallel test-worker JVMs — each warming its own resident compilation
  * session — instead of one worker compiling all 88 serially. Keep each part self-contained: a case that shares a
  * class-level helper (e.g. `orderingPrelude`) must stay in the same part as that helper. */
class ExamplesIntegrationTest4 extends FullIntegrationTest {

  // Since the bounds-as-refinements flip (Step 6-ii, uniform bignum) `Int` is a single type with no `[MIN, MAX]`
  // parameters — an integer's value range is meta-information in the refinement channel, not the type. So these
  // programs use a plain `Int`; the historical "widen a literal into a broader declared range" is now a no-op (there is
  // no narrower range to widen from), and every integer lays out as a bignum, so what these still assert is the runtime
  // value, across magnitudes.
  "plain integers" should "carry a literal at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
        |def widened: Int = 7
        |
        |def main uses Console: Unit = printLine(show(widened))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  it should "construct and access a record with a bare Int field (W2)" in {
    // `Counter`'s bare `Int` field generalizes the type to `Counter[lo, hi]`; the accessor `n` (a match under the hood)
    // recovers the field. Exercises construct + accessor + handleCases end-to-end at runtime.
    compileAndRun(
      """import eliot.effect.Console
        |data Counter(n: Int)
        |
        |def main uses Console: Unit = printLine(show(n(Counter(42))))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "round-trip a record field through an explicit match (W2)" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Counter(n: Int)
        |
        |def field(c: Counter): Int = c match { case Counter(x) -> x }
        |
        |def main uses Console: Unit = printLine(show(field(Counter(7))))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  it should "match arms with different literals at runtime" in {
    // `First -> 5` and `Second -> 15` meet at a runtime-chosen `Int` result. (Before the flip the arms had distinct
    // singleton types `Int[5, 5]`/`Int[15, 15]` joined to `Int[5, 15]`; now they are all plain `Int`.)
    compileAndRun(
      """import eliot.effect.Console
        |data Choice = First | Second
        |
        |def choose(c: Choice): Int = c match {
        |  case First  -> 5
        |  case Second -> 15
        |}
        |
        |def main uses Console: Unit = printLine(show(choose(Second)))""".stripMargin
    ).asserting(_ shouldBe "15")
  }

  // W2 follow-up: type-level matching over a record. `Counter` no longer grows type parameters from its `Int` field
  // (the field is a plain `Int`), so the handler matches the nullary type constructor. The empty brackets `Counter[]`
  // force the Type namespace (a bare `Counter` is the value constructor `Int -> Counter`).
  it should "type-level match over a record (W2 follow-up)" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Counter(n: Int)
        |
        |def describe(t: Type): String = t match {
        |   case Counter[] -> "counter"
        |   case _         -> "<other>"
        |}
        |
        |def main uses Console: Unit = printLine(describe(Counter[]))""".stripMargin
    ).asserting(_ shouldBe "counter")
  }

  it should "run an arithmetic result at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
        |def total: Int = 3 + 4
        |
        |def main uses Console: Unit = printLine(show(total))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  // W3: a bare `Int` return. `double(x) = x + x` returns a plain `Int`; the caller runs it.
  it should "run a bare Int return computed from the body (W3)" in {
    compileAndRun(
      """import eliot.effect.Console
        |def double(x: Int): Int = x + x
        |
        |def main uses Console: Unit = printLine(show(double(21)))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  it should "calculate a bare data return from the body and run it (W3)" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Counter(n: Int)
        |
        |def mk(v: Int): Counter = Counter(v)
        |
        |def main uses Console: Unit = printLine(show(n(mk(42))))""".stripMargin
    ).asserting(_ shouldBe "42")
  }

  // Integer arithmetic across magnitudes. Since the flip (uniform bignum) every integer lays out as a
  // `java.math.BigInteger`, so a value of any size is carried and rendered exactly — a wrong narrow width can no longer
  // silently overflow. (Narrow representation selection returns in a later step, from the refinement channel's flow
  // analysis; until then these assert only the runtime value.)
  "integer arithmetic" should "carry a value beyond 16 bits" in {
    compileAndRun(
      """import eliot.effect.Console
        |def big: Int = 70000
        |
        |def main uses Console: Unit = printLine(show(big))""".stripMargin
    ).asserting(_ shouldBe "70000")
  }

  it should "compute a product that overflows 16 bits" in {
    compileAndRun(
      """import eliot.effect.Console
        |def product: Int = 1000 * 1000
        |
        |def main uses Console: Unit = printLine(show(product))""".stripMargin
    ).asserting(_ shouldBe "1000000")
  }

  // One program doing add/subtract/multiply over operands of very different magnitudes, all as plain `Int`.
  it should "compute the right result across magnitudes in one program" in {
    compileAndRun(
      """import eliot.effect.Console
        |def byteSum: Int = 100 + 100
        |def shortDiff: Int = 500 - 300
        |def intProduct: Int = 1000 * 1000
        |def longSum: Int = 5000000000 + 5000000000
        |
        |def main uses Console: Unit = {
        |   printLine(show(byteSum))
        |   printLine(show(shortDiff))
        |   printLine(show(intProduct))
        |   printLine(show(longSum))
        |}""".stripMargin
    ).asserting(_ shouldBe "200\n200\n1000000\n10000000000")
  }

  it should "carry a value beyond 32 bits" in {
    compileAndRun(
      """import eliot.effect.Console
        |def huge: Int = 5000000000
        |
        |def main uses Console: Unit = printLine(show(huge))""".stripMargin
    ).asserting(_ shouldBe "5000000000")
  }

  // A generic function over `Int` is one instantiation (there are no per-range instantiations any more), so both calls
  // dispatch to the same monomorphized method.
  "generic instantiation" should "run a generic Int function at two call sites" in {
    compileAndRun(
      """import eliot.effect.Console
        |def id(x: Int): Int = x
        |def a: Int = 3
        |def b: Int = 5
        |
        |def main uses Console: Unit = printLine(show(id(a) + id(b)))""".stripMargin
    ).asserting(_ shouldBe "8")
  }

  it should "run a generic Int function at two very different magnitudes" in {
    compileAndRun(
      """import eliot.effect.Console
        |def id(x: Int): Int = x
        |def a: Int = 3
        |def big: Int = 5000000000
        |
        |def main uses Console: Unit = printLine(show(id(a) + id(big)))""".stripMargin
    ).asserting(_ shouldBe "5000000003")
  }

  // Eliminating a multi-field `data` desugars its `match` into an N-parameter handler (`x -> y -> body`), which the
  // backend lowers into nested single-argument closures. Extracting the *first* field exercises the outer closure.
  "multi-field data" should "extract the first field of a two-field constructor" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Duo(a: String, b: String)
        |
        |def firstOf(p: Duo): String = p match {
        |  case Duo(x, y) -> x
        |}
        |
        |def main uses Console: Unit = printLine(firstOf(Duo("hello", "world")))""".stripMargin
    ).asserting(_ shouldBe "hello")
  }

  // Extracting the *second* field returns the inner closure's parameter, so it checks the captured-variable plumbing
  // across the peeled frames.
  it should "extract the second field of a two-field constructor" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Duo(a: String, b: String)
        |
        |def secondOf(p: Duo): String = p match {
        |  case Duo(x, y) -> y
        |}
        |
        |def main uses Console: Unit = printLine(secondOf(Duo("hello", "world")))""".stripMargin
    ).asserting(_ shouldBe "world")
  }

  // A three-field constructor peels into three nested closures; selecting the middle field confirms more than two
  // levels of nesting resolve their parameters correctly.
  it should "extract the middle field of a three-field constructor" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Triple(a: String, b: String, c: String)
        |
        |def middle(t: Triple): String = t match {
        |  case Triple(x, y, z) -> y
        |}
        |
        |def main uses Console: Unit = printLine(middle(Triple("one", "two", "three")))""".stripMargin
    ).asserting(_ shouldBe "two")
  }

  // A closure that references a captured variable more than once must capture it exactly once. `freeVariables` reports
  // every occurrence (it does not de-duplicate), so without `LambdaGenerator` de-duping the capture set, the generated
  // closure class would declare the field `s` twice — a `ClassFormatError: Duplicate field name` at load time.
  it should "generate a closure that captures the same variable twice" in {
    compileAndRun(
      """import eliot.effect.Console
        |def firstOf(a: String, b: String): String = a
        |
        |def make(s: String): Function[Unit, String] = ignore -> firstOf(s, s)
        |
        |def main uses Console: Unit = printLine(apply(make("captured-twice"), unit))""".stripMargin
    ).asserting(_ shouldBe "captured-twice")
  }

  // A two-field `Int` round-trip: matching the fields out and summing them, over the peeled closures.
  it should "match out two integer fields and sum them" in {
    compileAndRun(
      """import eliot.effect.Console
        |data IntPair(small: Int, large: Int)
        |
        |def sum(p: IntPair): Int = p match {
        |  case IntPair(s, l) -> s + l
        |}
        |
        |def main uses Console: Unit = printLine(show(sum(IntPair(200, 5000000000))))""".stripMargin
    ).asserting(_ shouldBe "5000000200")
  }

  // A *generic* multi-field constructor is emitted once (one class + factory per data type) but used at two
  // instantiations whose bare type-parameter fields have different JVM carriers (`Pair[Box[String], String]` stores a
  // `Box`, `Pair[String, String]` stores a `String`). Both the factory and every call site must erase the polymorphic
  // fields to `Object`, or the single shared `Pair(..)` descriptor mismatches the call site (a runtime
  // `NoSuchMethodError`). This is the generic-multi-field codegen bug that previously blocked two-field generic data.
  it should "construct and match a generic two-field constructor at two distinct instantiations" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[A](item: A)
        |
        |data Duo[A, B](fst: A, snd: B)
        |
        |def secondOf[A, B](p: Duo[A, B]): B = p match {
        |  case Duo(x, y) -> y
        |}
        |
        |def main uses Console: Unit = printLine(secondOf(Duo(Box("boxed"), secondOf(Duo("c", "plain")))))""".stripMargin
    ).asserting(_ shouldBe "plain")
  }

  // The same erasure, but with a *mixed* constructor: a concrete `tag: String` field beside a polymorphic `value: A`
  // field, used at `Tagged[String]` and `Tagged[Tagged[String]]`. The concrete field must keep its `String` descriptor
  // (only the polymorphic field erases), and the two instantiations must still agree on the shared factory. Exercised
  // through the auto-generated single-constructor field accessor (`value`), i.e. the pattern-match codegen path.
  it should "construct and access a generic constructor mixing a concrete and a polymorphic field" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Tagged[A](tag: String, value: A)
        |
        |def main uses Console: Unit = printLine(value(value(Tagged("outer", Tagged("inner", "deep")))))""".stripMargin
    ).asserting(_ shouldBe "deep")
  }

  // A single-constructor *union* whose constructor name differs from the type name (`data Color = Red`). The data class
  // must be named after the *type* (`Color`), not the constructor (`Red`): the factory's declared return, the match
  // parameter type, and the `PatternMatch$Color$impl` singleton all refer to `Color`, so naming the class `Red` made the
  // factory `Red()` return a `Red` while every call site expected a `Color` (a runtime `NoSuchMethodError`). Records
  // (constructor name = type name) always coincided and were never affected.
  it should "construct and match a single-constructor union whose constructor name differs from the type" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Color = Red
        |
        |def name(c: Color): String = c match {
        |  case Red -> "red"
        |}
        |
        |def main uses Console: Unit = printLine(name(Red))""".stripMargin
    ).asserting(_ shouldBe "red")
  }

  // The same single-constructor-union naming, but with a field and a generic parameter, so it also rides the
  // bare-type-parameter erasure: `Box[A] = Wrap(item: A)` (constructor `Wrap` ≠ type `Box`) accessed via the
  // auto-generated `item` accessor.
  it should "construct and access a generic single-constructor union whose constructor name differs from the type" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[A] = Wrap(item: A)
        |
        |def main uses Console: Unit = printLine(item(Wrap("wrapped")))""".stripMargin
    ).asserting(_ shouldBe "wrapped")
  }

  // --- Numeric ability on `Int`: homogeneous add/subtract/multiply (the result type is again `Int`). ---

  // Nested calls to the same ability method resolve independently: `add(a, add(b, c))`.
  "the Numeric ability" should "add ints across nested calls" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def a: Int = 30
        |def b: Int = 20
        |def c: Int = 10
        |
        |def total: Int = add(a, add(b, c))
        |
        |def main uses Console: Unit = printLine(show(total))""".stripMargin
    ).asserting(_ shouldBe "60")
  }

  // Bound enforcement is intentionally gone at this step: with the range out of the type, a value that would once have
  // been rejected as "too big for the declared range" now simply computes. (It returns as a demand once value-range
  // contracts / `where` clauses land — bounds-as-refinements Step 8.) A once-rejected `add` now runs.
  it should "no longer reject a result that would exceed a former range bound" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def a: Int = 30
        |def b: Int = 20
        |
        |def total: Int = add(a, b)
        |
        |def main uses Console: Unit = printLine(show(total))""".stripMargin
    ).asserting(_ shouldBe "50")
  }

  // A plain generic function over `Numeric[T]` whose return is the same `T`, projected at the call from the
  // monomorphized callee. For `Int` the result is again `Int`.
  it should "run a generic function over a Numeric constraint" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def plus[T ~ Numeric[T]](x: T, y: T): T = add(x, y)
        |
        |def a: Int = 30
        |def b: Int = 20
        |
        |def total: Int = plus(a, b)
        |
        |def main uses Console: Unit = printLine(show(total))""".stripMargin
    ).asserting(_ shouldBe "50")
  }

  // Generic arithmetic composed, with a generic call feeding another.
  it should "compose generic Numeric functions" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def plus[T ~ Numeric[T]](x: T, y: T): T = add(x, y)
        |def times[T ~ Numeric[T]](x: T, y: T): T = multiply(x, y)
        |
        |def a: Int = 30
        |def b: Int = 20
        |
        |def main uses Console: Unit = printLine(show(plus(times(a, b), a)))""".stripMargin
    ).asserting(_ shouldBe "630")
  }

  // --- Interval: endpoint-wise interval arithmetic over `Int` endpoints (runtime `Numeric[Interval[Int]]`). ---

  // An endpoint is a `Bound[Int]`, so rendering one has to answer the unbounded case. Shared by the tests below.
  private val showing =
    """|def showBound(unboundedAs: String, bound: Bound[Int]): String = bound.foldBound(unboundedAs, v -> show(v))
       |def showInterval(i: Interval[Int]): String =
       |  "[" ++ showBound("-inf", i.start) ++ ", " ++ showBound("+inf", i.end) ++ "]"
       |""".stripMargin

  // Interval addition is endpoint-wise: `[0, 1] + [1, 2] = [1, 3]`.
  "the Interval type" should "add intervals endpoint-wise" in {
    compileAndRun(
      "import eliot.effect.Console\n" + showing +
        """|def a: Interval[Int] = closed(0, 1)
           |def b: Interval[Int] = closed(1, 2)
           |
           |def sum: Interval[Int] = a + b
           |
           |def main uses Console: Unit = printLine(showInterval(sum))""".stripMargin
    ).asserting(_ shouldBe "[1, 3]")
  }

  // Subtraction pairs a start with the other interval's end (`[0, 1] - [1, 2] = [-2, 0]`); multiplication selects the
  // min/max of the four corner products at runtime (via the `Compare[Int]` ordering leaf).
  it should "subtract and multiply intervals endpoint-wise" in {
    compileAndRun(
      "import eliot.effect.Console\n" + showing +
        """|def a: Interval[Int] = closed(0, 1)
           |def b: Interval[Int] = closed(1, 2)
           |
           |def diff: Interval[Int] = a - b
           |def prod: Interval[Int] = a * b
           |
           |def main uses Console: Unit = {
           |  printLine(showInterval(diff))
           |  printLine(showInterval(prod))
           |}""".stripMargin
    ).asserting(_ shouldBe "[-2, 0]\n[0, 2]")
  }

  // A half-open interval is bounded on one side only. `atLeast(2)` is `[2, ∞)`; `atMost(3)` is `(-∞, 3]`.
  it should "construct intervals open on one side" in {
    compileAndRun(
      "import eliot.effect.Console\n" + showing +
        """|def main uses Console: Unit = {
           |  printLine(showInterval(atLeast(2)))
           |  printLine(showInterval(atMost(3)))
           |  printLine(showInterval(whole))
           |}""".stripMargin
    ).asserting(_ shouldBe "[2, +inf]\n[-inf, 3]\n[-inf, +inf]")
  }

  // Addition and subtraction stay exact on a half-open interval, because each pairs endpoints so an absorbing
  // `Unbounded` lands in the position meaning the infinity it came from: `[2, ∞) + [1, 2] = [3, ∞)` keeps its
  // bounded start, and `[2, ∞) - [1, 2] = [0, ∞)` keeps one too (its start is `2 - 2`).
  it should "keep the bounded endpoint when adding and subtracting a half-open interval" in {
    compileAndRun(
      "import eliot.effect.Console\n" + showing +
        """|def open: Interval[Int] = atLeast(2)
           |def b: Interval[Int] = closed(1, 2)
           |
           |def main uses Console: Unit = {
           |  printLine(showInterval(open + b))
           |  printLine(showInterval(open - b))
           |}""".stripMargin
    ).asserting(_ shouldBe "[3, +inf]\n[0, +inf]")
  }

  // Multiplication is the one operation that widens: its corner products are compared *after* the multiplication, by
  // which point a product has lost the position that gave its infinity a direction, so any unbounded endpoint absorbs
  // the whole result. Sound but not tight — `[2, ∞) * [1, 2]` is really `[2, ∞)`.
  it should "widen to the whole line when multiplying a half-open interval" in {
    compileAndRun(
      "import eliot.effect.Console\n" + showing +
        """|def open: Interval[Int] = atLeast(2)
           |def b: Interval[Int] = closed(1, 2)
           |
           |def main uses Console: Unit = printLine(showInterval(open * b))""".stripMargin
    ).asserting(_ shouldBe "[-inf, +inf]")
  }
}
