package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI
import java.nio.file.Path

/** Compiler-as-platform Increments B and C: the compiler monomorphize track resolves ability instances **in the
  * compiler pool** (B) and **reduces** the resolved call to its normal form by folding the implementation's own body
  * in (C — the compiler backend).
  *
  * The whole scenario lives in the **compiler** source pool (no runtime layer), so this pins that resolution targets
  * the compiler platform, not the default runtime one. The guarding fix is `TypeStackLoop.abilityArity` reading the
  * marker signature from the track's platform: before it, the arity query hit the (empty) runtime pool, the method
  * reference's type arguments were never sliced to the ability prefix, and resolution silently never fired.
  *
  * **Increment D is gone with its subject.** It pinned the carrier of a `{Throw[String]}` sugar signature to the
  * compile-time `Either[String]`, and effects v6 has neither: there is no carrier to pin, `TypeStackLoop.pinCarriers`
  * is deleted, and the compile track's only control effect is `Abort` over its own frame primitive (F5). The ability
  * resolution and the body fold those cases exercised are what is kept here, over an ordinary constructor class — which
  * is the shape that has nothing to do with effects and was always what B and C were about.
  */
class CompilerAbilityResolutionTest extends ProcessorTest(LangProcessors(systemModules = Seq.empty)*) {

  private def compilerScan(pkg: Seq[String], name: String, content: String): Seq[SourceContent | PathScan] = {
    val path = (pkg :+ s"$name.els").foldLeft(Path.of(""))(_ `resolve` _)
    val uri  = URI.create((pkg :+ s"$name.els").mkString("/"))
    Seq(
      PathScan(path, Seq(uri), Platform.Compiler),
      SourceContent(uri, Sourced(uri, PositionRange.zero, content))
    )
  }

  // The leaf stubs the scenario names, all compiler-pool. `Bool` is here because every synthesized `implement` marker's
  // default `true` guard resolves to `eliot.lang.Bool::true` (ability-guards §2.3), and `Implementation` because the
  // `row` phase writes the `Default` sentinel as an ordinary type argument at every ability reference — saturation then
  // demands the value it names, and without it the resolution silently produces nothing.
  private val facts: Seq[com.vanillasource.eliot.eliotc.processor.CompilerFact] =
    compilerScan(Seq("eliot", "compiler"), "Type", "type Type") ++
      compilerScan(Seq("eliot", "lang"), "Function", "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B") ++
      compilerScan(Seq("eliot", "lang"), "String", "type String") ++
      compilerScan(Seq("eliot", "lang"), "Bool", "type Bool\ndef true: Bool\ndef false: Bool") ++
      compilerScan(Seq("eliot", "lang"), "Implementation", ProcessorTest.implementationStubContent) ++
      compilerScan(
        Seq("test"),
        "M",
        """import eliot.lang.Function
          |import eliot.lang.String
          |
          |data Box[A](unbox: A)
          |
          |ability Wrap[F[_]] {
          |   def wrap[A](a: A): F[A]
          |}
          |
          |implement Wrap[Box] {
          |   def wrap[A](a: A): Box[A] = Box(a)
          |}
          |
          |def wrapped: Box[String] = wrap("hello")
          |
          |def rewrapped: Box[String] = wrap(unbox(wrapped))
          |""".stripMargin
      )

  private def fqn(name: String) = ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default))

  private def reducedOf(name: String): IO[Option[MonomorphicExpression.Expression]] =
    runGeneratorWithFacts(facts, CompilerMonomorphicValue.Key(fqn(name), Seq.empty)).map(_._1.flatMap(_.reduced.map(_.value)))

  private def errorsOf(name: String): IO[Seq[TestError]] =
    runGeneratorWithFacts(facts, CompilerMonomorphicValue.Key(fqn(name), Seq.empty)).map { case (_, e) => toTestErrors(e) }

  /** The value references reachable in a reduced body, drilling through applications and lambdas, as `(module, name)`. */
  private def valueRefs(expr: MonomorphicExpression.Expression): Seq[(String, String)] = expr match {
    case MonomorphicExpression.MonomorphicValueReference(vfqn, _)    => Seq((vfqn.value.moduleName.name, vfqn.value.name.name))
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      valueRefs(target.value.expression) ++ valueRefs(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)           => valueRefs(body.value.expression)
    case _                                                           => Seq.empty
  }

  private def refsOf(name: String): IO[Seq[(String, String)]] = reducedOf(name).map(_.toSeq.flatMap(valueRefs))

  /** The string literals surviving in a reduced body — the payload carried through the reduction. */
  private def stringLiteralsOf(expr: MonomorphicExpression.Expression): Seq[String] = expr match {
    case MonomorphicExpression.StringLiteral(v)                     => Seq(v.value)
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      stringLiteralsOf(target.value.expression) ++ stringLiteralsOf(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)          => stringLiteralsOf(body.value.expression)
    case _                                                          => Seq.empty
  }

  private def litsOf(name: String): IO[Seq[String]] = reducedOf(name).map(_.toSeq.flatMap(stringLiteralsOf))

  "the compiler track (Increments B+C)" should "check a value calling an ability method without error" in {
    errorsOf("wrapped").asserting(_ shouldBe Seq.empty)
  }

  it should "reduce the call to the implementation's own body — `wrap(x)` becomes the concrete `Box(x)`" in {
    refsOf("wrapped").asserting(_ shouldBe Seq(("M", "Box")))
  }

  it should "carry the wrapped value into the reduced constructor" in {
    litsOf("wrapped").asserting(_ shouldBe Seq("hello"))
  }

  it should "reduce through a chain, folding the accessor and the implementation together" in {
    errorsOf("rewrapped").asserting(_ shouldBe Seq.empty)
  }

  it should "leave the chain at the same normal form as the value it rewraps" in {
    litsOf("rewrapped").asserting(_ shouldBe Seq("hello"))
  }
}
