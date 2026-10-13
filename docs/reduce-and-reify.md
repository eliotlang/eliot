# Optimization Thread: Reduce-and-Reify

Status: **Design record / discussion writeup (2026-07-05).** It captures a connected line of optimization
ideas and their feasibility, plus one enabling language redesign (`Dep` as a Reader). The purpose is to record
*why these are the same optimization* and *what the single missing piece is*, so the work — if taken — lands as
one coherent capability rather than three ad-hoc passes.

**Update (2026-07-06): §1 (the `Dep`→Reader redesign) is now IMPLEMENTED** — `DepCarrier` + `provide` ship in
`stdlib`/`jvm`, the per-dependency `implement Dep[X, IO]` is gone, and multi-dep is nested `provide`s (see
`EffectsTwoDeps`/`EffectsMulti`). The peel/static-inlining optimization proper (§2–4) is still design-only. One
implementation refinement §1.3 did not anticipate: the native `Dep` instance is written lambda-free
(`DepCarrier(providedValue)`, a named helper) rather than the sketch's inline `DepCarrier(x -> pure(x))`, so it
emits no `dependency$lambda$N` class that would collide with the cross-lift's — the same trick `Throw`'s native
`raise` uses. The cross-lift `Dep[X2, DepCarrier[X1, G]] where X1 != X2` mirrors `Throw`'s foreign-error lift.

> **Superseded in mechanism by effects v6 (2026-09-09), not in outcome.** `Dep` is still an effect with
> `dependency` and `provide`, and it still behaves as a Reader — but there is no `DepCarrier`, no cross-lift and
> no carrier machinery of any kind: `provide` installs a cell frame over the platform's private leaf, and
> `Dep[X]`'s single implementation reads it (`docs/effects.md` §3.4). **§2–4 remain design-only and are written
> against carriers**, so the "peel the Reader carrier" framing has to be restated before it is built — the
> optimization it names (a dependency provided once is a constant at every read) is untouched and, with the
> plumbing already gone, is now a smaller job than this document assumes.

> **Update (2026-10-05): the soundness gate is the compiler track, not the effect row.** §4.5 used to say "safe to
> evaluate at compile time = empty effect row". That has been false since v6 and is plainly false under effects
> D20 (`docs/effects.md` §11): a block parameter borrows its caller's effects (`pick(left: => A, …)`, `map(f: A =>
> B, …)`), so a signature says what a definition *introduces*, never what a *call* performs. The gate is now
> **"the term reduces to a value on the compiler track"** — the same online rule §2 already states, applied as
> the safety rule — with the conditions §4.5 lists. Partial residuals gained a hazard the first version missed:
> a stuck term may never be **dropped**, duplicated or reordered (§3.3, §6).

## 0. Thesis

Three seemingly separate ideas came up:

1. Make `Dep[X]` a proper **Reader** (discharged by `provide` at the edge), not a compile-time-singleton
   `implement`.
2. **Peel** the Reader carrier: once a dependency is `provide`d at one site, every `dependency` call sees the
   same value — collapse the carrier plumbing and refer to that value directly.
3. **Static inlining**: replace any pure, compile-time-known computation (`1+1`, projecting a known config
   record, `map`/`filter` over a known list) with its result.

The claim of this thread: **(2) and (3) are the same optimization**, and it is not a new pass at all — it is
the compiler's *existing* single NbE evaluator (`monomorphize/eval/`) run over runtime-position terms, with a
readback that **reifies whatever is compile-time-determined and residualizes the rest**. Put on one axis:

| | residual after normalization | readback |
|---|---|---|
| **Reader peel** (§3) | *partial* — a runtime value (`VNeutral`) survives | reify structure, leave the neutral |
| **static inlining** (§4) | *total* — everything reduces to ground | reify the whole result |

"Reduce what you can, reify the rest." The peel is the partial-residual endpoint; static inlining is the
total-residual endpoint. Build the readback for one and you get the other for free. (1) is the language
redesign that *creates a client* for the peel; it is separable and is written up here as the premise.

One caveat sits underneath all of this and is the load-bearing subtlety of static inlining: a value is
computed in the **`compiler`** platform but must be emitted as **`runtime`**-platform code, so "reify" is a
cross-platform *lift to portable surface syntax*, **not** a structural quote of the compiler's own
representation — see §4.2. Get that wrong and you splice compiler-only constructors into the target.

This is not exotic. It is **partial evaluation** (Jones/Gomard/Sestoft) realized as **normalization by
evaluation**, exactly as dependently-typed languages (Agda/Idris/Lean) already normalize closed terms to
canonical form. Eliot is *in that family by cornerstone* ("one evaluator, type-checking is normalization"),
which is why the engine already exists — see [monomorphize-review.md](./monomorphize-review.md) and the
[compiler-as-platform](./compiler-as-platform.md) native routing.

---

## 1. Premise: `Dep` should be a Reader (enabling redesign)

This is a design change in its own right; it could be its own doc. It is here because the peel (§3) has no
client without it.

### 1.1 Why the current `Dep` is too weak

```eliot
ability Dep[X, F[_]] { def dependency: F[X] }          -- stdlib/eliot/src/eliot/effect/Dep.els
implement Dep[Database, IO] { def dependency = pure(Database("jdbc://…")) }  -- the "injection"
```

`Dep` is **the only effect discharged by an `implement` instead of a `run`.** Compare the family:

| effect | ask | discharge |
|---|---|---|
| `Throw[E]` | `raise` | `runThrow(prog)` → `Either` |
| `State[S]` | `state`/`putState` | `runStateToPair(initial, prog)` |
| `Abort` | `abort` | `orElse(prog, fallback)` |
| **`Dep[X]`** | `dependency` | **`implement Dep[X, IO] { … }`** ← outlier |

Every other effect is discharged by a `run*` **function at the edge** taking a runtime argument. `Dep`'s
`implement` body is a global, coherence-unique, compile-time-resolved constant, so it cannot vary per run,
be built by *effectful* acquisition (open a DB connection from config), depend on other dependencies acquired
first, or be swapped for a fake in a test. That is a service locator with a hardcoded value, not dependency
injection.

### 1.2 The redesign: `Dep` = Reader, discharged by `provide`

`Dep` *is* Reader, and Reader is `State` minus the write and minus threading the state back:
`ReaderCarrier[X, G, A] = X => G[A]`. Reuse the entire `State` carrier machinery (see
`stdlib/.../effect/State.els`, `jvm/.../effect/State.els`), stripped of the pair-threading:

```eliot
-- stdlib (abstract): keep the ability, add the carrier + discharge (mirrors Throw/State)
ability Dep[X, F[_]] { def dependency: F[X] }

