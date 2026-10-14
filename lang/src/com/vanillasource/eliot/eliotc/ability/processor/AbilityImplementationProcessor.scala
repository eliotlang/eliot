package com.vanillasource.eliot.eliotc.ability.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation.Resolution
import com.vanillasource.eliot.eliotc.ability.fact.{AbilityImplementation, AbilityImplementationCheck}
import com.vanillasource.eliot.eliotc.ability.util.AbilityMatcher
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ModuleName, QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.GuardChannel
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, GroundValue}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Resolves the ability implementation for one `(ability, type arguments, platform)` query: collects the candidate
  * implementations, matches each pattern, discharges each `where` guard, and registers the aggregate
  * [[Resolution]] outcome on the [[AbilityImplementation]] fact. This processor **reports no resolution errors** —
  * whether a failed resolution is a compile error, and at what source position, is the demander's decision (the
  * checker's saturation pass reports a failed *demand* at its use-site reference; the checker-inserted probes like
  * `Coerce` treat non-applicability as a normal decline). It still aborts on internal errors, which leave the fact
  * absent.
  */
class AbilityImplementationProcessor extends SingleKeyTypeProcessor[AbilityImplementation.Key] with Logging {

  /** How one candidate implementation fared at the queried type arguments: kept (pattern matched, guard admitted),
    * declined (no structural match, or the guard reduced to `false`), or rejected (the guard `raise(msg)`-ed —
    * ability-guards §3.1).
    */
  private enum Verdict {
    case Keep(implFQN: ValueFQN, implTypeArgs: Seq[GroundValue])
    case Decline
    case Reject(message: String)
  }

  private val decline: CompilerIO[Verdict] = (Verdict.Decline: Verdict).pure[CompilerIO]

  override protected def generateFact(key: AbilityImplementation.Key): CompilerIO[Unit] = {
    val abilityValueFQN                   = key.abilityValueFQN
    val candidateModules: Set[ModuleName] =
      Set(abilityValueFQN.moduleName) ++ key.typeArguments.flatMap(collectModuleNames)

    for {
      abilityFQN <- abilityValueFQN.name.qualifier match {
                      case Qualifier.Ability(name) => AbilityFQN(abilityValueFQN.moduleName, name).pure[CompilerIO]
                      case other                   => error[CompilerIO](s"expected Ability qualifier, got: $other") >> abort
                    }
      _          <- getFactOrAbort(AbilityImplementationCheck.Key(abilityFQN, key.typeArguments, key.platform))
      candidates <- candidateModules.toSeq.flatTraverse(module =>
                      findImplementationsInModule(module, abilityValueFQN.name.name, abilityFQN.abilityName, key.platform)
                    )
      verdicts   <- candidates.traverse(verifyImplementation(_, abilityFQN, key.typeArguments, key.platform))
      resolution <- interpretVerdicts(verdicts, abilityValueFQN, abilityFQN, key)
      _          <- registerFactIfClear(AbilityImplementation(abilityValueFQN, key.typeArguments, resolution, key.platform))
    } yield ()
  }

  /** Aggregate the per-candidate verdicts into the query's [[Resolution]] (§3: exactly-one-survivor). A hard rejection
    * dominates — the instance author explicitly ruled this instantiation out, with a message. Otherwise the kept
    * candidates decide: exactly one resolves, none may still fall back to the ability method's own default
    * implementation, several is the use-site ambiguity.
    */
  private def interpretVerdicts(
      verdicts: Seq[Verdict],
      abilityValueFQN: ValueFQN,
      abilityFQN: AbilityFQN,
      key: AbilityImplementation.Key
  ): CompilerIO[Resolution] = {
    val rejections = verdicts.collect { case Verdict.Reject(message) => message }
    val kept       = verdicts.collect { case Verdict.Keep(implFQN, implTypeArgs) => (implFQN, implTypeArgs) }
      .distinctBy(_._1.show)
    if (rejections.nonEmpty) (Resolution.Rejected(rejections): Resolution).pure[CompilerIO]
    else
      kept match {
        case Seq((implFQN, implTypeArgs)) => (Resolution.Resolved(implFQN, implTypeArgs): Resolution).pure[CompilerIO]
        case Seq()                        => missingResolution(abilityValueFQN, abilityFQN, key)
        case _                            => (Resolution.Ambiguous: Resolution).pure[CompilerIO]
      }
  }

