package com.vanillasource.eliot.eliotc.monomorphize.eval

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import scala.util.DynamicVariable

/** Pure, synchronous NbE evaluator. Evaluates ORE syntax into the semantic domain (SemValue) via the shared
  * [[NbeEvaluator]] traversal — it only has to project ORE nodes onto [[NbeEvaluator.Term]], evaluating its ORE
  * type-argument sub-terms in the process.
  *
  * The evaluator always produces VLam for FunctionLiteral — the Checker is the only place that produces VPi.
  *
  * @param lookupTopDef
  *   Function to look up a top-level definition by ValueFQN, returning its semantic value
  */
class Evaluator(
    lookupTopDef: ValueFQN => Option[SemValue]
) extends NbeEvaluator[OperatorResolvedExpression](lookupTopDef) {

  override protected def decompose(
      env: Env,
      expr: OperatorResolvedExpression
  ): NbeEvaluator.Term[OperatorResolvedExpression] = expr match {
    case OperatorResolvedExpression.IntegerLiteral(value) =>
      NbeEvaluator.Term.IntegerLiteral(value.value)

    case OperatorResolvedExpression.StringLiteral(value) =>
      NbeEvaluator.Term.StringLiteral(value.value)

    case OperatorResolvedExpression.ParameterReference(name) =>
      NbeEvaluator.Term.ParameterReference(name.value)

    case OperatorResolvedExpression.ValueReference(vfqn, typeArgs) =>
      NbeEvaluator.Term.ValueReference(vfqn.value, typeArgs.map(ta => eval(env, ta.value)))

    case OperatorResolvedExpression.FunctionApplication(target, arg) =>
      NbeEvaluator.Term.FunctionApplication(target.value, arg.value)

    // Effects v6: the `row` phase writes every phantom binder inside a `with`'s subject and erases the node, so one
    // reaching the evaluator is a front-end defect, not a program the user can write. Fail loudly rather than
    // silently evaluate the subject as if nothing were bound.
    case OperatorResolvedExpression.WithBinding(_, implementation) =>
      throw IllegalStateException(
        s"A `with ${implementation.value}` binding survived row elaboration."
      )

    case OperatorResolvedExpression.FunctionLiteral(paramName, _, body) =>
      NbeEvaluator.Term.FunctionLiteral(paramName.value, body.value)
  }
}

object Evaluator {

  /** Reserved neutral head for the fail-safe fallback of [[applyValue]]: the stuck term minted when an argument is
    * applied to a non-applicable head (a `VConst` or `VType`). A dedicated reserved marker (not a variable), so this
    * head is definitionally distinct from every genuine variable and never unifies one away.
    */
  private val badApplyHead: SemValue.NeutralHead =
    SemValue.NeutralHead.Reserved(SemValue.NeutralHead.Marker.BadApply)

