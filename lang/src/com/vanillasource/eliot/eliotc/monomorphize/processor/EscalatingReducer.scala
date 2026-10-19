package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.{Env, MetaStore, SemValue}
import com.vanillasource.eliot.eliotc.monomorphize.eval.{Evaluator, MonomorphicEvaluator, Quoter}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, GroundValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*

/** The compiler platform's post-monomorphize **linker-executor** — the `used`-equivalent the runtime platform already
  * has (`docs/refinement-channel-follow-ups.md` §1). It evaluates a compiler-track value applied to concrete argument
  * metas and enforces the invariant that *post-monomorphize evaluation may only ever see monomorphized bodies*: a raw
  * (operator-resolved) callee body may appear only beneath a native leaf, never spliced into the reduction. The cheap
  * common case (a transfer bottoming at natives) pays nothing new; when the one-hop raw closure sticks on an ability
  * reference that a monomorphization would have exchanged for its exact impl, the loop *escalates* — it re-links each
  * stuck callee's own monomorphized (reduced-at-instantiation) form and re-evaluates.
  *
  * The stuck-driven loop skeleton ([[escalatingLoop]] + [[reducibleStuck]] + [[escalate]]) is the **one** mechanism the
  * in-checker read-back ([[com.vanillasource.eliot.eliotc.monomorphize.check.PostDrainQuoter]]) and this channel-facing
  * executor share: each instantiates it with its own (eval function, escalation fetch), so they are one mechanism, not
  * siblings. Each fetched reduced form was itself produced by a read-back that ran this same loop, so the recursion
  * lives in the cached, `activeFactKeys`-guarded fact graph rather than in a closure-composition flag.
  *
  * '''Not the only seam for §1's invariant.''' [[CompilerNativesProcessor]] enforces the same "monomorphized bodies
  * only" invariant *eagerly*, at a different seam: it contributes a refinement artifact's reduced
  * [[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue]] as a self-contained
  * [[com.vanillasource.eliot.eliotc.monomorphize.fact.BindingContribution.Leaf]] (never the raw body) so a
  * checking-time evaluation — which is pure and cannot demand facts mid-eval — already closes over the monomorphized
  * form. The two cannot be merged (that purity boundary is exactly why one is eager and this one lazy); they are the
  * eager and lazy halves of the one invariant, not redundant.
  */
object EscalatingReducer {

  /** Reduce compiler-track value `vfqn` at concrete `typeArguments`, applied to `argMetas`, to a ground value — linking
    * only monomorphized callees.
    *
    *   1. fetch `CompilerMonomorphicValue(vfqn, typeArguments)`'s reduced body;
    *   1. link each of its value references **reduced at its own ground type arguments**
    *      ([[ReducedBindingClosure.collectBindings]], `deep = true`): linked raw, one hop at a time, a `where` over
    *      `rangeWithin[0, 255](range(x))` does not evaluate (`WhereOnDefIntegrationTest`);
    *   1. evaluate the reduced body, apply `argMetas`, and `renormalize` (re-fires natives; empty metastore), and quote.
    *
    * [[None]] when the value produced no `CompilerMonomorphicValue`, has no reduced body, or the final result does not
    * reduce to a quotable ground form.
    */
  def reduceApplied(
      vfqn: ValueFQN,
      typeArguments: Seq[GroundValue],
      argMetas: Seq[SemValue]
  ): CompilerIO[Option[GroundValue]] =
    getFactIfProduced(CompilerMonomorphicValue.Key(vfqn, typeArguments)).flatMap {
      case Some(CompilerMonomorphicValue(_, _, _, _, Some(reduced), _)) =>
        // Every callee is linked reduced at its own instantiation from the start, so there is nothing left to
        // escalate to.
        ReducedBindingClosure.collectBindings(reduced.value, vfqn, Platform.Compiler, deep = true).flatMap { linked =>
          def evalWith(extra: Map[ValueFQN, SemValue]): SemValue = {
            val lookup  = linked.lookup(extra)
            val body    =
              new MonomorphicEvaluator(lookup).eval(Env.empty, MonomorphicExpression(GroundValue.Type, linked.body))
            val applied = argMetas.foldLeft(body)(Evaluator.applyValue)
            Evaluator.renormalize(applied, MetaStore.empty, lookup, deep = true)
          }
          escalatingLoop(
            MetaStore.empty,
            evalWith,
            _ => Map.empty[ValueFQN, SemValue].pure[CompilerIO],
            channelStuck
          ).map(result => Quoter.quote(0, result, MetaStore.empty).toOption.filterNot(isAbstractAbilityStructure))
        }
      case _                                                         => none[GroundValue].pure[CompilerIO]
    }