  /** No named implementation of the queried method applies. If the ability method carries a default body, the type
    * still implements the ability whenever *some* implementation block applies at these type arguments — the default
    * then resolves to itself. Otherwise the outcome is [[Resolution.NoImplementation]], for the demander to interpret.
    * (This also retires the former internal-error corner where a default's every sibling declined its guard: that is
    * now an ordinary `NoImplementation`.)
    */
  private def missingResolution(
      abilityValueFQN: ValueFQN,
      abilityFQN: AbilityFQN,
      key: AbilityImplementation.Key
  ): CompilerIO[Resolution] =
    for {
      resolved   <- getFactOrAbort(OperatorResolvedValue.Key(abilityValueFQN, key.platform))
      resolution <- resolved.runtime match {
                      case Some(_) => defaultResolution(abilityFQN, key)
                      case None    => (Resolution.NoImplementation: Resolution).pure[CompilerIO]
                    }
    } yield resolution

  /** The default-implementation arm of [[missingResolution]]: the ability method has a body of its own, so it resolves
    * to itself if any implementation block of this ability applies at the queried type arguments (evidence the type
    * implements the ability at all — the block need not name this method).
    */
  private def defaultResolution(
      abilityFQN: AbilityFQN,
      key: AbilityImplementation.Key
  ): CompilerIO[Resolution] = {
    val candidateModules = Set(key.abilityValueFQN.moduleName) ++ key.typeArguments.flatMap(collectModuleNames)
    for {
      allImpls <- candidateModules.toSeq.flatTraverse(
                    findAnyImplementationInModule(_, abilityFQN.abilityName, key.platform)
                  )
      verdicts <- allImpls.traverse(verifyImplementation(_, abilityFQN, key.typeArguments, key.platform))
    } yield {
      val rejections = verdicts.collect { case Verdict.Reject(message) => message }
      if (rejections.nonEmpty) Resolution.Rejected(rejections)
      else if (verdicts.exists { case _: Verdict.Keep => true; case _ => false })
        Resolution.Resolved(key.abilityValueFQN, key.typeArguments)
      else Resolution.NoImplementation
    }
  }

  private def findAnyImplementationInModule(
      moduleName: ModuleName,
      abilityLocalName: String,
      platform: Platform
  ): CompilerIO[Seq[ValueFQN]] =
    getFactOrAbort(ModuleAbilities.Key(moduleName, platform)).map(_.anonymousImplementationMethodsOf(abilityLocalName))

  private def findImplementationsInModule(
      moduleName: ModuleName,
      functionName: String,
      abilityLocalName: String,
      platform: Platform
  ): CompilerIO[Seq[ValueFQN]] =
    getFactOrAbort(ModuleAbilities.Key(moduleName, platform))
      .map(_.anonymousImplementationMethodsOf(abilityLocalName, functionName))

  private def verifyImplementation(
      vfqn: ValueFQN,
      expectedAbilityFQN: AbilityFQN,
      expectedTypeArgs: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Verdict] =
    getFactIfProduced(OperatorResolvedValue.Key(vfqn, platform)).flatMap {
      case None           => decline
      case Some(resolved) =>
        resolved.name.value.qualifier match {
          case ResolveQualifier.AbilityImplementation(resolvedAbilityFQN, _, _)
              if resolvedAbilityFQN == expectedAbilityFQN =>
            for {
              markerVfqn <- markerVfqnFor(vfqn, expectedAbilityFQN.abilityName).pure[CompilerIO]
              marker     <- getFactOrAbort(OperatorResolvedValue.Key(markerVfqn, platform))
              markerSig   = marker.signature
              matched    <- AbilityMatcher.matchImpl(markerSig, expectedTypeArgs)
              verdict    <- matched match {
                              case None    => decline
                              case Some(m) =>
                                constraintsSatisfied(marker, m, platform).ifM(
                                  dischargeGuard(vfqn, markerVfqn, markerSig, withPhantomDefaults(marker, m)),
                                  decline
                                )
                            }
            } yield verdict
          case _ => decline
        }
    }

