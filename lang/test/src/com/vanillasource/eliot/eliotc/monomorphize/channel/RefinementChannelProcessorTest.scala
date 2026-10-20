package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins the channel's one recognition point: a refinement operation is a callee with a `^Meta` companion, found by
  * `metaCompanionFqn` uniformly — an arithmetic transfer (the `Numeric[Int]` `add` ⤳ `add^Meta`) and a branch merge
  * (`fold` ⤳ `fold^Meta`) go through the same lookup, with no leaf FQN list. The arithmetic callee is an *ability-impl*
  * method, so this also pins that a companion keeps its callee's `(ability, pattern)` qualifier — nested inside the
  * meta namespace rather than dropped — which is what keeps that instance's transfer apart from a plain `add` declared
  * in the same module (`docs/meta.md` §4.2). The end-to-end transfer/merge is exercised by the
  * full-layer integration suite (`ExamplesIntegrationTest`).
  */
class RefinementChannelProcessorTest extends AnyFlatSpec with Matchers {
  import RefinementChannelProcessor.*

  private val intModule  = ModuleName(ModuleName.defaultSystemPackage, "Int")
  private val numericInt = Qualifier.AbilityImplementation("Numeric", "Int", None)

  "metaCompanionFqn" should "name a merge callee's ^Meta companion in its own module and the Meta namespace" in {
    val fold = ValueFQN(ModuleName(ModuleName.defaultSystemPackage, "Bool"), QualifiedName("fold", Qualifier.Default))
    metaCompanionFqn(fold) shouldBe
      ValueFQN(
        ModuleName(ModuleName.defaultSystemPackage, "Bool"),
        QualifiedName("fold", Qualifier.Meta(Qualifier.Default))
      )
  }

  it should "keep an ability-impl transfer callee's own qualifier, so it is that implementation's transfer" in {
    val add = ValueFQN(intModule, QualifiedName("add", numericInt))
    metaCompanionFqn(add) shouldBe ValueFQN(intModule, QualifiedName("add", Qualifier.Meta(numericInt)))
  }

  it should "name a plain def's transfer differently from a same-named ability impl's in the same module" in {
    metaCompanionFqn(ValueFQN(intModule, QualifiedName("add", Qualifier.Default))) should not be
      metaCompanionFqn(ValueFQN(intModule, QualifiedName("add", numericInt)))
  }

  "whereCompanionName" should "keep the callee's own qualifier too, suffixed to stay apart from the transfer" in {
    whereCompanionName(ValueFQN(intModule, QualifiedName("add", numericInt))) shouldBe
      QualifiedName("add$Where", Qualifier.Meta(numericInt))
  }
}
