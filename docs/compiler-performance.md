# Compiler performance: a cold build in seconds

Status: **proposal, 2026-10-24.** The measurements are real; the ideas are not built, and nothing here is decided.
Questions P1–P6 (§5) await Robert.

The goal (Robert, 2026-10-24): *"A cold compile of the build project should not take 1 minute. It should take a
couple of seconds actually. We need some very fundamental ideas to break through here."* eliot-build's launcher is
about 8 600 lines (4 472 in its 29 files, 4 137 in the three layers it compiles against). A cold compile takes 54 s,
about 160 lines a second. A couple of seconds is 3 000–4 000 lines a second, which is **20–25× faster**. No single
change gets there, so this document starts with where the time goes.

## 1. Where the 54 seconds go

All of it measured on `8bea273a`, compiling eliot-build cold into a fresh output directory, one compile at a time.

**It is not the JVM.** Four cold compiles in *one* JVM (a throwaway harness calling `Compiler.runCompiler` four
times, each into a new `-o`): 59.4, 52.3, 51.2, 50.9 s. A warm JVM saves 14%, so a daemon, a native image or class-data
sharing (AppCDS was measured and declined in August) cannot be the breakthrough. GC pauses total 1.0 s. The JVM keeps
1.9 of the machine's 20 cores busy, one of them the compiler.

**It is not compiling.** A JFR sampling profile (`-XX:StartFlightRecording=settings=profile` with
`-XX:FlightRecorderOptions:stackdepth=4096`; and **read it with `jfr print --stack-depth 4096`**, whose default of 5
frames silently truncates every stack) splits the 4 609 samples into disjoint parts, by whether the stack is inside
the cache save and otherwise by the nearest Eliot frame:

| what the CPU is doing | share of samples |
|---|---|
| the cats-effect run loop, with no Eliot frame on the stack at all: binds of the `CompilerIO`/`CheckIO` transformer stacks, fibers, tracing | **33%** |
| writing the incremental cache; 70% of it is structural `hashCode`/`equals`, mostly of fact keys | **33%** |
| the fact engine and the cache's bookkeeping outside the save (`processor`, `compiler`, dependency tracking) | 9% |
| structural `hashCode`/`equals` of `ModuleName`, `ValueFQN`, `QualifiedName`, `GroundValue` elsewhere | 3% |
| **the compiler**: checking, evaluating, interpreting metas, parsing, resolving, generating code | **22%** |

So about 12 s of 54 are compiling, and 42 s are administering facts. The program makes 67 037 facts, about eight per
line of source and a dozen per definition per track, and the per-fact tax is roughly (54 − 12) / 67 000 ≈ **0.6 ms**.
An ordinary hash-map lookup with an interned key costs about 20 ns.

Three experiments confirm the attribution (each jar byte-identical, each reverted afterwards):

| experiment | cold compile | the cache save |
|---|---|---|
| baseline, cats-effect tracing on (the default) | 60.7 s | 17.1 s |
| `-Dcats.effect.tracing.mode=none` | 54.9 s (−10%) | 16.8 s |
| + the save's value memo by identity instead of by structure | 53.6 s | 14.0 s |
| + a cached `hashCode` in four classes (`ModuleName`, `QualifiedName`, `ValueFQN`, `GroundValue.Structure`), instead of the cache-memo change | 50.5 s | 11.4 s |

A Scala case class recomputes its `hashCode` on every call, walking the whole value, so a deep key costs its size at
every map access. Fact keys hold `ValueFQN`s and lists of `GroundValue` type arguments, and they are hashed at every
`getFact`, every dependency edge, every cache-index entry and every dependency of every entry. Four cached hashes are
only a crude stand-in for interning, and they already take 8%.

**Where the 12 s of compiling go**, for later (from the per-processor statistics and the 2026-10-24 per-value probe,
`docs/meta.md` §16): type checking 11.1 s of processor self time, 7.7 s of it the compile track's 7 519 instance checks
of 1 667 distinct values (4.5 checks per value; the runtime track does 2.5); derived metas 5.2 s, 3.7 s of it stated
natives at 2–6 ms a fact; tokenizing and parsing 2.5 s; bytecode 2.8 s. Those figures include their share of the tax
above, so they overstate the work.

