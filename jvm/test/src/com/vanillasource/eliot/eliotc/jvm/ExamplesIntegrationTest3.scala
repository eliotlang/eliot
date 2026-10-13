package com.vanillasource.eliot.eliotc.jvm

/** Part 3 of 4 of the end-to-end example-program suite (see [[FullIntegrationTest]]). The 88 cases are split across
  * four classes so Mill runs them in four parallel test-worker JVMs — each warming its own resident compilation
  * session — instead of one worker compiling all 88 serially. Keep each part self-contained: a case that shares a
  * class-level helper (e.g. `orderingPrelude`) must stay in the same part as that helper. */
class ExamplesIntegrationTest3 extends FullIntegrationTest {

  // Leading-dot continuation lines merge into one expression by fixity (the lower line starts with the infix `.`), so a
  // method-style chain can be written across lines inside a block.
  "a leading-dot chain split across block lines" should "merge into one expression" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[A](content: A)
        |
        |def map[A, B](f: Function[A, B], box: Box[A]): Box[B] = Box(f(content(box)))
        |
        |def as[A, B](b: B, box: Box[A]): Box[B] = box.map(_ -> b)
        |
        |def main uses Console: Unit = {
        |  val result: Box[String] = Box("Hello")
        |    .map(_ -> "Earth!")
        |    .as("World!")
        |  printLine(content(result))
        |}""".stripMargin
    ).asserting(_ shouldBe "World!")
  }

  // The `examples/src/Blocks.els` shape: a two-effect {Console, State[String]} interaction in block form, with a `val`
  // binding the carried result of a sub-computation (`swap`). Console and State share one carrier, threaded across the
  // block; pinned here so the shipped example cannot silently regress.
  "a {Console, State} interaction in block form (the Blocks example)" should "run end to end" in {
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
        |def rename(next: String) uses Console, State[String]: Unit = {
        |  printLine("renaming the account...")
        |  val previous = swap(next)
        |  printLine(previous)
        |}
        |
        |def main uses Console: Unit = {
        |  val outcome = runStateToPair("before", rename("after"))
        |  printLine(second(outcome))
        |}""".stripMargin
    ).asserting(_ shouldBe "renaming the account...\nbefore\nafter")
  }

  // A nested block as a `val`'s right-hand side: both blocks lower, the inner producing the bound value.
  "a nested block" should "compute the inner block's value and bind it" in {
    compileAndRun(
      """import eliot.effect.Console
        |def main uses Console: Unit = {
        |  val x = {
        |    val inner = "deep"
        |    inner
        |  }
        |  printLine(x)
        |}""".stripMargin
    ).asserting(_ shouldBe "deep")
  }

  // A block ending in a binding is rejected (a block must end in an expression); the fail-safe is a hard error, not a
  // silent miscompile.
  "a block ending in a binding" should "be rejected" in {
    compileForErrors(
      """import eliot.effect.Console
        |def main uses Console: Unit = {
        |  printLine("x")
        |  val leftover = "oops"
        |}""".stripMargin
    ).asserting(_ should include("A block must end in an expression, not a binding."))
  }

  "ability" should "dispatch to correct implementation" in {
    compileAndRun(
      """import eliot.effect.Console
        |ability Display[A] {
        |   def display(a: A): String
        |}
        |
        |data Hello(name: String)
        |
        |implement Display[Hello] {
        |   def display(a: Hello): String = "Hello World!"
        |}
        |
        |def main uses Console: Unit = printLine(display(Hello("World")))""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "ability constraint" should "pass ability through generic function" in {
    compileAndRun(
      """import eliot.effect.Console
        |ability Display[A] {
        |   def display(a: A): String
        |}
        |
        |data Hello(name: String)
        |
        |implement Display[Hello] {
        |   def display(a: Hello): String = "Hello World!"
        |}
        |
        |def displayAnything[A ~ Display](thing: A): String = display(thing)
        |
        |def main uses Console: Unit = printLine(displayAnything(Hello("World")))""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "ability derive" should "derive implementation for generic type" in {
    compileAndRun(
      """import eliot.effect.Console
        |ability Display[A] {
        |  def display(a: A): String
        |}
        |
        |implement Display[String] {
        |  def display(str: String): String = str
        |}
        |
        |data Box[A](content: A)
        |
        |implement[A ~ Display] Display[Box[A]] {
        |  def display(box: Box[A]): String = display(content(box))
        |}
        |
        |def main uses Console: Unit = printLine(display(Box("Hello World!")))""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "generic types" should "support type-level integer parameters" in {
    compileAndRun(
      """import eliot.effect.Console
        |def hello[I: BigInteger]: String = "Hello World!"
        |
        |def main uses Console: Unit = printLine(hello[1])""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "pattern matching" should "match data constructors and extract values" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Maybe[A] = Nothing | Just(value: A)
        |
        |def describe(m: Maybe[String]): String = m match {
        |  case Nothing -> "empty"
        |  case Just(v) -> v
        |}
        |
        |def main uses Console: Unit = printLine(describe(Just("hello")))""".stripMargin
    ).asserting(_ shouldBe "hello")
  }

  "operators" should "evaluate infix operators with correct associativity" in {
    compileAndRun(
      """import eliot.effect.Console
        |def main uses Console: Unit = printLine(content(Cell("Hello") | Cell("World") | Cell("!")))
        |
        |data Cell(content: String)
        |
        |infix left
        |def |(lhs: Cell, rhs: Cell): Cell = rhs""".stripMargin
    ).asserting(_ shouldBe "!")
  }

  "handle with" should "support multiple data types with pattern matching" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Something = Else | Other
        |
        |data Greeting = Hello | Goodbye
        |
        |def greet(g: Greeting): String = g match {
        |  case Hello -> "Hello!"
        |  case Goodbye -> "Goodbye!"
        |}
        |
        |def something(s: Something): String = s match {
        |  case Else -> "Else!"
        |  case Other -> "Other!"
        |}
        |
        |infix def or(s1: String, s2: String): String = s1
        |
        |def main uses Console: Unit = printLine(something(Else) or greet(Goodbye))""".stripMargin
    ).asserting(_ shouldBe "Else!")
  }

  "monomorph check" should "handle dependent type integer arithmetic" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[I: BigInteger](content: String)
        |
        |def someFunction[I: BigInteger](arg: String): Box[I + 1] = Box[3](arg)
        |
        |def main uses Console: Unit = printLine(content(someFunction[2]("Hello World!")))""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "function as type" should "use type-level computation for types" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[A](content: A)
        |
        |def stringBox: Type = Box[String]
        |
        |def stringBoxWithContent: stringBox = Box("Hello World!")
        |
        |def main uses Console: Unit = printLine(content(stringBoxWithContent))""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "type values" should "match on type-level values" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Person[NAME: String](content: String)
        |
        |data Box[A](a: A)
        |
        |def personName(t: Type): String = t match {
        |   case Person[name] -> name
        |   case _            -> "<not a person>"
        |}
        |
        |def main uses Console: Unit = printLine(personName(Person["John"]))""".stripMargin
    ).asserting(_ shouldBe "John")
  }

  "dot operator" should "support method-style chaining" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Box[A](content: A)
        |
        |def filter[A](expr: String, box: Box[A]): Box[A] = box
        |
        |def map[A, B](f: Function[A, B], box: Box[A]): Box[B] = Box(f(content(box)))
        |
        |def flatMap[A, B](f: Function[A, Box[B]], box: Box[A]): Box[B] = f(content(box))
        |
        |def as[A, B](b: B, box: Box[A]): Box[B] = box.map(_ -> b)
        |
        |def logic: Box[String] = Box("Hello").filter("Expr").map(_ -> "Earth!").as("World!")
        |
        |def main uses Console: Unit = printLine(logic.content)""".stripMargin
    ).asserting(_ shouldBe "World!")
  }

  "unicode" should "support unicode operator names" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(<===>)
        |
        |def <===>: String = "Hello World!"""".stripMargin
    ).asserting(_ shouldBe "Hello World!")
  }

  "integer addition" should "compute and print a sum at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(3 + 4))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  "integer subtraction" should "compute and print a difference at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(10 - 4))""".stripMargin
    ).asserting(_ shouldBe "6")
  }

  "integer arithmetic" should "respect operator precedence at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(2 + 3 * 4))""".stripMargin
    ).asserting(_ shouldBe "14")
  }

  it should "compute a negative result at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(3 - 10))""".stripMargin
    ).asserting(_ shouldBe "-7")
  }

  // Byte operands whose sum overflows a byte carry up to a Short result: 100 and 100 each fit `Byte` ([-128,127]) but
  // 200 does not, so the `Numeric[Int]` `add`'s dependent result bound (`[200,200]`) is laid out at the wider
  // representation and the emission reboxes there.
  it should "carry a byte-operand sum into a wider result representation at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(100 + 100))""".stripMargin
    ).asserting(_ shouldBe "200")
  }

  // Short operands whose difference fits a byte: 1000 and 999 are `Short`, but the `Numeric[Int]` `subtract`'s result range [1,1]
  // fits `Byte`, so the emission reboxes the `long` result directly at the `Byte` representation (additive cancellation)
  // — no separate narrowing step.
  it should "narrow a short-operand difference into a byte result at runtime" in {
    compileAndRun(
      """import eliot.effect.Console
def main uses Console: Unit = printLine(show(1000 - 999))""".stripMargin
    ).asserting(_ shouldBe "1")
  }

}
