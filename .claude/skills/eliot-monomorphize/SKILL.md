---
name: eliot-monomorphize
description: Use when editing, debugging, or reasoning about code under `lang/src/com/vanillasource/eliot/eliotc/monomorphize/` — the NbE (Normalisation by Evaluation) type checker for Eliot. Covers the semantic domain (SemValue), the bidirectional checker, the three evaluators, pattern unification, the two platform tracks (runtime + compiler), and the TypeStackLoop that kind-checks a value's signature against its derived kind and drives monomorphization.
---

# monomorphize: NbE type checker

## Scope of this skill

This skill governs all code under `lang/src/com/vanillasource/eliot/eliotc/monomorphize/`:

```
monomorphize/
├── domain/
│   ├── SemValue.scala          (VType, VConst, VLam, VPi, VNative, VTopDef, VStuckNative, VFlex, VNeutral, Spine)
│   ├── Env.scala               (Vector[SemValue] + names; lookup by name, last-bound-wins; `level` mints neutrals)
│   └── FlexStore.scala         (IntMap[Option[SemValue]]; fresh/solve/lookup)
├── eval/
│   ├── NbeEvaluator.scala      (the ONE shared pure traversal; abstract `decompose`)
│   ├── Evaluator.scala         (NbeEvaluator over ORE; hosts applyValue/force/renormalize/groundToSem + object helpers)
│   ├── SemExpressionEvaluator.scala  (NbeEvaluator over the checker's SemExpression output)
│   ├── MonomorphicEvaluator.scala    (NbeEvaluator over reduced MonomorphicExpression; DROPS erased type args)
│   ├── Quoter.scala            (strict SemValue → GroundValue read-back; fails loudly on stuck forms)
│   └── ConcreteNormalForm.scala (isConcrete + structural equality of concrete normal forms — the Eq[Type] leaf's and the
│                                effect intrinsics' frame keys' one comparison; never the Unifier, which solves metas)
├── check/
│   ├── Checker.scala           (bidirectional check/infer; definitional-equality core; builds 4 collaborators;
│   │                            inferSpine = whole-spine argument resolution; slot deferral is compile-track only)
│   ├── CheckIO.scala           (StateT[CompilerIO, CheckState, *])
│   ├── CheckState.scala        (gamma Γ + rho ρ, unifier, bindingCache, abilityResolutions, metaConstraints)
│   ├── SemExpression.scala     (checker output ADT; type slots are SemValue, not GroundValue)
│   ├── TypeStackLoop.scala     (signature kind-check + the post-drain resolution sequence + defaults + postcondition)
│   ├── PostDrainQuoter.scala   (the SOLE SemValue→GroundValue transition; reification gate; integerLiteral rewrite;
│   │                            reduceSourced)
│   ├── GuardDischargeResolver.scala (W2b effectful-signature guard discharge; the calculated-return half went
│   │                            with the `auto`/implicit-generics feature)
│   ├── MarkerGuardSignature.scala (ability-impl marker detection + the parameter-stripped guard view)
│   ├── GuardChannel.scala      (the Left/Right guard read-back protocol shared with the ability processor)
│   ├── CarrierKindChecker.scala (D8 HKT kind seeding + verification; records every HKT instantiation meta's kind.
│   │                            NOT effect machinery — a kind system, and soundness: measured, do not delete)
│   ├── AbilityResolver.scala   (ability-ref collection + resolve-abilities saturation pass: the two-site search
│   │                            at the ground ability-level arguments; an effect's begin with its handle's type)
│   ├── HandleHoleResolver.scala (the one effect rule: each hole takes the nearest handle whose entry answers)
│   ├── HandedBinders.scala     (a block's handle types, read off its callee's `MonomorphicValue.handed`)
│   └── Track.scala             (Runtime/Compiler strategy: platform + 4 per-track hooks, no platform match in the core)
├── channel/
│   ├── RefinementChannelProcessor.scala (post-pass flow analysis over MonomorphicValue: ^Meta transfers/merges,
│   │                                     ^Where precondition demands)
│   ├── RefinementTable.scala   (per-node meta values keyed by source position; read by reconcile/backend/LSP)
│   ├── MetaTransferAccountingProcessor.scala (R2/R3: a native leaf states its transfer, a bodied value may not)
│   ├── WovenRecheck.scala      (the re-check at the seam)
│   ├── WovenValue.scala        (the fact codegen consumes)
│   └── WovenValueProcessor.scala (the seam; demands MetaTransferAccounting, so an unstated leaf transfer blocks
│                                codegen)
├── unify/
│   ├── Unifier.scala           (pattern unification; pure definitional equality; higherKindedFlexes map; flushPostponed)
│   ├── UnifyResult.scala       (Unified / Contradiction)
│   ├── UnifyError.scala        (context + optional expected/actual)
│   └── SemValuePrinter.scala   (human-readable SemValue rendering for error messages; nothing to invert since
│                                effects v6 — a type prints as what the user wrote)
├── fact/
│   ├── GroundValue.scala       (output: Direct, Structure, Type)
│   ├── GroundValueRenderer.scala (the USER-FACING rendering — LSP hover + ability-demand diagnostics. ONE entry
│   │                            point: a type prints as `Name[arg, …]` / an arrow, as written.
│   │                            `Show[GroundValue]` stays the terse debug instance — never user-facing)
│   ├── MonomorphicValue.scala  (runtime output fact: signature + runtime, keyed by (vfqn, typeArgs))
│   ├── CompilerMonomorphicValue.scala (compiler-track output fact — a DISTINCT type; cannot name MonomorphicValue.Key)
│   ├── MonomorphicExpression.scala (output expression ADT; type slots are ground)
│   ├── NativeBinding.scala     (Platform-keyed: vfqn → SemValue for the evaluator)
│   ├── ContributedBinding.scala (Key(vfqn, label); NOT platform-keyed — one contribution serves both tracks)
│   ├── BindingContribution.scala (Leaf(SemValue) | Body(SaturatedValue))
│   └── BodyValueReferences.scala (memoized per-value body reference set for transitive binding prefetch)
└── processor/
    ├── MonomorphicTypeCheckProcessor.scala   (runtime entry point → TypeStackLoop, Track.Runtime)
    ├── CompilerMonomorphicTypeCheckProcessor.scala (compiler entry point → TypeStackLoop, Track.Compiler; native-leaf boundary)
    ├── SystemNativesProcessor.scala          (Function → VNative→VPi, Type → VType, Bool true/false constants,
    │                                          integerLiteral, the Eq[Type] equals leaf, + ListReductions/EffectIntrinsics)
    ├── ListReductions.scala                  (compile-time twins of the List leaves: a list is a prepend chain over empty)
    ├── EffectIntrinsics.scala                (docs/effects.md §3.4: the compile track's escapeIntrinsic/exitIntrinsic —
    │                                          a fresh frame with identity handed to the body, exited by identity, stuck
    │                                          through a frame not installed or on an undecided result — the guarded-return
    │                                          read's `discharging`, and the type-keyed withCell/read/write; settles what
    │                                          crosses a frame)
    ├── DataTypeNativesProcessor.scala        (body-less Type-qualified names → inert VTopDef; pool-guarded)
    ├── MatchNativesProcessor.scala           (handleCases/typeMatch impls → VNative; pool-guarded)
    ├── DeclaringPool.scala                   (quiet two-pool membership probe used by the two leaf contributors above)
    ├── UserValueNativesProcessor.scala       (thin BodyContributorProcessor over the runtime user pool)
    ├── CompilerNativesProcessor.scala        (compiler-pool body supplier; ability-using values → reduced Leaf)
    ├── BodyContributorProcessor.scala        (shared base for the two user-category body suppliers)
    ├── BodyValueReferencesProcessor.scala    (emits BodyValueReferences off the SaturatedValue body walk)
    ├── BindingMergerProcessor.scala          (SINGLE owner of NativeBinding; category precedence + dependency closure)
    ├── BindingClosure.scala                  (closes a selected Body over its deps; reifyingWrap via binderRoles)
    ├── ReducedBindingClosure.scala           (BindingClosure's twin over a reduced MonomorphicExpression)
    └── EscalatingReducer.scala               (the shared stuck-driven escalation: evaluate with raw bindings, and on a
                                               reducible stuck refetch only the blocking refs reduced-at-their-args, to
                                               a fixed point. Post-mono evaluation must never see a RAW body)
```