type DepCarrier[X, G[_], A]
def runDepCarrier[X, G[_], A](obj: DepCarrier[X, G, A]): X => G[A]

/** Discharge {Dep[X]} by supplying `value`; other effects remain in G. */
def provide[X, G[_], A](p: DepCarrier[X, G, A], value: X): G[A] = runDepCarrier(p)(value)
```

```eliot
-- jvm layer: the single generic platform instance set (StateCarrier, minus the state threading)
data DepCarrier[X, G[_], A](runDepCarrier: X => G[A])

implement[X, G[_] ~ Effect] Effect[DepCarrier[X, G]] {
   def pure[A](a: A): DepCarrier[X, G, A]                = DepCarrier(x -> pure(a))
   def flatMap[A, B](f: A => DepCarrier[X, G, B], fa: DepCarrier[X, G, A]): DepCarrier[X, G, B] =
      DepCarrier(x -> flatMap(a -> runDepCarrier(f(a))(x), runDepCarrier(fa)(x)))
   def map[A, B](f: A => B, fa: DepCarrier[X, G, A]): DepCarrier[X, G, B] =
      DepCarrier(x -> map(f, runDepCarrier(fa)(x)))
}
implement[X, G[_] ~ Suspend & Effect] Suspend[DepCarrier[X, G]] {
   def suspend[A](thunk: Unit => A): DepCarrier[X, G, A] = DepCarrier(x -> suspend(thunk))
}
implement[X, G[_] ~ Effect] Dep[X, DepCarrier[X, G]] {
   def dependency: DepCarrier[X, G, X] = DepCarrier(x -> pure(x))   -- Reader's `ask`
}
```

The user's per-dependency `implement Dep[Database, IO]` **disappears entirely** — there is now exactly one
generic `Dep` instance (on the carrier), just as users never write `implement State[Int, IO]`. Effectful
acquisition lives at the edge; `provide` injects the *result*:

```eliot
def app: {Dep[Database], Dep[Topic], Console} Unit = {
   printLine(dependency.url)          -- unchanged; asks by type
   printLine(dependency.name)
}
def main: IO[Unit] =
   readConfig.flatMap(cfg ->
   openDatabase(cfg).flatMap(db ->    -- effectful, config-driven, runtime
   provide(provide(app, db), Topic("events"))))