  /** Does every type-parameter constraint of a structurally-matched candidate have an implementation at the bindings
    * the match produced? A candidate whose `~` constraints cannot be met at these arguments cannot apply, so it
    * **declines** rather than being kept and colliding with the candidate that does apply
    * (docs/testing-effects.md L1). This makes `~` mean at *selection* time what it already meant at checking time:
    * `implement[F[_] ~ Suspend] Console[F]` matches every carrier structurally, but only carries `Console` for a
    * carrier that can actually suspend — so a pure test carrier's own `Console` instance becomes the unique survivor
    * instead of an ambiguity, and in production nothing changes because a real carrier does have `Suspend`.
    *
    * Every step is **fail-safe towards keeping**: the constraint is only allowed to decline a candidate when its
    * ability arguments are known exactly. An untraced match (a `metaToGround`-collapsed binding, the same operand
    * unreliability [[dischargeGuard]] refuses a guard over), a binding that is the defaulted universe, an argument
    * shape this cannot ground, an unreadable ability arity, an absent probe (its resolution aborted on an error
    * reported at its own definition) and a cyclic probe all answer "satisfied". So this never *creates* a resolution
    * failure that the pattern match alone would not have had — it only ever removes a candidate that could not have
    * worked.
    */
  private def constraintsSatisfied(
      marker: OperatorResolvedValue,
      matched: AbilityMatcher.Match,
      platform: Platform
  ): CompilerIO[Boolean] =
    if (marker.paramConstraints.isEmpty || !matched.allTraced) true.pure[CompilerIO]
    else {
      // The marker's leading generic binders are, in declaration order, exactly the type parameters `matchImpl` bound
      // — it peels the same lambdas out of the same signature — so zipping recovers the binder name of each binding.
      val bindings = OperatorResolvedExpression.SignatureView
        .of(marker.signature)
        .binders
        .map(_.name.value)
        .zip(matched.groundArgs)
        .toMap
      marker.paramConstraints.toList
        .flatMap { case (binderName, constraints) => constraints.map(binderName -> _) }
        .forallM { case (binderName, constraint) => constraintSatisfied(binderName, constraint, bindings, platform) }
    }

  /** Fill each of a matched candidate's **phantom binders** with the `Default` sentinel (effects v6, §9.4).
    *
    * A derived instance (`implement[A ~ Display[A]] Display[Box[A]]`) carries a binding binder of its own, and the
    * pattern match cannot recover it: it occurs in no pattern argument, so there is nothing to trace it from and the
    * lossy fallback leaves junk. What it *means* is "search at these arguments" — the impl's own constraint is
    * discharged where the impl is instantiated — which is exactly `Default`. Without this the recorded resolution
    * carries a junk first argument and the nested dispatch fails as "no implementation found", naming the ability the
    * user did write.
    */
  private def withPhantomDefaults(marker: OperatorResolvedValue, matched: AbilityMatcher.Match): AbilityMatcher.Match = {
    val view        = OperatorResolvedExpression.SignatureView.of(marker.signature)
    val patternRefs = AbilityMatcher.patternParameters(marker.signature)
    val filled      = view.binders.map(_.name.value).zip(matched.groundArgs).map { case (binder, ground) =>
      if (patternRefs.contains(binder)) ground else defaultBindingGround
    }
    matched.copy(groundArgs = filled)
  }

  /** The `Default` sentinel as a ground value — the same shape
    * [[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding.defaultGround]] reads back, built here
    * because that reader lives downstream of this phase.
    */
  private val defaultBindingGround: GroundValue =
    GroundValue.Structure(WellKnownTypes.defaultImplementationFQN, Seq.empty, GroundValue.Type)

  /** One `~` constraint of a matched candidate: ground its ability arguments at the match's bindings, then ask whether
    * that ability is implemented there. Undecidable at any step ⤳ `true` (see [[constraintsSatisfied]]).
    */
  private def constraintSatisfied(
      binderName: String,
      constraint: AbilityConstraint[OperatorResolvedExpression],
      bindings: Map[String, GroundValue],
      platform: Platform
  ): CompilerIO[Boolean] =
    for {
      arity  <- abilityArity(constraint.abilityFQN, platform)
      args    = constraintArguments(binderName, constraint, arity, bindings)
      result <- args match {
                  case Some(groundArgs) if !groundArgs.exists(mentionsDefaultedUniverse) =>
                    hasImplementation(constraint.abilityFQN, groundArgs, platform)
                  case _                                                                 => true.pure[CompilerIO]
                }
    } yield result

