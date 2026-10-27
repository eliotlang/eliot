package com.vanillasource.eliot.eliotc.jvm.jargen

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.{CompilerError, Logging}
import com.vanillasource.eliot.eliotc.feedback.User.*
import com.vanillasource.eliot.eliotc.jvm.classgen.fact.GeneratedModule
import com.vanillasource.eliot.eliotc.module.fact.{UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.used.UsedNames

import java.io.{IOException, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
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
      digest     <- (JvmProgramGenerator.writeJar(targetDir, key.vfqn, allModules) <*
                      info[IO](s"Generated executable jar: ${JvmProgramGenerator.jarFilePath(targetDir, key.vfqn)}."))
                      .to[CompilerIO]
    } yield GenerateExecutableJar(key.vfqn, digest)

  /** Delete what a previous run wrote, before this generation reads anything.
    *
    * The processor owns its output path, and the engine's decision to run it *is* the signal that the file on disk is
    * no longer proven — so there is nothing to preserve. Any leftover temporary from a build that died mid-write goes
    * with it.
    */
  private def discardPreviousJar(mainValue: ValueFQN): IO[Unit] =
    IO.blocking {
      Files.deleteIfExists(JvmProgramGenerator.jarFilePath(targetDir, mainValue))
      Files.deleteIfExists(JvmProgramGenerator.temporaryJarFilePath(targetDir, mainValue))
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
      moduleKeys = JvmProgramGenerator.moduleKeys(vfqn, usedNames)
      // Every module's bytecode is independent of every other's: a parallel run generates them all at once.
      modules   <- prefetch(moduleKeys) >> moduleKeys.traverse(getFactOrAbort)
    } yield modules
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

  /** The modules whose classes make up the jar of the program `root` reaches. */
  def moduleKeys(root: ValueFQN, usedNames: UsedNames): Seq[GeneratedModule.Key] =
    usedNames.usedNames.keys.map(_.moduleName).toSeq.distinct.map(GeneratedModule.Key(_, root))

  /** Write the jar of `modules` for `mainValue` and answer the digest of what the file then holds — the generator's
    * write, and the jvm plugin's when the file is not what a cached [[GenerateExecutableJar]] says was written.
    *
    * The jar goes to a temporary file beside its destination and is moved into place once complete, so the destination
    * path only ever holds a whole jar: a build that dies mid-write leaves nothing runnable behind, rather than a
    * truncated file that looks present. The temporary is removed on any non-success (error or cancellation); after a
    * successful move it is already gone, so the cleanup is a no-op. The digest is read back from the file, not taken
    * from the bytes written, so it is the state this run left on disk.
    */
  def writeJar(targetDir: Path, mainValue: ValueFQN, modules: Seq[GeneratedModule]): IO[String] = {
    val jar       = jarFilePath(targetDir, mainValue)
    val temporary = temporaryJarFilePath(targetDir, mainValue)

    (jarOutputStream(targetDir, temporary).use { jos =>
      IO.blocking {
        generateManifest(jos)
        generateClasses(jos, modules)
      }
    } >> IO.blocking(Files.move(temporary, jar, ATOMIC_MOVE)).void)
      .guarantee(IO.blocking(Files.deleteIfExists(temporary)).void) >>
      IO.blocking(digestOf(jar)).flatMap(IO.fromOption(_)(new IOException(s"The jar $jar could not be read back.")))
  }

  /** The SHA-256 of the file at `path` as it stands, `None` if it is not there or cannot be read — which compares unequal
    * to any digest written, and so forces a rewrite: the fail-safe direction. Streamed, so the size of a jar does not
    * decide how much memory a build takes.
    */
  def digestOf(path: Path): Option[String] =
    try Option.when(Files.isRegularFile(path))(digestOf(Files.newInputStream(path)))
    catch { case _: Exception => None }

  /** Beside the destination, so the completing move stays within one directory (and one filesystem) and is atomic. */
  private def temporaryJarFilePath(targetDir: Path, mainValue: ValueFQN): Path =
    targetDir.resolve(jarFileName(mainValue) + ".tmp")

  private def jarOutputStream(targetDir: Path, temporary: Path): Resource[IO, JarOutputStream] =
    for {
      _   <- Resource.eval(IO.blocking(Files.createDirectories(targetDir)))
      os  <- Resource.fromAutoCloseable(IO.blocking(Files.newOutputStream(temporary, CREATE, TRUNCATE_EXISTING)))
      jos <- Resource.fromAutoCloseable(IO.blocking(new JarOutputStream(os)))
    } yield jos

  private def generateManifest(jos: JarOutputStream): Unit = {
    jos.putNextEntry(timestamped("META-INF/MANIFEST.MF"))
    jos.write("Manifest-Version: 1.0\nMain-Class: main\n".getBytes(StandardCharsets.UTF_8))
    jos.closeEntry()
  }

  /** Entries are written in name order, not in the order the modules were generated in. The module sequence comes from
    * the keys of a `Map`, whose iteration order is not a function of the program alone — sorting is what makes the same
    * program produce the same JAR (see [[timestamped]]).
    */
  private def generateClasses(jos: JarOutputStream, modules: Seq[GeneratedModule]): Unit = {
    val classFiles = modules.flatMap(_.classFiles).sortBy(_.fileName)

    classFiles.foreach { classFile =>
      jos.putNextEntry(timestamped(classFile.fileName))
      jos.write(classFile.bytecode)
      jos.closeEntry()
    }
  }

  /** An entry stamped with a fixed local time instead of "now", so the same program compiles to the same bytes.
    *
    * Without it every build produces a different JAR, which costs two things: a JAR cannot be compared byte for byte
    * against one built from the same sources — the cheapest oracle there is for "did this change alter codegen?" — and
    * every rewrite changes the artefact's digest ([[GenerateExecutableJar.digest]]) even when the program did not change
    * at all.
    *
    * `setTimeLocal` writes the DOS field directly, where `setTime` would convert from the default time zone (making the
    * bytes depend on where the build runs) and, outside the DOS range, add an extended-timestamp extra field.
    */
  private def timestamped(name: String): JarEntry = {
    val entry = new JarEntry(name)
    entry.setTimeLocal(epoch)
    entry
  }

  private def digestOf(stream: InputStream): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in     = new DigestInputStream(stream, digest)
    try {
      val buffer = new Array[Byte](64 * 1024)
      while (in.read(buffer) >= 0) ()
    } finally in.close()
    digest.digest().map(byte => f"$byte%02x").mkString
  }
}
