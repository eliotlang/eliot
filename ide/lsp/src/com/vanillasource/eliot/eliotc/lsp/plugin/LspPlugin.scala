package com.vanillasource.eliot.eliotc.lsp.plugin

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.apidoc.fact.ValueDoc
import com.vanillasource.eliot.eliotc.ast.fact.SourceAST
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactKeyCodecs
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.lsp.mainroot.{LspMainRootMount, LspMainRootSourceProcessor}
import com.vanillasource.eliot.eliotc.lsp.virtual.{
  VfsRoutedMount,
  VfsSourceContentProcessor,
  VfsStat,
  VfsStatProcessor,
  VirtualFileSystem
}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerProcessor}
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue
import com.vanillasource.eliot.eliotc.source.scan.PathScanner
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import com.vanillasource.eliot.eliotc.used.UsedNames

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The compilation target for the language server: a whole-workspace diagnostics driver.
  *
  * A batch build is driven *from `main`* — [[com.vanillasource.eliot.eliotc.used.UsedNamesProcessor]] walks the
  * reachable monomorphized graph, so only code reachable from `main` is ever demanded. An editor has no `main` and must
  * surface diagnostics for **every** name in the workspace, including unreferenced and generic definitions. This driver
  * therefore enumerates the filesystem source roots itself and, for every `(ModuleName, QualifiedName)` it finds,
  * demands [[SaturatedValue]] — which transitively forces tokenize → parse → core → resolve → matchdesugar → operator
  * and surfaces the bulk of diagnostics **without** use-site instantiation. Deeper type errors inside generic bodies
  * stay use-site-verified per the language cornerstone (they surface where the value is instantiated), not at the
  * definition; widening the driver toward those is future work.
  *
  * The unsaved-editor-buffer overlay is expressed entirely with ordinary facts in the `vfs:` namespace: `configure`
  * substitutes the scan's mount construction ([[com.vanillasource.eliot.eliotc.lsp.virtual.VfsRoutedMount]], routing
  * overlaid files to `vfs:` URIs), and `initialize` contributes the namespace's two normal processors
  * ([[com.vanillasource.eliot.eliotc.lsp.virtual.VfsStatProcessor]] and
  * [[com.vanillasource.eliot.eliotc.lsp.virtual.VfsSourceContentProcessor]]) — each owning key spaces nothing else
  * produces, so there is no ordering or interception involved. The errors the `run` driver triggers accumulate in the
  * generator and come back via [[com.vanillasource.eliot.eliotc.compiler.CompilationResult.errors]].
  */
class LspPlugin(vfs: VirtualFileSystem) extends CompilerPlugin with Logging {

  override def pluginDependencies(configuration: Configuration): Seq[Class[? <: CompilerPlugin]] = Seq(
    classOf[LangPlugin],
    classOf[StdlibPlugin]
  )

  /** Route source roots through the buffer overlay: every filesystem mount the scan builds becomes a
    * [[VfsRoutedMount]]. All `configure()`s run before any `initialize`, so [[LangPlugin]] sees the substituted factory
    * when it builds the `PathScanner`. Also mounts the synthesized per-module monomorphization roots
    * ([[LspMainRootMount]]) the type-hint driver roots at.
    */
  override def configure(): StateT[IO, Configuration, Unit] =
    StateT.modify(configuration =>
      configuration
        .updatedWith(
          FactKeyCodecs.configKey,
          codecs => (codecs.getOrElse(Map.empty) ++ VfsStat.keyCodecs).some
        )
        .set(PathScanner.mountFactoryKey, root => new VfsRoutedMount(root))
        .updatedWith(
          PathScanner.extraRuntimeMountsKey,
          mounts => (mounts.getOrElse(Seq.empty) :+ new LspMainRootMount).some
        )
    )

  override def initialize(configuration: Configuration): StateT[IO, CompilerProcessor, Unit] =
    StateT.modify(superProcessor =>
      SequentialCompilerProcessors(
        Seq(superProcessor, VfsStatProcessor(vfs), VfsSourceContentProcessor(vfs), LspMainRootSourceProcessor())
      )
    )

  override def run(configuration: Configuration, compilation: CompilationProcess): IO[Boolean] =
    for {
      modules <- checkedModules(configuration)
      _       <- debug[IO](s"LSP checking ${modules.size} workspace module(s): ${modules.map(_.show).mkString(", ")}")
      _       <- modules.traverse_(checkModule(compilation, _))
      _       <- documentAllLayers(configuration, compilation)
    } yield true // a whole-workspace check has no artefact to produce; its outcome is its diagnostics

  /** Demand the documentation ([[ValueDoc]]) of every declared name across *all* layer roots — the base and platform
    * layers (on the path as dependencies) as well as the user's workspace — so the hover index can document any name
    * the editor resolves, stdlib functions included (not just the user's own modules, which is all `checkModule`
    * reaches). Docs come from the parsed AST only, so this forces at most a parse of each layer file (cached across
    * recompiles); it never resolves or type-checks the bundled dependencies.
    */
  private def documentAllLayers(configuration: Configuration, compilation: CompilationProcess): IO[Unit] =
    layerSourceFiles(configuration).flatMap(_.traverse_ { case (base, file) => demandDocs(compilation, base, file) })

