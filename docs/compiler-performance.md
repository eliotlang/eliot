# Compiler performance: a cold build in seconds

Status: **proposal, 2026-10-24.** The measurements are real; the ideas are not built, and nothing here is decided.
Questions P1–P6 (§5) await Robert. §6 records the first experiment, the checker on virtual threads: it was **7%
slower**, and it weakens I2 as a processor-by-processor migration. §6 also corrects how §1's absolute times should be
read. §7 records the second, 2026-10-26: §1's third in the run loop reproduces, and it was mostly the `CompilerIO`
transformer stack. **One reader over `IO` instead of it makes a cold build 23% faster**, every jar byte-identical, with
no processor changed; it is adopted. §8 records the third, the cache save's hashing: it was the save's memo of values,
not fact keys, and memoizing by identity instead makes a cold build **16% faster**; adopted. §9 sizes the cache by fact
type: 83% of it was the `Map` codec writing each file's dictionary into every value, and sharing it makes the store
79% smaller and a cold build **12% faster**; adopted. Storing no value for a pure one-to-one stage saves little. §10
profiles what was left and adopts three changes: cats-effect tracing off, the cache encoded behind the compilation on
a thread of its own, and the index read and written through a buffer. A cold build is **23–27% faster**, a warm one
**47%** and an edited one **33%**.

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