  /** The channel instantiation of [[escalatingLoop]]'s stuck predicate: [[reducibleStuck]] *plus* a stuck abstract-
    * ability application (which quotes to a `GroundValue.Structure` headed by a [[Qualifier.Ability]] method). After
    * the linker fix this path is unreachable — abilities are resolved before the channel sees them — but were one ever
    * to survive, treating it as stuck makes the loop escalate rather than accept the bogus structure; if it still does
    * not resolve, [[reduceApplied]]'s [[isAbstractAbilityStructure]] filter drops it to ⊤ (never a structure). Quotes
    * once, mirroring [[reducibleStuck]], so the hot common path pays no extra cost
    * (`docs/refinement-channel-follow-ups.md` §2.5).
    */
  private def channelStuck(forced: SemValue, metaStore: MetaStore): Boolean = forced match {
    case SemValue.VLam(_, _)                                    => false
    case SemValue.VNeutral(SemValue.NeutralHead.Param(_, _), _) => false
    case SemValue.VNeutral(SemValue.NeutralHead.Fresh(_, _), _) => false
    case other                                                  =>
      Quoter.quote(0, other, metaStore) match {
        case Left(_)   => true
        case Right(gv) => isAbstractAbilityStructure(gv)
      }
  }

  /** Whether a quoted ground value is a structure headed by an abstract [[Qualifier.Ability]] method — a bogus
    * refinement result the channel must never store (§2.5). A legitimate channel meta is a `$Meta` structure
    * ([[Qualifier.Type]]) or a `Direct`, never ability-qualified, so this drops only bogus values.
    */
  private def isAbstractAbilityStructure(gv: GroundValue): Boolean = gv match {
    case GroundValue.Structure(name, _, _) =>
      name.name.qualifier match {
        case _: Qualifier.Ability => true
        case _                    => false
      }
    case _                                 => false
  }

  /** The shared stuck-driven escalation loop. Evaluate with the current escalated bindings; if the forced result is not
    * `stuck` (against `metaStore`) return it — the common case pays nothing. If it is stuck, `fetch` more bindings
    * (keyed by what has already been escalated) and loop until it is unstuck or no new binding is available.
    *
    * `stuck` defaults to [[reducibleStuck]] — the in-checker read-back keeps that predicate byte-for-byte — while the
    * channel executor passes its hardened variant ([[channelStuck]]) so a bogus abstract-ability structure escalates
    * rather than being accepted (§2.5).
    */
  def escalatingLoop(
      metaStore: MetaStore,
      eval: Map[ValueFQN, SemValue] => SemValue,
      fetch: Set[ValueFQN] => CompilerIO[Map[ValueFQN, SemValue]],
      stuck: (SemValue, MetaStore) => Boolean = reducibleStuck
  ): CompilerIO[SemValue] = {
    def loop(bindings: Map[ValueFQN, SemValue]): CompilerIO[SemValue] = {
      val forced = eval(bindings)
      if (!stuck(forced, metaStore)) forced.pure[CompilerIO]
      else
        fetch(bindings.keySet).flatMap { extra =>
          if (extra.isEmpty) forced.pure[CompilerIO] else loop(bindings ++ extra)
        }
    }
    loop(Map.empty)
  }

  /** Whether a *forced* result is stuck in a way escalation could resolve. A runtime `VLam` (a compiler-track function
    * over its value parameters) and a value-parameter / read-back neutral are legitimately structural — they must fall
    * straight through to the caller, never re-fire escalation. Anything else that fails to quote (a bodied top-def, a
    * stuck `match`, a stuck native) is a candidate.
    */
  def reducibleStuck(forced: SemValue, metaStore: MetaStore): Boolean = forced match {
    case SemValue.VLam(_, _)                                    => false
    case SemValue.VNeutral(SemValue.NeutralHead.Param(_, _), _) => false
    case SemValue.VNeutral(SemValue.NeutralHead.Fresh(_, _), _) => false
    case other                                                  => Quoter.quote(0, other, metaStore).isLeft
  }

