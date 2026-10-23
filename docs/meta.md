# Meta — the refinement channel

Status (2026-10-22): **two domains shipped, R2 and R3 armed, the interpretation and in-context demands built** (Part I).
**Completing totality is designed and decided, and partly built** (Part II):
- every leaf states its meta, type-parameter results included;
- what reaches a native through a frame, a cell or a loop is followed by the interpretation;
- a value that is never produced is ⊥;
- a `data` value carries the metas of its fields.

Of Part II, T1 (the gap mechanics), T2 (⊥), T3 (frames and cells), T4 (loops) and T5 (structural meta) are built,
and of T6 every leaf now states and the step budget is a reported limit (§17). T6's `List` domain is decided
(§17.1, decisions K–N). Where the work stopped, and how to continue it, is §18.1.

**One hole in the design is open (§8.1): custom metas.** A program cannot yet declare meta-information for its own
`data` types, and it should be able to. Decision O (2026-10-23, §8.1, not built) closes it: a brace on a bodied value states its
result's meta, replacing the derived one, trusted like a native's. That reverses R3. Its migration is planned in
§17.2. Cycles, stack depth and peak memory are not metas (§8.2); they are
`docs/grades.md`.

This document replaces three: `total-meta-transfers.md` (the rules and the interpretation), `string-length-meta.md`
(the `String` size domain) and `structural-meta.md` (the P6 design). Their full text is at `2a66009a`, and §21 maps
their section numbers, which older commits and notes cite, to this one.

- **Part I** is the shipped design, stated as it now stands. Where code or a test disagrees with it, the code is the
  defect.
- **Part II** is the design that finishes totality, revised on 2026-10-20 against the principle in §1.
- **Part III** is the record: how the design got here (§19), what must not come back (§20), and the citation map
  (§21).

**The process has no special values.** Metas are never lost, and nothing marks a meta as missing, unknown or
biggest. A **top** exists only where a domain has one in its own value type, such as `whole` for an interval: a value
of that domain like any other. Neither the machinery nor `ability Meta` has a top. Today's implementation still
produces a *not computed* result in a few places (spelled `None` in the code, and ⊤ in older text and comments).
That is not a meta. Each place is a gap to close (§8), not part of the design. ⊥ (§12) is not one either: it says
that a call does not return, a fact about control, and no companion or domain ever sees it.

---

# Part I — The shipped design

## 1. The model: every value has a meta

A type may declare **meta slots**:

```eliot
type Int {range: Interval[BigInteger]}
type String {length: Interval[BigInteger]}
type List[A] {size: Interval[BigInteger], item: Reach[MetaOf[A]]}
```

The slots are compile-time information *about* a value, and they are not part of its type. `Int == Int`
definitionally whatever the ranges, so the unifier never sees a slot. Legality is checked by this channel after
monomorphization (CLAUDE.md, *Types Are Values*, guardrail 1). The meta-constructor desugarer turns a slotted type into
a **meta structure** `Int$Meta(range: Interval[BigInteger])` with a derived `Meta[Int$Meta]` instance. That instance
joins the structure slot by slot, through each slot's own `Meta` instance. `ability Meta[D]` with its `join` is the
lattice. The channel names no domain.

**The principle: metas are total.** Every value has a meta. A value with a body has its meta **calculated** from the
body. A value with no body (a native) has it **defined**: it states it. Nothing else exists, so there is never an
undefined meta. Meta is a shadow logic that runs behind every function.

> **Closure.** Every named value has a meta transfer.
>
> 1. **Base.** Every **leaf** (a value with no Eliot body on any mount) states its transfer.
> 2. **Step.** Every other value derives its transfer from its body, whose calls all have transfers by (1) or (2).
>    Decision O (§8.1, 2026-10-23, not built) lets such a value state its result's meta instead, replacing the derived one.
> 3. **Termination.** *Total by Default*: the recursion gate rejects every cycle in the runtime-body reference graph,
>    so derivation walks a DAG.

Point 3 is why Eliot can have this and most languages cannot. The shadow is a homomorphic image of a program already
known to be acyclic. So there is no fixpoint iteration, no widening and no ascending-chain condition, **except at
leaves**. Leaves are exactly where the loops live (`foldLeft`, `forever`), and exactly where a statement is demanded.

**R1 makes meta total on types.** `metaTypeOf(T)` is `T$Meta` for a slotted type and `Unit` otherwise, computed from
the *evaluated* type (§4.1). `Unit` is a real meta: it says that nothing is tracked about this type, and it is always
known. Today `metaTypeOf` is not **structural**, so `Option[Int]` and `data Counter(n: Int)` both have meta `Unit`, and a
value put into one loses its meta (§8). Part II §13 makes it structural.

**Where today's implementation falls short.** Two things can still leave a node with no meta:

- an implementation gap the interpretation has not closed (§8, each one a work item);
- the step budget running out (§5.3).

Neither is a meta and neither may be read as one. Every demand that meets such a node fails with *"Cannot prove"*,
which is a different diagnostic from *"not satisfied"*. So a gap is fail-safe and never admits a program. The design
target is that no gap is left. Part II closes them, and turns the budget into a compiler limit whose exhaustion is
reported as an error, never handed on as a value.

## 2. The domains

### 2.1 The range domain: `Int`

```eliot
data Bound[T] = Unbounded | Bounded(value: T)
data Interval[T](start: Bound[T], end: Bound[T])
type Int {range: Interval[BigInteger]}
```

- **Endpoints are `Bound`s, so a range may be half-open.** An unbounded endpoint takes its direction from its
  position. `start` unbounded means no lower limit; `end` unbounded means no upper one. There is no signed infinity.
  This lets the base state the platform-independent half of a bound (`atLeast(0)` for a size) without inventing a
  platform maximum it may not assume. A platform then narrows it (`closed(0, platformMax)`).
- **The domain's top is `whole`**, open at both ends. It is the domain's own spelling of *nothing bounds this*, and
  it is the only spelling. `ReconcileProcessor.metaByPosition` compares verdicts structurally, so a second spelling of
  the same top reads as a disagreement. That bug is why the slot-level `Bound` wrapper and the generic
  `Meta[Bound[D]]` top were deleted (§19).
- **The arithmetic.** `add` and `subtract` are exact on a half-open interval: they pair endpoints, so an absorbing
  `Unbounded` lands in the position that means the infinity it came from. `multiply` widens to `whole`, because its
  corner products lose the position that gives an infinity its sign. That is sound but not tight.
- The constructors `interval`, `closed`, `atLeast`, `atMost` and `whole`, and the `where`-facing
  `rangeWithin[Lo, Hi](i)`, are abstract in the base and bodied per platform.

### 2.2 The size domain: `String`

`type String {length: Interval[BigInteger]}`. This is the domain that proved the machinery generic rather than
`Int`-shaped. It cost one compiler arm (the literal seed) and one declaration; every other piece was already
shipped.

- **The unit is the code point**, the count `String::length` returns on every target (S0). It is a language
  decision, not a channel detail. Only a property of the string *value* means the same thing on every target, so a
  `where` over a per-target count would hold on the JVM and fail on an MCU. The index family (`substring`,
  `indexOfInternal`, `take`/`drop`, and `split`/`replace` on an empty argument) switched units with `length`, on both
  tracks. The unit is stated once, on `type String` in `lang`.
