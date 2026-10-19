package com.vanillasource.eliot.eliotc.ability

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerFact
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI
import java.nio.file.Path

/** W2a leaf test (effectful-signatures): ability-instance resolution is platform-scoped, the ability analogue of CP3's
  * `CompilerNativesProcessor` for value bodies. An `implement` block present only in the **compiler** source pool — the
  * shape of the compile-time carrier's `Monad`/`Throw[Either[String]]` instances, which the W2 discharge must reach —
  * resolves when [[AbilityImplementation.Key]] is queried under [[Platform.Compiler]] and is **absent** under
  * [[Platform.Runtime]].
  *
  * The two pools share the ability declaration and the implemented type (`Show` + `Int`), exactly as the real carrier
  * shares the abstract `Throw`/`Either` base across both markers; only the `implement` lives compiler-only. The pools
  * are injected as one [[PathScan]] per marker (distinct URIs, same logical paths), mirroring `CompilerNativesProcessorTest`.
  */
class PlatformScopedAbilityResolutionTest
    extends ProcessorTest(LangProcessors(systemModules = ProcessorTest.systemModulesWithoutInt)*) {

  private val moduleName = ModuleName(Seq.empty, "M")

  // The base modules every resolution needs, keyed by `ModuleName` so each stub's path comes from the shared
  // `ModuleName.toPath` layout (no hard-coded `eliot/lang/…`). Present at both markers, just as the abstract base
  // (`lang` + `stdlib`) is on both the compiler and runtime paths. `PatternMatch`/`TypeMatch` live in the
  // `compilerInternalPackage`, `Type` in the `compilerPackage`; the rest in the `eliot.lang` prelude.
  private def lang(name: String): ModuleName        = ModuleName(ModuleName.defaultSystemPackage, name)
  private def internal(name: String): ModuleName    = ModuleName(ModuleName.compilerInternalPackage, name)
  private def compilerPkg(name: String): ModuleName = ModuleName(ModuleName.compilerPackage, name)

  private val systemStubs: Seq[(ModuleName, String)] = Seq(
    lang("Function")     -> "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B",
    compilerPkg("Type")  -> "type Type",
    lang("BigInteger")   -> "type BigInteger",
    lang("Unit")         -> "type Unit",
    lang("String")       -> "type String",
    // Every synthesized `implement`/`data` marker's default `true` guard resolves to `eliot.lang.Bool::true`, so Bool
    // is on both pools just like the real base layer (ability-guards §2.3).
    lang("Bool")         -> ProcessorTest.boolImportContent,
    internal("PatternMatch") -> "",
    internal("TypeMatch")    -> ""
  )

  // Shared by both pools: the ability and the type it is implemented for (cf. the abstract `Throw`/`Either` base).
  private val sharedM = "ability Show[A] { def show(x: A): A }\ndata Int"
  // The implementation lives ONLY in the compiler pool (cf. the carrier's compile-time `Monad`/`Throw` instances).
  private val implM   = "\nimplement Show[Int] { def show(x: Int): Int = x }"

  private def source(uri: URI, content: String): SourceContent =
    SourceContent(uri, Sourced(uri, PositionRange.zero, content))

  private def marker(platform: Platform): String = platform.toString.toLowerCase

  private def stubFacts(platform: Platform): Seq[CompilerFact] =
    systemStubs.flatMap { case (moduleName, content) =>
      val path = moduleName.toPath
      val uri  = URI.create(s"${marker(platform)}/$path")
      Seq(PathScan(path, Seq(uri), platform), source(uri, content))
    }

  private def moduleFacts(platform: Platform, content: String): Seq[CompilerFact] = {
    val uri = URI.create(s"${marker(platform)}/M.els")
    Seq(PathScan(Path.of("M.els"), Seq(uri), platform), source(uri, content))
  }

  private val pools: Seq[CompilerFact] =
    stubFacts(Platform.Compiler) ++ stubFacts(Platform.Runtime) ++
      moduleFacts(Platform.Compiler, sharedM + implM) ++
      moduleFacts(Platform.Runtime, sharedM)

  private val showVfqn        = ValueFQN(moduleName, QualifiedName("show", Qualifier.Ability("Show")))
  private val intArg: GroundValue =
    GroundValue.Structure(ValueFQN(moduleName, QualifiedName("Int", Qualifier.Type)), Seq.empty, GroundValue.Type)

  private def resolveUnder(platform: Platform): IO[Option[AbilityImplementation]] =
    runGeneratorWithFacts(pools, AbilityImplementation.Key(showVfqn, Seq(intArg), platform)).map(_._1)

  "platform-scoped ability resolution" should "resolve a compiler-pool-only instance under the compiler marker" in {
    resolveUnder(Platform.Compiler).asserting(_.flatMap(_.resolution.resolved).map(_._1.name.name) shouldBe Some("show"))
  }

  it should "tag the resolved implementation with the compiler marker" in {
    resolveUnder(Platform.Compiler).asserting(_.map(_.platform) shouldBe Some(Platform.Compiler))
  }

  it should "not resolve the compiler-pool-only instance under the runtime marker" in {
    resolveUnder(Platform.Runtime)
      .asserting(_.map(_.resolution) shouldBe Some(AbilityImplementation.Resolution.NoImplementation))
  }
}