  /** Apply a semantic value to an argument.
    *
    * The head cases (`VLam`/`VPi` β-reduce, `VNative` fires, `VNeutral`/`VTopDef`/`VStuckNative`/`VMeta` grow their
    * spine) are exhaustive for every applicable head. The remaining heads — `VConst` and `VType` — are *not*
    * applicable: applying an argument to a literal, a concrete type value, or `Type` can only arise in an **ill-typed**
    * program (one the checker has already, or is about to, reject with a "Not a function." / "Type mismatch."). The
    * pure evaluator keeps running through that error-recovery window (errors abort only post-drain, after the body
    * walk), so this case must neither crash nor silently succeed.
    *
    * It therefore returns a **loud stuck form** — a [[SemValue.VNeutral]] on the reserved [[badApplyHead]] carrying the
    * argument in its spine — rather than the old identity-ish fallback that returned the argument `x` unchanged (which
    * silently collapsed `F[A]` to `A`; see the [[com.vanillasource.eliot.eliotc.monomorphize.check.TypeStackLoop]]
    * `applyTypeArgs` note). If this stuck value ever survives to read-back, the strict [[Quoter]] fails it ("Cannot
    * quote neutral value") ⤳ `PostDrainQuoter.quoteSem`'s "Cannot resolve type." — but a program with a genuine type
    * error aborts *before* quoting and reports that real diagnostic, so users see the real error, never this internal
    * one. Fail-safe: never a silent wrong value.
    */
  def applyValue(f: SemValue, x: SemValue): SemValue = f match {
    case VLam(_, closure) => closure(x)

    case VPi(_, codomain) => codomain(x)

    case VNative(_, fire) =>
      // Always fire — even on a non-concrete argument. The native's `fire` produces its own canonical stuck form
      // (a `VStuckNative` carrying the native's FQN and the renormalised spine) when an argument is not yet concrete,
      // so distinct stuck natives stay definitionally distinct and `renormalize` can re-fire them once the arguments
      // solve. (A previous neutral-only special case collapsed every native to a single anonymous neutral head, which
      // conflated, e.g., `add(x, y)` with `subtract(x, y)` under definitional equality.) Firing on a
      // neutral mirrors firing on a concrete value: a curried native simply yields the next `VNative` awaiting the
      // remaining arguments.
      fire(unfoldTopDef(x))

    case VNeutral(head, spine) => VNeutral(head, spine :+ x)

    case topDef: VTopDef => topDef.copy(spine = topDef.spine :+ x)

    case VStuckNative(fqn, spine) => VStuckNative(fqn, spine :+ x)

    case VMeta(id, spine) => VMeta(id, spine :+ x)

    case VConst(_) | VType =>
      // Non-applicable head in an ill-typed program (already-recorded diagnostic) — fail loud at read-back, never
      // silently return the argument. See the class-level note above.
      VNeutral(badApplyHead, Spine.SNil :+ x)
  }

  /** Unfold a VTopDef by evaluating its cached body and applying the spine. Does not require MetaStore — used at
    * evaluation time before metas exist.
    */
  private def unfoldTopDef(v: SemValue): SemValue = v match {
    case topDef @ VTopDef(_, Some(cached), _, _) =>
      unfoldTopDef(applySpine(topDef, cached.value))
    case _                                       => v
  }

  /** Apply a bodied [[SemValue.VTopDef]]'s spine to its evaluated body, skipping the written type arguments the body
    * has no binder for (see [[SemValue.VTopDef.TypeArgFit]]). Those arguments stay in the spine — they are the
    * definition's type application wherever it does *not* unfold — but beta-applying them here would land a type in the
    * first value parameter's slot and shift every value argument one to the left.
    *
    * A type argument is applied only while the body's head lambda *is* the generic binder it belongs to, matched by
    * name: a type-level body binds its own parameters, an ordinary `def`'s body binds only what
    * [[com.vanillasource.eliot.eliotc.monomorphize.processor.BindingClosure]] wrapped it for, and both present as
    * leading [[SemValue.VLam]]s. `applied` is clamped to the spine's actual length, so a spine some phase peeled an
    * entry off of (a carrier's payload) can only ever under-skip, never drop a value argument.
    */
  private def applySpine(topDef: VTopDef, base: SemValue): SemValue = topDef.typeArgFit match {
    case None      => topDef.spine.toList.foldLeft(base)(applyValue)
    case Some(fit) =>
      val args                  = topDef.spine.toList
      val (typeArgs, valueArgs) = args.splitAt(fit.applied min args.size)
      val afterTypeArgs         = typeArgs.zipWithIndex.foldLeft(base) { case (acc, (arg, index)) =>
        fit.genericNames.lift(index) match {
          // A declared generic binder: applied only if the body actually binds it.
          case Some(binder) =>
            acc match {
              case VLam(name, _) if name == binder => applyValue(acc, arg)
              case _                               => acc
            }
          // Past the declared generics there is no binder to erase: types are values, so `[]` here is just how an
          // ordinary argument was written (`fitsIn[lo, hi, min, max]`), and it applies like any other.
          case None         => applyValue(acc, arg)
        }
      }
      valueArgs.foldLeft(afterTypeArgs)(applyValue)
  }

