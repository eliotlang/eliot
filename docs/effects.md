# Effects in Eliot — the design, and what is left to do

**Status (2026-09-11): effects v6 is shipped, and this is its single document.** An effect is an ability
declared with the `effect` keyword; an **implementation is a name**, bound by `with` and forwarded lexically
from `main` inward through declarations. There is no carrier, no monad, no `Id`, and nothing to infer. Part I
describes the tree as it is; Part II is what is left — where the tree still diverges from Part I, the one
change decided and not yet built (a binding binder marked by its type, §9), and the decisions still open;
Part III is the list of things closed by measurement or decision, and enough provenance to read a source
comment that cites a retired document or a retired section.

**Decided 2026-10-05, not built: D20 (§11).** Effects become parameters in the surface as they already are in
the mechanism — `def greet(name: String) uses Console: Unit` — a lazy argument is `=> A`, a function parameter
is a block that may be called or passed on but never kept, and a `data` field holds a value. It closes five
silent defects found while designing it (§8 items 9–13) and reverses three of Part I's rules.

**One-sentence summary.** The user writes **effect rows**; each row entry desugars to one **phantom generic
binder** whose value is an implementation, written at every reference by a syntax-directed pass that reads
declarations only — so an operation call is an ordinary call to a known method, effects verify as a
**channel** beside the type (the same architectural move as the `Int` refinement channel), and the NbE
checker holds no effect rule at all.

**How to read this document.** Part I states the shipped design; it is the authority for the tree, and the
CLAUDE.md *Effects Are a Channel* cornerstone is its summary. Part II lists the divergences (§8), the planned
change (§9), the method every change is run under (§10) and the open decisions (§11, numbered **D**). Part III
holds the list of things **closed by measurement or decision and must not be re-proposed** (§12) and the
provenance (§13). Where Part I and any code, stdlib signature, example or test disagree, Part I wins and the
artefact is the defect. The record of how v6 was decided and landed (the former §8–§10: the reasoning, the
flag-day log F1–F9 and the follow-ups A2–A11) is in git history, and §13 says how to read a citation to it.

---

# Part I — The design

*D20 (§11, decided 2026-10-05) replaces this Part's surface and three of its rules — §1 rules 3 and 4, §2.1's
`{}`, §2.2's supply by `Default`, §2.3 and §2.4's form; its "Reversals this records" lists them. Until it lands,
this Part is the tree.*

## 1. The user model — four rules

1. **Effects run where they are written.** An effectful expression in any plain position performs its
   effects there, and they join the enclosing definition's row. Strict call-by-value in **every** plain
   position, a bare generic slot included: `choose(readLine, readLine)` runs both reads, `Box(shout)` runs
   `shout` and stores its value. The only exception is the *declared* one below.

2. **Suspension is declared.** A parameter that must *not* run its argument declares a row: `whenTrue: {} A`
   receives the computation unrun; `if[T](c: Bool, value: {Abort} T)` spells this and means it. After
   desugaring such a slot is a **thunk** (`{Abort} T` ⤳ `Unit => T`), since there is no carrier left to hold
   an unrun computation — but the thunk is an artefact of the lowering, not the surface: what a reader and
   every phase go by is the **row tag** on the declaration (`EffectRow.parameterEffects`), never the shape.

3. **A stored computation is bound where it is written.** A row-typed `data` field (`data TestCase(body:
   {Throw[E]} Unit)`) is a thunk whose operation calls were bound at construction, by the declarations in
   force *there*. Storing it, passing it through a plain generic and running it later are all ordinary;
   running it performs what the field's row declares, charged at the read. A `with` applied to it afterwards
   is an error, not a rebinding — there is nothing left to bind. This replaces v5's pinned rows: no base, no
   `| Id`, no canonical stack, and no ordering to spell.

4. **A binding passes into a position if and only if that position declares a row.** *(Found last, stated
   last, and it outranks the other three.)*
   - A **plain generic is a payload, always.** `A`, `B`, `T` in `def .[A, B](a: A, f: A => {} B): B`,
     `def ++[T ~ Combine[T]](left: T, right: T): T`, `def foldLeft[A, B](initial: B, …): B` carry values.
     A function that transports effects says so with a row on the slot.
   - A **rowless slot receives the value**, computed where the argument stands (rule 1). It is not a place a
     computation can be *handed on* unrun, and there is nothing to diagnose: the effects were the caller's.
   - **A row on the slot is what lets the callee's body reach the caller's declarations.** The lexical walk
     that decides an operation's implementation crosses into an argument iff the slot it sits at declares a
     row — `{}` counts. So a lambda at a rowless arrow (`map`'s `f: A => B`) may bind and discharge locally
     but may not reach the enclosing def's bindings, and anything it performs and does not discharge is the
     error at the lambda.
   - The four "carrier namings" of v5 collapse to **one predicate**: a slot either declares a row or it is a
     payload. There is no third kind of slot and no name-keyed exemption.

   Everything the write decides is then decided by declarations, per reference, order-free.

**Consequences the user sees.** `something.foldLeft(f, z)` with `something : {Console} List[T]` works with
zero declaration on `foldLeft`: the effects run, the payload flows, `Console` joins the caller's row, and
the collections library stays effect-oblivious. Evaluation order is readable from signatures. Rows remain
the only effect surface, and diagnostics stay in row vocabulary.

**Rule 4 was agreed and then worked around four times, and every stall in this design's history traces to
that erosion.** Recorded so it cannot read as new — the vocabulary is v5's, and the lesson is not:

| # | how rule 4 was worked around | what it cost |
| --- | --- | --- |
| 1 | bare-generic slots exempted from rule 1 — "mode belongs to the instantiation" | six days; a mode resolver, obligations, splice-restart (~350 lines), all reversed |
| 2 | declined a row on `foldLeft`/`foldOption` for ergonomics | the elaborator could not hoist at a generic-return callee *at all*; kept the whole payload router alive |
| 3 | the derived discharge stack routed a computation through `.`'s **rowless** slot "as data" | 5 `State`-family miscompiles; made "a generic is a payload" false, so nothing downstream could assume it |
| 4 | `foldOption` left with a strict `ifNone` because both declared spellings failed | a silent lazy-branch failure mode |

A fifth move was proposed and rejected in that form: *let the elaborator's payload test accept a
generic-headed return*. It **approximates** rule 4 instead of **declaring** it in the signature.

## 2. The surface

Three constructs carry the whole model.

**An `effect` is an ability with no carrier binder**, declared with its own keyword. **A member's row lists
what it performs *beyond* the ability it belongs to** — membership in the block already says the member needs
that binding, exactly as `show` inside `ability Show[T]` does not repeat `~ Show[T]`. So `{Console}` on a
member of `effect Console` is not written; a member's row is real where it names *other* effects. A function
that needs no such binding is not a member: it lives outside the block as an ordinary def with its own row,
as `updateState`, `orRaise` and `orAbort` do beside the primitives `state`, `putState` and `raise` inside.

```eliot
effect Console {
   def printLine(s: String): Unit
   def readLine: Option[String]
}

effect FileSystem {
   def readAll(path: Path): {Throw[IoError]} String
}

ability Show[T] {
   def show(t: T): String
}
```

**An anonymous `implement` is a default; a named one never is.** An anonymous block in one of the **two
sites** — the ability's module or the type's module — is the default for its pattern, subject to the existing
coherence and `where` rules, and is what a declaration with no named implementation binds. A **named**
`implement` may live anywhere, is never searched, is not checked for overlap, and its clauses may declare
rows — a clause row is what the implementation performs beyond its ability, charged wherever the name is
bound. It takes no parameters and closes over nothing: what it needs at runtime it asks an effect for.

```eliot
implement Console {                                       -- the platform's default
   def printLine(s: String): Unit = printLineInternal(s)
   def readLine: Option[String] = lineOrNone(readLineInternal)
}

implement session: Terminal {                             -- a test's, in the test module
   def write(line: String): {Writer[String]} Unit = tell(line ++ ";")
   def read: String = "Bob"
}
```

**`with` binds a name for its subject**: infix, subject-first, at the loosest precedence, left-associative,
so `xs.sort.render with reverseOrd` applies to the whole chain and `c with a with b` is `(c with a) with b`,
an inner `with` for the same ability shadowing the outer within its subject. It works on an ability exactly
as on an effect, and there are **two positions, one construct**: an expression in a body, and a slot's type
in a signature — the same split as `f(x)` and `List[Int]`, both application under the types-are-values
cornerstone.

```eliot
def greetTranscript: String = runWriterToLog(greet with session)
def sorted: List[Int] = sort(xs) with reverseOrd

def transcriptOf(program: {Console} Unit with recordingConsole): String = runWriterToLog(program)
```

The slot form reads *"this slot's computation, run with `mockConsole`"*: the actual delivered there has its
binding applied by the callee's signature, and the caller writes nothing. It is the one way a callee decides
the binding of calls it cannot see, since an actual's calls are bound in the caller. `with` **inside** a row
(`{Console with mockConsole}`) is rejected: it would put an ability on the left instead of a subject, a
second grammar repeating a pairing the implementation already declares. A `with` is accepted on a parameter's
or a field's type only, never on a def's own return row, which would be a second spelling of `with` around
the body.

**`with` is written almost nowhere.** Most code fixes nothing: a def declaring `{Console}` receives its
binding from its caller, up to `main`. That chain is what makes a fake possible — `greeting` never said
which console, so a test may say. A `with` in production code is the same mistake as a hard-coded dependency.

### 2.1 Rows, and the empty row `{}`

A row on a **return** is what the definition performs and does not discharge. A row on a **slot** is what the
slot receives unrun, and it is the declaration that lets the walk cross into the argument (§1 rule 4).

`{}` is the empty row: *"a computation, and I add nothing to it"*. It is the spelling of every
suspended-but-effect-transparent slot — `fold`'s arms, `else`'s fallback, `catch`'s handler, `.`'s `f` — and
it is the only spelling in the tree. It supplies no entry, so an operation inside such an argument is bound
by whatever the *caller* declares, and the argument is not run until the callee runs it. It needs no import,
and it puts nothing in the user's scope: under v5 `{}` named the machinery ability `Effect` and the whole
`eliot.carrier` package existed to hold it; both are gone.

A row with a base (`{Throw[E] | Id} A`, `{| Recorded} A`) has no v6 meaning — there is no carrier stack to
name — and is rejected by the desugar rather than silently read as an open row.

### 2.2 A parameter row is *supplied* — what makes a discharger

A **non-empty** row in a parameter position says *"I will run this computation, and these bindings come from
me"*. Which entries those are is read one level up, at the declaration:

- an entry the definition's own declared (return) row already has is **not** supplied — `if`'s
  `value: {Abort} T` rides the caller's `Abort`, because `if` declares `Abort` itself and the walk continues
  outward;
- an entry it lacks is **supplied**, bound by the slot's own `with` or, with none, by `Default`.

```eliot
def if[T](condition: Bool, value: {} T): {Abort} T                     -- performs Abort
def else[A](computation: {Abort} A, fallback: {} A): A                 -- supplies Abort: discharged here
def catch[E, A](computation: {Throw[E]} A, onError: E => {} A): A      -- supplies Throw[E]
def runStateToPair[S, A](initial: S, p: {State[S]} A): Pair[A, S]      -- supplies State[S]
```

Read as English they are already right, and that is the whole of what a discharger is: no tail, no base, no
second concept. Only a **top-level** parameter row supplies; a row in an arrow codomain (`onError: E => {} A`)
is the callback's own row, bound where the callback's body is written.

A supplied entry's own **type arguments** are written at the call from the actual's declaration, matched
entry-by-entry against the slot's row (`bad : {Throw[String]} String` against `Throw[E]` gives
`E := String`). Where no declaration answers, the call spells it — `runThrow[AssertionError, Unit](body)` —
and an argument nothing determines is **rejected** rather than defaulted, which is what stops a `catch` from
compiling against a frame it will not meet at runtime. Three shapes reach that rejection honestly, and all
three are ordinary: the actual is a **parameter reference** (a parameter has no callee whose declaration
could state the row); the actual **raises nothing**, so its row has no entry of that ability at all; or the
slot's row names the **same ability twice** (`{Throw[IoError], Throw[AssertionError]}`), where only the call
can say which one is being discharged.

Since v5's supply rule was per *entry* and syntactic, a definition could not supply an entry its own declared
return row already named. That limitation is **gone**: discharge is a frame installed at the discharger's
call, and the nearest enclosing frame is its own, so a definition may now discharge the very effect it
declares — which is what lets `describedAs(body: {Throw[AssertionError]} Unit): {Throw[AssertionError]} Unit`
catch its body's failure and re-raise a better one.

### 2.3 Storing a computation

A row-typed `data` field is the one place a type holds a computation, and it needs no extra spelling:

```eliot
data Task[E](step: {Throw[E]} String, label: String)
```

The field lowers to a thunk. Its calls are bound where the constructor is applied, and the field's row is the
declaration that covers them there — so `Task(failing, "load")` binds `failing`'s `Throw` at the
construction site rather than charging it to whoever built the value. Reading the field runs it: the accessor hands back the
thunk and the read applies it, so the entries the field's row declares are **charged at the read**, and an
undeclared read is the ordinary "performs but does not declare" error there. A read is the *call*, in either of
its spellings — `step(task)` and `task.step` are one thing, since `.` is an ordinary function whose body applies
its second argument to its first. Nothing captures a frame, so
nothing can dangle — a frame is installed by a discharger's call and left when that call returns or is exited.

The accessor's return is the field *as stored*, deliberately not a return row: a return row would mint a
phantom binder and claim the accessor performs those effects.

Because the read binds nothing, a `with` covering one of the field's entries **at the read** is an error, not a
rebinding — in both of `with`'s positions, a body's and a slot's type, since it is one construct. It would
otherwise absorb the charge without changing what runs: the effect stops propagating outward while the thunk goes
on running the implementation it was built with. What stays legal over a read is what merely *describes* it — this
definition's own declared row, which propagates the effect to its caller, and the `Default` a discharging slot
supplies, whose frame the thunk enters at runtime (`runThrow(step(t))`).

### 2.4 Naming a row

A **row alias** is a type alias whose body is a row, and it names the row together with its payload:

```eliot
type Git[A] = {Process, FileSystem, Throw[IoError], Throw[GitError]} A

def publishedTags(root: Path, id: PackageId): Git[List[TagRef]] = …
```

The alias is an **ordinary type alias** and a use of it an **ordinary application**: `Git` lowers to
`type Git[A] = A` — a row is declaration metadata and never a type (§3.3), so it is erased from the alias's body
exactly as it is erased from a return type — and `Git[List[TagRef]]` stays in the signature for the evaluator to
reduce. What crosses the use site is the row's **entries**, with the use's arguments substituted into them and
nothing else: the definition naming the alias mints one marked binding binder per entry and records them in its
declared row, exactly as if the row had been written out. So the definition is the one the user could have written
by hand in everything but its return type, which stays the name they did write.

**The alias is an ordinary name, and that is the whole of how a use finds it.** The alias *declares* its row, on
its own declaration, exactly as a `def` writing `{Console}` declares one — `core/…/EffectSugarDesugarer` records it
and mints nothing, since an alias names a row rather than performing one. A use is then the ordinary reading of a
resolved name: `resolve/…/ValueResolver` has already resolved `Talk` through the dictionary, and it reads the
declaration that name resolved to for the row it declares, resolving the entries **in the alias's own scope** before
substituting the use's arguments — the same reading `superConstraints` makes of an ability's own `~` constraints
(§2.5), and the same rule by which a callee's declared row propagates to its caller. So import scope, shadowing,
privacy and qualification are the ordinary ones: an alias crosses files, a binder named `Talk` shadows it, and
nothing is matched by spelling.

A written-out row and a named one **compose**, because the alias contributes to the return *position* — which is
what a written-out row lowers to: `def announce(n: String): {Log} Talking[Unit]` declares both, and an effect named
twice is declared once.

It landed 2026-09-11 (`afd6d34c`) as a **splice** — the whole row, payload included, put where the alias stood —
because the write then recognised a binding binder by its occurring in no type, and binders passed to an applied
alias stopped reading as bindings. The mark (§9) removed that reason, and with it the payload rewrite; step 8
(2026-09-12) replaced the file-local syntactic scan with the name reading above (§9.3). **One limit remains**: it
works in **return position only**, every other position being an error rather than a silent widening (before the
splice a row in an alias body was erased with no diagnostic, so `type Printing = {Console} Unit` silently meant
`Unit`). A parameter row is *supplied* rather than received and must additionally be thunked, which is a rewrite of
the slot rather than of the type naming it; lifting that is **D18** (§9.5).

What has no spelling is v5's `ability Web[F[_] ~ Console & Log]` — a set of abilities required *of a binder*.
With no carrier binder there is nothing to hang such a requirement on, and a name for a set of
*implementations* is likewise deliberately absent (§12, "not now").

What survives is the ordinary **superability closure** on a `~` constraint: `~ A` is closed under what `A`
itself requires of the parameter this use bound to this binder (`ValueResolver.superConstraints`, transitive
and idempotent). That is a relation between abilities and their parameters, and it never lands an ability on
something it was not written about.

### 2.5 `~` and `&`

`&` is a standard-library name: `infix left type &[A, B]` in `eliot.lang.Ability` (prelude, so ambient). The
parser accepts *any* operator between two `~` constraints and `ValueResolver.resolveCombinator` looks the
name up in the ordinary dictionary, requiring `WellKnownTypes.abilityCombinatorFQN` — so a module declaring
its own `&` takes the name back and gets a diagnostic instead of the built-in meaning. The combinator rides
ast→core on the unresolved constraint's `combinedBy` purely to reach that check and is dropped there; no
phase past resolve knows it existed, and that is a property of the model rather than a convention, because
`combinedBy` exists only on the *unresolved* constraint type.

An ability name resolves like every other name: `ValueResolverScope.getAbility` is a keyed lookup of the
ability's **marker** (`QualifiedName(n, Qualifier.Ability(n))`) in the ordinary dictionary, with the same
`privateNames` fallback a value name gets. So an ability honours import scope and shadowing, and is no
longer decided by `Map` hash order. **Do not reintroduce the scan**, and do not add a second lookup path for
ability names.

`~` stays reserved — it is a binder marker like `:`, not a name. Taking it into user space is **D3**.

Constraints travel in **two** generic types, one per resolution state, each parametric in the phase's
expression type: `ast/fact/UnresolvedAbilityConstraint[E]` (ast and core) and
`resolve/fact/AbilityConstraint[E]` (resolve through operator resolution). The split is load-bearing rather
than cosmetic.

### 2.6 What deliberately has no spelling

- **A row alias with its own AST node.** `type Git[A] = {…} A` names a row through the `{E} A` node the
  parser already has (§2.4). A bare set of abilities with no payload (`type Web = {Console, Log}`) is not a
  type and gets no node: paying for it with a new `ast.fact.Expression` case is not how this language grows.
- **A name for a set of implementations**, and a `with` that binds several at once. §12, "not now".
- **A closed row.** A slot's row cannot say *"and nothing else"*: an entry the slot does not supply continues
  the walk into the caller's scope, so there is no way to forbid what the caller allows. This is what deleted
  `eliot.test`'s `pure { … }`, whose whole meaning was that pin; making it expressible is a language
  addition, not a library one (§7).
- **A negative effect.** Discharge is a frame, so a discharged entry simply never joins the row.
- **Row or binding inference of any kind.** See §5.

## 3. The mechanism

### 3.1 The desugar writes the implementation

Two passes, both syntax-directed, both reading declarations only.

**`core/processor/EffectSugarDesugarer`** turns each row entry and each `~` constraint into **one phantom
generic binder**: a binder of kind `Type` that occurs in the generic list and in **no parameter or return
type**, so rows still never flow into types (§3.3). Three rewrites and nothing else:

```
def greeting(name: String): {Console} Unit    ⟶   def greeting[Impl](name: String): Unit      -- Impl ~ Console[Impl]
def sort[T ~ Ord[T]](xs: List[T])             ⟶   def sort[Impl, T ~ Ord[Impl, T]](xs: List[T])
computation: {Throw[E]} A                     ⟶   computation: Unit => A
```

