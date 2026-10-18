package com.vanillasource.eliot.eliotc.jvm

/** The hole rule (`docs/effects.md` §3.3, D36 point 4): a use takes the nearest handle in lexical scope whose entry
  * answers it after evaluation — the shapes D30's comparison as spelled refused (§8 items 60 and 61).
  */
class HoleRuleIntegrationTest extends FullIntegrationTest {

  "a raise under a single Throw of a constructed type" should "take that handle and solve the operation's type" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def lookup(n: Int) uses Throw[Option[Int]]: String = if(n == 0) raise(none) else "found"
        |
        |def main uses Console: Unit = printLine(foldEither(e -> "none", a -> a, runThrow[Option[Int], String](lookup(0))))
        |""".stripMargin
    ).asserting(_ shouldBe "none")
  }

  // §8 item 60: the slot lists `Throw[String]`, and the open block captured the caller's `Throw[Int]`.
  "a use in an open block an entry its slot lists does not answer" should "reach the handle the block captured" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def guarded(body uses *, Throw[String]: Unit) uses *: String =
        |   foldEither(e -> e, u -> "done", runThrow[String, Unit](body))
        |
        |def main uses Console: Unit =
        |   printLine(foldEither(n -> "int " ++ show(n), s -> s, runThrow[Int, String](guarded(raise(1)))))
        |""".stripMargin
    ).asserting(_ shouldBe "int 1")
  }

  it should "be refused in a closed block, naming what is in scope" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def guarded(body uses Throw[String]: Unit): String = foldEither(e -> e, u -> "done", runThrow[String, Unit](body))
        |
        |def main uses Console: Unit =
        |   printLine(foldEither(n -> "int " ++ show(n), s -> s, runThrow[Int, String](guarded(raise(1)))))
        |""".stripMargin
    ).asserting(_ should include("No handle in scope here answers 'Throw[Int]'."))
  }

  "two entries of one effect" should "each be answered by the entry its use's type evaluates to" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def both(n: Int) uses Throw[String], Throw[Int]: String = if(n == 0) raise(1) else if(n == 1) raise("one") else "fine"
        |
        |def described(n: Int): String =
        |   catch[String, String](catch[Int, String](both(n), i -> "int " ++ show(i)), s -> "string " ++ s)
        |
        |def main uses Console: Unit = printLine(described(0) ++ "," ++ described(1) ++ "," ++ described(2))
        |""".stripMargin
    ).asserting(_ shouldBe "int 1,string one,fine")
  }

  // §8 item 61: the member's `Writer[List[T]]` at `T := Box` is the clause's `Writer[List[Box]]`.
  "an anonymous implementation's clause repeating its member's entry at the pattern's type" should "be the call's" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.collection.List
        |
        |data Box(n: Int)
        |
        |ability Logged[T] {
        |   def logged(t: T) uses Writer[List[T]]: Unit
        |}
        |
        |implement Logged[Box] {
        |   def logged(t: Box) uses Writer[List[Box]]: Unit = tell(singleton(t))
        |}
        |
        |def main uses Console: Unit = printLine(show(size(runWriterToLog[List[Box], Unit](logged(Box(1))))))
        |""".stripMargin
    ).asserting(_ shouldBe "1")
  }
}