  /** The constraint's **pattern** arguments as ground values — what the two-site search is asked at — or [[None]] when
    * they cannot be determined exactly.
    *
    * Effects v6 gives every `~` constraint a leading **binding** argument (`A ~ Display[A]` is `Display[Impl, A]`,
    * `docs/effects.md` §9.4), which is the implementation to use rather than part of the pattern to search by. It is a
    * phantom binder, so it has no ground value at all — grounding it would produce junk and decline every derived
    * instance. Dropping it leaves exactly the ability's pattern parameters, one fewer than its declared arity; any
    * other count is a shape this cannot read, and declines to judge.
    */
  private def constraintArguments(
      binderName: String,
      constraint: AbilityConstraint[OperatorResolvedExpression],
      arity: Option[Int],
      bindings: Map[String, GroundValue]
  ): Option[Seq[GroundValue]] =
    for {
      abilityArity <- arity
      declared     <- constraint.typeArgs.drop(1).toList.traverse(groundArgument(_, bindings))
      full         <- Option.when(declared.size + 1 == abilityArity)(declared)
    } yield full

  /** Ground one constraint type-argument expression at the match's bindings: a reference to a bound type parameter is
    * that parameter's binding, a value reference is its type constructor applied to its own grounded arguments.
    * Anything else (a literal, a lambda, an application the resolver left unfolded) is [[None]] — not judged.
    */
  private def groundArgument(
      expr: OperatorResolvedExpression,
      bindings: Map[String, GroundValue]
  ): Option[GroundValue] =
    expr match {
      case OperatorResolvedExpression.ParameterReference(name)      => bindings.get(name.value)
      case OperatorResolvedExpression.ValueReference(name, typeArgs) =>
        typeArgs.toList
          .traverse(typeArg => groundArgument(typeArg.value, bindings))
          .map(args => GroundValue.Structure(name.value, args, GroundValue.Type))
      case _                                                        => None
    }

  /** The number of ability-level type parameters of `abilityFQN`, read off its marker's leading generic binders (the
    * same read [[com.vanillasource.eliot.eliotc.monomorphize.check.AbilityResolver]] makes to slice a method
    * reference's arguments). [[None]] when the marker has no signature in this pool.
    */
  private def abilityArity(abilityFQN: AbilityFQN, platform: Platform): CompilerIO[Option[Int]] =
    getFactIfProduced(OperatorResolvedValue.Key(abilityMarkerFqnFor(abilityFQN), platform))
      .map(_.map(orv => OperatorResolvedExpression.SignatureView.of(orv.signature).binders.length))

  /** Is `abilityFQN` implemented at `groundArgs`? Asked by resolving the ability's own *marker* method, which is the
    * query "does some implementation of this ability apply here" — the whole candidate/guard machinery of this
    * processor, re-entered through the fact engine so the answer is computed once and cached.
    *
    * Only [[Resolution.NoImplementation]] refutes a constraint. `Ambiguous` means implementations do exist (the
    * ambiguity is a separate failure, reported at the site that demands the ability itself), and `Rejected` keeps the
    * instance author's message answerable at that same site rather than degrading it to a silent decline here.
    *
    * A **cyclic** probe — a constraint that leads back to a resolution already in progress up this request chain, as a
    * mutually-constrained pair of instances would — is answered "satisfied" instead of demanded: the read would be
    * refused by the engine as a dead-lock (and recorded as a global cyclic-demand error), and a candidate must not be
    * declined for a question we chose not to ask.
    */
  private def hasImplementation(
      abilityFQN: AbilityFQN,
      groundArgs: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Boolean] = {
    val probe = AbilityImplementation.Key(abilityMarkerFqnFor(abilityFQN), groundArgs, platform)
    activeFactKeys.flatMap { ancestors =>
      if (ancestors.contains(probe)) true.pure[CompilerIO]
      else getFactIfProduced(probe).map(!_.map(_.resolution).contains(Resolution.NoImplementation))
    }
  }

