package com.vanillasource.eliot.eliotc.monomorphize.specialise

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.NamedImplementationCheck
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EffectDeclarations
import com.vanillasource.eliot.eliotc.monomorphize.channel.EffectDeclarations.{Evidence, defaultBinding}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EnvironmentThreading.Specialisation
import com.vanillasource.eliot.eliotc.monomorphize.check.{DeclaredEntries, ImplementationBinding, NamedImplementationArguments}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CheckedValue,
  GroundValue,
  GroundValueRenderer,
  CompilerMonomorphicValue,
  MonomorphicEffects,
  MonomorphicExpression,
  MonomorphicValue
}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}

import scala.collection.mutable

/** **The specialisation pass** — `docs/effects.md` D35 step 4. The checker checks a value with its effect bindings left
  * out ([[CheckedValue]]); this walk puts them back from the **evidence** in scope, so the body it returns is the
  * specialisation at `typeArguments`:
  *
  * {{{
  * checked  greet[Default]    = printLine[Default]("Hello")                     -- Console.printLine, undispatched
  * at       greet[recordingConsole[writing[Default]]]
  *          = recordingConsole.printLine[writing[Default]]("Hello")             -- dispatched on the received evidence
  * }}}
  *
  * Evidence is *given* — what this value receives, at its own type arguments, and what a callee's slot gives a block
  * written at it — or *installed* by a `with`, whose binder call the resolver leaves around its subject
  * (`scripted$with(xs, subject)`); the nearest answering an entry is the one ([[EffectDeclarations.answers]]). At every
  * reference to a callee receiving an effect entry, the binding there becomes the evidence's
  * ([[EffectDeclarations.bindingOf]]); a reference to an effect's member is dispatched to the clause of the
  * implementation the evidence runs, exactly as the checker dispatched it when the binding was in the key: a named
  * implementation at the type arguments its pattern takes at the entry, its clause's per-call entries at the call's
  * evidence ([[DeclaredEntries.forwarded]]).
  *
  * Nothing else changes: no node's type, no shape, so every expression the checker typed stays as it typed it. Every
  * piece of evidence is a constant here, since every binding in `typeArguments` and every `with` names a compile-time
  * implementation, so the pass always succeeds on a program the row phase accepted, and a reference with no evidence
  * is an internal error.
  */
object EvidenceSpecialisation {

  /** The specialised body, and the value's effects with each slot's [[MonomorphicEffects.Slot.offered]] read off the runs
    * of its parameter in that body.
    */
  case class Specialised(body: Option[Sourced[MonomorphicExpression.Expression]], effects: MonomorphicEffects)

  /** The runtime track's specialisation of `checked` at `typeArguments`, whose effect bindings the checker left out. */
  def specialise(checked: CheckedValue, typeArguments: Seq[GroundValue]): CompilerIO[Specialised] =
    specialiseBody(
      Platform.Runtime,
      Specialisation(checked.vfqn, typeArguments, checked.name, checked.signature, checked.runtime, checked.effects),
      guarded = false
    )