```

Each `provide` discharges one `Dep[X]` from the row (nested like `runThrow(runStateToPair(…))`), so the
multi-dep symmetry holds and one-value-per-type is now *structural* rather than coherence-reliant. Tests
become `provide(app, fakeDb)`.

### 1.3 What this must **not** become

Do **not** make `Dep` *instances* into ZIO-`ZLayer`-style runtime-composable recipes. That would require
ability instances to be first-class runtime values passed around, contradicting the cornerstone ("references
to ability values are fully resolved during monomorphization, they are not passed around in structures").
The Reader `provide` gets ZLayer's *observable behavior* (effectful, config-driven, edge-supplied injection)
while keeping instances compile-time-resolved. That is the correct trade-off for Eliot.

Language survey backing this shape: Reader/`ReaderT` + the `Has` pattern (Eliot's type-indexed `{Dep[X]}`
already removes the `Has`-boilerplate); ZIO `ZLayer` / Effect-TS `Layer`+`Context.Tag` (the "constructing a
service is itself effectful" concern, made a feature); algebraic effect handlers (Unison/Koka/OCaml 5 — a
`Dep` handler answering `dependency` with a captured runtime value is the closest match to Eliot's abilities);
OO DI (`@Provides` factories). Common thread: **ask by type, supply at the edge with a value that may itself
be built effectfully.**

---

## 2. Why both optimizations reduce to one thing: NbE = partial evaluation

Eliot's `SemValue` domain (`monomorphize/domain/SemValue.scala`) already carries the static/dynamic split
that partial-evaluation theory needs:

- **static plumbing** = `VConst`/`VTopDef` constructor structures + field projections + `VLam` closures. All
  reducible.
- **dynamic residual** = `VNeutral(head, spine)` — a stuck term. `NeutralHead.Param(level, name)` is a
  lambda-bound variable (e.g. the acquired `db`); `VStuckNative` is a native whose args never rigidified.

The evaluator's two core reductions collapse the plumbing **regardless of whether an argument is a value or a
neutral**:

- **iota** (constructor meets eliminator): `runDepCarrier(DepCarrier(g))` → project field 0 → `g`.
- **beta** (`VLam` meets arg): `VLam("x", closure)` applied to `VNeutral(db)` → `closure(VNeutral db)`. The
  closure is a Scala `SemValue => SemValue`, so applying it to a neutral **substitutes `db` into the body and
  keeps normalizing** — it does not care that `db` is neutral.

That is the whole engine. What differs between "constant folds away" and "runtime value peels the carrier" is
only whether a `VNeutral` survives to readback. NbE is an **online partial evaluator**: no separate
binding-time-analysis pass annotates static/dynamic; you just evaluate, and whatever gets stuck *is* the
dynamic residual.

---

## 3. Optimization A — Reader / carrier peeling (partial residual)

**Goal.** After `provide(app, db)` at one site, collapse the `DepCarrier` plumbing so `db` is evaluated once
and referenced directly wherever `dependency` appeared. `db` need **not** be a compile-time constant.

### 3.1 The reduction (a `SemValue` trace)

`provide(app, db) = runDepCarrier(app)(db)`:

```
app                       ⇓  VConst(Structure(DepCarrier, [ VLam("x", clo) ]))
runDepCarrier(app)        ⇓  VLam("x", clo)                        -- iota
· applied to db=VNeutral  ⇓  clo(VNeutral db)                      -- beta, db propagates inward
  each `dependency` in clo:  runDepCarrier(DepCarrier(VLam("x", y->pure y)))(x)
                          ⇓  pure(x) ⇓ pure(VNeutral db)           -- iota + beta again
  flatMap/map on DepCarrier ⇓ unfold to the IO carrier's flatMap/map
