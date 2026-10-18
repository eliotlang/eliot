package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, GroundValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibNativesProcessor

import java.net.URI
import java.nio.file.Path

/** Ability guards Stage 0: the `Eq` ability plus its `==`/`!=` operators resolve, through a first-order ability
  * constraint (`[A ~ Eq]`), to the compiler-pool `Eq[Type]` instance and its body-less `equals` leaf.
  *
  * The leaf's *reduction* is pinned in [[EqTypeLeafTest]]; this suite pins the *resolution wiring* around it on the
  * compiler track: monomorphizing `==` and `!=` at `A = Type` resolves the `Eq[Type]` instance and inlines the operator
  * to its `equals` impl method. That is the whole chain a guard `where E1 != E2` will lean on — operator ⇒ ability
  * method ⇒ compiler-pool instance ⇒ native-on-impl leaf — minus the concrete-argument firing (which needs a ground use
  * site, i.e. a landed guard). The `Eq` machinery mirrors the shipped split (`stdlib/.../Eq.els` + `lang/.../Eq.els`)
  * flattened into one compiler-pool file; `Function` is auto-imported as in a real build so the arrow-typed signatures
  * resolve.
  */
class EqOperatorResolutionTest
    extends ProcessorTest(
      (LangProcessors(
        systemModules = Seq(ModuleName(Seq("eliot", "lang"), "Function")),
        extraNativeBindingLabels = Seq(StdlibNativesProcessor.stdlibLabel)
      ) :+ StdlibNativesProcessor())*
    ) {

  private def compilerScan(pkg: Seq[String], name: String, content: String): Seq[SourceContent | PathScan] = {
    val path = (pkg :+ s"$name.els").foldLeft(Path.of(""))(_ `resolve` _)
    val uri  = URI.create((pkg :+ s"$name.els").mkString("/"))
    Seq(
      PathScan(path, Seq(uri), Platform.Compiler),
      SourceContent(uri, Sourced(uri, PositionRange.zero, content))
    )
  }

  private val eqContent: String =
    """import eliot.lang.Bool
      |
      |ability Eq[A] {
      |   def equals(a: A, b: A): Bool
      |}
      |
      |infix left above &&
      |def ==[A ~ Eq](a: A, b: A): Bool = equals(a, b)
      |
      |infix left at ==
      |def !=[A ~ Eq](a: A, b: A): Bool = fold(equals(a, b), false, true)
      |
      |implement Eq[Type] {
      |   def equals(a: Type, b: Type): Bool
      |}
      |""".stripMargin

  private val facts: Seq[com.vanillasource.eliot.eliotc.processor.CompilerFact] =
    compilerScan(Seq("eliot", "compiler"), "Type", "type Type") ++
      compilerScan(
        Seq("eliot", "lang"),
        "Function",
        "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B"
      ) ++
      compilerScan(
        Seq("eliot", "lang"),
        "Bool",
        "type Bool\ndef true: Bool\ndef false: Bool\ninfix def &&(a: Bool, b: Bool): Bool\ndef fold[A](condition: Bool, whenTrue: A, whenFalse: A): A"
      ) ++
      // The `Eq` block declares a binding binder, whose `Implementation[A]` mark and `Default` sentinel the compiler
      // writes module-qualified — so this module is in every pool.
      compilerScan(Seq("eliot", "lang"), "Implementation", ProcessorTest.implementationStubContent) ++
      compilerScan(Seq("eliot", "lang"), "Eq", eqContent)

  private def eqFqn(name: String) =
    ValueFQN(ModuleName(Seq("eliot", "lang"), "Eq"), QualifiedName(name, Qualifier.Default))

  /** Monomorphize `name` from `eliot.lang.Eq` at `A = Type` and return `(errors, reduced-body value references)`. */
  private def resolveAt(name: String): IO[(Seq[TestError], Seq[(String, String)])] =
    runGeneratorWithFacts(facts, CompilerMonomorphicValue.Key(eqFqn(name), Seq(GroundValue.Type))).map { case (r, e) =>
      (toTestErrors(e), r.flatMap(_.reduced.map(v => valueRefs(v.value))).getOrElse(Seq.empty))
    }

  private def valueRefs(expr: MonomorphicExpression.Expression): Seq[(String, String)] = expr match {
    case MonomorphicExpression.MonomorphicValueReference(vfqn, _)    => Seq((vfqn.value.moduleName.name, vfqn.value.name.name))
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      valueRefs(target.value.expression) ++ valueRefs(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)           => valueRefs(body.value.expression)
    case _                                                           => Seq.empty
  }

  "monomorphizing `==` at A = Type" should "type-check without error (the Eq[Type] instance resolves)" in {
    // Absence of an error here IS the proof the `Eq[Type]` instance was found: an unresolved ability constraint would
    // fail monomorphization with "No ability implementation found".
    resolveAt("==").asserting(_._1 shouldBe Seq.empty)
  }

  it should "inline the operator to the resolved `equals` ability method" in {
    // `==`'s body `equals(a, b)` folds in; `equals` resolves to the `Eq[Type]` instance's method (module `Eq`). Its
    // native leaf is not folded further only because `a`/`b` are still neutral parameters here — the same
    // neutral-argument shape as `someFn` reducing to `Either::pure` in CompilerAbilityResolutionTest. The concrete-arg
    // firing all the way to the native is pinned separately in EqTypeLeafTest.
    resolveAt("==").asserting(_._2 shouldBe Seq(("Eq", "equals")))
  }

  "monomorphizing `!=` at A = Type" should "type-check without error (the Eq[Type] instance resolves)" in {
    resolveAt("!=").asserting(_._1 shouldBe Seq.empty)
  }

  it should "negate the resolved `equals` through `fold`" in {
    resolveAt("!=").asserting(_._2 should contain allOf (("Eq", "equals"), ("Bool", "fold")))
  }
}
