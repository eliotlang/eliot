package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.{AbilityImplementation, NamedImplementationCheck}
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Quoter
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, GroundValueRenderer}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}

/** The ability-resolution *saturation* concern (a checker collaborator, symmetrical with `carriers` / `guards`):
  * discovering every ability-qualified value reference and resolving each to its concrete impl, factored out of the
  * [[TypeStackLoop]]'s post-drain fold. None of this is definitional equality: it re-enters `getFact` to read an
  * ability marker's arity, and it drives the `resolve-abilities` fixed-point pass.
  *
  * Two entry points, mirroring the two phases:
  *   - [[collectAbilityRefs]] — walk the checker's output trees to find every ability-qualified reference (called from
  *     `TypeStackLoop.processIO` to seed the pass context).
  *   - [[resolveAbilities]] — the `resolve-abilities` saturation pass body: try to resolve every still-unresolved
  *     reference once, recording each impl.
  *
  * The resolutions themselves live in the shared [[CheckState.abilityResolutions]] map — this module only *records*
  * into it. Operates over [[CheckIO]], reading and writing the shared [[CheckState]] through `get`/`modify`/`inspect`.
  * It depends on exactly the primitives passed at construction — that narrow surface is the module boundary.
  *
  * @param resolveAbility
  *   Resolve an ability at concrete type arguments to its impl — the checker's `resolveAbility`.
  * @param bindingForwards
  *   Which binders of an implementation method take the **call's** binding when a member dispatches to it
  *   ([[DeclaredEntries.forwarded]]) — read off the two declarations, so the checker passes the fact read.
  * @param platform
  *   The track's platform, used to key the ability-marker signature fact.
  */
