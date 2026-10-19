package com.vanillasource.eliot.eliotc.monomorphize.unify

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Quoter
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** D3: stuck native applications carry a distinct [[SemValue.VStuckNative]] head, so the unifier never
  * injectivity-decomposes a non-injective native (the F1 landmine), two stuck natives are equal only when their FQN and
  * arguments match, and the quoter fails loudly on one that survives to read-back.
  *
  * The adversarial case is an applied meta `?F[a]` unified against a stuck-native-headed value `add(x, y)`: a real
  * (injective) constructor decomposes (`?F := Box`), the stuck native must not — it has to postpone instead, leaving
  * the meta for `renormalize`/defaulting once its arguments concretise.
  */
class StuckNativeUnifyTest extends AnyFlatSpec with Matchers {
  private val ctx: Sourced[String] = Sourced(URI.create("Test.els"), PositionRange.zero, "ctx")

  private def fqn(name: String, qualifier: Qualifier): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, qualifier))

  private val addFqn: ValueFQN = fqn("add", Qualifier.Default)
  private val subFqn: ValueFQN = fqn("subtract", Qualifier.Default)
  private val boxFqn: ValueFQN = fqn("Box", Qualifier.Type)

  /** A rigid bound-variable argument, standing in for a not-yet-concrete operand of a stuck native. */
  private def rigid(name: String): SemValue = VNeutral(NeutralHead.Param(0, name), Spine.SNil)

  private def spineOf(args: SemValue*): Spine          = args.foldLeft(Spine.SNil: Spine)(_ :+ _)
  private def stuck(f: ValueFQN, args: SemValue*): SemValue = VStuckNative(f, spineOf(args*))

  /** A fresh unifier with one metavariable, plus that meta's id. */
  private def withMeta(): (SemValue.MetaId, Unifier) = {
    val (id, store) = MetaStore.empty.fresh
    (id, Unifier.create(store, 0))
  }

  "an applied meta unified against a constructor application" should "decompose injectively, solving the meta to the constructor" in {
    val (id, u) = withMeta()
    val result  = u.unify(VMeta(id, spineOf(rigid("a"))), VTopDef(boxFqn, None, spineOf(rigid("a"))), ctx).drain()
    (result.errors, result.metaStore.lookup(id)) shouldBe (Nil, Some(VTopDef(boxFqn, None, Spine.SNil)))
  }

  "an applied meta unified against a stuck native application" should "not injectivity-decompose the non-injective native" in {
    val (id, u) = withMeta()
    u.unify(VMeta(id, spineOf(rigid("a"))), stuck(addFqn, rigid("x"), rigid("y")), ctx).drain().metaStore.lookup(id) shouldBe None
  }

  it should "postpone the constraint rather than erroring" in {
    val (id, u) = withMeta()
    val result  = u.unify(VMeta(id, spineOf(rigid("a"))), stuck(addFqn, rigid("x"), rigid("y")), ctx)
    (result.errors, result.postponed.size) shouldBe (Nil, 1)
  }

  "two stuck natives with the same FQN and arguments" should "unify by definitional equality" in {
    val (_, u) = withMeta()
    u.unify(stuck(addFqn, rigid("x"), rigid("y")), stuck(addFqn, rigid("x"), rigid("y")), ctx).errors shouldBe empty
  }

  "two stuck natives with different FQNs" should "fail to unify" in {
    val (_, u) = withMeta()
    u.unify(stuck(addFqn, rigid("x"), rigid("y")), stuck(subFqn, rigid("x"), rigid("y")), ctx).errors should not be empty
  }

  "a stuck native surviving to read-back" should "make the quoter fail loudly" in {
    Quoter.quote(0, stuck(addFqn, rigid("x"), rigid("y")), MetaStore.empty) shouldBe
      Left(s"Cannot quote stuck native application ${addFqn.show}")
  }
}
