package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The higher-kinded *kind system* (D8): the "is this `[F[_]]` binder solved to something of the right kind" checks,
  * factored out of the [[com.vanillasource.eliot.eliotc.monomorphize.check.Checker]] into one cohesive module — the
  * same move D4 made for the refinement lattice. A *carrier* here is the instantiation meta of a higher-kinded binder.
  * None of this is definitional equality: it reconstructs a *kind* from a binder's signature and verifies a carrier
  * meta's solution against it, post-drain.
  *
  * Two entry points, mirroring the two phases:
  *   - [[recordCarrierFlexes]] — at *instantiation* time (called from `Checker.instantiatePolymorphic`), tag each
  *     freshly-peeled higher-kinded instantiation meta with its expected kind.
  *   - [[verifyCarrierKinds]] — at *finalization* time (a `TypeStackLoop` post-drain pass), check every recorded
  *     carrier two ways: a wrong-kind solution is a mismatch; an unsolved carrier with an unsatisfiable rigid-arity
  *     postponement is reported rather than silently dropped.
  *
  * The recorded kind itself lives in the [[com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier]]'s
  * [[com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier.higherKindedFlexes]] map — this module only *seeds* and
  * *reads* it. Operates over [[CheckIO]], reading and writing the shared [[CheckState]] through
  * `get`/`modify`/`inspect`. It depends on exactly three checker primitives, passed at construction — that narrow
  * surface is the module boundary.
  *
  * @param force
  *   Force a SemValue through the current meta store — the checker's `force`.
  * @param evalExpr
  *   Evaluate an ORE expression against an env (prefetching every reachable binding) — the checker's `evalExpr`.
  * @param doUnify
  *   Unify two semantic values, updating the unifier in the state — the checker's `doUnify`.
  */
