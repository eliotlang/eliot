package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicEffects, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.collection.mutable

/** **Environment threading** — effects D20c option 5, decided as threaded after monomorphization (`docs/effects.md`
  * §11, "The `transcriptOf` spike"). An implementation is compile-time code and a runtime environment: the code is the
  * binding the row phase wrote and monomorphization specialised on, and the environment — a frame, a cell, an
  * implementation's parameters — is passed here, at the `WovenValue` seam, and never searched for.
  *
  * {{{
  * increment[State@Default](env)  = putState(env, state(env) + 1)          -- a received entry is a hidden parameter
  * runStateToPair(initial, p)     = withCellInternal(initial, cell -> p(cell), …)   -- a slot's block takes its frame
  * main                           = …(runStateToFinalState(5, cell -> { increment(cell); double(cell) }))
  * }}}
  *
  * Three routes carry an environment, all over the woven body:
  *
  *   - **a received entry is a hidden parameter.** A definition receives one frame per entry of its declared row and
  *     per `~` constraint ([[MonomorphicEffects.received]]), ahead of its own parameters, and a reference to it passes
  *     the frame in scope for each.
  *   - **a block takes what its slot gives.** A row-typed slot's thunk took `unit`; it now takes the frames of the
  *     entries the slot supplies, and running the block passes them, found in scope where it runs.
  *   - **a dispatched operation gets its frame's parts.** A reference the checker dispatched to an implementation's
  *     clause passes the frame of the entry it was dispatched from: the frames its clauses perform on, then the
  *     implementation's own parameters, which the clause receives ahead of the member's.
  *
  * **A frame is found by entry, at ground arguments** (2(c)), so `{Throw[IoError], Throw[GitError]}` is two frames. The
  * binding the reference was written with must be the frame's — the code and the environment come from the same
  * place — and a disagreement is an error, never a silent pick. An entry with no frame in scope is given its default,
  * which works only for a default with no parameters: the platform's `Console`, an ability's instance. A default that
  * needs parameters, with nothing in scope to give them, is the error D19 asked for — an undischarged control effect.
  *
  * A **named implementation bound by a body `with`** is not in scope as a frame, because the row phase erases the
  * `with`. Its frame is built at the reference from the frames in scope there, which are the frames at the `with`:
  * the row phase rejects a use of a `with`'s binding below a nearer binding of an effect its clauses perform.
  *
  * Nothing here changes a definition that receives and supplies no environment: its frames have no parts, a block
  * with nothing to take keeps its `unit`, and the woven body is the monomorphic one.
  */
object EnvironmentThreading {

  /** The woven signature and body of `mv`. */
  def thread(mv: MonomorphicValue): CompilerIO[(GroundValue, Option[Sourced[MonomorphicExpression.Expression]])] =
    new Threader(mv).run

  /** One part of an environment: a parameter in scope holding it, and its type. */
  private case class Part(name: String, tpe: GroundValue)

  /** A frame in scope: the entry it answers, the binding its code was written with, the frames its implementation's
    * clauses perform on, and the implementation's own parameters.
    */
  private case class Frame(entry: Entry, binding: GroundValue, clauses: Seq[Frame], own: Seq[Part]) {
    def parts: Seq[Part] = clauses.flatMap(_.parts) ++ own
  }

  /** What an implementation's environment is made of, before anything holds it: the clause entries with their
    * bindings and shapes, and the types of the implementation's own parameters.
    */
  private case class Shape(clauses: Seq[(Entry, GroundValue, Shape)], own: Seq[GroundValue]) {
    def types: Seq[GroundValue] = clauses.flatMap(_._3.types) ++ own
  }

  private val emptyShape = Shape(Seq.empty, Seq.empty)

  private val defaultBinding: GroundValue = ImplementationBinding.defaultGround

  private class Threader(mv: MonomorphicValue) {
    private var counter = 0

    private def fresh(): String = {
      counter += 1
      s"$$env$counter"
    }

