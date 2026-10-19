# Total Meta Transfers — meta as a shadow logic

Status: **R2 and R3 are armed and the interpretation's walk has landed.** **P1, P2, P3 are done** (S5, S6), **§8.1's
naming fix is done** (S7), **P4's walk is done** (S8, §P4 below) and its one debt — the round-trip conversions the
derived metas made common — **is repaid** (S9, the backend peephole in the same section). A value with a body no longer
stops the channel: its meta is obtained by *interpreting that body* under the caller's argument metas, so the closure
invariant of §2 is executed rather than merely enforced. What is left of P4 is §7's separation (the walk still drops
every record inside a lambda, which is what makes a *parametered* def's own table empty) and the higher-order rows of
§5, which additionally wait on the `List` size domain. Every
native leaf in the tree that produces a meta-carrying type states its transfer, **nothing else states one**, and
`monomorphize/channel/MetaTransferAccountingProcessor` is a **codegen precondition** in `WovenValueProcessor` —
a leaf that states nothing no longer compiles, and neither does a bodied value that states one. With it the
channel's own untotality is gone: a meta that is absent now means "nobody has computed this yet", never "a native
quietly defaulted to ⊤" — and since S8 the *bodied* half of that residue is answered too, leaving only what the
interpretation itself declines (a lambda, a `match`, a budget it exhausted). The pieces that had to land first all did:
the range domain's top (`whole`, §5), the removal of the last *global* top (the generic `Meta[Bound[D]]`, §5's third
amendment) — so every top is a domain top and the machinery keeps none — and the backend's result-edge re-encode
(`docs/string-length-meta.md` §8, S3), which the first stated transfer tripped and which S8 then leaned on at every
derived call node.

