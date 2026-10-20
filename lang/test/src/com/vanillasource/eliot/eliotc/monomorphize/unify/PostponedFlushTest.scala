package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** The end-of-check fail-safe for the postponement queue ([[Unifier.flushPostponed]] — Step 0 of the
  * bounds-as-refinements migration / the TODO.md hole): a constraint the checker could never discharge must not be
  * silently carried along and forgotten, it must surface as a hard mismatch error — *unless* the triage classifies it
  * benign because a more precise fail-safe already owns it (an applied abstract associated type, or a `$bad-apply`
  * read-back artifact of a phantom meta defaulted to a non-applicable value).
  *
  * `flushPostponed` runs *after* the finalizer has defaulted every unsolved non-assoc meta, so these tests set that
  * post-default state up by hand: the genuine backstop case is a postponed application whose meta solved to a concrete
  * head that then mismatches; the benign cases are an unsolved abstract-assoc placeholder and a meta defaulted to
  * [[SemValue.VType]].
  */
class PostponedFlushTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default))

  private def topDef(name: String): SemValue = VTopDef(fqn(name), None, Spine.SNil)

  /** A non-rigid constant `VConst`, which the injectivity decomposition refuses to solve an applied meta against — so
    * `?F[A] ~ konst` postpones instead of unifying or failing outright.
    */
  private val konst: SemValue = VConst(GroundValue.Direct("k", GroundValue.Type))

  /** A fresh applied meta `?F[A]` over an empty store, as `(idF, unifier)`. */
  private def appliedFlex: (SemValue.FlexId, Unifier) = {
    val (idF, s1) = FlexStore.empty.fresh
    (idF, Unifier.create(s1, 0))
  }

  private def applied(id: SemValue.FlexId): SemValue = VFlex(id, Spine.SNil :+ topDef("A"))

  /** Postpone `?F[A] ~ konst` and return `(idF, postponed unifier)`. */
  private def postponed: (SemValue.FlexId, Unifier) = {
    val (idF, u) = appliedFlex
    (idF, u.unify(applied(idF), konst, ctx))
  }

  "an undischargeable constraint (?F[A] against a non-rigid constant)" should "genuinely postpone" in {
    postponed._2.postponed should not be empty
  }

  "a postponed application whose meta solved to a mismatching concrete head" should "flush to a mismatch error" in {
    val (idF, u) = postponed
    // `?F := _ -> Box`, so `?F[A]` now reduces to the concrete `Box`, which cannot equal `konst` — a genuine leak.
    val solved   = u.copy(flexStore = u.flexStore.solve(idF, VLam("x", _ => topDef("Box"))))
    solved.flushPostponed().errors should not be empty
  }

  it should "clear the postponement queue" in {
    val (idF, u) = postponed
    val solved   = u.copy(flexStore = u.flexStore.solve(idF, VLam("x", _ => topDef("Box"))))
    solved.flushPostponed().postponed shouldBe empty
  }

  "the triage re-drain" should "discharge a constraint whose meta solved to a now-consistent head" in {
    val (idF, u) = postponed
    // `?F := _ -> konst`, so `?F[A]` reduces to `konst` and the constraint holds — no error.
    val solved   = u.copy(flexStore = u.flexStore.solve(idF, VLam("x", _ => konst)))
    solved.flushPostponed().errors shouldBe empty
  }

  "flushing an empty queue" should "add no errors" in {
    appliedFlex._2.flushPostponed().errors shouldBe empty
  }

  "a phantom meta defaulted to VType, applied to a spine" should "be triaged benign ($bad-apply head)" in {
    val (idF, u) = postponed
    // Mimic `defaultUnsolvedFlexes`: solve the leftover meta to `VType`. `?F[A]` then reads back as `$bad-apply(A)`.
    val defaulted = u.copy(flexStore = u.flexStore.solve(idF, VType))
    defaulted.flushPostponed().errors shouldBe empty
  }
}
