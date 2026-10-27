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
**47%** and an edited one **33%**. §11 profiles the result and fixes the fact keys' hash and the read path (−2%). §12
ranks what is left on **one core**: parallel compilation (I3) is the last step by Robert's decision, and a sequential
mode stays even then. §12 also measures I6 for signatures: almost every signature check of an instance is the substitution
of its type arguments into the value's parametric signature. §12.4 built that, measured a cold build **3–4% faster**,
and **reverted it**: the gain does not pay for a second path to a signature whose soundness is an argument. §14
sizes I2 and finds, on the way, that cats-effect's **twenty worker threads handing each fact's fiber to one another**
cost a quarter of a cold build: one worker makes it **23% faster**, with no other change. §14.4 builds I2 as a
synchronous engine with `CompilerIO` a plain function: another **16% faster**, adopted. Both together take eliot-build
from 17.9 s to 10.9 s cold. §15 starts parallel compilation (I3): the plan — a fact must be a function of its key, and a
processor hints what it will read instead of forking — and its first step, built: **no processor reads the request
chain any more**. Four did, each to answer differently inside a cycle, and none of them fired anywhere; the one cycle
a user can write, two instances constrained by each other, is now an error instead of an answer that depended on who
asked first.

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
**0.8 s faster** (17.5/17.8 s against 16.5/17.1 s, alternated) and the jar is identical. Adopted in `47848a12`.

### 11.1 The read path, done (2026-10-26)

