package com.vanillasource.eliot.eliotc.plugin

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.UpToDateProcessor
import com.vanillasource.eliot.eliotc.compiler.cache.codec.{CoreFactCodecs, FactKeyCodecs}
import com.vanillasource.eliot.eliotc.monomorphize.fact.ContributedBinding
import com.vanillasource.eliot.eliotc.plugin.LangPlugin.{
  allRoots,
  compilerRoots,
  eliotCompilerOverlay,
  mountFactory,
  pathKey
}
import com.vanillasource.eliot.eliotc.plugin.Configuration.namedKey
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration}
import com.vanillasource.eliot.eliotc.processor.CompilerProcessor
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.progress.ProgressDescriber
import com.vanillasource.eliot.eliotc.source.content.SourceContentReader
import com.vanillasource.eliot.eliotc.source.file.FileContentReader
import com.vanillasource.eliot.eliotc.source.scan.{FilesystemMount, PathScanner, PoolModulesProcessor, SourceMount}
import com.vanillasource.eliot.eliotc.source.stat.FileStatProcessor
import scopt.{OParser, OParserBuilder}

import java.nio.file.Path

class LangPlugin extends CompilerPlugin {

  /** Contribute this layer's fact key codecs, so a store opened from the effective configuration can read back every
    * key type these processors can produce (see `FactKeyCodecs`).
    */
  override def configure(): StateT[IO, Configuration, Unit] =
    StateT.modify(
      _.updatedWith(
        FactKeyCodecs.configKey,
        codecs => (codecs.getOrElse(Map.empty) ++ CoreFactCodecs.keyCodecs ++ LangFactCodecs.keyCodecs).some
      )
    )

  override def progressDescriber: Option[ProgressDescriber] = Some(LangProgressDescriber)

  private val cmdLineBuilder: OParserBuilder[Configuration] = OParser.builder[Configuration]

  import cmdLineBuilder.*

  override def commandLineParser(): OParser[?, Configuration] = OParser.sequence(
    arg[Path]("<path>...")
      .unbounded()
      .required()
      .action((path, config) => config.updatedWith(pathKey, _.getOrElse(Seq.empty).appended(path).some))
      .text("paths of either directories or files to compile (the user program)"),
    opt[Path]("path")
      .unbounded()
      .action((path, config) => config.updatedWith(pathKey, _.getOrElse(Seq.empty).appended(path).some))
      .text(
        "an additional source root (a package's `src` dir). Each root is scanned for the runtime pool; for the " +
          "compile-time pool its sibling `compiler/` overlay is added on top (override-preferred). Repeatable; " +
          "this is the option form of the positional `<path>`, usable after a subcommand."
      )
  )

  override def initialize(configuration: Configuration): StateT[IO, CompilerProcessor, Unit] = {
    // There is a single list of source roots (`pathKey` — the positional `<path>` program plus every `--path` layer).
    // Both pools are derived from it: every root is a runtime mount, and *for the compiler pool only* each root's
    // sibling `compiler/` overlay is added on top. `PathScanner` scans the runtime mounts for a runtime request;
    // for a compiler request it scans the runtime mounts *and* the overlay mounts, tagging the overlays so the merge
    // prefers an overlay definition over the platform's (the compiler-as-platform override). So the compiler track sees
    // the whole runtime track and borrows what no layer overrides, while a layer's own `compiler/` contribution
    // wins; user type-level code is checked in both tracks because the program root is in the same list. Roots become
    // mounts through the (substitutable) factory; plugins may contribute extra runtime mounts — both settled in
    // configure(), which completes before any initialize.
    //
    // A tool that drives the compiler programmatically (the LSP) may additionally set `compilerPathKey` with
    // compile-time overlay roots listed *explicitly* — for a layout whose overlay is not the `compiler/` sibling
    // of a runtime root, so the sibling derivation cannot find it. Those roots are added to the compiler pool on top of
    // the derived siblings (deduplicated). The CLI never sets this key, so `eliotc` stays fully `--path`-parameterised.
    val compilerRootPaths = (allRoots(configuration).map(eliotCompilerOverlay) ++ compilerRoots(configuration)).distinct
    val compilerMounts    = compilerRootPaths.map(mountFactory(configuration))
    val runtimeMounts     = allRoots(configuration).map(mountFactory(configuration)) ++
      configuration.getOrElse(PathScanner.extraRuntimeMountsKey, Seq.empty)
    StateT
      .modify(superProcessor =>
        SequentialCompilerProcessors(
          Seq(
            superProcessor,
            UpToDateProcessor(),
            FileStatProcessor(),
            FileContentReader(),
            SourceContentReader(),
            PathScanner(compilerMounts, runtimeMounts),
            // The whole-pool module enumeration behind the `namedValues` reflection. Mount-dependent, so — like
            // `PathScanner` — it lives here rather than in the mount-free `LangProcessors` list; it is only demanded
            // when a body actually reflects.
            PoolModulesProcessor(compilerMounts, runtimeMounts)
          ) ++ LangProcessors(
            extraNativeBindingLabels =
              // The native-binding merger built inside LangProcessors must consult every native contributor that other
              // layers registered in their configure() (e.g. stdlib's arithmetic natives). All configure() complete before
              // initialize, so the roster is already final here.
              configuration.getOrElse(ContributedBinding.extraNativeLabelsKey, Set.empty[String]).toSeq
          )
        )
      )
  }
}