result                    ⇓  a plain IO term with `VNeutral db` at every former `dependency`
```

Read that back and the `DepCarrier` layer is **gone**; `db` sits as a free variable everywhere `dependency`
was. The transformation is **inline-at-unique-use + beta + dead-constructor-elimination** — whose textbook
name is **lambda-dropping** (Danvy & Schultz, *"Lambda-Dropping: Transforming Recursive Equations into
Programs with Block Structure"*). A Reader monad *is* manual lambda-lifting of the environment; a single
`provide` site makes the parameter invariant, so lambda-dropping puts it back as a block-scoped free variable.

### 3.2 Soundness conditions (all guaranteed by cornerstone, none discovered by analysis)

- **Single elimination site** ⇒ the environment parameter is call-site-invariant. (The common DI case: wire
  once at the top.)
- **Read-only** ⇒ the value is immutable across the discharged scope; this is *why it must be a Reader and not
  a `State`* — a writer would make the value vary and forbid the collapse.
- The "single reaching definition dominates every use" property is **not computed** by a dataflow pass — it is
  *structurally guaranteed* because `provide` binds the environment with one λ and NbE substitutes it. **No
  lattice, no fixpoint, no alias/escape analysis, no profiling.** Contrast a C compiler folding a global
  mutable, which needs reaching-definitions + aliasing + escape analysis; Eliot needs none because
  read-only + no-recursion + whole-program-monomorphization make the fact free.

### 3.3 When it does not fully fire

- **Multiple `provide` sites with different `db`s.** The parameter is genuinely variadic. NbE still reduces
  each *use* independently (demand-driven), so each site peels at the cost of normalizing the body per site
  (= specialization / code duplication). Not a failure; an inherent size trade-off. Fallback if undesired:
  keep threading `db` as an argument.
- **Sharing / duplication.** Beta *substitutes* `db` for `x`. If `db` were a *compound* stuck computation
  (`readConfig.flatMap(openDb)`) used N times, naive substitution would duplicate — and *re-run* —
  acquisition N times. Dodged by construction in idiomatic code: acquisition is written
  `openDb(cfg).flatMap(db -> provide(app, db))`, so the thing reaching `provide` is `db` the **λ-bound
  parameter** — an atomic `NeutralHead.Param`, free to duplicate; the `flatMap` already sequenced acquisition
  exactly once. Real let-insertion in readback is only needed if a compound neutral is duplicated (see §6).
- **Dropping and reordering (added 2026-10-05).** Duplication is the visible half; the dangerous half is the
  opposite. Eliot is strict, so a stuck term may be an *effect that has not happened yet*, and beta does not know
  that. In `{ printLine("a"); 1 + 1 }` the statement's result is unused, so substituting it into a body that never
  mentions it **drops the print** — and a reduction that floats a stuck term past another reorders two effects.
  Neither is a sharing problem. A partial residual must keep every stuck term, once, in evaluation order: that is
  let-insertion for *every* compound neutral, not only a duplicated one (§6). Static inlining (§4) is unaffected —
  it replaces a term only when nothing in it is stuck.

### 3.4 Generalizes to the whole effect family

The peel is generic NbE, so the same mechanism collapses `State` (single `runState` site, effectively
read-only state), `Throw`, etc. Teach the readback to share neutrals once and every carrier benefits.

---

## 4. Optimization B — static inlining (total residual, a.k.a. CTFE)

**Goal.** Replace a compile-time-known computation with its result: `1+1 → 2`; projecting a field from a
known config record; `[1,2,3].map(f)` where the list and `f` are known. "May this be replaced?" is decided by
evaluation, not by a purity reading of signatures — §4.5.

### 4.1 It is the same pass, run to completion

When *no* input is neutral, the §2 reductions run to a ground value and readback is *total*. The engine is
identical; only the residual differs (nothing survives).

### 4.2 Reification crosses the platform boundary — it is a per-type *lift*, not a structural quote

**This is the subtle part** (raised 2026-07-05). The value is computed **in the compiler platform**, but the
materialized expression must be spliced into code that codegen lowers in the **runtime platform** — and those
are *different layers* ([compiler-as-platform.md](./compiler-as-platform.md)). The compiler's `Cons` is not
(necessarily) the target's `Cons`; the target may even represent `List` completely differently (array-backed,
unrolled) than the compiler's cons-cell scratch representation. So "it's all just `Cons`" is **false**: naively
quoting the compiler-platform value and emitting its FQNs would splice compiler-only constructors into runtime
code — either nonexistent at runtime or, worse, silently wrong.

The correct target for reification is **portable surface syntax — "what the user could have typed"** — *not*
the compiler-platform concrete value and *not* a runtime-concrete value. If the user could have written
`Cons(1, Cons(2, Nil))` in source and it compiles for the target, then emitting that exact expression is sound
for the target *by construction*; the compiler evaluation was only a shortcut to discover *which* such
expression to emit. This is Template Haskell's `Lift` (`a -> Q Exp`), Scala 3's `quoted.ToExpr[T]`, Common
Lisp's `make-load-form`, MetaOCaml cross-stage persistence — all **per-type**, because opaque representations
cannot be quoted generically. Two tiers:

- **Tier 1 — shared-name `data` constructors (easy majority).** FQNs are *platform-independent*: the
  `compiler`/`runtime` marker is a separate key dimension, not part of the name (the `add` pattern — same
  `Cons` FQN, different body per platform). So a compiler-computed `Structure(ConsFQN, …)` carries the *same*
  `ConsFQN` the runtime platform resolves, and the layer merge's **`signatureEquality` invariant provably
  forces the field structure to agree** across layers — which is exactly what licenses a structural quote. The
  quoter already reconstructs constructor applications (`monomorphize/eval/Quoter.scala`: `VTopDef(fqn, None,
  spine) => Structure(fqn, args, …)`), so the *skeleton* is emitted as abstract-FQN constructor calls and
  `used → uncurry → backend` lowers it **exactly as it lowers hand-written source `Cons`** — the target's own
  `Cons`/`Nil` lowering handles its representation. **This dissolves the "target has a different List
  representation" worry into the backend's existing job**, not the reifier's.
- **Tier 2 — native leaves (the genuinely hard, non-generic part).** A value sitting as a compiler-platform
  *native* (`Int`/`BigInteger`, `Bool`, anything a compiler native computes into a raw Scala structure) has
  **no constructor form** — no `Structure` to quote. Each such native needs a **per-type "lift to source"
  rule**: `BigInteger 3` → the integer-literal AST node; `Bool` → the `true`/`false` reference. This *is* the
  `Lift`/`ToExpr`/`make-load-form` protocol. Structural quote **recurses through Tier-1 constructors and
  bottoms out at these per-native lifts** (`Cons(3, Nil)`: the spine is Tier 1, the `3` leaf is Tier 2).

**Fail-safe (per [gaps must be fail-safe]):** if a leaf has no lift rule, or the value is stuck/partial,
**decline to materialize** and emit the runtime computation instead — never emit a compiler-only or
field-mismatched constructor. This is the mirror image of the existing compiler-as-platform boundary
("reaching a runtime-only native at compile time = error"): here a *compiler-only* value must never leak into
runtime code. And it degrades gracefully — a missing lift rule costs an optimization, never correctness.

Note this is why the *reification* problem is **specific to static inlining, not the peel**: the peel (§3)
only re-plumbs terms *already in runtime vocabulary* (the program's own `db` variable — a `VNeutral` that was
always a runtime term), so it never crosses the representation boundary. The lift protocol is needed exactly
when the readback emits a **compiler-*originated*** value leaf — the total case here, and any mixed peel that
also constant-folds a pure sub-term.

### 4.3 What is List-specific

`map`/`filter` are driven by an eliminator (`fold` / the data destructor), which in Eliot is a **native** (no
user recursion). For NbE to evaluate `map` over a *concrete* `Cons/Nil` spine, that eliminator needs a
**compiler-track reduction over concrete constructors** — the same pattern as the existing `add` / `Bool`-fold
/ `Eq[Type]::equals` compiler natives (`SystemNativesProcessor` / `ContributedBinding` / `CompilerNativesProcessor`;
see [compiler-as-platform.md](./compiler-as-platform.md)). It is a terminating unfold over an already-finite
value. Without such a rule the transformation stays stuck as a `VStuckNative` and nothing folds. **Concrete
work item: give the collection eliminator(s) a compiler-track reduction.**

### 4.4 The genuinely hard part is the *policy*, not the mechanism

"Could be (should be?)" — the parenthetical is the whole difficulty. The reduction is easy; **the decision to
materialize is a three-way trade-off**: runtime-speed↑, binary-size↑, compile-time↑. `range(0, 1_000_000)
.map(f)` folded at compile time bakes a megabyte of literal `Cons` cells into the binary — catastrophic on an
ATtiny. This is precisely why partial evaluators/supercompilers need a *whistle* / size budget: the reduction
always terminates (§4.5), but you do not always *want* it to. Recommended stance:

- **Always fold:** scalars, small tuples/records, statically-bounded results under a size threshold. Near-free,
  universally good. (`1+1`, `length([a,b,c])`, config-record projection.)
- **Do not materialize:** collections whose reified size exceeds a budget — keep the runtime loop.
- **Prefer fusion over materialization** for collections, especially on MCU targets: **deforestation /
  short-cut fusion** (Wadler; `foldr/build`; stream fusion) eliminates the *intermediate* without precomputing
  the *result* — trading neither size nor compile-time. Materialization trades size for speed; fusion is a pure
  structural win. On microcontrollers the default should skew toward "fold scalars, fuse collections,
  materialize little."

### 4.5 Why this is *safer* in Eliot than in C++/Zig — and what the soundness gate is

Two properties hand over, for free, the guards CTFE needs elsewhere:

1. **Totality / no recursion ⇒ unbounded compile-time evaluation cannot hang the compiler.** C++ `constexpr`
   and Zig `comptime` cap iterations precisely because CTFE can diverge. A non-`Inf` Eliot term *provably
   terminates*, so evaluating to normal form is safe by construction. See the "Total by Default" cornerstone.
   (`Inf` cannot fold at all: its loop primitive has no compile-time leaf, so it is stuck — rule 2.)
2. **The compiler track is the soundness gate.** *Rewritten 2026-10-05.* The rule:

   > **A term may be replaced by its value iff it reduces to a value on the compiler track.**

   The compiler track has no world. Every way out to it — a console, a file, a process, the clock — is a platform
   native, and a platform native has no compile-time leaf, so an evaluation that reaches one is **stuck**
   (`VStuckNative`). A term that reduces *completely* therefore touched nothing outside the program, and its value
   is what running it would produce. This is §2's online rule — *whatever gets stuck is the dynamic residual* —
   used as the safety rule as well: the stuck part is both what cannot be known yet and what might touch the
   world. No binding-time analysis, no purity analysis, no annotation.

   **Why not the effect row** — the first version of this section. "Pure (empty row) ⇒ safe to force" assumed a
   signature says what a call performs. Since effects v6 it says what a definition *introduces*: a block parameter
   borrows its caller's effects, resolved where the argument is written (`docs/effects.md` D20 — `pick(left: => A,
   …)` declares nothing and runs `printLine`). A row-keyed gate would therefore have to inspect every argument
   anyway, and it would still refuse what this rule folds.

   **What it folds that a purity gate would refuse**, correctly — as far as the compile track implements the
   effect involved:
   - A discharged `Abort` or `Throw` reduces today — `if (c) a else b` over a known `c` is the case — because the
     compile track's `Abort` and `Throw` (`stdlib/eliot/compiler/eliot/effect/`) are written over the escape
     intrinsic (`monomorphize/processor/EffectIntrinsics`), with their frames threaded along the declared chain
     (`docs/effects.md` §3.4). That is the same reduction that makes a guarded return type reduce during checking.
   - **`State` and `Writer` do not reduce today.** Nothing implements them over the cell intrinsic that
     `eliot/compiler/Cell.els` declares. So `runStateToPair(…)` and
     `runWriterToLog(greeting("Bob") with recordingConsole)` are stuck and stay in the code — safe, merely
     unoptimized. Each starts folding once its effect gets a compile-track implementation, the same overlay work
     `Abort` and `Throw` got; the mocked `greeting` would then fold to `"Hello, Bob!;"`, since a named double is pure
     Eliot. The gate needs no change for that — only the compile track's coverage grows.
   - An undischarged `raise` or `abort` has no frame and is stuck; `printLine` under `main`'s binding reaches the
     platform's native and is stuck. Both stay in the code.

   **Precedent.** This is the established design, not the novel one. D's compile-time function evaluation and Zig's
   `comptime` run ordinary functions at compile time and decide "can it?" by whether the interpreter gets through
   without reaching something it cannot do — there is no purity annotation. C++ defines a *constant expression* by
   its evaluation not reaching a forbidden operation; `constexpr` grants permission, it does not certify purity.
   Online partial evaluation and supercompilation residualize what does not reduce, with effectful primitives
   simply never static. Lean's kernel reduces a pure reference definition and treats `extern`/`IO` primitives as
   opaque constants — the native-leaf boundary under another name. Keying on declared purity (Haskell's types,
   Rust's `const fn`) is the narrower approach.

   **Conditions — invariants to keep, not analyses to run:**

   a. **No compiler-track leaf touches the world.** The compile-time leaves are Scala
      (`SystemNativesProcessor`, `StdlibNativesProcessor`), written by the compiler's authors; a leaf reading a
      clock, a file or an environment would make this rule unsound everywhere at once.
   b. **A name realised on both tracks computes the same value on both.** `String` operations, `Int` arithmetic,
      `Bool`'s `fold` and every compile-time twin. Today a disagreement is a type-checking oddity; with folding it
      is a **miscompile**, silently. This needs differential tests — the same inputs evaluated on both tracks.
   c. **An effect's default does not resolve to a different implementation on the compiler track.** Were a
      `compiler/` overlay to ship a pure anonymous `implement Console` (for checking, say), `printLine` under
      `main` would reduce there and the print would vanish from the binary. Today only `Abort` has an overlay
      implementation, and it is the same control effect over an intrinsic frame. A *named* implementation is the
      same name on both tracks, so a mocked computation folding (once its effects reduce) is correct. Giving
      `Throw`/`State`/`Writer` compile-track implementations is exactly this condition's case: each must be the
      control effect's one semantics over an intrinsic frame, never an interpretation effect's default.
   d. **Stuck means "residualize", quietly.** The checker's stuck term is a loud error ("Cannot resolve type"),
      correctly. The optimizer needs its own entry where stuck is an outcome, not a failure — the "quiet-stall
      evaluator entry with fuel" `docs/effects.md` §12 already lists under "not now".
   e. **A budget.** Totality says it ends, not that it ends soon (§4.4, §6).
   f. **A partial result keeps every stuck term, once, in order** (§3.3). Total folding — the case this section
      is about — replaces a term only when nothing in it is stuck, so it never meets this condition; the peel
      always does.

---

## 5. What exists vs. what is missing

**Already present (the engine):**

- `monomorphize/eval/Evaluator.scala` — `apply`/`force`, iota + beta, with `VLam.closure: SemValue =>
  SemValue` propagating neutrals.
- `monomorphize/eval/Quoter.scala` — readback that *reconstructs constructor applications* (`VTopDef(fqn,
  None, spine) => Structure`). This is the type-level quoter → `GroundValue`; it **deliberately fails on
  `VNeutral`/`VLam`/`VStuckNative`** ("Cannot quote neutral value"). Correct for types; must stay that way.
- `monomorphize/eval/MonomorphicEvaluator.scala` — the runtime-track evaluator feeding
  [`used`](../lang) → `uncurry` → backend.
- Constant folding of small pure computations arguably already happens wherever a value is forced in a
  compile-time position.

**Missing piece #1 — the one both optimizations share:** a **value-path readback** that emits into the
runtime/codegen term (resolved `Expression` / uncurried core), **tolerating a residual `VNeutral`** and
**let-sharing** a multiply-used one. The existing `Quoter` targets `GroundValue` and rejects neutrals; the
value path is separate and must not change the type path. *Build this once and both the peel (partial) and
static inlining (total) light up.*

**Missing piece #1b — the reification/lift protocol (§4.2), a sub-part of #1 but the hard one for static
inlining:** the value-path readback must emit **portable surface syntax** (abstract-FQN constructors +
literals), not the compiler-platform concrete value, because the result crosses from the `compiler` platform
to the `runtime` platform. Tier 1 (shared-name `data`) quotes structurally (licensed by the merge's
`signatureEquality` invariant); Tier 2 (native leaves — `Int`/`Bool`/…) needs a **per-native lift-to-source
rule** (the `Lift`/`ToExpr`/`make-load-form` analogue). No lift rule / stuck leaf ⇒ **decline to materialize**
(fail-safe). Not needed by the peel, which only re-plumbs runtime-vocabulary terms.

**Missing piece #2 — static-inlining, collections:** a compiler-track reduction for the collection eliminator
(`fold` / destructor) over concrete constructors (§4.3). *Independent of #1b* — this makes the value *exist*
at compile time; #1b makes it *emittable* as runtime code.

**Missing piece #3 — the peel, trigger:** delta-unfold `app`'s monomorphic body (and the `DepCarrier`
`flatMap`/`map`/`ask` instance bodies) at the discharge site so the redex is *exposed*. Whole-program
monomorphization already has the bodies; this is an unfolding *policy*, not new analysis.

**Missing piece #4 — the hard one (open everywhere):** the **materialization budget** heuristic (§4.4). This
is a policy, not a correctness question, so it can start dumb-conservative (fold only bounded/small results)
and grow.

**Missing piece #5 — the gate's entry and its guards (§4.5, added 2026-10-05):** a **quiet-stall** evaluator
entry for the optimizer, where stuck means "residualize" rather than the checker's error (with fuel, for
§4.4's budget); and **differential twin tests** that evaluate every name realised on both tracks over the same
inputs. The second guards a soundness condition and should land first.

**Missing piece #6 — compile-track control effects (optional, coverage only):** `Throw`, `State` and `Writer`
implemented over the compile track's escape and cell intrinsics, as `Abort` already is. Without them a discharged
`Throw`/`State`/`Writer` is stuck and simply not folded; with them it folds, mocked computations included.

---

## 6. Open questions / risks

- **Let-insertion for compound neutrals — required for soundness, not only for sharing.** Idiomatic monadic bind
  makes the shared value atomic (§3.3), but a general value-path readback should still be able to introduce a
  shared `let` rather than duplicate a costly neutral, so the optimization is not defeated by non-idiomatic code.
  *Added 2026-10-05:* duplication is the mild half. In a strict language a compound neutral may be an effect not
  yet performed, so beta substituting it into a body that ignores it **drops** the effect, and floating it past
  another neutral **reorders** two effects (§3.3's `{ printLine("a"); 1 + 1 }`). The partial-residual readback
  must let-bind every compound neutral at the point it is evaluated, in evaluation order — the standard remedy
  for partial evaluation of a strict language with effects (Bondorf & Danvy's let-insertion; Lawall & Thiemann,
  *Sound Specialization in the Presence of Computational Effects*). Total folding is untouched: it never
  residualizes anything.
- **Codegen expectations.** Do `used`/`uncurry`/the jvm backend accept a *normalized* (carrier-collapsed) term
  shape, or do they rely on seeing the un-reduced calls? The value-path readback must emit a term the back half
  already understands. Cross-check against [architecture-review.md](./architecture-review.md) (the back-half
  seam) and [monomorphize-review.md](./monomorphize-review.md).
- **The gate's invariants must be kept, and tested.** A term is replaced only when it reduces completely on the
  compiler track (§4.5; *rewritten 2026-10-05*, it used to read "the effect row is the gate"). That rule is only
  as sound as §4.5's conditions (a)–(c): no compile-time leaf touches the world, twins agree, and no effect's
  default resolves differently on the compiler track. Of these, (b) is the one that can rot unnoticed — a
  `String` twin drifting from its jvm leaf is today a checking oddity and becomes a silent miscompile the day
  folding lands — so differential tests over both tracks should land *before* the optimization does.
- **Compile-time blowup.** Totality bounds evaluation (it will not hang) but does not make it *fast*. Heavy
  compile-time data slows the compiler; the §4.4 budget must weigh compile-time, not only binary size.
- **Multiple-site specialization vs. size** (§3.3) — where to draw the peel-vs-thread line when a value is
  provided at more than one site.
- **The lift protocol's surface** (§4.2) — which Tier-2 natives get lift rules, and where those rules live.
  Likely the same compiler-platform that owns the native's *reduction* (`SystemNativesProcessor` /
  `StdlibNativesProcessor`) should own its *lift* — keeping the leaf's compile-time reduction and its
  source-reification co-located. Open: whether a value can be Tier-1-structural at the top but hide a Tier-2
  native with no lift rule deep inside (⇒ the decline must be detected *before* committing to materialize, not
  half-way through emission).

---

## 7. Prior art / names (for grounding)

- **Constant folding** → **partial evaluation** (Jones/Gomard/Sestoft; online vs. offline; Futamura's
  static/dynamic split) → **supercompilation** (Turchin; Bolingbroke & Peyton Jones, *"Supercompilation by
  Evaluation"*).
- **Deforestation / fusion** — Wadler; `foldr/build` short-cut fusion (Gill/Launchbury/Peyton Jones); stream
  fusion. The "don't materialize the intermediate" answer to §4.
- **CTFE / comptime** — C++ `constexpr`/`consteval`, Zig `comptime`, D CTFE, Rust const-eval, Nim `static`,
  Jai `#run`. All restricted, iteration-capped; Eliot's version is unrestricted-but-safe (§4.5). D, Zig and C++
  decide *whether* something can be evaluated by evaluating it — the interpreter refuses I/O and globals — which
  is the gate §4.5 adopts; Rust's `const fn` is the annotation-keyed alternative.
