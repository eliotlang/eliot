package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.channel.EnvironmentThreading
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.MonomorphicEvaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CompilerMonomorphicValue,
  GroundValue,
  MonomorphicExpression,
  NativeBinding
}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Turns the compiler backend's *reduced* body (a [[MonomorphicExpression]] from
  * [[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue]]) into a self-contained
  * [[NativeBinding]] value (a `VTopDef` carrying a lazy thunk), closing the reduced body over its dependencies'
  * [[NativeBinding]]s. The [[MonomorphicExpression]] analogue of [[BindingClosure]] — same shape (a lazy self-contained
  * thunk over one-hop dependency bindings, mutual-recursion-guarded via the active fact-request chain), over the
  * reduced compile-time tree instead of the raw operator-resolved body.
  *
  * Used by [[CompilerNativesProcessor]] for a compiler-platform value whose body performs ability dispatch: its raw
  * body cannot reduce against the abstract ability method (`raise`/`pure` have no binding), so its `compiler`-label
  * contribution is this *reduced* form (the concrete `Either::raise`/`Either::pure`), which both the compiler track
  * (when it inlines the value into another compile-time reduction) and the runtime track (when it evaluates the value
  * in a type position) can then reduce.
  */
object ReducedBindingClosure {

  /** Reduce `vfqn` at concrete `typeArguments` on the **compiler track** and return its reduced body as a
    * self-contained NbE binding, or [[None]] if it produced no [[CompilerMonomorphicValue]] / has no reduced body.
    *
    * This is how a compile-time reduction reaches a callee's *reduced-at-its-instantiation* form: the stuck-driven
    * escalation shared by [[com.vanillasource.eliot.eliotc.monomorphize.check.PostDrainQuoter]] and
    * [[EscalatingReducer]] fetches this for each value reference that blocks a signature/body read-back.
    *
    * It always reduces **deep** ([[collectBindings]] with `deep = true`): each dependency is itself taken reduced at
    * its own instantiation, so a **stacked** carrier resolves through every layer — a reduced *function* body
    * (`guardOr`, `else`) keeps its impl-dispatch dependencies structural (they close over its runtime value
    * parameters), and applying it to concrete arguments must reach those dependencies already reduced (an
    * `AbortCarrier` impl over the base `Either` resolving through every layer) rather than a still-abstract raw
    * binding. Deep is safe here because it is only ever driven by [[EscalatingReducer.escalatingLoop]]'s laziness — a
    * *stuck* term escalates, nothing else — so it never eagerly monomorphizes a dependency evaluation does not demand.
    * (The former one-hop `deep = false` mode existed only to avoid that eager gotcha for the marker-guard reader, which
    * now reads the marker's signature-twin mono instead — no caller needs one-hop, so the flag is gone.) Always the
    * compiler track: a compile-time reduction never reaches a runtime target.
    */
  def reduceInstance(
      vfqn: ValueFQN,
      typeArguments: Seq[GroundValue]
  ): CompilerIO[Option[SemValue]] =
    getFactIfProduced(CompilerMonomorphicValue.Key(vfqn, typeArguments)).flatMap {
      case Some(cmv) =>
        threaded(cmv).flatMap(_.traverse(r => buildBinding(vfqn, r.value, Platform.Compiler, deep = true)))
      case None      => None.pure[CompilerIO]
    }

  /** A compile-track specialisation's reduced body, **threaded** (`docs/effects.md` §3.4): every binding a reduction
    * links is threaded, so a control effect's frame reaches its operation along the declared chain, exactly as on the
    * runtime track. A specialisation receiving and supplying nothing comes back as it was. Where the threading fails,
    * its errors are kept and the unthreaded body is linked — which takes no frame, so a control effect run through it
    * stays stuck rather than reaching another frame.
    */
  def threaded(cmv: CompilerMonomorphicValue): CompilerIO[Option[Sourced[MonomorphicExpression.Expression]]] =
    recover(EnvironmentThreading.threadCompiled(EnvironmentThreading.Specialisation.of(cmv)))(cmv.reduced)

  /** Build the NbE binding for `vfqn` from its reduced compile-time body, resolving each dependency's binding in the
    * given `platform` pool. The dependencies are resolved once here; the resulting `VTopDef` thunk is self-contained,
    * so evaluating it later needs no further fact lookup.
    */
  def buildBinding(
      vfqn: ValueFQN,
      reduced: MonomorphicExpression.Expression,
      platform: Platform,
      deep: Boolean = false
  ): CompilerIO[SemValue] =
    for {
      deps <- collectBindings(reduced, vfqn, platform, deep)
    } yield VTopDef(
      vfqn,
      Some(Lazy {
        val evaluator = new MonomorphicEvaluator(ref => deps.get(ref))
        evaluator.eval(Env.empty, MonomorphicExpression(GroundValue.Type, reduced))
      }),
      Spine.SNil
    )

  /** Collect the binding of every value reference in the reduced body, skipping the value's own FQN and any FQN already
    * an ancestor on the active fact-request chain (the same mutual-recursion guard [[BindingClosure]] uses). By default
    * each dependency is its raw [[NativeBinding]] body (a one-hop closure).
    *
    * When `deep` (the escalation fetch), a dependency is instead taken **reduced at its own instantiation**
    * ([[reduceInstance]], recursively, against the reference's ground type arguments), so a **stacked** carrier
    * resolves through every layer: when the reduced body of a *function* stays structural (its impl-dispatch
    * dependencies close over its runtime value parameters, e.g. `else`/`guardOr`), each such dependency
    * (`AbortCarrier`'s `Effect`/`Abort` impl over the base `Either`) still gets its own nested base ability resolved
    * rather than closing over the raw, still-abstract binding. `MonomorphicEvaluator` drops a reference's type
    * arguments, so the already-instantiated reduced dependency is used directly with no re-application. A dependency
    * with no reduced form (a native leaf, a `data` constructor) falls back to its raw [[NativeBinding]] body.
    */
  private[processor] def collectBindings(
      reduced: MonomorphicExpression.Expression,
      selfFqn: ValueFQN,
      platform: Platform,
      deep: Boolean
  ): CompilerIO[Map[ValueFQN, SemValue]] =
    valueReferences(reduced).filterNot(_._1 == selfFqn).toList.foldLeftM(Map.empty[ValueFQN, SemValue]) {
      case (acc, (fqn, typeArgs)) =>
        activeFactKeys.flatMap { ancestors =>
          if (ancestors.contains(NativeBinding.Key(fqn, platform))) acc.pure[CompilerIO]
          else {
            def raw: CompilerIO[Map[ValueFQN, SemValue]] =
              getFactIfProduced(NativeBinding.Key(fqn, platform)).map {
                case Some(binding) => binding.semValue.fold(acc)(sv => acc + (fqn -> sv))
                case None          => acc
              }
            if (deep)
              reduceInstance(fqn, typeArgs).flatMap {
                case Some(reduced) => (acc + (fqn -> reduced)).pure[CompilerIO]
                case None          => raw
              }
            else raw
          }
        }
    }

  private[processor] def valueReferences(
      expr: MonomorphicExpression.Expression
  ): Set[(ValueFQN, Seq[GroundValue])] = expr match {
    case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs, _) => Set((vfqn.value, typeArgs))
    case MonomorphicExpression.FunctionApplication(target, argument)     =>
      valueReferences(target.value.expression) ++ valueReferences(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)               => valueReferences(body.value.expression)
    case _                                                               => Set.empty
  }
}
