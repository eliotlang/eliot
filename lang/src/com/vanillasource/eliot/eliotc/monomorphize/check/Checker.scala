package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.{Evaluator, Quoter}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{BodyValueReferences, CompilerMonomorphicValue, GroundValue}
import com.vanillasource.eliot.eliotc.monomorphize.unify.UnifyResult
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** Bidirectional type checker for the NbE pipeline. All state is threaded via the CheckIO state monad.
  *
  *   - `check(tm, expected)` checks a term against a known type.
  *   - `infer(tm)` infers a term's type.
  *
  * The checker produces [[SemExpression]]s with [[SemValue]] in every type slot. All ground-type conversion is deferred
  * to a post-drain pass in [[TypeStackLoop]], using [[com.vanillasource.eliot.eliotc.monomorphize.eval.Quoter]]. This
  * avoids any silent "default to Type" behaviour for unsolved metas — they surface as explicit errors at quoting time.
  */
class Checker(
    fetchBinding: ValueFQN => CompilerIO[Option[SemValue]],
    resolveAbility: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[(ValueFQN, Seq[GroundValue])]],
    track: Track,
    // Whether this checker serves a signature-twin mono (the signature split, Step 6). A signature twin's own mono walks
    // its signature (which may reference self-in-signature type constructors like `Function`/`Type`), so the callee flip
    // must **not** fire there — reading `CompilerMonomorphicValue(Function@Signature, …)` while computing exactly that
    // fact is a demand cycle. The flip therefore fires only for body checks (a runtime/compiler *value* mono), whose
    // callees are other values; the twin those bodies read then computes its signature in place, bottoming the demand.
    signatureOnly: Boolean = false
) {

  /** The track's platform — fact keys read it off the [[track]] rather than threading a bare [[Platform]]. */
  private val platform: Platform = track.platform

  /** The higher-kinded-carrier kind checker (D8): seeds each `[F[_]]` carrier instantiation meta with its expected kind
    * and verifies the solution post-drain. A non-equality *kind system*, kept out of this checker's definitional
    * equality concern. Called from [[instantiatePolymorphic]] (seed) and [[TypeStackLoop]]'s `carrier-kinds` pass
    * (verify). See [[CarrierKindChecker]].
    */
  private[check] val carriers: CarrierKindChecker =
    new CarrierKindChecker(force, (tm, env) => evalExpr(tm, env), doUnify, platform)

  /** The effectful-signatures guard discharge (W2b): runs a `{Throw[String]}` return guard on the compile-time
    * `Either[String, _]` carrier to obtain (or reject) the return type. A non-equality concern, kept out of this
    * checker's definitional equality concern. Called from [[infer]] / [[applyInferred]] (read sides) and
    * [[TypeStackLoop]] (callee side). See [[GuardDischargeResolver]].
    */
  private[check] val guards: GuardDischargeResolver =
    new GuardDischargeResolver(force, freshMeta)

  /** The ability-resolution saturation concern: discovering ability-qualified references and resolving each to its
    * concrete impl. A non-equality *saturation* pass, kept out of this checker's definitional equality concern. Called
    * only from [[TypeStackLoop]] (`processIO` seeds refs via `collectAbilityRefs`; the `resolve-abilities` post-drain
    * pass drives `resolveAbilities`). Constructed with the CompilerIO primitives it needs plus the platform. See
    * [[AbilityResolver]].
    */
  private[check] val abilityResolver: AbilityResolver =
    new AbilityResolver(resolveAbility, bindingForwards, platform)

  /** Which binders of `method` take the call's binding when `member` dispatches to it, off the two declarations — both
    * referenced and resolved, so present. See [[DeclaredEntries.forwarded]].
    */
  private def bindingForwards(member: ValueFQN, method: ValueFQN): CompilerIO[Seq[(Int, Int)]] =
    for {
      memberValue <- getFactOrAbort(OperatorResolvedValue.Key(member, platform))
      clauseValue <- getFactOrAbort(OperatorResolvedValue.Key(method, platform))
    } yield DeclaredEntries.forwarded(memberValue, clauseValue)

  /** Ensure a NativeBinding is in the cache, fetching it via CompilerIO if needed. */
  private def ensureBinding(vfqn: ValueFQN): CheckIO[Option[SemValue]] =
    for {
      cached <- inspect(_.bindingCache.get(vfqn))
      result <- cached match {
                  case Some(value) => pure(value)
                  case None        =>
                    for {
                      opt <- liftF(fetchBinding(vfqn))
                      _   <- modify(_.cacheBinding(vfqn, opt)) // cache before recursing so cycles short-circuit
                      _   <- ensureBodyBindings(vfqn)
                    } yield opt
                }
    } yield result

  /** Ensure the bindings of everything `vfqn`'s checking body references transitively — reached via the memoized
    * [[BodyValueReferences]] fact (walked once per value, never re-walked here) and recursing through `ensureBinding`,
    * whose binding-cache short-circuit both dedups and terminates on the (recursion-free, but diamond-shaped) reference
    * DAG. This is what lets `renormalize` re-fire a nested stuck native once its bound metavariables solve: a native
    * reached only through a bodied helper (e.g. `multiply`/`lessThanOrEqual`/`fold` inside a derived `multiplyMin` used
    * in the `*` result type) must already be in the flat [[CheckState.bindingCache]] the re-fire lookup consults.
    */
  private def ensureBodyBindings(vfqn: ValueFQN): CheckIO[Unit] =
    for {
      refs <- liftF(getFactIfProduced(BodyValueReferences.Key(vfqn, platform)))
      _    <- refs.fold(Set.empty[ValueFQN])(_.references).toList.traverse_(ensureBinding)
    } yield ()

  /** Evaluate an ORE expression against an env (defaulting to the current state's env). Prefetches every reachable
    * binding into [[CheckState.bindingCache]] first — including rewriting abstract associated-ability-types to fresh
    * metas via [[ensureBinding]] — so that the pure [[Evaluator]] has everything it needs.
    */
  def evalExpr(tm: OperatorResolvedExpression, env: Option[Env] = None): CheckIO[SemValue] =
    for {
      _ <- prefetchBindings(tm)
      s <- get
    } yield s.makeEvaluator.eval(env.getOrElse(s.rho), tm)

  /** Force a SemValue through the current meta store. */
  private[check] def force(v: SemValue): CheckIO[SemValue] =
    inspect(s => Evaluator.force(v, s.unifier.metaStore))

  /** Deeply normalise a SemValue, re-firing stuck native applications (e.g. the dependent-bounds `add` in
    * `Int[add(LMin,RMin), …]`) whose bound arguments have since been solved. Uses the binding cache as the native
    * lookup. See [[Evaluator.renormalize]].
    */
  private[check] def renormalize(v: SemValue): CheckIO[SemValue] =
    inspect(s => Evaluator.renormalize(v, s.unifier.metaStore, fqn => s.bindingCache.getOrElse(fqn, None)))

  /** Unify two semantic values, updating the unifier in the state. */
  private def doUnify(l: SemValue, r: SemValue, context: Sourced[String]): CheckIO[Unit] =
    modify(s => s.withUnifier(s.unifier.unify(l, r, context)))

  /** Speculatively unify, committing the solutions on success and leaving the state untouched (no error either) on
    * contradiction — the definitional-equality step (arm 1) of the check-mode resolution ladder.
    */
  private def tryUnifyCommitting(actual: SemValue, expected: SemValue, context: Sourced[String]): CheckIO[Boolean] =
    get.flatMap(s =>
      s.unifier.tryUnify(actual, expected, context) match {
        case UnifyResult.Unified(u)       => modify(_.withUnifier(u)).as(true)
        case UnifyResult.Contradiction(_) => pure(false)
      }
    )

  /** Allocate a fresh metavariable. */
  private[check] def freshMeta: CheckIO[VMeta] =
    for {
      s                   <- get
      (metaId, freshStore) = s.unifier.metaStore.fresh
      _                   <- modify(_.withUnifier(s.unifier.copy(metaStore = freshStore)))
    } yield VMeta(metaId, Spine.SNil)

  /** Check a term against a known expected type. */
  def check(
      tm: Sourced[OperatorResolvedExpression],
      expected: SemValue
  ): CheckIO[SemExpression] =
    for {
      forcedExpected <- force(expected)
      result         <- tm.value match {
                          // FunctionLiteral against a known VPi — unify domain, bind param, check body against codomain.
                          // Works for both annotated (unify annotated paramType with domain) and unannotated (use domain
                          // as paramType). Attribution falls to the body on codomain mismatches.
                          case OperatorResolvedExpression.FunctionLiteral(paramName, paramTypeStack, body)
                              if forcedExpected.isInstanceOf[VPi] =>
                            val VPi(domain, codomain) = forcedExpected: @unchecked
                            for {
                              paramType    <- paramTypeStack match {
                                                case Some(ts) =>
                                                  for {
                                                    pt <- evalExpr(ts.value)
                                                    _  <- doUnify(pt, domain, paramName.as("Type mismatch."))
                                                  } yield pt
                                                case None     => pure(domain)
                                              }
                              // Genuine dependent Π: bind the parameter's *type* in Γ and a fresh neutral standing for
                              // its (unknown) runtime *value* in ρ, then check the body against `codomain(neutral)` —
                              // never `codomain(paramType)`, which would substitute the parameter's type where its value
                              // belongs. Today every `VPi` codomain is constant (the `Function` native / `infer` build
                              // `_ => B`), so the two agree; the neutral is the correct form once dependent types land.
                              paramNeutral <- inspect(_.paramNeutral(paramName.value))
                              _            <- modify(_.bindValueParam(paramName.value, paramType))
                              bodyExpr     <- check(body, codomain(paramNeutral))
                            } yield SemExpression(
                              forcedExpected,
                              SemExpression.FunctionLiteral(paramName, paramType, body.as(bodyExpr))
                            )

                          // Unannotated FunctionLiteral against non-VPi expected — cannot infer param type.
                          case OperatorResolvedExpression.FunctionLiteral(_, None, _) =>
                            liftF(compilerError(tm.as("Cannot infer type of unannotated lambda.")) >> abort)

                          // An immediately-applied unannotated lambda `(x -> body)(arg)` checked against a known type — a
                          // `let`. Infer the argument's type for the binder and push `expected` down into the body. This
                          // is the shape a non-effectful block `val`/statement lowers to.
                          case OperatorResolvedExpression.FunctionApplication(target, arg)
                              if isUnannotatedLambda(target.value) =>
                            val OperatorResolvedExpression.FunctionLiteral(paramName, _, body) =
                              target.value: @unchecked
                            typeImmediateLambda(target, paramName, body, arg, Some(forcedExpected)).map(_._1)

                          case _ =>
                            for {
                              (expr, inferred) <- infer(tm)
                              checkedResult    <- checkAgainst(tm, expr, inferred, expected)
                            } yield checkedResult
                        }
    } yield result

  /** Check-mode resolution at a *return boundary* (a lambda body against its codomain, a def body against its declared
    * return): the shared [[resolveGuardedLadder]].
    */
  private def checkAgainst(
      tm: Sourced[OperatorResolvedExpression],
      expr: SemExpression,
      inferred: SemValue,
      expected: SemValue
  ): CheckIO[SemExpression] =
    checkAgainstDefault(tm, expr, inferred, expected)

  /** The return-boundary resolution: [[resolveGuardedLadder]] (see [[checkAgainst]]'s doc). The one path, on both
    * tracks, since the v2 uniform bridge was deleted — the elaborator writes the carrier, so a return boundary is an
    * ordinary definitional-equality judgment with the pure-lift arm behind it.
    */
  private def checkAgainstDefault(
      tm: Sourced[OperatorResolvedExpression],
      expr: SemExpression,
      inferred: SemValue,
      expected: SemValue
  ): CheckIO[SemExpression] =
    resolveGuardedLadder(tm, expr, inferred, expected)

  /** The check-mode resolution ladder shared by return boundaries ([[checkAgainst]]) and spine argument slots
    * ([[checkArgumentSlot]]), fronted by the W2b guard-kind acceptance. A value whose type is on the compile-time
    * `Throw[String]` carrier types as `Either[..]`, not `Type`; where a `Type` kind is expected, accept it as a
    * *guarded type* (discharged to its payload — or rejected — by the signature/read-site discharge) rather than
    * letting the unifier reject `Either[..]` ≠ `Type`. Otherwise runs the plain [[resolveLadder]]. The expectation is
    * forced here (not at the callers): by the time the ladder runs, inference of the term may have solved metas in it.
    *
    * This is the single entry every check site shares — [[check]]'s fallback via [[checkAgainst]], and
    * [[checkArgumentSlot]].
    */
  private def resolveGuardedLadder(
      tm: Sourced[OperatorResolvedExpression],
      expr: SemExpression,
      inferred: SemValue,
      expected: SemValue,
  ): CheckIO[SemExpression] =
    for {
      forcedExpected <- force(expected)
      guardKind      <- forcedExpected match {
                          case VType => guards.isGuardCarrier(inferred)
                          case _     => pure(false)
                        }
      outcome        <- if (guardKind) pure(expr) else resolveLadder(tm, expr, inferred, expected)
    } yield outcome

  /** The check-mode resolution ladder proper — the algorithm shared verbatim by return boundaries and argument slots
    * (R3-1 dedup): polytype instantiation, then the pure-wrap pre-arm for the shape definitional equality can only
    * *degenerately* solve, then unify → pure-wrap → committed mismatch.
    *
    * Position no longer changes the ladder. Under effects-as-rows the desugar writes every bind the elaboration needs
    * and, since §1 rule 4, classifies every position from its declaration, so the position-dependent arms the v2 ladder
    * carried — the bind-lift pre-arm and failure arm (argument slots only) and the pure-boundary `Id` defaulting
    * (return boundaries only) — became unreachable and were deleted with the `allowBindLift` flag that selected them
    * (docs/effects-as-rows.md §4; every one of their call sites fired zero times across the full gate). A carrier
    * reaching a pure position that this ladder cannot reconcile is now a committed mismatch, which is the fail-safe
    * direction: a loud error, never a silently stripped effect.
    */
  private def resolveLadder(
      tm: Sourced[OperatorResolvedExpression],
      expr: SemExpression,
      inferred: SemValue,
      expected: SemValue
  ): CheckIO[SemExpression] =
    for {
      (updatedExpr, instantiated) <- instantiatePolymorphic(expr, inferred)
      out                         <- resolveFailureLadder(tm, updatedExpr, instantiated, expected)
    } yield out

  /** The failure ladder consulted when definitional equality does not immediately unify: commit the exact mismatch.
    * Since effects v6 there is no arm between the two — a pure term meeting a carrier-headed expectation was the one
    * shape that recovered here, and there is no carrier.
    */
  private def resolveFailureLadder(
      tm: Sourced[OperatorResolvedExpression],
      updatedExpr: SemExpression,
      instantiated: SemValue,
      expected: SemValue
  ): CheckIO[SemExpression] =
    tryUnifyCommitting(instantiated, expected, tm.as("Type mismatch.")).flatMap {
      case true  => pure(updatedExpr)
      case false => commitMismatch(instantiated, expected, tm, updatedExpr)
    }

  /** Commit the exact `instantiated`/`expected` mismatch into the unifier (deferred to drain, like all mismatches) and
    * yield the un-coerced expression as the slot's contribution.
    */
  private def commitMismatch(
      instantiated: SemValue,
      expected: SemValue,
      tm: Sourced[OperatorResolvedExpression],
      fallbackExpr: SemExpression
  ): CheckIO[SemExpression] =
    modify(st => st.withUnifier(st.unifier.addMismatch(instantiated, expected, tm.as("Type mismatch."))))
      .as(fallbackExpr)

  /** Peel leading VLam closures by substituting fresh metas; return the non-VLam head together with the fresh metas in
    * order.
    *
    * @param bindInEnv
    *   Whether to bind each peeled parameter name to its fresh meta in the env. `false` (the default) is required for
    *   polytype instantiation inside the checker, so the callee's type-parameter names don't shadow any caller-scope
    *   parameter with the same name. `true` is used only by the top-level signature walk, where leftover type
    *   parameters should become in-scope names.
    */
  private[check] def peelLams(
      sem: SemValue,
      bindInEnv: Boolean = false
  ): CheckIO[(SemValue, Seq[SemValue])] = {
    def loop(s: SemValue, acc: Seq[SemValue]): CheckIO[(SemValue, Seq[SemValue])] =
      for {
        forced <- force(s)
        result <- forced match {
                    case VLam(name, closure) =>
                      for {
                        meta <- freshMeta
                        _    <- if (bindInEnv) modify(_.bindTypeParam(name, meta)) else pure(())
                        rest <- loop(closure(meta), acc :+ meta)
                      } yield rest
                    case other               => pure((other, acc))
                  }
      } yield result
    loop(sem, Seq.empty)
  }

  /** Infer the type of a term. */
  def infer(
      tm: Sourced[OperatorResolvedExpression]
  ): CheckIO[(SemExpression, SemValue)] = tm.value match {
    case OperatorResolvedExpression.IntegerLiteral(value) =>
      // Use the same VTopDef shape that DataTypeNativesProcessor binds for BigInteger, so the unifier sees a
      // single canonical form for this type rather than a VConst(Structure) vs VTopDef mismatch.
      val tpe = VTopDef(WellKnownTypes.bigIntFQN, None, Spine.SNil)
      pure((SemExpression(tpe, SemExpression.IntegerLiteral(value)), tpe))

    case OperatorResolvedExpression.StringLiteral(value) =>
      val tpe = VTopDef(WellKnownTypes.stringFQN, None, Spine.SNil)
      pure((SemExpression(tpe, SemExpression.StringLiteral(value)), tpe))

    case OperatorResolvedExpression.ParameterReference(name) =>
      for {
        state  <- get
        // A parameter's type is read straight from Γ ([[CheckState.gamma]]). Γ already holds the right type for every
        // kind of binder: a runtime value parameter's declared type, an erased type parameter's recovered type,
        // a peeled instantiation meta. (Type-position references go through the evaluator against ρ, never this path.)
        result <- state.gamma.lookupByName(name.value) match {
                    case Some(tpe) =>
                      pure((SemExpression(tpe, SemExpression.ParameterReference(name)), tpe))
                    case None      =>
                      liftF(compilerError(tm.as("Name not defined.")) >> abort)
                  }
      } yield result

    case OperatorResolvedExpression.ValueReference(vfqn, typeArgs) =>
      for {
        explicitTypeArgs <- typeArgs.traverse(explicitTypeArgument)
        result           <- inferValueReference(tm, vfqn, explicitTypeArgs)
      } yield result

    case OperatorResolvedExpression.FunctionApplication(_, _) =>
      inferSpine(tm)

    // Effects v6: the `row` phase writes every phantom binder inside a `with`'s subject and erases the node, so one
    // reaching the checker is a front-end defect. Fail loudly rather than check the subject as if nothing were bound.
    case OperatorResolvedExpression.WithBinding(_, implementation, _) =>
      throw IllegalStateException(s"A `with ${implementation.value.show}` binding survived row elaboration.")

    case OperatorResolvedExpression.FunctionLiteral(paramName, Some(paramTypeExpr), body) =>
      for {
        paramType            <- evalExpr(paramTypeExpr.value)
        _                    <- modify(_.bindValueParam(paramName.value, paramType))
        (bodyExpr, bodyType) <- infer(body)
        tpe                   = VPi(paramType, _ => bodyType)
      } yield (
        SemExpression(
          tpe,
          SemExpression.FunctionLiteral(paramName, paramType, body.as(bodyExpr))
        ),
        tpe
      )

    case OperatorResolvedExpression.FunctionLiteral(_, None, _) =>
      liftF(compilerError(tm.as("Cannot infer type of unannotated lambda.")) >> abort)
  }

  /** One written type argument: evaluated, except the `row` phase's hole ([[WellKnownTypes.inferredTypeArgumentFQN]]),
    * which is a fresh metavariable — solved from the call's arguments like a binder past the end of the list.
    */
  private def explicitTypeArgument(typeArg: Sourced[OperatorResolvedExpression]): CheckIO[SemValue] =
    typeArg.value match {
      case OperatorResolvedExpression.ValueReference(name, Seq()) if name.value === WellKnownTypes.inferredTypeArgumentFQN =>
        freshMeta.widen[SemValue]
      case other                                                                                                       =>
        evalExpr(other)
    }

  /** Infer a value reference's type from its saturated signature, applying the given (already-evaluated) explicit type
    * arguments.
    */
  private def inferValueReference(
      tm: Sourced[OperatorResolvedExpression],
      vfqn: Sourced[ValueFQN],
      explicitTypeArgs: Seq[SemValue]
  ): CheckIO[(SemExpression, SemValue)] =
      for {
        _      <- ensureBinding(vfqn.value)
        svOpt  <- liftF(getFactIfProduced(SaturatedValue.Key(vfqn.value, platform)))
        result <- svOpt match {
                    case Some(sv) =>
                      // Read the *saturated* signature, so a callee's parameter-position bare omittable references
                      // (e.g. bare `Int`) present as ordinary leading generic binders that the instantiation machinery
                      // solves from this call's arguments. Signatures reference only their own parameters or top-level
                      // values, so they evaluate under an empty env — outer-session bindings are not in scope.
                      for {
                        // The Step-6 callee flip: when this reference fully applies the callee (an argument per binder)
                        // and every argument is ground, read the callee's reduced ground signature from its signature
                        // twin's mono (`CompilerMonomorphicValue(callee@Signature, groundArgs)`) instead of evaluating the
                        // callee's signature in place. Otherwise (a partially-applied callee whose remaining binders this
                        // call infers, a not-yet-ground argument, a marker, or an unproduced twin) fall back to the
                        // in-place evaluation, which handles inference metas the ground read cannot.
                        appliedSig       <- flippedCalleeSignature(vfqn, sv, explicitTypeArgs).flatMap {
                                              case Some(flipped) => pure(flipped)
                                              case None          =>
                                                evalExpr(sv.value.signature.value, env = Some(Env.empty))
                                                  .map(sig => explicitTypeArgs.foldLeft(sig)(Evaluator.applyValue))
                                            }
                        // Discharge a `{Throw[String]}` guard on a *complete* (fully applied) value read by name (W2b):
                        // `def y: Bar = foo` where `foo`'s return is `Right(Bar)`. A guarded *function* read unapplied
                        // stays a `VPi`/`VLam` (the guard is in its codomain), so it is left untouched here.
                        discharged       <- guards.dischargeGuardedReturn(appliedSig, vfqn).map(_.getOrElse(appliedSig))
                      } yield (
                        SemExpression(discharged, SemExpression.ValueReference(vfqn, explicitTypeArgs)),
                        discharged
                      )
                    // A resolved name with no saturated value is a definition an earlier phase rejected, which reported
                    // why. Its name was resolved, so "not defined" would point at a name that plainly is; this names
                    // the definition instead (`docs/effects.md` §8 item 21).
                    case None     =>
                      liftF(
                        compilerError(
                          tm.as(
                            s"Cannot use '${vfqn.value.name.name}' here: its own definition was rejected."
                          ),
                          Seq(s"Fix the errors reported at '${vfqn.value.name.name}'.")
                        ) >> abort
                      )
                  }
      } yield result

  /** The Step-6 callee flip (signature split): a callee reference's *own* reduced ground signature, read from
    * `CompilerMonomorphicValue(callee@Signature, groundArgs)` — the same twin mono the value's own signature already
    * flips to. Applies only when the reference **fully applies** the callee (an explicit argument per binder, so the
    * ground read's per-instantiation defaulting matches the caller's intent) and **every argument is ground** (a
    * quotable [[SemValue]]); a partial application whose remaining binders this call infers, or an argument still an
    * unsolved meta, keeps the in-place evaluation that handles those. `None` falls back. Acyclic: the twin's own mono
    * checks no body, so it never re-enters this path.
    */
  private def flippedCalleeSignature(
      vfqn: Sourced[ValueFQN],
      sv: SaturatedValue,
      explicitTypeArgs: Seq[SemValue]
  ): CheckIO[Option[SemValue]] =
    if (
      signatureOnly ||
      explicitTypeArgs.sizeIs != SignatureView.of(sv.value.signature).binders.size
    ) pure(None)
    else
      get.flatMap { s =>
        explicitTypeArgs.toList.traverse(a => Quoter.quote(0, a, s.unifier.metaStore)) match {
          case Right(groundArgs) =>
            liftF(
              getFactIfProduced(
                CompilerMonomorphicValue.Key(vfqn.value.copy(name = vfqn.value.name.signatureTwin), groundArgs)
              )
            ).map(_.map(cmv => Evaluator.groundToSemPi(cmv.signature)))
          case Left(_)           => pure(None)
        }
      }

  /** Infer a function application by operating on its full spine: decompose the nested (curried) applications at the
    * root, resolve the head once — an immediately-applied unannotated lambda `(x -> body)(arg)` routes to
    * [[typeImmediateLambda]] (it is a `let`, the shape a non-effectful block `val`/statement lowers to; the lambda
    * alone has no inferable parameter type, so it is inferred from the first argument), anything else is inferred —
    * then resolve the arguments left to right:
    *
    *   - **each slot** ([[applyInferred]]) runs the resolution ladder immediately, left to right. There is no second
    *     phase: the deferral that used to hold a bare flex domain receiving a carrier-headed argument went with the
    *     carrier (effects v6, F3), and nothing is folded *around* the core — the checker inserts no binds.
    *
    * Each fold step receives the intermediate application node's own [[Sourced]] target, so diagnostics and the
    * rebuilt [[SemExpression]] keep the exact positions the former per-curried-node recursion produced.
    */
  private def inferSpine(tm: Sourced[OperatorResolvedExpression]): CheckIO[(SemExpression, SemValue)] = {
    val (head, apps) = decomposeSpine(tm)
    inferSpineApplications(tm, head, apps)
  }

  private def inferSpineApplications(
      tm: Sourced[OperatorResolvedExpression],
      head: Sourced[OperatorResolvedExpression],
      apps: List[(Sourced[OperatorResolvedExpression], Sourced[OperatorResolvedExpression])]
  ): CheckIO[(SemExpression, SemValue)] = {
    for {
      (start, rest) <- head.value match {
                            case OperatorResolvedExpression.FunctionLiteral(paramName, None, body) =>
                              typeImmediateLambda(head, paramName, body, apps.head._2, None).map((_, apps.tail))
                            case _                                                                 =>
                              infer(head).map((_, apps))
                          }
      result        <- rest.foldLeftM(start) { case ((targetExpr, targetType), (target, arg)) =>
                         applyInferred(target, targetExpr, targetType, arg)
                       }
    } yield result
  }

  /** Decompose a nested (curried) application into its head and, for each argument, the intermediate application
    * node's target paired with that argument — e.g. `f(a)(b)` yields `(f, [(f, a), (f(a), b)])`. The intermediate
    * targets carry the [[Sourced]] positions the per-argument logic attributes errors to.
    */
  private def decomposeSpine(
      tm: Sourced[OperatorResolvedExpression]
  ): (Sourced[OperatorResolvedExpression], List[(Sourced[OperatorResolvedExpression], Sourced[OperatorResolvedExpression])]) =
    tm.value match {
      case OperatorResolvedExpression.FunctionApplication(target, arg) =>
        val (head, apps) = decomposeSpine(target)
        (head, apps :+ (target, arg))
      case _                                                           => (tm, Nil)
    }

  /** Apply one argument of a spine ([[inferSpine]]'s Phase-A fold step): peel any polytype (`VLam`) layers with fresh
    * metas, then apply the argument to the resulting monotype. If the monotype isn't already `VPi`, it gets unified
    * against a fresh one. The implicit metas introduced by peeling are baked into the target reference. The argument
    * itself is resolved by [[checkArgumentSlot]].
    */
  private def applyInferred(
      target: Sourced[OperatorResolvedExpression],
      targetExpr: SemExpression,
      targetType: SemValue,
      arg: Sourced[OperatorResolvedExpression]
  ): CheckIO[(SemExpression, SemValue)] =
    for {
      (updatedTarget, peeled) <- instantiatePolymorphic(targetExpr, targetType)
      vpi                     <- peeled match {
                                   case p: VPi => pure(p)
                                   case _      =>
                                     for {
                                       domMeta <- freshMeta
                                       codMeta <- freshMeta
                                       p        = VPi(domMeta, _ => codMeta)
                                       _       <- doUnify(peeled, p, target.as("Not a function."))
                                     } yield p
                                 }
      argExpr                 <- checkArgumentSlot(arg, vpi.domain)
      argSem                  <- evalExpr(arg.value)
      // The codomain may embed a native applied to the target's instantiation metas — e.g. a dependent result type
      // `Int[add(LMin,RMin), …]`. Those bounds are solved by the argument checks just above, so renormalise the
      // codomain now to re-fire the natives (`add(3,4) ⤳ 7`) before the type reaches unification or quoting. A *bare*
      // metavariable result (a result-position type parameter, e.g. `pick[A](a,b): A`) has nothing to re-fire and is
      // left untouched; it resolves through the meta store wherever it is next forced.
      rawRetType               = vpi.codomain(argSem)
      retType                 <- rawRetType match {
                                   case _: VMeta => pure(rawRetType)
                                   // Renormalise the codomain (re-firing any stuck guard natives now that the argument
                                   // checks solved their bounds), then discharge a `{Throw[String]}` guard the callee
                                   // returns (W2b) — `Right(t)` ⤳ `t`, `Left` aborts, a still-stuck guard is left to
                                   // defer at this generic caller's own site.
                                   case other    =>
                                     renormalize(other).flatMap(rn =>
                                       guards.dischargeGuardedReturn(rn, target).map(_.getOrElse(rn))
                                     )
                                 }
    } yield (
      SemExpression(
        retType,
        SemExpression.FunctionApplication(target.as(updatedTarget), arg.as(argExpr))
      ),
      retType
    )

  /** Resolve one spine argument against its parameter domain. Lambda-shaped arguments route through the ordinary
    * [[check]] (the immediately-applied-lambda `let` shape needs the expected type pushed down); everything else is
    * inferred once and run through the shared resolution ladder ([[resolveGuardedLadder]], which folds in the
    * effectful-signatures kind acceptance (W2b) exactly as the return-boundary [[checkAgainst]] does).
    */
  private def checkArgumentSlot(
      arg: Sourced[OperatorResolvedExpression],
      domain: SemValue
  ): CheckIO[SemExpression] =
    arg.value match {
      case _: OperatorResolvedExpression.FunctionLiteral                                                 =>
        check(arg, domain)
      case OperatorResolvedExpression.FunctionApplication(target, _) if isUnannotatedLambda(target.value) =>
        check(arg, domain)
      case _                                                                                             =>
        for {
          (argExpr, argType) <- infer(arg)
          forcedDomain       <- force(domain)
          outcome            <- defaultArgSlot(arg, argExpr, argType, forcedDomain)
        } yield outcome
    }

  /** Phase-A argument-slot resolution — the one path for every slot on both tracks since the v2 uniform bridge was
    * deleted. Every slot runs the shared resolution ladder.
    *
    * The *deferral* arm went with the carrier (effects v6, F3): it existed to hold a bare flex domain receiving an
    * effect-carrier-headed argument until the compile track's Phase B could pin the carrier. There is no carrier to
    * pin — an implementation is a written phantom binder — so no slot's decision waits on one.
    */
  private def defaultArgSlot(
      arg: Sourced[OperatorResolvedExpression],
      argExpr: SemExpression,
      argType: SemValue,
      forcedDomain: SemValue
  ): CheckIO[SemExpression] =
    resolveGuardedLadder(arg, argExpr, argType, forcedDomain)

  /** Whether `expr` is an unannotated function literal `(x -> body)`. Its parameter type cannot be inferred from the
    * literal alone; when it is *immediately applied* the type is taken from the argument (see [[typeImmediateLambda]]).
    */
  private def isUnannotatedLambda(expr: OperatorResolvedExpression): Boolean = expr match {
    case OperatorResolvedExpression.FunctionLiteral(_, None, _) => true
    case _                                                      => false
  }

  /** Type an immediately-applied unannotated lambda `(param -> body)(arg)` — a `let` (the shape a block
    * `val`/statement lowers to). The parameter type is taken from the (instantiated) argument; the body is checked
    * against `expected` when known (pushing the type down) and inferred otherwise. Returns the rebuilt application
    * expression and its type.
    *
    * It is an ordinary `let` and nothing more. The bind rule that used to sit here — an effect-carrier-headed argument
    * sequenced into `flatMap/map(param -> body, arg)` — went with the carrier (effects v6, F3): a block `val` binding
    * an effectful call binds its *value*, because the effect is performed by the callee under the implementation the
    * write handed it, not staged into a carrier the checker has to thread.
    */
  private def typeImmediateLambda(
      target: Sourced[OperatorResolvedExpression],
      paramName: Sourced[String],
      body: Sourced[OperatorResolvedExpression],
      arg: Sourced[OperatorResolvedExpression],
      expected: Option[SemValue]
  ): CheckIO[(SemExpression, SemValue)] =
    for {
      (argExpr0, argType0) <- infer(arg)
      (argExpr, argType)   <- instantiatePolymorphic(argExpr0, argType0)
      _                    <- modify(_.bindValueParam(paramName.value, argType))
      (bodyExpr, bodyType) <- expected match {
                                case Some(exp) => check(body, exp).map(e => (e, exp))
                                case None      => infer(body)
                              }
      lamType               = VPi(argType, _ => bodyType)
      lamExpr               =
        SemExpression(lamType, SemExpression.FunctionLiteral(paramName, argType, body.as(bodyExpr)))
    } yield (
      SemExpression(bodyType, SemExpression.FunctionApplication(target.as(lamExpr), arg.as(argExpr))),
      bodyType
    )

  /** Peel leading `VLam` closures from an inferred type with fresh metas, baking the metas as implicit type arguments
    * onto the expression's [[SemExpression.ValueReference]] and updating its `expressionType`. Returns the updated
    * expression paired with the peeled (monotype) type. Used both by the generic `check` fallback and by
    * [[applyInferred]] — any polytype introduced by referencing a generic value gets instantiated at exactly one place.
    */
  private def instantiatePolymorphic(
      expr: SemExpression,
      tpe: SemValue
  ): CheckIO[(SemExpression, SemValue)] =
    for {
      (peeled, implicitMetas) <- peelLams(tpe)
      _                       <- carriers.recordCarrierMetas(expr, implicitMetas)
      updated                 <- appendTypeArgs(expr, implicitMetas)
    } yield (updated.copy(expressionType = peeled), peeled)

  /** Append implicit-meta type args to a [[SemExpression.ValueReference]] expression. Only a value reference can
    * inherit a polytype (since polymorphism lives on named signatures), so no other shape should ever arrive here with
    * non-empty `extraArgs`. Hitting that branch indicates a compiler bug.
    */
  private def appendTypeArgs(expr: SemExpression, extraArgs: Seq[SemValue]): CheckIO[SemExpression] =
    if (extraArgs.isEmpty) pure(expr)
    else
      expr.expression match {
        case ref: SemExpression.ValueReference =>
          val updatedArgs = ref.typeArguments ++ extraArgs
          pure(expr.copy(expression = ref.copy(typeArguments = updatedArgs)))
        case other                             =>
          throw new IllegalStateException(
            s"Polytype instantiation produced implicit type arguments for a non-reference expression: $other"
          )
      }

  /** Prefetch-only traversal: walks an ORE and calls [[ensureBinding]] at every ValueReference, discarding any resulting
    * SemValue. Used for subtrees whose actual evaluation is deferred to a pure [[Evaluator]] invocation inside a
    * [[VLam]] closure — the closure must find every reachable binding already in the cache. `ensureBinding` itself pulls
    * each value's transitive body dependencies (see [[ensureBodyBindings]]), so this need only visit `ore`'s own
    * references.
    */
  private def prefetchBindings(ore: OperatorResolvedExpression): CheckIO[Unit] =
    OperatorResolvedExpression.foldValueReferences[CheckIO, Unit](ore, ()) { (_, vfqn) =>
      ensureBinding(vfqn.value).void
    }

}
