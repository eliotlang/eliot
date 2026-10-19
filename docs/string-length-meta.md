# A length meta for `String` — the refinement channel's second domain

Status: **shipped, S0 through S5 — the domain is complete and R2 is armed.** S0 (code-point indices), **S1** (the
`size` slot), **S2** (the literal seed, plus the LSP gate), **S3** (the backend result-edge re-encode, landed with
`length`'s `{size(s)}` — the first *stated* transfer in the tree), **S4** (the rest of `String.els`) and **S5** (the
platform leaves, and arming R2) have all landed; a `where` precondition over a string's size is checked at every use
site today, a literal's size reaches the `Int` domain through `length`, a size survives concatenation, slicing,
trimming, case conversion, repetition and replacement rather than stopping at the first call, and a native that
produces a `String` or an `Int` and says nothing about its meta no longer compiles. What was left is out of this
document's scope by design: `List`/`Array` size and the meta interpretation (§12) — the latter has since landed its
walk, which retires part of §9's precision reality check.

The domain cost **one** compiler arm (§3.1) and one Eliot declaration beside it. Everything else — the meta structure,
the derived `Meta` lattice, the `^Where` demand, the transfer name transform — was the shipped machinery, reached by
declaring a slot on a second type. That is the claim §1 made for going second with `String` rather than `List`, and it
held.

All of §11's decisions are now **settled**. The unit is the **code-point count** (§5.1), which added a prerequisite
stage S0 (the index family switched units with `length`) and rules out a byte-size slot (§5.2) and deferred any second slot
until S5 (§5.3, now landed). The **⊤ question is gone** — the range domain grew a top (`whole`,
`total-meta-transfers.md` §5), which this domain inherits for free and, per the §7 correction, does not actually need:
a `String` size is bounded by a representation like anything else. The slot is named **`size`** (§3.2), the scope
landed first was **S1+S2** (§11.4), and R2 stayed **dormant** across it (§11.5) until S5 armed it.

Prior art, and the two documents this one sits between:

- `docs/total-meta-transfers.md` — meta as a shadow logic (R1–R4, the leaf/derive split). Its §2.3 defers
  *structural* meta types to "the second refinement domain (`List`/`Array` `size`)". This is that second domain,
  in the one shape that needs **no** structural meta.
- The shipped value-range domain: `type Int {range: Interval[BigInteger]}`,
  `monomorphize/channel/RefinementChannelProcessor`, `core/processor/Meta{Constructor,Transfer,Where}Desugarer`.

---

## 1. What is being added, and why this one first

```eliot
type String {size: Interval[BigInteger]}
```

(The `Bound` wrapper is the range domain's stated top, inherited by every domain — see §7. Predicate and stage
examples further down were written before it landed and spell the bare `Interval`; read them as the wrapped form.)

A `String` gains one meta slot: the interval of its length, in the units `String::length` counts — which §5.1
decides is the **code-point count**, and which S0 makes true of every target. That single line turns on the whole
channel for strings — merges, `where` preconditions, transfers, hover — because everything downstream of a slot
declaration is already domain-agnostic.

**One** slot, deliberately, and it holds a count of code points rather than of storage units. Both halves of that
are load-bearing and §5 argues them: the unit must be a property of the string *value* or a `where` stops meaning
the same thing on two targets (§5.1), and byte size does not join it as a second slot because a byte count is what a
backend *derives* from `size`, exactly as an `Int`'s machine width is derived from its `range` (§5.2).

Why `String` and not `List` first, even though the TODO names `List`/`Array` `size` as the next domain: **`String`
is nullary.** `meta(List[A])` wants to be structural (`List$Meta(size, meta(A))`, meta as a functor on the type
structure — `total-meta-transfers.md` §2.3), which drags in a decision about recursive `data` and multiplies the
leaves forced to state a transfer. `String` has no element type, so its meta is a plain one-slot structure exactly
like `Int`'s, and it needs **no** change to `metaTypeOf`, no functor, no recursion story. It is the second domain
minus its hardest sub-problem — and it is the domain that proves the machinery is genuinely generic rather than
`Int`-shaped, which is the precondition for `List` size later.

What it buys today:

- `where` preconditions over strings — the fixed-width case that motivates the whole channel on a microcontroller
  (`def banner(s: String): Unit where fitsDisplay(size(s))`).
- **Cross-domain flow**: `length`'s stated transfer turns a string's size into an `Int`'s range, so a literal's
  length feeds `Int` arithmetic, `Int` `where`s, and (later) array bounds. *Verified* (§4.4).
- **A bound on a parsed number**: `parseInt` on a string of `n` code points yields an `Int` in `[-(10ⁿ-1), 10ⁿ-1]`,
  so a fixed-width input field lands in a known `Int` range without a `where` on the number. This is the everyday
  motivation, and it is the reason the unit has to be a count of characters rather than of bytes (§5.1, §6).
- A representation story for a future MCU backend: a `String` whose size is pinned to `[0, 16]` can be laid out as
  a fixed buffer, exactly as an `Int` pinned to `[0, 255]` is laid out as a byte today.
- The first honest test of R2 (`total-meta-transfers.md`) against a leaf set that is *not* three arithmetic
  operations. That test does not go well, and §7 says so.

---

## 2. What the existing machinery already gives, unchanged

Everything in this list works with **zero compiler change** once the slot is declared. This is the payoff of the
channel's "the `^Meta` companion is the one recognition point" discipline.

| mechanism | what happens for `String` | status |
|---|---|---|
| meta structure | `MetaConstructorDesugarer` emits `data String$Meta(size: Interval[BigInteger])` + accessor | *verified* |
| lattice | the auto-derived `Meta[String$Meta]` joins the slot through `Meta[Interval[T]]` | *verified* (compiles) |
| `metaTypeOf` | `String` ⤳ `String$Meta` by the same membership test, no new case | *shipped* |
| branch merges | `fold^Meta` reduces at `A := String$Meta`; `if`/`match` over strings joins for free | **does not fire — and does not fire for `Int` either**, see below |
| `where` | `MetaWhereDesugarer` already retypes *every* parameter `T` ⤳ `T$Meta`; a `where` over a `String` parameter needs no change (its own doc comment names this as the second-domain work) | *verified* (§4.3) |
| transfers | a return brace on a `String`-returning def desugars as usual; a **cross-domain** brace (`String$Meta` in, `Int$Meta` out) is just the ordinary per-position name transform | *verified* (§4.2) |
| backend | `IntRepresentation.isIntegerType` gates every width decision, so a `String$Meta` stamped on a node is ignored by the JVM backend | *verified* (§4.1) — the one thing it did **not** already give is §8's result edge, closed in S3 |

