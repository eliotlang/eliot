package com.vanillasource.eliot.eliotc.jvm

/** The `uses` clause (`docs/effects.md` D21, step 4): `uses E` on a definition is what its caller hands it, and on a
  * parameter it makes the argument code — `uses *` open to the caller's effects, `uses *, E` with `E` given by the
  * callee, and `uses E` alone **closed**, where the argument may use what the slot supplies and nothing from around it.
  */
class UsesClauseIntegrationTest extends FullIntegrationTest {

  "a definition's uses clause" should "be handed its effects by its caller" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def greet(name: String) uses Console: Unit = printLine("Hello, " ++ name)
        |
        |def main uses Console: Unit = greet("Bob")
        |""".stripMargin
    ).asserting(_ shouldBe "Hello, Bob")
  }

  it should "be required of a definition that performs" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def greet(name: String): Unit = printLine("Hello, " ++ name)
        |
        |def main uses Console: Unit = greet("Bob")
        |""".stripMargin
    ).asserting(_ should include("This value performs the effect 'Console' but does not declare it"))
  }

  "a parameter's `uses *`" should "run the caller's code where the callee mentions it" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def twice(action uses *: Unit): Unit = {
        |   action
        |   action
        |}
        |
        |def main uses Console: Unit = twice(printLine("tick"))
        |""".stripMargin
    ).asserting(_ shouldBe "tick\ntick")
  }

  it should "make a function-typed parameter code the callee calls" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def both(action uses *: String => Unit, a: String, b: String): Unit = {
        |   action(a)
        |   action(b)
        |}
        |
        |def main uses Console: Unit = both(s -> printLine(s), "a", "b")
        |""".stripMargin
    ).asserting(_ shouldBe "a\nb")
  }

  "a parameter's `uses *, E`" should "add what the callee gives to the caller's effects" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def attempt(body uses *, Throw[String]: String): String =
        |   foldEither(e -> "caught " ++ e, v -> v, runThrow[String, String](body))
        |
        |def main uses Console: Unit = printLine(attempt({
        |   printLine("trying")
        |   raise("boom")
        |}))
        |""".stripMargin
    ).asserting(_ shouldBe "trying\ncaught boom")
  }

  private val closedPrelude =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |
      |def runPure(body uses Throw[String]: String): String =
      |   foldEither(e -> "caught " ++ e, v -> v, runThrow[String, String](body))
      |
      |""".stripMargin

  "a closed clause" should "admit what the slot supplies" in {
    compileAndRun(closedPrelude + """def main uses Console: Unit = printLine(runPure(raise("boom")))""")
      .asserting(_ shouldBe "caught boom")
  }

  it should "reject an effect from the scope the argument is written in" in {
    compileForErrors(closedPrelude + """def main uses Console: Unit = printLine(runPure({
        |   printLine("hi")
        |   "v"
        |}))""".stripMargin)
      .asserting(_ should include(":8:4:This uses the effect 'Console' inside an argument whose `uses` clause is closed"))
  }

  it should "admit an effect the argument discharges itself" in {
    compileAndRun(
      closedPrelude +
        """def quiet: Option[String] = runAbort(abort)
          |def main uses Console: Unit = printLine(runPure(foldOption("none", v -> v, quiet)))""".stripMargin
    ).asserting(_ shouldBe "none")
  }

  it should "reject code of the caller's passed on to it" in {
    compileForErrors(
      closedPrelude +
        """def leak(code uses *: String): String = runPure(code)
          |def main uses Console: Unit = printLine(leak("v"))""".stripMargin
    ).asserting(_ should include("'code' is code its caller wrote, and an argument whose `uses` clause is closed cannot run it."))
  }

  it should "let an entry the callee's own row has ride the caller's binding" in {
    compileAndRun(
      """import eliot.effect.Console
        |import eliot.effect.Throw
        |
        |def described(body uses Throw[String]: String) uses Throw[String]: String =
        |   catch[String, String](body, e -> raise("described " ++ e))
        |
        |def failing uses Throw[String]: String = described(raise("boom"))
        |
        |def main uses Console: Unit = printLine(foldEither(e -> "caught " ++ e, v -> v, runThrow(failing)))
        |""".stripMargin
    ).asserting(_ shouldBe "caught described boom")
  }

  it should "close a function-typed parameter too" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def both(action uses Console: String => Unit, a: String): Unit = action(a)
        |
        |def main uses Console: Unit = both(s -> printLine(s), "a")
        |""".stripMargin
    ).asserting(_ should include("This uses the effect 'Console' inside an argument whose `uses` clause is closed"))
  }

  "a uses clause with a row on the type as well" should "be rejected, as a row in any type is" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |def run(body uses *: {Console} Unit): Unit = body
        |
        |def main uses Console: Unit = run(printLine("x"))
        |""".stripMargin
    ).asserting(_ should include("Expected a type, with its effects in a `uses` clause before the colon"))
  }
}
