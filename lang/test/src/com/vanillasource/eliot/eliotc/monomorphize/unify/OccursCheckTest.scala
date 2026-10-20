package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** M0 precondition #2 (no inferred infinite type): the occurs-check in [[Unifier.solveFlex]] rejects solving `?id := t`
  * when `id` occurs in `t`. This is the `x x` / Y-combinator route — applying a value to itself forces its type meta
  * `?A` to unify with `?A -> ?B`, which without the occurs-check would build a cyclic solution and loop
  * `Evaluator.force`. Legitimate higher-order metavariables (a meta that does *not* mention itself) still solve.
  */
class OccursCheckTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Type))

  private val intFqn: ValueFQN = fqn("Int")

  private def withFlex(): (SemValue.FlexId, Unifier) = {
    val (id, store) = FlexStore.empty.fresh
    (id, Unifier.create(store, 0))
  }

  /** The non-dependent function type `domain -> codomain`. */
  private def arrow(domain: SemValue, codomain: SemValue): SemValue = VPi(domain, _ => codomain)

  "a meta unified against a function type containing itself (x x)" should "be rejected as an infinite type" in {
    val (id, u) = withFlex()
    u.unify(VFlex(id, Spine.SNil), arrow(VFlex(id, Spine.SNil), VType), ctx).errors.map(_.context.value) shouldBe
      List("Cannot construct infinite type.")
  }

  it should "leave the meta unsolved rather than committing the cycle" in {
    val (id, u) = withFlex()
    u.unify(VFlex(id, Spine.SNil), arrow(VFlex(id, Spine.SNil), VType), ctx).flexStore.lookup(id) shouldBe None
  }

  "a meta occurring indirectly through another solved meta" should "be rejected as an infinite type" in {
    val (a, u0)     = withFlex()
    val (b, store1) = u0.flexStore.fresh
    // ?b := (?a -> Type), then unify ?a ~ ?b : solving ?a := ?b would indirectly contain ?a.
    val u           = u0.copy(flexStore = store1.solve(b, arrow(VFlex(a, Spine.SNil), VType)))
    u.unify(VFlex(a, Spine.SNil), VFlex(b, Spine.SNil), ctx).errors.map(_.context.value) shouldBe
      List("Cannot construct infinite type.")
  }

  "a meta unified against a function type not containing itself" should "solve without error" in {
    val (id, u) = withFlex()
    u.unify(VFlex(id, Spine.SNil), arrow(VTopDef(intFqn, None, Spine.SNil), VType), ctx).errors shouldBe Nil
  }

  it should "solve the meta to that function type" in {
    val (id, u) = withFlex()
    u.unify(VFlex(id, Spine.SNil), arrow(VTopDef(intFqn, None, Spine.SNil), VType), ctx).flexStore
      .lookup(id) should matchPattern { case Some(VPi(VTopDef(`intFqn`, None, Spine.SNil, _), _)) => }
  }
}
