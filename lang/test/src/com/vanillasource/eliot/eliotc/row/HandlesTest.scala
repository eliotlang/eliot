package com.vanillasource.eliot.eliotc.row

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

/** The new effect path (`docs/effects.md` D36, `LangProcessors`' `handles`), end to end up to the codegen seam: an
  * effect is an ability over a handle type, a `uses` entry a handle parameter, a named implementation a `data` and its
  * instance, and the checker fills every handle position with the nearest handle in scope whose entry answers it.
  */
class HandlesTest extends ProcessorTest(LangProcessors(handles = true)*) {

  private val greeting =
    """effect Greeting { def say(line: String): Unit }
      |implement shouting(mark: String): Greeting { def say(line: String): Unit = sink(mark) }
      |def sink(s: String): Unit
      |def greet(name: String) uses Greeting: Unit = say(name)
      |""".stripMargin

  "the new effect path" should "compile a call bound by a `with` of a parameterised implementation" in {
    woven(greeting + "def test: Unit = greet(\"Bob\") with shouting(\"!\")").asserting(_ shouldBe Seq.empty)
  }

  it should "call the callee at the implementation's handle type" in {
    references(greeting + "def test: Unit = greet(\"Bob\") with shouting(\"!\")", "test", "greet")
      .asserting(_ shouldBe Seq(Seq(handleType("shouting"))))
  }

  it should "dispatch the operation to the implementation's instance" in {
    targets(greeting + "def test: Unit = greet(\"Bob\") with shouting(\"!\")", "greet", "say", Seq(handleType("shouting")))
      .asserting(_ shouldBe Seq(Qualifier.AbilityImplementation("Greeting", "Shouting$Handle[]", None)))
  }

  private def handleType(implementation: String): GroundValue =
    GroundValue.Structure(ValueFQN(testModuleName, QualifiedName(s"${implementation.capitalize}$$Handle", Qualifier.Type)), Seq.empty, GroundValue.Type)

  private def woven(source: String, name: String = "test", typeArguments: Seq[GroundValue] = Seq.empty): IO[Seq[TestError]] =
    runGenerator(source, WovenValue.Key(ValueFQN(testModuleName, default(name)), typeArguments), systemImports)
      .map { (errors, facts) =>
        if (sys.env.contains("HANDLES_DEBUG")) errors.foreach(e => println(s"${e.message} ${e.description.mkString(" | ")} @ ${e.sourceRange}"))
        if (sys.env.contains("HANDLES_DEBUG")) facts.values.foreach {
          case r: com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue if r.value.vfqn.moduleName == testModuleName =>
            println(s"ROW ${r.value.vfqn.name.name}: ${r.value.runtime.map(b => com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.render(b.value))}")
          case _ => ()
        }
        toTestErrors(errors)
      }

  private val capturing =
    """effect Out { def put(s: String): Unit }
      |effect Note { def note(s: String): Unit }
      |implement recording: Out { def put(s: String) uses Note: Unit = note(s) }
      |implement noting: Note { def note(s: String): Unit = sink(s) }
      |def sink(s: String): Unit
      |def greet uses Out: Unit = put("x")
      |""".stripMargin

  "a capture" should "be filled with the handle in scope at the `with`" in {
    references(capturing + "def test: Unit = (greet with recording) with noting\n", "test", "greet")
      .asserting(_ shouldBe Seq(Seq(GroundValue.Structure(
        ValueFQN(testModuleName, QualifiedName("Recording$Handle", Qualifier.Type)),
        Seq(GroundValue.Structure(ValueFQN(testModuleName, QualifiedName("Noting$Handle", Qualifier.Type)), Seq.empty, GroundValue.Type)),
        GroundValue.Type
      ))))
  }

  it should "dispatch what the clause performs on the captured handle" in {
    targets(
      capturing + "def test: Unit = (greet with recording) with noting\n",
      QualifiedName("put", Qualifier.AbilityImplementation("Out", "Recording$Handle[Capture$0]", None)),
      "note",
      Seq(GroundValue.Structure(ValueFQN(testModuleName, QualifiedName("Noting$Handle", Qualifier.Type)), Seq.empty, GroundValue.Type))
    ).asserting(_ shouldBe Seq(Qualifier.AbilityImplementation("Note", "Noting$Handle[]", None)))
  }