and its sibling tests under `lang/test/src/com/vanillasource/eliot/eliotc/monomorphize/`.

`monomorphize` is the sole monomorphic type-checker package. There is **one** evaluator traversal (`NbeEvaluator`)
and **one** semantic domain (`SemValue`) shared by types and values — never a second, weaker compile-time interpreter.

**Effects are almost not in this package.** The upstream `row/` phase does all the plumbing (`docs/effects.md` §3.2):
a handle is an ordinary value parameter of an ordinary type parameter, and an operation an ordinary ability method
dispatched by its handle's type. The checker holds exactly **one** effect rule, the hole (`check/HandleHoleResolver`),
and the read of a block's handle types beside it (`check/HandedBinders`); there is no carrier, no monad, and since D7
no post-mono effect verifier. **Adding another effect decision — a bind, a `pure`, a lift, a carrier, inference of a
binding — to the checker is a reversal of this design**; see the anti-patterns.

## How NbE works

ORE (operator-resolved expressions) are evaluated into `SemValue` (semantics) via Scala closures. Type equality
becomes structural equality of normal forms. Unification happens locally as the checker walks the term — no constraint
set, no worklist. All read-back to `GroundValue` is deferred to a single post-drain pass; the checker never converts to
`GroundValue` mid-flight, so there is **no silent `Type` fallback anywhere**.

### The semantic domain (`SemValue`)

| Variant | What it represents | Produced by |
|---|---|---|
| `VType` | The type of all types | Constant (`Type` native, checker defaults) |
| `VConst(ground)` | Non-function ground values: literals (`42`, `"hi"`), compile-time `Bool` (`Direct(true/false)`), the unit placeholder | Evaluator (literals), Bool natives, MatchNativesProcessor |
| `VLam(name, closure)` | Runtime lambda closure | Evaluator (always, for `FunctionLiteral`) |
| `VPi(domain, codomain)` | Function type (dependent or non-dependent) | Checker (checking a `FunctionLiteral` against `VType`), the `Function` native |
| `VNative(paramType, fire)` | Primitive awaiting a concrete argument; `fire` yields its own stuck form when the arg is not concrete | SystemNativesProcessor (Function, integerLiteral, the Eq[Type] equals leaf), MatchNativesProcessor (handleCases/typeMatch), StdlibNativesProcessor (arithmetic, Bool `fold`/`&&`/comparisons) |
| `VTopDef(fqn, cached, spine)` | Lazy top-level definition (cached=Some) **and** applied type/value constructor (cached=None) | BindingClosure (cached body), DataTypeNativesProcessor (body-less), the FQN-preserving missing-binding fallback |
| `VStuckNative(fqn, spine)` | A **non-injective** native application (`add(x,y)`, `min`, `&&`, `fold`) stuck on not-yet-concrete args | a `VNative`'s `fire` when an argument is abstract |
| `VFlex(id, spine)` | Unsolved metavariable, a *flex* (there is **no** `expected` field; not `VMeta`, which read as refinement meta) | Checker (`FlexStore.fresh`) |
| `VNeutral(head, spine)` | Stuck application on a rigid head. `NeutralHead` is **structural** (identity by constructor): `Param(level, name)` (bound vars), `Fresh(Origin.Unify/Quote, depth)` (binder-descending probes), `Reserved(Marker.BadApply/GuardProbe/Match)` (scope-less markers) | Evaluator (unresolved `ParameterReference` → `Param`), the `$bad-apply` fail-safe (`Reserved(BadApply)`) |

`VStuckNative` is deliberately **distinct from `VTopDef`** (D3): a native application is *not injective* (`add(1,3)` =
`add(2,2)`), so the unifier must never injectivity-decompose it and the quoter must fail loudly if one survives read-back.
`Evaluator.renormalize` re-fires a `VStuckNative` once its arguments become concrete.

### `applyValue` — the one application primitive (fail-safe fallback, F1)

`Evaluator.applyValue(f, x)` is exhaustive over every *applicable* head: `VLam`/`VPi` β-reduce, `VNative` fires (even
on a non-concrete argument — the native produces its own `VStuckNative`), and `VNeutral`/`VTopDef`/`VStuckNative`/`VFlex`
grow their spine. The two remaining heads — `VConst` and `VType` — are **not applicable**; reaching them can only happen
in an **ill-typed** program (one the checker has already, or is about to, reject). This case returns a **loud stuck
form** — a `VNeutral` on the reserved head `NeutralHead.Reserved(Marker.BadApply)` carrying the argument — *never* the old
identity-ish fallback that returned `x` (which silently collapsed `F[A]` to `A`). If a `$bad-apply` value survives to
read-back the strict `Quoter` fails it ("Cannot quote neutral value") ⤳ `PostDrainQuoter`'s "Cannot resolve type."; but a
program with a genuine type error aborts *before* quoting and reports that real diagnostic. Fail-safe: never a silent
wrong value. (Do not reintroduce the identity fallback — see anti-patterns.)

