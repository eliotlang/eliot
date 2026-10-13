package com.vanillasource.eliot.eliotc.core.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ast.fact.{AST, SourceAST, Visibility}
import com.vanillasource.eliot.eliotc.ast.processor.ASTParser
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Tokenizer

/** The lowering of a **named** `implement` (effects v6, `docs/effects.md` §9.4 step 1). Landed dark: `with` and the
  * named form are still rejected at core, so nothing in the tree reaches this yet and the lowering is pinned here.
  */
class NamedImplementationDesugarerTest extends ProcessorTest(new Tokenizer(), new ASTParser()) {
  private val console = Qualifier.AbilityImplementation("Console", "", Some("recordingConsole"))

  "a named implementation" should "put every clause in its own implementation namespace" in {
    desugar("implement recordingConsole: Console {\n  def printLine(s: String): Unit = u\n  def readLine: X = None\n}")
      .asserting(_.filter(_._2 == console).map(_._1) shouldBe Seq("printLine", "readLine", "Console"))
  }

  it should "mint the implementation's marker beside the clauses" in {
    desugar("implement recordingConsole: Console {\n  def readLine: X = None\n}")
      .asserting(_ should contain(("Console", console)))
  }

  it should "mint the name marker that `with` looks up" in {
    desugar("implement recordingConsole: Console {\n  def readLine: X = None\n}")
      .asserting(_ should contain(("recordingConsole", Qualifier.Implementation("recordingConsole"))))
  }

  it should "key the implementation on its pattern as well as its name" in {
    desugar("implement stringThrow: Throw[String] {\n  def raise[A](err: String): A = a\n}")
      .asserting(_ should contain(("Throw", Qualifier.AbilityImplementation("Throw", "String", Some("stringThrow")))))
  }

  it should "make every clause public" in {
    definitions("implement recordingConsole: Console {\n  private def readLine: X = None\n}")
      .asserting(_.map(_.visibility).distinct shouldBe Seq(Visibility.Public))
  }

  it should "prepend the implementation's own generic parameters to every clause" in {
    definitions("implement[E] loggingThrow: Throw[E] {\n  def raise[A](err: E): A = a\n}")
      .asserting(_.head.genericParameters.map(_.name.value) shouldBe Seq("E", "A"))
  }

  it should "give the marker one argument per pattern element" in {
    definitions("implement stringThrow: Throw[String] {\n  def raise[A](err: String): A = a\n}")
      .asserting(_.find(_.name.value.name == "Throw").get.args.map(_.typeExpression.value.render) shouldBe Seq("String"))
  }

  it should "leave the marker unguarded" in {
    definitions("implement recordingConsole: Console {\n  def readLine: X = None\n}")
      .asserting(_.find(_.name.value.name == "Console").get.typeDefinition.value.render shouldBe "eliot.lang.Bool::true")
  }

  it should "give the marker the clause row so `with` can write what the implementation performs" in {
    definitions("implement recordingConsole: Console {\n  def printLine(s: String) uses Writer[String]: Unit = t\n}")
      .asserting(_.find(_.name.value.name == "Console").get.typeDefinition.value.render shouldBe "{Writer[String]} eliot.lang.Bool::true")
  }

  it should "give every clause the union of the clauses' rows, so all of them declare one prefix" in {
    definitions("implement recordingConsole: Console {\n  def printLine(s: String) uses Writer[String]: Unit = t\n  def readLine: X = None\n}")
      .asserting(_.filter(_.body.isDefined).map(_.typeDefinition.value.render) shouldBe Seq("{Writer[String]} Unit", "{Writer[String]} X"))
  }

  it should "leave an associated type out of the clause row" in {
    definitions("implement recordingConsole: Console {\n  def printLine(s: String) uses Writer[String]: Unit = t\n  type Buffer = X\n}")
      .asserting(_.find(_.name.value.name == "Buffer").get.typeDefinition.value.render shouldBe "Type")
  }

  private def definitions(source: String) =
    ast(source).map(_.namedImplementations.flatMap(NamedImplementationDesugarer.desugar))

  private def desugar(source: String): IO[Seq[(String, Qualifier)]] =
    definitions(source).map(_.map(fd => (fd.name.value.name, fd.name.value.qualifier)))

  private def ast(source: String): IO[AST] =
    runGenerator(source, SourceAST.Key(file)).map(_._2.values.collectFirst { case SourceAST(_, Sourced(_, _, a)) =>
      a
    }.get)
}
