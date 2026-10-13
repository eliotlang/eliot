package com.vanillasource.eliot.eliotc.jvm

/** The base's everyday conveniences, run end to end: `when`/`unless` (a one-armed statement with no `Abort`),
  * `someIf`, `foreachOption`, `isSome`/`isNone`, `anyOption`, `Eq[Option]`, `mapOption` (which once could not resolve
  * `some` in its own file), `toList`, `filterMap`/`findMap`/`includes`, the base's `first`/`second` merged with the
  * platform's `data Pair`, a `catch` translating one error type into another, and `else`/`orElse` binding looser than
  * `++` so a fallback may be an operator expression as it stands.
  */
class StdlibConveniencesIntegrationTest extends FullIntegrationTest {

  "the base's option, list and branching conveniences" should "compile and run as documented" in {
    compileAndRun(
      """|import eliot.collection.List
        |
        |data Shape = Circle(radius: Int) | Square(side: Int)
        |
        |def radiusOf(shape: Shape): Option[Int] = shape match {
        |   case Circle(r) -> some(r)
        |   case _         -> none
        |}
        |
        |def checked(code: Int) uses Throw[String]: String = {
        |   unless(code == 0) raise("exit " ++ show(code))
        |   "output"
        |}
        |
        |def inner(failing: Bool) uses Throw[String]: String = if(failing) raise("boom") else "fine"
        |
        |def translated(failing: Bool) uses Throw[Int]: String = inner(failing) catch (e -> raise(length(e)))
        |
        |def header(failed: Int): String = if(failed == 0) "ok" else "failed " ++ show(failed)
        |
        |def bools(o: Option[Int]): String = if(o.isSome) "some" else "none"
        |
        |def main uses Console: Unit = {
        |   val shapes = singleton(Square(1)) ++ singleton(Circle(2)) ++ singleton(Circle(3))
        |   printLine(shapes.filterMap(radiusOf).joined(","))
        |   printLine(show(shapes.findMap(radiusOf) orElse 0))
        |   printLine(show(singleton(Square(1)).findMap(radiusOf) orElse 0))
        |   when(true) printLine("when ran")
        |   unless(true) printLine("unless must not run")
        |   someIf(true) "x".foreachOption(x -> printLine("some " ++ x))
        |   printLine(show(someIf(false) 1 orElse 9))
        |   printLine(checked(0) catch (e -> e))
        |   printLine(checked(3) catch (e -> e))
        |   printLine(translated(false) catch (n -> show(n)))
        |   printLine(translated(true) catch (n -> show(n)))
        |   printLine(header(0))
        |   printLine(header(2))
        |   printLine(bools(some(1)) ++ " " ++ bools(none))
        |   printLine(if(some(1) == some(1) && some(1) != some(2) && none == none[Int] && some(1) != none) "eq ok" else "eq broken")
        |   printLine(if(some(3).anyOption(n -> n > 2)) "any ok" else "any broken")
        |   printLine(some(4).mapOption(n -> n + 1).toList.joined(",") ++ "|" ++ none[Int].toList.joined(","))
        |   printLine(if(shapes.map(s -> show(radiusOf(s) orElse 0)).includes("2")) "includes ok" else "includes broken")
        |   val p = pair("left", 5)
        |   printLine(p.first ++ show(p.second))
        |   printLine(none[String] orElse "a" ++ "b")
        |}""".stripMargin
    ).asserting(
      _ shouldBe Seq(
        "2,3", "2", "0", "when ran", "some x", "9", "output", "exit 3", "fine", "4", "ok", "failed 2", "some none",
        "eq ok", "any ok", "5|", "includes ok", "left5", "ab"
      ).mkString("\n")
    )
  }
}
