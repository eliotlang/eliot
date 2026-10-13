package com.vanillasource.eliot.eliotc

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.resolve.fact.{
  QualifiedName as ResolveQualifiedName,
  Qualifier as ResolveQualifier
}
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.module.fact.ModuleName
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.common.SequentialCompilerProcessors
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey, CompilerProcessor}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.Path

abstract class ProcessorTest(val processors: CompilerProcessor*) extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  val file           = URI.create("Test.els")
  val testModuleName = ModuleName(Seq.empty, "Test")
  val sourceContent  = SourceContent(file, Sourced(file, PositionRange.zero, "test source"))

  /** The canonical module environment every test runs in: one stub per `ModuleName.defaultSystemModules` entry (so the
    * auto-imports resolve), plus modules that are *registered (loadable) but not auto-imported* — the import-required
    * effects `Console`/`Log`/`Dep` (in `effectPackage`; a snippet that prints must `import eliot.effect.Console`), and
    * the modules the resolver/ability-checker loads by *fixed FQN* rather than by import: the desugaring-machinery
    * `PatternMatch`/`TypeMatch` (in `compilerInternalPackage`) and `Type` (in `compilerPackage`, reached via the
    * resolver's bare-name special-case → `WellKnownTypes.typeFQN`). This is the SINGLE place the module set and each
    * module's package live. A test that needs richer content for a few modules calls [[ambientStubsWith]] to override
    * just those, instead of re-listing the whole set — so relocating a module, or adding/removing one, touches only
    * this list.
    */
  val systemImports = Seq(
    SystemImport(
      "Function",
      "type Function[A, B]\ninfix right type =>[A, B] = Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B"
    ),
    SystemImport("Type", "type Type", ModuleName.compilerPackage),
    SystemImport("BigInteger", "type BigInteger"),
    SystemImport("Unit", "type Unit"),
    SystemImport("String", "type String"),
    // `IO` carries the jvm layer's run boundary `runMain`, so a suite that registers it (see
    // `LangProcessors(runBoundaryFunctions = …)`) finds the value declaring the platform carrier head.
    SystemImport("IO", "type IO\ndef runMain[A](io: IO[A]): A", Seq("eliot", "jvm")),
    SystemImport("PatternMatch", "", ModuleName.compilerInternalPackage),
    SystemImport("TypeMatch", "", ModuleName.compilerInternalPackage),
    SystemImport("Int", ProcessorTest.intStubContent),
    SystemImport("Runtime", ProcessorTest.runtimeStubContent),
    // The rest of the auto-imported `eliot.lang` prelude. Each is a *minimal* default (a type/ability head plus its
    // single primitive) — enough for auto-import to find the module and resolve the bare type/ability name in every
    // snippet. Tests that exercise the derived surface (`+`/`-`/`*`, `<`/`min`/`max`, `==`, `some`/`fold`, …) enrich
    // just those modules via [[ambientStubsWith]], which overrides these defaults.
    // The ability-constraint combinator `&` is an ordinary prelude declaration the resolver looks up by name
    // (`docs/effects-syntax-userspace.md` §4 stage 1), so every snippet writing `A ~ X & Y` needs it in scope.
    SystemImport("Ability", "infix left type &[A, B]"),
    // The `Default` binding sentinel. Effects v6 writes it as an ordinary type argument at every reference whose
    // implementation nothing names, and saturation then demands the value it names — so a snippet calling *any*
    // ability method needs this module present, exactly as the real `stdlib/eliot/src/eliot/lang/Implementation.els` is.
    SystemImport("Implementation", ProcessorTest.implementationStubContent),
    SystemImport("Bool", ProcessorTest.boolImportContent),
    SystemImport("Numeric", ProcessorTest.numericStubContent),
    SystemImport("Compare", ProcessorTest.compareAbilityStubContent),
    SystemImport("Show", ProcessorTest.showAbilityStubContent),
    SystemImport("Eq", ProcessorTest.eqAbilityStubContent),
    SystemImport("Combine", ProcessorTest.combineAbilityStubContent),
    SystemImport("Option", ProcessorTest.optionStubContent),
    SystemImport("Either", "type Either[E, A]"),
    SystemImport("Pair", "type Pair[A, B]"),
    SystemImport("Interval", "type Interval[T]"),
    SystemImport("Bound", "type Bound[T]"),
    SystemImport("Console", ProcessorTest.consoleStubContent, ModuleName.effectPackage),
    SystemImport("Log", ProcessorTest.logStubContent, ModuleName.effectPackage),
    SystemImport("Dep", ProcessorTest.depStubContent, ModuleName.effectPackage),
    SystemImport("Abort", ProcessorTest.abortStubContent, ModuleName.effectPackage),
    SystemImport("Throw", ProcessorTest.throwStubContent, ModuleName.effectPackage),
    SystemImport("State", ProcessorTest.stateStubContent, ModuleName.effectPackage),
    SystemImport("Inf", ProcessorTest.infStubContent, ModuleName.effectPackage),
    SystemImport("Writer", ProcessorTest.writerStubContent, ModuleName.effectPackage)
  )

  /** The canonical [[systemImports]] with the named modules' content replaced — existing entries keep their package (so
    * e.g. `"PatternMatch" -> realDecl` stays in `compilerInternalPackage`), and any name not already present is added
    * as a new `eliot.lang` stub. Lets a test enrich only the modules it exercises (a richer `Int`, a real
    * `PatternMatch` ability, an extra `Bool`/`Option`) without restating the rest of the prelude.
    */
  def ambientStubsWith(overrides: (String, String)*): Seq[SystemImport] = {
    val overrideMap = overrides.toMap
    systemImports.map(s => overrideMap.get(s.module).fold(s)(c => s.copy(content = c))) ++
      overrides.collect {
        case (name, content) if !systemImports.exists(_.module == name) => SystemImport(name, content)
      }
  }

  def sourced[T](value: T): Sourced[T] = Sourced(file, PositionRange.zero, value)

  def default(name: String): QualifiedName = QualifiedName(name, Qualifier.Default)

  def toResolve(qn: QualifiedName): ResolveQualifiedName =
    ResolveQualifiedName(
      qn.name,
      qn.qualifier match {
        case Qualifier.Default    => ResolveQualifier.Default
        case Qualifier.Type       => ResolveQualifier.Type
        case Qualifier.Ability(n) => ResolveQualifier.Ability(n)
        case _                    => throw IllegalArgumentException("Cannot convert AbilityImplementation in test helper")
      }
    )

  def createGenerator(facts: Seq[CompilerFact]): IO[IncrementalFactGenerator] =
    for {
      generator <- IncrementalFactGenerator.create(SequentialCompilerProcessors(processors), None)
      _         <- generator.registerFact(sourceContent)
      _         <- facts.traverse_(generator.registerFact)
    } yield generator

  def runGeneratorWithFacts[K <: CompilerFact](
      facts: Seq[CompilerFact],
      trigger: CompilerFactKey[K]
  ): IO[(Option[K], Seq[CompilerError])] =
    for {
      generator <- createGenerator(facts)
      result    <- generator.getFact(trigger)
      errors    <- generator.currentErrors()
    } yield (result, errors)

  def runGenerator(
      source: String,
      trigger: CompilerFactKey[? <: CompilerFact],
      imports: Seq[SystemImport] = Seq.empty
  ): IO[(Seq[CompilerError], Map[CompilerFactKey[?], CompilerFact])] =
    for {
      generator <- IncrementalFactGenerator.create(SequentialCompilerProcessors(processors), None)
      _         <- generator.registerFact(SourceContent(file, Sourced(file, PositionRange.zero, source)))
      // Register each source under **both** platform pools: the runtime pool (the value monos) and the compiler pool.
      // Since signature-unification C1/C2 the value mono reads its signature twin (`CompilerMonomorphicValue(v@Signature)`)
      // **mandatorily**, and the compiler pool borrows the whole runtime track in a real build (`PathScanner`), so the
      // harness must mirror that — otherwise the twin never produces and the mandatory read aborts.
      _         <- generator.registerFact(PathScan(Path.of("Test.els"), Seq(file)))
      _         <- generator.registerFact(PathScan(Path.of("Test.els"), Seq(file), Platform.Compiler))
      _         <- imports.traverse { imp =>
                     val modulePath = imp.moduleName.toPath
                     val impFile    = URI.create(modulePath.toString)
                     generator.registerFact(PathScan(modulePath, Seq(impFile))) >>
                       generator.registerFact(PathScan(modulePath, Seq(impFile), Platform.Compiler)) >>
                       generator.registerFact(SourceContent(impFile, Sourced(impFile, PositionRange.zero, imp.content)))
                   }
      _         <- generator.getFact(trigger)
      facts     <- generator.currentFacts()
      errors    <- generator.currentErrors()
    } yield (errors, facts)

  /** A source stub for a system module the harness pre-registers (its `.els` path is derived from [[moduleName]] via
    * the shared [[ModuleName.toPath]] layout). `packages` defaults to the `eliot.lang` prelude; relocated modules (e.g.
    * `PatternMatch`/`TypeMatch` in [[ModuleName.compilerInternalPackage]]) are declared once in the canonical
    * [[systemImports]], so tests reach them through [[ambientStubsWith]] and never repeat the package.
    */
  case class SystemImport(module: String, content: String, packages: Seq[String] = ModuleName.defaultSystemPackage) {
    def moduleName: ModuleName = ModuleName(packages, module)
  }

  case class TestError(message: String, highlight: String)

  extension (message: String) infix def at(highlight: String): TestError = TestError(message, highlight)

  def toTestErrors(errors: Seq[CompilerError]): Seq[TestError] =
    errors.map { error =>
      val lines     = error.content.linesIterator.toSeq
      val from      = error.sourceRange.from
      val to        = error.sourceRange.to
      val highlight =
        if (from.line == to.line) lines(from.line - 1).substring(from.col - 1, to.col - 1)
        else {
          val firstLine   = lines(from.line - 1).substring(from.col - 1)
          val lastLine    = lines(to.line - 1).substring(0, to.col - 1)
          val middleLines = lines.slice(from.line, to.line - 1)
          (firstLine +: middleLines :+ lastLine).mkString("\n")
        }
      TestError(error.message, highlight)
    }
}

