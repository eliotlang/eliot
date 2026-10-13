package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the compile-time twins of the `eliot.collection.List` leaves (effects v6 step 10.1.1,
  * `ListReductions`): a list is *decided while compiling*. An ability guard folds a literal list on the compiler track
  * — through the borrowed `foldLeft` body, `foldLeftInternal`'s twin and `Eq[String]`'s — so each `handler[..]` site is
  * bound to one implementation in the emitted bytecode, exactly as `Strings.els` decides its route table from
  * `startsWith`. Before the twins the fold stayed stuck and the guard could not be evaluated.
  *
  * The pair of implementations is the point: a guard that could not reduce would fail to *select*, not select the
  * wrong one, so a single accepted case would also pass with the guard silently defaulted — both branches being chosen
  * from one list shows the fold answering `true` and `false` from the same chain. The negative branch is spelled
  * `all(w -> !(w == S), …)` rather than `!any(…)`: a rowed call is `Either[String, Bool]` on the compile track and a
  * guard body is not elaborated, so `!` over it is a type mismatch — a standing limitation of guards, not of lists.
  */
class ListGuardReductionIntegrationTest extends FullIntegrationTest {
  private val routes =
    """|import eliot.effect.Console
       |import eliot.collection.List
       |ability Route[S: String] { def handler: String }
       |""".stripMargin

  "an ability guard over a list built with append" should "fold it at compile time and select by membership" in {
    compileAndRun(
      routes +
        """|def known: List[String] = append(append(empty, "/api"), "/about")
           |implement[S: String] Route[S] where any(w -> w == S, known) { def handler: String = "known" }
           |implement[S: String] Route[S] where all(w -> !(w == S), known) { def handler: String = "unknown" }
           |def main uses Console: Unit = printLine(handler["/api"] ++ "/" ++ handler["/x"] ++ "/" ++ handler["/about"])
           |""".stripMargin
    ).asserting(_ shouldBe "known/unknown/known")
  }

  "an ability guard over the words of a literal" should "split it at compile time and select by membership" in {
    compileAndRun(
      routes +
        """|def known: List[String] = words("  /api /about ")
           |implement[S: String] Route[S] where any(w -> w == S, known) { def handler: String = "known" }
           |implement[S: String] Route[S] where all(w -> !(w == S), known) { def handler: String = "unknown" }
           |def main uses Console: Unit = printLine(handler["/about"] ++ "/" ++ handler["/x"])
           |""".stripMargin
    ).asserting(_ shouldBe "known/unknown")
  }

  "an ability guard over a split of the type argument" should "cut it at compile time and select by a piece" in {
    compileAndRun(
      routes +
        """|implement[S: String] Route[S] where any(w -> w == "b", split(",", S)) { def handler: String = "has b" }
           |implement[S: String] Route[S] where all(w -> !(w == "b"), split(",", S)) { def handler: String = "no b" }
           |def main uses Console: Unit = printLine(handler["a,b"] ++ "/" ++ handler["a,c"] ++ "/" ++ handler["ab"])
           |""".stripMargin
    ).asserting(_ shouldBe "has b/no b/no b")
  }
}
