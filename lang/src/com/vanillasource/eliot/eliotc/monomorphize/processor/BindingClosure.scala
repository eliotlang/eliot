package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.NativeBinding
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Turns a [[SaturatedValue]] into a binding (a `VTopDef` carrying a lazy body thunk) for the NbE evaluator, closing
  * the body over the [[NativeBinding]]s of its dependencies. The caller decides which body the value contributes,
  * passed as `selfBody`: the [[BindingMergerProcessor]], for a
  * [[com.vanillasource.eliot.eliotc.monomorphize.fact.BindingContribution.Body]] contribution, uses
  * [[OperatorResolvedValue.runtime]].
  *
  * Reads the [[SaturatedValue]] (the same fact the monomorphize checker reads), not the raw [[OperatorResolvedValue]],
  * for two reasons: the saturated signature carries the binder roles ([[SaturatedValue.binderRoles]]) the wrap
  * consumes, and its leading binders line up with the type arguments the checker applies (saturation may prepend
  * binders for omittable `auto` parameters) — keeping the wrap's binder indices aligned (D6).
  *
  * The subtle, error-prone part lives here once: the dependency collection. Dependencies are
  * always resolved via [[NativeBinding]] (the checker's semantics, the bindings the bodies were type-checked against);
  * only the top-level value's own body selection differs between callers.
  */
object BindingClosure {

  /** Build the NbE binding for `saturated`, taking the value's own body via `selfBody`. A value whose `selfBody` is
    * empty — a body-less native or abstract declaration — yields a stuck `VTopDef(vfqn, None, SNil)`; a body-ful value
    * yields a `VTopDef` carrying a lazy thunk that evaluates the body on demand against its dependencies'
    * [[NativeBinding]]s.
    */
  def buildBinding(
      saturated: SaturatedValue,
      selfBody: OperatorResolvedValue => Option[Sourced[OperatorResolvedExpression]],
      platform: Platform
  ): CompilerIO[SemValue] = {
    val vfqn = saturated.value.vfqn
    val body = selfBody(saturated.value)
    for {
      bodyBindings <- body match {
                        case Some(b) => collectBindings(b.value, vfqn, platform)
                        case None    => Map.empty[ValueFQN, SemValue].pure[CompilerIO]
                      }
    } yield VTopDef(
      vfqn,
      body.map { b =>
        Lazy {
          val evaluator = new Evaluator(ref => bodyBindings.get(ref))
          evaluator.eval(Env.empty, reifyingWrap(b.value, saturated))
        }
      },
      Spine.SNil,
      // The binding states the wrap it just performed, so unfolding can tell a written type argument this body can bind
      // from one it cannot (see `SemValue.VTopDef.TypeArgFit`). A body-less value binds nothing and needs no statement —
      // its spine *is* its argument list.
      Option.when(body.isDefined)(VTopDef.TypeArgFit(saturated.binderRoles.roles.map(_.name.value), 0))
    )
  }

  /** Wrap a runtime body in [[FunctionLiteral]] binders for the leading generic parameters it reifies — the ones it
    * references in *value* position — so that applying the value's explicit type arguments substitutes them.
    *
    * A generic parameter is a binder of the value's '''signature''' (a leading generic `[N]`), but it is '''not''' a
    * lambda of the '''runtime body''': `def bigOf[V] = V` has signature `(V: BigInteger) -> BigInteger` yet runtime
    * body just `V` (a bare reference, the reification of the erased `V`). Evaluated as-is under the empty env, that `V`
    * becomes a free [[SemValue.VNeutral]], so a later `bigOf[1]` only appends `1` to the neutral's spine instead of
    * reducing to `1` — and any computed type argument flowing through such a value (e.g. `h[subtract(N, bigOf[1])]`)
    * stays a stuck term that fails read-back ("Cannot resolve type."). Wrapping the reified binders as leading lambdas
    * makes the spine application substitute them, exactly as the checker does for the value's own monomorphization via
    * `bindTypeStackParam`.
    *
    * Which binders are reified — and the contiguous prefix to wrap (up to and including the last reified one, so
    * explicit-type-argument threading stays positionally aligned) — is the precomputed [[SaturatedValue.binderRoles]]
    * (D6). When nothing is reified the body is returned unchanged, so ordinary generics (e.g. `id[A](x: A) = x`, whose
    * `A` never appears in value position) keep their existing cached shape and implicit-type-argument application is
    * unaffected.
    */
  private def reifyingWrap(
      body: OperatorResolvedExpression,
      fact: SaturatedValue
  ): OperatorResolvedExpression =
    fact.binderRoles.reifiedPrefixBinders
      .foldRight(fact.value.name.as(body)) { (name, accBody) =>
        name.as(OperatorResolvedExpression.FunctionLiteral(name, None, accBody))
      }
      .value

  /** Collect the [[NativeBinding]] of every ValueReference in `ore`, skipping FQNs already accumulated in this walk and
    * the value's own FQN (`selfFqn`). Each binding closes over its own dependencies' bindings in turn, so the closure is
    * transitive.
    *
    * Nothing here can cycle: a body is only bound once the recursion gate admitted it, so a chain of bindings is a chain
    * of body references, which has no cycle. Should one appear anyway, the engine refuses the read as a cyclic fact
    * demand and fails the build. Skipping a binding that is being generated up the request chain, as this did once,
    * made a binding's value depend on which value was bound first (`docs/compiler-performance.md` §15.2).
    */
  private def collectBindings(
      ore: OperatorResolvedExpression,
      selfFqn: ValueFQN,
      platform: Platform
  ): CompilerIO[Map[ValueFQN, SemValue]] =
    OperatorResolvedExpression
      .foldValueReferences[CompilerIO, Map[ValueFQN, SemValue]](ore, Map.empty) { (acc, vfqn) =>
        if (acc.contains(vfqn.value) || vfqn.value == selfFqn) acc.pure[CompilerIO]
        else
          getFactIfProduced(NativeBinding.Key(vfqn.value, platform)).map {
            case Some(binding) => binding.semValue.fold(acc)(sv => acc + (vfqn.value -> sv))
            case None          => acc
          }
      }
}