  /** Every `.els` under every (distinct) layer root, paired with the base directory its module name is relative to.
    * Both the runtime roots and their compile-time `compiler/` overlays are documented, so hover reaches every
    * name.
    */
  private def layerSourceFiles(configuration: Configuration): IO[Seq[(Path, Path)]] = {
    val runtimeRoots = LangPlugin.allRoots(configuration).map(_.toAbsolutePath.normalize).distinct
    val roots        = (runtimeRoots ++ runtimeRoots.map(LangPlugin.eliotCompilerOverlay)).distinct
    roots.flatTraverse(baseAndFilesUnder)
  }

  private def baseAndFilesUnder(root: Path): IO[Seq[(Path, Path)]] = IO.blocking {
    if (Files.isRegularFile(root) && isEliotSource(root)) {
      Seq(root.getParent -> root)
    } else if (Files.isDirectory(root)) {
      Files.walk(root).iterator().asScala.filter(p => Files.isRegularFile(p) && isEliotSource(p)).map(root -> _).toSeq
    } else {
      Seq.empty
    }
  }

  /** Demand a [[ValueDoc]] for every name a single file declares, keyed by the name's module (derived from the file's
    * path) and the qualifier the front end assigns — plain functions and ability methods carry theirs on the parsed
    * name; a `data`/`type` type constructor lives in the type namespace.
    */
  private def demandDocs(compilation: CompilationProcess, base: Path, file: Path): IO[Unit] =
    compilation.getFact(SourceAST.Key(file.toFile.toURI)).flatMap {
      case None            => IO.unit
      case Some(sourceAst) =>
        val moduleName = moduleNameOf(base, file)
        val names      = sourceAst.ast.value.functionDefinitions.map(_.name.value) ++
          sourceAst.ast.value.typeDefinitions.map(dataDef => QualifiedName(dataDef.name.value, Qualifier.Type))
        names.distinct.traverse_(name => compilation.getFact(ValueDoc.Key(ValueFQN(moduleName, name))).void)
    }

  /** Demand a front-end fact for every name the module declares, so each one's diagnostics are produced; then, if the
    * module declares its own `main`, drive monomorphization from it.
    */
  private def checkModule(compilation: CompilationProcess, moduleName: ModuleName): IO[Unit] =
    compilation.getFact(UnifiedModuleNames.Key(moduleName)).flatMap {
      case None        => IO.unit // unification itself failed; its error is already reported
      case Some(names) =>
        names.names.keys.toList
          .traverse_(qn => compilation.getFact(SaturatedValue.Key(ValueFQN(moduleName, qn))).void) >>
          monomorphizeMain(compilation, moduleName, names)
    }

  /** Drive whole-program monomorphization from a file's own `main`, so its reachable code is type-checked at concrete
    * types and the per-node ground types behind hover type hints exist.
    *
    * The root is not the module's `main` itself — the idiomatic `main` declares effects and so takes their handles —
    * but the module's synthesized `lspmain.*` wrapper ([[LspMainRootSourceProcessor]]), which binds each with a `with`
    * of its standard implementation exactly as the jvm target's entry point does (`row/EntryPoint`). [[UsedNames]] walks the reachable
    * monomorphic graph from that root, forcing a [[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue]]
    * (carrying per-node ground types) for every reachable instantiation — exactly the facts the type-hint index reads.
    * The trigger is **per file**: each `examples/` source is its own module with its own `main`, so every such file
    * becomes an independent monomorphization root. Modules without a `main` (libraries) are left to use-site
    * verification, as a batch build would. A wrapper that fails to monomorphize (a broken `main`, or a workspace
    * without the jvm layer on its path) simply yields no [[UsedNames]] fact; whatever did monomorphize is still cached,
    * so hints degrade rather than disappear.
    */
  private def monomorphizeMain(
      compilation: CompilationProcess,
      moduleName: ModuleName,
      names: UnifiedModuleNames
  ): IO[Unit] = {
    val mainName = QualifiedName("main", Qualifier.Default)
    compilation
      .getFact(UsedNames.Key(LspMainRootSourceProcessor.wrapperVfqn(moduleName)))
      .flatMap(_.traverse_(demandRefinementTables(compilation, _)))
      .whenA(names.names.contains(mainName))
  }

  /** After the reachable monomorphic graph is forced, additionally demand each instance's
    * [[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable]] — the refinement channel's per-node value
    * ranges — so hover can show the computed range of an `Int`-typed node. The tables are keyed identically to the
    * [[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue]]s (`(vfqn, typeArguments)`), and
    * [[UsedNames]] already records exactly those instantiations. A table that fails to produce simply yields no fact,
    * so a hint degrades to no range rather than a wrong one.
    */
  private def demandRefinementTables(compilation: CompilationProcess, used: UsedNames): IO[Unit] =
    used.usedNames.toList.traverse_ { case (vfqn, stats) =>
      stats.monomorphicTypeParameters.distinct.traverse_(typeArgs =>
        compilation.getFact(RefinementTable.Key(vfqn, typeArgs)).void
      )
    }

