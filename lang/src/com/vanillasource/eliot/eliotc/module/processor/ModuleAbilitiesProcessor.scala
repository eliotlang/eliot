package com.vanillasource.eliot.eliotc.module.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  QualifiedName,
  Qualifier,
  Role,
  UnifiedModuleNames,
  ValueFQN
}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

/** Builds [[ModuleAbilities]] for a module by decoding the `Qualifier.Ability` (declared ability methods) and
  * `Qualifier.AbilityImplementation` (implementation methods) names in its [[UnifiedModuleNames]] into structured
  * records. The one place that knows the ability-marker name encoding.
  */
class ModuleAbilitiesProcessor extends SingleFactProcessor[ModuleAbilities.Key] {

  override protected def generateSingleFact(key: ModuleAbilities.Key): CompilerIO[ModuleAbilities] =
    getFactOrAbort(UnifiedModuleNames.Key(key.moduleName, key.platform)).map { names =>
      // Only `Runtime`-role names are real ability methods/implementations; a name's `Signature` twin (the
      // signature-split compile-time value) shares its qualifier but is not an ability surface member, so it is
      // excluded here by matching `Role.Runtime`.
      val keys            = names.names.keys.toSeq
      val declaredMethods = keys.collect { case qn @ QualifiedName(_, Qualifier.Ability(abilityName), Role.Runtime) =>
        ModuleAbilities.DeclaredMethod(ValueFQN(key.moduleName, qn), abilityName)
      }
      val implementations = keys.collect {
        case qn @ QualifiedName(
              methodName,
              Qualifier.AbilityImplementation(abilityName, pattern, implementationName),
              Role.Runtime
            ) =>
          ModuleAbilities.Impl(ValueFQN(key.moduleName, qn), methodName, abilityName, pattern, implementationName)
      }
      ModuleAbilities(key.moduleName, declaredMethods, implementations, key.platform)
    }
}
