package com.vanillasource.eliot.eliotc.monomorphize.unify

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Pattern unification on SemValues with a postponement queue. All operations return a new Unifier instance — no
  * mutation occurs.
  *
  * @param metaStore
  *   The current meta store
  * @param depth
  *   The current binding depth (for fresh variable generation)
  * @param postponed
  *   Queue of postponed unification problems (when a meta's spine is not pattern)
  * @param errors
  *   Accumulated errors with source positions and (when available) the expected and actual semantic values.
  *
  * This is *pure definitional (value) equality* with metavariable solving on top: it forces both sides through the
  * evaluator (= normalises) and compares. There is no notion of "assignability", directional widening, or branch
  * joining here — a mismatch is a mismatch.
  *
  * Per-metavariable *kind* bookkeeping lives in [[higherKindedMetas]] — the expected kind of each higher-kinded
  * (`[F[_]]`) instantiation meta, seeded at instantiation and verified post-drain
  * ([[com.vanillasource.eliot.eliotc.monomorphize.check.CarrierKindChecker]]). A meta absent from the map is ordinary
  * (kind `Type`) and is solved by ordinary unification or defaulted to `Type` at finalization.
  *
  * @param higherKindedMetas
  *   Per-meta-id expected kind for higher-kinded instantiation metas, with the call-site context an error is reported
  *   at. Written by [[recordHigherKindedMeta]].
  */
case class Unifier(
    metaStore: MetaStore,
    depth: Int,
    postponed: List[(SemValue, SemValue, Sourced[String])],
    errors: List[UnifyError],
    higherKindedMetas: Map[Int, (SemValue, Sourced[String])] = Map.empty
) {

  /** Every higher-kinded instantiation meta with its recorded kind, as `(id, (expectedKind, context))`. Drives
    * post-drain carrier-kind verification.
    */
  def carrierMetas: List[(Int, (SemValue, Sourced[String]))] = higherKindedMetas.toList

  /** Record a higher-kinded instantiation meta's expected kind (`[F[_]]`). */
  def recordHigherKindedMeta(id: MetaId, expectedKind: SemValue, context: Sourced[String]): Unifier =
    copy(higherKindedMetas = higherKindedMetas.updated(id.value, (expectedKind, context)))

  /** Speculatively unify `l` against `r` without committing a mismatch (D5), returning an explicit [[UnifyResult]]
    * rather than forcing callers to diff [[errors]] before and after a [[unify]] call. A [[UnifyResult.Unified]]
    * carries the unifier with every solution applied (safe to commit); a [[UnifyResult.Contradiction]] carries the same
    * partial solutions but with the new mismatch errors stripped, leaving the report-or-recover decision to the caller
    * (a check-mode lift-arm elaboration, or a [[drain]] re-postpone).
    */
  def tryUnify(l: SemValue, r: SemValue, context: Sourced[String]): UnifyResult =
    speculate(unify(l, r, context))

  /** Classify a unification result `after` (produced from `this`) as [[UnifyResult.Unified]] or
    * [[UnifyResult.Contradiction]] by whether it added any error, stripping the new errors in the latter case so a
    * caller can recover without them. This is the single home of the former scattered `errors.size`-delta check (D5);
    * because `after` only ever *prepends* errors to `this`, restoring `this`'s error list drops exactly the new ones
    * while preserving every metavariable solved along the way.
    */
  private def speculate(after: Unifier): UnifyResult =
    if (after.errors.size == errors.size) UnifyResult.Unified(after)
    else UnifyResult.Contradiction(after.copy(errors = errors))

  /** Unify two semantic values, reporting errors with the given context message and source position — pure definitional
    * equality.
    */
  def unify(l: SemValue, r: SemValue, context: Sourced[String]): Unifier = {
    val fl = Evaluator.force(l, metaStore)
    val fr = Evaluator.force(r, metaStore)
    (fl, fr) match {
      case (VType, VType) => this

      case (VConst(g1), VConst(g2)) =>
        if (!groundEquals(g1, g2)) addMismatch(fl, fr, context)
        else this

      case (VPi(d1, c1), VPi(d2, c2)) =>
        val (fresh, u1) = freshVar()
        u1.unify(d1, d2, context)
          .unify(c1(fresh), c2(fresh), context)

      case (VLam(_, c1), VLam(_, c2)) =>
        val (fresh, u1) = freshVar()
        u1.unify(c1(fresh), c2(fresh), context)

      // Eta: VLam vs non-lambda
      case (VLam(_, c), other)        =>
        val (fresh, u1) = freshVar()
        u1.unify(c(fresh), Evaluator.applyValue(other, fresh), context)

      case (other, VLam(_, c))                                =>
        val (fresh, u1) = freshVar()
        u1.unify(Evaluator.applyValue(other, fresh), c(fresh), context)

      // Neutral-neutral: same head, same spine length
      case (VNeutral(h1, sp1), VNeutral(h2, sp2)) if h1 == h2 =>
        unifySpines(fl, fr, sp1, sp2, context)

      // Flex-flex where the left meta is *applied* but the right is a *bare, unsolved* meta: solve the bare meta to
      // the applied one rather than postponing (the mirror orientation — bare left — already solves directly via the
      // next case), so a higher-kinded application produced through a polymorphic combinator — the `.` operator's
      // result `?B := ?F[payload]` — is visible through `?B` instead of postponed behind it.
      case (VMeta(idA, spineA), VMeta(idB, Spine.SNil))
          if spineA.toList.nonEmpty && idA.value != idB.value && metaStore.lookup(idB).isEmpty =>
        if (occursIn(idB, fl)) addOccursError(context)
        else copy(metaStore = metaStore.solve(idB, fl))

      // Meta solving (pattern rule)
      case (VMeta(id, spine), rhs)                            =>
        solveMeta(id, spine, rhs, context)

      case (lhs, VMeta(id, spine))                                              =>
        solveMeta(id, spine, lhs, context)

      // VTopDef equality by FQN: same constructor → unify spines pointwise (definitional equality). Constructors
      // applied to differing arguments are genuinely unequal values and are rejected here.
      case (VTopDef(fqn1, _, sp1, _), VTopDef(fqn2, _, sp2, _)) if fqn1 == fqn2 =>
        unifySpines(fl, fr, sp1, sp2, context)

      // Stuck native equality by FQN: a native application is *not* injective (`add(1, 3) == add(2, 2)`), so it is
      // never injectivity-decomposed against a meta (see `tryDecomposeApplied`); two stuck natives are definitionally
      // equal only when the same operation is applied to pointwise-equal arguments.
      case (VStuckNative(fqn1, sp1), VStuckNative(fqn2, sp2)) if fqn1 == fqn2   =>
        unifySpines(fl, fr, sp1, sp2, context)

      case _ =>
        addMismatch(fl, fr, context)
    }
  }

  /** Try to solve a meta. Empty spine solves directly. Non-empty spine attempts injectivity-style decomposition against
    * a rigid applied rhs (see [[tryDecomposeApplied]]); failing that, postpones.
    */
  private def solveMeta(id: MetaId, spine: Spine, rhs: SemValue, context: Sourced[String]): Unifier =
    metaStore.lookup(id) match {
      case Some(solved) =>
        // Already solved — unify the solution (with spine applied) against rhs.
        val applied = spine.toList.foldLeft(solved)(Evaluator.applyValue)
        unify(applied, rhs, context)
      case None         =>
        rhs match {
          case VMeta(rhsId, _) if rhsId.value == id.value =>
            this // Same unsolved meta — trivially equal, no solve needed
          case _                                          =>
            val spineList = spine.toList
            if (spineList.isEmpty) {
              // Empty spine — solve directly, but first run the occurs-check: solving `?id := rhs` where `id` occurs in
              // `rhs` would build an infinite type (the `x x` / Y-combinator route) and loop `Evaluator.force` once the
              // cyclic meta is in the store. This is precondition #2 of the termination model — reject it instead.
              if (occursIn(id, rhs)) addOccursError(context)
              else copy(metaStore = metaStore.solve(id, rhs))
            } else {
              // Non-empty spine — try injectivity decomposition first, otherwise postpone.
              tryDecomposeApplied(id, spineList, rhs, context)
                .getOrElse(copy(postponed = (VMeta(id, spine), rhs, context) :: postponed))
            }
        }
    }

  /** Injectivity-based decomposition of `?id s1..sm ~ H r1..rn`, mirroring what GHC and Scala 3 do for applied types:
    * type constructor application is treated as injective, so the decomposition is unique.
    *
    * Two arity regimes, both sound because the rhs head is rigid (a type constructor or bound variable, never a
    * projection/constant lambda):
    *   - **Equal arity** (`m == n`): `?id := H` (unapplied) plus pointwise spine unification `s_i ~ r_i`.
    *   - **Partial application** (`n > m`): `?id := H r1..r(n-m)` (the head applied to the leading prefix) plus
    *     pointwise unification of the trailing `m` args `s_i ~ r(n-m+i)`. This is what lets a higher-kinded meta solve
    *     to a type constructor partially applied to a prefix — `?F[A] ~ Either[E, A] ⟹ ?F := Either[E]` — exactly as
    *     `?F[A] ~ Box[A] ⟹ ?F := Box` works for the equal-arity case. The solution
    *     is unique: with `H` injective, only `?F = H r1..r(n-m)` makes `?F s1..sm = H r1..rn` hold for all the trailing
    *     args.
    *
    * Fires only when the rhs's head is rigid and `n >= m`:
    *   - [[VTopDef]] with no cached body — data-type constructors are bound this way and never β-reduce. Cached
    *     VTopDefs (user-defined type aliases) would have been unfolded by the ambient [[Evaluator.force]] before
    *     reaching here, so we should not see them; the `None` guard is a belt-and-braces check.
    *   - [[VNeutral]] — rigid bound variables with a spine.
    *
    * `n < m` and non-rigid rhs shapes return `None`, leaving the caller to postpone. Cases like `?A [?B] ~ Function
    * [Int, String]` have multiple valid solutions and correctly postpone. A [[VStuckNative]] head (`add(x, y)`, `min(x,
    * y)`, …) is deliberately **not** in the rigid set: a native application is non-injective, so decomposing `?F [a] ~
    * add(x, y)` to `?F := add` would be unsound — it falls through to `None` and postpones, leaving the meta for
    * `renormalize`/defaulting once the arguments concretise.
    */
  private def tryDecomposeApplied(
      id: MetaId,
      metaSpine: List[SemValue],
      rhs: SemValue,
      context: Sourced[String]
  ): Option[Unifier] = rhs match {
    case VTopDef(fqn, None, rhsSpine, _) =>
      decomposeSpines(id, metaSpine, rhsSpine, prefix => VTopDef(fqn, None, prefix), context)
    case VNeutral(head, rhsSpine)        =>
      decomposeSpines(id, metaSpine, rhsSpine, prefix => VNeutral(head, prefix), context)
    case _                               => None
  }

  /** Decompose `?id metaSpine ~ head rhsSpine` by injectivity. `rebuildHead` reattaches the leading prefix of
    * `rhsSpine` (everything beyond the trailing `metaSpine.length` args) to the rigid head, which becomes `?id`'s
    * solution; the trailing args unify pointwise against `metaSpine`. Returns `None` when the rhs is under-applied
    * relative to the meta (`n < m`), which has no injective solution.
    */
  private def decomposeSpines(
      id: MetaId,
      metaSpine: List[SemValue],
      rhsSpine: Spine,
      rebuildHead: Spine => SemValue,
      context: Sourced[String]
  ): Option[Unifier] = {
    val rhsList = rhsSpine.toList
    if (rhsList.length < metaSpine.length) None
    else {
      val (prefix, suffix) = rhsList.splitAt(rhsList.length - metaSpine.length)
      val headWithPrefix   = rebuildHead(prefix.foldLeft(Spine.SNil: Spine)(_ :+ _))
      // Occurs-check the injective solution too: `?F[A] ~ Either[?F, A]` would solve `?F := Either[?F]` (infinite).
      // Returning None here postpones rather than committing the cycle, so it surfaces as an unresolved type, never a
      // silently-accepted recursive one.
      if (occursIn(id, headWithPrefix)) None
      else {
        val solvedMeta = copy(metaStore = metaStore.solve(id, headWithPrefix))
        Some(metaSpine.zip(suffix).foldLeft(solvedMeta) { case (u, (l, r)) => u.unify(l, r, context) })
      }
    }
  }

  /** Flush the postponement queue at the very end of the check (after the finalizer defaulted every unsolved meta):
    * convert every constraint still postponed into a hard mismatch error with its recorded context — unless a triage
    * classifies it benign.
    *
    * A leading triage re-[[drain]] runs first — with the metas now defaulted to [[SemValue.VType]], a constraint that
    * was only waiting on those metas trivially re-verifies and is discharged. Whatever remains postponed after that is,
    * in general, an equality obligation the check could never discharge; per the fail-safe rule it must surface as an
    * error rather than be silently carried along and forgotten (the hole that let pre-fix applied-associated-type
    * garbage compile — see TODO.md).
    *
    * One shape is exempt because a *more precise* fail-safe already owns it (see [[isBenignPostponement]]): a
    * `$bad-apply` head, the read-back artifact of a phantom meta defaulted to a non-applicable value. Flushing it would
    * reject currently-correct programs whose types are settled through other channels (e.g. the
    * guard/effectful-signature discharge), never through this postponed constraint. The genuine class this flush is the
    * sole backstop for — a postponed application whose meta *did* solve to a concrete head that then mismatches the
    * other side — is caught.
    *
    * The recorded context (source position and message — typically "Type mismatch.") is reused verbatim, and the stored
    * `(actual, expected)` pair drives the same `Expected/Actual` hint as any other mismatch.
    */
  def flushPostponed(): Unifier = {
    val triaged = drain()
    val genuine = triaged.postponed.filterNot { case (l, r, _) => triaged.isBenignPostponement(l, r) }
    genuine.foldLeft(triaged.copy(postponed = Nil)) { case (u, (l, r, ctx)) =>
      u.addMismatch(l, r, ctx)
    }
  }

  /** Whether a still-postponed constraint is benign — owned by a more precise fail-safe than the [[flushPostponed]]
    * backstop — and so must not be converted into a mismatch error. True when *either* forced side is headed by the
    * **`$bad-apply` reserved head** ([[SemValue.NeutralHead.Marker.BadApply]]) — produced when a phantom meta,
    * defaulted to a non-applicable [[SemValue.VType]]/[[SemValue.VConst]], is applied to a spine. Either an ill-typed
    * program already recorded a diagnostic (so the build fails regardless) or the application is a vacuous phantom;
    * skipping never hides a genuine, otherwise-undiagnosed mismatch.
    */
  private def isBenignPostponement(l: SemValue, r: SemValue): Boolean =
    hasBenignHead(l) || hasBenignHead(r)

  private def hasBenignHead(v: SemValue): Boolean =
    Evaluator.force(v, metaStore) match {
      case VNeutral(NeutralHead.Reserved(NeutralHead.Marker.BadApply), _) => true
      case _                                                              => false
    }

  /** Drain the postponement queue, re-attempting postponed unifications. Repeats until stable. */
  def drain(): Unifier = {
    @scala.annotation.tailrec
    def loop(u: Unifier): Unifier = {
      val current = u.postponed
      if (current.isEmpty) u
      else {
        val (result, changed) = current.foldLeft((u.copy(postponed = Nil), false)) {
          case ((acc, anyChanged), (l, r, ctx)) =>
            acc.tryUnify(l, r, ctx) match {
              case UnifyResult.Contradiction(after) =>
                // Could not discharge — keep any solutions, drop the error, and re-postpone (no progress).
                (after.copy(postponed = (l, r, ctx) :: after.postponed), anyChanged)
              case UnifyResult.Unified(after)       =>
                // A constraint re-postponed inside unify (e.g. a non-pattern spine) is not real progress.
                if (after.postponed.length > acc.postponed.length) (after, anyChanged)
                else (after, true)
            }
        }
        if (changed) loop(result) else result
      }
    }
    loop(this)
  }

  private def unifySpines(l: SemValue, r: SemValue, sp1: Spine, sp2: Spine, context: Sourced[String]): Unifier = {
    val l1 = sp1.toList
    val l2 = sp2.toList
    if (l1.length == l2.length) l1.zip(l2).foldLeft(this) { case (u, (a, b)) => u.unify(a, b, context) }
    else addMismatch(l, r, context)
  }

  /** Occurs-check: does the metavariable `id` appear anywhere in `value` (under spines and binders)? Forces through
    * already-solved metas, so an *indirect* cycle — `?id := … ?other …` where `?other` is itself solved to something
    * mentioning `?id` — is also caught. This is the soundness gate of precondition #2 (no inferred infinite type): the
    * caller refuses to solve `?id := value` when this holds, so `Evaluator.force` can never enter a cyclic meta.
    */
  private def occursIn(id: MetaId, value: SemValue): Boolean =
    Evaluator.force(value, metaStore) match {
      case VMeta(other, spine)     => other.value == id.value || spine.toList.exists(occursIn(id, _))
      case VTopDef(_, _, spine, _) => spine.toList.exists(occursIn(id, _))
      case VStuckNative(_, spine)  => spine.toList.exists(occursIn(id, _))
      case VNeutral(_, spine)      => spine.toList.exists(occursIn(id, _))
      case VPi(domain, codomain)   =>
        val (fresh, _) = freshVar()
        occursIn(id, domain) || occursIn(id, codomain(fresh))
      case VLam(_, closure)        =>
        val (fresh, _) = freshVar()
        occursIn(id, closure(fresh))
      case VNative(paramType, _)   => occursIn(id, paramType)
      case VConst(_) | VType       => false
    }

  /** Record an "infinite type" rejection from the occurs-check, reusing the source position of `context`. */
  private[monomorphize] def addOccursError(context: Sourced[String]): Unifier =
    copy(errors =
      UnifyError(Sourced(context.uri, context.range, "Cannot construct infinite type."), None, None) :: errors
    )

  private def freshVar(): (SemValue, Unifier) = {
    val v = VNeutral(NeutralHead.Fresh(NeutralHead.Origin.Unify, depth), Spine.SNil)
    (v, copy(depth = depth + 1))
  }

  /** Record a type-mismatch error. By convention the left value is the actual (inferred) type and the right is the
    * expected type, matching the argument order of [[unify]] as called by the checker.
    */
  private[monomorphize] def addMismatch(actual: SemValue, expected: SemValue, context: Sourced[String]): Unifier =
    copy(errors = UnifyError(context, Some(expected), Some(actual)) :: errors)

  private[monomorphize] def addError(context: Sourced[String]): Unifier =
    copy(errors = UnifyError(context, None, None) :: errors)

  /** Structural equality for ground values. */
  private def groundEquals(g1: GroundValue, g2: GroundValue): Boolean = (g1, g2) match {
    case (GroundValue.Type, GroundValue.Type)                                   => true
    case (GroundValue.Direct(v1, t1), GroundValue.Direct(v2, t2))               => v1 == v2 && groundEquals(t1, t2)
    case (GroundValue.Structure(n1, a1, t1), GroundValue.Structure(n2, a2, t2)) =>
      n1 == n2 &&
      a1.length == a2.length &&
      groundEquals(t1, t2) &&
      a1.zip(a2).forall { case (l, r) => groundEquals(l, r) }
    case _                                                                      => false
  }
}

object Unifier {
  def create(metaStore: MetaStore, depth: Int): Unifier =
    Unifier(metaStore, depth, Nil, Nil)
}