object LangPlugin {

  /** The internal system property selecting the new effect path (`docs/effects.md` D36, [[LangProcessors]]'s
    * `handles`): `-Deliot.effects.handles=true`. Internal and temporary — it goes when the new path becomes the only
    * one.
    */
  val handlesProperty: String = "eliot.effects.handles"

  /** Whether the new effect path is asked for: [[handlesProperty]], or the environment variable `ELIOT_EFFECTS_HANDLES`,
    * which a forked test JVM inherits without the notice a JVM prints for `JAVA_TOOL_OPTIONS`.
    */
  def handlesRequested: Boolean =
    sys.props.get(handlesProperty).contains("true") || sys.env.get("ELIOT_EFFECTS_HANDLES").contains("true")

  /** Every source root: the positional `<path>` program and every `--path` layer. Both the runtime and compile-time
    * mount pools are derived from this one list (see `initialize` and [[eliotCompilerOverlay]]).
    */
  val pathKey: Configuration.Key[Seq[Path]] = namedKey[Seq[Path]]("paths")

  /** All configured source roots, in order. */
  def allRoots(configuration: Configuration): Seq[Path] = configuration.getOrElse(pathKey, Seq.empty)

  /** **Explicit** compile-time overlay roots, set programmatically by a driver (the LSP) — *not* a CLI option. Each is
    * scanned for the compiler pool only, override-superseding the borrowed runtime definition of the same name, exactly
    * like a derived `compiler/` sibling ([[eliotCompilerOverlay]]). This is the escape hatch for a project layout whose
    * overlay is not that sibling, where the derivation finds nothing. Empty for the CLI, which keeps deriving siblings
    * from its `--path` runtime roots only.
    */
  val compilerPathKey: Configuration.Key[Seq[Path]] = namedKey[Seq[Path]]("compilerPaths")

  /** All explicit compile-time overlay roots, in order (see [[compilerPathKey]]). */
  def compilerRoots(configuration: Configuration): Seq[Path] = configuration.getOrElse(compilerPathKey, Seq.empty)

  /** A root's **compile-time overlay**: the directory `compiler/` beside its `src/` source root — the build system's
    * standard layout, where a package is `src/` (runtime), `compiler/` (compile-time overlay) and `test/`. This is a
    * layer's opt-in compiler-platform contribution; it is scanned only for the compiler pool, where it
    * override-supersedes the borrowed runtime definition of the same name. Most roots (every runtime-only layer, the
    * user program) have no such sibling, in which case the derived mount simply resolves nothing.
    */
  def eliotCompilerOverlay(root: Path): Path = root.resolveSibling("compiler")

  private def mountFactory(configuration: Configuration): Path => SourceMount =
    configuration.getOrElse(PathScanner.mountFactoryKey, FilesystemMount(_))
}
