package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.NamedImplementationCheck
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EnvironmentThreading.Specialisation
import com.vanillasource.eliot.eliotc.monomorphize.check.{DeclaredEntries, ImplementationBinding, NamedImplementationArguments}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicEffects}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.row.BindingWriter

import scala.collection.mutable

/** What the two walks after monomorphization read off declarations about effects (`docs/effects.md` D35): what an
  * implementation's environment holds, and the ground binding a piece of **evidence** stands for. The [[EnvironmentThreading]] reads it to thread frames, and
  * [[com.vanillasource.eliot.eliotc.monomorphize.specialise.EvidenceSpecialisation]] to write each reference's bindings and dispatch each operation from the evidence in
  * scope. Every read is memoised for the walk that owns the instance.
  *
  * @param platform
  *   The pool declarations are read from.
  * @param specialisation
  *   A value's specialisation at type arguments; only its effect entries are read here, which do not depend on the
  *   bindings among those arguments.
  */
class EffectDeclarations(platform: Platform, specialisation: (ValueFQN, Seq[GroundValue]) => CompilerIO[Specialisation]) {
  import EffectDeclarations.*

  /** The ability member an implementation's clause implements. */
  def memberOf(clause: ValueFQN, ability: AbilityFQN): ValueFQN =
    ValueFQN(ability.moduleName, QualifiedName(clause.name.name, Qualifier.Ability(ability.abilityName)))

  type Declared = DeclaredEntries.Declared

  /** The entries an ability member declares beyond its own ability — `FileSystem.readFile`'s `{Throw[IoError]}`. Each
    * call performs them on the caller's binding, so their frames come from the call site, never from the
    * implementation's environment, even where the implementation's clause repeats them.
    */
  def memberDeclared(member: ValueFQN): CompilerIO[Set[Declared]] =
    memberRows.get(member) match {
      case Some(declared) => declared.pure[CompilerIO]
      case None           =>
        getFactIfProduced(OperatorResolvedValue.Key(member, platform))
          .map(_.fold(Set.empty[Declared])(DeclaredEntries.ofMember))
          .flatTap(declared => memberRows.update(member, declared).pure[CompilerIO])
    }

  val memberRows: mutable.Map[ValueFQN, Set[Declared]] = mutable.Map.empty

  /** The effect entries a definition performs itself, by the index of the binder each binds ([[DeclaredEntries]]). */
  def declaredEntries(vfqn: ValueFQN): CompilerIO[Map[Int, Declared]] =
    getFactIfProduced(OperatorResolvedValue.Key(vfqn, platform))
      .map(_.fold(Map.empty[Int, Declared])(DeclaredEntries.of))

  /** The effect entries an implementation's **environment** holds ([[DeclaredEntries.environment]]): those one of its
    * clauses performs itself while the member it implements does not declare them. `recordingConsole`'s `printLine`
    * performs `Writer[String]`, which `Console.printLine` does not declare, so the implementation holds a `Writer` frame
    * from where it is installed; `FileSystem`'s clauses perform `Throw[IoError]`, which every member declares, so it
    * holds none and each call brings its own. The clauses are the values in the marker's namespace but the marker, as
    * the row phase reads them.
    */
  def environmentEntries(marker: ValueFQN, ability: AbilityFQN): CompilerIO[Set[Declared]] =
    for {
      abilities <- getFactOrAbort(ModuleAbilities.Key(marker.moduleName, platform))
      members   <- getFactOrAbort(UnifiedModuleNames.Key(ability.moduleName, platform))
      clauses   <- abilities.implementations
                     .filter(impl => impl.vfqn.name.qualifier == marker.name.qualifier && impl.vfqn != marker)
                     .traverse { impl =>
                       val member = memberOf(impl.vfqn, ability)
                       // A clause implementing no member is the conformance check's error, not an environment entry.
                       if (!members.names.contains(member.name))
                         getFactOrAbort(NamedImplementationCheck.Key(marker, platform)) >>
                           abort[(OperatorResolvedValue, OperatorResolvedValue)]
                       else
                         for {
                           clause         <- getFactOrAbort(OperatorResolvedValue.Key(impl.vfqn, platform))
                           memberDeclared <- getFactOrAbort(OperatorResolvedValue.Key(member, platform))
                         } yield clause -> memberDeclared
                     }
    } yield DeclaredEntries.environment(clauses)

  def abilityOfMarker(marker: ValueFQN): CompilerIO[Option[AbilityFQN]] =
    getFactIfProduced(OperatorResolvedValue.Key(marker, platform)).map(_.flatMap(_.name.value.qualifier match {
      case com.vanillasource.eliot.eliotc.resolve.fact.Qualifier.AbilityImplementation(ability, _, _) => Some(ability)
      case _                                                                                          => None
    }))

