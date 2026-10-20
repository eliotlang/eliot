package com.vanillasource.eliot.eliotc.monomorphize.processor

import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.{
  escapeFrameFQN,
  escapeIntrinsicFQNs,
  exitIntrinsicFQNs,
  typeFQN
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.{FlexStore, SemValue}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal

import java.util.concurrent.atomic.AtomicLong
import scala.util.DynamicVariable
import scala.util.control.ControlThrowable

/** The compiler platform's control-flow primitive — **escape** — as an evaluator intrinsic (`docs/effects.md` §3.4). A
  * finishing clause is a non-local exit, which is not expressible as a def in a strict pure core, so each platform has
  * it as a private leaf — an exception on the jvm, and here, on the compile track, a native the one NbE evaluator runs
  * directly. The compile track has no **cell**, since no compile-track effect is written over one.
  *
  * '''Escape: a frame is a value with identity.''' Each control effect's compile-track overlay module declares its own
  * private `escapeIntrinsic`/`exitIntrinsic` pair, as each jvm module declares its `escapeInternal`/`exitInternal`. The
  * escape makes a **fresh frame** and hands it to the body it runs — which binds it in a handle with `with`
  * (`docs/effects.md` §3.4) — and `exitIntrinsic(frame, payload)` leaves through exactly that frame, by identity. Which
  * frame an operation reaches is decided by the handle passed to it, never by a search, so a helper's own `else`
  * cannot catch the `abort` its caller wrote: the caller's block holds the caller's frame. An exit through a frame that
  * is not installed — its escape has returned, or what was passed is not a frame at all, as in an evaluation no handle
  * reached — stays **stuck** on its own name, loud at read-back, never a nearest-frame guess.
  *
  * '''Strictness at the frame.''' The evaluator applies a top-level definition lazily — the application only grows the
  * spine, and the body runs when the value is forced — so a thunk's *result* may still hold an application that would
  * exit when forced. Every value that crosses a frame boundary is therefore **settled** inside the frame
  * (forced, and its constructor and stuck-native spines with it — [[Evaluator.renormalize]]'s traversal, which stops at
  * a lambda since a lambda's calls run later): the body's result before the frame is left, and an exit's payload before
  * the exit is taken. Pending applications settle in that traversal's order (head, then arguments left to right), which
  * is the one evaluation order the compile track has.
  *
  * The escape answers whatever its `onExit`/`onValue` make.
  */
object EffectIntrinsics {

  /** The exact-FQN intrinsics, folded into [[SystemNativesProcessor]]'s system reductions. */
  val bindings: Map[ValueFQN, SemValue] =
    escapeIntrinsicFQNs.map(fqn => fqn -> escapeNative(fqn)).toMap ++
      exitIntrinsicFQNs.map(fqn => fqn -> exitNative(fqn)).toMap

  /** The declared type of every escape argument: the type `Type`, not [[VType]] itself — a native whose parameter is
    * `VType` models *every* written type argument, and an argument must arrive as a value, never be taken from a type
    * argument the reference happens to write (an explicit `[E]`).
    */
  private val argumentType: SemValue = VTopDef(typeFQN, None, Spine.SNil)

  /** The non-local exit in flight: names the one escape frame that catches it, so every frame between rethrows by not
    * matching. A control throwable — no stack trace, and not a `NonFatal` that a generic handler would swallow.
    */
  private final case class Exiting(frame: Long, value: SemValue) extends ControlThrowable

  /** The installed escape frames, innermost first. Dynamically scoped: a frame is installed exactly for the extent of
    * its escape, restored on return and on exit alike — which is what keeps an exit through a frame whose escape has
    * returned stuck rather than unwinding past everything.
    */
  private val escapes: DynamicVariable[List[Long]] = new DynamicVariable(Nil)

  private val frameIds: AtomicLong = new AtomicLong()

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
    Evaluator.renormalize(v, FlexStore.empty, Evaluator.currentNativeLookup.value)

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
    Evaluator.force(value, FlexStore.empty) match {
      case VStuckNative(fqn, Spine.SApp(Spine.SNil, VConst(GroundValue.Direct(Literal.IntegerValue(id), _))))
          if fqn == escapeFrameFQN =>
        Some(id.toLong)
      case _ => None
    }
}