- **The slot is `length`, named like the function that returns it** (since 2026-10-22). It was `size` until then:
  a slot's accessor lived in its type's module and default namespace, beside `def length(s: String): Int` ("Name was
  already defined in this module"). Decision K gave accessors a namespace of their own (§17.1), and then `List`'s
  `size` slot hid `String`'s in every file importing `List`, since a file sees one value per name: `split`'s own
  statement could not read its argument's size. Robert took the rename, so each slot is named like the function that
  computes it, `String::length` and `List::size`. Slot names are still one scope per file: two domains whose slots
  share a name cannot both be read by their bare names in one file, and one is written module-qualified.
- **The seed.** `def stringLiteral[N: BigInteger]: String {closed(N, N)}` in `eliot.lang.Runtime`. No program calls
  it. It is the vessel that keeps the seed's construction in Eliot: the channel supplies the literal's
  `codePointCount`, and the brace builds the meta. Counting is platform-independent because the unit is.
- **Rendering may name a domain; nothing else may.** `TypeHintIndex.boundsOf` decodes a range for LSP hover only when
  the meta's own FQN is `Int$Meta`, so hovering `"hello"` does not show a value range of `5..5`. The channel's
  diagnostics say *meta-information*, the language's word, which is true of every domain.
- **Byte size is not a slot.** It is to `size` what an `Int`'s machine width is to its `range`: a backend derives it
  from the meta using knowledge only it has (bytes per code point). A `bytes` slot in the base would name units the
  base may not name. A per-platform transfer brace cannot fill it either: a brace desugars to an ordinary named value,
  so two layers bodying it is "Has multiple implementations."

What the `String` leaves state (`n` is `length(s)`):

| leaf | stated |
|---|---|
| `length` | `{length(s)}`. This is in the base, because the identity holds on every target. |
| `combine` (`++`) | `{length(a) + length(b)}`, exact |
| `substring(startIndex, endIndex, s)` | `{interval(Bounded(0), end(n))}`. Only the length ceiling, because the index ceiling needs an interval **intersection** the domain lacks (`minBound` absorbs `Unbounded`, which is right at a `start` and wrong at an `end`). |
| `trim` | `{interval(Bounded(0), end(n))}` |
| `toUpperCase` / `toLowerCase` | `{interval(start(n), end(n) * 3)}`. Not length-preserving: `ß` ⤳ `SS`, `ﬃ` ⤳ `FFI`. |
| `repeat(count, s)` | the product of `count`'s and `n`'s ceilings, floored at `0` |
| `replace(target, replacement, s)` | `n + (n + 1) · size(replacement)` at most. An empty target inserts between every pair. |
| `indexOfInternal(part, s)` | `{interval(Bounded(0 - 1), end(n))}` |
| `parseIntInternal` | `{whole}`. The honest bound `±(10ⁿ − 1)` is exponential in `n`: about 890 MB at the JVM's size ceiling. |
| `Show[Int]::show` (base) | `{atLeast(1)}`. The ceiling is a digit count, which needs a logarithm no leaf offers. |
| `Show[Path]::show` (base) | `{atLeast(0)}`. The ceiling is the platform's path limit. |
| jvm input and holder natives (`readLineInternal`, `environmentVariableInternal`, `fileNameInternal`, …) | `{closed(0, 2147483647)}`, the JVM's own `String` ceiling |

`substring`'s parameters are named `startIndex`/`endIndex` because **a brace resolves a name to a parameter before an
accessor**. A parameter named `end` would make the `Interval` accessor unreachable from the signature that needs it.

### 2.3 Growing a domain or a slot

Declaring a slot is all a new domain needs. The structure, the lattice, `where`, transfers and hover are
domain-agnostic. Two constraints apply:

- **A brace states every slot or none.** A brace is a positional list applied to the meta constructor, so a partial
  statement cannot be written. A second slot means every leaf states both, spelling an open endpoint or the domain's
  top for the one it does not know.
- **A domain that is not an interval grows its own top**, in its own vocabulary, and never borrows a wrapper (§2.1).
  The second slot most likely to earn itself is an **encoding claim** (`ascii ⊑ latin1 ⊑ unicode`), which would let a
  backend size an ASCII string at exactly `size` bytes. It would be the first non-interval domain. It needs a
  user-declared `Meta[Encoding]`, and a domain head that `MetaConstructorDesugarer.slotJoin` accepts (a simple type
  application).

## 3. The rules

**R1: every type has a meta type** (§1).

**R2: a leaf must state its transfer.** It states it as a brace on its return: `def length(s: String): Int {length(s)}`.
The check is **use-site and post-monomorphization** (*Use-Site Verification*): whether a generic leaf's result
carries meta is known only once its type is ground. It reads the **evaluated** result type, so
`def readGauge: Reading` over `type Reading = Gauge` is not exempt by spelling. Its fact,
`MetaTransferAccounting`, is a **codegen precondition** in `WovenValueProcessor`: a leaf that states nothing blocks its
own codegen, the same shape and reasoning as an undeclared effect. A leaf whose brace lists slots over a result that
carries no meta is reported at the leaf.

**There is no exemption for a type-parameter result** (since 2026-10-22, §10). R2 shipped with one: a leaf whose
result head was a type parameter (`exitInternal[K, A]: A`, `foldLeftInternal[A, B]: B`) was not checked, on the
grounds that it forwards a meta rather than originating one. That was false for most of them, and those leaves are
where the remaining not-computed metas came from. Every one states now, the generated `typeMatch[R]` last, and R2
judges every body-less leaf at its instance's evaluated result type, whatever its declaration spells there.

**R3: a bodied value derives, and may not state.** A brace on a value with an Eliot body is an error, read after the
layer merge (`MonomorphicValue.runtime`). That makes a brace in one layer over a body in another the same check as
both in one declaration. A brace therefore lives only on a native leaf, so **a user module cannot author a transfer**
(it declares no natives). That consequence is a hole, not a goal (§8.1). R2 and R3 are the two arms of
`MetaTransferAccountingProcessor`, and both judge the declaration (`OperatorResolvedValue`).

> **R3 is reversed by decision O (2026-10-23, §8.1), not yet built.** A brace on a bodied value will state its
> result's meta, replacing the derived one, trusted as a native's statement is. Until it is built, the tree enforces
> R3 as described above.

**R4: there is no undefined meta.** A top is stated, never an omission (§1).

`where` is untouched by all four. A precondition is a stated contract, never derived.

### 3.1 The leaf boundary is the true native leaf

The compiler pool reads the runtime track completely, so derivation walks straight through the jvm layer's Eliot
bodies. A leaf is what the plain reading says: **no Eliot body on any mount**. That is a Scala or bytecode native, the
same bottom the native-leaf fail-safe guards. Derivation may pass through a jvm body but stops at the native under
it, and that native is what states.

An **ability method declaration is not a leaf.** `def add(a: T, b: T): T` inside `ability Numeric[T]` resolves to an
implementation, which is either bodied (it derives) or native (it states, as `Numeric[Int]`'s body-less `add`
does). Most of the body-less `def`s in the tree are such declarations, so a leaf predicate that missed this would
report a hundred spurious errors.

### 3.2 Where a statement lives, and what it may say

- **A statement lives in the layer that owns the fact it states.** Ask what the bound is a property of. If it is a
  representation, the statement belongs to the platform: `readLineInternal`'s `closed(0, 2³¹−1)`, `outcomeExitCode`'s
  full `int` range. If it is the operation itself or its argument's meta, it belongs to the base: `length`'s
  `{length(s)}`, `combine`'s sum, `show`'s `atLeast(1)`. Exactly one layer may carry a brace: it desugars to a concrete
  companion, so two would be "Has multiple implementations". Signature equality ignores braces, so the merge stays
  lexical.
- **A leaf's statement is axiomatic.** Nothing rechecks it, and since S9 the backend no longer even truncates at the
  call boundary (§7). So a bound that is merely *usually* true is a bug. POSIX's `[0, 255]` exit status is false on
  Windows, where `Process.exitValue()` returns an arbitrary `int`, so the leaf states the full `int`. A wide statement
  still bounds a width, which is what a fixed-width `where` needs.
- **R2 demands a statement, never a tight one.** When the honest bound cannot be written or afforded, a leaf states
  what it can: an open endpoint when one side is honest (`atLeast(1)`), the domain top when neither is
  (`parseIntInternal`'s `whole`), or the representation's limit when the bound is a representation fact. A bound that
  would hang the compiler is worse than a wide one.
- **An unbounded loop never reaches the interpretation.** A leaf is *summarized* by its statement and never executed,
  whatever it returns. So a native's loop (`foreverInternal`) is never run: it states `{endless(step)}`, and what the
  interpretation iterates is the step it is handed, to a fixpoint the domain's widening makes finite (§11.2). A
  blocking read can be honestly `uses Inf` and honestly bounded (an ADC's `0 .. 1023`); nothing forbids stating it.

### 3.3 The positions a meta is stated at

> **A meta is stated exactly where the compiler cannot see the value's supplier**, and whoever supplies it proves it.

| Position | Supplier | Visible to the compiler? | So |
|---|---|---|---|
| leaf return | the native | no | stated, axiomatic (R2) |
| parameter | every caller | not from the body | stated; each call proves it (§4.4) |
| `data` field | the constructor | not from a reader | stated; construction proves it (§4.5) |
| bodied return | the body | **yes** | **derived only (R3)** |

A brace on a bodied return is declined. Where the derived meta is not computed, nothing could fit it. Where the
derived meta is precise, a brace could only widen it. The one remaining argument for it is abstraction, which is a
modular certificate, and *Use-Site Verification* deliberately gives that up. Part II adds a fifth row: what a native
hands its code (§14).

Decision O (2026-10-23, §8.1, not built) reverses the last row: a bodied return may be stated, and the statement
replaces the derived meta, trusted rather than proven. The reasoning above assumed a statement must be checked against
the derivation; O does not check it, so it can narrow where the derivation cannot see.

## 4. Companions

A brace, a `where` and a parameter brace each desugar in `core` to a **companion**: an ordinary Eliot function over
metas, compiled in the compiler pool only and reduced by the one evaluator. The channel recognises a refinement
operation *only* by a companion being present. It never names a leaf, a branch construct or a domain.

### 4.1 A companion never names a meta type

Naming a meta type by spelling (`T` ⤳ `T$Meta`) failed for an alias, an alias of an alias, a computed type, a type
parameter and every slotless type. So no companion names one (`core/processor/MetaCompanionLayout`):

```eliot
def asByte(x: Byte) where rangeWithin[0, 255](range(x)): Byte = x
  ⟶  asByte$Where[Meta$x: Type](x: Meta$x): Bool = rangeWithin[0, 255](range(x))

def add(a: Int, b: Int): Int {a.range + b.range}
  ⟶  add^Meta[Meta$a, Meta$b, Meta$, Constructor$](constructor$: Constructor$, a: Meta$a, b: Meta$b): Meta$ =
         constructor$(a.range + b.range)
```

- **The binders.** A companion takes the def's own binders, as the effect desugar leaves them (handles and handed
  binders included, so they line up with the callee's monomorphization key), then one binder per parameter, then for
  a transfer one for the result.
- **Where the types come from: the payload signature.** Each def with a companion gets
  `<name>$Payload[<its binders>]: Type = Function[P1, Function[…, R]]`, the parameter and result types *as a companion
  reads them*. A **code parameter reads as what its code yields**, including a function-typed one
  (`f uses *: E => A` reads as `A`; `EffectSugarDesugarer.yielded`). The channel reduces it at the callee's type
  arguments, peels as many arrows as the companion takes parameters, and instantiates the binders with
  `MetaInterpreter.metaTypeOf` of each. It counts the companion's own leading lambdas, since a signature's arrows
  cannot tell a parameter from a function-typed result.
- **The meta constructor is passed, not named.** A slot-producing brace takes `constructor$` first, and the channel
  passes the value constructor of the result's evaluated meta type (`metaConstructorOf`). So `{closed(0, 255)}` on a
  leaf returning an alias of `Int` states the same meta as on `Int`. A structure-producing brace (a bare
  type-parameter result, such as `fold`'s `{join(whenTrue, whenFalse)}`) takes none. The channel tells the two apart
  by the companion's leading parameter.
- **A `Unit` meta is known**, because it is produced as `unit` where its value is: a value whose type declares no meta
  has the meta `unit`, whether a constructor built it, it is a function value, a frame or a cell, a leaf stating
  nothing returned it (the leaf's implicit statement), or it is a parameter of a root context (§6). So it reaches a
  transfer and a `where` as any other known meta does. The implicit default is the only place that knows `Unit`
  (`MetaInterpreter.metaTypeOf` and `untrackedMeta`); a consumer never asks whether a meta type is `Unit`. An argument
  whose meta is not computed is passed as a dedicated neutral that never reads back, so a companion that projects it
  or stores it produces no meta (§13.5).

### 4.2 A companion's identity is its callee's

A companion keeps its callee's qualifier, **nested**: `Qualifier.Meta(of: Qualifier)`, the meta namespace *of* a
namespace. So `Numeric[Int]::add`'s transfer cannot collide with a bodied `add` in the same module.

- Reusing the implementation's own namespace would fail, because `AbilityImplementationCheckProcessor.checkNoExtras`
  reports any member the ability does not declare.
- Stripping the qualifier merged the two values, and both readers were wrong at once (S7).

The emitters own the names (`MetaTransferDesugarer.companionName`, `MetaWhereDesugarer.companionName`), and every
reader names a companion through them. Readers that ask *"is this a compiler-pool-only channel artefact?"*
(`CompilerNativesProcessor.isRefinementArtifact`, `RowChecker.checkable`, `CoreProcessor`) ask the namespace, never
a name suffix.

### 4.3 A brace's literals are compile-time numbers

An interval endpoint is a `BigInteger`, and a value-position literal is an `Int`, with no widening between them. A
brace's companion is compiler-track code, exactly like a signature (`CoreProcessor.isMetaBody` ⤳ the converter's
`compilerTrackContext`), so its literals are read as compile-time `BigInteger`s:

- an endpoint is written `Bounded(0)`;
- a negative one is a subtraction the compile-time `Numeric[BigInteger]` reduces (`Bounded(0 - 1)`; the language has
  no negative-literal syntax).

An *ordinary def body* is still runtime-track, so a helper called from a brace cannot name a `BigInteger` constant in
value position. That is why `rangeWithin[Lo, Hi]` takes its bounds as type parameters, and why a reusable meta value
puts its literals in type position:

```eliot
def between[Lo: BigInteger, Hi: BigInteger]: Interval[BigInteger] = closed(Lo, Hi)
def byte: Interval[BigInteger] = between[0, 255]
```

The `TODO.md` item on unifying `Int` literal handling would remove that.

### 4.4 Parameter braces

`x: Int {closed(0, 255)}` states the meta of the value at that parameter. A brace *before* a type is an effect row,
which is a parse error outside a row alias; a brace *after* a value parameter's type is its meta. A code parameter
takes none.

- **The statement companion** (`MetaParameterDesugarer`, `<def>$Parameter$<p>` in the def's meta namespace) lists the
  slots of the parameter's meta and takes `constructor$`. It reads **no parameter**: its parameters are placeholders
  that only count and place.
- **Every full call proves it:** `join(argument, stated) ≡ stated`, compared structurally. The join is the meta type's
  own instance, reached through `eliot.compiler.Meta::joined[D ~ Meta]`. A brace on a parameter whose type carries no
  meta is reported at the call.
- **Inside the body**, demands read what each call supplies (§6). The call has proven that it fits.
- **A def with a braced parameter cannot be passed as a value**, bare or partially applied, for the same reason a
  `where`-bearing one cannot: the demand would escape through a function value the channel never sees applied.

### 4.5 Field braces and handed statements

`data Pixel(r: Int {closed(0, 255)}, g: Int)` states the meta of the value field `r` holds.

- **Construction is a parameter brace.** A field *is* its constructor's parameter (both are an
  `ArgumentDefinition`), so `MetaParameterDesugarer` runs over the data-desugared definitions too, and every
  construction proves its argument by §4.4's demand.
- **A read goes through the eliminator.** An accessor's body is a `match`, and a `match` calls the type's
  `handleCases`. Both reach the field as a handler's parameter. The eliminator is body-less, so what it hands a handler
  has a supplier the compiler cannot see, and it is stated by a **handed statement**,
  `handleCases$Handed$case<i>$<j>`: what the eliminator hands handler *i* as argument *j*, with the field's brace
  copied in (`MetaParameterDesugarer.handedStatement`). It has a parameter statement's shape plus one binder, the meta
  type of the handed argument, which the channel reads off the handler's own function type. So a field of a generic
  `data` is stated at its instantiation.
- **The channel reads it** through one lookup (`MetaInterpreter.handedMetas`), both when deriving (a handler is
  applied to the stated metas) and when checking demands (an arm's binders are bound to them). It is axiomatic, and it
  holds because the constructor is the only supplier and proved it.

A handed statement has no user spelling: only the data desugarer writes one. The channel's reading is general,
though: any body-less callee stating what it hands a code parameter is read the same way. That is what §14 builds
on.

### 4.6 The eliminators state the join of their arms

`Bool::fold` and every data type's `handleCases` are body-less leaves that take each arm as **code** and state the
join:

```eliot
def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A) uses *: A {join(whenTrue, whenFalse)}

data Shape = Circle(r: Int) | Empty
   ⟶  def handleCases[R](value: Shape, case0 uses *: Int => R, case1 uses *: Unit => R) uses *: R
          {joined(case0, case1)}
```

A `match` is the same thing `fold` is, an eliminator, so the channel needs no branch construct. The generated brace
names `eliot.compiler.Meta::joined` by its module, so a data type's module needs no import. `PatternMatch` declares no
signature for `handleCases`, since the arity is per type. Each constructor class's `handleCases` applies its own
handler to its fields.

## 5. The interpretation

`monomorphize/channel/MetaInterpreter` answers one question: *what is this node's meta?* It answers it by walking the
real monomorphized body:

- **Parameters** are bound to the caller's argument metas.
- **Leaves** are substituted by their stated transfer, reduced by the one evaluator.
- **Every other callee** is interpreted recursively.
- **A call that does not return** is ⊥ (`Value.Never`, §12): a leaf states it with `{never}` or `{exit(…)}`, a call
  any of whose value arguments is ⊥ is ⊥, and a stated transfer reads a ⊥ yield as a sibling of the same meta type.
- **Frames and cells** (§11.1) are followed through the one call that made them: the interpretation threads a store of
  them through every step, in Eliot's strict evaluation order, and code a native runs is followed run by run (§14).
- **A loop** (§11.2) is the fixpoint of its code: a native states it (`looped`, `endless`), and the interpretation runs
  the code from the loop's initial accumulator until neither the accumulator nor any cell changes.
- **A value a `data` constructor built** is held with its fields as the interpretation holds them, and with its
  structural meta (§13), and a `match` on it runs only the arm its constructor selects. Both are generated statements
  (§13.4), so the channel names no constructor and no eliminator. Within one interpretation a field read is the very
  field; across a boundary the interpretation does not walk (a parameter, a call node of the caller, a cell) the value
  is its structural meta, and a field read is the field's meta read out of it (since T5).

### 5.1 State or derive

Whether a callee is summarised or read is one `ifM` on the test R2 and R3 enforce: the companion lookup. So the
closure invariant of §1 is executed and enforced by the same two facts, and the two cannot drift into two opinions.
`def double(x: Int): Int = add(x, x)` at `60` is `[120, 120]`, and `label("abcd")` with `def label(s: String): String
= s` is a size of `[4, 4]`. A pure `val` block is interpreted too: it lowers to an applied lambda, so its bindings are
ordinary parameter bindings.

The interpretation **raises no diagnostic and records nothing**. A record is keyed by source position *in the value
being walked*, and a demand by the position it is written, so interpreting a callee inside its caller must do neither.
Otherwise a callee's positions would land in its caller's table, and its preconditions would be reported once per
call site.

### 5.2 Closures and code arguments

A function value is a **closure**: the function-typed node and the environment it was written in (`Value.Closure`).
It is interpreted only when something applies it, so it costs nothing until then.

```eliot
def applyTo(f: Int => Int, x: Int): Int = f(x)
def shift(k: Int): Int = applyTo(y -> add(y, k), 10)                 // shift(5) is [15, 15]
def compose(f: Int => Int, g: Int => Int): Int => Int = a -> f(g(a))  // compose(…)(10) applies the returned closure
applyTo(double, 10)                                                   // a def passed as a value is a closure too
```

`Function::apply` is not a leaf: the `lang` layer bodies it as `f(a)`. A stated transfer cannot apply a closure,
because a companion is Eliot over metas. So the closure at a code parameter is applied to the arguments its runs pass,
which carry no meta (a unit, handles), and **the companion receives the yield**. That is how `fold` and `handleCases`
join their arms. When a handled statement exists (§4.5), the handler is applied to the stated metas instead.

### 5.3 Budget, gate, memo

- **The budget is a step count**, `interpretationBudget = 1024` per call node, threaded through depth *and* fan-out
  together. It was 256 until T4: a loop runs its code up to about four times, and `forever` over a `putState` and a
  `raise` took more than 256 steps. Four times as many changed no example's jar or compile time. A hard count bounds a diamond-shaped call DAG absolutely, and a cache would not: distinct argument metas
  defeat its key. **Running out of it is a compiler limit** (decision I, §18, built 2026-10-22): the derivation answers
  that it ran out (`MetaInterpreter.metaOfCall` gives `None`, and a loop cut short before its fixpoint counts too),
  and `RefinementChannelProcessor`, the walk that asks for every call's meta once, reports *"Computing the
  meta-information of this call of 'f' takes more than 1024 steps"* at the call. `MetaDemandsProcessor` asks once per
  context, so it reports nothing of its own: it reads the call as unknown, which fails any demand on it. What the
  derivation had got to is never handed on.

  **Open (2026-10-22): decision I as built makes eliot-build fail to compile.** The limit fires at `main`'s call of
  `answer`, the whole build tool, at 1024 steps, at 16 384 and at 262 144 alike, so the cost grows exponentially, the
  diamond-shaped DAG this note names, and no budget is the answer. Before I such a call was left not computed, which
  nothing read. It is on `master`, unreleased, and every dependent pins `v0.18`, so nothing is broken yet, but no
  release can be cut until it is decided. The candidates are the memo above, measured first, and narrowing what I
  reports; either changes a decided rule, so it waits on Robert.
- **There is no type gate (since T3).** A call whose result type carried no meta used to be skipped before any body
  was fetched, which kept the interpretation off most call nodes. T3 removed it: a cell written by a call returning
  `Unit` (`putState`) must be followed, and so must a `Unit` call that never returns. Measured on a cold compile of
  every example, one at a time: 214.2 s before, 219.6 s after (+2.5%), no example more than 1.5 s apart, and every jar
  byte-identical.
- **There is no memo.** A memo on `(vfqn, typeArgs, argMetas)` is the prepared answer if the budget or the clock is
  ever observed to bind. Measure it with `--statistics`, diffing a run with and without the flag, since the flag
  itself inflates a build by about 20%. P5's contexts are already such a memo, for demands (§6).
- **A loop's meta is a fixpoint** (§11.2, built in T4). A loop lives in a native, which states it through a primitive
  of the transfer language recognised by name (`looped`, `endless`), because the channel names no leaf. *Finite* is
  not *computable at compile time*: `readLines`' size is truthfully `0 .. 2³¹−1`. So the iteration count is never run
  out; the domain's widening makes the fixpoint finite.

## 6. Demands are checked in context (P5)

Every meta the checker demands is a compile-time value, and every one is known. The program is monomorphized from
`main`, which takes no parameters, so each call chain starts at known metas: literals, leaf statements, `Unit` for a
handle. *Known* is not *narrow*. `whole` is known, and `useByte(parseInt(line))` fails because the value does not
fit, not because it is missing.

> **A demand (a callee's `where`, a parameter brace) is checked in every meta context in which its call is reached
> from `main`, with the argument metas of that context.**

```eliot
def useIt(y: Int): Int = useByte(y)
printLine(show(useIt(42)))      // passes: `y` is [42, 42] in this context
printLine(show(useIt(1000)))    // fails at `useByte(y)`, reached from here with `y` ∈ [1000, 1000]
```

This is not a derived `where`: `useIt` gains no precondition. The uses the program makes are checked, which is what
*Use-Site Verification* already says. An error a caller causes in a callee's body is the cornerstone's accepted *type
error not of their making*, reported at the use.

- **A demand is a fact per context.** `MetaDemands(vfqn, typeArgs, context)` is one body's demands checked in one
  context, with a `Known`, `Unknown` or `Symbolic` entry per leading parameter (`MetaDemandsProcessor`). The fact cache
  is the memo, so a context reached from many calls is checked once.
- **The root context is the definition, made symbolic.** Each meta-carrying parameter is `Symbolic`, and a handle, a
  function or a code parameter is `Unknown`. A demand that is unknown only because a symbolic parameter decides it is
  **deferred**, and a call to that value asks for the context its arguments make instead. A body that references
  nothing demanding is never walked.
- **Each violation is reported once, at the use that decides it.** A non-root context reports nothing itself. Its
  violations travel in its fact, and each caller lifts them to its own call until they reach a root context:

  ```
  Probe.els:8:19: The precondition of 'Probe::useByte' is not satisfied by the argument's meta-information.
  8 |    printLine(show(twice(100)))
    |  In 'Probe::useIt' at Probe.els:4:26, where 'y' is Int$Meta(Interval(Bounded(200), Bounded(200))).
    |  In 'Probe::twice' at Probe.els:5:26, where 'z' is Int$Meta(Interval(Bounded(100), Bounded(100))).
  ```

- **What a call does not apply is unknown.** A bare reference or partial application of a value whose root defers is
  checked with its unapplied parameters `Unknown`. So `call(useIt, 5)` fails loudly instead of escaping through a
  function value.
- **The demands walk keeps what a closure captures.** A call whose result is unknown because a closure captured a
  symbolic parameter is deferred, not reported in the root.
- **The stamping walk demands nothing.** `RefinementChannelProcessor`'s table waits on the value's root context,
  which keeps the reporting where it was. Demanding at the `WovenValue` seam instead would withhold a violating value's
  woven body, and with it every later value's own diagnostics.

## 7. Representation

The channel stamps each node's meta on the **refinement table**, keyed by source position, and the backend derives
each `Int` node's width from it (`IntRepresentation`). Byte, Short, Int, Long and `BigInteger` are a backend's choice,
never meta.

- **Metas are invisible to monomorphization identity.** A body has one layout, stamped at its parameters' boundary
  width. The interpretation's output is consumed at the **call node in the caller**. Letting a call site's parameter
  meta narrow a callee's interior would need call-site-sensitive layout or meta-split monomorphization, and both are
  refused. Getting this wrong arrives as a `ClassCastException`, not a type error.
- **What a meta is, and what may be stamped, are separate questions.** The interpretation answers the first
  everywhere. The stamping walk answers the second conservatively, and it still drops every record inside a lambda. A
  def's parameters are leading lambdas in its monomorphized body, so **a parametered def's own table is empty**: its
  literals and leaf calls are laid out at the boundary width. That is a representation decision (what a narrowed value
  may be stamped as behind an `apply` bridge), not a meta one, and it is open.
- **Ambiguous positions are recorded too.** Desugaring makes positions non-unique: a `val` block's synthesized lambda
  and application share the bound expression's range. So the channel records a not-computed verdict as well as a known
  one, and an aliased position is ambiguous and stays at the boundary width. Any new recording site inherits this
  obligation.
- **A value is emitted at the width its consumer will read, and a consumer that adapts to any width reads it as it
  arrives** (S9). A call boundary hands an integer back at the boundary width (the bignum), and three edges skip the
  round trip:
  - a boundary result meeting a boundary-width slot (an argument, a constructor argument, an `apply` bridge, a method
    return), via `createExpressionCodeAtBoundaryWidth`;
  - an intrinsic's operand, which unboxes from any width (`createExpressionCodeUnconverted`, `unconvertedRepOf`);
  - a constant, which is pushed at the consumer's width.

  The result-edge re-encode (`convertResultFromBoundary`, S3) stays as the fallback, and no consumer in today's tree
  reaches it. The trap is that a compute-domain decision (`viaBigInteger`) reads the **node** meta (`nodeRepOf`),
  never the arriving width. Otherwise every boundary operand would flip to `BigInteger` arithmetic.
- **A `fold` merge** converts each arm from the boundary width, since an arm is a thunk and a function value hands its
  result back at that width whatever its body knew. An intrinsic's own result and a `fold` merge are the two places a
  narrow width is materialised.
- A lying statement no longer truncates at a call boundary. It truncates wherever the narrow width is next
  materialised, or nowhere. That does not weaken R2, which was always axiomatic, but the backend is no longer an
  accidental second check on it.

## 8. What is not computed yet

Each of these is a gap: fail-safe at every demand, and pinned by a test that changes visibly when it closes.

| gap | example | closed by |
|---|---|---|
| a `data` type recursive other than through a field of its own type | `Rose(kids: Option[Rose], value: Int)`, or `A(b: Option[B])` with `B(a: Option[A])`: the structural map has no finite meta type, so the value's meta is not computed | the recursive group of §13.3, deferred to the recursive-data fold (decision J, §18) |
| a demand on a closure the walk cannot see applied | `applyTo(y -> useByte(y), 10)`; `useByte(f(x))` with `f` a parameter | a context that can carry a closure (a fact key holding a body and an environment), when a program needs it |

Decision I closed the step budget's row (2026-10-22): running out of it is an error, never a gap (§5.3), though see
the note there. T6 closed the `List` row the same day: a fold's element is every item's meta (§17.1). T5 closed the
three rows above the `List` one: a field read out of a value the interpretation did not build, the join
of two constructed values, and with them `if … else`. Each was a structure that could not carry what flows through
it, which is what Part II finds.

The `eliot-code` skill tells users what a range is derived through and what is not computed today. It was probed
against the tree, not taken from this document. Update it when a row closes.

### 8.1 A hole in the design: custom metas

The rows above are metas the channel does not compute yet. This one is a meta nobody can **declare**.

> **Required (Robert, 2026-10-22): custom metas should be possible.** A program defines meta-information for its own
> `data` types: it implements `Meta` for a domain of its own, and describes how a value's meta is calculated.
> Everything else (deriving it through bodies, joining it at branches, widening it in loops, demanding it with `where`
> or a brace) is the channel's, as for `range` and `size`.

**Today nothing but the `Meta` instance can be written.** Probed at `e5fe6e94` with a `Sign` domain
(`data Sign = Negative | Zero | Positive | AnySign`, with `Eq[Sign]`):

| Attempt | Result |
|---|---|
| `implement Meta[Sign] { def join… def widen… }` | Compiles, and is inert: no type can carry a `Sign`. |
| `data Temperature {sign: Sign}(degrees: Int)` | Parse error. Only `type` parses a slot brace (`TypeAliasDefinition`); `DataDefinition` has none. |
| `type Celsius {sign: Sign} = Int` | Parses, and the slot is dead. A meta type is read off the *evaluated* type (§4.1), so a `Celsius` has `Int$Meta`, and `sign(c)` in a `where` is "Type mismatch. Expected: Celsius$Meta, Actual: Int$Meta". |
| `type Reading {sign: Sign}` and a body-less `def readSensor: Reading {Positive}` | "Function not implemented." A slotted *abstract* type and the leaves that state it (R2) need a platform layer, which a user module is not. |
| `def warm: Temperature {Positive} = Temperature(5)` | R3: a bodied value derives and may not state. |

**Why it is a hole and not a rule.** Two decisions produce it, and neither was taken to forbid custom metas:

- **R3** (§3) keeps a statement off a bodied value because the compiler can see the body. Its consequence, that *"a
  user module cannot author a transfer"*, was recorded as a consequence, never decided as a goal.
- **§13.6** gives a `data` value's structural meta no surface, so the generated records may be named freely. It says
  nothing about a meta a `data` type declares beyond its fields.

The user documentation says the opposite of the requirement today, accurately for the tree: the site's *Under the
hood* chapter (`eliotlang.github.io`, `_docs/meta-domains.md`) says a program cannot add a new kind of
meta-information, and why. It changes when this closes.

**Decision O (2026-10-23, not built): a brace on a bodied value states its result's meta, replacing the derived
one.** Robert, after declining a proof-based alternative: *"I definitely don't want proofs, nor properties. I don't
want to prove anything, I just want values. … 'requirePositive' _should_ constrain n's meta. Not be calculating it,
but by _defining_. … This would only require that we can 'override' the calculated meta with our own. We don't need
to check it. If the user knows he/she can constrain the value more than the automated calculation does, he/she is
free to do it."* Then: *"pure trust, replace result only"*.

```eliot
def requirePositive(n: Int) uses Abort: Int {positive(range(n))} = if(n > 0) n
```

Here `positive` stands for an ordinary compile-time function over an interval that raises its start to `1`. It is
illustrative: the base has no such function yet. Without the brace, the result's meta is `n`'s, unchanged, because
the derivation cannot learn from a condition (`if` is `fold(condition, value, abort)`, and the `abort` arm is ⊥). The
author knows what the condition means, so the author says it.

The rule, which replaces R3:

1. **A brace on a value's return states that value's meta, wherever it stands.** On a native it is the only meta, and
   R2 still demands it. On a bodied value it is optional, and when present it **replaces** the derived meta of the
   result. A brace reads the argument metas, as a native's does (`{length(s)}`), and is reduced by the one evaluator
   like every companion.
2. **Pure trust.** Nothing checks it: not against the derivation, not at runtime, not in a test build. It has the
   standing of a native's statement (§3.2), now in any module. Two safeguards were offered and declined: an assertion
   inserted at the return in test builds where the brace says more than the derivation, and an intersection with the
   derived meta, so a brace could never widen.
3. **The result only.** The interpretation still walks the body: cells, frames and exits inside it are followed as
   before (§11.1), and only the meta of the value the body returns is replaced. A call whose derivation is ⊥ (it never
   returns) stays ⊥, since ⊥ is a fact about control and not a meta (§12). So a brace is **not a summary**: it saves
   no interpretation steps, and it is no answer to decision I's cost (§5.3).

**Accepted consequence: a wrong brace is silent, and it is the first such place a user writes.** The backend lays
out from metas (§7). A brace on an `Int` that is narrower than the values it returns makes a width that wraps them,
and once a `size` sizes a static buffer, a brace too small for a list makes code that writes past it. A native's
statement always carried this risk (§3.2: *"a bound that is merely usually true is a bug"*); O extends it from a
handful of platform files to every module. This is a deliberate exception to the rule that a gap is fail-safe, and it
was taken as one.

**What it reverses**, each recorded rather than rewritten in place: R3 (§3), and with it *"a user module cannot
author a transfer"*; §3.3's row *"bodied return: derived only"*; and §20's *"a brace on a bodied return"*. R2 stands.
Parameter braces (§4.4) and field braces (§4.5) are unchanged: those are still proven by whoever supplies the value.
When built, `MetaTransferAccountingProcessor` loses its R3 arm.

**How it answers custom metas.** A `data` type declares a slot, and its constructor states it from the field metas,
since the generated constructor is a leaf and R2 asks a leaf to state:

```eliot
data Temperature(degrees: Int) {sign: signOf(range(degrees))}
def warm: Temperature {Positive} = Temperature(5)          // a bodied value may now state, by O
```

The constructor statement is the slot's default, and with O any bodied function building a `Temperature` may
state a narrower one. O applies to every domain, `range` and `size` included, with no confinement to user domains.

**Superseded the same day:** the two-part proposal this section carried (a constructor statement, and a trusted
statement confined to user domains), and a third offered in conversation, of named properties introduced by a
checking primitive, which Robert declined: Eliot tracks values, not proofs (§20).

**Still open** (§17.2's Q3–Q7 propose answers to the first four, for sign-off):

- **The slot beside the structure.** A `data` meta is already the structure of §13.1: reach, then field metas. A
  declared slot is a second component. Is the meta a pair (structure, slots), joined component-wise, the slots
  through the user's instances? Or is a declared slot the *only* meta, replacing the structure? The latter would lose
  the field metas T5 carries.
- **The `data` slot's spelling**, which does not parse today (the probe above).
- **Sums.** One statement per constructor, each over its own fields; their join is the user's.
- **Generic `data`.** `MetaConstructorDesugarer` does not thread a type's generic parameters onto its meta structure
  yet, so `data Box[A](…) {…}` needs that first. `MetaOf[A]` (decision L) is the spelling such a slot would use.
- **Whether a brace may read the derived meta** of its own body, to narrow it rather than restate it. Not needed for
  `requirePositive`, which narrows its argument's.
- **A slotted abstract `type` in a user library**, for platforms to implement, is a separate question: it needs a
  layer, not just a declaration.

**Decided 2026-10-23** (§18, Q3–Q6 and decision P): the spelling is slots on the head and a statement per constructor
(Q3); a declared slot is the type's own and replaces nothing, and a field's meta is read through the type's own
accessor, nested, never flattened into the type's meta (P); every constructor states every slot (Q5); a slot on a
recursive `data` and `MetaOf[A]` in a `data` slot wait for O6 (Q6). A sum's fields have no meta accessor, as they
have no value accessor (P). Still open from the list above: whether a brace may read its own body's derived meta, and a
slotted abstract `type` in a user library.

> **Partly reversed the same day by decision R** (§18): meta code reads no field's meta. A field accessor applied to
> a meta would need the meta to mirror the runtime structure in public, and the compiler generates no such mirror.
> A type's meta is what its own slots declare, and a constructor's statement flattens what it needs from its fields
> into them. P's other half stands: a declared slot is the type's own and replaces nothing, so it sits beside the
> structural meta, which stays without a surface (§13.6).

**A precision gap the derivation should still close.** A field holding a list built by a fold has the loop's widened
meta (§11.2), so a derived `transpose` loses its matrix's dimensions. A brace can now restate them; iterating once per
count where a loop's count has a known range would derive them (§18, "Open, not blocking").

### 8.2 Not a meta: cycles, stack and peak memory

Robert, 2026-10-23: metas were meant to calculate *"memory allocation and runtime time / instruction count on
microcontrollers"*, and he doubted the second was a meta at all, since optimization changes it after the stage metas
are computed in. The split:

- **A value's size is a meta.** How big a value is (an `Int`'s width from its `range`, a buffer's capacity from
  `List::size` or `String::length`) is information the value carries. The backend already derives a width from it
  (§7). Sizing a static buffer for an MCU is the same move and needs no new mechanism. Byte size stays the backend's
  derivation, not a slot (§2.2).
- **Cycles, stack depth and peak memory are not metas.** They belong to a *computation*, not to a value. They compose
  along control flow (summed in sequence, the hull over branches), not by a value join. And they are final only after
  lowering, inlining and folding, so they are computed over the code a backend emits.

What the channel owes them is **bounds**: a loop's iteration count is a `size` meta, and an operation's width is a
`range`. The design that consumes them is `docs/grades.md`.

---

# Part II — Completing totality (design, decided 2026-10-21)

## 9. The finding

Probed against `b204fbe3` with `useByte` (`where rangeWithin[0, 127](range(x))`):

| expression fed to `useByte` | today |
|---|---|
| `fold(c, 5, 7)` | proven |
| `foldOption(7, a -> 9, runAbort(if(c, 5)))` | proven: no field is read |
| `foldOption(7, a -> a, o)` with `o = None` | not computed |
| `if(c, 5) else 7` | not computed |
| `orZero(Some(7))` | not computed |

Trace `if(c, 5) else 7`:

```eliot
def else[A](computation uses *, Abort: A, fallback uses *: A) uses *: A = foldOption(fallback, a -> a, runAbort(computation))
def runAbort[A](obj uses *, Abort: A) uses *: Option[A] =                                       -- jvm layer
   escapeInternal((frame: AbortFrame[Unit]) -> obj with aborting(frame), (nothing: Unit) -> None, a -> Some(a))
def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T = fold(condition, value, abort)
implement aborting(frame: AbortFrame[Unit]): Abort { def abort[A]: A = exitInternal(frame, unit) }   -- jvm layer
```

Three things fail, and each is a value whose meta is not defined:

1. **The field read.** `foldOption`'s `Some` arm reads `v` out of the `Option`, and an `Option[Int]`'s meta is
   `Unit`. The structure cannot carry the field's meta.
2. **The exit.** `abort` is `exitInternal(frame, unit)`, a native whose result is a type parameter. R2 exempts it as
   *forwarding*, so it states nothing. But it forwards nothing: it produces an `A` it was never given, because **it
   does not return**. `fold` joins `[5, 5]` with a not-computed meta and gets nothing.
3. **The escape.** `escapeInternal` is a native too. What it returns and what it hands `onValue` are facts only it
   knows, and it states neither.

By §1's principle, (2) and (3) are not lost precision. They are leaves that break R2, admitted by an exemption whose
premise is false. (1) is a structure too poor to hold what flows through it. Closing all three is what makes meta
total.

## 10. Every leaf states, type-parameter results included

**Decided 2026-10-20** (Robert: *"If it's implemented, the meta of the result is calculated. If it is native, it is
defined."*). This reverses R2's type-parameter exemption (§3). A body-less value on the runtime track states its
transfer whatever its result type is. "Forwarded" is a claim, so the leaf states it: `{x}` when it returns its
argument `x`, or a join of its code's yields.

R2 rides the runtime track's `MonomorphicValue`, so compile-track-only leaves are outside it (`typeMatch`,
`foldNamedValues`, the `exitIntrinsic`s, which the channel never interprets). Read from the tree, the runtime leaves
with a type-parameter result and what each must define:

| leaf | statement |
|---|---|
| `exitInternal[K, A](frame, err): A` (Abort, Throw) | `{exit(frame, err)}`, **stated since T3** (`{never}` in T2): its frame receives `err`, and it does not return (§11.1, §12). |
| `escapeInternal[K, A, R](body, onExit, onValue): R` (Abort, Throw) | `{joined(onExit, onValue)}`, **stated since T3**. It hands `body` a fresh frame, `onValue` what `body` yields, and `onExit` what `body`'s exits gave the frame, which is ⊥ when nothing exits (§11.1, §14). |
| `withCellInternal[S, A, R](initial, body, combine): R` (State, Writer, Dep) | `{combine}`, **stated since T3**. It hands `body` a fresh cell holding `initial`, and `combine` what `body` yields and the cell's content when `body` returns (§11.1). |
| `readCellInternal[S](cell): S` (State, Writer, Dep) | `{content(cell)}`, **stated since T3**: the cell's content where it is read (§11.1) |
| `foldLeftInternal[A, B](list, initial, combine): B` | `{looped(initial, combine)}`, **stated since T4**, the loop's fixpoint from `initial`, which it hands `combine` too (§11.2). The element it hands `combine` is every item's meta, **since T6** (§17.1). |
| `foldLinesInternal`, `foldCodePointsInternal` (File) | `{joined(onValue, onError)}`, **stated since T4**, once their holder was decomposed (§11.3): `onValue` is handed the loop's fixpoint, the step `{closed(0, 2147483647)}` per line and `{closed(0, 1114111)}` per code point |
| `resultValue[A](r: IoResult): A` (File) | none can be honest: it returned its holder's content as any `A`. **Decomposed away in T4** (§11.3). |
| `Bool::fold[A]` | already `{join(whenTrue, whenFalse)}` |

`foreverInternal` returns `Unit`, so R2 asks nothing of it, but it states `{endless(step)}` since T4: its step runs
again and again, and it returns only by an exit (§11.2).

The armed check is the proof of the table. Removing the exemption compiles the tree and the examples, and any leaf
the table missed is an error naming it. It was to wait on `foldLeftInternal`, waiting on `List` (§17), but
`foldLeftInternal` states since T4. **Tried after T4, the check found a leaf the table missed**: the data desugarer's
`typeMatch[R]`, the body-less member of every `data`'s type-level match implementation, which the backend generates
and which reaches codegen at `R = String` (`ExamplesIntegrationTest3`, "match on type-level values"). It was put back,
and stating `typeMatch` became what removing the exemption waited on.

**Built 2026-10-22.** `typeMatch[R]` states `{joined(matchCase, elseCase)}`, as `handleCases` states the join of its
handlers, and R2 judges every body-less leaf by its instance's evaluated result type: `forward[A](a: A): A` is reported
at `A = Gauge`, and accepted once it states `{a}` (`MetaTransferAccountingProcessorTest`). Every jar is byte-identical.
Stating the join took one change the plan did not list: `typeMatch`'s two handlers were value parameters, whose meta
is a function's, so a companion could not join what they yield. They are code now (`uses *`, written with
module-qualified `Function`, which is how a companion recognises the arrow it reads a yield off), as `handleCases`'s
handlers are and as a `match` arm is. That deleted the row walk's special reading of a type `match`'s arguments as code
whatever their types said (`BindingWriter.isMatchMachinery`). What `matchCase` is handed, a type argument of the
matched type, has no handed statement, so its meta is not computed; no program reads one.

## 11. What reaches a native through a frame, a cell or a loop

Three kinds of native receive a value from elsewhere in the run rather than from their arguments: an escape receives
what its body's exits carried, a cell what was written into it, a loop what its own previous iteration produced. Each
value has a meta, and the process follows it. **There is no top to state instead.** `ability Meta` gains no `top`:
the process needs none, and only a domain may have one, in its own value type (decided 2026-10-20, §18).

### 11.1 Frames and cells are followed

A frame and a cell are made by one call of their native (`escapeInternal`, `withCellInternal`), handed to that call's
body, and gone when the call returns (`docs/effects.md` §3.4). They are private and second-class, so they never
escape the call. That is the property `termination/PurityGuardTest` keeps, since a public cell is Landin's knot. So
while the interpretation runs that one call, it can follow them exactly, in Eliot's strict evaluation order:

- **A frame collects its exits.** Interpreting `exitInternal(frame, err)` adds `err`'s meta to what the frame
  received, and the call itself is `Never` (§12). When `body` is done, the escape hands `onExit` the join of what the
  frame received. If nothing exited, `onExit` is handed ⊥: its arm is unreached, as a `match`'s is.
- **A cell holds content.** It starts at `initial`'s meta. A write replaces the content, and a read returns it. Where
  control splits (a join over code arguments: `fold`, a `match`), each branch starts from the same content, and the
  content after is the join of the branches' contents. A branch that is `Never` contributes nothing. `combine` is
  handed the content when `body` returns.
- **An exit keeps the cells as they were.** Control leaves through a frame with every cell alive at that point, so an
  exit records the cells with what it carried, and `onExit` continues from the join of those. That is what keeps
  `runStateToPair(s, runThrow(c))`'s state across a `raise` (`docs/effects.md` §3.4), which a branch "contributing
  nothing" alone would have lost.

The interpretation gains a value for a frame and a cell, each with an identity local to one call and its received
meta or content. The natives say what they hand through a few compile-track meta primitives, recognised by name as
`joined` and `never` are (§14). So the channel still names no leaf. This is the one place the interpretation becomes
stateful, and the state never outlives the call that made it.

**Built in T3 (2026-10-21).** `MetaInterpreter` threads a `Store` (the cells and frames alive, by an identity local
to one interpretation) through every step, left to right, and stops evaluating a call's arguments at the first ⊥, as
strict evaluation does. A stated call's code arguments are each run once (`runCode`), handed what the callee states it
hands them (§14). A run whose statements read another run starts where that one left off: after its return
(`{body}`, `stored(body)`) or after its exits (`exited(body)`), and it is not made at all when it is handed ⊥. Every
other run starts at the call, and those are the callee's alternatives (`fold`'s arms, a `match`'s). The store after
the call joins what the runs that no other run continues end with, and the frames keep every exit any of them made.

A frame or a cell reaches the code using it as a **field of a handle** (`Aborting$Handle(frame)`, read back through
`aborting$frame` → `handleCases`), so following one takes following a constructed value through a `match`. That is two
more generated statements (§13.4): a constructor states `{built}`, and the eliminator hands each handler `fieldOf`
(`fieldOr` for a braced field) of its `value`, ⊥ when another constructor built it.

**Code the interpretation does not follow is fail-safe.** A call it cannot read (a leaf stating nothing, a callee whose
monomorphization failed, an unknown head, the budget spent) is handed whatever its arguments hold; if any of them
holds a frame or a cell, in a field or in a closure's environment, every cell's content becomes not computed and every
frame receives an exit carrying a not-computed meta (`Store.tainted`). Since a frame and a cell are second-class, an
argument is the only way code can reach one.

### 11.2 A loop is a fixpoint, which the domain makes finite

`foldLeftInternal`, the file folds, and a cell written or a frame exited inside `foreverInternal`'s step all run code
an unknown number of times. The meta of a loop's result is the join over every iteration count: `acc₀` is
`initial`, and `accₙ₊₁` is `join(accₙ, step(element, accₙ))`, up to its fixpoint. That is exact for every count, and
it is sound with no bound on the count.

The fixpoint is reached only if the domain's ascending chains are finite, and an interval's are not
(`[0, 0] ⊑ [0, 1] ⊑ …`). So **the domain supplies a widening**: an operation of its own, beside `join`, that makes
every ascending chain finite. An interval opens each endpoint that moved, so a counter's accumulator reaches
`Interval(Bounded(0), Unbounded)`. That is a value the domain already has. A domain of finite height widens by
joining. The machinery gets an operation from the domain, and no value.

Where the count is known exactly (a list whose size meta is `[n, n]`), iterating `n` times is exact and may be
preferred. That is a precision choice for later.

This reverses the recommendation the open "budget, not widening" decision carried (§19). A budget that ran out would
have to hand on a missing meta, and the process has none.

**Built in T4 (2026-10-21).** `widen(previous, next)` is a second method of `ability Meta`, beside `join`, so a domain
cannot forget it; `Unit` widens to `previous`, an interval opens each endpoint `next` moved past (`lowered`/`raised`
in the compiler overlay), and the derived `Meta[Int$Meta]` widens field-wise as it joins. The channel reaches it
through `eliot.compiler.Meta::widened(previous, next)`, which is `widen(previous, join(previous, next))`. A native
states its loop with two primitives of `eliot.compiler.Meta`, recognised by name as the frame and cell ones are:

```eliot
private def foldLeftInternal[A, B](list: List[A], initial: B,
                                   combine uses *: A => B {looped(initial, combine)} => B) uses *: B {looped(initial, combine)}
private def foreverInternal(step uses *: Unit) uses *: Unit {endless(step)}
```

`looped(initial, code)` is the loop's accumulator: on the domain `code` is handed it in, on the result, and on whatever
else is handed what the loop ended with (a file fold's `onValue`). `endless(code)` says `code` runs again and again and
the call does not return. `MetaInterpreter.runCode` runs a loop's code from the call, handed the accumulator so far,
until neither the accumulator nor any cell changes. What a run yields and the cells it ends with are **joined** into
the next run's after the first run and **widened** after every later one: the delay keeps a step that yields a
constant exact (`foldLeft(5, e -> acc -> 7, list)` is `[5, 7]`, not `[5, ∞)`), and an interval is stable after at most
two widenings. A run that does not return continues nothing, but its exits are kept, each with the loop-start cells as
they were. An accumulator that is a constructed value or a closure, changing from run to run, is not computed until
structural meta (T5). A loop whose code is handed a frame or a cell of its own is not followed. Proven in
`DerivedMetaTransferIntegrationTest` (a constant step, a growing one, a cell written per run, `forever` over a cell and
a frame). All 47 examples byte-identical.

### 11.3 A holder that returns any type is decomposed

`resultValue[A](r: IoResult): A` returns its holder's content as whatever `A` its caller names. Nothing relates `A`
to what the producing native put in the holder, so no statement can be honest. Per §1, the gap is closed in the
declarations:

- the file natives read a concrete result through typed leaves, each stating its own meta
  (`resultString(r): String {closed(0, 2147483647)}`, and so on);
- a generic fold returns its accumulator itself and takes its error path as code, so its result is the loop's
  fixpoint joined with what the error path yields, which is ⊥ when the path raises.

That is a platform decomposition written in Eliot, over smaller leaves.

**Built in T4 (2026-10-21), with one compiler change the design did not foresee.** `resultValue` is gone: the jvm
`FileSystem` reads a holder through `resultString` (stating the JVM `String` ceiling), `resultBool`, `resultStrings`
and `resultPaths`, each emitted with a cast to what its native put there. The folds take two continuations, as the
escape does, and the backend calls neither inside its `try`:

```eliot
private def foldLinesInternal[B, R](initial: B,
                                    step: B {looped(initial, step)} => String {closed(0, 2147483647)} => B,
                                    path: Path,
                                    onValue uses *: B {looped(initial, step)} => R,
                                    onError uses *: String {closed(0, 2147483647)} => R)
   uses *: R {joined(onValue, onError)}
```

`foldLines` passes `acc -> acc` and `message -> raise(IoError(message))`, so its result is the loop's fixpoint and the
error path adds ⊥. (`File.els` spells the primitives `eliot.compiler.Meta::looped`: importing the module would collide
with `eliot.collection.List`'s `joined`.) The step stays a **value** parameter, as `FileSystem` declares it. Code may write a cell, so were
the step code, `onError` would honestly start after any number of its runs, which no statement can say; a value cannot,
so `onError` starting at the call is true. So the arrow-domain brace (§14), until now on a code parameter only, is
accepted on a function-typed value parameter too: the definition applies a function value as it runs code, and what
it hands it is the same statement. The parser change is that, and nothing else.

## 12. ⊥: a value that is never produced

**Decided 2026-10-21 (decision B); built in T2 (2026-10-21)**, except what waits on later stages: a handed
`Unreached` argument is T5's (§13.4), and a frame receiving what exits through it is T3's (§11.1). The interpretation's `Value` gains **`Never`**: *evaluating this does not
produce a value.* It is the identity of every join. It is not a value in any domain. It is the interpretation's own
form, and no companion ever receives it.

- **Where it originates.** A leaf states it with `{never}` (`eliot.compiler.Meta::never`), recognised by name as the
  reduced result of a companion, as `joined` is reached by name. The exits state it: each jvm module's
  `exitInternal`, and `foreverInternal` may. It is axiomatic like every statement, and R3 is unchanged. An `Unreached`
  handed argument is `Never` too (§13.4).
- **Strictness.** Eliot is call-by-value in every value position, so a call any of whose **value** arguments is
  `Never` is never made, and is itself `Never`. That covers a constructor (`Some(abort)`), a derived callee, a stated
  one, and a `val` block. A **code** argument is a closure, not evaluated before the call, so its `Never` is the
  callee's business.
- **A stated transfer over a `Never` yield.** In place of a `Never` yield, the channel passes the companion another
  argument it reads **at the same meta type**. That is sound because statements are monotone (⊥ ⊑ v ⇒ f(…⊥…) ⊑
  f(…v…)), and exact for a join, which is idempotent. So `fold(c, 5, abort)` is `[5, 5]`, and a `match` with an
  unreached arm is the join of the reached ones. If every yield the companion reads is `Never`, the call is `Never`:
  `fold(c, abort, abort)` does not return. **Monotonicity becomes a stated obligation on statements**, and every one
  in the tree meets it (interval arithmetic, the joins, constants).
- **What is stamped.** A `Never` node is never materialised, so its width is irrelevant. It is stamped at the boundary
  width, which is a representation choice, not a meta claim.
- **A demand under ⊥ is vacuous** (decision C). A demand whose call is never made (a `Never`
  argument, or an arm handed ⊥) is not checked in that context. This is P5 read literally: a demand is checked where
  it is *reached*. `f(Some(5))` compiles over `case None -> useByte(1000)`, and adding `f(None)` reports at that call.
  Built for a call with a ⊥ argument and a `val` block bound to one; an arm handed ⊥ waits on T5.
- **⊥ through a call returning `Unit`** is followed since T3, which removed §5.3's type gate: a derived body is
  interpreted whatever its result type, so a call that always exits is ⊥ even when it returns `Unit`. (Before T3 it
  read as not computed: sound, since ⊥ ⊑ every meta, but it lost the pruning downstream, and a cell written in such a
  call was not followed at all.)

## 13. Structural meta: a `data` value carries its fields' metas

**Decisions E–G.**

### 13.1 The rule

> **The meta of a value of a `data` type is, per constructor, whether that constructor can have built it
> and, if so, the metas of the fields it was built with.**

This is the structural map, `meta(D(f₁: T₁, …)) = D$Meta(f₁: meta(T₁), …)`, plus the one thing a sum forces: a record
of **which constructors are reached**. Without that tag, the join of `Left(5)` and `Right("ab")` would have to invent
a meta for `Right`'s field, and a `match` could not tell that `Some(7)` never runs its `None` arm.

What does not change: R2 and R3 (constructors and eliminators are body-less leaves already, so they state, through
generated braces); the one evaluator; layout (a field is stored at its boundary width); and the surface, since a user
never spells a structural meta (§13.6).

### 13.2 The meta structure

Per `data`, the core desugar emits into the compiler pool (dead in the runtime pool, as `Int$Meta` is):

```eliot
data Option[A] = None | Some(value: A)
  ⟶  data Option$Meta$Some[M$value](value: M$value)                     -- one record per constructor with fields
      data Option$Meta[M$Some$value](None: Reach[Unit], Some: Reach[Option$Meta$Some[M$Some$value]])
      implement[M$Some$value ~ Meta] Meta[Option$Meta[M$Some$value]] {  -- field-wise, as MetaConstructorDesugarer does
         def join(a, b) = Option$Meta(join(None(a), None(b)), join(Some(a), Some(b)))
      }
      type Option$Fields[A] = Function[A, Unit]                          -- every constructor's field types, in order
```

- **`Reach[M]`** is one compile-track `data Reach[M] = Unreached | Reached(meta: M)`, with
  `implement[M ~ Meta] Meta[Reach[M]]`. `Unreached` is the join's identity. It lives with
  `Interval`/`Bound` in the compiler overlay, since a `data` may not be in the base. It is a bottom **for a
  constructor**, a fact about the program with one spelling, so it is not a second spelling of any domain's top.
- **One binder per field, not per type parameter**, instantiated by the channel. `metaTypeOf(Option[Int])` reduces
  `Option$Fields` at `[Int]` with the one evaluator, peels the arrows and maps `metaTypeOf` over the field types:
  `Option$Meta[Int$Meta]`. `$Fields` is the same device as `$Payload` (§4.1), for the same reason.
- **A field-less constructor's slot is `Reach[Unit]`.** So an enum carries a meta (`AB$Meta(A: Reach[Unit], B:
  Reach[Unit])`), which is what lets `pick(B)` derive `[20, 20]` (§15).
- **Excluded:** the generated meta structures themselves (a regress), and any type that declares **slots**. A slotted
  type's meta is what it declares (`Int`, `String`, or `docs/recursive-data.md`'s `data Tree[A] {depth: …}`).

**Built (T5, 2026-10-22)**, by `StructuralMetaDesugarer`, which `DataDefinitionDesugarer` calls for every `data` a
program, a library or a named implementation declares; the generated structures and a slotted type's are desugared
without one. Three things differ from the sketch above. A field-less constructor has a record too, an empty one
(`Option$Meta$None`), so its slot is `Reach[Option$Meta$None]` rather than `Reach[Unit]`: every slot's type then names
its constructor, which is what makes decision H free (§13.7). The names are `D$Meta$C` for a record, `D$Meta` for the
summary, `meta$D$C$f` for a record's field and `reach$D$C` for a slot, so no two `data` in a module collide. And the
instances join and widen through `eliot.compiler.Meta::joined` and `widening`, plain definitions, since generated code
cannot name the ability's own methods by module; `Meta` is resolved for it by fixed FQN, as `PatternMatch` is.
`Reach` is declared abstractly in the base (`type Reach[M]`) beside its overlay `data`, as `Bound` and `Interval` are,
so the runtime pool knows the module a compile-track probe asks about.

### 13.3 Recursive `data` folds into its own summary

The structural map does not terminate on a recursive type: `meta(Tree[A])` would contain `meta(Tree[A])`. Keeping
`Unit` there, as the first draft did, would lose the meta of everything stored in a tree, and metas are not lost. So a
recursive type's meta **folds its recursive positions into itself**: per constructor, its reach and field metas,
joined over every node of the value.

```eliot
data Tree[A] = Leaf | Node(left: Tree[A], value: A, right: Tree[A])
  ⟶  data Tree$Meta$Node[M$value](value: M$value)                  -- the recursive fields have no slot
      data Tree$Meta[M$Node$value](Leaf: Reach[Unit], Node: Reach[Tree$Meta$Node[M$Node$value]])
      Node(left, value, right) states  joined(left, right, ⟨Unreached, Reached(Tree$Meta$Node(value))⟩)
```

Here `⟨…⟩` is the meta constructor applied, `constructor$` (§4.1).

- **A recursive field gets no slot.** The constructor joins that field's meta, a summary of the same type, into its
  own. The eliminator hands a handler the whole summary for a recursive field. That is sound, because every node of a
  subtree is a node of the tree.
- **Nothing a value holds is lost.** The summary is finite, and every field meta of every node is in it. What it does
  not say is **shape**: how many nodes, and how deep. Shape is a property the size domain states as a slot of its own
  (`docs/recursive-data.md`'s `{depth: …}`, `List`'s `size`), and a slotted type keeps its slots (§13.2).
- **Mutual recursion folds over the group.** Types that reach each other share one summary, with a slot per
  constructor of every member. So `meta(A)` and `meta(B)` are the same summary for `data A(b: Option[B])` and
  `data B(a: Option[A])`.

**The test is on the declaration.** D's recursive group is the set of types whose `$Fields` bodies reach D and that D
reaches, followed through aliases, type-level definitions and other types' `$Fields`. So `data A(b: Option[B])` and
`data B(a: Option[A])` form one group, while `Box[Box[Int]]` forms none: `Box`'s field is its parameter, which is no
reference. A direct self-mention test misses mutual recursion. A cycle on the instantiated types' head constructors
misreads `Option[Option[Int]]`. The test is computed once per type FQN, reading resolved bodies and **never another
type's verdict**, so it cannot form a fact cycle.

**`List` is not `data` on the jvm.** It is a native type (`java.util.List`), so its meta is not generated and must be
declared. Its elements' meta would be a slot whose type is the element type's meta type, and no slot can be that today:
a slot's type is fixed (`Interval[BigInteger]`). That parametric slot, together with `size`, is the `List` domain's
project, and `foldLeftInternal` waits on it (§10).

**Built (T5, 2026-10-22), for a field typed by the type itself only.** A field is recursive when its type is the
`data` applied to its own type parameters in order (`left: Tree`, `left: Tree[A]`); its slot is left out, the
constructor joins it, and a `match` hands its handler the summary. The recursive *group* — recursion through another
type (`Rose(kids: Option[Rose])`) or across types (`A(b: Option[B])`, `B(a: Option[A])`) — is not built: the
structural map of such a type is infinite, and `MetaInterpreter.metaTypeOf` declines it on meeting a type it is already
expanding, so the value's meta is not computed (§8), fail-safe. That narrows decision G, and the sketch above does not
cover the case anyway: a field like `kids: Option[Rose]` is neither the type itself nor in a group with `Option` (whose
`$Fields` does not reach `Rose`), so the sketch's own test does not make it recursive, and its meta type
`Option$Meta[Rose$Meta[…]]` is infinite. The tree has one such type, eliot-build's `Clause(children: List[Clause])`,
which is finite today only because `List` carries no element meta; T6 makes it infinite too, and the cycle guard
covers it then as it covers `Rose` now. **Deferred by decision J** (§18) to the recursive-data fold.

### 13.4 The constructor and the eliminator state

**The constructor's transfer** is written by the data desugarer, as it writes `handleCases`'s join:

```eliot
Some[A](value: A): Option[A]  {Unreached, Reached(Option$Meta$Some(value))}
None[A]: Option[A]            {Reached(unit), Unreached}
```

This is an ordinary slot-producing brace taking `constructor$`. `metaConstructorOf` must learn to instantiate a
generic meta type at its arguments.

**The eliminator's handed transfers** read the eliminator's `value`. `handleCases$Handed$case<i>$<j>` answers a
`Reach`: `Reached(m)` hands `m`, and `Unreached` hands **⊥**, because no value of that constructor reaches the
`match`. `handleCases`'s own join is unchanged: arm pruning falls out of ⊥, not out of a new eliminator rule.

**A braced field** (§4.5) keeps its role as the construction's demand. On the reading side, the structural field meta
is at least as precise, because construction proved the argument fits and the structure stores the argument's own
meta. So `Pixel(5, 0).r` reads `[5, 5]`, not `[0, 255]`. Where the subject's meta was not computed (a gap, §8), the
brace still answers, as today.

**Accessors need nothing.** An accessor's body is a `match`.

**The value-level half is built (T3, 2026-10-21).** Following a frame or a cell needed the link before structural meta
existed, so the data desugarer already writes it, as statements the channel follows by name (§14):

```eliot
data Option[A] = None | Some(value: A)
  ⟶  Some(value: A): Option[A]  {built}                          -- every constructor
      handleCases$Handed$case1$0 = fieldOf(value, Some)            -- every argument of every handler
      handleCases$Handed$case0$0 = fieldOf(value, None)            -- a field-less handler's unit
data Pixel(r: Int {byte}, g: Int)
  ⟶  handleCases$Handed$case0$0 = fieldOr(value, Pixel, ⟨byte⟩)  -- a braced field: its brace where `value` is not known
```

`{built}` makes a constructor's call the value it built, its fields as the interpretation holds them. `fieldOf` hands
that field when the handler's own constructor built `value`, and ⊥ when another did, so the handler is not run and the
eliminator's join is the arm that was. Where the interpretation did not build `value` it hands nothing known, and
`fieldOr` reduces to its brace (it is bodied as its third argument), so a braced field answers as it did (§4.5). The
constructor is written without type arguments, since brackets on an upper-case name make a type application. A named
implementation's handle is a `data` like any other, so `CoreProcessor` runs the companion desugarers over the named
implementations' lowering too.

T5 gives the same statements their metas: `{built}` becomes the constructor's transfer above, and `fieldOf` its handed
transfer, reading `Reach`.

**Built (T5, 2026-10-22).** The statements took their arguments: a constructor states `{built(⟨…⟩)}`, whose argument is
its transfer, applying the meta constructor itself inside its recursive fields' joins (`MetaTransferDesugarer` leaves
such a brace unwrapped), and `fieldOf(value, C, read)` hands a handler `read`, `reached(reach$D$C(value), meta$D$C$f)`,
or `reachedThen(…)` for a field-less constructor's unit and a recursive field's summary. Both are bodied as their last
argument, so a reduced statement is its meta; the channel still recognises them by name, building a `Value.Data` that
keeps its meta, and handing the very field of one the interpretation built. `fieldOr` is gone: where the scrutinee's
meta is not computed, a braced field is its constructor's parameter statement, which every construction proved, at the
scrutinee type's arguments, since a constructor takes its type's parameters. The `main`-level walk reads the same
statements (`MetaInterpreter.handedMetas`), with the call's other arguments walked first, so a demand inside an arm
reads the field too, and an arm handed ⊥ demands nothing.

### 13.5 A field's meta is always defined

A constructor's transfer stores each argument's meta. By §1, each argument has one: a computed or stated meta, or the
known `Unit`. So a structural meta has no unknown field, and needs no per-field "unknown" form. A `Known | Unknown`
wrapper would have been a second spelling of every domain's top.

A gap in an argument is a gap in the construction. It propagates, and is never replaced by anything. That needs two
mechanical repairs to the stated-transfer path, and both are independent of `data`. **Both are built (T1)**:

- **A not-computed argument reaches a companion as a dedicated neutral, and a reduced result containing it is not
  computed** (`Marker.NotComputed`, `MetaInterpreter.companionArgument`). It used to arrive as a `SemValue.VType`
  placeholder. That was safe only because every companion *projects* its arguments: `range(a)` stalls on it. A
  constructor transfer *stores* its argument, so the placeholder would have quoted into the structure as `Type`, a
  well-formed and wrong meta. A statement companion's placeholders, which read no parameter, are the same neutral.
- **A `Unit` meta is passed as `unit`**, never as a placeholder. It is known (§4.1).

### 13.6 Structural meta has no surface

> ~~**Partly reversed 2026-10-23 by decision P** (§18): meta code reads a single-constructor `data` value's field
> metas through the type's own accessors, nested as the data is (`t.degrees.range`, `r.temp.degrees.range`). The
> sentence below on a `where` no longer holds once O4a (§17.2) is built.~~ **Restored the same day by decision R**
> (§18): the section holds as written. Meta code reads no field's meta, so the generated records still have no name a
> user can write. A meta a `data` type declares beyond its fields is O4b's (§17.2), and is not structural meta.

A user cannot write one. There is no brace on a `data`-typed parameter, field or leaf return: a brace lists slots, and
R2's "lists slots over a result that carries no meta" check widens to "…or whose meta is structural". A `where` cannot
read one either, since the generated accessors are in no user's scope. Structural meta carries a slotted type's meta
through a data value. It is observed only through that slotted type's `where` or brace downstream. That is also why
the generated records may be named freely: no user code can depend on their shape.

A meta a `data` type would declare *beyond* its fields is not structural meta, and has no spelling at all yet. That is
the hole of §8.1.

### 13.7 Layers

A data type's meta is generated from its **compiler-pool** declaration: the jvm layer's (borrowed), or the compiler
overlay's where one supersedes it (`Option`, `Either`, `Pair`, `Bound`, `Interval`). The bodies the channel
interprets are the runtime track's. Handed transfers go by handler *index*, so an overlay that declared constructors
in another order would hand arm *i* the wrong constructor's fields, silently. **Required** (decision H):

- the two declarations agree on constructor names, order and field count;
- this is checked where the meta type is first asked for;
- a disagreement is an error at the overlay's declaration.

Today every overlay copy is spelled exactly as the jvm layer's.

**Built (T5, 2026-10-22) as the merge's own check.** The overlay's and the platform's copies of a `data` each generate
a `D$Meta` constructor whose parameters are `Reach[D$Meta$C]`, one per constructor in order, and a record constructor
per constructor whose parameters are its fields. The two copies merge by name like any other, and a merge requires
equal signatures, so constructors renamed or reordered, or a field count changed, fail with "Has multiple different
definitions" when the first structural meta of the type is built. The message is the merge's, not one written for
this, and it lands at whichever copy the pool lists first.

### 13.8 Representation

Nothing in the backend reads a structural meta: a `data` is an object whatever is known of its fields. One hardening
goes with it, built in T1: `IntRepresentation.intervalBounds` decoded `Int$Meta(Interval(…))` by **shape**, without
checking the name, and now checks it as hover does (§2.2), since a `String`'s size meta has the same shape.

## 14. What a native hands its code

§4.5's handed statements are generated. `escapeInternal`'s and `withCellInternal`'s are hand-written, so they need a
spelling. The rule of §3.3 already covers it: a code parameter's **arrow domain** is a type occurrence whose supplier
is the callee. That gives the table a fifth row:

| Position | Supplier | Visible to the compiler? | So |
|---|---|---|---|
| handed argument (arrow domain) | the callee running the code | not for a native | stated, axiomatic |
| handed argument, bodied callee | the body | **yes** | **declined (R3)** |

```eliot
private def escapeInternal[K, A, R](body uses *: AbortFrame[K] {frame} => A, onExit uses *: K {exited(body)} => R,
                                    onValue uses *: A {body} => R) uses *: R {joined(onExit, onValue)}
private def exitInternal[K, A](frame: AbortFrame[K], nothing: K): A {exit(frame, nothing)}
```

`{frame}` after `body`'s domain says that `body` is handed a fresh frame of this call. `{body}` after `onValue`'s says
that what `onValue` is handed is what `body` yields: it is read as a code parameter always is, as the value its code
yields. `{exited(body)}` after `onExit`'s says it is handed the join of what `body`'s exits gave its frame (§11.1), and
`{exit(frame, nothing)}` that the frame receives `nothing` and the call does not return. The cell's natives say the
same of a cell:

```eliot
private def withCellInternal[S, A, R](initial: S, body uses *: StateCell[S] {cell(initial)} => A,
                                      combine uses *: A {body} => S {stored(body)} => R) uses *: R {combine}
private def readCellInternal[S](cell: StateCell[S]): S {content(cell)}
private def writeCellInternal[S](cell: StateCell[S], s: S): Unit {store(cell, s)}
```

**The spelling, settled in T3.** `frame`, `cell`, `exit`, `exited`, `stored`, `content` and `store` are body-less
primitives of `eliot.compiler.Meta`, as `never` is. A statement that is one application of one of them to the stating
value's parameters is a **frame or cell statement** (`MetaStatement`): the channel recognises it by name in the
companion's resolved body and follows it, and never reduces it, since its arguments are frames, cells and runs of code,
which have no meta. Two things the decision's sketch left implicit needed a statement each: which code is handed the
frame or cell (`{frame}`, `{cell(initial)}` on its domain), and that an exit is received by its frame (`exit`, which
replaced T2's `{never}` on `exitInternal`). A frame or cell statement lists no slots, whatever its result, so R2's
"slots over a result carrying no meta" does not apply to `writeCellInternal`'s `Unit {store(cell, s)}`.

**A statement that names a parameter, or joins parameters, is that value** where all of them but one are ⊥, not only
its meta: ⊥ is the identity of every join (§12). So `onValue` is handed the very value `body` built, and an escape whose
`onExit` is not run is `onValue`'s value, a constructed `Some` or `Right` included. Every such statement in the tree
(`fold`'s, the eliminators', the escape's, the cell's) says its callee's result *is* one of those values, which is what
makes reading it so sound. Where two are live the statement is reduced over their metas, as before.

The arrow-domain grammar slot is free: a brace *before* a type is the rejected row spelling, and one *after* a
parameter's type is §4.4's. It desugars to a handed companion (`escapeInternal$Handed$onValue$0`) named as the data
desugarer names one, which reads parameters (`MetaParameterDesugarer.arrowStatement`). It is accepted on a body-less
def only: a body hands what it computes, so a brace on one is an error at the brace (R3). Since T4 it is accepted on a
function-typed **value** parameter as well (`step: B {looped(initial, step)} => String => B`, §11.3): the callee
supplies what it applies a function value to exactly as it supplies what it runs code with.

**Declined (decision F): reshaping the escape primitive** to `escape(body: Frame => R, onExit: K => R)`, so
its transfer is a plain join and nothing is handed. It would need nothing new here, but `docs/effects.md` §3.4 fixes
the three-argument shape for every target (a microcontroller's status-flag path). The change would also reach the
backend's frame codegen and the compile track's intrinsic. That is a cross-cutting change bought to avoid one grammar
slot.

## 15. Worked traces

- **`orZero(Some(7))`.** `Some(7)` is `{Unreached, Reached(Some-record([7, 7]))}`. The `Some` arm is handed `[7, 7]`
  and yields it. The `None` arm is handed ⊥ and is `Never`. The join reads the `Some` yield in its place, so the result
  is `[7, 7]`.
- **`if(c, 5) else 7`.** `abort` ⤳ `exitInternal` is `Never`, so `fold(c, 5, abort)` is `[5, 5]`. `escapeInternal`
  hands `onValue` `[5, 5]`, which builds `Some([5, 5])`, and `onExit` builds `None`. The escape's join is
  `{Reached(unit), Reached(Some-record([5, 5]))}`. `foldOption`'s `match` hands its arms `unit` and `[5, 5]`, so the
  result is `[5, 7]`. `useByte(if(c, 300) else 5)` is then *not satisfied* rather than *cannot prove*: the
  accept/reject pair the tests want.
- **`catch`.** This is the same shape over `Throw`'s `escapeInternal` and `Either`. A `raise` is `Never`, and the
  body's value is `Right`'s field. `onError` is handed what the `raise`s gave the frame, so `catch(raise("x"), e -> length(e))` is
  `[1, 1]`. The result is the join of the handler's yield and the body's. A body that never raises hands `onError` ⊥,
  and the result is the body's alone. **Built in T3** for the two cases where one side is ⊥: the escape is then the
  live side's constructed `Left` or `Right` itself (§14), which `foldEither`'s `match` reads. A body that may do both
  makes the escape a join of a `Left` and a `Right`, which waits on structural meta (T5).
- **`pick(B)`** with `data AB = A | B`, `pick(x) = x match { case A -> 10 case B -> 20 }`. Today it is `[10, 20]`.
  With `B`'s meta `{Unreached, Reached(unit)}`, the `A` arm is handed ⊥, so `pick(B)` is `[20, 20]`.

## 16. Cost and risk

- **The type gate is already open** (T3, §5.3: +2.5% on a cold compile of the examples). What T5 adds is a meta for
  every data value, so every call returning one carries a structure, and so does every handle, since a named implementation's handle is a `data`. Handles stay in: excluding them
  would key a rule on `Handles.isDataType` and lose a double's `with` arguments, which are exactly what a test's
  `where` would read.
- **P5's contexts multiply.** A context is keyed by the metas of every meta-carrying parameter, so a deferred body
  taking an `Option[Int]` gets one context per distinct structural meta its callers supply. Contexts exist only for
  bodies whose root defers, but that bound is not measured.
- **The interpretation carries state, and computes fixpoints.** Following a frame or a cell (§11.1) threads their
  contents through one call's interpretation. A loop (§11.2) iterates its step until the domain's widening is stable,
  which for an interval is a few steps per endpoint. Measured in T4 on a cold sweep of every example: 3 min 55 s
  before, 3 min 58 s after, with the budget at 1024 (§5.3), and every jar byte-identical.
- **Generated values.** Per data type, the desugar generates a meta structure, a record per constructor with fields,
  a join instance, a `$Fields`, a transfer per constructor, and a handed transfer per handler argument. Each record is
  itself desugared (type, constructor, accessors, eliminators). This is compiler-pool and demand-driven, but every cold
  build mints the names.

So structural meta's stage is **gated on a measurement**: the sweep's wall time, `--statistics` with and without the
flag, and the cache size, against the stage before it. If the budget or the clock binds, the memo of §5.3 is added
then.

**The stage before it, measured 2026-10-22** at `4147fa63` (T4 and the `Unit` cleanup), every one of the 52 examples
compiled cold, one at a time, twice over:

| | run 1 | run 2 |
|---|---|---|
| wall time, summed | 218.0 s | 225.4 s |
| the same with `--statistics` | 225.0 s | 233.1 s |
| time inside the compiler (`--statistics` total) | 192.9 s | 200.0 s |
| the channel's processors (`MetaDemands`, `RefinementChannel`, `MetaTransferAccounting`, `Reconcile`) | 3.36 s (1.74%) | 3.57 s (1.78%) |
| cache, summed | 138.0 MB | 138.4 MB |

- **Noise.** Two runs of an unchanged tree differ by 3.4% in the sum and by 6% per example on average (17% at worst),
  so a single example's time says nothing and only the sum is compared. The cache's size moves by up to 3% per
  example between identical runs; every jar was byte-identical. The flag costs 3% here, not the 20% `CLAUDE.md`
  quotes, so the plain and flagged sums can both be compared.
- **Where the channel's time goes.** `RefinementChannelProcessor` is 2.19 s of the 3.36 s; the interpretation
  (`MetaDemandsProcessor`) is 0.38 s. The most a single example spends in the channel is 3.5% of its compile
  (`Intervals`).
- **The budget does not bind.** An env-gated probe in `metaOfCall` (thrown away afterwards) recorded every derivation:
  293 over all the examples, none exhausted. The most expensive used 450 of its 1024 steps (`TestSuite::runTest`), the
  next 348 (`Numeric::*` in `Intervals`); 226 of the 293 took ten steps or fewer, and only 3 took more than 200.
- **P5's contexts do not multiply yet**: 525 `MetaDemands` facts for 496 refined values, 1.06 contexts each.

So T5 is compared against these sums, and the memo is owed if the summed wall time grows by clearly more than the
noise, if the channel's share stops being a rounding error, or if any derivation exhausts its budget.

**T5, measured 2026-10-22** the same way:

| | baseline (mean) | T5 as first built | T5 as committed |
|---|---|---|---|
| wall time, summed | 221.7 s | 256.5 s (+15.7%) | 237.8 s (+7.3%) |
| time inside the compiler | 196.5 s | 234.1 s | 214.0 s |
| the channel's processors | 3.46 s (1.76%) | 3.99 s (1.71%) | 4.08 s (1.91%) |
| persisting the cache | 31.4 s | 60.5 s | 39.8 s |
| cache, summed | 138.2 MB | 384.9 MB (2.8×) | 196.0 MB (1.42×) |

- **The interpretation did not grow.** The channel's share is unchanged, the budget probe found the same 293
  derivations, none exhausted and the most expensive at 450 steps, and P5 still has 525 contexts for 496 refined
  values. So the memo is not added.
- **The generated names were the cost.** As first built, T5 tripled the cache, because every value's fact holds its
  file's dictionary, and every dictionary held every generated name of its file and of the prelude, so a file's facts
  grew with the square of its names. A name holding a `$` is now in no dictionary (§17), and the generated structures
  lost their `TypeMatch` implementation and eliminator transfer. What remains is the work T5 does: every program builds
  at least its `Console` handle, whose structural meta and `Meta` instances are checked on the compiler track, and every
  `data` in a loaded module mints its structures.
- **One clean run of the committed build.** Its second run measured load from another job on the machine (its six
  near-empty library modules took 10.0 s against 7.5–8.0 s in every other run) and is left out. Every jar of both
  builds is byte-identical to the baseline's, and every exit code the same.

## 17. Staging

Each stage is verified by the fast example sweep and byte-identity compare (`scripts/example-sweep.sh`), with every
example's output unchanged and the whole suite green. Clear `target/` between the two halves of a compare: the fact
cache once kept a stale companion across a change and hid a brace that did not evaluate.

- **T1: gap mechanics** (§13.5, §13.8). The dedicated neutral and gap propagation; `Unit` metas passed as `unit`;
  `intervalBounds` checks the FQN. **Built 2026-10-21**, byte-identical across all 47 examples.
- **T2: ⊥** (§12). `Value.Never`, `{never}` on each `exitInternal`, strictness, same-meta-type substitution, vacuous
  demands. It is observable through a demand in an `Abort`-using body: `def g(c: Bool) uses Abort: Int =
  useByte(fold(c, 5, abort))` is proven. **Built 2026-10-21** (`DerivedMetaTransferIntegrationTest`), byte-identical
  across all 47 examples, since none demands or narrows an `Int` an arm may exit from.
- **T3: frames and cells** (§11.1, §14). The arrow-domain brace in the parser and desugarer; the frame and cell values
  in the interpretation, and their primitives; the statements of the escape and cell natives. `catch` and
  `runStateToPair` become accept/reject pairs. `if … else` still waits on the field read. **Built 2026-10-21**, and it
  took two things the plan did not list. A frame or a cell reaches its code as a field of a handle, so the data
  desugarer already writes the value-level half of §13.4 (`{built}`, `fieldOf`, `fieldOr`); a first cut that read the
  data shape in the channel instead was green, and was replaced under §20's rule. And §5.3's type gate is gone, because
  a cell written by a call returning `Unit` (`putState`) must be followed. Cells and frames are proven in
  `DerivedMetaTransferIntegrationTest` (a write, a read after it, arms joined, a raise keeping the state, a handler
  handed what was raised, one nothing exits to not run); `catch` and `if … else` over a body that may both return and
  exit wait on T5's structural join, pinned as a gap there.
- **T4: loops and the holder** (§11.2, §11.3). The fixpoint primitive and `Meta`'s widening, with an interval's; the
  file folds and `resultValue` decomposed in the jvm layer. **Built 2026-10-21**, byte-identical across all 47
  examples. Beyond the plan, it took a parser change (the arrow-domain brace on a value parameter, §11.3), a budget
  four times as large (§5.3), and a join before the first widening, which keeps a constant step exact (§11.2).
  `foldLeftInternal` states its accumulator, which was T6's.
- **T5: structural meta** (§13). `Reach`, the structures, records, join instances and `$Fields`; the recursive groups
  and their folded summaries; constructor and handed transfers; `metaConstructorOf` for a generic meta type; the overlay
  agreement check. This flips `DerivedMetaTransferIntegrationTest`'s field-read case and makes `if … else` an
  accept/reject pair, and it adds `Pair`, `Either`, a user record, a user tree and `pick(B)`'s pruning. Jars change
  wherever an `Int` is read out of a `data`. **Gated on §16's measurement**, which also decides the memo, and with it
  the budget becoming a reported limit (decision I). **Built 2026-10-22, except the recursive groups** (§13.3, "Built",
  deferred by decision J), and every jar is byte-identical: no example narrows an `Int` read out of a `data`.
  The accept/reject pairs are in `DerivedMetaTransferIntegrationTest` and `FieldBraceIntegrationTest`, a field read in a
  `match` arm in `main` among them. It took four things the plan did not list. `Reach` needed an abstract base
  declaration, since compile-track probes read the runtime pool's names of every module they meet. `AbilityMatcher`
  looked up a pattern's references in the runtime pool whatever the platform being matched, which a compiler-pool-only
  record exposed, and now takes the platform. The LSP checks a module's meta-namespace names on the compiler track,
  since they may name `Reach`. And the first build tripled the cache (§16): every value's fact holds its file's
  dictionary, which held every generated name of the file and of the prelude, so a name holding a `$`, which only the
  compiler writes, is now in no dictionary, and the resolver finds one generated code names unqualified in its own
  module. The generated structures also get no `TypeMatch` implementation and no eliminator transfer.
- **T6: `List` carries its elements.** The parametric slot (§13.3), with the size domain. `foldLeftInternal` hands
  `combine` its element's meta, `typeMatch[R]` states (§10), and R2's exemption is removed: the last runtime leaf now
  states. **The leaf half is built (2026-10-22)**: `typeMatch[R]` states and the exemption is gone (§10), and decision
  I with it (§5.3), every jar byte-identical across all 47 examples. The `List` domain is decided in §17.1
  (decisions K–N) and built in the order given there.
- **T7: the record.** This document's status, CLAUDE.md's refinement-channel paragraph, and `eliot-code`'s list of
  what is not computed today.

### 17.1 T6: the list domain (decided 2026-10-22, decisions K–N)

`List` is the third domain, and the first whose meta holds **another type's meta**. Until T6 a `List`'s meta was
`Unit`, so a fold's element was not computed (§8), and nothing about how many items a list has could be demanded.
**Built 2026-10-22** (decisions K–N, with the corrections below).

```eliot
type List[A] {size: Interval[BigInteger], item: Reach[MetaOf[A]]}
```

- **`size`** is the size domain of §2.2, counted in items. `empty` is `[0, 0]` and `append` adds one, exactly.
- **`item`** is the meta **every** item has, or that there is none: the items of a list have one type, so one meta
  type, and the slot holds `Reached` with the join of their metas, or `Unreached` for a list that can have no item.
  `Unreached` is the value the join ignores, so `append(empty, 1000).item` is exactly `Reached([1000, 1000])`.
- **A `where` says what a list with no item means.** `item` is the `Reach` itself, so a demand over every item reads
  it through `forAll`, which holds of `Unreached`, and the predicate is a real `true` or `false` either way:

  ```eliot
  def allBytes(list: List[Int]) where list.item.forAll(m -> withinByte(m.range)): Int
  allBytes(empty)                  // accepted: no item to fail it
  allBytes(append(empty, 300))     // rejected
  def check(list: List[Int]) where list.item.forAll(m -> withinByte(m.range)) && nonEmpty(size(list)): Int
  check(empty)                     // rejected: the other conjunct is false
  ```

  `Reach` is importable for this (`import eliot.lang.Reach`), on the compile track only.
- **⊥ only where it is control.** A fold over a list that can have no item does not run its step: the element it
  hands is `reached(item(list), m -> m)`, as a `match` arm whose constructor cannot have built its value does not run
  (§13.4). Nothing reads ⊥ as a value.
- **What it buys.** A fold's element has a meta, so the last item read out of `[3, 7]` is `[0, 7]` with the
  accumulator's start, which closes §8's `List` row. A `where` can demand a size (`where nonEmpty(size(list))`).
  `List::size`, derived through the loop, is `atLeast(0)`, not exact: exactness waits on iterating a known count
  (§18, open).

The leaves state it:

```eliot
def empty[A]: List[A]                             {closed(0, 0), Unreached}
def append[A](list: List[A], element: A): List[A] {size(list) + closed(1, 1), eliot.compiler.Meta::joined(item(list), Reached(element))}
private def foldLeftInternal[A, B](list: List[A], initial: B,
                                   combine uses *: A {eliot.lang.Reach::reached(eliot.collection.List::item(list), m -> m)} =>
                                                   B {looped(initial, combine)} => B) …
private def argumentsInternal: List[String]       {closed(0, 2147483647), Reached(MetaOf[String](closed(0, 2147483647)))}
def split(separator: String, s: String): List[String]
   {interval(Bounded(1), end(length(s) + closed(1, 1))), Reached(MetaOf[String](interval(Bounded(0), end(length(s)))))}
```

`List.els` names `eliot.compiler.Meta::joined` by its module, since its own `joined` (the `Show` one) hides it. The jvm
`List.els` names `item` by its module: the accessor is generated from the slots the stdlib's declaration carries,
and a file sees only its own names and its imports, so a module-qualified name in code about metas is a slot
accessor first too. `prepend` states what `append` does; `words` `[0, length(s)]` items, each at most `length(s)`;
the file holders `resultStrings` and `resultPaths` (`Reached(unit)`, a `Path` having no slots) and `argumentsInternal`
the JVM's `int` limits. The LSP checks a name in the slot namespace on the compiler track, as a meta companion's.

**Proven** by `ListMetaIntegrationTest` (accept/reject pairs for a fold's item, a `size` demand, a `forAll` demand,
`forAll` over the empty list with and without another conjunct, and `split`'s items) and by eliot-build: its 368 tests
pass on this tree (run with the budget's report switched off locally, for the reason in §5.3's note below). Every
example's jar is byte-identical: none narrows an `Int` read out of a list. `FieldBraceIntegrationTest`'s list case
flipped as expected: a field read out of a list's item is what the item was built with, not merely its brace.

**The decisions** (Robert, 2026-10-22, after the draft's points were elaborated with examples):

1. **K. Slot accessors live in a namespace of their own.** `List::size` is a function, so a `size` slot would be "Name
   was already defined in this module". `Qualifier.Meta(Default)` is not free for it: it already holds a definition's
   transfer companion (`length`'s brace is `length` there). So an accessor is generated in a new qualifier (`Slot`),
   and in code a meta companion holds (a brace, a `where`) a name resolves to a parameter, then to a slot accessor,
   then to an ordinary value. No spelling changes: accessors are named only there (`Int.els`, `String.els`, the two
   `where` examples), and no Scala names one by its string. In a brace an ordinary `size` cannot be meant, since its
   argument there is a meta. The generated `Meta` instances name the accessors' namespace directly. Declined: a slot
   named something free (`count`), which leaves §2.2's debt to every later domain. **Built 2026-10-22**, byte-identical across every
   example: `Qualifier.Slot`, an accessor generated there by `MetaConstructorDesugarer`, and `slotsFirst` on the
   resolver's scope for a meta companion and a `Meta` instance's methods. `WhereOnDefIntegrationTest` pins it: a module
   declaring its own `def range(x: Int): Int` still reads `Int`'s slot in a `where`, which failed before.
2. **L. A slot names a type parameter's meta with the marker `MetaOf[A]`**, legal only in a slot, naming one of the
   type's own parameters. The desugarer makes each a binder of the meta structure and lists the parameters in a
   generated `List$Carried[A] = Function[A, Unit]`, the device `$Fields` (§13.2) and `$Payload` (§4.1) already are;
   the channel instantiates it as it does a structural meta: `metaTypeOf(List[Int])` is `List$Meta[Int$Meta]`.
   Declined: a slot type mentioning a parameter meaning its meta, a reading by position, wrong for a value parameter
   (`type Buffer[N: Int] {limit: N}`). The marker is also the spelling §8.1's custom metas and a `where` over items may
   grow into, once the evaluator can reduce it outside a slot; that is not part of T6. **Built 2026-10-22, with point 5**,
   byte-identical across every example: `MetaConstructorDesugarer` rewrites each `MetaOf[A]` to the structure's binder
   `M$A`, generates `T$Carried`, and derives the instance with `StructuralMetaDesugarer.metaInstance`, so `Int$Meta`'s
   instance is the same generator's now; `MetaInterpreter.metaTypeOf` applies a slotted type's structure to the meta
   types `T$Carried` lists. Pinned by `CoreProcessorTest`; nothing declares a `MetaOf` slot until M.
3. **M. `item` on the surface, `Reach` inside.** **Corrected while it was built (Robert, 2026-10-22):** reading an
   empty list's `item` as ⊥ was the bottom value §18 rules out, ⊥ standing in for a missing meta, which decision B
   forbids, and it was unsound besides: ⊥ swallowed a whole predicate, so `forAll`'s `check(empty)` above would have
   been accepted with its size conjunct false. *"The meta value type will always cover 'bottom' states, if there are
   any"*: `item` is the `Reach` itself, the declaration says so (`Reach[MetaOf[A]]`), and a `where` reads it with
   `forAll` (Robert: *"keep Reach, forAll is fine"*). The generated `noItem`/`withItem`/`eachItem` helpers this
   needed were dropped with it.
4. **N. A native producing a list of a slotted type states its items with a nested brace.** `argumentsInternal`,
   `split`, `words` and the file holders must state a `String` meta inside the item, and §4.1's `constructor$` is the
   result's meta constructor only. An inner `{…}` means *the meta of the type that stands here, built from these
   slots*, the type inferred from where it stands; it is implemented as a compile-track leaf answering the constructor
   of a meta type given as a type argument. The natives stay as fast as they are and state the honest bound.
   **Fallback**, if the checker cannot infer the type through `Reached(…)`: decompose each into a fold handing every
   item through a braced arrow domain, as T4 did `resultValue` (§11.3). That costs O(n²), since the JVM `append` copies
   its list (`readLines` of a 10,000-line file is about 50 million element copies), and loses the size's precision to
   the loop's widening, so it is measured before it is taken.

   **N's spelling is reversed (Robert, 2026-10-22), before it was built.** In an expression `{…}` is already a block,
   so `Reached({closed(0, 5)})` parses as `Reached(closed(0, 5))`, a bare interval. The item's meta is written
   `MetaOf[T](slots…)` instead (Robert: *"Didn't we introduce MetaOf[]?"*): in code about metas, the resolver resolves
   `T` as a type name and replaces the expression by that type's meta constructor, from `T`'s own module. `T` must be a
   type declaring slots, named directly; through an alias, a type parameter or a slotless type it is an error naming
   `T`, so nothing infers a meta type from a spelling (§4.1). A slotless item is `unit` (`List[Path]`'s), and a generic
   one the parameter's own meta. The fold decomposition stays the fallback.
   **And `String`'s slot is renamed `length`** (Robert, 2026-10-22), since `List`'s `size` hid it (§2.2).
5. **No decision, only work:** `MetaConstructorDesugarer` threads a type's parameters onto its meta structure and
   instance (`implement[M$A ~ Meta] Meta[List$Meta[M$A]]`), as `StructuralMetaDesugarer` already does for `data`. **Built with L.**

**What else changes.**

- **Where the statements live.** `type List[A]` is declared in `lang`, `stdlib` and `jvm`. The slots go on the base's
  declaration, as `String`'s do (`lang`'s `type String` is slotless). `empty`/`append`/`prepend` are declared in both
  `lang` and `stdlib`, and a brace is a bodied companion, so it is stated in exactly one of them, the one owning the
  slots, or it is "Has multiple implementations."
- **R2 and the classification.** A slotted type with carried parameters is still slotted (R2 demands its leaves
  state), and its meta type is applied: `declaresSlots` and `metaTypeOf` learn `$Carried` beside `$Fields`.
- **Recursion through a list.** eliot-build's `Clause(children: List[Clause])` has a finite meta today only because a
  list carries none. With T6 its meta type is infinite, the cycle guard declines it, and it is not computed (§13.3,
  decision J). Fail-safe, and precision is lost only where nothing demands it today.
- **A demand reading ⊥.** Decision C makes a demand an unreached path holds vacuous; a predicate that reads an empty
  list's `item` gets ⊥ out of the reading instead, and must hold too. Confirmed or extended when M is built.
- **Cost.** Measured as T5 was (§16): every list value gains a meta, and a nested list a nested one.

**Order:** K and the type parameters of point 5 first, each byte-identical; then L and M with the generic leaves
(`empty`, `append`, `prepend`, `foldLeftInternal`); then N's natives, which R2 reports one by one once `List` has slots.

### 17.2 The pivot to decision O (stages O1–O7, planned 2026-10-23)

Decision O (§8.1) is two features under one rule: **overriding** the derived meta of any bodied value's result, in
every domain, and **declaring a meta on a `data` type**. The first needs no new syntax and touches two classes; the
second needs syntax, a desugaring and a decision about the meta's shape. So the override goes first, and custom metas
are built on it.

Each stage is verified as §17 says (the sweep, byte-identity, every suite green), and eliot-build is compiled locally
as T6 did. Until O5 no example can change: no bodied value in the tree carries a brace, because R3 refused one, so a
jar that moves before then is a bug. The pivot is independent of decision I (§5.3): O neither helps nor hinders it, and
no release is cut until I is settled anyway.

**Where the tree stands, and the trap.** Every meta question goes through `MetaInterpreter.metaOfCall`. Its `metaOf`
splits on one test, whether the callee has a `^Meta` companion (`declaresTransfer`): with one the call is *stated*,
and its body is never walked; without one it is *derived*. The only reason no bodied value has a companion is R3's arm
in `MetaTransferAccountingProcessor`. **Deleting that arm alone would not give O**: a bodied value's brace would take
the stated arm and its body would never be walked, so the brace would be a summary, which O declined (rule 3). Both
change in the same stage.

- **O1: the override.**
  - `MetaTransferAccountingProcessor` loses R3's arm (`verifyNotStated`, `reportStated`). R2's arm is unchanged. A
    bodied value's brace is checked instead for three things, each an error at the value:
    1. slots over a result that carries no meta (today's `reportSlotsWithoutMeta`, now for both arms);
    2. a control primitive in the brace (`never`, `exit`, `frame`, `cell`, `store`, `content`, `looped`, `endless`,
       `built`, `fieldOf`), read off `MetaStatement.primitive` (Q1);
    3. a result of a `data` type, until O5 (Q7): its structural meta has no surface (§13.6), so a brace could only
       state slots, and a `data` type has none before O4.
  - `MetaInterpreter.metaOf` gets three arms: a **native** with a companion is *stated*, as now; a **bodied** value with
    a companion is *derived, then stated*; anything else is *derived*. "Bodied" is `MonomorphicValue.runtime`, the test
    R2 already judges, read after the layer merge, so a brace in the base over a body in the jvm layer is O's case
    exactly as both in one declaration are.
  - **The replacement.** The derived `(run, value)` keeps its `Run`: the store, the frames, every exit and the budget
    spent are what the body did. Only `value` is replaced, by the brace reduced over the argument metas
    (`reduceTransfer`), as a `Value.Meta`. ⊥ stays ⊥. A code argument is passed as not computed
    (`companionArgument(None)`), **not** as `Value.known`'s `unit`, so a brace reading one is not computed, fail-safe,
    until O2.
  - A braced body that runs out of the step budget is still reported (decision I): the brace replaces a result, not a
    walk (Q2).
  - The scaladocs of both classes explain the split as "R2 and R3 read as a definition"; they are rewritten with it.
  - **Tests.** R3's three cases flip: `MetaTransferAccountingProcessorTest`'s "a bodied value that states a
    transfer" and "the codegen precondition … stating bodied value", and `TransferBraceReductionIntegrationTest`'s
    "a transfer brace on a bodied value". New cases in `DerivedMetaTransferIntegrationTest`:
    - `requirePositive` with its brace satisfies a demand for `atLeast(1)` that it fails without one;
    - a brace wider than the derivation is taken as written (trust cuts both ways);
    - a cell written inside a braced body is still followed after the call;
    - a braced body that never returns is still ⊥;
    - a brace in one layer over a body in another;
    - a brace reading a code parameter is not computed;
    - a control primitive in a bodied brace, a brace over a `data` result, and slots over a slotless result are refused.

    One case **pins the accepted price**: a brace narrower than the value returned compiles, and the value is wrapped
    at the width the brace chose. It documents the behaviour, so that a change to it is seen.
  - Jars: byte-identical across every example.

  **Built 2026-10-23**, on the recommendations for Q1, Q2 and Q7, all three refusals or a report and so fail-safe.
  `MetaInterpreter.metaOf` has the three arms; "bodied" is a produced `MonomorphicValue` with a runtime body, so a
  value whose monomorphization failed upstream is read as its statement. `MetaStatement` gained `controls`, a primitive
  or `never` anywhere in the brace. Beyond the plan:
  - **A brace over a function result is refused too**, as slots over a result carrying no meta. The bodied arm reads
    its result past as many arrows as the companion takes parameters, as the channel does, where R2's arm counts the
    signature's parameters; read R2's way, `def lift(g: Gauge): Gauge => Gauge {level(g)}` was judged at `Gauge` and
    accepted. Accepted, the brace would have replaced the closure the body returns with a meta that is not computed.
  - **The cross-layer case is a `ProcessorTest`**, since the integration session has one source root:
    `runGenerator(…, layers = …)` adds files of module `Test`, which merge as two layers' copies do.
    `MetaInterpreterTest` tells the arms apart through ⊥: a stated call is its brace, a derived one stays ⊥ when its
    body never returns, in one file and across two.
  - **Where the price is paid.** The pinned case wraps only behind a consumer that computes at the call node's width:
    `add(lie(300), 0)` with `lie`'s `{closed(0, 10)}` prints `44`, while `useByte(lie(1000))`, handed straight to a ⊤
    parameter, prints `1000`, because S9 leaves a boundary result meeting a ⊤ slot unconverted (§2.2). So a wrong
    brace is silent *and* its effect depends on what reads the result.

  The jars are byte-identical across all 47 examples.

- **O2: a bodied brace reads its code parameters.** A native's brace reads a code parameter as what its runs yield
  (§5.2). On a bodied value the body runs it, so the walk records what each application of a code parameter yields,
  and the brace reads their join: `def choose(c: Bool, a uses *: Int, b uses *: Int): Int {joined(a, b)} = fold(c, a,
  b)`. A parameter the body never runs reads as not computed. Small, and it **may wait for a program that needs it**:
  O1 is fail-safe without it.

- **O3: the vocabulary a brace narrows with.** `requirePositive`'s `positive(range(n))` needs functions over an
  interval that the base does not have: an **intersection**, which §2.2 already wants for `substring`'s index ceiling,
  and narrowings like `positive`. They live where `interval`, `closed` and `atLeast` do (§2.1): abstract in the base,
  bodied per platform, with the compile track's copy in the overlay. Independent of O4, and also small. Speculatively
  re-bodying natives that exist only to carry a statement is not part of it.

- ~~**O4a: a field's meta through the data's own accessor** (decision P).~~ **Dropped 2026-10-23 by decision R**
  (§18), before any code. The plan generated, for every field of a single-constructor `data`, a meta accessor of the
  field's name in `Qualifier.Slot`, so `def f(p: Pair[Int, Int]) where withinByte(p.first.range)` would read. Starting
  it found the clash that undid it: meta code looks a bare name up in `Qualifier.Slot` first (decision K), and meta
  code also reads ordinary `data` values, so the generated `start`/`end` of `Interval` would have shadowed the value
  accessors `Meta[Interval]`'s join and nine braces read endpoints with (`end(length(s))`). Only the argument's type
  tells the two apart. Asked how to resolve it, Robert went to the root instead: in meta code `p` is a meta, and
  `p.first` names a runtime field of a value meta code does not have, so the reading works only if the meta mirrors the
  runtime structure in public. The compiler generates no such mirror; a type's meta is what its slots declare. The
  `where` above has no spelling: a program that wants it wraps the pair in a `data` of its own with a slot.

- **O4b: a meta declared on a `data` type.** Parser, AST, desugaring, slot accessors. The next stage.
  - **Spelling** (Q3, decided): the slots on the type's head, as on `type`; each constructor states them in a brace
    after its fields.

    ```eliot
    data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}
    data Shape {area: Interval[BigInteger]} = Circle(radius: Int) {…} | Square(side: Int) {…}
    ```

    `DataDefinition` gains the slots and `DataConstructor` the statement.
  - **The slot is the type's own** (decision P): `sign(t)` reads it, and it is all meta code reads of a `Temperature`
    (decision R). Nothing is copied up a level, and nothing reads a field's meta: what a slot needs of a field, the
    constructor's statement flattens into it. In the representation the slots are fields of `D$Meta`
    beside the reach and constructor records, which keep each field's meta nested as T5 builds it. The generated instance already joins and widens field-wise, so a slot goes through the user's `Meta[Sign]`
    with no new machinery. `metaTypeOf` is unchanged, since a slot's type is closed.
  - **Accessors**: each slot gets a `Qualifier.Slot` accessor (decision K), as a `type`'s slot does. A slot named like
    one of the type's fields is an error at the slot (decided 2026-10-23): meta code would read the slot under the name
    value code reads the field by.
  - **The constructor's statement** becomes `{built(constructor$(slots…, Unreached, Reached(D$Meta$C(fields…))))}`,
    where `slots…` are the user's expressions; the companion's parameters are the constructor's fields, so
    `range(degrees)` reads that field's meta exactly as a parameter brace reads a parameter's. A field of a `data`
    type is read through that type's own slots (`sign(temp)`), and a field of a slotless type has nothing to read.
  - **Every constructor of a slotted `data` states its slots** (Q5, decided; Robert: *"They have to state what the
    meta value is, so not stating is a compiler error. Meta values are total."*). A constructor without one is an
    error at the constructor.
  - **Refused until O6**, each a fail-safe error at the slot (Q6, decided): a slot on a recursive `data` (the summary
    of §13.3 would join a node's slot with its children's), and a slot naming a type parameter's meta (`MetaOf[A]`),
    since `D$Meta`'s binders are one per field, not one per type parameter.
  - **Two layers' copies** of a slotted `data` must agree on their slots. Decision H's mechanism should give that for
    free, since the two `D$Meta` signatures must match; an overlay copy of a slotted `data` is new ground and gets a
    test.
  - **Tests**: the `Sign` probe of §8.1 turned into accept/reject pairs: a demand on `sign(t)` after construction; a
    join at `if … else` through the user's `join`; a loop through the user's `widen`; a `match` arm still handed
    the field's structural meta beside the slot (`t match { case Temperature(d) -> useByte(d) }` after
    `Temperature(5)`); a constructor statement reading a slotted field's slot; a missing constructor statement; a
    slot named like a field; the refusals of Q6. LSP hover shows nothing for a custom domain (`TypeHintIndex` decodes
    `Int$Meta` only), which is acceptable.
  - Jars: byte-identical, since no example declares a slot on a `data`.

- **O5: a brace on a bodied value returning a slotted `data`.** `def warm: Temperature {Positive} =
  Temperature(5)`. The brace lists the slots only. The field metas stay derived (a `match` arm reads them, nothing states them),
  so here a brace narrows *part* of a meta. The companion's `constructor$` parameter (§4.1) is already the channel's to choose: it
  passes a constructor that rebuilds the derived meta with the stated slots. This lifts O1's refusal for a slotted
  `data` result; a slotless `data` result stays refused, having nothing to state. Tests: the brace narrows the slot,
  the fields are still read after the call, and ⊥ stays ⊥.

- **O6: generic and recursive `data` slots.** `MetaOf[A]` in a `data` slot (a binder per carried parameter, as
  `T$Carried` gives a `type`), and slots on recursive `data`, designed with the recursive-data fold (decision J).
  Design only, until a program needs it.

- **O7: the record.**
  - Part I: R3 leaves §3; §3.3's table gets "stated or derived; a brace is trusted"; §5.1's state-or-derive becomes
    three arms; §8.1 closes; §2.3 says a user may now grow a domain; §13.6 stands (decision R).
  - CLAUDE.md's refinement-channel paragraph.
  - The global `eliot-code` skill (`~/.claude/skills/eliot-code/`): overriding and custom metas, with the warning
    that a wrong brace is silent, and reading a field's meta through its accessor.
  - The site chapter `_docs/meta-domains.md` (eliotlang.github.io, by cloud PR, fetched first), which says a program
    cannot add a meta today.
  - apidoc: a brace on a bodied public `def` is now part of its API, so check that the renderer shows it.

  Each earlier stage records itself here as it lands, as T1–T6 did, so O7 is the Part I rewrite and the outside
  documents.

**Order.** O1 (built), then O4b and O5, in that order (O4a was dropped by decision R). O2 and O3 are independent of each other and of O4, and
can come whenever a program asks for them. O6 waits on the recursive-data fold. O7 is last.

**The plan's questions**, as answered (Q3–Q6 by Robert on 2026-10-23; Q1, Q2 and Q7 built in O1 as recommended):

| | Question | Answer |
|---|---|---|
| Q1 | A control primitive in a bodied brace | Refused. The body's control is derived (rule 3), so a primitive would restate or contradict what the walk followed. Built in O1. |
| Q2 | A braced body that runs out of the budget | Still reported. The brace replaces a result, not a walk. Built in O1. |
| Q3 | The `data` slot spelling | Slots on the head, a statement brace per constructor (O4b). Decided as recommended. |
| Q4 | The slot beside the structure | **Neither option** (both and replace were offered): decision P. A slot is the type's own; a field's meta is read through the data's own accessor, nested (O4a). **Partly reversed by decision R**: no field's meta is read in meta code, so the slot sits *beside* the structural meta, which keeps no surface. |
| Q5 | A constructor without a statement | An error. Decided: meta values are total. |
| Q6 | Recursive `data`, `MetaOf` in a `data` slot | Refused until O6. Decided as recommended. |
| Q7 | A bodied brace over a slotless `data` result | Refused: nothing nameable to state. Built in O1. |

Two points decision P raised were decided with it, as recommended: a sum's fields get no meta accessor, mirroring
value code, and a slot named like a field of its type is an error. Decision R made the first moot, since no field gets
one. The second stands on a reason of its own: meta code would read the slot under the name value code reads the
field by.

Out of scope: a brace on an **ability method declaration** stays §18's open question (whether an ability may declare
a transfer its implementations must satisfy). An implementation's member is an ordinary bodied value, so O applies to
it from O1. O1 pins what a brace on each of the two does.

## 18. Decisions

**Decided 2026-10-20:**

- *One document* for the channel (this one).
- *Every leaf states, type-parameter results included* (§10). This reverses R2's exemption.
- *The process has no special values* (Robert: *"There is no 'null' or some special value to mark 'missing', or
  'biggest' … The process does not need a 'top', only the domain may."*). `Meta` gains no `top`. A domain has a top
  only in its own value type. Nothing marks a meta missing, and a gap is closed, never represented (§1, §11). This
  declined the first draft's per-domain `top` statement.
- *A structural meta has no unknown field* (§13.5). It dissolves the earlier "all-or-nothing knowledge per
  construction" and its deferred per-field unknown, both of which assumed an unknown argument was a meta.

**Decided 2026-10-21** (Robert took A and C–I as recommended, after B):

- **B. The interpretation gains ⊥** (§12): `{never}` at the exits, strictness, same-meta-type substitution, and
  monotonicity as an obligation on statements. ⊥ says *this call does not return*: a fact about control, which every
  join absorbs. It is never a placeholder for a missing meta, no companion receives it, and no domain contains it, so
  the rule that the process has no special values stands. Accepted as the way to the rest of Part II (Robert: *"I
  accept, if this is the way to get to the other features"*): the alternative, an empty result type on every exit
  primitive, is declined in §20, and without ⊥ neither `if … else` (`fold(c, 5, abort)` is `[5, 5]`) nor a pruned
  `match` arm (§15) derives.
- **A. Frames and cells are followed** (§11.1): the interpretation threads what reaches a frame and a cell through the
  one call that made it, and the natives state it through name-recognised primitives (§14).
- **C. A demand an unreached path holds is vacuous** in that context (§12), rather than checked with unknown binders.
- **D. A loop's meta is its fixpoint, which the domain's widening makes finite** (§11.2): `Meta` gains `widen` beside
  `join`, an operation and no value. This reverses the earlier open recommendation of a budget for iteration (§19).
- **E. A sum's meta is tagged by reach** (§13.1), rather than a tag-blind structure or single-constructor data only.
- **F. The arrow-domain brace** (§14), rather than reshaping the escape primitive.
- **G. Recursive `data` folds into its own summary** (§13.3), joined over every node, with mutual recursion folded over
  the group. Shape stays the size domain's.
- **H. An overlay `data` must agree with the runtime one** (§13.7), as an error.
- **I. The step budget becomes a compiler limit** (§5.3): running out of it is an error naming the call, never a value.
  **Built 2026-10-22.**

**Decided 2026-10-22:**

- **J. The recursive group of decision G is deferred to the recursive-data fold** (`docs/recursive-data.md`), and
  designed with it. T5 folds only a field typed by the type itself (§13.3, "Built"); a type recursive through another
  type or across types keeps a meta that is not computed, which is fail-safe. Recursive `data` is legal — only a
  recursive *value* is refused (the recursion gate reads bodies, never signatures), and strict positivity refuses only
  negative recursion — but no program can walk one to an arbitrary depth until the fold exists: without recursion, user
  code reads a fixed number of levels, one `match` each. So a precise meta through such a type buys little before the
  fold, and the fold is what decides how a structure that reaches itself is summarised. Both wait on `List` carrying
  its size, the domain T6 starts. G is not reversed: a field typed by the type itself folds as it says.
- **K–N, the list domain** (§17.1): slot accessors in a namespace of their own (K); `MetaOf[A]`, in a slot only, names
  a type parameter's meta (L); `List[A] {size, item}` with `item` a `Reach` a `where` reads with `forAll`, as corrected while
  it was built (M); a native states a list's items with `MetaOf[T](…)`, a nested brace being a block, a decomposition
  into folds the fallback (N); and `String`'s slot is `length`. **Built 2026-10-22.**

**Decided 2026-10-23:**

- **O. A brace on a bodied value states its result's meta, replacing the derived one** (§8.1). Pure trust: nothing
  checks it. The result only: the body is still walked for cells, frames and exits, so a brace is not a summary.
  Every domain, `range` and `size` included. It reverses R3, §3.3's last row and §20's *"a brace on a bodied
  return"*, and is how custom metas are declared: a `data` slot stated by its constructor, narrowed by any bodied
  value's brace. A silent wrong brace is the accepted price. **Not built.** Its remaining questions are in §8.1.
  **O1, the override, built 2026-10-23** (§17.2).
- **P. A `data` value's meta is read through the data's own accessors, nested, never flattened** (§17.2, O4a). Asked
  whether a declared slot sits beside T5's field metas or replaces them, Robert chose neither: *"'temp.degrees.range'
  is [5,5]. You should be able to access the meta through the accessor of the data element, not 'flattened'. A data
  structure may have dozens of fields with other data structures inside them, we would want to preserve that
  structure."* So a declared slot is the type's own meta and replaces nothing, and meta code reads a field's meta with
  the field's accessor, as deep as the data nests. It partly reverses §13.6 (*"A `where` cannot read one either"*),
  marked there. With it: a sum's fields get no meta accessor, as they get no value accessor; a slot named like one of
  its type's fields is an error. It is not §20's declined *"accessor stating a field's meta"*: the accessor reads a
  meta, it states nothing. **Not built.** **Partly reversed the same day by decision R**: no accessor reads a field's
  meta, so O4a is dropped; that a declared slot is the type's own and replaces nothing stands.
- **R. Meta code reads no field's meta: a type's meta is what its own slots declare** (§17.2). Partly reverses P,
  before any of it was built. Starting O4a found that a generated meta accessor named like a field would shadow, in
  meta code, the value accessors of the `data` meta code computes with (`Interval`'s `start`/`end`). Robert: *"metas
  are values themselves, which _do_ have accessors to other values. So writing "a.i.range" would use "a", which is a
  meta value. The "i" accessor on that meta value would mean absolutely nothing. "a.i" is the runtime structure, but
  not the meta structure. … To make "a.i.range" work, we would need the "a"'s meta to have the same structure as the
  "a"'s runtime type. So I see two ways, either we auto-generate something, like the same structure for the meta. Or
  we don't do anything, and let the user define the meta's structure. I tend to vote for the second one. So basically
  "a.i.range" would not work. The user would have to flatten meta structures to an appropriate meta value."* So:
  - no meta accessor is generated for a field, and O4a is dropped;
  - a declared slot is the type's own and replaces nothing (P's first half, which stands), so it sits **beside** the
    structural meta (§13.1), which keeps following a value into a `match` and keeps no surface: §13.6 holds as written;
  - what a slot needs of a field, the constructor's statement flattens into it (O4b), reading an `Int` field's `range`
    or a slotted `data` field's own slots;
  - a constraint on a field of a type the program does not own (`p.first.range` of a `Pair`) has no spelling. A
    program wraps the value in a `data` of its own with a slot.

  **Not built.**
- **Q3, Q5, Q6 of §17.2**: the `data` slot spelling is slots on the head and a statement per constructor; a
  constructor that does not state its slots is an error (*"Meta values are total"*); a slot on a recursive `data` and
  `MetaOf[A]` in a `data` slot wait for O6. **Not built.**

**Open, not blocking anything:**

- **May an ability declare a transfer its implementations must satisfy?** (§3.1). A contract feature, not part of
  totality.
- **A compile-time `power` native**, which would make `parseIntInternal` tight (`"42"` ⤳ `[-99, 99]`). Safe now that
  the interval domain has `whole` to saturate into. A prerequisite for nothing.
- **Iterating a loop exactly** when its count is known (`[n, n]`), instead of widening (§11.2). Proposed 2026-10-23
  to cover a bounded count `[lo, hi]` too, one run per count joined, which would derive through a fold what a
  brace otherwise has to restate (§8.1).
- **An interval intersection**, which `substring`'s index ceiling waits on (§2.2).

### 18.1 Where this was left (2026-10-22)

Of Part II, T1 to T4 are built (2026-10-21) and the rest is a design. Every decision in §18 is
taken: B on 2026-10-21 after the question whether ⊥ is a special value (it is not), then A and C–I as recommended.
T3 settled the frame and cell primitives' spelling (§14), and T4 the loop's: `looped` and `endless`, and `widen` as a
second method of `ability Meta` (§11.2).

**T5** (§17), structural meta, is built (2026-10-22) and measured against its baseline (§16). The memo is not added,
since the interpretation's share of a compile did not move and no derivation came near the budget. The recursive group
of §13.3 is not built: decision J (§18, 2026-10-22) defers it to the recursive-data fold.

**T6** is built (2026-10-22): `typeMatch[R]` states, R2 has no exemption left (§10), decision I makes running out of
the step budget an error (§5.3), and the `List` domain carries its size and its items (§17.1, K–N), every example
byte-identical. **Open: decision I fails eliot-build** (§5.3's note) and waits on Robert. Decision O (2026-10-23,
§8.1) is taken; its migration is planned in §17.2 (stages O1–O7). O1, the override, is built (2026-10-23) on the
recommendations for Q1, Q2 and Q7. Q3–Q6 were answered the same day, Q4 with decision P, and the plan was adjusted:
O4 is split into O4a (a field's meta through its accessor) and O4b (declared slots). Starting O4a found a name clash
in meta code, and Robert answered it with decision R: no field's meta is read in meta code, so O4a is dropped.
**Next: O4b**, a meta declared on a `data` type. Next after that is **T7**, the record, which O7 folds into.

**The `Unit` question (2026-10-21), closed the same day.** Robert: a type declaring no meta gets `Unit` implicitly, and
*"the rest of the machinery should not know that this was 'default'"*. T3 removed two places that knew: §5.3's type
gate (a call returning a type with no meta was not interpreted) and the stated path's shortcut (a code argument of meta
type `Unit` was not run). The other four were patches over a `Unit`-typed value read back as not computed instead of
`unit`, and are gone: `companionArgument`'s `Unit` arm, `demandPrecondition` skipping a `Unit` argument,
`MetaDemands.rootOf` counting a parameter of a type with no meta as `Unknown`, and `metaConstructorOf`'s `!= unitType`,
which now asks the question about slots it was (does the meta type have a constructor). What replaced them is the
value produced where it is (§4.1): a constructed value, a function value, a frame and a cell know `unit`; a leaf
stating nothing states it implicitly over a result whose type declares no meta; a root context knows it of such a
parameter, and a context other than the root takes the root's entry wherever the root is not symbolic, so contexts
still vary only where a meta is tracked. Every jar is byte-identical.

The probes behind §9 used a throwaway `ScratchProbeTest` in `jvm/test`, over `DerivedMetaTransferIntegrationTest`'s
`useByte` harness, with `compileForErrors` printing each verdict. Its proofs were run with
`./mill jvm.test.testOnly com.vanillasource.eliot.eliotc.jvm.ScratchProbeTest`. Recreate it to re-probe, and delete it
afterwards.

---

# Part III — Record

## 19. How it got here

The stages in order. The full narrative of each is in the three retired documents at `2a66009a`.

- **The channel** shipped with `Int`'s range, `^Meta` transfer companions, `^Where` preconditions and the `Meta[D]`
  join. Merges are recognised only via `Meta.join`. A call boundary was then a not-computed meta, so precision was a
  property of *where code sits relative to a native*.
- **The tops (§2.1).**
  1. A slot-level `Bound` wrapper was added for `parseIntInternal`, the one leaf whose honest bound is exponential.
  2. `Interval`'s endpoints became `Bound`s, because the base may not assume a platform maximum.
  3. The slot wrapper collapsed into `whole`, because two spellings of a top read as disagreement to the reconcile pass.
  4. The generic `Meta[Bound[D]]` top was deleted, leaving every top a domain top and the machinery with none.

  All 40 compiling examples were byte-identical at each step, since a stated top and a half-open range decode to the
  same boundary layout. That conclusion stands: the process has no top (§18).
- **S0: code-point indices.** `length` and the index family switched units on both tracks. It was justified on its
  own: string slicing was already non-portable.
- **S1–S2: the `size` slot and the literal seed**, plus the LSP hover gate. The declaration alone was inert, with every
  example byte-identical.
- **S3: the result-edge re-encode.** `length`'s `{size(s)}` was the first stated transfer, and it failed class
  verification immediately (`'java/math/BigInteger' is not assignable to 'java/lang/Byte'`): a generated native static
  method returns the boundary bignum while the node was stamped narrow. The fix converted at every non-intrinsic
  application, because the width on the stack, not the callee, decides it.
- **S4: the rest of `String.els`.** A brace could not name a number. A type-position helper (`boundedAt[V]`) worked
  around it, then was deleted when brace literals became compile-time `BigInteger`s (§4.3). The compile-time `pow` leaf
  was scoped in and dropped: `parseIntInternal` states `whole`.
- **S5: R2 armed.** Eleven more leaves stated (§2.2's table and the jvm holders). One machinery fix was needed: a brace
  could not stand on a def with an **untracked parameter**, because the companion retyped every parameter `T` ⤳
  `T$Meta`, which exists only for slotted types. Every brace until then had only `Int`/`String` parameters. The lesson:
  a rule exercised only on tracked types is not yet known to be total. Across all 43 examples the armed check fired
  nowhere; that is why every leaf in the tree was stated, not only the ones that fired.
- **S6: R3 armed.** It was a no-op on the layers and **not** a no-op on the tests. A suite stated braces on bodied test
  defs, the only way to author a brace end to end. It was rewritten through the layer braces that ship each shape
  (`TransferBraceReductionIntegrationTest`). The lesson: check a "no-op on the tree" against the tests too.
- **S7: companion naming** (§4.2), byte-identical. It had to land before the interpretation multiplied the companions
  that would collide.
- **S8: the interpretation** (§5). Option B was chosen over a generated shadow twin per value (§20). It was
  byte-identical for 35 of 40 examples. The five that grew did so from round trips through the re-encode.
- **S9: the round-trip peephole** (§7). All 13 changed examples emit fewer instructions (5919 against 6091), and each
  of S8's five is below where it started (`Ranges` 146 against 150 before S8).
- **Companions from evaluated types (§4.1)**, 2026-10-19. This added `$Payload`, `constructor$`, and R2 reading the
  evaluated result type. That last change closed a fail-open: an alias result had been exempt.
- **Parameter braces (§4.4)**, 2026-10-19. A reusable "Byte" is a meta *value*, never a type, because a transparent
  alias is erased by normalization. The bound lives on the occurrence.
- **P5: demands in context (§6)**, 2026-10-20. It retired the definition-site check over not-computed parameters and
  §4.4's "the body reads the stated meta". All 47 example jars were byte-identical.
- **S10: closures (§5.2).** This repaired `fold`'s join, which had received only not-computed arms since D36 made every
  code argument a thunk. Sound, since every demand on it failed, but no test pinned the merge. It also fixed the
  backend's `fold` arms, which would have been a `ClassCastException` on the first narrowed `fold`.
  A dot call (`"hello".length`) and an infix spine (`60 + 60`) derive since S10. Each was once listed as a place where
  the meta was lost; both were probed proven on 2026-10-20.
- **S11: a `match` states the join of its arms (§4.6).** It was chosen over function metas in the transfer language,
  the channel naming `PatternMatch`, and deferring to structural meta. Jars went from 657 477 to 565 827 bytes (−13.9%)
  and instructions from 18 870 to 16 042 (−15.0%), because the selector-lambda chains are gone.
- **Field braces (§4.5)**, 2026-10-20.
- **The flex rename.** Unification metavariables are *flexes* (`VFlex`, `FlexStore`, …), so an identifier containing
  `Meta` in `monomorphize/` means refinement meta.
- **Totality revisited (Part II)**, 2026-10-20. The structural design was first written leaving type-parameter leaves
  exempt and treating a not-computed meta as a value: "⊤ fields", "a `Never` recorded as ⊤", "all-or-nothing
  knowledge". It was revised against §1's principle the same day. That revision proposed `Meta[D]` gaining a `top` for
  the leaves fed through a frame, a cell or a loop, and it was declined: the process needs no top. So those values are
  followed (§11), a loop is a fixpoint the domain widens, and recursive `data` folds into a summary instead of keeping
  `Unit`.
- **Part II decided**, 2026-10-21. ⊥ was accepted as a fact about control rather than a special value (B), as the way
  to the rest of Part II, and A and C–I were then taken as recommended. Nothing was built.
- **T1–T3 built**, 2026-10-21. T3's first cut followed a handle's frame by reading the data shape in the channel
  (constructors through `ModuleConstructors`, `handleCases` by name, as the `match` native does). It was green, and it
  crossed §20, so Robert chose generated statements instead (`{built}`, `fieldOf`, `fieldOr`), the value-level half of
  §13.4.

## 20. Do not re-propose

- **A shadow twin program** (`f$Meta` emitted per value). It doubles the compiler pool, and it has no node-level
  correspondence to the monomorphized body, so every higher-order transfer would degrade.
- **A wrapper top** (`Bound` around a slot, the generic `Meta[Bound[D]]`). Any second spelling of a top reads as a
  disagreement. A domain grows its own top.
- **A `top` (or `bottom`, `unknown`, `missing`) member on `Meta`, or any process-level value standing for a meta.**
  The process has no special values; a domain may have a top in its own value type (§18). A gap is closed, never
  represented.
- **A budget whose exhaustion hands on a missing meta** (§5.3, decision I).
- **An `{Inf}` leaf may not return a meta-carrying type.** It buys nothing, since a leaf is summarized, never executed.
  It would also forbid an honestly bounded blocking read.
- ~~**A brace on a bodied return** (§3.3).~~ **Reversed 2026-10-23 by decision O** (§8.1): a brace on a bodied value
  states its result's meta, trusted. What stays declined is a brace *checked* against the derivation, which could
  only ever widen what is derived.
- **Proofs or properties.** Named predicates introduced by a checking primitive and carried as proven facts (offered
  2026-10-23). Robert: *"I definitely don't want proofs, nor properties. … I just want values."* A meta is a value,
  and a value the derivation cannot see is stated (O).
- **Flattening a `data` value's meta into one record**, or a declared slot replacing its field metas (§17.2's Q4, both
  offered 2026-10-23). ~~A field's meta is read through the field's accessor, nested (decision P).~~ Since decision R
  no meta code reads a field's meta: the compiler flattens nothing, and a constructor's statement flattens what its
  slots need, by the author's hand.
- **Meta accessors named after a `data` type's fields**, a public mirror of the structural meta (O4a, planned and
  dropped 2026-10-23, decision R). In meta code a name means a meta's own slot or an ordinary value; a field
  accessor applied to a meta names a runtime field of a value meta code does not have.
- **The accessor stating a field's meta.** It needs an R3 exception, and it misses `match` arms and multi-constructor
  types.
- **The channel naming `PatternMatch`, or any branch construct or leaf.** A generated statement carries the link. That
  holds for following a constructed value too: reading a data type's constructors and dispatching `handleCases` in the
  channel was built and green in T3, and replaced by `{built}`/`fieldOf` (2026-10-21).
- **Naming a meta type by spelling** (`T` ⤳ `T$Meta`) in a companion (§4.1).
- **Per-platform transfer braces, and a byte-size slot** (§2.2).
- **A per-target string unit**, whether the host's count or a contributed `sizeOf` native (§2.2).
- **Stamping nothing at a static-method call**, or **emitting a native at the narrowed width** (call-site-sensitive
  codegen). The first guts transfers; the second splits a shared method per meta.
- **Meta-split monomorphization.** Refinements stay invisible to instantiation identity (§7).
- **A derived `where`.** A precondition is a stated contract. P5 checks uses and declares nothing.
- **A definition-site demand check over not-computed parameters.** That is a modular certificate; P5 replaced it.
- **A tag-blind structural meta**, or **single-constructor data only** (§13.1).
- **Recursive `data` with the meta `Unit`.** It loses every meta stored in the structure (§13.3).
- **An empty type as ⊥** (`exitInternal: K => Nothing`). It would change every exit primitive's signature on both
  tracks and need an eliminator with no handlers. `{never}` states the same fact at the leaf that knows it.
- **⊥ by parametricity** ("a native returning a type parameter it was never given does not return"). That is true of a
  parametric function and not of a native. It is inference where a statement belongs.
- **A memo before a measurement** (§5.3).

## 21. Citation map

Code comments cite the retired documents by section. Read them through this table.

| retired citation | here |
|---|---|
| `total-meta-transfers.md` §1, §2 (closure, induction) | §1 |
| … §2.1, §2.2 (leaf boundary, where a transfer lives) | §3.1, §3.2 |
| … §2.3 (structural meta) | §13 |
| … §3 (R1–R4), §4 (use-site R2) | §3 |
| … §5 (tops), its amendments | §2.1, §19 |
| … §5.1, §5.2 (loops, budget, iteration) | §3.2, §5.3, §11.2 |
| … §6, §6.2, P4, S8 (the interpretation) | §5 |
| … §7 (meta vs. stamping, layout) | §7 |
| … §8.1 (companion naming), S7 | §4.2 |
| … §8.2 (braces do not merge) | §3.2 |
| … §8.3 (what users are told) | §8 |
| … §8.4, §8.5 (budget, memo) | §5.3 |
| … §8.6 (flex rename) | §19 |
| … §8.7, §8.8 (companion layout) | §4.1 |
| … §8.9 (parameter braces) | §4.4, §3.3 |
| … §8.10 (field braces, handed statements) | §4.5 |
| … §9 P1–P3, §P2, §P3 (R2/R3 armed) | §3, §19 |
| … §P4 S9 (round-trip peephole) | §7 |
| … P5 | §6 |
| … S10 (closures) | §5.2 |
| … §9 S11 (`match` join) | §4.6 |
| … P6 | Part II |
| … §10 (non-goals), §11 (decisions); decision 8 (`power`) | §18, §20 |
| `string-length-meta.md` §1, §3.1–§3.3, §5.1, §5.2, §6 | §2.2 |
| … §5.3 (second slots) | §2.3 |
| … §4, §7, §10 (prototype, arming, stages) | §19 |
| … §8 (result edge) | §7, §19 (S3) |
| … §9 (precision reality check) | §7, §8 |
| … §11, §12 | §18, §20 |
| `structural-meta.md` §1 | §9 |
| … §2, §3.1–§3.3 | §13.1–§13.4 |
| … §3.4 | §13.5 |
| … §3.5 | §12 |
| … §3.6 | §14 |
| … §3.7–§3.9 | §13.6–§13.8 |
| … §4, §5, §6 | §15, §16, §10 |
| … §7, §8, §9 | §20, §17, §18 |

Three older refinement documents were retired before these and are still cited by code. Their text is at the commit
before their removal: `generic-refinement-merges.md` and `bounds-as-refinements.md` at `19448816^`, and
`refinement-channel-follow-ups.md` at `5e165f0f^`.
