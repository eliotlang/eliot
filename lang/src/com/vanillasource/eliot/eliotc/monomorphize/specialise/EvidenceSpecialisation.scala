package com.vanillasource.eliot.eliotc.monomorphize.specialise

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.NamedImplementationCheck
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EffectDeclarations
import com.vanillasource.eliot.eliotc.monomorphize.channel.EffectDeclarations.{Evidence, Listed, defaultBinding}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EnvironmentThreading.Specialisation
import com.vanillasource.eliot.eliotc.monomorphize.check.{DeclaredEntries, ImplementationBinding, NamedImplementationArguments}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CheckedValue,
  GroundValue,
  GroundValueRenderer,
  MonomorphicEffects,
  MonomorphicExpression
}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}

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
  * evidence ([[DeclaredEntries.forwarded]]). Where the checked body already holds a named implementation — a `with` in
  * this body wrote it — the evidence must run the same one.
  *
  * Nothing else changes: no node's type, no shape, so every expression the checker typed stays as it typed it. Every
  * piece of evidence is a constant here, since every binding in `typeArguments` and every `with` names a compile-time
  * implementation, so the pass always succeeds on a program the row phase accepted, and a reference with no evidence
  * is an internal error.
  */
object EvidenceSpecialisation {

  def specialise(
      checked: CheckedValue,
      typeArguments: Seq[GroundValue]
  ): CompilerIO[Option[Sourced[MonomorphicExpression.Expression]]] =
    checked.runtime.traverse { body =>
      val value = Specialisation(checked.vfqn, typeArguments, checked.name, checked.signature, checked.runtime, checked.effects)
      new Specialiser().walk(
        body.map(MonomorphicExpression(checked.signature, _)),
        value.effects.received.map(received =>
          Evidence.Given(received.entry, typeArguments.lift(received.binderIndex).getOrElse(defaultBinding))
        )
      ).map(_.map(_.expression))
    }

  /** A value's specialisation as the declarations are read here: its checked value at `typeArguments` with the
    * bindings left out, carrying `typeArguments` themselves, which is what a slot's riding entries are read at.
    */
  private def checkedAt(vfqn: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Specialisation] =
    EffectBindings
      .erased(vfqn, typeArguments)
      .flatMap(erased => getFactOrAbort(CheckedValue.Key(vfqn, erased)))
      .map(checked => Specialisation(vfqn, typeArguments, checked.name, checked.signature, checked.runtime, checked.effects))

  private class Specialiser {
    private val declarations = EffectDeclarations(Platform.Runtime, checkedAt)
    import declarations.*

