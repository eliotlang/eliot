package com.vanillasource.eliot.eliotc.jvm

/** A named implementation takes **parameters**, supplied where it is bound — `greet with scripted("Bob")`
  * (`docs/effects.md` §2, §3.4). The arguments are values computed where the `with` is written; the implementation's
  * frame holds them, and travels the declared chain like every other frame.
  */
class NamedImplementationParametersIntegrationTest extends FullIntegrationTest {

  private val terminal =
    """import eliot.effect.Console
      |
      |effect Terminal {
      |   def write(line: String): Unit
      |   def read: String
      |}
      |
      |def greet uses Terminal: Unit = {
      |   val name = read
      |   write("Hello, " ++ name ++ "!")
      |}
      |
      |implement scripted(answer: String): Terminal {
      |   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
      |   def read: String = answer
      |}
      |""".stripMargin

  "a named implementation's parameters" should "be the arguments its `with` supplies" in {
    compileAndRun(
      s"""$terminal
         |def main uses Console: Unit = {
         |   printLine(runWriterToLog(greet with scripted("Bob")))
         |   printLine(runWriterToLog(greet with scripted("Alice")))
         |}""".stripMargin
    ).asserting(_ shouldBe "Hello, Bob!;\nHello, Alice!;")
  }

  it should "reach a definition that receives the binding and passes it on" in {
    compileAndRun(
      s"""$terminal
         |def twice uses Terminal: Unit = {
         |   greet
         |   greet
         |}
         |
         |def main uses Console: Unit = printLine(runWriterToLog(twice with scripted("Bob")))""".stripMargin
    ).asserting(_ shouldBe "Hello, Bob!;Hello, Bob!;")
  }

  it should "be computed outside the `with`, under the bindings around it" in {
    compileAndRun(
      s"""$terminal
         |def answer uses Terminal: String = read
         |
         |def main uses Console: Unit = printLine(runWriterToLog(greet with scripted(answer) with scripted("outer")))""".stripMargin
    ).asserting(_ shouldBe "Hello, outer!;")
  }

  it should "come from the nearest `with` of the implementation" in {
    compileAndRun(
      s"""$terminal
         |def main uses Console: Unit = printLine(runWriterToLog({
         |   greet
         |   greet with scripted("inner")
         |} with scripted("outer")))""".stripMargin
    ).asserting(_ shouldBe "Hello, outer!;Hello, inner!;")
  }

  it should "reach code passed to a code parameter" in {
    compileAndRun(
      s"""$terminal
         |import eliot.collection.List
         |
         |def main uses Console: Unit =
         |   printLine(runWriterToLog(append(singleton("a"), "b").foreach(s -> write(s ++ read)) with scripted("!")))""".stripMargin
    ).asserting(_ shouldBe "a!;b!;")
  }

  it should "reach a block whose slot rides the definition's own entry" in {
    compileAndRun(
      s"""$terminal
         |def bracketed(body uses *, Terminal: Unit) uses *, Terminal: Unit = {
         |   write("[")
         |   body
         |   write("]")
         |}
         |
         |def main uses Console: Unit = printLine(runWriterToLog(bracketed(greet) with scripted("Bob")))""".stripMargin
    ).asserting(_ shouldBe "[;Hello, Bob!;];")
  }

  it should "include functions" in {
    compileAndRun(
      s"""$terminal
         |implement shouting(lead: String, loud: String => String): Terminal {
         |   def write(line: String) uses Console: Unit = printLine(lead ++ loud(line))
         |   def read: String = "Eve"
         |}
         |
         |def main uses Console: Unit = greet with shouting("> ", s -> s ++ "!!")""".stripMargin
    ).asserting(_ shouldBe "> Hello, Eve!!!")
  }

  it should "leave an effect the implementation performs to the discharge around the `with`" in {
    compileAndRun(
      s"""$terminal
         |implement strict(allowed: String): Terminal {
         |   def write(line: String) uses Throw[String]: Unit = fold(line == allowed, unit, raise("refused " ++ line))
         |   def read: String = allowed
         |}
         |
         |def main uses Console: Unit = printLine(catch[String, String]({
         |   write("ok")
         |   write("nope")
         |   "unreached"
         |} with strict("ok"), e -> e))""".stripMargin
    ).asserting(_ shouldBe "refused nope")
  }

  "a `with` naming an implementation that takes parameters" should "supply them" in {
    compileForErrors(
      s"""$terminal
         |def main uses Console: Unit = printLine(runWriterToLog(greet with scripted))""".stripMargin
    ).asserting(_ should include("The implementation 'scripted' takes parameters, and they are supplied where it is bound"))
  }

  it should "supply as many as it takes" in {
    compileForErrors(
      s"""$terminal
         |def main uses Console: Unit = printLine(runWriterToLog(greet with scripted("a", "b")))""".stripMargin
    ).asserting(_ should include("The implementation 'scripted' takes 1 parameter, but this `with` supplies 2."))
  }

  it should "supply them at the parameters' types" in {
    compileForErrors(
      s"""$terminal
         |def main uses Console: Unit = printLine(runWriterToLog(greet with scripted(1)))""".stripMargin
    ).asserting(_ should include("Type mismatch."))
  }

  it should "not be written on a parameter's clause" in {
    compileForErrors(
      s"""$terminal
         |def run(body uses *, Terminal with scripted("a"): Unit) uses *: String = runWriterToLog(body)
         |def main uses Console: Unit = printLine(run(greet))""".stripMargin
    ).asserting(_ should include("The implementation 'scripted' takes parameters, which a parameter's clause cannot supply."))
  }

  it should "not stand around a run of a code parameter that lists its effect" in {
    compileForErrors(
      s"""$terminal
         |def run(body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted("a"))
         |def main uses Console: Unit = printLine(run(greet))""".stripMargin
    ).asserting(_ should include("and a slot cannot supply arguments"))
  }

  "a `with` supplying arguments" should "name an implementation that takes parameters" in {
    compileForErrors(
      s"""$terminal
         |implement silent: Terminal {
         |   def write(line: String): Unit = unit
         |   def read: String = "x"
         |}
         |
         |def main uses Console: Unit = greet with silent("a")""".stripMargin
    ).asserting(_ should include("The implementation 'silent' takes no parameters."))
  }

  "an implementation with type parameters and parameters" should "be refused where it is bound" in {
    compileForErrors(
      """import eliot.effect.Console
        |
        |effect Source[T] {
        |   def next: T
        |}
        |
        |implement[T] constant(value: T): Source[T] {
        |   def next: T = value
        |}
        |
        |def pick uses Source[String]: String = next
        |
        |def main uses Console: Unit = printLine(pick with constant("v"))""".stripMargin
    ).asserting(_ should include("takes both type parameters and parameters, which is not supported yet"))
  }
}
