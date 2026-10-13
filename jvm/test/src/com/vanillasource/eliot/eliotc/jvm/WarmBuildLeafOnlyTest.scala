package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** The standing warm-build metric assertion (`docs/incremental-compilation.md` §6 Step 6, §8): a warm compilation over
  * an unchanged source tree must regenerate **only world leaves** — the facts that form the boundary with the external
  * world and are therefore always re-run (every `FileStat`, the `OutputFileStat` digest, the `UpToDate` anchor
  * constant, the synthetic-main `SourceContent`). Everything else — every native contributor's `ContributedBinding`,
  * every `NativeBinding`, and above all every `MonomorphicValue` — must validate structurally and be accepted from the
  * warm in-memory cache without re-running its processor.
  *
  * This is the test that would have caught the two warm-build regressions §3/§8 diagnose: an input-less contributor
  * that loses its `UpToDate` anchor becomes an edge-less entry read as a leaf, and an equality-unstable fact reports
  * "value differs" every run — either one drags a slice of the monomorphize layer back through a needless re-typecheck.
  * The assertion is a single structural invariant ([[com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator.nonLeafRegenerations]]
  * is empty), so it guards *any* contributor that forgets the anchor, not just the three the fix addressed.
  *
  * Unlike the shared-session suites ([[FullIntegrationTest]]), this test drives its own [[CompilationSession]] and
  * compiles the *same* source file **in place, untouched** between the two runs: rewriting it (as `compileAndRun` does)
  * would move its mtime and force the non-leaf `SourceContent` to recompute-and-compare, which is not the unchanged-tree
  * scenario §8 measures. Writing once and compiling twice reproduces that measurement exactly.
  *
  * Untouched is necessary but no longer sufficient: the tree must also have **settled**. A file written moments ago has
  * an mtime too recent to identify its content ([[com.vanillasource.eliot.eliotc.source.stat.FileStat.unsettled]]), so
  * its leaf differs between the two runs by design and its `FileContent` is legitimately re-read — a real cost of a
  * build that follows an edit within the settle margin, and not the steady state this invariant is about. The source is
  * therefore backdated before compiling, which is what a source tree older than a couple of seconds looks like.
  */
class WarmBuildLeafOnlyTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val program = """def main uses Console: Unit = printLine("warm")"""

  "a warm build over an unchanged tree" should "regenerate only world leaves" in {
    withSession(program) { session =>
      for {
        cold     <- session.compileOnce()
        warm     <- session.compileOnce()
        nonLeaf  <- warm.generator.nonLeafRegenerations()
        warmKeys <- warm.generator.regeneratedKeys()
      } yield {
        cold.targetProduced shouldBe true
        warm.targetProduced shouldBe true
        warm.errors shouldBe Seq.empty
        // The warm run still re-runs the world leaves (never a no-op), but *nothing* with recorded dependencies.
        warmKeys should not be empty
        nonLeaf shouldBe Set.empty[CompilerFactKey[?]]
      }
    }
  }

  it should "regenerate far fewer facts warm than cold" in {
    withSession(program) { session =>
      for {
        cold     <- session.compileOnce()
        warm     <- session.compileOnce()
        coldKeys <- cold.generator.regeneratedKeys()
        warmKeys <- warm.generator.regeneratedKeys()
      } yield warmKeys.size should be < coldKeys.size
    }
  }

  /** Build a dedicated session over a fresh source/target directory pair holding `source` as module `Test`, then run
    * `use` against it. Mirrors [[FullIntegrationTest.SharedSession]]'s argument construction (the `jvm exe-jar` command
    * plus one `--path` per layer `eliot/src` root), but hands the session out directly so the caller controls each
    * `compileOnce` and never rewrites the source between runs.
    */
  private def withSession(source: String)(use: CompilationSession => IO[Assertion]): IO[Assertion] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-warm-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-warm-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), source))
      _          <- settle(sourceDir.resolve("Test.els")) >> settle(sourceDir)
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- use(session)
    } yield result

  /** Backdate a path so its mtime is old enough to identify its content, making it indistinguishable from a source tree
    * that has been sitting on disk. Verified and retried, since `setLastModified` is best-effort on some filesystems.
    */
  private def settle(path: Path, attempts: Int = 100): IO[Unit] =
    IO.blocking { path.toFile.setLastModified(SettledMtime); path.toFile.lastModified() }.flatMap { actual =>
      if (actual == SettledMtime || attempts <= 0) IO.unit else settle(path, attempts - 1)
    }

  private val SettledMtime = 1_600_000_000_000L

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
