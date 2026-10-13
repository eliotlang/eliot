package com.vanillasource.eliot.eliotc.module.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.platform.Platform
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
      // An implementation that takes parameters is one only the runtime track can run: its environment is threaded at
      // the `WovenValue` seam, which the compile track has no counterpart of. Where the compile track has its own
      // parameterless implementation of the same ability and pattern — the compiler overlay's `Abort` beside the jvm
      // layer's, which the compile pool borrows — the parameterised one is no implementation there at all, so the two
      // never compete (`docs/effects.md` §11, D20c option 5). Where it has none, the borrowed one stays what it was: a
      // name a reference can resolve to, whose body reaches a platform native and is stuck if the evaluator ever runs it.
      val parameterized   = keys.collect {
        case QualifiedName(ImplementationParameters.name, qualifier: Qualifier.AbilityImplementation, Role.Runtime) =>
          qualifier
      }.toSet
      val qualifiers      = keys.collect {
        case QualifiedName(_, qualifier: Qualifier.AbilityImplementation, Role.Runtime) => qualifier
      }.toSet
      val superseded      = parameterized.filter { qualifier =>
        key.platform == Platform.Compiler && qualifiers.exists(other =>
          !parameterized.contains(other) && other.name == qualifier.name &&
            other.implementationName == qualifier.implementationName &&
            qualifier.pattern.startsWith(other.pattern) && qualifier.pattern.drop(other.pattern.length).startsWith("(")
        )
      }
      val implementations = keys.collect {
        case qn @ QualifiedName(
              methodName,
              qualifier @ Qualifier.AbilityImplementation(abilityName, pattern, implementationName),
              Role.Runtime
            ) if !ImplementationParameters.isAuxiliary(methodName) && !superseded.contains(qualifier) =>
          // An implementation's parameters and binder declarations are looked up by name, never one of its clauses.
          ModuleAbilities.Impl(ValueFQN(key.moduleName, qn), methodName, abilityName, pattern, implementationName)
      }
      ModuleAbilities(key.moduleName, declaredMethods, implementations, key.platform)
    }
}
