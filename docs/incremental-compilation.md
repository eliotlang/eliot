# Incremental Compilation: Why a Warm Build Still Computes

Status (2026-08-06): **done and shipped as the only backend.** Regeneration has been at the leaf floor since §8; the
cost was then I/O, with §11 measuring **~60% of a warm build in cache load + save**. §13's plan — explicit per-type
codecs over a content-addressed object store, replacing Java serialization outright — is complete. A warm build is
**half** what it was, and the cache is no longer the dominant cost:

| Step | State |
| ---- | ----- |
| 1 — the encoding, coverage proven by the compiler | **DONE**, §14. 0.55× the Java graph with sharing, 4.89× without. |
| 2 — the fact model made encodable, decision on the key | **DONE**, §15. `CompilerFactKey.valueCodec`, abstract, no default. |
| 3 — the content-addressed store | **DONE**, §17. Wired behind `ELIOT_CACHE_BACKEND=store`. |
| 4 — lazy values, the cutoff by content id | **DONE**, §18. Warm build **50% faster** (load −79%, save −76%), **half the disk**, **zero growth**, jar byte-identical, under-invalidation checked. |
| 5 — compaction | not started, and no longer urgent: a warm build appends nothing (§18). |
| 6 — flip the default, retire the old path | **DONE**, §20. Java serialization deleted; there is no backend switch. |

§17, §18 and §20 are the current state; §19 is the profile that says where the remaining time goes, and **§23
corrects the one attribution it guessed at**. §16 is the handover that preceded them and is superseded on two points
it names.

**The cache work is finished; the next bottleneck is not the cache and not the compiler.** §23 measures it: ~41% of a
warm build is JVM boot, log4j and cats-effect initialisation, and Scala classloading — all of it *before*
`--statistics` starts counting, which is why three sections looked for it in the wrong place. §24 then prices the
biggest named piece of it, log4j, at ~110 ms (~8%) with **no switch that recovers any of it** — only not loading
`log4j-core` at all. Both sections are measurement; neither has been acted on, and §23's AppCDS option
(1,331 → 908 ms, byte-identical jar, no compiler change) is **declined** — it constrains ordinary JVM operation more
than the time is worth.

§§1–2 are the model everything else refers to. §§3–10 are the diagnosis, its fixes and the retention changes, kept as
the record of what each defect looked like.

## 1. The model that already exists

A fact is computed by a processor from other facts. `DependencyTrackingProcess` records every `getFact` a
generation performs as a direct dependency of the key being generated, eagerly on each read, so a fact's
dependency set is complete by the time the fact becomes observable. At the end of a run
`IncrementalFactGenerator.buildCacheData` writes one `CacheEntry(value, directDeps)` per fact, and `FactCache`
persists the lot as a single Java-serialized object graph under `<target>/.eliot-cache`.

**Validation is backward and demand-driven.** Asked for a key, `resolve` accepts the cached value if the entry
has one *and* every recorded dependency is unchanged; otherwise it regenerates. "Unchanged" is decided per
dependency by `computeUnchanged`, which has exactly three branches:

| Prior entry                     | How it is validated                                        |
| ------------------------------- | ---------------------------------------------------------- |
| has a stored value              | recompute and compare — **the equality cutoff**             |
| no value, non-empty `directDeps`| **structural drill** through its edges, without materialising|
| no value, no edges              | cannot be validated ⇒ **changed**                           |

The equality cutoff is what stops propagation: a source file whose mtime moved but whose tokens recompute equal
invalidates nothing downstream. The structural drill is what lets a fact that cannot be stored still prove
itself unchanged, by reaching the leaves through its edges.

**Leaves are the boundary with the world.** An entry with no dependencies is always regenerated — that is how a
`FileStat` re-stats its file every run. Two facts exist purely to give that boundary the right shape:

- `UpToDate` — a constant with no inputs. A processor that is an *input-less compiler constant* would otherwise
  look exactly like a source leaf; depending on `UpToDate` turns it into a non-leaf whose drill terminates at a
  leaf that always compares equal.
- `OutputFileStat` — the content digest, not the mtime, of a written artefact. An mtime would self-invalidate on
  every write; a digest is stable across a rewrite of the same bytes (which is what makes deterministic output a
  prerequisite, not a nicety) and still differs when the output is deleted, truncated or replaced. It is read
  *after* the write, so it records what the run actually left on disk. This dependency is the only thing tying an
  accepted-from-cache writer to the file's real state — presence alone, its earlier shape, let a corrupted jar
  survive a build that reported success.

**Fingerprints guard reuse.** `CacheFingerprint.compiler` digests every entry on the running compiler's
classpath (path, size, mtime; recursively for directories), so even an uncommitted recompile of the compiler
discards the cache — which is what makes "natives are constant within a compiler version" true. `config`
digests the effective `Configuration`: each `Configuration.Key` decides via `identityContribution` whether — and
as what — its value participates, so diagnostic-only flags (`--statistics`, `--visualize-facts`) and
unrepresentable injected values (a mount factory) opt out at their declaration rather than through a list of
exceptions (see `docs/cache-identity-configuration.md`). Both, plus `FactCache.CACHE_VERSION`, are matched on
load.

Failures are deliberately never cached, so a fact that errored has no entry and re-emits its error every run
until fixed.

## 2. The invariant the cache actually runs on

The cache does not need a fact to be *serializable*. It needs it to be **equality-stable**:

```
read(write(value)) == recompute(value)
```

Serializability is necessary and nowhere near sufficient, and the two are routinely different. Scala's function
types extend `Serializable`, so a closure persists without complaint and comes back as a different object;
case-class equality over a function field, or over an array, is by reference. Such a value satisfies "did this
serialize?" and fails "will it still compare equal?".

`FactCache` still decides by trial serialization (`canSerialize`), which can only ask the first question. Every
defect in §3 follows from that gap; §13 makes the invariant a codec law that can be enforced instead of an
accident of the serializer.

## 3. What a warm build actually did

Measured on the `eliot-build` tree (77 source files), warm build immediately after an identical one: **2 601 ms,
64% in processors, 2129 facts regenerated** — 979 `ContributedBinding`, 547 `FileStat`, 310 `NativeBinding`, 251
`MonomorphicValue`, 25 `RefinementTable`, 14 `CompilerMonomorphicValue`, one each of `UpToDate`, `SourceContent`,
`OutputFileStat`. The tokenizer did not run — no source file changed — yet half the wall time went on
re-typechecking 251 monomorphic values.

Instrumenting the three "changed" verdicts in `computeUnchanged` identified **three independent trigger classes**,
none of which could ever settle on any number of consecutive no-change builds:

- **3.1 Serializable, but never equal (11 facts).** `SemValue.VNative(paramType, fire: SemValue => SemValue)` is a
  case class holding a lambda. The lambda serializes (it captures only an FQN), so the value **is** persisted, and
  the recomputed lambda never equals the deserialized one — 11 facts report "value differs" every run, all
  `eliot.lang.String` natives. **Still live** under the Java-serialized cache, but no longer drives regeneration count
  (§8); §15 turned it into an explicit decline on the fact key, so under the codecs it can no longer be written at all.
- **3.2 Unstorable *and* edge-less (6 facts).** Where a native's value genuinely fails to serialize the entry is
  stored edges-only — and the drill then has no edges to follow, because `StdlibNativesProcessor` reads no facts at
  all. Six `ContributedBinding`s landed in the "no value, no edges ⇒ changed" branch every run. **FIXED** by the
  `UpToDate` anchor (§6 Step 2, measured in §8).
- **3.3 Declines are not cached (55 facts, 23 reached).** `BindingMergerProcessor` aborts when no contributor has a
  binding for a name — a correct answer, not a failure. Nothing is persisted for such a demand, so next run
  `prior.get(key)` is `None`, which reads as *new / previously failed ⇒ changed* for every dependent that ever
  asked. **FIXED** by the `NativeBinding` / `UnifiedModuleNames` totalization
  (`docs/retire-optional-fact-reads.md` §6.1–6.2); §6 Step 3 would generalize it to any decline.

**The cascade, and why every trigger count above is a lower bound.** Any one of the three is enough on its own.
Once a `MonomorphicValue` is judged changed, regenerating it re-runs the checker for that value, which demands its
callees' values, and so on — 251 re-checks from a handful of roots. `forallM` short-circuits on the first changed
dependency, so more triggers may sit behind the ones that fire first.

## 4. Which fact types are equality-stable (measured, not inferred)

Round-tripping every materialised fact through Java serialization and comparing to itself, over a full build:

| Fact type            | Verdict                                      |
| -------------------- | -------------------------------------------- |
| `ContributedBinding` | 1695 stable, 36 unserializable, **11 unstable** |
| `NativeBinding`      | 65 stable, 248 unserializable, **12 unstable**  |
| `GeneratedModule`    | **26 unstable** (all of them)                   |
| *every other type*   | stable                                       |

- Exactly **three** fact types cannot be persisted: two carry `SemValue` closures, and `GeneratedModule` holds
  generated bytecode in a `ClassFile`, whose array compares by reference. `GeneratedModule` never surfaced as a
  trigger in §3 only because nothing downstream of it regenerated. **`GeneratedModule` is fixed since §14** —
  `ClassFile` compares its bytes — leaving two, which are exactly the two declines §15 states on the key.
- `ContributedBinding` and `NativeBinding` are *mixed* per instance — the same type is stable for a `None`
  contribution and unstable for a `Leaf`. A per-value decision would make the cache's behaviour depend on
  content; **the decision belongs to the type**.
- **`MonomorphicValue` is stable** — it carries `GroundValue` and `MonomorphicExpression`, both ordinary data.
  Those 719 facts can be *accepted* from cache, not merely drilled through.

These three types are exactly the explicit declines §13 has to carry.

## 5. Measured dead end: per-entry payload framing

The first Step 1 attempt gave each fact an independent byte frame, so a codec failing on one entry could not
mis-position a shared stream and corrupt every entry after it. It works, and it costs **7.5× on disk: 17.9 MB →
134 MB** — ≈ 8 KB per frame against ~1 KB in the shared graph. Java serialization's back-references *and* class
descriptors are **per-stream**: one shared stream writes each `ValueFQN`, `ModuleName`, `Sourced` and each class
descriptor once and refers back to it everywhere after; 16 800 independent frames re-write all of it every time.
§12 finding 3 reproduced the same result (~17×) from the write side.

> **The tension, stated once:** cross-entry structure sharing requires coupled sequential decoding; per-entry
> failure containment requires independent frames. **Plain Java serialization cannot provide both.** That is the
> case for owning the format outright (§13) — not for patching it with a shared descriptor table.

(A second symptom of that attempt — the warm build afterwards rebuilt everything, cache apparently unused —
was never diagnosed before the revert.)

## 6. The plan

### Step 1 — SUPERSEDED by §13

The original Step 1 was a representation-based `FactCodec` that chose *whether* and *in what shape* a value is
persisted while never touching a stream, so the container stayed Java's single shared graph and kept its size.
§11 and §12 then showed the container itself is the cost, so the container is what §13 replaces.

**Three rules survive into §13 unchanged**, and they are the durable part:

- The persistence decision belongs to the **type**, not to a value: §4's mixed types would otherwise make caching
  behaviour depend on content.
- **Declining** (deliberately not persisted) and **failing** (a codec error) are distinct signals and must not be
  conflated. Encoding to *zero bytes* is neither: `UpToDate` is field-less, encodes to nothing, and must stay
  comparable — it is the anchor the whole of §3.2 depends on.
- The choice is **abstract with no default**, so every key states it once. Both possible defaults are wrong:
  defaulting to persist reintroduces §3.1, and defaulting to decline would silently make a *leaf* unvalidatable —
  an edge-less declining `FileStat` disables the entire cache while everything still compiles and passes.

### Step 2 — every input-less contributor takes the `UpToDate` edge — LANDED 2026-08-03

`StdlibNativesProcessor`, `DataTypeNativesProcessor` and `MatchNativesProcessor` contribute without reading any
fact on at least one path, so their entries carried no edges and were indistinguishable from a `FileStat`. All
three got the anchor, applied uniformly at the top of the generation so early returns are covered. Measurement and
verification in §8.

The invariant to hold on to: **an empty dependency set means "world leaf, re-check me every run"**. A compiler
constant must anchor on *every* path. This cannot be automated in the engine — auto-anchoring an edge-less
generation would turn genuine world leaves into constants and never notice a changed source file again.

### Step 3 — cache declines