**Most fact keys shared their hash with ten others.** A key's hash was the synthesized case class hash, which tells
classes apart only by `productPrefix`, and nearly every key is a class named `Key` in its fact's companion:
`ResolvedValue.Key(vfqn, platform)`, `UnifiedModuleValue.Key(vfqn, platform)` and nine more phases of the same value
hashed alike. Of eliot-build's 74 162 keys only 38 284 hashes were distinct, and 43 248 keys sat in collision buckets
of up to eleven, which every map and set of keys scanned with `equals`. `CompilerFactKey.hashCode` now seeds the hash
with the class name (not the `Class`'s identity hash, so it is the same in every run): 74 160 distinct hashes.

**A read touches only its own generation.** Three changes, all in the engine, no processor touched:

- each generation records its reads into a dependency set of its own, which the generator holds under the key from the
  generation's start (so recording is still eager), instead of every read updating the one map of every key's
  dependencies; the map is read as a whole only at the end, for the diagnostics and the cache;
- a fact already present is answered from one look at the fact map, with no `Deferred` allocated and no atomic update;
- the cycle check compares a key's remembered hash before its structure.

Measured, alternated, eliot-build, every jar identical, suites and the example sweep identical:

| | cold (9 runs each) | warm | edit | back |
|---|---|---|---|---|
| lexer fix only (`47848a12`) | 16.80 s | 2.80, 2.83 s | 6.26, 6.23 s | 6.38, 6.41 s |
| + key hash + read path | 16.47 s (**−2%**) | 2.74, 2.74 s | 6.26, 6.23 s | 6.07, 6.24 s |

**Far less than §11 estimated**, and the reason is in the profile after it: the read path's samples fell only from
~180 to ~150. What is left is the comparison itself, which no engine change removes. A processor builds a fresh key
for each read, so `HashMap.get` and `Set.+` on a hit compare it field by field with the equal key already stored
(`ValueFQN`, `ModuleName`'s list, a monomorphic key's ground types); and the cycle check still walks a request chain
hundreds of keys deep, testing hashes now instead of structures. Both are what interning (I1) is for: an interned key
compares by reference, and a chain of ids is a bit set. The changes are kept because the per-read update of one shared
map is what parallel fact generation (I3) would have contended on first.

**The CPU the profile does not see is the JVM's, not the scheduler's.** A cold build spends 58–60 s of CPU in 17 s,
while the compilation runs on about 0.75 of a core. Capping the processors with `-XX:ActiveProcessorCount=4` cuts that
to 46–48 s, so most of it is the JIT's and the collector's threads, and leaves the wall time unchanged (16.9/17.1/17.1 s
against 16.9/16.8/18.5 s, alternated): handing a fact's fiber to another worker thread is not where the idle quarter of
a core goes.

> **Corrected 2026-10-26 (§14):** four processors still leave four workers handing fibers to each other. With **one**
> compute worker a cold build is 23% faster, so the handoffs were a quarter of the build after all.

## 12. One core: what is left, and whether a signature check is a substitution (2026-10-26)

Robert, 2026-10-26: parallel compilation (I3) is the **last** step, after everything else, and even then a sequential
mode stays, because a parallel build's wall clock is harder to measure and every step so far was judged by alternated
sequential runs. So this section ranks single-core levers only.

### 12.1 Where 15.4 seconds go, per fact

eliot-build cold at `6b442ee4`: 15.4 s, jar `a74475df…`. A temporary print in `TimedCompilerProcessor` (reverted)
recorded each generation's self time under `--statistics`: 13.0 s of processor self time in that run, the facts told
apart by key (counts from the key's first 400 characters, so a few long keys merge). By fact:

| what | self time | facts | per fact |
|---|---|---|---|
| **type checking, both tracks** | **≈4.3 s** | | |
| ↳ compile track, `Signature` role (the signature of an instance, on either track) | 1.9 s | ~4 300 | 0.2–0.5 ms |
| ↳ runtime track, values | 1.1 s | 2 303 | 0.49 ms |
| ↳ compile track, values (generated and `$Payload` included) | 1.2 s | ~2 400 | 0.4–0.6 ms |
| resolution, row elaboration, operators, match desugaring, recursion check, blocks | ≈2.9 s | 3 554 each | 0.03–0.35 ms |
| front end: `SourceAST`, `SourceTokens`, `CoreAST`, `ModuleValue` | ≈1.7 s | 99 files | parse 6.7 ms, tokenize 4.7 ms a file |
| bytecode: `GeneratedModule` | 1.25 s | 50 | 25 ms a module |
| derived metas and meta demands | ≈0.8 s | ~2 900 | |

The same build's JFR profile (`rp.json`, after §11.1) puts 13.7% of the compilation's samples in a `hashCode`/`equals`
near the leaf (the top frames `CompilerFactKey.hashCode`, still structural at every call, and `BoxesRunTime.equals`) and
~17% in the cats-effect run loop. Ranked, on one core:

| lever | what it removes | rough gain | needs |
|---|---|---|---|
| **I6 for signatures** (§12.2) | ~3 400 of the ~4 100 signature checks of an instance | **1.2–1.5 s** (built in §12.4: 0.5–0.7 s; reverted) | P4 |
| I2, direct-style queries | the run loop | ≤2.5 s | the largest refactor in the list |
| I1, interning | the hashing and equality | ≤2 s | nothing; mechanical |
| I9, a hand-written lexer and parser | most of the front end's 1.1 s of parsing | ~1 s | nothing |
| bytecode | unexamined: 25 ms a module | ? | a profile |

### 12.2 A signature check of an instance is a substitution

Every instance `(value, type arguments)` on either track has its signature checked as a fact of its own, a
`Signature`-role `CompilerMonomorphicValue`, and each one runs the whole `TypeStackLoop` again: `.`'s signature is
checked 231 times in eliot-build, `Option::handleCases`'s 110. A partial-arity signature twin already reads back a
**parametric** signature, a binder with no type argument coming back as a `GroundValue.Param` (`bindTwinBinders`), so
there is an exact test of whether the instances needed their own check:

> for every concrete signature check with type arguments, also run the same check with none (once per value, its
> errors discarded), substitute the instance's arguments into the `Param`s, and compare with the concrete result.

The fact holds nothing else that depends on the arguments: a signature twin's fact is its key, its name, the signature,
no body and no `handed`. The probe (in `CompilerMonomorphicTypeCheckProcessor`, reverted) also ran when the concrete
check failed, to catch an instance whose own check rejects what the parametric one accepts:

| corpus | instances | same after substitution | binder-free (no `Param`) | different | parametric check fails | concrete fails, parametric accepts |
|---|---|---|---|---|---|---|
| eliot-build | 4 101 | 3 382 | 719 | 0 | 0 | 0 |
| the 52 examples | 2 602 | 2 194 | 403 | 4 | 1 | not probed |
| `lang.test` (1 061 tests) | 155 | 125 | 20 | 10 | 0 | 0 |
| `jvm.test` (816 tests) | 12 412 | 10 543 | 1 820 | 41 | 7 (+1 both fail) | 0 |

**Every difference is a guard's verdict.** Mostly from one arm, `publishStuckGuardCarrier`: with the binders neutral a
guard cannot decide, so the parametric check publishes its undischarged carrier (`Bool` for an `implement … where`
marker such as `Route`, `Coerce` or `InRange`; `Either[String, Type]` for a return guard such as `head`), and each
instance publishes its own verdict (`true`, `Right(String)`, `Left("empty")`). **But two are not**, and this section
first said they were (corrected in §12.4): `Route[S] where accepted(S)`, whose `accepted` runs `runThrow` over a
`raise`, read back as a definite `false` and a definite `true` through the *escalation*, each wrong for one instance. **Every parametric failure computes on a binder**:
`Box[I + 1]` over a value binder `I: BigInteger`, `pick[C: Bool]`. Both are cases a rule can recognise and leave to
today's check per instance.

In eliot-build the 4 101 instances belong to 719 values: 499 have one instance, 176 have two to twenty (1 234
instances), and 44 have more than twenty (2 368 instances). A parametric check costs about what a concrete one does
(median 0.28 ms).

**The probe found one trap.** The facts a parametric check demands report their own errors, outside the probe's
isolation: one jvm test ("a user constant from a value-position literal in a where guard") failed with *"Too many type
arguments."* while the probe ran, and passes without it. A parametric check is a guess the compiler makes for itself,
so its failure must decline silently and fall back, never reach the program's diagnostics.

### 12.3 What it means

The rule it suggests: check a value's signature **once**, with its binders neutral. If that read-back came from the raw
quote or the escalation (not the stuck-guard arm), every instance's signature is the substitution of its arguments;
otherwise, or if the parametric check fails, the instance is checked as today. Both ways out are fail-safe: a guard
falls back, and so does anything that computes on a binder.

Why it should be sound, for P4: a `GroundValue` can hold a binder only as a `Param` heading a spine. Anything that
**eliminates** a binder (a `match`, arithmetic, an ability dispatched on it, a guard's `fold`) leaves a stuck neutral,
which does not read back, so the parametric check fails and the instance falls back. An evaluation under a neutral that
nothing eliminated commutes with substitution. **That holds for plain evaluation only** (§12.4): the escalation threads
control effects and dispatches abilities, and settled `accepted(S)` to a verdict with `S` a placeholder. The measurements agree: 19 270 instances, no difference outside the guard
arm, and no instance whose own check rejected what the parametric check accepted, though that last column is thin,
since the failing programs in the suites mostly fail before their signature twins. Open for P4:

- **a higher-kinded binder's argument**: substituting `Int` for `F` in `F[Unit]` builds `Int[Unit]`; the concrete twin
  does not reject it either, and `CarrierKindChecker` in the value mono does and still would;
- **an error only a concrete twin would report**: none was seen;
- **where the substitution happens**: in the processor, keeping the 4 101 facts (the smallest change), or in the readers
  of the signature, which also removes the facts and their tax.

Estimate: the ~1.8 s those 4 101 checks cost becomes ~719 parametric checks (~0.3 s) and 4 101 substitutions of
microseconds, so **1.2–1.5 s, 8–10% of a cold build**, and a larger share in a larger program, whose instances grow
faster than its definitions. It is the one-signature step of I6. A body checked once is the rest of I6, and it is not
measured here.

### 12.4 Built, measured and reverted: a signature checked once, instances by substitution (2026-10-26)

> **Reverted** (Robert, 2026-10-26), in the commit after `38937a26`, which built it. 0.5–0.7 s of a 17 s cold build,
> within the run-to-run spread and nothing warm, does not pay for what it adds: a **second path** to an instance's
> signature, where signature unification left one at every arity; a soundness argument that **already failed once**
> (the miscompile below) and that the next native answering a placeholder definitely would break **silently**, not
> loudly; and three mechanisms the engine did not have (a process refusing reads, a flag tied to one arm of
> `processSignatureTwin`, a cycle check the per-value fact itself creates). Its gain also shrinks with every lever that
> cheapens a check (I1, I2, below), which is the larger question it found: what *one* check costs. Do not re-propose it
> without a new fact, such as a program whose values have many instances each, where the multi-instance rows below
> would dominate. The record stays as it was built:

Robert decided P4 for signatures (2026-10-26): reuse a signature checked with placeholders wherever that check
succeeded without computing on a placeholder. Built in the processor, as §12.3's smallest change:

- **`ParametricSignature(vfqn, signature: Option[GroundValue])`**, one per value, made by `ParametricSignatureProcessor`
  running the same compile-track check an instance's twin runs (`CompilerMonomorphicTypeCheckProcessor
  .checkOnCompilerTrack`), at no type arguments. A `Signature`-role instance with type arguments reads it and
  substitutes (`GroundValue.substituteParams`); everything else, and an instance whose value has `None`, is checked as
  before. The instance facts stay, so no reader changed.
- **What is substituted into**: only a read-back by the **raw quote**, plain evaluation, whose checked type is not a
  guard carrier (`TypeStackLoop.Result.substitutable`). The escalation and the stuck-guard arm never are.
- **What the parametric check may not read** (`PlaceholderRefusingProcess`): a fact whose key holds a `Param`, or one
  already in progress on the chain. Either read is answered `None` and the result discarded, and its errors are never
  reported, since they are not the program's. An instance does not read the parametric signature while that is itself
  being computed up the chain (`activeFactKeys`), which would close a cycle.
- **`None`, not a decline**: the first version declined, and the 13 declined values were regenerated by every warm
  build, with the instances behind them (warm +12%). An answer of `None` is cached like any other.

**The first version miscompiled.** Substituting into the escalation's read-back made
`EffectIntrinsicsIntegrationTest` ("let an exit pass through a discharger of another effect to its own frame") print
`inner/inner` for `outer/inner`: `implement Route[S] where accepted(S)` read back as one verdict for every `S`. §12.2's
probe had recorded that difference and the analysis had misattributed it; the raw-quote rule is what closes it.

Measured, eliot-build, every jar identical (`a74475df…` cold/warm/back, `073f213d…` edited), the example sweep
identical, every suite green (2 310 tests, 7 new):

| | before (`6b442ee4`) | after | |
|---|---|---|---|
| cold, 8 alternated pairs | 17.77 s mean, 17.93 median | 17.30 s mean, 17.25 median | −0.47 s (pairwise sd 0.73) |
| cold, all 20 pairs today | | | −0.74 s mean, **−3 to −4%** |
| warm, 4 rounds | 2.92 s | 2.90 s | flat |
| edit / back, 4 rounds | 6.54 / 6.36 s | 6.61 / 6.49 s | within noise |

919 values get a parametric signature, 906 of them substitutable; an instance's signature costs 63 µs instead of
~450 µs, so the ~4 100 instance checks fall from ~1.8 s to ~0.26 s. **The estimate (1.2–1.5 s) was twice the result**,
for two reasons it missed:

| instances per value | values | instances | parametric checks | the instances' own checks, before |
|---|---|---|---|---|
| 1 | 697 | 695 | 540 ms | ~313 ms |
| 2–4 | 93 | 244 | 66 ms | ~110 ms |
| 5–20 | 102 | 1 136 | 81 ms | ~511 ms |
| more than 20 | 27 | 1 283 | 24 ms | ~577 ms |

(one `--statistics` run on a loaded machine, so the absolute times are high; the proportions hold.)

- **Most values have one instance.** An effectful definition is generic in its handle types and usually has one
  instance (`Launcher.main`, `StandardProcess$Handle`), and §12.2 counted 719 values by name where there are 919 by
  name and qualifier. For a value with one instance, the parametric check replaces one concrete check, and costs more.
- **A parametric check costs more than a concrete one** (median 0.57 ms against ~0.45 ms): the values with one instance
  are the large effectful signatures.

The values with more than one instance gain what §12 predicted (~1.2 s of checks become ~0.34 s). Taking the path only
for them would recover most of the ~0.25 s the others lose, but nothing a processor can read says how many instances a
value will have; any rule would be a guess. Which is the next lever is unchanged from §12.1: what a single check costs
(about half a millisecond even for `B => ((B, A) => B) => List[A] => B`) is now the larger question than how many there
are.


## 13. What one check costs, and who reads what (2026-10-26)

§12.4 left one question: why a check costs about half a millisecond. A JFR profile cannot answer it directly, because
a quarter of the compilation's samples are the cats-effect run loop with no Eliot frame on the stack, and those
samples cannot be charged to any processor. So a temporary probe (reverted) marked every generation and every fact read
with a JFR event (`eliot.Label`, no stack trace), pushed and popped in `TimedCompilerProcessor` and
`TimedCompilationProcess` under `--statistics`. Each execution sample is then charged to the label running at its
timestamp, the run loop included. A second label, `read<P>T`, gave every fact read its reader's and its target's key
types.

**Caveats.** The machine was under load (load average 3–8 on 20 cores), so no wall time here can be compared with
earlier sections. Plain cold runs took 20.1 s and 16.4 s; with the probe and JFR, 26 s. The probe adds a few `IO`
steps and two event commits to every read, so **a read's share below is inflated**. Samples inside the probe are
dropped. Counts (reads, generations, calls) do not depend on load, and every jar was `a74475df…`.

### 13.1 The split

1 738 attributed samples on the compilation (the write-behind worker's 472 excluded):

| | samples | share |
|---|---|---|
| a processor's own code | 753 | 43% |
| the cats-effect run loop, charged to whichever generation or read was running | 661 | 38% |
| the engine (fact map, dependency tracking, dispatch, names) | 294 | 17% |
| hashing or equality as the leaf | 30 | 2% |

By where it happened: inside a generation 57%, inside a fact read 32%, dispatching a key to its processor 6%, the
engine between generations 5%. **A cold build makes 1 040 000 fact reads**, 15.5 for each of its 67 000 facts. Without
the probe (the `p13` profile of the same commit), samples with a read-path frame on the stack are a 9.8% lower bound,
since a read's run-loop steps carry no frame.

### 13.2 A check is half bind interpretation, and its code has no hot spot

394 samples ran inside a check on either track, reads excluded:

| | share of a check |
|---|---|
| the run loop | 45% |
| `CheckIO`'s own constructors (`inspect`, `pure`, `modify`, `get`) | 6% |
| everything else: no method above 2% (`Checker`, `TypeStackLoop`, `HandleHoleResolver`, `PostDrainQuoter`, `Quoter`, `Env`, …) | 49% |

Reads come on top of that. A runtime check reads 32 facts (74 417 reads over 2 303 checks: `NativeBinding`,
`BodyValueReferences`, `SaturatedValue`, its signature twin), a compile-track check 17.5 (131 323 over 7 519). Read
time inside a check is 14% (signature twins) to 26% (runtime values) of its samples.

So the half millisecond is **not** an expensive algorithm somewhere. Half of it is `CheckIO = StateT[CompilerIO, …]`
interpreted step by step, and the other half is spread evenly over the checker. Nothing local makes a check fast. I2 is
what removes that half, applied to `CheckIO` together with the fact reads, since §6 showed the checker cannot be moved
alone. It is a ceiling on I2's gain for checks, not an estimate.

### 13.3 The largest key type is the derived meta, and most of it is re-linking

| key type | samples | share | generations | reads it makes |
|---|---|---|---|---|
| **`DerivedMeta`** | 299 | **17.2%** | 5 544 | **431 426** (78 each) |
| `CompilerMonomorphicValue`, signature twins | 258 | 14.8% | ~4 300 | 84 188 |
| `MonomorphicValue` | 149 | 8.6% | 2 303 | 74 417 |
| `GeneratedModule` | 115 | 6.6% | 50 | 19 004 |
| `ResolvedValue` | 104 | 6.0% | 3 554 | 27 249 |
| `CompilerMonomorphicValue`, values | 98 | 5.6% | ~3 200 | 47 135 |
| `RowElaboratedValue` | 79 | 4.5% | 2 814 | 42 423 |

**41% of all fact reads are made by derived metas**, and three targets account for nearly all of them:
`CompilerMonomorphicValue` 175 865, `NativeBinding` 121 581 and `UnifiedModuleNames` 95 339. The first two come from
one place. Every `EscalatingReducer.reduceApplied` (a meta's `join`, a data type's slots, a stated companion, a carried
meta) links its callee's reduced body with `ReducedBindingClosure.collectBindings(deep = true)`. That links each
reference as `reduceInstance`, which reads the reference's `CompilerMonomorphicValue` and links *its* references the same
way, down the whole transitive closure. Nothing is remembered between calls. Counted with temporary counters (reverted):

| | calls | distinct `(value, type arguments)` | repeated |
|---|---|---|---|
| `reduceApplied` | 28 907 | 1 240 | 23× |
| `reduceInstance` | 139 573 | 1 379 | **101×** |

The same 1 379 instances are linked 139 573 times: each link re-reads the instance's facts, renames it apart
(`instanceNamed`), collects its references (`valueReferences`), asks for the request chain (`activeFactKeys`) and
allocates a fresh `VTopDef` thunk. The evaluation that follows does differ per call, since it applies that call's
argument metas. The linking does not, because it depends only on the instance, apart from the cycle guard (below).

Without the probe, samples with a `ReducedBindingClosure` or `EscalatingReducer` frame on the stack are a 3.8% lower
bound (their run-loop steps and reads carry no such frame). With the probe, linking's code plus the derived metas'
`CompilerMonomorphicValue` and `NativeBinding` reads come to about 8%. Discounting the inflated reads gives an
**estimated 5–9% of a cold build, 0.8–1.4 s**. The last two estimates in this document were twice what was measured,
so plan for half of that. It would also remove 29% of all fact reads, which shrinks what I1 and I2 have to make cheap.

**The obvious shape: a fact.** `ReducedInstance(vfqn, typeArguments)` holds the linked binding, so `reduceInstance`
becomes one memoized read. A fact holding a `SemValue` with no value codec has a precedent: `NativeBinding` declines
its codec for the same reason. One question needs an answer before it is built. `collectBindings` leaves out a
reference that is an **ancestor on the active request chain**, so today's link depends on who asked. As a fact, the
first reader's chain would decide it for every later reader. Either the cases where the guard fires must be shown to
give the same binding, or a link that skipped an ancestor must not be shared. That is exactly the "soundness by
argument" §12.4 was reverted for, so it needs the probe-first treatment §12.2 had: compare every memoized link with a
fresh one across eliot-build, the examples and both test suites before trusting it. The third target,
`UnifiedModuleNames` (95 339 reads, 17 per derived meta), comes from `declaresSlots`, `declaresFields`,
`parameterStatements` and `handedStatementsOf`, each asking the module's names again. That repetition is cheap per read,
and I1/I2 make it cheaper still.

Other readers that read the same facts many times, for later: `AbilityImplementationCheck` makes 47 050 reads of
`OperatorResolvedValue` over 268 generations (175 each), `AbilityImplementation` 21 981 over 425, `RecursionCheckedValue`
39 646 of `NamedValuesRewrittenValue` (14 each), and `MetaDemands` 22 984 of `UnifiedModuleNames`.

### 13.4 Bytecode has no hot spot

`GeneratedModule` is 6.6% of the samples (115) for 50 modules: 23% run loop, then `JvmIdentifier.encode`/`encodeChar`
(9%), and nothing else above 3%. 25 ms a module is the sum of many small steps. It is not a lever on its own.

### 13.5 What it means

The ranking from §12.1, revised:

| lever | what it removes | rough gain | needs |
|---|---|---|---|
| **memoize the linked reduced instance** (§13.3) | 138 000 of 139 573 links, 29% of all fact reads | 0.8–1.4 s estimated, plan for half | a correctness argument for the cycle guard, then a probe |
| I2, direct-style queries | the run loop: 45% of a check, 38% of the compilation | the largest, ≤2.5 s and likely more for checks | the largest refactor |
| I1, interning | the remaining hashing and equality on reads | ≤2 s, shrinks if reads are fewer | mechanical |
| I9, a hand-written lexer and parser | most of parsing | ~1 s | nothing |
| bytecode | nothing found | — | — |

I2 is still the largest lever. The memoized link is the largest lever that does not touch every processor. It also has
a clear first step: an isolated probe comparing memoized links with fresh ones, the same as §12.2, which costs an
afternoon and decides it.

### 13.6 The probe: a link never depends on who asked (2026-10-26)

§13.3's open question was whether the request chain ever changes a link. Two things in a link read it:
`collectBindings` skips a reference whose `NativeBinding` is an ancestor, and the engine refuses a read of a
`CompilerMonomorphicValue` that is in progress, which `reduceInstance` takes as "no reduced form". The probe (in
`ReducedBindingClosure`, reverted) recorded every link's **shape**: for each reference, in traversal order, whether it
was skipped as an ancestor, linked raw (with or without a binding) or linked deep, and the deep link's own shape,
recursively. The first shape of each `(value, type arguments)` was kept per compilation session (keyed by the engine
instance, so tests sharing a JVM never meet), and every later link was compared with it:

| corpus | links | first in session | same shape | different | ancestor skip anywhere | no `CompilerMonomorphicValue` |
|---|---|---|---|---|---|---|
| eliot-build | 139 573 | 1 379 | 138 194 | **0** | **0** | 0 |
| the 52 examples | 9 735 | 1 679 | 8 056 | **0** | **0** | 3 |
| `lang.test` | 25 | 14 | 11 | **0** | **0** | 0 |
| `jvm.test` | 148 887 | 20 919 | 127 968 | **0** | **0** | 46 |

All 49 links with no `CompilerMonomorphicValue` are genuine, **none** on the chain: a type constructor
(`Test::Box^Type` and four more) or `eliot.lang.Any::Any`, which have no compile-track value at all. The eliot-build jar
stayed `a74475df…` and both suites passed under the probe.

**So in every program we have, a link is a function of `(value, type arguments)` alone.** The guards exist for a cycle
that no corpus reaches. That does not make sharing sound by itself, since a future program could reach one, but it
makes the fail-safe design cost nothing:

- `ReducedInstance(vfqn, typeArguments)` holds the `Linked` (body, bindings, instance names), with no value codec, as
  `NativeBinding` has none. `reduceInstance` returns its binding; `reduceApplied` takes its `Linked` instead of linking
  the callee's body itself, since that is the same `collectBindings(…, deep = true)` of the same body. A deep reference
  is a read of the reference's own `ReducedInstance`, so each instance is linked once in total.
- **A link that touched the chain is not shared.** If any guard fires while a `ReducedInstance` is generated (an ancestor
  skipped, a `CompilerMonomorphicValue` or a `ReducedInstance` refused as on the chain), it is answered as "not
  shareable", and every reader links fresh, exactly as today. A reader whose own chain holds the instance's
  `CompilerMonomorphicValue` does not read the fact, and gets today's "no reduced form". On the corpora above the
  fallback is never taken, so the fact always answers; in a program that does reach a cycle, the answer is today's.
- That answer is a value (`None`), not a decline: §12.4 found that a decline is regenerated on every warm build.

What it should remove, from §13.3: 138 000 of the 139 573 links and their ~300 000 reads, about 29% of all fact reads,
and the evaluation of each linked body's thunk, which a shared binding forces once instead of once per link.
Estimated 0.8–1.4 s; plan for half.

### 13.7 Built, measured at no gain, and reverted (2026-10-26)

> **Reverted** (Robert, 2026-10-26): built in `7d0f90b4`, reverted in `3efa5c22`. Do not re-propose memoizing these
> links without a new fact that says hit reads are what costs.

Built as §13.6 designed it:
`ReducedInstance` and `ReducedInstanceProcessor`; `reduceInstance` and `reduceApplied` read it and link afresh only on
`Unshared` or a refused read; `Linked` moved into the fact. In eliot-build, 2 614 instances are linked once each: 1 927
shared, 687 absent, **none** unshared and no fresh link at all (the 2 614 are §13.3's 1 379 `reduceInstance` targets
plus the `reduceApplied` targets, which were linked outside `reduceInstance`). Every jar is identical (`a74475df…`
cold/warm/back, `073f213d…` edited), the example sweep is identical, and every suite is green; one test lists the fact
types that decline a value codec and had `ReducedInstance` added to it.

Measured on an otherwise quiet machine, alternated, eliot-build:

| | base (`d39f064b`) | `ReducedInstance` | difference |
|---|---|---|---|
| cold, 10 pairs (with warm/edit/back between) | 17.76 s | 17.24 s | −0.52 s, pairwise sd 1.0 |
| cold, 10 more pairs (GC logged) | 17.79 s | 17.74 s | **−0.05 s ± 0.17** (standard error) |
| warm / edit / back, 4 rounds each | 2.93 / 6.71 / 6.75 s | 2.89 / 6.65 / 6.61 s | within noise |
| GC pauses, cold | 735 ms | 780 ms | +45 ms |

Pooled, about **−0.3 s ± 0.2 s, at most 2%**, against the 0.8–1.4 s §13.5 estimated (and half of that planned). Under
`--statistics` (6 pairs) the derived metas' own time fell as expected, 1 676 → 1 232 ms, and the new processor costs
152 ms, but the two type checks rose by about as much (compile track 2 904 → 3 232 ms, runtime 1 316 → 1 415 ms), so the
total did not move (19.7 vs 20.1 s). Why the checks rose is **not established**: their links now come from the fact, and
the shared bindings stay alive for the whole compile (the extra GC above). Not chased further.

What it means for the estimates in this document: **a fact read that hits is cheap**. Removing 300 000 of them, and
138 000 links, removed about what the derived metas' self time showed (0.4 s), and that was absorbed elsewhere. The
per-read cost behind §13.3's estimate came from a probe that inflated every read (§13, caveats), and that is the third
estimate here to come out at half or less of what was measured. A count of removed work times a per-unit cost taken from
a profile is not a forecast; only an A/B is.

Reverted by the same test as §12.4: a fact type, a processor and a fallback path for a gain that the noise cannot
tell from zero.

The ranking in §13.5 therefore has I2 alone at the top: the run loop is 45% of a check and 38% of the compilation, and
it is the one cost every measurement so far agrees on.

## 14. I2 sized: one worker first, then a synchronous engine (2026-10-26)

Robert asked what I2 can do and what it would gain. To answer, master (`78ed1b17`) was profiled again. Before the profile,
a one-line test of the scheduler turned out to be worth more than any estimate.

### 14.1 One compute worker: −23%, with nothing else changed

Every fact is `start`ed as a fiber, and its reader suspends on the fact's `Deferred`. The work-stealing pool wakes an idle
worker for the new fiber, so a cold build's 67 000 facts mean on the order of a hundred thousand thread wake-ups. Each
one moves a fiber to another core and to cold caches. §11.1 capped the JVM at four processors, but four workers still
hand fibers to each other, so it could not see this. The test: `Main` overrides `IOApp.computeWorkerThreadCount` from
a system property, and is reverted afterwards. The write-behind save runs on the blocking pool and is unaffected.

eliot-build, alternated, each round cold → warm → edit → back, jars `a74475df…`/`073f213d…` in every run, index
identical (the object store differs by a few bytes, as it does between any two runs, §8):

| | cold (4 rounds) | warm | edit | back |
|---|---|---|---|---|
| twenty workers (today) | 18.95, 17.25, 18.21, 17.00 = **17.85 s** | 2.93 s | 6.70 s | 6.72 s |
| one worker | 13.85, 13.65, 13.50, 14.11 = **13.78 s (−23%)** | 2.91 s | 6.24 s (−7%) | 6.18 s (−8%) |

Four earlier cold pairs agree (18.56 → 14.04 s). The compilation also needs **less CPU**: its thread has 1 374 samples
with twenty workers and 933 with one, because the same work runs on one warm core. Warm builds do not move, since they
generate almost nothing.

**Adopted for the CLI (2026-10-26)**: `computeWorkerThreadCount = 1` in `Main`; the example sweep is identical. Nothing in a processor forks today, so it is the
sequential mode made explicit. It does not settle I3: parallel facts need a different structure anyway (§14.3). The LSP
runs on `IORuntime.global` with one compile session per package. There the equivalent is each session's compile
running on a single-thread executor of its own (`evalOn`), which has not been measured.

### 14.2 What is left of the run loop

The profile with one worker (933 compile-thread samples, classified as in §11):

| | share |
|---|---|
| no Eliot frame on the stack: `IOFiber.runLoop`, `succeeded`, `IO` node allocation, continuations | 22.5% |
| a cats leaf called by Eliot code: building `Kleisli`/`IO`/`StateT` binds | 9.1% |
| inside a cats call made by Eliot code (`traverse`, `Eval`, `Chain`) | 2.9% |
| the engine (fact map, dependency sets, chain), including its `Ref`/`Deferred` plumbing | 14.3% |
| everything else: compiling | 51.2% |

So cats-effect is about **35%** of the remaining compilation, roughly 4.3 s of 13.8 s, and part of the engine's 14% is
`IO` plumbing. That is I2's ceiling: what direct style would save if a bind cost nothing. The `StateT` samples under
`ast.parser` are the hand-written parser's own `StateT`, which is I9's, not I2's.

### 14.3 The proposal: a synchronous engine, processors unchanged

§6 showed that one processor cannot be moved through a bridge, because the fact read is the hot crossing. §7 showed that
changing what `CompilerIO` *is* changes no processor. So the step is both at once:

- **`CompilerIO[T]` becomes a plain function of the `Context`**: a bind is a call, an abort an exception (as now), and
  errors stay in the `Context` (as now). It gets a stack-safe `tailRecM` and a hand-written `Sync` instance (`delay` and
  `blocking` are calls), so the jvm backend's `[F[_]: Sync]` code generation and `Logging` compile unchanged. The nine
  `.to[CompilerIO]` sites (file reads, the jar) run their `IO` synchronously at the edge. The conservative variant is
  `Kleisli[SyncIO, Context, *]`, which comes with a `Sync` instance but still interprets every bind, only more cheaply.
- **The engine is synchronous**: a mutable map from key to *in progress* or *done*; a missing fact is generated on the
  reader's own stack; the chain and the cycle refusal stay exactly as `DependencyTrackingProcess` has them. The
  validation drill becomes plain recursion. The compile runs on one dedicated thread with a large stack (a request chain
  is hundreds of keys deep, each a few dozen frames), and `IO` stays at the edge: `CompilationSession`, the CLI, the
  LSP's cancel-restart, which becomes a thread interrupt checked at each read.
- **`CheckIO` and the other `StateT`s stay** at first. Flattening `CheckIO` as §6 did, with its code in the stash, is a
  second, separately measured step.
- **Parallelism is kept, and gets easier.** Direct-style code is what virtual threads need. A parallel I3 is a latch per
  key and a virtual thread per parallel request, and a read blocks, which a virtual thread does cheaply. The sequential
  engine stays as the debugging mode Robert asked for (§12).

What changes: `CompilerIO`, `IncrementalFactGenerator`, `DependencyTrackingProcess`, the statistics, visualization and
progress wrappers, `CompilationSession`/`CompilationServer`, the nine edge sites, and the tests that run a `CompilerIO`
by hand. No processor logic changes.

**The gain, honestly:** the ceiling is §14.2's ~35% plus part of the engine's 14%, about **4–4.5 s of 13.8 s**. A
function-based monad still allocates one closure per bind, so not all of it comes back. The last three estimates in this
document came out at half or less, so the planning figure is **1.5–2.5 s (−10–18%)**, cold, on top of §14.1. Warm builds
gain little: they generate almost nothing, and 2.9 s is mostly startup. Only an A/B decides, as for §7: built as an
experiment, measured alternated against §14.1, and kept only if it beats the noise.

**Risks:** stack depth (the dedicated thread's stack size; a `traverse` over a long list nests binds, which a
trampolined monad would not); LSP cancellation, which becomes cooperative instead of a fiber cancel; and the error
buffer's discipline under exceptions, which §7.5 already had to get right once.

### 14.4 Built and adopted: the synchronous engine (2026-10-26)

Robert decided to adopt §14.1 (`d2172c39`) and then to build §14.3. It is built as §14.3 describes, with one more
member: the outside asks for facts through `FactDemand` (`generator.demand`), an `IO` whose `getFact` runs the
compilation on a `CompileThread` and waits for it. That is what the target plugins' `run` and the tests' harnesses now
receive, so their code is unchanged but for that type. No processor changed. `CheckIO` and the other `StateT`s were
left as they are.

- **`CompilerIO`** is `opaque type CompilerIO[T] = Context => T`. `map` and `flatMap` are extension methods, so a `for`
  needs no cats syntax and builds no syntax wrapper, and a hand-written `Sync` and `LiftIO` instance serves the jvm
  backend's `[F[_]: Sync]` code, `Logging`, and the `.to[CompilerIO]` sites. A lifted `IO` runs in place through
  `syncStep`, `IO.blocking` included, and only one with an asynchronous boundary (a test's `IO.sleep`) goes to the
  runtime and is waited for.
- **The engine** (`IncrementalFactGenerator`, `DependencyTrackingProcess`) keeps its logic step for step. A `Deferred`
  became a `Slot` that is completed once, a `Ref` a mutable map confined to the compilation's thread, and a fiber per
  fact a call. Two waits that could never end on one thread now answer at once: a fact found in progress (a cycle that
  a validation's fresh chain hides) is an internal error and `None`, and a validation check found in progress is
  "changed", the fail-safe answer. Before, both waited on a `Deferred` that would never complete.
- **Cancellation** (the LSP's cancel-restart) interrupts the thread. Every fact read checks for the interrupt, and an
  `InterruptedException` is not `NonFatal`, so no handler catches it. The cancellation completes once the thread has
  stopped.
- **The stack:** eliot-build needs between 256 and 512 KB (256 KB overflows, 512 KB does not). `CompileThread` reserves
  1 GB, which costs only address space. An overflow fails the compilation with an error; it is never silent.

One bug was found by the suites before any timing was trusted: `runCompilerIO` read the context's errors before running
the computation, so a computation that registered errors and then succeeded lost them. It is fixed, and the cold timing
below was taken again afterwards.

Verified: every suite green (`./mill __.test`, 2 303 tests, the same count as before); the example sweep identical to
the baseline; eliot-build's jar `a74475df…` cold/warm/back and `073f213d…` edited; a build with an undefined name
prints the same diagnostics, byte for byte, as the one-worker compiler; and `--statistics`, `--progress` and
`--visualize-facts` work.

Measured on eliot-build, alternated against the one-worker compiler of §14.1:

| | cold | warm | edit | back |
|---|---|---|---|---|
| one worker (4 rounds) | 12.90 s | 2.67 s | 5.93 s | 5.82 s |
| synchronous engine (4 rounds) | 10.73 s (**−17%**) | 2.43 s (−9%) | 5.38 s (−9%) | 5.41 s (−7%) |
| cold again, after the `runCompilerIO` fix (4 pairs) | 12.95 → 10.86 s (**−16%**) | | | |

That is −2.1 s, at the top of §14.3's planning range (1.5–2.5 s) and about half of its ceiling. The compile thread's
profile now has 721 samples, against 933 with one worker and 1 374 with twenty. The run loop is gone: samples with no
Eliot frame fell from 22.5% to 0.7%. What is left of cats is 14%: syntax wrappers, `StateT` (`CheckIO`), `Chain`, and
`Eval` under `traverse`. The engine's own map and set work is 22% (hashing and equality of keys, which is I1). The other
63% is compiling.

**Found on the way, not caused by this:** on JDK 25 the CLI **exits 0 when a build fails**. cats-effect 3.5.2's `IOApp`
treats the thread whose `getId()` is 1 as the main thread, and JDK 25's main thread has id 3. So `IOApp` takes its
"not forked" path, warns that `main` runs on another thread, and never calls `System.exit` with the code. The same
build exits 1 on JDK 21. Every compiler before today behaves the same way on this machine. Open.


## 15. Parallel compilation: the plan, and facts that depended on who asked (2026-10-27)

Robert, 2026-10-27: the serial levers are into diminishing returns, so I3 starts. His plan was to parallelize inside
processors on demand: wherever several facts are asked for, or a `traverse` reads facts, read them in parallel. §15.1
is the plan this became, §15.2 its first step, built.

### 15.1 The plan

**A fact must be a function of its key.** In a parallel engine, whichever reader reaches a fact first computes it, and
every later reader is handed that result. So a fact whose value depends on *who asked* — on the request chain —
changes from run to run, and two threads that each wait for a fact the other is computing deadlock, because neither is
on the other's chain. Neither depends on where the forks are placed. Four sites read the chain (§15.2), and making
them independent of it comes before any parallelism, as single-threaded work measured on its own.

**A hint, not `parTraverse`.** One primitive, `prefetch(keys): CompilerIO[Unit]`: "I will need these". The engine may
start them on other threads, and it returns at once; the processor then reads them in order, as it does today. So
`parTraverse(keys)(getFactOrAbort)` becomes `prefetch(keys) >> keys.traverse(getFactOrAbort)`: the same parallelism
for fact reads, and

- **a generation stays on one thread.** `Context.errors` is a plain field because nothing inside one computation
  forks, and an abort is an exception; parallel branches of one generation would share both, and `CheckIO`'s
  `StateT` too. A prefetched fact is a generation of its own, with its own `Context`;
- **the reads are recorded in the same order**, so the cache's dependency edges are the sequential build's;
- **the sequential mode is the same binary** with `prefetch` doing nothing, so the alternated A/B of every step so far
  stays possible (§12, Robert's condition for I3);
- **an abort means what it means today.** A prefetched fact that aborts has no fact, and the read in order sees that
  as it does now; there is no sibling to cancel and no race over which error comes first.

**A few hints near the top, not one at every `traverse`.** A fact missing at a read is generated on the reader's
thread, so one parallel fan-out near the top parallelizes everything beneath it, and inner sites add mostly overhead.
The candidates: the front end per file (the path scan knows every file), the `used` walk (hint every reference of a
body before descending into it: that walk is the "chain of discoveries" of §3, I3), and bytecode per class.

**The ceiling before the engine.** One sequential run has what the ceiling needs: each fact's self time and its
dependency edges (the progress tracker's `started`/`ended`/requester events, the visualizer's graph). Total work over
the longest weighted path is the most any schedule can gain, and the path says which fan-outs are worth a hint. It
decides whether §3's 4–8× is there before the engine changes.

**What the engine needs.** The `Slot` becomes a latch and the maps concurrent; the engine's map and set work is 22% of
a build (§14.4), so key equality (I1) becomes contention as well as cost. A read that finds a fact in progress on
another thread waits for it, after checking the waits-for graph: a cycle across threads is reported as the cyclic
demand it is. The `registerFact` push pattern needs an audit, since a pushed sibling can race a direct generation of
the same key. Errors are reported sorted. `--statistics` stays sequential-only, since self time is CPU time only when
nothing else runs. Open: virtual threads, or a `ForkJoinPool` of platform threads with large stacks and `managedBlock`
— a mounted virtual thread runs on its carrier's stack, eliot-build needs 256–512 KB of it, and `CompileThread`
reserves 1 GB.

**The order.** (1) Facts independent of the chain (§15.2). (2) The ceiling, measured. (3) The latch engine with
`prefetch` a no-op: byte-identical, and timed against today's. (4) The hints, one fan-out at a time, each measured
against the sequential mode.

### 15.2 Step 1, built: no processor reads the request chain

The engine has always threaded the request chain through each generation, to refuse a read of a fact the reader is
itself generating. `CompilerIO.activeFactKeys` also showed it to processors, and four used it to decide a value:

- `AbilityImplementationProcessor.hasImplementation` answered a constraint probe "satisfied" when the probe was an
  ancestor;
- `BindingClosure.collectBindings` and `ReducedBindingClosure.collectBindings` left out the binding of a value whose
  `NativeBinding` was an ancestor;
- `EscalatingReducer.escalate` left out a callee whose `CompilerMonomorphicValue` was an ancestor.

Each answer was computed once and cached for every later reader, so a fact depended on which reader got there first.
Robert's question was how that could work at all. **It worked because it never happened**: instrumented, none of the
four branches fired in any of the 2 303 tests, the 50 examples, or eliot-build. They guarded cycles the pipeline no
longer has: a body is bound only once the recursion gate (`termination`) admitted it, so a chain of bindings is a
chain of body references, which has no cycle. The comments still named a covariant `data` cycle and a
monad-transformer lift, which date from before the gate.

The ability guard is the one a user can reach: two instances, each constrained by the other at the same arguments.

```eliot
implement[A ~ Bar] Foo[A] { def foo(a: A): String = "foo" }
implement[A ~ Foo] Bar[A] { def bar(a: A): String = "bar" }
def main uses Console: Unit = printLine(foo(Hello("World")))
```

It compiled and ran: `Foo[Hello]` was resolved by assuming `Bar[Hello]`, and `Bar[Hello]` by assuming `Foo[Hello]`.
Whichever of the two was asked for second was answered under that assumption, and the answer was cached for every
later reader. (With the bodies calling each other it is refused anyway, as a cyclic demand of the instances'
`MonomorphicValue`s, so it was never a hole in the recursion rule.)

What changed:

- **`activeFactKeys` is deleted** from `CompilerIO` and `CompilationProcess` and from the three process wrappers. The
  chain is the engine's alone, which `DependencyTrackingProcess` says, so a processor cannot answer by who asked
  again.
- **The four guards are deleted.** A cycle is the engine's refusal, a "Cyclic fact demand" error that fails the
  build, whichever of its keys was asked for first.
- **A generation registers nothing after a read was refused as cyclic.** A refused read returns `None`, and a
  processor may carry on past it (`getFactIfProduced`, `recover`); whatever it then registers may be built on the
  missing input, which is missing only because of where it was asked from. Dropping it makes the outcome of a cycle
  the same from every entry: no fact, and the error. The generation's empty read accounts for the missing fact.
- **An absent constraint probe aborts the resolution** instead of answering "satisfied". The processor reports no
  resolution errors, so a probe produces no fact only on an internal error or a cycle. Answered "satisfied", the
  candidate was kept and the program above was written to a jar beside the error that failed the build — the
  artefact invariant's failure, fixed here the way the failing `where` was (`664d166d`).
- **An absent resolution aborts the demanding value's check** (`AbilityResolver.reportFailedDemand`). It used to leave
  the reference pending, which an absent fact never stops being, and the value then reported a second, misleading
  error about the abstract method stating no meta transfer.

So **the program above is now an error** ("Cyclic fact demand: …", exit 1, no jar). That is the one change of
language behaviour, and it is deliberate: a cycle of constraints at the same arguments has no finite derivation, and
in a language with no recursion there is no recursive dictionary for it to stand for. The message is the engine's,
naming fact keys; a diagnostic in the user's terms is a follow-up, if such programs turn up. `BuildArtifactTest`
holds the program (no jar, the cycle reported), and `DependencyTrackingProcessTest` the registrations dropped after a
refusal.

Not chain reads, and left alone: `UsedNamesProcessor`'s `ancestors` is the processor's own descent within one
computation, a function of its key. The progress tracker's requester is reporting, never a value.

Verified: every suite green (`./mill __.test`, 2 306 tests: two that observed `activeFactKeys` removed, five added);
the example sweep identical to the baseline, md5s included; eliot-build's jar `a74475df…`.

Next is step 2 of §15.1: the ceiling, from one sequential run's self times and edges.
