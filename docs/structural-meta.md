# Structural Meta — meta follows a value into its data

Status: **design, decisions pending** (2026-10-20). This is P6 of `docs/total-meta-transfers.md`, the project its §2.3
called out of scope and its §10 named "the second refinement domain's project". It is scoped to **non-recursive**
`data`; recursive `data` stays where §2.3 left it, for the `List`/`Array` size domain to decide (§3.2).

The one-line summary: a `data` value's meta is the metas of its fields, tagged by which constructors can have built it;
a value that is never produced is ⊥; and a data type's constructor and eliminator, which are body-less leaves, state
both, by generated companions, exactly as `handleCases` already states the join of its arms (S11).

---

## 1. What is lost today

Meta is total on types but not structural: `metaTypeOf` maps a slotted type to its `T$Meta` and everything else to
`Unit`, so `Option[Int]`, `Pair[Int, String]` and `data Counter(n: Int)` carry nothing, and a value put into one comes
back out at ⊤. Probed against `master` (`b204fbe3`) with the `useByte` harness of `DerivedMetaTransferIntegrationTest`
(`where rangeWithin[0, 127](range(x))`):

| expression fed to `useByte`                                | today                     |
|------------------------------------------------------------|---------------------------|
| `fold(c, 5, 7)`                                            | proven                    |
| `foldOption(7, a -> 9, runAbort(if(c, 5)))`                 | proven — no field is read |
| `foldOption(7, a -> a, o)` with `o = None`                  | "not known"               |
| `if(c, 5) else 7`                                          | "not known"               |
| `orZero(Some(7))` (the pinned test, `case Some(x) -> x`)   | "not known"               |

`eliot-code`'s list of what is unknown today (§8.3 of the parent document) names "a field read, hence `if … else`".
That turns out to be **half** of the story, and the other half is what makes this more than a `metaTypeOf` change.

### 1.1 `if … else` is blocked twice

Trace `if(c, 5) else 7` through the tree as it stands:

```eliot
def else[A](computation uses *, Abort: A, fallback uses *: A) uses *: A = foldOption(fallback, a -> a, runAbort(computation))
def runAbort[A](obj uses *, Abort: A) uses *: Option[A] =                                       -- jvm layer
   escapeInternal((frame: AbortFrame[Unit]) -> obj with aborting(frame), (nothing: Unit) -> None, a -> Some(a))
def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T = fold(condition, value, abort)
implement aborting(frame: AbortFrame[Unit]): Abort { def abort[A]: A = exitInternal(frame, unit) }   -- jvm layer
```

1. **The field read.** `foldOption`'s jvm body is a `match`, whose `Some` arm reads `v` out of the `Option`. A data
   value carries no meta of its fields, so `v` is ⊤. This is the gap §8.3 names.
2. **The exit.** Even with (1) closed, the value inside the `Some` is `fold(c, 5, abort)`'s, and `abort` is
   `exitInternal(frame, unit)` — a native whose result is a type parameter, which R2 exempts as *forwarded*, so it
   states nothing and is ⊤. `fold`'s companion joins `[5, 5]` with ⊤ and the join stalls. But `exitInternal` does not
   return an unknown value — **it does not return**. The honest meta of a call that never returns is ⊥, the identity of
   the join, and the channel's lattice has a top (`None`) and no bottom.
3. **The escape.** `runAbort`'s result is `escapeInternal`'s, another type-parameter-result native, so it is ⊤ for the
   same reason as (2). What it hands `onValue` is what `body` yielded — a fact only the native knows, so it has to be
   stated (§8.9's rule: *a meta is stated exactly where the compiler cannot see the supplier*).

So `if … else` needs three things: structural meta (§3.1–3.4), a ⊥ for a value never produced (§3.5), and a way for a
native to state what it hands its code (§3.6). Each is useful without the others, and §8 stages them that way.

---

## 2. The rule

> **The meta of a value of a non-recursive `data` type is, per constructor, whether that constructor can have built it
> and, if so, the metas of the fields it was built with.**

This is the structural map §2.3 sketched — `meta(D(f₁: T₁, …)) = D$Meta(f₁: meta(T₁), …)` — with the one addition a sum
type forces: a sum's meta records **which constructors are reached**. Without that tag a sum is lossy twice: the join
of `Left(5)` and `Right("ab")` would have to invent a meta for `Right`'s field out of `Left(5)` (there is no bottom to
invent it from), and a `match` could not tell that `Some(7) match { case None -> … }` never runs its `None` arm.

