package com.vanillasource.eliot.eliotc.monomorphize.specialise

import com.vanillasource.eliot.eliotc.monomorphize.fact.{CheckedValue, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor

/** Produces a [[MonomorphicValue]] — the specialisation everything downstream reads — from the value the checker
  * checked with its effect bindings left out ([[CheckedValue]], [[EffectBindings]]), with every binding written and
  * every effect operation dispatched from the evidence in scope ([[EvidenceSpecialisation]], `docs/effects.md` D35
  * step 4). The effect entries are the checked value's, and so is the signature but for one block per variant of a slot's runs
  * (`docs/effects.md` D35 step 5a): no binding reaches either. What the runs of
  * each code parameter give is read off the specialised body ([[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Slot.variants]]).
  *
  * On the new effect path (`handles`, `docs/effects.md` D36) there is nothing to put back: an effect binding is a
  * handle's type, an ordinary type argument the checker checks the value at, and each operation is an ability method
  * the checker dispatched by that type. The specialisation is the checked value itself.
  */
class SpecialisationProcessor(handles: Boolean = false) extends SingleKeyTypeProcessor[MonomorphicValue.Key] {

  override protected def generateFact(key: MonomorphicValue.Key): CompilerIO[Unit] =
    if (handles)
      getFactOrAbort(CheckedValue.Key(key.vfqn, key.typeArguments)).flatMap(checked =>
        registerFactIfClear(
          MonomorphicValue(key.vfqn, key.typeArguments, checked.name, checked.signature, checked.runtime, checked.effects)
        )
      )
    else
      for {
        erased  <- EffectBindings.erased(key.vfqn, key.typeArguments)
        checked <- getFactOrAbort(CheckedValue.Key(key.vfqn, erased))
        result  <- EvidenceSpecialisation.specialise(checked, key.typeArguments)
        _       <- registerFactIfClear(
                     MonomorphicValue(key.vfqn, key.typeArguments, checked.name, result.signature, result.body, result.effects)
                   )
      } yield ()
}
