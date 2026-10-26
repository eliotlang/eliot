package com.vanillasource.eliot.eliotc.apidoc.plugin

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.apidoc.build.{DocModelBuilder, DocText}
import com.vanillasource.eliot.eliotc.apidoc.fact.ValueDoc
import com.vanillasource.eliot.eliotc.apidoc.processor.ValueDocProcessor
import com.vanillasource.eliot.eliotc.apidoc.render.HtmlSite
import com.vanillasource.eliot.eliotc.ast.fact.{AST, SourceAST}
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactKeyCodecs
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.Configuration.namedKey
import com.vanillasource.eliot.eliotc.plugin.{CompilerPlugin, Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilerProcessor, FactDemand}
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import scopt.{OParser, OParserBuilder}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The compilation target for documentation generation: a whole-workspace apidoc-site driver.
  *
  * Like the language server it has no `main` to drive from, so it enumerates the filesystem source roots itself and, for
  * every `.els` file, demands [[SourceAST]] (which forces tokenize → parse, attaching the captured `/** ... */` doc
  * comments to each declaration) and stops there — it never resolves, type-checks, or generates code. Because it scans
  * the abstract base, the platform layers, and the user program together, the generated site documents them as one,
  * merging the abstract signature of a name with the layers that implement it.
  *
  * It adds no processors of its own; the front end comes from its plugin dependencies. `JvmPlugin` is deliberately not a
  * dependency (and is kept off the classpath by the build module), so no codegen processor is ever activated.
  */
class ApiDocPlugin extends CompilerPlugin with Logging {

  /** Contribute this layer's fact key codecs, so a store opened from the effective configuration can read back every
    * key type these processors can produce (see `FactKeyCodecs`).
    */
  override def configure(): StateT[IO, Configuration, Unit] =
    StateT.modify(
      _.updatedWith(FactKeyCodecs.configKey, codecs => (codecs.getOrElse(Map.empty) ++ ValueDoc.keyCodecs).some)
    )
  private val cmdLineBuilder: OParserBuilder[Configuration] = OParser.builder[Configuration]
  import cmdLineBuilder.*

  override def commandLineParser(): OParser[?, Configuration] = OParser.sequence(
    cmd("apidoc")
      .text("generate an HTML documentation site for every .els under the source roots")
      .action((_, config) => config.set(ApiDocPlugin.selectedKey, true))
  )

  override def isSelectedBy(configuration: Configuration): Boolean = configuration.contains(ApiDocPlugin.selectedKey)

  override def pluginDependencies(configuration: Configuration): Seq[Class[? <: CompilerPlugin]] = Seq(
    classOf[LangPlugin],
    classOf[StdlibPlugin]
  )

  /** Registers only the [[ValueDocProcessor]], which turns the parsed ASTs into per-name [[ValueDoc]] facts. The rest of
    * the front end (source readers, tokenizer, parser) comes from [[LangPlugin]]. The processor is given the same
    * layer-labelled roots [[run]] documents, captured now from the configuration, so it can locate a name across layers.
    */
  override def initialize(configuration: Configuration): StateT[IO, CompilerProcessor, Unit] =
    StateT.modify(superProcessor =>
      SequentialCompilerProcessors(Seq(superProcessor, ValueDocProcessor(rootsWithLayer(configuration))))
    )

  override def run(configuration: Configuration, compilation: FactDemand): IO[Boolean] =
    for {
      outDir     <- IO.pure(configuration.getOrElse(Compiler.targetPathKey, Path.of("target")).resolve("apidoc"))
      // Discard the previous site before anything is read: this run regenerates it unconditionally, so from here on
      // what is on disk is not what the sources say. A failure below then leaves no site rather than the old one, and
      // a module that no longer exists cannot leave its page behind.
      _          <- discardPreviousSite(outDir)
      layerFiles <- collectLayerFiles(configuration, compilation)
      _          <- debug[IO](s"Apidoc collected ${layerFiles.size} source file(s) for documentation.")
      docFor     <- docLookup(compilation, layerFiles)
      built       = DocModelBuilder.build(layerFiles, docFor)
      _          <- built.warnings.traverse_(warn[IO](_))
      _          <- writeSite(outDir, HtmlSite.render(built.modules))
      _          <- info[IO](s"Generated documentation for ${built.modules.size} module(s): ${outDir.resolve("index.html")}.")
    } yield true