    def run: CompilerIO[(GroundValue, Option[Sourced[MonomorphicExpression.Expression]])] =
      for {
        received  <- receivedFrames(mv)
        _         <- if (mv.runtime.isEmpty && received.exists(_.parts.nonEmpty))
                       compilerAbort[Unit](
                         mv.name.as("Internal: a body-less value cannot receive an environment it would have to pass on.")
                       )
                     else ().pure[CompilerIO]
        signature <- signatureOf(mv)
        body      <- mv.runtime.traverse { body =>
                       walkDefinition(body.map(MonomorphicExpression(mv.signature, _)), mv, received).map(walked =>
                         received
                           .flatMap(_.parts)
                           .foldRight(walked) { (part, inner) =>
                             inner.as(
                               MonomorphicExpression(
                                 functionType(part.tpe, inner.value.expressionType),
                                 MonomorphicExpression.FunctionLiteral(inner.as(part.name), part.tpe, inner)
                               )
                             )
                           }
                           .map(_.expression)
                       )
                     }
      } yield (signature, body)

    /** The frames a definition receives, each held by fresh hidden parameters. */
    private def receivedFrames(value: MonomorphicValue): CompilerIO[Seq[Frame]] =
      value.effects.received.traverse { received =>
        val binding = value.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding)
        shapeOf(received.entry, binding).map(shape => held(received.entry, binding, shape))
      }

    /** A frame whose every part is a fresh parameter. */
    private def held(entry: Entry, binding: GroundValue, shape: Shape): Frame =
      Frame(
        entry,
        binding,
        shape.clauses.map { case (clauseEntry, clauseBinding, clauseShape) =>
          held(clauseEntry, clauseBinding, clauseShape)
        },
        shape.own.map(tpe => Part(fresh(), tpe))
      )

    /** Peel the definition's own value parameters — retyping a row-typed one to the block it now is — and walk the body
      * under the frames it receives.
      */
    private def walkDefinition(
        body: Sourced[MonomorphicExpression],
        value: MonomorphicValue,
        received: Seq[Frame]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        slots  <- value.effects.slots.traverse(slot => slotDomain(value, slot).map(slot.parameterIndex -> _)).map(_.toMap)
        walked <- peel(body, 0, value.naturalArity.getOrElse(0), slots, Context(received, Map.empty, Map.empty))
      } yield walked