**Every minted binder says so in its declared type** — `Impl: Implementation[Console]`
(`GenericParameter.implementationMark`, §9). The mark is the one place the fact "this binder is a binding" is
written down, and it names the ability the binding is for, so nothing is re-derived from the shape of a
signature. It lives between `core` and `row` and nowhere else: the write erases it at the end of that phase
(`BindingWriter.Writer.unmarked`), so the checker is handed the signature it was handed before the mark
existed. Minted binders are still *placed* together, but they need not be a **prefix**: `typeArgs` applies
positionally, and the write merges the marked indices with what the call determines for the rest — which is
what lets a member of a *parameterised* ability declare effects of its own. The pass is idempotent, because
`CoreProcessor` applies it uniformly to definitions the ability lowering has already produced, and its
idempotence test is the same mark (`GenericParameter.isBinding`): one fact, one place it is written down, two
readers.

A **row alias** (§2.4) adds no mechanism of its own. It lowers to an ordinary type alias whose own declared row is
recorded and not minted, and a definition naming one as its return type is handed the alias's row *entries* —
resolved in the alias's scope, arguments substituted, payload untouched — by `resolve/…/ValueResolver`, which mints
them exactly as `EffectSugarDesugarer` mints entries written out in the return position.

**`row/BindingWriter`**, run by `RowElaborationProcessor` between the recursion gate and saturation, then
rewrites one definition so that every reference carries the implementation each of the callee's phantom
binders stands for. Three jobs, one walk:

1. **write the bindings** — each binding binder, read off the callee's marks, is given a value by the
   resolution order below, merged by index with whatever else the call determines. A binder is never left to a
   metavariable.
2. **thunk and apply** — an actual delivered to a row-typed slot is wrapped in a lambda, and a reference to
   one of *this* definition's row-typed parameters is applied to `unit`. Doing both unconditionally is what
   makes a pass-through (`runAbort(computation)`, `val restFailures = rest`) come out right with no inspection
   of the argument's shape or type: wrap and apply are inverse, so a pass-through is an η-expansion.
3. **erase `with`** — the node exists to put a binding in scope for its subject; once the subject's references
   carry it, it is dropped, from a body and from a slot's type.

**Where a binding comes from — the resolution order**, walking outward lexically:

1. the nearest enclosing `with` for that ability;
2. this definition's own phantom binder for it — a **received** binding, filled by its caller. There is no
   graph to sum: forwarding is the enclosing signature, read once;
3. for an actual at a row-typed slot, the entries that slot **supplies** (§2.2) — bound by the slot's own
   `with`, or by `Default` with none;
4. `Default`, "search at the ground arguments" — today's two-site resolution — for an ordinary **ability**.
   For an **effect** there is no default: an uncovered one is the "performs but does not declare" error,
   reported here at the reference.

**Effect-ness is read from one place only**: the callee's declared row. An ability appearing in
`effectRow.returnEffects` is an effect at this reference — which for an `effect`'s member is what membership
recorded, and for an ordinary definition is what its `{ … }` says. A `~` constraint's ability is in no row, so
it defaults. Nothing keys on a name or a shape.

**Both halves of a definition are written, because both hold references.** A guarded return
(`def head[COND: Bool]: if(COND, String[]) else raise("empty")`) is compile-time code in type position, and
its `if`/`else`/`raise` are ordinary calls with row-typed slots and phantom binders. What differs is only the
scope check: a signature's effects are the guard channel's vocabulary, discharged by the guarded-return read
rather than performed, so an uncovered one defaults instead of being reported. The same exemption covers the
platform **run boundary** (`row/RunBoundaryFunctions`), which is where every effect's chain ends.

**Pure code is untouched**, and so is code that only forwards: a definition with no rows, no constraints and
no rowed callee is returned unchanged.

Two mechanical invariants of the pass, both of which cost real bugs to learn:

- **Position fidelity.** The walk returns the **original** nodes when nothing changed. Rebuilding an equal
  spine re-attributes it to per-argument positions, silently moving every diagnostic anchored at a call and
  duplicating LSP hover hints.
- **The universe is built by demand, not guessed.** `RowChecker.Universe.onMiss` reports every name consulted
  but absent; the processor fetches exactly those and repeats until a round misses nothing new. Guessing would
  fall back to unknown-callee approximations, and an unwritten binder silently runs on the platform's default.

### 3.2 What the write may consult

Declarations, and nothing else: the callee's declared parameter and return types, its declared row, its
membership in an `effect`/`ability` block, the implementation names a `with` resolved to, the run-boundary
registry, and one level of type-alias expansion inside those signatures.

v5 needed a written *whitelist* here (§5's retired rule 5), because its elaborator had to classify each
slot — carrier-headed or payload, pinned or open — and every classification is a place an approximation can
accrete. **There is no classification left to approximate**: a slot either declares a row or it does not, and
that is read off the declaration. A rule that inspects a *sibling argument's expression shape* is inference,
not desugaring, and is still prohibited; a decision that cannot be made from a declaration is a gap to close
**in the declarations**.

The fail-safe direction is built in: a missing write is an aborted definition with a violation at its own
position, never a binding silently taken from the platform's default.

### 3.3 One verifier, and one precondition

Checking a runtime term yields a **payload type** (the existing NbE judgment, which never sees an effect)
and a **row** (a second output, exactly as an `Int`'s range lives in the refinement channel beside the type,
not inside it). Row constraints are set-shaped: union for sequencing, inclusion for boundaries
(`derived ⊆ declared`) — commutative and order-independent, so no argument-order sensitivity can exist.

**The verifier is the scope check**, per definition, pre-monomorphization: not a separate pass but the write's
own walk (§3.1). An operation or a rowed callee needs a covering declaration, and the only places one can come
from are the enclosing def's row or constraints, an enclosing `with`, or a slot's row. It is complete before
monomorphization, since nothing about it is instantiation-dependent; it emits *"This value performs the effect
'X' but does not declare it…"* at the reference, and it owns the diagnostic for a `with` whose subject contains
no covered use. *Forward what is declared, derive what is done* — a forwarded per-operation verdict would be a
checker self-report and is rejected, as is any negative-effect surface.

There **was** a second one, post-monomorphization, re-deriving each ground instantiation's row and checking it
against the declaration. D7 (§11, closed) retired it, on the measurement it asked for rather than on the argument: under
names its "performs X" could only mean "a reference forwards a **received** binding to a callee declaring X as a
row entry", which sees propagation through a *declaring callee* but never a direct operation call — by then
`AbilityResolver` has rewritten `printLine` into the implementation method, which declares no row. Its coverage
was therefore a strict subset of the scope check's with no case of its own, and a second place to maintain one
diagnostic. **Do not reintroduce a post-mono effect verifier**: what a monomorphic body can still say about
effects is strictly less than what the declaration walk already said.

What survives at that seam is **not** its shadow: `monomorphize/channel/SuppliedRowArgumentsProcessor`, wired as
a **codegen precondition** via `getFactOrAbort`, rejecting a supplied row entry whose type argument nothing
determines (§2.2). That check needs ground arguments and so cannot move earlier.

### 3.4 The three primitives

A resuming clause is a call and needs nothing. A finishing clause is a **non-local exit**; a stateful
implementation needs a value **threaded through calls that never mention it**. Neither is expressible as a def
in a strict pure core — under v5 both were expressed by the transformer instances, which is the whole reason
the carrier existed. They are now three **platform-private leaves**, one per target, and nothing else:

| primitive | shape | jvm | microcontroller | compile track |
| --- | --- | --- | --- | --- |
| escape | `escapeInternal[K, A, R](body: {} A, onExit: K => {} R, onValue: A => {} R): R` — abortive, never re-entered; the frame is the machine stack | an exception, one class per instantiation | a status flag and a jump (hypothetical, §13's A2) | an evaluator intrinsic |
| cell | `withCellInternal[S, A, R](initial: S, body: {} A, combine: A => S => {} R): R` — scoped to one call, saved and restored around it | a static field per instantiation | a register | an evaluator intrinsic |
| loop | `foreverInternal` | `while(true)` | the super-loop | never runs (`Inf` is stuck) |

The control effects' single implementations are written over them: `Throw[E]`'s `raise` is `exit`, `State[S]`'s
`state`/`putState` are `read`/`write`, `Writer[W]` appends to a cell, `Dep[T]` reads one. The frame an operation
reaches is the **nearest enclosing** one of its instantiation — the machine stack's discipline, inside the leaf
and nowhere else.

They are **private, and the dischargers are therefore abstract in the base.** A public cell is Landin's knot —
a cell holding a closure that reads the cell is a loop, and `termination/PurityGuardTest` exists to keep it out
— so `withCell` may not be a base name, and `escape` follows for uniformity. `runThrow`, `runAbort`,
`runStateToPair`, `runWriterToPair` and `provide` are body-less signatures in the base and bodied per platform;
`catch`, `else`, `runStateToValue`, `runStateToFinalState`, `runWriterToValue` and `runWriterToLog` are ordinary
platform-independent bodies over those and stay in the base, where the base-layer rule says they belong.

Eliot has no *layer*-private visibility — `private` is module-scoped — so each jvm module needing a primitive
declares its own copy. That is five copies of two shapes, and it is the right trade: one public `eliot.jvm.Cell`
would put a mutable cell in reach of any jvm program. Repetition also separates the frames for free, since the
backend keys a frame class on the name that installed it.

### 3.5 Discharge

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or
thread through, and the entry simply never joins the row — so there is nothing to spell as a negative effect,
and a wrapper-reached discharge inside a `{Console}` body just compiles.

**Nesting order at the run site decides interaction**, and it is written where the frames are installed:
`runStateToPair(s, runThrow(c))` versus `runThrow(runStateToPair(s, c))` is the difference between state
surviving a `raise` and not. There is no canonical form to fix and no ordering for the compiler to choose.

A discharger may be called any way a function can be — v5's "a discharger must be called directly" rule has no
subject, since a thunk is a plain value and passes through the dot's plain `T` as the value it is. A
discharger's **handler may itself perform effects** (`onError: E => {} A`, bound where it is written).

**A `val` is a bind, and binds the value** (D17, decided 2026-09-12). A `val`'s right-hand side is a plain
position, so rule 1 applies to it unchanged: `val x = lookupConfig("db.url")` *runs* the lookup there, charges
the enclosing definition, and binds `x` to the `String`. There is nothing suspended left to discharge, so
`val x = comp` followed by `x else fallback` is the error it looks like — reported on the right-hand side,
where the effect was actually performed — and the discharge belongs on that right-hand side instead
(`val x = lookupConfig("db.url") else "gave up!"`). A `val` is therefore **not** a new kind of position, which
is what keeps rule 4's single predicate single.

**Two families, and only one is rebindable.** The **control effects** — `Throw`, `Abort`, `State`, `Writer`,
`Dep`, `Inf` — have exactly one implementation per platform, over the private primitives; `with` has nothing
to choose there. The **interpretation effects** — `Console`, `Log`, `FileSystem`, `Process`, `Environment` —
and every ability are what `with` and a naming slot are for. The families are a description, not a bit in the
language: nothing keys on it.

### 3.6 An effect is declared, never read off a shape

v5 read effect-ness off an ability method's declared row; v6 moves the declaration to the block, where an
ability's already is. Membership in an `effect` block says a member needs that binding; a **constructor
class** is simply an `ability` (`ability Container[F[_]] { def wrap[A](a: A): F[A] }`), and needs no exception.

**No phase reads effect-ness from a name, a shape, or a higher-kinded binder.** The one reading is the callee's
declared row (§3.1). The `<Ability>Carrier` naming convention, an LSP reverse table and "has an `Effect`
instance" all miscompiled in both directions under v5 and are prohibited; under v6 there is no carrier to
recognise at all, so the prohibition has nothing left to guard and is kept only so it is not reinvented.

Nothing of carrier identification remains: the dead `Effect`/`Suspend` pocket (`EffectMachinery`,
`EffectCarriers.declaredEffects`, `ModuleName.carrierPackage`) was deleted on 2026-09-10 (`90b74273`). The one
structural predicate that survived is **not about effects**: `CarrierKindChecker.isHktBinder`, "is this binder
higher-kinded?", which `check/CarrierKindChecker` asks in order to reject a `[F[_]]` binder instantiated at a
fully-applied proper type. That is a kind system living next door, and it is soundness (§12).

### 3.7 Rendering

There is nothing to invert. An effect is an ordinary nullary ability, an implementation is a name, and a
computation is a thunk, so a type contains no machinery to hide: `GroundValueRenderer` prints what the user
wrote. v5's inverter — a canonical `ThrowCarrier[E, StateCarrier[S, Id], A]` stack rendered back as
`{Throw[E], State[S] | Id} A`, plus the `Id[X]` ⤳ `X` erasure — went with the carrier, and with it the two
entry points it needed, which existed only because a carrier's last argument meant one thing applied to a
payload and another unapplied.

### 3.8 What the checker holds

**No effect rule at all.** v5's one survivor, `EffectLifter.tryPureWrap`, had a carrier-headed expected type
for its subject and is deleted with it. There is no lift, no `pure`, no `Id`, and no effect diagnostic in the
checker.

Two things beside it are **not** effect machinery and must not be deleted as such (measured — see §12):
`check/CarrierKindChecker` (§3.6) and the compile track's mid-spine default ladder with its deferred slots
(`Track.Compiler`, `Unifier.resolveDeferredSlot`) — the sole live reader of the `Unifier`'s higher-kinded-meta
record.

## 4. The scope check, as a spec

A `Row` is a set of effect-ability entries (production: multiset of *(ability, type-args)*). The check is per
definition, over the operator-resolved body **and signature**, reading only *declared* information. It is the
write's own walk (§3.1), so this is a spec of `row/BindingWriter` rather than of a separate pass:

- **the environment** at a point is `bindings` (innermost-first, so a nearer `with` shadows an outer one for
  the same ability) and `thunks` (the row-typed parameters in scope, whose references apply to `unit`).
- **a reference** to a callee with phantom binders resolves each binder by §3.1's order. An **ability** with
  nothing in scope binds `Default`; an **effect** with nothing in scope is the error, at that reference.
- **entering an argument** at slot *i*: the walk descends with the slot's supplied entries bound (to the
  slot's `with`, else `Default`) iff slot *i* declares a row. A rowless slot is descended into with no slot
  scope, so a lambda there reaches nothing enclosing.
- **a `with`** binds its ability for its subject's text, and crosses a def boundary only through a
  declaration. A `with` whose subject contains no covered use and no call to a declaring def is a hard error
  naming the fix, never a silent no-op.
- **a stored read is charged, never bound**: a saturated call to a `data` field accessor whose field declares a
  row runs the thunk, so the field's entries are charged there (§2.3). They need a covering *declaration* — this
  definition's row, or a slot's supply — and a `with` in scope for one of them is the error at the read, in either
  of `with`'s positions.
- **a clause row is charged at the binding site**: `greeting("Bob") with recordingConsole` performs
  `Writer[String]` there, because the name's clauses declare it, and the binding for it comes from the same
  order.
- **two exemptions, both because something else answers**: a **signature**, whose `raise`/`abort` is the guard
  channel's vocabulary and is discharged by the guarded-return read; and a platform **run boundary**, where
  every effect's chain ends and each of `main`'s entries is bound to the two-site `Default`.
- **a declared entry must be consumed** (decided 2026-10-05): an entry of a definition's own row that no reference
  in its **body** is written from is the error "This value declares the effect 'X' but does not perform it", at the
  entry. A row says *my caller hands me an implementation of these*; one nothing here uses asks every caller to
  declare an effect on no evidence, all the way to `main`. Consumed means written from at least once — by an
  operation, a declaring callee, a `with`'s clause-row bindings, or a stored read — on any path, so `if(ok, v)`
  consumes `Abort` whatever `ok` is. An actual at a slot that does not supply the entry is bound in the *caller's*
  scope (§2.1), so running it consumes nothing: `def run[A](v: {Console} A): {Console} A = v` is rejected, being its
  `{}`-slot twin with a declaration nobody needs. Exempt: a body-less value (its row is a contract a later layer
  bodies), a run boundary, and a signature's own references. An `implement` clause declares the union of its block's
  clause rows (`ImplementationRows`), so it is held only to the entries it wrote. The error waits for the
  definition's callees: a callee performing the effect *undeclared* is its usual cause, and that callee's error is
  reported instead (`RowElaborationProcessor.calleesWritten`).

**Suspension is row-neutral.** Whether a slot is strict or declared-suspended changes only *when* the effect
runs and *whose* declarations bind it — never whether the caller must declare what it performs.

**Two same-ability entries** at different arguments are two entries; at identical arguments they deduplicate.
In one slot's row they are the shape §2.2 requires the call to spell.

## 5. Standing rules

These bind every future change to the effect system.

1. **Where decisions live.** Part I states the decision. An appendix, a commit message or a measurement note
   that changes a decision must change Part I, never amend a rule in place elsewhere. (This exists because a
   *reversal* once read as a refinement for six days.)
2. **Stop on conflict; do not route around it.** If Part I's rules appear to conflict with each other, with
   the tree, or with a measurement — or if a step finds itself *narrowing*, *bounding*, *deferring*,
   *approximating* or *exempting* one of them — **stop and surface it.** Do not land the workaround and
   record it as a corollary. The tells, in this project's own vocabulary: "bounded staging", "the corpus
   forced", "narrowed to ~nothing", "a small local concession", "keeps the shipped idiom", "it usually
   holds". A conflict is a decision for Robert, not a judgement call in flight.
3. **There is nothing to infer.** A phantom binder is written by a `with`, by the enclosing declaration, by a
   supplying slot, or as `Default`, in that order — never left to a metavariable, a join, a lattice, an
   ordering-sensitive slot decision, or a sum over a monomorphized sub-graph. Carrier inference was the
   historical bug class (carrier theft, premature commitment); binding inference would be the same bug in the
   new vocabulary, and **dynamic scoping resolved at compile time** is its exact shape. Both are prohibited.
4. *(Retired: "carrier-ness by tag, never by name or shape" — no subject. §3.6 keeps what it protected.)*
5. *(Retired: the elaborator whitelist — no subject. §3.2 says what replaced it.)*
6. **A component may read solved metas and splice rewrites; it may never run inside unification, never
   retract a solution, never grow an ordering arm.** A shape that genuinely needs mid-drain resolution is a
   stop-and-redecide signal, not a licence for a mid-flight arm.
7. **A rule invented so that one idiom elaborates is a rule shaped by that idiom, whatever its stated
   generality.** Delete the rule; fix the declaration.
8. **Fail-safe direction.** Every bound, every deferral and every declination must be able only to *withhold*
   a permission, never to grant one silently.

## 6. Testing — a named implementation is the injection point

Production code that declares an effect row commits to no interpretation: it names no implementation, so
whoever runs it decides. In production that is the synthesized entry point binding each of `main`'s entries to
the platform's default; in a test it is one `with`.

```eliot
effect Terminal {                                  -- the application's own effect
   def write(line: String): Unit
   def read: String
}

def greet: {Terminal} Unit = {                     -- production code, untouched by the test
   val name = read
   write("Hello, " ++ name ++ "!")
}

implement session: Terminal {                      -- the test's double, in the test module
   def write(line: String): {Writer[String]} Unit = tell(line ++ ";")
   def read: String = "Bob"
}

def greetTranscript: String = runWriterToLog(greet with session)
```

Four properties fall out of the design rather than being added for testing.

- **A double is one declaration.** It needs no type to hang on, no colocation with the ability or a carrier,
  and no coherence question: a named implementation is never searched, so it may freely overlap a default.
  *Never searched* is now enforced rather than assumed: the two-site search and both coherence checks read
  `ModuleAbilities.anonymousImplementationMethodsOf` / `anonymousMarkersOf`. Until 2026-09-12 they read the
  unfiltered lists, and the rule held only because a double is not usually declared in the ability's own module —
  one that *was* got picked up by an ordinary row naming nothing (§8 item 7). Colocation is allowed, so it had to
  be safe.
- **A double cannot cheat.** A user module cannot declare a native, and the platform's natives and primitives
  are private to its layer, so an implementation reaches the world only through effects **its own clauses
  declare** — which are charged, and bound, at the binding site.
- **A double keeps its own state through an effect**, not through a carrier: `session` above writes to
  `Writer[String]`, and that entry is charged where `session` is bound and discharged there by
  `runWriterToLog`. It never appears in `greet`'s row.
- **Interpretation is per effect, not per program.** `body with mockConsole with mockFileSystem` binds two
  doubles and leaves everything else at its default; under v5 one type argument decided every effect at once.

`eliot-test` is the worked framework: a suite's whole declaration is its return type — the row alias
`type Test = {Writer[List[TestResult]]} Unit`, widened to `{Console} Test` where its cases perform for real — and an
author's own discharge word binds the doubles on its body slot's type, so a faked case writes no fixture at all and
reads `"…" should "…" in onConsole { … }`. `examples/src/EffectsNamedEffect.els` is the minimal version of the same
thing, and `EffectsFakeConsole.els` does it for a stdlib effect.

**What this deletes from v5's testing story**, all of it symptom rather than design: the fake carrier and its
`Effect` instance; the rule that a fake gets no lifting because it has no `Suspend` (the n² cross-lift wall);
the region rule and the `{| Recorded}` capture tag that opted out of it; "do not stack over a fake"; and
run-then-assert as a necessary shape. `Dep[X]` + `provide` remains available for a seam you want stated in the
signature, and swapping the platform layer remains the whole-program integration answer.

## 7. Live limitations

Each is stated, fail-safe, and either has a plan entry or is a deliberate trade.

1. **A row cannot be closed.** A slot's row says what it supplies, not what it forbids: an entry it does not
   supply continues the walk into the caller's scope. So there is no way to write "this body may perform
   nothing at all", which is what `eliot.test`'s `pure { … }` meant and why it is deleted. Making it
   expressible is a language addition and is not planned.
2. **A discharger's type arguments are sometimes written by hand.** Where no declaration determines a supplied
   entry's arguments (§2.2's three shapes), the call spells them. The rejection is loud and names the fix; the
   alternative — defaulting — compiles a `catch` against a frame it will not meet.
3. **An under-applied backend *intrinsic* has nowhere to link.** An intrinsic is emitted inline at each call
   site, so only a saturated call can be emitted at all; `digits.map(show)` for such a name is a hard error at
   the definition naming the fix (`digits.map(n -> show(n))`). This is a gap in the backend, not a rule of the
   language — the same shape for an ordinary native, ability-implementation or not, is supported.
4. **A guarded return cannot carry its author's message.** The compile-track `Throw` went with the carrier
   (the flag day's F5, §13), so a `raise("…")` in a signature reduces without its text. Accepted rather than fixed; the route
   back is keying `Throw`'s compile-time frame on a fixed marker the way `Abort` keys on `Aborted`, at the cost
   of two instantiations sharing one frame.
5. **A row alias works in return position only** (§2.4). A slot or a field doubling many effects still writes
   the full chain: a parameter row is *supplied* rather than received and must additionally be thunked, which is
   a rewrite of the slot rather than of the type naming it. Lifting it is **D18** (§9.5). The alias's *other*
   limit — that it had to be declared in the file using it — is gone: it is reached by ordinary name resolution
   (§2.4), so it crosses files, honours import scope, and is shadowed by a binder of the same name.
6. **A stored computation's binding is fixed where it is constructed.** Deciding the handler before storing is
   unambiguous and easier to understand; losing first-classness is the accepted price. A `with` applied to a
   stored computation later is an error, never a rebinding — rejected at the read, in both of `with`'s positions.
7. **An undischarged control effect reaching `main` fails at runtime, not at the boundary.** The run boundary
   binds every entry of `main`'s row to the two-site default, and a control effect's single implementation then
   exits into no frame. **Decided 2026-09-12: this must be a compile error** (D19, §11). The route is settled and
   costed; the remaining question is which of the two costs to pay, and until it is paid the runtime failure
   stands. Loud, so fail-safe.

---

# Part II — What is left

**Status (2026-09-12, third entry of the day).** v6 landed on 2026-09-09 and its record — the reasoning, the
flag-day log, the follow-ups — is no longer here: Part I states what it built, and git history holds how
(Part III §13 says how to read a citation to it). This part holds only what is *not* done: where the tree still
diverges from Part I (§8), the change §9 decided — **built in full on 2026-09-12**, and kept here for the
corrections it measured and the one thing it did not move — the method any such change is run under (§10), and
the decisions still open (§11). Every entry marked **decision** is Robert's.

**Two decisions were taken on 2026-09-12 and this part rewritten around them.** **D17** closed to rule 1 — a
`val` is a bind — which turned out to need **no code at all**: the tree was right and §3.5 carried the wrong
sentence (§8 item 3). **D19** closed to "it must be a compile error", and pursuing it found two silent defects
that had nothing to do with `main` and are now fixed — a named implementation was answering the two-site search,
and a slot's `with` was invisible to the layer merge (§8 items 7 and 8). D19's own fix is **not** landed: both
routes to it are now measured rather than argued, and picking between their costs is the open half of that entry.
Of the six divergences §8 opened the day with, **items 2 and 3 are closed** and item 5 is decided but not
landed; items 1, 4 and 6 remain open, and two more (items 7 and 8) were found and closed in the same change.
**Item 2 needed no decision** and closed on its own later that day: a dot-read is the same read as the call
spelling, so the rule had only to be stated on the call rather than on one of its two spellings.

**2026-10-05.** **D20** is decided and not built: effects are parameters (`uses`), a lazy argument is `=> A`, a
function parameter is a block that is called or passed on and never kept, and a field holds a value. Designing it
found **five more silent divergences**, §8 items 9–13, all of which D20 closes. It supersedes **D18**.

**2026-10-11.** **D20c** opened: D20 has no spelling for an *open* stored computation, whose effects are handled
where it runs. That is the closed kind's counterpart, and §12's rejection argued against the closed kind only.
A spike refuted option 1's hoped-for `runST` argument: a binding names an implementation, never a frame, so a kept
hook exits into, or writes into, an unrelated frame of the same instantiation silently. That also showed §8 item
10's failure is not always loud. **A fourth option was proposed the same day** (D20c, option 4): an open field is
a *finite product* of closed specialisations, one per implementation of the effect in the pool, selected at the read
by the binder in scope — the closed world of a whole-program monomorphiser standing in for runtime evidence. It
needs no region, no runtime implementation and no reified continuation, and it is a *proposal*, not a decision.

**2026-10-12.** The spike's lesson turned out not to need storage: two programs that keep nothing let a helper's
private `catch` or `State` intercept its caller's `raise` or `putState` (§8 items 14 and 15, open, and not closed by
D20). The same probe refuted D20c's "second property" — no compile-time identity separates two live frames. **Option
5 was proposed** (D20c): a frame is a parameter too — an implementation's code stays a compile-time name and its
*environment* (an escape frame, a cell, a parameter) is passed at runtime along the declared chain, so an operation
reaches the frame it was handed. It would also settle D19 and give doubles state. Option 4 was corrected to
specialise at the read, and found to need a positivity rule against Landin's knot. Both are proposals, not decisions.

## 8. Where the tree diverges from Part I

Found by probing the compiler while the user docs site was rewritten for v6 (2026-09-10) and by the flag day's
own record. Part I wins by standing rule 1, so each is a defect to close or a decision to make, never a doc
fix. The silent ones come first, because a silent acceptance is the one failure mode this design forbids
(standing rule 8).

1. **An unused `with` is a silent no-op.** §4 says a `with` whose subject contains no covered use and no call
   to a declaring def is a hard error naming the fix. No such check exists anywhere in `row/`. It needs
   something the write does not track — whether a binding was ever *consumed* — and is the same
   silent-acceptance family that A11 closed for a stored read (§2.3).
2. ~~**A dot-read of a row-typed `data` field hands back the thunk.**~~ **Closed 2026-09-12.** Only the call
   form `step(task)` was a read (§2.3); `task.step` was a type error where a value is expected and a **silent
   no-op** as a block statement (`job.run` printed nothing). The first write-up's premise was wrong in one word:
   `.` does not *lower* to the accessor call, it **is** a call — the ordinary
   `infix left below apply def .[A, B](a: A, f: A => {} B): B = f(a)` in `eliot.lang.Function` — so the accessor
   reaches the write as an *argument*, and the read rule, stated on the head of a saturated call, never saw it.
   `BindingWriter.calledSpine` now reads through that one name (`WellKnownTypes.applyOperatorFQN`, recognised
   exactly as `&` is, so a module declaring its own `.` takes the name back and gets none of it) and hands the
   read rule the call the author spelled: the same entries charged at the same position, the same `with`
   rejected, the same application to `unit`. Only the *reading* rules consult that view — what runs is still the
   `.` call, since the accessor's own bindings were already written when the walk reached it as an argument.
   Two things this measured. A rule stated on a *call* has two spellings in this language and has to say so;
   the sibling rules that read a call (`argumentRow`, the supplied-row determination) turned out to need no
   change, because A6 reads `E` off the field's declared row by a route the spelling never touches —
   `runThrow(t.step)` determines it as `runThrow(step(t))` does, measured, not assumed. §10's gate held whole
   (all 45 jars byte-identical), which here can only say the change touched nothing else: no example stores a
   computation, so the witness for the feature is the eight cases added to `StoredComputationIntegrationTest`.
   **Subject gone 2026-10-12**: D20 step 3 rejects a row on a field, so `calledSpine` and those cases were deleted
   with the stored read they served.
3. ~~**`val x = comp` then `x else …` fails**~~ — **closed 2026-09-12, and it was never a tree defect.**
   **D17 is decided: a `val` is a bind**, so rule 1 was right and §3.5's "a `val`-bound computation is
   dischargeable" was the wrong half; it is struck there and recorded as a reversal below. Measured before
   deciding, on the real tree: `val x = lookupConfig(…)` inside a `{Abort}`-declaring definition compiles and
   runs; the same `val` under a pure return reports at **5:12, the right-hand side** — the position the effect
   is actually performed at; and moving the discharge onto that right-hand side
   (`val x = lookupConfig(…) else "gave up!"`) compiles and prints the fallback. **No code changed.**
4. **`runThrow("no failure")` is accepted.** §2.2 lists an actual that raises nothing among the shapes
   rejected for an argument nothing determines; the tree accepts it when the call spells the argument. Every
   case is loud (the argument is written by hand), so this is last.
5. **An undischarged control effect reaching `main` fails at runtime**, not at the boundary (§7 item 7).
   **Decided 2026-09-12: it must be a compile error.** The mechanism was measured rather than argued and is
   written up under **D19** (§11); one cost has to be chosen before it lands.
6. **A row alias works in return position only** (§2.4). Interim by design. §9 step 5 made the alias itself an
   ordinary *definition*; step 8 (2026-09-12) made it an ordinary *name*, which lifted the file-local limit and
   the misreadings that came with it (§9.3). What is left is the position, and lifting it is **D18** (§9.5).
7. ~~**A named implementation answered the two-site search.**~~ **Closed 2026-09-12.** §2 and §6 both say a named
   implementation "is never searched" and "is not checked for overlap", and nothing enforced it: the search and
   both coherence checks read `ModuleAbilities.implementationMethodsOf`, which returns *every* implementation in
   a candidate module, named ones included. The rule held only by the accident of **where doubles usually live** —
   a test's double is not in the ability's module, so it was not a candidate — and a double **colocated with its
   own ability** was silently picked up by an ordinary row that named nothing. That is the silent-acceptance
   family (standing rule 8), and it is exactly what D19 needs to be able to lean on. The search and the two
   checks now read `anonymousImplementationMethodsOf` / `anonymousMarkersOf`; the unfiltered pair stays for the
   marker lookups, which address an implementation by its full identity and must see named ones.
8. ~~**A slot's `with` was not part of the signature the layer merge compares.**~~ **Closed 2026-09-12.**
   `Expression.structuralEquality` had no `WithBinding` arm, so two *identical* copies of a signature carrying
   `obj: {Abort} A with abortByEscape` fell to its `case _ => false` and the merge rejected them with "Has
   multiple different definitions." A layer therefore could not body a discharger whose slot names an
   implementation — which is the whole surface D19's route is built on. The catch-all is fail-safe by design
   (an unrecognised shape reads as *different*), so this was a missing arm, not a wrong default.

**Items 9–13 were found on 2026-10-05**, probing `aa0523ff` while D20 (§11) was designed. Every one compiles
clean and is wrong at runtime, so all five are the silent family (standing rule 8). They share one cause — a row
on a slot both *marks a block* and *mints bindings*, and nothing keeps the block inside the call or checks that
the callee has the binding it hands over — and **D20 closes all five by construction**; each is listed with the
D20 rule that does it. The witnesses are the five programs below, which become D20's regression tests.

9. **Rule 4's third bullet is not enforced: a lambda at a rowless arrow reaches the enclosing bindings.**
   `def applyTwice(f: String => Unit): Unit` called as `applyTwice(s -> printLine(s))` from a `{Console}`
   definition compiles and prints, exactly as `f: String => {} Unit` would: the two spellings behave identically
   for a lambda written at the slot. So the tree already treats every function parameter as a block, just
   without the restriction that makes that safe (item 10). D20 makes this the rule (rule 2) rather than the
   accident, and adds the restriction (rule 3).
10. ~~**A function parameter can be kept, and its lambda runs after the call that bound it.**~~ **Closed 2026-10-12**
    by D20 rule 3 (work-list step 1, below). Rows erase from
    types, so `capture(f: String => {} Unit): Holder = Holder(f)` stores the block in a rowless field. With
    `leaky: {Console} Holder = capture(s -> printLine(s))`, a later `runIt(h: Holder): Unit` — declaring
    nothing — prints. With `escaped: Holder = catch[String, Holder](capture(s -> raise(s)), …)`, the `raise`
    runs after `catch` has returned and the program dies with a bare `RuntimeException` from
    `Throw.exitInternal`: a frame the type system said was there is not. That is the loud case. Fired inside an
    unrelated frame of the same instantiation, the hook exits into *that* frame, and a hook bound through a named
    implementation writes into an unrelated `Writer`'s log, both silently (D20c's spike, 2026-10-11). D20 rule 3.