  /** The modules this compile diagnoses. Where the build tool said which roots are the project's own
    * ([[LspPlugin.checkedRootsKey]]), every module under them — whatever its package, since a project may well be the
    * one declaring `eliot.lang`. Otherwise every module under every source root, less the reserved library packages.
    */
  private def checkedModules(configuration: Configuration): IO[Seq[ModuleName]] =
    configuration.get(LspPlugin.checkedRootsKey) match {
      case Some(roots) => roots.flatTraverse(modulesUnder(_, _ => false)).map(_.distinct)
      case None        => workspaceModules(configuration.getOrElse(LangPlugin.pathKey, Seq.empty))
    }

  /** Walk the filesystem source roots for `.els` files and derive each one's module name from its path relative to the
    * root it was found under. Classpath resources (the bundled stdlib) are intentionally excluded — the editor
    * diagnoses the user's workspace, not its dependencies. Files in the bundled Eliot library namespace
    * ([[isBundledLibraryModule]]) are dropped for the same reason: they are supplied by the bundled layer roots, and a
    * workspace folder that merely *contains* them (opening the compiler repo itself) must not re-enumerate them.
    */
  private def workspaceModules(roots: Seq[Path]): IO[Seq[ModuleName]] =
    roots.flatTraverse(modulesUnder(_, isLibraryModule)).map(_.distinct)

  private def modulesUnder(root: Path, skipped: ModuleName => Boolean): IO[Seq[ModuleName]] = IO.blocking {
    if (Files.isRegularFile(root) && isEliotSource(root)) {
      Seq(moduleNameOf(root.getParent, root)).filterNot(skipped)
    } else if (Files.isDirectory(root)) {
      Files
        .walk(root)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && isEliotSource(p))
        .map(p => moduleNameOf(root, p))
        .filterNot(skipped)
        .toSeq
    } else {
      Seq.empty
    }
  }

  /** Whether a path-derived module belongs to the Eliot library namespace — the standard library and platform layers
    * under `eliot.lang` / `eliot.effect` / `eliot.compiler` ([[LspPlugin.bundledLibraryPackages]]). These arrive on the
    * path as dependencies (never bundled with the server), so the whole-workspace driver must not diagnose them: they
    * are dependencies, not the user's own modules.
    *
    * The reserved package sequence is matched as a contiguous sub-path of the module's segments (rather than a prefix)
    * because a workspace folder is rarely exactly a source root. Opening the compiler repo yields the source root
    * inside the folder, so the same `String.els` derives to `stdlib.eliot.eliot.lang.String` here — mis-rooted, but
    * still recognisable as the `eliot.lang` library because the `eliot/lang` package directories survive in its path.
    * Matching a sub-path catches it regardless of how many source-root segments precede the package. Leaving these in
    * would let a mis-rooted stdlib file auto-import (and so shadow) its own name — the spurious "Imported names shadow
    * local names" diagnostic this guard exists to prevent.
    */
  private def isLibraryModule(moduleName: ModuleName): Boolean = {
    val segments = moduleName.packages :+ moduleName.name
    LspPlugin.libraryPackages.exists(segments.containsSlice)
  }

  /** A module name is its file's path relative to the source root, with `.els` stripped: `pkg/Sub/Foo.els` under root
    * `pkg` ⇒ `ModuleName(["Sub"], "Foo")`, matching the forward `packages/name.els` mapping used by `PathScanner`.
    */
  private def moduleNameOf(base: Path, file: Path): ModuleName = {
    val relative = base.relativize(file)
    val segments = (0 until relative.getNameCount).map(relative.getName(_).toString).toVector
    ModuleName(segments.dropRight(1), stripEliotExtension(segments.last))
  }

  private def isEliotSource(path: Path): Boolean = path.getFileName.toString.endsWith(LspPlugin.eliotExtension)

  private def stripEliotExtension(fileName: String): String = fileName.dropRight(LspPlugin.eliotExtension.length)
}

object LspPlugin {
  private val eliotExtension = ".els"

  /** The source roots whose modules the driver diagnoses, when a build tool said which of the path's roots are the
    * project's own. Absent, every root is walked and the reserved library packages are skipped instead.
    */
  val checkedRootsKey: Configuration.Key[Seq[Path]] = Configuration.opaqueKey[Seq[Path]]("lspCheckedRoots")

  /** The reserved Eliot library packages (`eliot.lang`, `eliot.effect`, `eliot.compiler`), each as its
    * directory sequence. The whole-workspace driver treats any file whose path contains one of these as a library
    * dependency rather than a user module (see [[LspPlugin.isLibraryModule]]). Derived from [[ModuleName]] so a new
    * reserved package is picked up here without a second list to keep in sync. `eliot.compiler.internal` needs no
    * separate entry — `eliot.compiler` already matches it as a sub-path.
    */
  private val libraryPackages: Seq[Seq[String]] =
    Seq(
      ModuleName.defaultSystemPackage,
      ModuleName.effectPackage,
      ModuleName.compilerPackage
    )
}
