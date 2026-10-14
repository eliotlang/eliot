package com.vanillasource.eliot.eliotc.row.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.Qualifier as ResolveQualifier
import com.vanillasource.eliot.eliotc.row.RunWithHoister
import com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError
import com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue

/** Moves the bindings of a value's runs of a code parameter onto its slots ([[RunWithHoister]]), on the declaration
  * every caller reads, because a caller compiles the code it hands over against that declaration.
  *
  * It reads the bound declarations of the callees a run is passed to, which is why it sits after the recursion gate:
  * the value is certified not to reach itself, so none of those reads leads back to it. A callee with no bound
  * declaration — one the gate or an earlier phase rejected, with its own error — carries nothing. A disagreement is
  * reported on the runtime track only, as the `row` phase reports, and aborts this value on both.
  */
class SlotBindingProcessor
    extends TransformationProcessor[RecursionCheckedValue.Key, SlotBoundValue.Key](key =>
      RecursionCheckedValue.Key(key.vfqn, key.platform)
    ) {

  override protected def generateFromKeyAndFact(
      key: SlotBoundValue.Key,
      checked: RecursionCheckedValue
  ): CompilerIO[SlotBoundValue] = {
    val value = checked.value
    for {
      callees   <- RunWithHoister.passedTo(value).toSeq.traverse { callee =>
                     getFactIfProduced(SlotBoundValue.Key(callee, key.platform)).map(_.map(callee -> _.value))
                   }
      known      = callees.flatten.toMap
      abilities <- RunWithHoister.candidates(value, known).toSeq.traverse { marker =>
                     getFactIfProduced(OperatorResolvedValue.Key(marker, key.platform)).map(_.flatMap {
                       _.name.value.qualifier match {
                         case ResolveQualifier.AbilityImplementation(ability, _, _) => Some(marker -> ability)
                         case _                                                    => None
                       }
                     })
                   }
      hoisted    = RunWithHoister.hoist(value, known, abilities.flatten.toMap)
      _         <- hoisted.errors match {
                     case Seq()  => ().pure[CompilerIO]
                     case errors =>
                       errors
                         .traverse_ { case (message, help) => compilerError(message, help) }
                         .whenA(key.platform == Platform.Runtime) >> abort[Unit]
                   }
    } yield SlotBoundValue(hoisted.value)
  }
}