  private val failing =
    """effect Fail[E] { def fail[A](e: E): A }
      |implement[E] failing(code: E): Fail[E] { def fail[A](e: E): A = stop(code) }
      |def stop[A, E](e: E): A
      |data Num
      |def one: Num
      |""".stripMargin

  "a handle position" should "take the handle whose entry its type arguments answer" in {
    references(
      failing + """def failNumber uses Fail[String], Fail[Num]: String = fail(one)
                  |def test: String = failNumber with failing(one) with failing("a")
                  |""".stripMargin,
      "test",
      "failNumber"
    ).asserting(_ shouldBe Seq(Seq(failingType(stringType), failingType(numType))))
  }

  it should "be refused when no handle in scope answers it" in {
    woven(
      failing + "def failNumber uses Fail[String]: String = fail(one)\n",
      "failNumber",
      Seq(failingType(stringType))
    ).asserting(_ shouldBe Seq("No handle in scope here answers 'Fail[Num]'." at "fail(one)"))
  }

  it should "be refused when two equally near handles answer it" in {
    woven(
      failing + "def twice[E](e: E) uses Fail[E], Fail[String]: String = fail(e)\n",
      "twice",
      Seq(failingType(stringType), failingType(stringType), stringType)
    ).asserting(_ shouldBe Seq("Two equally near handles answer 'Fail[String]': 'Fail[String]', 'Fail[String]'." at "fail(e)"))
  }

  it should "refuse a `with` whose handle no hole takes" in {
    woven(
      failing + """def failString uses Fail[String]: String = fail("x")
                  |def test: String = failString with failing(one) with failing("a")
                  |""".stripMargin
    ).asserting(_ shouldBe Seq("This `with` binds nothing: no use of 'Fail[Num]' in its subject takes it." at "failing"))
  }

  it should "refuse a received handle no hole takes" in {
    woven(
      failing + "def failString uses Fail[String], Fail[Num]: String = fail(\"x\")\n",
      "failString",
      Seq(failingType(stringType), failingType(numType))
    ).asserting(_ shouldBe Seq("This value declares the effect 'Fail[Num]' but does not perform it." at "Fail"))
  }

  "a block" should "take the handle its callee's body hands it" in {
    references(
      failing + """def guarded[A](body uses *, Fail[String]: A) uses *: A = body with failing("guarded")
                  |def test: String = guarded(fail("x"))
                  |""".stripMargin,
      "test",
      "guarded"
    ).asserting(_ shouldBe Seq(Seq(stringType, failingType(stringType))))
  }

  it should "dispatch an operation on the handle it was handed" in {
    targets(
      failing + """def guarded[A](body uses *, Fail[String]: A) uses *: A = body with failing("guarded")
                  |def test: String = guarded(fail("x"))
                  |""".stripMargin,
      "test",
      "fail"
    ).asserting(_ shouldBe Seq(Qualifier.AbilityImplementation("Fail", "Failing$Handle[E], E", None)))
  }

  private val catching =
    """def anyOf[E]: E
      |def pick[A](a: A, b: A): A
      |def catching[E, A](body uses *, Fail[E]: A, fallback: A) uses *: A = body with failing(anyOf)
      |""".stripMargin

  it should "decide its callee's type arguments by what every hole it could answer agrees on" in {
    references(failing + catching + "def test: String = catching(fail(one), \"fallback\")\n", "test", "catching")
      .asserting(_ shouldBe Seq(Seq(numType, stringType, failingType(numType))))
  }

  it should "be nearer than the definition's own handle for the same entry" in {
    references(
      failing + catching + "def inner(n: Num) uses Fail[Num]: String = pick(catching(fail(n), \"caught\"), fail(n))\n",
      "inner",
      "catching",
      Seq(failingType(numType))
    ).asserting(_ shouldBe Seq(Seq(numType, stringType, failingType(numType))))
  }

  it should "be refused when the holes it could answer disagree" in {
    woven(
      failing + catching +
        """def two(n: Num) uses Fail[String], Fail[Num]: String = pick(fail("s"), fail(n))
          |def test: String = catching(two(one), "fallback")
          |""".stripMargin
    ).asserting(
      _ shouldBe Seq(
        "Which handle answers 'Fail[String]' depends on type arguments nothing here decides." at "two(one)",
        "Which handle answers 'Fail[Num]' depends on type arguments nothing here decides." at "two(one)"
      )
    )
  }

