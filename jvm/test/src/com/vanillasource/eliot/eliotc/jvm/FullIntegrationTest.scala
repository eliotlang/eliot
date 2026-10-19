package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.std.Mutex
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.{CompilationResult, CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** Base trait for the end-to-end JVM integration suites: each test compiles a small Eliot program to an executable jar
  * and runs it, asserting on its output (or on the compiler's diagnostics for the failing cases).
  *
  * The expensive part of such a compile is not the few-line test program — it is discovering the plugins and compiling
  * the base-layer `.els` files (`lang` + `stdlib` + `jvm`, plus `stdlib`'s `compiler/` overlay) that every program
  * stands on. Rather than
  * pay that cold start per test, every suite shares a single resident [[CompilationSession]] ([[FullIntegrationTest]]'s
  * companion): the base is compiled once and every subsequent test reuses the warm *in-memory* fact cache — which keeps
  * the monomorphized `SemValue`s, dropped only on disk serialization. Because the generator always re-reads leaf source
  * facts, overwriting the program file in place invalidates exactly that program's fact chain while the base facts are
  * accepted by value. See [[FullIntegrationTest.SharedSession]].
  */
trait FullIntegrationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  import FullIntegrationTest.shared

  /** The executable jar the shared session builds, so a suite can assert on the artefact on disk itself — that a failed
    * build left none, that an unchanged rebuild did not rewrite it.
    */
  protected def jarPath: Path = shared.jarPath

  /** Compile `source` (as module `Test`) to an executable jar and run it in-process, returning its captured standard
    * output. `stdin` is fed to the program's standard input — needed by `Console.readLine`-based programs.
    */
  protected def compileAndRun(source: String, stdin: String = ""): IO[String] =
    compiled(source) >> runJar(shared.jarPath, stdin)

  /** Compile `source` and return the compiler's printed diagnostics (captured from standard output). The build is
    * expected to fail; the returned text contains the reported error messages.
    */
  protected def compileForErrors(source: String): IO[String] =
    IO.blocking {
      val baos        = new ByteArrayOutputStream()
      val printStream = new PrintStream(baos)
      val oldOut      = System.out
      val oldErr      = System.err
      try {
        // Compiler diagnostics are written via cats-effect `Console[IO]` (standard error); capture both streams so the
        // reported error text is observable here.
        System.setOut(printStream)
        System.setErr(printStream)
        shared
          .compile(source)
          .flatMap(_.errors.traverse_(_.print()))
          .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      } finally {
        System.setOut(oldOut)
        System.setErr(oldErr)
      }
      printStream.flush()
      baos.toString
    }

  /** Compile `source` to an executable jar, then run it as a separate `java -jar` process, returning whatever it printed
    * to standard output. Used to exercise a deliberately non-terminating (`{Inf}`) program: the loop never returns on
    * its own, so the process is killed as soon as it has printed enough output to observe the loop (or after
    * `timeoutMillis` as a hard cap).
    */
  protected def compileAndRunBounded(source: String, timeoutMillis: Long): IO[String] =
    compiled(source) >> runJarBounded(shared.jarPath, timeoutMillis)

  /** Compile `source` to an executable jar, run it to completion as a separate `java -jar` process, and return what it
    * printed to standard output together with the exit code it reported.
    *
    * Out-of-process for the same reason [[compileAndRunBounded]] is, one step further along: a program that registers a
    * non-zero exit code ends in `System.exit`, which a reflective in-VM call does not survive any more than it survives
    * a program that never returns. The exit code is also the thing under test here, and it exists only as a property of
    * a real process.
    */
  protected def compileAndRunToCompletion(source: String): IO[(String, Int)] =
    compiled(source) >> runJarToCompletion(shared.jarPath)

  /** Compile, and fail the test *with the compiler's diagnostics* unless the build succeeded — it reported nothing and
    * produced its jar. A test that goes on to run the jar must never be allowed to read an artefact this compilation
    * did not produce: the build deletes the jar it cannot vouch for, so the alternative is an unattributable
    * `ClassNotFoundException` instead of the error that actually broke the program.
    *
    * The gate includes the error list, which it could not while the session leaked diagnostics from the *previous*
    * test's program (facts that program left in the warm cache, regenerated by this run's validation walk and reported
    * as if they were this program's). That leak was the engine reporting questions it asked about the past — fixed at
    * the source in `IncrementalFactGenerator.currentErrors`, which reports only the graph the run actually built — so a
    * diagnostic reaching a test here is now this test's own.
    */
  private def compiled(source: String): IO[Unit] =
    shared.compile(source).flatMap { result =>
      val what = if (result.targetProduced) "reported errors" else "produced no jar"

      IO.raiseError(new IllegalStateException(s"Compilation $what:\n${describe(result.errors)}"))
        .unlessA(result.succeeded)
    }

  private def describe(errors: Seq[CompilerError]): String =
    errors.map(error => s"${error.contentSource}:${error.sourceRange.from.line}: ${error.message}").mkString("\n")

  private def runJarBounded(jarPath: Path, timeoutMillis: Long): IO[String] = {
    // The bounded tests all assert the loop line was printed more than five times; stopping once a comfortable margin of
    // complete lines is present lets us kill the process the moment the loop is demonstrably running, reclaiming most of
    // `timeoutMillis` (a tight print-loop fills the output within a few milliseconds of JVM startup). `timeoutMillis`
    // remains a hard upper bound in case the program is slow or never prints.
    val minLines     = 12
    val pollInterval = 10.millis

    val start = IO.blocking {
      // Run out-of-process (not via an in-process classloader as `runJar` does): an `{Inf}` program never returns, so it
      // must be killed, which a reflective in-VM call cannot survive. Output is redirected to a file to avoid a pipe
      // deadlock when the unbounded loop outpaces a reader.
      val outFile = Files.createTempFile("eliot-bounded-out", ".txt")
      val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val process = new ProcessBuilder(javaBin, "-jar", jarPath.toString)
        .redirectErrorStream(true)
        .redirectOutput(outFile.toFile)
        .start()
      (process, outFile)
    }

    def completedLines(outFile: Path): IO[Int] =
      IO.blocking(Files.readString(outFile)).handleError(_ => "").map(_.linesIterator.count(_.nonEmpty))

    start.bracket { case (process, outFile) =>
      IO.monotonic.flatMap { startTime =>
        def poll: IO[Unit] =
          IO.monotonic.flatMap { now =>
            if ((now - startTime).toMillis >= timeoutMillis) IO.unit
            else
              completedLines(outFile).flatMap { lines =>
                if (lines >= minLines || !process.isAlive) IO.unit
                else IO.sleep(pollInterval) >> poll
              }
          }
        poll >> IO.blocking(Files.readString(outFile))
      }
    } { case (process, outFile) =>
      IO.blocking {
        process.destroyForcibly()
        process.waitFor()
        Files.deleteIfExists(outFile)
        ()
      }
    }
  }

  /** Run an executable jar as its own `java -jar` process and wait for it, answering its standard output and exit
    * code. Standard error is folded into the output so a stack trace from a program that died cannot vanish.
    */
  private def runJarToCompletion(jarPath: Path): IO[(String, Int)] = IO.blocking {
    val outFile = Files.createTempFile("eliot-exit-out", ".txt")
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
    try {
      val process = new ProcessBuilder(javaBin, "-jar", jarPath.toString)
        .redirectErrorStream(true)
        .redirectOutput(outFile.toFile)
        .start()
      val code    = process.waitFor()
      (Files.readString(outFile).stripLineEnd, code)
    } finally {
      Files.deleteIfExists(outFile)
      ()
    }
  }

  /** Run an executable jar in-process (a fresh classloader over the jar), feeding `stdin` and capturing standard
    * output.
    */
  private def runJar(jarPath: Path, stdin: String): IO[String] = IO.blocking {
    val classLoader = new URLClassLoader(Array(jarPath.toUri.toURL), ClassLoader.getPlatformClassLoader)
    try {
      val mainClass   = classLoader.loadClass("main")
      val mainMethod  = mainClass.getMethod("main", classOf[Array[String]])
      val baos        = new ByteArrayOutputStream()
      val printStream = new PrintStream(baos)
      val inStream    = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8))
      val oldOut      = System.out
      val oldIn       = System.in
      try {
        System.setOut(printStream)
        System.setIn(inStream)
        Console.withOut(printStream) {
          mainMethod.invoke(null, Array.empty[String])
        }
      } finally {
        System.setOut(oldOut)
        System.setIn(oldIn)
      }
      printStream.flush()
      baos.toString.stripLineEnd
    } finally {
      classLoader.close()
    }
  }
}

