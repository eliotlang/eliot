package com.vanillasource.eliot.eliotc.jvm

/** **Handle erasure** (`docs/effects.md` D36 point 11): a handle costs only what it holds. One with no field is no
  * parameter, argument or class; one with a single field is passed as that field; one with more stays its `data`.
  * Each shape is asserted on what it prints and on what the jar contains.
  */
class HandleErasureIntegrationTest extends FullIntegrationTest with EmittedInstructions {

  private val greeter =
    """effect Greeter {
      |   def greeting: String
      |}
      |
      |def greet uses Greeter, Console: Unit = printLine(greeting)
      |""".stripMargin

  private val fieldless =
    """def greet(name: String) uses Console: Unit = printLine("Hi " ++ name)
      |
      |def main uses Console: Unit = greet("Ann")
      |""".stripMargin

  private val oneField =
    s"""$greeter
       |implement fixed(text: String): Greeter {
       |   def greeting: String = text
       |}
       |
       |def main uses Console: Unit = greet with fixed("Hello")
       |""".stripMargin

  private val twoFields =
    s"""$greeter
       |implement joined(first: String, second: String): Greeter {
       |   def greeting: String = first ++ second
       |}
       |
       |def main uses Console: Unit = greet with joined("Hel", "lo")
       |""".stripMargin

  private val takesString = "(Ljava/lang/String;)Ljava/lang/Void;"

  private val joined = "Test$Joined$Handle"

  "a handle with no field" should "run" in {
    compileAndRun(fieldless).asserting(_ shouldBe "Hi Ann")
  }

  it should "be no parameter" in {
    (compileAndRun(fieldless) >> descriptorsOf("greet")).asserting(_ shouldBe Seq(takesString))
  }

  it should "be no class" in {
    (compileAndRun(fieldless) >> classNames).asserting(_.filter(_.endsWith("$Handle")) shouldBe Seq.empty)
  }

  "a handle with one field" should "run" in {
    compileAndRun(oneField).asserting(_ shouldBe "Hello")
  }

  it should "be passed as its field" in {
    (compileAndRun(oneField) >> descriptorsOf("greet")).asserting(_ shouldBe Seq(takesString))
  }

  it should "be no class" in {
    (compileAndRun(oneField) >> classNames).asserting(_.filter(_.endsWith("$Handle")) shouldBe Seq.empty)
  }

  "a handle with two fields" should "run" in {
    compileAndRun(twoFields).asserting(_ shouldBe "Hello")
  }

  it should "stay its data" in {
    (compileAndRun(twoFields) >> classNames).asserting(_.filter(_.endsWith("$Handle")) shouldBe Seq(joined))
  }
}
