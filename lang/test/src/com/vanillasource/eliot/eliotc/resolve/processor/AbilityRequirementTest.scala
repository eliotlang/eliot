package com.vanillasource.eliot.eliotc.resolve.processor

import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue

/** An ability may require other abilities **of the parameter this use bound to this binder**, and a use of it declares
  * what it requires — the superability relation (`ValueResolver.superConstraints`).
  *
  * It is a closure over ordinary binders — a `~ Pretty[T]` bringing `Show[T]` with it — and never names a set of
  * effects, which has no spelling beyond a row alias (`docs/effects.md` D20a). The closure lands in
  * `resolveParamConstraints` only, so what these assert is the resolved constraints of the binder.
  */
class AbilityRequirementTest extends ProcessorTest(LangProcessors()*) {

  private val prelude =
    "ability Show[A] { def show(a: A): String }\n" +
      "ability Pretty[A ~ Show[A]] { def pretty(a: A): String }\n"

  "a required ability" should "be inherited by a `~` constraint naming the ability that requires it" in {
    constraintNames(prelude + "def f[T ~ Pretty[T]](x: T): String = pretty(x)")
      .asserting(_ shouldBe Seq("Pretty", "Show"))
  }

  it should "collapse what the use already writes for itself" in {
    constraintNames(prelude + "def f[T ~ Pretty[T] & Show[T]](x: T): String = pretty(x)")
      .asserting(_ shouldBe Seq("Pretty", "Show"))
  }

  it should "close transitively through another requiring ability" in {
    constraintNames(
      prelude + "ability Report[A ~ Pretty[A]] { def report(a: A): String }\n" +
        "def f[T ~ Report[T]](x: T): String = report(x)"
    ).asserting(_ shouldBe Seq("Report", "Pretty", "Show"))
  }

  it should "substitute the ability's parameters with the arguments the use wrote" in {
    constraintArguments(prelude + "def f[T ~ Pretty[T]](x: T): String = pretty(x)")
      .asserting(_.map(_._1) shouldBe Seq("Pretty", "Show"))
  }

  // `Show` is required of `Keyed`'s *first* parameter, which the use binds to `String` — not to `T`, so it must not
  // join `T`'s constraints, where it would read as a requirement on the wrong type.
  it should "stay on the parameter it was declared for, never landing on an unrelated binder" in {
    constraintNames(
      prelude + "ability Keyed[K ~ Show[K], A] { def key(a: A): K }\n" +
        "def f[T ~ Keyed[String, T]](x: T): String = show(key(x))"
    ).asserting(_ shouldBe Seq("Keyed"))
  }

  it should "close rather than loop when two abilities require each other" in {
    constraintNames(
      "ability A[X ~ B[X]] { def a(x: X): String }\nability B[X ~ A[X]] { def b(x: X): String }\n" +
        "def f[T ~ A[T]](x: T): String = a(x)"
    ).asserting(_.toSet shouldBe Set("A", "B"))
  }

  it should "leave an ability that requires nothing exactly as it was" in {
    constraintNames(prelude + "def f[T ~ Show[T]](x: T): String = show(x)").asserting(_ shouldBe Seq("Show"))
  }

  /** The abilities constrained on the binder `T` of the resolved value. */
  private def constraintNames(source: String, binder: String = "T"): IO[Seq[String]] =
    resolvedValue(source).map(_.paramConstraints.getOrElse(binder, Seq.empty).map(_.abilityFQN.abilityName))

  private def constraintArguments(source: String, binder: String = "T"): IO[Seq[(String, Seq[String])]] =
    resolvedValue(source).map(
      _.paramConstraints
        .getOrElse(binder, Seq.empty)
        .map(c => (c.abilityFQN.abilityName, c.typeArgs.map(_.render)))
    )

  private def resolvedValue(source: String): IO[ResolvedValue] =
    runGenerator(source, ResolvedValue.Key(fVfqn), systemImports).map { case (errors, facts) =>
      if (errors.nonEmpty) throw new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}")
      facts.values.collectFirst { case rv: ResolvedValue if rv.vfqn === fVfqn => rv }.get
    }

  private val fVfqn = ValueFQN(testModuleName, QualifiedName("f", Qualifier.Default))
}
