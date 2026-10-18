package com.vanillasource.eliot.eliotc.ability.util

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, QualifiedName, ValueFQN, WellKnownTypes}
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
  * Shared by the two checks that demand it: an anonymous implementation's, at the ground arguments the two-site search
  * matched it at ([[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationCheckProcessor]]), and a
  * named one's, wherever a binding dispatches to it
  * ([[com.vanillasource.eliot.eliotc.ability.processor.NamedImplementationCheckProcessor]]).
  */
object ImplementationConformance {

  /** An ability member or an implementation's clause, as the check reads it. */
  case class Method(
      vfqn: ValueFQN,
      name: Sourced[com.vanillasource.eliot.eliotc.resolve.fact.QualifiedName],
      signature: Sourced[OperatorResolvedExpression],
      hasRuntime: Boolean
  )

  def method(vfqn: ValueFQN, platform: Platform): CompilerIO[Method] =
    getFactOrAbort(OperatorResolvedValue.Key(vfqn, platform)).map(resolved =>
      Method(vfqn, resolved.name, resolved.signature, resolved.runtime.isDefined)
    )

  /** The members `ability` declares, its marker included. */
  def abilityMethods(ability: AbilityFQN, platform: Platform): CompilerIO[Seq[Method]] =
    getFactOrAbort(ModuleAbilities.Key(ability.moduleName, platform)).flatMap(
      _.declaredMethodsOf(ability.abilityName).traverse(method(_, platform))
    )

  /** Report every way `implementation` (its clauses and its marker) fails to conform to `ability`'s `members`.
    *
    * @param complete
    *   Whether a member the ability leaves abstract and the implementation does not give is an error. Not for a named
    *   implementation that declares no clause at all — `implement standardConsole: Console`, which a platform layer
    *   bodies — whose missing clause is reported where a binding dispatches to it.
    * @param leadingParameters
    *   How many parameters the implementation takes. Each clause receives them ahead of its member's own, so they are
    *   not part of the member's contract and are dropped before comparing.
    */
  def check(
      ability: AbilityFQN,
      members: Seq[Method],
      implementation: Seq[Method],
      platform: Platform,
      complete: Boolean = true,
      leadingParameters: Int = 0
  ): CompilerIO[Unit] = {
    // An implementation's own declarations on the new effect path (`docs/effects.md` D36) are not clauses.
    val clauses = implementation.filterNot(m => Handles.isDeclaration(m.vfqn.name.name))
    checkCompleteness(ability, members, clauses).whenA(complete) >>
      checkNoExtras(members, clauses) >>
      checkSignatures(ability, members, clauses, platform, leadingParameters)
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
      platform: Platform,
      leadingParameters: Int
  ): CompilerIO[Unit] = {
    val byName = implementation.map(m => m.vfqn.name.name -> m).toMap
    members
      .filterNot(_.vfqn.name.name == ability.abilityName)
      .flatMap(member => byName.get(member.vfqn.name.name).map(member -> _))
      .traverse_ { case (member, clause) =>
        for {
          markerSignature <- markerSignature(clause.vfqn, ability.abilityName, platform)
          compatible      <- AbilityMatcher.signaturesCompatible(
                               withoutHandles(member.signature),
                               withoutHandles(withoutLeadingParameters(clause.signature, leadingParameters)),
                               markerSignature
                             )
          _               <- compilerError(clause.name.as("Signature of implementation does not match the ability definition."))
                               .unlessA(compatible)
        } yield ()
      }
  }

  /** A signature without the handles it takes beyond its first parameter — on the new effect path (`docs/effects.md`
    * D36) every parameter typed by a binding binder. The first is the member's own effect's handle, which a clause
    * takes as its implementation's handle and which the pattern matches; the rest are the compiler's to arrange: a
    * clause takes the handles its member declares, in the member's order, and what it captures is bound where it is
    * installed. On the old path no parameter is typed by a binding binder, and nothing is dropped.
    */
  private def withoutHandles(signature: Sourced[OperatorResolvedExpression]): Sourced[OperatorResolvedExpression] = {
    val view    = SignatureView.of(signature)
    val marked  = view.binders.filter(_.parameterType.exists(t => isMark(t.value))).map(_.name.value).toSet
    val handles = view.parameters.zipWithIndex.collect {
      case (Sourced(_, _, OperatorResolvedExpression.ParameterReference(name)), index) if index > 0 && marked(name.value) =>
        index
    }.toSet
    if (handles.isEmpty) signature
    else signature.as(view.copy(parameters = view.parameters.zipWithIndex.filterNot((_, i) => handles(i)).map(_._1)).toExpression)
  }

  private def isMark(declared: OperatorResolvedExpression): Boolean =
    OperatorResolvedExpression.spine(declared)._1 match {
      case OperatorResolvedExpression.ValueReference(head, _) => head.value == WellKnownTypes.implementationTypeFQN
      case _                                                  => false
    }

  private def withoutLeadingParameters(
      signature: Sourced[OperatorResolvedExpression],
      count: Int
  ): Sourced[OperatorResolvedExpression] =
    if (count == 0) signature
    else {
      val view = SignatureView.of(signature)
      signature.as(view.copy(parameters = view.parameters.drop(count)).toExpression)
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