### Type/value constructors are body-less `VTopDef`s

`DataTypeNativesProcessor` binds every body-less `Type`-qualified name (an abstract `type`, or a `data` type's
constructor) to `VTopDef(fqn, None, SNil)`. Applying type args just grows its spine. So `Box[Int]` is
`VTopDef(BoxFQN, None, [VTopDef(IntFQN, …)])` — **not** a `VConst(Structure)`. `Structure(...)` only appears after
read-back. This is load-bearing: `typeMatch`/`handleCases` dispatch on the `VTopDef(head, None, spine)` shape, and the
unifier's same-FQN case operates on these `VTopDef`s.

### VLam vs VPi — who produces what

The single most important invariant:

- **The evaluator always produces `VLam` for `FunctionLiteral`** (`NbeEvaluator.eval`), regardless of position.
- **The Checker produces `VPi`** when checking a `FunctionLiteral` against `VType` (the term IS a function type).
- **The Checker produces `VLam`** when checking a `FunctionLiteral` against `VPi(d, c)` (the term is a runtime lambda).
- **The `Function` native produces `VPi`.** `Function[A, B]` = `apply(apply(functionNative, A), B)` → `VPi(A, _ => B)`.

Both are applicable. `VConst`/`VStuckNative` never represent function types.

### Env, closures, and name lookup

`Env` is `Vector[SemValue]` (indexed by de Bruijn level) plus a parallel `names` vector. The evaluator resolves a
`ParameterReference` by **name** (`Env.lookupByName`, last-bound-wins), *not* by a pre-computed level — there is no
`nameLevels` table on `CheckState`. Closures are native Scala functions capturing the current env and body; no ORE
substitution ever happens. `Spine` is a reversed cons list (`SNil | SApp(tail, head)`) for O(1) append.

**Γ and ρ are separate (the textbook NbE-checker shape).** `CheckState` holds two `Env`s, grown in lockstep:

- **`gamma` (Γ)** — name → its **type**. Read only by `Checker.infer`'s `ParameterReference` (`state.gamma.lookupByName`).
- **`rho` (ρ)** — name → its **value**, the env the evaluator consumes (`makeEvaluator.eval(rho, …)`). An erased
  type parameter binds its `groundToSem` value; a runtime value parameter binds a **fresh neutral**
  (`paramNeutral`, `Param(rho.level, name)`) standing for its unknown runtime value; a peeled instantiation meta binds
  the meta.

Three `bind*` methods keep them in sync: `bindValueParam(name, type)` (Γ=type, ρ=neutral — runtime value params),
`bindTypeStackParam(name, type, value)` (Γ=type, ρ=value — erased explicit type args, both computed from the ground arg
in `applyTypeArgs`), `bindTypeParam(name, meta)` (Γ=ρ=meta — peeled leftover type params). `Checker.check`
(`FunctionLiteral` vs `VPi`) binds the neutral in ρ and checks the body against **`codomain(paramNeutral)`** — genuine
dependent Π, never `codomain(paramType)`. (Every current-Eliot `VPi` codomain is constant, so the two agree today; the
neutral is correct once runtime-value-dependent types land.) `monoEnv` (the reification env in `PostDrainQuoter`) is
just ρ captured before the body check; `PostDrainQuoter.isRuntimeParam` reads runtime-ness off ρ (a name bound to a
neutral) rather than threading a separate `runtimeParams` set.

### Three evaluators, one traversal

`NbeEvaluator[E]` is the single pure, syntax-directed traversal. Its subclasses differ **only** in `decompose` (how a
node projects onto `NbeEvaluator.Term`):