class AbilityResolver(
    resolveAbility: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[(ValueFQN, Seq[GroundValue])]],
    bindingForwards: (ValueFQN, ValueFQN) => CompilerIO[Seq[(Int, Int)]],
    platform: Platform
) {
  import AbilityResolver.AbilityRef

  /** Walk a [[SemExpression]] tree and collect all ability-qualified [[SemExpression.ValueReference]] nodes with their
    * current type arguments. Non-ability references and other shapes are skipped.
    */
  def collectAbilityRefs(expr: Sourced[SemExpression]): Seq[AbilityRef] = {
    def go(se: SemExpression): Seq[AbilityRef] = se.expression match {
      case SemExpression.ValueReference(vfqn, typeArgs)        =>
        vfqn.value.name.qualifier match {
          case _: Qualifier.Ability => Seq((vfqn, typeArgs))
          case _                    => Seq.empty
        }
      case SemExpression.FunctionApplication(target, argument) =>
        go(target.value) ++ go(argument.value)
      case SemExpression.FunctionLiteral(_, _, body)           =>
        go(body.value)
      case _                                                   => Seq.empty
    }
    go(expr.value)
  }

  /** Saturation pass: try to resolve every still-unresolved ability reference once. Returns `true` iff at least one
    * ability newly resolved this call — a resolution records the impl, which the next round's drain propagates.
    *
    * Every reference is offered to [[tryResolveOne]] each round; the "already resolved?" test lives *there*, after the
    * reference's own ability-level arguments have been sliced and quoted, because that is the first point at which the
    * [[CheckState.AbilityResolutionKey]] exists. Skipping by position alone here is what let a second reference at the
    * same span inherit the first one's impl (see [[CheckState.abilityResolutions]]).
    */
  def resolveAbilities(allRefs: Seq[AbilityRef]): CheckIO[Boolean] =
    allRefs.toList.foldLeftM(false)((acc, ref) => tryResolveOne(ref).map(_ || acc))

  /** Try to resolve a single ability reference. Returns `true` iff the reference got newly resolved this call.
    *
    *   - The ref's own ability-level type arguments are used. Quoting failure (still-unsolved metas) leaves the ref
    *     pending for the next iteration. (A fallback that took them from the enclosing definition's first constraint of
    *     the same local ability name was never reached by the tests, the examples or the dependents, and was wrong for
    *     two entries of one ability; it was deleted, `docs/effects.md` §8 item 54.)
    *   - An **effect**'s operation is left alone: the specialisation pass dispatches it from the evidence in scope
    *     (`docs/effects.md` D35). An **ability**'s ground arguments begin with its binding, always `Default`
    *     ([[ImplementationBinding]]), and the rest go to the two-site search; the resolution is keyed under the full
    *     sliced arguments, so the post-drain quoter re-derives the same slice.
    *   - `resolveAbility` returning `None` at *ground* arguments is a failed **demand**: the [[AbilityImplementation]]
    *     fact's non-resolved outcome is read back and reported here, at this reference — the demand site — and the
    *     check aborts (see [[reportFailedDemand]]). Only an *absent* fact (the resolution aborted on an upstream error,
    *     already reported at its own definition) leaves the ref silently pending, to be emitted by [[PostDrainQuoter]]
    *     as an abstract reference.
    */
  private def tryResolveOne(ref: AbilityRef): CheckIO[Boolean] = {
    val (abilityVfqn, refTypeArgs) = ref
    abilityVfqn.value.name.qualifier match {
      case Qualifier.Ability(abilityName) =>
        for {
          state      <- get
          // The ability query must carry ONLY the ability-level type arguments. A method reference's own type args are
          // `[abilityParams ++ methodParams]` (the ability's params are prepended to every method signature), so we
          // slice off the method-level params, keeping the leading `abilityArity` (e.g. `flatMap` of `Monad[F[_]]`
          // queries `Monad[F]`, not `Monad[F, A, B]`).
          arity      <- abilityArity(abilityVfqn.value, abilityName)
          _          <- modify(_.recordAbilityArity(abilityVfqn.value, arity))
          refSliced   = refTypeArgs.take(arity)
          refGroundE  = refSliced.toList.traverse(a => Quoter.quote(0, a, state.unifier.metaStore))
          // A resolution is recorded under the ref's own ground arguments, which pin the impl and which the post-drain
          // quoter re-derives exactly. Arguments still unsolved metas leave the ref pending for the next round.
          progressed <- refGroundE match {
                          case Right(groundArgs) if state.abilityResolutions.contains((abilityVfqn, groundArgs)) =>
                            pure(false)
                          case Right(ground)                                                                   =>
                            // An effect's operation is left to the specialisation pass, which dispatches it on the
                            // evidence in scope (`docs/effects.md` D35): nothing in the key says which implementation
                            // answers it — the runtime track checks with effect bindings left out, and the compile
                            // track's are the caller's, while a block's are what the callee running it gives.
                            //
                            // On the new effect path (`docs/effects.md` D36) the first argument is the handle's type, not
                            // a binding, and the operation is an ordinary ability method dispatched by it: the handle's
                            // instance is the only one at its type.
                            liftF(AbilityKind.isEffect(AbilityFQN(abilityVfqn.value.moduleName, abilityName), platform))
                              .flatMap {
                                case Some(true) if ImplementationBinding.split(ground)._2.isDefined => pure(false)
                                case _                                                               =>
                                  resolveGround(abilityVfqn, abilityName, ground)
                              }
                          case Left(_)                                                                         =>
                            pure(false)
                        }
        } yield progressed
      case _                              => pure(false)
    }
  }

  /** Resolve a reference at its ground ability-level arguments, recorded under them. Only an **ability** reaches here,
    * and its binding is always `Default`: an ability has no named implementation and is never bound with `with`
    * (`docs/effects.md` D29), so it is answered by the two-site search alone. An effect's operation is the
    * specialisation pass's to dispatch (D35).
    */
  private def resolveGround(
      abilityVfqn: Sourced[ValueFQN],
      abilityName: String,
      groundArgs: Seq[GroundValue]
  ): CheckIO[Boolean] =
    ImplementationBinding.split(groundArgs) match {
      case (_, Some(_: ImplementationBinding.Implementation)) =>
        liftF(compilerAbort(abilityVfqn.as(s"Internal: the ability '$abilityName' is bound to a named implementation.")))
      case (patternArgs, _)                                   =>
        searchAndRecord(abilityVfqn, abilityName, (abilityVfqn, groundArgs), patternArgs)
    }

  /** The two-site search: resolve the ability at its ground pattern arguments and record the impl under `key`, or
    * report the failed demand. What every reference gets today, and what a `Default` binding asks for.
    */
  private def searchAndRecord(
      abilityVfqn: Sourced[ValueFQN],
      abilityName: String,
      key: CheckState.AbilityResolutionKey,
      patternArgs: Seq[GroundValue]
  ): CheckIO[Boolean] =
    liftF(resolveAbility(abilityVfqn.value, patternArgs)).flatMap {
      case Some(impl) => record(abilityVfqn.value, key, impl).as(true)
      case None       => reportFailedDemand(abilityVfqn, abilityName, patternArgs)
    }

  /** Record a resolution, and which of the implementation method's binders take the call's binding instead of the
    * implementation's ([[DeclaredEntries.forwarded]]): the entries the member declares are the call's to bind
    * (`docs/effects.md` §3.4), whatever the implementation's clause, which restates them, was handed.
    */
  private def record(
      member: ValueFQN,
      key: CheckState.AbilityResolutionKey,
      impl: (ValueFQN, Seq[GroundValue])
  ): CheckIO[Unit] =
    for {
      forwards <- liftF(bindingForwards(member, impl._1))
      _        <- modify(_.recordAbilityResolution(key, impl).recordBindingForwards(member, impl._1, forwards))
    } yield ()

  /** A ground-argument demand did not resolve. The producer
    * ([[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationProcessor]]) registers the failed
    * [[AbilityImplementation.Resolution]] outcome on the fact instead of erroring — the position belongs to the
    * demander, and this saturation pass is the demander: it holds the demanding reference, so the failure is reported
    * *here*, at the use site, and the value's monomorphization aborts (the hard error at the manifest instantiation,
    * ability-guards §3). An **absent** fact means the resolution itself aborted on an upstream error already reported
    * at its own definition, so nothing is added and the reference stays silently pending. A `Resolved` outcome cannot
    * reach this method (the `resolveAbility` callback returns it), but is left pending for the next round rather than
    * treated as an error, keeping the match total and fail-safe.
    */
  private def reportFailedDemand(
      ref: Sourced[ValueFQN],
      abilityName: String,
      groundArgs: Seq[GroundValue]
  ): CheckIO[Boolean] =
    liftF(getFactIfProduced(AbilityImplementation.Key(ref.value, groundArgs, platform))).flatMap { factOpt =>
      val argsShown = groundArgs.map(GroundValueRenderer.render).mkString("[", ", ", "]")
      factOpt.map(_.resolution) match {
        case None                                                      => pure(false)
        case Some(AbilityImplementation.Resolution.Resolved(_, _))     => pure(false)
        case Some(AbilityImplementation.Resolution.NoImplementation)   =>
          liftF(
            compilerError(
              ref.as(
                s"No ability implementation found for ability '$abilityName' with type arguments $argsShown."
              )
            ) >> abort[Boolean]
          )
        case Some(AbilityImplementation.Resolution.Rejected(messages)) =>
          liftF(messages.traverse_(message => compilerError(ref.as(message))) >> abort[Boolean])
        case Some(AbilityImplementation.Resolution.Ambiguous)          =>
          liftF(
            compilerError(
              ref.as(
                s"Multiple ability implementations found for ability '$abilityName' with type arguments $argsShown."
              )
            ) >> abort[Boolean]
          )
      }
    }

  /** The number of ability-level type parameters of the ability owning `methodVfqn` — read off the ability *marker*'s
    * signature (the synthetic value named after the ability, sharing the method's `Ability(name)` qualifier), whose
    * leading generic binders are exactly the ability's parameters. Used to slice an ability method reference's full
    * type args down to the ability-level prefix for the impl query. Falls back to `Int.MaxValue` (slice nothing) if the
    * marker has no resolvable signature, preserving prior behaviour rather than mis-slicing.
    */
  private def abilityArity(methodVfqn: ValueFQN, abilityName: String): CheckIO[Int] = {
    val markerFqn = ValueFQN(methodVfqn.moduleName, QualifiedName(abilityName, methodVfqn.name.qualifier))
    liftF(getFactIfProduced(OperatorResolvedValue.Key(markerFqn, platform))).map {
      case Some(orv) =>
        OperatorResolvedExpression.SignatureView.of(orv.signature).binders.length
      case None      => Int.MaxValue
    }
  }
}

object AbilityResolver {

  /** An ability-qualified value reference paired with its current type arguments (unquoted [[SemValue]]s). */
  type AbilityRef = (Sourced[ValueFQN], Seq[SemValue])
}
