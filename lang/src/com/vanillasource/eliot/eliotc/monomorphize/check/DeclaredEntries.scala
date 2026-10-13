package com.vanillasource.eliot.eliotc.monomorphize.check

import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
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

  /** Where a dispatched clause takes the **call's** binding: each of the clause's own entries that the ability member it
    * implements declares too, as `(clause binder index, member binder index)`. Such an entry is per call — the caller
    * hands the member its binding (`docs/effects.md` §3.4) — so the implementation's own value for that binder, which is
    * `Default` for an anonymous one, is not the one to run on.
    */
  def forwarded(member: OperatorResolvedValue, clause: OperatorResolvedValue): Seq[(Int, Int)] = {
    val byEntry = of(member).map(_.swap)
    of(clause).toSeq.flatMap { case (clauseIndex, entry) => byEntry.get(entry).map(clauseIndex -> _) }.sortBy(_._1)
  }
}
