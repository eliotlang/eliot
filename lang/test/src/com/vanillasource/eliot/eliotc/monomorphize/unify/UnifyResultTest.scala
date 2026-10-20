package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** D5: `tryUnify` returns an explicit [[UnifyResult]] in place of the former `errors.size`-delta idiom. These tests
  * pin the contract the four former call sites rely on: a success is [[UnifyResult.Unified]] carrying the solved
  * unifier; a failure is [[UnifyResult.Contradiction]] carrying the partial solutions but with the new mismatch errors
  * stripped (so `drain` can keep solutions and re-postpone, and a coercion/candidate caller can recover from its own
  * pre-unification state). `tryUnify` is non-committing — unlike [[Unifier.unify]] it never leaves a mismatch in the
  * error log.
  */
class UnifyResultTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default))

  private def topDef(name: String): SemValue = VTopDef(fqn(name), None, Spine.SNil)

  private def boxed(args: SemValue*): SemValue =
    VTopDef(fqn("Box"), None, args.foldLeft(Spine.SNil: Spine)(_ :+ _))

  private def freshUnifier: Unifier = Unifier.create(FlexStore.empty, 0)

  "tryUnify on definitionally equal values" should "report Unified" in {
    freshUnifier.tryUnify(topDef("A"), topDef("A"), ctx) shouldBe a[UnifyResult.Unified]
  }

  "tryUnify on mismatched values" should "report Contradiction" in {
    freshUnifier.tryUnify(topDef("A"), topDef("B"), ctx) shouldBe a[UnifyResult.Contradiction]
  }

  it should "not commit the mismatch error (non-committing)" in {
    freshUnifier.tryUnify(topDef("A"), topDef("B"), ctx).unifier.errors shouldBe empty
  }

  "the committing unify, by contrast" should "leave the mismatch in the error log" in {
    freshUnifier.unify(topDef("A"), topDef("B"), ctx).errors should not be empty
  }

  "tryUnify solving a metavariable" should "carry the solution on the result" in {
    val (id, store) = FlexStore.empty.fresh
    val u           = Unifier.create(store, 0).tryUnify(VFlex(id, Spine.SNil), topDef("A"), ctx).unifier
    u.flexStore.lookup(id) shouldBe Some(topDef("A"))
  }

  "tryUnify failing after a partial solve" should "preserve solutions but strip the error (what drain relies on)" in {
    val (id, store) = FlexStore.empty.fresh
    val solved      = boxed(VFlex(id, Spine.SNil), topDef("A"))
    val concrete    = boxed(topDef("B"), topDef("C"))
    val u           = Unifier.create(store, 0).tryUnify(solved, concrete, ctx).unifier
    (u.flexStore.lookup(id), u.errors) shouldBe (Some(topDef("B")), Nil)
  }
}
