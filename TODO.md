# TODO

Remaining work and open ideas. Completed or superseded items have been removed — for what is
already built, see the git history, the cornerstones in `.claude/CLAUDE.md`, and the memory
notes.

## Type system & language

- Add generics to function literals.
- Introduce arrays (records / multi-field `data` are already done).
- **Unify `Int` literal handling across the two tracks.** Managing the `BigInteger`/`Int` split
  between the compiler track and the runtime track is a constant source of friction. It cannot be
  solved by simply aliasing `type Int = BigInteger` on the compiler track, because `Int` carries
  meta-information that is itself expressed in terms of `BigInteger` (`type Int {range:
  Interval[BigInteger]}`). One unified handling of numeric literals for both tracks is wanted. The sharpest
  instance — a **transfer brace could not spell a number at all** — is fixed: a brace (and a `where`
  predicate) is compiler-track code, so its integer literals are bare `BigInteger`s, and an endpoint is
  written `Bounded(0)` / `Bounded(0 - 1)`. What remains is the general case: an *ordinary def body* is
  runtime-track, so a helper called from a brace still cannot name a `BigInteger` constant in value position —
  which is why `rangeWithin[Lo, Hi]` takes its bounds as type parameters.
- **A `where` demands every argument's meta, even one its predicate never reads.** A code argument reaches the channel
  as a thunk, whose meta is never known, so a `where` on a def with a code parameter yielding a tracked type
  (`body uses *: Int`) can never be proven (§8.8). Demanding only what the predicate reads would fix it, but narrows the
  decided "⊤ cannot discharge a demand" fail-safe — needs a decision.
- **The rest of meta braces on occurrences.** Parameter braces (`def writeByte(b: Int {byte}): Unit`) are built
  (`docs/total-meta-transfers.md` §8.9): every full call proves its argument fits, and inside the body the parameter
  carries the stated meta. One occurrence of the same decision (2026-09-15: a brace states the meta of the value at
  that position, and whoever supplies the value proves it) remains: a **`data` field** `r: Int {byte}` would be
  demanded at construction through the ordinary constructor call, and its generated accessor may state it as a leaf
  would (construction is the only supplier). A brace on a **bodied return** is declined (2026-10-20, §8.9): a body is a
  supplier the compiler sees, so its meta is derived, never stated, and R3 stands.
  A reusable meta value (`def byte: Interval[BigInteger] = …`) still has to write its literals in type position
  (`between[0, 255]`) until the `Int` literal item above is solved.
- **Flow grades: quantitative computation tracking (cycles/WCET, stack, peak memory) on the
  effect row.** Design sketched 2026-07-10 in the bounds-as-refinements discussion. The dependency
  ladder is ranges → sizes → grades (fold cost needs sizes, frame sizes need ranges): the
  refinement channel's **ranges** domain has shipped, and so has a `String` `size` domain
  (`docs/string-length-meta.md`) — but a fold's cost is a function of a *collection's* size, so what
  this still waits on is **`List`/`Array` `size`**, the one that needs structural meta
  (`docs/total-meta-transfers.md` §2.3). Core idea: the effect row generalizes from "set of abilities" to
  "abilities + **named grades**" —
  `def onTick(s: State): {Timer, cycles: ≤800} Unit` — where a grade is *not* an ability
  (nothing is performed, nothing resolved or discharged) but a quantity with an algebra,
  registered per platform (`flow cycles {Interval[BigInteger]}` +
  `implement Grade[D] { seq (+), branch (interval hull → BCET..WCET), zero, within (≤) }`);
  the existing effect row is the powerset special case (seq = branch = union, within = ⊆).
  The §4.2 projection discipline transfers wholesale: a parameter's grade is referenced by slot
  projection (`step.cycles`), never bound; return-position entries are expressions (axiomatic on
  natives, checked contracts on bodied defs) — showcase, with cross-channel composition:
  `def fold[T, A](ls: List[T], init: A, step: F[A]): {cycles: ls.size.end * step.cycles + 7} F[A]`.
  Grade-only rows on pure functions perform nothing and bind nothing (they name no ability, so the
  write has no binder to fill). Leaf grades are stated on native signatures by the platform
  layer (AVR datasheet cycle counts); width-dependent leaf costs are either expressions over the
  operands' value-metas or accounted post-lowering where `Represent`'s layout is known (same as
  stack frames). Accounting = generalize the effect walk (`EffectUsageCollector` + discharge-
  summary DAG) from the union lattice to arbitrary `Grade`s, run over the *residual* program
  (CTFE'd-away code costs zero). Covers: cycles (exact on cache-less simple cores — totality +
  monomorphization deleted the hard WCET subproblems: loop bounds come from sizes, no recursion,
  no indirect calls), max stack depth (sum frames / branch max over the whole-program DAG,
  post-lowering), and peak memory (composes as a monoid on `(net, peak)` pairs); an `Inf`
  super-loop's *step* carries the deadline budget — the real-time contract. Out of scope for the
  mechanism: in-place reuse (linearity is a type discipline, not meta-information) and hardware
  fidelity beyond simple cores. This supersedes the older idea of "WCET as type parameters /
  effects with type parameters" — quantities stay out of the type channel per the
  differentiation rule (doc §3).