object ProcessorTest {

  /** Declarations for the built-in opaque `Bool` type and its compile-time predicates, mirroring
    * `lang/eliot/src/eliot/lang/Bool.els`. The reductions are supplied by `SystemNativesProcessor`.
    */
  /** `eliot.lang.Implementation`, which every snippet declaring an effect row, a `~` constraint or an `ability` block
    * needs — the compiler writes both of its names into such a declaration, module-qualified, so neither depends on
    * the snippet's imports. `Default` is the sentinel a reference carries when no `with` names an implementation (and
    * saturation then demands the value it names); `Implementation[A]` is the **mark** a binding binder carries as its
    * declared type (`docs/effects.md` §9.2). A pool without this module fails with "Qualified named value not
    * available." at the declaration, so it is part of every stub prelude, exactly as the real
    * `stdlib/eliot/src/eliot/lang/Implementation.els` is part of every layer.
    */
  val implementationStubContent: String = "type Default\ntype Implementation[A] = Type"

  val boolImportContent: String =
    "type Bool\ndef true: Bool\ndef false: Bool\ninfix def &&(a: Bool, b: Bool): Bool"

  /** Minimal defaults for the rest of the auto-imported `eliot.lang` prelude. Each declares only the type/ability head
    * and its single primitive — the whole prelude is auto-imported into every module, so these must be self-consistent
    * (they reference no other prelude name beyond `Bool`, which is itself ambient) and export a small, collision-free
    * name set. Tests exercising the derived surface override the relevant module via [[ambientStubsWith]] (e.g. the
    * richer [[compareStubContent]] with `<`/`min`/`max`, or an `Arithmetic`/operator-carrying `Numeric`).
    */
  val numericStubContent: String =
    "ability Numeric[A] { def add(a: A, b: A): A\n def subtract(a: A, b: A): A\n def multiply(a: A, b: A): A }"

