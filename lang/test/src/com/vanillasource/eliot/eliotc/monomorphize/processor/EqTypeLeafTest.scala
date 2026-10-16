package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.stringFQN
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{BindingContribution, ContributedBinding, GroundValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI
import java.nio.file.Path

/** Ability guards Stage 0: the compiler-pool `Eq[Type]` structural-equality leaf. `implement Eq[Type]`'s `equals` is
  * body-less; [[SystemNativesProcessor]] attaches the native *directly to the ability implementation* (recognised
  * through the impl marker, the "native on an ability implementation" wiring `Compare`/`Numeric` use) rather than to a
  * separate `typeEquals` leaf FQN.
  *
  * `Eq[Type]` is compiler-pool-only (types are erased at runtime) and only fully reduces in a type/signature position —
  * which end-to-end means a compile-time guard (`where E1 != E2`). So Stage 0 verifies the leaf itself directly: run the
  * pipeline over a minimal `implement Eq[Type]`, pull the native `SemValue` the `system` [[ContributedBinding]] attaches
  * to the impl-method FQN, and apply it to type normal forms via [[Evaluator.applyValue]] — asserting it reduces two
  * equal types to `true`, two distinct types to `false`, and stays stuck (fail-safe) on a non-concrete argument. Types
  * are `VTopDef(head, None, spine)` normal forms — exactly what the checker hands a guard at a ground use site — so
  * `Int` is `VTopDef(IntFQN)` and `Int[0,5]` is `VTopDef(IntFQN, …, [0, 5])`.
  */
class EqTypeLeafTest
    extends ProcessorTest(
      LangProcessors(systemModules = Seq(ModuleName(Seq("eliot", "lang"), "Function")))*
    ) {

  private val eqModule: ModuleName = ModuleName(Seq("eliot", "lang"), "Eq")

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
      compilerScan(Seq("eliot", "lang"), "Bool", "type Bool") ++
      compilerScan(Seq("eliot", "lang"), "Eq", eqContent)

  private val intFQN: ValueFQN =
    ValueFQN(ModuleName(Seq("eliot", "lang"), "Int"), QualifiedName("Int", Qualifier.Type))

  private def typeCtor(fqn: ValueFQN, args: SemValue*): SemValue =
    VTopDef(fqn, None, args.foldLeft(Spine.SNil: Spine)(_ :+ _))

  private def bound(n: Int): SemValue = VConst(GroundValue.Direct(BigInt(n), Evaluator.bigIntGroundType))

  private val intType: SemValue    = typeCtor(intFQN)
  private val stringType: SemValue = typeCtor(stringFQN)

  /** The `Eq[Type]::equals` impl-method FQN, decoded from the module's ability surface on the compiler track. */
  private val implMethodFQN: IO[ValueFQN] =
    runGeneratorWithFacts(facts, ModuleAbilities.Key(eqModule, Platform.Compiler)).map {
      case (Some(ma), _)  => ma.anonymousImplementationMethodsOf("Eq", "equals").head
      case (None, errors) => fail(s"No Eq module abilities produced. Errors: ${toTestErrors(errors)}")
    }

  /** The `equals` native `SemValue`, pulled out of the `system` [[ContributedBinding]] `SystemNativesProcessor` attaches
    * to the `Eq[Type]` implementation method.
    */
  private val typeEqualsNative: IO[SemValue] =
    implMethodFQN.flatMap { fqn =>
      runGeneratorWithFacts(facts, ContributedBinding.Key(fqn, ContributedBinding.systemLabel)).map {
        case (Some(cb), _)  =>
          cb.contributed match {
            case Some(BindingContribution.Leaf(sem)) => sem
            case other                               => fail(s"Expected a Leaf contribution, got: $other")
          }
        case (None, errors) => fail(s"No Eq[Type]::equals binding produced. Errors: ${toTestErrors(errors)}")
      }
    }

  private def apply2(a: SemValue, b: SemValue): IO[SemValue] =
    typeEqualsNative.map(native => Evaluator.applyValue(Evaluator.applyValue(native, a), b))

  "the Eq[Type] leaf" should "reduce two identical bare types to true (Int == Int)" in {
    apply2(intType, intType).asserting(_ shouldBe Evaluator.trueValue)
  }

  it should "reduce two distinct bare types to false (Int == String)" in {
    apply2(intType, stringType).asserting(_ shouldBe Evaluator.falseValue)
  }

  it should "compare bounded types structurally: Int[0, 5] == Int[0, 5] is true" in {
    apply2(typeCtor(intFQN, bound(0), bound(5)), typeCtor(intFQN, bound(0), bound(5)))
      .asserting(_ shouldBe Evaluator.trueValue)
  }

  it should "distinguish differing bounds: Int[0, 5] == Int[0, 10] is false" in {
    apply2(typeCtor(intFQN, bound(0), bound(5)), typeCtor(intFQN, bound(0), bound(10)))
      .asserting(_ shouldBe Evaluator.falseValue)
  }

  it should "stay stuck (fail-safe) on a non-concrete argument rather than answer wrongly" in {
    apply2(VMeta(MetaId(0), Spine.SNil), intType).asserting(_ shouldBe a[VStuckNative])
  }

  it should "stay stuck when a bound is a non-concrete metavariable" in {
    apply2(typeCtor(intFQN, bound(0), VMeta(MetaId(1), Spine.SNil)), typeCtor(intFQN, bound(0), bound(5)))
      .asserting(_ shouldBe a[VStuckNative])
  }
}
