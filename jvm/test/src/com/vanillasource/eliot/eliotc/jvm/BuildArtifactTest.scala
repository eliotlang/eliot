package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.syntax.all.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The build's artefact invariant: after any run the executable jar is either exactly what the compilation implies, or
  * it is not there at all. A stale jar left behind by a failed build is worse than no jar — it runs, and it silently
  * answers with the previous program.
  */
class BuildArtifactTest extends FullIntegrationTest {

  private val working = """def main uses Console: Unit = printLine("working")"""
  private val other   = """def main uses Console: Unit = printLine("other")"""
  private val broken  = """def main uses Console: Unit = printLine(nonExistentName)"""

  /** Fails only in the refinement channel, after type checking: `1000` does not fit `asByte`'s `where`. */
  private val unmetPrecondition =
    """def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
      |def asByte(x: Int) where withinByte(range(x)): Int = x
      |def main uses Console: Unit = printLine(show(asByte(1000)))""".stripMargin

  /** Fails only in a derived meta's fact (`docs/meta.md` §17.3, F4): `long` does more than the step budget's worth of
    * work in its own body, which its fact reports and declines, and nothing reading it is produced.
    */
  private val outOfBudget = {
    def sums(depth: Int): String = if (depth == 0) "x" else s"add(${sums(depth - 1)}, ${sums(depth - 1)})"
    s"""def long(x: Int): Int = ${sums(9)}
       |def main uses Console: Unit = printLine(show(long(5)))""".stripMargin
  }

  "a failed compilation" should "leave no jar behind" in {
    (compileAndRun(working) >> compileForErrors(broken) >> exists(jarPath)).asserting(_ shouldBe false)
  }

  it should "leave no half-written temporary behind" in {
    (compileForErrors(broken) >> temporaryFiles).asserting(_ shouldBe Seq.empty)
  }

  it should "still report its errors" in {
    compileForErrors(broken).asserting(_ should include("Name not defined."))
  }

  "a failed where precondition" should "leave no jar behind" in {
    (compileAndRun(working) >> compileForErrors(unmetPrecondition) >> exists(jarPath)).asserting(_ shouldBe false)
  }

  "a derivation out of its step budget" should "leave no jar behind" in {
    (compileAndRun(working) >> compileForErrors(outOfBudget) >> exists(jarPath)).asserting(_ shouldBe false)
  }

  "a fixed compilation" should "produce the jar again" in {
    (compileForErrors(broken) >> compileAndRun(working)).asserting(_ shouldBe "working")
  }

  "a jar changed behind the compiler's back" should "be rewritten by an otherwise unchanged build" in {
    // Up-to-dateness is decided by the artefact's content, not by its mere presence: a corrupted jar is still present,
    // and used to be accepted by a build that reported success and rewrote nothing.
    (compileAndRun(working) >> corruptJar >> compileAndRun(working)).asserting(_ shouldBe "working")
  }

  "the same program" should "compile to byte-identical jars" in {
    // Deterministic output is what lets the content digest above settle, and what makes a jar comparable byte for byte
    // against one built from the same sources.
    (compileAndRun(working) >> bytesOf(jarPath).flatMap(first =>
      compileAndRun(other) >> compileAndRun(working) >> bytesOf(jarPath).map(_ === first)
    )).asserting(_ shouldBe true)
  }

  "an unchanged recompilation" should "not rewrite the jar" in {
    // Guards the ordering the artefact invariant rests on: the jar is discarded at the start of a regeneration, so the
    // output-presence dependency must be read *after* the write. Read before, it would record "absent" against a
    // next-run "present" and the jar would regenerate on every build, forever.
    (compileAndRun(working) >> modifiedAt(jarPath).flatMap(before =>
      compileAndRun(working) >> modifiedAt(jarPath).map(_ === before)
    )).asserting(_ shouldBe true)
  }

  private def exists(path: Path): IO[Boolean] = IO.blocking(Files.exists(path))

  private def bytesOf(path: Path): IO[Seq[Byte]] = IO.blocking(Files.readAllBytes(path).toSeq)

  private def corruptJar: IO[Unit] = IO.blocking(Files.writeString(jarPath, "not a jar")).void

  private def modifiedAt(path: Path): IO[Long] = IO.blocking(Files.getLastModifiedTime(path).toMillis)

  private def temporaryFiles: IO[Seq[String]] = IO.blocking {
    val entries = Files.list(jarPath.getParent)
    try entries.iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".tmp")).toVector
    finally entries.close()
  }
}
