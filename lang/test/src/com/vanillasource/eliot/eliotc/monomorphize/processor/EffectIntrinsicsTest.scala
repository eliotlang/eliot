package com.vanillasource.eliot.eliotc.monomorphize.processor

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The compile track's escape and cell primitives, fired directly as the evaluator fires them (`docs/effects.md`
  * §3.4): an escape hands its body a fresh frame and catches exactly the exit through that frame, whatever is installed
  * in between; a cell threads a value through a body that never mentions it; both settle what crosses the frame so a
  * lazily pending application runs inside it; and every use with no installed frame to reach stays stuck rather than
  * answering.
  */
class EffectIntrinsicsTest extends AnyFlatSpec with Matchers {
  private def native(fqn: ValueFQN): SemValue = EffectIntrinsics.bindings(fqn)

  private def app(f: SemValue, args: SemValue*): SemValue = args.foldLeft(f)(Evaluator.applyValue)

  private def spineOf(args: SemValue*): Spine = args.foldLeft(Spine.SNil: Spine)(_ :+ _)

  private def str(s: String): SemValue = VConst(GroundValue.Direct(s, Evaluator.stringGroundType))
  private def int(i: Int): SemValue    = VConst(GroundValue.Direct(BigInt(i), Evaluator.bigIntGroundType))

  /** Two instantiations to key frames by: the types `String` and `BigInteger`, as values. */
  private val stringKey: SemValue = VTopDef(stringFQN, None, Spine.SNil)
  private val intKey: SemValue    = VTopDef(bigIntFQN, None, Spine.SNil)

  /** A rigid bound variable — a not-yet-concrete key. */
  private val rigid: SemValue = VNeutral(NeutralHead.Param(0, "x"), Spine.SNil)

  private def left(v: SemValue): SemValue          = VTopDef(leftFQN, None, spineOf(v))
  private def right(v: SemValue): SemValue         = VTopDef(rightFQN, None, spineOf(v))
  private def pair(a: SemValue, b: SemValue): SemValue = VTopDef(pairConstructorFQN, None, spineOf(a, b))

  private def thunk(body: => SemValue): SemValue = VLam("_", _ => body)

  private val escapeFqn: ValueFQN = escapeIntrinsicFQNs.head
  private val exitFqn: ValueFQN   = exitIntrinsicFQNs.head

  /** The handlers the overlay's `runThrow` passes: `Left` of what left, `Right` of what finished. */
  private val onExit: SemValue  = VLam("e", left)
  private val onValue: SemValue = VLam("a", right)

  private def escape(body: SemValue => SemValue): SemValue =
    app(native(escapeFqn), VLam("frame", body), onExit, onValue)
  private def exit(frame: SemValue, value: SemValue): SemValue = app(native(exitFqn), frame, value)

  private def withCell(key: SemValue, initial: SemValue, body: => SemValue): SemValue =
    app(native(withCellFQN), key, initial, thunk(body))
  private def read(key: SemValue): SemValue                  = app(native(cellReadFQN), key)
  private def write(key: SemValue, value: SemValue): SemValue = app(native(cellWriteFQN), key, value)