- `Evaluator` (over ORE) — re-evaluates ORE type-argument sub-terms.
- `SemExpressionEvaluator` (over the checker's `SemExpression`) — reads the already-evaluated `SemValue` type args.
- `MonomorphicEvaluator` (over a reduced `MonomorphicExpression`) — **drops** the erased type arguments of a value
  reference (applying them would mis-bind a value parameter to an erased type arg).

Missing-binding fallback for a **value reference** is an FQN-preserving stuck `VTopDef(vfqn, None, SNil)` — **not** a
`VNeutral` (which would drop the FQN and corrupt read-back/codegen). A missing **parameter** reference falls back to
`VNeutral`.

### How computation runs

`force(v, flexStore)` walks solved metas and unfolds `VTopDef` when the cached body is present.
`renormalize(v, flexStore, lookupNative, deep)` additionally re-fires `VStuckNative`s whose args have concretised (and,
with `deep=true`, descends under `VPi` binders — used **only** post-drain at quote time, where every meta is solved).
`Checker.ensureBinding` prefetches transitively through the memoized `BodyValueReferences` fact (walked once per value),
so a native reached only through a bodied helper is already in the flat `bindingCache` the re-fire lookup consults.

## Pipeline

### Entry points (two tracks)

- `MonomorphicTypeCheckProcessor` → `TypeStackLoop.process(..., track = Track.Runtime)` → `MonomorphicValue`.
- `CompilerMonomorphicTypeCheckProcessor` → the **same** `TypeStackLoop.process(..., track = Track.Compiler)` →
  `CompilerMonomorphicValue` (a *distinct* fact type, so the compiler track cannot even name `MonomorphicValue.Key`;
  the `compiler-mono → runtime-mono` edge is impossible by construction — acyclic).

Both entry points first strip an ability-implementation *marker*'s pattern arguments
(`MarkerGuardSignature.strippedForGuard`) — a marker is monomorphized only to discharge its `where` guard, and its
pattern arguments are not real value parameters (they need not be of kind `Type`).

`NativeBinding`, `SaturatedValue`, `OperatorResolvedValue`, `UnifiedModuleNames`, `ModuleConstructors`, etc. are all
`Platform`-keyed (Runtime / Compiler). Each track unifies base + user program + its own layer independently.

### NativeBinding injection (ContributedBinding + BindingMergerProcessor)

`NativeBinding.Key(vfqn, platform)` maps a value FQN to its evaluator `SemValue`, produced by a **single owner**,
`BindingMergerProcessor`. The flow:

1. Every supplier emits a **total** `ContributedBinding` fact under its `label` — a `BindingContribution` when it defines
   a reduction for `vfqn`, else `None` (never declines). `ContributedBinding.Key(vfqn, label)` is **not** platform-keyed:
   one contribution serves both track merges. Labels:
   - **native category** (`langNativeLabels` = `system` / `datatype` / `match` / `compiler`, plus any `extraNativeLabelsKey`
     labels added by layers, e.g. stdlib's arithmetic/Bool natives):
     - `SystemNativesProcessor` (`system`) — `Function` → `VNative`→`VPi`; `Type` → `VType`; the compile-time `Bool`
       constants `true`/`false` (`VConst(Direct(Boolean))`); the value-position literal protocol `integerLiteral[V]`
       (reduces to `V`, so a literal feeding a compile-time computation — e.g. a `^Where` bound — evaluates); and the
       `Eq[Type]::equals` structural-equality leaf, bound to the impl-method FQN through the ability-impl marker. The
       `Bool` eliminator `fold` and the arithmetic natives are *stdlib*-owned (`StdlibNativesProcessor`) — the compiler
       special-cases nothing about them.
     - `DataTypeNativesProcessor` (`datatype`) — body-less `Type`-qualified constructors → `VTopDef(fqn, None, SNil)`,
       excluding the system-owned `Function`/`Type`. **Pool-guarded** (see `DeclaringPool`).
     - `MatchNativesProcessor` (`match`) — the `PatternMatch.handleCases` / `TypeMatch.typeMatch` impl FQNs → `VNative`s
       that reduce a concrete `VTopDef(ctor, None, spine)` scrutinee during checking. **Pool-guarded**.
     - `CompilerNativesProcessor` (`compiler`) — the compiler platform's Eliot-bodied reductions (the `add` pattern:
       ranked as a native so its reduction wins for *checking* while the runtime body is still what codegen reads). A
       compiler-only value whose checking body performs ability dispatch is contributed as the **reduced**
       `CompilerMonomorphicValue` body wrapped by `ReducedBindingClosure` (a `Leaf`), because its raw body would stall on
       the abstract ability method.
   - **user category** (`userLabel`): `UserValueNativesProcessor`, a thin `BodyContributorProcessor` subclass over the
     runtime pool. A body-ful value contributes a `BindingContribution.Body(saturated)`; a body-less one contributes
     `None` (its checking impl is a native, or the evaluator's stuck fallback — never an empty binding that would shadow a
     reducing native).
2. `BindingMergerProcessor` selects by **category precedence**: the first native `Some`, else — *only on the Runtime
   platform* — the first user `Some`, else abort (no binding; the evaluator stalls at the use site). The Compiler track
   never consults the user pool (native-leaf boundary). For a selected `Body` it runs `BindingClosure`, closing the body
   over its dependencies' `NativeBinding`s. The dependency walk lives **here** (the merger is the sole owner of the
   `NativeBinding` recursion); suppliers never read back the fact they feed. There is no mutual-recursion guard: a body
   is bound only once the recursion gate admitted it, so a chain of bindings cannot cycle, and a cycle anyway is the
   engine's refusal, which fails the build (`docs/compiler-performance.md` §15.2). Reified generic binders are wrapped as leading
   lambdas via `BindingClosure.reifyingWrap`, driven by the precomputed `SaturatedValue.binderRoles` (D6).

### TypeStackLoop — the signature kind-check

`TypeStackLoop.processIO` (`Checker` and `TypeStackLoop` hold a **`Track`**, not a bare `Platform` — see below; there
is **no `platform match` anywhere in the checking core**, only `track.<hook>` dispatch):

1. `walkTypeStack` — **derive the kind** from the signature's generic binders (`SignatureView.of(sig).binders` folded
   into `Function[K1,…,Type]`; a binderless signature has kind `Type`, checked directly), then fold `[kind, signature]`
   with `expectedType = VType`: for each level `check(level, expected)` then `expected = eval(level)`. **The fold body is
   identical for every level** — no "is this a type parameter?" branch. Generics emerge from `FunctionLiteral` levels
   checked against `VPi` kinds from above.
2. `applyTypeArgs` — apply explicit ground type args, every argument injected through the **one canonical
   `groundToSem` conversion** (a type or data value ⟹ its applicable constructor `VTopDef`, a `Direct` literal ⟹
   `VConst`) — the same form is applied to the signature closure and bound into ρ, so no ground value ever has two
   semantic forms. Over-application records one "Too many type arguments." error.
3. `instantiateRemaining` — peel leftover `VLam` closures (phantom / implicit type params) with fresh metas.
4. `settleAtRead` — settle the return position at the read (stateless, shape-driven): the **runtime** track
   discharges or defers a guard off the re-inflated ground leaf (`GuardDischargeResolver.dischargeGuardedSignature`,
   recognising the guard by its `Right`/`Left` shape, not a flag, and never for an ability-impl marker, whose verdict
   must survive undischarged for the ability processor to interpret per candidate); the **compiler** track, as the
   guard's *producer*, leaves it undischarged.
5. `check` the runtime body against the signature.
6. `runPostDrainResolution` (D1, below), report unifier errors, abort before quoting if any error exists.
7. Fetch `track.implBindings` (compiler-only impl-body fetch); for a body-less *guarded marker*, reduce the guard's
   bodied sub-values per instantiation (`reduceGuardSubValues` via `ReducedBindingClosure.reduceInstance`) and
   re-evaluate the guard return against them (ability-guards Stage 4 — so a guard reaching its ability *through* an
   operator, `where E1 != E2` via `!=`, still collapses to a concrete verdict). Then read back via `PostDrainQuoter`
   through `track.readBackBody` — the compiler track **reduces** the body (`reduceSourced`), the runtime track
   structurally quotes it (`quoteSourced`).

### Track — the per-track strategy (no `platform match` in the core)

`check/Track` is a sealed trait with two case objects, `Track.Runtime` / `Track.Compiler`, each carrying its `platform:
Platform` plus the **two** places the two tracks genuinely differ:

| Hook | Runtime | Compiler |
|---|---|---|
| `implBindings` | empty | fetch each drain-resolved ability impl's `NativeBinding` body |
| `readBackBody` | `quoter.quoteSourced` | `quoter.reduceSourced` |

One `track.platform match` remains in `TypeStackLoop.settleAtRead`, deliberately: the two sides are producer and
consumer of the same guard channel rather than two strategies for one job.

