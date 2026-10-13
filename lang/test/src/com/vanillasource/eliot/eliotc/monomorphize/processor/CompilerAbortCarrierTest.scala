package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, Role, UnifiedModuleNames}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI
import java.nio.file.Path

/** The signature split, Step 7: the compile-time `Abort` carrier overlay (`stdlib/eliot/compiler/eliot/effect/Abort.els`)
  * — the missing sibling of the `Either`/`Option` overlays — is well-formed Eliot and extracts into the **compiler**
  * source pool: the concrete `data AbortCarrier` plus the compile-time `Effect[AbortCarrier[G]]` / `Abort[AbortCarrier[G]]`
  * instances. It is what lets a `{Abort}`-carrier return guard (`if(cond) T else raise(msg)`) reduce on the compiler
  * track — `if` introduces `{Abort}` over the compile-time base and `else`/`runAbort` discharge it.
  *
  * Like [[CompilerEitherCarrierTest]], name extraction runs entirely before resolution (`ModuleNames` reads a file's own
  * declarations), so the overlay is injected as a single `compiler`-marker [[PathScan]] with no base stubs: its `import`s
  * and inner references need not resolve for the names to be collected. A malformed overlay cannot then ship unnoticed.
  */
class CompilerAbortCarrierTest extends ProcessorTest(LangProcessors(systemModules = Seq.empty)*) {
  private val abortModule = ModuleName(Seq("eliot", "effect"), "Abort")
  private val abortPath    = Path.of("eliot", "effect", "Abort.els")
  private val abortUri     = URI.create("eliot/effect/Abort.els")

  // A faithful copy of `stdlib/eliot/compiler/eliot/effect/Abort.els`'s shape: the concrete carrier plus the two
  // compile-time ability instances (the shipped file is exercised end-to-end by the LSP/jvm suites).
  private val carrier =
    """import eliot.carrier.Effect
      |import eliot.lang.Option
      |
      |ability Abort[F[_]] {
      |   def abort[A] uses Abort: A
      |}
      |
      |data AbortCarrier[G[_], A](runAbort: G[Option[A]])
      |
      |implement[G[_] ~ Effect] Effect[AbortCarrier[G]] {
      |   def pure[A](a: A): AbortCarrier[G, A] = AbortCarrier(pure(Some(a)))
      |   def flatMap[A, B](f: Function[A, AbortCarrier[G, B]], fa: AbortCarrier[G, A]): AbortCarrier[G, B] =
      |      AbortCarrier(flatMap(o -> foldOption(pure(None), a -> runAbort(f(a)), o), runAbort(fa)))
      |   def map[A, B](f: Function[A, B], fa: AbortCarrier[G, A]): AbortCarrier[G, B] =
      |      AbortCarrier(map(o -> foldOption(None, a -> Some(f(a)), o), runAbort(fa)))
      |}
      |
      |implement[G[_] ~ Effect] Abort[AbortCarrier[G]] {
      |   def abort[A]: AbortCarrier[G, A] = AbortCarrier(pure(None))
      |}
      |""".stripMargin

  private val compilerPool = Seq(
    PathScan(abortPath, Seq(abortUri), Platform.Compiler),
    SourceContent(abortUri, Sourced(abortUri, PositionRange.zero, carrier))
  )

  private def compilerNames: IO[Set[QualifiedName]] =
    runGeneratorWithFacts(compilerPool, UnifiedModuleNames.Key(abortModule, Platform.Compiler))
      .map(_._1.fold(Set.empty[QualifiedName])(_.names.keySet))

  private def implementedAbilities(names: Set[QualifiedName]): Set[String] =
    names.collect { case QualifiedName(_, Qualifier.AbilityImplementation(name, _, _), Role.Runtime) => name }

  "the compiler-platform Abort carrier" should "extract the concrete data names under the compiler marker" in {
    compilerNames.asserting(
      _ should contain allOf (
        QualifiedName("AbortCarrier", Qualifier.Type),
        QualifiedName("AbortCarrier", Qualifier.Default),
        QualifiedName("runAbort", Qualifier.Default)
      )
    )
  }

  it should "carry the compile-time Effect and Abort instances" in {
    compilerNames.map(implementedAbilities).asserting(_ should contain allOf ("Effect", "Abort"))
  }
}
