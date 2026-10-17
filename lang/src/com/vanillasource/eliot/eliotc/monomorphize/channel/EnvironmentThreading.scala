package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.channel.EffectDeclarations.defaultBinding
import com.vanillasource.eliot.eliotc.monomorphize.check.{DeclaredEntries, ImplementationBinding, NamedImplementationArguments}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CompilerMonomorphicValue,
  GroundValue,
  GroundValueRenderer,
  MonomorphicEffects,
  MonomorphicExpression,
  MonomorphicValue
}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.collection.mutable
import scala.util.chaining.*

/** **Environment threading** — effects D20c option 5 and D35 (`docs/effects.md` §3.4, §11). An implementation is code
  * and an environment — a frame, a cell, an implementation's parameters — passed together as **evidence**. The
  * specialisation pass has written every reference's code from the evidence in scope
  * ([[com.vanillasource.eliot.eliotc.monomorphize.specialise.EvidenceSpecialisation]]); this walk passes the
  * environment half, at the `WovenValue` seam, from the same evidence, and never searches for it.
  *
  * {{{
  * increment[State@Default](env)  = putState(env, state(env) + 1)          -- a received entry is a hidden parameter
  * runStateToPair(initial, p)     = withCellInternal(initial, cell -> p(cell), …)   -- a slot's block takes its frame
  * main                           = …(runStateToFinalState(5, cell -> { increment(cell); double(cell) }))
  * }}}
  *
  * Evidence comes into scope three ways, and an operation runs on the frame of the nearest evidence answering its entry,
  * which is the evidence its binding was written from — a disagreement is a defect, never a pick:
  *
  *   - **a received entry is a hidden parameter.** A definition receives one frame per entry of its declared row and
  *     per `~` constraint ([[MonomorphicEffects.received]]), ahead of its own parameters, and a reference to it passes
  *     the frame of the evidence in scope for each.
  *   - **a block takes what its callee's runs give.** A row-typed slot's thunk took `unit`; it now takes the frames of
  *     the entries the slot lists, at what the callee's body runs the parameter with
  *     ([[MonomorphicEffects.Slot.offered]]), and running the block passes them, found where it runs.
  *   - **a `with` installs its implementation around its subject**, as the resolver leaves it: its binder applied to the
  *     arguments it supplies and to its subject (`scripted$with(xs, greet)`, [[ImplementationParameters.binder]]). The
  *     arguments are bound to fresh parameters around the subject, and the implementation's frame is built where an
  *     operation runs on it, from those and from the frames its clauses find at the `with`.
  *
  * A **dispatched** operation is passed the frame of the entry it was dispatched from: the frames its clauses perform
  * on, then the implementation's own parameters, which the clause receives ahead of the member's.
  *
  * **Evidence is found by entry at ground arguments** (2(c)), so `{Throw[IoError], Throw[GitError]}` is two frames, and
  * two received entries that coincide at an instantiation are an error where one is used.
  *
  * Nothing here changes a definition that receives and supplies no environment: its frames have no parts, a block
  * with nothing to take keeps its `unit`, and the woven body is the monomorphic one.
  */
object EnvironmentThreading {

  /** The woven signature and body of `mv`. */
  def thread(mv: MonomorphicValue): CompilerIO[(GroundValue, Option[Sourced[MonomorphicExpression.Expression]])] =
    new Threader(Specialisation.of(mv), Track.Runtime, guarded = false).run
      .map(threaded => (threaded.signature, threaded.body))

  /** A compile-track specialisation's body, threaded as the runtime track threads a woven one: what the compile track
    * evaluates wherever a control effect may run, so that a frame reaches its operation along the declared chain
    * (`docs/effects.md` §3.4). [[None]] when it has no body.
    */
  def threadCompiled(value: Specialisation): CompilerIO[Option[Sourced[MonomorphicExpression.Expression]]] =
    new Threader(value, Track.Compiler, guarded = false).run.map(_.body)