**S4 stated the whole of `String.els`** (`docs/string-length-meta.md` §10): `length`, `combine`, `substring`,
`trim`, `toUpperCase`/`toLowerCase`, `repeat`, `replace` and `indexOfInternal` all carry a brace, and
`parseIntInternal` carries the domain's top. **S5 stated the residue and armed** — eleven more leaves, listed in
§P2, split exactly along §2.1's rule: two in the **base**, whose bound is a property of what the operation *is*
(`Show[Int]::show`, `Show[Path]::show`), and nine in the **jvm layer**, whose bound is a property of the JVM's own
representation (the input and holder natives). Three facts those two stages established are load-bearing and are
recorded in §P2 below — a brace could not name a number until S4 worked around it, since fixed at the root (a
brace's literals are compiler-track `BigInteger`s); a leaf whose honest bound is exponential states the top rather
than running the exponent; and a brace could not be stated at all on a def with an *untracked parameter* until S5
fixed the companion desugarer (§8.7).

A **second domain landed** before arming — `type String {size: Interval[BigInteger]}` with its literal seed
(`docs/string-length-meta.md`, S1+S2) — which changed nothing here structurally (a nullary type's meta is a plain
one-slot structure; §2.3's *structural* map is still owed by `List`) but enlarged what arming R2 cost: every
body-less `String`-returning leaf produces a meta-carrying type and so joined §P2's list. That is the sequencing
that document argued for and this one accepted: state those transfers once, in S4/S5, and arm after.

**Closes** the TODO item *"A native that produces a meta-carrying type must state its meta-information"* by
generalising it: the TODO closed one hole, this closed the class of holes it belonged to.

Prior art: the refinement channel (`Int`'s `range` slot, `^Meta` transfer companions, `^Where`
preconditions, `Meta[D]` join) — shipped; see `monomorphize/channel/RefinementChannelProcessor` and the
`project_refinement_channel_idea` / `project_generic_refinement_merges` memories.

---

## 1. The proposal

1. Metas are **total on types** — every type has a meta counterpart.
2. A **native must state** its meta transfer; omitting it means "`Unit`", and it is a compile error if the
   return type's meta is not actually `Unit`.
3. A **non-native derives** its transfer, always; stating one on a non-native is an error.
4. Therefore there is never an "undefined" meta: meta is a **full shadow logic running behind each
   function**.

This is right, and it is what the channel was already heading towards. Today the flow analysis is
intra-procedural, so every call boundary is ⊤, and the only reason arithmetic narrows at all is that
`Numeric[Int]`'s methods happen to be leaves carrying a brace. Precision is currently a property of *where
code sits relative to a native*, which is not a principle. Totality makes it a property of what the code
*is*.

**It should also come out smaller than today.** §7 is the argument for that, and §5 is the one decision
that determines whether it does.

---

## 2. The invariant

> **Closure.** Every named value has a meta transfer.

By induction:

1. **Base.** Every **leaf** — a value with no Eliot body anywhere on the path — states its transfer.
2. **Step.** Every other value derives its transfer from its body, whose calls all have transfers by (1)
   or (2).
3. **Termination.** *Total by Default*: the recursion gate rejects every cycle in the runtime-body
   reference graph, so the derivation walks a DAG.

Point 3 is why Eliot can have this and most languages cannot: the shadow is a homomorphic image of a
program already known to be acyclic. No fixpoint iteration, no widening, no ascending-chain condition —
**except at leaves**, which is exactly where the loops live (`foldLeft`, `forever`) and exactly where a
statement is demanded. The rule and the language's shape agree.

### 2.1 The leaf boundary is the true native leaf

The compiler pool scans *both* mount pools — `PathScanner`, `Platform.Compiler`: "the compiler track sees
the runtime track completely plus its own override overlay". So it reads the `jvm` layer's Eliot bodies
like any other, and derivation walks straight through them. A leaf is therefore what the plain reading
says: **no Eliot body on any mount** — a Scala/bytecode native, the bottom the native-leaf fail-safe
already guards.

Two consequences worth naming:

- **A leaf's transfer is *usually* platform data**, contributed by the layer that owns the native, next to its
  native binding — the same shape as `add` having a per-platform realisation, and the reason a platform leaf
  almost always has a *true, useful* bound to state rather than "don't know" (§5).

  > **Amended by S3: not always, and the example this bullet used is the exception.** It read
  > "`String::length` is `0 .. 2³¹-1` on the JVM and something else on an ATtiny". That was true of the bound
  > this document expected `length` to state, and false of the bound it actually states: `{size(s)}` — the
  > string's own tracked size. The difference is that a *representation* bound is platform data, while a leaf
  > whose result is an exact function of its argument's meta states that function, and a function can be
  > platform-independent. `length`'s is, because S0 made the unit the code point, so the identity holds on
  > every target and the brace belongs in the **base** declaration.
  >
  > The rule to carry forward is therefore sharper than "transfers are platform data": **a transfer lives in
  > the layer that owns the *fact it states*.** Ask what the bound is a property of — a representation
  > (platform: `Process::exitCode`'s `[0, 255]`, `readLine`'s `[0, 2³¹−1]`) or the argument's own meta (base:
  > `length`, and by the same reasoning `combine`'s `size(a) + size(b)`, §6 of `docs/string-length-meta.md`).
  > §8.2's constraint is unaffected and is what makes this safe: a brace is a *concrete* value, so exactly one
  > layer may carry it — and the second kind of transfer is precisely the kind no platform would want to
  > restate.
- **Derivation may pass through a jvm body but must stop at the native under it.** The compiler pool can
  *read* that body; it cannot *run* the bytecode leaf at the bottom (it stalls loudly). So the leaf under
  the jvm body is the one that must state — which is the same rule, applied at the real bottom.

### 2.2 An ability method declaration is not a leaf

`def add(a: T, b: T): T` inside `ability Numeric[T]` is body-less, but it is not a site that states
anything — it resolves to an *implementation*, and the implementation is either bodied (derives) or a
native (states). This is already how it works: `implement Numeric[Int] { def add(a, b): Int {range(a) +
range(b)} }` is a body-less impl method carrying the brace directly.

It matters operationally: of the ~167 body-less `def`s in the tree, the large majority are ability method
declarations. A leaf predicate that misses the distinction turns enforcement into a hundred spurious
errors.

Open question worth deciding separately: may an *ability* declare a transfer its implementations must
satisfy? That would be a stated contract on a non-leaf — useful, but it is a different feature (a
contract), not part of this.

### 2.3 Meta is total on types — but trivially, today

`metaTypeOf` is already total: `T` ⤳ `T$Meta` when `T` declares meta slots, else `Unit`. What it is not is
**structural**: `List[Int]`, `Option[Int]`, `data Counter(n: Int)` all ⤳ `Unit`. Meta does not follow a
value into a container.

The structural map — `meta(D(f₁: T₁, …)) = D$Meta(f₁: meta(T₁), …)`, meta as a functor on the type
structure — is the natural completion and is what the second refinement domain (`List`/`Array` `size`)
needs anyway. **Out of scope here**, because it multiplies the number of leaves forced to state and needs a
decision on recursive `data` that the size domain should drive.

Consequence to accept: meta is lost at every data boundary, exactly as today.

---

## 3. The four rules, precisely

**R1 — Every type has a meta type.** `metaTypeOf(T)` = `T$Meta` if `T` declares slots, else `Unit`;
non-structural. (It was also stated *carrier-transparently*, `metaTypeOf(F[A]) = metaTypeOf(A)` — a
computation's meta is its payload's meta. Effects v6 removed carriers, so a computation is a thunk and
the clause has no subject: `metaTypeOf(Unit => A)` is the ordinary function case.)

**R2 — A leaf must state its transfer.** The existing return brace. Omitting it asserts *"this return's
meta type is `Unit`"*; if it is not, that is an error (§4). A leaf with genuinely nothing to say states so
explicitly (§5).

**R3 — A non-leaf derives, and may not state.** A brace on a value with an Eliot body is an error naming
the fix. **Armed** (S6, §P3): the second arm of `MetaTransferAccountingProcessor`, split from R2 on the one
test that separates the rules — `MonomorphicValue.runtime` — and blocking codegen through the same
precondition. Read post-merge, so §8.2's cross-layer case (the brace in one layer, the body in another) is
the same check rather than a second mechanism.

**R4 — There is no undefined meta.** ⊤ is stated or structural, never an omission. The channel may still
*lose* precision, but never because nobody said anything.

`where` is untouched: a precondition is a stated contract, never derived.

---

## 4. Checking R2 is a use-site check

For a monomorphic leaf (`String::length: Int`) the check is static at the declaration. For a generic leaf
(`foldLeft[A, B](…): B`) it cannot be: whether `B`'s meta is `Unit` is known only once `B` is ground. So the
normative form of R2's check is **at the use site, post-mono** — if the callee has no transfer and its
ground return's meta type is not `Unit`, error. That is the *Use-Site Verification* cornerstone, not an
exception to it, and it is fail-safe.

The declaration-time check for the monomorphic case is then a nicety that reports at a better position,
not a second mechanism.

Diagnostics need care: the use-site error must point at the **leaf's declaration** while being raised from
a monomorphization — the reporting shape the post-mono channel processors already need.

---

## 5. ⊤: the machinery needs no `unknown` form — but the *domain* did need a top

**Settled, and shipped.** The architecture call below held: nothing was added to `Meta` or to the transfer
machinery. The empirical claim attached to it — that no leaf needs a top either — did not, and the leaf that
broke it is the one §P2 already flagged as doubtful. The resolution is a change to the **domain**, exactly
where this section predicted one would go if it were needed:

```eliot
data Bound[T] = Unbounded | Bounded(value: T)
data Interval[T](start: Bound[T], end: Bound[T])
type Int {range: Interval[BigInteger]}
```

An `Unbounded` endpoint absorbs through `Numeric[Bound[T]]`, and the domain's top is `whole` — the interval
open at both ends. (This shipped in two steps: the top was first a *second* `Bound` wrapped around the slot,
and collapsed into the endpoints once those could express an open end. See the second amendment below.)

> **Amended: `Interval` now has unbounded endpoints too.** The paragraph below argued for a wrapper at the
> slot *instead of* an unbounded endpoint per side, on the grounds that the case that seemed to need them —
> a size — can state `[0, platform max]`. That ground does not hold, and the objection is the layers
> cornerstone: **the base layer may not assume a platform maximum.** Non-negativity of a size is
> platform-independent; its maximum is a property of a representation. With closed-only endpoints an
> abstract declaration has to choose between inventing a maximum it cannot know and stating `Unbounded`,
> which throws away the `≥ 0` it *does* know. So `Interval`'s endpoints are each a `Bound[T]`
> (`data Interval[T](start: Bound[T], end: Bound[T])`), an unbounded endpoint's direction is read from its
> position, and the base states `atLeast(0)` while a platform narrows it to `closed(0, platformMax)`.
> This is the change §5's own last paragraph anticipated — a change to `Interval`, not to `Bound` or
> `Meta` — reached by a different motivation than the §5.2 one it expected.
>
> `Bound` is **kept** at the slot ~~for now~~ — **superseded, see the second amendment below.**
>
> Precision, on the arithmetic: `add` and `subtract` stay **exact** on a half-open interval, because each
> pairs endpoints so an absorbing `Unbounded` lands in the position meaning the infinity it came from, and
> the start/end pairing rules out the indeterminate `∞ − ∞`. `multiply` **widens** to the whole line, since
> its corner products are compared after the multiplication, when a product has lost the position that gave
> its infinity a direction. Sound, not tight — the same trade `Numeric[Bound[T]]` already documents for a
> zero operand, and recovering it needs a signed endpoint view local to that one method.

> **Amended again: the slot-level `Bound` is gone.** With `whole` available, the wrapper added no
> expressiveness — `Bounded(whole)` and `Unbounded` denoted the same thing — so the range domain is now
> `Interval[BigInteger]`, and so is the size domain, each topped by `whole`. The amendment above deferred
> this on two grounds; both are answered rather than waived:
>
> - **Two spellings of the top.** This was the real blocker, and collapsing is what *fixes* it. There is now
>   exactly one structure meaning "nothing bounds this", so a leaf that states the top and a `multiply` that
>   widened into it agree under `ReconcileProcessor.metaByPosition`'s structural comparison instead of
>   cancelling each other to ⊤. Keeping the wrapper was what created the second spelling.
> - **A domain that is not an interval.** Still true, and ~~still served: `Bound[D]` and its `Meta[Bound[D]]`
>   join stay in the tree as the generic top for such a domain~~ — **superseded, see the third amendment.**
>   What changed is that no *shipped* domain is of that kind — both are intervals — so neither pays for a
>   wrapper it does not need.

> **Amended a third time: the generic top is gone, and there is now no global top at all.** The
> `implement[T ~ Meta[T]] Meta[Bound[T]]` instance kept above — `Bound[D]` as the ready-made top of any
> domain `D` — has been deleted (`stdlib/eliot/compiler/eliot/lang/Bound.els`). It was dead code, but it was
> not *only* dead code: it was the last place the machinery offered a top of its own, and keeping it around
> contradicted the thing this document is for.
>
> The principle, stated positively: **every top is a domain top.** A domain says what "nothing bounds this"
> means in its own vocabulary — an interval says `whole` — and the machinery has no opinion, no `top` member
> on `Meta`, and no wrapper that manufactures one. A domain with no interval structure will get a top the
> same way `Interval` did: by growing one, in itself. That is a strictly better position than a generic
> wrapper, for the reason the second amendment already found the hard way — a wrapper is a *second spelling*
> of a top, and `ReconcileProcessor.metaByPosition` compares verdicts structurally, so two spellings of the
> same top read as disagreement and cancel to ⊤. A generic top does not just fail to help a future domain; it
> would hand that domain the bug the range domain had to be rescued from.
>
> `Bound` itself stays, unchanged and load-bearing: it is `Interval`'s endpoint, the thing that makes a range
> half-open. Only its second, aspirational job is gone — the base declaration now says so.
>
> **What this does *not* remove**, and the distinction is the whole of §3's R4: the channel still yields ⊤ at
> a boundary it cannot see through (a companion-free callee, a parameter, a lambda interior —
> `RefinementChannelProcessor`, spelled `None`). That is not a top the *domain* supports; it is the
> machinery's untotality, and it is removed by making the machinery total — R2 armed for leaves (§P2), R3,
> then the interpretation for non-leaves (§P4) — not by deleting a value. Deleting the generic top narrows
> "the meta machinery has a top" to exactly that one honest residue, which is what makes §P2's next step
> legible.
>
> Verified by the byte-identity oracle: all 40 compiling examples produce class content identical to the
> pre-change build, as an unexercised instance must.
>
> Verified by the byte-identity oracle: all 40 compiling examples produce class content identical to the
> pre-change build, since a stated top and a half-open range already decoded to the same ⊤ layout
> (`IntRepresentation.intervalBounds`). The two decoders lost one nesting level; nothing else downstream moved.

The original argument for a slot-level wrapper only, kept because the rest of it still holds: it is a
wrapper at the slot rather than an unbounded endpoint per side: endpoint-level infinities carry more
precision (a half-open `[0, ∞)`), but `Interval` is a runtime user-facing type, and the case that seemed to
need them — a size — states `[0, platform max]`, which is closed *and* keeps its non-negativity. Only a
leaf whose bound is **exponential in its argument's meta** genuinely escapes a closed interval, and there
is exactly one.

This is what R4 asks for. Absence stops being overloaded: a stated top says *"nothing bounds this"*, and
absence goes back to meaning only *"nobody has computed this yet"*. A stated top and an absent meta still
agree on **layout** (both are a bignum — `IntRepresentation.intervalBounds` decodes an open endpoint to
`None`), which is why nothing downstream moved: all 39 example jars were byte-identical across the change,
and all 40 again across the collapse.

The original argument, kept because its shape is still right:

I argued earlier that `Meta[D]` needed a `top`, then that a machinery-level `unknown` transfer form was
needed for `foldLeft`. **Both withdrawn.** Walking the leaves that seemed to need one:

| leaf | honest transfer | needs |
|---|---|---|
| `String::length` | ~~`0 .. platform max`~~ — the parenthesised "better" answer is the one that shipped: **`{size(s)}`**, the string's own size meta, which now exists | nothing |
| `Process::exitCode` | `0 .. 255` | nothing |
| `String::parseIntInternal` | ~~the platform int range~~ — **`Unbounded`**, spelled `whole` and **stated** (S4) | the domain top (above) |
| `Function::apply` | the function argument's transfer, applied | **higher-order** |
| `PatternMatch::handleCases` | join over the cases' transfers | **higher-order** |
| `List::foldLeft` | `combine`'s transfer iterated over `list`'s size meta | **higher-order + §5.2** |

The structural reason there is no gap: **on a real target every meta-carrying type has a representation,
hence a bound.** An `Int`-returning native returns a platform `Int`; an ADC read returns an ADC's width. A
platform leaf that cannot state a true bound is a leaf whose platform has not decided its representation,
which is a different bug. So R2 can be enforced with no escape hatch, and the *absence* of an escape hatch
is what makes it extract the information it exists to extract.

**Where that reasoning fails, and it fails exactly once.** The premise holds for a leaf that *returns* a
representation. It does not hold for one whose result is a **function of another value's meta**, because the
composition can outrun any representation the result is stored in. `parseIntInternal` is
`new BigInteger(String)` — the numeral read at full precision — so its honest bound is `±(10ⁿ − 1)` for an
argument of `n` code points. Two things go wrong at once: `10ⁿ` is not writable (`Numeric` is
add/subtract/multiply, no exponentiation), and at the representation limit `n = 2³¹−1` the honest answer is a
two-billion-digit `BigInteger` — finite, sound, and roughly 890 MB to materialise. A bound that hangs the
compiler is worse than a wide one, so the leaf states `Unbounded` and the exponent never runs.

Adding a `power` native would make the tight cases (`"42"` ⤳ `[-99, 99]`, a `where`-bounded field) genuinely
precise, and it is cheap — its own transfer is endpoint-wise `power` through `Numeric[Interval[T]]`, the same
shape `add` already has. It is a **precision follow-on, not a prerequisite**, and it is only safe *given* a
domain top to saturate into.

`Interval` may still gain unbounded endpoints if its own semantics want them — that is a change to
`Interval`, not to `Bound` or `Meta`, and §5.2 is the only thing that would motivate it. **It has, and
§5.2 was not what motivated it** — the platform-maximum objection above was. The prediction about the
*shape* of the change held exactly: `Bound` and `Meta` were untouched.

### 5.1 Unbounded iteration cannot reach the interpretation

A leaf is **summarized** by its stated transfer, never executed. Only *derived* code is interpreted, and
derived code is acyclic by the recursion gate. So a native's loop is invisible to the interpretation by
construction — `foreverInternal(thunk: Function[Unit, Unit]): Unit` is a leaf, its transfer is `Unit`, and
the interpretation never sees the `while(true)` under it. Robert's observation holds and is load-bearing:
an unbounded loop returns `Unit`, so it can carry no meta.

It holds by construction for `forever` as declared, not as a theorem. I proposed making it one — *an `{Inf}`
leaf may not return a meta-carrying type* — and that rule is **dropped**. It buys nothing and costs something:

- It buys nothing because the guarantee does not come from the return type. It comes from the first sentence
  of this section: a leaf is **summarized, never executed**, so its internal loop is invisible to the
  interpretation whatever it returns. Unboundedness of a loop and the truth of a stated return bound are
  orthogonal.
- It costs something because a blocking native is honestly `{Inf}` and honestly bounded. An ADC or UART read
  that spins until data arrives returns `0 .. 1023`; the rule would forbid stating it, for no gain.

The real guard is the **budget** (§5.2), and it is not redundant with this rule — it is the only thing
standing where iteration actually enters, which is a transfer that *invites* it by calling `iterate`. That is
`foldLeft`'s transfer, never `forever`'s.

Unbounded iteration can therefore only enter the interpretation if we *invite* it — which is exactly what
the fold does.

### 5.2 The fold: executing it is right, and it needs a budget, not an ∞

`list.foldLeft(0, _ -> count -> count + 1)` under B: bind `count` to its meta, evaluate `count + 1`'s
transfer, iterate. The information is all there and B is the only option that can use it — A cannot, since
iterating needs `combine`'s transfer applied to a value the interpretation learns at reduction time, and
A has no way to reach a lambda argument's transfer (§6.1).

Three cases, and the third is the whole question:

1. **Singleton size** `[n, n]` — iterate `n` times, exact.
2. **Size interval** `[lo, hi]` — the result is "after `lo` iterations, or `lo+1`, …, or `hi`": run to `hi`,
   joining each iterate from `lo` on. Exact, and terminates.
3. **Size honestly bounded but large** — `readLines` returns a `java.util.List`, so its size is truthfully
   `0 .. 2³¹-1`. Finite. Not iterable at compile time.

So: **finite ≠ computable at compile time**, and the machinery needs a *budget*, not an ∞. When the budget
is exhausted the interpretation yields absence — sound ("I know nothing" is always true), reportable, and
never wrong.

This is what preserves R4 rather than breaking it. Absence stops meaning *"nobody stated anything"* — a
modelling gap, which R4 forbids — and starts meaning *"the interpretation did not converge within
budget"*, a resource outcome. Different in kind, and only the first was ever the hole.

The alternative to a budget is a **widening operator** — join the iterates until stable — and *that* is
what would need unbounded endpoints: `[0,0] → [0,1] → [0,2] → …` has no fixed point without ∞. So the
choice is exactly: budget ⇒ no unbounded needed; convergence-by-widening ⇒ unbounded needed. **Recommend
the budget.** It is simpler, it is honest, and Eliot deliberately does not have widening machinery — the
recursion gate was supposed to make it unnecessary, and at every point except this one it does.

One constraint on how the iteration is expressed: **the channel must not name `foldLeft`.** The shipped
invariant is that nothing in the channel or reconcile names a leaf or a branch construct — a transfer is
recognised only as a transfer. So bounded iteration must live in the *transfer language* as a primitive
(`iterate(n, f, x)`, where `n` is a size meta) that `foldLeft`'s own stated transfer calls. One meta-language
leaf, not a channel special case.

All of which still waits on the size domain: until `List` carries a size, `foldLeft`'s transfer has no `n`
to iterate over, the reduction stalls, and the result is absence — soundly, and with no `unknown` form
needed even in the interim (a stalled transfer already drops to absence today).

The real lesson from the table: **the load-bearing problem is higher-order transfers, not ⊤.** Which is §6.

---

## 6. The architecture decision: twin program, or meta interpretation

This is the fork that decides whether the result is simpler than today or merely bigger.

### 6.1 Option A — emit a shadow twin

Derive, in `core` alongside the existing meta desugarers, a `f$Meta` **Eliot** twin of every value:
literals become `integerLiteral$Meta(n)`, calls become `f$Meta(args$Meta)`, lambdas become lambdas over
metas, rows are stripped. The twin is compiled by the ordinary pipeline and reduced by the one NbE
evaluator; the channel's `metaViaCompanion` stays the only recognition point, and simply always hits.

Attractive: pure name transform, no lookup, reuses every front-end phase (operator resolution, `match`
desugaring) for free.

Two problems, one fatal:

- It roughly **doubles the compiler pool**, against a TODO list that already carries three "compilation is
  slow" entries. Mitigable (a static "return meta is `Unit` ⇒ never force the twin" fast path, plus
  demand-driven facts), but not free.
- **There is no node-level correspondence between the twin and the runtime body.** The twin is derived from
  source; the channel walks monomorphized bodies. At a call with a lambda argument, the channel holds a
  `MonomorphicExpression` lambda node and cannot synthesise that lambda's twin from it — so every
  higher-order transfer in the §5 table degrades to unknown, and the channel keeps its whole existing case
  analysis *plus* a second program. That is bigger, not simpler.

### 6.2 Option B — interpret the body under a meta interpretation (recommended)

Do not emit a second program. Extend the channel's existing walk so that a node's meta is computed by
walking the *actual* body with:

- parameters bound to the caller's argument metas,
- **leaves substituted by their stated transfer** (reduced through NbE, exactly as today),
- non-leaves recursively interpreted, memoized on `(vfqn, typeArgs, argMetas)`.

Node correspondence is then free — you are walking the very body whose layout the representation pass
stamps. Higher-order falls out: a lambda node's meta is a *closure over metas*, built during the same
walk and applied when `apply`/`handleCases` calls it. That single property is what unblocks
every row of the §5 table, including the fold — iterating `combine`'s transfer requires reaching a lambda
argument's transfer, which is precisely what A cannot do.

**Decided: B.**

Costs, honestly: it is per-call-site rather than a compiled summary (memoization is doing real work), and
the interpretation is a Scala walk rather than emitted Eliot. The latter is *not* a "parallel generator"
violation — the channel already owns this walk; this extends it rather than adding a second mechanism. And
the stated transfers themselves stay Eliot, reduced by the one evaluator, so the single-evaluator
cornerstone is intact.

Termination is still the recursion gate's (§2), now applied directly rather than through a derived
program.

**Recommendation: B.** A is the more elegant transform in isolation; B is the smaller *system*, and it is
the only one of the two that makes the channel shrink.

> **Landed as B (S8), and it is `monomorphize/channel/MetaInterpreter`.** The walk is exactly the three bullets above:
> parameters bound to the caller's argument metas, leaves substituted by their stated transfer (reduced through the one
> NbE evaluator, as before), non-leaves recursively interpreted. Two things about *where* it sits are worth recording,
> because both were decided in the doing:
>
> - **It is a separate object from the channel's walk, and that split is §7's, made early.** The interpretation answers
>   *what the meta is*; `RefinementChannelProcessor` keeps *what may be stamped*. That fell out of a requirement rather
>   than a preference: a record is keyed by source position **in the value being walked**, and a `where` demand is
>   raised at the position it is written, so interpreting a *callee's* body inside the caller's walk must record
>   nothing and demand nothing — otherwise a callee's node positions would land in its caller's table and its
>   preconditions would be re-reported per call site. So the interpretation raises no diagnostic at all, which is what
>   makes it safe to run over another value's body.
> - **`state or derive` is one `ifM` on the test R2/R3 already enforce** (`MetaTransferAccountingProcessor`, the
>   companion lookup). The invariant of §2 is therefore executed and enforced by the same two facts, and cannot drift
>   into two opinions about which values summarise and which are read. §7's predicted deletions did not all arrive with
>   it, though, and the honest accounting is: the membership test *stays* (it is now the state-or-derive split rather
>   than a "does anyone happen to have written a brace" test), and so does the α literal arm (a seed is a stated
>   transfer whose argument is a raw number the walk supplies, not a node it can interpret). What did go is the notion
>   that a call's precision depends on where the callee sits relative to a native.

