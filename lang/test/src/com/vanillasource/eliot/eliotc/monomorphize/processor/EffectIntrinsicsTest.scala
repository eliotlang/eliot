package com.vanillasource.eliot.eliotc.monomorphize.processor

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The compile track's escape primitive, fired directly as the evaluator fires it (`docs/effects.md` §3.4): an escape
  * hands its body a fresh frame and catches exactly the exit through that frame, whatever is installed in between; it
  * settles what crosses the frame so a lazily pending application runs inside it; and an exit with no installed frame
  * to reach stays stuck rather than answering.
  */
class EffectIntrinsicsTest extends AnyFlatSpec with Matchers {
  private def native(fqn: ValueFQN): SemValue = EffectIntrinsics.bindings(fqn)

  private def app(f: SemValue, args: SemValue*): SemValue = args.foldLeft(f)(Evaluator.applyValue)

  private def spineOf(args: SemValue*): Spine = args.foldLeft(Spine.SNil: Spine)(_ :+ _)

  private def str(s: String): SemValue = VConst(GroundValue.Direct(s, Evaluator.stringGroundType))

  /** A rigid bound variable — a value not yet known. */
  private val rigid: SemValue = VNeutral(NeutralHead.Param(0, "x"), Spine.SNil)

  private def left(v: SemValue): SemValue  = VTopDef(leftFQN, None, spineOf(v))
  private def right(v: SemValue): SemValue = VTopDef(rightFQN, None, spineOf(v))

  private val escapeFqn: ValueFQN = escapeIntrinsicFQNs.head
  private val exitFqn: ValueFQN   = exitIntrinsicFQNs.head

  /** The handlers the overlay's `runThrow` passes: `Left` of what left, `Right` of what finished. */
  private val onExit: SemValue  = VLam("e", left)
  private val onValue: SemValue = VLam("a", right)

  private def escape(body: SemValue => SemValue): SemValue =
    app(native(escapeFqn), VLam("frame", body), onExit, onValue)
  private def exit(frame: SemValue, value: SemValue): SemValue = app(native(exitFqn), frame, value)

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
}
