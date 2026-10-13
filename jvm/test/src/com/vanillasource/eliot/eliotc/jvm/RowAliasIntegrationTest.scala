package com.vanillasource.eliot.eliotc.jvm

/** A **row alias** naming a definition's return type, end to end (`docs/effects.md` §2.4, §9.3 step 5).
  *
  * The alias is an ordinary type alias and the use an ordinary application — only the row's *entries* cross the use
  * site, never its payload — so what has to hold at runtime is that a definition naming one behaves exactly as the
  * written-out row does: it receives the row from its caller, its operations run on the implementation the boundary
  * binds, an entry's arguments follow the alias's own arguments, a row written out beside a named one adds to it, and
  * an effect the alias does not carry is still the "performs but does not declare" error.
  *
  * The alias being an ordinary **name** — so that it crosses files and is shadowed like any other — is asserted where
  * name resolution lives, in `resolve.processor.RowAliasesTest`; `examples/src/RowAlias.els` is the cross-module
  * witness the example sweep runs.
  */
class RowAliasIntegrationTest extends FullIntegrationTest {

  "a def naming a row alias" should "receive the row and run on the bound implementation" in {
    compileAndRun("""
      |import eliot.effect.Console
      |
      |type Speaking[A] = {Console} A
      |
      |def greet(name: String): Speaking[Unit] = printLine(combine("hello ", name))
      |def main uses Console: Unit = greet("world")""".stripMargin)
      .asserting(_ shouldBe "hello world")
  }

  "a parameterless row alias" should "carry its payload as well as its row" in {
    compileAndRun("""
      |import eliot.effect.Console
      |
      |type Greeting = {Console} Unit
      |
      |def greet: Greeting = printLine("hello")
      |def main uses Console: Unit = greet""".stripMargin)
      .asserting(_ shouldBe "hello")
  }

  "a row alias argument" should "reach the entry that mentions it" in {
    compileAndRun("""
      |import eliot.effect.Console
      |import eliot.effect.Throw
      |
      |type Fallible[E, A] = {Throw[E]} A
      |
      |def bad: Fallible[String, String] = raise("nope")
      |def main uses Console: Unit = printLine(bad catch (e -> combine("caught ", e)))""".stripMargin)
      .asserting(_ shouldBe "caught nope")
  }

  "a row written out beside a named one" should "declare and run both" in {
    compileAndRun("""
      |import eliot.effect.Console
      |import eliot.effect.Log
      |
      |type Speaking[A] = {Console} A
      |
      |def greet uses Log: Speaking[Unit] = {
      |   log("greeting")
      |   printLine("hello")
      |}
      |def main uses Console, Log: Unit = greet""".stripMargin)
      .asserting(_ shouldBe "[LOG] greeting\nhello")
  }

  "an effect the named row does not carry" should "still be reported at the reference" in {
    compileForErrors("""
      |import eliot.effect.Console
      |import eliot.effect.Log
      |
      |type Speaking[A] = {Console} A
      |
      |def noisy: Speaking[Unit] = log("unnamed")
      |def main uses Console: Unit = noisy""".stripMargin)
      .asserting(_ should include("performs the effect 'Log' but does not declare it"))
  }
}