## 2. The diagnosis

The architecture is right, but its unit costs are wrong by two to three orders of magnitude. Demand-driven facts,
whole-program monomorphization from `main`, a content-addressed cache and one evaluator are each sound decisions. But
every fact pays for a fiber, a five-layer monad transformer (`CheckIO` is `StateT` over `CompilerIO`, which is
`ReaderT[StateT[EitherT[IO, …]]]`), a structural hash of a deep key at every touch, a dependency record and a
content-hashed cache entry, and the program makes 67 000 of them. Cost is roughly:

> **facts × tax per fact + work**, on **one core of twenty**.

Every idea below attacks one of those four factors. The first three are about the tax and are worth most. None
touches a cornerstone: every one keeps a single evaluator, use-site verification, and facts as pure functions of their
inputs.

## 3. The ideas

### I1. Identity is an integer: intern names, types and keys

Every identity the compiler compares — a module name, a qualified name, a `ValueFQN`, a ground type, a fact key — is
**interned** when it is first made: a global table hands out one canonical object, with a dense `Int` id, per distinct
value. Equality becomes reference equality and the hash becomes the id, so a map lookup costs the same for
`foldLeft[List[Pair[String, Int]]]` as for `Int`. This is how rustc treats `Symbol`, `DefId` and `Ty`, and it is
hash-consing for the ground types.

What it removes: most of the save's 70% of hashing, the 3% elsewhere, and much of the engine's 9%.
What it opens: an `Int`-keyed engine can use arrays and primitive maps; the cache index can store a key once and
refer to it by id; and two hash-consed ground types are definitionally equal exactly when they are the same object, a
free fast path in front of `unify` that stays exactly definitional equality. Interning must be session-wide and
thread-safe (I3), and the cache codec must re-intern on load.

### I2. A query is a function call: a direct-style engine

Today a fact is computed on a fiber the reader then blocks on, and processors (and the checker inside them) are
written in a monad transformer stack that the fiber interprets bind by bind. That is a third of the CPU, and since
nothing runs in parallel, it buys nothing.

Proposed: **`getFact` is an ordinary call** into a memoizing query engine, as in rustc's query system or salsa. A
missing fact is computed on the caller's stack; an abort is an exception, which the engine turns into a decline;
errors accumulate in the query's own buffer and are recorded with its result; a cycle is detected from the set of
active queries (`activeFactKeys` already exists for this). Processors keep their shape: `getFactOrAbort`,
`getFactOrError` and `getFactIfProduced` stay, with the same semantics, but they return values, not `CompilerIO`
values. The checker's `CheckIO` becomes plain state threaded through pure functions, as the evaluator already is.
`IO` stays at the world's edge: file reads, writing the jar, the console. Dependency tracking for the cache is a
side effect of the call, as `DependencyTrackingProcess` does today.

This is the largest change in the list, since every processor is touched, and it is mechanical: the processors are
already written as sequences of fact reads.

### I3. Twenty cores: a parallel scheduler

Once a query is a call over interned keys, independent queries can run at the same time. A query becomes a task on a
work-stealing pool, with an in-flight latch per key so two readers never compute one fact twice, as in Bazel's
Skyframe, which is a parallel demand-driven fact graph. The parallelism is already in the program:

- **every file's front end**: the path scan knows every file a build will read, and tokenizing, parsing and
  desugaring each one is independent;
- **every instance the monomorphization walk discovers**: checking one does not wait for its siblings;
- **every definition's derived meta**, which decision S already made a fact per call;
- **every class's bytecode**, and every cache entry's encoding.

Facts are pure, so the output does not depend on the schedule: the jar stays byte-identical, provided the order of
reported errors and of anything written is sorted rather than the order of completion. Twenty cores will not give 20×
(the walk from `main` is a chain of discoveries), but the front end and codegen are embarrassingly parallel, and 4–8×
over what I1 and I2 leave is a reasonable expectation.