  /** `value`'s body specialised on `platform`'s track from the evidence it receives at its type arguments — or, for a
    * **guarded** read of a signature, from what the guarded-return read discharges ([[Evidence.Discharged]]): a
    * signature's control effects are its own, never its caller's.
    */
  def specialiseBody(platform: Platform, value: Specialisation, guarded: Boolean): CompilerIO[Specialised] =
    value.body match {
      case None       => Specialised(None, value.effects).pure[CompilerIO]
      case Some(body) =>
        val specialiser = new Specialiser(platform, value.effects.slots)
        val initial     =
          if (guarded) WellKnownTypes.guardDischargers.keys.toSeq.map((module, name) => Evidence.Discharged(AbilityFQN(module, name)))
          else
            value.effects.received.map(received =>
              Evidence.Given(received.entry, value.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding))
            )
        for {
          walked <- specialiser.peel(body.map(MonomorphicExpression(value.signature, _)), 0, Scope(initial, Map.empty))
          slots  <- specialiser.slotsOffered
        } yield Specialised(Some(walked.map(_.expression)), value.effects.copy(slots = slots))
    }

  /** The lexical scope of the walk: the evidence in scope, nearest first, and this value's own code parameters by name,
    * with the index of the parameter each is — a reference to one applied to `unit` is a run of it.
    */
  private case class Scope(evidence: Seq[Evidence], runnable: Map[String, Int]) {
    def offer(more: Seq[Evidence]): Scope = copy(evidence = more ++ evidence)
    def shadow(name: String): Scope       = copy(runnable = runnable - name)
  }

  /** A value's specialisation as the declarations are read here: on the runtime track its checked value at
    * `typeArguments` with the bindings left out, carrying `typeArguments` themselves; on the compile track, which keeps
    * the bindings in its key, its compile-time monomorphization there.
    */
  private def specialisationAt(platform: Platform)(vfqn: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Specialisation] =
    platform match {
      case Platform.Runtime  =>
        EffectBindings
          .erased(vfqn, typeArguments)
          .flatMap(erased => getFactOrAbort(CheckedValue.Key(vfqn, erased)))
          .map(checked => Specialisation(vfqn, typeArguments, checked.name, checked.signature, checked.runtime, checked.effects))
      case Platform.Compiler =>
        getFactOrAbort(CompilerMonomorphicValue.Key(vfqn, typeArguments)).map(Specialisation.of)
    }

  /** A value's specialisation with its slots' [[MonomorphicEffects.Slot.offered]]: the pass's own output. */
  private def specialisedAt(platform: Platform)(vfqn: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[MonomorphicEffects] =
    platform match {
      case Platform.Runtime  => getFactOrAbort(MonomorphicValue.Key(vfqn, typeArguments)).map(_.effects)
      case Platform.Compiler => getFactOrAbort(CompilerMonomorphicValue.Key(vfqn, typeArguments)).map(_.effects)
    }

  /** @param slots
    *   The value's own code parameters' slots, whose runs the walk records ([[slotsOffered]]).
    */
  private class Specialiser(platform: Platform, slots: Seq[MonomorphicEffects.Slot]) {
    private val declarations = EffectDeclarations(platform, specialisationAt(platform))
    private val checkedAt    = specialisationAt(platform)
    import declarations.*

    /** Whether an entry no evidence answers, or only `Default` does, is left as the checker left it, undispatched: on
      * the compile track, which is asked for a value at a **partial** key too — one whose binding binders are not all
      * in it — and at a key a type-level read wrote with `Default` bindings, and which reduces what it can, an
      * undispatched operation staying stuck (the fail-safe direction: nothing reduces past it). On the runtime track every key is whole and the row phase has seen every
      * entry answered, so a missing one is an error.
      */
    private val unanswerable: Boolean = platform == Platform.Compiler

    /** Per slot, each run of its parameter: the reference, and the binding of the evidence each entry finds there. */
    private val runs = mutable.Map.empty[Int, Seq[(Sourced[String], Seq[GroundValue])]]

    /** Peel the value's own leading parameters, a code parameter's name runnable inside, and walk what they bind. */
    def peel(node: Sourced[MonomorphicExpression], index: Int, scope: Scope): CompilerIO[Sourced[MonomorphicExpression]] =
      node.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          val inside =
            if (slots.exists(_.parameterIndex == index)) scope.copy(runnable = scope.runnable.updated(name.value, index))
            else scope.shadow(name.value)
          peel(body, index + 1, inside).map(walked =>
            node.as(node.value.copy(expression = MonomorphicExpression.FunctionLiteral(name, parameterType, walked)))
          )
        case _                                                               => walk(node, scope)
      }

    def walk(node: Sourced[MonomorphicExpression], scope: Scope): CompilerIO[Sourced[MonomorphicExpression]] =
      node.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          walk(body, scope.shadow(name.value)).map(walked =>
            node.as(node.value.copy(expression = MonomorphicExpression.FunctionLiteral(name, parameterType, walked)))
          )
        case _: MonomorphicExpression.FunctionApplication                    =>
          val (head, args) = spineOf(node)
          head.value.expression match {
            case reference: MonomorphicExpression.MonomorphicValueReference
                if ImplementationParameters.isBinder(reference.valueName.value) =>
              install(node, head, reference, args, scope)
            case reference: MonomorphicExpression.MonomorphicValueReference  =>
              call(node, head, reference, args, scope)
            case MonomorphicExpression.ParameterReference(name)
                if scope.runnable.contains(name.value) && args.sizeIs == 1 && isUnit(args.head) =>
              run(name, scope.runnable(name.value), scope).as(node)
            case _                                                            =>
              for {
                walkedHead <- walk(head, scope)
                walkedArgs <- args.traverse(walk(_, scope))
              } yield rebuilt(node, walkedHead, walkedArgs)
          }
        case reference: MonomorphicExpression.MonomorphicValueReference      =>
          call(node, node, reference, Seq.empty, scope)
        case _                                                               => node.pure[CompilerIO]
      }

    /** A run of the code parameter at `index`: the binding of the evidence each entry its slot lists finds here, which
      * is what the block written there is given. An entry nothing answers is given `Default`, which a block performing
      * it reports as unbound.
      */
    private def run(name: Sourced[String], index: Int, scope: Scope): CompilerIO[Unit] =
      slots.find(_.parameterIndex == index).toSeq.flatMap(_.entries).traverse { entry =>
        scope.evidence.find(answers(_, entry)).fold(defaultBinding.pure[CompilerIO])(bindingOf(_, entry))
      }.map(bindings => runs.update(index, runs.getOrElse(index, Seq.empty) :+ (name -> bindings)))

    /** The value's slots, each with what the runs of its parameter give ([[MonomorphicEffects.Slot.offered]]). Every run
      * of one parameter must give the same: the block written at its slot is compiled once, against that.
      */
    def slotsOffered: CompilerIO[Seq[MonomorphicEffects.Slot]] =
      slots.traverse { slot =>
        runs.getOrElse(slot.parameterIndex, Seq.empty) match {
          case Seq()                => slot.pure[CompilerIO]
          case (_, first) +: others =>
            others.find(_._2 != first) match {
              case None                    => slot.copy(offered = Some(first)).pure[CompilerIO]
              case Some((name, different)) =>
                val ability = slot.entries.zip(first.zip(different)).collectFirst {
                  case (entry, (a, b)) if a != b => entry.ability.abilityName
                }
                compilerError(
                  name.as(
                    s"'${name.value}' runs here with a different implementation of '${ability.getOrElse("")}' than at " +
                      "its other runs, which is not supported yet."
                  ),
                  Seq(s"Bind the same implementation around every run of '${name.value}'.")
                ) >> abort[MonomorphicEffects.Slot]
            }
        }
      }

    /** A `with`, as the resolver lowered it: its implementation is installed around its subject, closing over the
      * evidence where it stands; the arguments it supplies, and anything its subject is applied to, are walked outside
      * it.
      */
    private def install(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        orv     <- getFactOrAbort(OperatorResolvedValue.Key(reference.valueName.value, platform))
        count    = SignatureView.of(orv.signature).parameters.size - 1
        marker  <- markerOf(reference.valueName)
        ability <- abilityOfMarker(marker).flatMap {
                     case Some(ability) => ability.pure[CompilerIO]
                     case None          => compilerAbort[AbilityFQN](head.as("Internal: a `with` names no implementation."))
                   }
        walked  <- args.zipWithIndex.traverse { case (arg, index) =>
                     walk(arg, if (index == count) scope.offer(Seq(Evidence.Installed(marker, ability, scope.evidence))) else scope)
                   }
      } yield rebuilt(node, head, walked)

    private def markerOf(binder: Sourced[ValueFQN]): CompilerIO[ValueFQN] =
      binder.value.name.qualifier match {
        case qualifier: Qualifier.AbilityImplementation =>
          ValueFQN(binder.value.moduleName, QualifiedName(qualifier.name, qualifier)).pure[CompilerIO]
        case _                                          =>
          compilerAbort(binder.as("Internal: an implementation's binder is outside its implementation."))
      }

    /** A reference applied to `args`: an effect's member is dispatched; any other callee receiving an effect entry
      * has its binding written from the evidence, and each block at one of its slots is walked with what the slot
      * gives it.
      */
    private def call(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      reference.valueName.value.name.qualifier match {
        case Qualifier.Ability(abilityName) =>
          val ability = AbilityFQN(reference.valueName.value.moduleName, abilityName)
          isEffect(ability).flatMap {
            case true  => dispatch(node, head, reference, ability, args, scope)
            case false => written(node, head, reference, args, scope)
          }
        case _                              => written(node, head, reference, args, scope)
      }

    private def written(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      getFactIfProduced(OperatorResolvedValue.Key(reference.valueName.value, platform)).flatMap {
        case Some(orv) if orv.paramConstraints.nonEmpty || orv.effectRow.parameterEffects.nonEmpty =>
          for {
            callee    <- checkedAt(reference.valueName.value, reference.typeArguments)
            arguments <- withEvidence(reference.typeArguments, callee.effects.received, scope.evidence, head)
            walked    <- blocks(callee.copy(typeArguments = arguments), 0, args, scope)
          } yield rebuilt(node, retarget(head, reference.copy(typeArguments = arguments)), walked)
        case _                                                                                    =>
          args.traverse(walk(_, scope)).map(rebuilt(node, head, _))
      }

    /** `typeArguments` with each effect entry `received` at the binding of the evidence answering it. */
    private def withEvidence(
        typeArguments: Seq[GroundValue],
        received: Seq[MonomorphicEffects.Received],
        evidence: Seq[Evidence],
        at: Sourced[?]
    ): CompilerIO[Seq[GroundValue]] =
      received.foldLeftM(typeArguments) { (arguments, entry) =>
        if (!arguments.isDefinedAt(entry.binderIndex)) arguments.pure[CompilerIO]
        else
          isEffect(entry.entry.ability).flatMap {
            case false => arguments.pure[CompilerIO]
            case true  =>
              evidence.find(answers(_, entry.entry)) match {
                case None if unanswerable => arguments.pure[CompilerIO]
                case found                =>
                  for {
                    found   <- found.fold(evidenceAt(evidence, entry.entry, at))(_.pure[CompilerIO])
                    binding <- bindingOf(found, entry.entry)
                  } yield arguments.updated(entry.binderIndex, binding)
              }
          }
      }

    private def evidenceAt(evidence: Seq[Evidence], entry: Entry, at: Sourced[?]): CompilerIO[Evidence] =
      evidence.find(answers(_, entry)) match {
        case Some(found) => found.pure[CompilerIO]
        case None        => unbound(at, entry.ability) >> abort[Evidence]
      }

    /** An effect's member, dispatched on the evidence for its entry — the binding at its first ability-level argument,
      * the entry's arguments the rest ([[ImplementationBinding]]) — to its clause in the implementation the evidence
      * runs, as the checker's ability resolution dispatched it.
      */
    private def dispatch(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        ability: AbilityFQN,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        arity   <- abilityArity(ability)
        entry    = Entry(ability, reference.typeArguments.take(arity).drop(1))
        found    = scope.evidence.find(answers(_, entry))
        binding <- found.traverse(bindingOf(_, entry))
        walked  <- binding.flatMap(ImplementationBinding.fromGround) match {
                     case Some(implementation: ImplementationBinding.Implementation) =>
                       dispatchTo(node, head, reference, entry, binding.get, implementation, args, scope)
                     case _ if unanswerable                                          =>
                       args.traverse(walk(_, scope)).map(rebuilt(node, head, _))
                     case _                                                          =>
                       unboundEffect(head, ability)
                   }
      } yield walked

    private def dispatchTo(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        entry: Entry,
        binding: GroundValue,
        implementation: ImplementationBinding.Implementation,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] = {
      val member = reference.valueName.value
      val method = implementation.method(member.name.name)
      for {
        _          <- getFactOrAbort(NamedImplementationCheck.Key(implementation.head, platform))
        solved     <- NamedImplementationArguments.solve(implementation, entry.arguments, platform).flatMap {
                        case Some(arguments) => arguments.pure[CompilerIO]
                        case None            => unmatchedBinding(head, entry, implementation)
                      }
        names      <- getFactOrAbort(UnifiedModuleNames.Key(method.moduleName, platform))
        _          <- if (names.names.contains(method.name)) ().pure[CompilerIO] else missingClause(head, member, implementation)
        memberOrv  <- getFactOrAbort(OperatorResolvedValue.Key(member, platform))
        clauseOrv  <- getFactOrAbort(OperatorResolvedValue.Key(method, platform))
        forwards    = DeclaredEntries.forwarded(memberOrv, clauseOrv)
        // A per-call entry of the clause is the call's (`docs/effects.md` §3.4): the evidence where the operation is
        // performed answers it, not the implementation's environment.
        forwarded   = forwards.foldLeft(solved) { case (acc, (clauseIndex, memberIndex)) =>
                        reference.typeArguments.lift(memberIndex).filter(_ => acc.isDefinedAt(clauseIndex)).fold(acc)(acc.updated(clauseIndex, _))
                      }
        clause     <- checkedAt(method, forwarded)
        perCall     = forwards.map(_._1).toSet
        arguments  <- withEvidence(forwarded, clause.effects.received.filter(r => perCall.contains(r.binderIndex)), scope.evidence, head)
        own        <- ownParameterCount(implementation.head)
        walked     <- blocks(clause.copy(typeArguments = arguments), own, args, scope)
      } yield rebuilt(
        node,
        retarget(
          head,
          MonomorphicExpression.MonomorphicValueReference(
            head.as(method),
            arguments,
            Some(MonomorphicEffects.Dispatch(entry, binding))
          )
        ),
        walked
      )
    }

    /** How many of an implementation's own parameters (`docs/effects.md` D25) its clauses receive ahead of their
      * member's: what a dispatched reference's slots are offset by.
      */
    private def ownParameterCount(marker: ValueFQN): CompilerIO[Int] = {
      val declaration = ImplementationParameters.of(marker)
      getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, platform)).flatMap { names =>
        if (!names.names.contains(declaration.name)) 0.pure[CompilerIO]
        else
          getFactOrAbort(OperatorResolvedValue.Key(declaration, platform))
            .map(orv => SignatureView.of(orv.signature).parameters.size)
      }
    }

    /** The number of an ability's own type parameters, its binding first: its marker's binders. */
    private def abilityArity(ability: AbilityFQN): CompilerIO[Int] =
      getFactOrAbort(
        OperatorResolvedValue.Key(
          ValueFQN(ability.moduleName, QualifiedName(ability.abilityName, Qualifier.Ability(ability.abilityName))),
          platform
        )
      ).map(orv => SignatureView.of(orv.signature).binders.size)

    /** Walk `args`, each one at a slot of `callee` — parameter `offset + index` — with what that slot gives it. */
    private def blocks(
        callee: Specialisation,
        offset: Int,
        args: Seq[Sourced[MonomorphicExpression]],
        scope: Scope
    ): CompilerIO[Seq[Sourced[MonomorphicExpression]]] = {
      val slots = callee.effects.slots.map(slot => slot.parameterIndex -> slot).toMap
      args.zipWithIndex.traverse { case (arg, index) =>
        slots.get(index + offset) match {
          case Some(slot) =>
            for {
              offered <- offeredBy(callee, slot)
              closed  <- closedSlot(callee.vfqn, slot.parameterIndex)
              walked  <- block(arg, offered, if (closed) scope.copy(evidence = Seq.empty) else scope)
            } yield walked
          case None       => walk(arg, scope)
        }
      }
    }

    /** Whether `vfqn`'s parameter `index` takes code whose clause is **closed** (`docs/effects.md` §1 rule 4): a block
      * there sees only what the callee gives it and what it installs itself, never its writer's evidence, so an effect
      * the slot does not list is unbound there however the code is spelled (§8 item 60).
      */
    private def closedSlot(vfqn: ValueFQN, index: Int): CompilerIO[Boolean] =
      getFactIfProduced(OperatorResolvedValue.Key(vfqn, platform))
        .map(_.exists(_.effectRow.parameterEffects.exists(slot => slot.parameterIndex == index && slot.closed)))

    /** What `callee` gives a block at `slot`: the evidence its body's runs of the parameter find
      * ([[MonomorphicEffects.Slot.offered]]), each entry at `Default` where nothing runs it — a body-less callee, or a
      * parameter never run.
      */
    private def offeredBy(callee: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[(Entry, GroundValue)]] =
      if (slot.entries.isEmpty) Seq.empty.pure[CompilerIO]
      else
        specialisedAt(platform)(callee.vfqn, callee.typeArguments).map { specialised =>
          specialised.slots.find(_.parameterIndex == slot.parameterIndex) match {
            case Some(MonomorphicEffects.Slot(_, entries, Some(offered))) => entries.zip(offered)
            case Some(MonomorphicEffects.Slot(_, entries, None))        => entries.map(_ -> defaultBinding)
            case None                                                   => slot.entries.map(_ -> defaultBinding)
          }
        }

    /** A block at a callee's slot is given evidence for each entry the slot lists, at what the callee's runs give. */
    private def block(
        arg: Sourced[MonomorphicExpression],
        offered: Seq[(Entry, GroundValue)],
        scope: Scope
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      arg.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          walk(body, scope.shadow(name.value).offer(offered.map((entry, binding) => Evidence.Given(entry, binding))))
            .map(walked =>
              arg.as(arg.value.copy(expression = MonomorphicExpression.FunctionLiteral(name, parameterType, walked)))
            )
        case _                                                                =>
          compilerAbort(arg.as("Internal: a block at a row-typed slot is not a thunk."))
      }

    private def unboundEffect(at: Sourced[?], ability: AbilityFQN): CompilerIO[Sourced[MonomorphicExpression]] =
      unbound(at, ability) >> abort[Sourced[MonomorphicExpression]]

    private def unbound(at: Sourced[?], ability: AbilityFQN): CompilerIO[Unit] =
      compilerError(
        at.as(s"Nothing binds an implementation of the effect '${ability.abilityName}' here, and an effect has no default."),
        Seq(
          "An effect is bound by a `with` around its use, by the callee running the code it is written in, or by the " +
            "caller of a definition declaring it; a body-less discharger gives one only where a platform layer bodies it."
        )
      )

    /** A `with` bound an implementation for an entry its pattern does not match: `with none`, for `implement[T] none:
      * Source[Option[T]]`, where the reference performs `Source[Int]`.
      */
    private def unmatchedBinding(
        at: Sourced[?],
        entry: Entry,
        implementation: ImplementationBinding.Implementation
    ): CompilerIO[Seq[GroundValue]] = {
      val name      = implementationName(implementation, entry.ability)
      val performed =
        if (entry.arguments.isEmpty) entry.ability.abilityName
        else entry.arguments.map(GroundValueRenderer.render).mkString(s"${entry.ability.abilityName}[", ", ", "]")
      compilerError(
        at.as(s"This performs '$performed' on the implementation '$name', which does not implement it."),
        Seq(s"A `with` around a use of this definition binds '$name'; bind one that implements '$performed' instead.")
      ) >> abort[Seq[GroundValue]]
    }

    /** A `with` bound an implementation with no clause for this member on the path: the base's body-less `implement
      * standardConsole: Console` where no platform layer bodies it.
      */
    private def missingClause(
        at: Sourced[?],
        member: ValueFQN,
        implementation: ImplementationBinding.Implementation
    ): CompilerIO[Unit] = {
      val name = implementation.head.name.qualifier match {
        case Qualifier.AbilityImplementation(_, _, Some(name)) => name
        case _                                                 => member.name.name
      }
      compilerError(
        at.as(s"The implementation '$name' has no '${member.name.name}' here."),
        Seq(s"No layer on the path declares that clause of '$name'; a platform layer is what bodies it.")
      ) >> abort[Unit]
    }

    private def implementationName(implementation: ImplementationBinding.Implementation, ability: AbilityFQN): String =
      implementation.head.name.qualifier match {
        case Qualifier.AbilityImplementation(_, _, Some(name)) => name
        case _                                                 => ability.abilityName
      }
  }

  private def isUnit(node: Sourced[MonomorphicExpression]): Boolean =
    node.value.expression match {
      case MonomorphicExpression.MonomorphicValueReference(name, _, _) => name.value == WellKnownTypes.unitValueFQN
      case _                                                           => false
    }

  private def retarget(
      head: Sourced[MonomorphicExpression],
      reference: MonomorphicExpression.MonomorphicValueReference
  ): Sourced[MonomorphicExpression] =
    head.as(head.value.copy(expression = reference))

  /** An application spine: its head and its arguments in order. */
  private def spineOf(
      node: Sourced[MonomorphicExpression]
  ): (Sourced[MonomorphicExpression], Seq[Sourced[MonomorphicExpression]]) =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        val (head, args) = spineOf(target)
        (head, args :+ argument)
      case _                                                           => (node, Seq.empty)
    }

  /** `node`'s spine with its head and arguments replaced, every application keeping the type and position it had. */
  private def rebuilt(
      node: Sourced[MonomorphicExpression],
      head: Sourced[MonomorphicExpression],
      args: Seq[Sourced[MonomorphicExpression]]
  ): Sourced[MonomorphicExpression] =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, _) if args.nonEmpty =>
        node.as(node.value.copy(expression = MonomorphicExpression.FunctionApplication(rebuilt(target, head, args.init), args.last)))
      case _                                                                     => head
    }
}
