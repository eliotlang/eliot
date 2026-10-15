package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.Id
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.processor.MetaConstructorDesugarer
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, UnifiedModuleNames, UnifiedModuleValue, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{BindingContribution, CompilerMonomorphicValue, ContributedBinding}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue

/** The `compiler` native supplier: contributes the [[ContributedBinding]] under [[ContributedBinding.compilerLabel]] for
  * the compile-time reduction a name has in the **compiler** source pool.
  *
  * The compiler is itself a platform (the "compiler as a platform" plan, CP3). The compiler pool scans the runtime track
  * *completely* plus its own override overlay (the compiler-platform layer wins any redefinition, via
  * [[com.vanillasource.eliot.eliotc.source.scan.PathScan.overrideFiles]] and `UnifiedModuleValueProcessor`), so this
  * contributor reads the value's own [[Platform.Compiler]]-marker
  * [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]] for *every* name reachable in the build. That marker
  * sees the overlay's redefinition where one exists (e.g. the compile-time `Either` carrier) and otherwise the
  * platform's own definition (e.g. jvm's `foldEither`): the compiler *borrows* a platform body when it is
  * compiler-runnable, which is how compile-time code reaches a function a platform codebase defines. It is registered as
  * a **native** label, so its reduction is preferred for checking while the runtime layer's body is still what
  * codegen reads (the `add` pattern — one name, two platform implementations, kept apart by the
  * marker).
  *
  * **Body vs. native — borrow is fail-safe.** [[BodyContributorProcessor]] contributes a `Body` for a body-ful value and
  * `None` for a body-less one (a native leaf, whose checking implementation is a native — never an empty binding that
  * would shadow a reducing native, the `add` bug). So a pure platform body (jvm's `foldEither`, an `Effect[IO]` method)
  * becomes a compile-time `Body` and reduces if forced, whereas a body that bottoms out in a bytecode leaf
  * (the `Numeric[Int]` `add`) reduces only until it stalls on that leaf, surfacing a loud stuck term rather
  * than a silently wrong reduction, and a bare native leaf stays `None`.
  *
  * **Totality without spurious errors.** The [[BindingMergerProcessor]] consults this label for *every* name. A name is
  * asked about by membership first — does its module's [[Platform.Compiler]] [[UnifiedModuleNames]] declare it? — and
  * only a *member* triggers a [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]] request. Because the
  * compiler pool now contains the runtime track, every name in the build is a member and resolves; membership is `false`
  * only for a genuinely absent name (a module in no layer of the compiler pool), answered the total `None` without
  * requesting (and thus without erroring on) its value. With only the abstract base on the compiler path (no platform,
  * no overlay) every name is body-less or absent, so this contributes `None` everywhere and changes no resolution.
  */
class CompilerNativesProcessor extends BodyContributorProcessor(ContributedBinding.compilerLabel, Platform.Compiler) {

  // Only this label's queries belong to the compiler contributor; short-circuit other labels before any fetch. A
  // member of the compiler pool is built by BodyContributorProcessor from its compiler-marker SaturatedValue; a
  // non-member contributes the total `None` answer without requesting (and thus without erroring on) its value.
  override protected def generateFact(key: ContributedBinding.Key): CompilerIO[Unit] =
    if (key.label =!= ContributedBinding.compilerLabel) abort
    else
      inCompilerPool(key.vfqn).ifM(
        super.generateFact(key),
        registerFactIfClear(ContributedBinding(key.vfqn, ContributedBinding.compilerLabel, none))
      )

  /** Whether `vfqn` is declared by its module in the compiler pool (which spans the runtime track plus the override
    * overlay). Reading [[UnifiedModuleNames]] (a name set) rather than
    * [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]] keeps the non-member case quiet: a genuinely absent
    * module yields no fact and a present module simply does not list the name — neither path emits the "Could not find"
    * error a direct value request would.
    */
  private def inCompilerPool(vfqn: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(vfqn.moduleName, Platform.Compiler)).map(_.names.contains(vfqn.name))

