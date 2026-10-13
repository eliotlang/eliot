package com.vanillasource.eliot.eliotc.ability

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation
import com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation.Resolution
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

/** Ability-implementation guards Stage 2: the **use-site discharge** of a `where` guard and its 3-way interpretation.
  *
  * A guard rides the marker's return-type slot (§2.3). When [[com.vanillasource.eliot.eliotc.ability.util.AbilityMatcher]]
  * matches an impl at a concrete type-argument tuple, `AbilityImplementationProcessor` discharges the guard by
  * monomorphizing the marker on the **queried platform's track** (here the compiler track — the pool this scenario
  * lives in) and reading its reduced return value into the fact's [[Resolution]] outcome:
  *   - a `Bool` `true` (or a `Right(true)`) keeps the candidate — the instance resolves ([[Resolution.Resolved]]);
  *   - a `Bool` `false` (or a `Right(false)`) declines — the candidate is dropped, so with no other instance the
  *     outcome is [[Resolution.NoImplementation]];
  *   - a `Left(msg)` records [[Resolution.Rejected]] carrying the author's message.
  *
  * The producer reports **no errors** for these outcomes — a demanding use site (the checker's `AbilityResolver`)
  * reads the outcome and reports there, while a checker-inserted probe (`Coerce`) reads a failed outcome as a silent
  * decline.
  *
  * An unguarded impl (the default `eliot.lang.Bool::true` return slot) keeps verbatim, without monomorphizing the
  * marker at all — the pre-guards behaviour.
  *
  * The whole scenario lives in the **compiler** source pool (self-contained: `Bool` + `Either` + `Function`, no effect
  * machinery), so the guards reduce through constructor / native reductions alone. The guards are hand-built here — the
  * `error`/`E1 != E2` combinators that produce these verdicts in real code are exercised by their own tests
  * (`CompilerAbilityResolutionTest`, `GuardSignatureIntegrationTest`, and Stage 4's `Throw` client).
  */
