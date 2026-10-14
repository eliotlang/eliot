package com.vanillasource.eliot.eliotc.jvm.jargen

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.row.EntryPoint
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}

import java.net.URI
import java.nio.file.Path

/** Serves the source of the synthesized entry-point module (`main.els`): a `main` that runs the configured user `main`
  * with one `with` per effect of its clause, naming the platform's standard implementation of it
  * ([[com.vanillasource.eliot.eliotc.row.EntryPoint]], `docs/effects.md` D26):
  *
  * {{{
  * def main: Unit = app::main with eliot.effect.Console::standardConsole
  * }}}
  *
  * The content reads the user `main`'s declaration, so it is an ordinary derived fact, and it reads *which* main from
  * [[EntryPointTarget]], the edge-less leaf the `-m` choice enters the fact graph through: a cache shared across mains
  * (the main is a demand-scoped key) regenerates this source whenever the choice differs from the cached one. The main
  * must not reach it any other way — not through the URI, whose `PathScan` is validated through the filesystem mounts'
  * own reads and so is accepted from cache, nor through this processor's constructor, which the cache cannot see.
  *
  * This processor owns the [[SyntheticMainSourceProcessor.syntheticScheme]] namespace of
  * [[com.vanillasource.eliot.eliotc.source.content.SourceContent]]; its mount counterpart ([[SyntheticMainMount]])
  * makes the module reachable in the runtime scan pool under the same URI.
  */
class SyntheticMainSourceProcessor extends SingleKeyTypeProcessor[SourceContent.Key] {
  import SyntheticMainSourceProcessor.*

  override protected def generateFact(key: SourceContent.Key): CompilerIO[Unit] =
    if (key.uri != sourceUri) ().pure[CompilerIO]
    else
      for {
        target <- getFactOrAbort(EntryPointTarget.Key())
        source <- EntryPoint.source(target.main)
        _      <- registerFactIfClear(SourceContent(sourceUri, Sourced(sourceUri, PositionRange.zero, source)))
      } yield ()
}

object SyntheticMainSourceProcessor {

  /** The URI scheme namespace this processor owns. */
  val syntheticScheme: String = "eliot-synthetic"

  /** The module-relative path of the synthesized entry point, at the source root: module `main`. */
  val sourcePath: Path = Path.of("main.els")

  /** The one URI in the synthetic namespace. */
  val sourceUri: URI = URI.create(s"$syntheticScheme:main.els")

  /** The synthesized entry-point value: `main::main`, what the jar's `Main-Class` bootstraps. */
  val syntheticMainVfqn: ValueFQN = ValueFQN(ModuleName(Seq(), "main"), QualifiedName("main", Qualifier.Default))
}
