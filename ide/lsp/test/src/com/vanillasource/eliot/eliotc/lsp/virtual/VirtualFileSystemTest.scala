package com.vanillasource.eliot.eliotc.lsp.virtual

import cats.data.Chain
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.SourceContent
import com.vanillasource.eliot.eliotc.source.stat.FileStat
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.net.URI
import java.nio.file.Path

class VirtualFileSystemTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val file = new File("/tmp/eliot-vfs/Test.els")
  private val uri  = file.toURI

  "VirtualFileSystem" should "serve the buffer content of an overridden file keyed by its URI" in {
    val vfs = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    vfs.get(file).map(_.content) shouldBe Some("buffer content")
  }

  it should "match a file:/// URI override against the file:/ form of the same path" in {
    val vfs = new VirtualFileSystem
    vfs.update(URI.create("file:///tmp/eliot-vfs/Test.els"), "x")
    vfs.get(file).map(_.content) shouldBe Some("x")
  }

  it should "drop the override on remove, reverting to the on-disk file" in {
    val vfs = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    vfs.remove(uri)
    vfs.get(file) shouldBe None
  }

  it should "advance the stamp on every update so an edit invalidates the cache" in {
    val vfs    = new VirtualFileSystem
    vfs.update(uri, "first")
    val first  = vfs.get(file).map(_.stamp)
    vfs.update(uri, "second")
    val second = vfs.get(file).map(_.stamp)
    second.exists(s => first.exists(s > _)) shouldBe true
  }

  it should "ignore non-file URIs (e.g. untitled buffers) rather than fail" in {
    val vfs = new VirtualFileSystem
    noException should be thrownBy vfs.update(URI.create("untitled:Untitled-1"), "x")
  }

  "VfsStatProcessor" should "report the buffer stamp for an overridden file" in {
    val vfs   = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    val stamp = vfs.get(file).map(_.stamp)
    runCompilerIO(VfsStatProcessor(vfs).generate(VfsStat.Key(file)) >> getFactOrAbort(VfsStat.Key(file)))
      .asserting(_ shouldBe Right(VfsStat(file, stamp)))
  }

  it should "report no stamp for a file without an override (total fact)" in {
    runCompilerIO(VfsStatProcessor(new VirtualFileSystem).generate(VfsStat.Key(file)) >> getFactOrAbort(VfsStat.Key(file)))
      .asserting(_ shouldBe Right(VfsStat(file, None)))
  }

  "VfsRoutedMount" should "route an overridden file to its vfs: URI" in {
    val vfs = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    runCompilerIO(VfsStatProcessor(vfs).generate(VfsStat.Key(file)) >> VfsRoutedMount(file.getParentFile.toPath).resolve(Path.of(file.getName)))
      .asserting(_ shouldBe Right(Some(VfsUris.uriOf(file))))
  }

  it should "route an overridden file even when it does not exist on disk" in {
    val vfs = new VirtualFileSystem
    vfs.update(uri, "never saved")
    runCompilerIO(VfsStatProcessor(vfs).generate(VfsStat.Key(file)) >> VfsRoutedMount(file.getParentFile.toPath).resolve(Path.of(file.getName)))
      .asserting(_ shouldBe Right(Some(VfsUris.uriOf(file))))
  }

  it should "fall through to the filesystem for a file without an override" in {
    val vfs = new VirtualFileSystem
    runCompilerIO {
      VfsStatProcessor(vfs).generate(VfsStat.Key(file)) >>
        registerFactIfClear(FileStat(file, None, None)) >> // not on disk either
        VfsRoutedMount(file.getParentFile.toPath).resolve(Path.of(file.getName))
    }.asserting(_ shouldBe Right(None))
  }

  "VfsSourceContentProcessor" should "serve buffer content under the vfs: identity" in {
    val vfs    = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    val vfsUri = VfsUris.uriOf(file)
    runCompilerIO {
      VfsStatProcessor(vfs).generate(VfsStat.Key(file)) >>
        VfsSourceContentProcessor(vfs).generate(SourceContent.Key(vfsUri)) >>
        getFactOrAbort(SourceContent.Key(vfsUri))
    }.asserting(_.map(_.content.value) shouldBe Right("buffer content"))
  }

  it should "leave non-vfs URIs to the on-disk reader" in {
    val vfs = new VirtualFileSystem
    vfs.update(uri, "buffer content")
    runCompilerIO(VfsSourceContentProcessor(vfs).generate(SourceContent.Key(uri)) >> getFactOrAbort(SourceContent.Key(uri)))
      .asserting(_ shouldBe Left(Chain.empty))
  }

  "VfsUris" should "round-trip a file through the vfs scheme" in {
    VfsUris.fileOf(VfsUris.uriOf(file)) shouldBe file.getAbsoluteFile
  }

  it should "translate a vfs URI back to the editor-facing file URI" in {
    VfsUris.toFileUri(VfsUris.uriOf(file)) shouldBe file.toURI
  }

  /** Run a CompilerIO against an in-memory process that stores registered facts, returning errors or the result. */
  private def runCompilerIO[T](value: CompilerIO[T]): IO[Either[Chain[CompilerError], T]] =
    com.vanillasource.eliot.eliotc.processor.CompilerIO.runCompilerIO(new VirtualFileSystemTest.InMemoryProcess())(value).map(_._2).pure[IO]
}

object VirtualFileSystemTest {
  private class InMemoryProcess extends CompilationProcess {
    private var facts: Map[CompilerFactKey[?], CompilerFact] = Map.empty

    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
        key: K,
        ancestors: List[CompilerFactKey[?]]
    ): Option[V] =
      facts.get(key).map(_.asInstanceOf[V])

    override def registerFact(value: CompilerFact): Unit = facts = facts.updated(value.key(), value)
  }
}