  /** Deeply normalise a semantic value, **re-firing stuck native applications** whose arguments have since become
    * concrete.
    *
    * A native (e.g. `add`/`min`/`lessThanOrEqual`) applied to an argument that was a still-unsolved metavariable at
    * evaluation time goes stuck as a [[VStuckNative]] (its `fire` returns this canonical stuck form) — and ordinary
    * [[force]] never re-fires it, because the stuck form has dropped the `VNative` reducer and `force` only unfolds
    * *cached* bodies. This bites dependent-bounds arithmetic: `Int[LMin,LMax] + Int[RMin,RMax]` has result type
    * `Int[add(LMin,RMin), add(LMax,RMax)]`, and when that codomain is computed during application inference the bound
    * metavariables are not yet solved, so each `add(?,?)` sticks. By the time the inferred type is compared against the
    * expected type the metavariables *are* solved, but the `add`s never reduced.
    *
    * `renormalize` walks the value, [[force]]ing through solved metas. For each [[VStuckNative]] it renormalises the
    * spine and, via `lookupNative`, re-applies the native [[VNative]] reducer to the renormalised arguments — so a
    * now-fully-concrete `add(3, 4)` reduces to `7`. If re-firing still produces the same stuck native (an argument is
    * genuinely still abstract), that stuck form (already carrying the renormalised arguments) is kept and no progress
    * loops. A body-less `VTopDef` (a type constructor like `Int`, or an abstract `def`) is rebuilt with renormalised
    * arguments so that stuck natives nested in its spine (the bounds of `Int[add(LMin,RMin), …]`) re-fire; everything
    * else is returned as forced.
    *
    * `deep` additionally descends under [[VPi]] binders. An *intermediate* function type — a partial application's
    * codomain or the type of a curried head reference (`(+) : Int[L] -> Int[R] -> Int[add(L,R), …]`) — carries the
    * not-yet-fully-applied bounds in its codomain, and is never the value handed to the result-renormalisation at the
    * application site, so its stuck natives are not re-fired there. They are harmless to compilation (the uncurry pass
    * discards intermediate types) but must still be re-fired before read-back so the strict quoter does not reject a
    * stuck native that would otherwise be dropped. `deep` is therefore used **only post-drain at quote time**
    * ([[com.vanillasource.eliot.eliotc.monomorphize.check.PostDrainQuoter]]), where every metavariable is already
    * solved, so collapsing a binder's metas while descending is safe — unlike the shallow check-time use (e.g. the
    * application-result renormalisation), where metas under a binder may still be open mid-checking.
    *
    * `lookupNative` is the checker's binding cache (`vfqn => bindingCache.getOrElse(vfqn, None)`), which already holds
    * every native reachable from the term (prefetched before evaluation).
    */
  /** The native lookup the innermost [[renormalize]] is running under, for a consumer that has to renormalize from
    * *inside* an evaluation it did not start — the effect intrinsics' frames
    * ([[com.vanillasource.eliot.eliotc.monomorphize.processor.EffectIntrinsics]]), which must settle everything that
    * crosses a frame boundary while the frame is still installed, natives included.
    *
    * Dynamically scoped and installed only at the *outermost* call, so a recursive descent costs one reference
    * comparison rather than a thread-local write per node.
    */
  val defaultNativeLookup: ValueFQN => Option[SemValue] = _ => None

  val currentNativeLookup: DynamicVariable[ValueFQN => Option[SemValue]] = new DynamicVariable(defaultNativeLookup)

