package com.vanillasource.eliot.eliotc.source.file

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.compiler.cache.FactCacheData
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilerFactKey, CompilerProcessor}
import com.vanillasource.eliot.eliotc.source.stat.{FileStat, FileStatProcessor}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import java.time.Duration

/** End-to-end check that, under incremental compilation, reading a source file is gated by its [[FileStat]]: the
  * (expensive) read is skipped while the file's mtime is unchanged and redone when it changes. Reads are counted by
  * wrapping [[FileContentReader]], so "skipped" is asserted directly rather than inferred from content.
  */
class FileContentReaderIncrementalTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private def run(
      processors: CompilerProcessor,
      prior: Option[FactCacheData],
      file: File
  ): IO[(Option[FileContent], FactCacheData)] =
    for {
      generator <- IncrementalFactGenerator.create(processors, prior)
      content   <- generator.demand.getFact(FileContent.Key(file))
      cache     <- generator.buildCacheData()
    } yield (content, cache)

  "FileContentReader under incremental compilation" should "record a dependency on the file's FileStat" in {
    withTempFile("v1") { file =>
      Ref.of[IO, Int](0).flatMap { reads =>
        run(processors(reads), None, file)
          .asserting(_._2.entries(FileContent.Key(file)).directDeps should contain(FileStat.Key(file)))
      }
    }
  }

  it should "skip the read on an unchanged rerun" in {
    withTempFile("v1") { file =>
      Ref.of[IO, Int](0).flatMap { reads =>
        (for {
          run1 <- run(processors(reads), None, file)
          run2 <- run(processors(reads), Some(run1._2), file)
          n    <- reads.get
        } yield (run2._1, n)).asserting(_ shouldBe (Some(FileContent(file, "v1")), 1))
      }
    }
  }

  it should "re-read the file when its mtime changes" in {
    withTempFile("v1") { file =>
      Ref.of[IO, Int](0).flatMap { reads =>
        (for {
          run1 <- run(processors(reads), None, file)
          _    <- IO.blocking(Files.writeString(file.toPath, "v2")) >> setMtime(file, NewMtime)
          run2 <- run(processors(reads), Some(run1._2), file)
          n    <- reads.get
        } yield (run2._1, n)).asserting(_ shouldBe (Some(FileContent(file, "v2")), 2))
      }
    }
  }

  /** The defect this whole guard exists for: a filesystem stamps mtimes at a coarse tick, so a file rewritten inside one
    * tick keeps the mtime it had. Comparing on the mtime alone then reports "unchanged" and the build silently keeps
    * compiling the previous content. The rewrite here holds the mtime fixed, which is what a same-tick rewrite does.
    *
    * The margin is widened rather than the test racing a real two seconds, so "this mtime is too recent to be trusted"
    * is decided by the processor's rule and not by how long the test took to run.
    */
  it should "re-read a file rewritten without its mtime moving, when that mtime is too recent to be trusted" in {
    withTempFile("v1") { file =>
      Ref.of[IO, Int](0).flatMap { reads =>
        (for {
          run1 <- run(processors(reads, AlwaysUnsettled), None, file)
          _    <- IO.blocking(Files.writeString(file.toPath, "v2")) >> setMtime(file, PinnedMtime)
          run2 <- run(processors(reads, AlwaysUnsettled), Some(run1._2), file)
        } yield run2._1).asserting(_ shouldBe Some(FileContent(file, "v2")))
      }
    }
  }

  /** A margin long enough that any real mtime is inside it, making the unsettled branch deterministic. */
  private val AlwaysUnsettled = Duration.ofDays(365 * 100)

  /** The real reader plus stat processor, with reads of `FileContent` counted into `reads`. */
  private def processors(reads: Ref[IO, Int], settleMargin: Duration = Duration.ZERO): CompilerProcessor = {
    val countingReader = new CompilerProcessor {
      private val reader = new FileContentReader()
      override def generate(factKey: CompilerFactKey[?]): CompilerIO[Unit] = factKey match {
        case _: FileContent.Key => reads.update(_ + 1).to[CompilerIO] >> reader.generate(factKey)
        case _                  => reader.generate(factKey)
      }
    }
    SequentialCompilerProcessors(Seq(FileStatProcessor(settleMargin), countingReader))
  }

  // Fixed mtimes far apart, so a "changed" file is unambiguously distinguishable from an "unchanged" one regardless of
  // filesystem timestamp granularity. They are set with verify-and-retry because `setLastModified` is best-effort.
  private val PinnedMtime = 1_600_000_000_000L
  private val NewMtime    = 1_700_000_000_000L

  /** Set a file's mtime, retrying until it actually takes (some filesystems apply `setLastModified` lazily/best-effort).
    * Both target values are exact seconds, so a granularity-respecting filesystem reads them back exactly; the bounded
    * retry only guards against transient best-effort failures and never loops forever.
    */
  private def setMtime(file: File, value: Long, attempts: Int = 100): IO[Unit] =
    IO.blocking { file.setLastModified(value); file.lastModified() }.flatMap { actual =>
      if (actual == value || attempts <= 0) IO.unit else setMtime(file, value, attempts - 1)
    }

  private def withTempFile[A](content: String)(use: File => IO[A]): IO[A] =
    IO.blocking {
      val path = Files.createTempFile("eliot-filecontent", ".els")
      Files.writeString(path, content)
      path.toFile
    }.flatMap(file => setMtime(file, PinnedMtime).as(file))
      .bracket(use)(file => IO.blocking(file.delete()).void)
}