class AbilityGuardDischargeTest
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

  // The self-contained base every guard needs: `Function` (arrow signatures), `Type`, `String`, the compile-time
  // `Bool` (its `true`/`false` reductions come from `SystemNativesProcessor` and its `fold` reduction from
  // `StdlibNativesProcessor`), and the compile-time `Either` carrier whose `Left`/`Right` constructors the discharge
  // inspects.
  private val baseFacts: Seq[CompilerFact] =
    (compilerScan(Seq("eliot", "compiler"), "Type", "type Type") ++
      compilerScan(
        Seq("eliot", "lang"),
        "Function",
        "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B"
      ) ++
      compilerScan(Seq("eliot", "lang"), "String", "type String") ++
      // Every `ability` block and every effect row declares a binding binder, whose `Implementation[A]` mark and
      // `Default` sentinel the compiler writes module-qualified — so this module is in every pool.
      compilerScan(Seq("eliot", "lang"), "Implementation", ProcessorTest.implementationStubContent) ++
      // A row-typed slot lowers to a thunk `Unit -> A`, and the compile-time `fold` applies the arm it selects to
      // `eliot.lang.Unit::unit` — so both the `Unit` type (the thunk's parameter type) and that value must be
      // loadable, and the `{}` row's synthesised constraint resolves at `eliot.carrier.Effect`.
      compilerScan(Seq("eliot", "lang"), "Unit", "type Unit\ndef unit: Unit") ++
      compilerScan(Seq("eliot", "carrier"), "Effect", "ability Effect[F] { }") ++
      compilerScan(
        Seq("eliot", "lang"),
        "Bool",
        // `fold`'s arms are **suspended**, as the real `eliot.lang.Bool` declares them: the compile-time reduction
        // applies the arm it selects, so a stub with plain arms hands it a value where it expects a thunk and the
        // guard silently stops reducing. The `{}` row synthesises a constraint at `eliot.carrier.Effect`, which is
        // why that module is in the base set below.
        "import eliot.lang.Function\ntype Bool\ndef true: Bool\ndef false: Bool\ndef fold[A](cond: Bool, whenTrue uses *: A, whenFalse uses *: A): A"
      ) ++
      compilerScan(
        Seq("eliot", "lang"),
        "Either",
        "import eliot.lang.Function\ndata Either[E, A] = Left(error: E) | Right(value: A)"
      )).collect { case f: CompilerFact => f }

  private val moduleName = ModuleName(Seq("test"), "M")

  /** The `Show[Widget]` ability + type, plus an `implement Show[Widget]` carrying `guardClause` (a `where <expr>` or the
    * empty string for an unguarded instance). Everything colocated in one module so the implement sits with its ability.
    */
  private def moduleContent(guardClause: String): String =
    s"""import eliot.lang.Function
       |import eliot.lang.Bool
       |import eliot.lang.Either
       |
       |ability Show[A] { def show(x: A): A }
       |
       |data Widget
       |
       |implement Show[Widget] $guardClause {
       |   def show(x: Widget): Widget = x
       |}
       |""".stripMargin

  private def facts(guardClause: String): Seq[CompilerFact] =
    baseFacts ++ compilerScan(Seq("test"), "M", moduleContent(guardClause)).collect { case f: CompilerFact => f }

  /** A **generic** guarded implementation `implement[A] Show[A] where <guard>`: its one leading type parameter `A`
    * binds to the query's argument, so the guard is discharged over that binding — the shape needed to exercise the
    * guard-on-junk fail-safe, since a concrete `implement Show[Widget]` has no leading parameter to junk-ground.
    */
  private def genericModuleContent(guardClause: String): String =
    s"""import eliot.lang.Function
       |import eliot.lang.Bool
       |import eliot.lang.Either
       |
       |ability Show[A] { def show(x: A): A }
       |
       |implement[A] Show[A] $guardClause {
       |   def show(x: A): A = x
       |}
       |""".stripMargin

  private val showVfqn        = ValueFQN(moduleName, QualifiedName("show", Qualifier.Ability("Show")))
  private val widgetArg: GroundValue =
    GroundValue.Structure(ValueFQN(moduleName, QualifiedName("Widget", Qualifier.Type)), Seq.empty, GroundValue.Type)

  private def resolve(guardClause: String): IO[(Option[AbilityImplementation], Seq[TestError])] =
    runGeneratorWithFacts(facts(guardClause), AbilityImplementation.Key(showVfqn, Seq(widgetArg), Platform.Compiler))
      .map { case (impl, errors) => (impl, toTestErrors(errors)) }

  /** Resolve the generic guarded impl at an arbitrary query argument, so a call can pass the **defaulted universe**
    * `GroundValue.Type` in the operand slot (the junk a junk-grounded carrier slot reads back as).
    */
  private def resolveGeneric(
      guardClause: String,
      queryArg: GroundValue
  ): IO[(Option[AbilityImplementation], Seq[TestError])] =
    runGeneratorWithFacts(
      baseFacts ++ compilerScan(Seq("test"), "M", genericModuleContent(guardClause)).collect { case f: CompilerFact =>
        f
      },
      AbilityImplementation.Key(showVfqn, Seq(queryArg), Platform.Compiler)
    ).map { case (impl, errors) => (impl, toTestErrors(errors)) }

  private def resolvedName(guardClause: String): IO[Option[String]] =
    resolve(guardClause).map { case (impl, _) => impl.flatMap(_.resolution.resolved).map(_._1.name.name) }

  private def resolution(guardClause: String): IO[Option[Resolution]] =
    resolve(guardClause).map { case (impl, _) => impl.map(_.resolution) }

  "an unguarded implementation" should "resolve (the default `true` return slot, no discharge)" in {
    resolvedName("").asserting(_ shouldBe Some("show"))
  }

  "a guard reducing to `true`" should "keep the candidate — the instance resolves" in {
    resolvedName("where fold(false, false, true)").asserting(_ shouldBe Some("show"))
  }

  "a guard reducing to `false`" should "decline — the outcome is `NoImplementation`" in {
    resolution("where false").asserting(_ shouldBe Some(Resolution.NoImplementation))
  }

  it should "produce no error in the producer — reporting a failed demand belongs to the use site" in {
    resolve("where false").asserting { case (_, errors) => errors shouldBe Seq.empty }
  }

  "a guard `Right(true)`" should "keep the candidate — the success payload is `true`" in {
    resolvedName("where Right(true)").asserting(_ shouldBe Some("show"))
  }

  "a guard `Right(false)`" should "decline — the success payload is `false`" in {
    resolution("where Right(false)").asserting(_ shouldBe Some(Resolution.NoImplementation))
  }

  "a guard `Left(msg)`" should "record a `Rejected` outcome carrying the author's message" in {
    resolution("""where Left("Widget is not showable here")""")
      .asserting(_ shouldBe Some(Resolution.Rejected(Seq("Widget is not showable here"))))
  }

  it should "produce no error in the producer when the guard rejects — the demander reports the message" in {
    resolve("""where Left("nope")""").asserting { case (_, errors) => errors shouldBe Seq.empty }
  }

  // ---- The guard-on-junk fail-safe (docs/effects-as-channel.md, finding 13 / step 2 of the §7 sequence) ----

  "the generic guarded impl at a genuine type argument" should "discharge the guard normally and resolve" in {
    // The baseline: `where fold(false, false, true)` reduces to `true` over the real operand `A := Widget`, so the
    // guarded candidate is kept.
    resolveGeneric("where fold(false, false, true)", widgetArg)
      .map { case (impl, _) => impl.flatMap(_.resolution.resolved).map(_._1.name.name) }
      .asserting(_ shouldBe Some("show"))
  }

  "a guarded impl whose operand junk-grounded to the universe" should "decline rather than reduce the guard over junk" in {
    // The fail-safe: `A := Type` (the defaulted universe — what a junk-grounded carrier slot reads back as). Without
    // it, `fold(false, false, true)` would still reduce to `true` and silently keep the candidate on a junk operand;
    // with it, the guard is not discharged and the outcome falls through to `NoImplementation`, which the demanding
    // use site reports located — never a silent wrong instance choice.
    resolveGeneric("where fold(false, false, true)", GroundValue.Type)
      .map { case (impl, _) => impl.map(_.resolution) }
      .asserting(_ shouldBe Some(Resolution.NoImplementation))
  }

  it should "produce no error in the producer — the junk decline surfaces at the demanding use site" in {
    resolveGeneric("where fold(false, false, true)", GroundValue.Type)
      .asserting { case (_, errors) => errors shouldBe Seq.empty }
  }

  "an UNguarded generic impl at the universe" should "still resolve (the fail-safe is guard-specific)" in {
    // The fail-safe fires only for a real `where` guard: an unguarded generic impl keeps verbatim at any argument,
    // including the universe, exactly as before — the junk decline is scoped to guard discharge, not all resolution.
    resolveGeneric("", GroundValue.Type)
      .map { case (impl, _) => impl.flatMap(_.resolution.resolved).map(_._1.name.name) }
      .asserting(_ shouldBe Some("show"))
  }
}
