package com.vanillasource.eliot.eliotc.jvm.codec

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.compiler.cache.codec.{CoreFactCodecs, FactKeyCodecs}
import com.vanillasource.eliot.eliotc.compiler.cache.{ContentAddressedCacheBackend, FactCacheData}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.{Files, Path, StandardOpenOption}

/** The store as a cache backend, against the facts of a real build (`docs/incremental-compilation.md` §17).
  *
  * Two properties, and the second is the one the append-only layout stands or falls on. A store that round-trips but
  * re-encodes what it already holds would grow by the whole fact graph on every warm build — so "saving what was
  * loaded appends nothing" is not a performance assertion here, it is the correctness of the design.
  */
class ContentAddressedCacheBackendTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val program =
    """def greet(name: String): String = "hello " ++ name
      |
      |def main uses Console: Unit = printLine(greet("world"))""".stripMargin

  "the content-addressed cache" should "read back every entry it wrote" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        loaded <- open(cacheDir).flatMap(_.load())
      } yield loaded.map(readable) shouldBe Some(persistable(data))
    }
  }

  /** A loaded value is compared to a recomputed one by content hash, never materialised for the sake of it — so the
    * cutoff must answer *true* for exactly the fact that was stored, and false for anything else.
    */
  it should "recognise a recomputed fact by its content, without reading the stored one" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        loaded <- open(cacheDir).flatMap(_.load())
      } yield loaded.map(matchesEverything(data)) shouldBe Some(true)
    }
  }

  it should "append nothing when it saves back what it loaded" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        first  <- storeSize(cacheDir)
        _      <- open(cacheDir).flatMap(backend => backend.load().flatMap(_.traverse_(backend.save)))
        after  <- storeSize(cacheDir)
      } yield after shouldBe first
    }
  }

  it should "answer nothing when the compiler fingerprint moved on" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        loaded <- openWith(cacheDir, "a-different-compiler").flatMap(_.load())
      } yield loaded shouldBe None
    }
  }

  /** Two compilers sharing a target directory, the race that produces a *wrong* build rather than a slow one (§21).
    *
    * Both load the same region, then both append — different content, since they hold different facts. The second one
    * to write may only continue from where the region actually ends now, not from the length it loaded; taking the
    * loaded length would publish offsets that address the other compiler's bytes.
    */
  it should "continue from where the region actually ends when another compiler appended in between" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(firstEntries(data, 10)))
        first  <- open(cacheDir)
        second <- open(cacheDir)
        _      <- first.load()
        _      <- second.load()
        _      <- second.save(firstEntries(data, 20))
        _      <- first.save(data)
        loaded <- open(cacheDir).flatMap(_.load())
      } yield loaded.map(sameAs(data)) shouldBe Some(true)
    }
  }

  /** The other half of the same problem: offsets held in memory mean nothing once the region they came from has been
    * replaced rather than extended, so the save that holds them stands down instead of appending into it.
    */
  it should "stand down rather than write into a region that was replaced underneath it" in {
    withCache { (data, cacheDir) =>
      val replacement = firstEntries(data, 10)

      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        stale  <- open(cacheDir)
        _      <- stale.load()
        _      <- IO.blocking(clear(cacheDir))
        _      <- open(cacheDir).flatMap(_.save(replacement))
        _      <- stale.save(data)
        loaded <- open(cacheDir).flatMap(_.load())
      } yield loaded.map(sameAs(replacement)) shouldBe Some(true)
    }
  }

  /** A save appends the bodies before it publishes the index naming them, so a compiler killed in between leaves a
    * region longer than its index claims. The prefix the index does describe never moved, and is still readable.
    */
  it should "load a region left longer than its index by an interrupted save" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(data))
        _      <- IO.blocking(Files.write(objectsFileIn(cacheDir), Array[Byte](1, 2, 3), StandardOpenOption.APPEND))
        loaded <- open(cacheDir).flatMap(_.load())
      } yield loaded.map(sameAs(data)) shouldBe Some(true)
    }
  }

  /** The cache is locked, the build is not: a compiler that cannot have the lock does *more work*, and never waits on
    * another build to finish.
    */
  it should "forfeit a save rather than wait for a compiler holding the cache" in {
    withCache { (data, cacheDir) =>
      for {
        _      <- open(cacheDir).flatMap(_.save(firstEntries(data, 10)))
        before <- storeSize(cacheDir)
        _      <- heldLock(cacheDir).surround(open(cacheDir).flatMap(_.save(data)))
        after  <- storeSize(cacheDir)
      } yield after shouldBe before
    }
  }

  /** What the store is expected to hold back: a fact whose key declines to persist keeps its edges and loses its
    * value (§15), exactly as the Java-serialized cache does for the same two types.
    */
  private def persistable(data: FactCacheData): Map[CompilerFactKey[?], (Option[CompilerFact], Set[CompilerFactKey[?]])] =
    data.entries.map { case (key, entry) =>
      key -> (entry.value.filter(_ => key.valueCodec.isDefined), entry.directDeps)
    }

  /** A loaded entry holds its value on disk rather than in a field, so it is compared through what it answers. */
  private def readable(data: FactCacheData): Map[CompilerFactKey[?], (Option[CompilerFact], Set[CompilerFactKey[?]])] =
    data.entries.map { case (key, entry) => key -> (entry.value, entry.directDeps) }

  /** Whether a loaded cache is exactly the one that was saved — every key, every edge, every value.
    *
    * Answered as a single `Boolean` rather than by comparing the two maps, because a `shouldBe` between two whole
    * fact graphs prettifies its difference on failure, and doing that to thousands of deeply nested facts takes
    * longer than any timeout: a broken store would show up as a hung suite instead of a failed assertion.
    */
  private def sameAs(original: FactCacheData)(loaded: FactCacheData): Boolean =
    loaded.entries.keySet == original.entries.keySet && matchesEverything(original)(loaded) &&
      loaded.entries.forall { case (key, entry) => original.entries.get(key).map(_.directDeps).contains(entry.directDeps) }

  private def matchesEverything(original: FactCacheData)(loaded: FactCacheData): Boolean =
    loaded.entries.forall { case (key, entry) =>
      original.entries.get(key).flatMap(_.value).filter(_ => key.valueCodec.isDefined) match {
        case Some(fact) => entry.matches(fact)
        case None       => !entry.hasValue
      }
    }

  private def storeSize(cacheDir: Path): IO[Long] =
    IO.blocking(Files.list(cacheDir).toList.stream().mapToLong(Files.size).sum())

  /** A prefix of a run's facts, standing in for a compiler that holds different facts from another one. */
  private def firstEntries(data: FactCacheData, count: Int): FactCacheData =
    FactCacheData(data.entries.take(count))

  private def clear(cacheDir: Path): Unit =
    Files.list(cacheDir).toList.forEach(Files.delete)

  private def objectsFileIn(cacheDir: Path): Path = fileIn(cacheDir, ".eliot-objects")

  /** Found by prefix rather than spelled out, so the test does not restate the backend's naming rule. */
  private def fileIn(cacheDir: Path, prefix: String): Path =
    Files.list(cacheDir).toList.stream().filter(_.getFileName.toString.startsWith(prefix)).findFirst().orElseThrow()

  /** The cache lock, held the way another compiler in another process would hold it. */
  private def heldLock(cacheDir: Path): Resource[IO, FileLock] =
    Resource
      .fromAutoCloseable(
        IO.blocking(
          FileChannel.open(
            fileIn(cacheDir, ".eliot-lock"),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
          )
        )
      )
      .flatMap(channel => Resource.make(IO.blocking(channel.lock()))(held => IO.blocking(held.release())))

  private def open(cacheDir: Path): IO[ContentAddressedCacheBackend] = openWith(cacheDir, "test-compiler")

  private def openWith(cacheDir: Path, compilerFingerprint: String): IO[ContentAddressedCacheBackend] =
    ContentAddressedCacheBackend.create(cacheDir, compilerFingerprint, "test-config", keyCodecs)

  private def keyCodecs: FactKeyCodecs.Registry =
    CoreFactCodecs.keyCodecs ++ LangFactCodecs.keyCodecs ++ JvmFactCodecs.keyCodecs

  /** Compile once, then hand the resulting cache data and an empty directory to store it in. Every [[open]] is a
    * *new* backend, so nothing carries over in memory and each property is proven through the files.
    */
  private def withCache(use: (FactCacheData, Path) => IO[Assertion]): IO[Assertion] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-store-src"))
      outputDir  <- IO.blocking(Files.createTempDirectory("eliot-store-out"))
      cacheDir   <- IO.blocking(Files.createTempDirectory("eliot-store-cache"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), program))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", outputDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      data       <- result.generator.buildCacheData()
      assertion  <- use(data, cacheDir)
    } yield assertion

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
