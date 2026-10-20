package com.vanillasource.eliot.eliotc.monomorphize.eval

import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** F1: applying an argument to a non-applicable head (a `VConst` or `VType`) — which can only arise in an ill-typed
  * program — must produce a **loud stuck form** rather than the old identity-ish fallback that returned the argument
  * (silently collapsing `F[A]` to `A`). The stuck form is a [[SemValue.VNeutral]] on the reserved `$bad-apply` head, so
  * a value that ever survives to read-back fails the strict [[Quoter]] loudly instead of minting a wrong type.
  */
class EvaluatorApplyValueTest extends AnyFlatSpec with Matchers {
  private val arg: SemValue      = VConst(GroundValue.Direct(BigInt(7), GroundValue.Type))
  private val badApply: SemValue = VNeutral(NeutralHead.Reserved(NeutralHead.Marker.BadApply), Spine.SNil :+ arg)

  "applying an argument to a VConst" should "produce the loud $bad-apply stuck neutral carrying the argument" in {
    Evaluator.applyValue(VConst(GroundValue.Type), arg) shouldBe badApply
  }

  "applying an argument to VType" should "produce the loud $bad-apply stuck neutral carrying the argument" in {
    Evaluator.applyValue(VType, arg) shouldBe badApply
  }

  "the $bad-apply stuck form surviving to read-back" should "make the quoter fail loudly rather than mint a wrong type" in {
    Quoter.quote(0, Evaluator.applyValue(VType, arg), FlexStore.empty) shouldBe
      Left("Cannot quote neutral value — contains unresolved variable")
  }
}
