package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.Qualifier
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The **clause row** of an `implement` block: what the implementation itself performs — effects v6,
  * `docs/effects.md` §9.4 step 3.
  *
  * An implementation whose clauses declare a row is *parameterised by what they perform*, so `with recordingConsole`
  * has to write not just the marker but the implementations its clauses' `{Writer[String]}` runs on —
  * `greeting[recordingConsole[cellWriter]]`. That parameter is a phantom binder like every other one, and the only
  * thing this object arranges is that **every value of the block declares the same one, in the same order**.
  *
  * It is the **union** of the clauses' rows rather than each clause's own, because
  * [[com.vanillasource.eliot.eliotc.monomorphize.check.AbilityResolver]] hands the marker's type arguments to *every*
  * method of the implementation positionally: one list, so one prefix. A clause that performs nothing therefore
  * declares what its siblings perform — which is true of the implementation, is bound at the same `with`, and costs
  * nothing at runtime since a phantom binder is erased. Nothing here mints a binder: the entries are written into the
  * ordinary return-row position and [[com.vanillasource.eliot.eliotc.core.processor.EffectSugarDesugarer]] mints them
  * exactly as it mints any other row, which is what keeps marker and methods in step by construction.
  *
  * The **marker** carries the union on its guard slot. A row is erased from every type by that same desugar, so the
  * guard reaches [[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationProcessor]] untouched; what
  * the marker gains is the declaration the binder is read back from by the write
  * ([[com.vanillasource.eliot.eliotc.row.BindingWriter]]) for a `with`.
  *
  * **Associated types are excluded.** A `type` inside an `implement` block is a type occupying a pattern slot, not a
  * clause: giving it a row would mint binders on it and change its arity at every use.
  */
object ImplementationRows {

  /** Every effect the block's clauses declare, in clause order, without repetition. */
  def union(clauses: Seq[FunctionDefinition]): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    clauses.flatMap(clause => entriesOf(clause.typeDefinition)).distinctBy(UnresolvedAbilityConstraint.key)

  /** The definition with `row` as its declared return row, in place of whatever it declared, recording the entries it
    * only carries for its siblings ([[FunctionDefinition.carriedEffects]]).
    *
    * An entry the clause wrote itself keeps the clause's own copy, and with it the clause's own source position, so the
    * union is the same list in every clause while each entry still points at the clause that declared it. That is what
    * lets [[com.vanillasource.eliot.eliotc.row.BindingWriter]] hold a clause to the entries it wrote and not to the
    * ones it only carries for its siblings.
    */
  def carrying(
      row: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]],
      definition: FunctionDefinition
  ): FunctionDefinition =
    if (row.isEmpty) definition
    else {
      val own = entriesOf(definition.typeDefinition).map(entry => UnresolvedAbilityConstraint.key(entry) -> entry).toMap
      definition.copy(
        typeDefinition =
          rowed(row.map(entry => own.getOrElse(UnresolvedAbilityConstraint.key(entry), entry)), definition.typeDefinition),
        carriedEffects = row.map(UnresolvedAbilityConstraint.key).filterNot(own.contains).toSet
      )
    }

  /** The expression with `row` as its top-level row, in place of whatever it had. */
  def rowed(
      row: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]],
      expr: Sourced[Expression]
  ): Sourced[Expression] =
    if (row.isEmpty) expr
    else
      expr.value match {
        case Expression.EffectfulType(_, resultType) => expr.as(Expression.EffectfulType(row, resultType))
        case _                                       => expr.as(Expression.EffectfulType(row, expr))
      }

  /** The definition with only the row entries it wrote itself, undoing [[carrying]]: on the new effect path
    * (`docs/effects.md` D36) a clause is an instance method shaped like its member, and the union only ever served the
    * old path's single list of binders.
    */
  def own(definition: FunctionDefinition): FunctionDefinition =
    if (definition.carriedEffects.isEmpty) definition
    else
      definition.typeDefinition.value match {
        case Expression.EffectfulType(effects, resultType) =>
          val kept = effects.filterNot(entry => definition.carriedEffects.contains(UnresolvedAbilityConstraint.key(entry)))
          definition.copy(
            typeDefinition =
              if (kept.isEmpty) resultType else definition.typeDefinition.as(Expression.EffectfulType(kept, resultType)),
            carriedEffects = Set.empty
          )
        case _                                             => definition.copy(carriedEffects = Set.empty)
      }

  /** An anonymous `implement` block's definition as the new effect path (`docs/effects.md` D36) reads it: a clause with
    * only the entries it wrote itself ([[own]]), and the marker with no row at all. A clause is an instance method
    * shaped like its member, taking the handles its member declares, so nothing is bound where an ability's instance is
    * found and its marker declares no binder for what its clauses perform.
    */
  def anonymousOnHandles(definition: FunctionDefinition): FunctionDefinition =
    definition.name.value.qualifier match {
      case Qualifier.AbilityImplementation(ability, _, None) if definition.name.value.name == ability =>
        definition.typeDefinition.value match {
          case Expression.EffectfulType(_, resultType) => definition.copy(typeDefinition = resultType)
          case _                                       => definition
        }
      case Qualifier.AbilityImplementation(_, _, None)                                                => own(definition)
      case _                                                                                          => definition
    }

  def entriesOf(expr: Sourced[Expression]): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    expr.value match {
      case Expression.EffectfulType(effects, _) => effects
      case _                                    => Seq.empty
    }
}