  /** A **compile-time** value — one the compiler layer defines (concrete here) but the runtime pool leaves abstract, e.g.
    * the compiler-layer effect-carrier overlays that reduce an inline `if..else..raise` guard (the `Effect`/`Abort`
    * instances on `AbortCarrier`, `foldEither`/`foldOption`) — whose checking body performs ability dispatch
    * (`raise`/`pure`) cannot be reduced by the pure NbE evaluator against the abstract ability method (which has no
    * binding), so its raw body would stall on it. For such a value the compile-time reduction is the **compiler
    * backend's** output — the [[CompilerMonomorphicValue]] whose ability calls are resolved to the concrete impls and
    * folded — built here into a self-contained [[BindingContribution.Leaf]] ([[ReducedBindingClosure]]). Both tracks then
    * get the reducible form: the runtime track evaluates it in a type position (compiler-as-platform Increment E, the
    * guard consumer), and the compiler track inlines it when reducing another compile-time value (e.g. `pure` inside the
    * `AbortCarrier` `Effect` instance).
    *
    * The gate is deliberately narrow. Reducing an ability-using value at compile time only makes sense for a genuine
    * compile-time value; a *runtime* value that merely uses effects (`catch`/`runStateToPair`, or any user `{Console}` body)
    * is runtime-concrete and reaches runtime-only leaves (`runThrow`), so eagerly reducing it would raise a spurious
    * native-leaf error. So the reduced path is taken only when the value is **runtime-abstract** (no runtime body) — the
    * compiler-only / redefined-in-compiler-layer case. Everything else keeps the ordinary raw-body
    * [[BindingContribution.Body]] path.
    *
    * On a missing reduction — the value did not produce a `CompilerMonomorphicValue` (an upstream compile error) — the
    * raw body path is kept as a fail-safe: it stays stuck on the abstract ability and surfaces a loud use-site mismatch,
    * never a silently wrong reduction.
    */
  override protected def generateFromKeyAndFact(
      key: ContributedBinding.Key,
      fact: SaturatedValue
  ): CompilerIO[ContributedBinding] =
    if (performsAbility(fact.value))
      runtimeConcrete(key.vfqn).ifM(
        super.generateFromKeyAndFact(key, fact),
        getFactIfProduced(CompilerMonomorphicValue.Key(key.vfqn, Seq.empty)).flatMap {
          case Some(cmv) =>
            cmv.reduced match {
              case Some(reduced) =>
                ReducedBindingClosure
                  .buildBinding(key.vfqn, reduced.value, Platform.Compiler)
                  .map(sem => ContributedBinding(key.vfqn, label, Some(BindingContribution.Leaf(sem))))
              case None          => super.generateFromKeyAndFact(key, fact)
            }
          case None      => super.generateFromKeyAndFact(key, fact)
        }
      )
    else super.generateFromKeyAndFact(key, fact)

  /** Whether the value's checking body references any ability method (`Qualifier.Ability`). Read off the raw
    * operator-resolved body — the signal that its raw reduction would stall on an unresolved ability call.
    */
  private def performsAbility(value: OperatorResolvedValue): Boolean =
    value.runtime.exists { body =>
      OperatorResolvedExpression.foldValueReferences[Id, Boolean](body.value, false) { (acc, ref) =>
        acc || (ref.value.name.qualifier match {
          case _: Qualifier.Ability => true
          case _                    => false
        })
      }
    }

  /** Whether `vfqn` has a *runtime* implementation (a body in the runtime pool). A runtime-concrete value is a runtime
    * value — its reduction belongs to codegen, not compile time — so it is never compile-time-reduced here even when it
    * uses effects. Gated on runtime-pool membership (a quiet name-set read) so a compiler-only value is not probed and
    * does not trigger the runtime pool's "Could not find" abort.
    *
    * Having a body is read off the merged [[UnifiedModuleValue]], where the layers decided it, never off a later phase's
    * fact: a definition a later phase *rejected* (a `main` whose effect write failed) produces no `SaturatedValue`, and
    * reading that absence as "runtime-abstract" monomorphized it on the compile track, at no type arguments, only to
    * report a second, misleading error after the real one (`docs/effects.md` §8 item 29).
    *
    * A **refinement artifact** ([[isRefinementArtifact]]) is never runtime-concrete for this purpose even though it is
    * physically emitted into the runtime pool (dead there, never code-generated): it is a compile-time-only value the
    * refinement channel evaluates, so its ability calls (`add`/`join` dispatched on the interval domain) must be resolved
    * — the [[CompilerMonomorphicValue]] reduced form — never the raw operator-resolved body whose ability stays abstract
    * and stalls. This is the plan invariant (`docs/refinement-channel-follow-ups.md`) applied to the base binding
    * a post-monomorphize reduction closes over: never a raw body.
    */
  private def runtimeConcrete(vfqn: ValueFQN): CompilerIO[Boolean] =
    if (isRefinementArtifact(vfqn)) false.pure[CompilerIO]
    else
      getFactOrAbort(UnifiedModuleNames.Key(vfqn.moduleName, Platform.Runtime))
        .map(_.names.contains(vfqn.name))
        .ifM(
          getFactIfProduced(UnifiedModuleValue.Key(vfqn, Platform.Runtime)).map(_.exists(_.namedValue.runtime.isDefined)),
          false.pure[CompilerIO]
        )

  /** Whether `vfqn` is a compile-time-only refinement-channel artifact: a `^Meta` transfer companion / meta accessor
    * ([[Qualifier.Meta]]) or the auto-derived `Meta[<T>$Meta]` lattice instance
    * ([[Qualifier.AbilityImplementation]] whose pattern is the meta structure). Both are dead in the runtime pool (never
    * code-generated) and reached only by the refinement channel, so their reduction is compile-time and their ability
    * calls must be resolved through the compiler backend, not their raw bodies.
    */
  private def isRefinementArtifact(vfqn: ValueFQN): Boolean = vfqn.name.qualifier match {
    case _: Qualifier.Meta                           => true
    case Qualifier.AbilityImplementation(_, pattern, _) => pattern.contains(MetaConstructorDesugarer.metaTypeSuffix)
    case _                                           => false
  }
}
