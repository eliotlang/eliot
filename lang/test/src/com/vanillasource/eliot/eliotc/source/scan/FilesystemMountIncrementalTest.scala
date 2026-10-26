package com.vanillasource.eliot.eliotc.source.scan

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.compiler.cache.FactCacheData
import com.vanillasource.eliot.eliotc.module.fact.ModuleName
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.source.stat.FileStatProcessor
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.{Files, Path}
import java.time.Duration

/** A directory's mtime is the enumeration's invalidation signal ([[FilesystemMount.enumerate]] takes a `FileStat` on
  * every directory it walks), so it is exposed to the same coarse-tick defect as a file's: two files created inside one
  * tick leave the directory's mtime where the first one put it, and the second file stays invisible to the build.
  *
  * The settle rule covers the listing for the same reason it covers content — an unsettled directory forces the re-walk,
  * and [[PoolModules]] equality then stops propagation when the listing did not actually change.
  */
class FilesystemMountIncrementalTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private def modulesOf(root: Path, prior: Option[FactCacheData]): IO[(Set[ModuleName], FactCacheData)] =
    for {
      generator <- IncrementalFactGenerator.create(processors(root), prior)
      modules   <- generator.demand.getFact(PoolModules.Key(Platform.Runtime))
      cache     <- generator.buildCacheData()
    } yield (modules.map(_.modules).getOrElse(Set.empty), cache)

  "a source mount enumeration" should "see a file added without the directory's mtime moving" in {
    withTempRoot { root =>
      for {
        run1 <- modulesOf(root, None)
        _    <- IO.blocking(Files.writeString(root.resolve("Second.els"), "type Second"))
        _    <- setMtime(root.toFile, PinnedMtime) // as if the add had landed in the same tick as the last one
        run2 <- modulesOf(root, Some(run1._2))
      } yield run2._1
    }.asserting(_ shouldBe Set(ModuleName(Seq.empty, "First"), ModuleName(Seq.empty, "Second")))
  }

  /** The re-walk an unsettled directory forces is not itself an invalidation: an unchanged listing recomputes to the
    * same fact, so the equality cutoff leaves everything downstream of it alone.
    */
  it should "leave the enumeration unchanged when the re-walk finds the same listing" in {
    withTempRoot { root =>
      for {
        run1 <- modulesOf(root, None)
        run2 <- modulesOf(root, Some(run1._2))
      } yield run2._2.entries(PoolModules.Key()) shouldBe run1._2.entries(PoolModules.Key())
    }
  }

  /** Long enough that the pinned directory mtime is always inside it, so the unsettled branch is decided by the rule
    * rather than by how long the test took.
    */
  private def processors(root: Path) =
    SequentialCompilerProcessors(
      Seq(
        FileStatProcessor(Duration.ofDays(365 * 100)),
        PoolModulesProcessor(Seq.empty, Seq(FilesystemMount(root)))
      )
    )

  /** A whole-millisecond mtime, because `setLastModified` writes only milliseconds while the stat reads the
    * filesystem's full nanosecond stamp: pinning it here is what makes the restore above reproduce it exactly, rather
    * than zeroing the nanoseconds and reading as an ordinary change.
    */
  private val PinnedMtime = 1_600_000_000_000L

  private def withTempRoot[A](use: Path => IO[A]): IO[A] =
    IO.blocking {
      val root = Files.createTempDirectory("eliot-mount-incremental")
      Files.writeString(root.resolve("First.els"), "type First")
      root
    }.flatTap(root => setMtime(root.toFile, PinnedMtime))
      .bracket(use)(root => IO.blocking(deleteRecursively(root.toFile)).void)

  private def deleteRecursively(file: File): Unit = {
    Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
    file.delete(): Unit
  }

  /** `setLastModified` is best-effort on some filesystems, so it is verified and retried (bounded). */
  private def setMtime(file: File, value: Long, attempts: Int = 100): IO[Unit] =
    IO.blocking { file.setLastModified(value); file.lastModified() }.flatMap { actual =>
      if (actual == value || attempts <= 0) IO.unit else setMtime(file, value, attempts - 1)
    }
}
