package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.FlexStore
import com.vanillasource.eliot.eliotc.monomorphize.eval.Quoter
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What a companion is passed for each argument (`docs/meta.md` §13.5): a computed meta as itself, and a meta that is
  * not computed as a value that never reads back, so a companion storing it rather than projecting it cannot produce a
  * meta from it.
  */
class CompanionArgumentTest extends AnyFlatSpec with Matchers {
  private val gaugeMeta: GroundValue =
    GroundValue.Structure(
      ValueFQN(ModuleName(Seq("test"), "Gauge"), QualifiedName("Gauge$Meta", Qualifier.Default)),
      Seq.empty,
      GroundValue.Type
    )

  private def readBack(meta: Option[GroundValue]) =
    Quoter.quote(0, MetaInterpreter.companionArgument(meta), FlexStore.empty)

  "a companion argument" should "be the argument's meta when it is computed" in {
    readBack(Some(gaugeMeta)) shouldBe Right(gaugeMeta)
  }

  it should "never read back when the argument's meta is not computed" in {
    readBack(None).isLeft shouldBe true
  }
}
