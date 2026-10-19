package com.vanillasource.eliot.eliotc.lsp.virtual

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.apidoc.plugin.ApiDocPlugin
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.lsp.LspCompileTestLayers
import com.vanillasource.eliot.eliotc.lsp.plugin.LspPlugin
import com.vanillasource.eliot.eliotc.plugin.{Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** End-to-end proof that the virtual file system overrides the on-disk source through the *incremental* compile path the
  * server actually uses: a session is built exactly as `EliotCompilationService.startWorkspace` builds it (LspPlugin +
  * its VFS, LangPlugin, StdlibPlugin; no JVM backend), then recompiled across edits with the second run's cache fed from
  * the first. This exercises the whole point of the overlay — that a buffer change makes the leaf `FileStat` differ so the
  * edited file's dependency cone is invalidated and the *buffer* is checked, not the stale disk file.
  */
class VirtualFileSystemCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  // A source that type-checks against the *abstract* platform-independent workspace the LSP compiles (no JVM layer): a
  // plain `String` value depends on nothing platform-specific. (A `printLine` would not: the entry point binds `Console`
  // to `standardConsole`, which only a platform layer bodies.)
  private val validSource  = """def greeting: String = "Hello World!""""
  private val brokenSource = """def greeting: String = notDefinedAnywhere("Hello World!")"""

  "the virtual file system" should "make a recompile see an unsaved broken buffer over a clean disk file" in {
    withSession { (vfs, uri, compile) =>
      for {
        clean  <- compile
        _       = vfs.update(uri, brokenSource)
        broken <- compile
      } yield {
        val _ = clean shouldBe empty
        broken should not be empty
      }
    }
  }

  it should "clear the diagnostics once the buffer is corrected, without the file being saved" in {
    withSession { (vfs, uri, compile) =>
      for {
        _      <- compile
        _       = vfs.update(uri, brokenSource)
        broken <- compile
        _       = vfs.update(uri, validSource)
        fixed  <- compile
      } yield {
        val _ = broken should not be empty
        fixed shouldBe empty
      }
    }
  }

  it should "revert to the on-disk file when the override is dropped on close" in {
    withSession { (vfs, uri, compile) =>
      for {
        _      <- compile
        _       = vfs.update(uri, brokenSource)
        broken <- compile
        _       = vfs.remove(uri)
        closed <- compile
      } yield {
        val _ = broken should not be empty
        closed shouldBe empty
      }
    }
  }

  /** Set up a workspace with a valid `Test.els` on disk and hand the test the overlay, the file's URI, and a `compile`
    * action that runs one incremental compilation and returns its error messages.
    */
  private def withSession[A](body: (VirtualFileSystem, java.net.URI, IO[Seq[String]]) => IO[A]): IO[A] =
    tempDirectory.use { sourceDir =>
      val file          = sourceDir.resolve("Test.els")
      val vfs           = new VirtualFileSystem
      val lspPlugin     = LspPlugin(vfs)
      val configuration = LspCompileTestLayers.add(
        Configuration()
          .set(Compiler.targetPathKey, sourceDir.resolve(".eliot-lsp"))
          .set(LangPlugin.pathKey, Seq(sourceDir))
      )
      for {
        _       <- IO.blocking(Files.writeString(file, validSource))
        session <- CompilationSession.create(
                     lspPlugin,
                     Seq(lspPlugin, LangPlugin(), StdlibPlugin(), ApiDocPlugin()),
                     configuration
                   )
        compile  = session.compileOnce().map(_.errors.map(_.message))
        result  <- body(vfs, file.toUri, compile)
      } yield result
    }

  private def tempDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("eliot-lsp-vfs")))(dir =>
      IO.blocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      }
    )
}