  /** [[reducibleStuck]] for a **type-level** read-back (a signature): stuck also when the result quotes but still holds
    * an ability method's call ([[holdsAbilityCall]]). A body reached transitively — `if`'s, inside a guarded return —
    * is evaluated generically, where its `abort` is not dispatched; the escape around it answers `Right` of the
    * undispatched call, which quotes. Treating that as stuck escalates to `if` reduced at its instantiation, where the
    * call is dispatched. A runtime track signature never holds such a call, so the extra quote is the only cost.
    */
  def typeLevelStuck(forced: SemValue, metaStore: MetaStore): Boolean = forced match {
    case SemValue.VLam(_, _)                                    => false
    case SemValue.VNeutral(SemValue.NeutralHead.Param(_, _), _) => false
    case SemValue.VNeutral(SemValue.NeutralHead.Fresh(_, _), _) => false
    case other                                                  =>
      Quoter.quote(0, other, metaStore) match {
        case Left(_)   => true
        case Right(gv) => holdsAbilityCall(gv)
      }
  }

  /** Whether a ground value holds an ability **method**'s call anywhere: an evaluation that dispatched nothing, not a
    * value. An ability's *marker* is named after the ability itself and is not a call.
    */
  def holdsAbilityCall(gv: GroundValue): Boolean = gv match {
    case GroundValue.Structure(name, args, _) =>
      (name.name.qualifier match {
        case Qualifier.Ability(ability) => name.name.name != ability
        case _                          => false
      }) || args.exists(holdsAbilityCall)
    case GroundValue.Param(_, args, _)        => args.exists(holdsAbilityCall)
    case _                                    => false
  }

  /** Fetch the *reduced-at-instantiation* binding for each `candidate` value reference not yet escalated (`already`)
    * and not an ancestor on the active fact-request chain (would dead-lock). Each fetched form is post-processed by
    * `wrap` (the in-checker read-back wraps in ignore-lambdas to absorb the reference's type arguments; the
    * [[MonomorphicEvaluator]] path drops type arguments already, so it passes the reduced form through unchanged).
    * Keyed by FQN; one instantiation per FQN.
    *
    * A **monomorphic** callee (empty ground type arguments) is escalated too — `wrap(0, sem) = sem` splices its reduced
    * body directly. It needs escalation as much as a generic one: a runtime-*concrete* (borrowed-body) monomorphic
    * callee performing ability dispatch — which the refinement channel's transfers reach — has no
    * `CompilerNativesProcessor` `Leaf` and must be linked at its own `CompilerMonomorphicValue(fqn, [])`. This is
    * sound: escalation only ever fires on a [[reducibleStuck]] term (splicing more reduced bodies can make it quote,
    * never change an already-quoting result), a native leaf / `data` constructor still returns `None` (unchanged), and
    * the `CompilerMonomorphicValue.Key(fqn, [])` ancestor check still guards cycles.
    *
    * '''Ambiguous-FQN decline (§2.3).''' The binding map and both evaluators' lookups are keyed by **FQN only**, so an
    * FQN referenced at ≥2 *distinct* ground-arg vectors cannot be served correctly — one instantiation's reduced body
    * (its ability-impl choices baked in) would silently stand in for the other. Such an FQN is therefore **declined**
    * (never escalated): the term stays stuck (a loud error on the checker read-back, a sound ⊤ on the channel path)
    * rather than splicing an arbitrary winner. An FQN referenced at a single instantiation (however many times) is
    * escalated as before.
    */
  def escalate(
      candidates: Seq[(ValueFQN, Seq[GroundValue])],
      already: Set[ValueFQN],
      reduceInstance: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[SemValue]],
      wrap: (Int, SemValue) => SemValue
  ): CompilerIO[Map[ValueFQN, SemValue]] =
    activeFactKeys.flatMap { ancestors =>
      val unambiguous = candidates.distinct.groupBy(_._1).collect { case (_, Seq(single)) => single }.toSeq
      unambiguous.foldLeftM(Map.empty[ValueFQN, SemValue]) { case (acc, (fqn, groundArgs)) =>
        if (already.contains(fqn) || ancestors.contains(CompilerMonomorphicValue.Key(fqn, groundArgs)))
          acc.pure[CompilerIO]
        else
          reduceInstance(fqn, groundArgs).map {
            case Some(sem) => acc + (fqn -> wrap(groundArgs.size, sem))
            case None      => acc
          }
      }
    }
}
