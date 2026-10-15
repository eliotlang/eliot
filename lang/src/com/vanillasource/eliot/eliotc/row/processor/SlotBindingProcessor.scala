package com.vanillasource.eliot.eliotc.row.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.Qualifier as ResolveQualifier
import com.vanillasource.eliot.eliotc.row.{BindingWriter, RunWithHoister, SlotBindings}
import com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue
import com.vanillasource.eliot.eliotc.source.content.Sourced
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
      named     <- RunWithHoister.candidates(value, known).toSeq.traverse { marker =>
                     getFactIfProduced(OperatorResolvedValue.Key(marker, key.platform)).map(_.flatMap { orv =>
                       orv.name.value.qualifier match {
                         case ResolveQualifier.AbilityImplementation(ability, _, _) =>
                           Some((marker, ability, orv.effectRow.returnEffects, BindingWriter.freeParameters(orv)))
                         case _                                                    => None
                       }
                     })
                   }
      hoisted    = RunWithHoister.hoist(
                     value,
                     known,
                     named.flatten.map((marker, ability, _, _) => marker -> ability).toMap,
                     named.flatten.map((marker, _, clauses, _) => marker -> clauses).toMap
                   )
      unbindable = slotFreeParameters(hoisted.value, named.flatten.map((marker, _, _, free) => marker -> free).toMap)
      _         <- (hoisted.errors ++ unbindable) match {
                     case Seq()  => ().pure[CompilerIO]
                     case errors =>
                       errors
                         .traverse_ { case (message, help) => compilerError(message, help) }
                         .whenA(key.platform == Platform.Runtime) >> abort[Unit]
                   }
    } yield SlotBoundValue(hoisted.value)
  }

  /** A slot may not name an implementation with a type parameter its pattern does not take (`docs/effects.md` D28),
    * whether written there or moved there from a run. Only the arguments of a `with` determine such a parameter, and
    * those are computed in this definition's body, while the code the slot binds is compiled by the caller, which
    * reads only this declaration — so nothing could ever tell the caller what it is.
    */
  private def slotFreeParameters(
      value: OperatorResolvedValue,
      free: Map[ValueFQN, Seq[String]]
  ): Seq[(Sourced[String], Seq[String])] =
    SignatureView
      .of(value.signature)
      .parameters
      .flatMap(tpe => SlotBindings.of(tpe.value))
      .map(_.implementation)
      .distinct
      .flatMap { implementation =>
        free.get(implementation.value).filter(_.nonEmpty).map { parameters =>
          val name = implementation.value.name.qualifier match {
            case Qualifier.AbilityImplementation(_, _, Some(named)) => named
            case _                                                 => implementation.value.name.name
          }
          (
            implementation.as(
              s"The implementation '$name' cannot bind the code a caller wrote: only the arguments of a `with` " +
                s"determine its type parameter${if (parameters.size == 1) "" else "s"} " +
                s"${parameters.map(p => s"'$p'").mkString(", ")}, which its pattern does not take."
            ),
            Seq(s"Bind it where that code is written, with a `with` around it at the caller.")
          )
        }
      }
}
