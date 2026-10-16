package com.vanillasource.eliot.eliotc.monomorphize.check

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** The identity of a resolved ability reference ([[CheckState.AbilityResolutionKey]] and
  * [[CheckState.lookupAbilityResolution]]).
  *
  * A source position is *not* a node identity. Two references to the same operation can share a span and an FQN and
  * still resolve differently, because what decides an operation's implementation is a type argument — its callee's
  * **phantom binding** (effects v6, `docs/effects.md` §9.4). Keyed by position alone the two collapse to one entry and
  * the second silently inherits the first one's implementation, which is a miscompile with no diagnostic anywhere. The
  * ability-level type arguments are therefore part of the key.
  */
class AbilityResolutionKeyTest extends AnyFlatSpec with Matchers {

  private val uri = URI.create("Test.els")

  /** Both machinery references sit at the same span — this is the whole point. */
  private def sameSpan[A](a: A): Sourced[A] = Sourced(uri, PositionRange.zero, a)

  /** An implementation *name* as a ground type argument — what a phantom binder is instantiated to. */
  private def binding(name: String): GroundValue =
    GroundValue.Structure(
      ValueFQN(ModuleName(Seq("app"), "Terminals"), QualifiedName(name, Qualifier.Type)),
      Seq.empty,
      GroundValue.Type
    )

  private val quietBinding: GroundValue    = binding("quiet")
  private val standardBinding: GroundValue = binding("standard")

  private def impl(name: String): (ValueFQN, Seq[GroundValue]) =
    (ValueFQN(ModuleName(Seq("app"), "Terminals"), QualifiedName(name, Qualifier.Default)), Seq.empty)

  private val writeRef =
    sameSpan(ValueFQN(ModuleName(Seq("app"), "Terminals"), QualifiedName("write", Qualifier.Ability("Terminal"))))
  private val quietImpl    = impl("writeQuiet")
  private val standardImpl = impl("writeStandard")
  private val collided     = Map(
    (writeRef, Seq(quietBinding))    -> quietImpl,
    (writeRef, Seq(standardBinding)) -> standardImpl
  )

  private def lookup(
      resolutions: Map[CheckState.AbilityResolutionKey, (ValueFQN, Seq[GroundValue])],
      args: Option[Seq[GroundValue]]
  ) = CheckState.lookupAbilityResolution(resolutions, writeRef, args)

  "two references at one span" should "each resolve to the impl of their own binding" in {
    (lookup(collided, Some(Seq(quietBinding))), lookup(collided, Some(Seq(standardBinding)))) shouldBe
      (Some(quietImpl), Some(standardImpl))
  }

  it should "decline rather than guess when the reader cannot derive the binding" in {
    lookup(collided, None) shouldBe None
  }

  "a reference resolved from its own ground arguments" should "be found by those arguments" in {
    lookup(Map((writeRef, Seq(standardBinding)) -> standardImpl), Some(Seq(standardBinding))) shouldBe
      Some(standardImpl)
  }

  it should "still be found when the reader cannot derive the arguments, being the only entry at that span" in {
    lookup(Map((writeRef, Seq(standardBinding)) -> standardImpl), None) shouldBe Some(standardImpl)
  }

  "an unresolved reference" should "be absent" in {
    lookup(Map.empty, Some(Seq(standardBinding))) shouldBe None
  }
}
