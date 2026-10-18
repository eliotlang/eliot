package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.data.Chain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Visibility
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  ModuleName,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.resolve.fact.{
  AbilityFQN,
  QualifiedName as ResolvedQualifiedName,
  Qualifier as ResolvedQualifier
}
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** The search arm of [[AbilityResolver]], tested by **injection**: the reference is built by hand with its
  * ability-level arguments, and the two-site search is a stub that records what it was asked.
  *
  * No ability marker is registered, so the arity read falls back to "slice nothing" and a reference's whole argument
  * list is its ability-level slice, and the ability is not known to be an effect.
  */
class AbilityResolverBindingTest extends AnyFlatSpec with Matchers {

  private val uri = URI.create("Test.els")

  private def at[A](a: A): Sourced[A] = Sourced(uri, PositionRange.zero, a)

  private val throwModule = ModuleName(Seq("eliot", "effect"), "Throw")
  private val testModule  = ModuleName(Seq("eliot", "test"), "Impls")

  private val raise = at(ValueFQN(throwModule, QualifiedName("raise", Qualifier.Ability("Throw"))))

  private val string: GroundValue =
    GroundValue.Structure(
      ValueFQN(ModuleName(Seq("eliot", "lang"), "String"), QualifiedName("String", Qualifier.Type)),
      Seq.empty,
      GroundValue.Type
    )

  private val searchedImpl: (ValueFQN, Seq[GroundValue]) =
    (ValueFQN(throwModule, QualifiedName("raise", Qualifier.AbilityImplementation("Throw", "E", None))), Seq(string))

  /** The ability's module, declaring no member to read, so whether it is an effect is unknown and the reference goes to
    * the search ([[com.vanillasource.eliot.eliotc.ability.util.AbilityKind]]).
    */
  private val abilities = ModuleAbilities(throwModule, Seq.empty, Seq.empty)

  /** A [[CompilationProcess]] holding the ability's module: every other read answers "not produced". */
  private class Implementation extends CompilationProcess {
    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
        key: K,
        ancestors: List[CompilerFactKey[?]]
    ): IO[Option[V]] =
      IO.pure(
        Seq(abilities)
          .find(_.key() == key)
          .map(_.asInstanceOf[V])
      )

    override def registerFact(value: CompilerFact): IO[Unit] = IO.unit
  }

  /** Run the saturation pass over one reference carrying `args`, returning the recorded resolutions and every argument
    * list the two-site search was asked to resolve.
    */
  private type Resolutions = Map[CheckState.AbilityResolutionKey, (ValueFQN, Seq[GroundValue])]

  private def resolve(args: Seq[GroundValue]): (Resolutions, Seq[Seq[GroundValue]]) =
    run(args, Implementation()).fold(errors => fail(s"computation aborted: $errors"), identity)

  private def run(
      args: Seq[GroundValue],
      implementation: Implementation
  ): Either[Chain[CompilerError], (Resolutions, Seq[Seq[GroundValue]])] = {
    var searched = Seq.empty[Seq[GroundValue]]
    val search   = (_: ValueFQN, asked: Seq[GroundValue]) => {
      searched = searched :+ asked
      (Some(searchedImpl): Option[(ValueFQN, Seq[GroundValue])]).pure[CompilerIO]
    }
    val resolver = new AbilityResolver(search, Platform.Runtime)
    val pass     = resolver.resolveAbilities(Seq((raise, args.map(Evaluator.groundToSem))))
    pass.run(CheckState.initial).run(implementation).run(Chain.empty).value.unsafeRunSync().map {
      case (_, (state, _)) => (state.abilityResolutions, searched)
    }
  }

  "a reference" should "search at all of its ability-level arguments" in {
    resolve(Seq(string))._2 shouldBe Seq(Seq(string))
  }

  it should "record the search's answer under those arguments" in {
    resolve(Seq(string))._1 shouldBe Map((raise, Seq(string)) -> searchedImpl)
  }
}
