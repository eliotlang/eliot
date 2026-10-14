package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Quoter
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, GroundValueRenderer}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

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
  def resolveAbilities(
      allRefs: Seq[AbilityRef],
      paramConstraints: Map[String, Seq[AbilityConstraint[OperatorResolvedExpression]]]
  ): CheckIO[Boolean] =
    allRefs.toList.foldLeftM(false) { (acc, ref) =>
      tryResolveOne(ref, paramConstraints).map(_ || acc)
    }

  /** Try to resolve a single ability reference. Returns `true` iff the reference got newly resolved this call.
    *
    *   - If the ref is covered by an in-scope parameter constraint (e.g. calling a method of `Foo[T]` inside a `[T]
    *     with Foo[T]` context), the constraint's own type arguments are used — at monomorphization time they're already
    *     instantiated to the caller's concrete types, whereas the reference's implicit metas won't be solved until
    *     unification connects them. The constraint path is the direct way to get concrete args.
    *   - Otherwise the ref's own type arguments are used.
    *   - Quoting failure (still-unsolved metas) leaves the ref pending for the next iteration.
    *   - The ground ability-level arguments may end in a **binding** ([[ImplementationBinding]], effects v6 §9.4 step
    *     4): an implementation named there is recorded **directly** — its method of this reference's name, at the type
    *     arguments its pattern takes at this reference's pattern arguments ([[NamedImplementationArguments]]), an
    *     implementation whose pattern does not match them being an error here — with no search, no `where` filter and
    *     no coherence question, since `with`
    *     *replaces* the search rather than parameterising it; the `Default` marker (and no binding at all) goes to the
    *     two-site search at the pattern arguments — for an **ability**. An effect has no default (`docs/effects.md`
    *     D26), so a `Default` reaching one of its members is an error here ([[reportUnboundEffect]]). The resolution is keyed under the full
    *     sliced arguments, binding included, so one span referenced under two bindings records two entries — exactly
    *     as two carriers at one span do — and the post-drain quoter re-derives the same slice.
    *   - `resolveAbility` returning `None` at *ground* arguments is a failed **demand**: the [[AbilityImplementation]]
    *     fact's non-resolved outcome is read back and reported here, at this reference — the demand site — and the
    *     check aborts (see [[reportFailedDemand]]). Only an *absent* fact (the resolution aborted on an upstream error,
    *     already reported at its own definition) leaves the ref silently pending, to be emitted by [[PostDrainQuoter]]
    *     as an abstract reference.
    */
  private def tryResolveOne(
      ref: AbilityRef,
      paramConstraints: Map[String, Seq[AbilityConstraint[OperatorResolvedExpression]]]
  ): CheckIO[Boolean] = {
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
          // Prefer the reference's OWN type arguments once they are fully ground: they pin the exact implementation.
          // This is essential when several constraints share one ability — e.g. `Dep[Database]` and `Dep[Logger]` in a
          // single body are both `Dep`, so the by-name constraint lookup would always return the first and every `get`
          // would mis-dispatch to it. The constraint path remains the fallback *while the ref's args are still unsolved
          // metas* (its original rationale: at the use site the caller has already instantiated the constraint to
          // concrete types, whereas the reference's implicit metas are solved only once unification connects them).
          groundArgsE = refGroundE match {
                          case Right(ground) => Right(ground)
                          case Left(_)       =>
                            state.findConstraintTypeArgs(paramConstraints, abilityName) match {
                              case Some(cArgs) =>
                                cArgs.toList.traverse(a => Quoter.quote(0, a, state.unifier.metaStore))
                              case None        => refGroundE
                            }
                        }
          // The key this resolution is recorded under: the ref's own ground arguments when it has them (they pin the
          // impl and the post-drain quoter re-derives exactly them), otherwise the constraint-covered `None` key.
          key         = (abilityVfqn, refGroundE.toOption)
          progressed <- groundArgsE match {
                          case Right(_) if state.abilityResolutions.contains(key) => pure(false)
                          case Right(groundArgs)                                  =>
                            ImplementationBinding.split(groundArgs) match {
                              case (patternArgs, Some(impl: ImplementationBinding.Implementation)) =>
                                liftF(NamedImplementationArguments.solve(impl, patternArgs, platform)).flatMap {
                                  case Some(typeArguments) =>
                                    val method = impl.method(abilityVfqn.value.name.name)
                                    liftF(getFactOrAbort(UnifiedModuleNames.Key(method.moduleName, platform))).flatMap {
                                      case names if names.names.contains(method.name) =>
                                        record(abilityVfqn.value, key, (method, typeArguments)).as(true)
                                      case _                                         =>
                                        reportMissingClause(abilityVfqn, abilityName, impl)
                                    }
                                  case None                =>
                                    reportUnmatchedBinding(abilityVfqn, abilityName, impl, patternArgs)
                                }
                              case (patternArgs, _)                                      =>
                                liftF(isEffect(abilityVfqn.value, abilityName)).flatMap {
                                  case true  => reportUnboundEffect(abilityVfqn, abilityName)
                                  case false => searchAndRecord(abilityVfqn, abilityName, key, patternArgs)
                                }
                            }
                          case Left(_)                                            => pure(false)
                        }
        } yield progressed
      case _                              => pure(false)
    }
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

  /** Whether the ability `member` belongs to is an **effect**, read where effect-ness is recorded: the member's own
    * declared clause, which membership in an `effect` block fills with the effect itself.
    */
  private def isEffect(member: ValueFQN, abilityName: String): CompilerIO[Boolean] =
    getFactIfProduced(OperatorResolvedValue.Key(member, platform)).map(
      _.exists(_.effectRow.returnEffects.exists(_.abilityFQN == AbilityFQN(member.moduleName, abilityName)))
    )

  /** A member of an effect reached with `Default`: nothing bound an implementation of it. The write reports an
    * uncovered effect in a body, so what reaches here is a binding promised by a slot that names no implementation — a
    * body-less discharger whose platform layer, which would name one, is not on the path.
    */
  private def reportUnboundEffect(ref: Sourced[ValueFQN], abilityName: String): CheckIO[Boolean] =
    liftF(
      compilerError(
        ref.as(s"Nothing binds an implementation of the effect '$abilityName' here, and an effect has no default."),
        Seq(
          "An effect is bound by a `with`, by a parameter's slot that names an implementation, or by the caller of a " +
            "definition declaring it; a body-less discharger's slot names one only where a platform layer bodies it."
        )
      ) >> abort[Boolean]
    )

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

  /** A `with` bound an implementation for an entry its pattern does not match: `with none`, for `implement[T] none:
    * Source[Option[T]]`, where the reference performs `Source[Int]`. Reported at the reference, which is where the
    * entry's arguments are known — inside whatever definition the `with`'s subject reached.
    */
  private def reportUnmatchedBinding(
      ref: Sourced[ValueFQN],
      abilityName: String,
      impl: ImplementationBinding.Implementation,
      patternArgs: Seq[GroundValue]
  ): CheckIO[Boolean] = {
    val name    = implementationName(impl, abilityName)
    val entry   =
      if (patternArgs.isEmpty) abilityName else patternArgs.map(GroundValueRenderer.render).mkString(s"$abilityName[", ", ", "]")
    liftF(
      compilerError(
        ref.as(s"This performs '$entry' on the implementation '$name', which does not implement it."),
        Seq(s"A `with` around a use of this definition binds '$name'; bind one that implements '$entry' instead.")
      ) >> abort[Boolean]
    )
  }

  /** A `with` bound an implementation with no clause for this reference's method on the path: the base's body-less
    * `implement standardConsole: Console` where no platform layer bodies it (a library package's closure), which the
    * entry point binds all the same (`docs/effects.md` D26). Reading the missing method would abort with no error, so
    * the gap is reported here, at the reference.
    */
  private def reportMissingClause(
      ref: Sourced[ValueFQN],
      abilityName: String,
      impl: ImplementationBinding.Implementation
  ): CheckIO[Boolean] = {
    val name = implementationName(impl, abilityName)
    liftF(
      compilerError(
        ref.as(s"The implementation '$name' has no '${ref.value.name.name}' here."),
        Seq(s"No layer on the path declares that clause of '$name'; a platform layer is what bodies it.")
      ) >> abort[Boolean]
    )
  }

  private def implementationName(impl: ImplementationBinding.Implementation, abilityName: String): String =
    impl.head.name.qualifier match {
      case Qualifier.AbilityImplementation(_, _, Some(name)) => name
      case _                                                 => abilityName
    }

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
