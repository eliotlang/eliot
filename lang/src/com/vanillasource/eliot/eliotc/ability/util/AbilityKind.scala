package com.vanillasource.eliot.eliotc.ability.util

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, UnifiedModuleValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN

/** Whether an ability is an **effect** — the one reading of it (`docs/effects.md` §3.6, §8 item 53).
  *
  * It is read where it is recorded: membership in an `effect` block gives every member the effect itself as a declared
  * row entry (`AbilityMembers.lower`), and an `ability`'s members get none. So an ability is an effect exactly when a
  * member declares it. A member-less `effect` would leave no record, which is why one is refused at its declaration
  * (`CoreProcessor`): nothing could ever perform it.
  *
  * The members are read merged and unresolved ([[UnifiedModuleValue]]), so the resolver can ask while it is resolving
  * them, and the entry is compared by its local name. That is exact: a member is declared in its ability's module,
  * where the ability's own name is the local declaration — an explicit import of another of that name is a shadowing
  * error, and an ambient one is dropped.
  */
object AbilityKind {

  /** [[None]] when no member could be read — each reported its own error where it failed to merge — so a caller can
    * stay quiet rather than add a second error on a guess.
    */
  def isEffect(ability: AbilityFQN, platform: Platform): CompilerIO[Option[Boolean]] =
    for {
      abilities <- getFactOrAbort(ModuleAbilities.Key(ability.moduleName, platform))
      members   <- abilities
                     .declaredMethodsOf(ability.abilityName)
                     .filterNot(_.name.name == ability.abilityName)
                     .traverse(member => getFactIfProduced(UnifiedModuleValue.Key(member, platform)))
    } yield members.flatten match {
      case Seq() => None
      case known =>
        Some(known.exists(_.namedValue.effectRow.returnEffects.exists(_.abilityName.value == ability.abilityName)))
    }
}
