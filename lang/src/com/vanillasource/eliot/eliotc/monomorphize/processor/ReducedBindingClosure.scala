package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
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

import scala.collection.mutable

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
    * binding. It is driven by [[EscalatingReducer.escalatingLoop]]'s laziness (a *stuck* term escalates). Always the compiler track: a compile-time reduction never
    * reaches a runtime target.
    */
  def reduceInstance(
      vfqn: ValueFQN,
      typeArguments: Seq[GroundValue]
  ): CompilerIO[Option[SemValue]] =
    getFactIfProduced(CompilerMonomorphicValue.Key(vfqn, typeArguments)).flatMap {
      case Some(cmv) =>
        cmv.reduced.traverse(r => buildBinding(vfqn, r.value, Platform.Compiler, deep = true))
      case None      => None.pure[CompilerIO]
    }

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
      linked <- collectBindings(reduced, vfqn, platform, deep)
    } yield VTopDef(
      vfqn,
      Some(Lazy {
        val evaluator = new MonomorphicEvaluator(linked.lookup(Map.empty))
        evaluator.eval(Env.empty, MonomorphicExpression(GroundValue.Type, linked.body))
      }),
      Spine.SNil
    )

  /** A reduced body with the bindings of its value references, ready to evaluate. A [[MonomorphicEvaluator]] looks a
    * reference up by its FQN alone, so when [[collectBindings]] links a callee reduced at two instantiations, each
    * instantiation's references are renamed apart in `body` (`f#0`, `f#1`) and `instances` answers each new name with
    * the reference it stands for — the scheme `PostDrainQuoter.reduceThreaded` uses for the same reason.
    *
    * @param bindings
    *   The binding of each reference in `body`, by the name it has there.
    * @param instances
    *   Each renamed reference's original FQN and ground type arguments.
    */
  case class Linked(
      body: MonomorphicExpression.Expression,
      bindings: Map[ValueFQN, SemValue],
      instances: Map[ValueFQN, (ValueFQN, Seq[GroundValue])]
  ) {

    /** The lookup evaluating `body`: `extra` first, then the linked bindings. A renamed reference left unlinked (an
      * ancestor on the fact chain) is the stuck definition of its original name, exactly what the evaluator makes of an
      * unbound reference — never a definition named after the instance.
      */
    def lookup(extra: Map[ValueFQN, SemValue]): ValueFQN => Option[SemValue] =
      name =>
        extra
          .get(name)
          .orElse(bindings.get(name))
          .orElse(instances.get(name).map { case (original, _) => VTopDef(original, None, Spine.SNil) })
  }

  /** Collect the binding of every value reference in the reduced body, skipping the value's own FQN and any FQN already
    * an ancestor on the active fact-request chain (the same mutual-recursion guard [[BindingClosure]] uses). By default
    * each dependency is its raw [[NativeBinding]] body (a one-hop closure), which is the same at every instantiation.
    *
    * When `deep` (the escalation fetch), a dependency is instead taken **reduced at its own instantiation**
    * ([[reduceInstance]], recursively, against the reference's ground type arguments), so a **stacked** carrier
    * resolves through every layer: when the reduced body of a *function* stays structural (its impl-dispatch
    * dependencies close over its runtime value parameters, e.g. `else`/`guardOr`), each such dependency
    * (`AbortCarrier`'s `Effect`/`Abort` impl over the base `Either`) still gets its own nested base ability resolved
    * rather than closing over the raw, still-abstract binding. `MonomorphicEvaluator` drops a reference's type
    * arguments, so the already-instantiated reduced dependency is used directly with no re-application. A dependency
    * with no reduced form (a native leaf, a `data` constructor) falls back to its raw [[NativeBinding]] body.
    *
    * A deep callee referenced at two instantiations is two bindings, so its references are renamed apart ([[Linked]]):
    * keyed by FQN, `pickOf[BigInteger]` and `pickOf[String]` were one binding, the last linked, and a `where` over both
    * read the wrong implementation for one of them (`docs/effects.md` §8 item 49).
    */
  private[processor] def collectBindings(
      reduced: MonomorphicExpression.Expression,
      selfFqn: ValueFQN,
      platform: Platform,
      deep: Boolean
  ): CompilerIO[Linked] = {
    val (body, instances) =
      if (deep) instanceNamed(reduced)
      else (reduced, Map.empty[ValueFQN, (ValueFQN, Seq[GroundValue])])
    val references        = valueReferences(body).toList.map { case (name, typeArgs) =>
      (name, instances.getOrElse(name, (name, typeArgs)))
    }
    references
      .filterNot(_._2._1 == selfFqn)
      .foldLeftM(Map.empty[ValueFQN, SemValue]) { case (acc, (name, (fqn, typeArgs))) =>
        activeFactKeys.flatMap { ancestors =>
          if (ancestors.contains(NativeBinding.Key(fqn, platform))) acc.pure[CompilerIO]
          else {
            def raw: CompilerIO[Map[ValueFQN, SemValue]] =
              getFactIfProduced(NativeBinding.Key(fqn, platform)).map {
                case Some(binding) => binding.semValue.fold(acc)(sv => acc + (name -> sv))
                case None          => acc
              }
            if (deep)
              reduceInstance(fqn, typeArgs).flatMap {
                case Some(reduced) => (acc + (name -> reduced)).pure[CompilerIO]
                case None          => raw
              }
            else raw
          }
        }
      }
      .map(Linked(body, _, instances))
  }

  /** Rename apart the references to every FQN `expr` references at more than one instantiation, as [[Linked]]
    * describes. A reference to an FQN at a single instantiation keeps its name.
    */
  private def instanceNamed(
      expr: MonomorphicExpression.Expression
  ): (MonomorphicExpression.Expression, Map[ValueFQN, (ValueFQN, Seq[GroundValue])]) = {
    val ambiguous =
      valueReferences(expr).toSeq.groupBy(_._1).collect { case (fqn, instances) if instances.size > 1 => fqn }.toSet
    val names     = mutable.LinkedHashMap.empty[(ValueFQN, Seq[GroundValue]), ValueFQN]
    def rename(expr: MonomorphicExpression.Expression): MonomorphicExpression.Expression = expr match {
      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) if ambiguous.contains(vfqn.value) =>
        val name = names.getOrElseUpdate(
          (vfqn.value, typeArgs),
          vfqn.value.copy(name = vfqn.value.name.copy(name = s"${vfqn.value.name.name}#${names.size}"))
        )
        MonomorphicExpression.MonomorphicValueReference(vfqn.as(name), typeArgs)
      case MonomorphicExpression.FunctionApplication(target, argument)                                             =>
        MonomorphicExpression.FunctionApplication(renamed(target), renamed(argument))
      case MonomorphicExpression.FunctionLiteral(paramName, paramType, body)                                     =>
        MonomorphicExpression.FunctionLiteral(paramName, paramType, renamed(body))
      case other                                                                                                 => other
    }
    def renamed(node: Sourced[MonomorphicExpression]): Sourced[MonomorphicExpression] =
      node.map(n => n.copy(expression = rename(n.expression)))
    (rename(expr), names.map { case (instance, name) => name -> instance }.toMap)
  }

  private[processor] def valueReferences(
      expr: MonomorphicExpression.Expression
  ): Set[(ValueFQN, Seq[GroundValue])] = expr match {
    case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) => Set((vfqn.value, typeArgs))
    case MonomorphicExpression.FunctionApplication(target, argument)     =>
      valueReferences(target.value.expression) ++ valueReferences(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)               => valueReferences(body.value.expression)
    case _                                                               => Set.empty
  }
}