  def namedBinding(marker: ValueFQN): GroundValue =
    GroundValue.Structure(marker, Seq.empty, GroundValue.Type)

  /** The ground binding `evidence` stands for where it answers `entry` (`docs/effects.md` D35): what a given one
    * arrived as, and for an installed one its implementation's marker applied to what the write puts there — each
    * binding binder ([[BindingWriter.phantoms]]) at the evidence its `with` closed over, a per-call one and an
    * ability's at `Default`, and each binder before the last binding at what its pattern takes at the entry.
    */
  def bindingOf(evidence: Evidence, entry: Entry): CompilerIO[GroundValue] =
    evidence match {
      case Evidence.Given(_, binding)                => binding.pure[CompilerIO]
      case Evidence.Installed(marker, ability, closure) =>
        installedBinding(
          marker,
          ability,
          entry,
          clauseEntry => closure.find(answers(_, clauseEntry)).traverse(bindingOf(_, clauseEntry))
        )
      case Evidence.Discharged(ability)                 => dischargedBinding(ability, entry)
    }

  /** What the platform's discharger of `ability` gives a block performing `entry` — `runThrow`'s slot for a
    * `Throw[String]`, at `E := String` — which is what the guarded-return read installs for a signature's own control
    * effect (`docs/effects.md` D26): the read *is* that discharge, run by the compiler. Each of the discharger's binders
    * is what the slot's entry spelled over it takes at `entry`, and `Type` otherwise — the block it runs is a
    * signature's, whose value is a type.
    */
  def dischargedBinding(ability: AbilityFQN, entry: Entry): CompilerIO[GroundValue] =
    WellKnownTypes.guardDischargers.get((ability.moduleName, ability.abilityName)) match {
      case None             => defaultBinding.pure[CompilerIO]
      case Some(discharger) =>
        getFactOrAbort(OperatorResolvedValue.Key(discharger, platform)).flatMap { orv =>
          orv.effectRow.parameterEffects.find(_.effects.exists(_.abilityFQN == ability)) match {
            case None       => defaultBinding.pure[CompilerIO]
            case Some(slot) =>
              val spelled   = slot.effects.find(_.abilityFQN == ability).toSeq.flatMap(_.typeArgs)
              val solved    = spelled.zip(entry.arguments).collect {
                case (OperatorResolvedExpression.ParameterReference(name), argument) => name.value -> argument
              }.toMap
              val marked    = BindingWriter.phantoms(orv).map(_._1).toSet
              val arguments = SignatureView.of(orv.signature).binders.zipWithIndex.map { (binder, index) =>
                if (marked.contains(index)) defaultBinding else solved.getOrElse(binder.name.value, GroundValue.Type)
              }
              specialisation(discharger, arguments).map { value =>
                value.effects.slots
                  .find(_.parameterIndex == slot.parameterIndex)
                  .flatMap(found => found.variants.headOption.flatMap(offered => found.entries.zip(offered).find(_._1 == entry)))
                  .fold(defaultBinding)(_._2)
              }
          }
        }
    }

  def installedBinding(
      marker: ValueFQN,
      ability: AbilityFQN,
      entry: Entry,
      clause: Entry => CompilerIO[Option[GroundValue]]
  ): CompilerIO[GroundValue] =
    for {
      orv      <- getFactOrAbort(OperatorResolvedValue.Key(marker, platform))
      marks     = BindingWriter.phantoms(orv).toMap
      solved   <- NamedImplementationArguments.solve(
                    ImplementationBinding.Implementation(marker, Seq.empty),
                    entry.arguments,
                    platform
                  )
      arguments = solved.getOrElse(SignatureView.of(orv.signature).binders.map(_ => defaultBinding))
      received <- if (marks.isEmpty) Map.empty[Int, Entry].pure[CompilerIO]
                  else specialisation(marker, arguments).map(_.effects.received.map(r => r.binderIndex -> r.entry).toMap)
      declared <- declaredEntries(marker)
      held     <- environmentEntries(marker, ability)
      written  <- (0 to marks.keys.maxOption.getOrElse(-1)).toList.traverse { index =>
                    marks.get(index) match {
                      case None                                                                  =>
                        arguments.lift(index).getOrElse(defaultBinding).pure[CompilerIO]
                      case Some(_) if declared.get(index).exists(entry => !held.contains(entry)) =>
                        defaultBinding.pure[CompilerIO]
                      case Some(markAbility)                                                     =>
                        isEffect(markAbility).flatMap {
                          case true  =>
                            received.get(index).flatTraverse(clause).map(_.getOrElse(defaultBinding))
                          case false => defaultBinding.pure[CompilerIO]
                        }
                    }
                  }
    } yield GroundValue.Structure(marker, written, GroundValue.Type)

