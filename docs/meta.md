# Meta — the refinement channel

Status (2026-10-20): **two domains shipped, R2 and R3 armed, the interpretation and in-context demands built** (Part I).
**Completing totality is designed, with decisions pending** (Part II):
- every leaf states its meta, type-parameter results included;
- what reaches a native through a frame, a cell or a loop is followed by the interpretation;
- a value that is never produced is ⊥;
- a `data` value carries the metas of its fields.

Nothing in Part II is built. Where the work stopped, and how to continue it, is §18.1.

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
That is not a meta. Each place is a gap to close (§8), not part of the design.

---

# Part I — The shipped design

## 1. The model: every value has a meta

A type may declare **meta slots**:

```eliot
type Int {range: Interval[BigInteger]}
type String {size: Interval[BigInteger]}
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

`type String {size: Interval[BigInteger]}`. This is the domain that proved the machinery generic rather than
`Int`-shaped. It cost one compiler arm (the literal seed) and one declaration; every other piece was already
shipped.

- **The unit is the code point**, the count `String::length` returns on every target (S0). It is a language
  decision, not a channel detail. Only a property of the string *value* means the same thing on every target, so a
  `where` over a per-target count would hold on the JVM and fail on an MCU. The index family (`substring`,
  `indexOfInternal`, `take`/`drop`, and `split`/`replace` on an empty argument) switched units with `length`, on both
  tracks. The unit is stated once, on `type String` in `lang`.
- **The slot is `size`, not `length`.** A slot generates an accessor in the type's own module and Default namespace,
  and `def length(s: String): Int` already lives there ("Name was already defined in this module"). Moving meta
  accessors into `Qualifier.Meta` is the follow-on that lets a domain use any slot name. `List` is owed it first,
  since its natural slot name is also `size`.
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

What the `String` leaves state (`n` is `size(s)`):

| leaf | stated |
|---|---|
| `length` | `{size(s)}`. This is in the base, because the identity holds on every target. |
| `combine` (`++`) | `{size(a) + size(b)}`, exact |
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

**R2: a leaf must state its transfer.** It states it as a brace on its return: `def length(s: String): Int {size(s)}`.
The check is **use-site and post-monomorphization** (*Use-Site Verification*): whether a generic leaf's result
carries meta is known only once its type is ground. It reads the **evaluated** result type, so
`def readGauge: Reading` over `type Reading = Gauge` is not exempt by spelling. Its fact,
`MetaTransferAccounting`, is a **codegen precondition** in `WovenValueProcessor`: a leaf that states nothing blocks its
own codegen, the same shape and reasoning as an undeclared effect. A leaf whose brace lists slots over a result that
carries no meta is reported at the leaf.

*Shipped with one exemption, reversed in Part II (§10):* a leaf whose result head is a **type parameter**
(`exitInternal[K, A]: A`, `foldLeftInternal[A, B]: B`) is not checked, on the grounds that it forwards a meta rather
than originating one. That is false for most of them, and those leaves are where the remaining not-computed metas
come from.

**R3: a bodied value derives, and may not state.** A brace on a value with an Eliot body is an error, read after the
layer merge (`MonomorphicValue.runtime`). That makes a brace in one layer over a body in another the same check as
both in one declaration. A brace therefore lives only on a native leaf, so **a user module cannot author a transfer**
(it declares no natives). R2 and R3 are the two arms of `MetaTransferAccountingProcessor`, and both judge the
declaration (`OperatorResolvedValue`).

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
  `{size(s)}`, `combine`'s sum, `show`'s `atLeast(1)`. Exactly one layer may carry a brace: it desugars to a concrete
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
  whatever it returns. So a native's loop (`foreverInternal`) is invisible to the interpretation by construction. A
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
- **A `Unit` meta is known** for a `where`: an argument whose meta type is `Unit` counts as known and is passed as an
  opaque placeholder. A predicate that reads it anyway fails to check in the companion and is reported.

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

- **The budget is a step count**, `interpretationBudget = 256` per call node, threaded through depth *and* fan-out
  together. A hard count bounds a diamond-shaped call DAG absolutely, and a cache would not: distinct argument metas
  defeat its key. Today, when the budget runs out, the node is left with no meta, and a demand on it fails. That is
  never a wrong answer, but it is a gap (§1). Part II makes the budget a compiler limit: running out of it is an error
  naming the call, never a value handed on (pending decision I, §18).
- **The type gate makes it affordable.** Before any body is fetched, a call whose result type carries no meta is
  skipped. Under today's non-structural R1 that is the overwhelming majority of call nodes: the 43-example sweep ran
  4m18s with the interpretation against 4m20s without. Part II §13 opens this gate, and §16 measures it.
- **There is no memo.** A memo on `(vfqn, typeArgs, argMetas)` is the prepared answer if the budget or the clock is
  ever observed to bind. Measure it with `--statistics`, diffing a run with and without the flag, since the flag
  itself inflates a build by about 20%. P5's contexts are already such a memo, for demands (§6).
- **A loop's meta is a fixpoint** (Part II §11.2). A loop lives in a native, which states it through one primitive
  of the transfer language recognised by name, because the channel names no leaf. *Finite* is not *computable at
  compile time*: `readLines`' size is truthfully `0 .. 2³¹−1`. So the iteration count is never run out; the domain's
  widening makes the fixpoint finite.

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
| a field read out of a `data` value | `orZero(Some(7))`, `case Some(x) -> x` | structural meta (§13) |
| a call that reaches an exit | `fold(c, 5, abort)`: `exitInternal` states nothing | ⊥ (§12) and R2 without exemption (§10) |
| what an escape, a cell or a loop produces or hands its code | `runAbort`, `runStateToPair`, `foldLines` | §10, §11, §14 |
| a holder that returns any type | `resultValue[A](r: IoResult): A` | decomposing it in the jvm layer (§11.3) |
| the elements of a `List` | `foldLeft`'s `combine` is handed an element with no meta | `List` carrying its elements' meta (§13.3) |
| hence `if … else` | `else` reads its result back out of an `Option` built by an escape | all of the above (§15) |
| a demand on a closure the walk cannot see applied | `applyTo(y -> useByte(y), 10)`; `useByte(f(x))` with `f` a parameter | a context that can carry a closure (a fact key holding a body and an environment), when a program needs it |
| the step budget | a deep or wide derived DAG | a memo when observed, and exhaustion reported as an error (§5.3) |

The first six rows are not separate defects. Each is a leaf that does not define its meta, or a structure that
cannot carry one, which is what Part II finds.

The `eliot-code` skill tells users what a range is derived through and what is not computed today. It was probed
against the tree, not taken from this document. Update it when a row closes.

---

# Part II — Completing totality (design, decisions pending)

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
| `exitInternal[K, A](frame, err): A` (Abort, Throw) | `{never}`: it does not return (§12). What it is given is received by its frame (§11.1). |
| `escapeInternal[K, A, R](body, onExit, onValue): R` (Abort, Throw) | `{joined(onExit, onValue)}`. It hands `onValue` what `body` yields, and `onExit` what `body`'s exits gave the frame, which is ⊥ when nothing exits (§11.1, §14). |
| `withCellInternal[S, A, R](initial, body, combine): R` (State, Writer, Dep) | `{combine}`. It hands `combine` what `body` yields and the cell's content when `body` returns (§11.1). |
| `readCellInternal[S](cell): S` (State, Writer, Dep) | the cell's content where it is read (§11.1) |
| `foldLeftInternal[A, B](list, initial, combine): B` | the loop's fixpoint from `initial` (§11.2), handing `combine` the meta of the list's elements, which `List` does not carry yet (§13.3) |
| `foldLinesInternal`, `foldCodePointsInternal` (File) | the loop's fixpoint (§11.2), once their holder is decomposed (§11.3), handing the step `{closed(0, 2147483647)}` per line and `{closed(0, 1114111)}` per code point |
| `resultValue[A](r: IoResult): A` (File) | none can be honest: it returns its holder's content as any `A`. Decomposed away (§11.3). |
| `Bool::fold[A]` | already `{join(whenTrue, whenFalse)}` |

`foreverInternal` returns `Unit`, so R2 asks nothing of it, but it may state `{never}`: it returns only by an exit. Its
step's writes and exits are a loop (§11.2).

The armed check is the proof of the table. Removing the exemption compiles the tree and the examples, and any leaf
the table missed is an error naming it. It can be removed only once the last row can state, which is
`foldLeftInternal`, waiting on `List` (§17).

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

The interpretation gains a value for a frame and a cell, each with an identity local to one call and its received
meta or content. The natives say what they hand through a few compile-track meta primitives, recognised by name as
`joined` and `never` are (§14). So the channel still names no leaf. This is the one place the interpretation becomes
stateful, and the state never outlives the call that made it.

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

### 11.3 A holder that returns any type is decomposed

`resultValue[A](r: IoResult): A` returns its holder's content as whatever `A` its caller names. Nothing relates `A`
to what the producing native put in the holder, so no statement can be honest. Per §1, the gap is closed in the
declarations:

- the file natives read a concrete result through typed leaves, each stating its own meta
  (`resultString(r): String {closed(0, 2147483647)}`, and so on);
- a generic fold returns its accumulator itself and takes its error path as code, so its result is the loop's
  fixpoint joined with what the error path yields, which is ⊥ when the path raises.

That is a platform decomposition written in Eliot, over smaller leaves, and it needs no compiler change.

## 12. ⊥: a value that is never produced

**Pending decision B; recommended.** The interpretation's `Value` gains **`Never`**: *evaluating this does not
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
- **A demand under ⊥ is vacuous** (pending decision C; recommended). A demand whose call is never made (a `Never`
  argument, or an arm handed ⊥) is not checked in that context. This is P5 read literally: a demand is checked where
  it is *reached*. `f(Some(5))` compiles over `case None -> useByte(1000)`, and adding `f(None)` reports at that call.

## 13. Structural meta: a `data` value carries its fields' metas

**Pending decisions E–G; recommended.**

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

### 13.5 A field's meta is always defined

A constructor's transfer stores each argument's meta. By §1, each argument has one: a computed or stated meta, or the
known `Unit`. So a structural meta has no unknown field, and needs no per-field "unknown" form. A `Known | Unknown`
wrapper would have been a second spelling of every domain's top.

A gap in an argument is a gap in the construction. It propagates, and is never replaced by anything. That needs two
mechanical repairs to the stated-transfer path, and both are independent of `data`:

- **A not-computed argument reaches a companion as a dedicated neutral, and a reduced result containing it is not
  computed.** Today it arrives as a `SemValue.VType` placeholder. That is safe only because every companion
  *projects* its arguments: `range(a)` stalls on it. A constructor transfer *stores* its argument, so the placeholder
  would quote into the structure as `Type`, a well-formed and wrong meta.
- **A `Unit` meta is passed as `unit`**, never as a placeholder. It is known (§4.1 already counts it as known for a
  `where`).

### 13.6 Structural meta has no surface

A user cannot write one. There is no brace on a `data`-typed parameter, field or leaf return: a brace lists slots, and
R2's "lists slots over a result that carries no meta" check widens to "…or whose meta is structural". A `where` cannot
read one either, since the generated accessors are in no user's scope. Structural meta carries a slotted type's meta
through a data value. It is observed only through that slotted type's `where` or brace downstream. That is also why
the generated records may be named freely: no user code can depend on their shape.

### 13.7 Layers

A data type's meta is generated from its **compiler-pool** declaration: the jvm layer's (borrowed), or the compiler
overlay's where one supersedes it (`Option`, `Either`, `Pair`, `Bound`, `Interval`). The bodies the channel
interprets are the runtime track's. Handed transfers go by handler *index*, so an overlay that declared constructors
in another order would hand arm *i* the wrong constructor's fields, silently. **Required** (pending decision H):

- the two declarations agree on constructor names, order and field count;
- this is checked where the meta type is first asked for;
- a disagreement is an error at the overlay's declaration.

Today every overlay copy is spelled exactly as the jvm layer's.

### 13.8 Representation

Nothing in the backend reads a structural meta: a `data` is an object whatever is known of its fields. One hardening
goes with it. `IntRepresentation.intervalBounds` decodes `Int$Meta(Interval(…))` by **shape**, without checking the
name. It should check the FQN, as hover does (§2.2).

## 14. What a native hands its code

§4.5's handed statements are generated. `escapeInternal`'s and `withCellInternal`'s are hand-written, so they need a
spelling. The rule of §3.3 already covers it: a code parameter's **arrow domain** is a type occurrence whose supplier
is the callee. That gives the table a fifth row:

| Position | Supplier | Visible to the compiler? | So |
|---|---|---|---|
| handed argument (arrow domain) | the callee running the code | not for a native | stated, axiomatic |
| handed argument, bodied callee | the body | **yes** | **declined (R3)** |

```eliot
private def escapeInternal[K, A, R](body uses *: AbortFrame[K] => A, onExit uses *: K {exited(body)} => R,
                                    onValue uses *: A {body} => R) uses *: R {joined(onExit, onValue)}