What does **not** change:

- **R2 and R3.** A data type's constructors and eliminator are body-less leaves already (`DataDefinitionDesugarer`
  emits them with no body), so they state — by generated braces, which no user writes (§3.3). Everything bodied derives.
  §6 lists what the armed check would newly demand: nothing hand-written.
- **The one evaluator.** Meta structures are ordinary generated `data` in the compiler pool, joined by ordinary
  generated `Meta` instances, reduced by `EscalatingReducer`. The interpretation gains one value form (⊥), not an
  evaluator.
- **Layout.** A field is still stored at its boundary width (§8.10's decision, §7 of the parent). Structural meta
  reaches *checks* and the representation of `Int` nodes that read a field; it never lays out a `data`.
- **No surface.** A user never spells a structural meta (§3.7). It is derived and carried; it is observed only through a
  slotted type's `where` or brace downstream.

---

## 3. The design

### 3.1 The meta of a data type

Per non-recursive `data`, the core desugar emits into the compiler pool (dead in the runtime pool, as `Int$Meta` is):

```eliot
data Option[A] = None | Some(value: A)
  ⟶  data Option$Meta$Some[M$value](value: M$value)                     -- one record per constructor with fields
      data Option$Meta[M$Some$value](None: Reach[Unit], Some: Reach[Option$Meta$Some[M$Some$value]])
      implement[M$Some$value ~ Meta] Meta[Option$Meta[M$Some$value]] {  -- field-wise, as MetaConstructorDesugarer does
         def join(a: Option$Meta[M$Some$value], b: Option$Meta[M$Some$value]): Option$Meta[M$Some$value] =
            Option$Meta(join(None(a), None(b)), join(Some(a), Some(b)))
      }
      type Option$Fields[A] = Function[A, Unit]                          -- every constructor's field types, in order
```

- **`Reach[M]`** is one compile-track `data Reach[M] = Unreached | Reached(meta: M)` with
  `implement[M ~ Meta] Meta[Reach[M]]` — `Unreached` is the identity of the join. It lives with `Interval`/`Bound`, in
  the compiler overlay (a `data` may not be in the base). It is a bottom **for a constructor**, not for a domain: it
  says "no value of this constructor reaches here", which is a fact about the program, and it has one spelling — so it
  does not reopen §5's "a wrapper is a second spelling" (that was a second spelling of a *domain's* top; `Reach` adds no
  spelling to any domain).
- **One binder per field, not per type parameter.** `Option$Meta[M$Some$value]`, `Pair$Meta[M$Pair$first,
  M$Pair$second]`. The meta type of a field is never named from its spelling (§8.8: that failed for an alias, a computed
  type and a type parameter); the channel instantiates the binders. `metaTypeOf(Option[Int])` reduces `Option$Fields`
  at `[Int]` with the one evaluator, peels as many arrows as `Option$Meta` takes binders, and maps `metaTypeOf` over the
  field types: `Option$Meta[Int$Meta]`. `Option[Bool]` is `Option$Meta[Unit]`; `Option[Option[Int]]` is
  `Option$Meta[Option$Meta[Int$Meta]]`. `$Fields` is the same device as §8.8's `$Payload`, for the same reason.
- **A field-less constructor's slot is `Reach[Unit]`**, and a constructor with fields gets a record. A field-less enum
  (`data AB = A | B`) therefore carries a meta — `AB$Meta(A: Reach[Unit], B: Reach[Unit])` — which is what lets
  `pick(B)` derive `[20, 20]` instead of `[10, 20]` (§4.4). Contracting a one-field record to its field is an
  implementation freedom: only generated companions read the shape, never the channel.
- **What is excluded.** The generated meta structures themselves (`Int$Meta`, `Option$Meta`, the records, `Reach`) get
  no structural meta — that is a regress, and they are compile-time values the channel never tracks. A type that
  declares **slots** keeps its slots: a slotted type's meta is what it declares (`Int`, `String`, and
  `docs/recursive-data.md`'s `data Tree[A] {depth: …}`), and a structural meta is derived only for a `data` with none.

