package com.vanillasource.eliot.eliotc.compiler.cache

import cats.effect.Sync
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

import java.io.{File, FileInputStream}
import java.security.{DigestInputStream, MessageDigest}

/** Leaf processor for [[OutputFileStat]]: it digests the output file as it currently stands. Because it has no fact
  * dependencies the incremental engine always recomputes it, so an output that was deleted, truncated or overwritten
  * behind the compiler's back is detected on the next build.
  */
class OutputFileStatProcessor extends SingleFactProcessor[OutputFileStat.Key] with Logging {
  override protected def generateSingleFact(key: OutputFileStat.Key): CompilerIO[OutputFileStat] =
    Sync[CompilerIO].blocking(OutputFileStat(key.file, digestOf(key.file)))

  /** Any failure to read reads as "no usable content", which forces a rewrite — the fail-safe direction. */
  private def digestOf(file: File): Option[String] =
    try Option.when(file.isFile)(digestOf(new FileInputStream(file)))
    catch { case _: Exception => None }

  /** Streamed, so the size of a generated artefact does not decide how much memory a build takes. */
  private def digestOf(stream: FileInputStream): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in     = new DigestInputStream(stream, digest)
    try {
      val buffer = new Array[Byte](OutputFileStatProcessor.bufferSize)
      while (in.read(buffer) >= 0) ()
    } finally in.close()
    digest.digest().map(byte => f"$byte%02x").mkString
  }
}

object OutputFileStatProcessor {
  private val bufferSize = 64 * 1024
}
