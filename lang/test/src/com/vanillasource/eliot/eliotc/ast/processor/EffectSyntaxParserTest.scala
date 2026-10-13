package com.vanillasource.eliot.eliotc.ast.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ast.fact.{AST, Expression, SourceAST}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Tokenizer

/** Effects v6's surface, landed dark (`docs/effects.md` §10.1 step 4): `effect`, the named `implement`, and `with` in
  * expression and type position parse into their own AST nodes. Rejection happens at core (see
  * `UnsupportedSyntaxCheckerTest`); here only the parse is pinned.
  */
class EffectSyntaxParserTest extends ProcessorTest(new Tokenizer(), new ASTParser()) {
  "an effect declaration" should "parse into an effect definition with its members" in {
    ast("effect Console {\n  def printLine(s: String): Unit\n  def readLine: Option[String]\n}")
      .asserting(_.effectDefinitions.map(e => (e.name.value, e.functions.map(_.name.value.name))) shouldBe
        Seq(("Console", Seq("printLine", "readLine"))))
  }

  it should "carry its generic parameters" in {
    ast("effect Throw[E] {\n  def raise[A](err: E): A\n}")
      .asserting(_.effectDefinitions.flatMap(_.genericParameters.map(_.name.value)) shouldBe Seq("E"))
  }

  it should "not add its members to the ordinary function definitions" in {
    ast("effect Console {\n  def printLine(s: String): Unit\n}").asserting(_.functionDefinitions shouldBe empty)
  }

  it should "parse with no body" in {
    ast("effect Marker").asserting(_.effectDefinitions.map(_.name.value) shouldBe Seq("Marker"))
  }

  it should "still allow effect as a package segment in an import" in {
    ast("import eliot.effect.Console")
      .asserting(_.importStatements.map(i => (i.packageNames :+ i.moduleName).map(_.value).mkString(".")) shouldBe Seq("eliot.effect.Console"))
  }

  it should "still allow effect as a package segment in a qualified reference" in {
    ast("def a: X = eliot.effect.Console::printLine(\"x\")").asserting(_.functionDefinitions.size shouldBe 1)
  }

  "a named implementation" should "parse into a named implementation with its ability and members" in {
    ast("implement recordingConsole: Console {\n  def readLine: Option[String] = None\n}")
      .asserting(_.namedImplementations.map(n => (n.name.value, n.ability.value, n.functions.map(_.name.value.name)))
        shouldBe Seq(("recordingConsole", "Console", Seq("readLine"))))
  }

  it should "carry the ability's pattern" in {
    ast("implement stringThrow: Throw[String] {\n  def raise[A](err: String): A = a\n}")
      .asserting(_.namedImplementations.flatMap(_.pattern.map(_.value.render)) shouldBe Seq("String"))
  }

  it should "leave an anonymous implement block as it was" in {
    ast("implement Show[String] {\n  def show(t: String): String = t\n}")
      .asserting(a => (a.namedImplementations, a.functionDefinitions.size) shouldBe (Seq.empty, 2))
  }

  "a named implementation with parameters" should "carry them between its name and its ability" in {
    ast("implement scripted(answer: String, count: Int): Terminal {\n  def read: String = answer\n}")
      .asserting(_.namedImplementations.map(n => (n.name.value, n.parameters.map(_.name.value), n.ability.value)) shouldBe
        Seq(("scripted", Seq("answer", "count"), "Terminal")))
  }

  "a named implementation without parameters" should "carry none" in {
    ast("implement recordingConsole: Console {\n  def readLine: Option[String] = None\n}")
      .asserting(_.namedImplementations.flatMap(_.parameters) shouldBe empty)
  }

  "an anonymous implementation with parameters" should "give each clause the parameters ahead of its own" in {
    ast("implement[S] State[S](cell: Cell[S]) {\n  def putState(s: S): Unit = write(cell, s)\n}")
      .asserting(_.functionDefinitions.map(f => (f.name.value.name, f.args.map(_.name.value))) shouldBe
        Seq(("putState", Seq("cell", "s")), ("$parameters", Seq("cell")), ("State", Seq("arg0"))))
  }