### I4. A fact where a fact pays: coarser granularity

A definition passes through about a dozen facts per track (`ModuleValue`, `UnifiedModuleValue`, resolution, match
desugaring, operators, blocks, recursion check, row elaboration, saturation, rewrite, monomorphic check, weaving,
uncurrying, …), and each is dispatched, hashed, tracked and cached. A fact earns its tax in two cases only: when
**demand** makes it lazy (it is not computed unless something needs it), or when a **cache hit** saves more than the
fact costs. A pure transformation of one definition from one phase to the next is neither: nothing demands its middle
stages separately, and a hit on a middle stage saves almost nothing.

Proposed: a phase chain of pure functions **fused into one fact** where nothing between the steps is demanded on its
own. Concretely, the front end becomes one fact per module (source → tokens → AST → core → names), resolution through
the recursion check one fact per definition, and the facts that carry real identity stay as they are: the module
interface, the checked instance (its key is a monomorphization), the derived meta (decision S), the class. The
equality cutoff moves to the module interface: a change to a body that leaves every signature equal stops at the
module. That is what GHC's interface files do. Target: 5 000–10 000 facts instead of 67 000.

The LSP is the check: it reads `ResolvedValue`, `MonomorphicValue`, `ValueDoc` and positions, which a fused chain
still produces as fields of its one fact.

### I5. The cache writes what pays, linearly, off the critical path

Saving the cache is 17 s, a third of a cold build, for 66 MB: 4 MB a second. With I1 the writer hashes ids, not
structures; with I4 it writes a tenth of the entries. Two more rules:

- **cache only what is worth caching**: a fact type is cached if recomputing it costs more than loading it. Middle
  stages cost microseconds to recompute and kilobytes to store, and should not be cached at all. The per-entry probe
  that sized the cache by kind of value (`docs/meta.md` §16) extends to time recompute against load for every fact
  type.
- **write behind**: encode the cache on another core while bytecode is generated, and finish before the process
  exits. A cold build's wall time then does not include it.

Target: well under a second.

### I6. Check a definition once; instantiate by substitution

The runtime track checks each value 2.5 times on average, and the compile track 4.5 times: once per instantiation,
re-elaborating the whole body each time. That is how C++ templates work, and it is the reason C++ compiles slowly.

Proposed: elaborate a definition **once, generically**, with its type parameters neutral, as the ordinary abstract
check already does for an explicitly parametric definition (CLAUDE.md, *Use-Site Verification*). What cannot be
decided while a type parameter is neutral is recorded as a **residual obligation**: an ability resolution at an
instance type, a hole whose candidates depend on type arguments, a guard, a refinement demand. Instantiation is then
substitution into the elaborated body plus discharging the residuals at the concrete types. That is cheap, and it is
exactly the use-site verification the cornerstone requires: every manifest instantiation still has every obligation
decided. Where the generic check stalls, because a type is computed and needs a concrete argument to reduce (types are
values), that definition falls back to today's per-instance check. So this is an optimisation with a fail-safe
fallback, not a weaker checker. It also gives better errors: a mistake in a generic body is reported once at the body,
not once per instantiation.

### I7. Generate generically, not per type

Part II of the meta channel generates, for each `data` type, its record per constructor, its summary, its field and
reach accessors, its `Meta` instance's `join`, `widen` and marker, its `$Payload` and `$Handed$` companions. Each goes
through the whole pipeline, and each again at every instantiation: 14 MB of the cache and 3.3 s (`docs/meta.md` §16).
Most of that code is the same for every type, up to the type's shape, which `D$Fields` already states.

Proposed: **one generic Eliot definition per kind of generated code**, over the type's shape (`$Fields`), instantiated
per type, so the per-type part shrinks to the shape. With I6, the generic definition is checked once in total. Two
cases are the same idea at another level:

- a stated native's companion (2–6 ms per fact) is reduced at the callee's type arguments **once per instantiation**
  and the residual applied to each call's argument metas;
