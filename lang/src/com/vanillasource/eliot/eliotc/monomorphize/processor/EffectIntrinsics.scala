package com.vanillasource.eliot.eliotc.monomorphize.processor

import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.{
  cellReadFQN,
  cellWriteFQN,
  escapeFrameFQN,
  escapeIntrinsicFQNs,
  exitIntrinsicFQNs,
  pairConstructorFQN,
  typeFQN,
  withCellFQN
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.{MetaStore, SemValue}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.{ConcreteNormalForm, Evaluator}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal

import java.util.concurrent.atomic.AtomicLong
import scala.util.DynamicVariable
import scala.util.control.ControlThrowable

/** The compiler platform's two control-flow primitives — **escape** and **cell** — as evaluator intrinsics
  * (`docs/effects.md` §3.4). A finishing clause is a non-local exit and a stateful implementation needs a value
  * threaded through calls that never mention it; neither is expressible as a def in a strict pure core, so each
  * platform has them as private leaves — an exception and a static field on the jvm, and here, on the compile track,
  * natives the one NbE evaluator runs directly.
  *
  * '''Escape: a frame is a value with identity.''' Each control effect's compile-track overlay module declares its own
  * private `escapeIntrinsic`/`exitIntrinsic` pair, as each jvm module declares its `escapeInternal`/`exitInternal`. The
  * escape makes a **fresh frame** and hands it to the body it runs — a thunk the environment threading made take the
  * frame (`monomorphize/channel/EnvironmentThreading`) — and `exitIntrinsic(frame, payload)` leaves through exactly
  * that frame, by identity. Which frame an operation reaches is decided by what was passed to it along the declared
  * chain, never by a search, so a helper's own `else` cannot catch the `abort` its caller wrote: the caller's block
  * holds the caller's frame. An exit through a frame that is not installed — its escape has returned, or what was
  * passed is not a frame at all, as in an evaluation nothing threaded — stays **stuck** on its own name, loud at
  * read-back, never a nearest-frame guess.
  *
  * '''Cell''' is still the earlier, type-keyed form: `withCell(S, s0, body)`, `read(S)` and `write(S, s)` reach the
  * nearest enclosing cell whose key is definitionally equal ([[ConcreteNormalForm]]). No compile-track effect is
  * written over it.
  *
  * '''Strictness at the frame.''' The evaluator applies a top-level definition lazily — the application only grows the
  * spine, and the body runs when the value is forced — so a thunk's *result* may still hold an application that would
  * exit or write when forced. Every value that crosses a frame boundary is therefore **settled** inside the frame
  * (forced, and its constructor and stuck-native spines with it — [[Evaluator.renormalize]]'s traversal, which stops at
  * a lambda since a lambda's calls run later): the body's result before the frame is left, an exit's payload before the
  * exit is taken, a cell's initial and written content. Pending applications settle in that traversal's order (head,
  * then arguments left to right), which is the one evaluation order the compile track has.
  *
  * '''What a caller must know.''' A nullary top-level definition's body is evaluated **once** per binding
  * ([[SemValue.Lazy]]), so a nullary def that `read`s a cell would answer its first read forever; a def that reaches a
  * cell must take an argument (a thunk does), or its binding must not be memoised.
  *
  * '''What the cell answers is the overlay's data.''' `withCell` answers `Pair`, the value constructor the
  * compile-track overlay declares (`stdlib/eliot/compiler/eliot/lang/`), applied to exactly its fields, so the
  * overlay's own `foldPair` takes it apart like any constructed value. The escape answers whatever its
  * `onExit`/`onValue` make.
  */
object EffectIntrinsics {

  /** The exact-FQN intrinsics, folded into [[SystemNativesProcessor]]'s system reductions. */
  val bindings: Map[ValueFQN, SemValue] =
    escapeIntrinsicFQNs.map(fqn => fqn -> escapeNative(fqn)).toMap ++
      exitIntrinsicFQNs.map(fqn => fqn -> exitNative(fqn)).toMap ++
      Map(
        withCellFQN  -> withCellNative,
        cellReadFQN  -> readNative,
        cellWriteFQN -> writeNative
      )

  /** The declared type of every key parameter: the type `Type`, not [[VType]] itself — a native whose parameter is
    * `VType` models *every* written type argument, and a key must arrive as a value, never be taken from a type
    * argument the reference happens to write (a phantom binder, an explicit `[E]`).
    */
  private val keyType: SemValue = VTopDef(typeFQN, None, Spine.SNil)

  /** The declared type of every escape argument, for the same reason as [[keyType]]: anything but [[VType]], so a
    * written type argument is never taken for a value argument.
    */
  private val argumentType: SemValue = keyType

  private final class CellFrame(val key: SemValue, var content: SemValue)

  /** The non-local exit in flight: names the one escape frame that catches it, so every frame between rethrows by not
    * matching. A control throwable — no stack trace, and not a `NonFatal` that a generic handler would swallow.
    */
  private final case class Exiting(frame: Long, value: SemValue) extends ControlThrowable

  /** The installed escape frames, innermost first. Dynamically scoped: a frame is installed exactly for the extent of
    * its escape, restored on return and on exit alike — which is what keeps an exit through a frame whose escape has
    * returned stuck rather than unwinding past everything.
    */
  private val escapes: DynamicVariable[List[Long]] = new DynamicVariable(Nil)

  /** The installed cells, innermost first, dynamically scoped like [[escapes]]. */
  private val cells: DynamicVariable[List[CellFrame]] = new DynamicVariable(Nil)

  private val frameIds: AtomicLong = new AtomicLong()

  private def nearestCell(key: SemValue): Option[CellFrame] =
    cells.value.find(frame => ConcreteNormalForm.equal(frame.key, key))

  /** Force a value that is about to cross a frame boundary, so every pending application inside it runs while the frame
    * is still installed — **natives included**, under the lookup the enclosing evaluation is running with
    * ([[Evaluator.currentNativeLookup]]).
    *
    * It passed no lookup at first, on the reasoning that "a stuck native is legitimately stuck and re-firing is the
    * checker's business". Inside a frame there is no later pass to re-fire it in: the frame is gone by the time the
    * checker looks again. So a condition computed by a native — `s == "/api"`, any comparison, any arithmetic — stayed
    * stuck, its `fold` never chose an arm, and the whole frame reduced to a stuck value that read back as a *false*
    * guard rather than as an error. That is the fail-safe direction, which is why it was invisible: the program
    * compiled and simply selected the other implementation. Measured 2026-09-09 against a bodied condition, which
    * reduced where the native one did not.
    *
    * Re-firing can only ever reduce further: [[Evaluator.renormalize]] keeps a native stuck when it does not fire.
    */
  private def settle(v: SemValue): SemValue =
    Evaluator.renormalize(v, MetaStore.empty, Evaluator.currentNativeLookup.value)

  // ----------------------------------------------------------------------------------------------------------------
  // escape
  // ----------------------------------------------------------------------------------------------------------------

  /** `escapeIntrinsic(body, onExit, onValue)` — make a fresh frame, run `body` with it, and answer `onValue` of the
    * result, or `onExit` of what an exit through this frame carried. Both handlers run after the frame is gone, as the
    * caller's code.
    */
  private def escapeNative(fqn: ValueFQN): SemValue =
    VNative(
      argumentType,
      body => VNative(argumentType, onExit => VNative(argumentType, onValue => escape(fqn, body, onExit, onValue)))
    )

  private def escape(fqn: ValueFQN, body: SemValue, onExit: SemValue, onValue: SemValue): SemValue = {
    val frame   = frameIds.incrementAndGet()
    val outcome =
      try {
        val result = escapes.withValue(frame :: escapes.value)(settle(Evaluator.applyValue(body, frameValue(frame))))
        // A body that settles to a **neutral** decided nothing: it is waiting on a variable this evaluation has not
        // bound, which is what happens when a definition's own body is reduced before its arguments arrive. Answering
        // there consumes the frame — the escape is gone from the reduced body, and when the neutral is finally
        // re-reduced at a concrete argument, an exit inside it has no frame to reach. Staying stuck instead lets the
        // whole escape re-fire once the argument is there, with a frame installed around the part that needs it.
        // Nor has a body decided anything whose result still holds an undispatched operation or a stuck escape or exit:
        // it was evaluated read raw, with nothing dispatched or threaded, so an operation in it never ran. Answering
        // would hand a handler a value the operation should have replaced, and a handler that ignores its argument
        // (`_ -> false`) turns that into a plausible wrong answer.
        if (isNeutral(result) || undecided(result)) None else Some(Right(result))
      } catch { case Exiting(exited, value) if exited == frame => Some(Left(value)) }
    outcome match {
      case None               => VStuckNative.of(fqn, body, onExit, onValue)
      case Some(Right(value)) => Evaluator.applyValue(onValue, value)
      case Some(Left(value))  => Evaluator.applyValue(onExit, value)
    }
  }

  /** Whether a settled value holds, at its head or in a constructor's or a stuck native's arguments, an ability
    * **method**'s call — an ability's marker is named after the ability itself and is not a call — or a stuck escape or
    * exit.
    */
  private def undecided(v: SemValue): Boolean = v match {
    case VTopDef(fqn, None, spine, _) =>
      (fqn.name.qualifier match {
        case Qualifier.Ability(ability) => fqn.name.name != ability
        case _                          => false
      }) || spine.toList.exists(undecided)
    case VStuckNative(fqn, spine)     =>
      escapeIntrinsicFQNs.contains(fqn) || exitIntrinsicFQNs.contains(fqn) || spine.toList.exists(undecided)
    case _                            => false
  }

  /** Whether a settled value is still waiting on a variable — a neutral head, or one under an application spine. */
  private def isNeutral(v: SemValue): Boolean = v match {
    case _: SemValue.VNeutral => true
    case _                    => false
  }

  /** `exitIntrinsic(frame, payload)` — leave through `frame` with `payload` settled first (an exit pending inside the
    * payload is taken before this one, as strict evaluation would). Stuck when `frame` is not an installed frame.
    */
  private def exitNative(fqn: ValueFQN): SemValue =
    VNative(argumentType, frame => VNative(argumentType, payload => exit(fqn, frame, payload)))

  private def exit(fqn: ValueFQN, frame: SemValue, payload: SemValue): SemValue =
    frameOf(frame).filter(escapes.value.contains) match {
      case Some(installed) => throw Exiting(installed, settle(payload))
      case None            => VStuckNative.of(fqn, frame, payload)
    }

  /** A frame as a value: stuck on a name nothing declares, carrying its identity. */
  private def frameValue(frame: Long): SemValue =
    VStuckNative.of(escapeFrameFQN, VConst(GroundValue.Direct(BigInt(frame), GroundValue.Type)))

  /** The identity of a frame value, or [[None]] for anything else — `unit`, a neutral, a parameter nothing passed. */
  private def frameOf(value: SemValue): Option[Long] =
    Evaluator.force(value, MetaStore.empty) match {
      case VStuckNative(fqn, Spine.SApp(Spine.SNil, VConst(GroundValue.Direct(Literal.IntegerValue(id), _))))
          if fqn == escapeFrameFQN =>
        Some(id.toLong)
      case _ => None
    }

  /** Run `body` with `count` fresh frames installed and handed to it, as an escape runs its body: [[Right]] of what it
    * answers, or [[Left]] of what an exit through one of them carried. The guarded-return read discharges a signature's
    * own control effects with it (`docs/effects.md` §3.4), installing its frames around the read-back as well as the
    * evaluation, since a signature's codomain is only evaluated when it is quoted.
    */
  def discharging(count: Int)(body: Seq[SemValue] => SemValue): Either[SemValue, SemValue] = {
    val frames = Seq.fill(count)(frameIds.incrementAndGet())
    try Right(escapes.withValue(frames.reverse.toList ++ escapes.value)(body(frames.map(frameValue))))
    catch { case Exiting(exited, value) if frames.contains(exited) => Left(value) }
  }

  // ----------------------------------------------------------------------------------------------------------------
  // cell
  // ----------------------------------------------------------------------------------------------------------------

  /** `withCell(key, initial, body)` — run the thunk under a cell keyed by `key` holding `initial`; answers
    * `pair(result, finalContent)`. Stuck while the key is not concrete. An exit through the call discards the cell.
    */
  private def withCellNative: SemValue =
    VNative(keyType, key => VNative(VType, initial => VNative(VType, body => withCell(key, initial, body))))

  private def withCell(key: SemValue, initial: SemValue, body: SemValue): SemValue =
    if (!ConcreteNormalForm.isConcrete(key)) VStuckNative.of(withCellFQN, key, initial, body)
    else {
      val frame  = new CellFrame(key, settle(initial))
      val result = cells.withValue(frame :: cells.value)(settle(Evaluator.applyValue(body, Evaluator.unitValue)))
      pair(result, frame.content)
    }

  /** `read(key)` — the content of the nearest enclosing cell keyed by `key`; stuck when there is none. */
  private def readNative: SemValue = VNative(keyType, key => read(key))

  private def read(key: SemValue): SemValue =
    nearestCell(key).map(_.content).getOrElse(VStuckNative.of(cellReadFQN, key))

  /** `write(key, content)` — replace the content of the nearest enclosing cell keyed by `key`, settling it first;
    * answers unit. Stuck when there is no such cell.
    */
  private def writeNative: SemValue =
    VNative(keyType, key => VNative(VType, content => write(key, content)))

  private def write(key: SemValue, content: SemValue): SemValue =
    nearestCell(key) match {
      case Some(frame) =>
        frame.content = settle(content)
        Evaluator.unitValue
      case None        => VStuckNative.of(cellWriteFQN, key, content)
    }

  private def pair(first: SemValue, second: SemValue): SemValue =
    VTopDef(pairConstructorFQN, None, Spine.SNil :+ first :+ second)
}
