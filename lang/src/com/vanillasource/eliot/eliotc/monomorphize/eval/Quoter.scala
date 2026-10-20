package com.vanillasource.eliot.eliotc.monomorphize.eval

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.FlexStore
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue

/** Quotes semantic values back to ground values. This is the "read-back" phase of NbE.
  *
  * Fails on VNeutral, VFlex, VLam, VStuckNative — those should be resolved (or reduced) before quoting.
  */
object Quoter {

  /** Quote a semantic value to a ground value, forcing metas as needed. */
  def quote(depth: Int, v: SemValue, flexStore: FlexStore): Either[String, GroundValue] = {
    val forced = Evaluator.force(v, flexStore)
    forced match {
      case VType =>
        Right(GroundValue.Type)

      case VConst(ground) =>
        Right(ground)

      case VPi(domain, codomain) =>
        for {
          domGround <- quote(depth, domain, flexStore)
          codGround <- quote(depth + 1, codomain(freshVar(depth)), flexStore)
        } yield GroundValue.Structure(
          com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.functionDataTypeFQN,
          Seq(domGround, codGround),
          GroundValue.Type
        )

      case VNeutral(NeutralHead.SignatureBinder(index, _), spine) =>
        // A leftover generic binder of a partial-arity signature twin (signature-unification C2), read back **under its
        // binder** as a parametric signature. Distinct from a runtime `Param` neutral, which stays non-quotable (a body
        // parameter that keeps the body structural). The spine (a higher-kinded binder's application, `F[Unit]`) is
        // quoted positionally.
        for {
          args <- spine.toList.traverse(quote(depth, _, flexStore))
        } yield GroundValue.Param(index, args, GroundValue.Type)

      case VNeutral(_, _) =>
        Left("Cannot quote neutral value — contains unresolved variable")

      case VFlex(id, _) =>
        Left(s"Cannot quote unsolved meta ?${id.value}")

      case VLam(_, _) =>
        Left("Cannot quote lambda — expected a fully evaluated type")

      case VNative(_, _) =>
        Left("Cannot quote partially applied native")

      case VTopDef(fqn, None, spine, _) =>
        // An unevaluated constructor-like application (cached body is absent, e.g. for data value constructors).
        for {
          args <- spine.toList.traverse(quote(depth, _, flexStore))
        } yield GroundValue.Structure(fqn, args, GroundValue.Type)

      case VTopDef(fqn, _, _, _) =>
        Left(s"Cannot quote unapplied top-level definition ${fqn.show}")

      case VStuckNative(fqn, _) =>
        // A native application (`add(?, ?)`, …) that never reduced — its arguments stayed abstract. Reading it back as
        // a ground `Structure` would mint a nonsense, non-injective type; fail loudly instead (D3 / F1).
        Left(s"Cannot quote stuck native application ${fqn.show}")
    }
  }

  private def freshVar(depth: Int): SemValue =
    VNeutral(NeutralHead.Fresh(NeutralHead.Origin.Quote, depth), Spine.SNil)
}
