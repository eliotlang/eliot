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
  * unifier — rather than postponing when the applied meta happens to be on the left.
  *
  * The case arises in a `.` chain: the combinator (`.[A, B](a: A, f: Function[A, B]): B`) aliases its result meta `?B`
  * to a callee's meta-headed result `?F[payload]`, and postponing hides that application from whatever reads `?B` next,
  * which may then mis-solve it to the bare payload type. Solving `?B := ?F[payload]` eagerly — it is an alias, so there
  * is nothing to choose — keeps the application visible.
  */
class MetaApplicationUnifyTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default))

  private def topDef(name: String): SemValue = VTopDef(fqn(name), None, Spine.SNil)

  /** Two fresh metas over an empty store, as `(idF, idB, unifier)`. */
  private def twoMetas: (SemValue.MetaId, SemValue.MetaId, Unifier) = {
    val (idF, s1) = MetaStore.empty.fresh
    val (idB, s2) = s1.fresh
    (idF, idB, Unifier.create(s2, 0))
  }

  private def applied(id: SemValue.MetaId, arg: SemValue): SemValue = VMeta(id, Spine.SNil :+ arg)

  "unifying an applied meta on the left against a bare meta on the right" should "solve the bare meta to the application" in {
    val (idF, idB, u) = twoMetas
    val solved        = u.unify(applied(idF, topDef("A")), VMeta(idB, Spine.SNil), ctx)
    solved.metaStore.lookup(idB) shouldBe Some(applied(idF, topDef("A")))
  }

  it should "not postpone the constraint" in {
    val (idF, idB, u) = twoMetas
    u.unify(applied(idF, topDef("A")), VMeta(idB, Spine.SNil), ctx).postponed shouldBe empty
  }

  "the mirror orientation (bare meta on the left)" should "still solve the bare meta directly" in {
    val (idF, idB, u) = twoMetas
    val solved        = u.unify(VMeta(idB, Spine.SNil), applied(idF, topDef("A")), ctx)
    solved.metaStore.lookup(idB) shouldBe Some(applied(idF, topDef("A")))
  }

  "a cyclic flex-flex solve (?B occurs in the application)" should "be rejected by the occurs-check" in {
    val (idF, idB, u) = twoMetas
    u.unify(applied(idF, VMeta(idB, Spine.SNil)), VMeta(idB, Spine.SNil), ctx).errors should not be empty
  }
}
