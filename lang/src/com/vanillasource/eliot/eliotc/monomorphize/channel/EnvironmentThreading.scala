package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  ValueFQN,
  WellKnownTypes
}
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
  *     entries the slot lists, and running the block passes them, found in scope where it runs. For an entry the slot
  *     rides (the definition declares it too), the block's *code* is the caller's binding but its *frame* is still the
  *     run site's — so a definition can discharge the very effect it declares (`describedAs`), decided as (b).
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
  * the row phase rejects a use of a `with`'s binding below a nearer binding of an effect its clauses perform. One that
  * takes **parameters** reaches here as its binder applied to the `with`'s arguments around the subject
  * (`scripted$with(xs, greet)`, [[ImplementationParameters.binder]]): the arguments are bound to fresh parameters
  * around the subject, and a frame built for that implementation inside it holds them as its own parts.
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

  /** The arguments the `with` at `at` supplied to the implementation `marker` names, held by parameters around its
    * subject.
    */
  private case class Supplied(marker: ValueFQN, own: Seq[Part], level: Int, at: Sourced[?])

  /** A frame in scope: the entry it answers, the binding its code was written with, the frames its implementation's
    * clauses perform on, and the implementation's own parameters.
    */
  private case class Frame(entry: Entry, binding: GroundValue, clauses: Seq[Frame], own: Seq[Part], level: Int = 0) {
    def parts: Seq[Part] = clauses.flatMap(_.parts) ++ own
  }

  /** What an implementation's environment is made of, before anything holds it: the clause entries with their
    * bindings and shapes, and the types of the implementation's own parameters.
    */
  private case class Shape(clauses: Seq[(Entry, GroundValue, Shape)], own: Seq[GroundValue]) {
    def types: Seq[GroundValue] = clauses.flatMap(_._3.types) ++ own
  }

  private val emptyShape = Shape(Seq.empty, Seq.empty)

  /** What a block is given for one entry its slot lists. */
  private enum Listed {

    /** An entry the slot supplies, at the implementation its `with` names, or the default. */
    case Supplied(entry: Entry, named: Option[ValueFQN])

    /** An entry the slot rides: the block's code for it is the caller's `binding`, and its frame is the run site's. */
    case Rides(entry: Entry, binding: GroundValue)
  }

  private val defaultBinding: GroundValue = ImplementationBinding.defaultGround

  private class Threader(mv: Specialisation, track: Track, guarded: Boolean) {
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
        value: Specialisation,
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

    /** The lexical context: the frames in scope innermost first, the parameters whose type the threading changed,
      * this definition's row-typed parameters by index — running one passes its frames — and the arguments the `with`s
      * around this point supplied, innermost first. A frame and a supplied argument list are told apart in nearness by
      * their level, which every push raises.
      */
    private case class Context(
        frames: Seq[Frame],
        types: Map[String, GroundValue],
        runs: Map[String, Int],
        supplied: Seq[Supplied] = Seq.empty,
        depth: Int = 0
    ) {
      def push(more: Seq[Frame]): Context =
        copy(frames = more.map(_.copy(level = depth + 1)) ++ frames, depth = depth + 1)

      def supply(marker: ValueFQN, own: Seq[Part], at: Sourced[?]): Context =
        copy(supplied = Supplied(marker, own, depth + 1, at) +: supplied, depth = depth + 1)

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

    /** A `with` supplying arguments, as the resolver lowered it: `scripted$with(xs, subject)` becomes
      * `(answer -> subject)(xs)`, the subject walked with `answer` supplied to `scripted`, so every frame built for
      * `scripted` inside it holds them ([[built]]). The binder is never called, and what follows its subject applies
      * the subject's value.
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
        supplied = args.take(count)
        parts    = parameterTypes(head.value.expressionType, count).map(tpe => Part(fresh(), tpe))
        walked  <- supplied.traverse(walk(_, context))
        subject <- walk(args(count), context.supply(marker, parts, reference.valueName))
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
        .traverse(received =>
          lookup(context, received.entry, typeArguments.lift(received.binderIndex).getOrElse(defaultBinding), at)
        )
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
        frame    <- lookup(context, dispatch.entry, dispatch.binding, at)
        perCall  <- memberDeclared(memberOf(clause.vfqn, dispatch.entry.ability))
        own      <- declaredEntries(clause.vfqn)
        received <- clause.effects.received.traverse { received =>
                      val binding = clause.typeArguments.lift(received.binderIndex).getOrElse(defaultBinding)
                      val callSite = own.get(received.binderIndex).exists(perCall.contains)
                      frame.clauses.find(candidate => candidate.entry == received.entry) match {
                        case Some(clauseFrame) if !callSite => clauseFrame.pure[CompilerIO]
                        case _                              => lookup(context, received.entry, binding, at)
                      }
                    }
      } yield Passed(received.flatMap(_.parts) ++ frame.own, frame.own.size)

    /** The ability member an implementation's clause implements. */
    private def memberOf(clause: ValueFQN, ability: AbilityFQN): ValueFQN =
      ValueFQN(ability.moduleName, QualifiedName(clause.name.name, Qualifier.Ability(ability.abilityName)))

    private type Declared = DeclaredEntries.Declared

    /** The entries an ability member declares beyond its own ability — `FileSystem.readFile`'s `{Throw[IoError]}`. Each
      * call performs them on the caller's binding, so their frames come from the call site, never from the
      * implementation's environment, even where the implementation's clause repeats them.
      */
    private def memberDeclared(member: ValueFQN): CompilerIO[Set[Declared]] =
      memberRows.get(member) match {
        case Some(declared) => declared.pure[CompilerIO]
        case None           =>
          getFactIfProduced(OperatorResolvedValue.Key(member, track.platform))
            .map(
              _.toSeq
                .flatMap(_.effectRow.returnEffects)
                .map(entry => (entry.abilityFQN, entry.typeArgs.map(_.render)))
                .toSet
            )
            .map(declared =>
              member.name.qualifier match {
                case Qualifier.Ability(name) => declared.filterNot(_._1 == AbilityFQN(member.moduleName, name))
                case _                       => declared
              }
            )
            .flatTap(declared => memberRows.update(member, declared).pure[CompilerIO])
      }

    private val memberRows: mutable.Map[ValueFQN, Set[Declared]] = mutable.Map.empty

    /** The effect entries a definition performs itself, by the index of the binder each binds ([[DeclaredEntries]]). */
    private def declaredEntries(vfqn: ValueFQN): CompilerIO[Map[Int, Declared]] =
      getFactIfProduced(OperatorResolvedValue.Key(vfqn, track.platform))
        .map(_.fold(Map.empty[Int, Declared])(DeclaredEntries.of))

    /** The effect entries an implementation's **environment** holds: those one of its clauses performs itself while the
      * member it implements does not declare them. `recordingConsole`'s `printLine` performs `Writer[String]`, which
      * `Console.printLine` does not declare, so the implementation holds a `Writer` frame from where it is bound;
      * `FileSystem`'s clauses perform `Throw[IoError]`, which every member declares, so it holds none and each call
      * brings its own.
      */
    private def environmentEntries(marker: ValueFQN, ability: AbilityFQN): CompilerIO[Set[Declared]] =
      getFactOrAbort(ModuleAbilities.Key(marker.moduleName, track.platform)).flatMap { abilities =>
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
      * per entry the slot lists, held by fresh parameters. An entry the slot **supplies** (one the callee's own row
      * lacks) is held at its default, or at the implementation the slot names with `with`, whose clauses' frames are
      * held the same way ([[clauseBinding]]) and whose own parameters follow them, given at each run
      * ([[runArguments]]). An entry the slot **rides** is held at the caller's binding, which is the
      * block's code for it: the code rides, the frame is given where the block runs (`docs/effects.md` D20c, (b)).
      */
    private def givenFrames(
        callee: Specialisation,
        slot: MonomorphicEffects.Slot,
        context: Context,
        arg: Sourced[?]
    ): CompilerIO[(Seq[Frame], Seq[Part])] =
      listed(callee, slot)
        .flatMap(_.traverse {
          case Listed.Rides(entry, binding) =>
            shapeOf(entry, binding).map { shape =>
              val frame = held(entry, binding, shape)
              (frame, frame.parts)
            }
          case Listed.Supplied(entry, None) =>
            shapeOf(entry, defaultBinding).map { shape =>
              val frame = held(entry, defaultBinding, shape)
              (frame, frame.parts)
            }
          case Listed.Supplied(entry, Some(marker)) =>
            for {
              clauses      <- namedClauses(marker)
              clauseFrames <- clauses.traverse { clauseEntry =>
                                val binding = clauseBinding(callee, clauseEntry)
                                shapeOf(clauseEntry, binding).map(shape => held(clauseEntry, binding, shape))
                              }
              own          <- namedOwn(entry, marker)
            } yield {
              val frame = Frame(entry, namedBinding(marker), clauseFrames, own.map(tpe => Part(fresh(), tpe)))
              (frame, frame.parts)
            }
        })
        .map(offered => (offered.map(_._1), offered.flatMap(_._2)))

    /** Running one of this definition's row-typed parameters passes the frames of the entries its slot lists, found
      * where it runs — a ridden entry's at this definition's own binding for it, which is the block's code. An
      * implementation the slot names that takes parameters is given the arguments the `with` around this run supplied
      * ([[runArguments]]).
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
        frames <- listed(mv, slot).flatMap(_.traverse {
                    case Listed.Rides(entry, binding)         => lookup(context, entry, binding, name)
                    case Listed.Supplied(entry, None)         => lookup(context, entry, defaultBinding, name)
                    case Listed.Supplied(entry, Some(marker)) =>
                      for {
                        clauseFrames <- namedClauses(marker).flatMap(
                                          _.traverse(clause => lookup(context, clause, clauseBinding(mv, clause), name))
                                        )
                        own          <- runArguments(context, entry, marker, name)
                      } yield Frame(entry, namedBinding(marker), clauseFrames, own)
                  })
        parts   = frames.flatMap(_.parts)
        retyped = context.types.get(name.value).fold(head)(tpe => head.as(head.value.copy(expressionType = tpe)))
      } yield
        if (parts.isEmpty) applyAll(retyped, Seq(unit), keepTypes = None)
        else applyAll(retyped, parts.map(partReference(name, _)), keepTypes = None)
    }

    /** The arguments of the implementation `marker` a slot names, where a run of its code performs `entry`: those the
      * `with` around the run supplied, which the hoisting left there when it moved the implementation onto the slot
      * (`docs/effects.md` D25, step 2). An implementation without parameters takes none.
      */
    private def runArguments(context: Context, entry: Entry, marker: ValueFQN, at: Sourced[String]): CompilerIO[Seq[Part]] =
      namedOwn(entry, marker).flatMap { own =>
        context.supplied.find(_.marker == marker) match {
          case _ if own.isEmpty                                => Seq.empty.pure[CompilerIO]
          case Some(supplied) if supplied.own.map(_.tpe) == own => supplied.own.pure[CompilerIO]
          case Some(supplied)                                  =>
            unfitArguments(entry, namedBinding(marker), own, supplied).map(_ => Seq.empty)
          case None                                            =>
            compilerAbort(
              at.as(
                s"'${at.value}' runs here on ${describe(namedBinding(marker))}, which takes parameters, but no `with` " +
                  "around this run supplies them."
              )
            )
        }
      }

    /** The types of the parameters of the named implementation `marker`, where it runs `entry`. */
    private def namedOwn(entry: Entry, marker: ValueFQN): CompilerIO[Seq[GroundValue]] =
      implementationOf(entry, namedBinding(marker)).flatMap {
        case Some((head, typeArguments)) => ownParameterTypes(head, typeArguments)
        case None                        => Seq.empty.pure[CompilerIO]
      }

    /** Every entry `slot` lists, as what the block is given for it: an entry it **supplies**, with the implementation
      * the slot names for it with `with`, read off the slot's declared type; or an entry it **rides**, with `value`'s
      * own binding for it. An entry the slot names an implementation for is supplied even when `value` declares it too
      * (`docs/effects.md` D23): the slot's `with` handles it.
      */
    private def listed(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[Listed]] =
      for {
        named <- slotImplementations(value, slot.parameterIndex)
        rides <- ridingFlags(value, slot)
        listing <- slot.entries.zip(rides).traverse {
                   case (entry, rides) if !rides || named.contains(entry.ability) =>
                     (Listed.Supplied(entry, named.get(entry.ability)): Listed).pure[CompilerIO]
                   case (entry, _)                                                 =>
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

    /** The binding a slot's named implementation performs `clause` on: the definition's own, where its row has the
      * entry — the caller writes its binding there, which it also hands the definition — and the default otherwise,
      * which is what the caller writes for a clause the slot supplies. Either way the frame is given where the block runs
      * (`docs/effects.md` D20c, decided (b)).
      */
    private def clauseBinding(value: Specialisation, clause: Entry): GroundValue =
      value.effects.received
        .find(_.entry == clause)
        .flatMap(received => value.typeArguments.lift(received.binderIndex))
        .getOrElse(defaultBinding)

    /** Whether each entry of `slot` **rides** the definition's own row rather than being supplied by the slot (§2.2) —
      * decided as the declaration spells it, never at ground arguments: `raising[E](…, body: {Throw[E]} Unit):
      * {Throw[AssertionError]} Unit` supplies `Throw[E]` even where `E` is `AssertionError`, so the block's frame is the
      * slot's. An entry list that does not line up with the declaration's is a failure, never a guess.
      */
    private def ridingFlags(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[Boolean]] =
      getFactOrAbort(OperatorResolvedValue.Key(value.vfqn, track.platform)).flatMap { orv =>
        val row      = orv.effectRow.returnEffects.map(entry => (entry.abilityFQN, entry.typeArgs.map(_.render))).toSet
        val declared = orv.effectRow.parameterEffects.find(_.parameterIndex == slot.parameterIndex).toSeq.flatMap(_.effects)
        if (declared.size != slot.entries.size)
          compilerAbort(value.name.as("Internal: a row-typed parameter's entries did not reduce to ground arguments."))
        else declared.map(entry => row.contains((entry.abilityFQN, entry.typeArgs.map(_.render)))).pure[CompilerIO]
      }

    /** The implementations a slot's declared type names with `with`, by ability. The row phase strips them from the
      * signature it hands on, so they are read off the operator-resolved declaration, as the row phase read them.
      */
    private def slotImplementations(value: Specialisation, index: Int): CompilerIO[Map[AbilityFQN, ValueFQN]] =
      getFactIfProduced(OperatorResolvedValue.Key(value.vfqn, track.platform)).flatMap {
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

    private def abilityOfMarker(marker: ValueFQN): CompilerIO[Option[AbilityFQN]] =
      getFactIfProduced(OperatorResolvedValue.Key(marker, track.platform)).map(_.flatMap(_.name.value.qualifier match {
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

    /** What a block at `slot` of `value` takes: the parts of the frames of the entries the slot lists, in order. */
    private def slotDomain(value: Specialisation, slot: MonomorphicEffects.Slot): CompilerIO[Seq[GroundValue]] =
      listed(value, slot).flatMap(_.flatTraverse {
        case Listed.Rides(entry, binding)         => shapeOf(entry, binding).map(_.types)
        case Listed.Supplied(entry, None)         => shapeOf(entry, defaultBinding).map(_.types)
        case Listed.Supplied(entry, Some(marker)) =>
          for {
            clauses <- namedClauses(marker).flatMap(
                         _.flatTraverse(clause => shapeOf(clause, clauseBinding(value, clause)).map(_.types))
                       )
            own     <- namedOwn(entry, marker)
          } yield clauses ++ own
      })

    /** A value's woven signature: the parts of the frames it receives, then its own parameters with each row-typed one
      * retyped to the block it now is.
      */
    private def signatureOf(value: Specialisation): CompilerIO[GroundValue] =
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

    /** The frame in scope for `entry`, which the reference was written to run on `binding`. Arguments a `with` supplied
      * to that implementation nearer than any frame of the entry are what its frame is built from: the `with` is the
      * binding the row phase wrote here.
      */
    private def lookup(context: Context, entry: Entry, binding: GroundValue, at: Sourced[?]): CompilerIO[Frame] =
      nearest(context.frames, entry, at).flatMap { frame =>
        (frame, suppliedFor(context, binding)) match {
          case (_, Some(supplied)) if frame.forall(_.level < supplied.level)   =>
            built(context, entry, binding, at, Some(supplied))
          case (Some(frame), _) if sameImplementation(frame.binding, binding) => frame.pure[CompilerIO]
          case (Some(frame), _) if !isNamed(binding)                          =>
            compilerAbort(
              at.as(
                s"This runs the effect '${entry.ability.abilityName}' on ${describe(binding)}, but the nearest frame of " +
                  s"it here runs ${describe(frame.binding)}."
              )
            )
          case _                                                              => built(context, entry, binding, at, None)
        }
      }

    /** The nearest arguments a `with` supplied to the implementation `binding` names. */
    private def suppliedFor(context: Context, binding: GroundValue): Option[Supplied] =
      ImplementationBinding.fromGround(binding) match {
        case Some(ImplementationBinding.Implementation(head, _)) => context.supplied.find(_.marker == head)
        case _                                                   => None
      }

    /** The nearest frame for `entry`. Two at the same distance — a definition receiving `{Throw[E1], Throw[E2]}` at an
      * instantiation where `E1` and `E2` coincide — are an error at this use: which one an operation reaches is not
      * decided by anything written.
      */
    private def nearest(frames: Seq[Frame], entry: Entry, at: Sourced[?]): CompilerIO[Option[Frame]] =
      frames.filter(_.entry == entry) match {
        case first +: rest if rest.exists(other => other.level == first.level && other.parts != first.parts) =>
          compilerAbort(
            at.as(
              s"Two entries of the effect '${entry.ability.abilityName}' in scope here coincide at this instantiation, " +
                "so which one this performs is not decided."
            )
          )
        case matching                                                                                      =>
          matching.headOption.pure[CompilerIO]
      }

    /** A frame nothing in scope holds: a default, or a named implementation a body `with` bound, built from the frames in
      * scope for its clauses. Its own parameters are the arguments its `with` supplied, if it supplied any; otherwise they
      * have nothing to come from, which is the error for a default that needs them — a control effect nothing
      * discharges.
      */
    private def built(
        context: Context,
        entry: Entry,
        binding: GroundValue,
        at: Sourced[?],
        supplied: Option[Supplied]
    ): CompilerIO[Frame] =
      shapeOf(entry, binding).flatMap { shape =>
        supplied match {
          case Some(supplied) if supplied.own.map(_.tpe) != shape.own => unfitArguments(entry, binding, shape.own, supplied)
          case Some(supplied)                                         =>
            shape.clauses
              .traverse { case (clauseEntry, clauseBinding, _) => lookup(context, clauseEntry, clauseBinding, at) }
              .map(clauses => Frame(entry, binding, clauses, supplied.own))
          case None if shape.own.nonEmpty && guarded                  =>
            guards
              .find(guard => guard.entry == entry && sameImplementation(guard.binding, binding))
              .getOrElse(held(entry, binding, shape).tap(guards += _))
              .pure[CompilerIO]
          case None if shape.own.nonEmpty                             =>
            compilerAbort(
              at.as(
                s"The effect '${entry.ability.abilityName}' is performed here with nothing to discharge it: its " +
                  "implementation needs a frame, and only a discharger installs one."
              )
            )
          case None                                                   =>
            shape.clauses
              .traverse { case (clauseEntry, clauseBinding, _) => lookup(context, clauseEntry, clauseBinding, at) }
              .map(clauses => Frame(entry, binding, clauses, Seq.empty))
        }
      }

    /** A `with` supplied arguments its implementation does not take where it runs `entry`: a named implementation's type
      * parameters are what its pattern takes at the entry ([[NamedImplementationArguments]]), and a `with` binds every
      * use of its ability in its subject, so the arguments must fit each.
      */
    private def unfitArguments(
        entry: Entry,
        binding: GroundValue,
        own: Seq[GroundValue],
        supplied: Supplied
    ): CompilerIO[Frame] = {
      def shown(types: Seq[GroundValue]): String = types.map(GroundValueRenderer.render).mkString("(", ", ", ")")
      val performed =
        if (entry.arguments.isEmpty) entry.ability.abilityName
        else entry.arguments.map(GroundValueRenderer.render).mkString(s"${entry.ability.abilityName}[", ", ", "]")
      compilerAbort(
        supplied.at.as(
          s"${describe(binding).capitalize} takes ${shown(own)} where it runs '$performed', but this `with` " +
            s"supplies ${shown(supplied.own.map(_.tpe))}."
        ),
        Seq(s"A `with` binds every use of '${entry.ability.abilityName}' in its subject, so its arguments must fit each.")
      )
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

    /** The implementation `binding` runs on for `entry`, as its marker and type arguments: a named one at what its
      * pattern takes at the entry's arguments ([[NamedImplementationArguments]]), as its dispatched methods run.
      */
    private def implementationOf(entry: Entry, binding: GroundValue): CompilerIO[Option[(ValueFQN, Seq[GroundValue])]] =
      ImplementationBinding.fromGround(binding) match {
        case Some(named: ImplementationBinding.Implementation) =>
          NamedImplementationArguments.solve(named, entry.arguments, track.platform).map(_.map(named.head -> _))
        case _                                                 =>
          val abilityMarker = ValueFQN(
            entry.ability.moduleName,
            QualifiedName(entry.ability.abilityName, Qualifier.Ability(entry.ability.abilityName))
          )
          getFactIfProduced(AbilityImplementation.Key(abilityMarker, entry.arguments, track.platform))
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
      getFactOrAbort(OperatorResolvedValue.Key(marker, track.platform)).flatMap { orv =>
        val performed = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
        if (performed.isEmpty) Seq.empty.pure[CompilerIO]
        else {
          val arguments =
            typeArguments.getOrElse(Seq.fill(SignatureView.of(orv.signature).binders.size)(defaultBinding))
          for {
            held   <- abilityOfImplementation(orv).fold(Set.empty[Declared].pure[CompilerIO])(environmentEntries(marker, _))
            own    <- declaredEntries(marker)
            ground <- track.specialisation(marker, arguments)
          } yield ground.effects.received
            .filter(received =>
              performed.contains(received.entry.ability) && own.get(received.binderIndex).exists(held.contains)
            )
            .map(received => received.entry -> arguments.lift(received.binderIndex).getOrElse(defaultBinding))
        }
      }

    private def abilityOfImplementation(
        orv: OperatorResolvedValue
    ): Option[AbilityFQN] =
      orv.name.value.qualifier match {
        case com.vanillasource.eliot.eliotc.resolve.fact.Qualifier.AbilityImplementation(ability, _, _) => Some(ability)
        case _                                                                                          => None
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
