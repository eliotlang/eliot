package com.vanillasource.eliot.eliotc.lsp.index

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import com.vanillasource.eliot.eliotc.lsp.plugin.LspPlugin
import com.vanillasource.eliot.eliotc.lsp.virtual.VirtualFileSystem
import com.vanillasource.eliot.eliotc.lsp.LspCompileTestLayers
import com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValueRenderer, MonomorphicValue}
import com.vanillasource.eliot.eliotc.plugin.{Configuration, LangPlugin}
import com.vanillasource.eliot.eliotc.pos.Position
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibPlugin
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** End-to-end proof that hovering reports the *concrete monomorphic* type of an expression node, driven by the same
  * per-file `main` monomorphization the service uses. The compile runs through a real session (LspPlugin + VFS,
  * LangPlugin, StdlibPlugin; no JVM backend); [[LspPlugin]] demands `UsedNames(main)`, which forces a
  * [[MonomorphicValue]] for every reachable instantiation, and the index is built from those facts exactly as the
  * service builds it. `greeting`'s body exercises a string literal and a call; `main`'s body exercises a whole-value
  * reference whose type is the value's signature; `guarded` exercises a computation *captured* by a discharger, which
  * effects v6 renders as the thunk it is; and `parsed` / `sign` exercise discharge under a pure return.
  *
  * The machinery sweeps stay, and are the reason the suite exists: nothing the checker inserts may reach a user. Under
  * v6 there is no carrier machinery left to insert, so they are a standing net rather than a live hazard — which is
  * exactly when a net is worth keeping.
  */
class TypeHintIndexCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val imports     = "import eliot.effect.Console\nimport eliot.effect.Throw"
  private val line1       = """def greeting uses Console: Unit = printLine("Hello World!")"""
  private val guardedLine = """def guarded uses Throw[String]: String = raise("nope")"""
  private val mainLine    = """   greeting"""
  private val catchLine   = """   printLine(guarded catch (e -> e))"""
  private val pureLine    = """   printLine(parsed)"""
  // A discharge-to-pure: `parsed` has no effect row, so this instantiation of `guarded` runs on the identity carrier
  // and the checker inserts `Id` machinery (a `runId` / `pure@Effect[Id]` node) at these very source ranges.
  private val parsedLine  = """def parsed: String = guarded catch (e -> e)"""
  private val signLine    = """def sign(f: Bool): String = if(f, "+") else "-""""
  private val signUseLine = """   printLine(sign(true))"""
  private val source      =
    s"$imports\n$line1\n$guardedLine\ndef main uses Console: Unit = {\n$mainLine\n$catchLine\n$pureLine\n$signUseLine\n}\n$parsedLine\n$signLine"

  // `printLine`/`raise` are import-required (`Console`/`Throw` live in `eliot.effect`, not auto-imported), so the two
  // import lines push `greeting` to line 3, `guarded` to line 4 and `main`'s block body to lines 6–7.
  private val stringPosition    = Position(3, line1.indexOf("Hello") + 1)       // inside the "Hello World!" literal
  private val printLinePosition = Position(3, line1.indexOf("printLine") + 4)   // well inside the `printLine` reference
  private val greetingPosition  = Position(6, mainLine.indexOf("greeting") + 2) // inside the `greeting` reference in `main`
  private val guardedPosition   = Position(7, catchLine.indexOf("guarded") + 2) // inside the `guarded` reference under `catch`
  private val keywordPosition   = Position(3, 1)                                // the `def` keyword — no expression node
  private val parsedLineNumber  = 11                                            // `parsed`, after `main`'s closing brace
  private val signLineNumber    = 12                                            // `sign`, the if..else discharge-to-pure
  private val pureGuardedPosition = Position(parsedLineNumber, parsedLine.indexOf("guarded") + 2)

  "type hints" should "report the concrete type of a string literal" in {
    renderedTypesAt(stringPosition).asserting(_ shouldBe Seq("String"))
  }

  it should "report the monomorphic function type at a value reference" in {
    renderedTypesAt(printLinePosition).asserting(_ shouldBe Seq("String -> Unit"))
  }

  // The reference sits on a block statement line, so the block's lowering (a tower of immediately-applied lambdas)
  // contributes co-located application nodes; the value's own hint is among them.
  it should "report a whole value's type at a reference to it" in {
    renderedTypesAt(greetingPosition).asserting(_ should contain("Unit"))
  }

  // A computation delivered to a discharger's slot is a **thunk** under effects v6 — the row lowers to `Unit -> A` —
  // and that is what hover shows. There is no carrier stack to render as a pinned row any more, and no inverter to
  // render it with; what the user sees is the type the program actually has.
  it should "render a captured computation as the thunk it is" in {
    renderedTypesAt(guardedPosition).asserting(_ should contain("Unit -> String"))
  }

  // The same capture under a *pure* return reads identically, which is the point: discharge is a runtime frame now,
  // so nothing about the enclosing definition's own row changes the type at this position.
  it should "render the same thunk for a capture under a pure return" in {
    renderedTypesAt(pureGuardedPosition).asserting(_ should contain("Unit -> String"))
  }

  /** The effects-as-channel §9 gate: hover never shows carrier machinery. Swept over *every* position of the
    * discharge-to-pure definition, because the machinery the checker inserts there (`runId`, `pure@Effect[Id]`, an
    * `Id[String]`-typed node) sits at the user's own ranges — which is why the index Id-normalizes its input rather
    * than relying on rendering alone.
    */
  it should "never surface carrier machinery or an Id payload wrapper anywhere in a discharge-to-pure definition" in {
    renderedTypesAcross(parsedLineNumber, parsedLine.length).asserting(_.filter(machinery) shouldBe Seq.empty)
  }

  it should "keep an if..else discharged into a pure return free of carrier machinery too" in {
    renderedTypesAcross(signLineNumber, signLine.length).asserting(_.filter(machinery) shouldBe Seq.empty)
  }

  // The same sweep, positively: the computation `else` discharges *is* present at that line, as the thunk it lowers
  // to. Without this the machinery assertion above would also pass on a line the index simply failed to cover.
  it should "render the discharged computation of an if..else at the line it is written" in {
    renderedTypesAcross(signLineNumber, signLine.length).asserting(_ should contain("Unit -> String"))
  }

  it should "report nothing where there is no expression node" in {
    typesAt(keywordPosition).asserting(_ shouldBe None)
  }

  private def renderedTypesAt(position: Position): IO[Seq[String]] =
    typesAt(position).map(_.fold(Seq.empty[String])(_._2.map(GroundValueRenderer.render)))

  /** Carrier machinery that must never reach a user: a carrier type name, or the identity carrier's payload wrapper. */
  private def machinery(rendered: String): Boolean = rendered.contains("Carrier") || rendered.contains("Id[")

  /** Every distinct type rendered at any column of one source line. */
  private def renderedTypesAcross(line: Int, columns: Int): IO[Seq[String]] =
    withCompiledWorkspace { (uri, index) =>
      (1 to columns)
        .flatMap(column => index.typeHintsAt(uri, Position(line, column)).toSeq.flatMap(_._2))
        .distinct
        .map(GroundValueRenderer.render)
    }

  private def typesAt(position: Position) =
    withCompiledWorkspace((uri, index) => index.typeHintsAt(uri, position))

  /** Compile a one-file workspace, build the type-hint index from the materialised facts, and hand the test the file's
    * URI alongside the index.
    */
  private def withCompiledWorkspace[A](body: (URI, TypeHintIndex) => A): IO[A] =
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
    Resource.make(IO.blocking(Files.createTempDirectory("eliot-lsp-typehint")))(dir =>
      IO.blocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete)
      }
    )
}