`TypeStackLoop` / `Checker` take the `Track`; **fact keys read `track.platform`** (`Checker` keeps a private `val
platform = track.platform`, so the collaborators are still constructed with a bare `Platform`). The two entry
processors pass `Track.Runtime` / `Track.Compiler`. The `resolveAbility` seam is **two-arg** `(ValueFQN,
Seq[GroundValue]) => …`. `reduceSourced` physically lives in `PostDrainQuoter` (a read-back variant sharing its
machinery; `readBackBody` dispatches to it).

### Post-drain resolution (D1)

A fixed sequence, `TypeStackLoop.runPostDrainResolution` (no external design doc — it is described here and in its doc
comments):

- **Saturation** (`resolveAbilitiesToFixedPoint`): drain the unifier — so the resolver sees every solution the previous
  round injected — then try to resolve the still-unresolved ability references; loop while any reference newly resolves.
- **Finalization** (once): carrier-kind verification (`CarrierKindChecker.verifyCarrierKinds`) and the
  calculated-return fail-safe. A finalization step can commit new solutions (the kind check unifies a solution's kind
  against its expectation), so the runner drains once more before defaulting.
- **Finalizer** (`defaultUnsolvedFlexes`): every still-unsolved meta defaults to `VType` — what remains unsolved after
  the fixed point is an unconstrained (phantom) instantiation.
- **Postponement flush** (`Unifier.flushPostponed`): any constraint still postponed after the finalizer is an equality
  obligation the check never discharged — flushed to a hard mismatch error (after a triage re-drain; the one exemption
  is a `$bad-apply`-headed constraint, owned by a more precise fail-safe).
- **Postcondition** (`assertEveryFlexResolved`): every meta is solved — the compiler-bug backstop, `compilerAbort`ing
  on an internal invariant violation.

### Bidirectional checker & its collaborators

`Checker` is the **definitional-equality core only**: `check`/`infer`/`inferSpine`/`applyInferred`/`peelLams`/
`instantiatePolymorphic` plus the shared primitives (`force`, `freshFlex`, `doUnify`, `evalExpr`, `ensureBinding`,
`prefetchBindings`). Application checking is **spine-level** (`inferSpine`): the whole curried spine is decomposed at
the root and each argument slot runs the resolution ladder left to right. The check-mode **resolution ladder** per slot
is just **instantiate → unify → mismatch**, with no arm between the two. There is no bind arm either — a slot that must sequence never needed one, the `row/` phase having already written every binding
from the declaration (§1 rule 4).

No slot is deferred past its spine on either track: the compile track's deferred slots and `resolveDeferredSlot` were
deleted (4bb25c00). Non-equality concerns live in collaborator modules, each
constructed at the top of `Checker` with **exactly the checker primitives it needs** (that narrow surface is the module
boundary), and invoked from named hook points:

| Collaborator — field | Concern | Hook points |
|---|---|---|
| `check/GuardDischargeResolver` — `checker.guards` (W2b) | effectful-guard discharge (the calculated-return half went with the `auto`/implicit-generics feature) | `Checker.infer`/`applyInferred`; `TypeStackLoop.settleAtRead` |
| `check/CarrierKindChecker` — `checker.carriers` (D8) | HKT kind seeding + verification | `Checker.instantiatePolymorphic` → `recordCarrierFlexes`; `TypeStackLoop` post-drain → `verifyCarrierKinds` |
| `check/AbilityResolver` — `checker.abilityResolver` | ability-ref collection + the `resolve-abilities` saturation pass (resolve each ability-qualified ref to its impl) | `TypeStackLoop.processIO` → `collectAbilityRefs`; `TypeStackLoop` post-drain → `resolveAbilities` |

Do not reintroduce a lift, a `pure`, or an `Id` decision here (the deleted `check/EffectLifter`) — that is a reversal
of the design, not a fix.

Per-meta bookkeeping (the binder's kind + its call-site context) lives in the **single** `higherKindedFlexes` map on
the `Unifier`; the collaborators own the *algorithm*, not a private store.

### Higher-kinded meta bookkeeping (`Unifier.higherKindedFlexes`)

One `Map[Int, (SemValue, Sourced[String])]` on the `Unifier`, seeded by `CarrierKindChecker.recordCarrierFlexes` for
*higher-kinded* instantiation metas (`[F[_]]`) only: the binder's expected kind plus a call-site context, verified
post-drain (a binder solved to a value of the wrong kind is rejected rather than silently accepted). Its one reader
is `verifyCarrierKinds`, for the *kind* check alone, which is soundness and not effect machinery.

Every meta without an entry is plain: solved by ordinary unification, else defaulted to `VType` by
`defaultUnsolvedFlexes`.

### Guard discharge (W2b) — `GuardDischargeResolver`

A return-type expression may `raise` or `abort`: the guard read runs it inside the compile track's `runThrow` (an
uncovered `abort` as `… else raise("…")`, `WellKnownTypes.guardDischargers`), so its verdict is an `Either[String, _]`
(the compiler is the handler). Three hook points recognise that verdict's `Right`/`Left` **by FQN**
(`WellKnownTypes.eitherFQN` / `rightFQN` / `leftFQN`) and reduce `fold`/`foldEither` via the merged `NativeBinding`:
`isGuardCarrier` (the *kind* position — accept an `Either[..]`- or `Bool`-valued return where a bare `Type` is
expected), `dischargeGuardedSignature` (the *callee* side — `Right(t)` ⤳ the published type `t`, `Left(msg)` ⤳ a
diagnostic, an abstract-bounds guard deferred to the body via a return meta), and `dischargeGuardedReturn` (the
*applied / by-name read* sides). The `Left`/`Right` payload convention and the fallback rejection message live in
`GuardChannel`, shared with the ability processor's `where`-verdict interpreter so the two cannot drift. The compiler
track skips discharge (it is the *producer*: `pure(A)` ⤳ `Right(A)`, publishing the undischarged `Either` signature for
a consumer to discharge); the runtime track also skips it for an ability-impl *marker* (`MarkerGuardSignature`), whose
verdict the ability processor interprets per candidate.

### The refinement channel (`channel/`) — downstream of typing