```

`{body}` after `onValue`'s domain says that what `onValue` is handed is what `body` yields. It is read as a code
parameter always is, as the value its code yields. `{exited(body)}` after `onExit`'s says it is handed the join of what
`body`'s exits gave the frame (§11.1). The cell's natives say the same of its content:

```eliot
private def withCellInternal[S, A, R](initial: S, body uses *: StateCell[S] => A,
                                      combine uses *: A {body} => S {stored(body)} => R) uses *: R {combine}
private def readCellInternal[S](cell: StateCell[S]): S {content(cell)}
private def writeCellInternal[S](cell: StateCell[S], s: S): Unit {store(cell, s)}
```

`exited`, `stored`, `content` and `store` are compile-track meta primitives, recognised by name as `joined` and
`never` are, so the channel names no native. Their spelling is part of decision A.

The arrow-domain grammar slot is free: a brace *before* a type is the rejected row spelling, and one *after* a
parameter's type is §4.4's. It desugars to the handed companion the data desugarer writes
(`escapeInternal$Handed$onValue$0`), which can now read parameters. It is accepted on a body-less def only (R3).

**Declined (pending decision F): reshaping the escape primitive** to `escape(body: Frame => R, onExit: K => R)`, so
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
  and the result is the body's alone.
- **`pick(B)`** with `data AB = A | B`, `pick(x) = x match { case A -> 10 case B -> 20 }`. Today it is `[10, 20]`.
  With `B`'s meta `{Unreached, Reached(unit)}`, the `A` arm is handed ⊥, so `pick(B)` is `[20, 20]`.

## 16. Cost and risk

- **The type gate opens.** Every data type now carries meta, so every call returning one is
  interpreted. So is every handle, since a named implementation's handle is a `data`. Handles stay in: excluding them
  would key a rule on `Handles.isDataType` and lose a double's `with` arguments, which are exactly what a test's
  `where` would read.
- **P5's contexts multiply.** A context is keyed by the metas of every meta-carrying parameter, so a deferred body
  taking an `Option[Int]` gets one context per distinct structural meta its callers supply. Contexts exist only for
  bodies whose root defers, but that bound is not measured.
- **The interpretation carries state, and computes fixpoints.** Following a frame or a cell (§11.1) threads their
  contents through one call's interpretation. A loop (§11.2) iterates its step until the domain's widening is stable,
  which for an interval is a few steps per endpoint.
- **Generated values.** Per data type, the desugar generates a meta structure, a record per constructor with fields,
  a join instance, a `$Fields`, a transfer per constructor, and a handed transfer per handler argument. Each record is
  itself desugared (type, constructor, accessors, eliminators). This is compiler-pool and demand-driven, but every cold
  build mints the names.

So structural meta's stage is **gated on a measurement**: the sweep's wall time, `--statistics` with and without the
flag, and the cache size, against the stage before it. If the budget or the clock binds, the memo of §5.3 is added
then.

## 17. Staging

Each stage is verified by the fast example sweep and byte-identity compare (`scripts/example-sweep.sh`), with every
example's output unchanged and the whole suite green. Clear `target/` between the two halves of a compare: the fact
cache once kept a stale companion across a change and hid a brace that did not evaluate.

- **T1: gap mechanics** (§13.5, §13.8). The dedicated neutral and gap propagation; `Unit` metas passed as `unit`;
  `intervalBounds` checks the FQN. Expected byte-identical.
- **T2: ⊥** (§12). `Value.Never`, `{never}` on each `exitInternal`, strictness, same-meta-type substitution, vacuous
  demands. It is observable through a demand in an `Abort`-using body: `def g(c: Bool) uses Abort: Int =
  useByte(fold(c, 5, abort))` is proven.
- **T3: frames and cells** (§11.1, §14). The arrow-domain brace in the parser and desugarer; the frame and cell values
  in the interpretation, and their primitives; the statements of the escape and cell natives. `catch` and
  `runStateToPair` become accept/reject pairs. `if … else` still waits on the field read.
- **T4: loops and the holder** (§11.2, §11.3). The fixpoint primitive and `Meta`'s widening, with an interval's; the
  file folds and `resultValue` decomposed in the jvm layer.
- **T5: structural meta** (§13). `Reach`, the structures, records, join instances and `$Fields`; the recursive groups
  and their folded summaries; constructor and handed transfers; `metaConstructorOf` for a generic meta type; the overlay
  agreement check. This flips `DerivedMetaTransferIntegrationTest`'s field-read case and makes `if … else` an
  accept/reject pair, and it adds `Pair`, `Either`, a user record, a user tree and `pick(B)`'s pruning. Jars change
  wherever an `Int` is read out of a `data`. **Gated on §16's measurement**, which also decides the memo, and with it
  the budget becoming a reported limit (decision I).
- **T6: `List` carries its elements.** The parametric slot (§13.3), with the size domain. `foldLeftInternal` states,
  and R2's exemption is removed: the last runtime leaf now states.
- **T7: the record.** This document's status, CLAUDE.md's refinement-channel paragraph, and `eliot-code`'s list of
  what is not computed today.

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

**Pending, each recommended:**

- **A. Frames and cells are followed** (§11.1): the interpretation threads what reaches a frame and a cell through the
  one call that made it, and the natives state it through name-recognised primitives (§14).
- **B. The interpretation gains ⊥** (§12): `{never}` at the exits, strictness, same-meta-type substitution, and
  monotonicity as an obligation on statements. ⊥ says *this call does not return*: a fact about control, which every
  join absorbs. It is not a marker for a missing meta, but it is the one form the process adds, so it is listed for
  your call.
- **C. A demand an unreached path holds is vacuous** in that context (§12), rather than checked with unknown binders.
- **D. A loop's meta is its fixpoint, which the domain's widening makes finite** (§11.2): `Meta` gains `widen` beside
  `join`, an operation and no value. This reverses the earlier open recommendation of a budget for iteration.
- **E. A sum's meta is tagged by reach** (§13.1), rather than a tag-blind structure or single-constructor data only.
- **F. The arrow-domain brace** (§14), rather than reshaping the escape primitive.
- **G. Recursive `data` folds into its own summary** (§13.3), joined over every node, with mutual recursion folded over
  the group. Shape stays the size domain's.
- **H. An overlay `data` must agree with the runtime one** (§13.7), as an error.
- **I. The step budget becomes a compiler limit** (§5.3): running out of it is an error naming the call, never a value.

**Open, not blocking anything:**

- **May an ability declare a transfer its implementations must satisfy?** (§3.1). A contract feature, not part of
  totality.
- **A compile-time `power` native**, which would make `parseIntInternal` tight (`"42"` ⤳ `[-99, 99]`). Safe now that
  the interval domain has `whole` to saturate into. A prerequisite for nothing.
- **Iterating a loop exactly** when its count is known (`[n, n]`), instead of widening (§11.2).
- **An interval intersection**, which `substring`'s index ceiling waits on (§2.2).
- **A meta-accessor namespace** (`Qualifier.Meta` for slot accessors), owed to `List` (§2.2).

### 18.1 Where this was left (2026-10-20)

Part II is a design, and no code for it exists. Part I matches the tree at `336992f4`. The work stopped on one
question to Robert:

> **Is ⊥ a special value?** Decision B gives the interpretation `Value.Never`. It means *this call does not return*:
> a fact about control, which every join absorbs, and never a placeholder for a missing meta. `if … else` needs it,
> because `fold(c, 5, abort)` should be `[5, 5]`, not unknown. The only other encoding is an empty type
> (`exitInternal: K => Nothing`), which §20 declines because it changes every exit primitive's signature on both
> tracks. Does ⊥ fit the rule that the process has no special values, or does it fall under it?

To continue:

1. **Answer B.** If ⊥ is out, §12, the traces in §15 that prune an arm or absorb an exit, and T2 need another way to
   say that a call does not return. That way must not add a value to the process. Record the answer here, and as a
   reversal wherever it overturns text.
2. **Decide A, C–I.** Each is recommended. A records only the shape; the spelling of the frame and cell primitives
   (`exited`, `stored`, `content`, `store`, §14) is still open, and so is how D's `widen` sits beside `join` on `Meta`.
3. **Start T1** (§17), which is expected to be byte-identical. T5 waits on §16's measurement, and T6 on `List`
   carrying its elements' meta.

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
- **A brace on a bodied return** (§3.3).
- **The accessor stating a field's meta.** It needs an R3 exception, and it misses `match` arms and multi-constructor
  types.
- **The channel naming `PatternMatch`, or any branch construct or leaf.** A generated statement carries the link.
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