  def renormalize(
      v: SemValue,
      metaStore: MetaStore,
      lookupNative: ValueFQN => Option[SemValue],
      deep: Boolean = false
  ): SemValue =
    if (currentNativeLookup.value eq lookupNative) renormalizeUnder(v, metaStore, lookupNative, deep)
    else currentNativeLookup.withValue(lookupNative)(renormalizeUnder(v, metaStore, lookupNative, deep))

  private def renormalizeUnder(
      v: SemValue,
      metaStore: MetaStore,
      lookupNative: ValueFQN => Option[SemValue],
      deep: Boolean = false
  ): SemValue =
    force(v, metaStore) match {
      case VStuckNative(fqn, spine)      =>
        val args = spine.toList.map(renormalize(_, metaStore, lookupNative, deep))
        lookupNative(fqn) match {
          case Some(native: VNative) =>
            args.foldLeft(native: SemValue)(applyValue) match {
              // Still stuck on the same native (an argument is genuinely abstract) — keep it (renormalised args carried).
              case stuckAgain @ VStuckNative(stuckFqn, _) if stuckFqn == fqn => stuckAgain
              case fired                                                     => renormalize(fired, metaStore, lookupNative, deep)
            }
          case _                     =>
            // No native reducer found (should not happen for a stuck native) — keep the stuck form, renormalised.
            args.foldLeft(VStuckNative(fqn, Spine.SNil): SemValue)(applyValue)
        }
      case VTopDef(fqn, None, spine, _)  =>
        val args = spine.toList.map(renormalize(_, metaStore, lookupNative, deep))
        args.foldLeft(VTopDef(fqn, None, Spine.SNil): SemValue)(applyValue)
      case VPi(domain, codomain) if deep =>
        VPi(
          renormalize(domain, metaStore, lookupNative, deep),
          arg => renormalize(codomain(arg), metaStore, lookupNative, deep)
        )
      case forced                        => forced
    }

  /** Force a semantic value by walking solved metas and unfolding VTopDef. */
  def force(v: SemValue, metaStore: MetaStore): SemValue = v match {
    case VMeta(id, spine)                        =>
      metaStore.lookup(id) match {
        case Some(solved) =>
          val base = force(solved, metaStore)
          spine.toList.foldLeft(base)(applyValue)
        case None         => v
      }
    case topDef @ VTopDef(_, Some(cached), _, _) =>
      force(applySpine(topDef, force(cached.value, metaStore)), metaStore)
    case _                                       => v
  }

  import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes

  /** Convert a GroundValue to a SemValue: the read-back inverse for [[GroundValue.Structure]]s — the head FQN becomes a
    * [[VTopDef]] and the positional args become a spine in order. `GroundValue.Type` maps to [[VType]]. All other
    * ground values wrap as [[VConst]].
    *
    * Used by ability pattern matching so that concrete query-side ground arguments participate in structural
    * unification against pattern-side [[SemValue]]s produced by evaluating marker-function ORE signatures.
    */
  def groundToSem(g: GroundValue): SemValue = g match {
    case GroundValue.Type                    => VType
    case GroundValue.Structure(fqn, args, _) =>
      args.map(groundToSem).foldLeft(VTopDef(fqn, None, Spine.SNil): SemValue)(applyValue)
    case GroundValue.Param(index, args, _)   =>
      // Fail-safe (signature-unification C2): a `Param` is a leftover signature-twin binder, meant to be re-inflated to a
      // fresh metavariable by the value mono's dedicated re-inflation (which threads a Param→meta substitution — see
      // [[groundToSemParam]]). Reaching the plain conversion means a `Param` leaked outside a signature-twin fact; produce
      // a non-quotable *runtime-parameter* neutral (distinct head, negative level) so it fails **loudly** at read-back
      // ("Cannot quote neutral value") rather than silently carrying a parametric form into a ground signature.
      args
        .map(groundToSem)
        .foldLeft(
          VNeutral(NeutralHead.Param(-1 - index, s"$$sig-param-leak:$index"), Spine.SNil): SemValue
        )(applyValue)
    case _                                   => VConst(g)
  }