  private def failingType(argument: GroundValue): GroundValue =
    GroundValue.Structure(
      ValueFQN(testModuleName, QualifiedName("Failing$Handle", Qualifier.Type)),
      Seq(argument),
      GroundValue.Type
    )

  private val stringType: GroundValue =
    GroundValue.Structure(ValueFQN(ModuleName(ModuleName.defaultSystemPackage, "String"), QualifiedName("String", Qualifier.Type)), Seq.empty, GroundValue.Type)

  private val numType: GroundValue =
    GroundValue.Structure(ValueFQN(testModuleName, QualifiedName("Num", Qualifier.Type)), Seq.empty, GroundValue.Type)

  /** The qualifier of every reference to a value named `to` in `in`'s body at `typeArguments`. */
  private def targets(
      source: String,
      in: String | QualifiedName,
      to: String,
      typeArguments: Seq[GroundValue] = Seq.empty
  ): IO[Seq[Qualifier]] = {
    val name = in match {
      case n: QualifiedName => n
      case n: String        => default(n)
    }
    runGenerator(source, WovenValue.Key(ValueFQN(testModuleName, name), typeArguments), systemImports)
      .map { (errors, facts) =>
        if (sys.env.contains("HANDLES_DEBUG")) errors.foreach(e => println(s"${e.message} ${e.description.mkString(" | ")} @ ${e.sourceRange}"))
        if (sys.env.contains("HANDLES_DEBUG")) facts.values.foreach {
          case r: com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue if r.value.vfqn.moduleName == testModuleName =>
            println(s"ROW ${r.value.vfqn.name}: ${com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.render(r.value.signature.value)} = ${r.value.runtime.map(b => com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.render(b.value))}")
          case w: WovenValue if w.vfqn.moduleName == testModuleName => println(s"WOVEN ${w.vfqn.name} ${w.typeArguments} ${w.runtime}")
          case _ => ()
        }
        facts.values
          .collect { case woven: WovenValue if woven.vfqn.name == name => woven.runtime.toSeq.flatMap(b => targetsIn(b.value, to)) }
          .flatten
          .toSeq
      }
  }

  private def targetsIn(expression: MonomorphicExpression.Expression, to: String): Seq[Qualifier] =
    expression match {
      case MonomorphicExpression.MonomorphicValueReference(name, _, _) if name.value.name.name == to => Seq(name.value.name.qualifier)
      case MonomorphicExpression.FunctionApplication(target, argument)                             =>
        targetsIn(target.value.expression, to) ++ targetsIn(argument.value.expression, to)
      case MonomorphicExpression.FunctionLiteral(_, _, body)                                       => targetsIn(body.value.expression, to)
      case _                                                                                       => Seq.empty
    }

  /** The type arguments of every reference to a value named `to` in `in`'s body at `typeArguments`. */
  private def references(
      source: String,
      in: String,
      to: String,
      typeArguments: Seq[GroundValue] = Seq.empty
  ): IO[Seq[Seq[GroundValue]]] =
    runGenerator(source, WovenValue.Key(ValueFQN(testModuleName, default(in)), typeArguments), systemImports)
      .map { (errors, facts) =>
        if (sys.env.contains("HANDLES_DEBUG")) errors.foreach(e => println(s"${e.message} ${e.description.mkString(" | ")} @ ${e.sourceRange}"))
        facts.values
          .collect { case woven: WovenValue if woven.vfqn.name.name == in => woven.runtime.toSeq.flatMap(b => referencesIn(b.value, to)) }
          .flatten
          .toSeq
      }

  private def referencesIn(expression: MonomorphicExpression.Expression, to: String): Seq[Seq[GroundValue]] =
    expression match {
      case MonomorphicExpression.MonomorphicValueReference(name, typeArguments, _) if name.value.name.name == to =>
        Seq(typeArguments)
      case MonomorphicExpression.FunctionApplication(target, argument)                                         =>
        referencesIn(target.value.expression, to) ++ referencesIn(argument.value.expression, to)
      case MonomorphicExpression.FunctionLiteral(_, _, body)                                                   =>
        referencesIn(body.value.expression, to)
      case _                                                                                                   => Seq.empty
    }
}
