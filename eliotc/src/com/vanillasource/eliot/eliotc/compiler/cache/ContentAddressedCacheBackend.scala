package com.vanillasource.eliot.eliotc.compiler.cache

import cats.effect.{Fiber, IO, Ref, Resource}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.cache.codec.{
  ContentAddressedInput,
  ContentAddressedOutput,
  FactCodec,
  FactKeyCodecs,
  ObjectId,
  ObjectIdOutput
}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec

import scala.concurrent.duration.*
import scala.util.Random

/** The incremental cache over a content-addressed object store (`docs/incremental-compilation.md` §13, §17).
  *
  * Two files, with opposite lifecycles, which is why they are two:
  *
  *   - **the body region** (`.eliot-objects-<config>`) is **append-only**. Objects are referred to by byte offset, so
  *     an offset written last run still resolves this run precisely because nothing before it ever moves. A save
  *     appends what is new and rewrites nothing.
  *   - **the index** (`.eliot-index-<config>`) is rewritten every save. It is the only thing a load must read in full:
  *     per entry, where its key is, where its value is, and where each dependency's key is.
  *
  * **A value is never written twice.** This is not an optimisation but what makes the append model work at all: on a
  * warm build every entry survives, and re-encoding them would append the entire graph again on every run. A value
  * still in the store is placed from its [[ObjectId]] without being read, and one held in memory is *hashed before it
  * is encoded* — so a world leaf that recomputed to an equal-but-fresh object resolves to the bytes already on disk. A
  * warm build therefore appends nothing at all.
  *
  * Duplicate storage is still *possible* here, unlike in §13's original framing, because the ids carried between runs
  * are the entries' roots rather than every object: a genuinely new value re-appends any sub-object it shares with
  * something already stored. That is the deliberate trade for not persisting a whole `ObjectId → offset` index, which
  * measured *larger than the object bodies themselves*. Compaction reclaims it, and compaction is needed regardless —
  * the bodies are untyped bytes, so a mark-and-sweep cannot walk them and GC is necessarily a decode-and-re-encode of
  * the live entries.
  *
  * **Two compilers may share a target directory, and the cache is what is locked — never the build** (§21). A build
  * system blocks a second build because it owns the output; this owns only a cache, and a cache can always be thrown
  * away. So a third file, `.eliot-lock-<config>`, is held shared for a load and exclusively for a save, `tryLock`ed for
  * at most [[ContentAddressedCacheBackend.LOCK_TIMEOUT]] — and failing to get it is not an error: a load answers
  * `None`, a save does nothing, and the only cost either way is a full compilation. Three things make the locked window
  * sound rather than merely serialized:
  *
  *   - a save is based at the region's **actual** length, read under the lock, rather than the length its own load saw,
  *     so a compiler that appended in between is extended rather than overwritten;
  *   - the header carries a random **region id**, minted whenever the region is *replaced* and unchanged across every
  *     append, so offsets held in memory can be told to belong to a region that no longer exists;
  *   - the index is published by **atomic rename** after the bodies are appended, so a reader never sees half of one
  *     and an interrupted save leaves the previous cache intact.
  *
  * **A save can be written behind the compilation** ([[writeBehind]]): the facts are immutable once registered, so a
  * second core encodes each one into the store's buffer while the compilation goes on, and the save itself only
  * finishes what is left and writes the files. The encoding is made against the region it expects to extend, read
  * without the lock; the save checks under the lock that this is still the region it extends, and encodes from scratch
  * when it is not, so a pre-encoding never lands at an offset that means something else.
  *
  * Both directions are fail-safe: `load` answers `None` on any problem (missing file, format or fingerprint mismatch, a
  * body region shorter than the index it was written with), and `save` warns rather than failing the build. The worst
  * either can do is cost a full compilation.
  */