  /** A top-level definition whose body is `body`: applied lazily by the evaluator, it runs only when forced. */
  private val laterFqn: ValueFQN                   = ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName("later", Qualifier.Default))
  private def later(body: => SemValue): SemValue   = VTopDef(laterFqn, Some(Lazy(body)), Spine.SNil)
  private def marked(inner: SemValue): SemValue    = VTopDef(laterFqn, None, spineOf(inner))

  "escape" should "answer its onValue of the body's result when nothing exits" in {
    escape(_ => str("done")) shouldBe right(str("done"))
  }

  it should "answer its onExit of the value exited through its frame" in {
    escape(frame => exit(frame, str("boom"))) shouldBe left(str("boom"))
  }

  it should "catch an exit through its own frame inside another escape" in {
    escape(_ => escape(inner => exit(inner, str("inner")))) shouldBe right(left(str("inner")))
  }

  it should "let an exit through an outer frame pass every frame installed in between" in {
    escape(outer => escape(_ => exit(outer, str("outer")))) shouldBe left(str("outer"))
  }

  it should "run a pending application of the body's result inside the frame" in {
    escape(frame => later(exit(frame, str("late")))) shouldBe left(str("late"))
  }

  it should "settle the constructor arguments of the body's result inside the frame" in {
    escape(frame => marked(later(exit(frame, str("deep"))))) shouldBe left(str("deep"))
  }

  it should "settle an exit's payload before taking it, so an exit pending inside it wins" in {
    escape(frame => exit(frame, later(exit(frame, str("first"))))) shouldBe left(str("first"))
  }

  it should "stay stuck when its body settles to a neutral" in {
    val body = VLam("frame", _ => rigid)
    app(native(escapeFqn), body, onExit, onValue) shouldBe VStuckNative(escapeFqn, spineOf(body, onExit, onValue))
  }

  it should "stay stuck when its body's result holds an operation that did not run" in {
    val body = VLam("frame", _ => marked(exit(Evaluator.unitValue, str("x"))))
    app(native(escapeFqn), body, onExit, onValue) shouldBe VStuckNative(escapeFqn, spineOf(body, onExit, onValue))
  }

  "exit" should "stay stuck on a value that is not a frame" in {
    exit(Evaluator.unitValue, str("x")) shouldBe VStuckNative(exitFqn, spineOf(Evaluator.unitValue, str("x")))
  }

  it should "stay stuck through a frame whose escape has returned" in {
    var kept: SemValue = Evaluator.unitValue
    escape { frame => kept = frame; str("a") }
    exit(kept, str("b")) shouldBe VStuckNative(exitFqn, spineOf(kept, str("b")))
  }

  "discharging" should "answer Right of the body's result when nothing exits" in {
    EffectIntrinsics.discharging(1)(_ => str("ok")) shouldBe Right(str("ok"))
  }

  it should "answer Left of the value exited through any of its frames" in {
    EffectIntrinsics.discharging(2)(frames => exit(frames(1), str("x"))) shouldBe Left(str("x"))
  }

  "withCell" should "pair the body's result with the initial content when nothing writes" in {
    withCell(intKey, int(0), str("r")) shouldBe pair(str("r"), int(0))
  }

  it should "let the body read the content" in {
    withCell(intKey, int(7), read(intKey)) shouldBe pair(int(7), int(7))
  }

  it should "thread a written content to a later read and out of the frame" in {
    withCell(intKey, int(0), { write(intKey, int(3)); read(intKey) }) shouldBe pair(int(3), int(3))
  }

  it should "answer unit for a write" in {
    withCell(intKey, int(0), write(intKey, int(1))) shouldBe pair(Evaluator.unitValue, int(1))
  }

  it should "reach the nearest cell of the same instantiation and pass another's through" in {
    withCell(intKey, int(1), withCell(stringKey, str("s"), { write(intKey, int(2)); read(stringKey) })) shouldBe
      pair(pair(str("s"), str("s")), int(2))
  }

  it should "run a pending application of the body's result inside the frame" in {
    withCell(intKey, int(0), later(read(intKey))) shouldBe pair(int(0), int(0))
  }

  it should "settle a written content inside the frame" in {
    withCell(intKey, int(0), { write(intKey, later(int(9))); read(intKey) }) shouldBe pair(int(9), int(9))
  }

  it should "be discarded by an exit through it, restoring the frames outside" in {
    escape(frame => withCell(intKey, int(0), { write(intKey, int(5)); exit(frame, str("out")) })) shouldBe
      left(str("out"))
    read(intKey) shouldBe VStuckNative(cellReadFQN, spineOf(intKey))
  }

  it should "stay stuck on a key that is not concrete" in {
    val body = thunk(str("x"))
    app(native(withCellFQN), rigid, int(0), body) shouldBe VStuckNative(withCellFQN, spineOf(rigid, int(0), body))
  }

  "read" should "stay stuck when no cell of its instantiation is installed" in {
    withCell(stringKey, str("s"), read(intKey)) shouldBe pair(VStuckNative(cellReadFQN, spineOf(intKey)), str("s"))
  }

  "write" should "stay stuck when no cell of its instantiation is installed" in {
    write(intKey, int(1)) shouldBe VStuckNative(cellWriteFQN, spineOf(intKey, int(1)))
  }
}
