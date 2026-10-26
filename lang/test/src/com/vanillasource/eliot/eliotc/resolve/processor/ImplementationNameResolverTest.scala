package com.vanillasource.eliot.eliotc.resolve.processor

import cats.syntax.all.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.runCompilerIO
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

/** Resolving the `h` of a `with h` (effects v6, `docs/effects.md` §9.4 step 1), tested by **injection**: `with` is
  * still rejected at core, so nothing calls this yet. The dictionary and the module's [[ModuleAbilities]] are built by
  * hand — the two keyed steps the resolution is made of.
  */
class ImplementationNameResolverTest extends AnyFlatSpec with Matchers {

  private val uri  = URI.create("Test.els")
  private val fake = ModuleName(Seq("test"), "Fakes")

  private def at[A](a: A): Sourced[A] = Sourced(uri, PositionRange.zero, a)

  private val nameMarker = ValueFQN(fake, QualifiedName("recordingConsole", Qualifier.Implementation("recordingConsole")))

  private val implQualifier = Qualifier.AbilityImplementation("Console", "", Some("recordingConsole"))
  private val implMarker    = ValueFQN(fake, QualifiedName("Console", implQualifier))

  private val abilities = ModuleAbilities(
    fake,
    Seq.empty,
    Seq(
      ModuleAbilities.Impl(
        ValueFQN(fake, QualifiedName("printLine", implQualifier)),
        "printLine",
        "Console",
        "",
        Some("recordingConsole")
      ),
      ModuleAbilities.Impl(implMarker, "Console", "Console", "", Some("recordingConsole"))
    )
  )

  /** A [[CompilationProcess]] answering exactly the facts in `facts`, and nothing else. */
  private def process(facts: Map[CompilerFactKey[?], CompilerFact]): CompilationProcess = new CompilationProcess {
    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
        key: K,
        ancestors: List[CompilerFactKey[?]]
    ): Option[V] = facts.get(key).map(_.asInstanceOf[V])

    override def registerFact(value: CompilerFact): Unit = ()
  }

  private def resolve(
      dictionary: Map[QualifiedName, ValueFQN],
      facts: Map[CompilerFactKey[?], CompilerFact]
  ): Either[Seq[String], ValueFQN] = {
    val scope = ValueResolverScope(fake, dictionary, Map.empty, Set.empty)
    runCompilerIO(process(facts))(ImplementationNameResolver.resolve(at("recordingConsole")).runA(scope))
      .bimap(_.toList.map(_.message), _._2)
  }

  private val inScope = Map(nameMarker.name -> nameMarker)
  private val known   = Map[CompilerFactKey[?], CompilerFact](ModuleAbilities.Key(fake, Platform.Runtime) -> abilities)

  "a named implementation in scope" should "resolve to its implementation marker" in {
    resolve(inScope, known) shouldBe Right(implMarker)
  }

  "a name not in the dictionary" should "be an error at the name" in {
    resolve(Map.empty, known) shouldBe Left(Seq("Implementation not found."))
  }

  "a name marker whose module declares no such implementation" should "be an error at the name" in {
    val empty = Map[CompilerFactKey[?], CompilerFact](
      ModuleAbilities.Key(fake, Platform.Runtime) -> abilities.copy(implementations = Seq.empty)
    )
    resolve(inScope, empty) shouldBe Left(Seq("Implementation not found."))
  }
}
