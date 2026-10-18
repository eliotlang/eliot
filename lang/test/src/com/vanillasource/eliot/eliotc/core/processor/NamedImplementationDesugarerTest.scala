package com.vanillasource.eliot.eliotc.core.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ast.fact.{AST, FunctionDefinition, SourceAST, Visibility}
import com.vanillasource.eliot.eliotc.ast.processor.ASTParser
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Tokenizer

/** The lowering of a **named** `implement` (`docs/effects.md` D36 point 2): a `data` whose value is the handle a `with`
  * puts in scope, an ordinary instance of the effect's ability at that data type, and the declarations a `with` naming
  * the implementation resolves through.
  */
class NamedImplementationDesugarerTest extends ProcessorTest(new Tokenizer(), new ASTParser()) {
  private val instance = Qualifier.AbilityImplementation("Console", "RecordingConsole$Handle[]", None)
  private val named    = Qualifier.AbilityImplementation("Console", "", Some("recordingConsole"))

  "a bodied named implementation" should "mint its handle data type and constructor" in {
    desugar("implement recordingConsole: Console {\n  def readLine: X = None\n}")
      .asserting(_ should contain.allOf(("RecordingConsole$Handle", Qualifier.Type), ("RecordingConsole$Handle", Qualifier.Default)))
  }

  it should "put every clause in the instance at its handle type" in {
    desugar("implement recordingConsole: Console {\n  def printLine(s: String): Unit = u\n  def readLine: X = None\n}")
      .asserting(_.filter(_._2 == instance).map(_._1) shouldBe Seq("printLine", "readLine", "$captures", "Console"))
  }

  it should "give every clause its own handle as its first parameter" in {
    definitions("implement recordingConsole: Console {\n  def printLine(s: String): Unit = u\n}")
      .asserting(clause(_, "printLine").args.map(_.name.value) shouldBe Seq("self$handle", "s"))
  }

  it should "make every clause public" in {
    definitions("implement recordingConsole: Console {\n  private def readLine: X = None\n}")
      .asserting(clause(_, "readLine").visibility shouldBe Visibility.Public)
  }

  it should "keep the marker and the name marker a `with` resolves through" in {
    desugar("implement recordingConsole: Console {\n  def readLine: X = None\n}")
      .asserting(
        _ should contain.allOf(("Console", named), ("recordingConsole", Qualifier.Implementation("recordingConsole")))
      )
  }

  "a body-less named implementation" should "declare only what a `with` resolves through, minting no handle" in {
    desugar("implement standardConsole: Console {\n  def readLine: X\n}")
      .asserting(_.map(_._1).filter(_.contains("Handle")) shouldBe empty)
  }

  "a named implementation with parameters" should "hold each in a field of its handle" in {
    definitions("implement scripted(answer: String): Terminal {\n  def read: String = answer\n}")
      .asserting(_.map(_.name.value.name) should contain("scripted$answer"))
  }

  "a named implementation's type parameter no parameter fixes" should "be left out of its handle" in {
    definitions("implement[T] showing: Source[T] {\n  def describe(t: T): String = s\n}")
      .asserting(
        _.find(fd => fd.name.value == QualifiedName("Showing$Handle", Qualifier.Type)).get.genericParameters shouldBe empty
      )
  }

  private def clause(definitions: Seq[FunctionDefinition], name: String): FunctionDefinition =
    definitions.find(fd => fd.name.value.name == name && fd.body.isDefined).get

  private def definitions(source: String): IO[Seq[FunctionDefinition]] =
    ast(source).map(_.namedImplementations.flatMap(NamedImplementationDesugarer.desugar).map(_._1))

  private def desugar(source: String): IO[Seq[(String, Qualifier)]] =
    definitions(source).map(_.map(fd => (fd.name.value.name, fd.name.value.qualifier)))

  private def ast(source: String): IO[AST] =
    runGenerator(source, SourceAST.Key(file)).map(_._2.values.collectFirst { case SourceAST(_, Sourced(_, _, a)) =>
      a
    }.get)
}