    def walk(node: Sourced[MonomorphicExpression], evidence: Seq[Evidence]): CompilerIO[Sourced[MonomorphicExpression]] =
      node.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          walk(body, evidence).map(walked =>
            node.as(node.value.copy(expression = MonomorphicExpression.FunctionLiteral(name, parameterType, walked)))
          )
        case _: MonomorphicExpression.FunctionApplication                    =>
          val (head, args) = spineOf(node)
          head.value.expression match {
            case reference: MonomorphicExpression.MonomorphicValueReference
                if ImplementationParameters.isBinder(reference.valueName.value) =>
              install(node, head, reference, args, evidence)
            case reference: MonomorphicExpression.MonomorphicValueReference  =>
              call(node, head, reference, args, evidence)
            case _                                                            =>
              for {
                walkedHead <- walk(head, evidence)
                walkedArgs <- args.traverse(walk(_, evidence))
              } yield rebuilt(node, walkedHead, walkedArgs)
          }
        case reference: MonomorphicExpression.MonomorphicValueReference      =>
          call(node, node, reference, Seq.empty, evidence)
        case _                                                               => node.pure[CompilerIO]
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
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        orv     <- getFactOrAbort(OperatorResolvedValue.Key(reference.valueName.value, Platform.Runtime))
        count    = SignatureView.of(orv.signature).parameters.size - 1
        marker  <- markerOf(reference.valueName)
        ability <- abilityOfMarker(marker).flatMap {
                     case Some(ability) => ability.pure[CompilerIO]
                     case None          => compilerAbort[AbilityFQN](head.as("Internal: a `with` names no implementation."))
                   }
        walked  <- args.zipWithIndex.traverse { case (arg, index) =>
                     walk(arg, if (index == count) Evidence.Installed(marker, ability, evidence) +: evidence else evidence)
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
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      reference.valueName.value.name.qualifier match {
        case Qualifier.Ability(abilityName) =>
          val ability = AbilityFQN(reference.valueName.value.moduleName, abilityName)
          isEffect(ability).flatMap {
            case true  => dispatch(node, head, reference, ability, args, evidence)
            case false => written(node, head, reference, args, evidence)
          }
        case _                              => written(node, head, reference, args, evidence)
      }

    private def written(
        node: Sourced[MonomorphicExpression],
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      getFactIfProduced(OperatorResolvedValue.Key(reference.valueName.value, Platform.Runtime)).flatMap {
        case Some(orv) if orv.paramConstraints.nonEmpty || orv.effectRow.parameterEffects.nonEmpty =>
          for {
            callee    <- checkedAt(reference.valueName.value, reference.typeArguments)
            arguments <- withEvidence(reference.typeArguments, callee.effects.received, evidence, head)
            walked    <- blocks(callee.copy(typeArguments = arguments), 0, args, evidence)
          } yield rebuilt(node, retarget(head, reference.copy(typeArguments = arguments)), walked)
        case _                                                                                    =>
          args.traverse(walk(_, evidence)).map(rebuilt(node, head, _))
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
              for {
                found   <- evidenceAt(evidence, entry.entry, at)
                binding <- bindingOf(found, entry.entry)
                _       <- agrees(arguments(entry.binderIndex), binding, entry.entry, at)
              } yield arguments.updated(entry.binderIndex, binding)
          }
      }

    /** A named implementation the checked body already holds — a `with` in this body wrote it — must be the one the
      * evidence runs; its own bindings may still be left out, so only the implementation is compared.
      */
    private def agrees(written: GroundValue, binding: GroundValue, entry: Entry, at: Sourced[?]): CompilerIO[Unit] =
      ImplementationBinding.fromGround(written) match {
        case Some(_: ImplementationBinding.Implementation) if !sameImplementation(written, binding) =>
          compilerAbort(
            at.as(
              s"Internal: the evidence for the effect '${entry.ability.abilityName}' here runs ${describe(binding)}, but " +
                s"the binding written here is ${describe(written)}."
            )
          )
        case _                                                                                      => ().pure[CompilerIO]
      }

    private def evidenceAt(evidence: Seq[Evidence], entry: Entry, at: Sourced[?]): CompilerIO[Evidence] =
      evidence.find(answers(_, entry)) match {
        case Some(found) => found.pure[CompilerIO]
        case None        =>
          compilerAbort(at.as(s"Internal: no evidence for the effect '${entry.ability.abilityName}' is in scope here."))
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
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        arity   <- abilityArity(ability)
        entry    = Entry(ability, reference.typeArguments.take(arity).drop(1))
        found    = evidence.find(answers(_, entry))
        binding <- found.traverse(bindingOf(_, entry))
        walked  <- ImplementationBinding.fromGround(binding.getOrElse(defaultBinding)) match {
                     case Some(implementation: ImplementationBinding.Implementation) =>
                       dispatchTo(node, head, reference, entry, binding.get, implementation, args, evidence)
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
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] = {
      val member = reference.valueName.value
      val method = implementation.method(member.name.name)
      for {
        _          <- getFactOrAbort(NamedImplementationCheck.Key(implementation.head, Platform.Runtime))
        solved     <- NamedImplementationArguments.solve(implementation, entry.arguments, Platform.Runtime).flatMap {
                        case Some(arguments) => arguments.pure[CompilerIO]
                        case None            => unmatchedBinding(head, entry, implementation)
                      }
        names      <- getFactOrAbort(UnifiedModuleNames.Key(method.moduleName, Platform.Runtime))
        _          <- if (names.names.contains(method.name)) ().pure[CompilerIO] else missingClause(head, member, implementation)
        memberOrv  <- getFactOrAbort(OperatorResolvedValue.Key(member, Platform.Runtime))
        clauseOrv  <- getFactOrAbort(OperatorResolvedValue.Key(method, Platform.Runtime))
        forwards    = DeclaredEntries.forwarded(memberOrv, clauseOrv)
        // A per-call entry of the clause is the call's (`docs/effects.md` §3.4): the evidence where the operation is
        // performed answers it, not the implementation's environment.
        forwarded   = forwards.foldLeft(solved) { case (acc, (clauseIndex, memberIndex)) =>
                        reference.typeArguments.lift(memberIndex).filter(_ => acc.isDefinedAt(clauseIndex)).fold(acc)(acc.updated(clauseIndex, _))
                      }
        clause     <- checkedAt(method, forwarded)
        perCall     = forwards.map(_._1).toSet
        arguments  <- withEvidence(forwarded, clause.effects.received.filter(r => perCall.contains(r.binderIndex)), evidence, head)
        own        <- ownParameterCount(implementation.head)
        walked     <- blocks(clause.copy(typeArguments = arguments), own, args, evidence)
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
      getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, Platform.Runtime)).flatMap { names =>
        if (!names.names.contains(declaration.name)) 0.pure[CompilerIO]
        else
          getFactOrAbort(OperatorResolvedValue.Key(declaration, Platform.Runtime))
            .map(orv => SignatureView.of(orv.signature).parameters.size)
      }
    }

    /** The number of an ability's own type parameters, its binding first: its marker's binders. */
    private def abilityArity(ability: AbilityFQN): CompilerIO[Int] =
      getFactOrAbort(
        OperatorResolvedValue.Key(
          ValueFQN(ability.moduleName, QualifiedName(ability.abilityName, Qualifier.Ability(ability.abilityName))),
          Platform.Runtime
        )
      ).map(orv => SignatureView.of(orv.signature).binders.size)

    /** Walk `args`, each one at a slot of `callee` — parameter `offset + index` — with what that slot gives it. */
    private def blocks(
        callee: Specialisation,
        offset: Int,
        args: Seq[Sourced[MonomorphicExpression]],
        evidence: Seq[Evidence]
    ): CompilerIO[Seq[Sourced[MonomorphicExpression]]] = {
      val slots = callee.effects.slots.map(slot => slot.parameterIndex -> slot).toMap
      args.zipWithIndex.traverse { case (arg, index) =>
        slots.get(index + offset) match {
          case Some(slot) => block(arg, callee, slot, evidence)
          case None       => walk(arg, evidence)
        }
      }
    }

    /** A block at a callee's slot is given evidence for each entry the slot lists: one it rides at the caller's
      * binding, which the callee received; one it supplies at the implementation the slot names, or at `Default`.
      */
    private def block(
        arg: Sourced[MonomorphicExpression],
        callee: Specialisation,
        slot: MonomorphicEffects.Slot,
        evidence: Seq[Evidence]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      arg.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          for {
            offered <- listed(callee, slot).flatMap(_.traverse {
                        case Listed.Rides(entry, binding)         => Evidence.Given(entry, binding, bySlot = true).pure[CompilerIO]
                        case Listed.Supplied(entry, None)         => Evidence.Given(entry, defaultBinding).pure[CompilerIO]
                        case Listed.Supplied(entry, Some(marker)) =>
                          slotBinding(callee, slot.parameterIndex, entry, marker).map(Evidence.Given(entry, _, bySlot = true))
                      })
            walked  <- walk(body, offered ++ evidence)
          } yield arg.as(arg.value.copy(expression = MonomorphicExpression.FunctionLiteral(name, parameterType, walked)))
        case _                                                                =>
          compilerAbort(arg.as("Internal: a block at a row-typed slot is not a thunk."))
      }

    private def unboundEffect(at: Sourced[?], ability: AbilityFQN): CompilerIO[Sourced[MonomorphicExpression]] =
      compilerError(
        at.as(s"Nothing binds an implementation of the effect '${ability.abilityName}' here, and an effect has no default."),
        Seq(
          "An effect is bound by a `with`, by a parameter's slot that names an implementation, or by the caller of a " +
            "definition declaring it; a body-less discharger's slot names one only where a platform layer bodies it."
        )
      ) >> abort[Sourced[MonomorphicExpression]]

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
