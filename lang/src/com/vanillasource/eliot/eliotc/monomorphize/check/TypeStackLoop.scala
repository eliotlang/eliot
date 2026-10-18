package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.{Evaluator, Quoter}
import com.vanillasource.eliot.eliotc.monomorphize.fact.*
import com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer
import com.vanillasource.eliot.eliotc.monomorphize.unify.{SemValuePrinter, UnifyError}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}

/** Monomorphizes a value at concrete type arguments (signature-unification C1/C2 — one execution path at every arity):
  * re-inflates the value's own signature twin's ground signature ([[establishSignature]]), settles the return position
  * at the read ([[settleAtRead]]), checks the body against it, then drains and reads back to ground. There is no
  * in-place signature walk — the signature twin's mono (an ordinary body mono, [[processSignatureTwin]]) is the single
  * place a signature is computed. (The name is historical; it predates removing the `TypeStack`.)
  *
  * Holds the callbacks and the [[Checker]] as fields so internal helpers don't have to thread them.
  */
class TypeStackLoop(
    fetchBinding: ValueFQN => CompilerIO[Option[SemValue]],
    resolveAbility: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[(ValueFQN, Seq[GroundValue])]],
    track: Track,
    // Reduce a callee at concrete type arguments on the compiler track, returning its self-contained reduced binding
    // (deep — dependencies closed over their own reduced-at-instantiation forms; see `ReducedBindingClosure`). Feeds the
    // stuck-driven escalation fetch of the post-drain read-back.
    reduceInstance: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[SemValue]] = (_, _) =>
      none[SemValue].pure[CompilerIO],
    // The signature-split `Signature`-twin mode ([[processSignatureTwin]]): the value's body IS its sibling's signature
    // expression (a binder-stripped arrow chain), monomorphized as an ordinary body mono — per-binder kind checks, one
    // `check(chain, VType)`, ordinary read-back. Every twin produces (a plain type, a guard verdict, a W3 hole, or — at a
    // partial-arity key — a *parametric* signature with leftover binders as `GroundValue.Param`s). The default `false`
    // is the ordinary runtime/compiler value mono.
    signatureOnly: Boolean = false,
    // The value's *own* signature, reduced to ground by its signature twin (`CompilerMonomorphicValue(v@Signature,
    // args)`), fed in **mandatorily** by the processor (C1/C2). [[establishSignature]] binds the type arguments (leftover
    // binders to fresh metas, re-inflating the twin's `Param`s) and re-inflates it — the one way a value mono obtains its
    // signature. `None` only for a signature twin computing itself (`signatureOnly`, which does not read it).
    injectedSignature: Option[GroundValue] = None,
    // The new effect path (`docs/effects.md` D36 point 6): a callee's handed types at its other type arguments, read off
    // its checked value on this track ([[HandedBinders]]). `None` reads nothing, which only a value with no reference
    // to a callee with handed binders can afford.
    readHanded: Option[(ValueFQN, Seq[GroundValue]) => CompilerIO[Seq[GroundValue]]] = None,
    // The new effect path (`docs/effects.md` D36): no environment is threaded on either track, since a frame is a
    // handle's field and a handle is passed as any value is.
    handles: Boolean = false
) {
  import TypeStackLoop.AbilityRef

  private val checker = new Checker(fetchBinding, resolveAbility, track, signatureOnly)

  def process(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue
  ): CompilerIO[TypeStackLoop.Result] =
    processWithState(typeArguments, resolvedValue).map(_._2)

  /** Test seam: [[process]] but also returning the final [[CheckState]], so checker bookkeeping the
    * [[TypeStackLoop.Result]] does not carry (ambient carrier heads, metavariable roles) can be asserted directly.
    */
  private[monomorphize] def processWithState(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue
  ): CompilerIO[(CheckState, TypeStackLoop.Result)] =
    processIO(typeArguments, resolvedValue).run(CheckState.initial)

  private def processIO(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue
  ): CheckIO[TypeStackLoop.Result] =
    if (signatureOnly) processSignatureTwin(typeArguments, resolvedValue)
    else processValueMono(typeArguments, resolvedValue)

  /** The ordinary runtime/compiler *value* mono: establish the value's signature (the Step-6 flip reading its own
    * signature twin, or the in-place walk), check its body against it, drain, and read the body back. (The signature
    * twin's own mono is [[processSignatureTwin]].)
    */
  private def processValueMono(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue
  ): CheckIO[TypeStackLoop.Result] =
    for {
      // Establish the value's signature at the concrete type arguments (signature-unification C1/C2): re-inflate its
      // signature twin's ground signature — the twin is **mandatory**, there is no in-place walk. Leftover binders the
      // key carries no argument for re-inflate to fresh metavariables (a partial-arity mono's `GroundValue.Param`s),
      // which keep its constraint-covered ability refs deferrable (§4.7).
      instantiated <- establishSignature(resolvedValue, typeArguments)
      bodyToCheck   = resolvedValue.runtime

      // Settle the return position at the read (signature-unification C1) — one stateless, shape-driven step:
      //   - on the **runtime** track, discharge a guard off the re-inflated leaf shape (`dischargeGuardedSignature`):
      //     `Right(t)` ⤳ the plain type `t`, `Left(msg)` aborts with the author message, an `Either`/`Bool`
      //     carrier-headed return (a guard the twin published undischarged) is deferred to the body — all recognised on
      //     the ground shape, no `sawGuard` flag;
      //   - on the **compiler** track (the guard's *producer*) the carrier signature is left undischarged for a consumer.
      settled               <- settleAtRead(instantiated, bodyToCheck.isDefined, resolvedValue)
      (checkSig, returnMeta) = settled

      // Capture ρ now, before `check` binds the runtime value parameters as `FunctionLiteral` binders — so ρ holds only
      // the erased type-stack parameters (each already in its evaluable `groundToSem` form) and the leftover-binder
      // metas. This is exactly the env that seeds the reification gate in `PostDrainQuoter`.
      monoEnv <- inspect(_.rho)

      // Check runtime body if present — produces SemExpression with SemValue slots. A body-less value (an abstract
      // declaration) has nothing to check or emit here.
      runtime    <- bodyToCheck.traverse { body =>
                      checker.check(body, checkSig).map(expr => body.as(expr))
                    }

      // Collect all ability-qualified value references from the checked body. These drive the saturation tier of the
      // post-drain pipeline below. (The signature's own ability refs were resolved by the twin's mono, not re-walked here.)
      abilityRefs = runtime.toSeq.flatMap(checker.abilityResolver.collectAbilityRefs)
      handedRefs <- inspect(state => runtime.toSeq.flatMap(body => HandedBinders.references(body.value, state.handedBinders)))

      // Post-drain resolution + quoter assembly (shared with the signature twin): drain-and-resolve every metavariable,
      // abort on any unification error, then build the `PostDrainQuoter` over the drain-resolved impl bindings.
      quoter    <- drainAndBuildQuoter(resolvedValue, abilityRefs, returnMeta, monoEnv, handedRefs, runtime.isDefined)
      ownHanded <- inspect(_.ownHanded.map(_._2))
      handed    <- liftF(ownHanded.traverse(quoter.quoteSem(_, resolvedValue.name)))
      choices   <- inspect(_.handleChoices)
      runtime   <- pure(runtime.map(_.map(HandleHoleResolver.fill(_, choices))))
      groundSig <- liftF(quoter.quoteSem(checkSig, resolvedValue.signature))
      // The compiler track reduces its body (`reduceSourced`); the runtime track keeps it structural (`quoteSourced`).
      // Narrow-representation reconciliation happens in the backend's `ExpressionCodeGenerator` off the refinement
      // channel, so the body is read back directly.
      effects   <- monomorphicEffects(resolvedValue, typeArguments, quoter, monoEnv)
      threading  = PostDrainQuoter.Threading(
                     resolvedValue.vfqn,
                     typeArguments,
                     resolvedValue.name.as(resolvedValue.vfqn.name),
                     groundSig,
                     effects,
                     guarded = false
                   )
      monoBody  <- runtime.traverse(srcSem => liftF(track.readBackBody(quoter, srcSem, Option.unless(handles)(threading))))
    } yield TypeStackLoop.Result(
      groundSig,
      monoBody.map(sourcedMono => sourcedMono.as(sourcedMono.value.expression)),
      effects,
      handed
    )

  /** This specialisation's effect entries at their ground arguments ([[MonomorphicEffects]]), for the environment
    * threading at the `WovenValue` seam. An entry's arguments are type expressions over this definition's binders, so
    * they are reduced here, by the one evaluator, under the binders' ground values (`monoEnv`).
    *
    * A **received** entry is read off the constraint the desugar puts on every binding binder — `Impl ~ Console[Impl]`
    * for a row entry, `T ~ Ord[Impl, T]` for a `~` constraint — whose first argument names the binder and whose rest
    * are the entry's arguments. A **slot**'s entries are the parameter row's own. An entry whose arguments do not reduce
    * to ground (a partial-arity key's leftover binder) is left out, which can only leave a reference with no frame to
    * find — reported where the threading needs one, never guessed.
    */
  private def monomorphicEffects(
      resolvedValue: OperatorResolvedValue,
      typeArguments: Seq[GroundValue],
      quoter: PostDrainQuoter,
      monoEnv: Env
  ): CheckIO[MonomorphicEffects] = {
    val binders = SignatureView.of(resolvedValue.signature).binders.map(_.name.value)
    def ground(arguments: Seq[OperatorResolvedExpression]): CheckIO[Option[Seq[GroundValue]]] =
      arguments.toList
        .traverse(argument => checker.evalExpr(argument, Some(monoEnv)).map(quoter.quoteSemOption))
        .map(_.sequence)
    val received =
      resolvedValue.paramConstraints.values.flatten.toSeq
        .flatMap { constraint =>
          constraint.typeArgs.headOption
            .collect { case OperatorResolvedExpression.ParameterReference(name) => binders.indexOf(name.value) }
            .filter(index => index >= 0 && index < typeArguments.size)
            .map(_ -> constraint)
        }
        .sortBy(_._1)
    for {
      receivedEntries <- received.toList.traverse { case (index, constraint) =>
                           ground(constraint.typeArgs.drop(1)).map(
                             _.map(arguments =>
                               MonomorphicEffects.Received(index, MonomorphicEffects.Entry(constraint.abilityFQN, arguments))
                             )
                           )
                         }
      slots           <- resolvedValue.effectRow.parameterEffects.toList.traverse { slot =>
                           slot.effects.toList
                             .traverse(entry =>
                               ground(entry.typeArgs).map(_.map(MonomorphicEffects.Entry(entry.abilityFQN, _)))
                             )
                             .map(entries => MonomorphicEffects.Slot(slot.parameterIndex, entries.flatten))
                         }
    } yield MonomorphicEffects(receivedEntries.flatten, slots)
  }

  /** The signature twin's monomorphization as an **ordinary body mono** (signature-unification §3.1): the signature's
    * binder-stripped arrow chain *is* the body. Kind-check each declared binder against `VType` and bind its ground
    * argument in ρ/Γ, then check the arrow chain (`parameters → returnType`) against `VType` through the shared
    * resolution ladder — whose acceptance arms (ordinary type / guard carrier / W3 under-applied hole) cover every
    * return shape — run the ordinary post-drain resolution, and reduce the checked chain to its ground signature via
    * the same escalation read-back every compiler body takes. No `walkTypeStack`, no `settleReturnPosition`, no
    * `sawGuard` gate, no W3 decline: every signature twin produces — a plain type, a `Right(t)`/`Left(msg)` guard
    * verdict, or an under-applied W3 hole (§3.5). The published body is `None` (the signature is the whole product).
    */
  private def processSignatureTwin(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue
  ): CheckIO[TypeStackLoop.Result] = {
    val view               = SignatureView.of(resolvedValue.signature)
    // The arrow chain with the generic binders stripped (they are bound in ρ/Γ by `bindTwinBinders`): `params → return`.
    val bodyExpr           = resolvedValue.signature.as(view.copy(binders = Seq.empty).toExpression)
    for {
      _          <- bindTwinBinders(view.binders, typeArguments)
      // Capture ρ now — holding only the erased type-stack binders (their ground values / carrier metas), *before* the
      // check binds any value / pattern binder of the arrow chain. This is the same clean env the value mono captures
      // before its body check; the read-back reduces the checked chain under it, so a signature-position `match`'s
      // pattern binder (`case Box[a] -> a`) is extracted by the match reduction rather than shadowed by a stale
      // post-check neutral. (Carrier metas the pin below solves live in the metastore, not ρ, so this stays authoritative.)
      monoEnv    <- inspect(_.rho)
      checked    <- checker.check(bodyExpr, VType)
      abilityRefs = checker.abilityResolver.collectAbilityRefs(bodyExpr.as(checked))
      quoter     <- drainAndBuildQuoter(resolvedValue, abilityRefs, None, monoEnv)
      // The signature's ground read-back. Reduce the **raw** evaluated arrow chain first (renormalising natives as it
      // quotes): this settles an ordinary type, a bare `{Throw[String]}` carrier return, a type-level `match` (whose
      // pattern binder the match reduction binds — the check would freeze it to a neutral), a W3 under-applied hole,
      // and a *pure* guard (a marker's `where fold(…)`/`E1 != E2` Bool computation — the raw `fold` has no
      // instantiation type-argument to mis-fire the native on). It is *stuck*, never wrongly reduced, only for an
      // **effectful** inline guard (`if..else..raise`), whose `else`/`runAbort` need the checker's effect-lift `pure`
      // absent from the raw signature — so fall back there to the **checked** arrow chain via the escalation loop, which
      // reduces it to its `Right(t)` / `Left(msg)` verdict. No `sawGuard`: the shape falls out of which reduction settles.
      // The raw evaluation dispatches no ability and threads no frame, so it is not read at all for a signature that
      // runs a control effect: an operation stays an undispatched call there, and a discharger's handler can turn it
      // into a plausible wrong value — `runThrow(…)` read through `foldEither(_ -> false, …)` answered `false` for a
      // guard that had raised. Elsewhere a raw result still holding an ability method's call is not a type either.
      raw        <- checker.evalExpr(bodyExpr.value)
      threaded   <- liftF(quoter.runsControlEffects(checked))
      rawSig      = quoter.quoteSemOption(raw).filterNot(EscalatingReducer.holdsAbilityCall).filterNot(_ => threaded)
      groundSig  <- rawSig match {
                      case Some(ground) => liftF(ground.pure[CompilerIO])
                      case None         =>
                        liftF(
                          quoter.reduceSignatureToGroundOption(checked, guardRead(resolvedValue, typeArguments))
                        ).flatMap {
                          case Some(ground) => liftF(ground.pure[CompilerIO])
                          case None         => publishStuckGuardCarrier(view, checked, quoter, resolvedValue)
                        }
                    }
    } yield TypeStackLoop.Result(groundSig, None)
  }

  /** How the compile track threads a signature's read-back: the **guarded-return read** gives the signature's own
    * control effects their frames (`docs/effects.md` §3.4), so a `raise("…")` it performs is read as the rejection
    * `Left("…")`, carrying its author's message, and an `else` in it catches only the `abort` written inside it.
    */
  private def guardRead(
      resolvedValue: OperatorResolvedValue,
      typeArguments: Seq[GroundValue]
  ): Option[PostDrainQuoter.Threading] =
    Option.when(track.platform == Platform.Compiler)(
      PostDrainQuoter.Threading(
        resolvedValue.vfqn,
        typeArguments,
        resolvedValue.name.as(resolvedValue.vfqn.name),
        GroundValue.Type,
        MonomorphicEffects.empty,
        guarded = true
      )
    )

  /** A guard whose verdict depends on a *leftover generic binder* (a [[GroundValue.Param]]) is stuck as a value — its
    * `fold`/`else` cannot reduce with the binder abstract, so neither the raw quote nor the escalation grounds it
    * (signature-unification C2). Publish its undischarged **carrier type** (`Either[String, Type]`) instead, which the
    * value mono re-inflates and defers to the body (Use-Site Verification): the guard is re-decided at every concrete
    * instance above. The guard-ness is recognised by shape — the checked return's *type* is an `Either`/`Bool` carrier
    * ([[GuardDischargeResolver.isGuardCarrier]]) — no `sawGuard` flag. This arm is only a no-parameter guard's
    * abstract-site check (the sole Param-stuck-guard source, §7 census); anything else stuck hard-errors (fail-safe).
    */
  private def publishStuckGuardCarrier(
      view: SignatureView,
      checked: SemExpression,
      quoter: PostDrainQuoter,
      resolvedValue: OperatorResolvedValue
  ): CheckIO[GroundValue] =
    checker.guards.isGuardCarrier(checked.expressionType).flatMap {
      case true if view.parameters.isEmpty =>
        liftF(quoter.quoteSem(checked.expressionType, resolvedValue.name))
      case _                               =>
        liftF(compilerAbort[GroundValue](resolvedValue.name.as("Cannot resolve type.")))
    }

  /** Kind-check each signature binder's declared kind against `VType` and bind its ground type argument in ρ/Γ (the
    * signature-unification body model, §3.1 step 1). Left-to-right, so a binder whose kind references an earlier binder
    * sees it already bound. A leftover binder with no argument (a phantom the key does not carry) instantiates to a
    * fresh meta; the mono key carries one argument per binder in practice (§4.7).
    */
  private def bindTwinBinders(
      binders: Seq[SignatureView.Binder],
      typeArguments: Seq[GroundValue]
  ): CheckIO[Unit] =
    binders.zipWithIndex.traverse_ { case (binder, i) =>
      for {
        // Kind-check the binder's declared kind against `VType`, then evaluate it to the binder's *type* for Γ. The
        // binder's declared kind is authoritative — a higher-kinded carrier binder `[F[_]]` yields `VPi(Type, _ =>
        // Type)`, whereas the ground argument's own `valueType` is merely `Type` (`IO : Type`), which cannot type the
        // application `F[Unit]`. A value binder `[N: BigInteger]` yields `BigInteger`; an unannotated `[X]` yields `Type`.
        _       <- binder.parameterType.traverse_(kind => checker.check(kind, VType).void)
        kindSem <- binder.parameterType.traverse(kind => checker.evalExpr(kind.value)).map(_.getOrElse(VType))
        // ρ's value at this binder (signature-unification C2):
        //   - an explicit ground argument ⟹ its applicable head form;
        //   - a leftover binder (a generic type parameter) ⟹ a `SignatureBinder` neutral, so the arrow chain reads back
        //     **under its binder** as a `GroundValue.Param` (a parametric signature) instead of defaulting to `Type`.
        //
        // The third arm this had — a leftover *carrier* binder became a fresh meta, left open for `pinCarriers` to fix
        // to the compile-time `Either[String]` — went with the pinning (effects v6, F3). With no pin behind it the
        // meta would simply default to `Type`, where a `SignatureBinder` reads back honestly as parametric.
        value   <- typeArguments.lift(i) match {
                     case Some(arg) => pure(Evaluator.groundToSem(arg))
                     case None      =>
                       pure(VNeutral(NeutralHead.SignatureBinder(i, binder.name.value), Spine.SNil): SemValue)
                   }
        _       <- modify(_.bindTypeStackParam(binder.name.value, kindSem, value))
      } yield ()
    }

  /** Fail-safe for a *deferred guard* return the body did not pin down. A `{Throw[String]}` return guard the signature
    * twin published undischarged is deferred to a fresh return metavariable ([[settleAtRead]]); after the
    * drain-and-resolve loop that meta should have been solved to the body's inferred type. If it is still a bare
    * metavariable the body left it unconstrained, and if it forced to a stuck neutral the result depends on a variable
    * the inputs do not determine. Either way report a specific error — naming the producer and, for a neutral, the
    * stuck head — rather than letting [[defaultUnsolvedMetas]] silently make the return `Type`. (A return solved to
    * some *other* non-quotable form — an unforced top-level definition, a residual native/lambda — is caught instead by
    * the strict post-drain quoter, which knows precisely which of those are irreducible, so those are left to it.)
    */
  private def failOnUndeterminedDeferredReturn(
      returnMeta: SemValue.VMeta,
      resolvedValue: OperatorResolvedValue
  ): CheckIO[Unit] =
    checker.force(returnMeta).flatMap {
      case _: SemValue.VMeta          =>
        reportUndeterminedReturn(resolvedValue, "the body leaves it unconstrained")
      case SemValue.VNeutral(head, _) =>
        reportUndeterminedReturn(
          resolvedValue,
          s"the result depends on '${head.name}', which the inputs do not determine"
        )
      case _                          => pure(())
    }

  private def reportUndeterminedReturn(resolvedValue: OperatorResolvedValue, reason: String): CheckIO[Unit] =
    liftF(
      compilerError(
        resolvedValue.name.as(s"Cannot determine the return type of '${resolvedValue.vfqn.name.name}': $reason."),
        Seq("Write an explicit return type.")
      ) >> abort[Unit]
    )

  /** Finalize every still-unsolved meta to [[VType]]. Runs after the drain-and-resolve loop has reached its fixed
    * point, so any meta unification could have determined is already solved; what remains is an unconstrained (phantom)
    * instantiation, whose canonical value is `Type`.
    */
  private def defaultUnsolvedMetas: CheckIO[Unit] =
    modify { s =>
      val store  = s.unifier.metaStore
      val solved = store.entries.foldLeft(store) {
        case (acc, (rawId, None)) => acc.solve(SemValue.MetaId(rawId), VType)
        case (acc, _)             => acc
      }
      s.withUnifier(s.unifier.copy(metaStore = solved))
    }

  /** The shared post-drain tail of both monos (the value mono and the signature twin): run the drain-and-resolve
    * pipeline ([[runPostDrainResolution]]), report and abort on any unification error, then assemble the
    * [[PostDrainQuoter]] over the drain-resolved impl bindings. `track.implBindings` does not mutate the check state,
    * so the one post-drain `state` seeds both the error check and the quoter.
    *
    * `abilityMethodBindings` maps each drain-resolved ability *method* FQN to its impl body — the SemValue analogue of
    * [[PostDrainQuoter.resolveAbilityRefs]] — so an ability call surviving in a type-position SemValue reduces during
    * read-back (a guard naming an ability method directly, `where equals(E1, E2)`); one instance per method, no
    * conflation.
    */
  private def drainAndBuildQuoter(
      resolvedValue: OperatorResolvedValue,
      abilityRefs: Seq[AbilityRef],
      returnMeta: Option[SemValue.VMeta],
      monoEnv: Env,
      handedRefs: Seq[HandedBinders.Reference] = Seq.empty,
      hasBody: Boolean = true
  ): CheckIO[PostDrainQuoter] =
    for {
      _                    <- runPostDrainResolution(resolvedValue, abilityRefs, returnMeta, handedRefs, hasBody)
      state                <- get
      _                    <- state.unifier.errors.reverse.traverse_(err => liftF(reportUnifyError(err, state)))
      _                    <- if (state.unifier.errors.nonEmpty) liftF(abort[Unit]) else pure(())
      implBindings         <- track.implBindings(fetchBinding)
      abilityMethodBindings = state.abilityResolutions.toSeq.flatMap { case ((ref, _), (implFqn, _)) =>
                                implBindings.get(implFqn).map(ref.value -> _)
                              }.toMap
    } yield new PostDrainQuoter(
      state.unifier.metaStore,
      state.abilityResolutions,
      state.abilityArities,
      state.bindingForwards,
      monoEnv,
      fqn =>
        implBindings.get(fqn).orElse(abilityMethodBindings.get(fqn)).orElse(state.bindingCache.getOrElse(fqn, None)),
      track.platform,
      reduceInstance
    )

  /** The post-check resolution sequence (D1) that settles every metavariable, in order:
    *
    *   - SATURATION ([[resolveAbilitiesToFixedPoint]]): drain-interleaved ability resolution, to a fixed point.
    *   - FINALIZATION: carrier-kind verification, then the deferred-return fail-safe.
    *   - a final drain: a finalization step can commit new solutions (carrier-kind verification unifies a solution's
    *     kind against its expectation), so the equality core settles once more before defaulting — a constraint
    *     postponed against a meta those solutions ground still resolves instead of its metas defaulting to `Type`.
    *   - the FINALIZER ([[defaultUnsolvedMetas]]), the postponement flush, and the postcondition assertion.
    */
  private def runPostDrainResolution(
      resolvedValue: OperatorResolvedValue,
      abilityRefs: Seq[AbilityRef],
      returnMeta: Option[SemValue.VMeta],
      handedRefs: Seq[HandedBinders.Reference],
      hasBody: Boolean
  ): CheckIO[Unit] =
    for {
      _ <- resolveAbilitiesToFixedPoint(abilityRefs, handedRefs)
      _ <- checker.carriers.verifyCarrierKinds
      _ <- returnMeta.traverse_(failOnUndeterminedDeferredReturn(_, resolvedValue))
      _ <- modify(s => s.withUnifier(s.unifier.drain()))
      // A handed type is never defaulted: its runs decide it, or nothing does ([[HandedBinders.own]]).
      _ <- HandedBinders.own(hasBody, resolvedValue.name)
      _ <- defaultUnsolvedMetas
      // Fail-safe (TODO.md): any constraint still postponed after the finalizer is an equality obligation the check
      // never discharged. Flush the queue to hard mismatch errors (triaging benign, now-defaulted constraints away
      // first) rather than silently carrying and forgetting them — the hole that let pre-fix applied-associated-type
      // garbage compile. Runs before the meta-postcondition assertion, which the defaulting already satisfies.
      _ <- modify(s => s.withUnifier(s.unifier.flushPostponed()))
      _ <- assertEveryMetaResolved(resolvedValue)
    } yield ()

  /** Saturation: drain the unifier — so the resolver sees every solution the previous round injected — then resolve
    * abilities and handle holes ([[HandleHoleResolver]]: the certain ones each round, one by agreement when nothing else
    * progresses); loop while that progresses, and report the holes left open when it stops. Bounded: each ability and
    * each hole resolves at most once, so progress is monotone.
    *
    * This used to interleave the A.8.7 **mode resolution** and, on a decided hoist, splice a body rewrite and restart
    * the whole mono. Since §1 rule 4 the row elaborator classifies every position from its declaration and writes both
    * the placement and the carrier, so the runtime track produces no deferral to resolve: the obligations, the resolver
    * and the splice-restart are gone (docs/effects-as-rows.md A.11.8 step 1) and this is a plain fixpoint again.
    */
  private def resolveAbilitiesToFixedPoint(
      abilityRefs: Seq[AbilityRef],
      handedRefs: Seq[HandedBinders.Reference]
  ): CheckIO[Unit] = {
    def loop: CheckIO[Unit] =
      for {
        _         <- modify(s => s.withUnifier(s.unifier.drain()))
        resolved  <- checker.abilityResolver.resolveAbilities(abilityRefs)
        read      <- readHanded.fold(pure(false))(HandedBinders.read(handedRefs, _))
        abilities  = resolved || read
        holes     <- checker.handleHoles.resolveCertain
        agreed    <- if (abilities || holes) pure(false) else checker.handleHoles.resolveByAgreement
        _         <- if (abilities || holes || agreed) loop
                     else checker.handleHoles.reportUndecided >> checker.handleHoles.reportUntaken
      } yield ()

    loop
  }

  /** Postcondition of the post-drain pipeline (D1): every metavariable is solved. The finalizer
    * ([[defaultUnsolvedMetas]]) makes this hold by construction, so it never fires in normal operation; it is the
    * *compiler-bug* backstop — were a future resolution path to bypass the finalizer and leave a meta unsolved, this
    * assertion catches it. It [[compilerAbort]]s rather than reporting a user diagnostic because a surviving unsolved
    * meta is an internal invariant violation, not a type error of the user's making.
    */
  private def assertEveryMetaResolved(resolvedValue: OperatorResolvedValue): CheckIO[Unit] =
    inspect { s =>
      s.unifier.metaStore.entries.collect { case (rawId, None) => rawId }.toList
    }.flatMap {
      case Nil      => pure(())
      case unsolved =>
        liftF(
          compilerAbort[Unit](
            resolvedValue.name.as(
              s"Internal: post-drain resolution left metavariables unresolved: ${unsolved.mkString(", ")}."
            )
          )
        )
    }

  /** Establish the value's signature at `typeArguments` by re-inflating its signature twin's ground signature
    * (signature-unification C1/C2 — the twin is **mandatory**, there is no in-place walk). Bind each binder in ρ/Γ: a
    * binder the key carries an argument for to that ground value, a *leftover* binder (a partial-arity key, §4.7) to a
    * fresh metavariable — the same metavariable the twin's leftover `GroundValue.Param` re-inflates to
    * ([[Evaluator.groundToSemPiParam]]), so the parametric signature and ρ agree on it. The leftover metas keep a
    * partial-arity mono's constraint-covered ability refs deferrable; a full-arity key has no leftover and re-inflates
    * to a fully-ground `VPi`.
    */
  private def establishSignature(
      resolvedValue: OperatorResolvedValue,
      typeArguments: Seq[GroundValue]
  ): CheckIO[SemValue] = {
    val binders = SignatureView.of(resolvedValue.signature).binders
    injectedSignature match {
      case None                                           =>
        // The twin read is mandatory (the processor uses `getFactOrAbort`); a missing signature is an internal invariant
        // violation, not a user error.
        liftF(compilerAbort[SemValue](resolvedValue.name.as("Internal: value mono ran without its signature twin.")))
      case Some(_) if typeArguments.sizeIs > binders.size =>
        // More type arguments than the value has binders — a user error (formerly caught by `applyTypeArgs` stopping on
        // the first non-`VLam` head). Report once and abort rather than silently ignoring the excess.
        liftF(compilerError(resolvedValue.name.as("Too many type arguments.")) >> abort[SemValue])
      case Some(groundSig)                                =>
        for {
          // One fresh metavariable per *leftover* binder index, memoised so the ρ binding and the `GroundValue.Param`
          // re-inflation below share the same meta.
          paramMetas <- (typeArguments.size until binders.size).toList
                          .traverse(i => checker.freshMeta.map(m => i -> (m: SemValue)))
                          .map(_.toMap)
          _          <- binders.zipWithIndex.traverse_ { case (binder, i) =>
                          typeArguments.lift(i) match {
                            case Some(arg) =>
                              modify(
                                _.bindTypeStackParam(
                                  binder.name.value,
                                  Evaluator.groundToSem(arg.valueType),
                                  Evaluator.groundToSem(arg)
                                )
                              )
                            case None      =>
                              for {
                                kindSem <- binder.parameterType
                                             .traverse(k => checker.evalExpr(k.value))
                                             .map(_.getOrElse(VType))
                                _       <- modify(_.bindTypeStackParam(binder.name.value, kindSem, paramMetas(i)))
                              } yield ()
                          }
                        }
          handed      = HandedBinders.of(resolvedValue.signature).filter(paramMetas.contains)
          _          <- modify(_.copy(ownHanded = handed.map(i => binders(i).name.value -> paramMetas(i))))
        } yield Evaluator.groundToSemPiParam(groundSig, i => paramMetas.getOrElse(i, VType))
    }
  }

  /** Settle the value's return position at the read (signature-unification C1), stateless and shape-driven: the
    * **runtime** track discharges/defers a guard off the re-inflated ground leaf
    * ([[GuardDischargeResolver.dischargeGuardedSignature]], recognising the guard by its `Right`/`Left`/carrier shape,
    * not a flag), while the **compiler** track (the guard's *producer*) leaves the carrier undischarged.
    */
  private def settleAtRead(
      instantiated: SemValue,
      hasBody: Boolean,
      resolvedValue: OperatorResolvedValue
  ): CheckIO[(SemValue, Option[SemValue.VMeta])] =
    track.platform match {
      case Platform.Runtime  =>
        checker.guards.dischargeGuardedSignature(instantiated, hasBody, resolvedValue.name)
      case Platform.Compiler =>
        pure((instantiated, Option.empty[SemValue.VMeta]))
    }

  /** Emit a [[UnifyError]] as a compiler error, including `Expected` / `Actual` hints when the error carries both
    * sides. The semantic values are re-forced through the final metastore so any metas that were solved after the error
    * was raised display their resolution.
    */
  private def reportUnifyError(err: UnifyError, state: CheckState): CompilerIO[Unit] =
    compilerError(err.context, describe(err, state))

  private def describe(err: UnifyError, state: CheckState): Seq[String] =
    (err.expected, err.actual) match {
      case (Some(expected), Some(actual)) =>
        Seq(
          s"Expected: ${SemValuePrinter.show(expected, state.unifier.metaStore)}",
          s"Actual:   ${SemValuePrinter.show(actual, state.unifier.metaStore)}"
        )
      case _                              => Seq.empty
    }
}