class CarrierKindChecker(
    force: SemValue => CheckIO[SemValue],
    evalExpr: (OperatorResolvedExpression, Option[Env]) => CheckIO[SemValue],
    doUnify: (SemValue, SemValue, Sourced[String]) => CheckIO[Unit],
    platform: Platform
) {

  /** Tag the freshly-peeled instantiation metas that stand for *higher-kinded* type parameters (a `[F[_]]` carrier)
    * with their expected kind, so [[verifyCarrierKinds]] can reject a wrong-kind solution post-drain. Only a
    * [[SemExpression.ValueReference]] carries a polytype, so the binders' kinds are read off the referenced value's
    * signature ([[SignatureView]]); the metas align with the binders *after* the explicit type arguments already
    * applied. An ordinary `[A]` binder (kind `Type`) is left untagged — solving it to a proper type is correct.
    *
    * The record is unfiltered by ability constraints: every higher-kinded binder is kind-checked, constrained or not.
    */
  def recordCarrierFlexes(expr: SemExpression, implicitFlexes: Seq[SemValue]): CheckIO[Unit] =
    expr.expression match {
      case SemExpression.ValueReference(fqn, explicitArgs) if implicitFlexes.nonEmpty =>
        for {
          svOpt <- liftF(getFactIfProduced(SaturatedValue.Key(fqn.value, platform)))
          _     <- svOpt match {
                     case None     => pure(())
                     case Some(sv) =>
                       val binders = SignatureView.of(sv.value.signature).binders.drop(explicitArgs.size)
                       implicitFlexes.zip(binders).traverse_ {
                         case (VFlex(id, _), binder) => recordIfHigherKinded(id, binder, fqn)
                         case _                      => pure(())
                       }
                   }
        } yield ()
      case _                                                                         => pure(())
    }

  /** Evaluate a binder's kind annotation; if it forces to a `VPi` (a higher kind such as `Type -> Type`), record the
    * meta as higher-kinded with that kind (see [[recordCarrierFlexes]]). A `Type`-kinded (ordinary) binder, or one with
    * no annotation, is not recorded.
    */
  private def recordIfHigherKinded(
      id: SemValue.FlexId,
      binder: SignatureView.Binder,
      fqn: Sourced[ValueFQN]
  ): CheckIO[Unit] =
    binder.parameterType match {
      case None     => pure(())
      case Some(ts) =>
        for {
          kind   <- evalExpr(ts.value, Some(Env.empty))
          forced <- force(kind)
          ctx     = fqn.as("Higher-kinded type parameter mismatch.")
          _      <- forced match {
                      case _: VPi => modify(_.recordHigherKindedFlex(id, forced, ctx))
                      case _      => pure(())
                    }
        } yield ()
    }

  /** Post-drain kind check for higher-kinded type-parameter instantiation metas (`[F[_]]` carriers). Each carrier
    * recorded by [[recordCarrierFlexes]] is checked two ways:
    *
    *   - **Solved** to a value of the wrong kind — a fully-applied proper type (e.g. `?F := Box[String]`, kind `Type`)
    *     where a `Type -> Type` constructor is required — is reported as a mismatch. The unifier's direct empty-spine
    *     solve does not see the binder's declared kind, so it would otherwise be silently accepted.
    *   - **Unsolved** but with a postponed application `?F[a..] ~ H r..` against a *rigid* head `H` (a type constructor
    *     or bound variable) of a *different* arity is unsatisfiable: no injective type constructor `F` makes `F[a..] =
    *     H r..` (only a non-injective constant/projection lambda would, which a carrier never is). It is reported here
    *     rather than being silently dropped from the postponement queue. A carrier postponed against a *non-rigid*
    *     `VPi` (`?F[A, B] ~ Function[A, B]`) is left alone — that is legitimately higher-order unification the pattern
    *     unifier cannot solve, backstopped when the callee is monomorphized at its concrete instantiation.
    */
  def verifyCarrierKinds: CheckIO[Unit] =
    for {
      s <- get
      _ <- s.unifier.carrierFlexes.traverse_ { case (rawId, (expectedKind, context)) =>
             for {
               solution <- force(VFlex(SemValue.FlexId(rawId), Spine.SNil))
               _        <- solution match {
                             case _: VFlex => checkUnsolvedCarrier(SemValue.FlexId(rawId), context)
                             case _        =>
                               kindOfSolution(solution).flatMap {
                                 case None       => pure(())
                                 case Some(kind) => doUnify(kind, expectedKind, context)
                               }
                           }
             } yield ()
           }
    } yield ()

  /** Report an unsolved carrier meta that has a postponed application against a rigid head of a mismatched arity (see
    * [[verifyCarrierKinds]]). The postponement queue is at its fixed point by the time this runs, so such a constraint
    * can never resolve.
    */
  private def checkUnsolvedCarrier(id: SemValue.FlexId, context: Sourced[String]): CheckIO[Unit] =
    for {
      s    <- get
      store = s.unifier.flexStore
      unsat = s.unifier.postponed.exists { case (l, r, _) =>
                unsatisfiableApplication(id, l, r, store) || unsatisfiableApplication(id, r, l, store)
              }
      _    <- if (unsat) modify(st => st.withUnifier(st.unifier.addError(context))) else pure(())
    } yield ()

  /** True when `applied` forces to this carrier meta applied to a non-empty spine, and `other` forces to a rigid head
    * (a body-less [[VTopDef]] type constructor or a [[VNeutral]] bound variable) that is *under-applied* relative to
    * the meta — the unsatisfiable shape `?F[a..] ~ H r..` with `arity(H) < arity(F's spine)`: no injective `F` makes
    * `F` applied to *more* args equal `H` applied to *fewer*. (`arity(H) >= arity(F)` is satisfiable by
    * partial-application injectivity — `?F := H` applied to the leading prefix — and is solved in
    * [[com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier.decomposeSpines]], so it never reaches here as a
    * postponed constraint.)
    */
  private def unsatisfiableApplication(
      id: SemValue.FlexId,
      applied: SemValue,
      other: SemValue,
      store: FlexStore
  ): Boolean =
    Evaluator.force(applied, store) match {
      case VFlex(mid, spine) if mid.value == id.value && spine.toList.nonEmpty =>
        Evaluator.force(other, store) match {
          case VTopDef(_, None, rhsSpine, _) => rhsSpine.toList.length < spine.toList.length
          case VNeutral(_, rhsSpine)         => rhsSpine.toList.length < spine.toList.length
          case _                             => false
        }
      case _                                                                   => false
    }

  /** The kind (type) of a carrier meta's solution, or [[None]] when it cannot be determined locally (so no false
    * positive is raised). A type-constructor application `C[a..]` has the constructor's kind with the applied spine
    * removed: `Box` ⟹ `Type -> Type`, `Box[String]` ⟹ `Type`. A function type (`VPi`) or `Type` is itself a `Type`.
    */
  private def kindOfSolution(sv: SemValue): CheckIO[Option[SemValue]] =
    force(sv).flatMap {
      case VConst(g)                    => pure(Some(Evaluator.groundToSem(g.valueType)))
      case VType                        => pure(Some(VType))
      case _: VPi                       => pure(Some(VType))
      case VTopDef(fqn, None, spine, _) =>
        kindOfTypeConstructor(fqn).map(_.map(headKind => spine.toList.foldLeft(headKind)(Evaluator.applyValue)))
      case _                            => pure(None)
    }

  /** The kind of a type constructor, read off its signature (which, for a type constructor, *is* its kind chain — e.g.
    * `data Box[A]` ⟹ `Function[Type, Type]`). [[None]] when the value has no fetchable signature (a native or primitive
    * head), in which case the carrier check is skipped for that solution.
    */
  private def kindOfTypeConstructor(fqn: ValueFQN): CheckIO[Option[SemValue]] =
    liftF(getFactIfProduced(SaturatedValue.Key(fqn, platform))).flatMap {
      case None     => pure(None)
      case Some(sv) => evalExpr(sv.value.signature.value, Some(Env.empty)).map(Some(_))
    }

}
