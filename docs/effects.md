# Effects in Eliot — the design, and what is left to do

**Status (2026-09-11): effects v6 is shipped, and this is its single document.** An effect is an ability
declared with the `effect` keyword; an **implementation is a name**, bound by `with` and forwarded lexically
from `main` inward through declarations. There is no carrier, no monad, no `Id`, and nothing to infer. Part I
describes the tree as it is; Part II is what is left — where the tree still diverges from Part I, the record of the
changes built since (a binding binder marked by its type, §9; effects as parameters, D20 and D21 in §11), and the
decisions still open;
Part III is the list of things closed by measurement or decision, and enough provenance to read a source
comment that cites a retired document or a retired section.

**Decided 2026-10-05, D20 (§11); all seven steps of its work list built, 2026-10-09 and 2026-10-10.** Effects are
parameters in the surface as they already were in the mechanism — `def greet(name: String) uses Console: Unit` —
code a definition is handed may be called or passed on but never kept, and a `data` field holds a value. It closed
five silent defects found while designing it (§8 items 9–13) and reversed three of Part I's former rules.

**Decided 2026-10-09, D21 (§11); built with D20, all seven steps** — it amended D20 before it was built: a
function-typed parameter is an ordinary pure value, keepable like any other, and the one kind of parameter that is
code carries the one clause the surface has, `uses` — `uses *` for the caller's own effects
(`def foreach[A](action uses *: A => Unit, list: List[A]): Unit`), `uses *, Throw[E]` for a handler's slot, `uses E`
alone for a closed row. Purity is the absence of one word anywhere in a signature, and the compiler enforces it;
`=>` keeps its one meaning and D20's `=> A` was withdrawn. Part I is written in this surface since 2026-10-10.

**Decided and built 2026-10-12, D20c option 5 (§11, D20c).** An implementation is compile-time code — the binding, as
before — plus a **runtime environment**: an escape frame, a cell, or an anonymous implementation's own parameters
(`implement[S] State[S](cell: StateCell[S])`). Environments are threaded **after monomorphization**, at the
`WovenValue` seam (`monomorphize/channel/EnvironmentThreading`), chosen by entry at ground arguments, and never searched
for, so a helper's own `catch` or `State` no longer intercepts its caller's effects (§8 items 16 and 17) and an
undischarged control effect at `main` is a compile error (D19, §8 item 5). A slot entry the definition also declares
**rides by code and is given by frame** ((b)): the block keeps its caller's binding, takes the frame where it runs, and
running it performs that entry — which is what lets a definition discharge the very effect it declares. Option 5 was
built on a line parallel to D20/D21's and merged onto the `uses` surface the same day; its record in §11 keeps the
brace spelling it was written in.

**Where things stand (2026-10-10).** D20 and D21 are built in full: the surface is the `uses` clause, the brace
spelling is a parse error outside a row alias's body, and Part I, the CLAUDE.md cornerstone, the `.els` doc comments,
the user docs site and the `eliot-code` skill all describe it. Open, in order of severity:

- **Silent:** an unapplied reference to a `uses` definition passes as a value (§8 item 14) — D21 rule 2's second
  half, unbuilt; and an unused `with` binds nothing without complaint (§8 item 1).
- ~~**Loud, at runtime:** an undischarged control effect reaching `main` (§7 item 7, §8 item 5).~~ **Closed
  2026-10-12** by D20c option 5 (D19), on the runtime track.
- **Open, from D20c:** whether a slot that lists an entry its definition also declares is a handler or a pass-through
  (§11, D20c, "Open after the merge").
- **Loud, at compile time:** an effectful ability method needs its ability's type arguments spelled (§8 item 15); a
  `catch` over a parameter still spells its arguments (§2.2, §7 item 2); a function-typed code parameter's own
  entries are never supplied (§7 item 1).
- **Undecided:** a `uses` form for the row alias (D20a), `~`/`&` in user space (D3).
- **Outside this document:** no eliot release carries `uses` yet — v0.7 rejects it — so eliot-test's and
  eliot-build's `dep` lines, and the docs site's install page, wait on the next tag; the site's generated `apidoc/`
  pages predate effects v6 and need regenerating; and eliot-test's own notes still say a row cannot be closed and
  list `runThrow` spellings that are now redundant.

**One-sentence summary.** The user writes **`uses` clauses**; each clause entry desugars to one **phantom generic
binder** whose value is an implementation, written at every reference by a syntax-directed pass that reads
declarations only — so an operation call is an ordinary call to a known method, effects verify as a
**channel** beside the type (the same architectural move as the `Int` refinement channel), and the NbE
checker holds no effect rule at all.

**How to read this document.** Part I states the shipped design; it is the authority for the tree, and the
CLAUDE.md *Effects Are a Channel* cornerstone is its summary. Part II lists the divergences (§8), the built
binder mark (§9), the method every change is run under (§10) and the open decisions (§11, numbered **D**). Part III
holds the list of things **closed by measurement or decision and must not be re-proposed** (§12) and the
provenance (§13). Where Part I and any code, stdlib signature, example or test disagree, Part I wins and the
artefact is the defect. The record of how v6 was decided and landed (the former §8–§10: the reasoning, the
flag-day log F1–F9 and the follow-ups A2–A11) is in git history, and §13 says how to read a citation to it.

---

# Part I — The design

*Rewritten 2026-10-10 for D20 and D21 (§11), which are built: the surface is the `uses` clause, a parameter is a
value or code, and a field holds a value. Every reversal the two record is applied here; the mechanism of §3 did not
change. A citation to §1's former four rules is mapped at the end of §1.*

## 1. The user model — six rules

> **An effect is a parameter you don't spell.** `uses Console` on a definition means its caller hands it a
> `Console`, and an operation uses the one in scope *where it is written* — never where it runs. `with` binds one.
>
> **A parameter is a value or code.** Without a clause it is a value, computed before the call — a function value
> among them, which is pure and may be kept. With a `uses` clause it is code the caller wrote, run by the callee,
> which may call it or pass it on to another `uses` slot and never keep it: `uses *` says it may use whatever is in
> scope where it is written, and `uses *, Throw[E]` that the callee gives it `Throw[E]` as well.

1. **Effects run, and resolve, where they are written.** An effectful expression in a value position performs its
   effects there: strict call-by-value in **every** value position, a bare generic slot included, so
   `choose(readLine, readLine)` runs both reads and `Box(shout)` runs `shout` and stores its value. An operation, or
   a call to a definition that `uses` an effect, takes the nearest binding *in the text* — an enclosing `with`, the
   enclosing definition's own clause, or what an enclosing code parameter's clause supplies — which is §3.1's
   resolution order stated as scope. The one argument not run where it stands is code (rule 2).

2. **A parameter is code iff it has a `uses` clause, and a value is pure.** `whenTrue uses *: A` receives the
   caller's text unrun; after desugaring a nullary code slot is a **thunk** (`Unit => A`), but the thunk is the
   lowering, not the surface — every phase goes by the clause recorded on the declaration
   (`EffectRow.parameterEffects`, `callbackEffects`), never by the shape. Every other parameter is a value, and a
   **plain generic is a payload, always**. A lambda in any value position — an unmarked function parameter, a
   field, a generic slot, a result, a `val` — may use no effect it does not discharge itself: it may bind and
   discharge locally (`x -> runAbort(lookup(x))`), and it reaches no enclosing binding, so an effect taken from
   outside is *"This uses the effect 'X' inside a function written where a value is expected"* at that use. An
   ordinary ability (a `~` constraint) is not an effect and stays reachable. An unapplied reference to a `uses`
   definition is not a value either (not yet enforced: §8 item 14).

3. **Code is used, never kept.** A reference to a code parameter is the head of an application, or the direct
   argument of another code parameter (or stands inside a lambda so placed), and nothing else; anywhere else it is
   *"'f' is code its caller wrote: it can be called or passed on, not kept"*, and a value lambda cannot capture it.
   Nullary code runs when it is mentioned, so it can never be kept, but it can be captured. A pure function value
   may stand where code is expected (it simply uses no effects); code may not stand where a value is expected. The
   reason is frames, not purity: code run after its discharger returned finds no frame (§8 item 10), and the design
   keys on no family, so one rule covers the interpretation effects too. Swift's non-escaping closure parameters
   and Kotlin's inline-function parameters draw the same line.

4. **A closed clause supplies and closes.** A clause without `*` — `body uses Throw[E]: A` — admits the argument's
   text to what the slot supplies, what it rides (§2.2), and what it discharges itself, and to nothing from around
   it: *"This uses the effect 'X' inside an argument whose `uses` clause is closed"*, and the caller's own code
   passed there *"… cannot run it"*.

5. **A definition gives its code only what it has.** Running a code parameter is checked like a call to a
   definition with that signature: each entry its clause supplies must be bound there — by the implementation the
   slot names with `with`, or by passing the code on to a slot that supplies it (`catch` hands `computation` to
   `runThrow`). An entry the definition's own clause has rides instead, and is its caller's to give. **Only a
   body-less declaration gives an effect from nothing**, because that is where a frame comes from: the platform's
   primitives state what they give (§3.4), and the run boundary binds `main`'s entries. So **an effect's default is
   handed out at `main`, and by platform primitives, nowhere else.**

6. **A field holds a value.** A `data` field takes no clause and no row, and there is no stored computation: store
   data describing the work, and perform it where the effects are in scope (§2.3).

**Consequences the user sees.** **Purity reads off the signature as the absence of one word.** No `uses` anywhere:
pure, and total, since `Inf` is an entry like any other. Code parameters and no clause of its own: the definition
adds no effect, and a call performs exactly what its caller wrote in the code — `foreach`, `catch`, `orElse`, `.`.
A clause on the definition: it performs those, received from its caller. So `xs.foreach(x -> printLine(x))` inside
a `uses Console` definition needs no declaration on `foreach`: the lambda is the caller's code, the `Console` is the
caller's, and the collections library stays effect-oblivious. Evaluation order is readable from signatures, and
every diagnostic speaks the clause's vocabulary ("add it to its `uses` clause").

**Rule 2's predicate was agreed and then worked around four times, and every stall in this design's history traces
to that erosion.** It was §1's former rule 4 — *"a binding passes into a position if and only if that position
declares a row"* — and the vocabulary below is v5's; the lesson is not:

| # | how the predicate was worked around | what it cost |
| --- | --- | --- |
| 1 | bare-generic slots exempted from rule 1 — "mode belongs to the instantiation" | six days; a mode resolver, obligations, splice-restart (~350 lines), all reversed |
| 2 | declined a row on `foldLeft`/`foldOption` for ergonomics | the elaborator could not hoist at a generic-return callee *at all*; kept the whole payload router alive |
| 3 | the derived discharge stack routed a computation through `.`'s **rowless** slot "as data" | 5 `State`-family miscompiles; made "a generic is a payload" false, so nothing downstream could assume it |
| 4 | `foldOption` left with a strict `ifNone` because both declared spellings failed | a silent lazy-branch failure mode |

A fifth move was proposed and rejected in that form: *let the elaborator's payload test accept a generic-headed
return*. It **approximates** the predicate instead of **declaring** it in the signature. Moving the predicate from
the row to the clause (D21) is what finally made the declaration say it: a function parameter with no clause used
to run its caller's effects by accident (§8 item 9), and is now the pure value it reads as.

**Citations to the former numbering.** Until 2026-10-10 this section had four rules. *Rule 1* (effects run where
they are written) is rule 1. *Rule 2* (suspension is declared by a row on the slot) is rule 2, the clause in place
of the row. *Rule 3* (a stored computation is bound where it is written) is **reversed** by rule 6. *Rule 4* (a
binding passes into a position iff it declares a row; a plain generic is a payload; a lambda at a rowless arrow
reaches nothing enclosing) is rules 2 and 3, its predicate moved to the clause and its third bullet widened to every
value position.

## 2. The surface

**One clause in two positions, and three constructs.**

**The `uses` clause** stands where a parameter's type would, before the colon, on a definition and on a parameter
alike — `name [uses …]: Type` — so no effect is written inside a type anywhere. A `where` precondition stays after
the result type.

```eliot
def greet(name: String) uses Console: Unit = printLine("Hello, " ++ name ++ "!")
def main uses Console: Unit = greet("Bob")

def updateState[S](f: S => S) uses State[S]: Unit = putState(f(state))           -- f is a pure value
def when(condition: Bool, action uses *: Unit): Unit = fold(condition, action, unit)
def foreach[A](action uses *: A => Unit, list: List[A]): Unit
def runThrow[E, A](obj uses *, Throw[E]: A): Either[E, A]
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A): A
```

