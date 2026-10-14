package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.data.Chain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
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

/** The read-the-argument arm of [[AbilityResolver]] (effects v6, `docs/effects.md` §9.4 step 4 / §10.1 step 6),
  * tested by **injection**: the references are built by hand with their ability-level arguments beginning with a
  * binding, and the two-site search is a stub that records what it was asked.
  *
  * The only fact is the named implementation's marker, `implement[E] loggingThrow: Throw[E]`'s, which the arm matches
  * against the pattern arguments to solve the implementation's `E` (§8 item 30). No ability marker is registered, so
  * the arity read falls back to "slice nothing" and a reference's whole argument list is its ability-level slice.
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

  private val namedQualifier = Qualifier.AbilityImplementation("Throw", "E", Some("loggingThrow"))
  private val implMarker     = ValueFQN(testModule, QualifiedName("Throw", namedQualifier))
  private val implBinding    = GroundValue.Structure(implMarker, Seq(string), GroundValue.Type)

  /** `E: Type -> E -> Bool`, unguarded: the marker of an implementation whose pattern is its type parameter. */
  private val implMarkerValue = OperatorResolvedValue(
    implMarker,
    at(
      ResolvedQualifiedName(
        "Throw",
        ResolvedQualifier.AbilityImplementation(AbilityFQN(throwModule, "Throw"), "E", Some("loggingThrow"))
      )
    ),
    None,
    at(
      OperatorResolvedExpression.FunctionLiteral(
        at("E"),
        Some(at(OperatorResolvedExpression.ValueReference(at(WellKnownTypes.typeFQN)))),
        at(
          OperatorResolvedExpression.arrow(
            at(OperatorResolvedExpression.ParameterReference(at("E"))),
            at(OperatorResolvedExpression.ValueReference(at(WellKnownTypes.boolTrueFQN)))
          )
        )
      )
    )
  )

  private val searchedImpl: (ValueFQN, Seq[GroundValue]) =
    (ValueFQN(throwModule, QualifiedName("raise", Qualifier.AbilityImplementation("Throw", "E", None))), Seq(string))

  /** A [[CompilationProcess]] holding only the named implementation's marker: every other read answers "not produced". */
  private object markerOnly extends CompilationProcess {
    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
        key: K,
        ancestors: List[CompilerFactKey[?]]
    ): IO[Option[V]] =
      IO.pure(Option.when(key == implMarkerValue.key())(implMarkerValue.asInstanceOf[V]))

    override def registerFact(value: CompilerFact): IO[Unit] = IO.unit
  }

  /** Run the saturation pass over one reference carrying `args`, returning the recorded resolutions and every argument
    * list the two-site search was asked to resolve.
    */
  private type Resolutions = Map[CheckState.AbilityResolutionKey, (ValueFQN, Seq[GroundValue])]

  private def resolve(args: Seq[GroundValue]): (Resolutions, Seq[Seq[GroundValue]]) = {
    var searched = Seq.empty[Seq[GroundValue]]
    val search   = (_: ValueFQN, asked: Seq[GroundValue]) => {
      searched = searched :+ asked
      (Some(searchedImpl): Option[(ValueFQN, Seq[GroundValue])]).pure[CompilerIO]
    }
    val resolver = new AbilityResolver(search, (_, _) => Seq.empty.pure[CompilerIO], Platform.Runtime)
    val pass     = resolver.resolveAbilities(Seq((raise, args.map(Evaluator.groundToSem))), Map.empty)
    pass.run(CheckState.initial).run(markerOnly).run(Chain.empty).value.unsafeRunSync() match {
      case Right((_, (state, _))) => (state.abilityResolutions, searched)
      case Left(errors)           => fail(s"computation aborted: $errors")
    }
  }

  "a reference bound to an implementation" should "resolve directly to that implementation's method" in {
    resolve(Seq(implBinding, string))._1 shouldBe
      Map((raise, Some(Seq(implBinding, string))) ->
        (ValueFQN(testModule, QualifiedName("raise", namedQualifier)), Seq(string)))
  }

  it should "never ask the two-site search" in {
    resolve(Seq(implBinding, string))._2 shouldBe Seq.empty
  }

  it should "run at what its pattern takes at the pattern arguments, whatever the binding carries for it" in {
    val unsolved = GroundValue.Structure(implMarker, Seq(GroundValue.Type), GroundValue.Type)
    resolve(Seq(unsolved, string))._1 shouldBe
      Map((raise, Some(Seq(unsolved, string))) -> (ValueFQN(testModule, QualifiedName("raise", namedQualifier)), Seq(string)))
  }

  "a reference bound to Default" should "search at the pattern arguments, with the binding stripped" in {
    resolve(Seq(ImplementationBinding.defaultGround, string))._2 shouldBe Seq(Seq(string))
  }

  it should "record the search's answer under the full arguments, binding included" in {
    resolve(Seq(ImplementationBinding.defaultGround, string))._1 shouldBe
      Map((raise, Some(Seq(ImplementationBinding.defaultGround, string))) -> searchedImpl)
  }

  "a reference with no binding" should "search at all of its ability-level arguments, as today" in {
    resolve(Seq(string))._2 shouldBe Seq(Seq(string))
  }

  it should "record the search's answer under those arguments" in {
    resolve(Seq(string))._1 shouldBe Map((raise, Some(Seq(string))) -> searchedImpl)
  }
}
