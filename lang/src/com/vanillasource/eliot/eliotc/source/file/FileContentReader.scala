package com.vanillasource.eliot.eliotc.source.file

import cats.effect.{Resource, Sync}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.{CompilerError, Logging}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.source.stat.FileStat

import scala.io.Source

/** Generates the source code for a given File, i.e. reads it from disk. Note, that this generator does not fail if the
  * File is missing, so it can be used to probe whether a file is present. If File is not present, or not readable, this
  * will just silently ignore the issue and not produce a fact.
  *
  * Depends on [[FileStat]] (the file's last-modified time) so that, under incremental compilation, the (expensive) read
  * is skipped and the cached content reused whenever the file's mtime is unchanged.
  */
class FileContentReader extends SingleFactProcessor[FileContent.Key] with Logging {
  override protected def generateSingleFact(key: FileContent.Key): CompilerIO[FileContent] =
    getFactOrAbort(FileStat.Key(key.file)) >> Resource
      .make(Sync[CompilerIO].blocking(Source.fromFile(key.file)))(source => Sync[CompilerIO].blocking(source.close()))
      .use(source => Sync[CompilerIO].blocking(FileContent(key.file, source.getLines().mkString("\n"))))
      .attempt
      .flatMap {
        case Right(fact) => fact.pure[CompilerIO]
        case Left(_)     =>
          registerCompilerError(
            CompilerError("Could not read file.", Seq.empty, key.file.getPath, "", PositionRange.zero)
          ) >> abort
      }

}
