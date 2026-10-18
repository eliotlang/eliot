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

  private def woven(source: String, name: String = "test"): IO[Seq[TestError]] =
    runGenerator(source, WovenValue.Key(ValueFQN(testModuleName, default(name)), Seq.empty), systemImports)
      .map { (errors, _) =>
        if (sys.env.contains("HANDLES_DEBUG")) errors.foreach(e => println(s"${e.message} ${e.description.mkString(" | ")} @ ${e.sourceRange}"))
        toTestErrors(errors)
      }

  /** The qualifier of every reference to a value named `to` in `in`'s body at `typeArguments`. */
  private def targets(
      source: String,
      in: String,
      to: String,
      typeArguments: Seq[GroundValue] = Seq.empty
  ): IO[Seq[Qualifier]] =
    runGenerator(source, WovenValue.Key(ValueFQN(testModuleName, default(in)), typeArguments), systemImports)
      .map((_, facts) =>
        facts.values
          .collect { case woven: WovenValue if woven.vfqn.name.name == in => woven.runtime.toSeq.flatMap(b => targetsIn(b.value, to)) }
          .flatten
          .toSeq
      )

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