- **Opaque primitives in a reducing checker** — Lean's kernel reduces a pure reference definition and treats
  `extern`/`implemented_by`/`IO` primitives as opaque, so kernel reduction can never perform I/O: Eliot's
  native-leaf boundary, and the reason §4.5's gate is sound.
- **Effects in partial evaluation** — let-insertion (Bondorf & Danvy) and Lawall & Thiemann, *Sound
  Specialization in the Presence of Computational Effects*: a residualized effectful term is let-bound in
  evaluation order, never dropped, duplicated or moved (§3.3, §6).
- **Normalization in dependently-typed languages** — Agda/Idris/Lean/Coq normalize closed terms to canonical
  constructor form and lean on it (`decide`, reflection, `#eval`). **Eliot is in this family**, which is why
  the engine already exists.
- **Lambda-dropping** — Danvy & Schultz; the exact name for the §3 collapse (inverse of lambda-lifting).
- **Effect-handler fusion / evidence passing** — Koka (Xie & Leijen, *"Effect Handlers, Evidently"* /
  *"Generalized Evidence Passing"*): a **tail-resumptive** handler (like Reader's `ask`) compiles with no
  continuation capture, so `ask` becomes an evidence read → direct reference. The most literal match to §3 as a
  general effect optimization. `fused-effects` / `polysemy` chase the same fusion (and the "polysemy is slow"
  problem *was* this peel failing without aggressive inlining).
- **Peel vs. runtime-container, as a design axis** — **Dagger** (compile-time DI: generates direct references)
  vs. **Guice/Spring** (runtime container); **Eliot Reader peel** vs. **ZIO `ZEnvironment` / Effect-TS
  `Context`** (runtime type-indexed map — deliberately *not* peeled, because their env is dynamic by design).
- **Staging** — MetaOCaml / Scala LMS: the explicit, programmer-controlled version of the same transformation.
  MetaOCaml **cross-stage persistence (CSP)** restricts *which* values may be embedded in a later stage — the
  same limit the §4.2 lift protocol hits (not every runtime value has a source form).
- **Value → source ("lift") — the §4.2 protocol** — Template Haskell `Lift` (`a -> Q Exp`), Scala 3
  `quoted.ToExpr[T]`, Rust `ToTokens`/`quote!`, **Common Lisp `make-load-form`** (a compiled-file object
  supplies the recipe to rebuild itself at load time — the sharpest analogue). All **per-type**, all bottoming
  out at primitives — exactly the Tier-1-structural / Tier-2-native split.
- **Scala `given`/implicit params** — the *manual dual* of the Reader peel: never build the Reader; pass the
  value as an inferred argument (lambda-lifted, threaded by the compiler).

---

## 8. One-line summaries (for notes / memory)

- **Dep→Reader:** `Dep` is the only effect discharged by an `implement` instead of a `run`; make it discharged
  by `provide` and both the asymmetry and the weakness vanish.
- **Peel:** not an optimization to add — it is the NbE evaluator you already have, provided the runtime
  readback tolerates and let-shares a neutral environment the way the type-level quoter refuses to.
- **Static inlining:** the *same* pass run to completion (total residual); totality makes unbounded CTFE safe;
  the soundness gate is **"reduces to a value on the compiler track"**, never the effect row (a block borrows its
  caller's effects, so a signature cannot say what a call performs); two hard parts — the materialization budget,
  and reification.
- **The gate's price:** three invariants (no world-touching compile-time leaf, twins agree, no effect default
  differing by track) and, for partial residuals only, let-insertion of every stuck term so none is dropped.
- **Reification is not "just Cons":** the value is computed in the `compiler` platform but must be emitted as
  `runtime`-platform code. Reify to **portable surface syntax** ("what the user could have typed"): Tier-1
  shared-name `data` quotes structurally (merge's `signatureEquality` licenses it; codegen re-lowers per
  target — so a different target `List` representation is the backend's job); Tier-2 native leaves need a
  **per-type lift-to-source rule** (`Lift`/`ToExpr`/`make-load-form`). No rule ⇒ decline (fail-safe).
- **The thread:** reduce what you can, reify the rest. One evaluator, one value-path readback, one spectrum
  from partial (peel) to total (fold).