  /** A **signature**'s expression threaded with its own control effects given by the guarded-return read
    * (`docs/effects.md` §3.4): a `raise` or an `abort` nothing in the signature discharges is performed on a frame the
    * read installs, so the threaded body takes one leading parameter per such frame part, outermost, in
    * [[Threaded.guards]].
    */
  def threadGuarded(value: Specialisation): CompilerIO[Threaded] =
    new Threader(value, Track.Compiler, guarded = true).run

  /** What the threading made of a value: its signature and body, and the leading parameters of the body that are the
    * guarded-return read's frames (only ever in a guarded threading).
    */
  case class Threaded(
      signature: GroundValue,
      body: Option[Sourced[MonomorphicExpression.Expression]],
      guards: Seq[GroundValue]
  )

  /** One specialisation as the threading reads it, on either track: a [[MonomorphicValue]] on the runtime track, a
    * [[CompilerMonomorphicValue]] — whose body is its reduced one, structural wherever it takes arguments — on the
    * compile track, or a value the compile track is still checking, whose body is the one being read back.
    */
  case class Specialisation(
      vfqn: ValueFQN,
      typeArguments: Seq[GroundValue],
      name: Sourced[QualifiedName],
      signature: GroundValue,
      body: Option[Sourced[MonomorphicExpression.Expression]],
      effects: MonomorphicEffects
  ) {
    def naturalArity: Option[Int] = body.map(b => MonomorphicValue.countLeadingLambdas(b.value, 0))
  }

  object Specialisation {
    def of(mv: MonomorphicValue): Specialisation =
      Specialisation(mv.vfqn, mv.typeArguments, mv.name, mv.signature, mv.runtime, mv.effects)

    def of(cmv: CompilerMonomorphicValue): Specialisation =
      Specialisation(cmv.vfqn, cmv.typeArguments, cmv.name, cmv.signature, cmv.reduced, cmv.effects)
  }

  /** Which pool the threading reads declarations from, and which specialisations it reads. */
  private enum Track(val platform: Platform) {
    case Runtime  extends Track(Platform.Runtime)
    case Compiler extends Track(Platform.Compiler)

    def specialisation(vfqn: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Specialisation] = this match {
      case Runtime  => getFactOrAbort(MonomorphicValue.Key(vfqn, typeArguments)).map(Specialisation.of)
      case Compiler => getFactOrAbort(CompilerMonomorphicValue.Key(vfqn, typeArguments)).map(Specialisation.of)
    }
  }

  /** One part of an environment: a parameter in scope holding it, and its type. */
  private case class Part(name: String, tpe: GroundValue)

  /** A frame: the entry it answers, the binding its code was written with, the frames its implementation's clauses
    * perform on, and the implementation's own parameters. `level` is how deep the push that put it in scope is, which
    * tells two received at once apart from one nearer than the other.
    */
  private case class Frame(entry: Entry, binding: GroundValue, clauses: Seq[Frame], own: Seq[Part], level: Int = 0) {
    def parts: Seq[Part] = clauses.flatMap(_.parts) ++ own
  }

  /** The **evidence** in scope for an entry (`docs/effects.md` D35): an implementation's code and its environment as
    * one value, so the frame an operation runs on is the one the evidence its binding was written from holds — never
    * a frame chosen beside it.
    */
  private enum Evidence {

    /** A frame held by parameters: received from the caller, or given to a block by a callee's slot. It answers its
      * entry exactly.
      */
    case Held(frame: Frame)

    /** The implementation `marker` of `ability`, installed by a `with` around this point: it answers every entry of its
      * ability, its own parameters are the arguments the `with` supplied, held by `own`, and its clauses perform on the
      * evidence in scope at the `with`, `closure`. `at` is where the `with` was written.
      */
    case Installed(marker: ValueFQN, ability: AbilityFQN, own: Seq[Part], closure: Seq[Evidence], at: Sourced[?])
  }

