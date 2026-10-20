package com.vanillasource.eliot.eliotc.monomorphize.unify

import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** The per-meta *kind* bookkeeping ([[Unifier.higherKindedFlexes]]): which instantiation metas were peeled from a
  * higher-kinded (`[F[_]]`) binder, and to what kind, which post-drain kind verification reads.
  */
class HigherKindedFlexTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  /** Allocate `n` fresh metavariables, returning their ids and a fresh unifier over them. */
  private def withFlexes(n: Int): (Vector[SemValue.FlexId], Unifier) = {
    val (ids, store) = (0 until n).foldLeft((Vector.empty[SemValue.FlexId], FlexStore.empty)) { case ((acc, s), _) =>
      val (id, next) = s.fresh
      (acc :+ id, next)
    }
    (ids, Unifier.create(store, 0))
  }

  "a fresh metavariable" should "be ordinary — no kind bookkeeping" in {
    val (_, u) = withFlexes(1)
    u.carrierFlexes shouldBe empty
  }

  "recordHigherKindedFlex" should "expose the meta and its kind via carrierFlexes" in {
    val (ids, u) = withFlexes(1)
    val id       = ids.head
    u.recordHigherKindedFlex(id, VType, ctx).carrierFlexes shouldBe List((id.value, (VType, ctx)))
  }
}