---

## 7. Why this ends up simpler than today

> **Amended by S8: the separation happened, the deletions mostly did not.** The interpretation is its own object and
> raises no diagnostic, so "what the meta is" and "what may be stamped" are now answered in two places — that half is
> done, and it was forced rather than chosen (a callee's node positions may not land in its caller's table). The
> deletion list below is the part that did not come true: the membership test stays (as the state-or-derive split), the
> α arm stays (a seed's argument is a raw number, not a node), and `MetaTransferDesugarer`'s two-mode signature rule is
> untouched. What *is* gone is the "no companion ⇒ ⊤" branch's meaning — precision no longer depends on whether someone
> happened to write a brace. **What remains of the separation is the second half**, the one the last paragraph of this
> section warns about: the walk still drops every record inside a lambda, so a *parametered* def's own table is still
> empty (`docs/string-length-meta.md` §9), and lifting that is a representation decision, not a meta one.

Under B, these disappear:

- The `UnifiedModuleNames` **membership test** for a companion (`metaViaCompanion`, `hasWhereCompanion`'s
  sibling) — with totality there is nothing to test.
- The **"no companion ⇒ ⊤" branch** and with it the notion that a call site's precision depends on whether
  someone happened to write a brace.
- `MetaTransferDesugarer`'s **two-mode signature rule** (a monomorphic vessel suffixes `Int` ⤳ `Int$Meta`; a
  generic one keeps its signature verbatim and is reduced at meta type arguments). One convention replaces
  it, and the `T$Meta` name-suffix hack survives only where it belongs — as the *meta structure's own*
  generated name.
