# TODO

Remaining work and open ideas. Completed or superseded items have been removed — for what is
already built, see the git history, the cornerstones in `.claude/CLAUDE.md`, and the memory
notes.

## Type system & language

- **A `match` over a `data` whose nullary constructor is not first dispatches to the wrong arm.** Found
  2026-10-10. With `data Shape = Circle(radius: String) | Dot`, `describe(Dot)` (a `match` with `case Circle(r) -> r`
  and `case Dot -> "dot"`) returns `null`, and `describe(Circle("c"))` throws `ClassCastException: String cannot be
  cast to Void` in the arm's lambda; with an `Int` field, `Dot` reaches `Circle`'s arm and dereferences a null field.
  Declaring `Dot` first works. The arms are paired with the wrong constructors somewhere between `matchdesugar`'s
  `handleCases` and the synthesized `PatternMatch` implementation; not yet traced, and no test covers the order.
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
- **The meta companions read a type's *head name*; they must evaluate the type instead.** The `^Meta`
  transfer companion and the `$Where` precondition companion type each mentioned parameter by the pure name
  transform `Int` ⤳ `Int$Meta` (`MetaConstructorDesugarer.metaTypeSuffix`, applied by `MetaTransferDesugarer` /
  `MetaWhereDesugarer`). That reads the *syntax* of a type expression for a bare application head, and a type is
  an expression in general: a plain alias `type Byte = Int` already breaks it — `def asByte(x: Byte): Byte where
  withinByte(range(x))` fails with "Name not defined." at the parameter, because `Byte$Meta` exists for nothing
  (probed 2026-09-15). An alias of an alias, a type computed by a type-level function or a generic instantiated
  at such a type fail the same way. The channel's one legitimate read of a type is the one `MetaInterpreter.metaTypeOf`
  makes: the *evaluated* ground value's constructor, a value the evaluator produced. The companions should get
  their parameter meta types the same way — by evaluating the parameter's type and taking its meta type, which the
  channel already does for a *generic* companion (reduced at the meta type arguments) — instead of renaming at the
  core boundary, where no lookup is possible. Minting a `T$Meta` alias per alias is *not* the fix: it is the same
  structural read one level up and still misses every non-name type.
- **Meta braces in parameter position: `def writeByte(b: Int {byte}): Unit`.** Decided in principle
  2026-09-15, not built. The problem it answers is a reusable "Byte": today a bound can only be *demanded* by a
  `where` (`where within(byte, range(b))`, with `def byte: Interval[BigInteger] = closed(0, 255)` as the
  reusable meta *value*), which puts the restriction away from the `Int` it restricts, and a `where` leaves the
  parameter ⊤ inside the body because a predicate does not turn back into an interval. A **type alias cannot
  carry it**, by construction: a transparent alias is erased by normalization (`Byte` and `Int` are
  definitionally equal, per the cornerstone), so after evaluation there is nothing to read and before it there is
  only syntax; attaching a statement to the type value while `unify` ignores it leaks through metavariables into
  unrelated positions (the aliasing bug class the retired bounds-as-refinements design recorded, and why
  refinements stay out of types). So "Byte" is a meta *value*, never a type, and the bound lives on the
  **occurrence** it bounds. **One meaning**: a brace on a type occurrence states the meta of the value at that
  position, and *whoever supplies that value proves it* — a leaf return has no supplier the language can see, so
  it is axiomatic (today's return brace, R2 unchanged); a parameter is supplied by the caller, so at every full
  call the argument's meta must fit (`join(argument, stated) ≡ stated`, structurally, the comparison the reconcile
  pass already makes) and inside the body the parameter carries the stated meta instead of ⊤ (sound: every
  manifest call proved it); a bodied return is supplied by the body, so the derived meta must fit — this last is
  the one decided rule it touches (R3 makes a brace on a bodied def an error), a reversal to sign off on
  separately, or ship parameter braces first and leave R3. Everything stays **callee-keyed** and desugared from
  the def's own text: each braced parameter yields a nullary statement companion (named by def and parameter, in
  the meta namespace) that the channel reads exactly as it reads `$Where` (companion membership test at the call,
  reduction through the same executor, then the structural fit instead of a `Bool`); the walk's parameter-reference
  arm consults the companions of the value being walked; signature equality keeps ignoring braces, so the layer
  rule for return braces applies unchanged; the bare-reference / partial-application rejection extends to any def
  with a braced parameter. A `data` field `r: Int {byte}` is demanded at construction through the ordinary
  constructor call and its generated accessor may state it as a leaf would (construction is the only supplier).
  Grammar is the adjacency rule already in use: a brace *before* a type is an effect row, a brace *adjacent after*
  a type atom is its meta. This is Option B's deferred "contract annotation in value position" minus the splice it
  feared; the *refined alias* half of that deferral is closed for the reason above. Depends on the companion fix
  above for any parameter not typed by a bare slotted name.
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

The effect system has **one** document, `docs/effects.md`. **Effects v6 shipped 2026-09-09**: there is no
carrier — an effect is an ability declared with the `effect` keyword, an implementation is a **name** bound by
`with` and forwarded lexically from `main` inward, and a row entry is a phantom compile-time binder written at
every reference. Part I is that design; Part II is what is left (§8 the tree's divergences from Part I, §9 the next
change — a binding binder marked by its declared type, decided 2026-09-11, not built — §10 the gate, §11 the open
decisions D3/D17/D18/D19); Part III is §12's list of things closed by measurement or decision, which must not be
re-proposed, and provenance for source comments citing retired documents or the retired v6 plan record.
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

- **`getFact` still lives.** The processor-facing API is supposed to expose only `getFactOrAbort`
  / `getFactOrError` / `getFactIfProduced` (see `.claude/rules/eliot-design.md`), but a plain
  `getFact` is still there on `CompilationProcess` and its wrappers. Either rename it to
  `getFactIfProduced` or hide it entirely behind the three intent-carrying reads.
- `namedValues` can recurse — the reflection-driven enumeration is not protected against a value
  that reaches itself.
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