| clause | where | what it says | lowers to |
| --- | --- | --- | --- |
| none | a parameter | a value, computed before the call; pure, keepable | its type |
| `uses E, F` | a definition | what its caller hands it | one phantom binder per entry (§3.1) |
| `uses *` | a parameter | the caller's code, open to the effects in scope where it is written | `Unit => A`, or its function type |
| `uses *, E` | a parameter | the same, plus `E` given by the callee — a handler's slot | the same |
| `uses E` | a parameter | code that may use `E` and nothing from around it — a **closed** clause | the same |

`*` comes first and at most once, and only on a parameter, since only an argument has a caller's text to be open
to. It is not "any effects" and not a row variable: it names *the effects in scope where the argument is written*,
which is rule 1's lexical capture, so nothing is unified. A code parameter's type is read as the function the callee
calls it at: one with no arrow takes nothing and runs when mentioned, and for one with an arrow the clause belongs
to its final codomain as written — `f uses *: String => (String => Unit)` is code of one argument handing back a
function value. Code that should *produce* a function is `g uses *: Unit => F`, applied by the callee.

The parser refuses the rest: `uses *` on a definition (a body has no caller's text to be open to, and the parameter
list already says which code it runs), `*` anywhere but first, an entry-less clause, `uses` on a `data` field, and
`with` after a definition's clause. **The brace spelling is gone**: a row written in any type — a return type, a
parameter, an arrow codomain, the empty `{}` — is *"Expected a type, with its effects in a `uses` clause before the
colon (`def f uses Console: Unit`, `body uses *, Throw[E]: A`), but encountered symbol '{'"*, and the pinned form
`{E | G} A` does not parse at all. The one place a row is still written in braces is a row alias's body (§2.4).

**An `effect` is an ability with no carrier binder**, declared with its own keyword. **A member's clause lists what
it performs *beyond* the effect it belongs to** — membership in the block already says the member needs that
binding, exactly as `show` inside `ability Show[T]` does not repeat `~ Show[T]`. So `uses Console` on a member of
`effect Console` is not written; a member's clause is real where it names *other* effects. A function that needs no
such binding is not a member: it lives outside the block as an ordinary def with its own clause, as `updateState`,
`orRaise` and `orAbort` do beside the primitives `state`, `putState` and `raise` inside.

```eliot
effect Console {
   def printLine(s: String): Unit
   def readLine: Option[String]
}

effect FileSystem {
   def readFile(path: Path) uses Throw[IoError]: String
   def foldLines[B](initial: B, step: B => String => B, path: Path) uses Throw[IoError]: B
}

ability Show[T] {
   def show(t: T): String
}
```

**An anonymous `implement` is a default; a named one never is.** An anonymous block in one of the **two sites** —
the ability's module or the type's module — is the default for its pattern, subject to the existing coherence and
`where` rules, and is what a declaration with no named implementation binds. A **named** `implement` may live
anywhere, is never searched, is not checked for overlap, and its clauses may carry `uses` — what the implementation
performs beyond its ability, charged wherever the name is bound. It takes no parameters and closes over nothing:
what it needs at runtime it asks an effect for.

```eliot
implement Console {                                       -- the platform's default
   def printLine(s: String): Unit = printLineInternal(s)
   def readLine: Option[String] = lineOrNone(readLineInternal)
}

implement session: Terminal {                             -- a test's, in the test module
   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
   def read: String = "Bob"
}
```

**`with` binds a name for its subject**: infix, subject-first, at the loosest precedence, left-associative, so
`xs.sort.render with reverseOrd` applies to the whole chain and `c with a with b` is `(c with a) with b`, an inner
`with` for the same ability shadowing the outer within its subject. It works on an ability exactly as on an effect,
and there are **two positions, one construct**: an expression in a body, and after an entry of a parameter's
clause — the same split as `f(x)` and `List[Int]`, both application under the types-are-values cornerstone.

```eliot
def greetTranscript: String = runWriterToLog(greet with session)
def sorted: List[Int] = sort(xs) with reverseOrd

def transcriptOf(program uses *, Console with recordingConsole: Unit): String = runWriterToLog(program)
def mocked(body uses *, Console with mockConsole, …, Mocking with recording, Calls with journal, …: Unit) uses …
```

The clause form reads *"this argument, run with `recordingConsole` for its `Console`"*: the actual delivered there
has its binding applied by the callee's signature, and the caller writes nothing. It is the one way a callee decides
the binding of calls it cannot see, since an actual's calls are bound in the caller, and each `with` stands on the
entry it binds. A `with` after a definition's own clause is rejected: it would be a second spelling of `with` around
the body.

**`with` is written almost nowhere.** Most code fixes nothing: a def declaring `uses Console` receives its binding
from its caller, up to `main`. That chain is what makes a fake possible — `greeting` never said which console, so a
test may say. A `with` in production code is the same mistake as a hard-coded dependency.

### 2.1 `uses *` — the caller's code

A clause on a **definition** is what it performs and does not discharge, received from its caller. A clause on a
**parameter** is what makes the argument code, and it is the declaration that lets the walk cross into the argument
(rule 2).

`uses *` alone says *"the caller's code, and I add nothing to it"*. It is the spelling of every
effect-transparent code slot — `fold`'s arms, `orElse`'s fallback, `catch`'s handler, `.`'s `f`, `foldLeft`'s
`combine`, `forever`'s `step`. It supplies no entry, so an operation inside such an argument is bound by whatever the
*caller* has in scope, and the argument is not run until the callee runs it. It replaces the empty row `{}`; at an arrow,
`A => {} B` and `A => B` lowered to one type and behaved alike (§8 item 9), which is why the clause decides now and
the type does not.

```eliot
def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A): A { join(whenTrue, whenFalse) }
def foldLeft[A, B](initial: B, combine uses *: A => B => B, list: List[A]): B
infix left below apply def .[A, B](a: A, f uses *: A => B): B = f(a)
```

**Which a function parameter is, is the base's decision, made per signature.** `updateState`'s `f: S => S` and
`foldLines`' `step: B => String => B` are pure values; `map`'s `f uses *: A => B` and `foldLeft`'s `combine` take the
caller's code, so `names.map(n -> setting(n))` inside a `uses Abort` definition aborts that definition. Making every function
parameter pure would need a `map` and a `mapM`, a `foldLeft` and a `foldLeftM`; making every one code would leave
purity unsayable.

### 2.2 A slot's entries are *supplied* — what makes a discharger

A **named entry** in a parameter's clause says *"I will run this code, and this binding comes from me"*. Which
entries those are is read one level up, at the declaration:

- an entry the definition's own clause already has is **not** supplied — it **rides**: `if`'s
  `value uses *, Abort: T` takes the caller's `Abort`, because `if` declares `uses Abort` itself and the walk
  continues outward;
- an entry it lacks is **supplied**, bound by the entry's own `with` or, with none, by `Default` — which rule 5
  then holds the callee to giving, by a frame of its own or by passing the code on to a slot that gives it.

```eliot
def if[T](condition: Bool, value uses *, Abort: T) uses Abort: T                   -- performs Abort
def else[A](computation uses *, Abort: A, fallback uses *: A): A                   -- supplies Abort: discharged here
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A): A        -- supplies Throw[E]
def runStateToPair[S, A](initial: S, p uses *, State[S]: A): Pair[A, S]            -- supplies State[S]
```

Read as English they are already right, and that is the whole of what a discharger is: no tail, no base, no second
concept. Only the clause of a **nullary** code parameter supplies; a function-typed code parameter's clause is the
callback's own scope (`onError uses *: E => A`), bound where the callback is written, and a named entry there is not
supplied by the callee (§7 item 1).

**Who gives a supplied entry** is rule 5. A definition with a body gives only what it has: `else` hands
`computation` to `runAbort`, `catch` to `runThrow`, and a `with` on a slot's entry is a given implementation. A
body-less declaration gives from nothing, and each platform primitive says what in its own clause —
`escapeInternal[K, A, R](body uses *, Throw[K]: A, …)` in `Throw`'s jvm module, `withCellInternal[S, A, R](initial:
S, body uses *, State[S]: A, …)` in `State`'s (§3.4). A run that breaks the promise is *"'body' is given the effect
'Console' here, which this definition has no implementation of to give"*, or, where a slot binds another
implementation than the default the caller wrote, *"'body' was promised the default 'Console', but runs where a slot
binds another implementation for it"*. So a slot cannot launder an interpretation effect into a pure signature
(§8 item 13).

A supplied entry's own **type arguments** are written at the call from the actual's declaration, matched
entry-by-entry against the slot's clause (an actual `bad` declared `uses Throw[String]` against `Throw[E]` gives
`E := String`). Where no declaration answers, the call spells it — `runThrow[AssertionError, Unit](body)` — and an
argument nothing determines is **rejected** rather than defaulted, which is what stops a `catch` from compiling
against a frame it will not meet at runtime. Three shapes reach that rejection honestly, and all three are ordinary:
the actual is a **parameter reference** (a parameter has no callee whose declaration could state the row — D20 expected a
code parameter's own clause to answer this one, and measured 2026-10-10 it does only in part: `runThrow(body)` over
`body uses *, Throw[String]: Unit` determines `E` with nothing spelled, while `computation catch (err -> 0)` over
`computation uses *, Throw[String]: Int` is still *"Cannot tell which 'Throw' this call supplies"*, so a `catch` over a
parameter still spells its arguments or types its handler); the actual **raises nothing**, so it has no
entry of that ability at all; or the slot's clause names the **same ability twice** (`uses *, Throw[IoError],
Throw[AssertionError]`), where only the call can say which one is being discharged.

Since v5's supply rule was per *entry* and syntactic, a definition could not supply an entry its own declared row
already named. That limitation is **gone**: a slot entry the definition also declares rides by code but is given its
frame where the block runs (D20c's decided (b), §11 — the nearest-frame search this sentence first rested on was removed
by D20c option 5), so a definition may discharge the very effect it declares — which is what lets
`describedAs(body uses *, Throw[AssertionError]: Unit, newMessage: String) uses Throw[AssertionError]: Unit` catch
its body's failure and re-raise a better one.

### 2.3 A field holds a value

A `data` field takes no clause and no row (rule 6). A brace in a field's type is refused as itself — *"Expected a
value type, since a data field holds a value and not a computation (store data describing the work, and perform it
where the effects are in scope)"* — and the parser stops a field's binder at `uses`. Every field is therefore what
it reads as: a value, and a function-typed field a pure function value, which may be stored, passed and returned
like any other (`data Handler(name: String, f: Event => Response)`; `compose` is an ordinary definition).

**An effectful computation has no stored form**, and the reason is frames, not purity. A closure that captured the
platform's native `Console` would be harmless to keep; but `Throw`, `Abort`, `State`, `Writer` and `Dep` are frames
a discharger installs, and frame dependence is a property of the *implementation* — a test's `recordingConsole` is
written over `Writer`, so a stored closure bound to it is a stored `tell` with the same escape risk. What a program
keeps instead is **data describing the work**, performed where the effects are in scope:

```eliot
data Step = Skip | Print(line: String)

def perform(step: Step) uses Console: Unit = step match {
   case Skip -> unit
   case Print(line) -> printLine(line)
}
```

It is testable with a double, and on a microcontroller it is what is wanted anyway: a `data` step is a fixed-size
record, a stored closure a heap allocation. A native that keeps a callback — an event loop's handler table — stays
the platform's axiom. The relaxations that were considered (frame-free capture, binding at the run) are D21's
"Storing effectful computations" and §12's entry B.

*Reversed by D20 rule 6 (§11): until 2026-10-09 a row-typed field was a thunk bound at construction and charged at
the read, with a `with` at the read an error (A7, A11). Its constructor supplied the field's row by `Default`, which
is how a double's transcript stayed empty while the platform's console printed (§8 item 12).*

### 2.4 Naming a row

A **row alias** is a type alias whose body is a row, and it names the row together with its payload. It is the one
construct with no `uses` form yet, so its body is the one place the brace spelling still parses — as the whole body
only (`Expression.rowAliasBodyParser`):

```eliot
type Talking[A] = {Console} A