    private def peel(
        node: Sourced[MonomorphicExpression],
        index: Int,
        remaining: Int,
        slots: Map[Int, Seq[GroundValue]],
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      node.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, inner) if remaining > 0 =>
          val (newType, inside) = slots.get(index) match {
            case Some(domain) =>
              val retyped = blockType(parameterType, domain)
              (retyped, context.declare(name.value, retyped, Some(index)))
            case None         => (parameterType, context.shadow(name.value))
          }
          peel(inner, index + 1, remaining - 1, slots, inside).map { walked =>
            node.as(
              MonomorphicExpression(
                functionType(newType, walked.value.expressionType),
                MonomorphicExpression.FunctionLiteral(name, newType, walked)
              )
            )
          }
        case _                                                                                   => walk(node, context)
      }

    /** The lexical context: the frames in scope innermost first, the parameters whose type the threading changed, and
      * this definition's row-typed parameters by index — running one passes its frames.
      */
    private case class Context(frames: Seq[Frame], types: Map[String, GroundValue], runs: Map[String, Int]) {
      def push(more: Seq[Frame]): Context = copy(frames = more ++ frames)
      def shadow(name: String): Context  = copy(types = types - name, runs = runs - name)

      def declare(name: String, tpe: GroundValue, run: Option[Int]): Context =
        copy(types = types.updated(name, tpe), runs = run.fold(runs - name)(runs.updated(name, _)))
    }

    private def walk(node: Sourced[MonomorphicExpression], context: Context): CompilerIO[Sourced[MonomorphicExpression]] =
      node.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          walk(body, context.shadow(name.value)).map { walked =>
            node.as(
              MonomorphicExpression(
                functionType(parameterType, walked.value.expressionType),
                MonomorphicExpression.FunctionLiteral(name, parameterType, walked)
              )
            )
          }

        case MonomorphicExpression.ParameterReference(name) =>
          context.types.get(name.value).fold(node.pure[CompilerIO])(tpe =>
            node.as(node.value.copy(expressionType = tpe)).pure[CompilerIO]
          )

        case _: MonomorphicExpression.IntegerLiteral | _: MonomorphicExpression.StringLiteral => node.pure[CompilerIO]

        case _ =>
          val (head, args) = spineOf(node)
          head.value.expression match {
            case reference: MonomorphicExpression.MonomorphicValueReference  =>
              call(head, reference, args, context)
            case MonomorphicExpression.ParameterReference(name)
                if context.runs.contains(name.value) && args.sizeIs == 1 && isUnit(args.head) =>
              runBlock(head, name, args.head, context)
            case _                                                            =>
              for {
                walkedHead <- walk(head, context)
                walkedArgs <- args.traverse(walk(_, context))
              } yield applyAll(walkedHead, walkedArgs, keepTypes = Some(applicationTypes(node)))
          }
      }

    /** A reference to a value, applied to `args`: pass the frames it receives, and give each block at one of its slots
      * the frames that slot supplies.
      */
    private def call(
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      calleeOf(reference).flatMap {
        case None         =>
          args.traverse(walk(_, context)).map(walked => applyAll(head, walked, keepTypes = Some(applicationTypes(head, args))))
        case Some(callee) =>
          for {
            passed    <- reference.dispatchedFrom match {
                           case Some(dispatch) => dispatchedParts(callee, dispatch, head, context)
                           case None           => receivedParts(callee, reference.typeArguments, head, context)
                         }
            offset     = passed.own
            slots      = callee.effects.slots.map(slot => slot.parameterIndex -> slot).toMap
            walkedArgs <- args.zipWithIndex.traverse { case (arg, index) =>
                            slots.get(index + offset) match {
                              case Some(slot) => block(arg, callee, slot, context)
                              case None       => walk(arg, context)
                            }
                          }
            domains   <- callee.effects.slots.traverse(slot => slotDomain(callee, slot).map(slot.parameterIndex - offset -> _))
            // The reference's own type, threaded: the parts it is passed ahead of it, and each slot retyped to the block
            // it now is. Read off the reference rather than the callee's signature, which the checker may have settled
            // differently at this use (a guarded return, a partial key).
            retyped    = passed.parts.map(_.tpe).foldRight(
                           domains.foldLeft(head.value.expressionType) { case (tpe, (index, domain)) =>
                             if (index >= 0) mapParameter(tpe, index, blockType(_, domain)) else tpe
                           }
                         )(functionType)
          } yield applyAll(
            head.as(head.value.copy(expressionType = retyped)),
            passed.parts.map(partReference(head, _)) ++ walkedArgs,
            keepTypes = None
          )
      }

    /** What a reference passes: the parts, and for a dispatched one how many of them are the implementation's own. */
    private case class Passed(parts: Seq[Part], own: Int)

    private def receivedParts(
        callee: MonomorphicValue,
        typeArguments: Seq[GroundValue],
        at: Sourced[?],
        context: Context
    ): CompilerIO[Passed] =
      callee.effects.received
        .traverse(received =>
          lookup(context.frames, received.entry, typeArguments.lift(received.binderIndex).getOrElse(defaultBinding), at)
        )
        .map(frames => Passed(frames.flatMap(_.parts), 0))

    /** A dispatched clause receives its own entries first — the clause row's frames come from the frame it was dispatched
      * on, anything else from scope — and then the implementation's own parameters.
      */
    private def dispatchedParts(
        clause: MonomorphicValue,
        dispatch: MonomorphicEffects.Dispatch,
        at: Sourced[?],
        context: Context
    ): CompilerIO[Passed] =
      for {
        frame    <- lookup(context.frames, dispatch.entry, dispatch.binding, at)
        received <- clause.effects.received.traverse { received =>
                      val binding = clause.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding)
                      frame.clauses.find(candidate => candidate.entry == received.entry) match {
                        case Some(clauseFrame) => clauseFrame.pure[CompilerIO]
                        case None              => lookup(context.frames, received.entry, binding, at)
                      }
                    }
      } yield Passed(received.flatMap(_.parts) ++ frame.own, frame.own.size)

    /** A block at a callee's slot: the thunk the row phase wrapped it in now takes the frames the slot supplies. */
    private def block(
        arg: Sourced[MonomorphicExpression],
        callee: MonomorphicValue,
        slot: MonomorphicEffects.Slot,
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      arg.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          for {
            frames  <- givenFrames(callee, slot, context, arg)
            parts    = frames.flatMap(_.parts)
            inner   <- walk(body, context.shadow(name.value).push(frames))
          } yield
            if (parts.isEmpty)
              arg.as(
                MonomorphicExpression(
                  functionType(parameterType, inner.value.expressionType),
                  MonomorphicExpression.FunctionLiteral(name, parameterType, inner)
                )
              )
            else
              parts.foldRight(inner) { (part, acc) =>
                arg.as(
                  MonomorphicExpression(
                    functionType(part.tpe, acc.value.expressionType),
                    MonomorphicExpression.FunctionLiteral(arg.as(part.name), part.tpe, acc)
                  )
                )
              }
        case _                                                                =>
          compilerAbort(arg.as("Internal: a block at a row-typed slot is not a thunk."))
      }

    /** The frames a block at `slot` of `callee` is given, each held by a fresh parameter of the block: one per entry the
      * slot **supplies** (an entry the callee's own row lacks). A slot naming an implementation with `with` gives that
      * implementation, whose clauses perform on the frames the slot supplies for them and on the caller's for the rest.
      */
    private def givenFrames(
        callee: MonomorphicValue,
        slot: MonomorphicEffects.Slot,
        context: Context,
        arg: Sourced[?]
    ): CompilerIO[Seq[Frame]] =
      supplied(callee, slot).flatMap(_.traverse {
        case (entry, None)         => shapeOf(entry, defaultBinding).map(shape => held(entry, defaultBinding, shape))
        case (entry, Some(marker)) =>
          namedClauses(marker).flatMap { clauses =>
            clauses
              .traverse { clauseEntry =>
                if (rides(callee, clauseEntry)) lookupAny(context.frames, clauseEntry, arg)
                else shapeOf(clauseEntry, defaultBinding).map(shape => held(clauseEntry, defaultBinding, shape))
              }
              .map(clauseFrames => Frame(entry, namedBinding(marker), clauseFrames, Seq.empty))
          }
      })

    /** Running one of this definition's row-typed parameters passes the frames its slot supplies, found where it runs. */
    private def runBlock(
        head: Sourced[MonomorphicExpression],
        name: Sourced[String],
        unit: Sourced[MonomorphicExpression],
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] = {
      val index = context.runs(name.value)
      val slot  = mv.effects.slots.find(_.parameterIndex == index).getOrElse(MonomorphicEffects.Slot(index, Seq.empty))
      for {
        frames <- supplied(mv, slot).flatMap(_.traverse {
                    case (entry, None)         => lookup(context.frames, entry, defaultBinding, name)
                    case (entry, Some(marker)) =>
                      namedClauses(marker).flatMap(
                        _.filterNot(rides(mv, _)).traverse(clause => lookup(context.frames, clause, defaultBinding, name))
                      ).map(clauseFrames => Frame(entry, namedBinding(marker), clauseFrames, Seq.empty))
                  })
        parts   = frames.flatMap(_.parts)
        retyped = context.types.get(name.value).fold(head)(tpe => head.as(head.value.copy(expressionType = tpe)))
      } yield
        if (parts.isEmpty) applyAll(retyped, Seq(unit), keepTypes = None)
        else applyAll(retyped, parts.map(partReference(name, _)), keepTypes = None)
    }

    /** The entries `slot` supplies — those `value`'s own received entries lack — each with the implementation the slot
      * names for it with `with`, read off the slot's declared type.
      */
    private def supplied(
        value: MonomorphicValue,
        slot: MonomorphicEffects.Slot
    ): CompilerIO[Seq[(Entry, Option[ValueFQN])]] =
      slotImplementations(value, slot.parameterIndex).map { named =>
        slot.entries.filterNot(rides(value, _)).map(entry => entry -> named.get(entry.ability))
      }

    private def rides(value: MonomorphicValue, entry: Entry): Boolean =
      value.effects.received.exists(_.entry == entry)

    /** The implementations a slot's declared type names with `with`, by ability. The row phase strips them from the
      * signature it hands on, so they are read off the operator-resolved declaration, as the row phase read them.
      */
    private def slotImplementations(value: MonomorphicValue, index: Int): CompilerIO[Map[com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN, ValueFQN]] =
      getFactIfProduced(OperatorResolvedValue.Key(value.vfqn, Platform.Runtime)).flatMap {
        case None      => Map.empty.pure[CompilerIO]
        case Some(orv) =>
          SignatureView
            .of(orv.signature)
            .parameters
            .lift(index)
            .toSeq
            .flatMap(tpe => slotMarkers(tpe.value))
            .traverse(marker => abilityOfMarker(marker).map(_.map(_ -> marker)))
            .map(_.flatten.toMap)
      }

    private def slotMarkers(tpe: OperatorResolvedExpression): Seq[ValueFQN] = tpe match {
      case OperatorResolvedExpression.WithBinding(subject, implementation) => slotMarkers(subject.value) :+ implementation.value
      case _                                                               => Seq.empty
    }

    private def abilityOfMarker(marker: ValueFQN): CompilerIO[Option[com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN]] =
      getFactIfProduced(OperatorResolvedValue.Key(marker, Platform.Runtime)).map(_.flatMap(_.name.value.qualifier match {
        case com.vanillasource.eliot.eliotc.resolve.fact.Qualifier.AbilityImplementation(ability, _, _) => Some(ability)
        case _                                                                                          => None
      }))

    /** The entries a named implementation's clauses perform, at the bindings a slot's `with` writes for them. */
    private def namedClauses(marker: ValueFQN): CompilerIO[Seq[Entry]] =
      implementationClauses(marker, None).map(_.map(_._1))

    private def namedBinding(marker: ValueFQN): GroundValue =
      GroundValue.Structure(marker, Seq.empty, GroundValue.Type)

    /** The type a row-typed parameter takes once its block takes `domain`: `Unit => A` becomes `T₁ => … => A`. */
    private def blockType(parameterType: GroundValue, domain: Seq[GroundValue]): GroundValue =
      parameterType.asFunctionType match {
        case Some((_, result)) if domain.nonEmpty => domain.foldRight(result)(functionType)
        case _                                    => parameterType
      }

    /** What a block at `slot` of `value` takes: the parts of the frames the slot supplies, in order. */
    private def slotDomain(value: MonomorphicValue, slot: MonomorphicEffects.Slot): CompilerIO[Seq[GroundValue]] =
      supplied(value, slot).flatMap(_.flatTraverse {
        case (entry, None)         => shapeOf(entry, defaultBinding).map(_.types)
        case (entry, Some(marker)) =>
          namedClauses(marker).flatMap(
            _.filterNot(rides(value, _)).flatTraverse(clause => shapeOf(clause, defaultBinding).map(_.types))
          )
      })

    /** A value's woven signature: the parts of the frames it receives, then its own parameters with each row-typed one
      * retyped to the block it now is.
      */
    private def signatureOf(value: MonomorphicValue): CompilerIO[GroundValue] =
      for {
        received <- value.effects.received.traverse { received =>
                      val binding = value.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding)
                      shapeOf(received.entry, binding).map(_.types)
                    }
        slots    <- value.effects.slots.traverse(slot => slotDomain(value, slot).map(slot.parameterIndex -> _))
      } yield {
        val own = slots.foldLeft(value.signature) { case (signature, (index, domain)) =>
          mapParameter(signature, index, blockType(_, domain))
        }
        received.flatten.foldRight(own)(functionType)
      }

    /** The callee a reference names, when it is a monomorphized value that receives or supplies anything; [[None]] when
      * its declaration has no constraint and no row-typed parameter, which is every type reference and most values.
      */
    private def calleeOf(reference: MonomorphicExpression.MonomorphicValueReference): CompilerIO[Option[MonomorphicValue]] =
      getFactIfProduced(OperatorResolvedValue.Key(reference.valueName.value, Platform.Runtime)).flatMap {
        case Some(orv) if orv.paramConstraints.nonEmpty || orv.effectRow.parameterEffects.nonEmpty =>
          getFactOrAbort(MonomorphicValue.Key(reference.valueName.value, reference.typeArguments)).map(Some(_))
        case _                                                                                    =>
          if (reference.dispatchedFrom.isDefined || isImplementationClause(reference.valueName.value))
            getFactOrAbort(MonomorphicValue.Key(reference.valueName.value, reference.typeArguments)).map(Some(_))
          else None.pure[CompilerIO]
      }

    private def isImplementationClause(vfqn: ValueFQN): Boolean =
      vfqn.name.qualifier match {
        case _: Qualifier.AbilityImplementation => true
        case _                                  => false
      }

    /** The frame in scope for `entry`, which the reference was written to run on `binding`. */
    private def lookup(frames: Seq[Frame], entry: Entry, binding: GroundValue, at: Sourced[?]): CompilerIO[Frame] =
      frames.find(_.entry == entry) match {
        case Some(frame) if sameImplementation(frame.binding, binding) => frame.pure[CompilerIO]
        case Some(frame) if !isNamed(binding)                          =>
          compilerAbort(
            at.as(
              s"This runs the effect '${entry.ability.abilityName}' on ${describe(binding)}, but the nearest frame of it " +
                s"here runs ${describe(frame.binding)}."
            )
          )
        case _                                                         => built(frames, entry, binding, at)
      }

    /** The frame in scope for `entry` whatever binding it holds: a slot implementation's clause the callee rides. */
    private def lookupAny(frames: Seq[Frame], entry: Entry, at: Sourced[?]): CompilerIO[Frame] =
      frames.find(_.entry == entry) match {
        case Some(frame) => frame.pure[CompilerIO]
        case None        => built(frames, entry, defaultBinding, at)
      }

    /** A frame nothing in scope holds: a default, or a named implementation a body `with` bound, built from the frames in
      * scope for its clauses. Its own parameters have nothing to come from, which is the error for a default that needs
      * them — a control effect nothing discharges.
      */
    private def built(frames: Seq[Frame], entry: Entry, binding: GroundValue, at: Sourced[?]): CompilerIO[Frame] =
      shapeOf(entry, binding).flatMap { shape =>
        if (shape.own.nonEmpty)
          compilerAbort(
            at.as(
              s"The effect '${entry.ability.abilityName}' is performed here with nothing to discharge it: its " +
                "implementation needs a frame, and only a discharger installs one."
            )
          )
        else
          shape.clauses
            .traverse { case (clauseEntry, clauseBinding, _) => lookup(frames, clauseEntry, clauseBinding, at) }
            .map(clauses => Frame(entry, binding, clauses, Seq.empty))
      }

    private def sameImplementation(left: GroundValue, right: GroundValue): Boolean =
      (ImplementationBinding.fromGround(left), ImplementationBinding.fromGround(right)) match {
        case (Some(ImplementationBinding.Default), Some(ImplementationBinding.Default))                       => true
        case (Some(ImplementationBinding.Implementation(a, _)), Some(ImplementationBinding.Implementation(b, _))) =>
          a == b
        case (None, None)                                                                                     => left == right
        case _                                                                                                => false
      }

    private def isNamed(binding: GroundValue): Boolean =
      ImplementationBinding.fromGround(binding) match {
        case Some(_: ImplementationBinding.Implementation) => true
        case _                                             => false
      }

    private def describe(binding: GroundValue): String =
      ImplementationBinding.fromGround(binding) match {
        case Some(ImplementationBinding.Implementation(head, _)) =>
          head.name.qualifier match {
            case Qualifier.AbilityImplementation(_, _, Some(name)) => s"the implementation '$name'"
            case _                                                 => "the default implementation"
          }
        case _                                                   => "the default implementation"
      }

    /** The shape of the environment of `binding` for `entry`: the implementation it runs on — the two-site search's
      * answer for `Default` — with its clause entries' shapes and its own parameters' types.
      */
    private def shapeOf(entry: Entry, binding: GroundValue): CompilerIO[Shape] =
      shapes.get((entry, binding)) match {
        case Some(shape) => shape.pure[CompilerIO]
        case None        => computeShape(entry, binding).flatTap(shape => shapes.update((entry, binding), shape).pure[CompilerIO])
      }

    private val shapes: mutable.Map[(Entry, GroundValue), Shape] = mutable.Map.empty

    private def computeShape(entry: Entry, binding: GroundValue): CompilerIO[Shape] =
      implementationOf(entry, binding).flatMap {
        case None                        => emptyShape.pure[CompilerIO]
        case Some((marker, typeArguments)) =>
          for {
            clauses <- implementationClauses(marker, Some(typeArguments))
            shaped  <- clauses.traverse { case (clauseEntry, clauseBinding) =>
                         shapeOf(clauseEntry, clauseBinding).map(shape => (clauseEntry, clauseBinding, shape))
                       }
            own     <- ownParameterTypes(marker, typeArguments)
          } yield Shape(shaped, own)
      }

    /** The implementation `binding` runs on for `entry`, as its marker and type arguments. */
    private def implementationOf(entry: Entry, binding: GroundValue): CompilerIO[Option[(ValueFQN, Seq[GroundValue])]] =
      ImplementationBinding.fromGround(binding) match {
        case Some(ImplementationBinding.Implementation(head, typeArguments)) => Some((head, typeArguments)).pure[CompilerIO]
        case _                                                               =>
          val abilityMarker = ValueFQN(
            entry.ability.moduleName,
            QualifiedName(entry.ability.abilityName, Qualifier.Ability(entry.ability.abilityName))
          )
          getFactIfProduced(AbilityImplementation.Key(abilityMarker, entry.arguments, Platform.Runtime))
            .map(_.flatMap(_.resolution.resolved))
      }

    /** The entries an implementation's clauses perform, with the bindings its type arguments hold for them — read off
      * its marker's specialisation, whose clause row every clause carries. `typeArguments` [[None]] is a slot's `with`,
      * whose clause bindings the caller writes as `Default`. An implementation whose clauses declare no row needs no
      * specialisation read at all.
      */
    private def implementationClauses(
        marker: ValueFQN,
        typeArguments: Option[Seq[GroundValue]]
    ): CompilerIO[Seq[(Entry, GroundValue)]] =
      getFactOrAbort(OperatorResolvedValue.Key(marker, Platform.Runtime)).flatMap { orv =>
        val performed = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
        if (performed.isEmpty) Seq.empty.pure[CompilerIO]
        else {
          val arguments =
            typeArguments.getOrElse(Seq.fill(SignatureView.of(orv.signature).binders.size)(defaultBinding))
          getFactOrAbort(MonomorphicValue.Key(marker, arguments)).map(
            _.effects.received
              .filter(received => performed.contains(received.entry.ability))
              .map(received => received.entry -> arguments.lift(received.binderIndex).getOrElse(defaultBinding))
          )
        }
      }

    /** The types of an implementation's own parameters at its type arguments, read off its parameters declaration's
      * specialisation ([[ImplementationParameters]]); none when it declares none.
      */
    private def ownParameterTypes(marker: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Seq[GroundValue]] = {
      val declaration = ImplementationParameters.of(marker)
      getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, Platform.Runtime)).flatMap { names =>
        if (!names.names.contains(declaration.name)) Seq.empty.pure[CompilerIO]
        else
          for {
            orv   <- getFactOrAbort(OperatorResolvedValue.Key(declaration, Platform.Runtime))
            ground <- getFactOrAbort(MonomorphicValue.Key(declaration, typeArguments))
          } yield parameterTypes(ground.signature, SignatureView.of(orv.signature).parameters.size)
      }
    }
  }

  private def parameterTypes(signature: GroundValue, count: Int): Seq[GroundValue] =
    if (count <= 0) Seq.empty
    else
      signature.asFunctionType match {
        case Some((domain, codomain)) => domain +: parameterTypes(codomain, count - 1)
        case None                     => Seq.empty
      }

  private def mapParameter(signature: GroundValue, index: Int, f: GroundValue => GroundValue): GroundValue =
    signature.asFunctionType match {
      case Some((domain, codomain)) if index == 0 => functionType(f(domain), codomain)
      case Some((domain, codomain))               => functionType(domain, mapParameter(codomain, index - 1, f))
      case None                                   => signature
    }

  private def functionType(domain: GroundValue, codomain: GroundValue): GroundValue =
    GroundValue.Structure(WellKnownTypes.functionDataTypeFQN, Seq(domain, codomain), GroundValue.Type)

  private def isUnit(node: Sourced[MonomorphicExpression]): Boolean =
    node.value.expression match {
      case MonomorphicExpression.MonomorphicValueReference(name, _, _) => name.value == WellKnownTypes.unitValueFQN
      case _                                                           => false
    }

  private def partReference(at: Sourced[?], part: Part): Sourced[MonomorphicExpression] =
    at.as(MonomorphicExpression(part.tpe, MonomorphicExpression.ParameterReference(at.as(part.name))))

  /** An application spine: its head and its arguments in order. */
  private def spineOf(node: Sourced[MonomorphicExpression]): (Sourced[MonomorphicExpression], Seq[Sourced[MonomorphicExpression]]) =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        val (head, args) = spineOf(target)
        (head, args :+ argument)
      case _                                                           => (node, Seq.empty)
    }

  /** The types of each application node of a spine, innermost first, as the checker left them. */
  private def applicationTypes(node: Sourced[MonomorphicExpression]): Seq[GroundValue] =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, _) => applicationTypes(target) :+ node.value.expressionType
      case _                                                    => Seq.empty
    }

  private def applicationTypes(head: Sourced[MonomorphicExpression], args: Seq[Sourced[MonomorphicExpression]]): Seq[GroundValue] =
    args.scanLeft(head.value.expressionType)((tpe, _) => tpe.asFunctionType.fold(tpe)(_._2)).drop(1)

  /** Apply `head` to `args`. Each node's type is the codomain of its target's, unless `keepTypes` gives them — a spine
    * whose head the threading did not retype keeps the checker's.
    */
  private def applyAll(
      head: Sourced[MonomorphicExpression],
      args: Seq[Sourced[MonomorphicExpression]],
      keepTypes: Option[Seq[GroundValue]]
  ): Sourced[MonomorphicExpression] =
    args.zipWithIndex.foldLeft(head) { case (target, (arg, index)) =>
      val tpe = keepTypes
        .flatMap(_.lift(index))
        .getOrElse(target.value.expressionType.asFunctionType.fold(target.value.expressionType)(_._2))
      arg.as(MonomorphicExpression(tpe, MonomorphicExpression.FunctionApplication(target, arg)))
    }
}