Every row held on landing except the branch merge, and that one failed **in both domains**, so it is not a property
of this domain. `banner(fold(true, "ab", "abcd"))` reports *"an argument's meta-information is not known here"* rather
than joining `[2,2]` and `[4,4]`; so does `asByte(fold(true, 1, 1000))` with the shipped `Int` range. Whatever reaches
`fold`'s arms at the demand is ⊤ for a string exactly as it is for an integer — a plausible culprit is that
`fold[A](condition: Bool, whenTrue: {Effect} A, …)` now takes its arms as *rows*, so a pinned arm meta no longer
survives to the call node, but that was not chased down here. It is an `Int`-domain regression to find and fix on its
own evidence; the `String` domain neither caused it nor is blocked by it, and no test in the tree asserted a branch
merge narrows, which is how it went unnoticed. Both jar sweeps are byte-identical across this change, so nothing
about layout moved either way.

---

## 3. The three compiler changes

**All three have landed.** The sections below are kept as written, with what shipped noted against each.

### 3.1 The α seed for string literals (the only real one)

Before this, a string literal was ⊤: `RefinementChannelProcessor.walkFlow` fell through to `case _`. Nothing else can
originate a `String` meta, so without a seed the whole domain is inert — which is precisely what made the
declaration safe to land alone (§4.1).

Mirror the integer seed. `eliot.lang.Runtime` already carries `def integerLiteral[V: BigInteger]: Int {closed(V,
V))}`; add its string twin and one arm in the channel:

```eliot
// stdlib/eliot/src/eliot/lang/Runtime.els
def stringLiteral[N: BigInteger]: String {closed(N, N)}
```

```scala
// RefinementChannelProcessor.walkFlow, beside the IntegerLiteral arm
case MonomorphicExpression.StringLiteral(value) =>
  val text = value.value
  metaViaCompanion(
    WellKnownTypes.stringLiteralFQN,
    Seq(GroundValue.Direct(BigInt(text.codePointCount(0, text.length)), bigIntType)),
    Seq.empty
  ).map(meta => Flow(meta, recordAt(node, meta)))
```

**Landed as written**, `Bound`-wrapped for the domain top the range domain grew meanwhile. The prototype counted
`text.length`; with §5.1 resolved to code points that is the wrong measure (it counts UTF-16 units) and
`codePointCount` replaced it — which is what `StringSizeIntegrationTest` pins with a supplementary-character literal
that fits a four-wide bound and would not if storage units were counted.

`stringLiteral` is a declaration no program ever calls (a literal's characters are already the value, so unlike
`integerLiteral` nothing desugars into it). That is deliberate: it is the vessel that keeps the seed's *construction*
in Eliot, so the channel supplies a number and the language builds the meta.

Note what the channel does and does not decide. It supplies a raw `BigInteger` — the literal's measured length —
exactly as the integer seed supplies the literal's value; the *interval*, the singleton, and the `String$Meta`
wrapper are all built in Eliot, in the brace. The channel still constructs no domain structure. It does now know
that a string's meta is measured by counting, which is one notch more domain knowledge than the integer seed
carries — but because §5.1 fixes the unit as the code-point count, that knowledge is **platform-independent**: the
channel is not guessing a host representation, it is counting the same thing every target's `length` counts.

That is what retires the alternative the prototype left open (passing the literal *string* as the type argument and
letting the brace call a per-platform compile-time `sizeOf(v: String): BigInteger` native). A contributed native is
only needed when the unit is platform data; once the unit is the string value's own code-point count, the direct
count is both cheaper and *more* correct — one native and one name saved. See §5.1.

### 3.2 The slot cannot be called `length`

*Verified, and it is a hard error, not a preference:*

```
stdlib/eliot/src/eliot/lang/String.els:4: Name was already defined in this module.
  type String {length: Interval[BigInteger]}
               ^^^^^^
```

A meta slot generates its accessor (`length(m: String$Meta): Interval[BigInteger]`) in the type's **own module and
the Default namespace**, where `def length(s: String): Int` already lives. `Int` never hit this because nothing in
`eliot.lang.Int` is called `range`.

Two ways out:

- **v1: name the slot `size`.** Reads well in the two places it appears (`size(s)` in a brace or a `where`), leaves
  `length` to the runtime function it belongs to, and costs nothing. *Verified.*
