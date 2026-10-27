package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.check.{MarkerGuardSignature, Track, TypeStackLoop}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, GroundValue, MonomorphicValue, NativeBinding}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue

/** Entry point for NbE-based type checking (monomorphize). Delegates to TypeStackLoop for the actual type checking
  * work. Reads the [[SaturatedValue]] (the operator-resolved value with parameter-position bare omittable references
  * generalized into explicit generic binders), not the raw `OperatorResolvedValue`.
  *
  * A handle's type is an ordinary type argument of the key, and every effect operation is an ability method the checker
  * dispatches by it (`docs/effects.md` D36), so the checked value is the specialisation everything downstream reads.
  */
class MonomorphicTypeCheckProcessor
    extends TransformationProcessor[SaturatedValue.Key, MonomorphicValue.Key](key => SaturatedValue.Key(key.vfqn)) {

  /** A callee's binding. A callee whose own definition was rejected has none, on either track, and is skipped: the
    * checker names it where it is referenced (*"Cannot use 'x' here: its own definition was rejected."*), while an
    * abort here would stop this check before anything did.
    */
  private def fetchBinding(vfqn: ValueFQN): CompilerIO[Option[SemValue]] =
    getFactIfProduced(NativeBinding.Key(vfqn, Platform.Runtime)).map(_.flatMap(_.semValue))

  override protected def generateFromKeyAndFact(
      key: MonomorphicValue.Key,
      saturatedValue: SaturatedValue
  ): CompilerIO[MonomorphicValue] = {
    // An ability-implementation marker is monomorphized only to discharge its `where` guard (ability-guards §2.3); its
    // pattern-argument types are not real value parameters, so they are stripped to leave binders + guard return.
    val value = MarkerGuardSignature.strippedForGuard(saturatedValue.value)
    for {
      _                 <- BindingHints.hint(value.runtime, Platform.Runtime)
      // Read the value's *own* signature reduced to ground from its signature twin's compiler-track mono (types are
      // compile-time, so the signature is authoritative there) rather than walking it in place — the twin is
      // **mandatory** at every arity (signature-unification C1/C2): a partial-arity key reads a *parametric* signature
      // (leftover binders as `GroundValue.Param`s) which `establishSignature` re-inflates to fresh metas. A missing twin
      // means its own mono already reported the signature's errors, so aborting here is the correct decline (no in-place
      // re-check, no double reporting). Acyclic: `CompilerMonomorphicValue` never reads back a runtime `MonomorphicValue`.
      injectedSignature <-
        getFactOrAbort(
          CompilerMonomorphicValue.Key(key.vfqn.copy(name = key.vfqn.name.signatureTwin), key.typeArguments)
        ).map(cmv => Some(cmv.signature))
      result            <- TypeStackLoop.process(
                             key.typeArguments,
                             value,
                             fetchBinding = fetchBinding,
                             resolveAbility = resolveAbilityImpl,
                             track = Track.Runtime,
                             reduceInstance = ReducedBindingClosure.reduceInstance(_, _),
                             injectedSignature = injectedSignature,
                             readHanded = Some((callee, arguments) =>
                               getFactOrAbort(MonomorphicValue.Key(callee, arguments)).map(_.handed)
                             )
                           )
    } yield MonomorphicValue(
      key.vfqn,
      key.typeArguments,
      value.signature.as(key.vfqn.name),
      result.signature,
      result.body,
      result.handed
    )
  }

  private def resolveAbilityImpl(
      vfqn: ValueFQN,
      typeArgs: Seq[GroundValue]
  ): CompilerIO[Option[(ValueFQN, Seq[GroundValue])]] =
    getFactIfProduced(AbilityImplementation.Key(vfqn, typeArgs, Platform.Runtime)).map(
      _.flatMap(_.resolution.resolved)
    )
}