Extend the entry to three outcomes: `Value` / `Opaque` (materialisable, value not persisted) / `Declined`
(legitimately produced nothing). `regenerate` already has what it needs to tell them apart: a decline is an
explicit abort with **zero errors** and no registered fact; a generation that errored stays uncached so errors
keep re-surfacing. `resolve` then completes a validated `Declined` entry with `None` and runs nothing;
`computeUnchanged` drills its edges as for `Opaque`. Bump `CACHE_VERSION` (currently 34).

Caching a decline reached *through* a missing upstream stays sound: the missing key is among the recorded
edges, so if it starts producing, the decline invalidates.

§3.3's totalization removed the acute instance; this generalizes it, and is worth folding into §13's format work
rather than doing separately, since it changes the entry shape.

### Step 4 — iterate to a fixpoint

Re-measure and expect `ContributedBinding`, `NativeBinding` and `MonomorphicValue` regenerations to stay at zero.
Because of §3's cascade any survivor is likely a fourth instance of the same three classes; sweep fact values for
reference-equality fields (arrays, functions, `Ref`s) rather than waiting for them to surface.

### Step 5 — make the next regression visible

Answering "why did this rerun?" still requires hand-patching four call sites in `IncrementalFactGenerator`. Keep a
permanent DEBUG-level trace of the three changed-verdicts — value differs / no prior entry / edge-less and
unstorable — which costs nothing with the logger off. `nonLeafRegenerations()` gives the *count* today; this gives
the reason.

### Step 6 — verification

- `./mill __.test`, plus cases in `IncrementalFactGeneratorTest` / `FactCacheTest` for decline-caching, opaque
  drilling, and a **law test** asserting `restore(persist(v))` equals a *freshly recomputed* value rather than a
  retained one. Round-trip equality alone would have passed `VNative` and missed the bug.
- **LANDED**: the metric assertion itself — `WarmBuildLeafOnlyTest` compiles an unchanged tree twice in one session
  and requires `nonLeafRegenerations()` to be empty, so any contributor that forgets the anchor is caught
  automatically.
- Fast example sweep plus byte-identity comparison against a cold build — the jar must be identical whether or
  not the cache was used. (Executable only since 2026-07-31, when jar entries got a fixed timestamp and order.)
- The other direction, which matters as much: touch one source file and confirm a bounded, *correct* subset
  recompiles. Anything that widens what is accepted from cache carries under-invalidation as its risk.

### Target — REACHED (§8)

A warm build regenerates only world leaves, with no processor active but `FileStatProcessor` and
`OutputFileStatProcessor`.

## 7. Open items

- **The load floor.** With the processors silent, cache deserialization is the warm-build cost. Measured in §11,
  attacked by §13.
- **GC / retention.** §9 and §10 both leave entries accumulating with "a light LRU sweep if it ever matters" as the
  follow-up. §11 promoted it to a real lever (both load and save scale with the retained graph), and §13 makes it
  mandatory: a content-addressed store needs a mark-and-sweep from the live index roots.
- ~~**`GeneratedModule` cacheability**~~ — **CLOSED (§14)**: `ClassFile` now compares its bytes rather than its
  array reference, so generated bytecode is equality-stable and acceptable from cache.

## 8. Remeasurement after the totalization, and the Step 2 fix (2026-08-03)

The §3.3 totalization landed 2026-08-02. Re-measured on `examples.run jvm exe-jar -m DischargeDemo`, warm build
immediately after an identical one, instrumenting `regenerate` (why each fact re-ran) and `computeUnchanged` (which
dependency was judged changed):

```
Before (7588845b): regenerated 2129;  4385 ms;  MonomorphicTypeCheck 1341 ms (51.6%), 251 calls
After  (this tree): regenerated  366;   944 ms;  MonomorphicTypeCheck  214 ms (22.7%),   4 calls
```

The 366 broke down by *why* they re-ran — the taxonomy worth keeping, since a future regression will land in one of
these rows:

| count | reason              | fact type                | verdict                                            |
| ----- | ------------------- | ------------------------ | -------------------------------------------------- |
| 252   | value-leaf          | `FileStat` ×249, `OutputFileStat`, `UpToDate`, `SourceContent` | **correct** — world leaves    |
| **92**| **value-leaf**      | **`ContributedBinding`** | **BUG — §3.2**: edge-less, so read as a leaf       |
| **4** | **dep-changed**     | **`MonomorphicValue`**   | **BUG — the §3 cascade, 214 ms**                   |
| 18    | valueless-topdemand | `NativeBinding`, `ContributedBinding` | §3.1 residue, demanded top-level      |

"value-leaf" = the prior entry has a stored value but an **empty** `directDeps`, so `resolve` treats it as a world
leaf and regenerates it unconditionally. Instrumenting the changed-dependency verdict showed exactly **one**
edge-less *and* value-less `ContributedBinding` — neither a comparable value nor edges to drill — poisoning the 4
`MonomorphicValue`s that transitively depend on it and forcing the 214 ms re-typecheck.

**The fix (§6 Step 2).** Every input-less contributor got the same two lines `SystemNativesProcessor` already
carried, above the branch so hit, miss and abort-guarded paths are all covered:

```scala
getFactIfProduced(UpToDate.Key()) >> …the existing body…
```

Each such `ContributedBinding` becomes a non-leaf whose one edge is the always-equal `UpToDate` constant: `resolve`
validates it structurally and **accepts it from cache**, and the value-less one is drilled rather than
re-typechecked, which removes the cascade.

**Measured result**: **366 → 252 regenerations** — the leaf floor — with every native contributor,
`BindingMergerProcessor` and both `MonomorphicTypeCheckProcessor`s at **0 calls**. Wall time 944 → 724 ms. The
projected ~18 value-less §3.1 demands vanished too: they were demanded only *because* the 4 `MonomorphicValue`s
regenerated. **Step 1 is therefore not needed for regeneration count at all — only for the load floor.**

**Verification.** `./mill __.test` green; byte-identical cold-vs-warm jar; touch-one-source moved the warm count
252 → 357 (a bounded subset) with correct output, and back on restore. The `UpToDate` read stays
`getFactIfProduced` (tolerant): a minimal test bundle without `UpToDateProcessor` loses incrementality here but
never fails.

Reproduction: enable `<Logger name="com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator"
level="debug"/>`, build twice, read the `Incremental run: regenerated N` line. (Since §9, `--statistics` no longer
perturbs the config fingerprint, so a warm build can be observed with it on.) The per-reason breakdown came from
temporary traces, reverted — §6 Step 5 would make it standing.

## 9. Per-configuration cache files (2026-08-05)

The cache was a single `<target>/.eliot-cache` whose header carried both fingerprints; a run whose header did not
match got a cold build and then **overwrote** the file. Two configurations sharing a target directory — prod and
test, two different `-m` mains — cleared each other on every switch, and neither was ever warm.

The fix keys the two fingerprints by their **opposite lifecycles**:

- the **config** fingerprint (rarely changes; variants should coexist) goes into the **file name**,
  `.eliot-cache-<config>`, so variants keep separate files under one target;
- the **compiler** fingerprint (changes on every recompile; an old-compiler cache is dead weight) stays in the
  **header**, so a compiler change fails the header check and `save` overwrites the *same-named* file in place —
  no new file per compiler build, no accumulation from compiler churn. The full config value is still in the
  header as an exact-match backstop, so the truncated, sanitized name is only an index: a name collision degrades
  to a cold build, never to serving the wrong cache.

This preserves the `UpToDate` soundness argument — each file is inherently scoped to one compiler+config, so an
anchored constant can never leak across a config boundary.

Paired change: `CacheFingerprint.config` **excludes the diagnostic-only flags** `--statistics` and
`--visualize-facts`. They observe every processor invocation but change no fact, so folding them in gave a
diagnostic run a fresh config — you could not even observe a warm build with `--statistics` on. `CACHE_VERSION`
32 → 33.

Known residual: a *permanently* retired config leaves its file behind. Bounded by real config diversity, not build
count; the sweep is §7.

## 10. One accumulating cache across examples (2026-08-05)

§9 gave each `-m` main its own file, so two examples never *replaced* each other — but never *shared* either: every
example cold-built the whole stdlib/prelude subgraph the first time. This step makes them share **one** file that
**accumulates**. Two independent mechanisms, both required:

1. **The selected `main` stops splitting the cache file.** `mainKey` moves from `namedKey` to
   `Configuration.demandScopedKey` (`docs/cache-identity-configuration.md` §5), so `CacheFingerprint.config` yields
   the *same* fingerprint for every main over the same roots/backend. Nothing else carries the main into the
   fingerprint, so this one reclassification fully collapses the dimension; a different backend or source set still
   gets its own file.
2. **`buildCacheData` accumulates instead of pruning.** It now **retains every prior entry the run did not touch**,
   merged under this run's fresh and carried entries, so another example's monomorphic values survive a build that
   never asked for them.

**Why this is sound — the demand-scoped invariant.** A `demandScopedKey` may exclude itself from the identity only
because the value it injects reaches the fact graph through exactly one **edge-less, always-regenerated leaf**: for
the selected main, the synthetic-entry `SourceContent(eliot-synthetic:main.els)` written by
`SyntheticMainSourceProcessor`. `resolve` never serves a leaf from cache, so every main-dependent fact is reached
from that leaf through recorded edges and is invalidated on demand the moment the main differs. A cache shared
across mains therefore *self-heals*. Facts whose key already encodes the main coexist under distinct keys, and
retained entries are never served stale — the next demand re-validates their dependencies down to the world leaves.

The invariant is the checkpoint for any future `demandScopedKey`: a key may be demand-scoped only if its whole
effect on facts is confined to in-key facts and self-healing edge-less leaves. A key whose value changes a *shared*
fact without an invalidating leaf in the path would miscompile under sharing; it must stay `namedKey`.

**The one subtlety in the accumulation.** Retention excludes every key the run *resolved* (`directDependencies`) or
*carried*, not merely those it materialised. A key regenerated to a **failure** this run is in `directDependencies`
but not in the fresh map, so it is dropped rather than kept at its stale prior success — otherwise a later run
whose inputs are stable at the failing value would accept the stale value and swallow the error.
`IncrementalFactGeneratorTest` pins both directions.

No `CACHE_VERSION` bump (the persisted shape is unchanged). Growth is §9's tradeoff one step larger, and the
resident-server (LSP) in-memory cache now accumulates across edits for the same reason.

## 11. The missing time is the cache, not the compile (2026-08-05)

With regeneration at the leaf floor, "where does a warm build's wall time actually go?" could no longer be answered
from `--statistics`: the report only spanned `compileOnce`, but the two dominant costs sit *outside* it. Cache
**load** happens in `CompilationSession.create`, before the window opens; cache **save** in `persist()`, after it
closes; the fingerprint digest and the `buildCacheData` merge were folded anonymously into the engine remainder.

`--statistics` now accounts for the **whole session lifecycle** (setup → persist), with four coarse cache phases as
explicit lines. An always-on `statistics/PhaseTimings` records them regardless of the flag — four wall-clock
measurements per run, negligible next to the phases themselves, unlike the per-invocation processor wrapping that
stays opt-in — and `ProcessorStatistics.report` subtracts them from the engine remainder so **every millisecond
still lands in exactly one row**.

Measured on the `DischargeDemo` warm build immediately after an identical one:

```
Compiler statistics: 2,985 ms total, 92 ms (3.1%) in processors
     75  FileStatProcessor          } the leaf floor — 249 stats + 1 output digest
      4  OutputFileStatProcessor    }
    156  (offering every key to every processor)     — dispatch
    110  (computing cache fingerprints)
    888  (loading the incremental cache)             — 29.7%
    111  (building the incremental cache graph)
    912  (persisting the incremental cache)          — 30.6%
    716  (compiler engine, plugin setup and i/o)
```

**~60% of a warm build is cache load + save (1,800 of 2,985 ms), and the processors do essentially nothing
(92 ms).** The load floor is no longer a suspected cost; it is the measured one, and it is the
deserialize/serialize of the single Java-serialized graph. Save is now as expensive as load because §10 made the
persisted graph *accumulate*, so both scale with the retained cache rather than with the current main.

Instrumentation only: no fact shape, cache format or `CACHE_VERSION` change.

## 12. Spike: a per-entry store (H2 MVStore), and what it measured (2026-08-06)

§11 pinned the warm-build cost to cache load + save. The obvious next idea — the store *remembers what it already
holds*, so an unchanged tree writes nothing and a one-fact change writes one fact — was prototyped rather than
argued about. The spike is **committed and env-gated**: it changes nothing unless explicitly measuring.