  /** The synthetic marker value an `ability` block mints beside its methods: same local name as the ability, in the
    * ability's own namespace. Resolving *it* is the "is this ability implemented here" query.
    */
  private def abilityMarkerFqnFor(abilityFQN: AbilityFQN): ValueFQN =
    ValueFQN(abilityFQN.moduleName, QualifiedName(abilityFQN.abilityName, Qualifier.Ability(abilityFQN.abilityName)))

  /** Judge a matched candidate by its `where` guard (ability-guards Stage 2). The guard rides the marker's return-type
    * slot (§2.3): an unguarded marker's slot is the literal `eliot.lang.Bool::true` reference, kept verbatim without
    * evaluation (and without paying to monomorphize the marker). Any other slot is a real guard, discharged at the
    * concrete match binding by monomorphizing the marker and reading its reduced return. Three verdicts (§3.1): `true`
    * keeps, `false` declines (resolution falls through to another instance), `raise(msg)` rejects with the author's
    * message — carried on the [[Resolution.Rejected]] outcome for the demanding use site to report.
    *
    * The guard verdict is read from the marker's **signature twin** on the compiler track (signature-unification Phase D
    * — see [[readGuardVerdict]]), regardless of the queried `platform`: the guard is a compile-time `Bool` computation
    * and the compiler pool borrows the whole runtime track, so a runtime-layer guarded instance (the `Throw` self-lift,
    * `where E1 != E2`) resolves there too.
    *
    * Fail-safe: a real guard is discharged only over faithfully-traced bindings — a `metaToGround`-collapsed binding
    * could compare equal wrongly (§3.1), so an untraced match is an internal error rather than a silent wrong verdict.
    *
    * Second fail-safe — the guard-on-junk defusal (docs/effects-as-channel.md, finding 13, the amplifier): a match
    * may trace a guard operand faithfully to the **defaulted universe** `GroundValue.Type` — the read-back of a
    * type-argument slot unification never solved (e.g. an effect-carrier error slot `?E` that junk-grounded before the
    * `Throw` self-lift's `where E1 != E2` guard is reduced). Reducing the guard over that junk silently *selects* an
    * instance on a nonsense comparison (`Type != String` ⤳ the lift), which surfaces three steps later as a cryptic
    * unresolved carrier. So a guarded candidate whose operands include the defaulted universe is **declined** rather
    * than discharged — resolution falls through to `NoImplementation`, which the demanding use site reports *located*
    * (never a silent wrong instance choice). No legitimate guard compares the universe (the `Throw`/`Dep` self-lifts
    * compare error/key types; `Coerce`/`InRange` compare numbers), so this only ever fires on genuine junk. This is the
    * §7-independent mitigation the join-solver rewire (step 4) leans on: if that rewire ever junk-grounds a carrier
    * slot again, the guard fails loud here instead of miscompiling.
    */
  private def dischargeGuard(
      vfqn: ValueFQN,
      markerVfqn: ValueFQN,
      markerSig: Sourced[OperatorResolvedExpression],
      matched: AbilityMatcher.Match
  ): CompilerIO[Verdict] = {
    val keep: Verdict = Verdict.Keep(vfqn, matched.groundArgs)
    if (AbilityMatcher.isUnguarded(markerSig)) keep.pure[CompilerIO]
    else if (!matched.allTraced)
      error[CompilerIO](
        s"Internal: cannot discharge the guard of '${vfqn.name.name}' — a matched type parameter was not faithfully traced."
      ) >> abort[Verdict]
    else if (matched.groundArgs.exists(mentionsDefaultedUniverse)) decline
    else readGuardVerdict(markerVfqn, matched.groundArgs).flatMap(interpretGuard(_, keep))
  }

  /** A guard operand that is — or structurally contains — the defaulted universe [[GroundValue.Type]]: the read-back
    * of a type-argument slot unification never solved (defaulted at finalization, or produced by the matcher's
    * `metaToGround` fallback). Descends into constructor `args` **only**, never a value's `valueType` — every
    * [[GroundValue.Structure]] carries `valueType = Type`, so descending there would flag every operand. See the
    * second fail-safe in [[dischargeGuard]].
    */
  private def mentionsDefaultedUniverse(g: GroundValue): Boolean = g match {
    case GroundValue.Type                  => true
    case GroundValue.Structure(_, args, _) => args.exists(mentionsDefaultedUniverse)
    case _                                 => false
  }