  /** [[groundToSem]] with a `Param → SemValue` substitution — the value mono's re-inflation of a *parametric* signature
    * twin (signature-unification C2). Each [[GroundValue.Param]] is replaced by `param(index)` (a fresh metavariable
    * the caller allocates once per index), preserving the constraint-covered deferral a leftover generic binder needs;
    * its applied spine is re-inflated positionally. Non-`Param` ground values convert exactly as [[groundToSem]].
    */
  def groundToSemParam(g: GroundValue, param: Int => SemValue): SemValue = g match {
    case GroundValue.Type                    => VType
    case GroundValue.Structure(fqn, args, _) =>
      args.map(groundToSemParam(_, param)).foldLeft(VTopDef(fqn, None, Spine.SNil): SemValue)(applyValue)
    case GroundValue.Param(index, args, _)   =>
      args.map(groundToSemParam(_, param)).foldLeft(param(index))(applyValue)
    case _                                   => VConst(g)
  }

  /** [[groundToSemPi]] with a `Param → SemValue` substitution (the value mono's re-inflation, C2): rebuild `VPi` for
    * function types, converting leaf [[GroundValue.Param]]s via [[groundToSemParam]].
    */
  def groundToSemPiParam(g: GroundValue, param: Int => SemValue): SemValue =
    g.asFunctionType match {
      case Some((domain, codomain)) => VPi(groundToSemPiParam(domain, param), _ => groundToSemPiParam(codomain, param))
      case None                     => groundToSemParam(g, param)
    }

  /** Convert a ground *signature* to a [[SemValue]], re-inflating [[VPi]] for function types. [[groundToSem]] alone
    * leaves a quoted `Function[A, B]` as a stuck `Function` [[VTopDef]] — the Π structure is lost at quote time and
    * [[renormalize]] does not re-fire a body-less top-def — which the body check (matching a `FunctionLiteral` against
    * a `VPi`) cannot use. This rebuilds `VPi(domain, _ => codomain)` recursively, with a **constant** codomain: a
    * ground signature is fully applied, so its codomain carries no residual dependency on the domain value. The
    * signature-twin flip (signature split, Step 6) uses this to turn the twin's reduced ground signature back into a
    * checkable type.
    */
  def groundToSemPi(g: GroundValue): SemValue =
    g.asFunctionType match {
      case Some((domain, codomain)) => VPi(groundToSemPi(domain), _ => groundToSemPi(codomain))
      case None                     => groundToSem(g)
    }

  /** Ground type for BigInteger values (used by eval for IntegerLiteral). */
  val bigIntGroundType: GroundValue =
    GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  /** Ground type for String values (used by eval for StringLiteral). */
  val stringGroundType: GroundValue =
    GroundValue.Structure(WellKnownTypes.stringFQN, Seq.empty, GroundValue.Type)

  /** Ground type for Bool values (used by the compile-time Bool natives). */
  val boolGroundType: GroundValue =
    GroundValue.Structure(WellKnownTypes.boolFQN, Seq.empty, GroundValue.Type)

  /** Compile-time Bool values. The runtime representation is platform-dependent (the JVM backend maps Bool to a
    * platform boolean); these are the values the NbE evaluator reduces type-level predicates to.
    */
  val trueValue: SemValue  = VConst(GroundValue.Direct(true, boolGroundType))
  val falseValue: SemValue = VConst(GroundValue.Direct(false, boolGroundType))

  /** The compile-time `Unit` placeholder: what a `match` hands a field-less constructor's handler, what the effect
    * intrinsics apply a thunk to and answer for `write`. `unit` itself is a body-less base leaf with no compile-time
    * body, and nothing ever inspects a unit, so one inert constant serves every reader.
    */
  val unitValue: SemValue = VConst(GroundValue.Direct((), GroundValue.Type))
}