  /** What an implementation's environment is made of, before anything holds it: the clause entries with their
    * bindings and shapes, and the types of the implementation's own parameters.
    */
  private case class Shape(clauses: Seq[(Entry, GroundValue, Shape)], own: Seq[GroundValue]) {
    def types: Seq[GroundValue] = clauses.flatMap(_._3.types) ++ own
  }

  private val emptyShape = Shape(Seq.empty, Seq.empty)

  private class Threader(mv: Specialisation, track: Track, guarded: Boolean) {
    private val declarations = EffectDeclarations(track.platform, track.specialisation)
    import declarations.*
    private var counter = 0

    private def fresh(): String = {
      counter += 1
      s"$$env$counter"
    }

    /** The frames the guarded-return read gives: a signature's own control effects, made the first time one is met. */
    private val guards: mutable.ListBuffer[Frame] = mutable.ListBuffer.empty

    def run: CompilerIO[Threaded] =
      for {
        received  <- receivedFrames(mv)
        _         <- if (mv.body.isEmpty && received.exists(_.parts.nonEmpty))
                       compilerAbort[Unit](
                         mv.name.as("Internal: a body-less value cannot receive an environment it would have to pass on.")
                       )
                     else ().pure[CompilerIO]
        signature <- signatureOf(mv)
        body      <- mv.body.traverse { body =>
                       walkDefinition(body.map(MonomorphicExpression(mv.signature, _)), mv, received).map(walked =>
                         (guards.toSeq ++ received)
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
      } yield Threaded(signature, body, guards.toSeq.flatMap(_.parts).map(_.tpe))

    /** The frames a definition receives, each held by fresh hidden parameters. */
    private def receivedFrames(value: Specialisation): CompilerIO[Seq[Frame]] =
      value.effects.received.traverse { received =>
        bindingAt(value.typeArguments, received, value.name).flatMap(binding =>
          shapeOf(received.entry, binding).map(shape => held(received.entry, binding, shape))
        )
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
        value: Specialisation,
        received: Seq[Frame]
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      for {
        slots  <- value.effects.slots.traverse(slot => slotDomain(value, slot).map(slot.parameterIndex -> _)).map(_.toMap)
        walked <- peel(body, 0, value.naturalArity.getOrElse(0), slots, Context(received.map(Evidence.Held(_)), Map.empty, Map.empty))
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

    /** The lexical context: the evidence in scope innermost first, the parameters whose type the threading changed, and
      * this definition's row-typed parameters by index — running one passes its frames. Every push of frames raises the
      * depth, so two frames of one push are at the same distance.
      */
    private case class Context(
        evidence: Seq[Evidence],
        types: Map[String, GroundValue],
        runs: Map[String, Int],
        depth: Int = 0
    ) {
      def push(more: Seq[Frame]): Context =
        copy(evidence = more.map(frame => Evidence.Held(frame.copy(level = depth + 1))) ++ evidence, depth = depth + 1)

      def install(marker: ValueFQN, ability: AbilityFQN, own: Seq[Part], at: Sourced[?]): Context =
        copy(evidence = Evidence.Installed(marker, ability, own, evidence, at) +: evidence)

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
            case reference: MonomorphicExpression.MonomorphicValueReference
                if ImplementationParameters.isBinder(reference.valueName.value) =>
              supply(head, reference, args, context)
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

    /** A `with`, as the resolver lowered it: `scripted$with(xs, subject)` becomes `(answer -> subject)(xs)`, the
      * subject walked with `answer` supplied to `scripted`, so every frame built for `scripted` inside it holds them
      * ([[built]]). One supplying nothing (`recordingConsole$with(subject)`) is its subject, walked where the `with`
      * stands. The binder is never called, and what follows its subject applies the subject's value.
      */
    private def supply(
        head: Sourced[MonomorphicExpression],
        reference: MonomorphicExpression.MonomorphicValueReference,
        args: Seq[Sourced[MonomorphicExpression]],
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] = {
      val binder = reference.valueName.value
      for {
        orv     <- getFactOrAbort(OperatorResolvedValue.Key(binder, track.platform))
        count    = SignatureView.of(orv.signature).parameters.size - 1
        _       <- if (args.sizeIs <= count)
                     compilerAbort[Unit](head.as("Internal: an implementation's binder is not applied to its subject."))
                   else ().pure[CompilerIO]
        marker  <- markerOfBinder(binder, head)
        ability <- abilityOfMarker(marker).flatMap {
                     case Some(ability) => ability.pure[CompilerIO]
                     case None          => compilerAbort[AbilityFQN](head.as("Internal: a `with` names no implementation."))
                   }
        supplied = args.take(count)
        parts    = parameterTypes(head.value.expressionType, count).map(tpe => Part(fresh(), tpe))
        walked  <- supplied.traverse(walk(_, context))
        subject <- walk(args(count), context.install(marker, ability, parts, reference.valueName))
        rest    <- args.drop(count + 1).traverse(walk(_, context))
      } yield {
        val bound = parts.foldRight(subject) { (part, inner) =>
          inner.as(
            MonomorphicExpression(
              functionType(part.tpe, inner.value.expressionType),
              MonomorphicExpression.FunctionLiteral(head.as(part.name), part.tpe, inner)
            )
          )
        }
        applyAll(applyAll(bound, walked, keepTypes = None), rest, keepTypes = None)
      }
    }

    /** The marker of the implementation `binder` is the binder declaration of. */
    private def markerOfBinder(binder: ValueFQN, at: Sourced[?]): CompilerIO[ValueFQN] =
      binder.name.qualifier match {
        case qualifier: Qualifier.AbilityImplementation =>
          ValueFQN(binder.moduleName, QualifiedName(qualifier.name, qualifier)).pure[CompilerIO]
        case _                                          =>
          compilerAbort(at.as("Internal: an implementation's binder is outside its implementation."))
      }

    /** What a reference passes: the parts, and for a dispatched one how many of them are the implementation's own. */
    private case class Passed(parts: Seq[Part], own: Int)

    private def receivedParts(
        callee: Specialisation,
        typeArguments: Seq[GroundValue],
        at: Sourced[?],
        context: Context
    ): CompilerIO[Passed] =
      callee.effects.received
        .traverse { received =>
          bindingAt(typeArguments, received, at).flatMap(frameFor(context.evidence, received.entry, _, at))
        }
        .map(frames => Passed(frames.flatMap(_.parts), 0))

    /** A dispatched clause receives its own entries first — the clause row's frames come from the frame it was dispatched
      * on, anything else from scope — and then the implementation's own parameters.
      */
    private def dispatchedParts(
        clause: Specialisation,
        dispatch: MonomorphicEffects.Dispatch,
        at: Sourced[?],
        context: Context
    ): CompilerIO[Passed] =
      for {
        frame    <- frameFor(context.evidence, dispatch.entry, dispatch.binding, at)
        perCall  <- memberDeclared(memberOf(clause.vfqn, dispatch.entry.ability))
        own      <- declaredEntries(clause.vfqn)
        received <- clause.effects.received.traverse { received =>
                      val callSite = own.get(received.binderIndex).exists(perCall.contains)
                      frame.clauses.find(candidate => candidate.entry == received.entry) match {
                        case Some(clauseFrame) if !callSite => clauseFrame.pure[CompilerIO]
                        case _                              =>
                          bindingAt(clause.typeArguments, received, at).flatMap(frameFor(context.evidence, received.entry, _, at))
                      }
                    }
      } yield Passed(received.flatMap(_.parts) ++ frame.own, frame.own.size)

    /** A block at a callee's slot: the thunk the row phase wrapped it in now takes the frames the slot supplies. */
    private def block(
        arg: Sourced[MonomorphicExpression],
        callee: Specialisation,
        slot: MonomorphicEffects.Slot,
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] =
      arg.value.expression match {
        case MonomorphicExpression.FunctionLiteral(name, parameterType, body) =>
          for {
            offered <- givenFrames(callee, slot, context, arg)
            (frames, parts) = offered
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

    /** The frames a block at `slot` of `callee` is given, and the parts the block now takes as its parameters: one frame
      * per entry the slot lists, at the binding `callee`'s runs give it ([[offered]]), held by fresh parameters — its
      * clauses' frames and then its own parameters, given at each run.
      */
    private def givenFrames(
        callee: Specialisation,
        slot: MonomorphicEffects.Slot,
        context: Context,
        arg: Sourced[?]
    ): CompilerIO[(Seq[Frame], Seq[Part])] =
      offered(callee, slot)
        .traverse((entry, binding) => shapeOf(entry, binding).map(held(entry, binding, _)))
        .map(frames => (frames, frames.flatMap(_.parts)))

    /** Running one of this definition's row-typed parameters passes the frames of the entries its slot lists, each the
      * frame in scope here for the evidence the run finds ([[offered]]): a `with` around the run, a callee's slot the
      * run is written at, or this definition's own received entry.
      */
    private def runBlock(
        head: Sourced[MonomorphicExpression],
        name: Sourced[String],
        unit: Sourced[MonomorphicExpression],
        context: Context
    ): CompilerIO[Sourced[MonomorphicExpression]] = {
      val index = context.runs(name.value)
      val slot  = mv.effects.slots.find(_.parameterIndex == index).getOrElse(MonomorphicEffects.Slot(index, Seq.empty))
      for {
        frames <- offered(mv, slot).traverse((entry, binding) => frameFor(context.evidence, entry, binding, name))
        parts   = frames.flatMap(_.parts)
        retyped = context.types.get(name.value).fold(head)(tpe => head.as(head.value.copy(expressionType = tpe)))
      } yield
        if (parts.isEmpty) applyAll(retyped, Seq(unit), keepTypes = None)
        else applyAll(retyped, parts.map(partReference(name, _)), keepTypes = None)
    }

    /** What a block at `slot` of `value` is given for each entry the slot lists: the binding of the evidence the runs of
      * the parameter find in `value`'s body ([[MonomorphicEffects.Slot.offered]]), `Default` where nothing runs it.
      */
    private def offered(value: Specialisation, slot: MonomorphicEffects.Slot): Seq[(Entry, GroundValue)] =
      slot.offered.fold(slot.entries.map(_ -> defaultBinding))(slot.entries.zip(_))

    /** The type a row-typed parameter takes once its block takes `domain`: `Unit => A` becomes `T₁ => … => A`. */
    private def blockType(parameterType: GroundValue, domain: Seq[GroundValue]): GroundValue =
      parameterType.asFunctionType match {
        case Some((_, result)) if domain.nonEmpty => domain.foldRight(result)(functionType)
        case _                                    => parameterType
      }

    /** What a block at `slot` of `value` takes: the parts of the frames of the entries the slot lists, in order. */
    private def slotDomain(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[GroundValue]] =
      offered(value, slot).flatTraverse((entry, binding) => shapeOf(entry, binding).map(_.types))

    /** A value's woven signature: the parts of the frames it receives, then its own parameters with each row-typed one
      * retyped to the block it now is.
      */
    private def signatureOf(value: Specialisation): CompilerIO[GroundValue] =
      for {
        received <- value.effects.received.traverse { received =>
                      bindingAt(value.typeArguments, received, value.name).flatMap(shapeOf(received.entry, _)).map(_.types)
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
    private def calleeOf(
        reference: MonomorphicExpression.MonomorphicValueReference
    ): CompilerIO[Option[Specialisation]] =
      getFactIfProduced(OperatorResolvedValue.Key(reference.valueName.value, track.platform)).flatMap {
        case Some(orv) if orv.paramConstraints.nonEmpty || orv.effectRow.parameterEffects.nonEmpty =>
          track.specialisation(reference.valueName.value, reference.typeArguments).map(Some(_))
        case _                                                                                    =>
          if (reference.dispatchedFrom.isDefined || isImplementationClause(reference.valueName.value))
            track.specialisation(reference.valueName.value, reference.typeArguments).map(Some(_))
          else None.pure[CompilerIO]
      }

    private def isImplementationClause(vfqn: ValueFQN): Boolean =
      vfqn.name.qualifier match {
        case _: Qualifier.AbilityImplementation => true
        case _                                  => false
      }

    /** The frame `binding` runs `entry` on here: the one the nearest evidence answering the entry holds, which is the
      * evidence the binding was written from (`docs/effects.md` D35), so the two must agree. An installed
      * implementation's frame is built from its `with`'s arguments and the frames its clauses find at the `with`. Where
      * no evidence answers, `binding` takes no environment — an ability's default, an implementation holding nothing — or
      * is a guarded signature's own control effect, whose frame the guarded-return read installs.
      */
    private def frameFor(evidence: Seq[Evidence], entry: Entry, binding: GroundValue, at: Sourced[?]): CompilerIO[Frame] =
      answering(evidence, entry, at).flatMap {
        case Some(Evidence.Held(frame)) if frame.binding == binding                         => frame.pure[CompilerIO]
        case Some(Evidence.Held(frame))                                                     =>
          disagreement(entry, binding, frame.binding, at)
        case Some(Evidence.Installed(marker, _, own, closure, written)) if sameImplementation(binding, namedBinding(marker)) =>
          shapeOf(entry, binding).flatMap { shape =>
            if (own.map(_.tpe) != shape.own) unfitArguments(entry, binding, shape.own, own, written)
            else
              shape.clauses
                .traverse { case (clauseEntry, clauseBinding, _) => frameFor(closure, clauseEntry, clauseBinding, at) }
                .map(clauses => Frame(entry, binding, clauses, own))
          }
        case Some(Evidence.Installed(marker, _, _, _, _))                                   =>
          disagreement(entry, binding, namedBinding(marker), at)
        case None                                                                           =>
          shapeOf(entry, binding).flatMap {
            case shape if shape.types.isEmpty => Frame(entry, binding, Seq.empty, Seq.empty).pure[CompilerIO]
            case shape if guarded             =>
              guards
                .find(guard => guard.entry == entry && guard.binding == binding)
                .getOrElse(held(entry, binding, shape).tap(guards += _))
                .pure[CompilerIO]
            case _                            =>
              compilerAbort(
                at.as(s"Internal: no evidence for the effect '${entry.ability.abilityName}' is in scope here.")
              )
          }
      }

    /** The nearest evidence answering `entry`. Two frames at the same distance — a definition receiving
      * `{Throw[E1], Throw[E2]}` at an instantiation where `E1` and `E2` coincide — are an error at this use: which one an
      * operation reaches is not decided by anything written, since evidence is found by entry at ground arguments.
      */
    private def answering(evidence: Seq[Evidence], entry: Entry, at: Sourced[?]): CompilerIO[Option[Evidence]] = {
      val held = evidence.collect { case Evidence.Held(frame) if frame.entry == entry => frame }
      evidence.find {
        case Evidence.Held(frame)                => frame.entry == entry
        case Evidence.Installed(_, ability, _, _, _) => ability == entry.ability
      } match {
        case Some(Evidence.Held(first)) if held.exists(other => other.level == first.level && other.parts != first.parts) =>
          compilerAbort(
            at.as(
              s"Two entries of the effect '${entry.ability.abilityName}' in scope here coincide at this instantiation, " +
                "so which one this performs is not decided."
            )
          )
        case found                                                                                                         =>
          found.pure[CompilerIO]
      }
    }

    private def disagreement(entry: Entry, binding: GroundValue, evidence: GroundValue, at: Sourced[?]): CompilerIO[Frame] =
      compilerAbort(
        at.as(
          s"Internal: this runs the effect '${entry.ability.abilityName}' on ${GroundValueRenderer.render(binding)}, but " +
            s"the evidence for it here is ${GroundValueRenderer.render(evidence)}."
        )
      )

    /** A received entry's binding: the type argument at its binder, which every key whole enough to receive it holds. */
    private def bindingAt(typeArguments: Seq[GroundValue], received: MonomorphicEffects.Received, at: Sourced[?]): CompilerIO[GroundValue] =
      typeArguments.lift(received.binderIndex) match {
        case Some(binding) => binding.pure[CompilerIO]
        case None          => compilerAbort(at.as("Internal: a received entry's binder is not among the type arguments."))
      }

    /** A `with` supplied arguments its implementation does not take where it runs `entry`: a named implementation's type
      * parameters are what its pattern takes at the entry ([[NamedImplementationArguments]]), and a `with` binds every
      * use of its ability in its subject, so the arguments must fit each.
      */
    private def unfitArguments(
        entry: Entry,
        binding: GroundValue,
        own: Seq[GroundValue],
        supplied: Seq[Part],
        at: Sourced[?]
    ): CompilerIO[Frame] = {
      def shown(types: Seq[GroundValue]): String = types.map(GroundValueRenderer.render).mkString("(", ", ", ")")
      val performed =
        if (entry.arguments.isEmpty) entry.ability.abilityName
        else entry.arguments.map(GroundValueRenderer.render).mkString(s"${entry.ability.abilityName}[", ", ", "]")
      compilerAbort(
        at.as(
          s"${describe(binding).capitalize} takes ${shown(own)} where it runs '$performed', but this `with` " +
            s"supplies ${shown(supplied.map(_.tpe))}."
        ),
        Seq(s"A `with` binds every use of '${entry.ability.abilityName}' in its subject, so its arguments must fit each.")
      )
    }

    /** The shape of the environment of `binding` for `entry`: the implementation it runs on ([[implementationOf]]; none,
      * and so an empty shape, for `Default`), with its clause entries' shapes and its own parameters' types.
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
            clauses <- implementationClauses(marker, typeArguments)
            shaped  <- clauses.traverse { case (clauseEntry, clauseBinding, _) =>
                         shapeOf(clauseEntry, clauseBinding).map(shape => (clauseEntry, clauseBinding, shape))
                       }
            own     <- ownParameterTypes(marker, typeArguments)
          } yield Shape(shaped, own)
      }

    /** The implementation `binding` runs on for `entry`, as its marker and type arguments: a named one at what its
      * pattern takes at the entry's arguments ([[NamedImplementationArguments]]), as its dispatched methods run.
      *
      * Every entry here is an effect's — an implementation's `~` constraint holds no frame (D29) — and an effect has no
      * default (D26), so a `Default` binding names no implementation and has no environment: there is nothing to search
      * for, since an effect has no anonymous implementation to find (`docs/effects.md` §8 item 50).
      */
    private def implementationOf(entry: Entry, binding: GroundValue): CompilerIO[Option[(ValueFQN, Seq[GroundValue])]] =
      ImplementationBinding.fromGround(binding) match {
        case Some(named: ImplementationBinding.Implementation) =>
          NamedImplementationArguments.solve(named, entry.arguments, track.platform).map(_.map(named.head -> _))
        case _                                                 => none[(ValueFQN, Seq[GroundValue])].pure[CompilerIO]
      }

    /** The types of an implementation's own parameters at its type arguments, read off its parameters declaration's
      * specialisation ([[ImplementationParameters]]); none when it declares none.
      */
    private def ownParameterTypes(marker: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Seq[GroundValue]] = {
      val declaration = ImplementationParameters.of(marker)
      getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, track.platform)).flatMap { names =>
        if (!names.names.contains(declaration.name)) Seq.empty.pure[CompilerIO]
        else
          for {
            orv   <- getFactOrAbort(OperatorResolvedValue.Key(declaration, track.platform))
            ground <- track.specialisation(declaration, typeArguments)
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