### 3.2 Recursive data has no structural meta

`List`, a `Tree`, a pair of mutually recursive types: the structural map does not terminate on them
(`meta(List[A]) = List$Meta[meta(A), meta(List[A])]`), and what *should* summarise one — a size, a depth — is the size
domain's decision (§2.3, `docs/recursive-data.md`). Their meta stays `Unit`, exactly as today.

**The test is on the declaration, not the instance.** A `data` D is recursive iff the type-level references of
`D$Fields`'s body — followed through aliases, type-level definitions and other data types' `$Fields` — reach D. So
`data Nest[A](x: A, n: Option[Nest[Pair[A, A]]])` is recursive, and `data A(b: Option[B])` / `data B(a: Option[A])`
both are, while `Box[Box[Int]]` is not (`Box`'s field is its parameter, which is no reference). Two cheaper tests are
wrong in opposite directions: a direct self-mention misses mutual recursion through another type, and a cycle on the
*head* constructor of instantiated types misreads `Option[Option[Int]]` as recursive. A type reached by a recursive one
is unaffected (`Box[List[Int]]` is `Box$Meta[Unit]`). The test terminates because the declarations are finite, and the
structural map then terminates on every type it admits: each step descends to a field's declaration lower in an acyclic
graph or into a subterm of the type arguments.

It is computed once per type FQN, reading resolved bodies directly and **never another type's verdict**, so it cannot
form a processor-level fact cycle.

### 3.3 A data type's two leaves state, and the desugarer writes both

**The constructor's transfer.** A constructor is a body-less def returning a type that now carries meta, so R2 asks it
to state. The data desugarer writes the brace on it, as it writes `handleCases`'s since S11:

```eliot
Some[A](value: A): Option[A]  {Unreached, Reached(Option$Meta$Some(value))}
None[A]: Option[A]            {Reached(unit), Unreached}
```

It is an ordinary slot-producing brace: the channel passes the meta constructor of the result's evaluated meta type
(`constructor$`, §8.8), so the brace never names `Option$Meta`. (`metaConstructorOf` reads only nullary meta types
today and has to instantiate a generic one at its arguments.) The per-constructor record it does name is generated
beside it in the same file, from the declaration itself, which is not the spelling-of-a-type reading §8.8 retired.

**The eliminator's handed transfers.** §8.10's handed statements say what `handleCases` hands handler *i* as argument
*j*, and they read no parameter — a braced field's brace, copied in. A structural one reads the eliminator's `value`:

```eliot
handleCases$Handed$case1$0(value) = <Some's reach in value, mapped to its `value` field>   -- a Reach[M$Some$value]
handleCases$Handed$case0$0(value) = <None's reach in value>                                -- a Reach[Unit]
```

It answers a `Reach`, which the channel reads as the one place it is recognised, by name: `Reached(m)` is handed `m`,
`Unreached` is handed **⊥** (§3.5) — the arm cannot run, because no value of its constructor reaches the `match`. Where
`value`'s meta is ⊤ the transfer does not reduce, every arm is handed ⊤, and every arm is joined, which is today's
behaviour. `handleCases`'s own transfer — `joined(case0, case1, …)` — does not change: arm pruning falls out of ⊥
(§3.5), not out of a new eliminator rule.

**A braced field keeps its brace as the fallback.** Where `value`'s meta is known, the structural field meta is at
least as precise as the brace — the construction proved the argument fits (§8.10) and the structure stores the
argument's own meta, so `Pixel(5, 0).r` now reads `[5, 5]`, not `[0, 255]`. Where it is ⊤ (a `Pixel` from a ⊤
parameter at a root context, from a lambda binder, from an exhausted budget), the field's stated brace answers as it
does today. The two are separate companions, read structural-first.

**Accessors need nothing.** An accessor is a generated def whose body is a `match`, so it derives through the
eliminator.

### 3.4 Known means fully known