- **Important:** after compilation the program should be guaranteed to fit all resources —
  memory, stack, etc. (Mechanism: the flow-grade system above, plus `Represent`-driven layout
  from the refinement channel.)

## Effects & I/O

The effect system has **one** document, `docs/effects.md`: effects v6 in D20/D21's `uses` surface, with
D20c option 5's environments threaded after monomorphization. Part I is the design as built; Part II is what is left
(§8 the tree's divergences, §9 the binder mark's record, §10 the gate, §11 the open and decided-but-unbuilt
decisions); Part III is §12's list of things closed by measurement or decision, which must not be re-proposed, and
§13's map from every citation of a condensed or retired record to where its content lives.
Nothing effect-related is tracked here; add it there.

## Syntax sugar & ergonomics

- String formatting as plain syntactic sugar for type-parameters:
  `s"Something $variable"` → `s["Something $variable"]`, where `s[STR: String]: String`.
  Because `STR` is a type-parameter, `s` is optimized away in favour of its result, so the
  parsing can be arbitrarily complex.
- Parse strings and numbers into custom types (string interpolation, regexp parsing, …);
  numeric literals are just a special case of this.
- Introduce a `UserShow[T]` — like `Show[T]`, but intended for end users.

## Optimization

- Lists consisting only of constants should not take runtime memory.
- **Sharpen the codegen phantom classification.** The `used` driver dedups its `MonomorphicValue`
  demand on a codegen-relevant projection of the type arguments (`saturate/fact/BinderRoles.scala`
  + `used/CodegenProjection.scala`), but a *true* phantom — a size index that never reaches
  representation — still classifies conservatively as representation, so identical code is not
  collapse-erased. Refining it is a code-size win, never correctness: the projection only ever
  folds code that is already identical.
- Can compile-time bounds be used to optimize the `Seq` implementation? (e.g. only `head` →
  linked list, only iteration → array.)
- Benchmark goal: complicated functions that generate an LED-light pattern should compile to a
  handful of instructions (uniting switching multiple LEDs into a single instruction), where a
  C compiler would compile everything.

## Compiler architecture & tooling

- **The warm build's dominant cost is JVM/library startup, and it is a packaging fix, not a
  compiler one.** Replacing Java serialization with explicit codecs over a content-addressed
  object store cut a warm build in half (load −79%, save −77%) and the cache is down to ~36% of
  it. §23 then measured what is left: **~41% of a warm build happens before `--statistics` starts
  counting** — JVM boot, log4j, cats-effect's `IORuntime.global` and Scala classloading. AppCDS
  would take a warm build from 1,331 ms to 908 ms (−32%, byte-identical jar, no compiler change)
  but is **declined**: it constrains ordinary JVM operation more than the time is worth. §24 then
  prices log4j — the biggest named piece — honestly: **~110 ms, ~8%, and there is no switch.** Not
  DNS (9 ms), not NIC enumeration (4 ms — LOG4J2-2717 is a Windows pathology), not the plugin scan
  (the `Log4j2Plugins.dat` cache works), not JMX (no measurable win in either spelling), not the
  version (2.25.4 is identical, if wanted as a currency upgrade). It is **603 classes** —
  log4j-core 502 + api 101, twice cats-effect's 292 — and the only way to get the 110 ms is to not
  load `log4j-core`. That is affordable: the whole compiler has **35 log call sites** (22 debug,
  7 error, 4 warn, 2 info) across 32 `Logging` types, so an internal logger replaces it. Fix the
  split output at the same time: errors go through `User`/`Console`, but the "Generated executable
  jar" success line is a log4j `info` — product output that any logging change can delete. After
  that, dispatch (105 ms, > all processors combined) is the only structured cost left inside the
  compile window. See `docs/incremental-compilation.md` §23–§24; §19 is the earlier profile.
  Left on the cache itself: caching *declines* (§6 step 3) and compaction (§13 step 5, no longer
  urgent since a warm build appends nothing).
- **The warm cache's two soundness holes are closed; one refinement is left.** A run now reports only
  the diagnostics of the graph it actually built (`docs/incremental-compilation.md` §25 — validating the
  previous run's graph used to spill "Could not find 'X'." into a rename's rebuild, and fail it, since
  `succeeded` reads the error list), and a retained fact is no longer validated against an input that
  moved without it (§26 — the mixed-generation hole that served a two-programs-old body and was the
  shared-session test flakiness). The refinement §26 names: validate an edge against **the identity of
  the value the dependent consumed** rather than against the cache's current entry, which would keep the
  dependents whose input moved and moved back instead of dropping them. It needs a per-edge identity,
  which the value-less (`SemValue`-bearing) entries do not have.
- **Cache sharing across target directories.** One cache now accumulates across mains for the same
  roots and backend (§10), so the stdlib subgraph is built once per configuration rather than once
  per example. Two *different* compiler builds sharing a target directory still take turns
  replacing the region and both stay cold (§21) — correct, but never warm.
- Rename processors to generators?

## Microcontroller target

- You cannot set a pin `high` without first configuring it as an output — the type system
  should enforce this.

## IDE

- Should we build with the compiler in the dependencies instead of a bundled one in LSP?