  /** Whether `evidence` answers `entry`: a given one that very entry, an installed one any of its ability. */
  def answers(evidence: Evidence, entry: Entry): Boolean =
    evidence match {
      case Evidence.Given(arrived, _)     => arrived == entry
      case Evidence.Installed(_, ability, _) => ability == entry.ability
      case Evidence.Discharged(ability)      => ability == entry.ability
    }

  /** Whether `evidence` runs the implementation `marker`. */

  def isEffect(ability: AbilityFQN): CompilerIO[Boolean] =
    effects.get(ability) match {
      case Some(known) => known.pure[CompilerIO]
      case None        =>
        AbilityKind
          .isEffect(ability, platform)
          .map(_.getOrElse(false))
          .flatTap(known => effects.update(ability, known).pure[CompilerIO])
    }

  val effects: mutable.Map[AbilityFQN, Boolean] = mutable.Map.empty

  def sameImplementation(left: GroundValue, right: GroundValue): Boolean =
    (ImplementationBinding.fromGround(left), ImplementationBinding.fromGround(right)) match {
      case (Some(ImplementationBinding.Default), Some(ImplementationBinding.Default))                       => true
      case (Some(ImplementationBinding.Implementation(a, _)), Some(ImplementationBinding.Implementation(b, _))) =>
        a == b
      case (None, None)                                                                                     => left == right
      case _                                                                                                => false
    }

  def isNamed(binding: GroundValue): Boolean =
    ImplementationBinding.fromGround(binding) match {
      case Some(_: ImplementationBinding.Implementation) => true
      case _                                             => false
    }

  def describe(binding: GroundValue): String =
    ImplementationBinding.fromGround(binding) match {
      case Some(ImplementationBinding.Implementation(head, _)) =>
        head.name.qualifier match {
          case Qualifier.AbilityImplementation(_, _, Some(name)) => s"the implementation '$name'"
          case _                                                 => "the default implementation"
        }
      case _                                                   => "the default implementation"
    }

  /** The entries an implementation's environment holds frames of, with the bindings its type arguments hold for them
    * — read off its marker's specialisation, whose clause row every clause carries: the effects its clauses perform
    * ([[environmentEntries]]). Its `~` constraints hold none: an ability has no named implementation
    * (`docs/effects.md` D29), so a constraint is the two-site search's, whose anonymous answer takes no parameters.
    * An implementation performing nothing needs no specialisation read at all.
    */
  def implementationClauses(
      marker: ValueFQN,
      arguments: Seq[GroundValue]
  ): CompilerIO[Seq[(Entry, GroundValue, Option[Declared])]] =
    getFactOrAbort(OperatorResolvedValue.Key(marker, platform)).flatMap { orv =>
      val performed = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
      if (performed.isEmpty) Seq.empty.pure[CompilerIO]
      else
        for {
          held   <- abilityOfImplementation(orv).fold(Set.empty[Declared].pure[CompilerIO])(environmentEntries(marker, _))
          own    <- declaredEntries(marker)
          ground <- specialisation(marker, arguments)
        } yield ground.effects.received
          .filter(received => performed.contains(received.entry.ability) && own.get(received.binderIndex).exists(held.contains))
          .map(received =>
            (received.entry, arguments.lift(received.binderIndex).getOrElse(defaultBinding), own.get(received.binderIndex))
          )
    }

  def abilityOfImplementation(
      orv: OperatorResolvedValue
  ): Option[AbilityFQN] =
    orv.name.value.qualifier match {
      case com.vanillasource.eliot.eliotc.resolve.fact.Qualifier.AbilityImplementation(ability, _, _) => Some(ability)
      case _                                                                                          => None
    }
}

object EffectDeclarations {

  /** What answers an entry where it is performed, as D35 reads it. */
  enum Evidence {

    /** Received from the caller, or given to a block by the callee running it, at `binding`. */
    case Given(entry: Entry, binding: GroundValue)

    /** Installed by a `with` naming the implementation `marker` of `ability`, around its subject; `closure` is the
      * evidence in scope at the `with`, which its implementation's clauses perform on.
      */
    case Installed(marker: ValueFQN, ability: AbilityFQN, closure: Seq[Evidence])

    /** What a guarded signature's read discharges `ability` with: the platform discharger's ([[dischargedBinding]]). */
    case Discharged(ability: AbilityFQN)
  }

  val defaultBinding: GroundValue = ImplementationBinding.defaultGround
}