**Why the engine is on cats-effect, and why Loom changes the answer** (Robert, 2026-10-24): generators are meant to
run in parallel, and a processor that wants several facts should be able to request them all at once, with thousands
of facts in flight; OS threads cannot carry that many, fibers can, and the sequential schedule today is only for easier
debugging. That intent stands, and it separates two things cats-effect currently provides together. **A fiber per
fact** is what parallelism needs, at a fact's granularity, and 67 000 of them are not the cost. **Every step inside a
fact written as an `IO` program** is the cost (cats data structures are 36% of all allocated bytes: `EitherT`,
`IO.FlatMap`, `AndThen`, `IO.Map`, `IndexedStateT`, `Kleisli`), and it exists only because, before Loom, the only way
to wait for a fact without blocking an OS thread was to suspend a monadic computation. On JDK 25 a virtual thread
parks cheaply on a blocking read (virtual threads final since 21, no pinning on `synchronized` since 24, `ScopedValue`
final in 25), so a direct-style `getFactOrAbort` that parks on the fact's in-flight latch keeps the design: parallel
requests (`getFactsInParallel`, one virtual thread each), thousands in flight, a sequential mode for debugging on one
carrier thread. A virtual thread also has a real stack trace, which a fiber's work does not: 74% of the cats
allocations in the profile have no Eliot frame to attribute them to. The engine can serve both kinds of processor
while they are migrated, an old one as `IO` and a new one on a virtual thread blocking on a future completed from the
engine's deferred, so the checker can go first and be measured alone. To check first: that every runtime the compiler
runs on (the launcher, IntelliJ's for the LSP, CI) is JDK 21 or later.

> **Tried 2026-10-24 (§6): the checker alone on virtual threads is 7% slower.** Its 9 822 checks make 171 017 fact
> reads, which still run through the `CompilerIO` stack. So the checker cannot be migrated and measured on its own
> through this bridge, and a gain from I2, if there is one, has to be measured with the fact reads direct too.
>
> **Tried 2026-10-26 (§7): `CompilerIO` as one reader over `IO` is 23% faster**, with fibers and `IO` kept. Most of
> the run loop's third was the transformer stack's binds, so I2's remaining gain is measured from 31 s, not 40 s.

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
| P2 | May processor code leave cats-effect: direct-style queries on virtual threads, exceptions for abort, `IO` only at the edges, parallel requests kept? | Yes (I2). The per-step `IO` interpretation is a third of the CPU; parallelism needs a fiber per fact, which a virtual thread per fact gives without it. Migrate one processor at a time, the checker first. |
| P3 | May the compiler become concurrent? | Yes (I3), with sorted error output so a build's report and its jar never depend on the schedule. |
| P4 | Check once and instantiate by substitution, falling back to today's per-instance check where a generic check stalls? | Yes (I6). Every manifest instantiation still has every residual obligation decided, which is what *Use-Site Verification* requires. |
| P5 | Generated meta code as one generic definition over a type's shape, rather than per type? | Yes in principle (I7); first find what Eliot lacks to express code generic over `$Fields`. |
| P6 | Share compile-track elaboration with the runtime track where no overlay name is reached? | Investigate before deciding (I8): it needs a correctness argument. |

Not proposed, measured: a resident daemon or a native image for the CLI (a warm JVM saves 14%), and AppCDS (declined
2026-08-06).

## 6. Experiment 1: the checker on virtual threads (2026-10-24)

Robert asked for the checker to be tried on virtual threads and measured, as I2's mixed-mode migration proposed
(*"the checker first"*). Result: **every jar byte-identical, and 7% slower.** The code is not on `master`; it is kept
in the local stash *"perf experiment: the checker on virtual threads (docs/compiler-performance.md §6)"*.

### 6.1 What was built

The checker is written against one type alias, `CheckIO[A] = StateT[CompilerIO, CheckState, A]`, through five helpers
(`get`, `pure`, `modify`, `inspect`, `liftF`), and is run from one place (`TypeStackLoop.process`). The experiment
changed only those:

- `CheckIO[A] = Context => A`, where `Context` holds the `CheckState` and the accumulated errors as mutable fields. A
  bind is cats' `Function1` monad, so a `for` is plain function calls; an abort is an exception. The 2 353 lines of
  checker compiled against it **unchanged**. Two call sites changed: `TypeStackLoop.process` and one test running a
  `CheckIO` directly.
- `CheckIO.run` starts the check on a **virtual thread**, and the processor's fiber waits on a one-slot mailbox.
- `liftF(compilerIO)`, which is how the checker reads a fact or registers an error, runs that `CompilerIO` with the
  check's own `CompilationProcess` and error chain, so dependency tracking, cycle detection and error accumulation are
  exactly as before. Version 1 handed every lifted `CompilerIO` to the processor's fiber and parked the virtual thread
  until the reply came. Version 2 first runs it on the virtual thread with `IO.syncStep`, which runs synchronously up
  to the first async boundary, so a memo hit completes in place, and hands only the remainder to the fiber.

### 6.2 What was measured

On eliot-build, cold, with jars byte-identical in every run:

- **9 822 checks make 171 017 lifts**, 17 per check. Counted where each closure is built, since a `Function1` monad
  runs a closure after its builder's frame has returned, which hides the caller as cats-effect does. They are all fact
  reads: `ensureBinding`'s `fetchBinding` 49 024, `ensureBodyBindings`'s `BodyValueReferences` 49 024,
  `inferValueReference`'s `SaturatedValue` 37 271, `processValueMono` 13 197, `processSignatureTwin` 9 765,
  `CarrierKindChecker.recordCarrierFlexes` 5 673, `flippedCalleeSignature` 4 272, the ability resolver's searches
  2 344, `Track.implBindings` 254, `HandedBinders.read` 193.
- **Version 1** (every lift handed off) made the checker processors slower in a `--statistics` run: the compile track
  7.7 → 9.2 s, the runtime track 3.4 → 4.9 s.
- **Version 2** (`syncStep` first) handed off only 2 685 of the 171 017 lifts (1.6%). Cold compiles alternating
  baseline and experiment, each from its own copy of `lang`'s classes, no statistics:

| | run 1 | run 2 | run 3 |
|---|---|---|---|
| baseline (`8bea273a`) | 37.0 s | 37.7 s | 37.8 s |
| checker on virtual threads | 40.5 s | 40.5 s | 39.8 s |

### 6.3 What it means, and what it does not

- **Migrating the checker alone does not pay**, and that is the finding: the checker crosses into `CompilerIO` 17 times
  per check, and every crossing still runs the whole `ReaderT`/`StateT`/`EitherT`/`IO` stack, now through `syncStep`'s
  interpreter and a `SyncIO` run as well. Whatever the direct-style binds saved, the crossings cost more. So I2 cannot
  be migrated and measured one processor at a time through a bridge: the fact read is the hot crossing, and it has to
  be direct as well, which is the engine's part of I2.
- **Why exactly it is slower is not established.** A JFR profile of both versions was being taken when the work was
  stopped to record it here. The plausible causes are untested: `syncStep`'s per-call cost on 168 000 synchronous
  reads, the `Function1` monad's closures and `cats` instance dispatch, and a virtual thread per check (9 822).
- **It does not refute §1's attribution.** It shows the checker's own binds are not where the run loop's 33% is, or
  not enough of it to outweigh this bridge. The run loop's samples have no Eliot frame (§1), so they belong to whoever
  builds `IO` values: processors, the engine and the lifted reads alike.
- **The next experiment that would decide I2** is the fact read itself: a synchronous memo-hit path in the engine
  (`getFact` answering an already-computed fact without building an `IO`), measured with the existing processors
  unchanged.

### 6.4 Correction: §1's absolute times vary with the machine's load

The baseline compiler, unchanged, took 53.7 and 53.9 s at about 15:00 (§1, and `docs/meta.md` §16) and 37.0–37.8 s at
about 21:00, with nothing else heavy running then. Other jobs ran on the machine during the day, and a busy machine
lowers a single-threaded compile's clock speed. So an absolute time is only comparable with others taken in the same
session: the alternated comparisons (v0.18 against `8bea273a` in `docs/meta.md` §16, and §6.2 here) and the shares
from one profile (§1) stand, while §1's "54 s" and §4's starting point are this machine on a busy afternoon. The goal is
unchanged: 37 s is still 15–20× from a couple of seconds.


## 7. Experiment 2: one reader over `IO` instead of the transformer stack (2026-10-26)

§6 left two readings open: §1's 33% might not reproduce, or it might be real but not in the checker. This experiment
re-measures it and then removes the part §6 could not reach. Result: **the 33% is real, and a flat `CompilerIO` takes
a cold build from 40.1 s to 31.0 s (−23%), every jar byte-identical**, with fibers, `IO` and the engine unchanged.
**Adopted on `master` 2026-10-26** (§7.5), as Robert decided.

### 7.1 The 33% reproduces

A JFR profile of `d38d4ac6` (code identical to `8bea273a`), taken on a quiet machine (40.5 s cold, 3 199 samples), with
each sample classified by the frame it is executing:

| the executing frame | share |
|---|---|
| `cats.data` (`IndexedStateT`, `AndThen`, `Kleisli`, `EitherT`), the `IO` run loop, cats-effect tracing (`RingBuffer`, `ClassValue`), lambda allocation | **35%** |
| structural `hashCode`/`equals`, inside the cache save | 21% |
| structural `hashCode`/`equals`, elsewhere | 6% |
| the rest of the cache save (collections, encoding, i/o) | 15% |
| Scala collections, outside the save | 8% |
| **Eliot's own code** | **7%** |

Of the 35%, 27 points have **no Eliot frame on the stack** (§1's measure, now 30% against §1's 33%) and only 2.4
points have the checker on it. So §6's finding is explained: the checker's own `CheckIO` binds were a small part. The
run loop interprets binds after the frame that built them has returned, and most of them were never the checker's.

### 7.2 What was built

`CompilerIO` was `ReaderT[StateT[EitherT[IO, Chain[CompilerError], *], Chain[CompilerError], *], CompilationProcess, *]`:
every bind went through three transformer layers, each allocating its own closures. The experiment changed only its
representation:

- `CompilerIO[T] = Kleisli[IO, Context, T]`, one reader over `IO`. `Context` holds the `CompilationProcess` and the
  accumulated errors in a mutable field.
- An abort raises an `Aborted(errors)` exception without a stack trace. `runCompilerIO(process)(io)` gives the engine
  the same `Either[errors at the abort, (errors, value)]` it read from the stack before, and `recover`/
  `recoverWithAborted` run their computation in a fresh `Context` and merge its errors back, as the isolated
  `StateT` run did.
- `getFactOrAbort` is one `Kleisli` around `process.getFact(key).flatMap`, with no lifting through layers.

Four files changed: `CompilerIO.scala`, one line of `IncrementalFactGenerator.regenerate`, and the two wrappers that
swap the process under a processor (`TimedCompilerProcessor` for `--statistics`, `TrackedCompilerProcessor` for
`--visualize-facts`), through a new `withProcess`. **No processor changed**: the `StateT` layers over `CompilerIO`
(`CheckIO`, `ValueResolverScope`, `UsedNamesState`, `TypeState`) and every `Sync`/`LiftIO` use (`.to[CompilerIO]`,
`Logging`) compile against it as before. The tests that run a `CompilerIO` by hand (`ProcessorTest`,
`SequentialCompilerProcessorsTest`, `AbilityResolverBindingTest`, the LSP's `VirtualFileSystemTest`) were not ported,
so the suites were not run.

### 7.3 What was measured

On eliot-build's launcher, cold, alternating, each compiler from its own copy of the classes, the jar byte-identical
(`a74475df…`) in every run:

| | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| baseline (`d38d4ac6`) | 41.6 s | 40.5 s | 38.2 s | 40.1 s |
| flat `CompilerIO` | 31.2 s | 30.5 s | 31.3 s | 31.0 s |

- **Diagnostics are the same.** eliot-build with an undefined name in `Command.els`: both compilers print the same
  three diagnostics (the name, reported twice, and the rejected `usage` that reaches it), byte for byte.
- **The profile** of the flat version (31.8 s, 2 416 samples): samples executing cats code fall from 1 118 to 463, and
  the cache save stays at 1 131 to 1 143. The cache save is now **47%** of the build. The cats code that is left is
  the run loop (7%), `cats.data` (6%, the `StateT` layers above, `CheckIO` the largest), tracing (4%) and lambda
  allocation (2%).
- **Tracing off on top** (`-Dcats.effect.tracing.mode=none`): 30.4 → 29.6 s (two alternated pairs), −3%. §1's −10%
  was mostly the transformer stack's binds being traced.

### 7.4 What it means

- **The transformer stack, not fibers or `IO`, was most of the run loop's third.** Flattening it keeps everything
  Robert's rationale for cats-effect needs (§3 I2): a fiber per fact, `Deferred`s, parallel requests. So it answers P2
  partly without leaving cats-effect, and it is a precondition for I2 rather than a rival: whatever a direct-style
  engine would gain is now measured against 31 s, not 40 s.
- **It is small enough to adopt as it stands**: four files, the four tests above ported to `runCompilerIO`. One
  decision: the error buffer is a plain field, which is right while a generation runs sequentially (every one does
  today), and has to become an atomic reference the day a processor starts sub-computations in parallel within one
  generation.
- **The next target is the cache save, now nearly half the build**, and 62% of it is structural hashing and equality
  (702 of 1 131 samples), mostly of fact keys: I1 (interning), or first §1's cached `hashCode` in the four key classes,
  which took 5.4 s off the save there.

### 7.5 Adopted (2026-10-26)

Robert decided to adopt it. What changed from the experiment:

- **Every read of the error buffer is deferred to run time**: `registerFactIfClear`, `abort`, `currentErrors` and
  `withProcess` read it inside `IO.delay`/`IO.defer`. Applying a `Kleisli` is not running it, and combinators such as
  `map2` apply the second computation's function before the first one's effects have run, so an eager read could have
  seen errors not yet registered. The experiment had this hole in `registerFactIfClear`; its jars did not show it.
- `withProcess(wrap)(body)` hands `body` the wrapped process, so `TimedCompilerProcessor` reads its timings from the
  process it made rather than from a variable set on the side.
- `Aborted` is private to `CompilerIO`; only `runCompilerIO` turns it into the `Left` the engine reads.
- The five tests that ran a `CompilerIO` by hand (§7.2's four, plus `ImplementationNameResolverTest`) run it through
  `runCompilerIO`.

Verified: every suite green (`./mill __.test`); the example sweep (`scripts/example-sweep.sh`, 47 modules) identical,
line for line, to the baseline's, jars, instruction counts, exit codes and output included; `--statistics` and
`--visualize-facts` work. eliot-build's launcher, cold, alternating, jar `a74475df…` every time:

| | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| baseline (`d38d4ac6`) | 38.7 s | 38.4 s | 38.1 s | 38.4 s |
| adopted | 30.9 s | 30.4 s | 30.3 s | 30.5 s (−21%) |

**Found on the way, not caused by it:** `jvm.test` logs 19–25 `IllegalStateException`s from `MetaInterpreter` ("A
closure's path leads to no node of its owner's body"), each a `DerivedMeta` generation that the engine turns into a
decline, on `master` as with this change. Which keys fail **differs between two runs of the same code** (two runs of
`d38d4ac6`: `Abort::escapeInternal` 5 then 2, `Throw::escapeInternal` 0 then 3, `Function::.` 10 then 6), so a
derived meta's outcome depends on something besides its key. The suites stay green because nothing asserts on it. It
belongs to `docs/meta.md` §17.3 (closure addresses), not here, and is open.

## 8. The hashing fix: the save memoizes by identity (2026-10-26)

§7.4 named the next target: the cache save, 47% of a cold build, 62% of it structural hashing and equality, which §1
read as mostly fact keys. **It was not the keys.** It was the store's own memo of the values it encodes, and keying that
memo by identity takes a cold build from 32.1 s to 26.9 s (**−16%**), every jar byte-identical.

### 8.1 Where the hashing was

A profile of `39029d1f` (34.7 s cold, 2 648 samples), each hashing sample attributed to the nearest Eliot frame above
the outermost `hashCode`/`equals`:

| where the save hashes | samples |
|---|---|
| `ContentAddressedOutput.shared` and `remember`: the memo `HashMap[Any, ObjectId]` of every value written | 627 of 779 |
| case classes' own `hashCode`, reached from those (`Sourced`, the expression trees) | ~130 |
| `Located.keyLocation`: the map from fact keys to their offsets | 13 |

`shared` is called on every product and sum on the way down a value, and looked the value up in a map keyed by
**structure**. A case class's hash walks the whole value and a hit compares it field by field, so a value's subtree
was walked once for itself and once more for each of its ancestors: the cost of a tree of depth *d* was its size times
*d*. Fact keys already cache their hash (`CompilerFactKey`), which is why keys barely show.

Removing it does not remove all of that time: an equal but distinct instance, which the structural memo found, is now
encoded and digested again. By sample share the save took about 15.1 s, 11.7 s with the identity memo (−3.4 s, close to
§1's −2.8 s for the same experiment), and 9.5 s with §8.2's other two changes; samples in SHA-256 rose from 45 to 98.

### 8.2 What changed

Three files of `compiler/cache/codec`, nothing outside the cache:

- `ContentAddressedOutput` memoizes by **identity** (`java.util.IdentityHashMap`), and keeps the offset beside the id,
  so a repeated instance costs one lookup where it cost two. An equal but distinct instance is
  encoded again, comes to the same id, and is stored nowhere twice: dedup was always the id's job (`offsets`), and the
  memo only saves work. Encoding is now linear in the number of distinct instances.
- `ObjectIdOutput`, which hashes a recomputed value for the warm build's equality cutoff, memoizes the same way.
- `ObjectId.of` keeps one `MessageDigest` per thread instead of looking SHA-256 up by name for every object, a
  provider search that showed in the profile once the hashing was gone.

### 8.3 What was measured

Verified: every suite green (`./mill __.test`), and the example sweep (`scripts/example-sweep.sh`, 52 modules, 46 with a
jar) identical, line for line, to `39029d1f`'s. eliot-build's launcher, alternated in one session, jar `a74475df…` every
time:

| | run 1 | run 2 | run 3 | mean |
|---|---|---|---|---|
| `39029d1f` | 32.0 s | 31.4 s | 33.0 s | 32.1 s |
| identity memo only | 28.3 s | 27.8 s | 29.1 s | 28.4 s (−12%) |
| + offset beside the id, one digest per thread | 27.2 s | 26.7 s | 26.9 s | 26.9 s (−16%) |

- **Warm, nothing changed**: 5.8–5.9 s against 5.9–6.1 s, the same; a warm build saves almost nothing.
- **Incremental**, one string literal changed in `Command.els` and changed back: 13.4 and 13.4 s before, 10.6 and
  11.1 s after, with the same jars as a cold build of each source.
- **The save** falls from 44% of the samples to 32% (673 of 2 091). Hashing is gone from it; what is left is the
  per-object cost of 61 MB of small objects: a `Frame` of four streams per object, every byte through two
  synchronized `ByteArrayOutputStream`s, the identity map's memory traffic, and a SHA-256 per object.
- **Cached hashes in the engine's key classes do not pay any more.** `ValueFQN`, `ModuleName`, `QualifiedName` and
  `GroundValue.Structure` with a cached hash, plus a hash check before a fact key's structural `equals`: 27.7 s
  against 27.8 s, noise. §1's 8% from them was the save's structural memo hashing those classes. Not adopted; the
  remaining structural hashing outside the save is 8% of the samples, spread over the engine's maps and the active
  chain's `List.contains`, and interning (I1) rather than more cached hashes is what would remove it.
- **The cache store is not deterministic between two runs of the same compiler**: two cold builds of `39029d1f` wrote
  61.3 MB and 62.6 MB of objects, with the same jar. Which facts a run produces differs, the same nondeterminism as
  §7.5's, so the store's bytes cannot serve as an identity oracle for a cache change; the jars, warm hits and
  incremental jars above are the check instead.

### 8.4 What it means

- §1 to §7 counted the save's hashing as "mostly fact keys" and pointed I1 at it. Most of it went with a memo keyed
  the way the store's design already implied: the id answers *is this stored?*, the memo only *have I encoded this
  instance?*. What identity gives up, finding an equal value without encoding it, is what interning (I1) would give
  back, since equal values would then be the same instance.
- The save is still a third of the build, now for its encoding mechanics rather than its hashing. That is I5's ground:
  writing fewer entries (a fact type cached only when recomputing costs more than loading), writing behind on another
  core, and a cheaper object encoding.

## 9. What the cache holds, and which values pay (2026-10-26)

Robert's question: a pure step from one fact to the next adds nothing a recomputation could not give back, so can the
cache stop storing those? A probe in the save (env-gated, thrown away afterwards) recorded, per fact key type, its
entries, the bytes its values appended and its dependency edges, on a cold build of eliot-build at `f811acc0`.

### 9.1 Where the 61 MB was

| fact | entries | value bytes | edges |
|---|---|---|---|
| `ModuleValue` | 7 269 | 32.1 MB | 230 179 |
| `UnifiedModuleValue` | 3 999 | 18.2 MB | 13 821 |
| `GeneratedModule` (the bytecode) | 50 | 3.3 MB | 8 332 |
| everything else, 40 types | 62 844 | 7.0 MB | 480 441 |
| total | 74 162 | 60.6 MB | 732 773 |

**83% of the store was two facts, and neither for its content.** Both carry their file's `dictionary`, and the
`Map` codec wrote its entries **inline**, into the body of the object holding it, rather than as an object of its
own (`FactCodec`'s `given FactCodec[Map[K, V]]`, unlike a product, a sum or a `String`, never passed through
`Output.shared`). So every value of a file wrote the file's whole dictionary again, about 4 KB a value, which content
addressing could not dedup because the dictionary was never a separate object. Routing the `Map` codec through
`shared`, one line, changes it:

| | cold | warm | objects | index |
|---|---|---|---|---|
| `f811acc0` | 26.2, 26.6 s | 5.9 s | 61.3 MB | 4.8 MB |
| `Map` shared | 23.2, 23.3 s (**−12%**) | 5.6 s | 12.9 MB (**−79%**) | 4.8 MB |

Alternated in one session, jar `a74475df…` every time, the incremental jar below identical to it. The probe's value
encoding fell from 6.1 s to 3.2 s. Sharing every other collection too (`Seq`, `List`, `Set`, `Vector`) changes nothing
(12.9 MB, 23.3 s): no other collection repeats across objects. **Adopted** (2026-10-26): every suite green, the
example sweep identical to `f811acc0`'s, and eliot-build cold, warm, edited and changed back with the jars above.

### 9.2 Dropping values the next stage could recompute

On top of the shared `Map`, a switch (`ELIOT_NO_VALUE`, experiment only) saved selected key types with no value, the
entry keeping its edges, which is the existing path for a fact without a codec: the drill validates through it, and
a dependent that must recompute regenerates it. Measured cold, warm with nothing changed, after one string literal
in `Command.els` changed (`edit`), and after it changed back (`back`). Each variant alternated with A in one
session; every jar identical to A's.

| variant: values not stored | store | cold | warm | edit |
|---|---|---|---|---|
| A: none | 17.7 MB | 22.9–23.6 s | 5.6 s | 10.0–10.5 s |
| B: 15 middle stages (front end, per definition, post-mono) | 11.2 MB | 20.5–20.6 s (−11%) | 5.3 s | 12.0–12.2 s (**+20%**) |
| C: B, but `ModuleValue` kept | 13.8 MB | 21.4–21.5 s | 5.4 s | 12.2–12.5 s |
| D: the 9 read only by the next stage of the same definition or file | 15.3 MB | 22.0–22.2 s (−4%) | 5.6 s | 10.8–11.0 s (+7%) |
| E: the 6 with one dependency each (`SourceTokens`, `SourceAST`, `CoreAST`, `BlockDesugaredValue`, `MatchDesugaredValue`, `ReconciledMonomorphicValue`) | 15.6 MB | 22.6–23.1 s | 5.7 s | 10.1–10.3 s |

- **A truly one-to-one step is free to drop, and worth little** (E): −2 MB and no time either way. Content addressing
  already took most of the saving the idea expects: a stage that changes a few nodes of a tree stores only those
  nodes and refers to its predecessor's objects for the rest, so a one-to-one fact costs what it changed, not what it
  holds (`BlockDesugaredValue`: 0.04 MB for 3 554 values).
- **Most "middle" facts are not one-to-one, and their values do two jobs the edges cannot.** A value is what other
  definitions read (`UnifiedModuleValue`, `OperatorResolvedValue`, `SaturatedValue`, `WovenValue` and
  `UncurriedMonomorphicValue` are read by ten processors or more), and it is the **equality cutoff** wherever a
  dependency moves while the value does not. `RecursionCheckedValue` has 13 edges a definition, to the bodies it
  reaches; an edit anywhere among them moves a dependency, a stored value recomputes equal and stops there, and a
  value-less one cannot be compared, so it counts as changed and everything after it recomputes for every definition
  that reaches the edit. That is D's +7% and most of B's +20%.
- So the rule for **I5's "cache what pays"** is three questions, not one: is the value read across definitions, is it
  a cutoff point (a fact with several dependencies, or one fanning a file out into definitions), and does it cost
  more to recompute than to load? Only a fact answering no to all three should go value-less. rustc's query system
  splits the same thing into two knobs, `cache_on_disk_if` (store the value) and `no_hash` (no fingerprint, so no
  cutoff). A fingerprint without a body, which this cache has no state for, would give D's size without its edit
  cost; at 2 MB it is not worth the state.

### 9.3 What it means

- The size question had one answer, and it was a codec, not a policy. With it, the store is 18 MB, and **the index is
  now 27% of it**: 733 000 edges. `ModuleValue` alone has 230 000. `ModuleValueProcessor` builds a file's dictionary
  once, from its `CoreAST`, its `ModuleNames` and the `UnifiedModuleNames` of every module it imports (about 30), and
  registers all of the file's values from that one generation; a fact registered during a generation is recorded with
  the generation's whole read set (`DependencyTrackingProcess.producedDuring`), so each value carries all ~32 edges.
  One dictionary fact per file would carry the 30 once, and leave each value two (`CoreAST` and the dictionary): about
  18 000 edges instead of 230 000, roughly 1 MB of index. The dictionary is already built once per file, so the gain
  is the index and its validation, not compiling.
- Fewer facts, not fewer values, is the aggressive direction (I4): an entry costs an index record, its edges, a key
  object and the engine's tax whether or not it has a value, and dropping values only shrinks the smallest of those.
- The save's remaining time is per-object encoding of what is now 13 MB, and writing it behind on another core (I5)
  removes it from the wall time whatever its size.

## 10. Tracing off, the save written behind, the index buffered (2026-10-26)

### 10.1 Where 22.5 seconds went

A profile of `ef5efcf0` (22.5 s cold, 1 691 samples), each sample attributed to the nearest Eliot frame:

| what the CPU is doing | share of samples |
|---|---|
| the cats-effect run loop with no Eliot frame on the stack, tracing's 6% included | 24% |
| the cache save, serially after the compilation; three quarters of it `ContentAddressedOutput.store` | 21% |
| the engine's bookkeeping: the fact map, `DependencyTrackingProcess`'s shared dependency map, name hashing | ~19% |
| the lexer and the parser, inside parsley | ~7% |
| compiling: checking, the meta channel, resolution, bytecode | ~29% |

### 10.2 What changed

- **Tracing is off** unless the command line sets `cats.effect.tracing.mode`: `Main` and `LspMain` set it before
  anything touches `IO`, since cats-effect reads it once, when its tracing class loads. §1 measured it at −10%, and it
  still is: 24.4 s against 21.8 s, alternated.
- **The cache is encoded behind the compilation.** A fact is immutable once it has its value, so the generator hands
  each one, registered or accepted from the cache, to a `ContentAddressedCacheBackend.WriteBehind`, which encodes it
  into the store's buffer on another thread. The save finds those facts already placed and only encodes what is left
  and writes the files. The encoding is made for the region the save expects to extend, read without the lock; the
  save checks under the lock that this is still so, and encodes from scratch when another compiler appended in between
  (`ContentAddressedCacheBackendTest` covers both paths). Only the CLI uses it: a server persists once, on shutdown,
  and would encode every run for the last one's save.
- **The index is read and written through a 64 KB buffer.** It was written through a `DataOutputStream` straight on
  the file stream, so each of its few million varint bytes was a `write` system call: **2.0 s** of the save, measured
  with timestamps once the write-behind had taken the encoding away. §1's and §8's profiles never showed it, because a
  JFR execution sample does not cover a thread in native code. The load read it the same way, byte by byte, which is
  most of what the warm build gained.

**The first write-behind made the compilation slower than the save it took off it.** It ran as a fiber on the compute
pool behind a cats `Queue`: each `offer` woke the waiting worker, and a worker busy with a batch held a compute thread
the compilation's fibers were queued on. The compilation's own CPU rose by a third (the checker's samples from 98 to
178), and a cold build was 5–10% slower although the save fell from 5.9 s to 2.5 s. A worker on a blocking-pool
thread that polls a `ConcurrentLinkedQueue` every 2 ms, so an `offer` is one lock-free enqueue and wakes nothing,
keeps up with the compilation (its queue is empty when the save asks it to finish) and costs the compilation nothing
measurable.

### 10.3 What was measured

eliot-build's launcher, alternated in one session, every jar identical to its baseline's (`a74475df…` cold, warm and
changed back, `073f213d…` edited):

| | cold | warm | edit | back |
|---|---|---|---|---|
| `ef5efcf0` | 24.1, 24.4 s | 5.8, 5.9 s | 10.3, 10.6 s | 10.6, 10.7 s |
| adopted | 18.5, 18.7 s (**−23%**) | 3.0, 3.2 s (**−47%**) | 6.8, 7.2 s (**−33%**) | 6.9, 6.7 s |

Three more alternated cold pairs: 26.1/25.0/26.1 s against 19.5/18.7/18.2 s (**−27%**); the machine's load drifts, so
only the pairs compare. Every suite is green, and the example sweep is identical to `ef5efcf0`'s, line for line.

**The store is 7% larger and the index 15%** (13.9 MB against 12.9, 5.5 against 4.8) with exactly the same entries
and edges (74 171 and 732 995, from parsing both indexes). Only the layout moved: the write-behind places objects in
the order facts are registered, so the front end's large values come first and the keys most edges name land past the
first megabyte, where a zig-zag varint takes 4 bytes instead of 3 (712 785 of the edges, against 106 175 before).

### 10.4 What it means

- The save is now 0.3 s of encoding what was not offered plus the files, and no longer worth a target of its own.
- The run loop's 24% (I2) and the engine's ~19% (I1, and the cheap fixes: a dependency set per generation instead of
  one shared map updated at every read, the chain's `List.contains`) are what is left of the tax, beside ~29% of
  compiling.
- **A CPU profile does not see the kernel.** Wall-clock timestamps around a phase are the check that a profile's
  share is the whole of it.

## 11. Where 17.5 seconds go, and what is next (2026-10-26)

A profile of `98244409`: eliot-build cold 17.3–17.9 s, jar `a74475df…`. With the save written behind, the samples
split by thread: 1 237 on the compilation, 415 on the write-behind worker (off the critical path), 43 in the save's
last half second (the index and the files). The compilation runs on about **0.75 of one core** of twenty: 65–90
samples a second, spread over up to twenty compute threads, one at a time. Its 1 237 samples:

| what the compilation's CPU is doing | share |
|---|---|
| a fact read's bookkeeping: `DependencyTrackingProcess.getFact` and `IncrementalFactGenerator.modifyAtomicallyFor`, with the key hashing and equality they cause | ~15% |
| the cats-effect run loop with no Eliot frame on the stack | 17% |
| the rest of the engine, and names hashed or compared elsewhere | ~7% |
| the lexer and the parser | 11% |
| compiling: `monomorphize` 21% (the meta interpreter 4%), the rest of the phases and bytecode ~24% | ~45% |

**A fact read** costs, besides its `IO` binds: a `Deferred` allocated before the fact map is even looked up, a CAS on
the one `Ref[Map]` of every fact; `chain.contains(dependency)`, a linear scan of the request chain with structural
equality of keys; and an update of the one global `Ref[Map[key, Set[key]]]` of dependencies, hashing both keys into an
immutable map of 67 000 entries. 196 samples (16%) have a key's `hashCode`/`equals` as their leaf, and 95 of those are
under these two methods. All of it is engine-local: no processor is involved, and a per-generation dependency set
merged once at the generation's end, a chain the read can test in constant time, and a hit answered before anything
is allocated keep its semantics. It is also what parallelism (I3) would contend on first, since every read of every
fiber updates the same two maps.

**Half of the lexer is parsley recompiling a parser per token.** `TokenParser.sourcedLexeme` is a monadic `for`, and
parsley's `flatMap` builds and compiles its continuation at each token (`DynCall` ⤳ `LazyParsley.computeInstrs`: 70
samples). Written applicatively (`lift3(…, position, markAsToken(parser), position) <* whiteSpace`), the build is
**0.8 s faster** (17.5/17.8 s against 16.5/17.1 s, alternated) and the jar is identical. Not adopted yet.
