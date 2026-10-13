package com.vanillasource.eliot.eliotc.lsp.index

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.lsp.plugin.LspPlugin
import com.vanillasource.eliot.eliotc.lsp.virtual.VirtualFileSystem
import com.vanillasource.eliot.eliotc.module.fact.ModuleValue
import com.vanillasource.eliot.eliotc.lsp.LspCompileTestLayers
import com.vanillasource.eliot.eliotc.plugin.{Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** End-to-end proof that completion offers the names actually in scope in a file, built from a real compile through the
  * same session `EliotCompilationService` uses (LspPlugin + VFS, LangPlugin, StdlibPlugin; no JVM backend). The index is
  * constructed exactly as the service constructs it — from the [[ModuleValue]] (in-scope dictionaries) and
  * [[ResolvedValue]] (signatures) facts the compile materialises — so this exercises the real dictionaries, the real URI
  * keying, and that names the user never typed (ambient stdlib types) are offered while out-of-scope words are not.
  */
class CompletionIndexCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val source = """import eliot.effect.Console
                         |def main uses Console: Unit = printLine("Hello World!")""".stripMargin

  "completion" should "offer an in-scope stdlib name the file calls" in {
    completionNames.asserting(_ should contain("printLine"))
  }

  it should "offer the file's own top-level definition" in {
    completionNames.asserting(_ should contain("main"))
  }

  it should "offer ambient type names that never appear literally in the file" in {
    // `IO` was here until effects v6 deleted it: there is no carrier, so the platform ships no such type.
    completionNames.asserting(_ should contain allOf ("Int", "String", "Bool"))
  }

  it should "not offer a name that is out of scope" in {
    completionNames.asserting(_ should not contain "notInScopeAnywhere")
  }

  private def completionNames: IO[Seq[String]] =
    withCompiledWorkspace { (uri, index) => index.completionsAt(uri).map(_.name) }

  /** Compile a one-file workspace, build the completion index from the materialised facts, and hand the test the file's
    * URI alongside the index.
    */
  private def withCompiledWorkspace[A](body: (URI, CompletionIndex) => A): IO[A] =
    tempDirectory.use { sourceDir =>
      val file          = sourceDir.resolve("Test.els")
      val lspPlugin     = LspPlugin(new VirtualFileSystem)
      val configuration = LspCompileTestLayers.add(
        Configuration()
          .set(Compiler.targetPathKey, sourceDir.resolve(".eliot-lsp"))
          .set(LangPlugin.pathKey, Seq(sourceDir))
      )
      for {
        _       <- IO.blocking(Files.writeString(file, source))
        session <- CompilationSession.create(
                     lspPlugin,
                     Seq(lspPlugin, LangPlugin(), StdlibPlugin()),
                     configuration
                   )
        result  <- session.compileOnce()
        facts   <- result.generator.currentFacts()
      } yield {
        val resolved     = facts.values.collect { case value: ResolvedValue => value }.toSeq
        val moduleValues = facts.values.collect { case value: ModuleValue => value }.toSeq
        body(file.toUri, CompletionIndex.build(moduleValues, resolved))
      }
    }

  private def tempDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("eliot-lsp-completion")))(dir =>
      IO.blocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      }
    )
}
