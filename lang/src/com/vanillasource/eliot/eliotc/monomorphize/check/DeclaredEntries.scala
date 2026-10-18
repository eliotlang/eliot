package com.vanillasource.eliot.eliotc.monomorphize.check

import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.module.fact.Qualifier
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN

/** The effect entries a definition **performs itself**, by the index of the binder each binds — `docs/effects.md`
  * §3.4.
  *
  * Read off the constraint the desugar puts on a row binder (`Impl ~ Console[Impl]`): an entry an `implement` clause
  * only *carries* for a sibling has a binder and no constraint, and an ability member's own binding — the ability-level
  * one — has none either, so neither is here. An entry is compared **as its declaration spells it**: the ability, and
  * its arguments rendered before either side is specialised, which is how a clause's row is matched with its member's.
  */
object DeclaredEntries {

  /** An effect entry as its declaration spells it: the ability and its arguments, rendered. */
  type Declared = (AbilityFQN, Seq[String])

  def of(orv: OperatorResolvedValue): Map[Int, Declared] = {
    val binders = SignatureView.of(orv.signature).binders.map(_.name.value)
    val row     = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    orv.paramConstraints.values.flatten.flatMap { constraint =>
      constraint.typeArgs.headOption
        .collect { case OperatorResolvedExpression.ParameterReference(name) => binders.indexOf(name.value) }
        .filter(index => index >= 0 && row.contains(constraint.abilityFQN))
        .map(_ -> (constraint.abilityFQN, constraint.typeArgs.drop(1).map(_.render)))
    }.toMap
  }

  /** The entries an ability `member` declares beyond its own ability — `FileSystem.readFile`'s `Throw[IoError]`. Each
    * call performs them on the caller's binding, so an implementation's clause that repeats one takes it from the call,
    * never from the implementation's environment.
    */
  def ofMember(member: OperatorResolvedValue): Set[Declared] = {
    val declared = member.effectRow.returnEffects.map(entry => (entry.abilityFQN, entry.typeArgs.map(_.render))).toSet
    member.vfqn.name.qualifier match {
      case Qualifier.Ability(name) => declared.filterNot(_._1 == AbilityFQN(member.vfqn.moduleName, name))
      case _                       => declared
    }
  }
}
