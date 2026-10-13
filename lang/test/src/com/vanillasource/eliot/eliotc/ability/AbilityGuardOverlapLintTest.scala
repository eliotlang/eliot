package com.vanillasource.eliot.eliotc.ability

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ability.fact.{AbilityImplementation, ModuleAbilityOverlapCheck}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerFact
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan
import com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibNativesProcessor

import java.net.URI
import java.nio.file.Path

/** Ability-implementation guards Stage 3: the **definition-time overlap check becomes a conservative lint** (§3.2).
  *
  * The soundness rule ("exactly one candidate survives at every manifest use site") is enforced by the Stage-2 use-site
  * discharge; the module-local overlap scan (`ModuleAbilityOverlapCheckProcessor`) is demoted to early author feedback
  * with two of the three §3.2 arms:
  *   - two *unguarded* structurally-overlapping impls → error (exactly the pre-guards behaviour);
  *   - any `where` guard on *either* impl → undecided → deferred silently to the use site.
  *
  * These tests exercise both arms directly by triggering the overlap-check fact, and show the third §3.2 consequence
  * end-to-end: two *guarded* overlapping impls pass the definition-time lint yet, when both guards happen to admit the
  * same concrete use site, record the `Ambiguous` outcome there — which every demanding use site reports as "Multiple
  * ability implementations".
  *
  * The whole scenario lives in the **compiler** source pool (self-contained: `Bool` + `Function`, no effect
  * machinery), mirroring `AbilityGuardDischargeTest` — guards reduce through constructor / native reductions alone.
  */
