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

/** Name extraction under the **compiler** marker: a module present only as a `compiler`-marker [[PathScan]] yields its
  * `data` and `def` names and its anonymous `implement`s' ability-implementation names. Extraction runs entirely before
  * resolution (`ModuleNames` reads a file's own declarations), so the file is injected with no base stubs: `import`s and
  * inner references need not resolve for the names to be collected.
  *
  * The snippet is the shipped `stdlib/eliot/compiler/eliot/lang/Either.els` plus an anonymous implementation; the
  * overlay itself is exercised end to end by the LSP/jvm suites, which point the compiler path at the real roots.
  */
class CompilerMarkerNamesTest extends ProcessorTest(LangProcessors(systemModules = Seq.empty)*) {
  private val eitherModule = ModuleName(Seq("eliot", "lang"), "Either")
  private val eitherPath   = Path.of("eliot", "lang", "Either.els")
  private val eitherUri    = URI.create("eliot/lang/Either.els")

  private val source =
    """import eliot.lang.Show
      |
      |data Either[E, A] = Left(error: E) | Right(value: A)
      |
      |def foldEither[E, A, B](ifLeft uses *: E => B, ifRight uses *: A => B, e: Either[E, A]) uses *: B = e match {
      |   case Left(err) -> ifLeft(err)
      |   case Right(v) -> ifRight(v)
      |}
      |
      |implement Show[Either[String, String]] {
      |   def show(e: Either[String, String]): String = foldEither(err -> err, v -> v, e)
      |}
      |""".stripMargin

  private val compilerPool = Seq(
    PathScan(eitherPath, Seq(eitherUri), Platform.Compiler),
    SourceContent(eitherUri, Sourced(eitherUri, PositionRange.zero, source))
  )

  private def compilerNames: IO[Set[QualifiedName]] =
    runGeneratorWithFacts(compilerPool, UnifiedModuleNames.Key(eitherModule, Platform.Compiler))
      .map(_._1.fold(Set.empty[QualifiedName])(_.names.keySet))

  // The ability names of every ability-implementation member declared in the module.
  private def implementedAbilities(names: Set[QualifiedName]): Set[String] =
    names.collect { case QualifiedName(_, Qualifier.AbilityImplementation(name, _, _), Role.Runtime) => name }

  "a compiler-marker module" should "extract its data and def names" in {
    compilerNames.asserting(
      _ should contain allOf (
        QualifiedName("Either", Qualifier.Type),
        QualifiedName("Left", Qualifier.Default),
        QualifiedName("Right", Qualifier.Default),
        QualifiedName("foldEither", Qualifier.Default)
      )
    )
  }

  it should "extract its anonymous implementations" in {
    compilerNames.map(implementedAbilities).asserting(_ should contain("Show"))
  }
}