- a `$Payload` is reduced by the evaluator without being checked as a value at every instantiation (2 873 checks
  today).

Nothing here is a Scala reimplementation: the code stays Eliot, run by the one evaluator. What is open is whether
Eliot can already express code generic over a `data` type's shape, or what it lacks (P5).

### I8. The compile track borrows elaboration, not only bodies

The compile track checks 7 519 instances to the runtime track's 2 303, because a borrowed runtime body is elaborated
again in the compiler pool. For a definition none of whose transitive references is superseded by a `compiler/`
overlay, the two elaborations are the same. Sharing them, keyed by the overlay names the definition actually reaches,
would remove most of the compile track's duplicate checking. This needs a correctness argument before it is a
proposal (P6).

### I9. Small and certain

- **cats-effect tracing off** in the compiler's own runtime: −10%, measured, byte-identical. A single system
  property; it costs worse stack traces from the compiler's own fibers, which I2 removes anyway.
- **A hand-written lexer and a recursive-descent parser** with precedence climbing: 2.5 s of processor time for 99
  files is parser-combinator overhead on a cold JVM. Milliseconds are normal, and a loop-based parser also fixes the
  open TODO that 500 nested calls overflow the parser's stack.

## 4. The arithmetic, and an order

An estimate, not a measurement: today ≈ 42 s of tax + 12 s of work, on one core.

| step | tax | work | cold build |
|---|---|---|---|
| today | 42 s | 12 s | 54 s |
| I9: tracing off, cached hashes (measured: 50.5 s) | ~38 s | 12 s | ~50 s |
| I1 + I2: interned identities, direct-style queries | ~3 s (67 000 facts × ~40 µs) | ~9 s (the checker's binds lose `CheckIO`) | ~12 s |
| I4 + I5: coarse facts, a cache that pays | <1 s | ~9 s | ~10 s |
| I6 + I7 + I8 + lexer: less work | <1 s | ~3 s | ~4 s |
| I3: parallel on 20 cores (4× on the work, conservatively) | <1 s | ~0.8 s | ~2 s, plus JVM start (~0.6 s) |

The order follows the table: each step is independently measurable, keeps every jar byte-identical, and makes the next
one cheaper. **I1 and I2 first**, because the tax hides everything else. A processor's self time today is mostly its
share of the run loop and of hashing, so judging I6 or I7 before them would measure the wrong thing. I3 needs I2,
since fibers are the wrong unit of parallelism and a lock-free interned key table is the right one. Every step is
verified as the meta stages were: the example sweep with byte-identical jars, eliot-build's jar and its 368 tests, and
cold and warm timings of eliot-build against this document's baseline (54.9 s with tracing off, 50.5 s with the
cached hashes).

## 5. Questions

| | Question | Recommendation |
|---|---|---|
| P1 | Is per-phase fact granularity a goal in itself (incrementality, the LSP), or a means? | A means: I4. Fuse a chain where nothing demands its middle; keep facts at the module interface, the instance, the derived meta and the class. |
| P2 | May the engine's core leave cats-effect: direct-style queries, exceptions for abort, `IO` only at the edges? | Yes (I2). It is a third of the CPU, and the fibers buy no concurrency today. |
| P3 | May the compiler become concurrent? | Yes (I3), with sorted error output so a build's report and its jar never depend on the schedule. |
| P4 | Check once and instantiate by substitution, falling back to today's per-instance check where a generic check stalls? | Yes (I6). Every manifest instantiation still has every residual obligation decided, which is what *Use-Site Verification* requires. |
| P5 | Generated meta code as one generic definition over a type's shape, rather than per type? | Yes in principle (I7); first find what Eliot lacks to express code generic over `$Fields`. |
| P6 | Share compile-track elaboration with the runtime track where no overlay name is reached? | Investigate before deciding (I8): it needs a correctness argument. |

Not proposed, measured: a resident daemon or a native image for the CLI (a warm JVM saves 14%), and AppCDS (declined
2026-08-06).
