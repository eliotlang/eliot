package com.vanillasource.eliot.eliotc.module.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** A module's ability surface, decoded from the qualifier-tagged names in its [[UnifiedModuleNames]]: the methods
  * declared inside its `ability` blocks (`Qualifier.Ability`) and the methods of the implementations it provides
  * (`Qualifier.AbilityImplementation`).
  *
  * Every implementation of an ability contributes one synthetic method per ability method, each carried as a
  * `Qualifier.AbilityImplementation(abilityName, pattern, implementationName)` name; the method whose local name equals
  * the ability name is the implementation's "marker". Decoding these (and the `Qualifier.Ability` declarations) used to
  * be open-coded — the same `collect`-and-decode over the raw name map — in six places
  * (`AbilityImplementationProcessor`, `AbilityImplementationCheckProcessor`, `ImplementationMarkerUtils`,
  * `MatchDesugarUtils`, `ModuleAbilityOverlapCheckProcessor`). This fact owns that decode once; consumers query the
  * structured lists instead of re-pattern-matching the qualifier.
  *
  * @param declaredMethods
  *   One entry per method declared in an `ability` block in this module.
  * @param implementations
  *   One entry per ability-implementation method declared in the module, in name-table order.
  */
case class ModuleAbilities(
    moduleName: ModuleName,
    declaredMethods: Seq[ModuleAbilities.DeclaredMethod],
    implementations: Seq[ModuleAbilities.Impl],
    platform: Platform = Platform.Runtime
) extends CompilerFact {
  override def key(): CompilerFactKey[ModuleAbilities] = ModuleAbilities.Key(moduleName, platform)

  /** The methods declared in the body of ability `abilityName` (its interface). */
  def declaredMethodsOf(abilityName: String): Seq[ValueFQN] =
    declaredMethods.filter(_.abilityName == abilityName).map(_.vfqn)

  /** Every method of every **anonymous** implementation of `abilityName` — the two-site search's candidate set
    * (`docs/effects.md` §2): an anonymous `implement` is its pattern's default, a named one is reached only by `with`,
    * so a named implementation is never searched and never checked for overlap. Reaching for the unfiltered
    * [[implementationMethodsOf]] in a search or a coherence check is the defect this pair exists to keep out.
    */
  def anonymousImplementationMethodsOf(abilityName: String): Seq[ValueFQN] =
    implementations.filter(impl => impl.abilityName == abilityName && impl.implementationName.isEmpty).map(_.vfqn)

  /** [[anonymousImplementationMethodsOf]] narrowed to the methods called `methodName`. */
  def anonymousImplementationMethodsOf(abilityName: String, methodName: String): Seq[ValueFQN] =
    implementations
      .filter(impl =>
        impl.abilityName == abilityName && impl.methodName == methodName && impl.implementationName.isEmpty
      )
      .map(_.vfqn)

  /** The marker methods of the **anonymous** implementations of `abilityName` — what an overlap check compares. */
  def anonymousMarkersOf(abilityName: String): Seq[ValueFQN] =
    anonymousImplementationMethodsOf(abilityName, abilityName)

  /** The marker method of the implementation of `abilityName` with the given `pattern` key and implementation name (the
    * implementation's full identity, [[Qualifier.AbilityImplementation]]), if present.
    */
  def markerOf(abilityName: String, pattern: String, implementationName: Option[String]): Option[ValueFQN] =
    implementations.collectFirst {
      case impl
          if impl.abilityName == abilityName && impl.methodName == abilityName && impl.pattern == pattern &&
            impl.implementationName == implementationName =>
        impl.vfqn
    }

  /** The marker method of the **named** implementation called `implementationName` in this module (effects v6,
    * `docs/effects.md` §9.4 step 1), if present — the second half of resolving a `with name`, whose first half is the
    * keyed dictionary lookup of the implementation's name marker ([[Qualifier.Implementation]]) that decided this
    * module.
    *
    * The name alone identifies it: a name marker and its implementation are minted together by
    * [[com.vanillasource.eliot.eliotc.core.processor.NamedImplementationDesugarer]], and two named implementations in
    * one module cannot share a name — they would collide as ordinary duplicate declarations of the name marker.
    */
  def markerOfImplementationName(implementationName: String): Option[ValueFQN] =
    implementations.collectFirst {
      case impl if impl.implementationName.contains(implementationName) && impl.methodName == impl.abilityName =>
        impl.vfqn
    }
}

object ModuleAbilities {

  /** A method declared in the body of an `ability` block.
    *
    * @param vfqn
    *   The method's fully qualified name.
    * @param abilityName
    *   The declaring ability's local name.
    */
  case class DeclaredMethod(vfqn: ValueFQN, abilityName: String)

  /** A single ability-implementation method marker.
    *
    * @param vfqn
    *   The method's fully qualified name.
    * @param methodName
    *   The method's local name (equal to the ability name for the implementation's marker method).
    * @param abilityName
    *   The implemented ability's local name.
    * @param pattern
    *   The implementation's pattern key ([[Qualifier.AbilityImplementation.pattern]]) disambiguating multiple
    *   implementations of the same ability in one module.
    * @param implementationName
    *   The implementation's own name for a named `implement` ([[Qualifier.AbilityImplementation.implementationName]]),
    *   [[None]] for an anonymous default.
    */
  case class Impl(
      vfqn: ValueFQN,
      methodName: String,
      abilityName: String,
      pattern: String,
      implementationName: Option[String]
  )

  case class Key(moduleName: ModuleName, platform: Platform = Platform.Runtime)
      extends CompilerFactKey[ModuleAbilities] {
    override def valueCodec: Option[FactCodec[ModuleAbilities]] = Some(LangFactCodecs.moduleAbilitiesCodec)
  }
}