def greet(name: String): Talking[Unit] = printLine("Hello, " ++ name ++ "!")
def announce(name: String) uses Log: Talking[Unit] = …
```

The alias is an **ordinary type alias** and a use of it an **ordinary application**: `Talking` lowers to
`type Talking[A] = A` — a row is declaration metadata and never a type (§3.3), so it is erased from the alias's body
exactly as a clause never enters a type — and `Talking[Unit]` stays in the signature for the evaluator to reduce.
What crosses the use site is the row's **entries**, with the use's arguments substituted into them and nothing else:
the definition naming the alias mints one marked binding binder per entry and records them in its declared row,
exactly as if they had been written in its clause. So the definition is the one the user could have written by hand
in everything but its return type, which stays the name they did write.

**The alias is an ordinary name, and that is the whole of how a use finds it.** The alias *declares* its row, on its
own declaration, exactly as a `def` writing `uses Console` declares one — `core/…/EffectSugarDesugarer` records it
and mints nothing, since an alias names a row rather than performing one. A use is then the ordinary reading of a
resolved name: `resolve/…/ValueResolver` has already resolved `Talking` through the dictionary, and it reads the
declaration that name resolved to for the row it declares, resolving the entries **in the alias's own scope** before
substituting the use's arguments — the same reading `superConstraints` makes of an ability's own `~` constraints
(§2.5), and the same rule by which a callee's declared row propagates to its caller. So import scope, shadowing,
privacy and qualification are the ordinary ones: an alias crosses files, a binder named `Talking` shadows it, and
nothing is matched by spelling.

A definition's clause and a named row **compose**, because the alias contributes to the return *position*:
`def announce(n: String) uses Log: Talking[Unit]` declares both, and an effect named twice is declared once. That is
how eliot-test's `type Test = {Writer[List[TestResult]]} Unit` is widened where a suite's cases perform for real:
`def testCases uses Console: Test`.

**One limit remains**: it works in **return position only**, every other position being an error rather than a
silent widening. A parameter has its clause, and a set of effects named *in* a clause (`uses Git`, `uses *, Git`) is
**D20a**, open; it superseded D18, whose question — moving a slot rewrite to `resolve` so an alias could stand in a
parameter's type — has no subject once effects are not written in types. The alias landed 2026-09-11 as a splice and
became an ordinary alias and then an ordinary name on 2026-09-12 (§9.3 steps 5 and 8).

What has no spelling is v5's `ability Web[F[_] ~ Console & Log]` — a set of abilities required *of a binder*. With
no carrier binder there is nothing to hang such a requirement on, and a name for a set of *implementations* is
likewise deliberately absent (§12, "not now").

What survives is the ordinary **superability closure** on a `~` constraint: `~ A` is closed under what `A` itself
requires of the parameter this use bound to this binder (`ValueResolver.superConstraints`, transitive and
idempotent). That is a relation between abilities and their parameters, and it never lands an ability on something
it was not written about.

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
than cosmetic. A `uses` entry is parsed as the same `UnresolvedAbilityConstraint`, which is why an entry and a `~`
constraint resolve alike.

### 2.6 What deliberately has no spelling

- **A stored effectful computation** (§2.3), and **a native that keeps a callback** — the platform's to declare.
- **`uses *` on a definition**, and **`with` after a definition's own clause**: each would be a second spelling of
  something the signature already states.
- **An effect variable** (`f uses E: A => B` with `uses E` on the callee, Koka's spelling) and **effects on a function
  type** (`A => B uses Console`). The first needs row unification, the closed inference class; it is also
  unnecessary, since code is resolved lexically and never kept, so every code argument of one call is written in
  one scope and `*` is the one implicit variable there ever is. The second is the row on `VPi` (§12).
- **A row alias with its own AST node.** `type Talking[A] = {…} A` names a row through the `EffectfulType` node the
  parser writes every clause onto (§2.4). A bare set of abilities with no payload (`type Web = {Console, Log}`) is
  not a type and gets no node: paying for it with a new `ast.fact.Expression` case is not how this language grows.
  D20a may name a set in a clause without one.
- **A name for a set of implementations**, and a `with` that binds several at once. §12, "not now".
- **A negative effect.** Discharge is a frame, so a discharged entry simply never joins the clause.
- **Row or binding inference of any kind.** See §5.

*A closed row is no longer on this list.* Until D21 a slot's row said what it supplied and never what it forbade, so
"this argument may perform nothing else" was unsayable, and that is what deleted `eliot.test`'s `pure { … }`. A
clause without `*` says it (rule 4).

## 3. The mechanism

### 3.1 The desugar writes the implementation

**The parser writes a clause onto the row node the rest of the compiler always read** (`Expression.EffectfulType`):
a definition's onto its return type; a parameter's onto the slot's type, or for a function type onto its final
codomain as written; each entry's `with` chain after the type, in the order written. So
`body uses *, Mocking with recording: Unit` reaches `core` as the slot `body: {Mocking} Unit with recording`, and no
phase past `ast` learned the clause — except one bit the row node cannot carry, **closed**, which rides
`ArgumentDefinition.closedRow` into `ParameterEffects.closed` and `CallbackEffects.closed`.

Then two passes, both syntax-directed, both reading declarations only.

**`core/processor/EffectSugarDesugarer`** turns each clause entry and each `~` constraint into **one phantom
generic binder**: a binder of kind `Type` that occurs in the generic list and in **no parameter or return type**, so
rows still never flow into types (§3.3). Three rewrites, and one record:

```
def greeting(name: String) uses Console: Unit  ⟶  def greeting[Impl](name: String): Unit     -- Impl ~ Console[Impl]
def sort[T ~ Ord[T]](xs: List[T])              ⟶  def sort[Impl, T ~ Ord[Impl, T]](xs: List[T])
computation uses *, Throw[E]: A                ⟶  computation: Unit => A                     -- EffectRow.parameterEffects
action uses *: A => Unit                       ⟶  action: A => Unit                          -- EffectRow.callbackEffects, arity 1
```

The last line is the record: a function-typed code parameter keeps its type, which is the same arrow a value's is,
so `EffectRow.callbackEffects` records it with its arity (the arrows before the codomain the clause stands on) —
the one thing that tells `action` from `updateState`'s `f`.

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
them exactly as `EffectSugarDesugarer` mints entries written in a definition's clause.

**`row/BindingWriter`**, run by `RowElaborationProcessor` between the recursion gate and saturation, then
rewrites one definition so that every reference carries the implementation each of the callee's phantom
binders stands for. Three jobs, one walk:

1. **write the bindings** — each binding binder, read off the callee's marks, is given a value by the
   resolution order below, merged by index with whatever else the call determines. A binder is never left to a
   metavariable.
2. **thunk and apply** — an actual delivered to a nullary code slot is wrapped in a lambda, and a reference to one
   of *this* definition's nullary code parameters is applied to `unit`. Doing both unconditionally is what makes a
   pass-through (`runAbort(computation)`, `val restFailures = rest`) come out right with no inspection of the
   argument's shape or type: wrap and apply are inverse, so a pass-through is an η-expansion.
3. **erase `with`** — the node exists to put a binding in scope for its subject; once the subject's references
   carry it, it is dropped, from a body and from a slot's type.

**Where a binding comes from — the resolution order**, walking outward lexically:

1. the nearest enclosing `with` for that ability;
2. this definition's own phantom binder for it — a **received** binding, filled by its caller. There is no
   graph to sum: forwarding is the enclosing signature, read once;
3. for an actual at a code slot, the entries that slot **supplies** (§2.2) — bound by the entry's own `with`, or by
   `Default` with none, which rule 5 then holds the callee to giving;
4. `Default`, "search at the ground arguments" — the two-site resolution — for an ordinary **ability**. For an
   **effect** there is no default: an uncovered one is *"This value performs the effect 'X' but does not declare it;
   add it to its `uses` clause"*, reported here at the reference.

**A binding is for an entry, not only an ability.** Each step above looks for a binding of the reference's ability
that **answers its entry**: one is passed over only when both sides know their type arguments and they differ —
known meaning ground once the call's determined arguments are written in (`BindingWriter.knownArguments`), rendered
and compared. So inside `catch[Refused, String](checked(…), …)` the slot's `Throw[Refused]` does not answer
`checked`'s `Throw[Missing]`, which goes on to the enclosing definition's own entry or is the "performs but does not
declare" error; and the same comparison decides whether a slot entry **rides** (§2.2), so `mocked`'s own
`Throw[AssertionError]` does not make its slot's `Throw[IoError]` ride. An entry still generic in some binder
(`raise`'s `Throw[E]`) answers the nearest binding of its ability (`OverDeclaredEffectIntegrationTest`).

**Code and values are told apart by where a lambda stands, read off the declaration** (rules 2–4). A lambda is
walked as code only where it is the argument of a code parameter (up to that parameter's arity), the head of an
application, or an arm of a lowered `match` (`handleCases`/`typeMatch` and the `$selector` the lowering applies to
its arms, which are the author's code in place). Anywhere else it is a value, walked in `Scope.enterValue`: every
binding from outside is marked, so an effect taken from one is the rule-2 error, and a code parameter referenced
inside is captured, the rule-3 error. An argument at a closed slot is walked in `Scope.enterClosed`, the same cut
with the slot's riding entries left reachable. A run of one of this definition's code parameters is held to what
its caller's write promised (`BindingWriter.checkGiven`, rule 5): each entry the slot supplies must be bound there
by a callee's slot that supplies it (a `Binding` marked `bySlot`) — a row of the definition's own is not a frame,
nor is a `with` in the body.

**Effect-ness is read from one place only**: the callee's declaration. An ability appearing in
`effectRow.returnEffects` is an effect at this reference — which for an `effect`'s member is what membership
recorded, and for an ordinary definition is what its `uses` clause says. A `~` constraint's ability is in no row,
so it defaults. Nothing keys on a name or a shape.

**Both halves of a definition are written, because both hold references.** A guarded return
(`def head[COND: Bool]: if(COND, String[]) else raise("empty")`) is compile-time code in type position, and
its `if`/`else`/`raise` are ordinary calls with code slots and phantom binders. What differs is only the scope
check: a signature's effects are the guard channel's vocabulary, discharged by the guarded-return read rather than
performed, so an uncovered one defaults instead of being reported, and the value and closed cuts do not apply. The
same exemption covers the platform **run boundary** (`row/RunBoundaryFunctions`), which is where every effect's
chain ends.

**Pure code is untouched**, and so is code that only forwards: a definition with no clauses, no constraints and
no callee with one is returned unchanged.

Two mechanical invariants of the pass, both of which cost real bugs to learn:

- **Position fidelity.** The walk returns the **original** nodes when nothing changed. Rebuilding an equal
  spine re-attributes it to per-argument positions, silently moving every diagnostic anchored at a call and
  duplicating LSP hover hints.
- **The universe is built by demand, not guessed.** `RowChecker.Universe.onMiss` reports every name consulted
  but absent; the processor fetches exactly those and repeats until a round misses nothing new. Guessing would
  fall back to unknown-callee approximations, and an unwritten binder silently runs on the platform's default.

### 3.2 What the write may consult

Declarations, and nothing else: the callee's declared parameter and return types, its declared row and which of
its parameters are code (with their arity and whether closed), its membership in an `effect`/`ability` block, the
implementation names a `with` resolved to, the run-boundary registry, and one level of type-alias expansion inside
those signatures.

v5 needed a written *whitelist* here (§5's retired rule 5), because its elaborator had to classify each
slot — carrier-headed or payload, pinned or open — and every classification is a place an approximation can
accrete. **There is no classification left to approximate**: a parameter either has a `uses` clause or it does
not, and a lambda is code or a value by the slot it stands at, both read off the declaration. A rule that inspects a
*sibling argument's expression shape* is inference, not desugaring, and is still prohibited; a decision that cannot
be made from a declaration is a gap to close **in the declarations**.

The fail-safe direction is built in: a missing write is an aborted definition with a violation at its own
position, never a binding silently taken from the platform's default.

### 3.3 One verifier, and one precondition

Checking a runtime term yields a **payload type** (the existing NbE judgment, which never sees an effect)
and a **row** (a second output, exactly as an `Int`'s range lives in the refinement channel beside the type,
not inside it). Row constraints are set-shaped: union for sequencing, inclusion for boundaries
(`derived ⊆ declared`) — commutative and order-independent, so no argument-order sensitivity can exist. The surface
now says the same thing: a clause is declaration metadata on a definition or a parameter, never part of a type, so
`unify` sees `Function[A, B]` on both sides of a code parameter and a value alike.

**The verifier is the scope check**, per definition, pre-monomorphization: not a separate pass but the write's
own walk (§3.1). An operation or a callee with a clause needs a covering declaration, and the only places one can
come from are the enclosing def's clause or constraints, an enclosing `with`, or a slot's clause. It is complete
before monomorphization, since nothing about it is instantiation-dependent; it emits *"This value performs the effect
'X' but does not declare it; add it to its `uses` clause"* at the reference, and it owns the code/value, closed and
given checks of rules 2–5. *Forward what is declared, derive what is done* — a forwarded per-operation verdict would
be a checker self-report and is rejected, as is any negative-effect surface.

There **was** a second one, post-monomorphization, re-deriving each ground instantiation's row and checking it
against the declaration. D7 (§11, closed) retired it, on the measurement it asked for rather than on the argument: under
names its "performs X" could only mean "a reference forwards a **received** binding to a callee declaring X as a
row entry", which sees propagation through a *declaring callee* but never a direct operation call — by then
`AbilityResolver` has rewritten `printLine` into the implementation method, which declares no row. Its coverage
was therefore a strict subset of the scope check's with no case of its own, and a second place to maintain one
diagnostic. **Do not reintroduce a post-mono effect verifier**: what a monomorphic body can still say about
effects is strictly less than what the declaration walk already said.

What survives at that seam is **not** its shadow: `monomorphize/channel/SuppliedRowArgumentsProcessor`, wired as
a **codegen precondition** via `getFactOrAbort`, rejecting a supplied entry whose type argument nothing
determines (§2.2): *"Cannot tell which 'Throw' this call supplies … Write it out at the call"*. That check needs
ground arguments and so cannot move earlier.

### 3.4 The three primitives

A resuming clause is a call and needs nothing. A finishing clause is a **non-local exit**; a stateful
implementation needs a value **threaded through calls that never mention it**. Neither is expressible as a def
in a strict pure core — under v5 both were expressed by the transformer instances, which is the whole reason
the carrier existed. They are now three **platform-private leaves**, one per target, and nothing else:

| primitive | shape | jvm | microcontroller | compile track |
| --- | --- | --- | --- | --- |
| escape | `escapeInternal[K, A, R](body uses *, Throw[K]: A, onExit uses *: K => R, onValue uses *: A => R): R` — abortive, never re-entered; the frame is the machine stack | an exception, one class per instantiation | a status flag and a jump (hypothetical, §13's A2) | an evaluator intrinsic |
| cell | `withCellInternal[S, A, R](initial: S, body uses *, State[S]: A, combine uses *: A => S => R): R` — scoped to one call, saved and restored around it | a static field per instantiation | a register | an evaluator intrinsic |
| loop | `foreverInternal` | `while(true)` | the super-loop | never runs (`Inf` is stuck) |

The control effects' single implementations are written over them: `Throw[E]`'s `raise` is `exit`, `State[S]`'s
`state`/`putState` are `read`/`write`, `Writer[W]` appends to a cell, `Dep[T]` reads one. The frame an operation
reaches is the **nearest enclosing** one of its instantiation — the machine stack's discipline, inside the leaf
and nowhere else. **Each copy states in its own clause what it gives its `body`** (rule 5): `Abort`'s
`escapeInternal` gives `Abort` and `Throw`'s gives `Throw[K]`; the cell copies give `State[S]`, `Writer[S]` and
`Dep[S]`. Every discharger hands its code to one of them, which is what makes a body-less declaration the only
place an effect is given from nothing. The compile track's `escape`/`withCell` are generic over a key and cannot
name one effect; that track is never reported, so they state nothing.

They are **private, and the dischargers are therefore abstract in the base.** A public cell is Landin's knot —
a cell holding a closure that reads the cell is a loop, and `termination/PurityGuardTest` exists to keep it out
— so `withCell` may not be a base name, and `escape` follows for uniformity. `runThrow`, `runAbort`,
`runStateToPair`, `runWriterToPair` and `provide` are body-less signatures in the base and bodied per platform;
`catch`, `else`, `runStateToValue`, `runStateToFinalState`, `runWriterToValue` and `runWriterToLog` are ordinary
platform-independent bodies over those and stay in the base, where the base-layer rule says they belong.

Eliot has no *layer*-private visibility — `private` is module-scoped — so each jvm module needing a primitive
declares its own copy. That is five copies of two shapes, and it is the right trade: one public `eliot.jvm.Cell`
would put a mutable cell in reach of any jvm program. Repetition also separates the frames for free, since the
backend keys a frame class on the name that installed it, and it is what lets each copy name the one effect it
gives.

### 3.5 Discharge

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or
thread through, and the entry simply never joins the clause — so there is nothing to spell as a negative effect,
and a wrapper-reached discharge inside a `uses Console` body just compiles.

**Nesting order at the run site decides interaction**, and it is written where the frames are installed:
`runStateToPair(s, runThrow(c))` versus `runThrow(runStateToPair(s, c))` is the difference between state
surviving a `raise` and not. There is no canonical form to fix and no ordering for the compiler to choose.

A discharger may be called any way a function can be — v5's "a discharger must be called directly" rule has no
subject, since `.`'s `f uses *: A => B` is a code slot and passes the call through as the code it is. A
discharger's **handler may itself perform effects** (`onError uses *: E => A`, bound where it is written).

**A `val` is a bind, and binds the value** (D17, decided 2026-09-12). A `val`'s right-hand side is a value
position, so rule 1 applies to it unchanged: `val x = lookupConfig("db.url")` *runs* the lookup there, charges
the enclosing definition, and binds `x` to the `String`. There is nothing suspended left to discharge, so
`val x = comp` followed by `x else fallback` is the error it looks like — reported on the right-hand side,
where the effect was actually performed — and the discharge belongs on that right-hand side instead
(`val x = lookupConfig("db.url") else "gave up!"`). A `val` is therefore **not** a new kind of position, which
is what keeps rule 2's single predicate single — and a `val`-bound lambda is a value, so it may use no effect from
around it.

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
declaration (§3.1). Whether a parameter is code is read the same way — off its clause, never off its type's shape,
which is why D21 moved the predicate from the type to the clause. The `<Ability>Carrier` naming convention, an LSP
reverse table and "has an `Effect` instance" all miscompiled in both directions under v5 and are prohibited; under
v6 there is no carrier to recognise at all, so the prohibition has nothing left to guard and is kept only so it is
not reinvented.

Nothing of carrier identification remains: the dead `Effect`/`Suspend` pocket (`EffectMachinery`,
`EffectCarriers.declaredEffects`, `ModuleName.carrierPackage`) was deleted on 2026-09-10 (`90b74273`). The one
structural predicate that survived is **not about effects**: `CarrierKindChecker.isHktBinder`, "is this binder
higher-kinded?", which `check/CarrierKindChecker` asks in order to reject a `[F[_]]` binder instantiated at a
fully-applied proper type. That is a kind system living next door, and it is soundness (§12).

### 3.7 Rendering

There is nothing to invert. An effect is an ordinary nullary ability, an implementation is a name, and
code is a thunk, so a type contains no machinery to hide: `GroundValueRenderer` prints what the user
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
  the same ability), this definition's code parameters in scope — `thunks` (nullary, whose references apply to
  `unit`) and `callbacks` (function-typed) — and whether the region is a **value** or a **closed** argument.
- **a reference** to a callee with phantom binders resolves each binder by §3.1's order. An **ability** with
  nothing in scope binds `Default`; an **effect** with nothing in scope is the error, at that reference.
- **entering an argument** at slot *i*: the walk descends with the slot's supplied entries bound (to the entry's
  `with`, else `Default`) iff parameter *i* has a clause, and in the closed cut iff that clause has no `*`. A
  lambda at a parameter with no clause — or in any other value position — is entered in the value cut: bindings
  from outside are marked, and an effect taken from one is the error at that use.
- **a code parameter is used, never kept**: a reference to one stands as the head of a call or as the argument of a
  code parameter; anywhere else is the error at the reference, and so is a reference inside a value lambda or a
  closed argument.
- **a run of a code parameter is given only what the definition has**: each entry the parameter's slot supplies is
  bound at that run by a slot that supplies it, as the caller's write promised; otherwise the error names the
  parameter and the effect.
- **a `with`** binds its ability for its subject's text, and crosses a def boundary only through a
  declaration. A `with` whose subject contains no covered use and no call to a declaring def is a hard error
  naming the fix, never a silent no-op (not yet enforced: §8 item 1).
- **a clause on a named implementation is charged at the binding site**: `greeting("Bob") with recordingConsole`
  performs `Writer[String]` there, because the name's clauses declare it, and the binding for it comes from the same
  order.
- **two exemptions, both because something else answers**: a **signature**, whose `raise`/`abort` is the guard
  channel's vocabulary and is discharged by the guarded-return read; and a platform **run boundary**, where
  every effect's chain ends and each of `main`'s entries is bound to the two-site `Default`. Neither is cut into
  value or closed regions.
- **a declared entry must be consumed** (decided 2026-10-05): an entry of a definition's own clause that no
  reference in its **body** is written from is the error "This value declares the effect 'X' but does not perform
  it", at the entry, with the help *"Remove 'X' from its `uses` clause"*. A clause says *my caller hands me an
  implementation of these*; one nothing here uses asks every caller to declare an effect on no evidence, all the way
  to `main`. Consumed means written from at least once — by an operation, a declaring callee, or a `with`'s clause
  bindings — on any path, so `if(ok, v)` consumes `Abort` whatever `ok` is. Code the
  caller wrote was bound in the *caller's* scope (§2.1), and an entry of its slot that the definition's own clause
  has rides to the caller too (§2.2) for its code, ~~so running it consumes nothing~~ — **reversed 2026-10-12 by
  D20c's decided (b)**: its frame is given where it runs, so running it consumes the definition's received entry, and
  `def passThrough[A](v uses *, Console: A) uses Console: A = v` is accepted. Its `uses *` twin lists nothing, passes
  nothing and still consumes nothing. Exempt: a body-less
  value (its clause is a contract a later layer bodies), a run boundary, and a signature's own references. An
  `implement` clause declares the union of its block's clause entries (`ImplementationRows`), so it is held only to
  the entries it wrote. The error waits for the definition's callees: a callee performing the effect *undeclared* is
  its usual cause, and that callee's error is reported instead (`RowElaborationProcessor.calleesWritten`).

**Suspension is row-neutral.** Whether a parameter is a value or code changes only *when* the effect runs and
*whose* declarations bind it — never whether the caller must declare what it performs.

**Two same-ability entries** at different arguments are two entries; at identical arguments they deduplicate.
In one slot's clause they are the shape §2.2 requires the call to spell.

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
   ordering-sensitive slot decision, a row variable unified across arguments, or a sum over a monomorphized
   sub-graph. Carrier inference was the historical bug class (carrier theft, premature commitment); binding
   inference would be the same bug in the new vocabulary, and **dynamic scoping resolved at compile time** is its
   exact shape. Both are prohibited. `*` is not a variable: it names the scope the argument is written in.
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

Production code that declares a clause commits to no interpretation: it names no implementation, so whoever runs
it decides. In production that is the synthesized entry point binding each of `main`'s entries to the platform's
default; in a test it is one `with`.

```eliot
effect Terminal {                                  -- the application's own effect
   def write(line: String): Unit
   def read: String
}