  val compareAbilityStubContent: String = "ability Compare[A] { def lessThanOrEqual(a: A, b: A): Bool }"

  val showAbilityStubContent: String = "ability Show[A] { def show(value: A): String }"

  val eqAbilityStubContent: String = "ability Eq[A] { def equals(a: A, b: A): Bool }"

  /** The `Combine` ability stub, mirroring the real `eliot.lang.Combine` (a monoid — the `++` operator delegates to
    * `combine`, and `empty` is the identity). Minimal: the ability head with its two primitives; the `String` reduction
    * is supplied by `StdlibNativesProcessor`/the jvm backend under the `Combine[String]::combine` impl method, and
    * tests exercising the `++` operator itself enrich this module via [[ambientStubsWith]].
    */
  val combineAbilityStubContent: String = "ability Combine[A] { def combine(a: A, b: A): A\n def empty: A }"

  val optionStubContent: String = "type Option[A]\ndef none[A]: Option[A]"

  /** The `Compare` ability stub, mirroring the real `eliot.lang.Compare`: `lessThanOrEqual` is the single primitive
    * (its `BigInteger` reduction is supplied by `StdlibNativesProcessor` under the ability-method FQN), `min`/`max` are
    * derived, and `BigInteger` implements it with a body-less method (the native attaches to the implementation). The
    * `Int` environment resolves `lessThanOrEqual`/`min`/`max` through this ability rather than through plain
    * `BigInteger` defs. Requires a `fold`-carrying `Bool` stub for `min`/`max` (the `intImports` Bool override provides
    * one).
    */
  val compareStubContent: String =
    "import eliot.lang.Bool\n" +
      "ability Compare[A] { def lessThanOrEqual(a: A, b: A): Bool }\n" +
      "def min[A ~ Compare](a: A, b: A): A = fold(lessThanOrEqual(a, b), a, b)\n" +
      "def max[A ~ Compare](a: A, b: A): A = fold(lessThanOrEqual(a, b), b, a)\n" +
      "implement Compare[BigInteger] { def lessThanOrEqual(a: BigInteger, b: BigInteger): Bool }"