**What was built.** An `IncrementalCacheBackend` seam (`eliotc/src/…/compiler/cache/`) behind the session's
load/save. `JavaSerializationCacheBackend` is the default, delegating to the existing whole-graph `FactCache`.
`MvStoreCacheBackend` (selected by `ELIOT_CACHE_BACKEND=mvstore`) keeps one H2 MVStore open for the session and
stores each entry under its own content key, `base64(serialize(factKey)) → serialize(entry)`. On save it diffs
every entry against a **held snapshot** by value equality: an entry equal to its stored form is neither
re-serialized nor rewritten; only new/changed entries are `put` and gone ones `remove`d; `commit` flushes just the
pages those touched.

**Measured** (`examples.run jvm exe-jar -m DischargeDemo`, warm after an identical build, both backends on the same
machine; absolute figures run higher than §11's because it is a different box, and this tree's cache is 2.49 MB
against §5's 17.9 MB `eliot-build` tree):

```
phase            Java (default)     MVStore spike
cache load       1333 ms  30.7%     3113 ms  66.4%
cache save        978 ms  22.5%       28 ms   0.6%
build-cache       178 ms              72 ms
fingerprint       130 ms             146 ms
engine           1210 ms            1016 ms
processors        186 ms             108 ms
total            4342 ms            4687 ms
on-disk          2.49 MB            42 MB  (~17×)
```

**Three findings:**

1. **The delta-write hypothesis is confirmed.** Save collapses **978 → 28 ms**: a warm build writes essentially
   nothing. Every world-leaf fact — re-computed each run, so a fresh object — still compares value-equal to its
   stored form and is skipped, and the retained subgraph (§10) is untouched by construction.

2. **Per-entry storage alone makes *load worse*, not better (1333 → 3113 ms).** `load` still eagerly deserializes
   every entry, so per-entry framing only piles store overhead onto a bloated file. The load floor is not cut by
   *where* entries live; it is cut by not materializing the values a warm build's validation walk never needs —
   most facts are drilled through their edges or compared, not returned.

3. **Per-entry framing reproduces the §5 disk blowup (~17×).** Independent frames re-emit the shared `ValueFQN` /
   `ModuleName` / `Sourced` / class descriptors that one shared stream writes once.

**What it decided.** The engine was never the bottleneck — MVStore's diff/commit machinery delivered the delta
write on its own. The per-entry *framing* and the *eager load* are the costs, and both are properties of Java
serialization, not of the store. That is what §13 acts on; the earlier reading of these findings (build a shared
symbol/class-descriptor intern table beside the frames) is **superseded** — see §13's "what this makes obsolete".

**Handover notes.**

- **Turn it on:** `ELIOT_CACHE_BACKEND=mvstore` (unset / anything else ⇒ the Java graph). Cold run to populate,
  then a warm `--statistics` run; read the four cache-phase lines (§11). File: `<target>/.eliot-cache-mv-<config>`.
- **Dependency:** `com.h2database:h2-mvstore:2.2.224` on `eliotc` — pure-JVM, no native library.
- **Tests:** `eliotc.test` green; the default path is byte-for-byte unchanged (the flag is off). There is **no**
  test of the MVStore path itself — it is a spike.
- **Deliberate spike limitations:** load is eager (finding 2); the store is never explicitly closed (no session
  teardown hook — it relies on `commit` for crash-consistency and process exit to release the lock); the content
  key assumes deterministic serialization of equal first-order fact keys (a nondeterministic key orphans a row — a
  size leak, never a correctness fault); the small §3.1/§4 unstable set is rewritten every save.
- **Files:** `IncrementalCacheBackend`, `JavaSerializationCacheBackend`, `MvStoreCacheBackend`,
  `FactSerialization`, and the two-call-site wiring in `CompilationSession`.

## 13. The plan: explicit codecs over a content-addressed object store (2026-08-06)

§§11–12 leave two costs that look like two problems with two fixes — a shared intern table for save, a graph/value
split for load. They are one fix. **Own the format:** give every persisted type an explicit codec, and store
objects in a flat `id → bytes` map where every reference is an id.

### The shape

- **Explicit codecs, no Java serialization.** A codec writes its own fields and the type tag is a byte, so there
  are no class descriptors in the data at all — most of §5's 8 KB per frame.
- **The id is the hash of the object's own bytes, with children written as their ids.** An object is therefore
  stored at most once, ever — globally and *across runs*, where the shared graph dedups only within one file write.
- **Entries become an index**: `factKeyId → (valueId, depIds)`. That index is the only thing a load must read.

### Six properties that fall out rather than being engineered

1. **Duplicate storage is impossible by construction** — not "an intern table catches the common ones": writing an
   object whose id exists is a no-op. Monomorphization emits structurally identical subtrees everywhere, so the
   cache should land *below* today's 2.49 MB rather than merely back at it.
2. **No class descriptors** at all, so that whole term disappears instead of being tabulated. (§5 read the
   per-entry blowup as "back-references *and* class descriptors"; §14 separates them and finds the sharing term is
   by far the larger — see the correction there.)
3. **Delta-write is free.** Nothing to diff: the spike's held snapshot, its value comparison, and even the
   refinement of driving save from the engine's regenerated set all become unnecessary.
4. **Lazy load falls out** — read the index, chase ids only for values actually consumed.
5. **The equality cutoff stops materializing anything.** This is what cuts load, and no Java-serialization layout
   offers it. §1's "recompute and compare" today deserializes the stored value to compare against the recomputed
   one; with content ids you hash the recomputed value and compare 16 bytes, never touching the stored side. Most
   warm-build facts are *validated*, not consumed, and today every one of them pays a full deserialize.
6. **`GeneratedModule` becomes cacheable** (§4, §7): its codec normalizes `ClassFile`'s array to a sequence, so the
   type stops being reference-compared. The same move makes §2's equality-stability a codec law that can be
   enforced instead of an accident of the serializer.

### What it costs, honestly

The reachable surface is ~300 case classes, 9 enums and 42 fact key types, nearly all in `lang`. Hand-writing 300
codecs is not the project — **Scala 3 `Mirror` derivation is**, covering products and sums of primitives, strings,
collections and each other mechanically. **The whole risk is what refuses to derive**, which is why that is the
first thing built. Expect the leaves — `Sourced`, `Path`/`URI`, `BigInteger`, `ClassFile`'s array — plus the three
§4 declines.

Three obligations the current format does not have:

- **GC.** Rewriting the whole graph implicitly collected garbage; a content store accumulates and needs a
  mark-and-sweep from the live index roots. §7's retention item becomes mandatory rather than a lever.
- **Hashing on write** — memoize by object identity within a run, so the cost is proportional to new objects rather
  than to the retained graph.
- **A collision is a correctness fault**, so the id must not be truncated below 128 bits.

Two things get *easier*. Versioning is a non-problem: `CacheFingerprint.compiler` already discards the cache on any
compiler change (§1), so codecs may change freely and there is no migration story to write. And the format stops
storing what it does not need, which is the whole reason Java serialization's output is an order of magnitude above
the information content.

### What this makes obsolete

- **The shared symbol / class-descriptor intern table** (§5's blockquote, §12 finding 3). It exists only to make
  Java serialization tolerable and dies on contact with owning the format. **Do not build it.**
- **Chunked / bucketed frames** — the cheaper variant of the same idea, obsolete for the same reason.
- **The representation-based `FactCodec` as a container design** (§6 Step 1). Its three decision rules survive; its
  container does not.

The MVStore spike keeps its value: it proved the write side and left the `IncrementalCacheBackend` seam this lands
behind. MVStore remains a fine engine for `objects: id → bytes` plus `entries: keyId → (valueId, depIds)`.

### Build order (de-risking first)

**The one hard sequencing constraint, from §14:** explicit codecs and content addressing **ship together**. Codecs
alone measured 4.89× *worse* than the Java graph, and only 0.55× once sub-values are shared — so a release that has
the first without the second puts a fivefold regression on disk. Everything below is one step 2 in that sense; the
numbering is the order to build in, not the order to land in.

1. **Derivation prototype, coverage proven by the compiler** — done and measured, §14. The refusal set is the leaves
   plus the known declines, and the numbers clear the go/no-go.
2. **Make the fact model encodable, and put the decision on the type** — done, §15.
3. **Codec + id + store layout** behind the existing backend seam — content-hash ids in place of the in-stream
   back-references, `entries: keyId → (valueId, depIds)` as the index, index read eagerly and values lazily. The
   `Output`/`Input` seam already localises sharing to one place, so this touches neither the codecs nor the
   derivation. Fold in §6 Step 3 (cache declines) here, since it changes the entry shape anyway.
4. **Switch the equality cutoff to id comparison** — the load win, and the only step that changes what "unchanged"
   is *decided from*.
5. **GC sweep** from the live index roots.
6. **§6 Step 6 verification**, weighted toward under-invalidation: byte-identity cold-vs-warm, and
   touch-one-source-file for a bounded *correct* subset. Step 4 above changes the invalidation decision itself, so
   that direction matters more here than in any previous step.

## 14. Step 1 measured: the codec prototype (2026-08-06)

§13's first step — build the encoding, let the compiler prove coverage, and measure — is done. Nothing about a real
build changes: `FactCodec` is a new type in `eliotc` that nothing calls, and the instances and harness live in
`jvm/test`. Two small main-source changes did land, both forced by findings below.

**What the compiler proved.** `FactCodecs` names one instance per type reachable from a fact — **299 derived, 2
hand-written, ~16 leaf instances** in `FactCodec` itself. Because a field whose type has no instance is a compile
error, coverage is not a claim: **39 of the 41 fact types encode, and the 2 that do not are exactly §4's prediction**
(`ContributedBinding` and `NativeBinding`, whose `BindingContribution.Leaf` carries a `SemValue` closure). Derivation
covers products, sums, enums and singletons with no per-type thought; deriving all 299 costs about **1 s of
`jvm.test` compile time** (2.1 s against 1.1 s for an ordinary file), which is not a cost worth weighing.

**The size result, and it inverts §5's reading.** Measured over the 1,659 facts of a small real build:

```
java serialization, one shared graph:      1,979,690 bytes   (baseline)
explicit codecs, independent frames:       9,673,313 bytes   4.89×
explicit codecs, shared sub-values:        1,090,704 bytes   0.55×
```

Two things follow, and the second is a **correction to §5 and to §13's claim 2**:

1. **Explicit codecs alone are a 4.89× regression.** Removing every class descriptor, tagging sums with one byte and
   varint-encoding all integers still loses badly to `ObjectOutputStream` — because independent frames give up
   structure sharing, and `ObjectOutputStream` back-references aggressively.
2. **Structure sharing, not class descriptors, is the dominant term.** §5 attributed the per-entry blowup to
   "back-references *and* class descriptors" and §13 called descriptors "§5's dominant term". With descriptors at
   *zero* the penalty is still 4.89×, so the descriptors were the smaller half. Sharing the leaves — `String`, `URI`,
   `Path` — mattered as much as sharing the products: a compiler's facts repeat the same names, module paths and file
   URIs thousands of times, and every `Sourced` carries a full file URI.

**What that means for §13: the design is confirmed, the step order is not optional.** Content addressing is not an
optimization layered on explicit codecs — it is the thing that makes them viable at all. Shipping codecs first and
adding ids later would put a 4.89× regression on disk in between. They land together or not at all. With sharing,
codecs come out **45% below** the Java graph *before* any cross-run dedup, which is the property §13 claim 1 rests
on and the first evidence that the store can be smaller than today's 2.49 MB rather than merely equal.

The sharing measured here is by **value equality within one stream**, which is the byte-level model of the
content-addressed store (a value equal to one already written costs a back-reference). The real store replaces the
back-reference with a content-hash id, which additionally dedups **across runs** — so this number is a floor, not a
ceiling.

**Two findings the plan did not predict**, both surfaced by the compiler refusing to derive:

- **`GroundValue.Direct(value: Any, …)` has an untyped payload slot.** No codec can be derived for `Any`, and
  `GroundValue` is reachable from most of the monomorphize layer, so this one field blocks a large part of the fact
  surface. At runtime the slot holds exactly three shapes (`BigInt`, `String`, `Boolean` — as `PostDrainQuoter`'s
  scaladoc already documents), so the prototype encodes it with a discriminating tag and fails loudly on anything
  else. **The real fix is `Direct(value: Literal)` over a small sealed sum**, which is a mechanical but
  cross-cutting change to the checker's pattern matches; it is step 2 work, not a blocker.
- **`core.fact.Expression` was an unsealed `trait`** whose cases all live in its own companion, so it had no `Mirror`.
  Sealed now — a one-word change the compiler verifies, with no behavioural effect.

**One open item closed.** §7 listed "`GeneratedModule` could be made comparable by giving `ClassFile` a
value-comparable representation". The law test failed on exactly those 8 facts, so `ClassFile` now compares its
bytes rather than its array reference (a `case class` with an explicit `equals`/`hashCode`). This is sound because
code generation is deterministic — the same property the byte-identity build guard already relies on — and it makes
generated bytecode **acceptable from cache** rather than merely drillable. All of `__.test` is green with it.

**What the harness checks, standing** (`FactCodecConformanceTest`, three assertions over a real build):

- every fact type a build materialises is either covered or explicitly declined;
- every covered fact round-trips to an **equal** value — the half of §2 that reaches runtime. The other half (a value
  that round-trips equal but *recomputes* different, i.e. `VNative`'s lambda) cannot reach the harness at all,
  because no `FactCodec` exists for a function. That is the fail-safe property the whole approach turns on: a
  reflective walker would encode both silently.
- the shared encoding is smaller than Java's shared graph.

**Where step 2 picks up.** Move the instances onto the types as `derives FactCodec` (deleting `FactCodecs`), replace
`Direct(value: Any)` with a sealed literal, then build the store: content-hash ids in place of the in-stream
back-references, `entries: keyId → (valueId, depIds)` as the index, values read lazily. The codec's `Output`/`Input`
already localise sharing to one place, so the swap from "back-reference within a stream" to "id into an object
table" touches neither the codecs nor the derivation.

## 15. Step 2: the persistence decision moves onto the fact key (2026-08-06)

§14's prototype proved the encoding; this makes it part of the compiler rather than of a test. Still nothing reads or
writes a cache differently — the store is step 3 — but after this the codecs are main-source and the decision is
stated where §6 Step 1 says it belongs.

**`GroundValue.Direct(value: Any)` is now `Direct(value: Literal)`** over a sealed enum of the four shapes that ever
occupied the slot: `IntegerValue`, `StringValue`, `BooleanValue`, `UnitValue`. The `Any` was not merely unencodable;
it meant no consumer could match exhaustively, and the defensive arms show it — `IntRepresentation.directBigInt` was
accepting `BigInt`, `java.math.BigInteger`, `Int` and `Long`, three of which could never occur. Construction sugar
keeps the call sites where the payload type is statically known unchanged; every *pattern* site is explicit, because
matching is where the closed set has to be visible. The compiler found all of them — an unreachable type test is an
error, not a warning — and `Direct` now derives its codec like everything else, so §14's hand-written stand-in is gone.

**`CompilerFactKey.valueCodec` is abstract with no default.** Every fact states its own persistence decision once:

```scala
case class Key(uri: URI) extends CompilerFactKey[SourceTokens] {
  override def valueCodec: Option[FactCodec[SourceTokens]] = Some(LangFactCodecs.sourceTokensCodec)
}
```

`None` is a **decline**, not a failure: the fact still takes part in change detection through its edges (§1's
structural drill), it just cannot be handed back from cache. Two facts decline, and the conformance test pins that it
is exactly those two — `ContributedBinding` and `NativeBinding`, whose `SemValue` closures are §3.1. A new fact type
that states nothing does not compile, which is the whole point: both possible defaults are wrong (§6 Step 1), and the
"defaulting to persist" one is the bug that started this document.

**The structural instances are grouped per layer, not written on each declaration** — `CoreFactCodecs` (7),
`LangFactCodecs` (199), `JvmFactCodecs` (4), each importing the ones below it. This is a deliberate departure from
"`derives FactCodec` on every type", for a reason worth recording: those 199 are *structural* types, not facts, and
have no persistence decision to make — the decision is a property of the fact, and that is exactly where it now lives.
Grouping them also keeps the compile-time coverage proof readable as one list. What made the split possible at all is
that `FactCodec.derived` now builds a sum's case codecs from their own `Mirror` instead of summoning an instance per
case (302 named instances → 210): an `enum` case cannot carry a `derives` clause, so a per-case requirement would have
made per-declaration derivation impossible for every sum in the fact model.

**The conformance test now checks the shipped decision**, not a test-side registry: it reads each fact's codec from
its own key. Its three assertions are the declines being exactly the two expected, the round-trip law over every
covered fact, and the shared encoding beating the Java graph (1,093,088 vs 1,979,690 bytes — 0.55×, unchanged by this
step). `__.test` is green.

**Step 3 can now be written against `key.valueCodec`.** The one thing it still needs and this step does not provide is
a **stable type tag**: encoding a heterogeneous fact stream is fine from the key, but *decoding* one has to pick a
codec before it has a key, so the store needs a tag→codec table that survives across runs. That is a store concern,
not a fact-model one, and it is the first thing step 3 has to settle.

## 16. Handover: what exists, and what step 3 has to decide first (2026-08-06)

### What is on disk, and what it is wired to

| File | What it is | Wired into a build? |
| ---- | ---------- | ------------------- |
| `eliotc/…/cache/codec/FactCodec.scala` | the encoding: trait, `Output`/`Input` (sharing lives here), leaves, containers, `derived` | no |
| `eliotc/…/cache/codec/CoreFactCodecs.scala` | 7 structural instances (positions, the cache's boundary facts) | no |
| `lang/…/codec/LangFactCodecs.scala` | 199 structural instances — the bulk of the fact model | no |
| `jvm/…/jvm/codec/JvmFactCodecs.scala` | 4 instances + the hand-written `ClassFile` codec | no |
| `CompilerFactKey.valueCodec` | the per-fact decision, abstract with no default | **yes** — every fact key implements it |
| `jvm/test/…/codec/FactCodecConformanceTest.scala` | the three standing checks (declines, round-trip law, size) | test only |
| `eliotc/…/cache/{IncrementalCacheBackend, JavaSerializationCacheBackend}.scala` | the load/save seam and today's default | **yes** |
| `eliotc/…/cache/{MvStoreCacheBackend, FactSerialization}.scala` | the §12 spike, `ELIOT_CACHE_BACKEND=mvstore` | env-gated only |

`valueCodec` is the only part that a build compiles against, and it computes nothing at run time. Everything else is
inert until a backend calls it.

### The first decision: a stable type tag

Encoding a heterogeneous fact stream works from the key — you have the fact, so you have its codec. **Decoding does
not**: the reader has to choose a codec *before* it has a key, from a tag in the bytes. So the store needs a
tag → codec table, and the tag has to mean the same thing on the next run.

Two properties it must have, and they pull against each other:

- **Stable across runs**, or the whole cache is unreadable after an unrelated change. A tag derived from ordering (a
  `Seq.indexOf`, as the conformance test's write-only codec uses) is not: adding one fact type shifts every tag after
  it. A tag derived from the fact class's *name* is stable, costs a shared string once given the intern table, and
  degrades safely — an unknown name is a cache miss, not a mis-decode.
- **Complete, or a hard error.** A fact type absent from the table must fail loudly at *save*, never write a value
  nothing can read back. `CompilerFactKey.valueCodec` is compile-time-complete for the *value*; the tag table is a
  runtime map, so it needs its own guard. The conformance test's decline assertion is the model to extend.

Note that `CacheFingerprint.compiler` already discards the whole cache on any compiler change (§1), so tags need to
survive only within one compiler build. That makes "hash of the class name" sufficient and removes any migration
story — worth remembering before designing something more elaborate.

### The order that matters, and the trap

§14 measured explicit codecs **without** sharing at 4.89× the Java graph and **with** sharing at 0.55×. So:

> **Do not land the codecs as a cache format before content addressing works.** The intermediate state is a fivefold
> disk regression, and it is a state that looks finished — the encoding is complete, the tests pass, the format is
> "modern". The sharing is not a follow-up optimization; it is the half that pays.

The sharing model is already localised: `FactCodec.Output.shared` / `Input.shared` is the *only* place a repeated
value is collapsed. Swapping the in-stream back-reference for a content-hash id into an object table touches that one
pair of methods and nothing else — not the codecs, not the derivation.

### What is left after step 3, unchanged from §13

Steps 4–6 of §13's build order: the equality cutoff becoming an id comparison (the load win, and the only change to
what "unchanged" is *decided from*), the GC sweep from the live index roots, and §6 Step 6's verification weighted
toward under-invalidation. §6 Step 3 (caching declines) should be folded into step 3, since it changes the entry
shape anyway.

### Two things to retire when step 3 lands

- **The MVStore spike** (`MvStoreCacheBackend`, `FactSerialization`, the `h2-mvstore` dependency). Its finding is
  banked in §12 and its layout is refuted by §14; MVStore itself may still be a fine engine for `id → bytes`, but the
  per-entry-Java-frame code has no future. Delete it rather than leave a second, wrong cache path behind an env var.
- **`FactCache.canSerialize`** and the trial-serialization probe (§2), once nothing reads the Java graph. It is the
  mechanism every §3 defect came from, and it has no place beside a compile-time-complete decision.

### Where the numbers came from, if they need re-running

`./mill jvm.test.testOnly com.vanillasource.eliot.eliotc.jvm.codec.FactCodecConformanceTest` prints the three sizes
and a per-fact-type breakdown for a small real build (1,659 facts). §11's four cache-phase lines come from
`--statistics` on a warm build; §12's handover notes say how to run the MVStore comparison while it still exists.

## 17. Step 3, first half: the object encoding, and why references are offsets (2026-08-06)

§13 specified the store as `id → bytes` with **children written as their ids**, and §16 made "a stable type tag" the
first thing to settle. Building it measured both away. What §13 got right is the shape — content-addressed objects,
dedup by construction, lazy chase — and that shape now exists and round-trips. What it got wrong is the reference.

**Measured, over the same 1,659 facts of §14, against the shared stream (1,090,464 bytes) rather than against Java,
because §14 already established the stream as the bar:**

```
explicit codecs, one shared stream:        1,090,464 bytes   (baseline)
content-addressed, 16-byte ids inline:     6,239,311 bytes   5.72×
content-addressed, varint object numbers:  1,173,712 bytes   1.08×
content-addressed, varint byte offsets:      980,192 bytes   0.90×
```

**§13's literal layout is a 5.7× regression, and the distribution says why.** Of 61,653 objects in a real build's fact
graph, **48,607 — 79% — are referred to exactly once**, holding 84% of the body bytes. Compiler facts decompose into
*tiny* objects: ~15 bytes each. A 16-byte reference does not annotate such a body, it doubles it, and four in five of
them are pointed at from exactly one place, so the reference can never be amortised over reuse. The multiply-referred
minority (12,370 objects, 158 KB) is where all the sharing value is, and it is small.

**The fix is to separate the two jobs an id was doing.** They pull in opposite directions and only one of them has to
be on disk:

- **Identity** — `ObjectId`, a 128-bit Merkle hash: the object's own bytes with each child contributing *its id*,
  never a position. Nothing run-specific enters it, so it means the same thing in any store. This is what dedup keys
  on and what lets a recomputed value be compared to a stored one by 16 bytes without reading the stored side (§13
  claim 5). It is computed during encoding and **never written into a body**.
- **Location** — the object's **byte offset** in the append-only body region, varint-encoded. Stable for exactly the
  reason a sequence number is (the region only grows), but *self-locating*: unlike numbers, offsets need no table to
  be written or read, which matters precisely because a per-object table entry is the cost that 79% of objects cannot
  carry. That is the whole gap between 1.08× and 0.90×.

All six of §13's properties survive this, because none of them was about the reference width. The store is 0.90× the
shared stream and 0.49× Java **before any cross-run dedup**, so §13 claim 1 — that owning the format lands *below*
today's 2.49 MB rather than merely level with it — now has a second measurement behind it.

### The type tag dissolves, and a subtler hazard replaces it

§16 required a stable tag → codec table because "decoding has to pick a codec before it has a key". With the store
laid out this way it does not: a fact's value is decoded with the codec its own key states, and every object below it
is reached structurally by a codec that already knows what it is reading. **No tag appears anywhere in the format.**

**What is left of the tag is keys, and only keys.** A fact's value is decoded by the codec its own key states; a
*key* has nothing above it to say what it is, and the validation walk must materialise dependency keys to ask the
engine to recompute what they identify. So keys are stored beside their class name, mapped back through
`FactKeyCodecs.Registry` — assembled per layer beside the structural instances, exactly as §16 specified: stable
across runs (a name, not an ordering), and complete-or-loud, since `valueCodec`'s compile-time completeness cannot
extend to a table that is consulted before a key exists. The conformance test pins that every key type a real build
materialises is registered and reads back equal. The two **declining** facts appear in the table like any other: a
decline withholds the value, never the edges, so its key is still stored and still walked.

What does need care is the opposite of what §16 expected. **The store is untyped on purpose**, and two different types
routinely encode to identical bytes — a `URI` and its own string form, `Sourced[String]` and `Sourced[Token]`. Sharing
their *storage* is sound: the bytes are the same and the reader always supplies the codec. Sharing a *reader's cache*
of decoded values is not, and that is a live bug, not a hypothetical — it is what the round-trip law caught on the
first build, across a dozen fact types. `ContentAddressedInput` therefore keys its memo on **offset and reader**, the
reader being an identity token each derived codec carries. Recording the shape, since the instinct is to reach for a
class name: a class name does not discriminate generic types, and `Sourced[A]` alone makes that routine.

Two mechanical traps, both worth naming because both look like working code:

- **`mutable.HashMap.getOrElseUpdate` may not be used here, on either side.** Computing an object's id writes its
  children, and decoding an object decodes its children — both re-enter the very map the update is being placed in.
  The symptom is not an exception; it is a silently wrong graph.
- **A `lazy val` cannot tie the self-reference knot** for a codec that names itself, because the builder forces it
  while initialising. A plain identity token is what the reader memo actually needs.

### What exists now, and what step 3 still owes

New in `eliotc/…/cache/codec/`: `ObjectId`, `ContentAddressedOutput` (write: frames, Merkle hashing, dedup, append),
`ContentAddressedInput` (read: follow an offset, memoize per reader). `FactCodec.Output`/`Input` are now traits with
`Plain` / `Sharing` implementations beside the content-addressed pair — the swap §16 predicted would touch one pair of
methods did touch exactly that pair, plus the `shared` signature on the read side. `FactCodecConformanceTest` gains
the store round-trip law and the size assertion. **Still nothing a build does has changed**: no backend uses this yet.

### The store as a backend, and what it measures (2026-08-06)

`ContentAddressedCacheBackend` is wired behind the existing seam and selected by **`ELIOT_CACHE_BACKEND=store`**, the
default still being the Java graph. Two files with opposite lifecycles, which is why they are two:

- **the body region** (`.eliot-objects-<config>`) is **append-only**. An offset written last run resolves this run
  precisely because nothing before it ever moves; a save appends what is new and rewrites nothing.
- **the index** (`.eliot-index-<config>`) is rewritten every save, and is the only thing a load must read in full.

**A carried-forward value is never re-encoded**, and this is not an optimisation — it is what makes the append model
work at all. On a warm build every entry survives, so re-encoding would append the whole graph every run. A value
still in the store is placed from its identity alone, without being read. (This first shipped keyed on *reference*
identity — the object carried out of the store is the one that was read — which §18 replaced with content identity,
since that also covers a value recomputed to an equal-but-fresh object.) `ContentAddressedCacheBackendTest` pins it:
saving back what was loaded appends zero bytes.

An entry is placed **whole or not at all**. A key type with no registered codec cannot be read back, and dropping
merely the *dependency* naming it would leave an entry claiming fewer inputs than it has — under-invalidation, the one
failure direction that yields a wrong build rather than a slow one.

**Measured** (`examples.run jvm exe-jar -m DischargeDemo`, warm after an identical build, same machine, same tree as
§12):

```
phase              Java (default)      content store
cache load           537 ms  35.9%      276 ms  29.7%
cache save           395 ms  26.4%       97 ms  10.4%
build-cache           35 ms              33 ms
fingerprint           49 ms              46 ms
engine               375 ms             366 ms
processors            31 ms              29 ms
total              1,494 ms             930 ms        -38%
on-disk             2.49 MB            1.26 MB        0.51×
```

**Load −49%, save −75%, the whole warm build −38%, and the jar is byte-identical** across java-warm, store-cold and
store-warm. Note what has *not* been done yet: load still materialises every value eagerly, and the equality cutoff
still deserialises and compares. Both are §13 step 4, so the load column has considerably further to fall.

**Growth, measured rather than estimated**: a warm build appends **~17.9 KB**, 1.4% of the store — exactly the world
leaves, recomputed to equal-but-fresh objects that reference identity cannot recognise. So the store doubles in ~50
warm builds, which sets the compaction trigger comfortably. It is also **subsumed by step 4**: once the cutoff
compares `ObjectId`s, a recomputed leaf's id is computed anyway, and finding it equal to the stored one means its
offset is already known, so nothing is appended at all.

## 18. Step 4: the cutoff stops materializing, and the store stops growing (2026-08-06)

§13 step 4 was "switch the equality cutoff to id comparison". Building it found that the larger win was one layer
above: **most of the cutoff should not run at all.**

### The comparison that compared a value with itself

`computeUnchanged` validated a value-bearing derived fact by calling `getFactUntyped` on it — which resolves the key,
finds its dependencies unchanged, **accepts the cached value**, and hands it back, whereupon the entry compared that
value against itself. Every derived fact on a warm build was materialised to satisfy a tautology.

A fact is a pure function of its recorded dependencies — the assumption `acceptPrior` already rests on. So
dependencies that all hold mean **the fact holds**, with nothing recomputed and nothing read. The branch is now the
same drill the value-less facts always took, and the two differ only in the bookkeeping afterwards: a value-less
entry is carried forward to stay drillable, a value-bearing one is simply untouched and retained. Recompute-and-
compare is left with exactly the two cases that need it — a **world leaf**, whose only oracle is running it, and a
fact whose dependencies genuinely moved, where equality is what stops propagation.

Consequently a no-change run materialises **no fact value at all** beyond the leaves it must re-read. This is
backend-independent and helps the Java graph too — though not much, since that one deserialises everything at load
regardless.

### The cutoff itself, and the order that turned out to matter

`CacheEntry` holds a `CachedValue` rather than an `Option[CompilerFact]`: `hasValue` and `matches` are what
change-detection asks, and neither reads anything. A stored value is an `ObjectId` plus a thunk — `matches` hashes the
*recomputed* side with `ObjectIdOutput` and compares 16 bytes, never touching the stored side; the fact behind it is
decoded only where something consumes it (`acceptPrior`, and nowhere else).

The store's own growth turned out to need the same idea, applied in the opposite order, and this is the finding worth
keeping:

> **Identify before placing.** Seeding the writer with the ids it already holds is not enough. Encoding descends into
> a value's children and appends each as it goes, so by the time it can tell the value as a whole is a duplicate, the
> whole subtree underneath is already on disk. Hashing first costs no bytes and no buffers and asks the only question
> that matters.

With that, a warm build appends **zero bytes** — measured over three consecutive runs, the object region does not move
— where before it grew ~17.9 KB a run. The cold-build path skips the pre-check when there is nothing to match against,
so nothing is hashed twice.

### Measured

`examples.run jvm exe-jar -m DischargeDemo`, warm after an identical build, same machine and tree as §12/§17:

```
phase              Java (default)      content store
cache load           520 ms  35.9%      107 ms  14.7%      -79%
cache save           412 ms  28.5%      100 ms  13.8%      -76%
build-cache           18 ms              11 ms
fingerprint           46 ms              48 ms
engine               341 ms             349 ms
processors            36 ms              33 ms
total              1,447 ms             727 ms             -50%
on-disk             2.49 MB            1.29 MB             0.52×
warm-build growth        —              0 bytes
```

**A warm build is half what it was**, and cache I/O has gone from ~64% of it to ~29%. What is left in the load is
reading and decoding the index — every key, every dependency edge — which is now the floor rather than the values.

### Verification

- `__.test` green (871).
- **Byte-identity**: the jar is identical across java-warm, store-cold and store-warm.
- **Under-invalidation**, the direction that matters more (§6 Step 6): an mtime-only touch changes nothing; a real
  source edit produces a warm-build jar **byte-identical to a cold build of the same content**; reverting the edit
  restores the original jar exactly.

### Retired here

The **MVStore spike** (`MvStoreCacheBackend`, the `h2-mvstore` dependency, the `ELIOT_CACHE_BACKEND=mvstore` arm).
§16 scheduled it for removal once step 3 landed; carrying a refuted design through the entry-shape change was the
concrete reason to do it now. Its findings stay in §12. `FactSerialization` remains — the conformance test still
measures against Java as the baseline.

### What is left

- **§6 Step 3** — the entry shape carrying declines. The index already writes a per-entry flag to widen.
- **§13 step 5** — compaction: decode-and-re-encode of the live entries, not a mark-and-sweep, since the bodies are
  untyped bytes. Now much less urgent than it looked — a warm build no longer grows the store at all, so garbage
  accumulates only from genuinely changed facts.
- **§13 step 6** — flipping the default, and retiring `FactCache.canSerialize` with the Java graph.
- The load floor is now the **index** — measured in §19, which answers whether making it lazy is worth anything.

## 19. Where the remaining load goes, and why it is not worth chasing (2026-08-06)

§18 left the load at 107 ms and named the index as the floor. Measured rather than assumed, with the load broken into
its parts:

```
reading the 1.19 MB object region        1.1 ms
decoding the index (keys and edges)     88.1 ms   2,398 entries, 12,647 dep refs
  the same decode, 2nd pass in-process  41.3 ms
  3rd pass                              40.5 ms
  4th pass                              33.6 ms
```

**Three findings, and together they close the question.**

1. **Reading the objects is free.** 1.1 ms for 1.19 MB — the values really are lazy, and nothing is gained by moving
   keys out of the object region or by mapping the file. Whatever the load costs, it is not I/O.
2. **Over half of it is JIT warm-up, not work.** The same decode drops from 88 ms to ~35 ms by the fourth pass in the
   same JVM. A CLI build gets exactly one pass, so it pays the cold price every time and *no algorithmic change
   removes it*. A resident session (the LSP) already pays only the ~35 ms.
3. **Decoding scales with the whole store, not with the build.** With five examples accumulated into one cache (§10),
   entries go 2,398 → 4,136 and the load goes 107 → 147 ms, though a single example needs only its own slice.

**So would a lazy index pay?** Only for the slice nothing reaches. The structural drill visits every *reachable*
entry and every one of its edges, and it needs those keys as objects to ask the engine to recompute what they
identify — so the reachable set must be decoded either way. The saving is the unreachable remainder, ~40% of entries
in the five-config store above, or ~25 ms of a cold 147 ms. Against that: `prior` would have to be re-keyed by a hash
of the *encoded* key, and every lookup would then pay an encode-and-hash roughly as expensive as the decode it saves.
**Not worth it**, and recorded here so it is not re-proposed.

One micro-fix did land while looking: the index's type-name table was a `List` indexed per dependency reference
(O(n)) with a `Map[String, codec]` lookup on each. It is now an array of already-resolved codecs, one lookup per
distinct name. It measured **no change** — kept because it is strictly less work, not because it helped.

### What the profile looks like now

Warm build against a five-config accumulated store — the harder of the two scenarios, and the one that shows the gap
widening as a cache ages:

```
phase              Java (default)      content store
cache load           692 ms  37.5%      147 ms  18.2%      -79%
cache save           616 ms  33.4%      141 ms  17.4%      -77%
build-cache           16 ms              11 ms
fingerprint           51 ms              47 ms
engine               337 ms  18.2%      352 ms  43.5%
processors            46 ms              33 ms
total              1,847 ms             809 ms             -56%
on-disk             3.62 MB            2.48 MB             0.69×
```

**The cache is no longer the dominant cost.** At 36% of a warm build (down from ~71% on this scenario), it is behind
`compiler engine, plugin setup and i/o` at 43.5%, which is now the thing to measure if a warm build needs to get
faster still.

> **The attribution in that last sentence was wrong — CORRECTED by §23.** It read "which is JVM start, plugin
> discovery and processor-graph construction". Measured: JVM start is not in that row at all (the clock starts after
> it), plugin discovery is 6 ms, and only processor-graph construction is genuinely there. The row is real; two of
> its three named causes are not in it. Worse, the guess pointed at the wrong scale — the largest single cost of a
> warm build is *outside* every row of this report.

## 20. Step 6: the store is the only backend (2026-08-06)

Java serialization is gone from the compiler. The store is not selected, configured or switched — it is how the cache
works.

**Deleted**, not deprecated: `FactCache` (the whole-graph stream, its `Path` stand-in and its trial-serialization
probe), `JavaSerializationCacheBackend`, `FactSerialization`, `FactCacheTest`, the `IncrementalCacheBackend` seam and
the `ELIOT_CACHE_BACKEND` environment switch. The seam existed to A/B two backends; with one backend it was an
interface with a single implementation, so `CompilationSession` now holds the store directly.

**`FactCache.canSerialize` goes with it**, which closes §2. Persistability was decided by *trial serialization* — "did
this serialize?" — which cannot ask the question the cache actually runs on, "will it still compare equal?". Every
defect in §3 came from that gap. It is now a compile-time property of the fact key (§15) with no runtime probe behind
it.

**`FactCacheData.version` is deleted too.** It existed to discard a cache whose persisted fact shapes had changed —
which cannot happen without the compiler changing, and `CacheFingerprint.compiler` digests the whole running
classpath, so any such change already discards it. §13 called versioning a non-problem; this is that, applied. The
store header keeps its magic and format version, which identify the *file* rather than the fact model.

**Two codec paths went with the Java baseline.** `Output.Sharing` / `Input.Sharing` and `toSharedBytes` existed only
to measure the codecs against Java's one shared graph (§14). With nothing to compare against they were main-source
code no build ran, so the standing size guard now compares the store against the same facts as **independent frames**,
which is the property that actually matters and uses the `Plain` path the round-trip law already needs. It measures
**0.10×** — the store is a tenth of unshared frames.

Verified: `__.test` green (871), and a default build with no environment set writes `.eliot-objects-<config>` /
`.eliot-index-<config>`, with the jar byte-identical cold and warm.

Two things are worth keeping in view now that the old path is gone. There is **no fallback** — a defect in the store
is a defect in the cache, which is what the conformance law, the backend round-trip and the byte-identity check exist
for. And **concurrent writers are still unhandled**: two compilers sharing a target directory race on one append-only
region and one index (TODO.md carries this, alongside the world leaf still invalidating on mtime rather than content).
— *Concurrent writers are handled as of §21; the mtime leaf is still open.*

## 21. Concurrent compilers: the cache is locked, the build is not (2026-08-06)

§20 left "concurrent writers are unhandled" as the last correctness item on the store, and TODO.md carries a field
report that matches it: a CLI compile reporting errors with positions and types from a *previous* version of an
edited file, cured by deleting the cache, suspected to be a resident LSP compiler sharing the workspace.

### The three races, and which one can produce a wrong build

Read off the layout (§17): the body region is append-only and addressed by byte offset, the index is a full rewrite
carrying the region's length in its header.

1. **Stale append base.** `writeFiles` builds its writer at `current.bodyLength` — the length read at *load* time. Two
   compilers loading at `N` and appending `a` and `b` bytes leave the second one's index claiming its objects start at
   `N` when they start at `N+a`. Every offset it wrote points into the other's bytes.
2. **Torn index.** The index is written with a truncating `newOutputStream`, so a loader can read it mid-rewrite —
   and a compiler killed during a save destroys the old index without finishing the new one.
3. **Lost entries.** The last index published wins wholesale; the loser's entries become unreachable bytes.

Race 3 is benign — a slower next build. Race 2 is fail-safe today: it throws, `load` catches, cold build. Race 1 is
**not** sound. It is *usually* caught by the header's `bodyLength == bodies.length` check, which is why this has
mostly shown up as unexplained cold builds — but two compilers appending the **same number of bytes with different
content**, which is exactly what two compilers reacting to the same edit do, passes that check and resolves offsets
into the wrong objects. A decode of valid-looking garbage is the reported symptom.

There is a fourth path with the same ending, and it explains the field report's "out-of-date compiler" trigger. When
a load is *rejected* (a different `CacheFingerprint.compiler`), the following save truncates the region — while the
other compiler, whose in-memory placement still describes the old region, appends to it at its own base. Neither the
file name nor the header distinguishes one incarnation of the region from another, so nothing detects it.

### The principle: lock the cache, never the build

Mill blocks a second build because it owns the *output*; the lock protects a build directory that genuinely must not
have two writers. `javac` blocks nothing because it has no shared persistent state at all. This compiler is the
second thing with a bit of the first: its only shared state is a **cache**, which is by definition discardable. So
the lock protects the cache, is never waited on for long, and every way of failing to get it degrades to *doing more
work*, never to blocking a build and never to trusting a wrong byte.

### What landed

**A single lock file per configuration, shared for load and exclusive for save** (`.eliot-lock-<config>`). Acquisition
is `tryLock` polled for at most a second — a save is 100–141 ms, so contention that outlasts that is a hung process,
not a busy one. Failing to acquire is not an error: a load answers `None` (cold build), a save does nothing (the next
process start is cold). An OS file lock is released when the process dies, so there are no stale locks to break.

**A region id in the header, and the save rebases onto the region's real end.** The header gains a random 64-bit
`regionId`, minted whenever the region is *replaced* and carried unchanged across every append. It is what a save
checks before extending: our placement's offsets mean something only in the incarnation they were read from, so a
save whose region id no longer matches the one on disk skips rather than corrupting. When the ids do match, the
writer is based at the region's **actual** length read under the lock, not the length loaded at startup — which is
what makes an interleaved append correct rather than merely detected. Format version 2 → 3.

**The index is published by atomic rename.** Written to `<index>.tmp`, then `ATOMIC_MOVE` over the real name, with the
objects appended *first*. A reader now sees one complete index — the old or the new — and a compiler killed mid-save
leaves the previous cache intact plus some unreferenced bytes, instead of leaving no cache at all.

**The length check relaxes from `==` to `<=`.** With appends ordered before publication, a region longer than its
index claims is the normal aftermath of an interrupted save, not evidence of a race — the prefix `[0, bodyLength)` is
immutable and is exactly what the index describes. The region id is what guards the case `==` was standing in for.

### What this does not fix

- **Two different compiler builds sharing a target directory** still take turns replacing the region, so both stay
  cold. It is now *correct* — the region id catches the replacement — but not fast. Putting the compiler fingerprint
  in the file name would fix it outright and was rejected: every compiler rebuild would then orphan a store pair with
  nothing to reclaim it, which is a worse trade in the repository this is developed in.
- **The world leaf still invalidates on mtime, not content** (TODO.md). Independent of this, and the other half of
  the field report — the fix here should not be read as closing that.

### What it buys the plan

Compaction (§13 step 5) is the one operation that *moves* offsets, which invalidates the in-memory placement of every
process that loaded beforehand. The region id is exactly the stamp that makes it detectable: compaction mints a new
one, and every other writer's next save sees the mismatch and stands down. It needed to exist before compaction could
be written, and now it does.

### Verified

- `__.test` green. Five new cases in `ContentAddressedCacheBackendTest`, one per property: the interleaved append,
  the replaced region, the over-long region, the forfeited save, and the unchanged no-growth invariant.
- **The three new cases were each checked against the defect they exist for** — reverting the rebase to the loaded
  length, dropping the region-id comparison, and restoring `==` in the length check each fail exactly one case and
  nothing else.
- **Byte-identity**: the `DischargeDemo` jar is identical cold and warm under format 3, and a warm build still
  appends **zero bytes** (1,192,558 both runs).
- **Under-invalidation**: an edit produces a warm jar byte-identical to a cold build of the same content, and
  reverting the edit restores the original jar exactly.
- **Cost**: warm-build load 111 ms, save 116 ms with `--statistics`, unchanged against §18's 107/100 ms. A `tryLock`
  and a header read do not show up.

One thing found while proving the tests, which is worth knowing before writing another: comparing two whole
`FactCacheData`s with `shouldBe` **hangs on failure** rather than failing. ScalaTest prettifies the difference, and
prettifying thousands of deeply nested facts outlasts any timeout — a broken store looked like a hung suite for 50
minutes. The new cases compare through a `Boolean` for that reason, losing no strength (every key, edge and value is
still compared) and gaining a failure that arrives in six seconds.

## 22. The world leaf stops trusting a recent mtime (2026-08-06)

§21 closed the concurrent-writer half of the field report and left the other half open: the world leaf compares source
files by mtime, and an mtime does not identify content. This is that half.

### The defect

`FileStat` is a world leaf — always regenerated, and its equality against the cached value is the *sole* oracle for
"did this source file change?". Its two failure directions are not symmetric:

- **mtime moved, content did not** — `FileContent` is re-read, compares equal, propagation stops there. One read, no
  harm.
- **content moved, mtime did not** — the leaf matches, so `FileContent` is served *from cache with the old content*,
  and the whole build proceeds on the previous version of the file. Silent, and the compiler reports errors against
  source it is no longer reading.

The second is what the field report described (errors carrying positions and types from a previous version of an
edited file) and what makes `FullIntegrationTest` — which rewrites one `Test.els` per test against a resident
session — intermittently compile the *previous* test's program.

### What was measured first, because two obvious fixes are worthless

| | measured on the development machine |
|---|---|
| Timestamp granularity | **4.00 ms** — the kernel tick, not the API |
| Two immediate writes, nanosecond precision | **identical** (`…737618666Z` both) |
| 8,818 writes over 300 ms | **99.1%** collided with the previous timestamp |
| Filesystem clock vs JVM clock | filesystem reads **4–5 ms behind** |
| 111 source files / 167 KB: `File.lastModified()` | 0.19–0.38 ms |
| the same via `readAttributes` (mtime + size) | 0.14–0.28 ms |
| the same, read + SHA-256 | **21.5 ms cold**, 1.0 ms once JIT-warm |

Two conclusions, both recorded so they are not re-proposed. **Nanosecond timestamps buy nothing**: the kernel stamps
at tick granularity and zero-fills the rest, so "use `readAttributes` for nanosecond precision" does not narrow the
window at all. And **hashing every source on every build is affordable but wrong**: ~21 ms on the single cold pass a
CLI build gets (~2.7% of a warm build), scaling with the source tree rather than with the change — a real price paid
on every build to cover a 4 ms window.

### The rule

An mtime identifies content only once it can no longer move without being seen. Stat at `T`, read mtime `M`; a later
modification at `W > T` gets `floor(W) >= floor(T)`, so it can only reproduce `M` if `M` falls in the same tick as
`T`. **If `M` lies a full tick before `T`, every later modification is visible** and the mtime is conclusive. Inside
that window it is not.

So the leaf answers with an identity whose precision is chosen by whether the cheap one is currently trustworthy.
`FileStat` gains `unsettled: Option[String]`: `None` means compare on the mtime exactly as before, and `Some(nonce)`
means this stat identifies nothing. An unsettled file never compares equal to itself, so it is re-read, and the
existing equality cutoff at `FileContent` — which holds the whole content — stops propagation whenever the content
did not actually change. The cost of the doubt is one file read and nothing downstream.

**The token is a nonce, deliberately not a timestamp.** The defect being guarded against is a clock too coarse to
tell two observations apart, and `IO.realTime` is millisecond-resolution; dating the observation would rebuild the
same hole one level down. A `Boolean` "is recent" flag is worse still — two builds inside the window would compare
equal and the defect would survive untouched. Nothing reads the token; only its inequality is load-bearing.

Two details are load-bearing and easy to lose:

- **The clock is read before the stat.** Soundness needs `M + margin <= T`; a clock read *after* the stat gives
  `now >= T`, which does not imply it. It reads like a harmless reordering.
- **The margin must dominate the tick plus the clock skew**, measured at 4.00 ms and 4–5 ms. Two seconds is three
  orders of magnitude above both, and costs nothing that matters: the only files inside it are ones just written.

Directories fall out for free. `FilesystemMount.walk` takes a `FileStat` on every directory it walks as a *listing*
dependency, so it had the same defect — two files created inside one tick left the second invisible. An unsettled
directory simply forces the re-walk, and `PoolModules` equality then stops propagation when the listing is unchanged.
No listing digest was needed.

### What it costs

| scenario | before | after |
|---|---|---|
| warm build, settled tree | 111 stats, 0 reads | **identical** |
| build after an edit | 1 read | 1 read |
| first build past the margin | 0 reads | 1 read, once, then 0 |
| forced mass-touch inside the window | (silently wrong) | one read per file — the correct price |

Measured: warm-build load 109–116 ms and save 94–104 ms across three runs, against §21's 111/116 ms. The change adds
one clock read and one comparison per leaf and replaces `File.lastModified()` with a `readAttributes` that measured
slightly *faster*; no file is read on a settled tree, so there is nothing to regress.

### An invariant that became conditional

`WarmBuildLeafOnlyTest` asserts that a warm build regenerates **only world leaves**, and it failed — correctly. It
wrote `Test.els` and compiled twice immediately, so the file was inside the window, its leaf differed by design, and
its `FileContent` was legitimately re-read. **"Untouched" is no longer sufficient; the tree must also have settled.**
The test now backdates its source before compiling, which is what any source tree more than a couple of seconds old
looks like, and the invariant holds exactly as before. The same caveat applies to §18's zero-append property: it is a
property of a *settled* warm build, and was re-verified as such (1,192,578 bytes across three consecutive builds).

### Verified

- `__.test` green (1,625).
- **Every new test was checked against the defect it exists for.** Neutering the rule (never unsettled) fails the
  file rewrite, the directory add, and two leaf cases; making the token a constant — the "boolean flag" mistake —
  fails the file rewrite, the directory add, and the two-observations case. Nothing else moves in either mutation.
- **End to end through the CLI**, which is the field report itself: a program rewritten with its mtime held fixed and
  recent builds and runs as the *new* program. Against the unfixed compiler the same sequence silently runs the old
  one.
- Byte-identity: the `DischargeDemo` jar is identical across cold and two warm builds, and the store appends zero
  bytes. The example sweep builds 39/39 (`PluginA/B/C` carry no `main`).

### What is still open

A modification that *sets the mtime backwards onto exactly the cached value* — `touch -d`, `cp -p`, `rsync --times`,
an archive extraction — is not covered, because it defeats the premise that a later write produces a later stamp. It
needs the restored mtime to equal the cached one over different content, which is contrived outside a test, and the
only complete answer is hashing every build at the price measured above. Recorded rather than implied: the rule
closes the window the filesystem's own coarseness opens, not every way an mtime can lie.

## 23. The next bottleneck is process startup, and `--statistics` cannot see it (2026-08-06)

§19 named `compiler engine, plugin setup and i/o` as the thing to measure next, and guessed at what is in it. This
section measures it. The guess was wrong in a way worth recording, because the shape of the error is structural: the
report accounts for **every millisecond of its own window** and says nothing about the window's placement.

### The window starts too late

`Compiler.runWithConfiguration` reads the clock *after* `allLayers()` and `parseCommandLine`, and the process has by
then been running for some time. So `--statistics` reports 849 ms against a process that takes ~1,400 ms. **The
missing ~570 ms is 41% of a warm build and appears in no row.** It is the same ~575 ms that `eliotc --help` takes,
so it is fixed cost, independent of the program being compiled and of the cache.

The full ledger for a warm `HelloWorld` build (jar classpath, settled tree, five-config store):

| segment | ms | where it shows up |
|---|---:|---|
| **JVM boot + library and class init before `runCompiler`** | **~570 (43%)** | **nowhere — before the clock** |
| ServiceLoader plugin discovery | 6 | `engine` |
| CLI parse | ~20 | `engine` |
| plugin `configure()` + `initialize()` (the processor graph) | ~150 | `engine` |
| cache fingerprint | 59 | own row |
| cache load | 113 | own row |
| the compile itself (`targetPlugin.run`) | ~250 | processors 83 + dispatch 93 + remainder |
| build cache graph | 20 | own row |
| persist | 110 | own row |

That is what corrects §19: of its three named causes for the `engine` row, plugin discovery is 6 ms, JVM start is not
in the row at all, and only the processor graph is really there.

### What the invisible 570 ms is made of

Measured as `System.nanoTime` deltas around each initialisation, on a cold JVM:

```
JVM boot to main                                       ~60 ms
log4j2 LoggerContext init                                ~265 ms   ← isolated; marginal cost is ~110 ms, see §24
cats-effect IORuntime.global (pool construction)         ~140 ms
classloading Compiler$ and its Scala transitive closure  ~165 ms
```

The parts overlap — whichever runs first absorbs the shared classloading, so they do not add cleanly — but the total
matches the measured prefix. **Read each line as an upper bound, not as a marginal cost.** §24 measures log4j's
marginal cost by removing it and finds ~110 ms, not 265: the isolated probe was the first thing in that JVM to touch
the classloader, so it paid for warm-up the compiler would have paid anyway. The same caveat applies to the other
three lines. **Almost all of it is classloading and one-time library initialisation, and none of it
is the compiler's own work.** No algorithmic change touches it, exactly as §19 found for the load's JIT component;
unlike that one, this is addressable.

### AppCDS: −32% of the whole warm build, byte-identical

A class-data-sharing archive is the entire fix, and it needs no compiler change:

```
avg 1,331 ms   min 1,284 ms   warm build, as shipped
avg   908 ms   min   838 ms   warm build + AppCDS
```

The jar is **byte-identical** across cold-without-archive, cold-with-archive and warm-with-archive.

It shrinks every row, not just the prefix — which is itself the confirmation that those rows were classloading rather
than work:

| | as shipped | + AppCDS |
|---|---:|---:|
| prefix before `runCompiler` | ~570 | ~340 |
| plugin `configure()` + `initialize()` | ~150 | ~35 |
| cache fingerprint | 59 | 10 |
| cache load | 113 | 81 |
| persist | 110 | 85 |
| the compile itself | ~250 | ~250 |

Three practical constraints:

- **The classpath must be jars.** JDK 21 refuses to dump an archive with a directory on the classpath ("Cannot have
  non-empty directory in paths"). `ide/lsp/package.sh` already ships per-module jars and so qualifies as-is; the mill
  dev path (`examples.run`, class directories) does not without jarring first.
- **Generate at package time**, with one representative `-XX:ArchiveClassesAtExit` run, ship the `.jsa`, and add
  `-XX:SharedArchiveFile` to the launcher.
- **A stale archive is fail-safe.** `-Xshare:auto` is the default, so a classpath that no longer matches degrades
  silently to ordinary classloading. It cannot produce a wrong build and cannot fail a build.

### Measured and rejected, so they are not re-proposed

- **`-XX:TieredStopAtLevel=1`** — a small *regression* (1,375 vs 1,397 ms). The intuition that a short-lived CLI
  should skip C2 is wrong here: the compile itself is hot enough to want it.
- **log4j's `SimpleLoggerContextFactory`** — worth a further ~6% (816 vs 874 ms with AppCDS), but it **silences the
  user-facing INFO output**, including "Generated executable jar". Not a drop-in, and not worth that trade.
- **log4j property tuning** (`disable.jmx`, `shutdownHookEnabled=false`, `is.webapp=false`) — 869 vs 882 ms, inside
  noise. Confirmed independently in §24, which also rules out JMX and a version upgrade.
- **`-Xshare:off`** — confirms the default JDK archive is already earning ~110 ms (1,510 vs 1,397 ms). AppCDS extends
  that mechanism to the application's own classes rather than introducing a new one.

### What the profile looks like after AppCDS

~340 ms prefix, ~250 ms compile, ~165 ms cache load + save, ~35 ms plugin setup. Two observations for whoever picks
this up next:

- **log4j2 is the largest single startup item that is not the JVM itself.** §24 measures what it is really worth
  (~110 ms, ~8%) and establishes that there is no switch: reaching it means not loading `log4j-core` at all.
- **Dispatch (105 ms) exceeds all processors combined (98 ms)** on a warm build: 304 generations, each offered to
  every one of ~45 processors, error-isolated and type-tested. Read it as the upper bound it is (the measurement
  lands in that same figure, §"two compiler diagnostics" in CLAUDE.md), but it is the only remaining row inside the
  compile window with obvious structure to attack.

### The lesson for the report itself

A subtraction remainder is only as honest as its window. The `engine` row was arithmetically correct the whole time
and still pointed three sections of work at the wrong place, because nothing in the report could say "and 41% of the
run happened before I started counting". If `--statistics` is ever extended, the first thing to add is not a finer
breakdown of `engine` — it is the prefix, taken from `ProcessHandle.current().info().startInstant()` or an explicit
JVM-start stamp, so the total the report accounts against is the total the user waits for.

### Not applicable to the LSP

This is CLI latency. A resident `CompilationSession` pays the prefix once at server start, so hover and diagnostics
never see it. It is what `./mill examples.run`, the example sweep, and the IntelliJ before-run build task pay on
every single invocation.

## 24. What log4j actually costs, and why there is no switch to flip (2026-08-06)

§23 named log4j as the largest non-JVM item in the startup prefix and put it at ~265 ms. This section takes it
apart. Two results: **the 265 ms was an isolated-probe number and the marginal cost is ~110 ms**, and **there is
nothing to turn off** — no lookup, no scan, no flag. The cost is that 500-odd classes have to be read and defined.

### It is not a network lookup, and not a plugin scan

The obvious suspects were checked first, because log4j has a documented history of exactly this
([LOG4J2-2717](https://issues.apache.org/jira/browse/LOG4J2-2717) is a multi-*second* startup caused by network
interface enumeration in `UuidUtil`, and [LOG4J2-2795](https://issues.apache.org/jira/browse/LOG4J2-2795) tracks
`LogManager.getLogger` at ~600 ms cold):

| suspect | measured here |
|---|---|
| DNS — `InetAddress.getLocalHost().getCanonicalHostName()` | **9 ms** |
| NIC enumeration — `NetworkInterface.getNetworkInterfaces()`, 10 interfaces | **4 ms** |
| plugin registry — `PluginCache.loadCacheFiles` | **~4 ms** (the `Log4j2Plugins.dat` cache is present, so there is no classpath scan) |
| XML config — `XmlConfiguration.<init>` + `setFeature` | **~10 ms** |
| JMX — `Server.reregisterMBeansAfterReconfigure` | **~6 ms** |

LOG4J2-2717 is a Windows pathology; on this Linux box the NIC path is 4 ms. **None of the named causes is the cost.**

Sampling the main thread every 2 ms through `LogManager.getLogger`, ~72 of 78 samples land in
`jdk.internal.loader.Resource.getBytes`, `BuiltinClassLoader.defineClass`, `AbstractClassLoaderValue$Memoizer.get`
and `BootClassLoader.loadClassOrNull`. It is **raw classloading**, and the class count says the same thing:

```
classes loaded by one warm build   9,865
  log4j-core                         502
  log4j-api                          101   ← 603 for logging
  scala-library + scala3-library      681
  cats-effect                         292
```

**Logging loads twice as many classes as cats-effect.** That is the whole story.

### Every switch was measured, and none of them works

Interleaved A/B, eight rounds, **each variant given its own `-o` output directory** (see the trap below), min and
average in ms:

| variant | min | avg |
|---|---:|---:|
| A — log4j-core 2.22.1, as shipped | 1,282 | 1,313 |
| B — A + `-Dlog4j2.disableJmx=true -Dlog4j2.disable.jmx=true` | 1,250 | 1,307 |
| C — log4j-core 2.25.4 | 1,255 | 1,319 |
| D — C + `-Dlog4j2.disableJmx=true` | 1,276 | 1,321 |
| **E — log4j-api only, `log4j-core` off the classpath** | **1,139** | **1,205** |

- **Disabling JMX buys nothing measurable**, in either spelling, on either version, despite the manual saying it
  "increases the time for a cold app start". The MBean registration is ~6 ms; the classes behind it get loaded
  anyway.
- **Upgrading to 2.25.4 buys nothing measurable** either, though it is a free correctness/currency win if wanted:
  output is byte-for-byte the same, JMX is off by default from 2.24.0, and it loads 23 fewer classes (620 vs 643).
- **Removing `log4j-core` is worth ~110–140 ms, ~8–9%** — and it is the *ceiling*, not a starting point. There is no
  configuration that gets part of it: the jar is either loaded or it is not.

### What removing it would actually mean

The compiler barely logs. Across every module there are **35 call sites** — 22 `debug`, 7 `error`, 4 `warn`,
2 `info` — against **32 types** mixing in `Logging`. The configuration that matters is `log4j2.xml`: root at `info`,
`FactGenerator` pinned to `error`, and eight commented-out per-package `debug` loggers that a developer uncomments
when chasing a phase. Replacing that is a small internal logger, not a project.

One inconsistency to fix at the same time, and the reason the §23 `SimpleLoggerContextFactory` experiment silenced
the build: **user-facing output is split across two mechanisms.** Errors go through `User`, which prints via
`cats.effect.std.Console`. But the success line — `JvmProgramGenerator`'s "Generated executable jar: …" — is a
log4j `info`, so it is *logging configuration*, not program output, and any change to the logging backend can
delete it. Product output belongs in `User` regardless of what happens to log4j.

### The benchmark trap that nearly produced a fake result

The first run of the table above showed A, C and E at ~3,400 ms and B and D at ~1,280 ms — an apparent **2.1-second
JMX effect**. It was an artefact, and the giveaway is that E has no JMX code on the classpath at all.

Each variant has a different classpath, so each has a different **compiler fingerprint** and therefore a different
cache configuration — but they all shared one `target/` directory. A build whose config does not match the region
replaces it, so in an A-B-C-D-E round every variant preceded by a *different* config started cold and every variant
preceded by *itself* ran warm. The alternation printed as a clean, reproducible, entirely fictitious flag effect.

This is the limitation TODO already records from §21 ("two different compiler builds sharing a target directory take
turns replacing the region and both stay cold") — worth restating here because it does not present as a cache
problem. It presents as whatever variable the benchmark happened to be alternating on. **Any A/B over compiler
variants must give each variant its own `-o` directory**, and a result where the ordering explains the numbers
better than the variable does should be assumed to be this until proven otherwise.

## 25. A validation question is not a diagnostic (2026-08-18)

A warm run reported errors about names the program being compiled does not contain. The symptom that surfaced it
was the shared-session integration suites (`FullIntegrationTest`) going flaky whenever a test was added or moved —
which is exactly what changes *which program was compiled before* a given test, and in which worker.

### What was actually happening

Backward validation walks the **previous** run's dependency graph: that is how it learns what moved. When the drill
reaches a dependency whose own dependencies moved, it recomputes it and compares (`recomputeAndCompare`), and it
does so through the ordinary generation path — the same `regenerate` a real demand uses, whose errors go straight
into the run's diagnostics.

So a program that drops a name pays for it twice. Compile `def numbers … def total …`, then compile something else:
this run validates last run's `UsedNames` edges, reaches `UnifiedModuleValue(Test::numbers)`, regenerates it, and
the module no longer has that name — `Could not find 'numbers'.` The build is otherwise correct; the jar it writes
is byte-identical to a cold compiler's.

Two properties made it read as flakiness rather than as a bug:

- **It is order-dependent by construction.** `resolve` drills with `forallM`, which short-circuits on the first
  changed dependency, over a `Set` whose iteration order is not the demand order. Whether a ghost fact is reached
  before the drill finds a changed sibling is incidental.
- **It only affects diagnostics.** Every jar in the reproduction was byte-identical to the fresh-session one, so
  nothing failed where a miscompile would have been noticed.

The suites had already been shaped around it: `FullIntegrationTest.compiled` gated on `targetProduced` alone, with
a comment recording that gating on the error list "would fail every suite".

### Why it is not only a test problem

`CompilationResult.succeeded` is `errors.isEmpty && targetProduced`, and the CLI exits on it. A user renaming a
value and rebuilding against a warm on-disk cache therefore gets a failed build, reported against a name their
source no longer mentions — and an LSP session publishes the same as workspace diagnostics. The resident session in
the tests is the same seam; it just reproduces it in seconds.

### The fix: report the graph the run actually built

Each diagnostic is now recorded with the generation that produced it (`AttributedError`), and `currentErrors`
reports a diagnostic only if that generation is reachable from the run's **roots** through the edges **this run**
recorded. A root is a demand from outside any generation — the target plugin asking for its artefact, a host
querying a fact — recognised by its empty ancestor chain; the validation path bypasses the public entry point
(`request`, not `getFact`), because asking whether a fact changed is not building it.

This hides nothing a compilation reached. A fact the program genuinely needs is read by its dependent, and
`DependencyTrackingProcess` records that edge *before* the read — including a read that comes back empty because the
generation failed, which is the whole failure-reporting path.

The engine's own invariants split on whether they are a statement about a *demand*. "A key was produced twice with
different values" is two paths disagreeing about one fact, worth knowing wherever it arises, so it stays unattributed
and is always reported. **The strict-accounting check is attributed**, because its premise is that something asked for
the key — and validation asks for keys the previous run's graph had. A producer that only ever *pushes* a key (one file
parse registering every name it declares) answers a direct demand for a name that file no longer declares with nothing
at all: the right answer, and — before this — an "Internal error: no processor produced a fact, an error, or a decline"
on every warm build that dropped a name. It only ever showed up once the suites started gating on diagnostics, which is
the argument for gating on them.

### Verification

- `jvm/test/…/SharedSessionIsolationTest` — a corpus compiled in one resident session, forwards and backwards and
  twice over, against a per-program *fresh* session as the oracle, comparing all three observables (target
  produced, diagnostics, jar digest). It fails on the pre-fix compiler with exactly the leaked errors above.
- `IncrementalFactGeneratorTest` — a fact that only the previous run's graph reaches errors when regenerated for
  validation and is not reported; the same fact *is* reported when this run's graph still reaches it.
- `FullIntegrationTest.compiled` now gates on `succeeded` (diagnostics included), so the workaround the leak forced
  is gone and any future leak fails the suites instead of being absorbed by them.

### What this does not explain

The reachability filter is about *diagnostics*. It does not touch the second defect the same investigation turned
up — a warm session occasionally disagreeing with a cold one about the program itself — which §26 takes apart.

## 26. A retained fact must not be validated against an input that moved without it (2026-08-18)

§25 fixed the diagnostics half of the shared-session flakiness. The other half was a wrong *build*: roughly one full
`__.test` run in four, a worker running `ExamplesIntegrationTest2` after other suites failed seven consecutive tests
with `Too many type arguments.` and no jar — a compile a cold compiler performs without complaint.

### The mechanism, in twelve lines and no Eliot

Validation asks, per dependency, "is this the same as the cache's entry for it?". What a dependent actually needs to
know is "is it the same as what *I* consumed". Those two coincide only while the cache is **one consistent
generation** — and §10's accumulation policy (retain every prior entry the run did not touch) deliberately makes it
not one:

```
run 1: demand w        leaf=10 → m=10 → w=100        cache: leaf, m, w
run 2: demand m only   leaf=20 → m=20                cache: m advances, w retained at 100
run 3: demand w        w's dep m validates "unchanged" (it *is* the entry) → w served as 100
```

`w` is a function of `m`, `m` is now 20, and the answer 200 never appears. The middle run is the whole trap: it
updated a fact and never reached its dependents. In the compiler that middle run is a **failing compile** — the
overlapping-instances case in `ExamplesIntegrationTest2`, which advances the synthetic entry's `MonomorphicValue`
(the two programs' `main` signatures happen to agree, so it recomputes equal and validates "unchanged") but dies
before codegen, leaving `WovenValue`/`UncurriedMonomorphicValue` retained from *two* programs back — a `main`
carrying a type argument, from the effect corpus. The next compile accepts them, demands
`MonomorphicValue(Test::main, [<arg>])` against a `main` that now takes none, and reports exactly that. (The
argument was the carrier `IO` when this was measured; effects v6 replaced it with an implementation binding,
which changes nothing about the failure shape.)

That the trigger is a failing compile is why it looked like flaky test-ordering: whether a suite meets it depends on
which suite ran before it in the worker, and the middle run has to fail.

### The fix

`buildCacheData` now retains a prior entry only if none of its recorded dependencies **moved** this run — where moved
means the run resolved the key and got something other than what the cache held (a different value, or nothing at all
because it failed). A dependent recorded against a moved input is dropped and regenerates on its next demand.

Only *direct* dependents need dropping: a retained entry pointing at one of those now finds no prior entry, which
validation already reads as changed. And the drop is **fail-safe by construction** — its only effect is that a fact is
recomputed rather than served, so a mistake here costs time, never correctness. A run that moved nothing (the warm
no-change build, which is the case the metrics are about) does no work here at all: `WarmBuildLeafOnlyTest`'s
leaf-only invariant is unchanged.

### Verification

- `IncrementalFactGeneratorTest` — the three-run scenario above, asserting run 3 answers 200. It fails on the
  pre-fix engine with 100, which is the whole bug in one assertion.
- The original failure, run directly as its worker ordering (`EffectCorpusIntegrationTest` then
  `ExamplesIntegrationTest2` in one JVM, via `org.scalatest.tools.Runner`), reproduced 100% before and passes after,
  as does the full twelve-suite ordering the failing worker had.
- `./mill __.test` green across repeated runs.

### What this says about the design

The accumulation policy and backward validation are each sound on their own; together they need one extra invariant,
which is now stated where it is enforced: **a cache may mix generations only for facts whose inputs did not move.**
The alternative — recording, on each edge, the identity of the value consumed, and validating against *that* — is
strictly more precise (it would keep the dependents whose input moved and moved back), and is the direction to take
if retention loss ever shows up in the warm-build numbers. It costs a per-edge identity, which the value-less
(`SemValue`-bearing) entries do not have today.

## 27. A value-less fact the drill proved unchanged has not moved (2026-09-16)

§26's drop cost more than the time it promised to. A consumer building its own suite over an unchanged tree
alternated between two warm builds forever — 7 s, 22 s, 7 s, 22 s — once any edit, even an edit and its revert, had
happened in its target directory. A fresh cache never gets there, which is why nothing here measured it.

### The mechanism

§26 counts a key as moved when the run resolved it and the result does not match the cache's entry. A value-less
(`SemValue`-bearing) entry — `NativeBinding`, `ContributedBinding` — matches **nothing**: `CachedValue.Absent` has
nothing to compare against. And such a fact is regenerated whenever anything needs its value (`resolve` accepts only
entries that have one), which is routinely *in the same run* whose drill has just proved it unchanged. So every
value-less fact a run needed counted as moved, and every untouched entry recorded against one was dropped — here
~3100 `CompilerMonomorphicValue`/`MonomorphicValue`/`RefinementTable` entries, validated by that very drill and
therefore not in `touched`.

The next run found them missing, regenerated them and their dependents, which demanded *other* value-less facts,
which dropped *other* untouched entries. Two sets of entries took turns being absent, and the run that rebuilt one
dropped the other: 5,487 regenerations then 13,736, indefinitely, where a clean warm build does 1,232.

### The fix

A value-less entry moved unless this run's validation proved it unchanged (`unchangedChecks` completed `true`). That
is sound for the reason the drill is: a fact is a pure function of its recorded inputs, and those held. It is also
what §26 needs, by induction — a saved cache holds no dependent recorded against a value-less entry that moved,
because the run that moved it was, by this rule, unable to prove it unchanged and dropped the dependent then.

### Verification

- `IncrementalFactGeneratorTest` — a drilled-then-regenerated value-less fact keeps its untouched dependent (fails
  before the fix), and one whose leaf moved still drops it.
- The consumer's oscillating cache, rebuilt with the fixed compiler: one run at the old fast count, then 1,232
  regenerations every run. From an empty cache, edit → revert → broken edit → fix returns to 1,232 on the run after
  each step; before the fix every one of them started the alternation.
- `./mill __.test` green.

## 28. A key the run created, or pushed, can move too (2026-10-08)

The `v0.8` release workflow failed its suite on three `StoredComputationIntegrationTest` cases, each a correct
program reported as `Test.els:1: Could not find 'url'.` with no jar — a name the program does not contain. It was
§26's failure shape again, once in roughly three full `jvm.test` runs, and only when the suites in one worker happened
to run in a particular order.

### The mechanism

Recording every program one worker's shared session compiled (212 of them) and replaying them into one fresh session
reproduced it at program 139 about half the time. Three runs carry it, far apart:

- **Program 69** defines `def run … = … dependency.url …`. Its `SaturatedValue(Test::run)` and
  `BodyValueReferences(Test::run)` — the references its body names, `Test::url` among them — enter the cache.
- **Program 70** moves `OperatorResolvedValue(Test::run)`, so §26 drops its direct dependent
  `RowElaboratedValue(Test::run)`. `SaturatedValue(Test::run)`, recorded against that one, is retained: an input with
  no entry reads as changed, so §26 rightly needs only direct dependents.
- **Program 137** declares `data Job(run: …)`, and a failing compile creates `RowElaboratedValue(Test::run)` afresh
  for the accessor without ever reaching `SaturatedValue`. A key with no prior entry was never counted as moved, so the
  old `SaturatedValue` stayed — now pointing at an input that has an entry again, one generation newer than what it
  consumed.

Program 139 validates `BodyValueReferences(Test::run)` through that intact-looking chain, accepts program 69's
references, and monomorphizing `main` demands `Test::url`. Whether it gets there depends on which of the drill's
paths is taken first (the `forallM` over a `Set` of §25), which is why the replay was itself only half reproducible.

The same review found a sibling hole: `movedKeys` only looked at keys this run *resolved*. A key a generation
**pushed** (one file parse registering a `ModuleValue` for every name it declares) advances its entry without ever
being resolved, so a failing compile that re-pushed a name with a new definition left that name's dependents retained
against an entry they never consumed.

### The fix

`movedKeys` considers every key the run materialised, pushed ones included, and counts a key with **no prior entry**
as moved: whatever is still recorded against it was recorded against an older generation. Both are §26's rule
applied where it had not reached, and both are fail-safe in the same way — the only effect is a recomputation.

### Verification

- `IncrementalFactGeneratorTest` — a dependency dropped and then recreated without its dependent (four runs), and a
  pushed dependency that moved without its dependent (three runs). Both served the stale value before the fix.
- The recorded 142-program replay: about one run in two failed before, six of six passed after.
- `./mill __.test` green across repeated runs, `WarmBuildLeafOnlyTest` included.
