package com.vanillasource.eliot.eliotc.source.stat

import cats.effect.Sync
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.UUID

/** Generates file stats for a given file. This processor never fails.
  *
  * An mtime identifies a file's content only once it can no longer move without being seen. Stat the file at `T` and
  * read mtime `M`; a later modification at `W > T` gets mtime `floor(W) >= floor(T)`, so it can only reproduce `M` if
  * `M` falls in the same tick as `T`. Hence: **if `M` lies a full tick before `T`, every later modification is
  * visible**, and comparing on the mtime alone is conclusive. Inside that window it is not, and the stat says so by
  * carrying its own observation instant (see [[FileStat.unsettled]]).
  *
  * Two details are load-bearing:
  *   - **the clock is read before the stat**, so `now <= T`. Soundness needs `M + margin <= T`; a clock read *after*
  *     the stat gives `now >= T`, which does not imply it. It reads like a harmless reordering and is not one.
  *   - **[[FileStatProcessor.defaultSettleMargin]] must dominate the filesystem's tick plus any skew between its clock
  *     and this JVM's.** Measured on the development machine: a 4.00 ms tick (the kernel's, not the API's — nanosecond
  *     timestamps are stamped at that same tick and buy nothing) and 4–5 ms of skew. The default sits three orders of
  *     magnitude above both, because being wrong here compiles stale source, while being conservative only costs a
  *     re-read of a file written in the last two seconds.
  */
class FileStatProcessor(settleMargin: Duration = FileStatProcessor.defaultSettleMargin)
    extends SingleFactProcessor[FileStat.Key]
    with Logging {
  override protected def generateSingleFact(key: FileStat.Key): CompilerIO[FileStat] =
    for {
      now          <- Sync[CompilerIO].realTimeInstant // before the stat: see class doc
      lastModified <- Sync[CompilerIO]
                        .blocking(Files.readAttributes(key.file.toPath, classOf[BasicFileAttributes]))
                        .attempt
                        .map(_.toOption.map(_.lastModifiedTime.toInstant).filter(_.toEpochMilli > 0L))
      unsettled    <- lastModified.filter(_.plus(settleMargin).isAfter(now)).traverse(_ => nonce)
    } yield FileStat(key.file, lastModified, unsettled)

  /** A value unique to this observation, marking a stat that identifies nothing (see [[FileStat.unsettled]] for why it
    * is not a timestamp).
    */
  private val nonce: CompilerIO[String] = Sync[CompilerIO].delay(UUID.randomUUID().toString)
}

object FileStatProcessor {
  private val defaultSettleMargin = Duration.ofSeconds(2)
}
