package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** Flex-flex unification where one side is an *applied* meta (`?F[a]`) and the other a *bare, unsolved* meta (`?B`).
  * The unifier solves the bare meta to the application in *both* orientations — the Miller-pattern most-general
  * unifier — rather than postponing when the applied meta happens to be on the left, so the outcome does not depend on
  * which side a constraint is written.
  */
class FlexApplicationUnifyTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default))

  private def topDef(name: String): SemValue = VTopDef(fqn(name), None, Spine.SNil)

  /** Two fresh metas over an empty store, as `(idF, idB, unifier)`. */
  private def twoFlexes: (SemValue.FlexId, SemValue.FlexId, Unifier) = {
    val (idF, s1) = FlexStore.empty.fresh
    val (idB, s2) = s1.fresh
    (idF, idB, Unifier.create(s2, 0))
  }

  private def applied(id: SemValue.FlexId, arg: SemValue): SemValue = VFlex(id, Spine.SNil :+ arg)

  "unifying an applied meta on the left against a bare meta on the right" should "solve the bare meta to the application" in {
    val (idF, idB, u) = twoFlexes
    val solved        = u.unify(applied(idF, topDef("A")), VFlex(idB, Spine.SNil), ctx)
    solved.flexStore.lookup(idB) shouldBe Some(applied(idF, topDef("A")))
  }

  it should "not postpone the constraint" in {
    val (idF, idB, u) = twoFlexes
    u.unify(applied(idF, topDef("A")), VFlex(idB, Spine.SNil), ctx).postponed shouldBe empty
  }

  "the mirror orientation (bare meta on the left)" should "still solve the bare meta directly" in {
    val (idF, idB, u) = twoFlexes
    val solved        = u.unify(VFlex(idB, Spine.SNil), applied(idF, topDef("A")), ctx)
    solved.flexStore.lookup(idB) shouldBe Some(applied(idF, topDef("A")))
  }

  "a cyclic flex-flex solve (?B occurs in the application)" should "be rejected by the occurs-check" in {
    val (idF, idB, u) = twoFlexes
    u.unify(applied(idF, VFlex(idB, Spine.SNil)), VFlex(idB, Spine.SNil), ctx).errors should not be empty
  }
}