  "an anonymous implementation without parameters" should "declare no parameters" in {
    ast("implement Show[String] {\n  def show(t: String): String = t\n}")
      .asserting(_.functionDefinitions.map(_.name.value.name) shouldBe Seq("show", "Show"))
  }

  "with in expression position" should "bind the whole flat expression as its subject" in {
    body("def a: X = xs.sort.render with reverseOrd")
      .asserting(_ shouldBe "xs . sort . render with reverseOrd")
  }

  it should "be left-associative" in {
    body("def a: X = c with a with b").asserting(_ shouldBe "c with a with b")
  }

  it should "nest as a whole under an outer binding" in {
    bodyNode("def a: X = c with a with b").asserting {
      case Some(Expression.WithBinding(Sourced(_, _, Expression.WithBinding(_, inner)), outer)) =>
        (inner.value.render, outer.value.render) shouldBe ("a", "b")
      case other                                                                                => fail(s"unexpected: $other")
    }
  }

  it should "bind loosest inside a call argument" in {
    body("def a: X = written(greeting(name) with recordingConsole)")
      .asserting(_ shouldBe "written(greeting(name) with recordingConsole)")
  }

  it should "supply arguments to an implementation that takes parameters" in {
    bodyNode("def a: X = greet with scripted(name, 1)").asserting {
      case Some(Expression.WithBinding(_, Sourced(_, _, Expression.FunctionApplication(None, name, None, arguments)))) =>
        (name.value, arguments.map(_.value.render)) shouldBe ("scripted", Seq("name", "1"))
      case other                                                                                                    => fail(s"unexpected: $other")
    }
  }

  it should "accept a module-qualified implementation name" in {
    body("def a: X = c with Test::mock").asserting(_ shouldBe "c with Test::mock")
  }

  it should "apply to a block line" in {
    bodyNode("def a: X = {\n  val r = run with mock\n  r\n}").asserting {
      case Some(Expression.FlatExpression(Seq(Sourced(_, _, Expression.BlockExpression(lines))))) =>
        lines.head.expression.value.render shouldBe "run with mock"
      case other                                   => fail(s"unexpected: $other")
    }
  }

  "with in type position" should "bind a parameter's type, after its clause's entry" in {
    argumentType("def mocked(body uses *, Console with mockConsole: Unit) uses *: Unit")
      .asserting(_ shouldBe Seq("{Console} Unit with mockConsole"))
  }

  it should "bind a data field's type" in {
    ast("data Suite(cases: Unit with recordingConsole)")
      .asserting(_.typeDefinitions.flatMap(_.constructors.toSeq.flatten.flatMap(_.fields.map(_.typeExpression.value.render)))
        shouldBe Seq("Unit with recordingConsole"))
  }

  it should "be rejected on a def's own return type" in {
    runEngineForErrors("def a: Unit with mock = b").asserting(_ should not be empty)
  }

  it should "be rejected inside a row alias" in {
    runEngineForErrors("type Mocked = {Console with mock} Unit").asserting(_ should not be empty)
  }

  private def ast(source: String): IO[AST] =
    runGenerator(source, SourceAST.Key(file)).map(_._2.values.collectFirst { case SourceAST(_, Sourced(_, _, a)) => a }.get)

  private def runEngineForErrors(source: String): IO[Seq[String]] =
    runGenerator(source, SourceAST.Key(file)).map(_._1.map(_.message))

  private def bodyNode(source: String): IO[Option[Expression]] =
    ast(source).map(_.functionDefinitions.headOption.flatMap(_.body.map(_.value)))

  private def body(source: String): IO[String] = bodyNode(source).map(_.get.render)

  private def argumentType(source: String): IO[Seq[String]] =
    ast(source).map(_.functionDefinitions.flatMap(_.args.map(_.typeExpression.value.render)))
}