- The **α special case**: an integer literal stops being a distinct arm in `walkFlow` (it is a call to
  `integerLiteral`, whose transfer is stated like any other leaf's).

And one entanglement gets untied, which is the biggest structural win:

> Today one walk decides both *what the meta is* and *what layout is permitted*. They are different
> questions with different answers.

`walkFlow` currently skips lambda interiors and treats parameters as ⊤ — but those are **representation**
policy (the shipped boundary rule: narrow ints widen back to ⊤ at every call/ctor/typeMatch/function-value
edge, because a narrow heap box fails a reader's `CHECKCAST`), enforced inside the *meta* computation
because there is only one walk. Under B they separate: the interpretation computes metas everywhere,
including through lambdas and parameters; the representation side keeps its conservative policy about what
may be *stamped*. Fewer rules, each in one place, and the `CHECKCAST` hazard stops being something the
meta logic has to know about.

**This separation is also the thing most likely to be got wrong.** Letting a call-site parameter meta
narrow a callee's interior means either call-site-sensitive layout or splitting monomorphization on metas
— and the latter is explicitly forbidden by the shipped invariant *"refinements are invisible to monomorph
instantiation identity"*. The interpretation's output is consumed at the **call node in the caller**;
inside the callee, layout stays joined/conservative. Get that wrong and it arrives as a
`ClassCastException`, not a type error.

---

## 8. Remaining difficulties

**8.1 Ability-impl transfers collide — LANDED (S7), and it was P4's first step.** `metaCompanionFqn` kept only
`callee.name.name`, which *stripped* the `AbilityImplementation` qualifier — so `Numeric[Int]::add`'s transfer was a
plain `add^Meta` in `eliot.lang.Int`. Safe only because no other `add` is declared there; under totality it collides,
and it collided in **two readers at once**: the channel would apply an instance's transfer to a same-named sibling's
call sites (silently wrong metas), and R3 would report that sibling for a brace it does not carry (a spurious error).
A companion now keeps its callee's qualifier, so its identity is the callee's identity —
`add^Meta(Numeric#Int[L1, H1], Int[L2, H2])`. Dispatch itself was already solved — the channel sees the resolved
*instance* FQN post-mono.

**Amended in the doing: the qualifier is *nested*, not reused.** This section proposed naming the transfer in the
callee's **own** qualifier namespace (`add$Meta` beside `add`, mirroring `$Where`), which would also have retired
`Qualifier.Meta`. The tree refuses that, from two phases away: an implementation's namespace is not free real estate —
`AbilityImplementationCheckProcessor.checkNoExtras` reports every member of it the ability does not declare, so
`Numeric[Int]`'s brace would compile to "Method not defined in ability." The qualifier is therefore **wrapped** rather
than replaced: `Qualifier.Meta` becomes `Meta(of: Qualifier)`, the meta namespace *of* a namespace. That says the same
thing about identity — a companion shadows exactly one value, and a value is `(name, qualifier)` — while leaving the
ability machinery's view of an implementation exactly as it was, and it is total by construction: no future qualifier
kind can collide, whereas a name suffix has to argue about `$` each time.

`Qualifier.Meta` consequently **stays**, and that is the better outcome for the readers that ask *"is this a
compiler-pool-only channel artifact?"* — `CompilerNativesProcessor.isRefinementArtifact`, `RowChecker.checkable`, and
`CoreProcessor`'s compiler-track literal reading. They keep asking a namespace rather than sniffing a name suffix,
which for the transfer companion would have been the very `$Meta` marker a *type*'s meta structure already carries.

The emitter now owns the name — `MetaTransferDesugarer.companionName` / `MetaWhereDesugarer.companionName` — and every
reader (`RefinementChannelProcessor`, `MetaTransferAccountingProcessor`, `CompilerMonomorphicTypeCheckProcessor`) names
it through them, so the four hand-spelled copies that had to agree can no longer drift.

Verified by the byte-identity oracle: all 40 compiling examples produce class content identical to the pre-change
build, as a pure renaming of compiler-pool-only values must — the tree has no collision to repair, which is why this
had to land *before* the interpretation multiplies the companions that would collide. The regression it does fix is
pinned by test rather than by the tree: a module declaring both `implement Sum[Gauge] { def combine(…) {…} }` and a
bodied `def combine` is now accepted, and was reported for a brace it does not carry before.

**8.2 Braces do not merge.** A brace desugars to a companion *with a body*, i.e. a concrete value; if two
layers carried the brace, the merge rejects it with "Has multiple implementations". So R2 must say where
the brace lives — with the **native**, i.e. in the layer that owns the leaf — and a brace must not be part
of `signatureEquality` (the merge is lexical; requiring character-exact repetition across layers would be
a new failure mode for no gain). A body-supplying layer may not carry one: a special case of R3 — and it needed
no special code, because R3 reads the body *after* the layer merge (`MonomorphicValue.runtime`), so "the brace in
one layer, the body in another" and "both in one declaration" are literally the same check.

**8.3 `foldLeft` narrows nothing until the size domain lands.** `def size[A](list: List[A]): Int =
list.foldLeft(0, _ -> count -> count + 1)` is the first thing anyone will try; its transfer stalls for want
of an `n` (§5.2) and yields absence. Correct, but say so in the user-facing docs or the first report will
be "you said meta was total".

**8.4 The interpretation needs a budget on two axes — the second one LANDED (S8).** Fold iteration (§5.2) is one, and
is not reachable yet (nothing iterates until `List` carries a size). The other is depth/fan-out: the interpretation
recurses into callee bodies, and a diamond-shaped call DAG re-interpreted per path is exponential even though each path
is acyclic. What shipped for it is a **single step count threaded through depth and fan-out together**
(`MetaInterpreter.interpretationBudget`), not the memoization this entry proposed as the handler: a hard count bounds
the diamond absolutely, whereas a cache is defeated by exactly the distinct argument metas this entry already names.
Exhaustion yields absence, as required. Both budgets still have to fail that same way when the fold's arrives.

**8.5 Memoization key growth — deferred, and nothing pays it yet (S8).** The cache key would be
`(vfqn, typeArgs, argMetas)`, and metas are arbitrary domain values, so distinct call sites with distinct literal ranges
are distinct entries — bounded by call-site count, but interacting with the existing cache-serialization complaints. S8
shipped no memo at all and measured no cost to defer it: the 43-example sweep is 4m18s with the interpretation against
4m20s without, because the *type* gate (a call whose result carries no meta is skipped before its body is fetched)
keeps the walk off almost every call node. Add the memo when a program is found for which the budget, not the clock,
is what limits precision — and measure with `--statistics`, diffing a run with and without the flag (the flag itself
inflates ~20%).

**8.6 The `Meta`/metavariable name collision.** `SemValue.VMeta`, `MetaStore`, `TypeStackLoop.returnMeta`
are **unification metavariables**, nothing to do with refinement meta. New code in `monomorphize/` will
read ambiguously. "Meta" is the user-facing word for the refinement side (`ability Meta[D]`), so the
metavariable side is the better rename target — worth doing before this lands more of the other.

**8.7 A brace could not be stated on a def with an untracked parameter — LANDED (S5).** The `^Meta` companion types
its parameters by the pure name transform `T` ⤳ `T$Meta`, and `T$Meta` is generated only for a **slotted** type — a
slotless type's meta is the trivial `Unit`, which the channel supplies rather than a structure. So the transform was
total only by accident: every brace in the tree until S5 had exclusively `Int`/`String` parameters. The first leaf
whose brace stood over an untracked parameter — `outcomeExitCode(outcome: ProcessOutcome)`, and with it every
`Path`/`IoResult`/`ProcessOutcome` accessor in §P2's list — failed with "Name not defined." at its own parameter
type. The fix is the rule the *generic* branch of the same desugarer already relied on, made explicit and applied
per-parameter: a parameter the brace never mentions is dead in the companion, so it keeps its own type
(`core/processor/MetaCompanionReferences`). A parameter the brace *does* mention is retyped as before, so projecting
an untracked parameter still fails loudly. `MetaWhereDesugarer` had the identical defect for `where` predicates — a
`where` over an `Int` parameter failed if any *other* parameter of the def was untracked — and took the same rule,
until §8.8 replaced the name transform, and this rule with it.

**8.8 A companion's meta types and meta constructor are read from evaluated types, not named — LANDED (2026-10-19).**
§8.7 fixed the symptom, not the cause: `T` ⤳ `T$Meta` reads the *spelling* of a type, and a type is an expression.
Three shapes failed for a `where`: a parameter typed by an alias (`type Byte = Int`, "Name not defined." at
`Byte$Meta`), or by any other type that is not a bare slotted name (an alias of an alias, a computed type, a type
parameter); a predicate naming the def's own type parameters (`rangeWithin[Lo, Hi](range(x))`, since the companion had
no binders and was reduced at none); and every call to a def with a parameter whose type carries no meta ("an
argument's meta-information is not known", because that argument's meta was ⊤ rather than the known `Unit`).

The fix delegates to what already exists. The `^Where` companion is generic: it takes the def's binders as the effect
desugar leaves them (handles and handed binders included, so they line up with the callee's monomorphization key by
construction) and then one binder per parameter, typing that parameter —

```eliot
def asByte(x: Byte): Byte where rangeWithin[0, 255](range(x)) = x
  ⟶  asByte$Where[Meta$x: Type](x: Meta$x): Bool = rangeWithin[0, 255](range(x))
```

— and at each full call the channel monomorphizes it at the callee's own type arguments followed by
`MetaInterpreter.metaTypeOf` of each parameter's type, read from the callee's `MonomorphicValue` signature. The
monomorphizer has already evaluated that type (`Byte` is `Int` there), `metaTypeOf` is the one rule mapping a type to
its meta type (`T$Meta` if slotted, else `Unit`), and the companion's body is checked by the one checker at those
types, as every generic body is. Nothing is added to the evaluator or the checker; a `metaOf(T)` the checker evaluates
was the alternative, and it was rejected for the reasons `64f5f1ee` deleted the earlier `metaOf` intrinsic: a native is a
pure function and cannot ask whether a type declares a meta, and a per-type generated function collides across layers.

A parameter whose meta type is `Unit` counts as **known** (there is nothing to know) and is passed as an opaque
placeholder; a predicate that reads it anyway fails to check in the companion and is reported. A *tracked* argument
whose meta is ⊤ still cannot discharge a demand.

*The transfer companion takes the same shape*, plus one binder for the result, so `fold^Meta[A, Meta$condition,
Meta$whenTrue, Meta$whenFalse, Meta$]` binds `A` to what it is (the old reduction at the *meta* type arguments bound
`A` to `Int$Meta`, a pun `integerLiteral`'s `V` survived only because `metaTypeOf` passed non-types through). That
deleted `MetaCompanionReferences` and §8.7's rule with it. Both companions are now built by `MetaCompanionLayout`.

*Where the types come from: the payload signature.* The callee's monomorphic signature was the first source, and it is
wrong for a **code parameter**: the callee receives `whenTrue` as a thunk `Unit => A`, while a companion reads it as
the value its code yields (`A` — the payload rule `EffectSugarDesugarer` already applies in the meta namespace), so
`fold`'s `join(whenTrue, whenFalse)` failed to check over `Unit`. Peeling the thunk in the channel would have meant
reading the declaration's effect row and re-deriving how the effect desugar shifted parameter indices. Instead each def
with a companion gets one more generated value, `<name>$Payload[<its binders>]: Type = Function[P1, Function[…, R]]`,
the parameter and result types *as a companion reads them*; the channel reduces it at the callee's type arguments
(the one evaluator, so an alias is what it denotes) and peels as many arrows as the companion takes parameters — that
count is the companion's own leading lambdas, because a signature's arrow chain cannot tell a parameter from a
function-typed result. Counting the companion rather than the callee's body also closes a fail-open: `naturalArity`
is `None` for a body-less callee, so a `where` on a native leaf would never have been demanded (none exists in the
tree).

*One consequence for `where`.* A code parameter is now read at its payload's meta type, and a code argument reaches
the channel as a thunk, whose meta is never known — so a `where` on a def with a code parameter yielding a tracked
type (`body uses *: Int`) cannot be proven under "every argument's meta must be known", even when the predicate never
reads it. (Before this change it could not be either; the brief interim that typed code parameters as function values
admitted it by accident.) Demanding only what the predicate reads is the obvious refinement — a well-typed predicate
can observe a meta only through its type's eliminators, which stall on the placeholder — but it narrows a decided
fail-safe and is left for a decision.

*The meta constructor is passed, not named (2026-10-19).* A slot-producing brace lists slots, and its body used to apply
the constructor named by suffixing the result type as written (`Int$Meta(…)`), so a leaf whose result type is an alias
failed to resolve it — the same spelling read, one name later. Now the companion takes the constructor as its first
parameter, `constructor$`, typed by a binder of its own (`Constructor$`, after the result's), and the channel passes
the value constructor of the result's evaluated meta type at that constructor's own signature
(`MetaInterpreter.metaConstructorOf`):

```eliot
def add(a: Int, b: Int): Int {a.range + b.range}
  ⟶  add^Meta[Meta$a, Meta$b, Meta$, Constructor$](constructor$: Constructor$, a: Meta$a, b: Meta$b): Meta$ =
         constructor$(a.range + b.range)
```

A structure-producing brace (a bare type-parameter result, `fold`'s) takes none. The channel tells the two apart by the
companion's own leading parameter, as it counts the companion's parameters, and a companion's arity counts the
callee's parameters only. Nothing is named from spelling any more, and every layer brace's reduction is unchanged:
all example jars are byte-identical.

*R2 read the same spelling, and it was a fail-open.* Whether a leaf's result carries meta was decided by the declared
return head's name, so `def readGauge: Reading` over `type Reading = Gauge` was exempt from stating a transfer. R2 now
reads the **evaluated** result type — the instance's signature past as many arrows as the declaration has parameters —
and a type-parameter head is still exempt, being forwarded rather than originated. Read the same way, a leaf whose
brace lists slots over a result that carries no meta (a `String` stub, a `Bool`) is reported at the leaf: there is no
constructor to apply them to, where before it failed to resolve `Bool$Meta`.

---

## 9. Staging

**P1 — the leaf predicate.** "Does this unified value have an Eliot body anywhere on the path?", computed
after the layer merge, excluding ability method declarations (§2.2). Nothing consumes it yet; publish the
list and eyeball it.

**P1/P2 mechanism — LANDED, and ARMED (S5).** The leaf predicate turned out to be exactly the body test the
mono fact already carries — `MonomorphicValue.runtime.isEmpty` (no need for the two-track/`NativeBinding`
detection §2/§3 sketch). The R2 check rides each `MonomorphicValue` as
`monomorphize/channel/MetaTransferAccountingProcessor` (on the `SuppliedRowArgumentsProcessor` template): a
body-less value whose **declared** return head is a concrete meta-carrying type and which declares no `^Meta`
companion is reported at the value. A **type-parameter return head** (`foldLeftInternal : F[B]`)
is exempt — the meta is forwarded, not originated, so it is the §6 higher-order case, not this one. Proven
against the real stdlib: armed, it fired on exactly `String::length`, `indexOfInternal`, `parseIntInternal`,
and `Process::exitCode`/jvm `outcomeExitCode`; folds, type-parameter returns, bodied values, and the
brace-carrying arithmetic leaves all pass. It is now **demanded**: a `getFactOrAbort(MetaTransferAccounting.Key(…))` beside the
effect-accounting precondition in `WovenValueProcessor`, so a leaf that states nothing blocks its own `WovenValue`
and with it codegen — the same shape, and the same reasoning, as an undeclared effect. `String::length` left that
firing list by being *answered* (S3) rather than exempted, and S5 answered the rest.

**P2 prerequisite — the domain top — LANDED.** The range domain is `Interval[BigInteger]`, topped by `whole` (§5), so
every leaf below now has a transfer it can honestly state. This was P2's one blocker.

**P2 prerequisite — no global top — LANDED.** The generic `Meta[Bound[D]]` top is deleted (§5, third amendment):
tops are domain-owned, the machinery has none, and the only ⊤ left is the channel's own untotality — which is
what arming removes rather than something to state around.

**P2 prerequisite — the result-edge re-encode (S3) — LANDED, and it was the real gate.**
`docs/string-length-meta.md` §8: stating a transfer on a leaf emitted as a *generated native static method* pinned a
narrow meta on the call node while the method's return descriptor stayed the ⊤ bignum, and the class verifier
rejected it (`VerifyError: 'java/math/BigInteger' is not assignable to 'java/lang/Byte'`). It had never fired
because no leaf stated one; `String::length` — the first entry in the table below — fired it immediately. So S3
(`ExpressionCodeGenerator.convertResultFromBoundary`, the mirror of the argument edge's widening) landed *with* that
first stated transfer, as this entry required, and `String::length` is off the list below. The arithmetic leaves were
invisible to it and remain so: they are inline intrinsics, which already emit at the node's own width — the one shape
the re-encode skips.

**What that settles beyond one leaf.** The re-encode is a function of the node's meta against the one ⊤ boundary
width, not of which callee is being invoked, so it applies to every non-intrinsic application at once — the
over-applied spine, the `Function.apply` bridge and the two `match` calls included. Every leaf P2 still owes a
transfer therefore inherits a backend that already carries it, and this class of `VerifyError` cannot recur per-leaf.
Verified inert where nothing is stated: with the backend change and no brace, all 40 compiling examples were
byte-identical to the pre-change build.

**P2 — arm it + the missing statements — DONE (S5).** The precondition is wired and every leaf the armed check
demanded now states. §2.1's rule decided each one's layer — a bound that is a property of the *operation* lives in
the base, a bound that is a property of the *representation* lives in the platform layer (the brace desugars to a
separate `^Meta` companion, so a jvm-layer brace over a base-abstract declaration merges cleanly with no
`signatureEquality` change):

| leaf | stated transfer |
|---|---|
| ~~`String::length`~~ | **stated (S3)**: `{size(s)}` — the string's own size, exact, and better than the `[0, 2³¹−1]` this table first proposed. It is also the one row that belongs in the **base** rather than a platform layer, because since the unit is the code point the identity holds on every target — there is no platform datum in it |
| ~~`String::indexOfInternal`~~ | **stated (S4)**: `{interval(Bounded(0 - 1), end(size(s)))}` — ~~`Bounded([-1, 2³¹−1])`~~. The same correction `length` got: the ceiling is the argument's own size rather than a platform maximum, so this too belongs in the base. `-1` is still the not-found answer, spelled as a subtraction because the language has no negative-literal syntax at all |
| ~~`String::parseIntInternal`~~ | **stated (S4)**: `{whole}` — full-precision `BigInteger`, whose honest bound is exponential in its argument's size (§5), so the top is what it states and the exponent never runs |
| ~~`Show[Int]::show`~~ (base) | **stated (S5)**: `{atLeast(1)}` — every integer renders as at least one code point (`0` is `"0"`), which holds on every target, so the brace belongs in the base. The *ceiling* is the digit count of the argument's range and stays unwritten: it needs a base-10 logarithm no arithmetic leaf offers (decision 8). The half-open interval is what lets this state the half it knows — the third leaf to answer with a partial bound rather than the top |
| ~~`Show[Path]::show`~~ (base) | **stated (S5)**: `{atLeast(0)}` — a path renders as some number of code points, floor `0`; the ceiling is the platform's own path limit, which the base may not name |
| ~~jvm `readLineInternal`, `environmentVariableInternal`, `fileNameInternal`, `extensionInternal`, `resultErrorMessage`, `outcomeErrorMessage`, `outcomeStandardOutput`, `outcomeStandardError`~~ | **stated (S5)**: `{closed(0, 2147483647)}` — the JVM's own `String` ceiling (a `java.lang.String` holds at most that many UTF-16 units, so at most that many code points). This is what a representation transfer looks like, and it is exactly the row §2.1 predicted would be platform data: an MCU target with a smaller line buffer states its own |
| ~~jvm `outcomeExitCode`~~ | **stated (S5)**: `{closed(0 - 2147483648, 2147483647)}` — **not** the `Bounded([0, 255])` this table proposed. POSIX truncates a status to a byte; Windows hands back an arbitrary `int` (`0xC0000005`), and `java.lang.Process.exitValue()` returns that `int` unchanged. A leaf's statement is *axiomatic* — nothing rechecks it — so a bound that is merely usually right silently poisons every `where` downstream of it. The wide statement is the honest one, and it still bounds the width, which is what a fixed-width `where` needs |
| `Process::exitCode`, `Process::standardOutput`/`standardError`, `File::message` | **not leaves** — the jvm layer's `data ProcessResult(exitCode: Int, …)` / `data IoError(message: String)` make these generated field accessors, i.e. *bodied*, so they derive rather than state. The earlier proof run listed `Process::exitCode` because it predated those concrete `data` declarations |

Plus the generic leaves of §5, which are the higher-order rows and wait on P4. **This is what closed the
original TODO.**

Sequencing note (`docs/string-length-meta.md` §7): declaring `String` meta-carrying pulled every body-less
`String`-returning leaf into this list — roughly a dozen more. Landing the `String` domain *before* arming meant
stating them once instead of twice. **Done, through S4+S5**: nine in `String.els`, then the two base `show`s and
the nine jvm natives above.

**What the armed check actually costs a reader, measured.** R2 is a *use-site* check riding each
`MonomorphicValue`, so it only fires on leaves the program instantiates. Across all 43 examples it fires nowhere,
and a probe program exercising the file/process/environment/console/`show` surface fires nowhere either. That is
also its one sharp edge: arming with only the leaves today's examples reach would have left the rest to break for
the first user who opened a file, which is why S5 stated every leaf in the tree rather than every leaf that
happened to fire.

**Three things S4 and S5 established that arming depended on.**

- **A brace can name a number, once the brace is read as what it is.** An interval endpoint is a `BigInteger` and a
  value-position integer literal is an `Int`, with no widening between them by cornerstone — so `Bounded(0)` in a
  brace *was* a type error, and every transfer with a floor of `0` was unwritable. S4 detoured through a
  type-position-literal helper (`boundedAt[V]`); that helper has since been **deleted** in favour of fixing the
  premise. A brace's `^Meta` companion — and a `where`'s `^Where` companion — is compiler-pool-only code, so its
  literals are compile-time `BigInteger`s exactly as a signature's already were. Anyone stating the remaining leaves
  writes `Bounded(0)`, and the workaround's two edges are gone with it: a negative endpoint is an ordinary
  subtraction (`Bounded(0 - 1)`), and argument order no longer decides a call's type parameters. The residual
  friction is narrower and still open in the TODO: an *ordinary def body* remains runtime-track, so a helper called
  from a brace cannot name a `BigInteger` constant in value position (hence `rangeWithin[Lo, Hi]`'s type params).
- **A leaf whose bound is exponential states the top rather than the bound.** §5 predicted exactly one such leaf and
  it stayed one; `whole` is a *stated* top, which is what keeps R4 intact, and the `power` native of decision 8 is
  still a precision follow-on rather than a prerequisite. The lesson generalises to arming: R2 demands a *statement*,
  never a *tight* statement, and a leaf that cannot afford its honest bound has a legal thing to say.

- **A brace could not be stated at all on a def with an untracked parameter** (§8.7) — the one piece of *machinery*
  arming needed, and the only surprise of S5. Every brace in the tree until then had exclusively `Int`/`String`
  parameters, which hid that the companion desugarer retypes *every* parameter to a `T$Meta` that exists only for a
  slotted type. The nine holder/input natives all have an untracked parameter (`ProcessOutcome`, `IoResult`,
  `Path`), so eight of the eleven S5 statements were unwritable until it was fixed. The general lesson is the one
  §2.1 keeps restating from a different angle: a rule that has only ever been exercised on the *tracked* types is
  not yet known to be total.

**What arming settled.** The channel no longer has a ⊤ that means two things. Before S5, an absent meta could be
either "this value is bodied and the interpretation that would derive it does not exist yet" (P4's job) or "a native
silently defaulted"; only the first survives, and it is now the *only* thing P4 has to answer. That is the property
this document set out to buy, and it is bought at the price §P2 anticipated: eleven statements and one desugarer fix.

**P3 — R3 enforcement — DONE (S6).** Error on a brace over a bodied value: the second arm of
`MetaTransferAccountingProcessor`, reported at the value and blocking codegen through the precondition R2 already
installed. R2 says a leaf must state; R3 says nothing else may. The implementation is the cheap half this entry
predicted — one arm, one message, no new fact and no new lookup — and both rules now key off the value's
pre-monomorphization `OperatorResolvedValue`, since both judge a *declaration*: an instance with none (a fact a
test harness injected, a value synthesized past the front end) declares nothing and so has nothing to state or
over-state.

**What it was not is a no-op, and the exception is the finding.** The prediction above was made about the
*layers*, and there it held exactly: every brace in `stdlib/` and `jvm/` sits on a body-less leaf, all 43 examples
are unaffected, and the byte-identity oracle shows identical class content. But the tree also contained one
suite — the former `InlineTransferBraceIntegrationTest` — whose every program stated a brace on an ordinary
**bodied** test def, because that was the only way to author a brace end-to-end at all: a user module declares no
natives, so a body-less user def dies at `JvmClassGenerator`'s "Function not implemented." before the channel is
ever reached. R3 outlaws that shape, so the suite was rewritten as `TransferBraceReductionIntegrationTest`,
exercising each shape through the layer brace that actually ships it: the ability call inside a brace through
`Numeric[Int]::add`, the generic constructor helper through `Runtime::integerLiteral`'s `closed(V, V)`, the
negative endpoint through `subtract`, the half-open endpoint through `Show[Int]::show`'s `atLeast(1)`, and the
dotted subject-last form through a `where` predicate (the one user-authored meta companion R3 leaves alone — a
precondition is a stated contract, never derived). A last case pins R3 itself.

**Nothing was lost by that move, and something was gained.** The old suite carried its own caveat: lowering a
brace-narrowed return across a *user* def's call boundary is a deferred backend representation matter
(`docs/refinement-channel-follow-ups.md` §4), because the callee's body is compiled once with ⊤ parameters and so
returns a wide representation the narrowed caller does not match. That caveat *was* the shape R3 removes — the
`CHECKCAST`-class hazard §7 warns arrives as a `ClassCastException` rather than a type error — so arming R3 turned
a known-broken, layer-unreachable capability into a compile error with a fix named. The general lesson for the
remaining stages: **"no-op on the tree" must be checked against the tests as well as the layers**, since a test
suite is exactly where a shape no shipped artefact uses will still be alive.

**One hazard R3 inherited, and it is now repaired (S7).** `declaresTransfer` finds a companion by the same lookup
`RefinementChannelProcessor.metaViaCompanion` uses, so R3 flags exactly the situation in which the channel would read
a companion for a bodied value — and where that lookup was wrong (an ability-impl leaf's brace colliding with a bodied
sibling of the same bare name in the same module) R3 was wrong the same way, reporting a brace the value does not
carry. §8.1's naming fix repaired both readers at once, as predicted, because there is one name and both ask for it.
Both readers now name it through the desugarer that emits it, so a future divergence would have to be written twice.

**P4 — the interpretation (§6.2).** The naming fix of 8.1 — **done (S7)**. **The walk — done (S8)**; §7's separation is
what remains.

`monomorphize/channel/MetaInterpreter` is the whole of it, and the channel's walk shrank to calling it: an ordinary
call, and a bare reference (a nullary def is a saturated call spelled without parentheses), now ask *"what is this
callee's meta?"* instead of *"did someone write a brace on it?"*. What the walk gained is what §2's induction
promises — `def double(x: Int): Int = add(x, x)` called at `60` is `[120, 120]`, `def label(s: String): String = s` at
`"abcd"` is a size of `[4, 4]`, and both chain: `quadruple(30)` interprets `double` twice. A pure `val` block is
interpreted too, since it lowers to an applied lambda and its bindings are ordinary parameter bindings.

Four decisions this stage made:

- **First-order.** A lambda is a function value and carries no meta of its own, so a callee that applies one of its own
  parameters is ⊤ — §5's higher-order rows are untouched, and the fold additionally waits on `List` size (§8.3, still
  exactly as written). What §6.2 called the payoff of B — *reaching a lambda argument's transfer* — is therefore
  still owed; what B bought here is that the walk is over the real body, so paying it later is a closure over metas in
  a walk that already exists rather than a second program.
- **The budget is a step count, and there is no memo.** §8.4 asks for two budgets and §6.2/§8.5 for memoization on
  `(vfqn, typeArgs, argMetas)`. What landed is one hard step count threaded through depth *and* fan-out together
  (`interpretationBudget`, 256 nodes per call site), which is the stronger guarantee of the two: it bounds the
  exponential diamond §8.4 warns about absolutely, rather than relying on a cache key that distinct argument metas
  defeat. Exhaustion yields absence, never a wrong answer. Memoization is a *precision* follow-on, to be added if the
  budget is ever observed to bind — and §8.5's cache-key growth is a cost nothing pays yet.
- **The gate is the node's own type, and it is what makes this affordable.** Before any body is fetched, the
  interpretation asks whether the call's result type carries meta at all (§2.3: non-structural, so `List[Int]`,
  `Unit`, `IO[…]` and every function type answer no). That skips the overwhelming majority of call nodes at one cached
  `UnifiedModuleNames` lookup. Measured: the 43-example sweep takes 4m18s with the interpretation and 4m20s without —
  no regression to report, which is the outcome that would *not* have held without the gate.
- **A `where` is still demanded at the definition, over ⊤ parameters.** The interpretation computes metas; it does not
  demand preconditions. So `def relay(y: Int): Int = useByte(y)` is still rejected at `relay`, even where the use site
  would satisfy it — re-demanding a callee's `where` with the caller's argument metas is P5, and it is now possible for
  the first time.

**What it costs the emitted code, honestly.** 35 of the 40 compiling examples are byte-identical; the five that moved
(`Arithmetic`, `ArithmeticAbility`, `IntReturn`, `Ranges`, `WherePrecondition`) all *grew*, and each still prints its
documented output. The growth is the result-edge re-encode (S3) firing where it never had to before: a derived call
node now has a narrow meta, so the bignum the boundary hands back is narrowed to it — and every consumer today is a ⊤
position, which widens it straight back. `Ranges`'s `count() + count() + count()` is the clearest case: three
`BigInteger` ⤳ `Byte` ⤳ `BigInteger` round trips that compute the same `21`. That is not a soundness matter and not
new in kind (a literal argument has round-tripped through its own stamped width since the channel shipped), but it is
the first change to make it common. The fix is a backend peephole — a narrowing whose only consumer immediately widens
need not be emitted — which is worth doing *before* the derived metas start being spent on anything, and which would
shrink today's literal path too.

**P4 follow-on — the round-trip peephole — DONE (S9), and it repaid more than S8 borrowed.** The paragraph above named
the fix and this landed it, in the one place it belongs: the backend. The rule is a sentence — **a value is emitted at
the width its consumer will read, and a consumer that adapts to any width reads it as it arrives** — and it replaces
the assumption underneath the growth, which was that a width the channel *stamps* on a node is a width the backend must
*materialise* there. It is not. The stamp is information; where it costs an instruction to realise and nothing to skip,
it is skipped.

Three edges, which are all of them:

- **A boundary result meeting a ⊤ consumer** — a call argument, a constructor argument, an `apply` bridge argument, a
  method return. A call boundary already hands its value back at the ⊤ width, so the re-encode down and the widening
  back are one round trip; both halves go (`ExpressionCodeGenerator.createExpressionCodeAtBoundaryWidth`, threaded as
  the consumer's demand rather than as a property of the expression). This is `Ranges`'s three trips.
- **An intrinsic's operand** — `show`, the two comparisons, the arithmetic leaves. These *adapt*: `unboxToLong` and
  `pushAsBigInteger` each take any integer width, so an operand arriving from a boundary is read as it arrives
  (`createExpressionCodeUnconverted`, and `unconvertedRepOf` names what that leaves). The `fold` merge takes the same
  rule for its arms.
- **A constant**, which is materialised at whatever width is asked for, so it is pushed at the consumer's width
  directly. This is the *oldest* of the three and predates the interpretation entirely — an argument literal has been
  boxed narrow and widened again since the channel shipped.

**The one thing that must not move with it**, and the trap this stage had to see: a compute-domain decision reads the
**node** meta, never the arriving width. `viaBigInteger` asks whether an operand's *range* fits a primitive `long`;
letting it read the arriving width instead would flip every boundary operand to `BigInteger` arithmetic, and the
peephole would pessimize exactly what it set out to shrink. So the two questions that shared one variable are now
`nodeRepOf` (what the channel knows) and `unconvertedRepOf` (how the value arrives), and only the second decides an
unbox.

**Measured, against S8 *and* against the build before it.** All 40 compiling examples print identical output and the
whole suite is green. 27 of the 40 are byte-identical to S8's build; the 13 that moved *all* emit **fewer
instructions** — 5919 against 6091 across them. Comparing to the pre-S8 build settles the debt rather than merely
reducing it: each of S8's five is now *below* where it started, `Ranges` at 146 against 185 after S8 and 150 before it,
`ArithmeticAbility` at 172 against 208 and 185. The other eight moved for the constant edge alone, which S8 never
touched.

Four classes grew a handful of *constant-pool* bytes while their code shrank, which is the honest reading of the one
runtime change here: `pushIntegerConstant` now goes through `BigInteger.valueOf` for a constant that fits a `long`
instead of parsing a decimal string, so a `long` pool entry replaces a `String` one. That path was not exotic — it is
what every literal inside a *parametered* def took, since §7's record policy makes its meta ⊤ there.

**What it leaves.** `convertResultFromBoundary` stays, and is now the *fallback* rather than the common path — with a
probe in it, across the example corpus it never fires. It stays because `createExpressionCode`'s contract is still
"at the node's stamped width" and its remaining callers (a lambda body, a `Bool` operand) are only non-integer by
today's typing, not by construction. The narrow width is still genuinely materialised in two places — an intrinsic's
own result (`boxFromLong(resultRep)`) and a `fold` merge — and those are unchanged. One consequence worth stating
plainly, since S3's entry claims the opposite in passing: an *untruthful* stated transfer no longer truncates at the
call boundary, because nothing narrows there; it truncates wherever the narrow width is next materialised, or not at
all. That does not weaken R2, which was always axiomatic — nothing rechecks a leaf's statement — but it does mean the
backend is no longer an accidental second opinion on one.

**P5 — precision follow-ons.** Structural meta types (§2.3); `where` demanded with known argument metas at
nested calls, which P4 makes possible for the first time.

Each stage verified by the fast example sweep + byte-identity compare
(`reference_verification_harness_recipes`).

---

## 10. Non-goals

- Structural/functorial meta types — the second refinement domain's project.
- Derived `where` preconditions; `where` stays a stated contract.
- Meta-driven monomorphization — refinements stay invisible to instantiation identity.
- A second evaluator. Stated transfers are Eliot on the one NbE evaluator, or this is not worth having.

---

## 11. Decisions needed before code

1. ~~Option B over Option A~~ — **decided: B** (§6.2).
2. ~~A machinery-level `unknown` form~~ — **dropped, and the counterexample arrived** (§5). The machinery
   still has no `unknown` form and `Meta` is untouched, which is the half that mattered. But `parseIntInternal`
   *was* the real counterexample this decision reserved the right to be revisited for, so the **domain** grew a
   top. It first grew one as a slot-level `Bound` wrapper and now spells it `whole`, the both-ends-open interval
   (§5). **Settled and shipped** — and finished off by the third amendment: the generic `Meta[Bound[D]]` top is
   deleted, so *every* top is now a domain top and the machinery has none.
3. **Budget, not widening** (§5.2) — still the recommendation, but the framing has changed. §5.2's argument was
   *budget ⇒ no ∞ needed; widening ⇒ ∞ needed*, and ∞ now exists in the domain, so widening is no longer
   impossible — an ascending chain terminates at `whole`. That is a **removed one-way door**, not a reason
   to switch: the budget is still simpler and more honest, and Eliot deliberately has no widening machinery.
4. ~~An `{Inf}` leaf may not return a meta-carrying type~~ — **dropped** (§5.1). It buys nothing (a leaf is
   summarized, never executed, so its loop never reaches the interpretation whatever it returns) and would
   forbid a legitimate blocking native that is honestly `{Inf}` and honestly bounded.
5. ~~**Brace placement**~~ — **decided and exercised (S5)**: with the layer that owns the *fact* (§2.1), which for
   nine of the eleven S5 leaves is the layer that owns the native, and never in two layers at once (§8.2). No
   `signatureEquality` change was needed, as §8.2 predicted: every S5 brace sits on a declaration only one layer
   makes.
6. ~~**Scope of v1**: P2 alone, or P2 + P4~~ — **decided by landing it: P2 alone** (S5). P4 is a separate
   change with its own prerequisite (the 8.1 naming fix), and P2 is useful without it: totality at the leaves is
   what makes an absent meta mean one thing instead of two.
7. **May an ability declare a transfer its impls must satisfy?** (§2.2) — probably yes eventually, but as a
   contract feature, not part of this.
8. **A `power` native** (§5) — worth having for precision once `String` size lands, and safe only now that
   the domain has a top to saturate into. Not a prerequisite for anything. **Reaffirmed by S4**, which was
   originally scoped to include it (`docs/string-length-meta.md` §10) and did not: `parseIntInternal` states
   `whole` and the exponent never runs, so the tight cases (`"42"` ⤳ `[-99, 99]`, a `where`-bounded input field)
   remain the only thing `power` would buy.
