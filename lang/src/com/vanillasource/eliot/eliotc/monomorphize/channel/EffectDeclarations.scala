package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EnvironmentThreading.Specialisation
import com.vanillasource.eliot.eliotc.monomorphize.check.{DeclaredEntries, ImplementationBinding, NamedImplementationArguments}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicEffects}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.row.{BindingWriter, SlotBindings}
import com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.collection.mutable

/** What the two walks after monomorphization read off declarations about effects (`docs/effects.md` D35): which
  * entries a slot lists and how, which implementation a slot names, what an implementation's environment holds, and
  * the ground binding a piece of **evidence** stands for. The [[EnvironmentThreading]] reads it to thread frames, and
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

  /** The effect entries an implementation's **environment** holds: those one of its clauses performs itself while the
    * member it implements does not declare them. `recordingConsole`'s `printLine` performs `Writer[String]`, which
    * `Console.printLine` does not declare, so the implementation holds a `Writer` frame from where it is bound;
    * `FileSystem`'s clauses perform `Throw[IoError]`, which every member declares, so it holds none and each call
    * brings its own.
    */
  def environmentEntries(marker: ValueFQN, ability: AbilityFQN): CompilerIO[Set[Declared]] =
    getFactOrAbort(ModuleAbilities.Key(marker.moduleName, platform)).flatMap { abilities =>
      abilities.implementations
        .filter(impl => impl.vfqn.name.qualifier == marker.name.qualifier && impl.methodName != impl.abilityName)
        .traverse { impl =>
          for {
            own      <- declaredEntries(impl.vfqn)
            declared <- memberDeclared(memberOf(impl.vfqn, ability))
          } yield own.values.toSet -- declared
        }
        .map(_.flatten.toSet)
    }

  /** Every entry `slot` lists, as what the block is given for it: an entry it **supplies**, with the implementation
    * the slot names for it with `with`, read off the slot's declared type; or an entry it **rides**, with `value`'s
    * own binding for it. An entry the slot names an implementation for is supplied even when `value` declares it too
    * (`docs/effects.md` D23): the slot's `with` handles it.
    */
  def listed(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[Listed]] =
    for {
      named <- slotImplementations(value, slot.parameterIndex)
      rides <- ridingFlags(value, slot)
      listing <- slot.entries.zip(rides).zipWithIndex.traverse {
                 case ((entry, rides), index) if !rides || named.forEntry(index, entry.ability, rides).isDefined =>
                   (Listed.Supplied(entry, named.forEntry(index, entry.ability, rides)): Listed).pure[CompilerIO]
                 case ((entry, _), _)                                                                            =>
                   value.effects.received.find(_.entry == entry) match {
                     case Some(received) =>
                       (Listed.Rides(
                         entry,
                         value.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding)
                       ): Listed).pure[CompilerIO]
                     case None           =>
                       compilerAbort[Listed](value.name.as("Internal: a ridden entry is not among the received ones."))
                   }
               }
    } yield listing

  /** The binding the implementation `marker` a slot names performs `clause` on: the definition's own, where its row
    * has the entry spelled as the clause spells it (`docs/effects.md` D30) — the caller writes its binding there, which
    * it also hands the definition — and otherwise what the slot binds the clause's ability to, which is what the
    * caller writes for a clause the slot supplies: another implementation the slot `named`, or the default (D26).
    * Either way the frame is given where the block runs (D20c, decided (b)).
    */
  def clauseBinding(
      value: Specialisation,
      clause: (Entry, Option[Declared]),
      named: SlotNamed,
      marker: ValueFQN
  ): CompilerIO[GroundValue] =
    declaredEntries(value.vfqn).flatMap { own =>
      clause._2
        .flatMap(spelled => own.collectFirst { case (index, declared) if declared == spelled => index })
        .flatMap(value.typeArguments.lift) match {
        case Some(binding) => binding.pure[CompilerIO]
        case None          =>
          named.forClause(marker, clause._1.ability) match {
            case Some(other) => namedSlotBinding(value, named, clause._1, other)
            case None        => defaultBinding.pure[CompilerIO]
          }
      }
    }

  /** Whether each entry of `slot` **rides** the definition's own row rather than being supplied by the slot (§2.2) —
    * decided as the declaration spells it, never at ground arguments: `raising[E](…, body: {Throw[E]} Unit):
    * {Throw[AssertionError]} Unit` supplies `Throw[E]` even where `E` is `AssertionError`, so the block's frame is the
    * slot's. An entry list that does not line up with the declaration's is a failure, never a guess.
    */
  def ridingFlags(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[Boolean]] =
    getFactOrAbort(OperatorResolvedValue.Key(value.vfqn, platform)).flatMap { orv =>
      val row      = orv.effectRow.returnEffects.map(entry => (entry.abilityFQN, entry.typeArgs.map(_.render))).toSet
      val declared = orv.effectRow.parameterEffects.find(_.parameterIndex == slot.parameterIndex).toSeq.flatMap(_.effects)
      if (declared.size != slot.entries.size)
        compilerAbort(value.name.as("Internal: a row-typed parameter's entries did not reduce to ground arguments."))
      else declared.map(entry => row.contains((entry.abilityFQN, entry.typeArgs.map(_.render)))).pure[CompilerIO]
    }

  /** The implementations a slot's declared type names with `with`, by ability. The row phase strips them from the
    * signature it hands on, so they are read off the bound declaration, as the row phase read them.
    */
  def slotImplementations(value: Specialisation, index: Int): CompilerIO[SlotNamed] =
    getFactOrAbort(SlotBoundValue.Key(value.vfqn, platform)).flatMap { bound =>
      val named = SignatureView.of(bound.value.signature).parameters.lift(index).toSeq.flatMap(tpe => SlotBindings.of(tpe.value))
      named
        .traverse(n => abilityOfMarker(n.implementation.value).map(_.map(n.implementation.value -> _)))
        .map(abilities => SlotNamed(named, abilities.flatten.toMap))
    }

  /** The implementations a slot names, and which entries each binds ([[SlotBindings]]). */
  case class SlotNamed(named: Seq[SlotBindings.Named], abilities: Map[ValueFQN, AbilityFQN]) {
    def forEntry(index: Int, ability: AbilityFQN, declared: Boolean): Option[ValueFQN] =
      SlotBindings.forEntry(named, index, ability, declared, abilities.get).map(_.value)

    def forClause(implementation: ValueFQN, ability: AbilityFQN): Option[ValueFQN] =
      SlotBindings.forClause(named, implementation, ability, abilities.get).map(_.value)
  }

  def abilityOfMarker(marker: ValueFQN): CompilerIO[Option[AbilityFQN]] =
    getFactIfProduced(OperatorResolvedValue.Key(marker, platform)).map(_.flatMap(_.name.value.qualifier match {
      case com.vanillasource.eliot.eliotc.resolve.fact.Qualifier.AbilityImplementation(ability, _, _) => Some(ability)
      case _                                                                                          => None
    }))

  /** The entries a named implementation's clauses perform, each with its spelling, at the bindings a slot's `with`
    * writes for them.
    */
  def namedClauses(marker: ValueFQN): CompilerIO[Seq[(Entry, Option[Declared])]] =
    implementationClauses(marker, None).map(_.map((entry, _, spelled) => entry -> spelled))

  def namedBinding(marker: ValueFQN): GroundValue =
    GroundValue.Structure(marker, Seq.empty, GroundValue.Type)

  /** The ground binding `evidence` stands for where it answers `entry` (`docs/effects.md` D35): what a given one
    * arrived as, and for an installed one its implementation's marker applied to what the write puts there — each
    * binding binder ([[BindingWriter.phantoms]]) at the evidence its `with` closed over, a per-call one and an
    * ability's at `Default`, and each binder before the last binding at what its pattern takes at the entry.
    */
  def bindingOf(evidence: Evidence, entry: Entry): CompilerIO[GroundValue] =
    evidence match {
      case Evidence.Given(_, binding, _)          => binding.pure[CompilerIO]
      case Evidence.Installed(marker, ability, closure) =>
        installedBinding(
          marker,
          ability,
          entry,
          clauseEntry => closure.find(answers(_, clauseEntry)).traverse(bindingOf(_, clauseEntry))
        )
    }

  /** The ground binding of the implementation `marker` the slot `index` of `value` names for `entry`: its clauses at
    * what the slot binds them to ([[clauseBinding]]).
    */
  def slotBinding(value: Specialisation, index: Int, entry: Entry, marker: ValueFQN): CompilerIO[GroundValue] =
    slotImplementations(value, index).flatMap(namedSlotBinding(value, _, entry, marker))

  def namedSlotBinding(value: Specialisation, named: SlotNamed, entry: Entry, marker: ValueFQN): CompilerIO[GroundValue] =
    installedBinding(
      marker,
      entry.ability,
      entry,
      clauseEntry => namedClauses(marker).flatMap(_.find(_._1 == clauseEntry).traverse(clauseBinding(value, _, named, marker)))
    )

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
      case Evidence.Given(arrived, _, _)     => arrived == entry
      case Evidence.Installed(_, ability, _) => ability == entry.ability
    }

  /** Whether `evidence` runs the implementation `marker`. */
  def runs(evidence: Evidence, marker: ValueFQN): Boolean =
    evidence match {
      case Evidence.Given(_, binding, _)    => sameImplementation(binding, namedBinding(marker))
      case Evidence.Installed(installed, _, _) => installed == marker
    }

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
    * `typeArguments` [[None]] is a slot's `with`, whose clause bindings the caller writes as `Default`. An
    * implementation performing nothing needs no specialisation read at all.
    */
  def implementationClauses(
      marker: ValueFQN,
      typeArguments: Option[Seq[GroundValue]]
  ): CompilerIO[Seq[(Entry, GroundValue, Option[Declared])]] =
    getFactOrAbort(OperatorResolvedValue.Key(marker, platform)).flatMap { orv =>
      val performed = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
      val arguments =
        typeArguments.getOrElse(Seq.fill(SignatureView.of(orv.signature).binders.size)(defaultBinding))
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

    /** Received from the caller, or given to a block by a callee's slot, at `binding`. `bySlot` marks what a
      * callee's slot gives a block for an implementation it names with `with`.
      */
    case Given(entry: Entry, binding: GroundValue, bySlot: Boolean = false)

    /** Installed by a `with` naming the implementation `marker` of `ability`, around its subject; `closure` is the
      * evidence in scope at the `with`, which its implementation's clauses perform on.
      */
    case Installed(marker: ValueFQN, ability: AbilityFQN, closure: Seq[Evidence])
  }

  /** What a block is given for one entry its slot lists. */
  enum Listed {

    /** An entry the slot supplies, at the implementation its `with` names, or the default. */
    case Supplied(entry: Entry, named: Option[ValueFQN])

    /** An entry the slot rides: the block's code for it is the caller's `binding`, and its frame is the run site's. */
    case Rides(entry: Entry, binding: GroundValue)
  }

  val defaultBinding: GroundValue = ImplementationBinding.defaultGround
}
