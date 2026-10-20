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
    returnType("def if[T](condition: Bool, value uses *: T) uses *, Abort: T").asserting(_ shouldBe Seq("{Abort} T"))
  }

  it should "reject `*` when no parameter has one" in {
    errors("def f uses *: Unit").asserting(_ shouldBe Seq(strayStar))
  }

  it should "reject `*` when the only clause on a parameter is closed" in {
    errors("def f[E, A](body uses Throw[E]: A) uses *: A").asserting(_ shouldBe Seq(strayStar))
  }

  "a definition with a `uses *` parameter" should "write `*` alone, which adds no entry" in {
    returnType("def map[A, B](f uses *: A => B, list: List[A]) uses *: List[B]").asserting(_ shouldBe Seq("List[B]"))
  }

  it should "write `*` first, before its entries" in {
    returnType("def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T")
      .asserting(_ shouldBe Seq("{Abort} T"))
  }

  it should "be refused without a clause" in {
    errors("def when(condition: Bool, action uses *: Unit): Unit")
      .asserting(_ shouldBe Seq(s"Expected keyword 'where' or $openClause, but encountered symbol ':'."))
  }

  it should "be refused with a clause not starting with `*`" in {
    errors("def f(body uses *: Unit) uses Console: Unit")
      .asserting(_ shouldBe Seq(s"Expected $openClause, but encountered identifier 'Console'."))
  }

  it should "be refused with `*` after an entry" in {
    errors("def f(body uses *: Unit) uses Console, *: Unit")
      .asserting(_ shouldBe Seq(s"Expected $openClause, but encountered identifier 'Console'."))
  }

  it should "count a function-typed code parameter" in {
    errors("def catch[E, A](computation uses Throw[E]: A, onError uses *: E => A): A")
      .asserting(_ shouldBe Seq(s"Expected keyword 'where' or $openClause, but encountered symbol ':'."))
  }

  it should "reject a `with`, as on a definition's own row" in {
    errors("def f uses Console with mock: Unit").asserting(_ should not be empty)
  }

  "a parameter's `uses *`" should "be the empty row on a nullary type" in {
    argument("def f(value uses *: T) uses *: T").asserting(_ shouldBe Seq(("{} T", false)))
  }

  it should "be the row on the codomain of a function type" in {
    argument("def f[A](action uses *: A => Unit, list: List[A]) uses *: Unit")
      .asserting(_ shouldBe Seq(("A => {} Unit", false), ("List[A]", false)))
  }

  it should "reach the final codomain of a curried function type" in {
    argument("def f[A, B](combine uses *: A => B => B) uses *: B").asserting(_ shouldBe Seq(("A => B => {} B", false)))
  }

  it should "stop at a parenthesized codomain, which is the function the code hands back" in {
    argument("def f(make uses *: String => (String => Unit)) uses *: Unit")
      .asserting(_ shouldBe Seq(("String => {} String => Unit", false)))
  }

  it should "add the entries the callee gives" in {
    argument("def runThrow[E, A](body uses *, Throw[E]: A) uses *: A")
      .asserting(_ shouldBe Seq(("{Throw[E]} A", false)))
  }

  it should "refuse a `with` after an entry, naming the `with` around the run" in {
    errors("def mocked(body uses *, Mocking with recording, Calls: Unit) uses *: Unit").asserting(
      _ shouldBe Seq(
        "Expected no `with` on a parameter, since code's effects are bound where it runs, by a `with` around the run " +
          "in the body (`body with fake`), but encountered keyword 'with'."
      )
    )
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
    errors("def f(body uses *, Console: Unit with mock) uses *: Unit").asserting(_ should not be empty)
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
    errors("def f(body uses *: {Console} Unit) uses *: Unit = body").asserting(_ shouldBe Seq(rowInType))
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

  private val openClause: String =
    "`uses *` before the colon, since a parameter runs code its caller wrote and the call performs what that code " +
      "does (`def map[A, B](f uses *: A => B, list: List[A]) uses *: List[B]`)"

  private val strayStar: String =
    "Expected ability name, since only a definition with a `uses *` parameter writes `*` in its own clause, " +
      "but encountered symbol '*'."

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
