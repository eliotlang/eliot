package com.vanillasource.eliot.eliotc.jvm.jargen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.cache.OutputFileStat
import com.vanillasource.eliot.eliotc.feedback.{CompilerError, Logging}
import com.vanillasource.eliot.eliotc.feedback.User.*
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule
import com.vanillasource.eliot.eliotc.module.fact.{UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.used.UsedNames

import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import java.util.jar.{JarEntry, JarOutputStream}

class JvmProgramGenerator(targetDir: Path) extends SingleFactProcessor[GenerateExecutableJar.Key] with Logging {

  override protected def generateSingleFact(key: GenerateExecutableJar.Key): CompilerIO[GenerateExecutableJar] =
    for {
      // Being asked to generate at all means the incremental engine could not prove the existing JAR is what the
      // current facts imply (if it could, this processor would not run). Its content is therefore worthless from here
      // on, so it is discarded *before* any input is demanded: whatever happens below — a type error, an abort, an
      // exception, cancellation — the build can no longer leave a stale, runnable JAR behind. See `discardPreviousJar`.
      _          <- discardPreviousJar(key.vfqn).to[CompilerIO]
      _          <- validateMainExists(key.vfqn)
      // Generate everything reachable from the synthesized entry point (module `main`, mounted into the runtime scan
      // pool by SyntheticMainMount and served by SyntheticMainSourceProcessor — ordinary facts, nothing is injected
      // here). The wrapper calls the configured user `main`, so the whole user program is reached through it — and it
      // is the ONLY valid monomorphization root: the idiomatic user `main` declares effects (`def main uses Console:
      // Unit`), each a handle type parameter, so demanding it as a standalone root would leave those neutral; the
      // wrapper binds each with a `with`.
      allModules <- generateModulesFrom(SyntheticMainSourceProcessor.syntheticMainVfqn)
      _          <- generateJarFile(key.vfqn, allModules).to[CompilerIO]
      // Depend on the output JAR's presence (a leaf), so a deleted JAR forces a rewrite under incremental compilation.
      // Read *after* the write, so the recorded value is the state this run actually left on disk. Reading it before
      // would record "absent" (this generation just deleted it) against a next-run "present", and the JAR would then
      // regenerate on every single build, forever.
      _          <- getFactOrAbort(OutputFileStat.Key(jarFilePath(key.vfqn).toFile))
    } yield GenerateExecutableJar(key.vfqn)

  /** Delete what a previous run wrote, before this generation reads anything.
    *
    * The processor owns its output path, and the engine's decision to run it *is* the signal that the file on disk is
    * no longer proven — so there is nothing to preserve. Any leftover temporary from a build that died mid-write goes
    * with it.
    */
  private def discardPreviousJar(mainValue: ValueFQN): IO[Unit] =
    IO.blocking {
      Files.deleteIfExists(jarFilePath(mainValue))
      Files.deleteIfExists(temporaryJarFilePath(mainValue))
    }.void

  /** Pre-flight for the configured entry point: fail with a plain, attributable message when the `-m` module — or its
    * `main` value — does not exist, instead of surfacing as a resolution error inside the synthesized wrapper source.
    * Deeper problems (a `main` declaring an effect the platform cannot run) are left to monomorphization, whose
    * missing-instance errors already point at the user's own source.
    */
  private def validateMainExists(vfqn: ValueFQN): CompilerIO[Unit] =
    getFactOrAbort(UnifiedModuleNames.Key(vfqn.moduleName)).flatMap {
      case names if !names.present                   =>
        failGlobally(s"Module '${vfqn.moduleName.show}' was not found on the source paths.")
      case names if !names.names.contains(vfqn.name) =>
        failGlobally(s"Module '${vfqn.moduleName.show}' has no '${vfqn.name.name}' value to run.")
      case _                                         => ().pure[CompilerIO]
    }

  private def failGlobally(message: String): CompilerIO[Unit] =
    compilerGlobalError(message).to[CompilerIO] >>
      registerCompilerError(CompilerError(message, Seq.empty, "<entry point>", "", PositionRange.zero)) >>
      abort[Unit]

  private def generateModulesFrom(vfqn: ValueFQN): CompilerIO[Seq[GeneratedModule]] =
    for {
      usedNames <- getFactOrAbort(UsedNames.Key(vfqn))
      modules   <- generateModules(vfqn, usedNames)
    } yield modules

  private def generateModules(vfqn: ValueFQN, usedNames: UsedNames): CompilerIO[Seq[GeneratedModule]] = {
    val moduleKeys = usedNames.usedNames.keys.map(_.moduleName).toSeq.distinct.map(GeneratedModule.Key(_, vfqn))

    // Every module's bytecode is independent of every other's: a parallel run generates them all at once.
    prefetch(moduleKeys) >> moduleKeys.traverse(getFactOrAbort)
  }

  /** Write the JAR to a temporary file beside its destination and move it into place once complete, so the destination
    * path only ever holds a whole JAR: a build that dies mid-write leaves nothing runnable behind, rather than a
    * truncated file that looks present. The temporary is removed on any non-success (error or cancellation); after a
    * successful move it is already gone, so the cleanup is a no-op.
    */
  private def generateJarFile(mainValue: ValueFQN, allClasses: Seq[GeneratedModule]): IO[Unit] =
    (jarOutputStream(mainValue).use { jos =>
      IO.blocking {
        generateManifest(jos)
        generateClasses(jos, allClasses)
      }
    } >> IO.blocking(Files.move(temporaryJarFilePath(mainValue), jarFilePath(mainValue), ATOMIC_MOVE)).void)
      .guarantee(IO.blocking(Files.deleteIfExists(temporaryJarFilePath(mainValue))).void) >>
      info(s"Generated executable jar: ${jarFilePath(mainValue)}.")

  /** Entries are written in name order, not in the order the modules were generated in. The module sequence comes from
    * the keys of a `Map`, whose iteration order is not a function of the program alone — sorting is what makes the same
    * program produce the same JAR (see [[timestamped]]).
    */
  private def generateClasses(jos: JarOutputStream, allClasses: Seq[GeneratedModule]): Unit = {
    val classFiles = allClasses.flatMap(_.classFiles).sortBy(_.fileName)

    classFiles.foreach { classFile =>
      jos.putNextEntry(timestamped(classFile.fileName))
      jos.write(classFile.bytecode)
      jos.closeEntry()
    }
  }

  private def jarOutputStream(mainValue: ValueFQN): Resource[IO, JarOutputStream] =
    for {
      _   <- Resource.eval(IO.blocking(Files.createDirectories(targetDir)))
      os  <- Resource.fromAutoCloseable(
               IO.blocking(Files.newOutputStream(temporaryJarFilePath(mainValue), CREATE, TRUNCATE_EXISTING))
             )
      jos <- Resource.fromAutoCloseable(IO.blocking(new JarOutputStream(os)))
    } yield jos

  private def jarFilePath(mainValue: ValueFQN): Path = JvmProgramGenerator.jarFilePath(targetDir, mainValue)

  /** Beside the destination, so the completing move stays within one directory (and one filesystem) and is atomic. */
  private def temporaryJarFilePath(mainValue: ValueFQN): Path = targetDir.resolve(jarFileName(mainValue) + ".tmp")

  def jarFileName(mainValue: ValueFQN): String = JvmProgramGenerator.jarFileName(mainValue)

  private def generateManifest(jos: JarOutputStream): Unit = {
    jos.putNextEntry(timestamped("META-INF/MANIFEST.MF"))
    jos.write("Manifest-Version: 1.0\nMain-Class: main\n".getBytes(StandardCharsets.UTF_8))
    jos.closeEntry()
  }

  /** An entry stamped with a fixed local time instead of "now", so the same program compiles to the same bytes.
    *
    * Without it every build produces a different JAR, which costs two things: a JAR cannot be compared byte for byte
    * against one built from the same sources — the cheapest oracle there is for "did this change alter codegen?" — and
    * every rewrite changes the artefact's digest ([[OutputFileStat]]) even when the program did not change at all.
    *
    * `setTimeLocal` writes the DOS field directly, where `setTime` would convert from the default time zone (making the
    * bytes depend on where the build runs) and, outside the DOS range, add an extended-timestamp extra field.
    */
  private def timestamped(name: String): JarEntry = {
    val entry = new JarEntry(name)
    entry.setTimeLocal(JvmProgramGenerator.epoch)
    entry
  }
}

object JvmProgramGenerator {

  /** The start of the MS-DOS epoch a ZIP entry's timestamp field is expressed in — the conventional fixed stamp for a
    * reproducible archive, and the earliest one representable without spilling into an extra field.
    */
  private val epoch: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0, 0)

  /** Where the executable for `mainValue` lands under `targetDir` — the one answer, asked by the generator that writes
    * it and by the `run` mode that executes it.
    */
  def jarFilePath(targetDir: Path, mainValue: ValueFQN): Path = targetDir.resolve(jarFileName(mainValue))

  def jarFileName(mainValue: ValueFQN): String = mainValue.moduleName.name + ".jar"
}
