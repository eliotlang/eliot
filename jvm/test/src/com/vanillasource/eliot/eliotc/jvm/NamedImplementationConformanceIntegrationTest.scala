package com.vanillasource.eliot.eliotc.jvm

/** A **named** implementation is checked against its ability as an anonymous one is: every member given, nothing
  * extra, each clause at its member's signature (`docs/effects.md` §8 item 42). It is never searched, so the check is
  * made where a binding dispatches to it; before, a missing or mistyped clause compiled and failed at runtime with a
  * `ClassCastException`.
  */
class NamedImplementationConformanceIntegrationTest extends FullIntegrationTest {

  private val terminal =
    """import eliot.effect.Console
      |
      |effect Terminal {
      |   def write(line: String): Unit
      |   def read: String
      |}
      |
      |def greet uses Terminal: String = {
      |   write("Hello")
      |   read
      |}
      |""".stripMargin

  private val main = "def main uses Console: Unit = printLine(greet with session)"

  "a named implementation" should "give every member of its ability" in {
    compileForErrors(
      s"""$terminal
         |implement session: Terminal {
         |   def write(line: String): Unit = unit
         |}
         |$main""".stripMargin
    ).asserting(_ should include("Ability implementation is missing method 'read'."))
  }

  it should "give no member its ability does not declare" in {
    compileForErrors(
      s"""$terminal
         |implement session: Terminal {
         |   def write(line: String): Unit = unit
         |   def read: String = "x"
         |   def bogus: String = "x"
         |}
         |$main""".stripMargin
    ).asserting(_ should include("Method not defined in ability."))
  }

  it should "give each member at its signature" in {
    compileForErrors(
      s"""$terminal
         |implement session: Terminal {
         |   def write(line: String): Unit = unit
         |   def read: Int = 42
         |}
         |$main""".stripMargin
    ).asserting(_ should include("Signature of implementation does not match the ability definition."))
  }

  it should "be checked even where the mistyped member is not the one called" in {
    compileForErrors(
      s"""$terminal
         |implement session: Terminal {
         |   def write(line: String, extra: String): Unit = unit
         |   def read: String = "x"
         |}
         |def main uses Console: Unit = printLine(read with session)""".stripMargin
    ).asserting(_ should include("Signature of implementation does not match the ability definition."))
  }

  it should "compare a clause without the implementation's own parameters" in {
    compileAndRun(
      s"""$terminal
         |implement session(answer: String): Terminal {
         |   def write(line: String): Unit = unit
         |   def read: String = answer
         |}
         |def main uses Console: Unit = printLine(greet with session("Bob"))""".stripMargin
    ).asserting(_ shouldBe "Bob")
  }

  it should "be checked when it takes parameters" in {
    compileForErrors(
      s"""$terminal
         |implement session(answer: Int): Terminal {
         |   def write(line: String): Unit = unit
         |   def read: Int = answer
         |}
         |def main uses Console: Unit = printLine(greet with session(42))""".stripMargin
    ).asserting(_ should include("Signature of implementation does not match the ability definition."))
  }
}
