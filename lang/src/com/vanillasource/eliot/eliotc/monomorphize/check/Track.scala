package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The per-track strategy for the two monomorphization tracks (runtime / compiler). It carries the track's [[Platform]]
  * (so fact keys read the platform off the track rather than a bare threaded value) plus the two places the checking
  * core genuinely differs between the two tracks, each extracted 1:1 from a former `platform match` conditional in
  * [[TypeStackLoop]]:
  *
  *   - [[implBindings]] — the compiler track's resolved-impl binding fetch, folded into the read-back evaluator's lookup
  *     (empty on the runtime track, whose body stays structural for codegen);
  *   - [[readBackBody]] — reduce the compile-time body ([[PostDrainQuoter.reduceSourced]]) on the compiler track, or
  *     keep it structural ([[PostDrainQuoter.quoteSourced]]) on the runtime track.
  *
  * The return-position settle is no longer a track hook: [[TypeStackLoop.settleAtRead]] reads the value's re-inflated
  * ground signature and settles it directly, branching on [[platform]] once for the guard's runtime-discharge vs
  * compiler-pass-through asymmetry (signature-unification C1). Everything else in the checking core is track-agnostic.
  * A hook receives exactly the collaborators / primitives it needs (the [[Checker]] for the two that call checker
  * methods, `fetchBinding` / the [[PostDrainQuoter]] for the read side); state access is via [[CheckIO]].
  */
sealed trait Track {
  def platform: Platform

  /** The resolved-impl bindings to merge ahead of the checker's binding cache for the read-back evaluator (compiler
    * track only; empty on runtime).
    */
  def implBindings(fetchBinding: ValueFQN => CompilerIO[Option[SemValue]]): CheckIO[Map[ValueFQN, SemValue]]

  /** Read a checked body back into a [[MonomorphicExpression]] tree: reduced (compiler) or structural (runtime). */
  def readBackBody(
      quoter: PostDrainQuoter,
      srcSem: Sourced[SemExpression],
      threading: PostDrainQuoter.Threading
  ): CompilerIO[Sourced[MonomorphicExpression]]

}

object Track {

  /** The runtime track ([[Platform.Runtime]]): guarded returns are discharged at their use site, no impl bindings (the
    * body stays structural for codegen), and the body is quoted structurally.
    */
  case object Runtime extends Track {
    override val platform: Platform = Platform.Runtime

    override def implBindings(
        fetchBinding: ValueFQN => CompilerIO[Option[SemValue]]
    ): CheckIO[Map[ValueFQN, SemValue]] = pure(Map.empty)

    override def readBackBody(
        quoter: PostDrainQuoter,
        srcSem: Sourced[SemExpression],
        threading: PostDrainQuoter.Threading
    ): CompilerIO[Sourced[MonomorphicExpression]] = quoter.quoteSourced(srcSem)
  }

  /** The compiler track ([[Platform.Compiler]]): the compiler platform *is* the runner, so a compile-time guarded
    * signature is published undischarged (this track is the guard's producer), each drain-resolved ability impl's body
    * is made reachable to the read-back evaluator, and the body is reduced to a normal form.
    *
    * The `{Throw[E]}` ⤳ `Either[E]` **carrier pinning** that used to sit here went with the carrier (effects v6, F3).
    * It keyed on a `Throw[E, F]` constraint's carrier position, which no longer exists, and its inline-guard fallback
    * pinned *every* still-open higher-kinded meta to `Either[String]` — under v6 that would capture an ordinary
    * constructor-class binder. The compile track's control effects run on frames now, exactly as the runtime's do.
    */
  case object Compiler extends Track {
    override val platform: Platform = Platform.Compiler

    /** Fetch each drain-resolved ability impl's [[NativeBinding]] body once from the compiler pool, so [[readBackBody]]'s
      * reduction can fold the concrete impl body in via NbE. Missing bindings are dropped. */
    override def implBindings(
        fetchBinding: ValueFQN => CompilerIO[Option[SemValue]]
    ): CheckIO[Map[ValueFQN, SemValue]] =
      for {
        state  <- get
        result <- state.abilityResolutions.values.toList
                    .distinctBy(_._1)
                    .traverse { case (implFqn, _) => liftF(fetchBinding(implFqn)).map(_.map(implFqn -> _)) }
                    .map(_.flatten.toMap)
      } yield result

    /** Reduce the body, threaded as the value's specialisation: a control effect it runs gets its frame along the
      * declared chain (`docs/effects.md` §3.4).
      */
    override def readBackBody(
        quoter: PostDrainQuoter,
        srcSem: Sourced[SemExpression],
        threading: PostDrainQuoter.Threading
    ): CompilerIO[Sourced[MonomorphicExpression]] = quoter.reduceSourced(srcSem, Some(threading))

  }
}