- **The structural fix: put meta accessors in `Qualifier.Meta`.** A slot accessor is compile-time-only machinery; it
  has no business sharing a namespace with the type's public API, and the collision will recur for every future
  domain whose natural slot name is also a natural function name (`List`'s `size` and `List::size`, say). It means
  teaching the brace/`where` desugarers to resolve accessors in that namespace, so it is its own small slice.

Take `size` now; log the namespace fix as the follow-on that lets a later domain use whatever name it wants.

**Landed as `size`.** The namespace fix stays a follow-on, and it is `List`'s problem first: `List`'s natural slot name
is also `size`, and unlike `String::length` the collision there is with the function users reach for most.

### 3.3 The LSP hover would mislabel a string's size as a value range

`TypeHintIndex.boundsOf` decodes *any* `X$Meta(Interval(lo, hi))` shape and the hover renders it as the node's value
range. Its own comment says "`None` for any other meta (a future domain), which is simply not shown as a range" —
but the match is structural, and `String$Meta(Interval(5, 5))` has exactly that shape. Hovering `"hello"` would
report a value range of `5..5`.

Fix with the same gate the backend uses: decode only when the node's type is the tracked `Int` (or only when the
meta's own FQN is `Int$Meta`). Cosmetic, one line, but it lands the moment the seed does — and §7's guidance is that
a wrong *rendering* is the only place recognising a domain by name is sanctioned, so this is where to do it.

**Landed**, taking the second option (the meta's own FQN), since the LSP module has no dependency on the backend's
`isIntegerType` and the node's type is not in hand at the decode. `TypeHintRangeCompileTest` covers it by hovering a
string literal and asserting *no* range — a test that fails without the gate, which was checked by removing it.

The same reasoning caught one more user-facing text: the channel's own `where` diagnostics said *"the argument's value
range"*, which is the `Int` domain's vocabulary in a processor that has none. They now say *"meta-information"* — the
language's own word for exactly this (`ability Meta`), true of every domain.

---

## 4. What the prototype proved

The prototype was: the `size` slot, the `Meta` import in `String.els`, `stringLiteral`'s declaration, the
`WellKnownTypes` FQN, and the channel arm — 19 lines. Everything below was run.

### 4.1 The declaration alone is inert and harmless

With `type String {size: Interval[BigInteger]}` and no seed, `HelloWorld`, `Strings`, `Concat`, `Unicode`,
`WherePrecondition`, `Ranges`, `MatchRanges`, `IntField`, `Match` and `Arithmetic` all compile clean. Nothing
originates a string meta, so every node stays ⊤. The slot can therefore land as its own commit.

### 4.2 Cross-domain transfers desugar and typecheck

Both of these compile with no compiler change:

```eliot
def length(s: String): Int {size(s)}                      // String$Meta in, Int$Meta out
implement Combine[String] {
   def combine(a: String, b: String): String {size(a) + size(b)}   // Interval addition, as Int's `add`
}
```

`length^Meta(s: String$Meta): Int$Meta = Int$Meta(size(s))` falls straight out of `MetaTransferDesugarer`'s
per-position name transform — the mixed-domain case needed no special arm, which is the strongest evidence that the
transfer language is not `Int`-shaped.

### 4.3 A `where` over a string parameter works, in both directions

```eliot
def fitsFive(i: Interval[BigInteger]): Bool = lessThanOrEqual(end(i), 5)
def label(s: String): String where fitsFive(size(s)) = s

def main: {Console} Unit = printLine(label("hello"))          // compiles, runs, prints `hello`
```

and with an eleven-character literal:

```
StringSize.els:5:38: The precondition of 'StringSize::label' is not satisfied by the argument's value range.
```

A use-site check, discharged from the literal seed through the auto-derived instances, with no channel knowledge of
strings beyond the seed.

### 4.4 The cross-domain path carries all the way into the `Int` domain

```eliot
def withinFive(i: Interval[BigInteger]): Bool = lessThanOrEqual(end(i), 5)
def small(x: Int): Int where withinFive(range(x)) = x

def main: {Console} Unit = printLine(show(small(length("hello"))))        // accepted
//                                         small(length("hello world"))  // rejected at the call
```

`"hello"` ⤳ `String$Meta(Interval(5,5))` ⤳ `length^Meta` ⤳ `Int$Meta(Interval(5,5))` ⤳ the `Int` `where`. The two
domains compose through the ordinary machinery.

### 4.5 …and that exact program then failed to *run* — see §8

It runs now: it is `StatedTransferResultEdgeIntegrationTest`'s first case, and S3 is what made it one.

---

## 5. Units, and why byte size is not a second slot

### 5.1 The unit is the code-point count — decided

`String.els` documents `length` as counting "the platform's storage units, not user-perceived characters", and the
JVM leaf is `java.lang.String::length` — UTF-16 code units. A UTF-8 target would count differently for the same
literal. So "the length of `"日本"`" is not today a platform-independent number, and the seed has to get it from
*somewhere*.

Three answers were on the table:

1. **Pragmatic.** The channel counts with the host's `String.length` (what the prototype did). That is exactly what
   `StringReductions` already does for the compile-time `length` native, so the seed agrees with the compile-time
   leaf by construction — but it bakes a JVM-flavoured assumption into a platform-independent module.
2. **Contributed.** The seed calls a per-platform compile-time `sizeOf` native living beside that platform's own
   `length` reduction. The unit is then defined once per platform, in the place that already has to agree with the
   runtime leaf value-for-value (`total-meta-transfers.md` §2.1, "a leaf's transfer is platform data").
3. **Define it away.** Declare the language's string length to be the **code-point** count and make every
   platform's `length` conform (JVM: `codePointCount`).

**Take (3).** (1) and (2) both leave `size` a per-target number, and a `where` precondition over a per-target
number is a precondition that holds on the JVM and fails on an MCU — a portability hole in the one channel whose
whole purpose is a portable compile-time guarantee. Only the code-point count is a property of the *string value*
rather than of a representation, which is what the Platform-Independence cornerstone requires of anything the base
layer can name. This is a **language decision**, not a channel implementation detail, and it is the answer that
also makes §6's two unbounded leaves statable.

**The bill is larger than "a behaviour change to `length`".** `String.els:8-11` already promises that `length` and
the index family agree — "Use it for slicing with `substring`/`take`/`drop`, which index in the same units". So the
unit cannot be changed in `length` alone; it changes in every index-taking leaf, or the API becomes incoherent
(`s.substring(0, s.length)` would truncate a string containing a non-BMP code point):

| leaf | today | after |
|---|---|---|
| `length` | `String.length()` (`StringNatives.scala:138`) | `codePointCount(0, length())` |
| `substring` | UTF-16 indices (`StringNatives.scala:214`) | `offsetByCodePoints` on both clamped indices |
| `take` / `drop` | bodied on `substring` | unchanged — inherit it |
| `indexOfInternal` | UTF-16 index | `codePointCount(0, idx)` on the result |
| compile-time reductions | `s.length` / host `substring` (`StringReductions.scala:40`, `:76-93`) | same translation, so the two tracks keep agreeing |

That is ~5 leaves plus their compile-time twins, and it costs O(n) index translation on the JVM.

**Land it as its own change, ahead of the meta work, and justify it without the meta work.** — **done, as S0**; the
table above is now history rather than a plan, and `split`/`replace` on their empty argument moved with it (§10).
Before that change `length("日本語𝕏")`
returned a different number per target, so any string-slicing user program was already non-portable — the meta domain
did not create that defect, it only made it visible. "Eliot string indices are code points" stands on its own as a
cornerstone fix; smuggling it in as a refinement-channel prerequisite would have made both changes harder to review.

Then say it in `String.els`'s doc comment: **the meta counts the same units `length` returns, and that unit is the
code point.** The meta must never be able to disagree with the function.

### 5.2 Byte size is a backend derivation, not a slot

The tempting next step is a second slot — `type String {size: …, bytes: …}` — on the grounds that when a string is
*stored* it is bytes that matter. The need is real; the slot is the wrong home for it, and `Int` already settled the
question.

`type Int {range: Interval[BigInteger]}` carries no byte width. It carries the **value range**, and the backend
derives Byte/Short/Int/BigInteger from it (`IntRepresentation.isIntegerType` gates every width decision — §2, §8).
The width is not meta-information; it is what a backend computes *from* meta-information using knowledge only it
has.

Strings are the same shape: **bytes is to `size` what the JVM's `Byte` choice is to an `Int`'s range.** An MCU
backend that wants to lay a `[0,16]`-size string into a fixed buffer multiplies `size` by its own maximum
bytes-per-code-point — a number only that backend knows. A `bytes` slot in `stdlib/eliot/src/eliot/lang/String.els`
would have the base layer assert that every string has a byte count, in units the base layer is forbidden to name.
That is exactly the "no platform *representation*" line.

The mechanism cannot rescue it either. A transfer brace desugars in `core` (phase 4) into an ordinary named value —
`length^Meta` — which `module` (phase 5) then merges across layers, so two layers each giving it a body is "Has
multiple implementations." **Per-platform transfer braces are impossible**; the only per-platform hook is a native
leaf reduction (§5.1's rejected option 2). A `bytes` slot would therefore be a base-layer slot that no base-layer
brace could ever honestly fill.

### 5.3 Any second slot is downstream of the ⊤ decision

This is a general constraint on the domain, worth stating once because it applies to every future slot and not just
to bytes.

Multi-slot metas are structurally free: `MetaConstructorDesugarer` reuses `DataDefinitionDesugarer` verbatim and the
auto-derived `Meta` instance joins field-wise, so `String$Meta(size, bytes)` and its lattice would fall out with no
compiler change. The cost is on the *transfer* side. A brace is a positional comma-separated list applied straight
to the meta constructor (`MetaTransferDesugarer.metaBody` builds `String$Meta(braceExprs*)`, parsed by
`optionalBracketedCommaSeparatedItems`), so **partial statement is not expressible**: a def either states every slot
or carries no brace at all and is ⊤ in all of them.

With one slot, "unstated ⇒ ⊤" is free. With two, "I know the size but not the bytes" has to be *spelled* as an
explicit unbounded interval — which is precisely the open-endpoint question §6 and S5 defer. **A second slot pulls
the ⊤ decision forward out of S5 into S2**, against §7's sequencing. Keep the domain at one slot until S5 lands.

The second slot that would genuinely earn itself later is not raw bytes but an **encoding claim** — a small
`ascii ⊑ latin1 ⊑ unicode` lattice, letting a backend tighten bytes to exactly `size` for an ASCII string. It is a
real fact no function of `size` determines, and it would be the first non-`Interval` domain, which is what proves
the lattice is generic rather than `Interval`-shaped. Two things it would need beyond the slot: a user-declared
`Meta[Encoding]` instance (the derived compound join dispatches per slot to the *domain's* instance, and only
`Meta[Interval[T]]` exists today) and a domain head that is a simple type application, since
`MetaConstructorDesugarer.slotJoin` yields no instance otherwise. Still downstream of S5.

---

## 6. The transfers the `String` leaves would state

This was the list R2 will demand (§7), and working it out is what showed where the domain is genuinely hard. `n`
abbreviates `size(s)`. **Every row marked *stated* has landed** — `length`'s in S3, the rest in S4 (§10) — and the
"stated as" column is what is in the tree, which differs from the honest transfer in exactly two places, each argued
below the table.

| leaf | honest transfer | stated as |
|---|---|---|
| `combine` (`++`) | `{size(a) + size(b)}` | **as written** — exact; `Interval` addition, as `Int`'s `add` |
| `length` | `{size(s)}` | **as written** (S3) — cross-domain, exact |
| `substring(startIndex, endIndex, s)` | `{Interval(0, min(end(range(endIndex)) - start(range(startIndex)), end(n)))}` | **`{interval(Bounded(0), end(n))}`** — the length ceiling only; see *the ceiling that could not be spelled* below |
| `take` / `drop` | derived from `substring` — **if** derivation existed (§9) | not stated: bodied ⇒ ⊤, and by R3 they may not |
| `trim` | `{Interval(0, end(n))}` | **as written** |
| `toUpperCase` / `toLowerCase` | `{Interval(start(n), end(n) * 3)}` | **as written** — **not** length-preserving: `ß` ⤳ `SS`, `ﬃ` ⤳ `FFI`. The discipline catches a bug an eyeball would not. The `* 3` factor is a code-point fact, so §5.1 makes this row portable rather than JVM-flavoured |
| `repeat(count, s)` | `{Interval(0, end(range(count)) * end(n))}` | **as written, plus a floor**: the product is raised to `0`, since a provably negative `count` would otherwise give a negative ceiling for a result that is simply `""`. The other cross-direction: an `Int` meta drives a `String` meta |
| `replace(target, replacement, s)` | `{Interval(0, end(n) + (end(n) + 1) * end(size(replacement)))}` | **as written** — an empty `target` inserts between every pair, so the occurrence count is `n+1` |
| `indexOfInternal(part, s)` | `{Interval(-1, end(n))}` | **as written** — a match starts inside `s`, and the sentinel is one below the first index |
| `Show[Int]::show` | a digit count of `range(value)`, plus one for the sign | **not stated** — the digit count needs a base-10 logarithm no arithmetic leaf offers, and it is an `Int.els` leaf rather than a `String.els` one. Left to S5 |
| `parseIntInternal` | `{Interval(-(10ⁿ - 1), 10ⁿ - 1)}` | **`{whole}`** — the domain's top; see below |
| `readLine`, environment/file/process reads | platform max | **not stated** — S4 stops at the leaves with an honest bound (§10) |

**The ceiling that could not be spelled.** `substring` states the bound its *argument* implies (a slice is never longer
than what it was cut from) and not the one its *indices* imply, because taking the tighter of two ceilings is not
something the endpoint vocabulary can express. `minBound` absorbs on an unbounded endpoint, which is exactly right at an
interval's `start` — the least of "no lower limit" and anything is no lower limit — and exactly wrong at its `end`,
where absorbing throws the other, bounded ceiling away. Spelling it correctly needs a `foldBound` on each side, which is
a three-line `match` in a signature, so the honest read is that the domain is missing an **intersection**: the least
interval contained in both. That is a small addition to `Interval` whenever a second caller wants it, and it is what the
index ceiling waits for. The length ceiling is the half that holds unconditionally, so nothing here is unsound — just
wider than the table's row, and only for a slice narrower than its subject.

**`parseIntInternal` states the top on purpose**, and `total-meta-transfers.md` §5 is where that was settled: its honest
bound is exponential in its argument's size, so at the upper end of a platform's own string size it is a
two-billion-digit `BigInteger` — finite, sound, and roughly 890 MB to materialise. A bound that hangs the compiler is
worse than a wide one. `whole` is a *stated* top rather than an omission, which is the distinction R4 exists for, and
the exponentiation the tight case needs stays a **precision follow-on** (`total-meta-transfers.md` decision 8) rather
than part of this stage. The `pow` leaf S4 was originally scoped to carry is therefore **not** in it.

**What S4 needed that did not exist: a way to write a number.** An interval endpoint is a `BigInteger` — a limit has no
width of its own — while a value-position integer literal is an `Int`, and by the *Types Are Values* cornerstone there
is no widening between them. So `Bounded(0)` inside a brace was a `Bound[Int]` and a type error, and every row above
whose floor is `0` was unwritable. S4 worked around it with the same idiom `rangeWithin[0, 127]` and `integerLiteral[V]`
use — put the literal in **type** position, where it is already a `BigInteger` — packaged as one compile-time-only
helper, `boundedAt[V: BigInteger]: Bound[BigInteger] = Bounded(V)`.

**That workaround has since been removed, by fixing the premise instead.** A brace is *compiler-track code*: its `^Meta`
transfer companion (and a `where` clause's `^Where` companion) is compiler-pool-only, dead in the runtime pool. So a
literal in one is read exactly as a literal in a signature already was — a bare `BigInteger`, never the runtime
`integerLiteral[n] : Int` protocol — which is a one-line rule in `CoreProcessor`/`CoreExpressionConverter` rather than a
helper. `Bounded(0)` now means what it says, `boundedAt` is deleted, and the two edges the workaround had are gone with
it: nothing is in type position, so nothing forbids a negative (`Bounded(0 - 1)` is the sentinel, the subtraction
reduced by the compile-time `Numeric[BigInteger]`), and argument order no longer decides a call's type parameters,
because there is no `Int` for a first argument to fix `T` to. What remains of the underlying friction is narrower: an
*ordinary def body* is still runtime-track, so a helper called from a brace cannot name a `BigInteger` constant in
value position — which is why `rangeWithin[Lo, Hi]` keeps its type parameters.

**§5.1 retires the two hardest rows** *in principle* — both were unbounded *because* the unit was a storage count,
and neither is stated in the tree for the separate reasons the table gives (an exponent that cannot be run, a
logarithm that cannot be written):

- `parseIntInternal`: `n` code points admit at most `n` decimal digits (`isInteger` allows an optional sign, which
  only reduces the count), so `|value| ≤ 10ⁿ - 1`. Honest, and exactly the bound that motivates the domain — a
  parsed field of known width lands in a known `Int` range.
- `Show[Int]::show`: the digit count of an interval is a function of its endpoints, so `show` is bounded whenever
  its argument's range is. It inherits ⊤ only from a ⊤ argument, which is the ordinary situation, not a missing
  bound of its own.

One prerequisite `parseIntInternal` exposes: the brace needs **exponentiation over `BigInteger` at compile time**,
and `StdlibNativesProcessor` offers only `add`/`subtract`/`multiply`. It cannot be written in Eliot either — a
recursion-free core has no loop (the *Total by Default* cornerstone) — so this is one new native leaf, in the same
place the arithmetic leaves already live. **S4 did not add it**, and the reason is not the cost of the leaf but what
running the exponent would mean at a platform's own size ceiling — see the table's note and
`total-meta-transfers.md` §5. The leaf states `whole` instead, and the tight case stays a precision follow-on.

That leaves one genuinely unbounded row instead of three. `total-meta-transfers.md` §5 argues that no leaf needs a
⊤ form, because "on a real target every meta-carrying type has a representation, hence a bound"; an unbounded
*input* read (`readLine`, a file, an environment variable) remains the counterexample, and the ⊤ question (S5) is
still owed for it — either `Interval` grows open endpoints, or these leaves state `2³¹-1`-flavoured bounds that are
true but useless, or R2 keeps an escape.

---

## 7. Interaction with R2 accounting — this makes arming harder, not easier

`MetaTransferAccountingProcessor` (landed at `526c7bc`, registered but **undemanded**) reports a body-less value
whose declared return head is a concrete meta-carrying type and which states no transfer. Its §P2 arming list was
five leaves: `String::length`, `indexOfInternal`, `parseIntInternal`, `Process::exitCode`, jvm `outcomeExitCode`.

Declaring `String` meta-carrying makes **every body-less `String`-returning leaf** join that list — the `String.els`
set above, plus `Path::show`, `File::message`, `Process::standardOutput`/`standardError`, `Environment`'s and the
jvm layer's private internals. Roughly a dozen more. §5.1 helps here: the `String.els` set is now statable almost
throughout (§6).

**S4 has since cleared the `String.els` half of that list**, so what R2 would demand today is the *rest*: the input
leaves (`readLine`, environment/file/process reads), whose bounds are platform data and belong beside their natives,
and two leaves whose bound is real but inexpressible — `Show[Int]::show` (a digit count wants a logarithm) and
`parseIntInternal` (an exponent that must not be run, already stating `whole`). That is the shape S5 inherits: not
"can a bound be found" but "what does a leaf say when the honest bound cannot be written down", and `whole` is
already the answer for one of them.

**Correction, since the ⊤ decision has landed.** This section used to say the *input* leaves — `readLine`,
environment, file and process reads — would need a ⊤ escape. They do not, and the reason is the same one
`total-meta-transfers.md` §5 gives for every other leaf: **a representation is a bound.** A JVM `String` holds at
most `2³¹−1` units, so `readLine` states `Bounded([0, 2³¹−1])` — closed, ordinary, and it keeps the fact that a
size is non-negative, which is the half worth having. Wide is not the same as unbounded.

The domain top that *did* land (a slot-level `Bound`, since collapsed into `whole` — `total-meta-transfers.md` §5)
existed for a different and much narrower reason: a
leaf whose bound is **exponential in its argument's meta**. `parseIntInternal` is the only one, and it is in the
`Int` domain, not this one. So this domain needs the top for nothing, and inherits it for free.

**Amendment, since half-open intervals landed** (`total-meta-transfers.md` §5). The correction above stays right
for a *leaf*: a leaf's transfer is platform data contributed beside its native, so the jvm `readLine` states
`closed(0, 2³¹−1)` and should. What it does not license is stating a maximum in the **base** — an abstract
`def length(s: String): Int` has no platform to read one from. That is now expressible without inventing one:
`atLeast(0)` states the half that is true on every target, and a platform narrows it to a closed range
where the base signature is redefined. So this domain gains a use for open endpoints that the `Int` domain reached
by the same route, and the sequencing conclusion below is unaffected.

The sequencing conclusion is unchanged and now unblocked: **land the `String` domain while R2 is dormant, and arm
R2 afterwards**, so the dozen new leaves are stated once rather than twice. Nothing here blocks the domain — an
unstated leaf is ⊤ today, which is exactly what a `String` leaf is right now anyway.

---

## 8. The backend gap the prototype found — **closed (S3)**

The §4.4 program compiled and then died at class-verification time:

```
java.lang.VerifyError: Bad type on operand stack
  Type 'java/math/BigInteger' is not assignable to 'java/lang/Byte'
```

Diagnosis. Stating `{size(s)}` on `length` makes the channel pin `[5,5]` on the **call node**, so the backend picks
that node's representation as `Byte` (`repInternalNameOf` reads the stamped meta). But `String::length` is emitted
as a *generated native static method* whose return descriptor is the ⊤ `BigInteger`. The backend widens **arguments**
to bignum at every ordinary call boundary (`generateArgumentToBignum`, since S9
`createExpressionCodeAtBoundaryWidth`) but has no symmetric re-encode on a call's **result** edge — because until now
no ordinary call node ever carried a narrow meta. `Numeric[Int]`'s `add` is invisible to this: it is an *inline
intrinsic*, so the backend already controls the width it leaves on the stack.

This is precisely the hazard `total-meta-transfers.md` §7 warns about — "get that wrong and it arrives as a
`ClassCastException`, not a type error" — reached from the other side: not a callee's interior, but a leaf whose
stated transfer outruns its emitted descriptor.

Three ways to close it, in order of preference:

1. **Re-encode on the result edge.** After invoking a generated native static method, convert from the method's
   declared return representation to the node's stamped one — the mirror of the argument edge's widening, using the
   conversion the backend already has. Small, local, and it makes *every* future stated transfer on a static-method
   leaf work.
2. **Stamp nothing at a static-method call node** (only intrinsics, literals and merges narrow). Sound and trivial,
   but it throws away the transfer's whole point.
3. **Emit the native at the narrowed width** — call-site-sensitive codegen for a shared method. Rejected: it splits
   a native per call-site meta, and refinements must stay invisible to monomorphization identity.

Take (1), and land it **with** the first stated `String` transfer, not after. Until a transfer is stated the gap
cannot fire (§4.1 verified a clean sweep), so it does not block the slot — but it does block `length`'s brace, which
is the first thing anyone will write.

**Landed as (1), and one line wider than "after invoking a generated native static method".** The conversion is
`ExpressionCodeGenerator.convertResultFromBoundary`, applied to *every* application except an intrinsic:

- **Why every application, not just a static-method call.** The property that matters is not "who is the callee" but
  "what width is on the stack". Every call boundary hands an integer back at the ⊤ bignum — a generated native's
  declared return descriptor, an ordinary Eliot method's return (emitted at that width by the same function), or
  the `Function.apply` bridge's erased `Object` read at a concrete `Int`. So the re-encode is derived from the node's
  own meta against that one boundary width, exactly as the argument edge derives its widening from the argument's own
  rep (that edge was `generateArgumentToBignum`; since S9 it is `createExpressionCodeAtBoundaryWidth`, which emits at
  the boundary width rather than converting to it). Restricting it to static-method calls would leave the over-applied
  spine, the apply-bridge and the two `match` calls as latent repeats of the same `VerifyError`, each waiting for its
  own first stated transfer.
- **Why an intrinsic is excluded, and why that is not a special case.** `generateIntrinsic` already *emits* at the
  node's stamped width (`resultRep`) — it owns the machine operation, so there is no boundary between it and the node.
  Converting again would convert from a width the value does not have. This is the only exclusion, and it is the same
  distinction §8's diagnosis already drew: "`Numeric[Int]`'s `add` is invisible to this: it is an inline intrinsic."
- **It is inert without a transfer.** With the node meta ⊤ — every call in the tree before `length`'s brace — the
  boundary width and the node width coincide and nothing is emitted. Verified: with the backend change alone and no
  brace, all 40 compiling examples are byte-identical to the pre-change build, logs included.
- **The narrowing is what the transfer asserts.** `BigInteger` ⤳ `long` ⤳ `i2b` ⤳ `Byte` truncates if the stated bound
  is a lie. That is the R2 contract (`docs/total-meta-transfers.md` §3), the same trust an arithmetic leaf's computed
  range already gets — not a soundness hole this stage opens.

Verified end to end by `StatedTransferResultEdgeIntegrationTest`: the §4.4 program runs and prints `5`; the emitted
instruction window right after `String.length` is exactly the narrowing; an eleven-code-point literal is *rejected* by
the `Int` precondition (so the size is carried precisely, not merely widened into the domain); and a call with no
stated transfer emits no conversion at all.

> **Amended by the round-trip peephole (`total-meta-transfers.md` §P4, S9): the re-encode is now the fallback, not the
> common path.** Nothing above is wrong — the gap is real and the conversion is still what closes it — but the second
> bullet's framing ("the only exclusion") turned out to be one case of a rule rather than the rule. The property that
> matters is not "who is the callee" and not even "what width is on the stack", but **what width the consumer reads**.
> An intrinsic was excluded because it emits at the node's own width; a consumer that reads the ⊤ width (an argument
> slot, a method return) is excluded for the mirror-image reason — converting down for it means converting straight
> back — and an intrinsic's *operand* is excluded for a third, since an intrinsic adapts to any width it is handed.
> With all three named, the re-encode fires only where a consumer genuinely demands the node's width, which no
> consumer in today's tree does. So the `StatedTransferResultEdgeIntegrationTest` window right after `String.length`
> is now empty by design (its case asserts the *absence*, with the rejecting case carrying the proof that the transfer
> reached the channel), and the last bullet's "the narrowing is what the transfer asserts" is no longer literally
> true at a call boundary: a lying transfer truncates wherever the narrow width is next materialised, or nowhere.
> R2 is unaffected — a leaf's statement was always axiomatic — but the backend is no longer an accidental check on it.

---

## 9. Precision reality check — say this in the docs before someone tries it

The channel is intra-procedural (`RefinementChannelProcessor`: a parameter, a value reference, a lambda body are all
⊤). So what actually narrows is: string literals, and directly-called leaves with stated transfers, within one body.

```eliot
def greet(name: String): String = "Hello, " ++ name      // ⊤ *inside* greet: `name` is a parameter
def banner: String = "-" ++ "-"                          // [2,2] — literal ++ literal, via combine^Meta
def label(s: String): String = s                         // at `label("abcd")`: [4,4] — derived (S8)
```

That is the same shape of limitation `Int`'s range already has, and the same fix retires both: the **meta
interpretation** of `total-meta-transfers.md` §6.2/P4, which interprets a callee's body under the caller's argument
metas.

> **Amended: P4's walk landed (S8), and it retired the third line but not the first.** A *call* to a bodied def now
> carries the size its body derives from the caller's arguments — `label("abcd")` is `[4, 4]`, and so is
> `greet("bob")`'s combined size at its call site. What is unchanged is the view from *inside* a parametered body: a
> parameter is still ⊤ there, because the derivation's output is consumed at the **call node in the caller** and the
> callee is laid out once, conservatively (`total-meta-transfers.md` §7). The two bullets below are the same story
> from two other angles, and S8 moved neither of them.

Two facts about *today's* channel sharpen this, both worth knowing before S2/S3 (found while auditing this section;
the first is still open, the second is fixed):

- **"a lambda body is ⊤" is really "any def that takes a parameter is ⊤, throughout".** `walkFlow`'s lambda arm drops
  its subtree's *records*, not merely its own verdict — and a def's own parameters are leading `FunctionLiteral`s in
  the monomorphized body, so a parametered def produces an **empty** `RefinementTable`. The examples above narrow
  because they are paramless; move the identical expression into `def banner(unused: Bool): String` and it is ⊤ again.
  `where` demands still fire there (they are checks made during the walk, not records), so this is precision only. It
  dissolves under P4, whose §7 separates "what the meta is" from "what may be stamped" — which is exactly this
  conflation. **Still open after S8**: the walk that landed is the *derivation*, and it made that separation only in
  the direction it needed (the interpretation records nothing). Dropping a lambda subtree's records is the other
  direction, and it is a representation decision — what a narrowed value may be stamped as behind an `apply` bridge —
  so it did not come along.
- **A dot call is ⊤, where the same call spelled directly is not.** `length("hello")` narrows and `"hello".length`
  does not; `substring(0, 3, s)` narrows and `s.substring(0, 3)` does not. The dot's subject reaches the callee
  through the `Function.apply` bridge, which is one of the aliased positions the channel drops (the bullet below).
  This is **not** new in S4 — the shipped `length` transfer has behaved this way since S3 — but it is the first
  limitation a reader will hit, because the dot form is the idiomatic one everywhere else in the language. **Sharpened
  by S8**: it is *not* the record-policy limitation it is filed next to, and P4's walk did not retire it — a probe
  confirms `small("hello".length)` still reports "meta-information is not known". The subject arrives applied through
  `Function::apply`, so the interpretation meets a function *value*, which carries no meta of its own. It therefore
  dissolves with the **higher-order** half of P4 (§5's table), not with the walk.
- **A meta may not be recorded without also recording the ⊤s around it.** The table is matched to the backend's tree
  by *source position*, and desugaring makes positions non-unique: a pure `val` block lowers to `(x -> rest)(e)` with
  the synthesized lambda and application both anchored on the bound expression's range. Recording only pinned nodes
  let the bound expression's meta be read as the application's, and the backend duly re-encoded a `Function.apply`
  result to `Byte` — §8's hazard, reached with no stated transfer at all. The channel now records ⊤ verdicts too, so
  an aliased position is ambiguous and drops to ⊤. Any new recording site inherits this obligation.

`String::show` being the identity was a good miniature of this: it is *bodied*, so under R3 it may not state a
transfer, and until P4 existed it derived nothing — correct and useless. **S8 answered it**: its body is interpreted at
the call, so `show` now carries its argument's size through. `foldLeft` is the pattern that remains (§8.3 of
`total-meta-transfers.md`), and it waits on two things rather than one — a higher-order interpretation, and a `List`
size for it to iterate over.

---

## 10. Staging

Each stage compiles and passes the example sweep on its own.

- **S0 — code-point indices** (§5.1) — **LANDED**. `length` and the whole index family (`substring`,
  `indexOfInternal`, and their `StringReductions` twins) switched to code-point units; `take`/`drop` inherited it. A
  **prerequisite, not part of this feature**: it is a cornerstone fix that stands on its own, it touches no channel
  code, and it was reviewed as a language change rather than as refinement plumbing. Test: a non-BMP string round-trips
  through `length`/`substring`/`indexOf`/`take`/`drop` on both tracks (`StringOperationsIntegrationTest`), and
  `Unicode.els` now carries a supplementary character.

  Two leaves outside the index family had to move with it, because they are documented in the *unit*: `split("", s)`
  is "the characters of `s`", and `replace("", r, s)` inserts "between every pair of characters". Java cuts both at
  UTF-16 boundaries, which would have split a surrogate pair now that a character is a code point, so the empty
  separator cuts at `(?s)(?<=.)` and the empty target fills `(?s)(?<=.)|^` instead. Behaviour on BMP text is
  unchanged, character for character.

  The **unit is now stated once**, on `type String` in `lang` (the layer that owns the name), and `length`,
  `substring`, `take`, `drop`, `indexOf` and `split` refer to it rather than restating it. `String.els`'s old sentence
  — "the platform's storage units" — is what S1's doc-comment change was going to have to contradict; it is gone.
- **S1 — the slot** — **LANDED**. `type String {size: Interval[BigInteger]}` + the `eliot.compiler.Meta` import,
  on the **stdlib** declaration of `String` (the layer that owns the refinement domain — `Bound`/`Interval` live there,
  and `Int`'s slot sets the precedent). The unit sentence went on the **lang** declaration instead, where `String`'s doc
  comment already lives and S0 already stated the unit: a name declared in two layers may carry a doc comment on only
  one of them. Inert on its own, exactly as predicted: no seed, so every node stayed ⊤ and the example sweep was clean.
- **S2 — the seed** — **LANDED**. `Runtime::stringLiteral`, `WellKnownTypes.stringLiteralFQN`, the channel arm, and the
  LSP `boundsOf` gate (§3.3). Tests: `StringSizeIntegrationTest` (accept/reject, the ⊤ fail-safe, a demand inside a
  parametered def, and the two code-point cases), `TypeHintRangeCompileTest` for the hover gate, and `StringSize.els`
  as a worked example. All 39 pre-existing example jars are byte-identical to master, so the domain is additive to
  codegen as §4.1 claimed.
- **S3 — the backend result-edge re-encode** (§8) — **LANDED**, together with the first stated transfer, `length`'s
  `{size(s)}`, exactly as this stage required. The brace sits on the **base** declaration rather than a platform one,
  which §P2 of `total-meta-transfers.md` would otherwise place with the native: `length` returns the string's own
  `size`, and since S0 fixed the unit that identity is true on every target, so there is nothing platform-dependent to
  state. (It is also the only place available — the jvm layer ships no `String.els`; its leaves are Scala natives.) The
  remaining leaves of §6 *are* platform data and wait for S4. Tests:
  `StatedTransferResultEdgeIntegrationTest` (run + emitted-instruction + reject + ⊤ cases); `StringSize.els` grew the
  cross-domain half of §4.4 as a worked example. The sweep: 39 of the 40 compiling examples are byte-identical, the
  fortieth being `StringSize` itself, whose source this changed — so the first stated transfer in the tree narrows
  nothing that was not asked to narrow. (`Unicode.els` calls `length` and is unmoved: its subject arrives through a
  `val`, which §9 explains is ⊤.)
- **S4 — the remaining `String.els` transfers** (§6) — **LANDED**. `combine`, `substring`, `trim`,
  `toUpperCase`/`toLowerCase`, `repeat`, `replace` and `indexOfInternal` state their size, each with its bound argued
  in its doc comment, and `parseIntInternal` states the domain's top. The input leaves stay unstated and ⊤ as this
  stage said. Three things about it are worth carrying forward:

  - **The compile-time `pow` leaf is *not* in it.** This stage was scoped to include it, and
    `total-meta-transfers.md` §5 had meanwhile settled the opposite: `parseIntInternal`'s honest bound is exponential
    in its argument's size, so at a platform's size ceiling computing it is an 890 MB numeral. It states `whole`, the
    exponent never runs, and `pow` stays decision 8's precision follow-on. Where the two documents disagreed, the
    later analysis in the document that owns R2 won.
  - **A brace could not name a number**, which is what actually gated the stage — see §6's *"what S4 needed that did
    not exist"*. One compile-time-only helper (`boundedAt[V]`) closed it, in `stdlib/eliot/compiler/`, which is also
    the first time this domain needed anything in the compiler overlay at all. **Superseded**: the premise was wrong
    rather than the spelling — a brace is compiler-track, so its literals are `BigInteger`s like a signature's. The
    helper is deleted and the braces read `Bounded(0)` / `Bounded(0 - 1)`; §6 records what that removed.
  - **`substring`'s parameters were renamed** to `startIndex`/`endIndex`. A brace resolves `end` to the *parameter*
    before the `Interval` accessor, so the old names made the accessor unreachable from the very signature that needed
    it. The rename is behaviour-preserving (natives bind by name and arity, not by parameter name) and the prose reads
    better for it.

  Tests: `StringTransfersIntegrationTest` — an accept/reject pair per stated leaf against one eight-wide display, so
  neither a widened-to-⊤ transfer nor an absent one can pass; the `ß` ⤳ `SS` case, which fails against a one-wide
  display precisely because the bound refuses to promise that one code point stays one; and the two `whole` cases,
  where the program runs but a `where` over the parsed number is *unprovable* rather than satisfied — the R4
  distinction, observable as two different diagnostics. `StringSize.els` grew a two-transfer composition
  (`toUpperCase(combine(...))`) as a worked example. The sweep: all 40 compiling examples build and 39 are
  byte-identical to the pre-change build, the fortieth being `StringSize` itself, whose source this changed.

  One thing the sweep caught that is worth repeating: **clear `target/` between the two halves of a byte-identity
  compare.** The accumulating fact cache is keyed by content but had kept a stale `^Meta` companion across the change,
  which hid a brace that did not evaluate at all until the cache was dropped.
- **S5 — ⊤ decided, R2 armed — LANDED.** The answer to "what does a leaf say when its honest bound cannot be
  written down" turned out to need *both* shapes the stage offered, chosen per leaf rather than once: an **open
  endpoint** where one side is honest and the other is not (`Show[Int]::show`'s `atLeast(1)` — every integer renders
  as at least one code point, and the ceiling needs a logarithm), and a **stated platform max** where the bound is a
  representation fact (`closed(0, 2147483647)` on the JVM's eight input/holder natives). Eleven leaves stated in
  all; the full list and each one's reasoning is `docs/total-meta-transfers.md` §P2. R2 is now a codegen
  precondition in `WovenValueProcessor`, so this is where the original TODO — *"a native that produces a
  meta-carrying type must state its meta-information"* — actually closed, for both domains at once. It is also what
  un-gates **any second slot** in any domain (§5.3).

  One piece of machinery had to be fixed to get there, and it is worth knowing before writing the next brace: a
  brace could not be stated on a def with an **untracked parameter** at all. The `^Meta` companion retypes each
  parameter `T` to `T$Meta`, which exists only for a slotted type, and every brace written until S5 happened to
  have only `Int`/`String` parameters — so `outcomeExitCode(outcome: ProcessOutcome)` failed with "Name not
  defined." at its own parameter. A parameter the brace never mentions then kept its own type; since
  `docs/total-meta-transfers.md` §8.8 no companion names a meta type at all, so the question no longer arises.

`List`/`Array` size (structural meta) and the meta interpretation (P4) stay out; S1–S4 is a complete, useful feature
without either.

---

## 11. Decisions needed before code

All settled; kept as the record of what was chosen and why.

1. ~~**Slot name `size`**~~ — **decided: `size`** (§3.2). The `Qualifier.Meta` accessor-namespace fix stays a follow-on,
   owed to `List` rather than to `String`.
2. ~~**Unit**~~ — **decided: the code-point count** (§5.1), with the index family switching units alongside `length`
   in a prerequisite change (S0). Two consequences settled with it: **byte size is not a slot** (§5.2, a backend
   derivation on the `Int`-range/width precedent), and **no second slot before S5** (§5.3).
3. ~~**Backend option (1)** for the result edge (§8)~~ — **decided and landed: (1)**, and taken at every non-intrinsic
   application rather than only at a generated native's call, since the width on the stack — not the identity of the
   callee — is what the conversion is a function of (§8). (2) remains the tempting cheap answer that guts the feature.
4. ~~**Scope**~~ — **decided: S1+S2**, the working `where` domain with no stated transfers, which is what landed.
5. ~~**Order vs. R2**~~ — **decided: the domain landed first, R2 stays dormant** (§7). The dozen-odd `String`-returning
   leaves that R2 will demand a transfer from are now on its list; they get stated once, in S4, rather than twice.
6. ~~**Is S0 in scope for whoever takes this?**~~ — **settled by landing it first** (§10). It went in as its own
   change, ahead of the slot and with no channel code touched, exactly as §5.1 recommended: landing the domain on the
   pragmatic unit and switching later was never a safe fallback, because every `where` written in the meantime would
   silently change meaning.
7. ~~**Does S4 carry the compile-time `pow` leaf?**~~ — **decided: no** (§10). `parseIntInternal` states `whole`,
   which `total-meta-transfers.md` §5 had already settled while this document still listed `pow` as part of the
   stage; an exponent that is 890 MB at a platform's size ceiling is a bound that hangs the compiler, and a wide
   stated top is strictly better. `pow` remains worth having for the tight cases (`"42"` ⤳ `[-99, 99]`), as decision
   8 there says, and is a prerequisite for nothing.

8. ~~**What does a leaf say when its honest bound cannot be written down?**~~ — **decided per leaf, and shipped
   (S5)**: an open endpoint when one side is honest (`atLeast(1)`), the domain top when neither is
   (`parseIntInternal`'s `whole`), and the platform's representation limit when the bound is a representation fact.
   The question S4 left was never "will the leaves have bounds" but what shape the answer takes, and the answer is
   that the domain already had all three shapes — no new machinery, only the desugarer fix above.

**S1–S5 are done.** What this document leaves open is what it always meant to: `List`/`Array` size (structural
meta) and the meta interpretation, both in §12.

---

## 12. Non-goals

- Structural/functorial meta types — still the `List`/`Array` domain's project.
- The meta interpretation (P4). **No longer true as written**: its walk landed after this document did
  (`total-meta-transfers.md` §P4/S8), so a bodied value's size *is* derived at the call site — `String::show` and
  `label` included. It was out of scope here, not out of reach.
- Any narrowing of `String` *representation* on the JVM (there is none to pick); the layout payoff is an MCU
  backend's, not this change's.
- A **byte-size slot** (§5.2). Byte count is a backend derivation from `size` plus the target's encoding, exactly as
  an `Int`'s machine width is derived from its `range` rather than carried beside it. The base layer names neither.
- An **encoding-claim slot** (`ascii ⊑ latin1 ⊑ unicode`, §5.3) — the second slot most likely to earn itself, and the
  first non-`Interval` domain. It was gated on S5 like any second slot; with S5 landed that gate is open, and it is
  now an ordinary next candidate rather than a blocked one.
- Changing what `length` counts is **no longer a non-goal**: §5.1 decides it, and S0 makes it a prerequisite of this
  feature rather than something it must work around.
