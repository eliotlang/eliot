# Claude Memory

## Project Overview

ELIOT is a functional, generic programming language for microcontrollers, implemented in Scala 3. This repo
contains the whole compiler and the ELIOT standard library. The compiler is plugin-based over a fact-based
compilation system, with multiple backends (currently JVM).

## Build System

**Mill** (1.1.0+), Scala **3.7.4**. Key deps: `cats-effect`, `parsley-cats` (parsers), `ASM 9.9` (jvm module).

```bash
./mill __.compile                  # compile all modules
./mill __.test                     # test all modules
./mill lang.test                   # one module (also jvm.test, eliotc.test, ide.lsp.test)

# Filter test output by class name (the -- flag is rejected by ScalaTest)
./mill lang.test 2>&1 | grep -v DEBUG | grep "ClassName"

# Compile an example to an executable jar (lands in target/HelloWorld.jar), then run it
./mill examples.run jvm exe-jar examples/src/ -m HelloWorld
java -jar target/HelloWorld.jar

# The same, then run it: the compiler exits with the program's exit code. The backend word may be left
# out when exactly one backend on the classpath accepts the mode (`Compiler.withDefaultBackend`), which
# is how a platform-independent package's `compiler run -m …` line in `eliot.pkg` works.
./mill examples.run run -m HelloWorld examples/src/
```