object FullIntegrationTest {

  /** The single resident compilation session shared across every integration suite in this test JVM. Building it is
    * deferred to first use and done exactly once (a plain `lazy val` — suites run serially, so no synchronization is
    * needed for the initialization itself; concurrent *compiles* are guarded by the session's own mutex).
    */
  private lazy val shared: SharedSession = SharedSession.create().unsafeRunSync()

  /** A resident session pinned to a fixed configuration — one source directory, one target directory, module `Test` —
    * so a single [[CompilationSession]] serves every test. Each compile overwrites the program file in place; the
    * generator's content-addressed change detection then recompiles just that file while accepting the unchanged base
    * facts from the warm in-memory cache. A mutex serialises write-then-compile so suites cannot interleave one test's
    * file write with another's compile (compiles are already serial under `AsyncIOSpec`; the guard makes it robust).
    */
  final class SharedSession private (sourceFile: Path, val jarPath: Path, session: CompilationSession, lock: Mutex[IO]) {
    def compile(source: String): IO[CompilationResult] =
      lock.lock.surround(IO.blocking(Files.writeString(sourceFile, source)) >> session.compileOnce())
  }

  object SharedSession {

    /** CP1.5: the abstract base (`lang` + `stdlib`) and the `jvm` target layer are passed to the compiler as filesystem
      * source roots — each layer module's `eliot/src` source root — instead of being discovered on the classpath. A forked
      * test JVM's working dir is a per-worker sandbox, so the build hands the repo root in via `ELIOT_REPO_ROOT` (see
      * `build.mill`). These options are appended *after* the `jvm exe-jar …` command — the only position scopt accepts
      * these top-level options (exactly as `-o` already trails it) (CP1.5).
      */
    private def layerPathArgs: List[String] = {
      val repoRoot             =
        Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
      def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
      // One `--path` per layer `eliot/src` root; the compiler pool additionally scans each root's sibling `compiler/`
      // overlay (only `stdlib` ships one — the compile-time `Either`/`Option`/`Pair`/`Bound`/`Interval`, `Abort` and
      // `Throw`).
      List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
    }

    def create(): IO[SharedSession] =
      for {
        sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-src"))
        targetDir  <- IO.blocking(Files.createTempDirectory("eliot-target"))
        args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
        sessionOpt <- Compiler.createSession(args)
        session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
        lock       <- Mutex[IO]
      } yield new SharedSession(sourceDir.resolve("Test.els"), targetDir.resolve("Test.jar"), session, lock)
  }
}