11. ~~**A lambda written in a value position captures the enclosing bindings.**~~ **Closed 2026-10-12** by D20
    rule 4 (work-list step 1, below). `built: {Console} Holder =
    Holder(s -> printLine(s))` — no function parameter involved — compiles, and the stored lambda prints when a
    def declaring nothing runs it. D20 rule 4.
12. ~~**A data constructor supplies a field's row by `Default`.**~~ **Closed 2026-10-12** by D20 rule 6 (work-list
    step 3, below): a row on a field is rejected. With `data Job(run: {Console} Unit)`,
    `makeJob: Job = Job(printLine(…))` declares nothing, because the constructor *supplies* `Console` (it is an
    entry the constructor's own row lacks, §2.2) and binds the **platform's** console. A test reading the job
    through `useJob(j: Job): {Console} Unit` under `with recordingConsole` gets real output and an empty
    transcript: the read is charged to a binding the stored thunk never uses. A11 rejected a `with` *at* the read;
    this is the same lie one definition further out. D20 rule 6.
13. ~~**A slot launders an interpretation effect.**~~ **Closed 2026-10-12** by D20 rule 5 (work-list step 2, below).
    `launder(body: {Console} Unit): Unit = body` supplies `Console`
    by `Default`, so `looksPure(name: String): Unit = launder(printLine(…))` performs real I/O with a pure
    signature, and an outer `with recordingConsole` binds nothing (which is item 1, the unused `with`). The
    two-site default meant for `main` is reachable from any definition with a body. D20 rule 5.

**Items 14–15 were found on 2026-10-12**, probing `9c7a31c9` while D20c (§11) was analysed. Both compile clean and
are wrong at runtime, and **D20 does not close them**: neither keeps a block, so both stay legal under every D20
rule. The cause is one — the primitives find a frame by its *instantiation* on the machine stack (§3.4), so an
operation reaches the nearest frame of its type rather than the one its binding was written under — and D20c's
option 5 is the proposed fix. The programs are in D20c.

14. **A helper's own `catch` swallows its caller's `raise`.** `guarded(body: {} Unit)` runs `body` inside a private
    `catch[String, Unit]`; a caller writing `guarded(raise("boom"))` inside its own `catch[String, Unit]` sees the
    helper's handler run and its own code carry on after the call. Accidental handling: a library's private use of
    `Throw` intercepts every block it runs.
15. **A helper's own `State` receives its caller's `putState`.** `counted(body: {} Unit)` runs `body` inside a private
    `runStateToFinalState("helper", …)`; a caller's `putState` written for its own `State[String]` lands in the
    helper's cell, and the caller's state is unchanged.

**Items 16–17 were found on 2026-10-12** by D20c option 5's first spike (§11, "The `transcriptOf` spike").

16. ~~**A slot's named implementation performed its clauses' effects into whatever frame enclosed the run.**~~
    **Closed 2026-10-12** by the spike. `transcriptOf(program: {Console} Unit with recordingConsole)` whose body runs
    `program` without discharging the double's `{Writer[String]}` compiled clean. Called inside an unrelated
    `runWriterToLog`, the double's lines landed in *that* log (`outer;Bob;`, exit 0); called with no `Writer` frame
    at all, it died with a `NullPointerException` in `Writer.tell`. The caller writes the double's clause bindings as
    `Default`, and nothing held the callee to giving them. Now each such clause ability is promised to the block like
    the slot's own entries (D20 rule 5).
17. **Two entries of one effect are one binding in the walk.** `{Throw[IoError], Throw[GitError]}` mints two binders,
    but `BindingWriter` keys its scope, the rides set and the promises by ability, so the innermost binding answers
    for both, and a slot supplying `Throw[IoError]` under a definition riding `Throw[AssertionError]` supplies
    nothing. Harmless today — both bindings are `Default` and the primitive picks a frame by instantiation — and the
    first thing option 5 breaks, since there the binding *is* the frame. Open; the spike's second finding.

## 9. Built: a binding binder is marked by its type

**Decision (2026-09-11).** A binder the desugar mints — for a row entry, for a `~` constraint, or for an
ability block's binding slot — is declared with the type `Implementation[A]`, `A` being the ability it binds,
and every phase that needs to know which binders are bindings reads that declared type and nothing else.
Abilities and effects are one mechanism here, so one marker serves both.

**Where the tree is (2026-09-12). §9.3 is built, all seven steps.** Every binding binder carries its mark, the
write reads it and nothing else, the four dead encodings are gone, and the row alias is an ordinary type alias
whose use hands over entries rather than a spliced row. §9.2 records the three corrections steps 1–4 measured and
step 5 a fourth. Nothing of §9 is left; what the alias still cannot do — a parameter, a field, another file — is
**D18** (§9.5), which this step did not move and was not asked to.

### 9.1 What the tree did before this, and what it cost

Nothing marked a phantom binder past the AST, and the same fact was encoded four times —
`GenericParameter.inferable` on the AST binder, the count `inferableArity` that `CoreProcessor` collapses it to
and every later fact forwards unread, `BindingWriter.mintedPhantoms`' re-derivation (unmentioned in every
parameter and return type *and* the first argument of one of the definition's own constraints), and the
`Qualifier.Ability` special case for a member's own slot. Each cost something concrete:

- **The prefix rule.** A count describes only a prefix, so the write was a prefix write, and a binding behind a
  binder no declaration determined was an error (`nonPrefixPhantom`): a member of a *parameterised* ability
  declaring effects of its own (`ability Show[T] { def show(t: T): {Log} String }`) could not be written at all.
- **The splice.** A row alias applied to binders *mentions* them, so the write stopped seeing them as bindings;
  `RowAliasExpander` therefore β-reduced the alias textually before minting — the payload rewrite step 5 deleted.
  Only that: what confines the alias to **return position** is the slot rewrite a parameter needs, and to **one
  file** that `core` has no dictionary. Neither was the splice's doing and both are still there (D18).
- **Fragility.** A user's unmentioned `[P]` was told from a binding only by the constraint rule, and four
  encodings had to be kept in step by hand.

### 9.2 The form chosen, and the two not chosen

The marker has three possible homes, and the reasoning is recorded because the first two are the natural
readings:

- **A flag on the signature's `FunctionLiteral`.** From `core` on, a signature is one expression — a chain of
  `FunctionLiteral`s, one per generic binder, around the curried `Function[…]` arrow chain — so a binder *is*
  that node, and `SignatureView.Binder(name, parameterType)` its projection. A new field there lands on every
  phase's expression case class and every match over it, and on runtime lambdas in bodies where it is always
  empty. Rejected on cost.
- **A typed marker the checker verifies.** Give the implementation marker's signature the return type
  `Implementation[Console]` in the ability lowering and declare `Default` over `Implementation[A]`, so the
  checker rejects a wrong name in the slot as an ordinary type error. Cornerstone-faithful, and **deferred**:
  only the compiler ever writes that slot, so the check would catch a compiler bug and nothing else, at the
  price of teaching the checker a typing for implementation values it has no decision to make with. The
  upgrade is contained — drop the alias body, retype the marker, retype `Default` — and nothing in the write
  or in resolution moves if it is ever taken.
- **The existing `parameterType` slot, with an alias that reduces to `Type` (chosen).** A binder already
  carries an optional declared type (the kind annotation in `type Int[MIN: BigInteger]`). The prelude declares

  ```eliot
  type Implementation[A] = Type
  ```

  in `eliot.lang.Implementation`, beside `Default`, with its FQN in `WellKnownTypes`; the desugar mints
  `Impl: Implementation[Console]`, module-qualified so the mark never depends on the file's imports. **The
  checker changes nothing**: the marker names and `Default` keep their `Type` typing and the kind check of a
  binder against what is passed is unchanged. The write reads the operator-resolved signature, where an alias
  is not yet expanded (the evaluator expands it at monomorphization), so the syntactic head `Implementation`
  is what it pattern-matches — recognition by a well-known FQN, exactly as `&` is recognised (§2.5). No case
  class changes.

**Three corrections, measured when steps 1–4 were built (2026-09-11). Do not re-propose the original readings.**

- **The alias does not reduce away for the checker; the mark is erased at `row` instead.** The plan said
  definitional equality normalises `Implementation[Console]` to `Type` and the checker therefore never sees a
  mark. It does see one, and it **kind-checks the argument** before any reduction: an ability's marker has one
  binder per ability parameter *plus* the binding slot, so its kind is `Type -> Type` and upwards, never
  `Type` — `HelloWorld` failed at its own `{Console}` with "Type mismatch. Expected: Type, Actual: Type ->
  Type". No declared kind for the alias's parameter accepts every ability, so the fix is not a better kind:
  the mark is **dropped at the end of the `row` phase**, beside the `with` nodes that phase already erases
  (`BindingWriter.Writer.unmarked` rewrites a marked binder's declared type back to `Type`). The mark
  therefore lives between `core` and `row` and nowhere else, and "the checker changes nothing" holds *by
  construction* — it is handed the signature it was handed before the mark existed — rather than by a
  reduction. Nothing else about the chosen form moves.
- **The mark's argument resolves as an ability, not as a type.** An ability name is not in the type namespace,
  so the ordinary value path reached it only through the ability fallback, missed the fixed-FQN abilities
  (`PatternMatch`/`TypeMatch`) entirely, and reported a mistyped effect as "Name not defined." instead of
  "Ability not found.". `ValueResolver.markedAbility` recognises a mark by its head's FQN and sends the
  argument through `resolveAbilityName` — the one lookup an ability name uses (§2.5) — yielding the ability's
  own marker value. One consequence is worth stating, because it reaches every pool: the mark is written into
  **every** row, `~` constraint and `ability` block, so `eliot.lang.Implementation` is now required wherever
  any of those is declared. Every layer has it; a hand-built test pool must carry it
  (`ProcessorTest.implementationStubContent`) or the declaration itself fails to resolve.

- **The prefix's diagnostic does not go with the prefix.** §9.3 step 3 said `nonPrefixPhantom` is deleted along with
  the prefix rule, the merge simply writing a binding wherever it sits. It cannot: a type-argument list has **no
  hole**. Index 1 of `ability Show[T] { def show(t: T): {Log} String }` is the block's `T`, inferred from the
  argument and spelled by nothing, so a positional write that wants index 2 must put *something* at 1. The merge
  therefore still stops at the first slot nothing fills — which is the fail-safe direction for an ordinary binder,
  and is not for a binding, since a dropped binding grounds to the platform's default with no error at all. So the
  diagnostic stays, moved and re-aimed: no longer a property of the *declaration* (where it rejected the shape
  outright), but of the **call**, raised only when that call's own arguments leave a binding out of reach
  (`BindingWriter.Writer.unreachableBinding`). What step 3 bought is what §9.1 asked for: the shape is writable now —
  `show[String](x)` reaches the binding and compiles — where before no call to it could be written at all. Deleting
  the check outright was the one thing not on offer.

The read side already is what a marked binder needs: the slot holds a *name* — a ground `Structure` headed by
the implementation's marker FQN or by `Default` — and `ImplementationBinding` reads it back for `AbilityResolver`
to use directly or to search. Nothing there moves.

### 9.3 The work list

1. **The alias and its FQN — DONE (2026-09-11).** `type Implementation[A] = Type` in
   `stdlib/eliot/src/eliot/lang/Implementation.els`; `WellKnownTypes.implementationTypeFQN`. Every stub prelude
   grew it, not only the ones that had the module: the mark is written into every declaration that takes a
   binding, so a pool without `eliot.lang.Implementation` no longer resolves (§9.2).
2. **Mint with the declared type — DONE (2026-09-11).** `EffectSugarDesugarer` for a row entry and a `~`
   constraint, `AbilityMembers` for the block's binding slot, both through the shared
   `GenericParameter.implementationMark`. `abilityLevel` keeps its other job — delimiting the ability-level
   prefix that `AbilityResolver` slices and that a member's own binders follow. Two things this step needed
   that the plan did not foresee, both in §9.2: `BindingWriter.Writer.unmarked` erases the mark at the end of
   `row`, and `ValueResolver.markedAbility` resolves its argument as an ability. Nothing reads the mark yet —
   the write still derives a phantom binder the old way — so the gate is that the mark is **inert**: 45/45
   example jars byte-identical, 1689 tests green.
3. **The write reads marks — DONE (2026-09-11).** `BindingWriter.phantoms` is "the binders whose declared type is
   headed by the marker, with the ability read off its argument", in index order. Deleted with it: `mintedPhantoms`'
   non-occurrence test, `constraintStartingWith`, `prefixOf`, `nonPrefixPhantom`, the `Qualifier.Ability` arm and
   `referencedParameters` — the non-occurrence test's only helper. The positional write is one merge
   (`mergeTypeArguments`): written names fill the marked indices, and what the call determines — its explicit
   `typeArgs`, else what a supplied row slot settles — fills the unmarked ones in order (`suppliedArguments` reads
   "the unmarked binders" where it read `drop(phantomCount)`). Type-argument application stays positional; the
   prefix goes, and with it the *declaration-level* rejection of a parameterised ability's member — but not the
   diagnostic, which moves to the call (§9.2's third correction). The gate held: 45/45 example jars
   byte-identical, every test green.
4. **Delete the dead encodings — DONE (2026-09-11).** `GenericParameter.inferable`, `ArgumentDefinition.inferable`,
   and `inferableArity` on `NamedValue`, `ResolvedValue`, `BlockDesugaredValue`, `MatchDesugaredValue` and
   `OperatorResolvedValue`, with every forward. The desugar's idempotence test ("is this binder one I minted?") is
   `GenericParameter.isBinding` — the mark read at the AST exactly as the write reads it at the operator-resolved
   signature. One fact, one place it is written down, two readers.
5. **The alias is ordinary — DONE (2026-09-12).** `RowAliasExpander` and the splice in `CoreProcessor` are gone.
   `core/processor/RowAliases` reads the file's row aliases and hands a definition naming one as its **return type**
   the alias's *entries*, the use's arguments substituted into them and the payload untouched;
   `EffectSugarDesugarer.desugar` mints and records them exactly as it does entries written out in the return
   position, through the same one code path. The alias itself lowers to the ordinary `type Git[A] = A` — its body's
   row erased like any other — and the use stays `Git[List[TagRef]]` in the signature, for the evaluator to reduce.
   Rows still flow into no type (§3.3). Return position, file-local as before; the other positions are D18 (§9.5).

   **A fourth correction, measured building this. Do not re-propose the plan's reading.** The plan had the alias mint
   its own binders (`type Git[I0: Implementation[Process], …, A] = A`) and the use rewrite its return type to
   `Git[J0, …, X]`, the `J`s dead arguments reducing away. Two measurements sank it, and both say the same thing — an
   alias is a *name for a row*, not a definition that performs one, so it has nothing to receive and nothing to be
   given:
   - **an alias's parameters are its value args, not binders.** `TypeAliasDefinition` lowers `type Git[A]` to a
     `Type`-returning function of one *argument*, so a mark minted there lands on an arrow domain, where nothing
     erases it: `BindingWriter.Writer.unmarked` erases a *binder's* declared type, and a value parameter's is not
     one — so the checker would meet `Implementation[Process]` with an ability of kind `Type -> Type` inside it,
     which is §9.2's first correction over again. Minting them as *generic* binders instead erases cleanly, but then
     the write writes the dead arguments at every use, for a reduction that was going to happen anyway.
   - **the alias cannot state its own row either.** Recording the entries on the alias's `effectRow` — the ordinary
     place a row lives — does not resolve: `resolveEffectRow` resolves an entry's arguments against the signature's
     *generic* params, and `type Fallible[E, A] = {Throw[E]} A` mentions `E`, which is a value arg. So the entries
     stay where they can be read, among the file's own declarations at `core` — which is where the splice read them
     too, and exactly why neither limit moves here (D18).

   What the step bought is the payload: a use site's return type now keeps the name the user wrote, the alias is a
   value the evaluator reduces rather than text the desugar substitutes, and **only entries cross a use site**.
6. **Tests — DONE (2026-09-12).** `RowAliasExpanderTest` became `RowAliasesTest`, and its identity claim moved
   with the mechanism: a definition naming an alias mints and declares exactly what the written-out row does — the
   abilities its binding binders mark, and its declared row, compared against the written-out source — while its
   *signature* keeps the application (`Impl -> Function(String)(Talk(Unit))`). The alias itself lowers with no
   binding binder and its body to its payload; the position and arity rejections stay, and a **type alias naming a
   row alias** joins them, which was a silent loss before. `jvm`'s `RowAliasIntegrationTest` is the end-to-end
   witness — a def naming an alias runs on the implementation the boundary binds, a parameterless alias carries
   payload and row, an alias argument reaches the entry mentioning it (`Fallible[String, String]` discharged by
   `catch`), and an effect the named row does not carry is still reported at the reference.
7. **Documents — DONE (2026-09-12).** Part I §3.1 is rewritten to the mark and its "how the write recognises a
   phantom binder today" paragraph deleted; §2.4 describes the ordinary alias and keeps its two limits, now
   attributed to where they come from; §7 item 5 and §8 item 6 say the same. The CLAUDE.md cornerstone already
   carried the mark from steps 1–4; its `row`-phase line loses "as a leading positional prefix". One thing the plan
   expected to delete stays, and that is the correction above: **the alias's two limits are not lifted by this
   step** — only by D18.

8. **The alias is an ordinary name — DONE (2026-09-12).** `core/processor/RowAliases` is gone, and with it the last
   thing about a row alias that was not ordinary. The alias now **declares** its row, on its own declaration
   (`EffectSugarDesugarer` records the body's row in its `effectRow` and mints nothing for it — an alias names a row
   rather than performing one, so it has nothing to receive). A use is the ordinary reading of a resolved name:
   `ValueResolver` reads the declaration the return type's head resolved to, resolves its entries **in the alias's
   own scope** and substitutes the use's arguments, then mints one marked binder per entry and records them — the
   same shape `superConstraints` has had all along, which is the rule that lets a name stand for a set of effects.

   **What that lifted, and why it was not a limit of the design.** The file-local limit went with the scan, and so
   did the misreadings that came with matching a name by spelling before resolution: an imported alias silently
   contributed nothing (the row vanished and the body's effect was reported undeclared at the reference, pointing
   nowhere near the cause), a *binder* named like an alias was read as a use of it (`def id[Talk](x: Talk): Talk`
   drew both an arity and a position error), and a qualified spelling of an alias in scope matched nothing. A
   written-out row and a named one also **compose** now, for free: the alias contributes to the return *position*,
   which is what a written-out row lowers to, so `{Log} Talking[Unit]` declares both and an effect named twice is
   declared once.

   Two things are no longer where they were. **Both are demand-driven now**, because `resolve` is: a misused alias
   in a definition nothing reaches is not reported, exactly as that definition's types are not checked (the use-site
   cornerstone). And the rejection is **reported from the runtime platform only** and aborts silently on the
   compiler one, the guard `RowElaborationProcessor` already makes, so one misuse is one message.

### 9.4 What does not change

The resolution order (§3.1), `with` in both positions, `Default` and the two-site search, `ImplementationBinding`'s
read, the monomorphization key, thunk-and-apply, the scope check and its diagnostic, and the per-position meaning
of a row (received at a return, supplied and thunked at a parameter, the callback's own in an arrow codomain,
bound at construction in a field). The checker gains no effect rule and no new typing. Type-argument
application stays positional. Nothing infers a binding: the mark says *which* binders are bindings, and the
resolution order still says what each is written to.

### 9.5 The alias in every position — the phase question (D18)

The alias is ordinary at a **return**: the definition naming it reads the row off that name's declaration and mints
it (§9.3 step 8). At a **parameter or field** it must first know the slot is a row slot — to thunk it and record it
as supplying — which is a rewrite of the *slot*, and `EffectSugarDesugarer` does that at `core`, where no name is
resolved yet. So the question is no longer "which phase can see the declaration" — step 8 answered that with
`resolve` — but **which of the desugar's rewrites can move there with it**:

- `EffectSugarDesugarer` sits beside the `~` lowering and the `data` split, and the split's order is load-bearing
  (A7: the `data` is split first so the constructor reaches the desugar with an ordinary parameter row).
- Minting a *return* binder moved cleanly (step 8 mints into the resolved signature); thunking a slot is a bigger
  move, because the thunk changes the parameter's **type**, which the module phase's signature merge compares.

**Decision needed before a row alias leaves return position.** Steps 1–8 are done; the file-local limit is gone and
the position one is not. D18 is now the only thing left of §9.

### 9.6 Reversals this records

Standing rule 1: a reversal is written down as one, never amended in place.

- §9.3 step 5's measurement (2026-09-11) *"the alias cannot state its own row either"* is reversed by step 8. It
  was true of the code as it stood and not of the design: `resolveEffectRow` resolved an entry's arguments against
  the signature's *generic* params, and a type alias's parameters are its **value** args, so `type Fallible[E, A] =
  {Throw[E]} A` could not name `E`. Step 8 resolves a type definition's own row with its value args in scope, which
  is what they are — they are in scope for its body, and a row it declares is written over them. The step's *other*
  measurement stands and is not reopened: an alias still mints **no** binders of its own.
- F1's rule (2026-09-09) *"phantom-binder discovery needs **no new metadata**"* is reversed. The no-metadata
  rule did not avoid metadata: it produced a count nobody reads, a re-derivation, a prefix constraint and a
  splice.
- Part I §3.1's *"minted binders are a leading prefix because `typeArgs` applies positionally"* loses its
  reason. Positional application stays; the prefix does not.
- §12's entry closing "a handler as a marker type" says the phantom binder *"occurs in no type"*. Refined, not
  reopened: it occurs in no parameter or return type of any value; it may carry a declared type that reduces
  to `Type`, which the `row` phase erases before the checker sees it. That is not the in-type binder that entry
  closed — nothing unifies it. (The plan's *other* refinement, a binder standing as a dead argument of a row
  alias, never happened: step 5's correction is that an alias has no binders to be given.)

### 9.7 The gate

§10's byte-identity gate over the 45 example jars, and every test green. It held at every step, step 5 included —
which that step's gate had to *expect*, since **no example and no stdlib file declares a row alias at all**: the
sweep can only say the change touched nothing else, and the witness for the feature itself is the pair of suites
in step 6. A shape with no example has no gate (§10), and this one has two of them: a parameterised ability member
with its own row, and the alias.

## 10. How a change is run

Kept from the v6 record, because every change to this channel is run the same way.

**The gate.** `./mill __.test` green, every example program carrying a `main` compiles, and every example jar
`md5sum`-identical to the pre-change build — `scripts/example-sweep.sh`, whose `jar.md5` lines are the gate.
Byte-identity is a **safety oracle, not a hard gate**: when the output legitimately changes wholesale it is
replaced by **behavioural identity**, the same script's `exit`/`stdout` lines compared before and after, with
the size and instruction-count difference stated in the commit. A regression there is a finding to explain,
not a cost to accept. A report is compared with its `#` header lines stripped; the cache under `target/` must
go between compiles; stdin must be `/dev/null`; and never run `./mill` while the sweep runs, or it rebuilds
`out/` underneath and the report reads as a large regression that is not there.

**What the gate cannot see.** It is a behavioural identity over the corpus the examples cover, and the corpus
does not cover the type-level surface: no example writes a guarded return type, a `foreach`, a `catch` with an
ignoring handler, or a stored computation, and four real defects hid in exactly that gap at the flag day. A
shape with no example has no gate; the jvm end-to-end suites are what separate it.

**When the question is "is this still load-bearing?"** — reuse the method rather than re-inventing it:

- **Arm-liveness tracing, not inspection.** A temporary env-gated tracer with a `fire(arm, sample)` call on
  every arm under consideration; run the whole gate *and* a compile of all examples; delete only zero-fire
  arms, at outcome granularity (a router's entry count is mostly routing).
- **Switch it off, part by part.** An env-gated bypass answers in behaviour. Gate each part **separately**: an
  all-off run once said "43 failures, delete nothing" while the per-part runs said one part costs 1 test and
  another 36, which was the whole finding.
- **An examples-only audit under-reports**, because elaboration is demand-driven: disabling *every* effect
  arm once still compiled all 45 examples to byte-identical jars.
- **A firing arm is a question, not a verdict**; **measure twice, before and after** — one deletion's first
  cut looked like an improvement while dropping a fail-safe.
- **Tracer gotchas**: mill prefixes every forwarded line with a worker id, so never anchor a grep; a sample key
  must carry the range **end** as well as the start and, for an argument, its spine **head**; delete the cache
  before every run or the pipeline replays facts and the trace comes back empty.

**Two traps in the test harness.** A `ProcessorTest` needs an `Implementation` stub declaring `type Default`
(and, after §9, the `Implementation[A]` alias) or every snippet calling an ability method loses its
monomorphization **silently, with no error at all** — the write puts `Default` at the reference as an ordinary
type argument and saturation demands the value it names. And a test stub that diverges from the real
declaration (`fold`'s suspended arms) fails silently too.

## 11. Open decisions

Numbers are kept where a cross-reference or a source comment uses them. Only rule decisions are listed;
every convenience is in §12 under "not now".

### D3 — `~` and `&` fully in user space

Still blocked on meaning, not difficulty. What is left is whether `~` and `where` unify, and the phase-order
blocker (the superability closure runs at resolve, before operators are structured) still lands first if that
is ever answered yes. Its half that asked "what does an ability denote as a value?" is answered under names by
*nothing*: an implementation is not a value, so an ability denotes no type of values.

### D18 — a row alias at a parameter or a field: which rewrites move with it

**Superseded by D20 (2026-10-05)**: once a row is a `uses` clause rather than part of a type, there is no alias in
a parameter's type to move rewrites for; what replaces the row alias is D20a. Kept as written for its citations.

§9.5. The declaration is read at `resolve` since §9.3 step 8; what is open is whether the **slot rewrites** — the
thunk and the supplying record — can move there too, and what that does to the signature the module phase merges.
Needed before a row alias leaves return position.

### D19 — an undischarged control effect at `main`: decided, and costed

**Closed 2026-10-12 by D20c option 5, as amended, by neither route below**: the platform's control defaults take a
frame as a parameter, a discharger's body supplies it, and `main` holds none, so an undischarged control effect
there is the ordinary "nothing gives this" error. The routes and their costs are kept as the record.

**Decided 2026-09-12: it must be a compile error.** What is left is not *whether* but *which of two costs*, and
that is the open half of this entry.

**The question was never a missing bit.** The compiler already hard-errors when the two-site search finds
nothing — `No ability implementation found for ability 'Terminal' with type arguments []`, at the operation call.
The reason `{Throw}` does not hit it is mundane: the jvm layer ships `implement[E] Throw[E]` **anonymously**, and
anonymous *means* "this pattern's default". So the lever is which implementations are defaults, not a new
property of effects. The earlier proposal — a marker keyword on the platform's `implement`, read at the boundary —
was the wrong first guess and is kept below only as the costed alternative.

**Route A — name the control implementations. No new surface.** Give each of the five a name
(`abortByEscape`, `throwByEscape`, `stateByCell`, `writerByCell`, `depByCell`), **abstract in the base and bodied
per platform** like the dischargers themselves, and let each discharger's slot name it:
`def runAbort[A](obj: {Abort} A with abortByEscape): Option[A]`. That is `transcriptOf`'s shape (§6) applied to
the dischargers, and it needs nothing the language does not have. An undischarged entry then reaches the boundary,
finds no default, and is the error above.

Two defects had to be closed before it could even be tried, and **both are landed** (§8 items 7 and 8): a named
implementation was answering the default search, and a slot's `with` was not part of the signature the layer merge
compares. Route A's own `.els` half is **not** landed.

**What Route A costs, measured on the 45-example sweep** (`Abort` first, end to end and running; then the other
four). 41 of 45 still compile; **4 break, in three distinct ways**:

1. **Every user-written discharger must spell the implementation.** `TestSuite`'s
   `runTest(name: String, test: {Throw[String]} Unit, rest: {} Unit): {Console} Unit` supplies `Throw` and
   `catch`es it; with no default its slot has nothing to bind, so it must be written
   `test: {Throw[String]} Unit with throwByEscape`. This is the route's standing price, and it is a language-feel
   call: a discharger stops being an ordinary function and has to name a platform implementation.
2. **A named double whose clauses perform a control effect can no longer be bound through a slot `with`.**
   `EffectsTestFramework`'s `transcriptOf(program: {Console} Unit with recordingConsole)` fails, because
   `BindingWriter.slotImplementation` writes the double's *clause-row* entries as `Default` **by design** — the
   scope that covers them is the callee's (`runWriterToLog`, inside `transcriptOf`'s body), which the write cannot
   see from the signature. With `Writer` no longer defaulted there is nothing for that `Default` to find, and the
   language has no spelling for what is wanted. The nearest is chaining the slot's `with`
   (`{Console} Unit with recordingConsole with writerByCell`), which needs `suppliedScope` to bind a `with` for an
   ability the slot's row does not supply — a small, principled extension, but an extension.
3. **An abstract `implement[W ~ Combine[W]] writerByCell: Writer[W]` in the base breaks codegen** — "Function not
   implemented." at `Combine`'s *own* ability marker, in two examples. Undiagnosed. A constrained named
   implementation is the one shape §2.5 warns about ("a named implementation … takes no parameters and closes over
   nothing"), and a `~` constraint may be precisely that.

**Route B — a declared mark on the platform's implementation.** "This implementation is only meaningful under a
frame", read where `main` binds. It needs one piece of new surface **and** a boundary-only sentinel, because
`Default` resolves to an implementation in the `ability` phase while "this came from the run boundary" is known
only in `row` (`BindingWriter` writes one `Default` under `atBoundary`). In exchange it costs **nothing** to
existing code: none of A's three items are paid. It does not reinstate the family bit §3.5 forbids — the mark is
on the *implementation*, not the effect, so a platform shipping an unmarked `Throw` (log-and-continue) would
legitimately reach `main` — but §3.5's sentence needs that refinement written down.

**What is needed.** The choice. Route A charges every discharger a name and still owes two pieces of work (the
slot-`with` clause row, and the `Combine` codegen bug); Route B charges one marker of surface and owes nothing.
The experiment's diff is deliberately not kept in the tree — this entry is enough to rebuild it.

### D20 — effects are parameters; a function you are given is called, never kept

**Decided 2026-10-05. Not built.** It changes the surface and three of Part I's rules. The mechanism of §3 —
phantom binders, the write, `with`, the primitives, the one verifier — stays. Until it lands, Part I is the tree.

#### Why

A row means something different in each position it is written, and "performs" is none of them:

| where | what `{R}` means in the tree |
| --- | --- |
| a definition's result | the implementations its caller passes it |
| a parameter | don't run the argument; let it see the caller's bindings; and *I* supply what of `R` my own row lacks |
| an arrow codomain, `A => {} B` | only "let it see the caller's bindings" — and §8 item 9 measured even that as a no-op |
| a `data` field | don't run it; the **constructor** supplies `R` by `Default`; the reader is charged `R` |

`{}` exists only to switch on the first two with nothing to supply. The user docs' own example shows the cost:
`def pick[A](left: {} A, right: {} A, flag: Bool): A` runs `printLine` and declares no effect, which a reader
taking the row as "what this performs" cannot explain. And the mixing is not only hard to explain: §8 items 9–13
are five programs it lets through.

#### The model, as a developer is told it

> **An effect is a parameter you don't spell.** `uses Console` on a definition means its caller hands it a
> `Console`, and an operation uses the one in scope *where it is written* — never where it runs. `with` binds one.
>
> **A parameter is a value or a block.** A value is computed before the call. A block — a function, or `=> A` — is
> code passed unrun: it sees everything where it was written, effects included, and the callee may call it or pass
> it on, but never keep it. `uses` on a block lists what the callee gives it, which is all a handler is.

That is the whole of it. `pick` declares nothing for the reason it does not declare `flag`: the `Console` its
arguments use is resolved in the caller, where that text is. "Performs but does not declare" is a name not in
scope; "declares but does not perform" (§4) is an unused parameter. An effect is an ability with no default: a
`~ Ord[T]` is chosen by the types and searched for, an effect is chosen by the caller and handed down.

#### The surface

The `uses` clause stands where a parameter would, before the colon, on a definition and on a parameter alike —
`name [uses …]: Type` — so no effect is written inside a type anywhere. A `where` precondition stays after the
result type.

```eliot
def greet(name: String) uses Console: Unit = printLine("Hello, " ++ name)
def main uses Console: Unit = greet("Bob")

def pick[A](left: => A, right: => A, flag: Bool): A
def fold[A](condition: Bool, whenTrue: => A, whenFalse: => A): A { join(whenTrue, whenFalse) }
def if[T](condition: Bool, value: => T) uses Abort: T = fold(condition, value, abort)

def runThrow[E, A](body uses Throw[E]: => A): Either[E, A]
def catch[E, A](computation uses Throw[E]: => A, onError: E => A): A
def map[A, B](f: A => B, list: List[A]): List[B]
infix left below apply def .[A, B](a: A, f: A => B): B = f(a)

def transcriptOf(program uses Console with recordingConsole: => Unit): String = runWriterToLog(program)

effect FileSystem {
   def readAll(path: Path) uses Throw[IoError]: String
}

implement recordingConsole: Console {
   def printLine(s: String) uses Writer[String]: Unit = tell(s ++ ";")
   def readLine: Option[String] = None
}
```

| written | meaning | lowers to |
| --- | --- | --- |
| `x: A` | a value, computed before the call | `A` |
| `f: A => B` | a block taking `A` | `A => B` |
| `x: => A` | a block taking nothing — a lazy argument | `Unit => A`, today's `{} A` |
| `x uses E: => A` | a block the callee gives `E` — a handler's slot | `Unit => A`, today's `{E} A` |
| `f uses E: A => B` | a block taking `A` that the callee gives `E` | `A => B`; no use in the corpus |

`=> A` is Scala's by-name spelling and the function arrow with its argument dropped. It is not written `Unit => A`,
because every caller would then write `_ -> …` and an argument that *is* a `Unit => A` value would be ambiguous.
`if` loses its `{Abort}` on `value`: under the model the block takes `Abort` from where it is written, so the
repeated entry said nothing, and §2.2's "an entry the callee's own row has is not supplied" stops being a rule a
user has to learn.

#### The rules

1. **Effects resolve where they are written.** An operation, or a call to a definition that `uses` an effect,
   takes the nearest binding in scope *in the text*: an enclosing `with`, the enclosing definition's own `uses`,
   or what an enclosing block parameter's `uses` gives. This is §3.1's resolution order, unchanged, stated as
   scope.
2. **A parameter is a block iff its declared type is a function type or `=> A`** — read off the declaration,
   through one level of alias expansion as §3.2 already allows. Every other parameter is a value and strict, a
   bare generic included, which is rule 4 of §1 with the predicate moved from "declares a row" to "is a block". A
   **value constructor's parameters are always values**: they are fields.
3. **A block is used, never kept.** A reference to a function-typed parameter is allowed only as the head of an
   application, as the direct argument of a block parameter of a *definition*, or inside a lambda that is itself
   so placed. Anything else keeps it — a constructor argument, a generic value slot (`List(f)`, `.`'s subject), a
   result, a `val`, a lambda in value position — and is the error *"a function parameter can be called or passed
   on, not kept"*. A `=> A` parameter needs no such check: mentioning it runs it, so it can never be kept.
4. **A lambda may use effects from around it only as a block** — written directly as the argument of a block
   parameter of a definition. In a value position (a field, a generic slot, a result, a `val`) it may use none;
   it is a value, and a value carries no effects. This is §1 rule 4's third bullet made true (§8 item 9).
5. **A definition gives its block only what it has.** Running a block parameter is checked exactly like a call to
   a definition with that signature: each entry its `uses` lists must be in scope there — the definition's own
   `uses` (it forwards what it was given), the implementation the slot names with `with`, or a further block
   parameter it passes the block on to (`catch` hands `computation` to `runThrow`). **Only a body-less
   declaration gives an effect from nothing**: that is where a frame comes from, so each platform primitive
   states what it gives — `escapeInternal[K, A, R](body uses Throw[K]: => A, …)` in `Throw`'s module, the cell
   copies `uses State[S]`, `Writer[W]`, `Dep[X]` in theirs. The consequence is the one-line rule a user sees: **an
   effect's default is handed out at `main`, and by platform primitives, nowhere else.** Since a block's binding is
   still written at the outer call from the callee's declaration (§3.1), the check also requires what the body
   gives to *be* what the declaration promised: a block declared to receive `Default` may not be passed on to a
   slot that gives `with recordingConsole`. *Restated 2026-10-12 by D20c option 5's decision 2: a body gives a
   default only by supplying its parameters, so a frame-carrying default is given by the discharger holding the
   frame, and an environment-free one still only at `main`.*
6. **A field holds a value.** `uses` and `=> A` are rejected on a `data` field. A stored computation (§1 rule 3,
   §2.3) is gone: under rule 3 a block cannot be kept, and a field is the one place everything is kept.

#### What it buys

- **Purity reads off the signature**, which today it cannot (§8 items 10 and 13 are pure signatures doing I/O). No
  `uses` and no block parameter: pure, and total — `Inf` would be a `uses` entry. Block parameters and no `uses`:
  adds no effect of its own, which is `pick`, `map`, `catch` and every handler that returns a plain value. A
  `uses` clause: performs those, received from its caller.
- **The explanation shrinks.** §1's four rules become the two paragraphs above. Gone from what a user learns:
  `{}` as an empty row that means "suspend", the row on an arrow codomain, "supplied versus rides the caller",
  rule 4 as a rule of its own (it is how closures work), stored computations charged at the read with a `with`
  there an error (§2.3, A11, §7 item 6), and the dot-read rule §8 item 2 had to add.
- **Effects never appear in a type.** "A row is declaration metadata, never a type" (§3.3) becomes true of the
  syntax as well as the mechanism.
- **One of §2.2's hand-spelled shapes should go.** A parameter reference is rejected today because "a parameter
  has no callee whose declaration could state the row"; a block parameter now has a declared `uses`, so
  `runThrow(body)` over `body uses Throw[AssertionError]: => Unit` can read `E` from it. Expected, to be measured.

#### What it costs

- **`compose`, and any helper that stores a callback it is given** (`handler(name, f) = Handler(name, f)`). The
  caller can still write `Handler(name, e -> …)` directly, with a lambda that uses no effects. No function
  parameter in eliot, eliot-test or eliot-build is kept today, so nothing in the corpus breaks.
- **A `val`-bound lambda may use no effects** (rule 4). Rare — the corpus has none — and fail-safe.
- **eliot-test names two implementations.** `mocked` gives its body `Mocking` and `Calls` through their anonymous
  defaults, which rule 5 forbids from a definition with a body. They become `implement recording: Mocking` and
  `implement journal: Calls`, bound with `with` on `mocked`'s slot alongside the five doubles already there.
- **A native that keeps a callback** — an event loop's handler table — has no spelling. Natives are axiomatic, so
  such a native is the platform's to declare correctly; when one is needed, that is the moment for §12's "not
  now" entry B (a derived *keeps* property).
- **A wide, mechanical migration**: every `.els` in eliot, eliot-test and eliot-build, the Scala test snippets, the
  user docs site, the TextMate grammar and the `eliot-code` skill.

#### The work list

Fixes first, in today's syntax, because §8 items 9–13 are defects whatever the surface; then the surface, as a
mechanical migration that §10's byte-identity gate can watch.

1. **Rules 3 and 4** — the keep check and the value-position lambda check, in the `row` phase beside the walk
   that already knows which slots are blocks. Closes §8 items 10 and 11. Gate: §10, plus the probes as tests.
   **Built 2026-10-12**, in today's syntax. `BindingWriter`'s walk carries whether each position is *kept*. A
   reference to a function-typed parameter is legal only as an application head or as the direct argument of a
   definition's function-typed parameter; a lambda in a kept position is walked **sealed**, so a block or an
   effect binding from outside it is reported. "Is a definition" excludes `data` constructors, read from
   `ModuleConstructors`, the one owner of that question. The gate held: all 45 example jars byte-identical, the
   suite green, eliot-test's 183 cases green, and eliot-build's errors identical before and after (it does not
   currently build against `master`, for reasons unrelated to this step). The probes are
   `BlockKeepingIntegrationTest`. Five findings, recorded rather than folded into the rules (standing rule 1):
   - **A suspended parameter can be kept too.** Rule 3 says a `=> A` parameter "needs no such check: mentioning
     it runs it". That holds except inside a lambda that is itself kept: `Holder(s -> body)` keeps `body`, which
     then runs after its frames are gone. The check covers it, which is rule 3's own intent ("a block is never
     kept"), but the sentence is wrong as written.
   - **`compose` is not a cost.** Functions are curried, so `compose(f, g) = a -> f(g(a))` takes three parameters
     and its result lambda is its own last one. Called in full it keeps nothing. What keeps a block is a call
     applied to fewer arguments than it takes — a function still holding them — and that is rejected where it
     stands in a kept position (`loud(f) = compose(f, …)`). "What it costs" overstates this.
   - **`match` arms are blocks, by a read-through.** `match` lowers to
     `handleCases(scrutinee, $selector -> $selector(arm₁)…(armₙ))`, so the arms reach the call as arguments of a
     lambda's parameter, which rule 4 treats as a value. In the source the arms run in place, as `if`'s do, so the
     walk reads through `WellKnownTypes.isPatternMatchHandleCases` the way `calledSpine` reads through `.`.
   - **An arm's or a lambda's result is a kept position**, even when the lambda is a block. Returning a block
     from a `match` arm is therefore rejected even when the `match` itself stands at a block slot. Fail-safe, and
     no code in the corpus does it.
   - **Only effect bindings are sealed.** A `~` constraint's binding from outside a kept lambda is still used
     there: an ability implementation is a compile-time choice with no frame to outlive.
2. **Rule 5** — the give-only-what-you-have check; the platform primitives' copies declare what they give; eliot-test
   names `recording`/`journal`. Closes item 13. Gate: §10; eliot-test's suites green.
   **Built 2026-10-12.** Each reference that runs a row-typed parameter is checked like a call, against what the
   parameter's declaration promised for every entry its slot supplies: the slot's `with` gives its implementation by
   that declaration; an entry promised as `Default` must be in scope where the block runs, from a further slot that
   supplies it; a binding in scope that names a different implementation is rejected, since the block already runs
   on the promised one. The five primitive copies now declare what they give (`escapeInternal`'s `body: {Throw[K]} A`,
   `{Abort} A`, and the cells' `{State[S]}`, `{Writer[S]}`, `{Dep[S]}`), so a discharger's chain ends at a body-less
   declaration. eliot-test's `mocked` names `recording` and `journal` (eliot-test `d2dda86`, green on `v0.7` and on
   `master`), so **eliot-test needs a release before an eliot release carrying this step**: its `v0.3` `mocked` does
   not compile under rule 5. The gate held: 45 jars byte-identical, the suite green, eliot-test 183/183, eliot-build's
   errors unchanged. The probes are `BlockGivingIntegrationTest`. Two notes:
   - **A promise is compared by implementation name**, not by the clause-row arguments written with it: a slot's
     `with` writes the double's clause rows as `Default` (§3.1) and a body's `with` writes them from its scope, so
     the full terms differ for the same implementation.
   - **A latent misreading is fixed on the way.** The names of a definition's row-typed parameters were taken from
     the *end* of the body's leading lambdas, which misnames them when the body returns a lambda. Every reader now
     takes them from the front, past the generic binders. No jar moved.
3. **Rule 6** — reject a row on a field, and delete the stored-read machinery it leaves without a subject
   (`chargeStored`, A11's `byWith` read rule, `calledSpine`'s dot-read view if nothing else reads it,
   `StoredComputationIntegrationTest`). Closes item 12. No example stores a computation, so the jars do not move.
   **Built 2026-10-12.** A top-level row on a field — `{}` included — is a core error beside the pinned-row one
   (`EffectSugarDesugarer.rowErrors`); a function-typed field stays legal, its lambda being a value under rule 4.
   Deleted with their subject: `EffectRow.returnThunkEffects`, the accessor's and `handleCases`'s stored-field
   lowering, `BindingWriter`'s `runStored`/`storedRow`/`chargeStored`/`calledSpine` and `Binding.byWith`,
   `StoredComputationIntegrationTest`, three `TerminationIntegrationTest` cases and the stored-field groups of
   `CoreProcessorTest`/`OperatorResolverProcessorTest`. Nothing outside this repository stored one: eliot-test's
   `TestCase` no longer does and eliot-build has none. The gate held: 45 jars byte-identical, the suite green,
   eliot-test 183/183, eliot-build's errors unchanged. Part I (§1 rule 3, §2.3, A11, §7 item 6) still describes
   the old rule and is rewritten in step 7.
4. **The parser accepts the new surface** — `uses` on a definition and a parameter, `=> A` in parameter position —
   onto the *existing* `EffectRow` metadata, so no phase past `ast` learns anything. `A => {} B` and `A => B` are
   one shape (§8 item 9), so dropping the codomain `{}` must be byte-identical; that is this step's gate.
5. **Migrate**, by script, every file listed under the costs. Gate: byte-identity over the 45 examples.
6. **Delete the old surface**: a row in a result or a parameter type is a parse error naming the `uses` spelling.
7. **Documents**: Part I rewritten to the model above, the CLAUDE.md cornerstone, the user docs' effect chapters
   and the `eliot-code` skill.

#### Interactions

- **D18 is superseded.** Its question — moving a row alias's slot rewrites to `resolve` so an alias can stand at a
  parameter — has no subject once a row is a clause rather than part of a type. What replaces the row alias is
  D20a below.
- **D19 is independent but touches the same slots.** Route A names the control implementations on the
  dischargers' slots; rule 5 has the *primitives* state what they give. Whichever route D19 takes, it should be
  written against D20's surface, and rule 5's "only a body-less declaration gives from nothing" may make Route B's
  boundary sentinel smaller. Not measured.

#### Open within D20

- **D20a — naming a set of effects.** `type Git[A] = {Process, FileSystem, Throw[IoError], Throw[GitError]} A`
  has no form once a row is not in a type. The natural replacement is a name used in the clause, `uses Git`.
  Being declaration metadata rather than a type, it may not need the `ast.fact.Expression` case §12 warns about;
  its declaration's spelling is undecided.
- **D20b — a dual-parse window or a flag day** for steps 4–6. A window lets the migration land repository by
  repository; a flag day keeps one spelling in the tree at every commit.
- **D20c — an open block, handled where it runs.** Opened 2026-10-11 and spiked the same day; below.

#### D20c — an open block: a stored computation whose effects are handled where it runs

**Opened 2026-10-11. Open; the decision is Robert's.** It does not block D20: rule 3 is what turns the spike's
silent program into a compile error, and whatever D20c decides is a relaxation of rule 3 afterwards, as "not now"
entry B is.

**The question.** Declaring an algebra as an `effect`, writing code against it in direct style, storing that code
and interpreting it later under a handler chosen *then* is one of the main things an effect system is for: hooks
run at the end of a process, a job queue, an event loop's handler table. Under D20 it has no spelling. A block
cannot be kept (rule 3) and a lambda in a value position may use no effects (rule 4), so the only route left is
to declare a `data` type mirroring the algebra and an interpreter over it. That is a free monad written by hand,
with the effect system playing no part.

**Two kinds of stored computation, and D20 only examined one.**

- **Closed**: its effects were bound where it was written. That is §2.3 today, and what rules 3 and 6 remove. It
  is unsound for a control effect: the frame it was bound under may be gone, or replaced, when it runs.
- **Open**: its effects are bound by whoever runs it. Here the frame problem does not arise, because the runner
  installs the frame at the moment it runs the code.

§12's rejection of "effects on function types" argues against the closed kind only: "the binding is fixed where
the lambda is written while the effect is charged where it runs". The open kind was never weighed, so this is a
gap in D20, not a consequence of its argument.

**Compiling it is the obstacle, not soundness.** An implementation is a compile-time name, resolved by
monomorphization and never passed at runtime. A stored open block must be compiled against the implementation it
will meet at a run site somewhere else in the program. Four ways to bridge that; the first three were written up
when the entry opened, the fourth was proposed the same day and is set out after the spike:

1. **The binding travels in the container's type** (`Hooks[I]`). A registration site gets `I` from its caller as
   every phantom binder does, so monomorphization is unchanged and nothing new exists at runtime. The hoped-for
   soundness argument was `runST`'s: a discharger's own binding is local to its slot, so its result type cannot
   mention it, and a hook bound to a dying frame cannot escape. **The spike refutes that argument as stated**
   (below).
2. **The implementation is passed when the block runs.** This is the open semantics exactly, and it is sound against
   frames by construction. Implementations then become runtime values inside structures, which is the 2026-09-06
   "an implementation is a value" draft that was reversed the same day. The step-6 spike measured that storing a
   polymorphic value needs six operations the compiler does not have. The D20c spike did not touch this option.
3. **The compiler reifies the block**: `reify(body uses Cleanup: => A): Program[Cleanup, A]`, the free monad built
   by the compiler from direct-style code. Its continuations use no effects, so D20 already lets them be stored,
   and `interpret(program) uses Cleanup: A` runs it under the caller's binding. It needs continuation capture (a
   CPS transform or a platform primitive), a recursive `Program` (blocked like `docs/recursive-data.md`) and a
   native loop to interpret it. A partial form works under D20 today. An algebra whose operations return nothing
   the code branches on can be recorded at registration by a named implementation that `tell`s each operation as
   data, then interpreted at the end. It stops working at the first branch on an operation's result, since that
   branch needs the continuation.

**The spike (2026-10-11, on `97a6352c`).** It uses today's tree, where §8 item 10's hole still lets a function
parameter be kept, so a kept hook stands in for option 1's stored block. `capture(f: String => {} Unit): Holder =
Holder(f)` keeps the block, and `fire(h: Holder): Unit = run(h)("…")` runs it from a definition that declares
nothing.

- **`Throw`.** `armed: {Throw[String]} Holder = capture(s -> raise(s))`. Fired inside the `catch` it was armed
  under, the live frame catches it. Armed under one `catch` and fired inside a *different*, unrelated
  `catch[String, String]`, **the unrelated frame catches it**, silently. Fired with no frame at all, it is the
  bare `RuntimeException` from `Throw.exitInternal` that item 10 records. All three are one specialisation, a
  single `armed$Default` in the jar. The binding written is `Default` every time, because `Throw` has one
  implementation per platform.
- **An interpretation effect through a named implementation.** `register: {Console} Holder` captures
  `s -> printLine(s)` under `with recordingConsole`, whose clause declares `{Writer[String]}`, inside
  `runWriterToPair`. Fired later inside an unrelated `runWriterToLog`, the hook's output **lands in the unrelated
  log** (`unrelated-own;late;`), exit 0, no diagnostic. The binding the hook carries, `recordingConsole`, is the
  same name at registration and at run, so option 1's type comparison passes, and the program still writes into
  the wrong frame.

**What that settles.** A binding names an *implementation*, never a *frame*. A control effect has one
implementation per platform, so every frame of `Throw[String]` shares one name and one specialisation. The frame
an operation reaches is decided on the machine stack, inside the primitive (§3.4), where no type sees it. An
interpretation effect is no escape: a named implementation closes over nothing, but its clauses' control effects
are bound where the `with` stands, so the frame is reached through the implementation.

For option 1 to be sound, a frame must have a name of its own. Each discharger call would bind a fresh region
binder that its result type may not mention, and the region would follow a binding through every implementation's
clause rows. Monomorphization can mint a region per call site at most, not per dynamic frame, and whether that is
enough has not been measured. A site run twice, as inside a fold, installs two frames under one region. The
subcase that is already sound is a block whose bindings reach no frame at all, for example the platform's
`Console`. But the control/interpretation split is not a bit in the language (§3.5), so that criterion would have
to be *derived* (reaches an escape or cell primitive), the way "not now" entry B derives *keeps*.

**Option 4 — an open field is a finite product, selected at the read (proposed 2026-10-11, not decided).** The
options above all look for a way to carry *one* binding into the stored block. The fourth observes that there is
nothing to carry: the whole program is monomorphised from `main`, so for any effect the compiler knows **every
implementation that exists** — the anonymous default and each named one. A universally quantified binder over a
finite domain is a finite product, and the domain is finite because the world is closed. Koka passes evidence at
runtime because it cannot know this; Eliot never has to.

*The surface.* One spelling, D20's own, in one more position: a `data` field may carry a clause,
`data Hook(run uses Cleanup: => Unit)`. `uses` on any slot says what the *holder* of the slot gives it; a block
additionally sees its writer's scope, a value sees nothing else, and which of the two a slot is follows from its
position (D20 rule 2), so the user learns no third kind of parameter. The body written at the constructor is
checked exactly as a `def` with that clause — it may use `Cleanup`, captures values from around it, and captures
no effect from around it. Reading the field is calling such a `def`: `Cleanup` is charged and bound **at the read**,
by whoever runs it, which is what "open" means. The construction site is irrelevant to interpretation.

```eliot
effect Cleanup {
   def remove(path: Path): Unit
}

data Hook(run uses Cleanup: => Unit)

def hookFor(path: Path): Hook = Hook(remove(path))
def runAll(hooks: List[Hook]) uses Cleanup: Unit = hooks.foreach(h -> h.run)

implement dryRun: Cleanup {
   def remove(path: Path) uses Console: Unit = printLine("would remove " ++ show(path))
}

def main uses Console, FileSystem: Unit = runAll(hooks) with dryRun
```

`hookFor` declares nothing. A test binds `with recordingCleanup` on `runAll`, and that implementation's own
clause row is charged at that `with`, as every `with` charges a clause row today. The same shape serves a control
effect — `data Task[E](step uses Throw[E]: => String)`, run as `runThrow(task.step)` — and the frame is installed
around the run, so a `raise` reaches a live frame by construction. Neither of the spike's two silent programs
can be written: a stored body cannot capture the binding either one misfired through. A job queue, an at-exit
list and an event handler table are all this shape, and so is what a Java programmer calls a `Runnable` field.

*The mechanism, and what it leaves untouched.* The desugar lifts the field's body to a synthesised named
definition carrying the field's clause as an ordinary phantom binder, so polymorphism stays where the step-6
spike says it must live, on a named signature. The constructor instantiates that definition once per
implementation of the effect in the pool and stores the tuple; the accessor becomes a definition with the same
`uses` clause whose body selects the component by a compile-time `TypeMatch` on its binder, which reduces away
before codegen. The write walk, the scope check, the two-site default and the mono key are untouched, nothing
new exists at runtime, and the checker still holds no effect rule. The pool-wide implementation set is a fact
the two-site search already reads. The monomorphiser is already a compile-time interpreter of handlers; this
hands it a lambda instead of a `def`.

*Costs.* **Code size**: a field using two interpretation effects with two implementations each is four
specialisations of one body. A production build usually has one implementation per interpretation effect, so the
product is one closure; a test module adds a factor; it is bounded by what the program declares and is never a
runtime cost. **No effect-polymorphic stored value**: a `Hook` names its exact effects, there are no row
variables, and blocks stay the only effect-transparent thing — and they are never stored. **A native that keeps a
callback** runs it at the platform default, as an axiom of that native's declaration, which is D20's own answer
for natives. **The "declares but does not perform" check (§4) must not fire on a field's clause**: a no-op hook
is legitimate, and a field's clause is a contract like an ability member's, not a derived fact about a body.
**The product is part of the data type's representation**, so adding a named implementation anywhere changes an
open field's layout — fine in a whole-program compiler, and it enters the cache identity as every pool fact does.

*What it reverses, named so it is signed off rather than amended in place (standing rule 1).* D20 rule 6's
rejection of `uses` and `=> A` on a field is reversed; "a field holds a value" stays literally true, since an open
computation *is* a value, and the old §2.3 row-typed field returns with the **opposite** binding site — run, not
construction. D20 rule 4 narrows from "a lambda in a value position may use none" to "may use only what its own
declaration gives it; nothing ambient". §12's rejection of "effects on function types" is not touched: the clause
sits on a field declaration, D20's own metadata slot, and the stored thing is nominal, so nothing enters the type
language. Options 1–3 are all avoided: no region binder, no implementation as a runtime value, no reified
continuation.

*Where to measure first.* How a named implementation's clause row reaches the lifted definition's instantiation
(today a `with` site writes it; here the binding arrives ground through the accessor's binder), and the §4 check's
exemption for a field's clause. Both are in `row/`, and the §10 gate applies: no example stores a computation, so
the jars must not move.

**A second property, recorded here because option 4's proposal found it and nothing else uses it yet.** Because
the value-reference graph is acyclic, **no call site is ever dynamically nested inside itself**: a frame installed
at a site is the only live frame of that site at any moment. The one caveat is a native that re-enters a callback
while an earlier invocation is live, which strict folds never do and a lazy paramorphism would — a property such a
native would have to declare. This is what the spike called "unmeasured" for option 1's regions, and it is
decidable from the recursion gate alone: a discharger's frame can be a type-level value minted per call site, and
every `raise` then has one static target. On the JVM that buys nothing. On a microcontroller it is `Throw` as a
status flag and a jump with no exception machinery, and `State` threaded as parameters with no static cell, both
derived by the backend from frames that are mono-key components; it would also make D19 free, since an
undischarged control effect at `main` has no frame site to bind. It is independent of option 4 and not worth
paying for until a non-JVM backend exists. **Refuted 2026-10-12** by the tunnelling probe below: a higher-order
argument nests a site inside itself with no cycle in the reference graph, so the property does not hold and is kept
only so its citations resolve.

**The tunnelling probes (2026-10-12, on `9c7a31c9`).** The spike's conclusion — a binding names an implementation,
never a frame — does not need a stored computation. Two programs that keep nothing, and are therefore legal under
every rule of D20, misbehave on today's tree. They are §8 items 14 and 15:

```eliot
def guarded(body: {} Unit): {Console} Unit =
   catch[String, Unit]({ body; printLine("helper: body finished") }, e -> printLine("helper caught: " ++ e))

def main: {Console} Unit =
   catch[String, Unit]({ guarded(raise("boom")); printLine("caller: after guarded") },
                       e -> printLine("caller caught: " ++ e))
-- prints "helper caught: boom", then "caller: after guarded"

def counted(body: {} Unit): {Console} Unit = printLine("helper's state: " ++ runStateToFinalState("helper", body))
def main: {Console} Unit =
   printLine("caller's state: " ++ runStateToFinalState("caller", counted(putState("written by caller"))))
-- prints "helper's state: written by caller", then "caller's state: caller"
```

The `raise` and the `putState` are written inside the caller's discharger, and the binding the write gives them is
the right one; the frame the primitive reaches at runtime is the nearest of the same instantiation (§3.4), which is
the helper's. This is *accidental handling*: a library's private use of a control effect intercepts the effects of
every block it runs. D20 rule 1 states the right semantics and the runtime does not implement it. D20 does not
close it, since running a block inside a frame the callee installed is D20's normal case, not an edge.

The same probe refutes the second property above. Both `Throw` frames are installed by the one `runThrow` call in
`catch`'s body, at the one specialisation `catch[String, Unit]`, one live inside the other; `F(b -> F(_ -> b))` does
the same with a single definition whose block parameter calls it again. **No compile-time identity — a site, a
specialisation, a mono-key component — can tell two live frames apart.** The identity has to exist at runtime, and
the question is only where it travels.

**Option 5 — a frame is a parameter too (proposed 2026-10-12; adopted as amended the same day).** D20 says an effect
is a parameter you don't spell. Option 5 takes that literally for the half of an implementation that is a runtime
fact. It is needed for items 14 and 15 whether or not Eliot ever stores an open computation, and it is what makes
option 4 sound for a control effect.

*The model.* An implementation has two halves: its **code**, a compile-time name exactly as today — the phantom
binder, the write, the mono key and erasure are untouched — and its **environment**, a runtime value. The hidden
parameter a `uses X` definition receives is the environment of the implementation bound to `X`. Whether there is
one is decided per binding, which monomorphisation already specialises on, so nothing marks an effect, and the
control/interpretation split stays the description §3.5 says it is: an effect needs a runtime handle because of
how its implementation is written, not because of what the effect is.

| binding | environment |
| --- | --- |
| the platform's `Console` | none — erased, nothing is passed, the same cost as today |
| `Throw[E]`, `Abort` | the escape frame |
| `State[S]`, `Writer[W]` | the cell |
| `Dep[X]` | the value itself; no primitive at all |
| `recordingConsole` | the `Writer` environment its clause row uses, captured at the `with` that binds it |

*The surface.* An environment is declared as **implementation parameters**, and a `with` in body position supplies
them; a `with` on a slot's type stays argument-free. Frames are created by the primitives only, and their types are
private to the platform layer, so a user never sees one — they write `uses Throw[E]` as before.

```eliot
-- jvm layer; EscapeFrame and Cell are private types
implement[E] throwing(frame: EscapeFrame[E]): Throw[E] {
   def raise[A](err: E): A = exitInternal(frame, err)
}

def runThrow[E, A](obj uses Throw[E]: => A): Either[E, A] =
   escapeInternal(frame -> obj with throwing(frame), err -> Left(err), a -> Right(a))

implement[S] celled(cell: Cell[S]): State[S] {
   def state: S = readCell(cell)
   def putState(s: S): Unit = writeCell(cell, s)
}

implement[X] given(x: X): Dep[X] {
   def dep: X = x
}

-- a test module
implement scripted(lines: List[String]): Console uses State[List[String]] { … }
```

`raise` exits to the frame it was handed and never to the nearest of its type. On the JVM the exception carries the
frame object and a handler rethrows any exception that is not its own; a cell is a box in the frame rather than a
static field per instantiation. On a microcontroller a frame is a pointer to a jump buffer or a stack slot, which is
C's `setjmp`/`longjmp`. On the compile track the evaluator's intrinsics carry the same identities.

*Abilities too, and the line is named versus default — no keyword (2026-10-12; its "never a default" is amended
below, under "Option 5, amended").* Nothing in the mechanism is
effect-specific. A `~ Ord[T]` constraint is the same phantom binder as a `uses Console` entry, so a named ability
implementation may take parameters, and the definitions specialised at that binding receive its environment exactly
as above:

```eliot
implement byKey[T](key: T => Int): Ord[T] {
   def compare(a: T, b: T): Ordering = compare(key(a), key(b))
}

people.sort with byKey(p -> p.age)
```

One implementation replaces one named implementation per key. What cannot take parameters is a **default**, of an
ability and an effect alike: a default is chosen where nobody holds arguments — the two-site search, or `main` — so
*parameters require a name, and a named implementation is never a default*. The grammar already says so, because
parameters attach to the name and an anonymous `implement Console { … }` has nowhere to write them; it is the
marker that separates `def f` from `def f(x)`. A keyword for implementations that take parameters (a "framed" or
"stateful" mark, or Kotlin's `object` versus `class`) was considered and declined. It would mark a property nothing
reads: a frame is an ordinary value of a platform-private type, so `throwing(frame: EscapeFrame[E])` and
`byKey(key: T => Int)` are one construct differing only in a parameter's type, and a keyword over that difference
would reinstate the control/interpretation bit §3.5 keeps out of the language. A reader sees what matters where it
matters: the parameter list at the declaration, the arguments at the `with` (`with scripted(List("Bob"))` versus
`with reverseOrd`), and nothing in a definition's `uses` — whether an environment flows is lowering, as how a
`Console` prints is.

*The lowering.* A definition that declares an entry whose implementation has an environment takes it as a hidden
parameter and passes it on to the declaring definitions it calls. A definition that does not declare it — `guarded`,
`map`, `foreach`, every pure body — receives nothing: a block reaches its frame by ordinary closure capture, as it
captures a local `val`. That is why `guarded`'s own `catch` cannot see the caller's `raise`: the block holds the
caller's frame, and `guarded` was never handed it. The `unit` a suspended slot's thunk is applied to today (§3.1) is
where the environment goes instead. The cost is one pointer per call between declaring definitions, and the
per-instantiation static cells are gone.

*A worked example: where a `State` is seeded, and where an implementation is invoked.* An implementation is invoked
in exactly one place, the `with` that binds it. `with celled(cell)` names the code at compile time — every `state`
and `putState` the block runs is compiled against `celled`'s methods — and builds the environment at runtime, here
just `cell`. No implementation object with methods exists at runtime; the methods are ordinary functions taking the
environment. The seed is the discharger's: it hands `initial` to the cell primitive, which makes a fresh cell. There
is no global seed, and every discharge has its own cell.

```eliot
-- user code
def increment uses State[Int]: Unit = putState(state + 1)
def double uses State[Int]: Unit = putState(state * 2)

def main uses Console: Unit =
   printLine(show(runStateToFinalState(5, {
      increment
      double
   })))                                                                   -- prints 12

-- jvm layer: the only place a cell is made and the only place `celled` is invoked
private type Cell[S]                                                      -- a mutable box
private def withCellInternal[S, R](initial: S, body: Cell[S] => R): R    -- makes the cell holding `initial`
private def readCell[S](cell: Cell[S]): S
private def writeCell[S](cell: Cell[S], s: S): Unit

implement[S] celled(cell: Cell[S]): State[S] {
   def state: S = readCell(cell)
   def putState(s: S): Unit = writeCell(cell, s)
}

def runStateToPair[S, A](initial: S, p uses State[S]: => A): Pair[A, S] =
   withCellInternal(initial, cell -> {
      val a = p with celled(cell)                                         -- the invocation
      Pair(a, readCell(cell))
   })

-- base layer, unchanged: passes the block on
def runStateToFinalState[S, A](initial: S, p uses State[S]: => A): S = second(runStateToPair(initial, p))
```

After the row phase and monomorphisation, in pseudo-code:

```
celled$state(cell)          = readCell(cell)
celled$putState(cell, s)    = writeCell(cell, s)

increment$celled(env)       = celled$putState(env, celled$state(env) + 1)
double$celled(env)          = celled$putState(env, celled$state(env) * 2)

runStateToPair(initial, p)  = withCellInternal(initial, cell -> {
                                 val a = p(cell)                  -- the block receives the environment
                                 Pair(a, readCell(cell))
                              })
runStateToFinalState(i, p)  = second(runStateToPair(i, p))

main()                      = printLine(show(runStateToFinalState(5, env -> {
                                 increment$celled(env)
                                 double$celled(env)
                              })))
```

Two routes carry an environment, and they are complementary. **A block receives what its slot gives, as an
argument**: `p uses State[S]` lowers to `Env => A` where today it is `Unit => A`, and the discharger calls `p(cell)`
where today it calls `p(unit)`. **A definition that declares the entry receives it as a hidden parameter**:
`increment` and `double` take `env` and are specialised at the binding `celled`. Anything else a block uses it
captured where it was written. That is §8 item 15 resolved: `counted`'s own `runStateToFinalState("helper", body)`
makes a second cell and passes it to `body`, but `body` was written in `main` with its `putState` already bound to
`main`'s `env`, which it captured. The helper's cell is a different value, and `body` never reads it.

On the JVM `withCellInternal` is `body.apply(new Cell(initial))`. On a microcontroller the cell is a slot in
`withCellInternal`'s own stack frame, with no heap allocation and no way to outlive the call, since nothing can keep
it. The other two kinds of invocation follow the same pattern. A platform default such as `Console` is invoked
implicitly at `main` with no arguments; its environment is empty and erased. A double is invoked by the user at the
`with`, as in `with scripted(List("Bob"))`, and its environment is its parameters.

*Why it is sound.* Three properties, each structural:

- **The right frame.** An operation reaches the frame it was handed, so it reaches the one its discharger
  installed — the one D20 rule 1 names.
- **A live frame.** An environment travels only as a hidden `uses` parameter or inside a block, a block cannot be
  kept (D20 rule 3), and kept code gets its environments from the code running it (option 4). So no environment
  outlives the discharger that created it.
- **No interception.** A library sees only the frames it installed itself; an intervening discharger of the same
  type is invisible to an operation it did not give.

*What it settles.*

- **§8 items 14 and 15**, by construction.
- **D19.** An implementation with parameters cannot be a default — at `main` nobody holds its arguments — so the
  platform's control implementations stop being anonymous, and an undischarged `Throw` at `main` is the ordinary
  "nothing gives this" error. This is Route A with the names parameterised and written in the dischargers'
  *bodies* rather than their slots. Route A's first cost, a user-written discharger spelling the implementation,
  is expected not to arise: under D20 rule 5 such a discharger passes its block on to `catch` or `runThrow`, whose
  body binds it. **Wrong — see "Option 5, amended" below**: the block is written from the discharger's
  *declaration*, and the name is only in a body. Route A's second cost — a double whose clauses perform a control
  effect — is option 5's own first measurement below.
- **Stateful doubles.** `greet with scripted(List("Bob"))` lifts "a named implementation takes no parameters and
  closes over nothing".
- **Parameterised ability implementations.** `sort with byKey(p -> p.age)`, by the same mechanism.
- **Fibers, later.** A frame is an ordinary value, so a scheduler can hold a fiber's environments and resume it under
  them; a static cell could never be per fiber.

*Why it is not §12's "implementation as a runtime value".* That stored *polymorphic* operations in a record, which
the checker cannot represent (the step-6 measurement). Here the operations stay compile-time methods on named
signatures — polymorphism lives where `Checker.appendTypeArgs` says it must — and only a monomorphic environment
exists at runtime: the class's code versus an instance's fields. It is not "a runtime handler stack" either: it
removes the one dynamic lookup that exists today, the nearest-frame search inside the primitive.

*Three primitives remain, and the cell is the only storage.* With environments, everything that only *remembers*
is a library over `State`, and a double that records calls asks for `State` in its clauses as `recordingConsole`
asks for `Writer` today. Leaving early and running forever are not storage, so they stay primitives:

| capability | primitive | built on it |
| --- | --- | --- |
| remember a value | cell | `State`, `Writer`, counters, recording doubles |
| leave early | escape | `Throw`, `Abort`, `catch`, `else` |
| run forever | loop | `Inf` |
| read a value | none | `Dep` — an implementation parameter |

`Throw` cannot be written over `State`: `raise` could set a flag, but control would return and the caller carry on,
so every caller would have to test the flag after every call — a rewrite of every call site, which is the carrier
again, not a library. Option 5 makes one cheaper variant available to a *backend*: only calls to definitions that
declare the entry need the test, which a target without `longjmp` may use. That is code generation, not a fourth
way for a library to leave early.

**Option 5, amended: the slot chooses the code, whoever gives it supplies the environment (proposed and decided
2026-10-12).** As written, option 5 cannot be built: it moves the code half of the control implementations
somewhere the write never reads.

*The gap.* A block is not a named definition, so it gets no specialisation of its own: `{ increment; double }` is
compiled as part of `main`'s, and the binding in `increment[Impl]` is written by the walk over `main`'s body. For a
reference inside a slot's argument, that walk takes the binding from the slot's declaration (§3.1 step 3,
`BindingWriter.suppliedScope`): the slot's `with`, or `Default`. Today that is `Default`, and the two-site search
answers it with the anonymous `implement[S] State[S]`. Option 5 makes the implementation `celled`, never a default,
and writes it only in `runStateToPair`'s body, two calls away:

```
main  ──block──▶  runStateToFinalState(p)  ──p──▶  runStateToPair(p)  ⟶  p with celled(cell)
```

No declaration that `main`'s walk may read names `celled`, so there is nothing to write. Monomorphization cannot
recover it either. Following the lambda through two bodies to the `with` decides a binding from callee bodies (§3.2,
standing rule 3). Specialising the block inside `runStateToPair` instead needs a polymorphic function value, which
the step-6 spike measured the checker cannot hold. The D19 bullet under "What it settles" fails the same way: a
caller of `runTest(test uses Throw[String]: => Unit)` writes its block from `runTest`'s declaration, and `throwing`
appears only in `catch`'s body. Route A's first cost therefore returns in full: every discharger names a platform
implementation, and the base needs public `Cell`/`EscapeFrame` types to declare one.

*The rule.* Option 5 takes an implementation's code and its runtime part from the same place. The amendment takes
them from two:

- **The code comes from the declaration**, as today. A slot that names nothing gives the **default**, which for a
  control effect is the platform's one implementation. `main`'s block is written `increment[Default]`, so every
  binding is still known before monomorphization.
- **The environment comes from whoever gives the binding.** A discharger's body supplies a frame, a `with`
  supplies its arguments, and `main` supplies nothing. A default that needs an environment is therefore reachable
  only through a slot whose body supplies it. At `main`, and at an ability's two-site search, nothing holds its
  arguments, and it is the "nothing gives this" error. That is D19's error with no further mechanism.

```eliot
-- jvm layer: the default State; its environment is a cell
implement[S] State[S](cell: Cell[S]) {
   def state: S = readCell(cell)
   def putState(s: S): Unit = writeCell(cell, s)
}

def runStateToPair[S, A](initial: S, p uses State[S]: => A): Pair[A, S] =
   withCellInternal(initial, cell -> Pair(p with State(cell), readCell(cell)))
```

`p with State(cell)` chooses no code. It supplies the runtime part of the default the declaration already promised,
and D20 rule 5's check is unchanged: a body giving `p` any other implementation is an error. The spelling (an
anonymous implementation's parameters after its pattern, supplied by naming the ability) is a placeholder. In the
worked example above, read `celled` as this default; the lowered pseudo-code is the same with `increment$celled`
renamed `increment$Default`.

*What a developer sees.* A handler's slot says which effect it gives, and the platform decides how. A name is
written only to substitute an implementation (`with recordingConsole`, `sort with byKey(p -> p.age)`), which is what
`with` already means: a name is a choice, and no name is the default. `runStateToFinalState(5, { increment; double })`
and `def runTest(test uses Throw[String]: => Unit) uses Console: Unit = catch(test, …)` name nothing, and no
implementation of a control effect has a name anywhere.

*What it keeps.* §8 items 14 and 15 stay closed, because the frame is still passed and never searched for. D19
stays settled, as "the default for `Throw` needs a frame, and nothing here gives one". Parameterised named
implementations stay, for doubles and for abilities, because their `with` stands in the body whose references it
binds, where the walk sees it.

*What it reverses within option 5.* Option 5 is a proposal, but the reversal is recorded rather than edited in, per
standing rule 1. It reverses "parameters require a name, and a named implementation is never a default" in the
paragraph on abilities, and "named, parameterised and never a default" in the reversals list below. A default may
now have an environment, and a default and a named implementation differ only in who chooses them: the declaration,
or a `with`. The anonymous `implement[E] Throw[E]` and its four siblings stay anonymous and gain parameters, so that
paragraph's "an anonymous `implement` has nowhere to write them" goes with it. Its rejection of a keyword stands.

**Decided 2026-10-12 (Robert): option 5, as amended, is adopted. Not built.** Three decisions:

1. **Adopted, with its reversals**: those under "What option 5 reverses" below, as amended, and those under "What it
   reverses within option 5" above. It closes **D19** by neither of its routes: an undischarged control effect at
   `main` is an error because the platform's default needs a frame and `main` holds none.
2. **Who gives a default: D20 rule 5 restated.** *A body gives a default only by supplying its parameters.* An
   environment-free default, such as the platform's `Console`, has nothing to supply, so only `main` gives it, and
   `launder(body uses Console: => A) = body` stays an error (§8 item 13). A default that takes parameters is given
   by the body that supplies them, which for the control effects is the platform's dischargers, holding a frame
   from a primitive. The rule is judged on the merged body, never on a body-less signature in another layer, which
   a module could otherwise satisfy by splitting one definition across two files. **User code may declare a
   default with parameters.** `implement Config(s: Settings) { … }`, given by
   `def withConfig[A](s: Settings, body uses Config: => A): A = body with Config(s)`, is a user-written discharger.
   It is sound because the implementation's clause rows are charged where it is given.
3. **Arguments are passed at the `with`, with no other syntax.** One construct chooses code and supplies arguments:
   `with scripted(List("Bob"))` names a substitute and supplies its arguments, and `p with State(cell)` names the
   default (by its ability, since an anonymous implementation has no other name) and supplies its arguments. A
   default named at a `with` chooses nothing the declaration had not already chosen, and rule 5's check that a body
   gives what its declaration promised is unchanged.

**Option 4, corrected: specialise at the read, not at the constructor.** Option 4's user model stands, and it
unifies more than its proposal says. *Kept code gets its effects only from whoever runs it* already describes a named
implementation, whose clause rows are charged at the `with`, and it covers D20 rule 4 too: a lambda in a value
position is kept code, and a plain function type declares nothing, so it may use nothing. An open field is the same
rule with captured values. Its mechanism, though, has a hole: in `hookFor`, `Hook(remove(path))` under `dryRun`
calls a clause that `uses Console`, and that `Console` is bound at the `with dryRun` in `main`. The constructor
cannot build the `dryRun` component from the pool's implementation *declarations*; it would need every chain of
clause bindings, which multiplies with depth and is unbounded if an implementation may use its own effect. The read
is the only place the ground binding exists, which is the use-site verification cornerstone. So the specialisations
are the ones the field is *read* under: a stored value is one closure with one entry point per binding the program
reads it at — one in production, one more in a test build. That set decides what code is **generated**, the way
`used` does, and never decides a binding; whether standing rule 3's "sum over a monomorphized sub-graph" reaches it
is a reading for Robert to confirm. With option 5, the control half of an open field (`Task[E](step uses Throw[E]:
=> String)`) is simply a closure taking an environment, and only interpretation effects need specialising.

**Landin's knot comes back with open fields, and positivity is what must stop it.**

```eliot
data Knot(run uses State[Knot]: => Unit)

runStateToValue(Knot(state.run), state.run)   -- reads itself and runs itself, forever, with no recursion
```

The stored body references `state` and an accessor, never itself, so the recursion gate sees no cycle. After
lowering, the field takes the `State[Knot]` environment as an argument, which puts `Knot` in a negative position.
So **`StrictPositivityChecker` must count a field's `uses` entries as negative positions**; without it, option 4 makes
"no recursion" false. This binds option 4 whether or not option 5 is taken — today's static cell would loop the same
way.

*What option 5 reverses, named so it is signed off rather than amended in place (standing rule 1).*

- **§3.4's** "the frame an operation reaches is the nearest enclosing one of its instantiation" and its jvm column's
  "a static field per instantiation".
- **§2's** "a named implementation … takes no parameters and closes over nothing"; with it, §12's record entry stops
  listing "a parameterised `implement name(params)`" among the things lost.
- **§3.5's** control effects keep one implementation per platform, but it is named, parameterised and never a
  default; the anonymous `implement[E] Throw[E]` and its four siblings go. *Amended above: they stay anonymous and
  the default, and gain parameters.*
- **New surface:** `with` takes runtime arguments in body position, for an ability as for an effect. No new
  keyword: parameters require a name, and a named implementation is never a default. *Amended above: a default may
  take parameters too, supplied by the discharger that gives it (`p with State(cell)`).*
- **The second property above**, refuted.

*Where to measure first.* Building `recordingConsole`'s environment at the `with` that binds it: a definition
specialised at that binding needs the `Writer` frame at runtime though its signature names only `Console`, so the
runtime parameter list depends on the specialisation. That is the step Route A's second cost also hit. Everything
else is the existing thunk-and-apply with the frame in place of `unit`. The §10 gate cannot approve this change:
the jars move by design. Its witnesses are §8 items 14 and 15 as regression tests, and the 45 examples' stdout and
exit transcripts (`scripts/example-sweep.sh`) staying identical.

*Amended:* the specialisation-dependent parameter list need not arise. Mint a second phantom binder per entry for
the environment's type (`Impl: Implementation[State[S]], Env`) and give the definition an ordinary parameter
`env: Env`. The write fills `Env` wherever it fills `Impl`: from the implementation's declaration at a `with` or a
slot's default, and forwarded from the definition's own binder for a received binding. Arity is then the
declaration's, the checker sees ordinary parametric polymorphism and still holds no effect rule, and an
implementation with no environment has `Env := Unit`. Dropping that argument is a backend optimisation; until it
exists, a `Console` call carries one more argument than today.

What stays hard is a **slot's** `with` whose double performs a control effect:
`transcriptOf(program uses Console with recordingConsole: => Unit) = runWriterToLog(program)`. Today
`slotImplementation` writes the double's `Writer` as `Default`, and the nearest-frame search finds
`runWriterToLog`'s frame at runtime; option 5 deletes that search. So where `transcriptOf`'s body passes `program`
on to `runWriterToLog`, the walk must build `recordingConsole`'s environment from the `Writer` that slot gives, and
check the double's clause row against the scope there. It reads only the definition being walked, so it stays
within §3.2, but it is new, and it is Route A's second cost. **That is the first spike.**

Smaller items. On the compile track a frame becomes a `SemValue` with identity in `EffectIntrinsics`, which meets the
rule that an escape whose result is neutral stays stuck and fires again. A `raise` in a signature binds `Default` today
(`uncoveredDefaults`), and with no frame there a guarded return needs an answer of its own. Parameterised *ability*
implementations should start on the runtime track only, and D19's undiagnosed "Function not implemented." at a
constrained named implementation sits on their path. The whole change lands **after D20 work-list steps 1–3**: the
"live frame" argument needs rules 3 and 6, and on today's tree a kept function parameter or a stored row-typed field
carries a frame past its discharger. For an escape that is a loud crash; for a cell it is a silent write to an
orphaned box.

**What is open.** Whether Eliot stores open computations at all. If it does, the choice is between option 1 with
regions (a new kind of binder, unmeasured), option 2 (reverses the 2026-09-06 decision and runs into the step-6
limit), option 3 (blocked on recursive data), and option 4 (a reversal of D20 rule 6, with the two measurements
above outstanding, read-site specialisation in place of the constructor product, and the positivity rule).
Until it is decided, D20's answer is option 3's partial form, or a hand-written `data` and interpreter.

Option 5, as amended, is decided (above). Four questions about building it were **decided 2026-10-12 (Robert)**:

- **Order.** D20 steps 1–3 first, then option 5, written in today's syntax, then D20's surface (steps 4–6). The two
  gates stay apart: option 5 is judged on transcripts, and the surface migration stays byte-identical.
- **An anonymous implementation's parameters** follow its pattern: `implement[S] State[S](cell: Cell[S])`, supplied
  as `with State(cell)`.
- **A signature's control effect** (a guarded return's `if`/`else`/`raise`, which binds `Default` unchecked today
  through `uncoveredDefaults`) is given by the guarded-return read, `GuardDischargeResolver`, acting as the
  discharger and supplying a compile-track frame.
- **Parameterised ability implementations** (`with byKey(…)`) come after the first cut: they bring compile-track
  evaluation and sit on D19's undiagnosed constrained-implementation codegen failure.

Not decisions: **the marker grows** (the environment's binder makes `Implementation[A]` something like
`Implementation[A, Env]`, which the CLAUDE.md cornerstone describes), and **the spike** comes first in the build: a
slot's `with` whose double performs a control effect (`transcriptOf`, under "Where to measure first"). A fallback is
chosen only if it fails.

**The `transcriptOf` spike (2026-10-12, on `ba9fad1b`).** Its question: at the place `transcriptOf`'s body runs
`program`, can the walk find the bindings the double's environment is built from, reading only the definition being
walked? **Yes**, and building that check closed a live hole (§8 item 16). It also turned up two things the
environment's *construction* needs and the decision does not settle (§8 item 17 and the `Env` type of a default).
Both are Robert's; nothing past the check below is built.

1. **Built: a slot's named implementation promises its clause rows to the block.** §2.2's supplied-versus-rides rule
   now applies to the double's clauses as it does to the slot's own entries. A clause ability the callee
   **supplies** (its own row lacks it) is written `Default` by the caller, as before, and is now *promised* to the
   block as `Default`: the callee must run the block where a further slot gives it, or it is rule 5's "nothing here
   gives it", naming the double. A clause ability the callee **rides** is written from the caller's scope, like any
   entry a slot does not supply, so the block captures it and the run site needs nothing. The callee then performs
   nothing by declaring it, and §4 says so, which is right: `transcriptOf: {Writer[String]} Unit = program` declares
   `Writer` for nothing, and the double writes into the caller's log. Under option 5 the double's environment is
   exactly these bindings' environments — the supplied ones arrive as the block's parameter where it runs, the
   riding ones were captured where it was written — so building it needs no further reading. The gate held: all 45
   example jars byte-identical, the suite green, eliot-test green. The probes are three cases of
   `BlockGivingIntegrationTest`.
2. **Finding: the walk's unit is the ability, not the entry** (§8 item 17). Under option 5 the walk must choose the
   frame, and with two entries of one effect in scope it cannot: an operation's arguments are inferred, so
   `raise(err)` learns `E` from `err` in the checker, after the write. It occurs today in eliot-build's `Git` alias
   (`Throw[IoError]`, `Throw[GitError]`) and eliot-test's `mocked` (`Throw[IoError]` supplied beside
   `Throw[AssertionError]` ridden). Three ways out:
   - **(a)** one entry per effect whose default takes an environment, checked at the row; the two programs nest
     their dischargers or sum their errors.
   - **(b)** an effect's environment in scope holds every frame of it the definition was handed, and the primitive
     picks by instantiation among those. That is today's key comparison, but over frames passed rather than
     searched, so items 14–15 stay closed; it costs a list per effect and a comparison per operation.
   - **(c)** the entry is chosen at the reference's *ground* arguments, as `AbilityResolver` already prefers a
     reference's own ground arguments over a same-ability constraint (`Dep[Database]` beside `Dep[Logger]`). A choice
     made from a type, not a declaration, so §3.2 and standing rule 3 would need signing off; two entries equal at
     some instantiation would be a use-site error.
3. **Finding: `Env` at a `Default` binding has nothing to read.** The amendment fills the environment's binder "from
   the implementation's declaration at a `with` or a slot's default". A `with` names a declaration; `Default` does
   not — the two-site search chooses at ground arguments, during monomorphization. The base cannot spell it either:
   `runStateToPair`'s body-less base signature must merge character-exactly with the jvm body, and `Cell[S]` is
   private to jvm. What does work, measured: a slot `p: Env => A` with `Env` a binder the caller writes, because a
   body is checked at its instantiation (`def f[A](x: A): String = x` compiles when used at `String`). Three ways
   to fill it:
   - **(i)** read an effect's default off its own module — its anonymous implementation — rejecting an effect with
     anonymous implementations at more than one pattern when any takes parameters. The two-site search stays the
     source of truth at monomorphization, so a disagreement must be a type error at the call; whether the checker
     re-checks a call after `AbilityResolver` substitutes the implementation's method is unmeasured, and if it does
     not, the disagreement is silent.
   - **(ii)** `Env` is a type the checker computes from the ground binding, by an intrinsic over the resolved
     implementation. The checker learns what an implementation's parameters are.
   - **(iii)** no `Env` binder: environments are threaded after monomorphization, where every binding is ground
     and every specialisation is its own method, so a parameter list that depends on the specialisation costs the
     backend nothing. It is the shape the amendment set out to avoid, because the checker never sees the plumbing.
     It is also the only one of the three that answers finding 2 for free, since at that point every entry's
     arguments are ground and (c) is a lookup. The compile track evaluates unlowered terms, so under (iii) its
     frames (`EffectIntrinsics`, the guard read's) are threaded by the evaluator itself rather than by the lowering.

   **Recommendation: (iii) with 2(c)**, because it is the one reading under which both findings have an answer
   from facts that exist, and the plumbing it hides from the checker is a lowering of ground terms, as `uncurry` is.
   But it reverses the amendment's `Env` binder, which is recorded as a proposal ("Not decisions" above), and 2(c)
   touches standing rule 3, so both need sign-off before option 5's environment is built.

**Decided 2026-10-12 (Robert): (iii) and 2(c).** Environments are threaded **after monomorphization**, at the
`WovenValue` seam — the step built for a post-mono lowering, whose ground re-check (`WovenRecheck`) then checks the
plumbing as it checks every woven body. There is no `Env` binder and the marker does not grow; the checker never
sees an environment. A reference's frame is chosen among the entries in scope **at its ground arguments**, so two
entries of one effect are told apart by their instantiation, and two that coincide at one are a use-site error.
Reversed (standing rule 1): the amendment's "mint a second phantom binder per entry for the environment's type".
Standing rule 3 stands: the walk still writes every binding from declarations and nothing infers one. What the
ground arguments choose is only *which declared entry's frame* a reference uses, which no binding names; where that
choice and the written binding disagree, it is an error, never a silent pick.

#### Reversals this records

Standing rule 1: each is written down as a reversal, not amended in place. They take effect when D20 lands.

- **§1 rule 3 (a stored computation is bound where it is written)** is reversed: a field holds a value (rule 6).
  With it go §2.3, A11, §7 item 6 and the read half of §8 item 2.
- **§1 rule 4's predicate** moves from "the position declares a row" to "the parameter is a block" (rule 2), and
  its third bullet — "a lambda at a rowless arrow may not reach the enclosing def's bindings" — becomes rule 4,
  which is about *value* positions; a lambda at a function-typed parameter of a definition now may.
- **§2.1's `{}`** is replaced by `=> A`; the empty row has no spelling.
- **§2.2's "an entry it lacks is supplied … by `Default`"** is narrowed by rule 5 to body-less declarations; a
  definition with a body supplies only by `with` or by passing the block on.
- **§2.4's row alias** loses its form (D20a), and **D18** with it.
- **§12's "not now: `with` on a def's own return row"** is restated, not reopened: `with` on a definition's own
  `uses` clause is likewise rejected.

**Closed, numbers kept so citations resolve.** **D17** (a `val`-bound computation) closed 2026-09-12: **a `val`
is a bind**, rule 1 unchanged, §3.5's sentence struck as a reversal — the tree was already right and no code
changed (§8 item 3). **D4** (`Suspend`-riding effects: pinning and supplying)
dissolved at the flag day — a stored computation is a thunk bound where it is written. **D5** (a lambda body at
a rowless arrow slot) closed 2026-09-08 as rule 4's third bullet. **D7** (the post-mono accounting verifier)
closed 2026-09-10, retired by measurement — §3.3 and §12. **D13** (every `eliot-test` case built with its
handlers already applied) closed 2026-09-08, yes — `Mock` is `with` on a discharge word's slot type.

---

# Part III — What is closed, and provenance

## 12. Closed by measurement or decision — do not re-propose

- **Carrier inference** — a carrier metavariable, a join solver, an `Id`-headed uniform judgment, a mode
  obligation, a post-drain mode resolver. Of 15 fix commits in the v2 window, the four highest-impact were
  one failure: a carrier metavariable captured by first-contact unification. Under v6 the class is still
  prohibited in its restated form (§5 rule 3): a binding is filled by `with`, declaration, slot or default, never
  joined.
- **A second, post-monomorphization effect verifier.** Retired 2026-09-10 by measurement (D7): what a
  monomorphic body can still say about effects is strictly less than what the declaration walk already said, because
  an operation call is by then a call to an implementation method, which declares no row. Verification belongs where
  the declarations are — the write's own walk, before monomorphization. A post-mono re-derivation buys no case and
  splits one diagnostic across two files. (This is not about the *precondition* that stayed at that seam: a supplied
  row entry's arguments genuinely need ground types.)
- **The carrier as the injection point** (§6's strategy as the *final* form). It chose an interpretation by
  instantiating a type, and every one of v5's testing limitations was a symptom. Superseded by §6.
- **An implementation as a runtime value (a record whose fields are the operations).** Measured 2026-09-07:
  the checker cannot represent a Π-typed field. A field position demands a proper type
  (`Expected: Type / Actual: Type -> Type`), there is no surface spelling for a binder in a type position,
  and instantiating a polytype anywhere but a `ValueReference` throws — `Checker.appendTypeArgs`, whose
  comment states the invariant: *"polymorphism lives on named signatures."* The root cause is the mono
  key's indexing: a polytype is `VLam`, and instantiating it writes metas into a `ValueReference`'s
  `typeArguments`, which *is* the key; a field is reached by an accessor **application**, which has neither.
  Six operations carry their own type parameter, and the four that return it (`FileSystem.foldLines[B]`,
  `foldCodePoints[B]`, `PatternMatch.handleCases[R]`, `TypeMatch.typeMatch[R]`) admit no workaround. Two
  further defects of any record encoding: an ability may declare an **associated type** (`type Cases[R]`),
  which a record has no place for; and a **nullary operation as a plain field is strict**, so `readLine`
  would be computed once and every call would return the same line. Also lost with it: a parameterised
  `implement name(params)`, a record capturing an escape, and `with` as an ordinary def taking an
  implementation as a value.
- **A handler as a marker *type* in the type language** — a marker type per handler, a binder that occurs in
  types and is unified, a "two handlers meeting is a mismatch" rule, one environment per stored computation,
  a handler result function, and a post-mono lowering pass with `handler`/`returning`/`finish`/`resume`/
  `return`. What is closed is each of those: the lowering pass (§3.4's primitives make it unnecessary), the
  in-type binder and everything unification of it forced, and the four keywords. What is **not** closed —
  reopened and decided 2026-09-08 — is a *phantom* binder per row entry as the mono-key component (§3.1): it
  occurs in no parameter or return type of any value, so nothing unifies it, and it is the elaborator's existing
  prefix write with a name in place of a carrier. Part II §9 gives that binder a declared type and lets it stand
  as a *dead argument* of a row alias, which reduces away before any type is compared; neither is the in-type
  binder this entry closes.
- **Transitive `with` / summing abilities over a monomorphized sub-graph.** Dynamic scoping resolved at
  compile time; the carrier's third job in a new costume. Forwarding is by declaration only (§3.1).
- **A bindings field on `ValueReference`, a scoping node, or a consulted-set component on the mono key** as
  the carrier of a binding. Decided 2026-09-08 against, for the phantom binder (§3.1): the key already
  carries one argument per binder, and transitivity is a ground-value tree.
- **A runtime handler stack / dynamic scoping at runtime** as the language's mechanism. Kills erasure and
  makes storage semantics unwritable in a type. The one dynamic discipline that exists is the machine stack
  inside the escape and cell leaves (§3.4), private to the platform.
- **Full unification — every ability passed, none searched inside a function.** Declared rows for every
  ground ability use: no new capability over a `~` constraint, and the declaration burden puts
  `{Eq[Int], Combine[String], PatternMatch[Shape]}` on most monomorphic code, transitively. Rows inferred for
  abilities: that is inference, and it makes `printAll(xs) with reverseOrd` reach an undeclared resolution.
  Under either reading the two-site search stays, because the compile track dispatches
  `Meta`/`Numeric`/`PatternMatch`/`TypeMatch` from machinery with no call chain.
- **A public cell or escape in the base.** A public cell is Landin's knot; both primitives are
  platform-private, and the dischargers are abstract in the base for exactly that reason (§3.4).
- **A runtime free monad as the effect representation.** A heap tree of closures walked by an interpreter,
  a coproduct-with-injection to compose effects (the row back in the type), and no plain spelling of the
  scoped operations.
- **A `World` token / threading a fake dependency to sequence and protect I/O.** Not needed in a strict
  core (§3.5); purity is decided by evaluator stuckness.
- **A rule on twin-less natives** — the reachability check ("a native without a twin may be called only
  from an `implement`") and, with it, **a purity annotation for twin-less pure natives**. Withdrawn
  2026-09-08: a twin proves reduction, not purity, and the jvm layer's `Path` algebra is pure,
  twin-less and legitimately called from plain defs. Purity is not detected and not annotated; a side
  effect is declared as an operation of an `effect`, and a native leaf's declaration is axiomatic. The
  hole the rule aimed at (`def now: Int = currentTimeMillisInternal`) is a layer bug with a named fix — an
  effect such as `Time` — not a compiler check.
- **An anonymous implementation-literal expression** (`Console(printLine = …)`). A named `implement`
  covers every case in the tree, and a literal is a value.
- **Deleting `CarrierKindChecker`.** Measured per arm under v5: `verifyCarrierKinds` is the only thing
  rejecting a `[F[_]]` binder instantiated at a proper type. It is a kind system and stays; `EffectLifter`
  has no subject under v6 and goes at the flag day.
- **Putting the row on `VPi`** (Koka-style). Teaches every unification site, the printer and the `Function`
  native about rows. v6 keeps the row as declaration metadata beside the signature.
- **A row alias with its own AST node.** An `ast.fact.Expression` case is the most expensive thing this
  language can add. The row alias that exists (§2.4) rides the `{E} A` node the parser already has; a payload-less
  `type Web = {Console, Log}` is not a type and stays unspelled.
- **Discharge markers (`{-E}`).** There is no negative-effect surface; discharge is a frame a discharger
  installs.
- **Scanning the dictionary for an ability or implementation name.** Replaced by the keyed marker lookup,
  and the same closure covers `with`: an implementation name resolves at `resolve` to a `ValueFQN` by keyed
  lookup, and only that FQN flows onward — never a string carried downstream for `AbilityResolver` to search
  by, which would be a second lookup path with the same three failures the scan had: it ignores import
  scope, cannot be shadowed, and is decided by hash order.
- **A third kind of parameter for a function the callee keeps** (`val f: B => C`, with a lambda passed there
  barred from using effects). Rejected 2026-10-05 with D20: two kinds of parameter — a value and a block — is the
  model, and `compose` is not worth a third. D20 rule 3 forbids keeping instead; the relaxation that would admit
  `compose` without new syntax is "not now" entry B below.
- **Block parameters written as nested `def` signatures** (`def f(a: A): B`, `def body uses Throw[E]: A`).
  Considered 2026-10-05 and dropped for D20's `f: A => B` and `=> A`: the parameter names are never referred to,
  and a function type already says everything the form said. The one case it covered that a function type cannot
  — a lazy argument — is `=> A`.
- **Effects on function types** (`A => B uses Console`), so a kept function could carry its effects and charge
  whoever calls it later. Rejected again 2026-10-05; it is "putting the row on `VPi`" above, and it brings back the
  stored-computation defect §8 item 12 measured — the binding is fixed where the lambda is written while the
  effect is charged where it runs. That argument covers a *closed* computation only. The *open* kind, bound
  where it runs, was never weighed here and is open as D20c (§11).
- **The `<Ability>Carrier` convention as an explicit declaration.** Its premise — that an effect *has* a
  representation — is gone; the property it named ("has a canonical monad transformer") is exactly what
  §3.4's two primitives replace.

**Not now — conveniences deliberately left out of the flag day (2026-09-08).** Each is additive later, none
touches the mechanism, and the plan is kept as simple as possible until the flag day has landed:

- **A name for several implementations** (`implement mocks = mockConsole & mockFileSystem`), and **a set of
  abilities required of a binder** (v5's `ability Web[F[_] ~ Console & Log]`, whose carrier-binder spelling went
  with the binder; `EffectAbilitySet.els` was deleted at the flag day). A row *with its payload* does have a name
  since 2026-09-11 — the row alias, §2.4 — and the superability closure stays for ordinary binders.
- **`with` on a def's own return row.** Rejected as a second spelling of `with` around the body.
- **A row spelling for a ground ability default** (`def printAll(xs: List[Int]): {Ord[Int]} Unit`). `with`
  on an ability reaches a callee only through a `~` constraint on a generic.
- **A user-declared boundary default** (`implement Throw[ConfigError]` handling an undischarged
  `Throw[ConfigError]` at `main`). It would overlap the platform's single `implement[E] Throw[E]`, which
  coherence rejects; the boundary binds the platform layer's defaults only.
- **Compile-time reduction of effectful code under a pure implementation** (a quiet-stall evaluator entry
  with fuel). Possible under the model, wanted by nothing.
- **B — a derived *keeps* property** (2026-10-05, the relaxation of D20 rule 3). The compiler records, from each
  definition's body, which function parameters it keeps — stores, returns, wraps in a kept lambda, or passes to a
  parameter that keeps — bottom-up over the reference graph, which the recursion gate makes acyclic. A call
  passing a lambda that uses effects to a kept parameter is the error at that call, naming why. It accepts every
  program rule 3 accepts plus `compose` over effect-free arguments, so switching to it breaks nothing. It is a
  check, not a binding decision, so §5 rule 3 does not touch it, and it is the use-site verification cornerstone
  applied here. Wanted by nothing until a platform native or a library genuinely keeps callbacks.
- **Derived mocks** (`with mock[Database]`, EasyMock-style): compile-time reflection over an ability's methods,
  a derived implementation, a derived per-ability answer store and a recorder derivation. A feature in its
  own right, after the flag day, and a consumer for D3 if it is ever wanted.

---

## 13. Retired documents, and how to read a citation

Ten documents were merged into this one and deleted. Their full text is in git history (`git log --diff-filter=D
-- docs/`), and the table below says what each was and where its subject now lives. **Scaladoc comments across
the compiler cite them by their own section anchors** — those are *historical pointers* ("this code came out of
X §N"), not live references, and they do not index this document.

| retired document | what it was | anchor scheme in comments | where its live content is |
| --- | --- | --- | --- |
| `effects-as-channel.md` (v2, "uniform carriers") | the first shipped design: the carrier as a type argument the *checker solves* | `§0`–`§13`, `finding N`, `U1`/`U4-x` | superseded in direction and in code. What it got right and still holds: the **channel** (§3.3) and rows never flowing into types. Its carrier-specific results — carrier-ness by tag, `Id` without `Suspend[Id]`, the payload/row rendering inverter — went with the carrier. What it got wrong is §12's first entry |
| `effects-as-rows.md` (v3) | the previous landed design + its A.1–A.11 record: the elaborator writes the carrier | `§1`–`§9`, `A.x`, `R1`–`R6` | its **user model** is Part I §1–§2 and its **standing rules** are §5, both largely intact; its mechanism (the elaborator, the derivation spec, the whitelist) is superseded by §3. §10's gate method is its A.9.4 |
| `effect-row-tails.md` | pinned rows as the one spelling of a carrier stack | prose only | no live content: a stored computation is a thunk (§2.3) and D4 dissolved |
| `testing-effects.md` | substituting effect implementations | `L1`–`L3`, `§2.x` | §6 — the question is the same and the answer changed: a name, not a carrier |
| `effects-v5-one-carrier.md` | rows as constraints on one carrier — the subtraction from v3 | `§4 step N`, `§5 Q1`–`Q4`, `§7` | §2.1 (step 1) and §2.2 (step 2) both survive v6 unchanged in meaning; its §7 (an ability requiring abilities) has no v6 spelling (§2.4); step 4 and Q1 are §12 |
| `effects-as-channel-v4.md` | the row leaves the type, the carrier leaves the language | `R1`–`R11`, `P0`–`P5`, `Q1`–`Q4`, `§0`–`§11` | superseded by Part I (v6); its seam finding — specialisation is the mechanism, not a follow-up — is §3.1 |
| `effects-v4-p0-spike.md` | does the `WovenValue` seam know the carrier? | `S1`–`S3` | the seam test (`EffectsSeamGroundnessTest`); the test is permanent |
| `effects-v4-p2-sizing.md` | sizing the flag day | `§1`–`§5` | the flag-day record, history (below) |
| `effects-v4-flag-day-readiness.md` | is the flag day ready? (no) | `B1`–`B3` | §12 |
| `effects-syntax-userspace.md` | `~` and `&` as ordinary values | `stage 1`–`stage 4`, `§7.x` | §2.5 (stages 1–2, landed), **D3** (stages 3–4) |

**Citations to Part II's former numbering** (in commits and comments dated before 2026-09-07): *D1*/*B1*–*B4*
were the v4 decision and its blockers (now Part I and §12); *D2* the `<Ability>Carrier` declaration (§12's last
entry); *W1*–*W4* the v5 work items (A3, history); *D8*/*D9*/*D10* the surface spelling, the cell and the purity
annotation (decided: §2, §3.4, §12); *D14*/*D15* (2026-09-07 only) were the slot `with` and the binding's
carrier (decided: §2, §3.1); *D6*, *D11*, *D12* and *D16* (grades, the ground-ability row spelling, the
user boundary default, bundles and effect sets) are §12's "not now" group. Two older citations in the tree — `docs/effect-lift-in-checker.md` and
`docs/effectful-signatures.md` — point at documents retired before these and are likewise historical.

**Citations to Part II's retired sections.** Until 2026-09-11 Part II was the v6 plan and its record, and
Scala comments dated 2026-09-07 to 2026-09-10 cite its anchors: **§8** (how the plan was run), **§9** (the
model — §9.1 in five sentences, §9.2 what was decided and why, §9.3 the surface, §9.4 the core desugar, §9.5
semantics, §9.6 the three primitives, §9.7 specialisation and purity, §9.8 what it deleted/kept/added, §9.9 the
standing rules re-read) and **§10** (§10.1 the nine pre-flag-day steps, §10.2 the flag day **F1–F9**, §10.3 the
follow-ups **A2–A11**). Those are historical pointers into the document as it stood at `cb2a9c6d`
(`git show cb2a9c6d:docs/effects.md`), not live references. Where the live content is: §9.2's decisions are
Part I §2, §3.1 and §3.5; §9.3 is §2; §9.4 is §3.1 and §4; §9.5 is §3.5 and §4; §9.6 is §3.4; §9.7 is §3.5 and
§12; §9.8 is history; §10.1's steps and §10.2's F-items name their commits and live there; and of §10.3's
A-items, A5 is §3.3, A6 is §2.2, A7 and A11 are §2.3, A8 and A9 are §7, A3 and A4 are absorbed by §7 and §11,
and A2 (a microcontroller target's exit primitive) is still hypothetical and is §3.4's table. Part II's
*current* §8–§10 are new sections and share nothing but their numbers with those.
