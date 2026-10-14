package com.vanillasource.eliot.eliotc.row

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.WithBinding
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The implementations a code parameter's slot names with `with`, and which of the slot's entries each one binds
  * (`docs/effects.md` D23, D26).
  *
  * A `with` written after a clause entry binds that one entry, even one the definition also declares:
  * `body uses *, Throw[IoError] with escaping, Throw[AssertionError]` binds only the first. A binding carried from a
  * callee's slot ([[RunWithHoister]]) names no entry, and binds every entry of its ability the definition does **not**
  * declare — exactly what that callee supplies — and the clause entries of the slot's other named implementations: in
  * `mocked`, `Throw with escaping` carried from `catch` binds `Throw[IoError]`, while the declared
  * `Throw[AssertionError]` keeps riding.
  */
object SlotBindings {

  /** One implementation a slot names, and the index of the clause entry it was written after, if it was. */
  case class Named(implementation: Sourced[ValueFQN], entry: Option[Int])

  /** The implementations a slot's type names, outermost `with` last, as written. */
  def of(tpe: OperatorResolvedExpression): Seq[Named] = tpe match {
    case WithBinding(subject, implementation, entry) => of(subject.value) :+ Named(implementation, entry)
    case _                                           => Seq.empty
  }

  /** The implementation binding the slot's entry at `index`, of `ability`: one written after it, else — for an entry the
    * definition does not `declare` — one the slot names for its ability without naming an entry.
    */
  def forEntry(
      named: Seq[Named],
      index: Int,
      ability: AbilityFQN,
      declared: Boolean,
      abilityOf: ValueFQN => Option[AbilityFQN]
  ): Option[Sourced[ValueFQN]] =
    named
      .find(_.entry.contains(index))
      .orElse(
        Option
          .unless(declared)(named.find(n => n.entry.isEmpty && abilityOf(n.implementation.value).contains(ability)))
          .flatten
      )
      .map(_.implementation)

  /** The implementation binding a clause entry of `ability` performed by `implementation`, another one the slot names:
    * one named for the ability without an entry, else one written after an entry of it.
    */
  def forClause(
      named: Seq[Named],
      implementation: ValueFQN,
      ability: AbilityFQN,
      abilityOf: ValueFQN => Option[AbilityFQN]
  ): Option[Sourced[ValueFQN]] =
    named
      .filter(n => n.implementation.value != implementation && abilityOf(n.implementation.value).contains(ability))
      .sortBy(_.entry.isDefined)
      .headOption
      .map(_.implementation)
}