  /** The `Arithmetic` ability stub, mirroring the real `eliot.lang.Arithmetic`: `add`/`subtract`/`multiply` are the
    * primitives (their `BigInteger` reductions are supplied by `StdlibNativesProcessor` under the ability-method FQNs),
    * and `BigInteger` implements them body-less (the natives attach to the implementation). `Arithmetic` is
    * heterogeneous — two operand types `A`/`B` with a per-operation result type — so each `BigInteger` result is the
    * associated type `AddResult`/`SubResult`/`MulResult` = `BigInteger`. The generic `+`/`-`/`*` operators are defined
    * *on top of* this ability (`def +[X, Y ~ Arithmetic[X, Y]](...): AddResult = add(...)` in the real `Arithmetic`
    * module) rather than per type — this minimal stub omits them, and tests needing the operators declare their own.
    * (`multiplyMin`/ `multiplyMax`, the `*` corner-product bounds, stay plain `BigInteger` defs — see the `intImports`
    * `BigInteger` stub.)
    */
  val arithmeticStubContent: String =
    "ability Arithmetic[A, B] { type AddResult\n type SubResult\n type MulResult\n def add(a: A, b: B): AddResult\n def subtract(a: A, b: B): SubResult\n def multiply(a: A, b: B): MulResult }\n" +
      "implement Arithmetic[BigInteger, BigInteger] { type AddResult = BigInteger\n type SubResult = BigInteger\n type MulResult = BigInteger\n def add(a: BigInteger, b: BigInteger): AddResult\n def subtract(a: BigInteger, b: BigInteger): SubResult\n def multiply(a: BigInteger, b: BigInteger): MulResult }"

  /** Minimal ambient `Int`/`Runtime` stubs. As of the Phase-6 literal desugar every value-position integer literal `n`
    * is rewritten to `integerLiteral[n] : Int[n, n]`, so `Int` and `Runtime` are in `defaultSystemModules` (always
    * auto-imported) and the test harness must register matching stubs. These minimal versions only declare the abstract
    * `Int` type and the `integerLiteral` constructor; the richer `Coerce`/arithmetic environment lives in the `Int`
    * tests' own import lists.
    */
  /** The package holding desugaring machinery relocated out of the `eliot.lang` prelude (`PatternMatch`/`TypeMatch`).
    * Re-exported here so the few tests that build a custom (non-`ambientStubsWith`) import set can register those stubs
    * at the FQN the resolver/ability-checker loads. Most tests never need it — `ambientStubsWith` preserves the
    * package.
    */
  val compilerInternalPackage: Seq[String] = ModuleName.compilerInternalPackage

  /** The package holding the compiler-coordinated ability the checker resolves by FQN — `Coerce` (check-mode widening).
    * Re-exported so the `Int` tests can register that stub at the FQN `WellKnownTypes` loads. Deliberately *not* in the
    * bare ambient prelude: a type-mismatch test resolves `coerceFQN`, which must find no module (clean mismatch) unless
    * the test opts into the full `Int` environment.
    */
  val compilerPackage: Seq[String] = ModuleName.compilerPackage

  /** The `eliot.effect` package holding the effect surface + machinery. Re-exported so the few tests that build a
    * custom (non-`ambientStubsWith`) import set can register the ambient `Console`/`Log`/`Dep` stubs at their real FQN.
    * Most tests never need it — `ambientStubsWith` preserves each module's package automatically.
    */
  val effectPackage: Seq[String] = ModuleName.effectPackage

  // A two-parameter `Int` used by many monomorphization tests as an ordinary multi-argument type constructor (the tests
  // predate production's nullary `Int {range: Interval[BigInteger]}`, whose bounds live in the refinement channel). The
  // parameters are ordinary explicit generics — the retired `auto` implicit-generics marker is gone.
  val intStubContent: String     = "type Int[MIN: BigInteger, MAX: BigInteger]"
  val runtimeStubContent: String = "def integerLiteral[V: BigInteger]: Int[V, V]"

