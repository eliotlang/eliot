package com.vanillasource.eliot.eliotc.lsp.index

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.lsp.plugin.LspPlugin
import com.vanillasource.eliot.eliotc.lsp.virtual.VirtualFileSystem
import com.vanillasource.eliot.eliotc.lsp.LspCompileTestLayers
import com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue
import com.vanillasource.eliot.eliotc.plugin.{Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.pos.Position
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** End-to-end proof that a hover reports the refinement channel's computed **value range** for an `Int`-typed node,
  * joined from each instance's [[RefinementTable]] (the same facts codegen reads for representation selection). The
  * compile runs through a real session ([[LspPlugin]] + VFS, LangPlugin, StdlibPlugin; no JVM backend): `LspPlugin`
  * demands `UsedNames(main)` — forcing a [[MonomorphicValue]] per reachable instantiation — *and* the matching
  * [[RefinementTable]] per instance, exactly as the service does. The integer literal `42` seeds the channel's singleton
  * interval `[42, 42]` (α), so hovering it reports that range; a non-pinnable node (the `show` function reference,
  * a ⊤ node) reports none, per the intra-procedural fail-safe (`docs/bounds-as-refinements.md` §6-iii).
  */
class TypeHintRangeCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val line1  = """import eliot.effect.Console"""
  private val line2  = """def main uses Console: Unit = printLine(show(42))"""
  private val source = s"$line1\n$line2"

  private val literalPosition  = Position(2, line2.indexOf("42") + 1)   // inside the `42` literal
  private val functionPosition = Position(2, line2.indexOf("show") + 2) // inside the `show` reference

  private val stringLine         = """def main uses Console: Unit = printLine("hello")"""
  private val stringSource       = s"$line1\n$stringLine"
  private val stringLiteralPosition = Position(2, stringLine.indexOf("\"hello\"") + 2)

  "a hover value range" should "report the singleton interval the channel pins for an integer literal" in {
    rangeAt(literalPosition).asserting(_ shouldBe Some((BigInt(42), BigInt(42))))
  }

  it should "report no range at a node the channel cannot pin (a ⊤ function reference)" in {
    rangeAt(functionPosition).asserting(_ shouldBe None)
  }

  // A string literal seeds the *size* domain (`docs/string-length-meta.md`), whose meta has exactly the same
  // `X$Meta(Interval(..))` shape as an `Int`'s range — so a shape-only decode would report `"hello"` as having
  // the value range `5..5`. A hover renders one domain and must name it; the size is simply not shown (until it has a
  // rendering of its own).
  "a hover value range" should "not report a string's size as if it were a value range" in {
    rangeAt(stringLiteralPosition, of = stringSource).asserting(_ shouldBe None)
  }

  private def rangeAt(position: Position, of: String = source): IO[Option[(BigInt, BigInt)]] =
    withCompiledWorkspace(of) { (uri, index) =>
      index.typeHintsAt(uri, position).flatMap { case (range, _) => index.intervalAt(uri, range) }
    }

  /** Compile a one-file workspace, build the type-hint index from the materialised `MonomorphicValue` + `RefinementTable`
    * facts exactly as the service does, and hand the test the file's URI alongside the index.
    */
  private def withCompiledWorkspace[A](source: String)(body: (URI, TypeHintIndex) => A): IO[A] =
    tempDirectory.use { sourceDir =>
      val file          = sourceDir.resolve("Test.els")
      val lspPlugin     = LspPlugin(new VirtualFileSystem)
      val configuration = LspCompileTestLayers.add(
        Configuration()
          .set(Compiler.targetPathKey, sourceDir.resolve(".eliot-lsp"))
          .set(LangPlugin.pathKey, Seq(sourceDir))
      )
      for {
        _       <- IO.blocking(Files.writeString(file, source))
        session <- CompilationSession.create(
                     lspPlugin,
                     Seq(lspPlugin, LangPlugin(), StdlibPlugin()),
                     configuration
                   )
        result  <- session.compileOnce()
        facts   <- result.generator.currentFacts()
      } yield {
        val monomorphic = facts.values.collect { case value: MonomorphicValue => value }.toSeq
        val refinements = facts.values.collect { case value: RefinementTable => value }.toSeq
        body(file.toUri, TypeHintIndex.build(monomorphic, refinements))
      }
    }

  private def tempDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("eliot-lsp-typehint-range")))(dir =>
      IO.blocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      }
    )
}