A structural meta with a ⊤ field cannot be represented: the domains have no top value to put there (the range domain's
`whole` is a top for `Int`, but a user's `Meta[Sign]` has whatever it has, and §5 decided the machinery keeps none), and
a `Known | Unknown` wrapper per field would be a second spelling of every domain's top — the reconcile-pass bug §5
removed. So **a constructor's transfer with a ⊤ argument is ⊤**: `Pair(5, x)` with `x` unknown carries nothing,
`Pair(5, 6)` carries both. ⊤ is rare where it matters — R2 makes every leaf state, and a P5 context knows every
parameter — and per-field ⊤ is §10's precision follow-on, needing a per-domain top that §5 would have to re-decide.

That needs two repairs to the stated-transfer path, both prerequisites and both independent of data:

- **A reduced meta containing a placeholder is ⊤.** An unknown argument reaches a companion as a `SemValue.VType`
  placeholder, which is safe today only because every companion *projects* its arguments (`range(a)` stalls on the
  placeholder, so nothing quotes). A constructor transfer *stores* its argument, so the placeholder would quote into the
  structure as `Type` and arrive as a well-formed, wrong meta. The fix is general: an unknown argument becomes a
  dedicated neutral, and a reduced result containing it is ⊤. That closes the hole for any companion, including a
  future hand-written one that passes an argument through.
- **A `Unit` meta is known.** An argument whose meta type is `Unit` is passed as `unit`, never as a placeholder (§8.8
  already counts it as known for a `where`). Without this, every data type with a function field, or a field whose type
  is slotless, would be ⊤ under the rule above.

### 3.5 ⊥ — a value that is never produced

The interpretation's `Value` gains **`Never`**: *evaluating this does not produce a value.* Not a meta value in any
domain: it is the channel's own form, as ⊤ (`None`) is, and it never reaches a record, a fact or a companion.

- **Where it originates.** A leaf states it — `{never}`, `eliot.compiler.Meta::never`, recognised by name as the
  reduced result of a companion, as `joined` is reached by name. The exits state it: `exitInternal` in each jvm module
  that declares one, and `foreverInternal` may. It is axiomatic like every leaf statement, and only a body-less value
  may state it (R3, unchanged). And an `Unreached` handed argument is `Never` (§3.3).
- **Strictness.** Eliot is call-by-value in every value position (*effects resolve where they are written*), so a call
  any of whose **value** arguments is `Never` is never made: it is `Never`. That covers a constructor (`Some(abort)`), a
  derived callee, a stated one, and a `val` block, which lowers to an applied lambda. A **code** argument is not
  evaluated before the call — it is a closure — so its own `Never` is the callee's business.
- **A stated transfer over a `Never` code yield.** A companion is Eliot over metas and cannot receive ⊥. The channel
  passes it, in place of a `Never` yield, another argument the companion reads **at the same meta type**. That is sound
  because statements are monotone (⊥ ⊑ v ⇒ f(…⊥…) ⊑ f(…v…)) and exact for a join, which is idempotent, so `fold(c, 5,
  abort)` is `[5, 5]` and a `match` with an unreached arm is the join of the reached ones. With no such argument the
  call is ⊤ — sound, and the case where every arm exits, which nothing needs yet. Monotonicity becomes a stated
  obligation on statements, and every statement in the tree meets it (interval arithmetic, the joins, constants).
- **What a record says.** A `Never` node is recorded as ⊤: it is never materialised, so its layout is irrelevant, and
  ⊤ is what the table already means by "nothing to narrow".
- **Demands under ⊥ are vacuous.** A demand whose call is never made — a `Never` argument, or an arm handed ⊥ — is not
  checked in that context. This is P5 read to the letter: a demand is checked in every context *it is reached in*, and
  an arm a context cannot enter is not reached there. `f(Some(5))` compiles over `case None -> useByte(1000)`, and
  adding `f(None)` reports at that call, exactly as P5 reports any demand at the use that decides it. (Decision 4: the
  alternative is checking an unreached arm with ⊤ binders, which rejects it.)

### 3.6 What a native hands its code

§3.3's handed transfers are generated; `escapeInternal` is hand-written, and what it hands `onValue` is what `body`
yielded. §8.9 already holds the rule for this — *a brace on a type occurrence states the meta of the value at that
position, and whoever supplies the value proves it* — and a code parameter's arrow domain is an occurrence whose
supplier is the callee. So the fourth row of §8.9's table:

| Position                        | Supplier                  | Visible to the compiler? | So                         |
|---------------------------------|---------------------------|--------------------------|----------------------------|
| handed argument (arrow domain)  | the callee running it     | not for a native         | stated, axiomatic          |
| handed argument, bodied callee  | the body                  | **yes**                  | **declined (R3)**          |

```eliot
private def escapeInternal[K, A, R](body uses *: AbortFrame[K] => A, onExit uses *: K => R, onValue uses *: A {body} => R)
   uses *: R {joined(onExit, onValue)}
```

`{body}` after `onValue`'s domain says "what `onValue` is handed is what `body` yields" — read as a code parameter
always is, as the value its code yields (S11). The grammar slot is free: a brace *before* a type is the rejected row
spelling, and one *after* a parameter's type is §8.9's. It desugars to the same handed companion the data desugarer
writes (`escapeInternal$Handed$onValue$0`), now able to read parameters. Each module's `escapeInternal` (Abort's,
Throw's) states it, and `withCellInternal` can state its `combine`'s first argument the same way.

Declined: **reshaping the escape primitive** to `escape(body: Frame => R, onExit: K => R)`, so its transfer is a plain
join of two yields and nothing is handed. It needs nothing new here, but it changes a primitive whose three-argument
shape `docs/effects.md` §3.4 fixes for every target (a microcontroller's status-flag path), the backend's frame
codegen and the compile track's intrinsic — a cross-cutting change bought to avoid one grammar slot.

### 3.7 Structural meta has no surface

A user cannot write one: there is no brace on a `data`-typed parameter, field or leaf return (a brace lists slots, and
only a slotted type has slots — R2's existing "lists slots over a result that carries no meta" check widens to "…or
whose meta is structural"), and a `where` cannot read one (a predicate observes a meta only through its type's
accessors, and the generated ones are not in any user's scope). Structural meta is **derived only**: it carries a
slotted type's meta through a data value so the read recovers it, and the observable is the slotted type's own `where`
or brace downstream. That keeps every existing rule about who states what intact, and it is why §3.3 can name generated
records freely: no user code can depend on the shape.

### 3.8 Layers

A data type's meta is generated from its **compiler-pool** declaration — the jvm layer's, borrowed, or the compiler
overlay's where one supersedes it (`Option`, `Either`, `Pair`, `Bound`, `Interval`) — while the bodies the channel
interprets are the runtime track's. The handed transfers are by handler *index*, so an overlay declaring a type's
constructors in another order than the runtime layer would hand arm *i* the fields of the wrong constructor, silently.
Today every overlay copy is spelled exactly as the jvm layer's. **Required:** the two declarations agree on constructor
names, order and field count, checked where the meta type is first asked for, and a disagreement is an error at the
overlay's declaration (decision 8). A mismatched *arity* already fails safe (the companion's parameter count does not
match the call, so ⊤); order does not.

### 3.9 Representation

Nothing in the backend reads a structural meta, and nothing should: a `data` is an object whatever is known of its
fields. One hardening goes with it: `IntRepresentation.intervalBounds` decodes `Int$Meta(Interval(…))` by **shape**,
without checking the structure's name, and structural metas are new structures that reach `Int`-typed nodes only by
way of a field read — check the FQN there, so no future shape can be mistaken for a range.

---

## 4. Worked traces

### 4.1 `orZero(Some(7))` — the pinned test flips

`Some(7)`: `{Unreached, Reached(Option$Meta$Some([7, 7]))}`. `orZero`'s body is a `match`; the `Some` arm is handed
`[7, 7]` and yields it, the `None` arm is handed ⊥ (`Unreached`) and is `Never`; `handleCases`'s join reads the
`Some` arm's yield in its place, so the result is `[7, 7]`.

### 4.2 `if(c, 5) else 7` — `[5, 7]`

`fold(c, 5, abort)`: `abort` ⤳ `exitInternal` ⤳ `Never`, so `fold`'s join is `[5, 5]`. `escapeInternal` hands
`onValue` that, which builds `Some([5, 5])`; `onExit` builds `None`; the escape's join is
`{Reached(unit), Reached(Some-record([5, 5]))}`. `foldOption`'s `match` hands `ifNone`'s arm `unit` and `ifSome`'s
`[5, 5]`, so the join is `[5, 7]`. `useByte(if(c, 300) else 5)` is then *not satisfied* rather than *not known*: the
accept/reject pair the integration tests want.

### 4.3 `catch`

The same shape over `Throw`'s `escapeInternal` and `Either`: a `raise` is `Never`, the body's value is `Right`'s field,
`onError` is handed ⊤ for the error (nothing states what an exit carries), and the result is the join of the handler's
yield and the body's.

### 4.4 `pick(B)` — an arm pruned

`data AB = A | B`, `pick(x) = x match { case A -> 10 case B -> 20 }`. Today `[10, 20]`; with `B`'s meta
`{Unreached, Reached(unit)}`, the `A` arm is handed ⊥, and `pick(B)` is `[20, 20]`.

---

## 5. Cost and risk

**The type gate opens.** `MetaInterpreter.derive` is skipped before any body is fetched when the call's type carries
no meta, and §8.5 credits that gate for the interpretation costing nothing measurable (the 43-example sweep took 4m18s
with the interpretation against 4m20s without). Every non-recursive data type now carries meta, so every call returning one is interpreted — and so
is every handle, since a named implementation's handle is a `data`. Handles stay in, because excluding them would key a
rule on `Handles.isDataType` and lose a double's `with` arguments, which are exactly what a test's `where` would read.

**P5's contexts multiply.** A `MetaDemands` context is keyed by the metas of every meta-carrying parameter, so a
deferred body taking an `Option[Int]` gets one context per distinct structural meta its callers supply, each a cached
fact. Contexts are made only for a body whose root context defers, which bounds it, but the bound is not measured.

**Generated values.** Per data type: a meta structure, a record per constructor with fields, a join instance, a
`$Fields`, a transfer per constructor, a handed transfer per handler argument — each record itself desugared through
`DataDefinitionDesugarer` (type, constructor, accessors, `PatternMatch`, `TypeMatch`). Compiler pool only and demand
driven, but every cold build mints the names.

So T2 (§8) is gated on a measurement, not an expectation: the sweep's wall time, `--statistics` with and without the
flag, and the cache size, against T1. If the budget or the clock binds, §8.5's memo on `(vfqn, typeArgs, argMetas)` is
the prepared answer and is added then, not before.

---

## 6. What the armed checks demand

R2 newly asks every body-less value returning a non-recursive `data` type to state. Measured by reading the tree:

- **Constructors and eliminators** — all generated (§3.3).
- **Natives returning data** — **none**. The jvm layer already builds every `data` value in Eliot over primitive natives
  (`lineOrNone(readLineInternal)`, `valueOrNone(environmentVariableInternal(…))`, `ProcessResult` from the
  `outcome…` leaves) — a platform written as small Eliot functions over trivial leaves pays off here: the leaves return
  `String`, `Int` and abstract native types.
- **Type-parameter-result natives** (`escapeInternal`, `exitInternal`, `withCellInternal`, `foldLeftInternal`) stay
  exempt and ⊤; §3.5 and §3.6 state what is worth stating, for precision, not because R2 asks.

The armed check is the proof: T2 compiles the whole tree and the examples under it, and any leaf this list missed is a
compile error naming it.

---

## 7. Declined

- **A tag-blind structure** — one slot per field, joined across constructors, no reach. Cheaper, but it has no value
  for an unbuilt constructor's fields (there is no bottom to put there), so a sum is ⊤ unless every constructor is
  built, which is never.
- **Single-constructor data only.** Exact and simple, and it misses `Option` and `Either`, which are the point.
- **An empty type as ⊥** — `exitInternal: K => Nothing` with `data Nothing` and a zero-armed eliminator, so a call
  that never returns is a value of a type with no reached constructor. It unifies `Reach` and `Never` elegantly and
  costs a signature change to every exit primitive on both tracks plus an eliminator with no handlers, which
  `DataDefinitionDesugarer` deliberately does not emit. `{never}` states the same fact at the leaf that knows it.
- **⊥ by parametricity** — a native returning a type parameter it was never given must not return. True of a
  parametric function and not of a native, which can do anything; inference where a statement belongs.
- **The channel naming `PatternMatch`** to link handler *i* to constructor *i* — S11's and §8.10's refusal stands; the
  generated handed transfers carry that link instead.
- **Per-field ⊤** — §3.4; it needs a per-domain top, which §5 of the parent decided against for the machinery.

---

## 8. Staging

Each stage is verified by the fast example sweep and byte-identity compare (`scripts/example-sweep.sh`), every example's output unchanged, and the whole suite green.

- **T1 — known-ness repairs (§3.4).** A dedicated placeholder neutral, a result containing it ⊤, a `Unit`-meta
  argument passed as `unit`; harden `intervalBounds` (§3.9). Expected byte-identical: no companion today stores an
  argument, and nothing reads a `Unit` meta.
- **T2 — structural meta (§3.1–3.3, 3.7, 3.8).** `Reach`; the meta structures, records, join instances and `$Fields`;
  the recursion test; constructor transfers; structural handed transfers with the brace as fallback; `metaConstructorOf`
  for a generic meta type; the overlay agreement check. Flips `DerivedMetaTransferIntegrationTest`'s field-read case to
  an accept/reject pair, adds `Pair`/`Either`/a user record/`pick(B)`'s pruning — the last needs T3's ⊥ for an
  `Unreached` arm, so T2 alone reads an unreached arm as ⊤ (no regression, no pruning). Jars change wherever an `Int`
  is read out of a `data`. **Gated on §5's measurement.**
- **T3 — ⊥ (§3.5).** `Value.Never`, `{never}` on each `exitInternal`, strictness, the same-meta-type substitution, ⊥
  recorded as ⊤, vacuous demands. Observable without T4 through a demand inside an `Abort`-using body
  (`def g(c: Bool) uses Abort: Int = useByte(fold(c, 5, abort))` proves), and with T2 through `pick(B)`.
- **T4 — handed braces on natives (§3.6).** The arrow-domain brace in the parser, desugared to the handed companion;
  `escapeInternal` in `Abort` and `Throw`, `withCellInternal`. `if … else` and `catch` become accept/reject pairs.
- **T5 — the record.** This document's status, the parent's P6 and §10, `eliot-code`'s list of what is unknown today
  (§8.3 of the parent), and CLAUDE.md's refinement-channel paragraph.

---

## 9. Decisions needed

1. **Home.** This document, with the parent's §2.3, §10 and P6 pointing here — a new project, as §10 said it would be,
   not an amendment of a decided non-goal. *Recommended.*
2. **A sum's meta is tagged by reach** (§2, §3.1) — over a tag-blind structure or single-constructor data only (§7).
   *Recommended.*
3. **The interpretation gains ⊥** (§3.5), stated `{never}` at the exits, with strictness and the same-meta-type
   substitution, and monotonicity a stated obligation on statements. This is the one new idea: the parent's lattice
   has had a top and no bottom by construction. *Recommended*; without it `if … else` stays ⊤ after everything else
   here.
4. **A demand an unreached path holds is vacuous in that context** (§3.5) — P5's "reached in" read literally — rather
   than checked with ⊤ binders. *Recommended.*
5. **The arrow-domain brace** (§3.6) — a fourth occurrence for §8.9's rule, accepted on a body-less def only — over
   reshaping the escape primitive. *Recommended.*
6. **Recursive data stays `Unit`** (§3.2), tested on the declaration, until the size domain decides its summary.
   *Recommended.*
7. **All-or-nothing knowledge per construction** (§3.4); per-field ⊤ deferred. *Recommended.*
8. **An overlay `data` must agree with the runtime one** in constructor names, order and field count, as an error
   (§3.8). *Recommended.*

---

## 10. Non-goals

- Recursive `data`, `List`/`Array` size, the fold's iteration budget — the size domain (§3.2).
- Per-field ⊤ (§3.4).
- A user spelling for a structural meta (§3.7).
- A layout decision from a structural meta (§3.9) — storing a narrowed field narrowly is a representation decision, the
  parent's §7.
- What an exit *carries* (`onError`'s argument): it would need every `raise` site's meta joined into the frame, which
  is a flow across calls the interpretation does not do.