**The CLI is a compatibility promise now.** A package's `compiler <words…>` line in `eliot.pkg` is
passed to whatever compiler MVS selects, so a mode word or a flag is renamed the way a public function
is — on a new line, never within `v0` (eliot.build's `docs/build-system.md`, "What a build runs").
Descriptors name assets with a bare `asset <name>` clause; nothing marks which asset is the compiler or
which word a backend answers to, because the launcher puts every asset on one classpath and the
backend registers its own word (`CompilerPlugin.backendWord`/`backendModes`).

**Program arguments follow a `--`.** `run -m Main src/ -- a b` starts `Main` with `Environment.arguments` = `a b`:
`Compiler.runCompiler` cuts the line at the first `--` *before* scopt parses it (the unbounded positional `<path>...`
would otherwise take them), and the rest — verbatim, a second `--` and `-o`-looking words included — travels in the
diagnostic `Compiler.programArgumentsKey`, so it never enters the cache identity. Only `run` passes them; `exe-jar`
refuses them (`JvmPlugin`'s `checkConfig`), because arguments it dropped would look honoured. That `--` is what a
test runner's filter rides (`eliot.test.Runner`); how a build tool or `eliotw` forwards its own trailing words to the
compiler line is the build system's side and is not decided here.

For a broad change, verify with the fast example sweep + byte-identity comparison rather than `examples.run`
per example — recipes and their traps are in the `reference_verification_harness_recipes` memory.

**Two compiler diagnostics, both opt-in and both off by default** — each observes every processor invocation
or fact read, so an ordinary build must never pay for them (an always-on tracker once cost 73% of a cold
build):

- `--statistics` prints, per processor, how often it ran and its cumulative **self time** — wall time minus
  time blocked waiting for facts, which is CPU time here since nothing runs in parallel (`eliotc/…/statistics/`,
  entirely a `wrapWith` add-on: no processor is special-cased and `CompilerIO` is untouched). Two derived
  lines close the accounting: dispatch (offering every key to every processor) and the engine. Measuring is
  itself per-invocation work and lands in the dispatch line, inflating a build ~20%, so read that line as an
  upper bound and diff a run with and without the flag for the true figure.
- `--visualize-facts <path>` writes the fact-flow graph (`eliotc/…/visualization/`).

A third opt-in diagnostic, `--progress` (`eliotc/…/progress/`, design `docs/progress-indication.md`), prints
append-only progress lines to stderr. It is the one diagnostic that hooks the engine itself —
`IncrementalFactGenerator` takes an optional `ProgressTracker` — because cache acceptance never reaches a processor.
It counts a fact when it is **demanded**, never when it is pushed, so cold and warm runs count the same total. §6
steps 1–4 are built: the count, the total it is shown against (`[n/total]`), which is what the last successful
`--progress` run delivered, kept in `<target>/.eliot-progress-<fingerprint>` (`ProgressProfile`), what the run is
doing — a plugin names its fact keys as a verb and a subject (`CompilerPlugin.progressDescriber`;
`LangProgressDescriber`, `JvmProgressDescriber`) — and the time left: the same profile keeps a history per run class
(cold / changed / unchanged), per-key-type self time taken from the tracker's own engine events, and the pure
`ProgressEstimator` is tested against runs recorded to `eliotc/test/resources/progress/`. A new fact key type should
get a describer line, or its work shows under whichever described fact asked for it; it needs nothing to be estimated.
Step 5 is built too: a target reports what it produced through `CompilerPlugin.progressMeasures` (jvm: the jar's size),
the profile keeps the last values so the `ok` line shows a delta, and `ProgressStyle` decorates — colour by role, ASCII
outside UTF-8, and a **log form** (no colour, time of day on each line, heartbeats ≥ 30 s) whenever stderr is not a
terminal, which includes `./mill examples.run`; to see the terminal form, start `out/examples/launcher.dest/run` directly.

### Module Structure

Modules (see `build.mill`):

1. **eliotc** — core compiler infrastructure (plugins, processors, feedback, utilities)
2. **lang** — compiler/language front end (parsing, AST, type system, modules, resolution, monomorphize)
3. **stdlib** — the platform-independent base standard library (abstract `type`/`def` signatures)
4. **jvm** — JVM backend (ASM bytecode, JAR generation)
5. **examples** — example ELIOT programs
6. **apidoc** — doc-comment facts + HTML site generation

The three layer modules carry `.els` besides their Scala, and those sources are an ordinary Eliot **package** in the
build system's standard layout — `<module>/eliot/src` (runtime) and `<module>/eliot/compiler` (the compile-time
overlay) — parked one level down because Mill already owns `<module>/src` for the Scala. The root `eliot.pkg` is what
says so: each layer's `at` clause names its directory, so `//lang`, `//stdlib` and `//jvm` stay the names a dependent
selects however the tree is rearranged.

### Releases: the `v0` line

This repository is an Eliot package (root `eliot.pkg`), so it is versioned the way the build system
versions every package: **a release is an annotated tag, and a major version is the number its tags start
with.** No branch is involved — the launcher reads tags and never branches (eliot-build's
`docs/build-system.md`, "Versions") — so releases are tagged on `master`, where development happens. The
line is **`v0`** and its first release is **`v0.0`** (2026-09-13, the commit where the layers moved into
the standard layout) — v0 rather than v1 because a line is an append-only promise about exported
signatures, `eliot compat-check` does not exist yet, and the base still changes shape wholesale. `v0.0`'s
commit is also the line's *lineage anchor*, the identity two spellings of this repository's URL are
unified by, so it never moves. (The `v0` branch is what releases used to be cut onto; nothing reads it.)

To publish a release: push the commit to `master`, then `./eliotw --release` — it tags the commit checked
out as the next `v0.<n>` and pushes that one tag, refusing a dirty tree, an unpushed commit, a commit that
does not follow the last release and one that release already names. `./eliotw --release v1.0` starts a
new line, which is never chosen for you. Tags must be **annotated**, which `--release` always makes — the
resolver reads the peeled ref and a lightweight tag has none, and `.github/workflows/release.yml` refuses a
lightweight tag rather than letting it fail at whoever depends on it.

Pushing the tag is the whole of publishing. That workflow runs `./mill __.test`, builds the three plugin
assets `eliot.pkg` declares (`scripts/package-assets.sh`) and creates the GitHub release with their
sha256s in the notes — so a tag carries the sources *and* the compiler binaries built from them, which
is what lets a consumer's one `dep` line select both at once. A tag pushed before the workflow existed
is published by running it manually with that tag as its input — and such a tag carries no packaging
script, so the workflow takes `scripts/package-assets.sh` from the ref the run was dispatched from and
everything it packages from the tag. **A published asset is never replaced**:
that promise is why the launcher pin carries no hash, so a mistake in an asset is a new tag, never a
re-upload.

### `eliotw` — this repository as a package, from the outside

`eliotw` is here, byte-identical to every other repository's, with its pin as the `launcher` line of
`eliot.pkg`, and they do **not**
build this one: this repo is polyglot, the compiler in it is Scala, and `mill` builds it. What the
wrapper is for here is reading the descriptor the way a consumer does, which is the one thing no test in
this repository can do:

```bash
./eliotw --project-model   # every package's source roots, as the layers' `at` clauses resolve them
./eliotw --release         # tag the pushed commit as the next release, and push the tag
```

The first is worth running before cutting a release. Every consumer's build mounts exactly those
directories, derived from `eliot.pkg`'s `package`/`at` clauses — so a typo in one is invisible here,
compiles fine, passes `./mill __.test`, and then silently mounts nothing at whoever depends on the tag.

It fetches the launcher release the `launcher` line pins (`v0.7`, the first with `--release`; `v0.6` was the
first with `--project-model`, which the LSP reads), which needs no compiler checkout and no mill.

### IDE Tooling (`ide/`)

Everything editor/IDE-related lives under **`ide/`**; put new editor integrations there.

- **`ide/lsp/`** — the LSP server. A Mill module nested under `ide`, so its target is **`ide.lsp`**
  (output under `out/ide/lsp/`). Depends on `lang` + `stdlib`; main class `LspMain`.
  - `ide/lsp/package.sh` builds a distribution under `ide/lsp/dist/` (git-ignored). It bundles **code only** —
    layer `.els` are never shipped; base/stdlib/platform layers reach the compiler on the **path** as ordinary
    dependencies ([[project_lsp_layers_from_path_not_bundled]]). It produces **separate per-module jars, never a
    fat assembly jar**: each layer jar carries a same-path `META-INF/services/…CompilerPlugin` file and a fat jar
    collapses those, silently dropping plugin registrations ([[gotcha_assembly_jar_breaks_layers]]). A second
    classpath dir `compiler-lib/` holds **only ASM**, the one backend dep "Run main" needs that `lib/` lacks.
  - **The project model comes from the build tool**, one compile session per package (`server/WorkspacePlan`,
    `server/PackageSession`): a workspace folder with `eliot.pkg` + `eliotw` is asked `./eliotw --project-model`
    (`buildtool/ProjectModelQuery`, offline by the tool's design), and each resolved package compiles exactly its
    build's roots — never a union, which could mix two platforms or two versions. A request about a file goes to the
    sessions with it on their path, owner first; a file's diagnostics are merged across the sessions checking it,
    except that a `main` is judged only by the packages where it monomorphized (a platform-less library package
    otherwise reports "no implementation of `Console`"). Other folders get one guessed session
    (`SourceRootDiscovery`); `eliot.paths` is gone. The jar's `Implementation-Version` (`git describe`) is compared
    with the eliot version each package selects, and a difference is a warning — the server always compiles with its
    own compiler. Its log config is `eliot-lsp-log4j2.xml`, selected by `LspMain`, because the apidoc jar's
    `log4j2.xml` on the same classpath logs to **stdout** and would corrupt the protocol.
  - Shipped: whole-workspace diagnostics, hover/go-to-def (reverse `PositionIndex`), live-edit VFS overlay,
    completion, concrete-type hover hints (`TypeHintIndex` from `MonomorphicValue` facts), a `▶ Run main` code lens (`MainIndex`, fires the `eliot.runMain` command), and apidoc doc hover
    (`DocIndex` from `ValueDoc` facts; the LSP activates `ApiDocPlugin` as a *non-target* plugin so only its
    processor runs, never HTML generation), and a `▶ Run tests` lens (`TestIndex`, command `eliot.runTests`) above each
    `testCases` — the name `eliot.test`'s runner gathers suites by, so "is a test" is the runner's own rule. Arguments
    are `[buildRoot, moduleName, dependencyRoot*]`, as for `runMain`; the client starts `eliot.test.Runner` over those
    roots with the module name after `--`, which selects exactly that suite. **The gate is the path, not a fact**: a
    session offers the lens only when a root holds `eliot/test/Runner.els` (`WorkspacePlan.Session.providesModule`),
    because dependency roots are never *checked* and so the runner has no `ResolvedValue` to ask; `main`'s
    broken-but-still-offered fallback has no analogue, since a missing runner is nothing a run could report on.
  - The one remaining design item is parser/checker **error recovery** (`docs/ide-type-hints.md`, Layers A/B) —
    it is what makes hints work on in-progress code. Everything else (find-refs, rename, semantic tokens) is
    routine additive work on the existing index.
- **`ide/textmate/`** — TextMate grammar for `.els` highlighting. Static files, not a build module.
- **`ide/intellij/`** — the shipped IntelliJ plugin: highlighting + diagnostics (LSP4IJ) + a native "Eliot
  Application" run configuration. A **self-contained Gradle build**, not part of the Mill build; its
  `prepareSandbox` shells out to `ide/lsp/package.sh`. Build with `cd ide/intellij && ./gradlew runIde|buildPlugin`.
  See `ide/intellij/README.md`. **IntelliJ shows runs natively, from the server's lenses**: the server still emits
  `eliot.runMain`/`eliot.runTests` lenses (what every other editor shows), and the plugin reads the same lenses through
  LSP4IJ (`run/EliotRunTargets`: the gutter never blocks — a pending request restarts highlighting when it answers —
  while the producer waits up to 2 s, since a context menu asks once and an unopened file's request only starts then)
  into a gutter ▶ (`EliotRunLineMarkerContributor`) and a `LazyRunConfigurationProducer`
  (`EliotRunConfigurationProducer`: context menu, Ctrl+Shift+F10, temporary-then-saved configs), while
  `EliotCodeLensFeature` withholds the lens text. **`.els` is an IntelliJ language of its own** (`language/`): TextMate's
  parser makes a whole file ONE PSI leaf (`EmptyLexer`), so no gutter icon can anchor to a TextMate file. Its PSI is a
  flat run of word/whitespace leaves — deliberately no second Eliot parser — and colouring stays TextMate's,
  registered for the `Eliot` file type in `eliot-textmate.xml` (TextMate finds the grammar by file name). So "is this runnable" stays
  the server's rule; do not detect `main`/`testCases` client-side. Every configuration gets the build before-run
  task, which invokes the compiler CLI and gates on the exit code, so a stale jar is never run; the compile shows in
  the **Build tool window** (`build/EliotBuild` over `BuildViewManager.createBuildProgress`): console output, each
  error a navigable entry, `--progress` as the status. The errors are parsed from the compiler's *human-readable*
  output (`build/EliotBuildOutputParser`) — sanctioned only because the plugin runs the compiler it bundles, so a
  change to `CompilerError.print` or the `--progress` log form must keep `EliotBuildOutputParserTest` green. Headless,
  the platform's `BuildViewManager` is a `DummyBuildViewManager` that drops every event; `EliotBuildTest` replaces
  the service with a recorder. A suite
  (`EliotRunTarget.Kind.TESTS`) is the same configuration over the runner: `mainModule = eliot.test.Runner`,
  `programArguments = --format=teamcity <suite module>`, `testRun = true`. A `testRun` swaps the default console for
  the platform's test console (`EliotRunConfiguration`'s `execute` override, `EliotTestConsoleProperties`), which
  reads the runner's TeamCity service messages (`eliot.test.Report`'s `Teamcity` style) into a results tree: module
  → subject → case, with a diff for a failed comparison. An ordinary run keeps the default console, under
  `ColoredProcessHandler` so ANSI renders. **It builds and tests locally** (`./gradlew buildPlugin test`; headless
  `BasePlatformTestCase`s under `src/test/`). Anything needing the live server — the gutter icon itself — has to be
  checked by hand in `runIde`.

## Architecture

### Plugin System

ServiceLoader-based: each plugin implements `CompilerPlugin` (in eliotc), may depend on other plugins, and
configures the pipeline. `LangPlugin` provides core compilation; `JvmPlugin` provides bytecode + jar output.

### Compilation Pipeline

Fact-based: **facts** are immutable pieces of compilation data; **processors** compute facts from other facts on
demand; `FactGenerator` orchestrates lazy computation with caching. A fact is identified by its key, usually a
subset of its data.

### Phases (packages in `lang`, roughly in order)

1. **source** — reading source files (multi-mount, platform-scoped; see the Layers cornerstone)
2. **token** — tokenizer
3. **ast** — building the AST
4. **core** — building the core language AST (desugars `data`, effect rows, meta transfers; a **row alias**
   `type Git[A] = {Process} A` is an ordinary alias here, and `EffectSugarDesugarer` records the row it names on
   the alias's own declaration, minting nothing — a use of it is read at `resolve`, by ordinary name resolution). Two checks ride the
   desugared named values here: `StrictPositivityChecker` and `VisibilityOrderChecker` (a file's public API must be a
   *prefix* — no public declaration may follow a private one, C++'s `private:` section as an ordering rule). The
   visibility check runs post-desugar precisely so `def`/`type`/`data`/`ability`/`implement` need no per-construct
   arms; `ability`/`implement` mint public values and so are covered with no exception, and a `private data` is
   private in *every* name it mints.
5. **module** — from modules to individual values; unifies same-named modules from different paths
6. **resolve** — resolve identifiers to fully qualified names or parameters; also where a `~` constraint is closed
   under what the named ability itself requires (`~ Web` ⤳ `Web, Console, Log`), and where a **row alias** named as a
   definition's return type hands it that alias's declared row: the same reading, of the declaration a resolved name
   resolved to, so an alias crosses files and is shadowed like any other name (`docs/effects.md` §2.4)
7. **matchdesugar** — pattern matches into function applications; exhaustiveness, nested/constructor/wildcard patterns
8. **operator** — infix operators by precedence and associativity, into structured applications
9. **termination** — the recursion gate (see the *Total by Default* cornerstone)
10. *(There is no `effect` package. It held carrier identification and the `Effect`/`Suspend` machinery recognition,
    and effects v6 left nothing in it: nothing mints those abilities, no `.els` declares them and there is no
    `eliot.carrier` package. The one predicate that survived — "is this binder higher-kinded?", a **kind** question,
    not an effect one — is `CarrierKindChecker.isHktBinder`, beside its only caller. The carrier-stack renderer that
    lived there is gone too, because a type now prints as what the user wrote.)*
11. **row** — the effect phase. `row/processor/RowElaborationProcessor` produces `RowElaboratedValue` between the
    recursion gate and saturation, running `row/BindingWriter` over **both halves** of a definition (body *and*
    signature — a guarded return holds references too). One walk, three jobs, **from declarations only**: write each
    binding binder's implementation at the indices the callee's declaration **marks** (`Impl: Implementation[Console]`),
    merged by index with what the call determines for the rest; **thunk and apply** (an actual at a row-typed slot
    is wrapped in a lambda, a reference to one of this definition's row-typed parameters is applied to `unit` — wrap and
    apply are inverse, so a pass-through is an η-expansion); and erase the `with` nodes. The walk **is** the pre-mono
    scope check: an effect with no covering declaration is the "performs but does not declare" error at that reference.
    `row/RowChecker` is now just the demand-driven `Universe` the walk consults (its `onMiss` is what lets a
    demand-driven consumer avoid guessing the consulted set); `row/RunBoundaryFunctions` is the platform run-boundary
    predicate, where every effect's chain ends and an uncovered entry binds the two-site `Default` instead. See the
    *Effects Are a Channel* cornerstone.
12. **ability** — checks and returns a type-specific ability implementation. A reference whose binder was written to a
    named implementation uses it **directly** — no structural match, no `where` filter, no coherence question, which is
    what lets a test's double freely overlap a default. Only the `Default` marker reaches the two-site search, whose candidates are the
    **anonymous** implementations alone (`ModuleAbilities.anonymousImplementationMethodsOf` — a named one is reached
    only by `with`, and reading the unfiltered list is how a colocated double silently answered an ordinary row), where
    selection is structural (pattern match) and then filtered twice: by the candidate's `where` guard, and by
    **constraint-aware declination** — a candidate whose `~` constraints have no implementation at the matched bindings
    declines. Every step of the check is fail-safe *towards keeping*, so it can only remove a candidate that could not
    have worked. A constraint probe that leads back to a resolution already in progress is answered "satisfied" off
    `activeFactKeys`, never demanded.
13. **monomorphize** — the NbE monomorphic type checker: evaluates data and value definitions into typed
    structures and checks all types at every instantiated usage, with the single evaluator. (It absorbed the
    former standalone `eval` phase.) Because `row` already wrote every implementation, the checker sees an effect as
    an ordinary nullary ability and a binding as an ordinary type argument:
    - **the checker holds no effect rule at all.** `EffectLifter` went with the carrier its one surviving arm needed.
      What is next door and must **not** be deleted as effect machinery is `check/CarrierKindChecker`, and that is
      measured, not assumed (`docs/effects.md` §12, "do not re-propose"): `verifyCarrierKinds` is the only thing
      rejecting a `[F[_]]` binder instantiated at a fully-applied proper type, and with it off that program silently
      compiles. It is a *kind* system living next door.
    - **verification is not here either** — it is phase 11's scope check, pre-mono and complete before
      monomorphization, and it is the *only* effect verifier: the post-mono re-derivation retired with D7 on the
      measurement that it could only ever see a strict subset of the same thing. What is left at the codegen seam is
      `channel/SuppliedRowArgumentsProcessor`, rejecting a supplied row entry whose argument nothing determines,
      which was never that check's shadow. The checker holds no effect diagnostic.
    - the **compile track** keeps its mid-spine default ladder and deferred slots *by design* (`Track.Compiler`,
      `Checker.resolveDeferredSlot`) — the sole live reader of the `Unifier`'s higher-kinded-meta record.
    - other non-equality collaborators, each hooked from `TypeStackLoop.runPostDrainResolution`:
      `check/CarrierKindChecker`, `check/GuardDischargeResolver` (W2b effectful-signature guard discharge —
      formerly `CalculatedReturnResolver`, whose calculated-return half was removed with the `auto`/implicit-generics
      feature), `check/AbilityResolver`.
    - riders on `MonomorphicValue`: `channel/RefinementChannelProcessor` (Int ranges),
      `channel/SuppliedRowArgumentsProcessor` (a supplied row entry's arguments are determined) and
      `channel/MetaTransferAccountingProcessor` (meta transfers: R2 a leaf must state, R3 nothing else may). The
      last two are `WovenValue` codegen preconditions.
14. **used** — collects used value names starting from a `main`
15. **uncurry** — uncurries calls for the backend

### Error Handling

Errors go through the `CompilationIO` monad (lang). `SourcedError` carries source position; `User` and `Logging`
provide user-facing messages and debug logging.

## Testing

- Tests extend `AsyncFlatSpec` with `AsyncIOSpec` with `Matchers`; files live in `<module>/test/src/`.
- A processor test needing more than a couple of leaf phases runs the **whole** pipeline via the shared builder —
  `extends ProcessorTest(LangProcessors()*)` (with `systemModules = …` / `maxNestedRepeats = …` when those differ)
  — never a hand-listed prefix. Computation is demand-driven and `SequentialCompilerProcessors` dispatches each
  key to its one handling processor, so a harness that triggers only an early fact simply never runs later
  phases; carrying the full pipeline is free, and a new phase is then wired in exactly one place. Only true leaf
  tests (tokenizer/AST/core, or manual fact injection) list processors explicitly. The jvm backend test reuses the
  same list and appends its own: `LangProcessors(…) :+ JvmClassGenerator()`.
- A `ProcessorTest` whose snippet calls an **ability or effect method** needs an `Implementation` stub declaring
  `type Default` in its system modules, or the value silently loses its monomorphization **with no error at all**:
  the write puts `Default` at the reference as an ordinary type argument, and saturation demands the value it names.

## Language Cornerstone: Types Are Values (λ\*)

Eliot has **no internal distinction between the type level and the value level**. Type constructors and value
constructors are both ordinary named values, and the type *of* a value is itself just another value. The only
thing that makes a computation "type-level" is *when* it is forced: type-level code happens to be evaluated
before code generation, but that staging is incidental, not a difference in kind. Formally this is a
**non-stratified Pure Type System** — λ\* / "type-in-type" (`Type : Type`) — where the compile-time/runtime split
is pure **phase / erasure** and type checking is **Normalisation by Evaluation** (the `monomorphize` package: one
`Evaluator`, one `SemValue` domain shared by types and values, `VType` as an ordinary value). This is deliberately
*not* "dependent types" bolted on; dependency is merely a consequence of types being values. Accepted trade-off:
`Type : Type` is logically inconsistent (Girard's paradox) — fine for a general-purpose language, with
termination handled separately rather than by a universe hierarchy.

**Sanctioned sugar vs. required discipline.** Familiar surface distinctions are *intentional sugar*, not
violations: the `Qualifier.Type`/`Qualifier.Default` namespaces, `[]` vs `()` call/pattern syntax, and the
restricted `Expression.typeParser` all collapse to "the same `FunctionDefinition` with a different qualifier tag."
What the cornerstone *requires* of every task: exactly **one evaluator and one value domain** (never a second,
weaker "compile-time" interpreter); type equality is **definitional** (force/normalise via that evaluator, then
compare), never a parallel bespoke mechanism; and kind/arity metadata stays out of semantic phases.

Three durable guardrails:

1. **`unify` is pure definitional equality** — never a `refinements` map or an assignability arm. There is **no
   `Int` widening and no `Coerce`**: `Int` is nullary (`type Int {range: Interval[BigInteger]}`) with bounds held
   as meta-information in the separate **refinement channel** (`monomorphize/channel/RefinementChannelProcessor`,
   checked post-mono). So `Int == Int` definitionally, and a narrower range flowing where a wider one is expected
   is definitionally equal — bound legality is the channel's job, not a checker-inserted coercion.
2. **`VPi` is the one primitive Π-former** *on principle* — do not fold `Function` into an ordinary `data`.
3. **Kind/arity metadata (`RoleHint`, esp. `typeParamCount`) must not drive any typing decision.** Its only
   sanctioned read is constructor-shape reconstruction for `match`.

## Language Cornerstone: Platform-Independence via Layers

Eliot targets everything from an ATtiny to the JVM, so the language and its base stdlib commit to **no platform
assumptions** — not even the size of an `Int`. The rule is **no platform *representation***: the base layer never
says how a type is laid out or how a primitive is computed. Everything representation- or platform-dependent is
declared **abstractly** — `type`s without a value constructor, body-less `def` signatures. The base *may* carry
`def` bodies and ability instances when the computation is **genuinely platform-independent**, byte-for-byte the
same on every target (e.g. `fitsIn`, the discharge helpers `catch`/`else`/`runStateToPair`). It must **never**
contain `data` (a chosen representation), a native leaf, or any representation-dependent body.

- `type Int {range: Interval[BigInteger]}` — an abstract type; no value constructor, no chosen width. Its range is
  channel meta-information, not a type parameter. An `Interval`'s **endpoints are each a `Bound[T]`**
  (`data Bound[T] = Unbounded | Bounded(value: T)`, `data Interval[T](start: Bound[T], end: Bound[T])`), so a range
  may be **half-open** — an unbounded endpoint's direction is read from its *position* (`start` = no lower limit,
  `end` = no upper), and there is no signed infinity. That is what lets the base state the platform-independent half
  of a bound (`atLeast(0)` for a size) instead of inventing a platform maximum it may not assume; a platform narrows
  it to `closed(0, platformMax)`. It is also what supplies the domain's stated **top**: `whole` — open at both ends —
  says *"nothing bounds this"*, so an absent meta means only "not computed yet" (`docs/total-meta-transfers.md` §5).
  The slot carried a **second, outer `Bound`** for that top until the endpoints could express it; collapsing it was
  the point of keeping one spelling, since `ReconcileProcessor.metaByPosition` compares verdicts *structurally* and
  two spellings of the same top read as disagreement. There is deliberately **no global top**: the generic
  `Meta[Bound[D]]` join is deleted, `Bound` is an interval endpoint and nothing else, and a future domain with no
  interval structure grows its own top rather than borrowing a wrapper (a wrapper is a second spelling, which is the
  bug above). The only ⊤ left is the channel's untotality (`None` at a companion-free callee, a parameter, a lambda
  interior), removed by arming R2 and by the §P4 interpretation — not by a value.
  Constructors `interval`/`closed`/`atLeast`/`atMost`/`whole` and the `where`-facing predicate
  `rangeWithin[Lo, Hi](i)` are abstract in the base, bodied per platform. `add`/`subtract` stay exact on a
  half-open interval; `multiply` widens to `whole` (its corner products lose the position that signs an infinity).
- `type String {size: Interval[BigInteger]}` — the channel's **second domain** (`docs/string-length-meta.md`),
  the same one-slot shape and the same machinery, which is what proves the channel is not `Int`-shaped. The unit is the
  **code point** — what `String::length` counts on every target — so a `where` over a size means the same thing
  everywhere; the slot is `size` because `length` is taken by the runtime function in that module. A literal seeds it
  (`Runtime::stringLiteral`'s brace, the string twin of `integerLiteral`'s), and `length` is the first leaf to **state**
  a transfer (`{size(s)}`), carrying a size into the `Int` domain. Stating one needed the backend's **result-edge
  re-encode** (S3, landed): a call boundary hands an integer back at the ⊤ bignum, so a node the transfer narrows is
  converted right after the call — `ExpressionCodeGenerator.convertResultFromBoundary`. Since the **round-trip
  peephole** (S9) that conversion is the *fallback*, not the common path: the backend's rule is **a value is emitted at
  the width its consumer will read, and a consumer that adapts to any width reads it as it arrives**, so a boundary
  result meeting a ⊤ slot (an argument, a method return — `createExpressionCodeAtBoundaryWidth`), an intrinsic's
  operand (`createExpressionCodeUnconverted`, which an intrinsic then unboxes from `unconvertedRepOf`) and a constant
  are all emitted with no conversion at all. The trap it had to see: a compute-domain decision (`viaBigInteger`) reads
  the **node** meta (`nodeRepOf`), never the arriving width, or every boundary operand would flip to `BigInteger`
  arithmetic. **S4 stated the rest of `String.els`** — `combine` exactly, `substring`/`trim` bounded by their
  subject, case conversion **tripling** its upper bound (`ß` ⤳ `SS`), `repeat`/`replace`/`indexOfInternal`, and
  `parseIntInternal` at the domain top `whole` since its honest bound is exponential in its argument's size. A brace
  **spells a number the compiler's way**: an endpoint is a `BigInteger`, a value-position literal is an `Int`, and
  there is no widening — so a brace's own literals are read as compile-time `BigInteger`s, because a brace's `^Meta`
  companion (and a `where`'s `^Where`) is compiler-pool-only code, exactly like a signature (`CoreProcessor`'s
  `isMetaBody` ⤳ `CoreExpressionConverter`'s `compilerTrackContext`). So an endpoint is written `Bounded(0)`, and a
  negative one is an ordinary subtraction the compile-time `Numeric[BigInteger]` reduces (`Bounded(0 - 1)`); S4's
  type-position workaround `boundedAt[V]` is deleted. An *ordinary def body* is still runtime-track, so a helper
  called from a brace cannot name a `BigInteger` constant in value position — which is why `rangeWithin[Lo, Hi]`
  takes its bounds as type parameters. **S5 finished it and armed R2** (`docs/total-meta-transfers.md` §P2): eleven
  more leaves state — `Show[Int]::show` at `atLeast(1)` (the ceiling is a digit count, needing a logarithm no leaf
  offers), `Show[Path]::show` at `atLeast(0)`, and the nine jvm input/holder natives at the JVM's `String`/`int`
  representation limits — and `MetaTransferAccountingProcessor` is now a **codegen precondition** in
  `WovenValueProcessor`, so a native producing a meta-carrying type and stating nothing no longer compiles. **S6 armed
  R3** (§P3), the same processor's other arm split on `MonomorphicValue.runtime`: a **bodied** value derives its meta
  from its body and so may not also state one — read post-merge, so a brace in one layer over a body in another is the
  same check. A brace therefore lives only on a native leaf, which means **a user module cannot author one at all**
  (it declares no natives); a `where` is untouched, being a stated contract by design. Two rules
  it settled: a transfer lives in the layer that owns the **fact it states** (an operation's own bound in the base, a
  representation's in the platform layer), and a leaf's statement is **axiomatic** — nothing rechecks it — so a bound
  that is merely usually true (POSIX's `[0, 255]` for an exit code, which Windows breaks) is a bug, and the honest
  wide statement wins. A brace also could not be written on a def with an **untracked parameter** until S5 fixed the
  companion desugarer: the `^Meta`/`^Where` name transform `T` ⤳ `T$Meta` only exists for slotted types, so a
  parameter the brace never mentions now keeps its own type (`core/processor/MetaCompanionReferences`).
- `def foldLeft[A, B](initial: B, combine: ..., list: List[A]): B` — an abstract function, signature only.
- A `type X = ...` alias and a body-less `type X` differ only by having a body; `data X(...)` is the *concrete*
  form that additionally introduces a value constructor.

For the operational mechanics of placing/moving `.els` files across layers — two-pool resolution, the
abstract↔concrete merge, signature-match gotchas — use the **`eliot-layers`** skill.

**Layers = redefinition, not inheritance.** A platform "implements" an abstract definition by *defining the same
name again*, in its own root path, with a body. There is no `extends`, `override`, or instance mechanism —
co-located definitions of the same qualified name across root paths are **merged**, preferring the concrete one.
The base declares `type List[A]` and a body-less `def foldLeft(...)`; the `jvm` layer re-declares them concretely
over its `java.util.List` native, and the compiler unifies them into one value.

An **effect implementation** is not such a merge either. The base declares `effect Console { … }` with no bodies;
the jvm layer re-declares it (name resolution is per-file) and adds the anonymous `implement Console { … }` over its
own private natives, which is the platform's *default* for that effect. `main` declares its effects
(`def main uses Console: Unit`), and the synthesized entry point is where every effect's chain ends: it binds each of
`main`'s entries to the two-site default, and an effect with none reaching `main` is an error there naming it
(`SyntheticMainSourceProcessor`, `row/RunBoundaryFunctions`).

**Layers *mix*, they do not *stack*; every file must stand on its own.** Name resolution is per-file — a file's
dictionary is its own declarations plus imports, never names declared in a *sibling* file of the same module. So
when one file needs a name a sibling declares (e.g. an `implement Console { … }`, which must be colocated with its
ability and so lives in the ability's module), that file must **re-declare what it needs**; the merge then
**verifies the copies agree** (`signatureEquality`) rather than letting them drift. Duplication is the sanctioned mechanism — do *not* "fix" a cross-file reference by widening
the resolver to span sibling files.

**How it works mechanically** (the `source` + `module` packages):

- The compiler gets multiple **source mounts** per platform pool (`source/scan/SourceMount.scala`; CLI roots
  become `FilesystemMount`s, plugins may contribute others — the jvm target mounts its synthesized `main.els`,
  the LSP routes overlaid buffers to a `vfs:` namespace). `PathScanner` resolves a module path against *all*
  mounts of the pool and returns *every* match as one `PathScan`; each URI scheme is served by one processor.
- `ModuleNamesProcessor` extracts names per file; `UnifiedModuleNamesProcessor` flattens all files of a module.
- Per name, `UnifiedModuleValueProcessor` collects the `ModuleValue` from every defining file and calls
  `unifyValues`. Abstract vs concrete is carried by `NamedValue.runtime: Option[Sourced[Expression]]` — `None` =
  abstract, `Some` = concrete. Rules: **prefer the implementation** (else keep the abstract one); **reject
  conflicts** — more than one implementation is "Has multiple implementations.", differing signatures are "Has
  multiple different definitions." So a layer may add a body but must **not** change the signature.

`data` desugars to an abstract type-constructor `FunctionDefinition` plus value-constructor functions
(`DataDefinitionDesugarer`), so even concrete types reduce to the same `NamedValue` model.

### The compiler is itself a platform (where compile-time code lives)

The **compiler is its own platform**, peer to jvm. Source resolution is **platform-scoped** (the `platform`
marker — `compiler` / `runtime` — is a key dimension threaded through the front end): the NbE checker resolves
names in the `compiler` platform, codegen (`used → uncurry → backend`) in the `runtime` platform. So one abstract
base name can have a **distinct concrete implementation per platform**, exactly as `add`/`fold`/`Bool` do via
native-binding routing (`ContributedBinding` + `BindingMergerProcessor`: the compile-time reduction wins for
checking, the runtime body for codegen).

**The compiler platform is not a monolithic layer stacked on `stdlib`; it is assembled from each layer's opt-in
compile-time contribution.** A layer that supports it ships, beside its runtime `src/` root, a sibling
**`compiler/`** root plus Scala natives for what no Eliot body can express. The compiler pool scans the
**entire runtime track** *plus* every root's `compiler/` overlay, and an overlay definition supersedes the
borrowed one for the same name (`PathScan.overrideFiles`; the runtime track carries no override files, so its
merge is unchanged). The compiler therefore **borrows** a runtime body wherever it is compiler-runnable — a pure
base body, a user program's pure helper, any pure `data`/fold — with the native-leaf boundary as the fail-safe: a
body reaching a bytecode leaf stalls **loudly**, never silently wrong.

What a layer may **not** borrow is a *sibling target* (jvm) that might be absent, so a layer's compile-time track
must be **self-sufficient** from the base plus its own `compiler/`. Roots reach the compiler via a single
repeatable **`--path <module>/eliot/src`** (no separate compiler/runtime path flags, no `compiler` Mill module);
`LangPlugin.eliotCompilerOverlay` derives each root's sibling. **One** root ships an overlay today,
`stdlib/eliot/compiler/`: the self-sufficient compile-time `Either`, `Option`, `Pair` and `Bound`/`Interval` (the
data the evaluator's escape/cell intrinsics answer, `monomorphize/processor/EffectIntrinsics`), the two intrinsic
declarations themselves (`eliot/compiler/Escape.els`, `eliot/compiler/Cell.els` — declared once and publicly,
because `EffectIntrinsics` matches their exact FQNs, and compile-track-only so no user program can reach them), and
the compile-time `Abort` written over `escape`, which is what makes an `if..else` return guard reduce. **The
compile track has `Abort` and no `Throw`**: `Abort` keys its frame on its own nullary `Aborted` marker, while a
generic binder has no value form (`escape(E[], …)` does not compile), so a compile-time reduction reaching
`runThrow` is *stuck* — loud, not wrong — and a guarded return therefore cannot carry its author's message
(`docs/effects.md` §7). Anything pure and already on the path
(base bodies, a user program's pure helpers) is **borrowed, not duplicated**. `CompilerNativesProcessor` reads that pool; the
compile-time intrinsics (`add`, `Bool` `fold`, `true`/`false`, `typeEquals`) are Scala native **leaves** in
`SystemNativesProcessor` (lang) / `StdlibNativesProcessor` (stdlib arithmetic) — the compiler platform's leaf
bottom, mirroring jvm's bytecode leaves.

**Where to put new compiler code.** When a task needs something evaluated at compile time — an effect
implementation, an intrinsic, an instance used only during checking — and it is expressible in ordinary Eliot, write it as **Eliot in
the owning layer's `compiler/` root**, keeping the abstract signature in `lang`/`stdlib` and the *runtime*
concrete impl in `jvm`. **First ask whether it can be borrowed**: a pure body already in the base or reachable on
the runtime track needs no overlay copy — that is how duplication is avoided. Add an overlay copy only when the
name must be *self-sufficient* (its only runtime concrete is a sibling target's) or is a checking-only addition.
Do **not** put `data`/bodies in the abstract base, and do **not** reimplement `data`/`match`/instances as Scala
`SemValue`s — the one NbE evaluator already runs them, so a Scala reimplementation is the single-evaluator
anti-pattern. Reserve Scala natives for genuinely primitive **leaves** no Eliot body can express.

## Language Cornerstone: Use-Site Verification (Sound, Not Modular)

Eliot does not prove a definition correct for every instantiation it *could* receive (modular completeness); it
guarantees that every instantiation which *actually manifests* — the whole program is monomorphized from `main` —
is fully type-checked. Soundness is total (**no incorrect program compiles**); only the modular per-definition
certificate is given up.

**Mechanism.** An obligation that cannot be discharged abstractly (binders left neutral) — a bound-dependent
operation, an ability impl, a calculated bound — is **deferred to the concrete use site**, where the one NbE
checker decides it exactly. A modular checker would reject the definition or demand a constraint; Eliot accepts it
and verifies each use — the same monomorphize-from-`main` stance already used for codegen and ability resolution,
extended from compilation to correctness. This applies to the *implicit/calculated layer* (`infer` params,
effectful-signature guards, abilities); explicit parametric defs still get the ordinary abstract check.

**Trade-off** (intentional): more burden on library authors (totality comes from *tests* — generators and probing,
see `docs/ide-type-hints.md` — not a proof); users may meet a type error "not of their making" (a library's latent
partiality surfacing at the use site, which the IDE should surface at the definition first). It is completely
**safe** (every manifest use is checked) and far more **powerful** (full dependent/computed types at near-zero
annotation, no type-level proof obligations, accepting functions a modular system would reject). This is **not a
silent gap**: it is complete verification of the actual program, hard-erroring at the use site
(cf. [[feedback_gaps_must_be_failsafe]]).

Principle: *we prove a definition correct for every input it does take, not every input it could take — and reject
any program in which some input it does take is wrong.*

## Language Cornerstone: Effects Are a Channel (`uses` In, Implementations Written)

The user writes **`uses` clauses** (`def greet(name: String) uses Console: Unit`); the compiler turns each entry into
a **phantom generic binder** whose value is an **implementation name**, written at every reference by a
syntax-directed pass. Clauses and implementations are two different things on purpose, and keeping them apart is
what makes effects free of special cases. Authoritative design: `docs/effects.md` — Part I states the shipped
design (§1 the user rules, §2 the surface, §3 the mechanism, §4 the scope check, §5 the standing rules, §6 testing,
§7 the live limitations; §3.4 is the primitives and the environment threading); Part II is what is left (§8 where
the tree diverges from Part I, §9 the binding binder marked by its declared type `Implementation[A]`, §10 the gate and
method, §11 the open and decided-but-unbuilt decisions — **D20a** naming a set of effects, **D20c** storing an open
computation); Part III holds §12's **do-not-re-propose** list and §13, which maps
every citation of a condensed record (**D20**/**D21** rules and steps, **D20c option 5**, **(b)**, **D22**) to Part I.
**There is no carrier, no monad, no `Id`, and nothing to infer** — if you are reading code or a comment that mentions
one, it is history.

**The surface is one clause in two positions.** On a **definition**, `uses Console, Throw[E]` is what its caller
hands it. On a **parameter**, a clause makes the argument **code** the caller wrote, which the callee may call or pass
on to another `uses` slot and **never keep**; without one, a parameter is a **value**, computed before the call:

```eliot
def compose[A, B, C](f: B => C, g: A => B): A => C = a -> f(g(a))   // pure: f and g are values, keepable
def map[A, B](f uses *: A => B, list: List[A]): List[B]             // the caller's code, on the caller's effects
def if[T](condition: Bool, value uses *, Abort: T) uses Abort: T    // nullary code: mentioning `value` runs it
def runThrow[E, A](body uses *, Throw[E]: A): Either[E, A]          // the caller's effects plus Throw[E], supplied
def runPure[E, A](body uses Throw[E]: A): Either[E, A]              // closed (D21's sample, not in the base)
def mocked(body uses *, Mocking with recording, Calls with journal: Unit): Unit  // a `with` per entry
```

`*` is first and at most once, and only on a parameter: it names the effects in scope **where the argument is
written** (lexical capture, not a row variable — nothing is unified). **Purity is the absence of one word**: a
signature with no `uses` anywhere is pure and, since `Inf` is an effect, total. The parser writes a clause onto the
same row node (`EffectfulType`) the rest of the compiler always read — a definition's onto its return type, a
parameter's onto the slot or its arrow codomain — so no phase past `ast` learned the clause except one bit,
**closed** (`ArgumentDefinition.closedRow`). **The brace spelling `{Console} Unit` is a parse error** naming the
clause, everywhere except a **row alias** body (`type Test = {Writer[List[TestResult]]} Unit`), which has no `uses`
form until D20a decides one.

**The user rules** (§1; D20's and D21's, which are the rules every artefact answers to — where code, a stdlib
signature, an example or a test conflicts with them, the rule wins and the artefact is the defect):

1. **Effects resolve where they are written.** Strict call-by-value in every value position:
   `choose(readLine, readLine)` runs both reads, `Box(shout)` runs `shout` and stores its value.
2. **A value is pure.** A lambda in any value position — an unmarked function parameter, a field, a generic slot, a
   result, a `val` — may use no effect it does not discharge itself ("This uses the effect 'X' inside a function
   written where a value is expected"). The rule's other half — an unapplied reference to a `uses` definition is not
   a value — is **not enforced yet** and fails silently (`docs/effects.md` §8 item 14).
3. **Code is used, never kept.** A `uses` parameter can be called or passed on; anywhere else it is "code its caller
   wrote", and a value lambda cannot capture it. A pure function value may stand where code is expected.
4. **A closed clause supplies and closes.** The argument at `body uses Throw[E]: A` may use what the slot lists and
   what it discharges itself (`BindingWriter.Scope.enterClosed`).
5. **A listed effect is the callee's to handle or to declare** (D22). An effect a code parameter's clause lists, the
   definition either handles where the code runs (a `with` on the slot, or passing the code on to a discharger) or
   declares in its own clause, passing it on; neither is "'body' may perform the effect 'X' here, which this
   definition neither handles nor declares". An effect the code uses through `*` unlisted is invisible to it — it can
   neither catch it nor declare it. An effect's default is handed out at `main` (the run boundary) and by the platform
   primitives, which state what they give.
6. **A field holds a value.** A `data` field takes no clause and no row; store data describing the work and perform
   it where the effects are in scope.

**Three other constructs.** An **`effect`** is an ability with no carrier binder; a member's clause lists what it
performs *beyond* the effect it belongs to (`uses Console` on a member of `effect Console` is not written). An
**anonymous `implement`** in one of the two sites is the *default* for its pattern; a **named `implement`** is never
a default — it may live anywhere, is never searched, is not checked for overlap, takes no parameters and closes over
nothing, and its clauses may carry `uses`. **`with`** binds a name for its subject: infix, subject-first, loosest
precedence, left-associative, as an expression in a body or after an entry of a parameter's clause. `with` on a
definition's own clause is rejected. **`with` is written almost nowhere**: a def declaring `uses Console` receives its
binding from its caller up to `main`, and a `with` in production code is the same mistake as a hard-coded dependency.

**The desugar writes the implementation; nothing solves for one.** `core/…/EffectSugarDesugarer` turns each entry and
each `~` constraint into one phantom binder of kind `Type` that occurs in **no parameter or return type**, and thunks
a code parameter (`Unit => A`; a function-typed one keeps its arrow, the clause recorded in
`EffectRow.callbackEffects` with its arity). `row/BindingWriter` writes each binder's value at every reference, by
the resolution order: (1) the nearest enclosing `with`; (2) this definition's own binder for it, a **received**
binding filled by the caller; (3) for an actual at a code slot, the entries that slot **supplies**; (4) `Default` —
the two-site search — for an **ability**, while for an **effect** an uncovered use is "This value performs the effect
'X' but does not declare it; add it to its `uses` clause" at that reference. **Effect-ness is read from one place
only**: the callee's declaration. Nothing keys on a name or a shape. A minted binder says it is a binding in its
**declared type** — `Impl: Implementation[Console]`, the mark `row/BindingWriter` reads and erases at the end of that
phase — so bindings need not sit in a prefix: `typeArgs` still applies positionally, and the write merges the marked
indices with what the call determines for the rest.

**What that deletes, and what must not come back.** There is no carrier metavariable, no join solver, no lattice, no
`Id`-headed judgment, no mode obligation, no post-drain mode resolver, and no elaborator whitelist to police.
**Reintroducing inference of a binding** — a meta, a lattice, an ordering-sensitive slot decision, a row variable
unified across arguments, or a **sum over a monomorphized sub-graph with transitive override**, which is dynamic
scoping resolved at compile time — is the historical bug class in new vocabulary and is **prohibited**. A rule that
inspects a *sibling argument's expression shape* is inference, not desugaring, and is prohibited. A decision that
cannot be made from a declaration is a gap to close **in the declarations**.

**A slot's entries are *supplied*, and that is what makes a discharger** (§2.2). `body uses *, Abort: A` says "on my
caller's bindings **extended by** `Abort`": an entry the definition's own clause already has is *not* supplied and
the walk continues outward (so `if`'s `value uses *, Abort: T` under its own `uses Abort` rides the caller's), and an entry it lacks
is supplied by the slot. A function-typed code parameter's clause is the callback's own scope (`onError uses *: E =>
A`). A supplied entry's **type arguments** are written from the actual's declaration (`bad uses Throw[String]:
String` against `Throw[E]` gives `E := String`); where no declaration answers, the call spells them
(`runThrow[AssertionError, Unit](body)`) and an argument nothing determines is **rejected**, never defaulted — which
is what stops a `catch` compiling against a frame it will not meet. Three shapes reach that rejection honestly: the
actual is a **parameter reference**, the actual **raises nothing**, or the slot names the **same ability twice**.

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or thread
through, so the entry simply never joins the clause and there is nothing to spell as a negative effect. **Nesting
order at the run site decides interaction** — `runStateToPair(s, runThrow(c))` versus `runThrow(runStateToPair(s,
c))` is the difference between state surviving a `raise` and not — and there is no canonical form for the compiler
to choose. A definition may discharge the **very effect it declares**, which is what lets `describedAs` rewrite its
body's failure message: a slot entry the definition also declares **rides by code and is given by frame** — the
block's binding is the caller's, its frame the one where it runs (D20c's decided (b)) — so running the block performs
that entry, and a listed effect is always the callee's to catch if it chooses (D22).

**An implementation is code plus an environment, passed and never searched for** (`docs/effects.md` §3.4; D20c option 5,
built on the runtime track). The code is the binding, as above. The environment — an escape frame, a cell, an anonymous implementation's
own parameters (`implement[S] State[S](cell: StateCell[S])`, recorded on a body-less `$parameters` declaration,
`ast/fact/ImplementationParameters`) — is threaded **after monomorphization**, at the `WovenValue` seam
(`monomorphize/channel/EnvironmentThreading`), whose ground re-check checks the plumbing: each value mono records its
entries at ground arguments (`MonomorphicEffects`); a definition receives one frame per entry as hidden parameters; a
row-typed slot's thunk takes the frames it supplies in place of `unit`; a frame is chosen **by entry at ground
arguments**, and its binding must be the code's. The platform's control effects take a frame (`ThrowFrame`,
`AbortFrame`, `StateCell`, `WriterCell`, `DepCell`) that their body-less primitives make and hand to the thunk they
run, so a helper's own `catch` cannot intercept its caller's `raise`, and an undischarged control effect at `main` is
a compile error (D19). Not built: `with Ability(args)`, named implementations with parameters, parameterised ability
implementations, compile-track frames (the compile track still keys a frame by its type).

**Three platform-private primitives, and nothing else.** A finishing clause is a non-local exit; a stateful
implementation threads a value through calls that never mention it. Neither is expressible in a strict pure core, so
each target ships **escape** (`escapeInternal(body, onExit, onValue)` — on jvm a fresh frame object handed to `body`,
exited by identity; an evaluator intrinsic keyed by type on the compile track), **cell** (`withCellInternal(initial,
body, combine)` — a one-slot array local to the call, handed to `body`) and **loop** (`foreverInternal`). The control effects' single implementations are
written over them, and each jvm copy states in its own clause what it gives its `body` (rule 5). They are private
because a public cell is Landin's knot (`termination/PurityGuardTest` exists to keep it out), so
`runThrow`/`runAbort`/`runStateToPair`/`runWriterToPair`/`provide` are **body-less in the base and bodied per
platform**, while `catch`/`else`/`runStateToValue`/`runStateToFinalState`/`runWriterToValue`/`runWriterToLog` are
ordinary base bodies over those. Eliot has no layer-private visibility, so each jvm module needing a primitive
declares its own copy, which also separates the frames for free.

**Two families, and only one is rebindable.** The **control effects** (`Throw`, `Abort`, `State`, `Writer`, `Dep`,
`Inf`) have exactly one implementation per platform over those primitives; `with` has nothing to choose there. The
**interpretation effects** (`Console`, `Log`, `FileSystem`, `Process`, `Environment`) and every ability are what
`with` and a naming slot are for. The families are a description, not a bit in the language: nothing keys on it.

**Clauses are the user surface and the verifier's vocabulary — they never flow back into types.** `EffectRow` is
declaration metadata (like `paramConstraints`), consumed by the desugar; verification is a separate **channel** with
**one verifier**: the pre-mono **scope check**, which is the write's own walk, complete before monomorphization. It
checks the other direction too: a declared entry no reference in the body is written from is "This value declares
the effect 'X' but does not perform it" (§4) — running a code parameter consumes nothing, since its effects were
bound where it was written. *Forward what is declared, derive what is done* — a forwarded per-operation verdict would
be a checker self-report and is rejected, as is any negative-effect surface. The post-mono second verifier was
**retired by D7 (2026-09-10)** on the measurement that it saw a strict subset of the scope check. **Do not grow a
second effect verifier.** What stays at the codegen seam is `monomorphize/channel/SuppliedRowArgumentsProcessor`, a
`getFactOrAbort` precondition rejecting a supplied entry whose type argument nothing determines; it needs ground
arguments, so it cannot move earlier, and it is not the retired check's shadow.

**The checker holds no effect rule at all.** What lives next door and must **not** be deleted as effect machinery is
`check/CarrierKindChecker`: `verifyCarrierKinds` is the only thing rejecting a `[F[_]]` binder instantiated at a
fully-applied proper type, and with it off that program silently compiles. It is a *kind* system, measured, not
assumed. Rendering has nothing to invert either — an effect is a nullary ability and an implementation is a name, so
`GroundValueRenderer` prints what the user wrote.

**A named implementation is the testing injection point** (§6). Production code that declares `uses Console` names no
implementation, so whoever runs it decides — the synthesized entry point in production, one `with` in a test. A
double is **one declaration** in the test module: no type to hang on, no colocation, no coherence question, since a
named implementation is never searched and may freely overlap a default. **A double cannot cheat**, because a user
module cannot declare a native and the platform's are private to its layer — an implementation reaches the world only
through effects **its own clauses declare**, which are charged and bound at the binding site. **Interpretation is per
effect, not per program**: `body with mockConsole with mockFileSystem` leaves everything else at its default.
`eliot-test` is the worked framework: a suite declares only its return type — the row alias
`type Test = {Writer[List[TestResult]]} Unit`, widened with a clause where its cases perform (`def testCases uses
Console: Test`) — and an author's own discharge word binds the doubles on its slot's entries, so a faked case writes
no fixture at all.

**A set of effects has no name** beyond the row alias (§2.4, D20a open). What survives of v5's ability-requires-
ability is the ordinary **superability closure** on a `~` constraint — `~ A` is closed under what `A` itself
requires of the parameter this use bound to this binder (`ValueResolver.superConstraints`, transitive and
idempotent).

**`&` is a standard-library name, and an ability resolves like any other value** (§2.5; full user-space `~`/`&` is
decision D3, blocked). The combinator is `infix left type &[A, B]` in `eliot.lang.Ability` (prelude, so ambient): the
parser accepts *any* operator between two `~` constraints and `ValueResolver.resolveCombinator` looks the name up in
the ordinary dictionary, requiring `WellKnownTypes.abilityCombinatorFQN` — so a module declaring its own `&` takes the
name back and gets a diagnostic. It rides ast→core on `AbilityConstraint.combinedBy` purely to reach that check and
is dropped there. `~` stays reserved — it is a binder marker like `:`, not a name. Alongside it,
`ValueResolverScope.getAbility` is a keyed lookup of the ability's **marker** (`QualifiedName(n, Ability(n))`), not a
scan of `dictionary.values` — **do not reintroduce the scan**, and do not add a second lookup path for ability names.

**Ambient scope.** The whole `eliot.effect` package is auto-imported: `ModuleName.effectSystemModules` joins the
`eliot.lang` prelude in `defaultSystemModules`, in a **weak** tier — an explicitly imported module is deduplicated,
and an ambient name colliding with a local declaration or explicit import is silently dropped (locals always win, so
the prelude can grow without breaking code), while explicit imports keep the strict shadowing errors.

**What has no spelling, on purpose.** A stored effectful computation (a field holds a value; store data and interpret
it — `docs/effects.md` §2.3, and D20c for one handled where it runs), a native that keeps a callback (the platform's axiom), and `uses *` on a
definition (a body has no caller's text to be open to). One gap is carried, fail-safe: a function-typed code
parameter's own entries (`f uses *, E: A => B`) land on the codomain the callee never supplies, so code using `E`
there is rejected.

**Cornerstone fidelity**: this is *more* types-are-values-faithful than the carrier was, not less — a phantom binder
is an ordinary generic binder, an implementation is an ordinary ground value in `typeArguments`, specialisation is the
ordinary monomorphization key, and effect flow is ordinary instantiation. No side channel does type-like work behind
the type system's back, and no kind or sort is added to the type language: `uses` is a clause on a declaration, never
part of a type, so `unify` sees `Function[A, B]` on both sides.

## Language Cornerstone: Total by Default (No Recursion; `Inf` is the Opt-Out)

**Eliot user code cannot express recursion or loops — full stop.** There is no `fix`/`letrec`, lambda parameters
are non-recursive, and every cycle is therefore a self/mutual reference among top-level named values, visible in
the resolved value-reference graph. The `termination` package gates this: `RecursionCheckProcessor` (running
`RecursionChecker`), placed after operator resolution and before the `row` phase, rejects any cycle in a value's
**runtime-body** reference graph ("Value 'X' is defined recursively.") — body only, never the signature, so a
covariant `data Tree(left: Tree, right: Tree)` and the monad-transformer lift are not flagged. A rejected value
never produces its `RecursionCheckedValue` fact, so it never reaches saturation or monomorphization (fail-safe by
construction). Every actual loop lives inside a **platform-provided native** (a `fold`, `forever`, the event
loop); the language mandates no recursion primitive of its own.

Three preconditions make "no recursion" mean what it says: an **occurs-check** in `Unifier` (no inferred infinite
type / Y-combinator), a **strict-positivity check** (`core.processor.StrictPositivityChecker`, no
negative-recursive `data`), and **purity** (no mutable cells — Landin's knot; guarded by
`termination/PurityGuardTest`). With a recursion-free typed core, **every program terminates by default**
(System T, not PCF) — modulo the already-accepted `Type:Type`/Girard residual.

The one opt-out is **`Inf`**, modelled as an ordinary **effect**
(`effect Inf { def forever(step uses *: Unit): Unit }`, ambient like all of `eliot.effect`) rather than a bespoke
termination lattice — there is **no `Terminating` token**; termination is simply `Inf`'s *absence* from the `uses`
clauses. Because a recursion-free core cannot itself diverge, `Inf` can **only originate on a native** (the loop
primitive, `foreverInternal`), and it propagates to callers for free through the ordinary scope check — a
`uses Console`-only function calling `forever` is rejected at that reference. `Inf` is **run, not discharged**: it is
the one effect that may legitimately reach `main` undischarged, where the run boundary binds the jvm layer's
`implement Inf` (`while(true)`) and it denotes a deliberate non-terminating program — a server or firmware
super-loop. Higher-order propagation is automatic: `forever`'s `step` is a `uses *` slot, so an effect-transparent
combinator is `Inf`-iff-its-step-is, with no per-arrow bit. Deferred, needing foundations that
do not yet exist: WCET/resource bounds, optional size-indexing, a *timeout*-based bound on `uses Inf` (needs a time
type), and linearity for in-place mutation.

## Language Overview

Eliot is a functional, strongly-typed language with whole-application compilation and monomorphization. Its main
building blocks:

- **Named values** — a generalization of "functions", since functions are represented fully curried
  (`a -> b -> c`), so a function is a lambda value. They are "named" because they are not anonymous and can be
  referenced from elsewhere.
- **Data** — completely represented as values in the core model, with no representation of its own. A `data`
  declaration defines a value constructor (if not abstract) and a type constructor; both are ordinary values.
- **Abilities and implementations** — typeclasses and instances, with multi-parameter capability. An
  implementation must be defined either in the module where the ability is defined or with the type it is for,
  and must be unique for a given type combination across the whole search space, with no overlap. Ability
  references are fully resolved during monomorphization, never passed around in structures.

## Compiler Change Patterns

- When making a field optional in `resolve.fact.Expression` case classes, update **all** pattern matches across
  the codebase **and** the test files that construct those cases directly.
- There is a **single** evaluator (`monomorphize/eval/Evaluator.scala`, NbE over `SemValue`). Unresolved/stuck
  terms surface as explicit errors at quote time (`PostDrainQuoter`, "Cannot resolve type."), never via a silent
  `Type` fallback. **Do not reintroduce a second compile-time evaluator** (see the Types Are Values cornerstone).

## Development Notes

- The language is in active development (see the TODO file for planned features).
- Current focus is microcontroller targets, though JVM is the implemented backend.
- The type system aims at dependent types and compile-time guarantees about resource usage.
- The standard library (`stdlib/`) is still minimal.