object TypeStackLoop {

  /** The platform-agnostic output of checking one value's type stack and body: the quoted signature and the optional
    * quoted body. Each track wraps this into its own fact ([[MonomorphicValue]] /
    * [[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue]]), so [[TypeStackLoop]] never names
    * either fact type — which is what keeps the two tracks acyclic by construction.
    */
  case class Result(
      signature: GroundValue,
      body: Option[Sourced[MonomorphicExpression.Expression]],
      effects: MonomorphicEffects = MonomorphicEffects.empty,
      handed: Seq[GroundValue] = Seq.empty
  )

  /** Static convenience wrapper — constructs a [[TypeStackLoop]] and runs its [[process]]. */
  def process(
      typeArguments: Seq[GroundValue],
      resolvedValue: OperatorResolvedValue,
      fetchBinding: ValueFQN => CompilerIO[Option[SemValue]],
      resolveAbility: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[(ValueFQN, Seq[GroundValue])]],
      track: Track,
      reduceInstance: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[SemValue]] = (_, _) =>
        none[SemValue].pure[CompilerIO],
      signatureOnly: Boolean = false,
      injectedSignature: Option[GroundValue] = None,
      readHanded: Option[(ValueFQN, Seq[GroundValue]) => CompilerIO[Seq[GroundValue]]] = None,
      handles: Boolean = false
  ): CompilerIO[Result] =
    new TypeStackLoop(
      fetchBinding,
      resolveAbility,
      track,
      reduceInstance,
      signatureOnly,
      injectedSignature,
      readHanded,
      handles
    )
      .process(typeArguments, resolvedValue)

  private type AbilityRef = AbilityResolver.AbilityRef
}