`RefinementChannelProcessor` is a **post-pass** over each runtime `MonomorphicValue` (producing the `RefinementTable`
fact), not part of checking: it walks the fully-ground body bottom-up and computes each node's **meta value** (an
opaque domain `GroundValue`, e.g. `Int$Meta(Interval(lo, hi))`) by flow. A literal seeds via the `integerLiteral^Meta`
companion; a call whose callee declares a `^Meta` companion computes a **transfer** (the `Numeric[Int]` `add^Meta`) or a **merge**
(`fold^Meta`, `Meta.join` over the arms) by reducing that companion through the one NbE evaluator — no leaf and no
branch construct is named, the companion is the sole recognition point. A def's `where` precondition (its `^Where`
companion) is demanded at every full call over the arguments' metas — an unknown (⊤) range or a `false` verdict is a
hard use-site error. Everything else is ⊤ (no entry; bignum layout — sound, wide, never wrong); lambda bodies are
walked (so nested `where` demands fire) but never record. Consumers: the reconcile pass stamps the table onto the body,
the JVM backend decodes machine widths from it, the LSP hover shows value ranges. Refinements flow strictly
**downstream** of type formation — they never feed back into the checker or unifier.

### Effects in the checker — the hole, and nothing else

Authoritative design: `docs/effects.md` (§3 the mechanism, §3.3 the hole rule, §4 the scope check). Effects are
capability passing: before monomorphization the `row` phase (`row/BindingWriter`) turns every use into plumbing — a
`HandleHole` at every handle position, a `with` into a `let` of its handle, a code parameter's actual into a block — and
decides nothing.

- **The checker holds exactly one effect rule, the hole** (`check/HandleHoleResolver`): each hole takes the nearest
  candidate whose entry is definitionally equal after evaluation. Beside it, `check/HandedBinders` reads a block's
  handle types off its callee. An operation is an ordinary ability method dispatched by its handle's type
  (`AbilityResolver`); an ability (`~`) is the two-site search at the reference's own type arguments — there is no
  binding slot and no `Default`.
- **Verification is the scope check, in two halves**: by name in the `row` walk, by entry in the hole rule. A post-mono
  verifier was retired by D7 (2026-09-10); do not grow one back.
- **There is nothing to invert when printing.** `fact/GroundValueRenderer` prints what the user wrote, and LSP hover
  leaves handles out (`TypeHintIndex`).

### PostDrainQuoter — the sole SemValue→GroundValue transition

Read-back happens exactly once, post-drain, in `PostDrainQuoter`:

- `quoteSem` deeply renormalises (so a stuck native in an intermediate `VPi` codomain re-fires) then calls the strict
  `Quoter.quote`. On a `Left` it aborts with `"Cannot resolve type."` (detail = the quoter message) — **no** silent
  `Type` fallback.
- The strict `Quoter` succeeds only on `VType`, `VConst`, `VPi` (→ Function `Structure`), and body-less
  `VTopDef(fqn, None, spine)` (→ `Structure`). It **fails loudly** on `VNeutral`, `VFlex`, `VLam`, `VNative`,
  `VStuckNative`, and an unapplied cached `VTopDef`.
- `PostDrainQuoter` also hosts the **compile→runtime reification gate** (materialising erased-parameter-determined
  sub-terms into literal/constructor trees), the **`integerLiteral[V]` quote-time rewrite** (a value-position literal
  reads back as a plain `IntegerLiteral` node, so no `integerLiteral` reference survives into the monomorphic tree),
  and the compiler track's `reduceSourced` (evaluate + read back the reduced compile-time body, folding resolved
  ability impls in).

### Unifier — pure definitional equality

Pattern unification on `SemValue`s with a `postponed` queue (`drain()` retries until stable):

1. Force both sides. `VType`~`VType` ✓. `VConst`~`VConst` → structural `GroundValue` equality.
2. `VPi`/`VLam` → extend with a fresh rigid var, unify under the binder. Eta: `VLam` vs other → unify `c(fresh)` against
   `apply(other, fresh)`.
3. `VNeutral`~`VNeutral` same head → zip spines. `VTopDef`~`VTopDef` same FQN → zip spines (definitional equality: a
   constructor applied to differing arguments is rejected).
4. `VStuckNative`~`VStuckNative` same FQN → zip spines; it is **never** injectivity-decomposed against a meta (non-injective).
5. `VFlex` → pattern rule: empty spine → solve directly (with an **occurs-check**); non-empty spine → `tryDecomposeApplied`
   (injectivity decomposition against a *rigid* `VTopDef(None)` / `VNeutral` head, equal-arity or partial-application),
   else postpone. A higher-kinded meta postponed against a non-rigid `VPi` (`?F[A,B] ~ Function[A,B]`) is **deliberately
   not decomposed** — `CarrierKindChecker` documents this as higher-order unification the pattern unifier cannot solve
   (see the diagnosing note on the two-arg HKT-over-Function limitation). **Flex-flex `?F[a..] ~ ?B`** (applied meta vs a
   *bare, unsolved* meta) solves the bare meta **in either orientation** — `?B := ?F[a..]` — rather than postponing when
   the applied side is on the left, so the outcome does not depend on which side a constraint is written. Pinned by
   `unify/FlexApplicationUnifyTest`.

`unify` is **pure definitional equality**: no assignability, no widening, no `refinements` map (`Int` is nullary;
value ranges ride the `channel/` post-pass, strictly downstream of typing), and no flex-flex `?F[X] ~ ?G[Y]`
decomposition rule.

`flushPostponed` runs after the finalizer: still-postponed constraints become hard mismatch errors (fail-safe — see the
post-drain sequence above). Pinned by `unify/PostponedFlushTest`.

### Two-pool membership guards (leaf contributors)

`ContributedBinding` is not platform-keyed, but a leaf contributor must read a *platform-keyed* source fact (a value, its
constructors) to build a reduction. Requesting that fact on a fixed pool a name is absent from would trip
`UnifiedModuleValueProcessor`'s "Could not find" build error. So `DataTypeNativesProcessor` and `MatchNativesProcessor`
ask `DeclaringPool.of(vfqn)` first — a quiet `UnifiedModuleNames` (name-set) probe that returns the pool a name is
declared in (preferring `Runtime`, falling back to `Compiler`, else `None`) — and read the source fact only on that
pool. `CompilerNativesProcessor.inCompilerPool` and `CompilerMonomorphicTypeCheckProcessor.inRuntimePool` use the same
quiet-probe pattern.

## The hard rules

