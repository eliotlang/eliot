package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, ReducedInstance}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

/** Links a compiler-track instance's reduced body once, for every reader ([[ReducedInstance]]).
  *
  * The fact is total: an instance with no reduced body is [[ReducedInstance.Link.Absent]], and one whose link met the
  * request chain is [[ReducedInstance.Link.Unshared]], both answers rather than declines, so a warm build never
  * regenerates a decline. The value's own [[CompilerMonomorphicValue]] being in progress up the chain is such a meeting:
  * the engine would refuse the read, and that refusal is not the value's absence.
  */
class ReducedInstanceProcessor extends SingleFactProcessor[ReducedInstance.Key] {

  override def generateSingleFact(key: ReducedInstance.Key): CompilerIO[ReducedInstance] = {
    val valueKey = CompilerMonomorphicValue.Key(key.vfqn, key.typeArguments)
    activeFactKeys
      .flatMap { ancestors =>
        if (ancestors.contains(valueKey)) ReducedInstance.Link.Unshared.pure[CompilerIO]
        else
          getFactIfProduced(valueKey).flatMap {
            case Some(CompilerMonomorphicValue(_, _, _, _, Some(reduced), _)) =>
              ReducedBindingClosure.linkShared(reduced.value, key.vfqn).map {
                case Some(linked) =>
                  ReducedInstance.Link.Shared(linked, ReducedBindingClosure.bindingOf(key.vfqn, linked))
                case None         => ReducedInstance.Link.Unshared
              }
            case _                                                         => ReducedInstance.Link.Absent.pure[CompilerIO]
          }
      }
      .map(ReducedInstance(key.vfqn, key.typeArguments, _))
  }
}
