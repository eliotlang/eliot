package com.vanillasource.eliot.eliotc.ast.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ast.fact.{AST, SourceAST}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Tokenizer

/** The `uses` clause (`docs/effects.md` D21, step 4): parsed onto the row node the rest of the compiler reads, so each
  * clause is asserted as that node renders, plus the one bit the node lacks. Since step 6 the clause is the only way to
  * write a row outside a row alias's body: the row spelling in any other type position is refused, naming the clause.
  */
class UsesClauseParserTest extends ProcessorTest(new Tokenizer(), new ASTParser()) {
  "a definition's uses clause" should "be the row on its return type" in {
    returnType("def greet(name: String) uses Console: Unit").asserting(_ shouldBe Seq("{Console} Unit"))
  }

  it should "take several entries, with their arguments" in {
    returnType("def f uses Console, Throw[String]: Unit").asserting(_ shouldBe Seq("{Console, Throw[String]} Unit"))
  }

  it should "follow the generic parameters and the parameter list" in {
    returnType("def if[T](condition: Bool, value uses *: T) uses Abort: T").asserting(_ shouldBe Seq("{Abort} T"))
  }

  it should "reject `*`, which only a parameter's clause may write" in {
    errors("def f uses *: Unit").asserting(_ shouldBe Seq("Expected ability name, but encountered symbol '*'."))
  }

  it should "reject a `with`, as on a definition's own row" in {
    errors("def f uses Console with mock: Unit").asserting(_ should not be empty)
  }

  "a parameter's `uses *`" should "be the empty row on a nullary type" in {
    argument("def f(value uses *: T): T").asserting(_ shouldBe Seq(("{} T", false)))
  }

  it should "be the row on the codomain of a function type" in {
    argument("def f[A](action uses *: A => Unit, list: List[A]): Unit")
      .asserting(_ shouldBe Seq(("A => {} Unit", false), ("List[A]", false)))
  }

  it should "reach the final codomain of a curried function type" in {
    argument("def f[A, B](combine uses *: A => B => B): B").asserting(_ shouldBe Seq(("A => B => {} B", false)))
  }

  it should "stop at a parenthesized codomain, which is the function the code hands back" in {
    argument("def f(make uses *: String => (String => Unit)): Unit")
      .asserting(_ shouldBe Seq(("String => {} String => Unit", false)))
  }

  it should "add the entries the callee gives" in {
    argument("def runThrow[E, A](body uses *, Throw[E]: A): A").asserting(_ shouldBe Seq(("{Throw[E]} A", false)))
  }

  it should "put each entry's implementation after the slot's type, in the order written" in {
    argument("def mocked(body uses *, Mocking with recording, Calls with journal: Unit): Unit")
      .asserting(_ shouldBe Seq(("{Mocking, Calls} Unit with recording with journal", false)))
  }

  it should "reject `*` after an entry" in {
    errors("def f(body uses Throw[E], *: A): A").asserting(
      _ shouldBe Seq("Expected ability name, but encountered symbol '*'.")
    )
  }

  it should "reject `*` twice" in {
    errors("def f(body uses *, *: A): A")
      .asserting(_ shouldBe Seq("Expected ability name, but encountered symbol '*'."))
  }

  it should "reject a trailing `with`, which belongs to the entry" in {
    errors("def f(body uses *, Console: Unit with mock): Unit").asserting(_ should not be empty)
  }

  "a parameter's clause without `*`" should "be the same row, closed" in {
    argument("def runPure[E, A](body uses Throw[E]: A): A").asserting(_ shouldBe Seq(("{Throw[E]} A", true)))
  }

  it should "need an entry" in {
    errors("def f(body uses: A): A")
      .asserting(_ shouldBe Seq("Expected symbol '*' or ability name, but encountered symbol ':'."))
  }

  "a parameter with no clause" should "be a value, whatever its type" in {
    argument("def f(body: Unit, g: A => B): Unit").asserting(_ shouldBe Seq(("Unit", false), ("A => B", false)))
  }

  "a data field" should "take no uses clause" in {
    errors("data Job(run uses *: Unit)")
      .asserting(_ shouldBe Seq("Expected symbol ':', but encountered keyword 'uses'."))
  }

  "a row written in a type" should "be refused on a return type, naming the uses clause" in {
    errors("def f: {Console} Unit = a").asserting(_ shouldBe Seq(rowInType))
  }

  it should "be refused on a parameter" in {
    errors("def f(body: {Throw[E]} A): A = body").asserting(_ shouldBe Seq(rowInType))
  }

  it should "be refused when empty" in {
    errors("def f(value: {} T): T = value").asserting(_ shouldBe Seq(rowInType))
  }

  it should "be refused in an arrow codomain" in {
    errors("def f(action: A => {Console} Unit): Unit = a").asserting(_ shouldBe Seq(rowInType))
  }

  it should "be refused on a data field, saying a field holds a value" in {
    errors("data Job(run: {Console} Unit)").asserting(_ shouldBe Seq(rowInField))
  }

  it should "be refused after a uses clause" in {
    errors("def f(body uses *: {Console} Unit): Unit = body").asserting(_ shouldBe Seq(rowInType))
  }

  it should "be refused with a pinned base, which no longer parses as a row at all" in {
    errors("def f: {Throw[E] | Id} Unit = a").asserting(_ should not be empty)
  }

  "a row alias" should "still be written as a row" in {
    aliasBody("type Test = {Writer[List[TestResult]]} Unit")
      .asserting(_ shouldBe Seq("{Writer[List[TestResult]]} Unit"))
  }

  it should "not admit a row anywhere but its whole body" in {
    errors("type Handler = String => {Console} Unit").asserting(_ shouldBe Seq(rowInType))
  }

  private val rowInType: String =
    "Expected a type, with its effects in a `uses` clause before the colon (`def f uses Console: Unit`, " +
      "`body uses *, Throw[E]: A`), but encountered symbol '{'."

  private val rowInField: String =
    "Expected a value type, since a data field holds a value and not a computation (store data describing the work, " +
      "and perform it where the effects are in scope), but encountered symbol '{'."

  private def aliasBody(source: String): IO[Seq[String]] =
    ast(source).map(_.functionDefinitions.flatMap(_.body.map(_.value.render)))

  private def ast(source: String): IO[AST] =
    runGenerator(source, SourceAST.Key(file))
      .map(_._2.values.collectFirst { case SourceAST(_, Sourced(_, _, a)) => a }.get)

  private def errors(source: String): IO[Seq[String]] =
    runGenerator(source, SourceAST.Key(file)).map(_._1.map(_.message))

  private def returnType(source: String): IO[Seq[String]] =
    ast(source).map(_.functionDefinitions.map(_.typeDefinition.value.render))

  private def argument(source: String): IO[Seq[(String, Boolean)]] =
    ast(source).map(_.functionDefinitions.flatMap(_.args.map(a => (a.typeExpression.value.render, a.closedRow))))
}