1. **No concept of "generic parameters" in the checker's fold.** `TypeStackLoop`/`Checker` never classify a signature's
   binders as "type params" vs "value" to drive a *typing or equality* decision; the fold over `[kind, signature]` is
   uniform (no `TypeParameterAnalysis`). *However*, sanctioned **peripheral** readers legitimately read binder structure
   through `SignatureView` — do **not** mistake them for a violation:
   `walkTypeStack` itself (reconstructs the kind `Function[K1,…,Type]` from the signature's binders to kind-check
   against — the kind is a projection of the signature, never stored),
   `CarrierKindChecker.recordCarrierFlexes` (seeds carrier kinds off the referenced value's `SignatureView`),
   `AbilityResolver.abilityArity` (reads an ability-marker's binder count for impl queries),
   and `SaturatedValue.binderRoles` feeding `BindingClosure.reifyingWrap` (which leading binders a body reifies).
   None of these drives a definitional-equality decision.
2. **The evaluator produces VLam; the Checker produces VPi.** Never produce `VPi` in the evaluator. The `Function` native
   is a `VNative` that fires to `VPi`.
3. **No ORE rewriting.** ORE is read once into `SemValue` and forgotten. All substitution is closure application.
4. **No constraint set.** Unification is immediate and local; the only deferral is the unifier's `postponed` queue.
5. **NativeBinding pre-fetching.** The evaluator is synchronous and pure. All bindings are prefetched into
   `CheckState.bindingCache` (via `prefetchBindings`/`ensureBodyBindings`/`BindingClosure.collectBindings`) before
   evaluation.
6. **State via `CheckIO`.** State is threaded through `StateT[CompilerIO, CheckState, *]` — `get`/`modify`/`inspect`,
   not in-place mutation. Bind a parameter with `state.bindValueParam(name, type)` (runtime param: Γ=type, ρ=neutral),
   `state.bindTypeStackParam(name, type, value)` (erased type-stack param: Γ=type, ρ=value), or
   `state.bindTypeParam(name, meta)` (peeled type param: Γ=ρ=meta). Never overload one env for type and value — Γ
   (`gamma`) and ρ (`rho`) are separate.
7. **No silent read-back fallback.** `applyValue` on a non-applicable head yields a loud `$bad-apply` stuck neutral; the
   quoter fails loudly on every stuck form; still-postponed constraints are flushed to hard errors (`flushPostponed`).
   Nowhere does a stuck value silently become `Type`.
8. **Two acyclic tracks.** The compiler track produces `CompilerMonomorphicValue` and cannot name `MonomorphicValue.Key`.
   Its `fetchBinding` enforces the native-leaf boundary: a runtime-concrete + compiler-absent name is a hard error
   ("Cannot use runtime-only value … at compile time"), never a fall-through to the runtime body.

## Diagnosing failures

- **"Type mismatch" on a correct program.** Check every referenced value has a `NativeBinding` (right platform). A
  missing *value* binding evaluates to a stuck `VTopDef` (FQN-preserving); a missing *parameter* to a `VNeutral`; either
  fails to unify against a concrete expected type. Add the missing case to the relevant supplier / prefetch.
- **"Cannot resolve type."** A stuck `SemValue` survived to `PostDrainQuoter.quoteSem`. Inspect *which* stuck form
  (`Quoter`'s `Left` message names it): an unsolved `VFlex` (a constraint that should have solved postponed and was
  dropped), a `VNeutral` (an unresolved parameter, or the `$bad-apply` fail-safe = an argument applied to a
  non-applicable head in an ill-typed program), a `VLam` (a polymorphic value not fully instantiated —
  `instantiateRemaining` should peel it), or a `VStuckNative` (see next).
- **"Cannot quote stuck native application."** A native's arguments stayed abstract at read-back. Check that
  `Evaluator.renormalize` (with the checker's binding cache as `lookupNative`) re-fires it once its metas solve, and that
  the native's `fire` produces a `VStuckNative` (not `false`) on non-concrete args.
- **Two-arg HKT carrier over `Function`** (`?F[A,B] ~ Function[A,B]`): the `Function` carrier normalises to a `VPi`,
  which the unifier deliberately does not decompose, so `?F` stays unsolved, defaults to `VType`, and the later `F[A,B]`
  applies args to `VType` → the loud `$bad-apply` fallback → "Cannot resolve type." This is a **known inference
  limitation** (surfaced, not silently miscompiled — a `data`-carrier `?F := Box` *does* decompose). See
  `MonomorphicTypeCheckTest`'s "surface (not silently miscompile) a two-arg HKT carrier over the Function type".
- **Infinite loop / stack overflow.** Likely `force` unfolding a self-referential `VTopDef` whose spine never grows.
  A fact-level cycle is refused by the engine (no processor sees the request chain); `force` itself has no depth limit, so ensure
  it only unfolds when the cached body is `Some`. The unifier's occurs-check prevents cyclic meta solutions.
- **`data` type declared only in the compiler pool has no constructor/match native.** Check `DeclaringPool.of` finds the
  compiler pool (name must be in the compiler `UnifiedModuleNames`).
- **A `where` precondition silently not demanded.** The demand fires only at a *full* call (`MonomorphicValue.
  naturalArity`) of a callee whose module declares the `^Where` companion; check `MetaWhereDesugarer` emitted it and the
  compiler pool sees the module.

## Anti-patterns (reject in review)

- **Classifying a signature's binders as type-params-vs-value to drive a typing or equality decision.** The fold is
  uniform. (The sanctioned peripheral `SignatureView` readers in Hard Rule 1 — including `walkTypeStack`'s kind
  derivation — are the *only* binder-structure reads, and none drives equality.)
- **Producing `VPi` in the evaluator**, or `VLam` in the Checker when the expected type is `VType`.
- **ORE substitution inside monomorphize.** All binding is closure capture in Scala.
- **Calling `CompilerIO` from an evaluator.** The evaluators are synchronous and pure; prefetch bindings first.
- **Adding a constraint list / worklist.** Unification is immediate; the only deferral is the unifier's `postponed` queue.
- **Eagerly allocating metas for type parameters at `ValueReference`.** Implicit instantiation is driven lazily by
  `FunctionApplication` / `instantiatePolymorphic` / `instantiateRemaining`.
- **Using `Map[String, SemValue]` for the environment hot path.** Env is `Vector[SemValue]`; the evaluator resolves
  parameters by name via `Env.lookupByName`.
- **Re-introducing assignability / widening / a `refinements` map into `unify`.** `unify` is pure definitional equality.
  Value-range refinements live in the `channel/` post-pass, strictly *downstream* of typing — never in the unifier or
  the checker.
- **Making any effect decision inside the checker** — a bind, a `pure`, a lift, a carrier, a binding. The write is a
  *desugar*, upstream, decided from declarations alone (`row/BindingWriter`, §1 rule 4: a position's declared row says
  whether the walk crosses into it). Deciding it per concrete instantiation in check mode is the arrangement this
  design replaced; re-adding a slot arm that inserts a node starts that reversal again. If the write cannot decide a
  position, the gap is in the **declarations** (§3.2), not in the checker.
- **Re-inlining a non-equality side-car into `Checker`.** A new lattice relation / inference back-edge / kind rule gets
  its *own* collaborator constructed with injected primitives — never grown into `Checker`, never folded into `unify`.
- **Deciding effect-ness by name or shape.** There is exactly one reading, and it is the callee's **declared row**
  (`EffectRow.returnEffects`) — which for an `effect`'s member is what membership recorded. Keying on a name, on a
  higher-kinded binder, or on "has an instance" miscompiled in both directions under the carrier and is prohibited
  now that there is nothing left to recognise.
- **Re-introducing inference of an effect binding** — a metavariable, a join/lattice, an eager pin, an
  ordering-sensitive slot decision, or a sum over a monomorphized sub-graph. The `row/` phase writes every binding, so
  there is nothing to solve; first-contact carrier unification was the historical theft/junk-ground bug class and its
  whole apparatus was deleted, not disabled.
- **Special-casing a concrete type (e.g. `Int`) in the checker/unifier.** Recognise the *ability protocol* by name
  (`eitherFQN`, `PatternMatch`/`TypeMatch`, the `^Meta`/`^Where` companion namespaces), never the type.
- **Returning `false` (not a `VStuckNative`) from a native on non-concrete args.** `&&`/`fold`/arithmetic must stay stuck
  so the unifier still solves metavariables; `false` would wrongly reject generic/open comparisons.
- **Injectivity-decomposing a `VStuckNative` against a meta.** A native application is non-injective; it must postpone.
- **Re-introducing a silent fallback in `applyValue` or any read-back path.** Applying an argument to a non-applicable
  head must yield the loud `$bad-apply` stuck form; stuck values must fail the quoter loudly; unsolved metas default to
  `VType` only through the finalizer, with the postponement flush as the equality backstop.
- **Reading a runtime-pool fact in a leaf contributor without a membership probe.** A `ContributedBinding` is not
  platform-keyed; probe `DeclaringPool` / `UnifiedModuleNames` before requesting a platform-keyed value, or a
  compiler-pool-only name pollutes the build with a spurious "Could not find" error.
- **Naming `MonomorphicValue.Key` from the compiler track.** It would create the forbidden `compiler-mono → runtime-mono`
  edge; the compiler track produces `CompilerMonomorphicValue` only.

## Testing

Tests live under `lang/test/src/com/vanillasource/eliot/eliotc/monomorphize/`:

- `processor/MonomorphicTypeCheckTest.scala` — the main end-to-end checker cases (inference, HKT decomposition, HKT
  binder kinds, dependent bounds, calculated returns W3/W4, guard signatures). **Extend these named end-to-end cases** rather
  than mock-unit-testing a collaborator (the collaborators were pure moves; their behaviour is pinned here).
- `processor/MonomorphicTypeCheckProcessorTest.scala` — processor-level cases.
- `processor/MatchNativesProcessorTest.scala` — `match` reduction via `handleCases`/`typeMatch` natives.
- `processor/MonomorphizationVersioningTest.scala` — per-instantiation keying.
- `processor/ReificationTest.scala` — the compile→runtime reification gate.
- `processor/ComputedTypeArgumentReadbackTest.scala` — computed type-argument read-back.
- `processor/EqOperatorResolutionTest.scala`, `processor/EqTypeLeafTest.scala` — the `Eq[Type]` structural-equality leaf
  and its resolution through `==`/`!=`.
- `processor/CompilerMonomorphicTypeCheckProcessorTest.scala`, `processor/CompilerNativesProcessorTest.scala`,
  `processor/CompilerNativeLeafBoundaryTest.scala`, `processor/CompilerMarkerNamesTest.scala`,
  `processor/CompilerAbilityResolutionTest.scala` — the compiler-as-platform track (register a `Platform.Compiler`
  `PathScan` for the compiler pool; see `CompilerNativesProcessorTest`'s `twoPoolFacts` template).
- `processor/CompilerOnlyDataNativesTest.scala` — the `DeclaringPool` compiler-pool fallback for the datatype native.
- `check/AbilityResolverBindingTest.scala`, `check/AbilityResolutionKeyTest.scala` — ability resolution and keying a
  resolution.
- `channel/RefinementChannelProcessorTest.scala` — the refinement channel's flow analysis and `^Where` demands.
- `eval/EvaluatorApplyValueTest.scala` — the F1 loud `$bad-apply` fallback shape + quoter failure.
- `unify/UnifyResultTest.scala`, `unify/OccursCheckTest.scala`, `unify/StuckNativeUnifyTest.scala`,
  `unify/FlexApplicationUnifyTest.scala`, `unify/PostponedFlushTest.scala`, `unify/HigherKindedFlexTest.scala` — pure
  unifier unit tests (construct `SemValue`s directly; `AnyFlatSpec`).

The write side lives with its own phase, not here: the effect suites under `jvm/test/.../` compile and run real
programs, since the write's output is only meaningful end-to-end.

Most processor tests run the whole pipeline via `ProcessorTest(LangProcessors()*)` and construct source text inline.
Follow the project testing conventions: single-line asserts, assert the `Seq` itself, prefer `.asserting(_ ...)`.

```bash
./mill lang.test 2>&1 | grep -v DEBUG | grep "MonomorphicTypeCheck"
```

## Facts and keys

- `MonomorphicValue.Key(vfqn, typeArguments)` / `CompilerMonomorphicValue.Key(vfqn, typeArguments)` — composite;
  same `vfqn` + different type args → different specialization. The compiler key is a *distinct* type (acyclicity).
- `MonomorphicValue` fields: `vfqn`, `typeArguments`, `name`, `signature: GroundValue`, `runtime:
  Option[Sourced[MonomorphicExpression.Expression]]`. `CompilerMonomorphicValue` mirrors it with `reduced` instead of
  `runtime` (the reduced compile-time body plugged in as a native, not codegen input).
- `NativeBinding.Key(vfqn, platform)` — value FQN → evaluator `SemValue`, per platform.
- `ContributedBinding.Key(vfqn, label)` — **not** platform-keyed; one supplier's total answer per label.
- `BodyValueReferences.Key(vfqn, platform)` — the memoized reference set of a value's checking body (transitive
  binding prefetch).
- `RefinementTable.Key(vfqn, typeArguments)` — keyed exactly like `MonomorphicValue.Key`; the per-node meta values of
  one monomorphic instance (the refinement channel's output).
- `MonomorphicExpression.expressionType: GroundValue` — always fully ground; no free variables or unsolved metas.