class AbilityGuardOverlapLintTest
    extends ProcessorTest(
      (LangProcessors(
        systemModules = Seq.empty,
        extraNativeBindingLabels = Seq(StdlibNativesProcessor.stdlibLabel)
      ) :+ StdlibNativesProcessor())*
    ) {

  private def compilerScan(pkg: Seq[String], name: String, content: String): Seq[SourceContent | PathScan] = {
    val path = (pkg :+ s"$name.els").foldLeft(Path.of(""))(_ `resolve` _)
    val uri  = URI.create((pkg :+ s"$name.els").mkString("/"))
    Seq(
      PathScan(path, Seq(uri), Platform.Compiler),
      SourceContent(uri, Sourced(uri, PositionRange.zero, content))
    )
  }

  // The self-contained base every guard needs: `Function` (arrow signatures), `Type`, and the compile-time `Bool`
  // (whose `true`/`false` reductions come from `SystemNativesProcessor` and its `fold` reduction from
  // `StdlibNativesProcessor`). Every synthesized marker's default guard is `eliot.lang.Bool::true`, so `Bool` must
  // always be in the pool.
  private val baseFacts: Seq[CompilerFact] =
    (compilerScan(Seq("eliot", "compiler"), "Type", "type Type") ++
      compilerScan(
        Seq("eliot", "lang"),
        "Function",
        "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B"
      ) ++
      // `fold`'s arms are **suspended**, as the real `eliot.lang.Bool` declares them: the compile-time reduction
      // applies the arm it selects, so a stub with plain arms hands it a value where it expects a thunk and the guard
      // silently stops reducing. A row-typed slot lowers to a thunk `Unit -> A` and the arm is applied to
      // `eliot.lang.Unit::unit`, so both must be loadable, and the `{}` row's synthesised constraint resolves at
      // `eliot.carrier.Effect`.
      compilerScan(
        Seq("eliot", "lang"),
        "Bool",
        "import eliot.lang.Function\ntype Bool\ndef true: Bool\ndef false: Bool\ndef fold[A](cond: Bool, whenTrue uses *: A, whenFalse uses *: A): A"
      ) ++
      compilerScan(Seq("eliot", "lang"), "Unit", "type Unit\ndef unit: Unit") ++
      // Every `ability` block and every effect row declares a binding binder, whose `Implementation[A]` mark and
      // `Default` sentinel the compiler writes module-qualified — so this module is in every pool.
      compilerScan(Seq("eliot", "lang"), "Implementation", ProcessorTest.implementationStubContent) ++
      compilerScan(Seq("eliot", "carrier"), "Effect", "ability Effect[F] { }")).collect { case f: CompilerFact => f }

  private val moduleName = ModuleName(Seq("test"), "M")

  /** A generic `implement[A] Show[A]` and a specific `implement Show[Widget]` in one module, each carrying
    * `guard1`/`guard2` (a `where <expr>` clause or the empty string for an unguarded instance). Their marker patterns
    * structurally overlap (the generic `A` unifies with `Widget`) yet are *distinct* implementations — different
    * `(ability, pattern)` identities that coexist — so the overlap lint has two markers to compare; whether it fires is
    * decided purely by the guards. (Two *identical* `Show[Widget]` patterns would instead be the same identity and
    * collide as a duplicate name, a different diagnostic; the lint is about distinct-but-unifiable patterns.)
    */
  private def moduleContent(guard1: String, guard2: String): String =
    s"""import eliot.lang.Function
       |import eliot.lang.Bool
       |
       |ability Show[A] { def show(x: A): A }
       |
       |data Widget
       |
       |implement[A] Show[A] $guard1 {
       |   def show(x: A): A = x
       |}
       |
       |implement Show[Widget] $guard2 {
       |   def show(x: Widget): Widget = x
       |}
       |""".stripMargin

  private def facts(guard1: String, guard2: String): Seq[CompilerFact] =
    baseFacts ++ compilerScan(Seq("test"), "M", moduleContent(guard1, guard2)).collect { case f: CompilerFact => f }

  private val showVfqn = ValueFQN(moduleName, QualifiedName("show", Qualifier.Ability("Show")))

  private val widgetArg: GroundValue =
    GroundValue.Structure(ValueFQN(moduleName, QualifiedName("Widget", Qualifier.Type)), Seq.empty, GroundValue.Type)

  /** Run only the definition-time overlap lint for `Show` in the test module, returning how many "Overlapping ability
    * implementation" errors it produced.
    */
  private def overlapErrorCount(guard1: String, guard2: String): IO[Int] =
    runGeneratorWithFacts(
      facts(guard1, guard2),
      ModuleAbilityOverlapCheck.Key(moduleName, "Show", Platform.Compiler)
    ).map { case (_, errors) =>
      toTestErrors(errors).count(_.message.contains("Overlapping ability implementation"))
    }

  /** Resolve `Show[Widget]` at the concrete use site, returning the recorded resolution outcome. */
  private def useSiteResolution(guard1: String, guard2: String): IO[Option[AbilityImplementation.Resolution]] =
    runGeneratorWithFacts(facts(guard1, guard2), AbilityImplementation.Key(showVfqn, Seq(widgetArg), Platform.Compiler))
      .map { case (impl, _) => impl.map(_.resolution) }

  "the overlap lint" should "report two unguarded overlapping impls (the pre-guards behaviour)" in {
    overlapErrorCount("", "").asserting(_ shouldBe 2)
  }

  it should "defer a native (unguarded) + guarded pair — no definition-time overlap error" in {
    overlapErrorCount("", "where false").asserting(_ shouldBe 0)
  }

  it should "defer two guarded overlapping impls — no definition-time overlap error" in {
    overlapErrorCount("where fold(false, false, true)", "where fold(false, false, true)").asserting(_ shouldBe 0)
  }

  "two guarded impls whose guards both admit the same use site" should "record the use-site ambiguity" in {
    // The `Ambiguous` outcome is what every demanding use site reports as "Multiple ability implementations".
    useSiteResolution("where fold(false, false, true)", "where fold(false, false, true)")
      .asserting(_ shouldBe Some(AbilityImplementation.Resolution.Ambiguous))
  }
}