  /** Read the marker's guard verdict from its **signature twin's** compile-time monomorphization (signature-unification
    * Phase D): `CompilerMonomorphicValue(marker@Signature, groundArgs).signature.deepReturnType`. A signature twin is
    * compiler-track by construction and the compiler pool borrows the entire runtime track, so a runtime-layer guarded
    * instance's marker twin (the `Throw` self-lift, `where E1 != E2`) resolves there too — the former platform switch
    * (Runtime-role `MonomorphicValue` vs Compiler-role `CompilerMonomorphicValue`) collapses to this one read. The guard
    * is a compile-time `Bool` computation; the twin's ordinary body mono reduces it to the verdict, which is that
    * signature's deep return type.
    */
  private def readGuardVerdict(
      markerVfqn: ValueFQN,
      groundArgs: Seq[GroundValue]
  ): CompilerIO[GroundValue] =
    getFactOrAbort(CompilerMonomorphicValue.Key(markerVfqn.copy(name = markerVfqn.name.signatureTwin), groundArgs))
      .map(_.signature.deepReturnType)

  /** Interpret a discharged guard's reduced return value (§3.1) into a [[Verdict]]. Both `Bool` representations are
    * accepted: a body-less `eliot.lang.Bool::true`/`false` reference (a [[GroundValue.Structure]] head) and a
    * native-reduced [[GroundValue.Direct]] boolean. A `Right(bool)` unwraps to its `Bool` payload (a guard using the
    * `{Throw[String]}` channel that succeeded); a `Left(msg)` is a hard rejection carrying the author's message;
    * anything else cannot occur at a concrete use site (a stuck guard) and is an internal error, never a silent accept.
    * (The `Right`/`Left` payload convention and the rejection fallback are the shared [[GuardChannel]] protocol.)
    */
  private def interpretGuard(verdict: GroundValue, keep: Verdict): CompilerIO[Verdict] =
    verdict match {
      case GroundValue.Direct(Literal.BooleanValue(true), _)                                            => keep.pure[CompilerIO]
      case GroundValue.Direct(Literal.BooleanValue(false), _)                                           => decline
      case GroundValue.Structure(fqn, _, _) if fqn == WellKnownTypes.boolTrueFQN  => keep.pure[CompilerIO]
      case GroundValue.Structure(fqn, _, _) if fqn == WellKnownTypes.boolFalseFQN => decline
      case GroundValue.Structure(fqn, args, _) if fqn == WellKnownTypes.rightFQN  =>
        GuardChannel.payload(args) match {
          case Some(payload) => interpretGuard(payload, keep)
          case None          => internalGuardError
        }
      case GroundValue.Structure(fqn, args, _) if fqn == WellKnownTypes.leftFQN   =>
        (Verdict.Reject(rejectionText(GuardChannel.payload(args))): Verdict).pure[CompilerIO]
      case _                                                                      => internalGuardError
    }

  /** The author message carried by a `Left(msg)` rejection — a literal `String` field. A non-literal (should not occur
    * at a concrete site) falls back to the shared generic message so the rejection is still reported, never silently
    * dropped.
    */
  private def rejectionText(field: Option[GroundValue]): String =
    field match {
      case Some(GroundValue.Direct(Literal.StringValue(s), _)) => s
      case _                                      => GuardChannel.fallbackRejectionMessage
    }

  private def internalGuardError: CompilerIO[Verdict] =
    error[CompilerIO](
      "Internal: ability guard did not reduce to a concrete `Bool` verdict at a concrete use site."
    ) >> abort[Verdict]

  private def markerVfqnFor(implVfqn: ValueFQN, abilityName: String): ValueFQN =
    ValueFQN(implVfqn.moduleName, QualifiedName(abilityName, implVfqn.name.qualifier))

  private def collectModuleNames(v: GroundValue): Seq[ModuleName] =
    v match {
      case GroundValue.Structure(typeName, args, _) =>
        args.flatMap(collectModuleNames) :+ typeName.moduleName
      case _                                        => Seq.empty
    }
}