  /** The real `PatternMatch`/`TypeMatch` ability declarations (the canonical `systemImports` register them empty, since
    * they are loaded by FQN only when a snippet has a `data`/`match`). Tests exercising `match` or field access enrich
    * them via `ambientStubsWith("PatternMatch" -> patternMatchAbilityStub, "TypeMatch" -> typeMatchAbilityStub)`.
    */
  val patternMatchAbilityStub: String =
    "ability PatternMatch[T] {\ntype Cases[R]\ndef handleCases[R](value: T, cases: Cases[R]): R\n}"
  val typeMatchAbilityStub: String    =
    "ability TypeMatch[T] {\ntype Fields[R]\ndef typeMatch[R](value: Type, matched: Fields[R], notMatched: Function[Unit, R]): R\n}"

  /** `Console` effect stub, mirroring `stdlib/eliot/src/eliot/effect/Console.els`. The whole `eliot.effect` package is
    * ambient (auto-imported in the weak prelude tier), so every full-prelude test carries this. The default
    * implementation lives in the real jvm layer, not here — a stub declares the effect, never how it runs.
    */
  val consoleStubContent: String =
    "effect Console {\ndef printLine(s: String): Unit\ndef readLine: String\n}"

  /** `Log` effect stub, mirroring `stdlib/eliot/src/eliot/effect/Log.els` (ambient — see [[consoleStubContent]]); the
    * concrete JVM instance lives in the real jvm layer.
    */
  val logStubContent: String = "effect Log {\ndef log(s: String): Unit\n}"

  /** `Dep` effect stub, mirroring `stdlib/eliot/src/eliot/effect/Dep.els` (ambient — see [[consoleStubContent]]); this is
    * just the reader operation (the `ask`) — the `provide` discharge lives in the jvm layer.
    */
  val depStubContent: String = "effect Dep[X] {\ndef dependency: X\n}"

  /** `Abort`/`Throw`/`State`/`Inf` effect stubs, mirroring their `stdlib/eliot/src/eliot/effect/` declarations (ambient —
    * see [[consoleStubContent]]): the bare `effect` head + its operations, no dischargers. Tests exercising a
    * discharge enrich the module via `ambientStubsWith`.
    */
  val abortStubContent: String  = "effect Abort {\ndef abort[A]: A\n}"
  val throwStubContent: String  = "effect Throw[E] {\ndef raise[A](err: E): A\n}"
  val stateStubContent: String  = "effect State[S] {\ndef state: S\ndef putState(s: S): Unit\n}"
  val infStubContent: String    = "effect Inf {\ndef forever(step uses *: Unit): Unit\n}"
  val writerStubContent: String = "effect Writer[W] {\ndef tell(w: W): Unit\n}"

  /** The *legacy* ambient prelude a self-contained checker/monomorphize unit test relies on: value application
    * (`Function`), the primitive opaque types (`Unit`/`String`/`BigInteger`), and the Phase-6 literal desugar's
    * `Int`/`Runtime`. Production auto-imports the *whole* `eliot.lang` prelude (see `ModuleName.defaultSystemModules`),
    * but these bespoke tests build the rest of their type world (`Bool`/`Option`/`Either`/`Numeric`/`Compare`/…) inline
    * or via explicit stubs that would otherwise double-import (shadow) against the full prelude. They therefore pin
    * this smaller set so their hand-built environments stand unchanged. (`IO` is not ambient anywhere anymore — the
    * carrier is the platform-owned `eliot.jvm.IO`, import-required.) The expanded auto-import is exercised end-to-end
    * by the examples + the jvm `ExamplesIntegrationTest`s.
    */
  val coreAmbientModules: Seq[ModuleName] =
    Seq("Function", "Unit", "String", "BigInteger", "Int", "Runtime")
      .map(ModuleName(ModuleName.defaultSystemPackage, _))

  /** [[coreAmbientModules]] minus the Phase-6 ambient `Int`/`Runtime`. Tests that use `Int`/`integerLiteral` as a
    * *local* declaration name (and never write a value-position integer literal) pass this to `ModuleValueProcessor` so
    * the ambient versions do not shadow their local ones.
    */
  val systemModulesWithoutInt: Seq[ModuleName] =
    coreAmbientModules.filterNot(m => Set("Int", "Runtime").contains(m.name))
}
