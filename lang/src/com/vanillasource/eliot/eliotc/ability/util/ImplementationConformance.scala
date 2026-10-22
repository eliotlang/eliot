package com.vanillasource.eliot.eliotc.ability.util

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, QualifiedName, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** Whether an implementation's clauses conform to its ability: it gives every member the ability leaves abstract,
  * nothing the ability does not declare, and each clause at its member's signature.
  *
  * Demanded at the ground arguments the two-site search matched an implementation at
  * ([[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationCheckProcessor]]); a named implementation is
  * such an implementation over its handle `data` (`docs/effects.md` §3.1).
  */
object ImplementationConformance {

  /** An ability member or an implementation's clause, as the check reads it. */
  case class Method(
      vfqn: ValueFQN,
      name: Sourced[com.vanillasource.eliot.eliotc.resolve.fact.QualifiedName],
      signature: Sourced[OperatorResolvedExpression],
      hasRuntime: Boolean,
      handles: Map[String, AbilityFQN] = Map.empty
  )

  def method(vfqn: ValueFQN, platform: Platform): CompilerIO[Method] =
    getFactOrAbort(OperatorResolvedValue.Key(vfqn, platform)).map(resolved =>
      Method(vfqn, resolved.name, resolved.signature, resolved.runtime.isDefined, handlesOf(resolved))
    )

  /** The binders typing `resolved`'s handles, with the effect each is a handle of
    * ([[com.vanillasource.eliot.eliotc.ast.fact.EffectRow.handles]]).
    */
  private def handlesOf(resolved: OperatorResolvedValue): Map[String, AbilityFQN] =
    resolved.effectRow.handles
      .flatMap(handle => resolved.effectRow.returnEffects.lift(handle.entry).map(handle.binder -> _.abilityFQN))
      .toMap

  /** The members `ability` declares, its marker included. */
  def abilityMethods(ability: AbilityFQN, platform: Platform): CompilerIO[Seq[Method]] =
    getFactOrAbort(ModuleAbilities.Key(ability.moduleName, platform)).flatMap(
      _.declaredMethodsOf(ability.abilityName).traverse(method(_, platform))
    )

  /** Report every way `implementation` (its clauses and its marker) fails to conform to `ability`'s `members`. */
  def check(
      ability: AbilityFQN,
      members: Seq[Method],
      implementation: Seq[Method],
      platform: Platform
  ): CompilerIO[Unit] = {
    // An implementation's own declarations (`docs/effects.md` D36) are not clauses.
    val clauses = implementation.filterNot(m => Handles.isDeclaration(m.vfqn.name.name))
    checkCompleteness(ability, members, clauses) >>
      checkNoExtras(members, clauses) >>
      checkSignatures(ability, members, clauses, platform)
  }

  /** A missing method is reported at the implementation's **marker** — the synthetic method named after the ability,
    * positioned at the `implement` head — so the error points at the block that is incomplete. `implementation` arrives
    * in name-table order, which is a `Map`'s and so hash-dependent; picking its head would report at whichever method's
    * name hashes first, and did until the qualifier grew a component. The declaring ability method is the fallback
    * when no marker is among the methods.
    */
  private def checkCompleteness(
      ability: AbilityFQN,
      members: Seq[Method],
      implementation: Seq[Method]
  ): CompilerIO[Unit] = {
    val defined = implementation.map(_.vfqn.name.name).toSet
    val marker  = implementation.find(_.vfqn.name.name == ability.abilityName)
    members
      .filter(!_.hasRuntime)
      .filterNot(m => defined.contains(m.vfqn.name.name))
      .traverse_ { m =>
        marker match {
          case Some(implMarker) =>
            compilerError(implMarker.name.as(s"Ability implementation is missing method '${m.vfqn.name.name}'."))
          case None             =>
            compilerError(m.name.map(qn => s"Ability implementation is missing method '${qn.name}'."))
        }
      }
  }

  private def checkNoExtras(members: Seq[Method], implementation: Seq[Method]): CompilerIO[Unit] = {
    val declared = members.map(_.vfqn.name.name).toSet
    implementation
      .filterNot(m => declared.contains(m.vfqn.name.name))
      .traverse_(m => compilerError(m.name.map(_ => "Method not defined in ability.")))
  }

  /** The ability's marker is skipped: it is a pattern vessel, not a real ability method. Its return-type slot carries
    * the implementation guard (ability-guards §2.3), which is not part of the method contract, so comparing the
    * implementation marker's signature against the ability marker's would spuriously unify the guard against the
    * ability marker's own return.
    */
  private def checkSignatures(
      ability: AbilityFQN,
      members: Seq[Method],
      implementation: Seq[Method],
      platform: Platform
  ): CompilerIO[Unit] = {
    val byName = implementation.map(m => m.vfqn.name.name -> m).toMap
    members
      .filterNot(_.vfqn.name.name == ability.abilityName)
      .flatMap(member => byName.get(member.vfqn.name.name).map(member -> _))
      .traverse_ { case (member, clause) =>
        for {
          markerSignature <- markerSignature(clause.vfqn, ability.abilityName, platform)
          compatible      <- AbilityMatcher.signaturesCompatible(
                               withoutHandles(member, ability),
                               withoutHandles(clause, ability),
                               markerSignature,
                               platform
                             )
          _               <- compilerError(clause.name.as("Signature of implementation does not match the ability definition."))
                               .unlessA(compatible)
        } yield ()
      }
  }

  /** A method's signature without the handles of other effects it takes (`docs/effects.md` D36): every parameter
    * typed by a handle binder of an effect other than `ability`. An effect's own handle, which a clause takes as its
    * implementation's handle and which the pattern matches, stays; the rest are the compiler's to arrange: a clause takes
    * the handles its member declares, in the member's order, and what it captures is bound where it is installed, while
    * what an ability's clause performs beyond its member is the `row` phase's to refuse, by name.
    */
  private def withoutHandles(method: Method, ability: AbilityFQN): Sourced[OperatorResolvedExpression] = {
    val view    = SignatureView.of(method.signature)
    val others  = method.handles.collect { case (binder, effect) if effect != ability => binder }.toSet
    val handles = view.parameters.zipWithIndex.collect {
      case (Sourced(_, _, OperatorResolvedExpression.ParameterReference(name)), index) if others(name.value) => index
    }.toSet
    if (handles.isEmpty) method.signature
    else
      method.signature.as(
        view.copy(parameters = view.parameters.zipWithIndex.filterNot((_, i) => handles(i)).map(_._1)).toExpression
      )
  }

  /** The signature of the marker of the implementation `clause` belongs to. */
  def markerSignature(
      clause: ValueFQN,
      abilityName: String,
      platform: Platform
  ): CompilerIO[Sourced[OperatorResolvedExpression]] =
    getFactOrAbort(OperatorResolvedValue.Key(ValueFQN(clause.moduleName, QualifiedName(abilityName, clause.name.qualifier)), platform))
      .map(_.signature)
}