def greet uses Terminal: Unit = {                  -- production code, untouched by the test
   val name = read
   write("Hello, " ++ name ++ "!")
}

implement session: Terminal {                      -- the test's double, in the test module
   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
   def read: String = "Bob"
}

def greetTranscript: String = runWriterToLog(greet with session)
```

Four properties fall out of the design rather than being added for testing.

- **A double is one declaration.** It needs no type to hang on, no colocation with the ability or a carrier,
  and no coherence question: a named implementation is never searched, so it may freely overlap a default.
  *Never searched* is enforced rather than assumed: the two-site search and both coherence checks read
  `ModuleAbilities.anonymousImplementationMethodsOf` / `anonymousMarkersOf` (§8 item 7). Colocation is allowed,
  so it had to be safe.
- **A double cannot cheat.** A user module cannot declare a native, and the platform's natives and primitives
  are private to its layer, so an implementation reaches the world only through effects **its own clauses
  declare** — which are charged, and bound, at the binding site.
- **A double keeps its own state through an effect**, not through a carrier: `session` above writes to
  `Writer[String]`, and that entry is charged where `session` is bound and discharged there by
  `runWriterToLog`. It never appears in `greet`'s clause.
- **Interpretation is per effect, not per program.** `body with mockConsole with mockFileSystem` binds two
  doubles and leaves everything else at its default; under v5 one type argument decided every effect at once.

**A discharge word binds doubles on its slot's entries**, so a faked case writes no fixture at all:
`transcriptOf(program uses *, Console with recordingConsole: Unit)` in `examples/src/EffectsTestFramework.els`, and
eliot-test's `mocked`, whose slot binds all seven of its doubles and recorders entry by entry
(`body uses *, Console with mockConsole, …, Mocking with recording, Calls with journal, …`). Rule 5 is why
`recording` and `journal` are named rather than anonymous: a definition with a body may give its slot only what it
has, and a `with` is what it has. A case then reads `"…" should "…" in mocked { … }`, a suite's whole declaration is
its return type — the row alias `type Test = {Writer[List[TestResult]]} Unit` — and a suite whose cases perform for
real composes it with a clause, `def testCases uses Console: Test`. A case that must perform nothing beyond its
assertions can now say so with a closed slot (`body uses Throw[AssertionError]: Unit`, rule 4); whether eliot-test
reinstates `pure { … }` is its call. `examples/src/EffectsNamedEffect.els` is the minimal version of the above, and
`EffectsFakeConsole.els` does it for a stdlib effect.

**What this deletes from v5's testing story**, all of it symptom rather than design: the fake carrier and its
`Effect` instance; the rule that a fake gets no lifting because it has no `Suspend` (the n² cross-lift wall);
the region rule and the `{| Recorded}` capture tag that opted out of it; "do not stack over a fake"; and
run-then-assert as a necessary shape. `Dep[X]` + `provide` remains available for a seam you want stated in the
signature, and swapping the platform layer remains the whole-program integration answer.

## 7. Live limitations

Each is stated, fail-safe, and either has a plan entry or is a deliberate trade. (The former item 1, "a row cannot
be closed", is gone: a clause without `*` is a closed row, rule 4.)

1. **A function-typed code parameter's own entries are not supplied.** `f uses *, E: A => B` writes `E` on the
   codomain, where the callee never supplies it, so code using `E` there is rejected rather than run on a binding
   nobody gave. Fail-safe, carried from D21 step 4, and no file in the corpus needs it; a nullary code parameter's
   entries are supplied as §2.2 says.
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
5. **A row alias works in return position only, and has no `uses` form** (§2.4). Its body is the one place the
   brace spelling parses, and a set of effects cannot be named in a clause, so a slot that needs many effects
   writes them out. Naming a set in a clause is **D20a**. The alias's *other* former limit — that it had to be
   declared in the file using it — is gone: it is reached by ordinary name resolution.
6. **An effectful computation cannot be stored** (§2.3). Deliberate: what a frame-bound implementation captures
   cannot outlive its frame, and data describing the work covers the trampoline while keeping every step testable.
   A native keeping a callback is the platform's to declare; opting in to frame-free capture is §12's entry B.
7. ~~**An undischarged control effect reaching `main` fails at runtime, not at the boundary.**~~ **Closed
   2026-10-12** by D20c option 5: "The effect 'Throw' is performed here with nothing to discharge it", at the
   operation. The run boundary
   binds every entry of `main`'s clause to the two-site default, and a control effect's single implementation then
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

**2026-10-05.** **D20** is decided — and, as D21 amends it, **built in full by 2026-10-10**: effects are parameters
(`uses`), code a definition is handed is called or passed on and never kept, a function parameter without a clause is
a pure value, and a field holds a value. Designing it found **five more silent divergences**, §8 items 9–13, all of
which D20 and D21 closed. It supersedes **D18**. Rewriting Part I for it found one more, §8 item 14.

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
5. ~~**An undischarged control effect reaching `main` fails at runtime**~~ **Closed 2026-10-12** by D20c option 5
   step 4: the platform's control defaults take a frame and `main` holds none, so it is a compile error where the
   effect is performed. Not at the boundary (§7 item 7).
   **Decided 2026-09-12: it must be a compile error.** The mechanism was measured rather than argued and is
   written up under **D19** (§11); one cost has to be chosen before it lands.
6. **A row alias works in return position only** (§2.4). Interim by design. §9 step 5 made the alias itself an
   ordinary *definition*; step 8 (2026-09-12) made it an ordinary *name*, which lifted the file-local limit and
   the misreadings that came with it (§9.3). What is left is the position: **D18** (§9.5) was the plan for it and
   is superseded by D20, whose effects are no longer written in types; naming a set in a clause is **D20a**.
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

9. ~~**Rule 4's third bullet is not enforced: a lambda at a rowless arrow reaches the enclosing bindings.**~~
   **Closed 2026-10-09 by D21 step 1, by rejecting it** (below).
   `def applyTwice(f: String => Unit): Unit` called as `applyTwice(s -> printLine(s))` from a `{Console}`
   definition compiles and prints, exactly as `f: String => {} Unit` would: the two spellings behave identically
   for a lambda written at the slot. So the tree already treats every function parameter as a block, just
   without the restriction that makes that safe (item 10). D20 makes this the rule (rule 2) rather than the
   accident, and adds the restriction (rule 3).
10. ~~**A function parameter can be kept, and its lambda runs after the call that bound it.**~~ **Closed 2026-10-09
    by D21 step 1.** Rows erase from
    types, so `capture(f: String => {} Unit): Holder = Holder(f)` stores the block in a rowless field. With
    `leaky: {Console} Holder = capture(s -> printLine(s))`, a later `runIt(h: Holder): Unit` — declaring
    nothing — prints. With `escaped: Holder = catch[String, Holder](capture(s -> raise(s)), …)`, the `raise`
    runs after `catch` has returned and the program dies with a bare `RuntimeException` from
    `Throw.exitInternal`: a frame the type system said was there is not. D20 rule 3.
11. ~~**A lambda written in a value position captures the enclosing bindings.**~~ **Closed 2026-10-09 by D21 step
    1.** `built: {Console} Holder =
    Holder(s -> printLine(s))` — no function parameter involved — compiles, and the stored lambda prints when a
    def declaring nothing runs it. D20 rule 4.
12. ~~**A data constructor supplies a field's row by `Default`.**~~ **Closed 2026-10-09 by D20 step 3.** With `data Job(run: {Console} Unit)`,
    `makeJob: Job = Job(printLine(…))` declares nothing, because the constructor *supplies* `Console` (it is an
    entry the constructor's own row lacks, §2.2) and binds the **platform's** console. A test reading the job
    through `useJob(j: Job): {Console} Unit` under `with recordingConsole` gets real output and an empty
    transcript: the read is charged to a binding the stored thunk never uses. A11 rejected a `with` *at* the read;
    this is the same lie one definition further out. D20 rule 6.
13. ~~**A slot launders an interpretation effect.**~~ **Closed 2026-10-09 by D20 step 2.** `launder(body: {Console} Unit): Unit = body` supplies `Console`
    by `Default`, so `looksPure(name: String): Unit = launder(printLine(…))` performs real I/O with a pure
    signature, and an outer `with recordingConsole` binds nothing (which is item 1, the unused `with`). The
    two-site default meant for `main` is reachable from any definition with a body. D20 rule 5.
14. **An unapplied reference to a `uses` definition passes as a value.** Found 2026-10-10, probing while Part I was
    rewritten for D20/D21. D21 rule 2 (§1 rule 2) says a reference to a `uses` definition that is neither the head of
    an application nor the direct argument of a `uses` slot is rejected like an effectful lambda in a value position;
    D21 step 1 built the lambda half and not this one. `data Holder(action: String => Unit)`, `def built uses
    Console: Holder = Holder(printLine)` and `def runIt(h: Holder): Unit = action(h)("…")` compile, and `main uses
    Console: Unit = runIt(built)` prints from a definition whose signature is pure — item 11's defect through a name
    instead of a lambda, and the silent family (standing rule 8). The fix belongs beside `Scope.enterValue`: a
    reference whose callee declares a clause, standing at a value position, is the rule-2 error, exactly as an
    η-expanded `s -> printLine(s)` there already is.
15. **An effectful ability method needs its ability's type arguments spelled.** Found 2026-10-10 while the
    `eliot-code` skill was rewritten. With `ability Greeter[A] { def greetAs(a: A) uses Console: Unit }`,
    `greetAs(Bob(…))` is rejected — *"Cannot pass the implementation of 'Console' … behind a type parameter this call
    does not determine"* — although the argument determines `A`; `greetAs[Bob](…)` compiles. Loud, not silent, so it
    is a usability gap rather than a soundness one; the cause is not yet traced.

**Items 16–17 were found on 2026-10-12**, probing while D20c (§11) was analysed, and **items 18–19** by D20c option 5's
first spike. They were numbered 14–17 on the line they were found on, and D20c's record below keeps those numbers.

16. ~~**A helper's own `catch` swallows its caller's `raise`.**~~ **Closed 2026-10-12** by D20c option 5 step 4.
    `guarded(body uses *: Unit)` runs `body` inside a private `catch[String, Unit]`; a caller writing
    `guarded(raise("boom"))` inside its own `catch[String, Unit]` saw the helper's handler run and its own code carry
    on after the call. Accidental handling: a library's private use of `Throw` intercepted every block it ran.
17. ~~**A helper's own `State` receives its caller's `putState`.**~~ **Closed 2026-10-12** by D20c option 5 step 4.
    `counted(body uses *: Unit)` runs `body` inside a private `runStateToFinalState("helper", …)`; a caller's
    `putState` written for its own `State[String]` landed in the helper's cell, and the caller's state was unchanged.
18. ~~**A slot's named implementation performed its clauses' effects into whatever frame enclosed the run.**~~
    **Closed 2026-10-12**, on both lines: `transcriptOf(program uses *, Console with recordingConsole: Unit)` whose
    body runs `program` without discharging the double's `Writer[String]` compiled clean, and its lines landed in an
    unrelated caller's log, or it died with a `NullPointerException` with no frame at all. Each such clause effect is
    now promised to the block like the slot's own entries (D20 rule 5); one the definition itself declares rides, and
    by (b) is given the definition's own frame where the block runs.
19. ~~**Two entries of one effect are one binding in the walk.**~~ **Closed 2026-10-12**: for frames by option 5's
    2(c) — the threading chooses a frame by entry at ground arguments, so `uses Throw[IoError], Throw[GitError]` is
    two frames — and in the walk by "the row phase binds an effect entry, not just its ability" (§3.1), where a
    binding answers a reference unless both know their arguments and they differ.

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
there is the ordinary "nothing gives this" error. The routes and their costs are kept as the record. **Built
2026-10-12** (option 5 step 4): "The effect 'Throw' is performed here with nothing to discharge it", at the
operation.

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

**Decided 2026-10-05. Steps 1–6 built 2026-10-09** (as D21 amends them: §8 items 9–13 closed, the `uses` clause
parsed, its closed form enforced, the corpus migrated to it, and the row spelling deleted but for a row alias's body);
**step 7 built 2026-10-10** — Part I rewritten to it. It changed the surface and three of Part I's former rules; the
mechanism of §3 — phantom binders, the write, `with`, the primitives, the one verifier — stayed. **D21 (below,
2026-10-09) amended this decision's rule 2 and its `=> A` spelling** — a function-typed parameter is a value, and
caller's code is marked `uses *` — and records the reversals; the two landed together, and this section is kept as
decided so its citations resolve. Part I, not this section, is the statement of what shipped.

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
   slot that gives `with recordingConsole`.
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
2. **Rule 5** — the give-only-what-you-have check; the platform primitives' copies declare what they give; eliot-test
   names `recording`/`journal`. Closes item 13. Gate: §10; eliot-test's suites green.

   **Built 2026-10-09.** A row-typed parameter's caller wrote its actual's bindings from the declaration, so each entry
   the slot supplies by `Default` — and each effect a slot's `with` implementation performs in its own clauses — is a
   promise. `BindingWriter.checkGiven` holds every run of the parameter to it: the entry must be bound there by a
   callee's slot that supplies it (a `Binding` marked `bySlot`), by `Default` as promised. A row of the definition's own
   is not a frame (the entry would ride, not be supplied), nor is a `with` in the body; a slot binding another
   implementation is *"promised the default … but runs where a slot binds another implementation"*, and nothing at all is
   *"'body' is given the effect 'Console' here, which this definition has no implementation of to give"*. The five jvm
   primitive copies now state what they give — `escapeInternal`'s `body: {Abort} A` and `{Throw[K]} A`,
   `withCellInternal`'s `{State[S]}`, `{Writer[S]}`, `{Dep[S]}` — and every discharger hands its computation to one.
   The compile track's `escape`/`withCell` are generic over a key and cannot name one effect; that track is never
   reported, so they are left as they are. eliot-test names `recording: Mocking` and `journal: Calls` and binds them on
   `mocked`'s slot (green under `v0.7` and under this compiler). Gate: `./mill __.test` (the same five banner-sensitive
   tests aside), 47 jars byte-identical, eliot-test 183 green; witnesses in `GivenEffectIntegrationTest`.
3. **Rule 6** — reject a row on a field, and delete the stored-read machinery it leaves without a subject
   (`chargeStored`, A11's `byWith` read rule, `calledSpine`'s dot-read view if nothing else reads it,
   `StoredComputationIntegrationTest`). Closes item 12. No example stores a computation, so the jars do not move.

   **Built 2026-10-09.** `EffectSugarDesugarer.rowErrors` reports a row anywhere in a field's type — top-level,
   `{}`, an arrow codomain, a pinned one — as *"A data field holds a value, not a computation, so its type cannot
   carry an effect row"*, and `DataDefinitionDesugarer` lowers every field to its payload first, so the constructor,
   the accessors and the eliminator see a value and that error is the only diagnostic. With no field row left to
   record, `EffectRow.returnThunkEffects` is gone, and with it the `row` phase's read rule: `runStored`, `storedRow`,
   `chargeStored` (A11's `with`-at-the-read rejection) and `calledSpine`, whose dot-read view nothing else consulted.
   `Binding.byWith` stays, for `checkGiven`. `StoredComputationIntegrationTest` and the three stored-`Inf` cases of
   `TerminationIntegrationTest` are deleted; `FieldValueIntegrationTest` holds the witnesses, item 12's program among
   them, and the replacement shape — data describing the work, performed where the effects are in scope. Gate:
   `./mill __.test` green, all 46 example jars byte-identical, eliot-test 183 green under this compiler.
4. **The parser accepts the new surface** — `uses` on a definition and a parameter, `=> A` in parameter position —
   onto the *existing* `EffectRow` metadata, so no phase past `ast` learns anything. `A => {} B` and `A => B` are
   one shape (§8 item 9), so dropping the codomain `{}` must be byte-identical; that is this step's gate.
5. **Migrate**, by script, every file listed under the costs. Gate: byte-identity over the 45 examples.
6. **Delete the old surface**: a row in a result or a parameter type is a parse error naming the `uses` spelling.
   **Built 2026-10-09**, as D21's step 6 records.
7. **Documents**: Part I rewritten to the model above, the CLAUDE.md cornerstone, the user docs' effect chapters
   and the `eliot-code` skill.

   **Built 2026-10-10.** Part I is rewritten in the `uses` surface and states D20 and D21 as shipped. §1's four rules
   became six — D21's order, rule 2's predicate the clause (the former rule 4, whose erosion table stays with it),
   rule 3 the keep rule, rule 4 the closed clause, rule 5 the given check, rule 6 a field holds a value — with a
   paragraph mapping a citation to the old numbering. §2 opens with the clause and its table, and the brace spelling
   is stated as a parse error but for a row alias's body; §2.1 is `uses *` (the empty row's successor, and the base's
   per-signature choice between a pure function parameter and code); §2.2 adds rule 5's "who gives a supplied entry";
   §2.3 is "a field holds a value", with the store-data shape in place of the stored computation; §2.4 composes an
   alias with a clause and points its remaining limit at D20a; §2.6 lists what has no spelling now and records that a
   closed row has one. §3.1 says where the parser writes a clause, gains the `callbackEffects` record and the
   code/value walk; §3.4's table carries the primitives' real signatures and what each copy gives; §4 replaces the
   stored-read bullet with the keep and given checks; §7's first and sixth items are the carried
   function-typed-code gap and the deliberate absence of stored computations. Rewriting it found **§8 item 14**.
   Outside this document: the CLAUDE.md cornerstone, the user docs site's effect chapters (eliotlang.github.io
   `_docs/`), and the `eliot-code` skill, whose SKILL.md is delivered separately because the skill is synced from
   outside this repository.

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
- **D20c — an open block**, below: whether a stored computation may have its effects handled where it runs. Its
  option 5 — an implementation's environment passed, never searched for — is decided and built.

#### D20c — an open block: a stored computation whose effects are handled where it runs

*Merged 2026-10-12 from a line of work parallel to D20/D21's steps 1–7. It was written before the `uses` surface
landed, so its programs use the brace spelling — `body: {Throw[E]} A` is `body uses *, Throw[E]: A`, `body: {} A` is
`body uses *: A`, and `def f: {Console} Unit` is `def f uses Console: Unit` — and its §8 items 14–17 are §8 items
16–19 above. Its D20 steps 1–3 duplicated D20/D21's own and were dropped in the merge; option 5 and (b) were ported.*

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
   `Writer` for nothing, and the double writes into the caller's log. **Reversed 2026-10-12 by decided (b)**, extended
   to a slot's named implementation: the double's ridden clause effect keeps the caller's code but takes its frame
   where the block runs, so `transcriptOf: {Writer[String]} Unit = program` runs the double on `transcriptOf`'s own
   frame and performs `Writer` by running it. Declaring it and then running the block under one's own discharge of it
   is still a declaration for nothing — the received frame is never used — and the undeclared form, supplied by that
   discharge, is the one to write. Under option 5 the double's environment is
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
entries of one effect are told apart by their instantiation, and two that coincide at one, at the same distance,
are a use-site error.
Reversed (standing rule 1): the amendment's "mint a second phantom binder per entry for the environment's type".
Standing rule 3 stands: the walk still writes every binding from declarations and nothing infers one. What the
ground arguments choose is only *which declared entry's frame* a reference uses, which no binding names; where that
choice and the written binding disagree, it is an error, never a silent pick.

**Option 5 built (2026-10-12, steps 1–4), in today's syntax.** What exists, and where it departs from the text
above:

1. **An anonymous implementation takes parameters** — `implement[S] State[S](cell: StateCell[S]) { … }`. Each clause
   receives them as leading value parameters, so the checker types a clause body as any other; a body-less
   `$parameters` declaration in the implementation's namespace records them for the conformance check and the
   threading (`ast/fact/ImplementationParameters`). Named implementations do not take parameters yet, and `with`
   takes no arguments yet: nothing in the tree needs either, because of item 4.
2. **A value's mono records its effect entries at ground arguments** (`MonomorphicEffects`), read off the
   constraint the desugar puts on each binding binder, and a dispatched reference records the member's entry and
   binding (`MonomorphicValueReference.dispatchedFrom`).
3. **Environments are threaded at the `WovenValue` seam** (`monomorphize/channel/EnvironmentThreading`), and the
   woven re-check checks the result. A definition receives one frame per entry as hidden parameters; a block at a
   row-typed slot takes the frames the slot supplies in place of `unit`, and running it passes them; a dispatched
   clause gets its frame's parts. A frame is found by entry at ground arguments, its binding must be the one the
   reference was written with, and an entry with no frame gets its default only when that default has no
   parameters. A body `with`'s named implementation is not a frame in scope (the walk erases the `with`), so its
   frame is built at the use, and the walk now rejects a use below a nearer binding of an effect its clauses perform
   — the one shape that would intercept.
4. **The platform primitives give the frame**, not the dischargers' bodies. The amendment's `p with State(cell)`
   is not needed for the platform: `withCellInternal` and `escapeInternal` are body-less, so — as D20 rule 5 already
   has it — they give their slot's default from nothing, and they now give its environment too, by handing the thunk
   a fresh frame. The dischargers (`runThrow`, `runStateToPair`, …) are unchanged. `p with State(cell)` remains the
   spelling for a *user* default with parameters, and is unbuilt.
5. **Found while building, and decided in the code** — each is fail-safe and recorded here for sign-off:
   - an implementation's **parameters are part of its identity** (its pattern key), and on the compile track a
     parameterised implementation gives way to the overlay's parameterless one of the same pattern. The compile
     track borrows the runtime track's sources, and the jvm `Abort` (with a frame) and the overlay's (without) must
     not merge into one value: a runtime value's signature is computed on the compile track, so a merge that let
     them differ would hand the jvm clause the overlay's signature. Where the overlay has none (`Throw`), the borrowed
     one stays resolvable, and running it on the compile track is stuck — loud — as before;
   - an `implement` clause gets **no frame for an entry it only carries** for a sibling (`ImplementationRows`'
     union): its binder keeps its positional slot but no constraint (`FunctionDefinition.carriedEffects`);
   - an effect a **member declares** (`FileSystem.readFile`'s `{Throw[IoError]}`) is per call: its frame comes from
     the call site, and an implementation's environment holds only the effects a clause performs that its member
     does not declare;
   - **supplied-versus-rides is decided as the declaration spells it**, not at ground arguments:
     `raising[E](…, body: {Throw[E]} Unit): {Throw[AssertionError]} Unit` supplies `Throw[E]` even at
     `E = AssertionError`. Two frames that coincide at one scope level are a use-site error;
   - frame types are public, body-less, constructor-less types (`ThrowFrame`, `StateCell`, …), because `private`
     reaches only `def` and `data`, and they are named apart per module because `eliot.effect` is imported whole.
6. **Gate** (behavioural — the jars move by design): every example's compile status, exit code and stdout
   identical; 20 of 47 jars changed; jar bytes 647514 → 645311 (−0.3%), instructions 18751 → 19164 (+2.2%). The suite
   is green, eliot-build's errors are unchanged, eliot-test is 182/183 — the one is the conflict below.
7. **Not built**: user defaults with parameters and `with Ability(args)`; named implementations with parameters
   (stateful doubles, `with scripted(List("Bob"))`); parameterised ability implementations; compile-track frames,
   including the guard read's (the compile track still finds an escape or a cell by its key, so items 14–15 are
   closed on the runtime track only); and option 4.

**Decided 2026-10-12 (Robert): (b), and built the same day.** Below is the question as it was put. Built in
`EnvironmentThreading`: a ridden entry's frame is a block parameter like a supplied one's, typed by the caller's
binding and passed from the run site at the definition's own binding, so the code and the frame still agree. One
consequence, recorded as a reversal in §4: running a block consumes the entries it rides (`BindingWriter`'s
`riddenEntries`), since the definition's received frame is now what the block runs on. eliot-test is 183/183 again;
every example's transcript is unchanged; 20 of 47 jars changed against the pre-option-5 baseline, bytes 647514 →
645235, instructions 18751 → 19266 (+2.7%).

**The question: a definition discharging the very effect it declares (2026-10-12).** §2.2 says "a definition may now
discharge the very effect it declares — which is what lets `describedAs(body: {Throw[AssertionError]} Unit):
{Throw[AssertionError]} Unit` catch its body's failure and re-raise a better one". That rested on the nearest-frame
search option 5 removed, and option 5's reversals did not list it. `describedAs`'s slot entry is its own row's entry,
so the slot rides: the body's `raise` is written on the caller's binding and runs on the caller's frame, and
`describedAs`'s own `catch` — a frame the body was not given — cannot see it. eliot-test's "put the new message in
front of the original failure" now fails. Two ways:

- **(a) Accept it as a reversal of §2.2's sentence.** A definition can no longer rewrite a failure of the effect
  it itself performs; `describedAs` has to change shape (for example, `runThrow` its body to an `Either` and
  re-raise, with the body's slot no longer riding).
- **(b) Ride by code, give by frame.** For an entry a slot lists, the block's *code* is decided as today — supplied,
  or riding the caller's binding — but its *frame* is always the one in scope where the block runs, passed to it
  like a supplied one's. `if`'s `value: {Abort} T` then gets `if`'s own received frame, which is the caller's, as
  now; `describedAs`'s body gets `catch`'s frame, as §2.2 promised. It is not item 14 again: a `{}` slot lists
  nothing and passes nothing, so a block reaches a helper's frame only for an entry the helper's own signature
  says it gives, and the threading still requires the frame's binding to be the block's code.

Recommendation: **(b)**. It keeps a documented feature, it is visible in the signature, and the cost is small: one
more frame parameter on a block whose slot lists an entry its definition also declares.

##### Open after the merge

**A slot listing an entry its definition also declares: handler or pass-through?** Found by a review of the plan on
the parallel line (`1034c8db`, R1, entered there as D21d). (b) gives a ridden entry's frame where the block runs, and
the threading picks the innermost frame of that entry at ground arguments. Under the built D21 a pass-through is
spelled by *not* listing — `guarded(body uses *: Unit) uses Console: Unit` reaches the caller's `Throw` through `*`,
and its own private `catch` intercepts nothing (§8 item 16's witness, `EnvironmentIntegrationTest`). But a **closed**
slot has to list what its block performs: `guarded(body uses Throw[String]: Unit) uses Throw[String], Console: Unit`
rides that entry, and under (b) a private `catch[String, …]` in `guarded`'s body then intercepts the caller's `raise`
— the same signature shape as `describedAs`, which means to intercept. The review's proposal: **an entry listed
explicitly on a slot is supplied** (the callee handles it), and an entry reached through `*` passes through on the
frame the definition received, never a nearer one. Undecided; Robert's.

The rest of that line's plan — its own D21 ("a function declares every effect it may perform", by row variables), the
index "State, decisions and plan" and the review — is superseded by the built D21 and kept in git history
(`a5593006`, `335dbe88`, `1034c8db`). Its D21e (entry-level keying in the walk) is built (§8 item 19).

#### Reversals this records

Standing rule 1: each is written down as a reversal, not amended in place. They took effect as D20 landed, and Part I
states the result since 2026-10-10.

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

### D21 — purity is spelled by absence: a function parameter is a value, and caller's code is `uses *`

**Decided 2026-10-09. Steps 1, 4, 5 and 6 built the same day** (below), with D20's 2 and 3, **and step 7 on
2026-10-10**, so D20 and D21 are built in full and Part I states them. It amends one rule of D20 and one of its
spellings; everything else D20 decided stands, and the two landed together. First written with a `block` keyword the same
day, and respelled before anything was built: the mark says nothing about `{ … }`, and a second keyword beside `uses`
was a second spelling of one fact.

#### Why

D20 made a function-typed parameter a block (its rule 2: "a parameter is a block iff its declared type is a
function type or `=> A`"). That gave the surface no way to say *this function must be given a pure function*: every
`f: A => B` in a parameter list could run its caller's effects, so a signature with no `uses` was pure only "of
itself", and a reader had to know that an arrow in a parameter means code while an arrow in a field means a value.
It also kept `compose` and every helper that stores a callback illegal, since a block may never be kept, although a
pure function is harmless to keep. Robert asked for purity to be expressible. The smallest change that does it is
to make a function-typed parameter what every other parameter is — a value — and to mark the one kind of parameter
that is code with the clause the surface already has: `uses`.

#### The model, as a developer is told it

> **An effect is a parameter you don't spell**, as D20 says, and **`uses` is where you spell which.** On a
> definition, `uses Console` means its caller hands it a `Console`. On a parameter, `uses` means the argument is
> *code*, not a value: `uses *` says it may use whatever effects are in scope where it is written — your effects,
> since you write it — and `uses Throw[E]` says the callee gives it `Throw[E]`. The callee may call that code or
> pass it on to another `uses` slot, never keep it. A parameter without `uses` is a value, computed before the call,
> and a value is pure: a function value among them, which is why it can be kept, stored and returned.
>
> **A signature with no `uses` anywhere is pure and total.** `uses` on a parameter means a call runs code you
> wrote, so the call performs exactly what you wrote there. `uses` on the definition means it performs those,
> handed down by you.

#### The surface

A parameter is `name [uses <entries>]: Type`, where the entries are `*` and effect names, `*` at most once and
first (`uses *, Throw[E]` is the spelling; `uses Throw[E], *` is a parse error). `*` is legal only on a parameter,
since only an argument has a caller's text to be open to. `=>` keeps its one meaning, the stdlib alias for
`Function`; D20's `=> A` and today's `{}` both go.

```eliot
def map[A, B](f: A => B, list: List[A]): List[B]                        // pure: f is a pure function value
def compose[A, B, C](f: B => C, g: A => B): A => C = a -> f(g(a))       // legal again: a value may be kept
def foreach[A](action uses *: A => Unit, list: List[A]): Unit          // action is the caller's code
def if[T](condition: Bool, value uses *: T) uses Abort: T              // nullary: mentioning value runs it
def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A): A { join(whenTrue, whenFalse) }
def runThrow[E, A](body uses *, Throw[E]: A): Either[E, A]             // the caller's effects plus Throw[E]
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A): A
infix left below apply def .[A, B](a: A, f uses *: A => B): B = f(a)
def greet(name: String) uses Console: Unit = printLine("Hello, " ++ name)

