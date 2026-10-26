package com.vanillasource.eliot.eliotc.source.stat

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import java.time.{Duration, Instant}

/** The rule [[FileStatProcessor]] decides by: whether the mtime it just read is old enough to identify the file's
  * content on its own. It is the whole of the cache's soundness at the source boundary, so it is asserted directly
  * rather than through what it causes downstream.
  *
  * Both branches are reached by moving the margin rather than the clock, so neither depends on how long the test ran:
  * the file's mtime is pinned years in the past, which a millisecond margin settles and a century-long one does not.
  */
class FileStatProcessorTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private def statOf(file: File, settleMargin: Duration): IO[Option[FileStat]] =
    IncrementalFactGenerator
      .create(SequentialCompilerProcessors(Seq(FileStatProcessor(settleMargin))), None)
      .flatMap(_.demand.getFact(FileStat.Key(file)))

  "a file stat" should "compare on the mtime alone once it is settled" in {
    withTempFile(file =>
      statOf(file, Settled).asserting(_ shouldBe Some(FileStat(file, Some(Instant.ofEpochMilli(PinnedMtime)), None)))
    )
  }

  it should "refuse to identify content by an mtime younger than the settle margin" in {
    withTempFile(file => statOf(file, Unsettled).asserting(_.flatMap(_.unsettled) should not be None))
  }

  it should "still report the mtime while unsettled, so existence stays readable" in {
    withTempFile(file =>
      statOf(file, Unsettled).asserting(_.flatMap(_.lastModified) shouldBe Some(Instant.ofEpochMilli(PinnedMtime)))
    )
  }

  /** Two observations of one unsettled file differ, which is what forces the re-read. Neither a `Boolean` "is recent"
    * flag nor a millisecond-resolution timestamp would reliably differ here, and the defect this guards against would
    * survive either untouched.
    */
  it should "differ between two observations while unsettled" in {
    withTempFile(file =>
      for {
        first  <- statOf(file, Unsettled)
        second <- statOf(file, Unsettled)
      } yield first.flatMap(_.unsettled) shouldNot be(second.flatMap(_.unsettled))
    )
  }

  it should "report a missing file as absent, not as unsettled" in {
    val missing = new File("/does/not/exist.els")

    statOf(missing, Unsettled).asserting(_ shouldBe Some(FileStat(missing, None, None)))
  }

  private val PinnedMtime = 1_600_000_000_000L
  private val Settled     = Duration.ofMillis(1)
  private val Unsettled   = Duration.ofDays(365 * 100)

  private def withTempFile[A](use: File => IO[A]): IO[A] =
    IO.blocking {
      val path = Files.createTempFile("eliot-filestat", ".els")
      Files.writeString(path, "content")
      path.toFile
    }.flatMap(file => setMtime(file, PinnedMtime).as(file))
      .bracket(use)(file => IO.blocking(file.delete()).void)

  /** `setLastModified` is best-effort on some filesystems, so it is verified and retried (bounded). */
  private def setMtime(file: File, value: Long, attempts: Int = 100): IO[Unit] =
    IO.blocking { file.setLastModified(value); file.lastModified() }.flatMap { actual =>
      if (actual == value || attempts <= 0) IO.unit else setMtime(file, value, attempts - 1)
    }
}
