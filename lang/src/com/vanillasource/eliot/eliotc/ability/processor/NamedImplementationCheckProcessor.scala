package com.vanillasource.eliot.eliotc.ability.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.NamedImplementationCheck
import com.vanillasource.eliot.eliotc.ability.util.ImplementationConformance
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.Qualifier as ResolveQualifier
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

/** Checks a **named** implementation against its ability ([[ImplementationConformance]]). It is demanded where a
  * binding dispatches to the implementation (`AbilityResolver`), since a named implementation is never searched and so
  * never reaches [[AbilityImplementationCheckProcessor]].
  *
  * Two things differ from an anonymous implementation's check. A clause receives the implementation's parameters ahead
  * of its member's own ([[ImplementationParameters]]), so they are dropped before comparing. And an implementation that
  * declares no clause at all — the base's `implement standardConsole: Console`, which each platform layer bodies — is
  * a declaration, not an incomplete implementation: where no layer on the path bodies it, the clause a reference needs
  * is reported at that reference.
  */
class NamedImplementationCheckProcessor extends SingleKeyTypeProcessor[NamedImplementationCheck.Key] {

  override protected def generateFact(key: NamedImplementationCheck.Key): CompilerIO[Unit] =
    for {
      marker     <- getFactOrAbort(OperatorResolvedValue.Key(key.marker, key.platform))
      ability    <- marker.name.value.qualifier match {
                      case ResolveQualifier.AbilityImplementation(ability, _, _) => ability.pure[CompilerIO]
                      case _                                                     =>
                        compilerAbort(marker.name.as("Internal: an implementation's marker is not an implementation's."))
                    }
      abilities  <- getFactOrAbort(ModuleAbilities.Key(key.marker.moduleName, key.platform))
      clauses     = abilities.implementations.map(_.vfqn).filter(_.name.qualifier == key.marker.name.qualifier)
      methods    <- clauses.traverse(ImplementationConformance.method(_, key.platform))
      members    <- ImplementationConformance.abilityMethods(ability, key.platform)
      parameters <- parameterCount(key.marker, key.platform)
      _          <- ImplementationConformance.check(
                      ability,
                      members,
                      methods,
                      key.platform,
                      complete = clauses.exists(_ != key.marker),
                      leadingParameters = parameters
                    )
      _          <- registerFactIfClear(NamedImplementationCheck(key.marker, key.platform))
    } yield ()

  /** How many parameters the implementation takes: those of its parameters declaration, which it has only when it
    * takes any.
    */
  private def parameterCount(marker: ValueFQN, platform: Platform): CompilerIO[Int] = {
    val declaration = ImplementationParameters.of(marker)
    getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, platform)).flatMap { names =>
      if (names.names.contains(declaration.name))
        getFactOrAbort(OperatorResolvedValue.Key(declaration, platform)).map(declared =>
          SignatureView.of(declared.signature).parameters.size
        )
      else 0.pure[CompilerIO]
    }
  }
}