def mocked(body uses *, Mocking with recording, Calls with journal: Unit): Unit
def transcriptOf(program uses *, Console with recordingConsole: Unit): String = runWriterToLog(program)
def runPure[E, A](body uses Throw[E]: A): Either[E, A]                 // closed: Throw[E] and nothing else
```

| parameter clause | what the argument is | lowers to |
| --- | --- | --- |
| none | a value, computed before the call — a pure function value when its type is one; keepable | its type |
| `uses *` | the caller's code, open to the effects in scope where it is written | `Unit => A` or `A => B`; today's `{} A`, `A => {} B` |
| `uses *, E` | the caller's code, plus `E` given by the callee — a handler's slot | the same; today's `{E} A` |
| `uses E` | code that may use `E` and nothing else — a **closed row** | the same; unsayable today (§2.6) |

`*` is not "any effects" and not a row variable: it names *the effects in scope where the argument is written* —
the caller's own `uses`, an enclosing `with`, an enclosing `uses *` parameter's scope. That is D20 rule 1's lexical
capture, so nothing is unified and standing rule 3 is untouched; in the mechanism it is the row tag with an open
row, which is what `{}` is today, and a closed clause is the same tag with the row closed. A code parameter's type
is read as the function the callee calls it at: one with no arrow takes nothing and runs when mentioned. Code that
should *produce* a function is `g uses *: Unit => F`, applied by the callee — the same thing spelled out, and no
corpus wants it. `uses` is not a type and never appears in one: it is a clause on the declaration, lowered and
checked by the `row` phase, so `unify` sees `Function[A, B]` on both sides and guardrail 1 (no assignability arm)
is untouched.

`uses *` on a definition's own clause is **rejected**: a body has no caller's text to be open to, and the only
meaning it could carry — "I run my `uses *` parameters" — the parameter list already states. Two spellings of one
fact, and the second invites the misreading that the body may perform anything. If a one-glance "may a call
perform?" read on the definition line alone is ever wanted, that is where it would go, with a "declares `*` but
runs no such parameter" check beside it; not now.

#### The rules, as they differ from D20

1. **A parameter is code iff it has a `uses` clause** — D20 rule 2 with the predicate moved from the type to the
   clause. A **value constructor's parameters are values** (D20 rule 6 stands: `uses` on a field is rejected).
2. **A value is pure.** A lambda in any value position — an unmarked `f: A => B` parameter, a field, a generic
   slot, a result, a `val` — may use no effect it does not discharge itself: it binds and discharges locally
   (`x -> runAbort(lookup(x))` is fine) and reaches no enclosing binding. A reference to a `uses` definition that is
   neither the head of an application nor the direct argument of a `uses` slot is rejected the same way — a `uses`
   definition is not a first-class value. This is D20 rule 4 widened from "a field, a generic slot, a result, a
   `val`" to every value position, and it is what makes an unmarked signature mean pure.
3. **Code is used, never kept** — D20 rule 3 unchanged, now a corollary: a `uses` parameter has no value type, so
   there is nothing to store it in. A pure function value may be passed where code is expected (it simply uses no
   effects). Code may not be passed where a value is expected — including an unmarked function parameter, which is
   a value position by rule 2. Two conventions this matches: Swift's closure parameters are non-escaping by default
   and may be called but not stored, and Kotlin's inline-function parameters may only be invoked or passed to
   another inline parameter.
4. **A closed clause supplies and closes.** At `body uses Throw[E]: A`, the argument's text may use `Throw[E]`, bound
   by the slot's `with` or the callee, and must discharge everything else itself; it is rule 2's check with the
   supplied entries added to what is in scope. §2.6's "a row cannot be closed" is reversed below.
5. **Effects resolve where they are written**, **a definition gives its code only what it has**, and **a field
   holds a value** — D20 rules 1, 5 and 6, unchanged. So is the frames argument for rule 3: code run after its
   discharger returned finds no frame (§8 item 10), and the design keys on no family, so one rule covers the
   interpretation effects too.

#### Storing effectful computations

Under D20 and D21 a stored computation is pure, and an effectful one has no spelling. The reason is not purity: a
closure that captured the platform's native `Console` would be harmless to keep. The reason is frames — `Throw`,
`Abort`, `State`, `Writer` and `Dep` are a frame the discharger installs, and frame dependence is a property of the
*implementation*, not the effect: a test's `recordingConsole` is written over `Writer`, so a stored closure bound to
it is a stored `tell` with the same escape risk. Three ways to have a heap trampoline, and the first is the one:

1. **Store data, not code.** The queue holds `data Step = Print(line) | Read(continue)` and the loop interprets it
   under its own `uses Console`. It compiles today, is testable with a double, and on a microcontroller is what is
   wanted anyway — a `data` step is a fixed-size record, a stored closure a heap allocation.
2. **Frame-free capture — the `@escaping` opt-in.** A lambda in a value position may use effects whose written
   bindings are frame-free (the implementation's clauses `uses` nothing: every platform default, no `Writer`- or
   `State`-backed double). The check is at the keep site and needs the written binding, so it runs after
   monomorphization. Its cost is the testing story: a program that stores `Console` closures cannot bind a
   `Writer`-backed double to them, and the error arrives under test. This is where Scala 3's capture checking
   lands too (a scoped capability cannot be captured by an escaping closure), for a fraction of the machinery. It
   is §12's "not now" entry B, and D21 reserves keeping for it; not taken, because option 1 covers the trampoline
   and keeps every stored step testable.
3. **Bind at the run instead of where written.** A Π-typed field, which §12 measured the checker cannot
   represent, and dynamic scoping of handlers, the prohibited class. Closed.

Natives that keep callbacks — an event loop's handler table — stay the platform's axiom under all three.

#### What it buys

- **Purity reads off the signature as the absence of one word**, and the compiler enforces it: rule 2 for the
  code a pure signature does not take, and the scope check for the `uses` it does not have. A pure definition is
  total too, since `Inf` is a `uses` entry, so the pure fragment is exactly the language a type may be computed in.
- **A pure function parameter exists.** A sort can demand a pure key, so how often it calls it is unobservable; a
  type-level helper can demand pure arguments; a `data` constructor already could, and now a definition can too.
- **Keeping a function is legal again** — `compose`, a handler table, `Handler(name, f)` — because what is kept
  is a pure value. `eliot.lang.Function`'s own doc, which shows `compose` and says `A => B` is the function type,
  becomes true of parameters again. D20's "what it costs" first bullet is withdrawn.
- **One clause.** The whole effect surface is `uses`, in two positions; `*` is its one extra symbol. One arrow,
  one meaning: `=>` is `Function`, in a parameter as in a field or a result. Nothing is read off a type's shape
  (§3.6), and D20's `=> A` — a second arrow that is not a type — is not needed.
- **A closed row exists**, for free: `uses E` without `*`. What deleted eliot-test's `pure { … }` is sayable again.

#### What it costs

- **`uses *` on every code parameter.** The base already marks them: 43 signatures in `stdlib` and `lang` carry
  `{}` or a row on a parameter today, and the migration is one spelling for one spelling, the handler slots gaining
  `*,` before their entry.
- **The widened value check rejects what the tree accepts by accident.** §8 item 9 measured that a lambda using
  effects at a rowless arrow (`applyTwice(f: String => Unit)` from a `{Console}` definition) compiles and prints.
  Under rule 2 that lambda is a value and the program is rejected at the lambda, naming `uses *` as the fix. The
  base cannot be affected (its function parameters all carry `{}`); §10's byte-identity gate over the 45 examples
  says whether any example relied on it.
- **Why not `A -> B` pure and `A => B` effectful, as Scala 3's capture checking spells it.** `->` is a hard
  symbol for a lambda and a `match` arm, and a type is an expression in this language, so a second meaning for it
  in type position fights the types-are-values cornerstone. The default would also point the wrong way: the
  unmarked arrow would be the effectful one, and purity would need the extra mark.
- **Why not a keyword (`block`, `impure`).** A second word beside `uses` for a fact `uses` can state; `block`
  additionally named the wrong thing. `impure` had one merit, separating openness from the supplied list, and
  `*` keeps that merit inside the one clause.
- **Why not an effect variable, `f uses E: A => B` with `uses E` on the callee.** It is Koka's spelling, and it
  needs row unification, which is §12's closed inference class. It is also unnecessary: code is resolved lexically
  and may not be kept, so every code argument in one call is written in one scope and draws from one set of
  effects. One implicit variable — the caller's scope — is all there ever is, and `*` is its spelling. Eliot gets
  effect polymorphism from capture, for free.
- **Why not pure code everywhere.** Then the base needs `map` and `mapM`, `foldLeft` and `foldLeftM`; the 43
  signatures say which they want, and `foldLeft`, `catch`, `if` and `.` all want the caller's effects.
- **Unchanged from D20**: eliot-test names `recording` and `journal`; a native that keeps a callback has no
  spelling; the migration is wide and mechanical.

#### The work list, as it amends D20's

D20's list stands with these substitutions; the numbering is D20's.

1. **Rules 2 and 3** replace D20's rules 3 and 4 at this step: the keep check and the value-position check, in the
   `row` phase, at every value position rather than at fields and generic slots only. In today's syntax a value
   position is a rowless slot, so the check is already expressible before the surface changes. Closes §8 items 9,
   10 and 11 (item 9 by rejecting it, not by legalising it as D20 did). Gate: §10, plus the probes as tests, plus a
   probe that an effectful lambda at a rowless arrow is rejected and a pure one at a row-typed slot is accepted.
   **Built 2026-10-09.** `EffectRow.callbackEffects` records each parameter whose function type carries a row in its
   final codomain, with its arity (the arrows before the row), so the `row` phase can tell `f: A => {} B` (code) from
   `f: A => B` (a value) — the two lowered to one type and nothing else told them apart. `BindingWriter` then walks a
   lambda as code only where it is the argument of a code parameter (up to that parameter's arity), the head of an
   application, or an arm of a lowered `match` (`handleCases`/`typeMatch` and the `$selector` the lowering applies to
   its arms); a lambda anywhere else is a value, and inside it every binding from outside is marked, so an effect taken
   from one is *"This uses the effect 'X' inside a function written where a value is expected"*. An ordinary ability
   (a `~` constraint) is not an effect and stays reachable. A callback parameter standing anywhere but the head of a
   call or the argument of a code parameter is *"'f' is code its caller wrote: it can be called or passed on, not
   kept"*, and any code parameter referenced inside a value lambda is *"… a function value cannot capture it"*. A
   value constructor's parameters are values whatever their type says. Gate: `./mill __.test` green but for five
   tests that compare stderr and read the sandbox's `JAVA_TOOL_OPTIONS` banner (green with it unset); all 47 example
   jars byte-identical; eliot-test's suite green (183) under this compiler; the witnesses for items 9–11 are
   `CodeAndValueIntegrationTest`.
4. **The parser accepts `uses` in both positions, with `*` on a parameter** — not `=> A` — onto the existing
   `EffectRow` metadata (`uses *` is the row tag with an open empty row; `uses *, E` the open row `{E}`; `uses E`
   the same row closed, one bit the metadata does not carry today and the only thing a phase past `ast` learns).
   `*` first and at most once, `*` on a definition and `uses` on a field rejected at the parser.

   **Built 2026-10-09.** `uses` is a hard keyword. A definition's clause (`FunctionDefinition`, entries only) is
   parsed onto the row on its return type, `{Console} Unit`; a parameter's
   (`ArgumentDefinition.parameter`) onto the row on the slot's type — `{E…} T` for a type with no arrow, the arrow
   codomain's `A => {E…} B` for one with — and each entry's `with` chain after the type in the order written, so
   `body uses *, Mocking with recording: Unit` is `body: {Mocking} Unit with recording`. No phase past `ast` reads the
   clause; the one bit it adds, **closed**, rides `ArgumentDefinition.closedRow` into `ParameterEffects.closed` and
   `CallbackEffects.closed`, and `BindingWriter` enforces it (D21 rule 4): the argument at a closed slot is walked in
   `Scope.enterClosed`, the value-position cut with the slot's *riding* entries left reachable, so it may use what the
   slot supplies or rides and what it discharges itself — an effect from around it is *"This uses the effect 'X' inside
   an argument whose `uses` clause is closed"*, and the caller's own code passed there is *"… cannot run it"*. The
   parser refuses `*` on a definition, `*` anywhere but first and once, an entry-less clause, a trailing `with` after a
   clause and `uses` on a field (a field's binder has no clause); a clause over a type that already carries a row is a
   `core` error, since it is the only thing that makes a row stand directly on a row. Top-level error recovery no
   longer restarts at `uses` (`Primitives.isItemBoundary`), which otherwise swallowed the real error. **D20b is
   answered by the sequencing**: both spellings parse until step 6. **One gap carried, not opened**: a function-typed
   code parameter's entries (`f uses *, E: A => B`) are written where today's `A => {E} B` writes them, which the
   callee never supplies, so code using `E` there is rejected — fail-safe, and no corpus file needs it. Gate:
   `./mill __.test` green; the 46 example jars byte-identical, and byte-identical again from a scratch copy of the
   examples rewritten to `uses` by script (48 files), which is step 5's gate run early over the examples; witnesses in
   `UsesClauseParserTest` and `UsesClauseIntegrationTest`.
5. **Migrate**, by script: `{} A` ⤳ `uses *: A`, `A => {} B` ⤳ `uses *: A => B`, `{E} A` on a parameter ⤳
   `uses *, E: A`, a result row ⤳ `uses`. Gate: byte-identity over the 45 examples.

   **Built 2026-10-09.** Every row in a signature is respelled — in eliot's `.els` (the three layers, the compiler
   overlay and the examples, doc comments included), in the Eliot snippets of the Scala tests, and in eliot-test and
   eliot-build — and with a slot's `with` chain each implementation now stands on the entry it binds
   (`body uses *, Mocking with recording, Calls with journal: Unit`). What keeps the row spelling on purpose: a **row
   alias**'s body (`type Test = {Writer[List[TestResult]]} Unit`, which has no `uses` form until D20a), the field rows
   the tests reject, and the three tests about the old spelling itself (`EffectSyntaxParserTest`,
   `UsesClauseParserTest`, `UsesClauseIntegrationTest`), which step 6 rewrites. The migration found one step-4 defect:
   a parameter's clause put the row *inside* a parenthesized codomain, so `f uses *: String => (String => Unit)` was
   code of arity two rather than code handing back a function value; the row now stands on the codomain as written.
   The hints that told a user to write `f: A => {} B` name `f uses *: A => B`; the diagnostics' "effect set" wording
   waits for step 6. Gate: `./mill __.test` green; all 46 example jars, their exit codes and their output identical;
   eliot-test's runner and eliot-build's launcher and suite runner byte-identical to the row spelling's, eliot-test 183
   green. eliot-test and eliot-build need an eliot release carrying `uses` before their `dep` lines can move to it.
6. **Delete the old surface**: a row in a type, and `=> A`, are parse errors naming the `uses` spelling.

   **Built 2026-10-09.** `=> A` was never built, so the step is the row. A `{` in a type run is checked against the row
   shape and, where it matches, refused as itself (`Parser.refusedAs`, from `Expression.typeRunAtom`): *"Expected a type,
   with its effects in a `uses` clause before the colon (`def f uses Console: Unit`, `body uses *, Throw[E]: A`), but
   encountered symbol '{'"* — on a return type, a parameter, an arrow codomain, after a clause, and for the empty row
   alike. A `data` field's type gets its own refusal (`Expression.fieldTypeRunParser`), saying a field holds a value,
   since it can take no clause either; the transfer brace and an `implement` body after a `where` do not match the row
   shape and pass through as before. **What keeps the row spelling** is the one construct with no `uses` form: a **row
   alias**'s body, as its whole body only (`Expression.rowAliasBodyParser`), until D20a decides one. The pinned form
   `{E | G} A` went with it — the parser no longer reads a tail, so `EffectfulType` lost its `tail` — and so did every
   core diagnostic the parser now pre-empts: `EffectSugarDesugarer.rowErrors` (a field row, a pinned row, a clause over
   a rowed type) and the field erasure `DataDefinitionDesugarer` did for it. The diagnostics that told a user to edit
   "its `{ ... }` effect set" name its `uses` clause. The node itself stays: the clause is still parsed onto it, so no
   phase past `ast` changed. Step 5's script had missed three return rows written across several lines in
   eliot-build (`Launcher.performed`/`compiled`, `CachedPackages.descriptorAt`), which this step's parser found; they
   are respelled. Gate: `./mill __.test` green (the banner-sensitive tests run with `JAVA_TOOL_OPTIONS` unset); all 47
   example jars byte-identical; eliot-test 183 green and eliot-build 368 green under this compiler, and eliot-build's
   launcher jar byte-identical to the one the step-5 compiler built from the step-5 source. The witnesses are
   `UsesClauseParserTest`'s "a row written in a type" and "a row alias", and `FieldValueIntegrationTest`.
7. **Documents**, as D20's step 7, written to D21's surface.

   **Built 2026-10-10**, as D20's step 7 records: Part I's six rules are D21's (its rules 1–4 and D20's 1, 5 and 6,
   reordered to the CLAUDE.md cornerstone's numbering), and every reversal below and under D20 is applied there. The
   unbuilt half of rule 2 — an unapplied reference to a `uses` definition in a value position — is §8 item 14.

#### Interactions

- **D20a** (naming a set of effects) is unchanged: `uses Git` on a definition, `uses *, Git` on a parameter.
- **D20b** (window or flag day) is unchanged; one symbol more in the same migration.
- **D19** names control implementations on the dischargers' slots; its spelling is `body uses *, Throw[E] with
  throwByEscape: A`, the handler-slot row of the table above.

#### Reversals this records

Standing rule 1: written down as reversals, not amended in D20's text. They took effect as D20 and D21 landed
together, and Part I states the result since 2026-10-10.

- **D20 rule 2's predicate** ("a parameter is a block iff its declared type is a function type or `=> A`") is
  reversed to "iff it has a `uses` clause"; a function-typed parameter is a value.
- **D20's `=> A`** is withdrawn before it is built; `value uses *: A` is the lazy argument, and §2.1's `{}` is
  replaced by it rather than by a second arrow.
- **D20 rule 4** ("a lambda may use effects from around it only as a block") is widened: the value positions it
  names are every position that is not a `uses` slot, an unmarked function parameter included, and an unapplied
  reference to a `uses` definition is rejected in them too.
- **D20's cost "`compose`, and any helper that stores a callback"** is withdrawn: a kept function is pure.
- **§2.6's "a row cannot be closed"** is reversed: `uses E` without `*` is a closed row, and the sentence in the
  CLAUDE.md cornerstone ("this body may perform nothing at all is unsayable") goes with it. eliot-test's `pure { … }`
  becomes expressible as a slot `body uses Throw[AssertionError]: Unit`; whether it is reinstated is eliot-test's
  call.
- **§8 item 9's resolution** changes: D20 legalised the measured behaviour (every function parameter is a block);
  D21 rejects it, and the two spellings `f: A => B` and `f: A => {} B` that behave identically today become the
  value and the code.
- **§12's "a third kind of parameter for a function the callee keeps"** stands as a rejection of a *third* kind,
  and D21 admits `compose` without one: a kept function is the *first* kind, a value, so the entry's "`compose` is
  not worth a third" has no subject and its "D20 rule 3 forbids keeping instead" now reads "keeping code"; keeping a
  frame-bound closure is still entry B.

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
  effect is charged where it runs.
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
| `effect-row-tails.md` | pinned rows as the one spelling of a carrier stack | prose only | no live content: there is no stored computation (§2.3) and D4 dissolved |
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