  /** Demand the [[ValueDoc]] fact for every documentable name across the collected files and return a lookup over them,
    * so the built model's docs come from the shared pipeline fact (the same fact the language server reads) rather than
    * from a private pass. Names with no fact — none should occur, since the processor produces one per key — read as
    * undocumented.
    */
  private def docLookup(
      compilation: FactDemand,
      layerFiles: Seq[(ModuleName, String, AST)]
  ): IO[ValueFQN => DocText.Selected] = {
    val fqns = layerFiles.flatMap { case (moduleName, _, ast) =>
      ast.functionDefinitions.map(fn => ValueFQN(moduleName, fn.name.value)) ++
        ast.typeDefinitions.map(dd => ValueFQN(moduleName, QualifiedName(dd.name.value, Qualifier.Type)))
    }.distinct
    fqns
      .traverse(fqn => compilation.getFact(ValueDoc.Key(fqn)).map(_.map(fqn -> _)))
      .map(_.flatten.toMap)
      .map(byFqn => fqn => byFqn.get(fqn).fold(DocText.Selected(None, Seq.empty))(vd => DocText.Selected(vd.doc, vd.warnings)))
  }

  /** Read every `.els` under every (distinct) source root, returning each parsed file tagged with its module name and
    * the layer (the root directory it was found under) so downstream merging can attribute each declaration to a layer.
    */
  private def collectLayerFiles(
      configuration: Configuration,
      compilation: FactDemand
  ): IO[Seq[(ModuleName, String, AST)]] =
    rootsWithLayer(configuration).flatTraverse { case (root, layer) =>
      eliotFilesUnder(root).flatMap(_.flatTraverse { file =>
        compilation.getFact(SourceAST.Key(file.toFile.toURI)).map {
          case Some(sourceAst) => Seq((moduleNameOf(root, file), layer, sourceAst.ast.value))
          case None            => Seq.empty
        }
      })
    }

  /** The distinct source roots to document, each paired with a human-readable layer label. Every configured root plus
    * its compile-time `compiler/` overlay is documented; deduplicating by absolute path keeps the base as one
    * root. The user's program paths fold in as additional roots.
    */
  private def rootsWithLayer(configuration: Configuration): Seq[(Path, String)] = {
    val runtimeRoots = LangPlugin.allRoots(configuration).map(_.toAbsolutePath.normalize).distinct
    val roots        = (runtimeRoots ++ runtimeRoots.map(LangPlugin.eliotCompilerOverlay)).distinct
    roots.map(root => root -> layerLabel(root))
  }

  private def layerLabel(root: Path): String = {
    val fileName = root.getFileName.toString
    if (fileName == "eliot") Option(root.getParent).map(_.getFileName.toString).getOrElse(fileName)
    else if (fileName == "compiler") "compiler"
    else fileName
  }

  private def eliotFilesUnder(root: Path): IO[Seq[Path]] = IO.blocking {
    if (Files.isDirectory(root)) {
      val walk = Files.walk(root)
      try walk.iterator().asScala.filter(p => Files.isRegularFile(p) && isEliotSource(p)).toVector
      finally walk.close()
    } else if (Files.isRegularFile(root) && isEliotSource(root)) {
      Seq(root)
    } else {
      Seq.empty
    }
  }

  private def moduleNameOf(root: Path, file: Path): ModuleName = {
    val relative = root.relativize(file)
    val segments = (0 until relative.getNameCount).map(relative.getName(_).toString).toVector
    ModuleName(segments.dropRight(1), segments.last.dropRight(ApiDocPlugin.eliotExtension.length))
  }

  private def isEliotSource(path: Path): Boolean = path.getFileName.toString.endsWith(ApiDocPlugin.eliotExtension)

  private def writeSite(outDir: Path, files: Seq[(String, String)]): IO[Unit] = IO.blocking {
    Files.createDirectories(outDir)
    files.foreach { case (relativePath, content) => Files.writeString(outDir.resolve(relativePath), content) }
  }

  /** Remove the pages of a previous run. The site is a flat directory of generated files ([[HtmlSite.render]] returns
    * plain file names), so only the regular files directly inside it are deleted — nothing is walked recursively.
    */
  private def discardPreviousSite(outDir: Path): IO[Unit] = IO.blocking {
    if (Files.isDirectory(outDir)) {
      val entries = Files.list(outDir)
      try entries.iterator().asScala.filter(Files.isRegularFile(_)).toVector.foreach(Files.delete)
      finally entries.close()
    }
  }
}

object ApiDocPlugin {
  private val selectedKey: Configuration.Key[Boolean] = namedKey[Boolean]("apidocSelected")
  private val eliotExtension                          = ".els"
}
