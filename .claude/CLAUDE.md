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
   under what the named ability itself requires (`~ Ord` ⤳ `Ord, Eq` for an `ability Ord[A ~ Eq]`), and where a **row alias** named as a
   definition's return type hands it that alias's declared row: the same reading, of the declaration a resolved name
   resolved to, so an alias crosses files and is shadowed like any other name (`docs/effects.md` §2.4)
7. **matchdesugar** — pattern matches into function applications; exhaustiveness, nested/constructor/wildcard patterns
8. **operator** — infix operators by precedence and associativity, into structured applications
9. **termination** — the recursion gate (see the *Total by Default* cornerstone)
10. *(There is no `effect` package. It held carrier identification and the `Effect`/`Suspend` machinery recognition,
    and effects v6 left nothing in it: nothing mints those abilities, no `.els` declares them and there is no
    `eliot.carrier` package. The kind question that sat beside it — is a higher-kinded binder instantiated at a
    proper type? — is `check/CarrierKindChecker`'s, and is soundness, not effect machinery. The carrier-stack
    renderer that lived there is gone too, because a type now prints as what the user wrote.)*
11. **row** — the effect phase, right after the gate. `row/processor/RowElaborationProcessor` produces
    `RowElaboratedValue` before saturation, running `row/BindingWriter` over **both halves** of a definition (body *and*
    signature — a guarded return holds references too). It does all of an effect's plumbing and decides nothing
    (`docs/effects.md` §3.2): a **hole** (`HandleHole`) at every handle position — an operation's, a `uses` callee's, a
    run's — carrying its entry and its lexical candidates nearest first; a `with` (a node carrying its arguments from `core`)
    turned into a `let` of the implementation's handle around its subject; an actual at a listing slot wrapped in a
    lambda taking one handle per listed entry, a `uses *`-only one in a thunk; an implementation's clause shaped as its
    member (`row/Captures`). It is the scope check's **by-name** half: an effect with no handle of it in scope is the
    "performs but does not declare" error at that reference, and the second-class rule (rules 2–3) is checked here.
    A `~` constraint mints nothing and is the two-site search's. `row/RowChecker` is the demand-driven
    `Universe` the walk consults (`onMiss`/`onMissClauses`; a probe of a name only a bodying layer mints asks the
    module's names first). The synthesized entry point is an ordinary definition binding `main`'s entries with `with`
    (`row/EntryPoint`), which reports an unbodied standard implementation at `main`. The same processor refuses an
    anonymous `implement` of an effect, and a named one of an ability (D29), at its marker. See the *Effects Are
    Capability Passing* cornerstone.
12. **ability** — checks and returns a type-specific ability implementation. An effect's operation is an ordinary
    ability method over its handle's type, and a named implementation's instance is the only one at its handle's type,
    so it is found by the same search with nothing to overlap. The two-site search's candidates are the
    **anonymous** implementations alone (`ModuleAbilities.anonymousImplementationMethodsOf` — a named one is reached
    only through its handle, and reading the unfiltered list is how a colocated double silently answered an ordinary
    row), where selection is structural (pattern match) and then filtered twice: by the candidate's `where` guard, and by
    **constraint-aware declination** — a candidate whose `~` constraints have no implementation at the matched bindings
    declines. Every step of the check is fail-safe *towards keeping*, so it can only remove a candidate that could not
    have worked. A constraint probe that leads back to a resolution already in progress is answered "satisfied" off
    `activeFactKeys`, never demanded.
13. **monomorphize** — the NbE monomorphic type checker: evaluates data and value definitions into typed
    structures and checks all types at every instantiated usage, with the single evaluator. (It absorbed the
    former standalone `eval` phase.) It produces `MonomorphicValue` (and `CompilerMonomorphicValue` on the compile
    track) directly; monomorphization clones by a handle's type in the key, so there is no specialisation pass:
    - **the checker holds one effect rule, the hole** (`check/HandleHoleResolver`, `docs/effects.md` §3.3): each hole
      takes the nearest candidate whose entry is definitionally equal after evaluation; none is an error naming what is
      in scope, a tie at one distance is an error, a candidate with an unsolved meta answers only if every hole it
      could answer agrees. Beside it, `check/HandedBinders` reads a block's handle types off its callee's
      `MonomorphicValue.handed` (`readHanded`). An operation is then dispatched by `AbilityResolver` by its handle's
      type like any ability method. What is next door and must **not** be deleted as effect machinery is
      `check/CarrierKindChecker`, and that is measured, not assumed (`docs/effects.md` §12, "do not re-propose"):
      `verifyCarrierKinds` is the only thing rejecting a `[F[_]]` binder instantiated at a fully-applied proper type,
      and with it off that program silently compiles. It is a *kind* system living next door.
    - **verification is the scope check, in two halves**: by name in phase 11, by entry in the hole rule — one
      verifier (D7); a post-mono re-derivation retired on the measurement that it could only ever see a strict subset.
    - other non-equality collaborators, each hooked from `TypeStackLoop.runPostDrainResolution`:
      `check/CarrierKindChecker`, `check/GuardDischargeResolver` (W2b effectful-signature guard discharge —
      formerly `CalculatedReturnResolver`, whose calculated-return half was removed with the `auto`/implicit-generics
      feature), `check/AbilityResolver`, `check/HandleHoleResolver`.
    - riders on `MonomorphicValue`: `channel/RefinementChannelProcessor` (Int ranges; it walks a definition's leading
      handle parameters as parameters, and a `where` companion leaves a callee's handles out) and
      `channel/MetaTransferAccountingProcessor` (meta transfers: R2 a leaf must state, R3 a bodied value may, its result only), the
      latter a `WovenValue` codegen precondition, and `channel/MetaDemandsProcessor` (P5: every `where` and parameter
      brace checked in each meta context its call is reached in from `main`, one `MetaDemands` fact per context; a
      body's root context leaves its meta-carrying parameters symbolic and defers what reads them to its callers).
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
- A `ProcessorTest` whose snippet uses an **effect** needs the `Implementation` stub (`type Inferred`, `type Handed =
  Type`) in its system modules — the `row` phase writes both names, module-qualified. `ProcessorTest`'s prelude
  carries it; a hand-built pool must too.

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
restricted `Expression.typeRunParser` all collapse to "the same `FunctionDefinition` with a different qualifier tag."
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
  says *"nothing bounds this"*, so an absent meta means only "not computed yet" (`docs/meta.md` §2.1, the channel's
  one document: Part I shipped, Part II the design that completes totality, Part III the record and citation map).
  The slot carried a **second, outer `Bound`** for that top until the endpoints could express it; collapsing it was
  the point of keeping one spelling, since `ReconcileProcessor.metaByPosition` compares verdicts *structurally* and
  two spellings of the same top read as disagreement. There is deliberately **no global top**: the generic
  `Meta[Bound[D]]` join is deleted, `Bound` is an interval endpoint and nothing else, and a future domain with no
  interval structure grows its own top rather than borrowing a wrapper (a wrapper is a second spelling, which is the
  bug above). **Metas are total: every value has one** — a bodied value's is calculated, a native's is stated
  (`docs/meta.md` §1). A *not computed* meta (`None`, ⊤ in older comments) is never a meta value: it is a gap the
  interpretation has not closed or its step budget, fail-safe at every demand, and the remaining gaps are listed in
  `docs/meta.md` §8. Part II closes them, every decision taken 2026-10-21, T1 (gap mechanics), T2 (⊥), T3 (frames and cells), T4 (loops) and T5 (structural meta, 2026-10-22: a `data` value's meta is, per constructor, `Reach`ed or not with its fields' metas — `StructuralMetaDesugarer`, generated `D$Meta`/`D$Meta$C`/`D$Fields`; the recursive group — recursion through another type or across types — is deferred to the recursive-data fold by decision J, and stays not computed until then) built, next T6 (`List` carries its elements' meta). **A name holding a `$` is in no dictionary**: only the compiler writes one, generated code names one in its own module unqualified (the resolver finds it there) and one elsewhere module-qualified — carrying them tripled the cache, since every value's fact holds its file's dictionary. T3 follows a frame or cell through the one call that made it, as a `Store` threaded through the interpretation; the natives state it with primitives of `eliot.compiler.Meta` (`frame`, `cell`, `exit`, `exited`, `stored`, `content`, `store`) on their results and on their code's **arrow domains** (`onValue uses *: A {body} => R`, the `ArgumentDefinition.handed` brace, on a function-typed value parameter too since T4), which the channel recognises by name and never reduces (`MetaStatement`). A handle is a `data`, so every constructor states `{built(…)}` (its structural meta) and every `match` eliminator hands `fieldOf(value, C, read)` (generated by the data desugarer): the channel names no constructor and no `PatternMatch`: R2's type-parameter exemption is **reversed** (decided
  2026-10-20 — every leaf states, e.g. `exitInternal` `{never}`); what reaches a native through a frame, a cell or a
  loop is followed (a loop is a fixpoint the domain's widening makes finite: `ability Meta` has `widen` beside `join`,
  and a native states its loop with `looped(initial, code)` or `endless(code)`); the interpretation gains ⊥ — "does
  not return", a fact about control and not a special value; and `data`,
  recursive included, carries its fields' metas. **The process has no special values** (decided 2026-10-20): no `top`
  on `Meta`, nothing marking a meta missing or biggest — only a domain may have a top, in its own value type.
  Constructors `interval`/`closed`/`atLeast`/`atMost`/`whole` and the `where`-facing predicate
  `rangeWithin[Lo, Hi](i)` are abstract in the base, bodied per platform. `add`/`subtract` stay exact on a
  half-open interval; `multiply` widens to `whole` (its corner products lose the position that signs an infinity).
- `type String {length: Interval[BigInteger]}` — the channel's **second domain** (`docs/meta.md` §2.2),
  the same one-slot shape and the same machinery, which is what proves the channel is not `Int`-shaped. The unit is the
  **code point** — what `String::length` counts on every target — so a `where` over a size means the same thing
  everywhere; the slot is named like the function that returns it. **Slot accessors live in `Qualifier.Slot`**, their
  own namespace, found first by bare (and module-qualified) names in code about metas — a brace, a `where`, a `Meta`
  instance — so a slot may share its module's function name; slot names are still one scope per file, which is why
  `String`'s is `length` and `List`'s `size`. A literal seeds it
  (`Runtime::stringLiteral`'s brace, the string twin of `integerLiteral`'s), and `length` is the first leaf to **state**
  a transfer (`{length(s)}`), carrying a size into the `Int` domain. Stating one needed the backend's **result-edge
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
  takes its bounds as type parameters. **S5 finished it and armed R2** (`docs/meta.md` §3): eleven
  more leaves state — `Show[Int]::show` at `atLeast(1)` (the ceiling is a digit count, needing a logarithm no leaf
  offers), `Show[Path]::show` at `atLeast(0)`, and the nine jvm input/holder natives at the JVM's `String`/`int`
  representation limits — and `MetaTransferAccountingProcessor` is now a **codegen precondition** in
  `WovenValueProcessor`, so a native producing a meta-carrying type and stating nothing no longer compiles. **R3** is
  the same processor's other arm, split on `MonomorphicValue.runtime` read post-merge: since **decision O** (2026-10-23,
  `docs/meta.md` §3, §8.1; S6 had armed R3 the other way, refusing it) a brace on a **bodied** value states its
  result's meta and **replaces** the derived one — pure trust, nothing checks it; the result only, the body still
  walked for cells/frames/exits, ⊥ still ⊥, the budget still reported; slots only, a control primitive or slots over a
  meta-less or function result refused. `MetaInterpreter.metaOf` has three arms to match: stated (native), derived then
  stated (`restated`, bodied with a brace), derived. A wrong brace is **silent** (`add(lie(300), 0)` wraps to `44`), the
  accepted price. **Custom metas** are that plus a `data` slot: `data Temperature {frost: Frost}(celsius: Int)
  {frostOf(range(celsius))}` — slots on the head, every constructor states every slot from its fields' metas
  (`DataSlotChecker` refuses a missing statement, a slot named like a field, `MetaOf`, a recursive type), the domain an
  ordinary `data` with a user `Meta` instance; a bodied function returning one restates its slots alone
  (`def warm: Temperature {AboveFreezing} = …`, `D$Slots`, `restatingConstructorOf`). Meta code reads **no field's
  meta** (decision R): a slot sits beside the structural meta, which keeps no surface; a constructor's statement
  flattens what a slot needs. Open: O2 (a bodied brace reading code), O3 (intersection/narrowing vocabulary), O6
  (`MetaOf`/recursive `data` slots). Two rules
  it settled: a transfer lives in the layer that owns the **fact it states** (an operation's own bound in the base, a
  representation's in the platform layer), and a leaf's statement is **axiomatic** — nothing rechecks it — so a bound
  that is merely usually true (POSIX's `[0, 255]` for an exit code, which Windows breaks) is a bug, and the honest
  wide statement wins. **A companion never names a meta type** (`core/processor/MetaCompanionLayout`,
  `docs/meta.md` §4.1): naming one by spelling (`T` ⤳ `T$Meta`) failed for an alias, a computed type,
  a type parameter and every slotless type. A `^Meta`/`^Where` companion takes the def's binders (as its effect desugar
  leaves them, so they line up with the callee's key), one binder per parameter and, for a transfer, one for the result;
  the channel instantiates them with `MetaInterpreter.metaTypeOf` of the def's **payload signature** `<name>$Payload`,
  a generated type the one evaluator reduces at the callee's type arguments (a code parameter reads as what its code
  yields). A slot-producing brace's meta *constructor* is not named either: the companion takes it as its first
  parameter (`constructor$`) and the channel passes the one of the result's evaluated meta type, so `{closed(0, 255)}`
  on a leaf returning an alias of `Int` states the same meta as on `Int`. R2 reads the evaluated result type too. A
  **parameter brace** `x: Int {closed(0, 255)}` (§4.4) states the meta of the value at that parameter, and whoever
  supplies it proves it: every full call demands its argument fit (`join(argument, stated) ≡ stated`, the join reached
  through `eliot.compiler.Meta::joined`), a demand in the body is checked at what each call supplies (P5), and a def
  with one cannot be passed as a value. Its statement companion
  (`MetaParameterDesugarer`, `<def>$Parameter$<p>`) reads no parameter: its parameters are placeholders that only count
  and place. A brace on a `data` field is the same decision (§4.5): the constructor proves it, and the `match` eliminator
  states it of what it hands a handler (a *handed* statement), which every accessor and arm reads it through;
  a meta is stated where the compiler cannot see the supplier, with one exception: a bodied return, which a brace
  replaces on trust because the derivation cannot learn from what a condition means (§3.3, R3).
- `type List[A] {size: Interval[BigInteger], item: Reach[MetaOf[A]]}` — the **third domain** (`docs/meta.md` §17.1),
  the first holding another type's meta: `MetaOf[A]` (legal in a slot only) becomes a binder of `List$Meta`, listed by
  a generated `List$Carried[A]`, and the channel applies it (`List[Int]` ⤳ `List$Meta[Int$Meta]`). `item` is a `Reach`,
  `Unreached` for a list that can have no item — never ⊥ — so a `where` reads it with `forAll` (`eliot.lang.Reach`). A
  native producing a list of a slotted type states its items with `MetaOf[T](slots…)`, which the resolver turns into
  `T`'s meta constructor, `T` named directly.
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

An **effect implementation** *is* such a merge (D26). The base declares `effect Console { … }` with no bodies and,
beside it, the body-less named `implement standardConsole: Console`; the jvm layer re-declares the effect (name
resolution is per-file) and bodies `implement standardConsole: Console { … }` over its own private natives. `main`
declares its effects (`def main uses Console: Unit`), and the synthesized entry point runs it by explicit passing —
`def main: Unit = app::main with eliot.effect.Console::standardConsole`, one `with` per entry, generated by
`row/EntryPoint` for both the jvm entry point and the LSP wrapper. An entry with no `standard…` implementation (a
control effect, or one the program declares itself) is an error at `main` naming it.

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
data the evaluator's intrinsics answer, `monomorphize/processor/EffectIntrinsics`) and the compile-time **`Abort` and
`Throw`**, spelled exactly as the jvm layer's (`implement aborting(frame: AbortFrame[Unit]): Abort`, so the overlay's
supersedes on the compile track) over each module's private `escapeIntrinsic`/`exitIntrinsic`, which `EffectIntrinsics` answers by
FQN: a fresh frame with identity, threaded along the declared chain as on the runtime track (`docs/effects.md` §3.4).
That is what makes an `if..else` return guard reduce, keeps a helper's own `else` from catching its caller's `abort`,
and lets a guarded return's `raise("…")` carry its author's message — the guarded-return read gives a signature its
control effects' frames. Anything pure and already on the path
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

## Language Cornerstone: Effects Are Capability Passing (`uses` In, Handles Passed)

The user writes **`uses` clauses** (`def greet(name: String) uses Console: Unit`); the compiler turns each entry into
a **handle**: a type parameter and a value parameter of that type, holding the implementation's environment, and an
effect into an ability over its handle's type, so each operation is an ordinary ability method chosen by the
handle's type. This is **capability passing** (D36, built 2026-10-18) — dependency injection checked statically and
scoped lexically, with second-class handles (Effekt's *effects as capabilities* is the nearest prior art): reason about
a design question in dependency terms, and keep *effect* for what the user is promised (purity is the absence of
`uses`, totality the absence of `Inf`). Before monomorphization one syntax-directed walk does all the plumbing and
decides nothing; **the one decision — which handle answers a use — is the checker's**: the nearest handle in lexical
scope whose entry answers it after evaluation (36 eliot-build declarations carry two or more `Throw` entries, so a
choice by effect name alone cannot work). Authoritative design: `docs/effects.md` — Part I states the shipped design
(§1 the user rules, §2 the surface, §3 the mechanism — §3.1 the desugar, §3.2 the walk, §3.3 the hole rule and a
block's handle types, §3.4 the primitives and frames —, §4 the scope check, §5 the standing rules, §6 testing, §7 the
live limitations); Part II is the record (§8 the closed divergences, none open; §9 which
binders are handles — the declaration's `EffectRow.handles` record, which replaced the `Implementation[A]` mark and the
ability binding slot; §10 the gate and method; §11 the decisions — D36, the
open questions **D20a** naming a set of effects, **D20c** storing an open computation, **D3**, and every earlier decision
one entry each); Part III holds §12's **do-not-re-propose** list and §13, the citation map (D35's Part I is at
`e03aa15c`; D36's step-by-step build record, before the 2026-10-19 compaction, at `671d2b57`; the records before the
2026-10-18 compaction at `14b61527`). **There is no carrier, no monad, no `Id`, no
evidence, no specialisation pass and nothing to infer** — if you are reading code or a comment that mentions one, it is
history. Handles are **erased** at the `WovenValue` seam (`monomorphize/channel/HandleErasure`, D36 step 4): a
handle with no surviving field is no parameter, argument or class, one with one field is that field, one with more
stays its `data` — the examples' jars are 5.2% under D35's.

**The surface is one clause in two positions.** On a **definition**, `uses Console, Throw[E]` is what its caller
hands it. On a **parameter**, a clause makes the argument **code** the caller wrote, which the callee may call or pass
on to another `uses` slot and **never keep**; without one, a parameter is a **value**, computed before the call:

```eliot
def compose[A, B, C](f: B => C, g: A => B): A => C = a -> f(g(a))                        // pure: f and g are values, keepable
def map[A, B](f uses *: A => B, list: List[A]) uses *: List[B]                           // the caller's code, on the caller's effects
def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T                      // nullary code: mentioning `value` runs it
def runThrow[E, A](body uses *, Throw[E]: A) uses *: Either[E, A]                        // the caller's effects plus Throw[E], supplied
def runPure[E, A](body uses Throw[E]: A): Either[E, A]                                   // closed (D21's sample, not in the base)
def transcriptOf(program uses *, Console: Unit) uses *: String =                        // a double around the run
   runWriterToLog(program with recordingConsole)
```

`*` is first and at most once. On a parameter it names the effects in scope **where the argument is written**
(lexical capture, not a row variable — nothing is unified). On a definition it is a **marker**, required exactly when
some parameter's clause has `*` (D24): a call performs what that code does, so the result side of a signature is
complete on its own. The parser checks it in both directions and drops it — no binder, no entry, no semantics.
**Purity is the absence of one word**: a definition with no `uses` before its result type is pure at every call and,
since `Inf` is an effect, total. The parser writes a clause onto the
same row node (`EffectfulType`) the rest of the compiler always read — a definition's onto its return type, a
parameter's onto the slot or its arrow codomain — so no phase past `ast` learned the clause except one bit,
**closed** (`ArgumentDefinition.closedRow`). **The brace spelling `{Console} Unit` is a parse error** naming the
clause, everywhere except a **row alias** body (`type Test = {Writer[List[TestResult]]} Unit`), which has no `uses`
form until D20a decides one. **A clause lists effects only** (D28): an ability is a `~` constraint, never
inherited along a call chain, so listing one is an error at the entry (`ValueResolver.requireEffect`), and no callee
can fix an ability's implementation for code its caller wrote.

**The user rules** (§1; D20's and D21's, which are the rules every artefact answers to — where code, a stdlib
signature, an example or a test conflicts with them, the rule wins and the artefact is the defect):

1. **Effects resolve where they are written.** Strict call-by-value in every value position:
   `choose(readLine, readLine)` runs both reads, `Box(shout)` runs `shout` and stores its value.
2. **A value is pure.** A lambda in any value position — an unmarked function parameter, a field, a generic slot, a
   result, a `val` — may use no effect it does not discharge itself ("This uses the effect 'X' inside a function
   written where a value is expected"). So may an unapplied reference to a `uses` definition not stand there
   (`Holder(printLine)`): fewer arguments than it declares (`EffectRow.returnArity`) is not a value, while at a code
   parameter it is the caller's code (`applyTwice(printLine)`).
3. **Code is used, never kept.** A `uses` parameter can be called or passed on; anywhere else it is "code its caller
   wrote", and a value lambda cannot capture it. A pure function value may stand where code is expected.
4. **A closed clause lists and closes.** The argument at `body uses Throw[E]: A` may use what the slot lists and
   what it discharges itself (`BindingWriter.Scope.enterClosed`).
5. **A listed effect is the callee's to handle or to declare** (D22). An effect a code parameter's clause lists, the
   definition either handles where the code runs (a `with` around its runs, or passing the code on to a discharger) or
   declares in its own clause, passing it on; neither is "'body' may perform the effect 'X' here, which this definition
   neither handles nor declares". An effect the code uses through `*` unlisted is invisible to it — it can neither
   catch it nor declare it — what the definition's own `uses *` (D24) marks instead. **An effect has no default**
   (D26): its handle is always passed — installed by a `with`, handed to a block by the callee running it, or
   received from the caller — and the entry point binds `main`'s entries with a `with` each.
6. **A field holds a value.** A `data` field takes no clause and no row; store data describing the work and perform
   it where the effects are in scope.

**Three other constructs.** An **`effect`** is an ability over a handle type: every operation takes the handle first,
and a member's clause lists what it performs *beyond* the effect it belongs to (`uses Console` on a member of `effect
Console` is not written). An **anonymous `implement`** in one of the two sites is an **ability**'s *default* for its
pattern, takes no parameters (a parameter list there is a parse error naming the named form), and of an **effect** is an
error, since an effect has no default (D26). A **named `implement`** is an effect's only (of an ability it is an error,
D29): it lowers to a `data` — its handle, its parameters as fields — and that ability's instance at the handle's type,
so it is unique and colocated by construction, may live anywhere and overlaps nothing. Only a layer that **bodies** it
mints its handle (`implement standardConsole: Console` in the base only declares the name). A clause entry its member
also declares, at the implementation's pattern, is the **call's** (a handle parameter of the instance method,
`standardFileSystem.readFile`'s `Throw[IoError]`); any other is **captured** at the `with`, a field holding the handle in
scope there (`recordingConsole.printLine`'s `Writer[String]`, `row/Captures`). Parameters are supplied by the `with`
(`greet with scripted("Bob")`), carried on the `with` node to the `row` walk, which turns it into a `let` of the handle
— the implementation's `data` built from the arguments — around the subject, so the arguments are typed as the
constructor's. A type parameter no field fixes is the pattern's alone, solved at each use, so
one `with showing` serves `Source[Int]` and `Source[String]`. **`with`** puts its handle in scope for its subject (D23):
infix, subject-first, loosest precedence, left-associative, as an expression in a body only — on a parameter or a field
it is a parse error. A `with` no hole in its subject takes is an error; one around a run of a code parameter is handed to
the block for what the slot lists. `with` on a definition's own clause is rejected. **`with` is written almost
nowhere**: a def declaring `uses Console` receives its handle from its caller up to `main`, and a `with` in production
code is the same mistake as a hard-coded dependency.

**The desugar plumbs; the walk checks by name; the checker chooses by type; nothing solves for a binding.**
`core/…/EffectSugarDesugarer` turns each entry into a binder of kind `Type` — the handle's type, recorded as the entry's
handle in `EffectRow.handles` (§9) — plus a handle parameter after the handles the definition already takes; a `~`
constraint mints nothing (an ability's, answered by the two-site search; an ability has no binding slot). A code parameter listing entries is a **block** taking one handle per
listed entry, each of a **handed** binder's type (`Implementation::Handed`, after every other binder); one listing none
is a thunk (`Unit => A`); a function-typed one keeps its arrow, its clause in `EffectRow.callbackEffects`.
`row/BindingWriter` writes a **hole** at every handle position with its lexical candidates, nearest first, grouped by
the binder that put them in scope (the definition's own handles, a block's, a `with`'s, what a block captured), and is
the scope check's **by-name** half ("performs the effect 'X' but does not declare it", "declares … but does not perform
it") and the second-class check. `check/HandleHoleResolver` fills each hole with the nearest candidate whose entry is
**definitionally equal after evaluation**: none is *"No handle in scope here answers 'X'. In scope: …"*; two at one
distance is an error whose fix is a distinct type, never a name (no `@Named`, no slot naming its implementation — D36
points 4 and 10); a candidate holding an unsolved meta answers only if every hole it could answer agrees, else *"Which handle
answers 'X' depends on type arguments nothing here decides"*. **A block's handle types are what its callee's body hands
it** (`check/HandedBinders`): solved by equality at the callee's runs, recorded on `MonomorphicValue.handed`, read by the
caller at the callee's key — every run must hand the same (*"This run of the code is handed another implementation than
another run of it"*), and the block is not in the callee's key. **Effect-ness is read from one place only**: the
callee's declaration. Nothing keys on a name or a shape.

**What must not come back.** There is no carrier metavariable, no join solver, no lattice, no `Id`-headed judgment, no
mode obligation, no evidence and no specialisation pass. **Reintroducing inference of a binding** — a meta of its own,
a lattice, an ordering-sensitive slot decision, a row variable unified across arguments, or a **sum over a monomorphized
sub-graph with transitive override**, which is dynamic scoping resolved at compile time — is the historical bug class in
new vocabulary and is **prohibited**; the hole rule is lexical lookup by type and is not that (signed-off reading). A
rule that inspects a *sibling argument's expression shape* is inference, not desugaring, and is prohibited. A decision
that cannot be made from a declaration is the checker's, by type, or a gap to close **in the declarations**.

**A listed effect is handed, and that is what makes a discharger** (§2.2). `body uses *, Abort: A` says the callee
hands the block an `Abort` handle at each run, from its own scope: its received one (it declares the effect and installs
nothing nearer — `if`'s `value`, so an `abort` reaches `if`'s caller) or one it made (a `with` around the run, or passing
the code on to a discharger — `else`, `catch`). "Ride" and "supply" are no longer concepts. A definition may discharge
the **very effect it declares**, which is what lets `describedAs` rewrite its body's failure message. A function-typed
code parameter's clause is the callback's own scope (`onError uses *: E => A`).

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or thread
through, so the entry simply never joins the clause and there is nothing to spell as a negative effect. **Nesting
order at the run site decides interaction** — `runStateToPair(s, runThrow(c))` versus `runThrow(runStateToPair(s,
c))` is the difference between state surviving a `raise` and not — and there is no canonical form for the compiler
to choose. A frame is a **handle's field**: a helper's own `catch` cannot intercept its caller's `raise`, because the
caller's block captured the caller's handle, and an undischarged control effect at `main` is a compile error (D19).

**Three platform-private primitives, and nothing else.** A finishing clause is a non-local exit; a stateful
implementation threads a value through calls that never mention it. Neither is expressible in a strict pure core, so
each target ships **escape** (`escapeInternal(body, onExit, onValue)` — on jvm a fresh frame object handed to `body`,
exited by identity; on the compile track an evaluator intrinsic of the same shape), **cell** (`withCellInternal(initial,
body, combine)` — a one-slot array local to the call, handed to `body`) and **loop** (`foreverInternal`). The control
effects' single implementations are **named** and written over them (`escaping`, `aborting`, `holding`, `writing`,
`providing`), and each platform discharger binds its own with an ordinary `with` over the frame its primitive made —
`escapeInternal((frame: ThrowFrame[E]) -> obj with escaping(frame), …)` — so a primitive gives no effect (D25, D26).
Every discharger over it (`catch`, `else`, `mocked`) passes its code on to that run and names nothing. A guarded
signature's uncovered `raise` is read inside the compile track's `runThrow`, an uncovered `abort` as `… else raise(…)`
inside it (`WellKnownTypes.guardDischargers`), so the guard read gets one verdict shape. The primitives are private
because a public cell is Landin's knot (`termination/PurityGuardTest` exists to keep it out), so
`runThrow`/`runAbort`/`runStateToPair`/`runWriterToPair`/`provide` are **body-less in the base and bodied per
platform**, while `catch`/`else`/`runStateToValue`/`runStateToFinalState`/`runWriterToValue`/`runWriterToLog` are
ordinary base bodies over those. Eliot has no layer-private visibility, so each jvm module needing a primitive declares
its own copy, which also separates the frames for free. An implementation with parameters is always **named** (D25).
An **ability** has no named implementation and is never bound with `with` (D29). A binding is mangled by the backend
as its whole identity — ability, module-and-pattern hash, name.

**Every effect is bound the same way; only what is bound differs** (D26). A control effect (`Throw`, `Abort`, `State`,
`Writer`, `Dep`) has one named implementation per platform, bound by its discharger's `with`; `Inf` and the
interpretation effects (`Console`, `Log`, `FileSystem`, `Process`, `Environment`) have the base's `standard…` ones,
which the entry point binds and a test replaces. A description of what the platform ships, not a bit in the language.

**Clauses are the user surface and the verifier's vocabulary — they never flow back into types.** `EffectRow` is
declaration metadata (like `paramConstraints`) and a handle is an ordinary value parameter, so `unify` sees
`Function[A, B]` on both sides of a code parameter and a value alike. There is **one verifier**, the scope check, in two
halves: by name in the `row` walk, by entry in the hole rule; its other direction is "This value declares the effect
'X' but does not perform it" — a handle parameter no hole takes. *Forward what is declared, derive what is done.* The
post-mono second verifier was **retired by D7 (2026-09-10)**; **do not grow a second effect verifier.**

**The checker holds exactly one effect rule, the hole**, and the read of a block's handed types beside it; everything
else an effect does there is an ordinary ability method on an ordinary type parameter. What lives next door and must
**not** be deleted as effect machinery is `check/CarrierKindChecker`: `verifyCarrierKinds` is the only thing rejecting
a `[F[_]]` binder instantiated at a fully-applied proper type, and with it off that program silently compiles. It is a
*kind* system, measured, not assumed. Rendering has nothing to invert — `GroundValueRenderer` prints what the user
wrote, and LSP hover leaves handles out (`TypeHintIndex`).

**A named implementation is the testing injection point** (§6). Production code that declares `uses Console` names no
implementation, so whoever runs it decides — the synthesized entry point in production, one `with` in a test. A
double is **one declaration** in the test module: no type to hang on, no colocation, no coherence question. **A double
cannot cheat**, because a user module cannot declare a native and the platform's are private to its layer — an
implementation reaches the world only through effects **its own clauses declare**, which it captures at the binding
site. **Interpretation is per effect, not per program**: `body with mockConsole with mockFileSystem` leaves everything
else bound as its caller binds it. `eliot-test` is the worked framework: a suite declares only its return type — the
row alias `type Test = {Writer[List[TestResult]]} Unit`, widened with a clause where its cases perform (`def testCases
uses Console: Test`) — and an author's own discharge word lists the faked effects on its slot and binds the doubles with
a `with` around its run of the code (eliot-test's `mocked`), so a faked case writes no fixture at all.

**A set of effects has no name** beyond the row alias (§2.4, D20a open). What survives of v5's ability-requires-
ability is the ordinary **superability closure** on a `~` constraint — `~ A` is closed under what `A` itself
requires of the parameter this use bound to this binder (`ValueResolver.superConstraints`, transitive and
idempotent).

**`&` is a standard-library name, and an ability resolves like any other value** (§2.5; full user-space `~`/`&` is
decision D3, blocked). The combinator is `infix left type &[A, B]` in `eliot.lang.Ability` (prelude, so ambient): the
parser accepts *any* operator between two `~` constraints and `ValueResolver.resolveCombinator` looks the name up in
the ordinary dictionary, requiring `WellKnownTypes.abilityCombinatorFQN` — so a module declaring its own `&` takes the
name back and gets a diagnostic. It rides ast→core on `UnresolvedAbilityConstraint.combinedBy` purely to reach that check and
is dropped there. `~` stays reserved — it is a binder marker like `:`, not a name. Alongside it,
`ValueResolverScope.getAbility` is a keyed lookup of the ability's **marker** (`QualifiedName(n, Ability(n))`), not a
scan of `dictionary.values` — **do not reintroduce the scan**, and do not add a second lookup path for ability names.

**Ambient scope.** The whole `eliot.effect` package is auto-imported: `ModuleName.effectSystemModules` joins the
`eliot.lang` prelude in `defaultSystemModules`, in a **weak** tier — an explicitly imported module is deduplicated,
and an ambient name colliding with a local declaration or explicit import is silently dropped (locals always win, so
the prelude can grow without breaking code), while explicit imports keep the strict shadowing errors.

**What has no spelling, on purpose.** A stored effectful computation (a field holds a value; store data and interpret
it — `docs/effects.md` §2.3, and D20c for one handled where it runs) and a native that keeps a callback (the
platform's axiom). One gap is carried, fail-safe: a function-typed code parameter's own entries
(`f uses *, E: A => B`) land on the codomain the callee never hands a handle for, so code using `E` there is rejected.

**Cornerstone fidelity**: this is *more* types-are-values-faithful than the carrier was, not less — a handle's type is
an ordinary type parameter, a handle an ordinary value of an ordinary `data`, an operation an ordinary ability method,
monomorphization clones along the ordinary key, and the hole rule compares by the one evaluator's definitional
equality. No side channel does type-like work behind the type system's back, and no kind or sort is added to the type
language: `uses` is a clause on a declaration, never part of a type.

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
(`effect Inf { def forever(step uses *: Unit) uses *: Unit }`, ambient like all of `eliot.effect`) rather than a bespoke
termination lattice — there is **no `Terminating` token**; termination is simply `Inf`'s *absence* from the `uses`
clauses. Because a recursion-free core cannot itself diverge, `Inf` can **only originate on a native** (the loop
primitive, `foreverInternal`), and it propagates to callers for free through the ordinary scope check — a
`uses Console`-only function calling `forever` is rejected at that reference. `Inf` is **run, not discharged**: it is
the one effect that may legitimately reach `main` undischarged, where the entry point binds the platform's
`standardInf` (`while(true)` on jvm) and it denotes a deliberate non-terminating program — a server or firmware
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