final class ContentAddressedCacheBackend private (
    objectsFile: Path,
    indexFile: Path,
    lockFile: Path,
    compilerFingerprint: String,
    configFingerprint: String,
    keyCodecs: FactKeyCodecs.Registry,
    placement: Ref[IO, ContentAddressedCacheBackend.Placement]
) extends Logging {

  def load(): IO[Option[FactCacheData]] =
    withLock(shared = true)(
      IO.blocking(readFiles()).flatMap(_.traverse { case (data, next) => placement.set(next).as(data) })
    )(busy("doing a full compilation.").as(None))
      .handleErrorWith(t => warn[IO]("Could not read the incremental cache; doing a full compilation.", t).as(None))

  /** Start encoding this run's facts while it runs. Hand every fact the run registers to [[WriteBehind.offer]], and the
    * write-behind to the [[save]] of what the run produced.
    *
    * The encoding continues the region this backend loaded, from its length on disk now, or starts a new one when
    * nothing was loaded. Both are read without the lock, so they are only what the save expects to find: it checks them
    * under the lock and discards the encoding if another compiler moved the region in between.
    */
  def writeBehind(): IO[ContentAddressedCacheBackend.WriteBehind] =
    for {
      current          <- placement.get
      (regionId, base) <- IO.blocking(provisionalContinuation(current))
      located           = ContentAddressedCacheBackend.Located(
                              new ContentAddressedOutput(current.values, base),
                              current,
                              keyCodecs
                            )
      offered           = new ConcurrentLinkedQueue[CompilerFact]()
      finishing         = new AtomicBoolean(false)
      worker           <- IO.blocking(ContentAddressedCacheBackend.encodeOffered(offered, finishing, located)).start
    } yield new ContentAddressedCacheBackend.WriteBehind(current, regionId, base, located, offered, finishing, worker)

  /** Write `data`, finishing what `behind` already encoded of it, if it is given and still applies. */
  def save(data: FactCacheData, behind: Option[ContentAddressedCacheBackend.WriteBehind] = None): IO[Unit] =
    behind
      .flatTraverse(finished) // before the lock: another compiler waits for a save, never for its encoding
      .flatMap(encoded =>
        withLock(shared = false)(
          placement.get
            .flatMap(current => IO.blocking(writeFiles(data, current, encoded)))
            .flatMap {
              case Some((next, written, dropped)) => placement.set(next) >> reportSave(data, next, written, dropped)
              case None                           =>
                debug[IO]("The incremental cache was replaced by another compiler; not writing it.")
            }
        )(busy("not writing it."))
      )
      .handleErrorWith(t => warn[IO]("Could not write the incremental cache; the next build will be a full one.", t))

  /** What `behind` encoded, once it has encoded everything it was offered, or nothing if its encoding failed: the save
    * then encodes from scratch, and fails or not exactly as it would have without one.
    */
  private def finished(
      behind: ContentAddressedCacheBackend.WriteBehind
  ): IO[Option[ContentAddressedCacheBackend.WriteBehind]] =
    behind.finish.flatMap {
      case true  => IO.pure(Some(behind))
      case false => debug[IO]("Encoding the incremental cache behind the compilation failed; encoding it now.").as(None)
    }

  private def busy(consequence: String): IO[Unit] =
    debug[IO](
      s"Another compiler held the incremental cache for ${ContentAddressedCacheBackend.LOCK_TIMEOUT}; $consequence"
    )

  /** Run `action` holding the cache lock, or `whenBusy` if it cannot be had within the timeout.
    *
    * Never waits longer than a save takes by an order of magnitude: contention that outlasts that is a hung compiler
    * rather than a busy one, and waiting on it would be exactly the build-blocking this design refuses. An OS file lock
    * dies with the process holding it, so there is no stale lock to break. Within one JVM an overlapping request throws
    * instead of answering empty, which counts as busy for the same reason.
    */
  private def withLock[A](shared: Boolean)(action: IO[A])(whenBusy: => IO[A]): IO[A] =
    lock(shared).use(_.fold(whenBusy)(_ => action))

  private def lock(shared: Boolean): Resource[IO, Option[FileLock]] =
    Resource
      .make(IO.blocking(openLockFile()))(channel => IO.blocking(channel.close()))
      .flatMap(channel =>
        Resource.make(acquire(channel, shared, ContentAddressedCacheBackend.LOCK_ATTEMPTS))(
          _.traverse_(held => IO.blocking(held.release()))
        )
      )

  private def openLockFile(): FileChannel = {
    Files.createDirectories(lockFile.getParent)
    FileChannel.open(
      lockFile,
      StandardOpenOption.CREATE,
      StandardOpenOption.READ,
      StandardOpenOption.WRITE
    )
  }

  private def acquire(channel: FileChannel, shared: Boolean, remaining: Int): IO[Option[FileLock]] =
    IO.blocking(Option(channel.tryLock(0L, Long.MaxValue, shared)))
      .recover { case _: OverlappingFileLockException => None }
      .flatMap {
        case Some(held)             => IO.pure(Some(held))
        case None if remaining <= 1 => IO.pure(None)
        case None                   =>
          IO.sleep(ContentAddressedCacheBackend.LOCK_RETRY) >> acquire(channel, shared, remaining - 1)
      }

  private def readFiles(): Option[(FactCacheData, ContentAddressedCacheBackend.Placement)] =
    Option
      .when(Files.exists(indexFile) && Files.exists(objectsFile))(())
      .flatMap { _ =>
        val bodies = Files.readAllBytes(objectsFile)
        val in     = readingIndex()

        try readIndex(in, bodies)
        finally in.close()
      }

  private def readIndex(
      in: DataInputStream,
      bodies: Array[Byte]
  ): Option[(FactCacheData, ContentAddressedCacheBackend.Placement)] = {
    readHeader(in).filter(acceptable(_, bodies.length)).map { header =>
      val names   = Array.fill(FactCodec.readVarInt(in))(in.readUTF()).map(keyCodecs)
      val input   = new ContentAddressedInput(bodies)
      val entries = Seq.fill(FactCodec.readVarInt(in))(readEntry(in, names, input))

      (
        FactCacheData(entries.map(read => read.key -> read.entry).toMap),
        ContentAddressedCacheBackend.Placement(
          Some(header.regionId),
          header.bodyLength,
          entries.map(read => read.key -> read.keyOffset).toMap,
          entries.flatMap(read => read.located.map { case (stored, offset) => stored.id -> offset }).toMap
        )
      )
    }
  }

  /** The header, or nothing when this is not an index of a shape this compiler knows.
    *
    * The magic and the format version are checked *before* the rest is read, so an index left by an older format is
    * declined rather than decoded as garbage — which would surface as a warning on the first build after an upgrade.
    */
  private def readHeader(in: DataInputStream): Option[ContentAddressedCacheBackend.Header] = {
    val magic         = in.readUTF()
    val formatVersion = in.readInt()

    Option.when(
      magic == ContentAddressedCacheBackend.MAGIC && formatVersion == ContentAddressedCacheBackend.FORMAT_VERSION
    )(ContentAddressedCacheBackend.Header(in.readUTF(), in.readUTF(), in.readLong(), in.readInt()))
  }

  /** Whether an index describes a store this compiler may reuse.
    *
    * The length test is `<=`, not `==`: the bodies are appended *before* the index that names them is published, so a
    * region longer than its index claims is what an interrupted save leaves behind, not evidence of a race. The prefix
    * the index does claim can never have moved — that is what append-only means — and the region id is what catches a
    * region that was replaced rather than extended.
    */
  private def acceptable(header: ContentAddressedCacheBackend.Header, bodyLength: Int): Boolean =
    header.compilerFingerprint == compilerFingerprint &&
      header.configFingerprint == configFingerprint &&
      header.bodyLength <= bodyLength

  private def readEntry(
      in: DataInputStream,
      names: Array[FactCodec[CompilerFactKey[?]]],
      input: ContentAddressedInput
  ): Read = {
    val (key, keyOffset) = readKey(in, names, input)
    val located          = Option.when(in.readBoolean())(readStoredValue(in, input, key))
    val deps             = Seq.fill(FactCodec.readVarInt(in))(readKey(in, names, input)._1).toSet

    Read(key, keyOffset, located, CacheEntry(located.map(_._1).getOrElse(CachedValue.Absent), deps))
  }

  /** A value is *located*, not read: the offset and id come out of the index, and the bytes behind them are touched
    * only if something consumes the fact. Comparing a recomputed value against it needs the id alone.
    */
  private def readStoredValue(
      in: DataInputStream,
      input: ContentAddressedInput,
      key: CompilerFactKey[?]
  ): (CachedValue.Stored, Int) = {
    val offset = FactCodec.readVarInt(in)
    val id     = ObjectId(in.readLong(), in.readLong())

    (new CachedValue.Stored(id, () => materialise(input, key, offset), identify(key, _)), offset)
  }

  /** Hash a recomputed fact exactly as the store hashed the one it holds. */
  private def identify(key: CompilerFactKey[?], fact: CompilerFact): ObjectId =
    new ObjectIdOutput().identify(fact)(using key.valueCodec.get.asInstanceOf[FactCodec[CompilerFact]])

  /** Read a key's type name index and offset, then decode it with the codec that name registers. */
  private def readKey(
      in: DataInputStream,
      names: Array[FactCodec[CompilerFactKey[?]]],
      input: ContentAddressedInput
  ): (CompilerFactKey[?], Int) = {
    val codec  = names(FactCodec.readVarInt(in))
    val offset = FactCodec.readVarInt(in)

    (input.read(offset)(using codec), offset)
  }

  private def materialise(input: ContentAddressedInput, key: CompilerFactKey[?], offset: Int): CompilerFact =
    input.read(offset)(using key.valueCodec.get.asInstanceOf[FactCodec[CompilerFact]])

  /** One entry as it comes back, with where its parts live so the next save can reuse them untouched. */
  private case class Read(
      key: CompilerFactKey[?],
      keyOffset: Int,
      located: Option[(CachedValue.Stored, Int)],
      entry: CacheEntry
  )

  /** Write what this run holds, or answer `None` when the region it was loaded from is gone (§21).
    *
    * Called with the cache lock held, which is what lets it read the region's real state rather than assume it.
    */
  private def writeFiles(
      data: FactCacheData,
      current: ContentAddressedCacheBackend.Placement,
      behind: Option[ContentAddressedCacheBackend.WriteBehind]
  ): Option[(ContentAddressedCacheBackend.Placement, Int, Int)] =
    continuation(current).map { case (freshRegionId, base) =>
      val ahead    = behind.filter(_.continues(current, base))
      val regionId = ahead.fold(freshRegionId)(_.regionId)
      val located  = ahead.fold(
        ContentAddressedCacheBackend.Located(new ContentAddressedOutput(current.values, base), current, keyCodecs)
      )(_.located)
      val encoded  = data.entries.toSeq.flatMap { case (key, entry) => located.entry(key, entry) }
      val appended = located.out.appendedBytes

      Files.createDirectories(objectsFile.getParent)
      if (base === 0) Files.write(objectsFile, appended)
      else Files.write(objectsFile, appended, StandardOpenOption.APPEND)
      writeIndex(regionId, base + appended.length, encoded)

      (
        ContentAddressedCacheBackend.Placement(
          Some(regionId),
          base + appended.length,
          located.keys,
          encoded.flatMap(_.value).map(placed => placed.id -> placed.offset).toMap
        ),
        appended.length,
        data.entries.size - encoded.size
      )
    }

  /** Which region this save writes, and the offset it continues at — or nothing, when it may not write at all.
    *
    * Three cases, and the middle one is the reason the region id exists:
    *
    *   - **nothing loaded** (a cold start, or a load that was rejected): whatever is on disk is unreachable to this
    *     run, so the region is *replaced* under a **new** id. Any compiler still holding offsets into the old one finds
    *     the id changed at its own next save and stands down, rather than appending into a region that no longer means
    *     what its offsets say.
    *   - **the region we loaded is still there**: extend it — but from its **actual** end, read here under the lock,
    *     not from the length this run loaded. Another compiler may have appended in between, and its bytes are as
    *     immutable as any others; basing at the loaded length would write our objects on top of the offsets we then
    *     publish. This is the race that produces a wrong build rather than a slow one.
    *   - **the region was replaced underneath us**: every offset carried in memory belongs to a store that is gone.
    *     Skip. The cache is an optimisation, so forfeiting one save costs a colder next build and nothing else.
    */
  private def continuation(current: ContentAddressedCacheBackend.Placement): Option[(Long, Int)] =
    current.region match {
      case None           => Some((Random.nextLong(), 0))
      case Some(regionId) => Option.when(regionOnDisk().contains(regionId))((regionId, currentBodyLength()))
    }

  /** The region a write-behind encodes for and its offset, as [[continuation]] would answer if the save were now;
    * read without the lock, so the save checks it again. A region that is gone is answered with its id all the same:
    * the save will not write, and the encoding is wasted.
    */
  private def provisionalContinuation(current: ContentAddressedCacheBackend.Placement): (Long, Int) =
    current.region.fold((Random.nextLong(), 0))(regionId => (regionId, currentBodyLength()))

  /** The id of the region the published index describes, if there is one this compiler could have written. */
  private def regionOnDisk(): Option[Long] =
    Option.when(Files.exists(indexFile))(()).flatMap { _ =>
      val in = readingIndex()

      try readHeader(in).filter(acceptable(_, currentBodyLength())).map(_.regionId)
      catch { case _: Exception => None }
      finally in.close()
    }

  private def currentBodyLength(): Int =
    if (Files.exists(objectsFile)) Files.size(objectsFile).toInt else 0

  /** Publish the index by **atomic rename**, after the bodies it names are already appended.
    *
    * Both halves of that matter. A reader holding only the shared lock still never sees a partly written index, and a
    * compiler killed during a save leaves the *previous* index in place — a truncating write in its position would
    * destroy a good cache to make an unusable one.
    */
  private def writeIndex(regionId: Long, bodyLength: Int, entries: Seq[ContentAddressedCacheBackend.Encoded]): Unit = {
    val names     = entries.flatMap(entry => entry.keyName +: entry.deps.map(_._1)).distinct
    val index     = names.zipWithIndex.toMap
    val temporary =
      indexFile.resolveSibling(s"${indexFile.getFileName}${ContentAddressedCacheBackend.TEMPORARY_SUFFIX}")
    val out       = writing(temporary)

    try {
      out.writeUTF(ContentAddressedCacheBackend.MAGIC)
      out.writeInt(ContentAddressedCacheBackend.FORMAT_VERSION)
      out.writeUTF(compilerFingerprint)
      out.writeUTF(configFingerprint)
      out.writeLong(regionId)
      out.writeInt(bodyLength)
      FactCodec.writeVarInt(out, names.size)
      names.foreach(out.writeUTF)
      FactCodec.writeVarInt(out, entries.size)
      entries.foreach { entry =>
        FactCodec.writeVarInt(out, index(entry.keyName))
        FactCodec.writeVarInt(out, entry.keyOffset)
        out.writeBoolean(entry.value.isDefined)
        entry.value.foreach { placed =>
          FactCodec.writeVarInt(out, placed.offset)
          out.writeLong(placed.id.high)
          out.writeLong(placed.id.low)
        }
        FactCodec.writeVarInt(out, entry.deps.size)
        entry.deps.foreach { case (name, offset) =>
          FactCodec.writeVarInt(out, index(name))
          FactCodec.writeVarInt(out, offset)
        }
      }
    } finally out.close()

    val _ = Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private def readingIndex(): DataInputStream =
    new DataInputStream(
      new BufferedInputStream(Files.newInputStream(indexFile), ContentAddressedCacheBackend.INDEX_BUFFER)
    )

  private def writing(file: Path): DataOutputStream =
    new DataOutputStream(
      new BufferedOutputStream(Files.newOutputStream(file), ContentAddressedCacheBackend.INDEX_BUFFER)
    )

  private def reportSave(
      data: FactCacheData,
      next: ContentAddressedCacheBackend.Placement,
      written: Int,
      dropped: Int
  ): IO[Unit] =
    debug[IO](
      s"Incremental cache: ${data.entries.size - dropped}/${data.entries.size} entries persisted, " +
        s"$written bytes appended (${next.bodyLength} total)."
    ) >> warn[IO](s"$dropped cache entries had a fact key type with no registered codec.").whenA(dropped > 0)
}

object ContentAddressedCacheBackend {
  private val MAGIC: String            = "ELIOT-CAS"
  private val FORMAT_VERSION: Int      = 3
  private val OBJECTS_FILE: String     = ".eliot-objects"
  private val INDEX_FILE: String       = ".eliot-index"
  private val LOCK_FILE: String        = ".eliot-lock"
  private val TEMPORARY_SUFFIX: String = ".tmp"

  /** The buffer the index is read and written through. The index is a few megabytes of varints, a byte or two each,
    * and written to the file stream unbuffered every one was a system call: two seconds of a cold build's save, which a
    * CPU profile does not see because the time is spent in the kernel (`docs/compiler-performance.md` §10).
    */
  private val INDEX_BUFFER: Int = 1 << 16

  /** How long a load or a save will wait for the cache lock before giving up on the cache entirely. A save is ~100–150
    * ms, so contention outlasting this is a hung compiler and not a busy one — and waiting on that would be the
    * build-blocking this design exists to avoid.
    */
  private val LOCK_ATTEMPTS: Int                  = 20
  private val LOCK_RETRY: FiniteDuration          = 50.millis
  private[cache] val LOCK_TIMEOUT: FiniteDuration = LOCK_RETRY * LOCK_ATTEMPTS.toLong

  def create(
      targetPath: Path,
      compilerFingerprint: String,
      configFingerprint: String,
      keyCodecs: FactKeyCodecs.Registry
  ): IO[ContentAddressedCacheBackend] =
    Ref
      .of[IO, Placement](Placement.empty)
      .map(
        new ContentAddressedCacheBackend(
          fileFor(targetPath, OBJECTS_FILE, configFingerprint),
          fileFor(targetPath, INDEX_FILE, configFingerprint),
          fileFor(targetPath, LOCK_FILE, configFingerprint),
          compilerFingerprint,
          configFingerprint,
          keyCodecs,
          _
        )
      )

  /** One pair of files per configuration, so distinct configurations coexist under one target directory instead of
    * clobbering each other. The name holds a truncated, sanitized fingerprint only to bound it; the header carries the
    * full value for the exact check, so a name collision degrades to a cold build rather than to serving the wrong
    * cache.
    */
  private def fileFor(targetDir: Path, prefix: String, configFingerprint: String): Path =
    targetDir.resolve(s"$prefix-${configFingerprint.filter(_.isLetterOrDigit).take(16)}")

  /** What an index says about itself, past the magic and format version that gate reading it at all. */
  private case class Header(
      compilerFingerprint: String,
      configFingerprint: String,
      regionId: Long,
      bodyLength: Int
  )

  /** One entry as it goes into the index. */
  private case class Encoded(
      keyName: String,
      keyOffset: Int,
      value: Option[ContentAddressedOutput.Placed],
      deps: Seq[(String, Int)]
  )

  /** Where everything the store already holds lives, carried between a load and the saves that follow it.
    *
    * `values` is keyed by **content**, not by which key or which object: that is what lets a leaf recomputed to an
    * equal-but-fresh value resolve to the bytes already on disk instead of appending them again.
    *
    * Every offset in here is meaningful **only in `region`**, the incarnation of the body region it was read from. An
    * offset survives an append because the region only grows; it does not survive the region being replaced, and that
    * is what the id is compared for before a save extends anything (§21).
    */
  private case class Placement(
      region: Option[Long],
      bodyLength: Int,
      keys: Map[CompilerFactKey[?], Int],
      values: Map[ObjectId, Int]
  )

  private object Placement {

    /** Nothing loaded: no region to extend, and no offset that means anything. */
    val empty: Placement = Placement(None, 0, Map.empty, Map.empty)
  }

  /** How long the write-behind's worker sleeps when it has encoded everything offered so far. */
  private val WRITE_BEHIND_POLL: FiniteDuration = 2.millis

  /** A save being written behind the compilation ([[ContentAddressedCacheBackend.writeBehind]]): a worker encoding each
    * fact it is offered, on a thread of its own, into the store's buffer for the region it expects the save to extend.
    *
    * The worker **polls** rather than waits to be woken. Offering a fact then costs the compilation one lock-free
    * enqueue and never a thread wake-up: a worker on the compute pool woken per fact, when the compilation's fibers ran
    * there, made the compilation slower than the save it took off it (`docs/compiler-performance.md` §10).
    */
  final class WriteBehind private[ContentAddressedCacheBackend] (
      startedFrom: Placement,
      private[ContentAddressedCacheBackend] val regionId: Long,
      base: Int,
      private[ContentAddressedCacheBackend] val located: Located,
      offered: ConcurrentLinkedQueue[CompilerFact],
      finishing: AtomicBoolean,
      worker: Fiber[IO, Throwable, Unit]
  ) {

    /** Hand a registered fact to the encoding. It is encoded later, on the worker, so it must not change, which a fact
      * never does.
      */
    def offer(fact: CompilerFact): Unit = offered.add(fact): Unit

    /** Wait until everything offered is encoded, and answer whether it all was. Nothing touches the encoding after
      * this but the save, so it may read it from any thread. Also what stops a write-behind no save will use.
      */
    def finish: IO[Boolean] =
      IO.delay(finishing.set(true)) >> worker.join.map(_.isSuccess)

    /** Whether the save of `current` at `offset` extends exactly what this encoded for. */
    private[ContentAddressedCacheBackend] def continues(current: Placement, offset: Int): Boolean =
      (current eq startedFrom) && offset === base
  }

  /** Encode what is `offered` until `finishing` is set and everything offered before it is encoded. The flag is read
    * before the queue is drained, so a fact offered before the finish is never left behind.
    */
  @tailrec
  private def encodeOffered(
      offered: ConcurrentLinkedQueue[CompilerFact],
      finishing: AtomicBoolean,
      located: Located
  ): Unit = {
    val last = finishing.get()

    Iterator.continually(offered.poll()).takeWhile(_ != null).foreach(located.prepare)
    if (!last) {
      LockSupport.parkNanos(WRITE_BEHIND_POLL.toNanos)
      encodeOffered(offered, finishing, located)
    }
  }

  /** Places each key and value in the store, reusing where it already lives and encoding only what is new.
    *
    * An entry is placed **whole or not at all**. A key type with no registered codec cannot be read back, and dropping
    * merely the *dependency* that names it would leave an entry claiming fewer inputs than it has — under-invalidation,
    * the one failure direction that produces a wrong build rather than a slow one.
    */
  private final class Located(
      val out: ContentAddressedOutput,
      prior: Placement,
      keyCodecs: FactKeyCodecs.Registry
  ) {
    private var keyOffsets: Map[CompilerFactKey[?], Int] = prior.keys
    private val prepared = new IdentityHashMap[CompilerFact, ContentAddressedOutput.Placed]()

    def keys: Map[CompilerFactKey[?], Int] = keyOffsets

    /** Place a fact's key and value ahead of the save, which then finds them placed: a write-behind's work. A fact
      * whose key type has no codec is left for the save, which drops it.
      */
    def prepare(fact: CompilerFact): Unit = {
      val key = fact.key()

      if (keyLocation(key).isDefined && key.valueCodec.isDefined) {
        val _ = prepared.put(fact, inMemory(key, fact))
      }
    }

    def entry(key: CompilerFactKey[?], entry: CacheEntry): Option[Encoded] =
      for {
        keyOffset  <- keyLocation(key)
        depOffsets <- entry.directDeps.toSeq.traverse(dep => keyLocation(dep).map(FactKeyCodecs.nameOf(dep) -> _))
      } yield Encoded(
        FactKeyCodecs.nameOf(key),
        keyOffset,
        Option.when(key.valueCodec.isDefined)(entry.stored).flatMap(valueLocation(key, _)),
        depOffsets
      )

    private def keyLocation(key: CompilerFactKey[?]): Option[Int] =
      keyOffsets.get(key).orElse {
        keyCodecs.get(FactKeyCodecs.nameOf(key)).map { codec =>
          val offset = out.write(key)(using codec.asInstanceOf[FactCodec[CompilerFactKey[?]]]).offset

          keyOffsets = keyOffsets.updated(key, offset)
          offset
        }
      }

    /** Where a value goes, **without reading it unless it has to, and without writing it unless it is new**.
      *
      * A value still in the store is placed from its id alone — materialising it merely to write it back is precisely
      * the cost the lazy read exists to avoid.
      *
      * One held in memory is **identified before it is placed**, and that order is the point. Encoding descends into a
      * value's children and appends each as it goes, so by the time the writer can tell that the value as a whole is
      * one it already holds, it has already appended the whole subtree underneath it. Hashing first — which costs no
      * bytes and no buffers — asks the only question that matters: is this exactly what is on disk already? On a warm
      * build the answer is yes for every world leaf, which is otherwise the one thing that grows the store every run.
      */
    private def valueLocation(key: CompilerFactKey[?], value: CachedValue): Option[ContentAddressedOutput.Placed] =
      value match {
        case CachedValue.Absent         => None
        case stored: CachedValue.Stored =>
          prior.values.get(stored.id).map(ContentAddressedOutput.Placed(stored.id, _))
        case CachedValue.InMemory(fact) => Some(Option(prepared.get(fact)).getOrElse(inMemory(key, fact)))
      }

    private def inMemory(key: CompilerFactKey[?], fact: CompilerFact): ContentAddressedOutput.Placed = {
      val codec = key.valueCodec.get.asInstanceOf[FactCodec[CompilerFact]]

      held(fact, codec).getOrElse(out.write(fact)(using codec))
    }

    /** The value's existing place, if the store already holds exactly it. Skipped when there is nothing to match
      * against, so a cold build does not hash everything twice.
      */
    private def held(fact: CompilerFact, codec: FactCodec[CompilerFact]): Option[ContentAddressedOutput.Placed] =
      Option
        .when(prior.values.nonEmpty)(new ObjectIdOutput().identify(fact)(using codec))
        .flatMap(id => prior.values.get(id).map(ContentAddressedOutput.Placed(id, _)))
  }
}
