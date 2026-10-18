# Effects in Eliot — the design, and what is left to do

This is the effect system's single document. It has two subjects that must not be confused:

- **The tree** — what the compiler builds today, **D35**: an implementation's code and environment are passed as
  **evidence** after monomorphization, and static code is a **specialisation pass**. Part I describes it and is the
  authority for the tree.
- **The plan** — **D36 as revised** (decided 2026-10-17/18, not built, §11): effects are **capability passing** — an
  effect desugars before monomorphization to an ability over a handle type, a `uses` entry to a handle parameter, a
  named implementation to a `data` and its instance, and the checker gives each use the nearest handle in lexical
  scope whose entry answers it. Building it replaces D35's mechanism and rewrites Part I.

**Status (2026-10-18).** The tree: effects v6's model in D20/D21's `uses` surface, with D22–D30 and D35 built — every
effect implementation named, `main` run with a generated `with` per entry, a clause listing effects only, abilities
never bound with `with`, entries compared as spelled, evidence and the specialisation pass (§3.4), and a block copy per
variant when runs differ. Latest release `v0.16`. The plan: D36, assessed implementable now; next is its step 2a.

An effect is an ability declared with the `effect` keyword. A definition's `uses` clause says what its caller hands
it. Before monomorphization one syntax-directed walk, reading declarations only, proves that every use of an effect has
something to answer it; after it, the evidence in scope answers it, on the compile track as on the runtime one. There
is no carrier, no monad, no `Id`, and nothing to infer — if a comment or a piece of code mentions one, it is history.

**How to read it.** Part I is the design as built: where code, a stdlib signature, an example or a test disagrees with
it, the disagreement is a defect in the artefact or an entry in §8, never silently tolerated. Part II is what is left:
the divergences (§8), the binder mark (§9), the method every change is run under (§10) and the decisions (§11 — the
plan, the open questions and the closed record). Part III holds what is **closed and must not be re-proposed** (§12)
and how to read a citation (§13). A decision changes Part I when it is *built*. Everything superseded lives in git
history; §13 says where.

**Next, in order.**

1. **D36 step 2a** (§11): the hole rule and the block-type read in the checker, tested as `ProcessorTest`s on the
   step-1 spike's program with its handle arguments replaced by holes.
2. **D36 step 2b**: the desugar beside today's pipeline, behind an internal switch, measured on the corpus.
3. **D36 step 3**: the switch with the deletions, Part I rewritten, a release. Then **step 4**, erasure.

Seven readings D36 needs await Robert's sign-off before Part I is rewritten (§11, D36, "To sign off"). D20a, D20c and
D3 stay open; none blocks the steps above.

**Open, in order of severity.**

- **Silent:** none known.
- **Latent:** §8 item 59, a decision D36 makes (§3.8 restated).
- **Loud, at compile time:** §8 items 60 and 61 and `raise(none)` refused under a single `Throw[Option[Int]]`, all
  closed by D36; a `catch` over a parameter spells its arguments (§7 item 2); a function-typed code parameter's own
  entries are never supplied (§7 item 1).
- **Undecided:** naming a set of effects in a clause (D20a), storing an effectful computation (D20c), `~`/`&` in user
  space (D3).
- **Outside this document:** eliot-test `v0.5`, eliot-build and the docs site's install page depend on `v0.11`, which
  they need not leave; the site's generated `apidoc/` pages predate effects v6; eliot-test's own notes still say a row
  cannot be closed.

---

# Part I — The design (the tree, D35)

*What the compiler builds today. D36 (§11) replaces §3 and rewrites the rest in capability terms when it is built.*

## 1. The user model — six rules

> **An effect is a parameter you don't spell.** `uses Console` on a definition means its caller hands it a
> `Console`, and an operation uses the one in scope *where it is written*. `with` binds one.
>
> **A parameter is a value or code.** Without a clause it is a value, computed before the call — a function value
> among them, which is pure and may be kept. With a `uses` clause it is code the caller wrote, run by the callee,
> which may call it or pass it on to another `uses` slot and never keep it: `uses *` says it may use whatever is in
> scope where it is written, and `uses *, Throw[E]` that the callee gives it `Throw[E]` as well.
>
> **A listed effect is the callee's to handle or to declare.** An effect a code parameter's clause lists, the callee
> may catch around the code; if it does not, it causes that effect itself and must declare it. An effect the code uses
> without the clause listing it is invisible to the callee, which can neither catch it nor needs to declare it. A
> definition taking such code says so in its own clause too, `uses *` first: a call to it performs what that code does.

1. **Effects run, and resolve, where they are written.** An effectful expression in a value position performs its
   effects there: strict call-by-value in **every** value position, a bare generic slot included, so
   `choose(readLine, readLine)` runs both reads and `Box(shout)` runs `shout` and stores its value. An operation, or
   a call to a definition that `uses` an effect, takes the nearest binding *in the text* — an enclosing `with`, the
   enclosing definition's own clause, or what an enclosing code parameter's clause supplies — which is §3.1's
   resolution order stated as scope. The one argument not run where it stands is code (rule 2). *The one refinement* is
   rule 5's: an effect a code slot **lists** is answered where the callee runs the code — by the implementation, and
   the frame, the callee has for it there (§3.4) — which is what lets the callee catch it. An effect the code uses
   through `*` unlisted is answered where it was written, as a closure captures.

2. **A parameter is code iff it has a `uses` clause, and a value is pure.** `whenTrue uses *: A` receives the
   caller's text unrun; after desugaring a nullary code slot is a **thunk** (`Unit => A`), but the thunk is the
   lowering, not the surface — every phase goes by the clause recorded on the declaration
   (`EffectRow.parameterEffects`, `callbackEffects`), never by the shape. Every other parameter is a value, and a
   **plain generic is a payload, always**. A lambda in any value position — an unmarked function parameter, a
   field, a generic slot, a result, a `val` — may use no effect it does not discharge itself: it may bind and
   discharge locally (`x -> runAbort(lookup(x))`), and it reaches no enclosing binding, so an effect taken from
   outside is *"This uses the effect 'X' inside a function written where a value is expected"* at that use. An
   ordinary ability (a `~` constraint) is not an effect and stays reachable. An unapplied reference to a `uses`
   definition is not a value either: given fewer arguments than it declares, it is *"'printLine' performs the
   effect 'Console' when it is called, so unapplied it is not a value …"* in a value position, exactly as
   `s -> printLine(s)` there is, while at a code parameter it is the caller's code (`applyTwice(printLine)`).

3. **Code is used, never kept.** A reference to a code parameter is the head of an application, or the direct
   argument of another code parameter (or stands inside a lambda so placed), and nothing else; anywhere else it is
   *"'f' is code its caller wrote: it can be called or passed on, not kept"*, and a value lambda cannot capture it.
   Nullary code runs when it is mentioned, so it can never be kept, but it can be captured. A pure function value
   may stand where code is expected (it simply uses no effects); code may not stand where a value is expected. The
   reason is frames, not purity: code run after its discharger returned finds no frame, and the design keys on no
   family, so one rule covers the interpretation effects too. Swift's non-escaping closure parameters and Kotlin's
   inline-function parameters draw the same line.

4. **A closed clause lists and closes.** A clause without `*` — `body uses Throw[E]: A` — admits the argument's
   text to what the slot lists (§2.2) and what it discharges itself, and to nothing from around
   it: *"This uses the effect 'X' inside an argument whose `uses` clause is closed"*, and the caller's own code
   passed there *"… cannot run it"*.

5. **An effect a code parameter lists is the definition's to handle or to declare** (D22). Running a code
   parameter is checked like a call to a definition with that signature. Each entry its clause lists the definition
   either **handles** where the code runs — passing the code on to a slot that discharges it (`catch` hands
   `computation` to `runThrow`), or binding an implementation with a `with` around the code's runs —
   or **declares** in its own
   clause, which passes it on to its caller; doing neither is *"'body' may perform the effect 'X' here, which this
   definition neither handles nor declares"*. Declaring does not prevent handling: `describedAs` declares
   `Throw[AssertionError]` because it raises one itself, and still catches its body's failure. Declaring an effect
   the definition handles and never performs itself is the over-declaration error (§4). An effect the code uses
   through `*` without the clause listing it is invisible to the definition: bound where the code was written, it can
   be neither caught nor declared there (§2.2). **An effect has no default** (D26): every implementation of one is
   passed — installed by a `with`, given to a block by the callee running it, or received from the caller. A
   platform's discharger binds its
   control effect's named implementation with a `with`, over the frame its primitive made (§3.4), and the synthesized
   entry point is an ordinary definition binding each of `main`'s entries with one `with`, naming the platform's
   `standard…` implementation of the effect (`app::main with eliot.effect.Console::standardConsole`). Nothing is
   exempt from this rule, the entry point included.

6. **A field holds a value.** A `data` field takes no clause and no row, and there is no stored computation: store
   data describing the work, and perform it where the effects are in scope (§2.3).

**Consequences the user sees.** **Purity reads off the definition's own clause as the absence of one word.** No
`uses` before the result type: a call is pure, and total, since `Inf` is an entry like any other — whatever its
arguments are, since a parameter with a clause forces one there (D24). `uses *` alone: the definition adds no effect,
and a call performs exactly what its caller wrote in the code — `foreach`, `catch`, `orElse`, `.`. Entries in the
clause: it performs those, received from its caller. So `xs.foreach(x -> printLine(x))` inside
a `uses Console` definition needs no declaration on `foreach`: the lambda is the caller's code, the `Console` is the
caller's, and the collections library stays effect-oblivious. Every diagnostic speaks the clause's vocabulary ("add
it to its `uses` clause").

**Rule 2's predicate was agreed and then worked around four times, and every stall in this design's history traces
to that erosion.** It was then stated on the row — *"a binding passes into a position iff that position declares a
row"* — and the vocabulary below is v5's; the lesson is not:

| # | how the predicate was worked around | what it cost |
| --- | --- | --- |
| 1 | bare-generic slots exempted from rule 1 — "mode belongs to the instantiation" | six days; a mode resolver, obligations, splice-restart (~350 lines), all reversed |
| 2 | declined a row on `foldLeft`/`foldOption` for ergonomics | the elaborator could not hoist at a generic-return callee *at all*; kept the whole payload router alive |
| 3 | the derived discharge stack routed a computation through `.`'s **rowless** slot "as data" | 5 `State`-family miscompiles; made "a generic is a payload" false, so nothing downstream could assume it |
| 4 | `foldOption` left with a strict `ifNone` because both declared spellings failed | a silent lazy-branch failure mode |

A fifth move — *let the elaborator's payload test accept a generic-headed return* — was rejected because it
**approximates** the predicate instead of **declaring** it in the signature. Moving the predicate from the row to the
clause (D21) is what made the declaration say it.

## 2. The surface

**One clause in two positions, and three constructs.**

**The `uses` clause** stands where a parameter's type would, before the colon, on a definition and on a parameter
alike — `name [uses …]: Type` — so no effect is written inside a type anywhere. A `where` precondition stays after
the result type.

```eliot
def greet(name: String) uses Console: Unit = printLine("Hello, " ++ name ++ "!")
def main uses Console: Unit = greet("Bob")

def updateState[S](f: S => S) uses State[S]: Unit = putState(f(state))           -- f is a pure value
def when(condition: Bool, action uses *: Unit) uses *: Unit = fold(condition, action, unit)
def foreach[A](action uses *: A => Unit, list: List[A]) uses *: Unit
def runThrow[E, A](obj uses *, Throw[E]: A) uses *: Either[E, A]
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A) uses *: A
```

| clause | where | what it says | lowers to |
| --- | --- | --- | --- |
| none | a parameter | a value, computed before the call; pure, keepable | its type |
| `uses E, F` | a definition | what its caller hands it | one phantom binder per entry (§3.1) |
| `uses *, E` | a definition | the same, and it runs code a parameter's `uses *` takes, so a call performs what that code does (D24) | nothing for `*`: a marker the parser checks and drops |
| `uses *` | a parameter | the caller's code, open to the effects in scope where it is written | `Unit => A`, or its function type |
| `uses *, E` | a parameter | the same, plus `E`, which the callee handles or declares — a handler's slot | the same |
| `uses E` | a parameter | code that may use `E` and nothing from around it — a **closed** clause | the same |

**A clause lists effects only** (D28). An ability is a `~` constraint on a type parameter, filled by the caller or
answered by its default, and is never handed down a call chain the way a clause's entries are: `def f uses
Compare[Int]: Bool` and `body uses *, Compare[Person]: A` are both *"'Compare' is an ability, and a `uses` clause lists
effects only"*, at the entry. Nor is an ability ever bound with `with` (D29): it has no named implementation, so its
binding is always the caller's `~` binder or the two-site search's.

`*` comes first and at most once. On a parameter it opens the argument to the caller's text, since only an argument
has a caller's text to be open to; on a definition it is written **exactly when** some parameter's clause has one, and
says that a call performs what the caller's code there does — so the result side of a signature is complete without
reading the parameters (D24). It is not "any effects" and not a row variable: it names *the effects in scope where the argument is written*,
which is rule 1's lexical capture, so nothing is unified. A code parameter's type is read as the function the callee
calls it at: one with no arrow takes nothing and runs when mentioned, and for one with an arrow the clause belongs
to its final codomain as written — `f uses *: String => (String => Unit)` is code of one argument handing back a
function value. Code that should *produce* a function is `g uses *: Unit => F`, applied by the callee.

The parser refuses the rest: a definition with a `uses *` parameter whose own clause does not start with `*` (*"Expected
`uses *` before the colon, since a parameter runs code its caller wrote and the call performs what that code does"*),
`*` on a definition with no such parameter (a closed clause is not one), `*` anywhere but first, an entry-less clause,
`uses` on a `data` field, `with` after a definition's clause, and `with` on a parameter or a field (below). **The brace spelling is gone**: a row written in any type — a return type, a
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
```

**An anonymous `implement` is an ability's implementation; an effect's is named.** An anonymous block in one of the
**two sites** — the ability's module or the type's module — is the implementation for its pattern, subject to the
existing coherence and `where` rules, and is what every reference to the **ability** binds (`Show[Int]`, `Eq[Path]`).
It takes no parameters, since nothing that finds it has arguments to give it: a parameter list there is *"Expected `{`:
an implementation with parameters is named, and a `with` supplies them …"* (D25). An anonymous `implement` of an
**effect** is *"An implementation of the effect 'Console' must be named, since an effect has no default"*, at the block
(D26): an effect's binding is always passed, so a default would never be reached. A **named** `implement` of an
**ability** is the mirror error, *"The implementation 'byKey' of the ability 'Compare' cannot be named: an ability's
implementation is found by its type, and never bound with `with`"* (D29): behaviour chosen per call is a value
parameter (`people.sortBy(p -> p.age)`).

A **named** `implement` of an effect may live anywhere, is never searched, is not checked for overlap, and its clauses
may carry `uses` — what the implementation performs beyond its effect, charged wherever the name is bound. An entry a
clause only repeats from its member (`standardFileSystem`'s `readFile` and its `Throw[IoError]`, which
`FileSystem.readFile` declares) is not the implementation's: each call hands it (§3.4), so the `with` binding the
implementation answers nothing for it. A named implementation closes over nothing, but it may take **parameters**,
written between its name and its effect as a definition's are (`implement scripted(answer: String): Terminal`) and
supplied by the `with` that binds it (`greet with scripted("Bob")`): each clause receives them ahead of its own. They
are its runtime environment, so one specialisation serves every argument. The arguments are typed by the entry the
`with` binds, whether the code spells it or only the checker reads it off the call (§3.1): in `pick(1) with mapping(x
-> show(x))`, for `implement[T] mapping(f: T => String): Source[T]`, `x` is an `Int` because `1` is. No keyword marks
such an implementation — parameters are an ordinary parameter list, whether they hold a frame or a function. A named
implementation's **type parameters** are what its pattern takes where it runs: `with showing`, for `implement[T ~
Show[T]] showing: Source[T]`, runs at `T := Int` for a `Source[Int]` and at `T := String` for a `Source[String]` under
the same `with`, by the match the two-site search makes for an anonymous implementation, and a pattern that does not
match the entry is *"This performs 'Source[Int]' on the implementation 'none', which does not implement it"* at that
use. Every type parameter must be its pattern's: one the pattern does not take is *"The type parameter 'K' of the
implementation 'keyed' is not taken by its pattern, so nothing where it runs determines it"*, at the declaration (D29,
reversing D27). With parameters, the arguments must fit every entry the `with` binds: *"The implementation 'constant'
takes (String) where it runs 'Source[String]', but this `with` supplies (Int)"*, at the `with`.

```eliot
implement standardConsole: Console                        -- the base: body-less, beside the effect
implement standardConsole: Console {                      -- the jvm layer: the same name, bodied (the layer merge)
   def printLine(s: String): Unit = printLineInternal(s)
   def readLine: Option[String] = lineOrNone(readLineInternal)
}

implement session: Terminal {                             -- a test's, in the test module
   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
   def read: String = "Bob"
}

implement scripted(answer: String): Terminal {           -- a test's that takes parameters
   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
   def read: String = answer
}
```

**`with` installs an implementation around its subject, for every effect of its ability that escapes it** (D23):
infix, subject-first, at the loosest precedence, left-associative, so `xs.map(greet).joined with session` applies to
the whole chain and `c with a with b` is `(c with a) with b`, an inner `with` for the same ability shadowing the outer
within its subject. Since every definition declares what it performs and does not discharge, what *escapes* a subject
is read off it: the effects its calls declare, and those its own code performs. A `with` binds exactly those, for its
implementation's ability — an effect discharged further down never escapes, so no `with` reaches it — and a `with`
that nothing escapes to is an error, never a no-op. It names an effect's implementation only, since an ability has no
named one (D29).

**A `with` is an expression in a body, and nothing else** (D35). On a parameter — after a clause entry or after its
type — or on a field it is a parse error, *"… no `with` on a parameter, since code's effects are bound where it runs,
by a `with` around the run in the body (`body with fake`)"*. A callee handles a listed effect of code its caller wrote
by a `with` **around the run**:

```eliot
def greetTranscript: String = runWriterToLog(greet with session)
def bobTranscript: String = runWriterToLog(greet with scripted("Bob"))

def transcriptOf(program uses *, Console: Unit) uses *: String = runWriterToLog(program with recordingConsole)
```

`transcriptOf`'s caller writes nothing: the block it passes is run where `transcriptOf` runs it, and finds
`recordingConsole` there for the `Console` its slot lists, since a block is given, at each run, the implementations the
callee has in scope for what its slot lists (§3.4). A `with` around a run handles its entry even when the definition
also declares that effect — the declaration then speaks only for what the definition performs itself. It binds only
what the slot **lists**: an effect the code uses through `*` unlisted was answered where it was written, and a `with`
for it is *"This `with` binds nothing: 'program' is code its caller wrote, and its use of 'X' was bound where it was
written"*. A `with` after a definition's own clause is rejected: it would be a second spelling of `with` around the
body. Runs of one parameter may install different implementations: the block its caller wrote is compiled once per
distinct implementation its runs install (§3.4, D35 step 5a).

**A `with`'s arguments are values computed where it is written**, outside its binding: in
`greet with scripted(answer) with scripted("outer")` the inner `answer` reads from `scripted("outer")`. Around a run of
a code parameter they are computed at the run, and the frame is built there and handed to the code, so a definition can
bind an implementation its own parameters configure, and each run may supply its own arguments:

```eliot
def answering(name: String, body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted(name))
```

A `with` naming an implementation that takes parameters must supply exactly them, and one naming an implementation
without parameters supplies none.

**`with` is written almost nowhere.** Most code fixes nothing: a def declaring `uses Console` receives its binding
from its caller, up to `main`, which the platform runs with a `with` per entry naming its `standard…` implementation.
That chain is what makes a fake possible — `greeting` never said which console, so a
test may say. A `with` in production code is the same mistake as a hard-coded dependency.

### 2.1 `uses *` — the caller's code

A clause on a **definition** is what it performs and does not discharge, received from its caller. A clause on a
**parameter** is what makes the argument code, and it is the declaration that lets the walk cross into the argument
(rule 2).

`uses *` alone says *"the caller's code, and I add nothing to it"*, and the definition repeats it in its own clause
(D24), where it adds no entry. It is the spelling of every effect-transparent code slot — `fold`'s arms, `orElse`'s fallback, `catch`'s handler, `.`'s `f`, `foldLeft`'s
`combine`, `forever`'s `step`. It supplies no entry, so an operation inside such an argument is bound by whatever the
*caller* has in scope, and the argument is not run until the callee runs it.

```eliot
def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A) uses *: A { join(whenTrue, whenFalse) }
def foldLeft[A, B](initial: B, combine uses *: A => B => B, list: List[A]) uses *: B
infix left below apply def .[A, B](a: A, f uses *: A => B) uses *: B = f(a)
```

**Which a function parameter is, is the base's decision, made per signature.** `updateState`'s `f: S => S` and
`foldLines`' `step: B => String => B` are pure values; `map`'s `f uses *: A => B` and `foldLeft`'s `combine` take the
caller's code, so `names.map(n -> setting(n))` inside a `uses Abort` definition aborts that definition. Making every
function parameter pure would need a `map` and a `mapM`, a `foldLeft` and a `foldLeftM`; making every one code would
leave purity unsayable.

### 2.2 A slot's listed entries — handle or declare (D22)

**An effect a code parameter's clause lists is the definition's to handle or to declare** (rule 5). Listing it says
*"I run this code with this effect in hand"*, and the definition then does one of two things with it, read one level
up, at the declaration:

- **declares it** in its own clause, which passes it on to its caller: `if`'s `value uses *, Abort: T` under `if`'s
  own `uses Abort`, whose body discharges nothing, so an `abort` in `value` reaches `if`'s caller. The walk calls such
  an entry **ridden**: it continues outward to the definition's own entry, and a run of the block finds the
  definition's received implementation unless the definition installs a nearer one around the run;
- **handles it** where the code runs — it lacks the entry, so the slot **supplies** it, and rule 5 holds the
  definition to running the code inside a `with` or a discharger of it.

Which one an entry is, is decided as the declaration spells it, never at ground arguments:
`raising[E](…, body uses *, Throw[E]: Unit) uses Throw[AssertionError]: Unit` supplies `Throw[E]` even at
`E = AssertionError`.

```eliot
def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T                  -- declares Abort: passed on
def else[A](computation uses *, Abort: A, fallback uses *: A) uses *: A              -- handles Abort here
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A) uses *: A   -- handles Throw[E]
def runStateToPair[S, A](initial: S, p uses *, State[S]: A) uses *: Pair[A, S]       -- handles State[S]
```

Read as English they are already right, and that is the whole of what a discharger is: no tail, no base, no second
concept. Only the clause of a **nullary** code parameter is handled this way; a function-typed code parameter's
clause is the callback's own scope (`onError uses *: E => A`), bound where the callback is written, and a named entry
there is not supplied by the callee (§7 item 1).

**How a listed entry is handled** is rule 5. A definition with a body handles only with what it has: `else` hands
`computation` to `runAbort`, `catch` to `runThrow`, and a `with` around the run installs a chosen implementation. The
platform's dischargers are no exception: `runThrow` binds `escaping` with a `with` around its run (§3.4). Doing
neither is *"'body' may perform the effect 'Console' here, which this definition neither handles nor declares"*, at
the run, with the help naming both fixes. So a slot cannot launder an interpretation effect into a pure signature. The
effects a `with`'s implementation performs in its own clauses are performed **where the `with` stands**, and are
answered there like any other use: `transcriptOf`'s `program with recordingConsole` performs the `Writer[String]` that
`recordingConsole` does, inside `runWriterToLog`, which handles it. "Lacks" and "declares" compare whole **entries**:
eliot-test's `mocked` declares `Throw[AssertionError]` and lists `Throw[IoError]` as well, so the latter is supplied,
and `mocked` runs its body, under `with mockFileSystem` (which raises `Throw[IoError]` in its clauses), inside its own
`catch[IoError, Unit]`, while its body's `Throw[AssertionError]` rides to `mocked`'s caller.

A supplied entry's own **type arguments** are written at the call from the actual's declaration, matched
entry-by-entry against the slot's clause (an actual `bad` declared `uses Throw[String]` against `Throw[E]` gives
`E := String`). Where no declaration answers, the call spells it — `runThrow[AssertionError, Unit](body)` — and an
argument nothing determines is **rejected** rather than defaulted, which is what stops a `catch` from compiling
against a frame it will not meet at runtime. Three shapes reach that rejection honestly:

- the actual is a **parameter reference**. A code parameter's own clause answers in part: `runThrow(body)` over
  `body uses *, Throw[String]: Unit` determines `E` with nothing spelled, while `computation catch (err -> 0)` over
  `computation uses *, Throw[String]: Int` is still *"Cannot tell which 'Throw' this call supplies"*, so a `catch`
  over a parameter spells its arguments or types its handler (§7 item 2);
- the actual **raises nothing**, so it has no entry of that ability to read `E` off. Spelling it is then enough —
  `runThrow[String, Int](5)` compiles — since a slot's entry is a permission, not an obligation: code that uses less
  than its slot allows is fine, as a pure function value is wherever code is expected (rule 3);
- the slot's clause names the **same ability twice** (`uses *, Throw[IoError], Throw[AssertionError]`), where only the
  call can say which one is being discharged.

**Handling and declaring combine, and an unlisted effect is out of reach.** A definition that declares a listed entry
may still discharge it around the code: the code finds the innermost implementation of that entry, code and frame,
where the definition runs it (§3.4). So `describedAs(body uses *, Throw[AssertionError]: Unit, newMessage: String) uses
Throw[AssertionError]: Unit` catches its body's failure and re-raises a better one, declaring the effect because it
raises it; and a `guarded(body uses Throw[String]: Unit) uses Console: Unit` that runs `body` inside a private
`catch[String, …]` catches its caller's `raise` — which its signature, by listing `Throw[String]` and not declaring
it, says it does. An effect the code uses through `*` and the slot does not list is **invisible** to the definition:
it was bound where the code was written, so `guarded(body uses *: Unit)` can neither catch a `Throw` its caller's code
performs nor needs to declare it, and its private `catch` intercepts nothing. A pass-through the definition cannot
touch is therefore spelled by not listing, and a closed clause, which lists everything, has none.

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
the platform's axiom. Storing an effectful computation whose effects are handled *where it runs* is the open D20c;
frame-free capture is §12's "not now" entry B.

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
`type Talking[A] = A` — a row is declaration metadata and never a type (§3.3) — and `Talking[Unit]` stays in the
signature for the evaluator to reduce. What crosses the use site is the row's **entries**, with the use's arguments
substituted into them and nothing else: the definition naming the alias mints one marked binding binder per entry and
records them in its declared row, exactly as if they had been written in its clause.

**The alias is an ordinary name, and that is the whole of how a use finds it.** The alias *declares* its row on its
own declaration — `core/…/EffectSugarDesugarer` records it and mints nothing, since an alias names a row rather than
performing one. `resolve/…/ValueResolver` has already resolved `Talking` through the dictionary, and reads the
declaration that name resolved to for the row it declares, resolving the entries **in the alias's own scope** before
substituting the use's arguments — the same reading `superConstraints` makes of an ability's own `~` constraints
(§2.5). So import scope, shadowing, privacy and qualification are the ordinary ones.

A definition's clause and a named row **compose**, because the alias contributes to the return *position*:
`def announce(n: String) uses Log: Talking[Unit]` declares both, and an effect named twice is declared once. That is
how eliot-test's `type Test = {Writer[List[TestResult]]} Unit` is widened where a suite's cases perform for real:
`def testCases uses Console: Test`.

**One limit remains**: it works in **return position only**, every other position being an error rather than a
silent widening. A set of effects named *in* a clause (`uses Git`, `uses *, Git`) is **D20a**, open.

v5's `ability Web[F[_] ~ Console & Log]` — a set of abilities required *of a binder* — has no spelling: with no
carrier binder there is nothing to hang such a requirement on, and a name for a set of *implementations* is likewise
deliberately absent (§12, "not now"). What survives is the ordinary **superability closure** on a `~` constraint:
`~ A` is closed under what `A` itself requires of the parameter this use bound to this binder
(`ValueResolver.superConstraints`, transitive and idempotent).

### 2.5 `~` and `&`

`&` is a standard-library name: `infix left type &[A, B]` in `eliot.lang.Ability` (prelude, so ambient). The
parser accepts *any* operator between two `~` constraints and `ValueResolver.resolveCombinator` looks the
name up in the ordinary dictionary, requiring `WellKnownTypes.abilityCombinatorFQN` — so a module declaring
its own `&` takes the name back and gets a diagnostic instead of the built-in meaning. The combinator rides
ast→core on the unresolved constraint's `combinedBy` purely to reach that check and is dropped there; no
phase past resolve knows it existed, because `combinedBy` exists only on the *unresolved* constraint type.

An ability name resolves like every other name: `ValueResolverScope.getAbility` is a keyed lookup of the
ability's **marker** (`QualifiedName(n, Qualifier.Ability(n))`) in the ordinary dictionary, with the same
`privateNames` fallback a value name gets. **Do not reintroduce the scan**, and do not add a second lookup path for
ability names.

`~` stays reserved — it is a binder marker like `:`, not a name. Taking it into user space is **D3**.

Constraints travel in **two** generic types, one per resolution state: `ast/fact/UnresolvedAbilityConstraint[E]`
(ast and core) and `resolve/fact/AbilityConstraint[E]` (resolve through operator resolution). A `uses` entry is parsed
as the same `UnresolvedAbilityConstraint`, which is why an entry and a `~` constraint resolve alike.

### 2.6 What deliberately has no spelling

- **A stored effectful computation** (§2.3), and **a native that keeps a callback** — the platform's to declare.
- **`with` after a definition's own clause**: a second spelling of something the signature already states. (`uses *`
  on a definition was listed here too until D24 made it required, as a marker, where a parameter has one.)
- **`with` on a parameter or a field** (D35): an implementation is installed in one place, around the run.
- **An effect variable** (`f uses E: A => B` with `uses E` on the callee, Koka's spelling) and **effects on a function
  type** (`A => B uses Console`). The first needs row unification, the closed inference class; it is also
  unnecessary, since code is resolved lexically and never kept, so every code argument of one call is written in
  one scope and `*` is the one implicit variable there ever is. The second is the row on `VPi` (§12).
- **A row alias with its own AST node**, and a bare set of abilities with no payload (`type Web = {Console, Log}`):
  not a type, and a new `ast.fact.Expression` case is not how this language grows. D20a may name a set in a clause
  without one.
- **A name for a set of implementations**, and a `with` that binds several at once. §12, "not now".
- **A negative effect.** Discharge is a frame, so a discharged entry simply never joins the clause.
- **Row or binding inference of any kind.** See §5.

## 3. The mechanism

### 3.1 The desugar mints a binder, and one walk checks every use

**The parser writes a clause onto the row node the rest of the compiler always read** (`Expression.EffectfulType`):
a definition's onto its return type; a parameter's onto the slot's type, or for a function type onto its final
codomain as written. So `body uses *, Mocking: Unit` reaches `core` as the slot `body: {Mocking} Unit`, and no
phase past `ast` learned the clause — except one bit the row node cannot carry, **closed**, which rides
`ArgumentDefinition.closedRow` into `ParameterEffects.closed` and `CallbackEffects.closed`.

Then two passes before monomorphization, both syntax-directed, both reading declarations only — and neither decides
which implementation runs: that is the evidence in scope after monomorphization (§3.4).

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
existed. The binder itself stays: it is the position, in a specialisation's key, that the evidence fills after
monomorphization (§3.4). Minted binders are still *placed* together, but they need not be a **prefix**: `typeArgs` applies
positionally, and the write merges the marked indices with what the call determines for the rest — which is
what lets a member of a *parameterised* ability declare effects of its own. Where nothing determines a binder that
stands before a binding (`greetAs(Bob(…))`, whose `A` only the checker reads off the argument), a body's write puts the
**hole** `eliot.lang.Implementation::Inferred` there, which the checker replaces with a fresh meta exactly as it
treats a binder left off the end of the list; a binding itself is never left to one. The pass is idempotent, and its
idempotence test is the same mark (`GenericParameter.isBinding`).

**A `with` supplying arguments is a call before it is a `with`.** The resolver lowers `greet with scripted("Bob")` to
`scripted$with("Bob", greet with scripted)`, a call of the implementation's body-less **binder** declaration
`def scripted$with[Subject$](answer: String, subject$: Subject$): Subject$` (`ast/fact/ImplementationParameters`), so
the checker types the arguments as any call's, and they stand outside the `with`, where they are computed. The row
phase walks the subject exactly as it would walk the `with` alone (`BindingWriter.walkSupplied`), and checks the count.
The binder's type parameters are the implementation's own, which the entries the `with` binds decide where it runs
(§2), so the same walk writes them: the implementation's pattern matched against those entries' arguments as the
definition spells them, so `pickInt(1) with mapping(x -> show(x))`, for `implement[T] mapping(f: T => String):
Source[T]`, is `mapping$with[Int](…)` and the lambda is typed at `Int => String`. Where only the checker can read an
entry's arguments — `pick(1) with mapping(x -> show(x))`, for `def pick[A](a: A) uses Source[A]`, whose `A` comes from
`1` — the walk writes a **keyed hole** at the callee's binder and at the binder's, `pick[Inferred[k]]` and
`mapping$with[Inferred[k]]`, and the checker reads every occurrence of one key as one metavariable
(`BindingWriter.sharedHoles`, `CheckState.sharedHoles`): nothing is decided before the checker, the key only says that
two places stand for one type. Entries that disagree leave the arguments to type themselves. A keyed hole the match
nests inside a larger type (`mapping$with[List[Inferred[k]]]` for a `Source[List[A]]`) the checker evaluates as a
parameter bound to its key's metavariable, so the one evaluator has no hole of its own. Nothing ever runs the binder:
its call is where the evidence is installed after monomorphization, and the environment threading replaces it (§3.4).
**Every** `with` in a body is lowered so (D35 step 2), one supplying nothing to a binder taking only its subject —
`greet with recordingConsole` is `recordingConsole$with(greet with recordingConsole)`, for `def
recordingConsole$with[Subject$](subject$: Subject$): Subject$`, which takes none of the implementation's type
parameters, since nothing in the call could decide them — so the specialisation pass and the threading see each place
an implementation is installed.

A **row alias** (§2.4) adds no mechanism of its own: a definition naming one as its return type is handed the alias's
row *entries* by `resolve/…/ValueResolver`, which mints them exactly as `EffectSugarDesugarer` mints entries written
in a definition's clause.

**`row/BindingWriter`**, run by `RowElaborationProcessor` between the recursion gate and saturation, is the **scope
check** (§4), and readies the definition for the checker. Three jobs, one walk:

1. **answer the bindings** — each binding binder, read off the callee's marks, is answered by the resolution order
   below, which is the scope check, and written `Default`, merged by index with whatever else the call determines. A
   binder is never left to a metavariable; which implementation it stands for is the evidence's, after
   monomorphization (D35).
2. **thunk and apply** — an actual delivered to a nullary code slot is wrapped in a lambda, and a reference to one
   of *this* definition's nullary code parameters is applied to `unit`. Doing both unconditionally is what makes a
   pass-through (`runAbort(computation)`, `val restFailures = rest`) come out right with no inspection of the
   argument's shape or type: wrap and apply are inverse, so a pass-through is an η-expansion. (The `unit` is where
   the environment goes after monomorphization, §3.4.)
3. **erase `with`** — the node puts an implementation in scope for its subject; once the subject is checked it is
   dropped, and the call of its implementation's binder the resolver left around it is where the evidence installs
   the implementation after monomorphization.

**What answers a use — the resolution order**, walking outward lexically:

1. the nearest enclosing `with` for that ability;
2. this definition's own phantom binder for it — a **received** binding, filled by its caller. There is no
   graph to sum: forwarding is the enclosing signature, read once;
3. for an actual at a code slot, the entries that slot **supplies** (§2.2), which the callee's runs give — rule 5
   holds the callee to running the code where something installs them;
4. `Default`, "search at the ground arguments" — the two-site resolution — for an ordinary **ability**. For an
   **effect** there is no default (D26): an uncovered one is *"This value performs the effect 'X' but does not declare
   it; add it to its `uses` clause"*, reported here at the reference.

**A use is of an entry, not only an ability, and entries compare as they are spelled** (D30). Each step above looks
for a binding of the reference's ability that **answers its entry**: one is passed over only when both sides are
spelled and the spellings differ — the arguments as this definition writes them, the call's determined arguments
substituted into a callee's entry and the definition's own binders by their names (`BindingWriter.spelledRendered`),
rendered and compared. So inside `catch[Refused, String](checked(…), …)` the slot's `Throw[Refused]` does not answer
`checked`'s `Throw[Missing]`, which goes on to the enclosing definition's own entry or is the "performs but does not
declare" error, and in a definition generic in `E` a received `Throw[E]` is told from a received `Throw[String]`. An
entry with a binder the call leaves to the checker (`raise`'s `Throw[E]`) is not spelled, and answers the nearest
binding of its ability (`OverDeclaredEffectIntegrationTest`). Whether a slot entry **rides** (§2.2) is decided on the
declarations alone, never at the arguments a call determines: the slot's entry rides iff the callee's own clause
spells it the same, so `mocked`'s own `Throw[AssertionError]` does not make its slot's `Throw[IoError]` ride, and
`raising[E]`'s `Throw[E]` is supplied even at `E := AssertionError`. A slot means one thing at every instantiation.

**Code and values are told apart by where a lambda stands, read off the declaration** (rules 2–4). A lambda is
walked as code only where it is the argument of a code parameter (up to that parameter's arity), the head of an
application, or an arm of a lowered `match` (`handleCases`/`typeMatch` and the `$selector` the lowering applies to
its arms, which are the author's code in place). Anywhere else it is a value, walked in `Scope.enterValue`: every
binding from outside is marked, so an effect taken from one is the rule-2 error, and a code parameter referenced
inside is captured, the rule-3 error. An argument at a closed slot is walked in `Scope.enterClosed`, the same cut
with the slot's riding entries left reachable. A run of one of this definition's code parameters must be handled for
each entry its slot supplies (`BindingWriter.checkGiven`, rule 5): by a `with` around the run, or by a callee's slot
that supplies it — a row of the definition's own is not a handler, nor is a `with` further out than the run.

**A `with`'s implementation performs its clause effects where the `with` stands.** Its clause entries beyond its
members' are answered there, by the same resolution order, and the evidence the `with` installs closes over what
answers them (§3.4). A body `with` whose named implementation performs an effect in its clauses may not be used
below a nearer binding of that effect (`BindingWriter.checkClauses`): *"… and a nearer binding of 'X' stands between
them"*. "That effect" is the clause's **entry**, looked up at its own arguments, so a nearer `Throw[NotFetched]` does
not stand between a `with` and the use of an implementation performing `Throw[GitError]`, and the `with`'s own binding
never stands between. The threading no longer needs the rule — a `with`'s clause frames are its closure's, found at the
`with` — and it is kept until a decision removes it (D35 step 5). A `~` constraint of the implementation holds no
frame — an ability has no named implementation (D29), so it is the two-site search's — and needs no check. An entry
the implementation's clauses only repeat from their members is none of this: dispatch hands such a clause the call's
binding (§3.4), so the `with` answers nothing for it (`BindingWriter.perCallBinders`, which reads the clauses and their
members off `ModuleAbilities` exactly as the threading does). That is what lets the entry point bind `main`'s
`FileSystem` with no `Throw[IoError]` in scope. A body `with` no reference in its subject is written from is *"This
`with` binds nothing …"*, with the help naming the slot's clause when its subject is a code parameter whose clause does
not list the effect.

**Effect-ness is read from one place only**: the callee's declaration. An ability appearing in
`effectRow.returnEffects` is an effect at this reference — which for an `effect`'s member is what membership
recorded, and for an ordinary definition is what its `uses` clause says. A `~` constraint's ability is in no row,
so it defaults. Nothing keys on a name or a shape.

**Both halves of a definition are written, because both hold references.** A guarded return
(`def head[COND: Bool]: if(COND, String[]) else raise("empty")`) is compile-time code in type position, and
its `if`/`else`/`raise` are ordinary calls with code slots and phantom binders. What differs is only the scope
check: a signature's effects are the guard channel's vocabulary, discharged by the guarded-return read rather than
performed, so an uncovered one is not reported, and the value and closed cuts do not apply. An uncovered `raise` or
`abort` runs on what its platform discharger's runs give — `runThrow`'s, `runAbort`'s (`WellKnownTypes.guardDischargers`,
`Evidence.Discharged`, §3.4) — because the guarded-return read *is* that discharge, run by the compiler (D26); any
other uncovered effect there has no evidence, and its operation stays undispatched and stuck. Nothing else is exempt: the synthesized entry point is an ordinary
definition writing a `with` per entry of `main`'s clause (`row/EntryPoint`), so there is no run boundary.

**Pure code is untouched**, and so is code that only forwards: a definition with no clauses, no constraints and
no callee with one is returned unchanged.

Two mechanical invariants of the pass, both of which cost real bugs to learn:

- **Position fidelity.** The walk returns the **original** nodes when nothing changed. Rebuilding an equal
  spine re-attributes it to per-argument positions, silently moving every diagnostic anchored at a call and
  duplicating LSP hover hints.
- **The universe is built by demand, not guessed.** `RowChecker.Universe.onMiss` reports every name consulted
  but absent, and `onMissClauses` every implementation whose clauses were asked for; the processor fetches exactly
  those and repeats until a round misses nothing new. Guessing would fall back to unknown-callee approximations, and
  an unwritten binder would be left for the checker to ground.

### 3.2 What the walk may consult

Declarations, and nothing else: the callee's declared parameter and return types, its declared row and which of
its parameters are code (with their arity and whether closed), its membership in an `effect`/`ability` block, the
implementation names a `with` resolved to with their clauses and the members those implement, and one level of
type-alias expansion inside those signatures.

There is **no classification left to approximate** — v5's elaborator had to classify each slot as carrier-headed or
payload, pinned or open, and needed a written whitelist to keep those approximations from accreting. A parameter
either has a `uses` clause or it does not, and a lambda is code or a value by the slot it stands at, both read off the
declaration. A rule that inspects a *sibling argument's expression shape* is inference, not desugaring, and is
prohibited; a decision that cannot be made from a declaration is a gap to close **in the declarations**.

The fail-safe direction is built in: a use nothing answers is an aborted definition with a violation at its own
position, never a binding silently left to the checker. The specialisation pass after monomorphization reads only
what this walk accepted, so on the runtime track a reference with no evidence is an internal error (§3.4).

### 3.3 One verifier, and one precondition

Checking a runtime term yields a **payload type** (the existing NbE judgment, which never sees an effect)
and a **row** (a second output, exactly as an `Int`'s range lives in the refinement channel beside the type,
not inside it). Row constraints are set-shaped: union for sequencing, inclusion for boundaries
(`derived ⊆ declared`) — commutative and order-independent, so no argument-order sensitivity can exist. A clause is
declaration metadata on a definition or a parameter, never part of a type, so `unify` sees `Function[A, B]` on both
sides of a code parameter and a value alike.

**The verifier is the scope check**, per definition, pre-monomorphization: not a separate pass but the write's
own walk (§3.1). An operation or a callee with a clause needs a covering declaration, and the only places one can
come from are the enclosing def's clause or constraints, an enclosing `with`, or a slot's clause. It is complete
before monomorphization, since nothing about it is instantiation-dependent; it emits *"This value performs the effect
'X' but does not declare it; add it to its `uses` clause"* at the reference, and it owns the code/value, closed and
given checks of rules 2–5. *Forward what is declared, derive what is done* — a forwarded per-operation verdict would
be a checker self-report and is rejected, as is any negative-effect surface.

A second, post-monomorphization verifier existed and was retired by measurement (D7, 2026-09-10): once `printLine`
is dispatched to the implementation's method, which declares no row, a monomorphic body
can only see propagation through a *declaring callee*, never a direct operation call — a strict subset of the scope
check. **Do not reintroduce a post-mono effect verifier.**

What sits at that seam is **not** its shadow: `monomorphize/channel/SuppliedRowArgumentsProcessor`, wired as a
**codegen precondition** via `getFactOrAbort`, rejecting a supplied entry whose type argument nothing determines
(§2.2): *"Cannot tell which 'Throw' this call supplies … Write it out at the call"*. That check needs ground arguments
and so cannot move earlier. The specialisation pass and the environment threading (§3.4) sit at the same seam and
report the plumbing errors only they can see — two received entries that coincide at an instantiation, an
implementation whose pattern does not match the entry it runs for — which are not effect verification either: every
use they meet was checked by the walk.

### 3.4 Evidence — the three primitives, frames, and the specialisation pass

A resuming clause is a call and needs nothing. A finishing clause is a **non-local exit**; a stateful
implementation needs a value **threaded through calls that never mention it**. Neither is expressible as a def
in a strict pure core — under v5 both were expressed by the transformer instances, which is the whole reason
the carrier existed. They are now three **platform-private leaves**, one per target, and nothing else:

| primitive | shape | jvm | microcontroller (hypothetical) | compile track |
| --- | --- | --- | --- | --- |
| escape | `escapeInternal[K, A, R](body uses *: ThrowFrame[K] => A, onExit uses *: K => R, onValue uses *: A => R): R` — abortive, never re-entered | a fresh frame object handed to `body`; `exitInternal(frame, err)` leaves through it by identity, and any other frame's exit passes through | a jump buffer (`setjmp`/`longjmp`), or a status flag and a jump (§13's A2) | an evaluator intrinsic, `escapeIntrinsic`/`exitIntrinsic`, the same shape: a fresh frame handed to `body`, exited by identity |
| cell | `withCellInternal[S, A, R](initial: S, body uses *: StateCell[S] => A, combine uses *: A => S => R): R` — scoped to one call | a one-slot array made per call and handed to `body` | a slot in the primitive's own stack frame | an evaluator intrinsic still keyed by type (`eliot.compiler.Cell`); no compile-track effect is written over it |
| loop | `foreverInternal` | `while(true)` | the super-loop | never runs (`Inf` is stuck) |

**The control effects' single implementations are named, written over them, and take the frame as a parameter** —
the frame is their environment — and **the platform's discharger binds one with an ordinary `with`**, supplying the
frame its primitive made (D25 step 4, built 2026-10-14):

```eliot
implement[E] escaping(frame: ThrowFrame[E]): Throw[E] {     -- jvm layer
   def raise[A](err: E): A = exitInternal(frame, err)
}

def runThrow[E, A](obj uses *, Throw[E]: A) uses *: Either[E, A] =
   escapeInternal((frame: ThrowFrame[E]) -> obj with escaping(frame), (err: E) -> Left(err), a -> Right(a))
```

`aborting(frame: AbortFrame[Unit]): Abort` likewise over `escapeInternal`; `holding(cell: StateCell[S]): State[S]`,
`writing(cell: WriterCell[W]): Writer[W]` and `providing(cell: DepCell[X]): Dep[X]` over `withCellInternal`. **A
primitive gives no effect**: it hands a fresh frame to a function, and what runs on that frame is the `with` its
caller writes. The `with` is in the body, never on the slot, so the base's body-less `runThrow` and the jvm body stay
textually equal, and a block a caller writes at `runThrow`'s slot finds `escaping` because `runThrow`'s body runs `obj`
under it — the overlay's own on the compile track. Every other discharger (`catch`, `else`, `runStateToValue`, …, and
every user discharger over them) writes no `with` and names nothing: it passes its code on to a slot, and a block is
given what the callee's runs give, so `escaping` reaches `catch`'s block, then `mocked`'s. That costs one wrapper
lambda per discharger instantiation, `frame -> obj(frame)` where the primitive was handed `obj` itself (+2.9% jar size
over the examples); an η-reduction in the backend would remove it.

**An implementation is evidence** (D35, built 2026-10-17, over D20c option 5's environments, built 2026-10-12). Its
code — the clauses its operations dispatch to — and its environment — a frame, a cell, its own parameters — are one
value, made after monomorphization, passed, and never searched for. The checker never sees it, and the marker does not
grow. Evidence comes into scope three ways, and a use is answered by the **nearest evidence answering its entry**
(`EffectDeclarations.answers`: held evidence answers its very entry, installed evidence any entry of its ability):

- **received** — a definition's binding binders hold its caller's evidence, at the definition's own type arguments:
  `greet[recordingConsole[writing[Default]]]` receives `recordingConsole` for its `Console`;
- **given to a block by the callee running it** — at each run of a code parameter the block finds, for each entry its
  slot lists, the evidence nearest **the run**: a `with` around it, a discharger the run is passed on to, or, for a
  ridden entry with nothing nearer, the definition's own received evidence. The callee records it per slot
  (`MonomorphicEffects.Slot.variants`, read off the callee's **specialised body**, never off its declaration), and a
  caller's block is specialised against it — once per distinct evidence the runs give (below). A run passed on to another callee's slot finds what that callee's runs
  give, so `catch`'s slot offers what `runThrow`'s does. What the block uses through `*` unlisted it answers with the
  evidence where it was written, as a closure captures. So D22's meaning is plain capture — an unlisted effect is
  invisible to the callee because the block already holds its evidence, and a listed one is found where the code runs,
  code and frame together — and (b)'s "ride by code, give by frame" is no longer a rule;
- **installed by a `with`**, at the binder call the resolver left around its subject (`scripted$with(xs, subject)`):
  the implementation, holding the `with`'s arguments as its parameters and closing over the evidence in scope **at the
  `with`** for the entries its clauses perform beyond their members'.

**The checker checks without the binding; the specialisation pass writes it** (`monomorphize/specialise/`). On the
runtime track the checker checks a value at its key with every effect binding left `Default` (`CheckedValue`;
`EffectBindings` reads which binders bind an effect off their `~` constraints, since the mark is erased at `row`), so a
definition is checked once per instantiation of its ordinary type parameters, and every operation of an effect is left
undispatched. `SpecialisationProcessor` produces the `MonomorphicValue` everything downstream reads, at the full key:
`EvidenceSpecialisation` walks the checked body with the evidence in scope, writes each callee's effect bindings from
it (`EffectDeclarations.bindingOf`), and dispatches each operation to the clause of the implementation the evidence
runs — a named implementation at the type arguments its pattern takes at the entry (`NamedImplementationArguments`),
its clause's per-call entries at the call's evidence (`DeclaredEntries.forwarded`). Its own errors are plumbing the
walk cannot see — an implementation whose pattern does not match the entry it runs for, a clause no layer on the path
bodies, a body-less discharger whose platform layer is absent — never effect verification.

```
checked  greet[Default]                            = printLine[Default]("Hello")      -- Console.printLine, undispatched
at       greet[recordingConsole[writing[Default]]] = recordingConsole.printLine[writing[Default]]("Hello")
```

Every piece of evidence the pass meets is a **constant** — every binding in a key and every `with` names a
compile-time implementation — so the pass clones a callee per distinct constant evidence. That reproduces the
specialisations the binding in the checker's key used to give (all 47 example jars byte-identical across the change),
always succeeds on a program the scope check accepted (there is no recursion and code is never kept, so the evidence at
every point is known by cloning along a finite call tree), and decides only **which code is generated**, never a
binding: correctness never depends on it. Measured on eliot-build's suite, 2804 checks serve 2813 specialisations and
the pass costs 0.65 s of a 45 s build.

**The environment is threaded from the same evidence**, after the pass, at the `WovenValue` seam
(`monomorphize/channel/EnvironmentThreading`), whose ground re-check (`WovenRecheck`) checks the plumbing as it checks
every woven body. Three routes carry one:

```
increment[State@Default](env)  = putState(env, state(env) + 1)          -- a received entry is a hidden parameter
runStateToPair(initial, p)     = withCellInternal(initial, cell -> p(cell), …)   -- a slot's block takes its frame
main                           = …(runStateToFinalState(5, cell -> { increment(cell); double(cell) }))
```

- **A received entry is a hidden parameter.** Each value's mono records its entries at ground arguments
  (`MonomorphicEffects`), and a definition receives one frame per entry of its declared row and per `~` constraint,
  ahead of its own parameters; a reference to it passes the frame of the evidence in scope for each.
- **A block takes what its callee's runs give.** A row-typed slot's thunk takes the frames of the entries its slot
  lists in place of `unit`, at the bindings the callee's runs give (`Slot.variants`), and each run passes the frames of
  the evidence it finds there. Running a block therefore consumes the entries its slot rides (§4): with nothing nearer,
  what it finds for one is the definition's received evidence.
- **A dispatched operation gets its frame's parts.** A reference the pass dispatched to an implementation's clause
  (`MonomorphicValueReference.dispatchedFrom`) passes the frame of the entry it was dispatched from: the frames its
  clauses perform on, then the implementation's own parameters, which the clause receives as leading value parameters.
  A `~` constraint holds no frame — the two-site search answers it with an anonymous implementation, which takes no
  parameters (D25, D29).

**How a frame is chosen.** An operation runs on the frame of the evidence answering its entry, which is the evidence
its binding was written from, so code and environment always come from the same place; a disagreement between the two
is an internal error, never a pick. Evidence is found **by entry at ground arguments** (2(c)), so `uses Throw[IoError],
Throw[GitError]` is two frames, and two received entries that coincide at one instantiation are an error where one is
used — without it, which of the two an operation reaches would be a silent pick. Held evidence carries its frame;
installed evidence's frame is built where an operation runs on it, from the `with`'s arguments — the threading turns
`scripted$with(xs, subject)` into `(answer -> subject)(xs)` and walks the subject with `answer` supplied to `scripted`
— and from the frames its clauses found at the `with`. An implementation taking parameters reaches an operation only
through a `with` that supplied them, and an undischarged control effect at `main` is the entry point's error (§1 rule
5).

**What that does and does not remove.** There is no search across calls: evidence reaches a definition only as a value
passed to it, so a helper's own `catch` or `State` cannot intercept the effects of a block its caller wrote with
`uses *` — the block captured its writer's evidence (§8 items 16–17). The one choice left is the lexical one above,
made inside the body that runs a block, and it is only ever over an effect the slot **lists**: a handler chosen by the
callee, as in Koka, which compiles handlers by the same evidence passing (Xie and Leijen), and Effekt, whose
second-class blocks are rule 3. That is the decided meaning of listing (D22).

**Runs given different implementations get a block each** (D35 step 5a, built 2026-10-17). The pass records, per
slot, every distinct binding vector the runs of the parameter give, in the order of their first run — its
**variants** (`MonomorphicEffects.Slot.variants`). The callee takes one block per variant: variant 0 is the parameter
as written, each further variant a parameter right after it (`MonomorphicEffects.positions`, `variantName`), and each
run is pointed at the parameter of the variant it gives. The caller writes the block once per variant, each copy
specialised against that variant's evidence and threaded with its frames, so the copies' dispatch and their parts
may differ:

```
twice(body, body$variant1) = run(_ -> body(unit)) ++ runWriterToLog(scripted$with("b", body$variant1(unit)))
main                       = twice(_ -> greet[fixed](…), answer -> greet[scripted](answer))   -- after threading
```

A slot with one variant is exactly what it was, so a program whose runs agree compiles to the same bytes (every
example jar byte-identical). Passing on composes: a run inside a block at another callee's slot is walked once per
variant of that slot, so a definition passing its code to two callees gives the union of what they give. The cost is
code size, one copy per distinct evidence and only where runs differ. Termination is the pass's own — no recursion,
so finitely many variants. The copies share their source positions, so the refinement channel walks each, and a
position whose copies disagree is ⊤, the reconcile pass's fail-safe for an ambiguous position; a `where` companion
is matched against the declared arguments, the further variants dropped. The compile track compiles no copy: its
evaluator applies a callee to the arguments the checker typed, and no compile-track effect has two implementations to
differ by, so runs differing there stay an error.

**Decided in the code while building, recorded for sign-off** (§11):

- an `implement` clause gets **no frame for an entry it only carries** for a sibling (`ImplementationRows`' union):
  its binder keeps its positional slot but no constraint (`FunctionDefinition.carriedEffects`);
- an effect a **member declares** (`FileSystem.readFile`'s `uses Throw[IoError]`) is per call: its frame comes from the
  call site, and so does its binding — dispatch hands the implementation's method the call's evidence at each clause
  binder whose entry the member declares, compared as the declarations spell it (`DeclaredEntries.forwarded`) — and an
  implementation's environment holds only the effects a clause performs that its member does not declare;
- supplied-versus-rides is decided **as the declaration spells it**, not at ground arguments (§2.2), and two received
  entries that coincide at one instantiation are a use-site error;
- frame types are public, body-less, constructor-less types (`ThrowFrame`, `StateCell`, …), because `private` reaches
  only `def` and `data`, and they are named apart per module because `eliot.effect` is imported whole.

**The primitives are private, and the dischargers are therefore abstract in the base.** A public cell is Landin's
knot — a cell holding a closure that reads the cell is a loop, and `termination/PurityGuardTest` exists to keep it
out — so `withCell` may not be a base name, and `escape` follows for uniformity. `runThrow`, `runAbort`,
`runStateToPair`, `runWriterToPair` and `provide` are body-less signatures in the base and bodied per platform;
`catch`, `else`, `runStateToValue`, `runStateToFinalState`, `runWriterToValue` and `runWriterToLog` are ordinary
platform-independent bodies over those and stay in the base. Eliot has no *layer*-private visibility — `private` is
module-scoped — so each jvm module needing a primitive declares its own copy: five copies of two shapes, which also
separates the frames for free and lets each copy name the frame type it makes.

**The compile track is threaded too** (built 2026-10-13). Its control effects — `Abort` and `Throw`, the only ones that
run at compile time — are the compile-track overlay's (`stdlib/eliot/compiler/eliot/effect/`), spelled exactly as the
jvm layer's (`implement aborting(frame: AbortFrame[Unit]): Abort`, so the two are one implementation and the overlay's supersedes
on that track) over the evaluator's own `escapeIntrinsic`/`exitIntrinsic` (`EffectIntrinsics`): a fresh frame with
identity is handed to the body, and an exit leaves through exactly the frame it was given. The names differ from the
jvm leaves' on purpose — an intrinsic serves both tracks' evaluators, and the runtime track must keep finding no binding
for a jvm leaf. The compile track's key **keeps** its bindings, since its evaluator needs an implementation's code
where it reduces, but its checker dispatches no effect's operation either: the specialisation pass dispatches them from
the evidence the key receives, after the read-back in `CompilerMonomorphicTypeCheckProcessor` and inside the checker's
reductions (`PostDrainQuoter.threaded`). A compile-track key that does not hold every binding — a partial key — or
holds `Default` for one, as a type-level read may write, leaves the operation undispatched and stuck, the fail-safe
direction: nothing reduces past it. A frame reaches its operation by the same threading, `EnvironmentThreading` read
through a track: a `CompilerMonomorphicValue` records its `MonomorphicEffects`, and every compile-track evaluation that
can reach a frame evaluates threaded code —

- a callee linked into a reduction is its specialisation's reduced body, threaded (`ReducedBindingClosure.threaded`);
- a value's own body, read back, is threaded with the effects it receives (`PostDrainQuoter.reduceSourced`), and so is
  the refinement channel's reduction of a `^Meta`/`^Where` companion (`EscalatingReducer.reduceApplied`);
- a **signature** is threaded with its own control effects given by the **guarded-return read**: a `raise` or `abort`
  nothing in the signature discharges runs on what its platform discharger's runs give (`Evidence.Discharged`,
  `WellKnownTypes.guardDischargers`), on a frame the read installs around the evaluation and its read-back, and an exit
  through it is the rejection `Left(payload)`, so a rejected guard reports its author's message
  (`PostDrainQuoter.Threading`, `guarded = true`).

Such an evaluation links every callee reduced at its own instantiation from the start, never a raw binding, which is
unthreaded and takes no frame; one referenced at two instantiations gets a binding per instantiation. Two guards keep an
unthreaded evaluation from answering: a signature that can run a control effect is never read raw, and an escape whose
body settles to a result still holding an undispatched operation or a stuck escape or exit stays stuck — read raw, an
operation never ran, and a handler that ignores its argument (`_ -> false`) would otherwise turn that into a plausible
wrong value. Pure compile-time code reaches no frame and is reduced exactly as before.

### 3.5 Discharge

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or
thread through, and the entry simply never joins the clause — so there is nothing to spell as a negative effect,
and a wrapper-reached discharge inside a `uses Console` body just compiles.

**Nesting order at the run site decides interaction**, and it is written where the frames are installed:
`runStateToPair(s, runThrow(c))` versus `runThrow(runStateToPair(s, c))` is the difference between state
surviving a `raise` and not. There is no canonical form to fix and no ordering for the compiler to choose.

A discharger may be called any way a function can be, since `.`'s `f uses *: A => B` is a code slot and passes the
call through as the code it is. A discharger's **handler may itself perform effects** (`onError uses *: E => A`,
bound where it is written).

**A `val` is a bind, and binds the value** (D17). A `val`'s right-hand side is a value position, so rule 1 applies to
it unchanged: `val x = lookupConfig("db.url")` *runs* the lookup there, charges the enclosing definition, and binds
`x` to the `String`. `val x = comp` followed by `x else fallback` is therefore the error it looks like, reported on the
right-hand side; the discharge belongs there (`val x = lookupConfig("db.url") else "gave up!"`). A `val`-bound lambda
is a value, so it may use no effect from around it.

**Every effect is bound the same way, and only what is bound differs** (D26). A control effect — `Throw`, `Abort`,
`State`, `Writer`, `Dep` — has one named implementation per platform, taking the frame its discharger's primitive made
and bound by that discharger's `with`; `Inf` and the interpretation effects — `Console`, `Log`, `FileSystem`,
`Process`, `Environment` — have the base's `standard…` ones, which the entry point binds and a test replaces with its
own. That is a description of what the platform ships, not a distinction in the language: nothing keys on it, and
whether an implementation needs an environment is decided per binding by how it is written.

### 3.6 An effect is declared, never read off a shape

Membership in an `effect` block says a member needs that binding; a **constructor class** is simply an `ability`
(`ability Container[F[_]] { def wrap[A](a: A): F[A] }`), and needs no exception. Whether an ability is an effect is
therefore read off its members' rows, in one place (`ability.util.AbilityKind`), and an `effect` with no operation,
which would leave no record, is refused at its declaration.

**No phase reads effect-ness from a name, a shape, or a higher-kinded binder.** The one reading is the callee's
declaration (§3.1). Whether a parameter is code is read the same way — off its clause, never off its type's shape.
The `<Ability>Carrier` naming convention, an LSP reverse table and "has an `Effect` instance" all miscompiled in both
directions under v5; there is no carrier to recognise now, and the prohibition is kept so it is not reinvented.

The one structural predicate that survived carrier identification is **not about effects**:
`CarrierKindChecker.isHktBinder`, "is this binder higher-kinded?", which `check/CarrierKindChecker` asks in order to
reject a `[F[_]]` binder instantiated at a fully-applied proper type. That is a kind system living next door, and it
is soundness (§12).

### 3.7 Rendering

There is nothing to invert. An effect is an ordinary nullary ability, an implementation is a name, and code is a
thunk, so a type contains no machinery to hide: `GroundValueRenderer` prints what the user wrote.

### 3.8 What the checker holds

**No effect rule at all.** There is no lift, no `pure`, no `Id`, and no effect diagnostic in the checker.

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
- **a reference** to a callee with phantom binders answers each binder by §3.1's order. An **ability** with
  nothing in scope binds `Default`; an **effect** with nothing in scope is the error, at that reference.
- **entering an argument** at slot *i*: the walk descends with the slot's supplied entries answered by the slot iff
  parameter *i* has a clause, and in the closed cut iff that clause has no `*`. A
  lambda at a parameter with no clause — or in any other value position — is entered in the value cut: bindings
  from outside are marked, and an effect taken from one is the error at that use.
- **an unapplied reference to a definition with a clause** — one given fewer arguments than it declares before its
  return position (`EffectRow.returnArity`), counting those a code parameter it is the argument of calls it with —
  performs nothing where it stands, so in a value position it is the rule-2 error at the reference. A function-typed
  return is a value: `def greeter uses Console: String => String` is called by a bare `greeter`.
- **a code parameter is used, never kept**: a reference to one stands as the head of a call or as the argument of a
  code parameter; anywhere else is the error at the reference, and so is a reference inside a value lambda or a
  closed argument.
- **a listed effect is handled or declared**: each entry the parameter's slot lists and the definition does not
  declare is answered at every run of the parameter by a `with` around the run or by a callee's slot that supplies it;
  otherwise it is *"'body' may perform the effect 'X' here, which this definition neither handles nor declares"*.
- **a `with`** binds its ability for what escapes its subject, crossing a def boundary only through a declaration.
  One that no reference in its subject is written from is *"This `with` binds nothing …"*, never a silent no-op;
  one around a run of a code parameter binds what the slot lists (§2).
- **a clause on a named implementation is charged at the binding site**: `greeting("Bob") with recordingConsole`
  performs `Writer[String]` there, because the name's clauses declare it, and the binding for it comes from the same
  order. An entry a clause only repeats from its member is the call's, not the binding site's.
- **one exemption, because something else answers**: a **signature**, whose `raise`/`abort` is the guard channel's
  vocabulary and runs on what its discharger's runs give, discharged by the guarded-return read. It is not cut into
  value or closed regions. The synthesized entry point is not exempt: it binds each of `main`'s entries with a `with`.
- **a declared entry must be consumed**: an entry of a definition's own clause that no reference in its **body** is
  written from is *"This value declares the effect 'X' but does not perform it"*, at the entry, with the help
  *"Remove 'X' from its `uses` clause"*. A clause says *my caller hands me an implementation of these*; one nothing
  here uses asks every caller to declare an effect on no evidence, all the way to `main`. Consumed means written from
  at least once — by an operation, a declaring callee, or a `with`'s clause bindings — on any path, so `if(ok, v)`
  consumes `Abort` whatever `ok` is. It is per entry (D30): a reference spelled `Throw[Int]` consumes the `Throw[Int]`
  entry and not a `Throw[String]` beside it, while one the call does not spell (`raise(e)`, its `E` left to the checker)
  may stand for any entry of its ability, and consumes all of them. Running a code parameter consumes exactly the
  entries its slot **rides**, since with nothing nearer the block finds the definition's received evidence for them
  where it runs (§3.4): `def passThrough[A](v uses *, Console: A) uses *,
  Console: A = v` is accepted, while its twin over `v uses *: A` lists nothing, consumes nothing and is the error. Exempt: a
  body-less value (its clause is a contract a later layer bodies) and a signature's own references.
  An `implement` clause declares the union of its block's clause entries (`ImplementationRows`), so it is held only to
  the entries it wrote. The error waits for the definition's callees: a callee performing the effect *undeclared* is
  its usual cause, and that callee's error is reported instead (`RowElaborationProcessor.calleesWritten`).

**Suspension is row-neutral.** Whether a parameter is a value or code changes only *when* the effect runs and
*whose* declarations bind it. What the caller must declare changes only by what the callee **handles**: an effect
its slot lists and it discharges or binds is not the caller's to declare; everything else the code performs is.

**Two same-ability entries** at different arguments are two entries; at identical arguments they deduplicate.
In one slot's clause they are the shape §2.2 requires the call to spell.

## 5. Standing rules

These bind every future change to the effect system.

1. **Where decisions live.** Part I states the design as built. A decision is recorded in §11 until it is built,
   and building it changes Part I. A finding that contradicts a decided rule is a **reversal**, recorded as one and
   signed off — never an amendment in place, an appendix or a commit message. (This exists because a reversal once
   read as a refinement for six days.)
2. **Stop on conflict; do not route around it.** If Part I's rules appear to conflict with each other, with
   the tree, or with a measurement — or if a step finds itself *narrowing*, *bounding*, *deferring*,
   *approximating* or *exempting* one of them — **stop and surface it.** Do not land the workaround and
   record it as a corollary. The tells, in this project's own vocabulary: "bounded staging", "the corpus
   forced", "narrowed to ~nothing", "a small local concession", "keeps the shipped idiom", "it usually
   holds". A conflict is a decision for Robert, not a judgement call in flight.
3. **There is nothing to infer.** A use of an effect is answered, in the scope check, by a `with`, by the enclosing
   declaration or by a supplying slot, in that order — an ability's, failing those, by `Default` — and after
   monomorphization by the evidence those pass, installed, received or given: never by a metavariable, a join, a
   lattice, an ordering-sensitive slot decision, a row variable unified across arguments, or a sum over a
   monomorphized sub-graph. Carrier inference was the historical bug class (carrier theft, premature commitment);
   binding inference would be the same bug in the new vocabulary, and **dynamic scoping resolved at compile time** is
   its exact shape. Both are prohibited. `*` is not a variable: it names the scope the argument is written in. What
   ground arguments choose after monomorphization (§3.4) is only *which entry's evidence* a use finds — the nearest
   answering it — and the specialisation pass, which clones by that evidence (and, on a target without erasure, would
   hold one entry per ground instantiation of a polymorphic operation the subject reaches), decides only what code is
   generated, never a binding (D35's reading of this rule, agreed by Robert).
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
`standard…` implementation of it, with a `with` it writes; in a test it is one `with`.

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

- **A double is one declaration.** It needs no type to hang on, no colocation, and no coherence question: a named
  implementation is never searched, so it may freely overlap another. *Never searched* is enforced rather than
  assumed: the two-site search and both coherence checks read `ModuleAbilities.anonymousImplementationMethodsOf` /
  `anonymousMarkersOf`. Colocation is allowed, so it had to be safe.
- **A double cannot cheat.** A user module cannot declare a native, and the platform's natives and primitives
  are private to its layer, so an implementation reaches the world only through effects **its own clauses
  declare** — which are charged, and bound, at the binding site.
- **A double keeps its own state through an effect**: `session` above writes to `Writer[String]`, and that entry is
  charged where `session` is bound and discharged there by `runWriterToLog`. It never appears in `greet`'s clause.
  A double may also take parameters (`greet with scripted("Bob")`), which configure it per binding without a
  specialisation per value. They are values: state that changes as the code runs is still an effect's.
- **Interpretation is per effect, not per program.** `body with mockConsole with mockFileSystem` binds two
  doubles and leaves everything else bound as its caller binds it — at `main`, the platform's `standard…`
  implementations. A double's evidence is one small object per install; a `standard…` implementation's is a constant,
  and the specialisation pass compiles both to direct calls (§3.4).

**A discharge word binds doubles around its run of the code**, so a faked case writes no fixture at all:
`transcriptOf(program uses *, Console: Unit) uses *: String = runWriterToLog(program with recordingConsole)` in
`examples/src/EffectsTestFramework.els`, and eliot-test's `mocked`, whose slot lists every effect it fakes (`body uses
*, Console, Log, FileSystem, Process, Environment, Mocking, Calls, Throw[IoError], Throw[AssertionError]: Unit`) and
whose body runs it as `body with mockConsole with mockLog with mockFileSystem with mockProcess with mockEnvironment
with recording with journal`, inside the recording's `runStateToValue` and its own `catch[IoError, Unit]`. Rule 5 is
why `recording` and `journal` are named rather than anonymous: a definition with a body handles its slot's listed
effects only with what it has, and a `with` is what it has. A case then reads `"…" should "…" in mocked { … }`, a suite's whole declaration is its return
type — the row alias `type Test = {Writer[List[TestResult]]} Unit` — and a suite whose cases perform for real composes
it with a clause, `def testCases uses Console: Test`. A case that must perform nothing beyond its assertions can say
so with a closed slot (`body uses Throw[AssertionError]: Unit`, rule 4); whether eliot-test reinstates `pure { … }` is
its call. `examples/src/EffectsNamedEffect.els` is the minimal version of the above, and `EffectsFakeConsole.els` does
it for a stdlib effect.

`Dep[X]` + `provide` remains available for a seam you want stated in the signature, and swapping the platform layer
remains the whole-program integration answer.

## 7. Live limitations

Each is stated, fail-safe, and either has a plan entry or is a deliberate trade.

1. **A function-typed code parameter's own entries are not supplied.** `f uses *, E: A => B` writes `E` on the
   codomain, where the callee never supplies it, so code using `E` there is rejected rather than run on a binding
   nobody gave. No file in the corpus needs it; a nullary code parameter's entries are supplied as §2.2 says.
2. **A discharger's type arguments are sometimes written by hand.** Where no declaration determines a supplied
   entry's arguments (§2.2's three shapes), the call spells them. The rejection is loud and names the fix; the
   alternative — defaulting — compiles a `catch` against a frame it will not meet.
3. **An under-applied backend *intrinsic* has nowhere to link.** An intrinsic is emitted inline at each call
   site, so only a saturated call can be emitted at all; `digits.map(show)` for such a name is a hard error at
   the definition naming the fix (`digits.map(n -> show(n))`). This is a gap in the backend, not a rule of the
   language — the same shape for an ordinary native, ability-implementation or not, is supported.
4. *Closed 2026-10-13 by compile-track frames (§3.4)*: a guarded return's `raise("…")` is read by the guarded-return
   read as the rejection, with its author's message.
5. **A row alias works in return position only, and has no `uses` form** (§2.4). A slot that needs many effects
   writes them out. Naming a set in a clause is **D20a**.
6. **An effectful computation cannot be stored** (§2.3). Deliberate: what a frame-bound implementation captures
   cannot outlive its frame, and data describing the work covers the trampoline while keeping every step testable.
   Storing one handled where it runs is D20c; frame-free capture is §12's entry B.
7. *Moved to §8 item 26 (2026-10-13)*: measured, it was not fail-safe — the wrong frame compiled. Closed the same day.

---

# Part II — What is left

## 8. Where the tree diverges from Part I

Each item is a defect to close or a decision to make, never a doc fix. Numbers are kept so citations resolve.

**Open** (found by the code review of 2026-10-15; all three close with D36).

| # | defect | severity |
| --- | --- | --- |
| 59 | §3.8 and `CLAUDE.md` say *"the checker holds no effect rule"*, while `monomorphize/check` holds `AbilityResolver`'s `isEffect`, `DeclaredEntries.forwarded`, `PostDrainQuoter.reachesFrames`/`threaded` and `TypeStackLoop.guardRead`. D36 decides it: §3.8 is restated, the checker holding one effect rule, the hole | decision |
| 60 | which binding answers a reference whose entry the call does not spell is decided before the checker types it: in `def guarded(body uses Throw[String]: Unit) uses Throw[Int]: String`, `guarded(raise(1))` was written from the slot's `Throw[String]` and ran on the caller's `Throw[Int]` frame (silent until D35 step 5; now refused, *"Nothing binds an implementation of the effect 'Throw' here"*). D36: the use takes the nearest handle whose entry answers after evaluation, so an open slot reaches the caller's `Throw[Int]` and a closed one names what is in scope | loud |
| 61 | an anonymous implementation's clause cannot repeat a member's entry over the ability's own type parameters (`Writer[List[T]]` implemented for `Box` must spell `Writer[List[Box]]`, and entries compare as spelled), refused at the clause. D36: entries compare after evaluation, so `Writer[List[T]]` at `T := Box` answers `Writer[List[Box]]` | loud |

**Cleanup** (found by the same review; not defects). Most of it is in code D36 deletes and goes with it; the rest is to
do with the next change that touches the file:

- *Unused code:* `RowChecker.checkable`; `PostDrainQuoter.reduceSignatureToGround`; `CheckState.metaConstraints`/
  `recordMetaConstraints`/`MetaConstraint` and `CarrierKindChecker.recordMetaConstraints`; `WellKnownTypes.boolFoldFQN`/
  `boolIfFQN`; `ImplementationParameters.isDeclaration`; `Unifier.occursInValue`; `CompilerMonomorphicValue.naturalArity`;
  unused imports in `AbilityImplementationCheckProcessor`; the `IO`/`runMain` stub in `ProcessorTest.scala:50-52`; the
  compile-track type-keyed cell (`compiler/Cell.els`, `EffectIntrinsics`' cell operations, their `WellKnownTypes`
  FQNs), read only by tests.
- *Possibly dead, to prove before deleting:* `GuardDischargeResolver`'s `VMeta` arms; `UnifiedModuleValueProcessor
  .mergeEffectRows`.
- *Text that names deleted code or says the opposite of the code:* §3.8 and `CLAUDE.md` on the compile track's
  "deferred slots" (deleted in 4bb25c00; `Unifier.scala:48` links it); base `.els` docs (`File.els:123`,
  `Process.els:127` on `with standardFileSystem`; `lang/Implementation.els:4-7, 32` on `Default`; `Throw.els`,
  `Abort.els`, `State.els` saying there is "nothing to bind with `with`"; `compiler/Cell.els:6, 8`); Scaladoc in
  `RowElaboratedValue`, `RowElaborationProcessor`, `ImplementationParameters`, `EffectIntrinsics`,
  `SuppliedRowArgumentsProcessor`, `NamedImplementationArguments`, `AbilityMatcher.patternParameters`, `LspPlugin`,
  `WellKnownTypes`; carrier-era and brace-row examples in `EffectRow`, `NamedImplementation`, `ImplementBlock`,
  `WithBinding`, `BindingWriter`, `ValueResolver`, `CoreExpressionConverter`, `AbilityImplementation(Processor)`,
  `AbilityResolver`, `TypeStackLoop`, `ReducedBindingClosure`, `PostDrainQuoter`, `ArgumentDefinition` and three tests;
  `Default` described as every binder's search in `WellKnownTypes`, `ImplementationBinding`, `EffectSugarDesugarer`,
  `RowElaborationProcessor`; `CLAUDE.md`'s `people.sorted[Person]`, its `type Default` testing note (abilities only
  since D26), `Expression.typeParser` (is `typeRunParser`), `AbilityConstraint.combinedBy` (is on the ast's
  `UnresolvedAbilityConstraint`) and its inexpressible `~ Web` example. Line-level references are in the review record,
  `git show 14b61527:docs/effects.md` §8.

**Closed.** One line each; the full record is at `14b61527` (§13).

| # | defect | closed |
| --- | --- | --- |
| 1 | an unused `with` was a silent no-op | 2026-10-13, D23: an error |
| 2 | a dot-read of a row-typed field handed back the thunk | 2026-09-12; stored computations deleted by D20 |
| 3 | `val x = comp` then `x else …` failed | 2026-09-12, D17 |
| 4 | `runThrow[String, String]("no failure")` accepted | 2026-10-15, not a defect: a slot entry is a permission |
| 5 | an undischarged control effect at `main` failed at runtime | 2026-10-12, option 5 (D19): a compile error |
| 6 | a row alias works in return position only | moved to §7 item 5 / D20a |
| 7 | a named implementation answered the two-site search | 2026-09-12 |
| 8 | a slot's `with` missing from the layer merge's comparison | 2026-09-12 |
| 9–13 | a row on a slot both marked a block and minted bindings (lambdas reaching enclosing bindings, kept function parameters, a field's row by `Default`, a slot laundering an effect) | 2026-10-09, D20/D21 (rules 2, 3, 5, 6) |
| 14 | an unapplied `uses` definition passed as a value | 2026-10-13: the rule-2 error |
| 15 | an effectful member of a parameterised ability needed its type arguments spelled | 2026-10-13: the checker's hole, `Implementation::Inferred` |
| 16–19 | a helper's `catch`/`State` intercepting its caller's; a slot implementation's clause effects; two entries of one effect one binding (numbered 14–17 on the option-5 line) | 2026-10-12, option 5 |
| 20 | a slot `with` on a declared entry silently dropped | 2026-10-13, D23 |
| 21 | a rejected definition was "Name not defined." at callers | 2026-10-13: *"Cannot use 'X' here: its own definition was rejected."* |
| 22–23 | a body or slot `with` compared by ability instead of entry (eliot-build's `CacheTests`, eliot-test's `mocked`) | 2026-10-13: keyed by entry |
| 24 | an ability member's own clause binding dropped at dispatch | 2026-10-13 |
| 25 | one arity for specialisations with and without an environment (`NoSuchMethodError`) | 2026-10-13 |
| 26 | on the compile track a helper's discharge caught its caller's effect | 2026-10-13, compile-track frames |
| 27 | a guarded return's `else` arm never reduced | 2026-10-13, `EscalatingReducer.typeLevelStuck` |
| 28 | a `where` over a control effect could not be evaluated | 2026-10-13, compile-track frames |
| 29 | a rejected `main` reported a second, misleading error | 2026-10-15, `CompilerNativesProcessor.runtimeConcrete` |
| 30 | a named implementation's own type parameters never solved | 2026-10-14, D25 step 1 (`NamedImplementationArguments`) |
| 31 | a `with`'s arguments typed from themselves alone | 2026-10-14, `BindingWriter.suppliedTypeArguments` |
| 32 | a slot naming a constrained named implementation rejected | 2026-10-14 |
| 33 | a run passed to a slot naming an implementation compiled against the definition's own slot | 2026-10-14, D25 step 3 |
| 34 | the compile track's raw read of a `where` answered a wrong value (silent) | 2026-10-14, `EffectIntrinsics.undecided` |
| 35 | a slot's `with` bound an ability, not an entry | 2026-10-14, `row/SlotBindings` |
| 36 | a `with`'s arguments untyped where its entry is not spelled (`people.sorted with byKey(…)`) | 2026-10-15: keyed holes, `CheckState.sharedHoles` |
| 37 | two named implementations of one ability mangled to one method (silent, releases ≤ `v0.12`) | 2026-10-15: a binding mangled as its whole identity |
| 38–39 | constrained / parameterised named ability implementations | 2026-10-15; the feature reversed by D29 |
| 40 | a named implementation's type parameter outside its pattern unsolved | 2026-10-15, D27; reversed by D29 |
| 41 | a slot-moved `with` could not read such a parameter | 2026-10-15, D28: refused |
| 42 | a named implementation never checked against its ability (silent) | 2026-10-15, `NamedImplementationCheck` |
| 43 | ride versus supply decided two ways (silent) | 2026-10-15, D30 |
| 44–45 | the threading hid a binding/frame disagreement; a slot implementation's clauses at `Default` | 2026-10-17, D35 step 5 |
| 46–47 | a closed slot kept a binding by ability; over-declaration checked per ability (silent) | 2026-10-15, D30 |
| 48 | the row phase found value-parameter names two ways | 2026-10-17, D35 step 5 |
| 49 | compile-track linking kept one binding per FQN (silent) | 2026-10-16, `ReducedBindingClosure.Linked` |
| 50 | an anonymous implementation's clause performing an undeclared effect failed late | 2026-10-16, `RowElaborationProcessor.anonymousClauseEntries` |
| 51 | `with` accepted on a value parameter and a field | 2026-10-17, D35 step 5: refused |
| 52 | the row write aborted on the runtime track only | 2026-10-16, `RowElaborationProcessor.reported` |
| 53 | "is this ability an effect?" answered three ways | 2026-10-16, `ability.util.AbilityKind` |
| 54 | a not-ground reference's arguments taken from the first same-named constraint | 2026-10-16, proved dead and deleted |
| 55 | `findAbilityMethodImpl` searched named implementations too | 2026-10-16 |
| 56–57 | the environment-entries rule implemented twice; five silent `Default` fallbacks | 2026-10-17, D35 step 5 |
| 58 | a binding's mangled identity hashed with 32 bits | 2026-10-16, `CommonPatterns.stableHash` |
| 62 | runs of one code parameter given different implementations refused | 2026-10-17, D35 step 5a |

## 9. The binding binder's mark (built 2026-09-11/12; deleted by D36)

A binder the desugar mints — for a clause entry, a `~` constraint, or an ability block's binding slot — is declared
with the type `Implementation[A]`, `A` the ability it binds, and every phase that needs to know which binders are
bindings reads that and nothing else (§3.1). It replaced four encodings of one fact. The corrections the build
measured, each a natural reading that is wrong — **do not re-propose the originals**:

- The mark rides the existing `parameterType` slot (`type Implementation[A] = Type`, minted module-qualified), not a
  flag on `FunctionLiteral` and not a typed marker the checker verifies.
- It does not reduce away for the checker (an ability's marker has kind `Type -> Type` or above), so it is **erased at
  the end of `row`** (`BindingWriter.Writer.unmarked`); after `row`, which binders bind an effect is read off each
  binder's `~` constraint (`specialise/EffectBindings`).
- Its argument resolves **as an ability** (`ValueResolver.markedAbility`), so every pool declaring a row, a constraint
  or an ability needs `eliot.lang.Implementation`, test stubs included (`ProcessorTest.implementationStubContent`).
- An undetermined binder before a binding is the **call**'s diagnostic, and in a body it is the checker's hole
  (`Implementation::Inferred`, §8 item 15).
- A row alias mints no binders of its own; it *declares* its row, and is an ordinary name resolved at `resolve`.

## 10. How a change is run

**The gate.** `./mill __.test` green, every example program carrying a `main` compiles, and every example jar
`md5sum`-identical to the pre-change build — `scripts/example-sweep.sh`, whose `jar.md5` lines are the gate.
Byte-identity is a **safety oracle, not a hard gate**: when the output legitimately changes wholesale (D36's switch) it
is replaced by **behavioural identity**, the same script's `exit`/`stdout` lines compared before and after, with the
size and instruction-count difference stated in the commit. A regression there is a finding to explain, not a cost to
accept. Compare reports with their `#` header lines stripped; clear the cache under `target/` between compiles; give
stdin `/dev/null`; never run `./mill` while the sweep runs. Changes reaching eliot-test or eliot-build are run against
their suites under this compiler.

**What the gate cannot see.** No example writes a guarded return type, a `catch` with an ignoring handler, or a stored
computation; a shape with no example has no gate, and the jvm end-to-end suites are what separate it.

**Is this still load-bearing?** Arm-liveness tracing, not inspection: an env-gated `fire(arm, sample)` tracer over the
whole gate *and* a compile of all examples, deleting only zero-fire arms. Switch parts off **separately** — an all-off
run once said "43 failures, delete nothing" where per-part runs said 1 and 36. An examples-only audit under-reports
(elaboration is demand-driven). A firing arm is a question, not a verdict; measure before and after. Tracer gotchas:
mill prefixes forwarded lines with a worker id; a sample key carries the range end and the spine head; delete the
cache before every run.

**Two traps in the test harness.** A `ProcessorTest` needs an `Implementation` stub declaring `type Default` and the
`Implementation[A]` alias, or a snippet calling an ability method loses its monomorphization **silently**. A test stub
that diverges from the real declaration fails silently too.

## 11. Decisions

Every decision is Robert's. This section holds the plan (D36), the open questions, and the record of everything
decided before, one entry each. Conveniences deliberately left out are in §12.

### D36 — effects are capability passing: an effect desugars to an ability over a handle (decided 2026-10-17, revised 2026-10-18, not built)

**The question** (Robert, 2026-10-17, rejecting the rules proposed for §8 item 60): how can the effect system be
simple, run before monomorphization, and desugar into ordinary constructors and abilities? An effect should differ from
an ability in exactly two ways: it is **inherited automatically** along a call chain, and it is **supplied
explicitly**, never found by a search.

**What the mechanism is** (2026-10-18). Nothing in the plumbing is effect-specific: it is **capability passing**,
dependency injection checked statically and scoped lexically. An implementation is a component; its `with` arguments
are its configuration; an implementation whose clauses use other effects (`mockConsole uses State[Recording]`) is
constructor injection; a `uses` entry declares a dependency; the hole rule resolves it by type, nearest binding first;
a `with` provides one for a scope; `main`'s generated `with standard…` is the composition root. The control effects fit
unchanged: `Throw`, `Abort`, `State`, `Writer` and `Dep` are components whose fields are a frame or a cell, and the
exit or the mutation is the platform primitive's. That is what Koka's evidence passing compiles tail-resumptive and
abortive handlers to, and Eliot has no other kind (general resumption is closed, §12). Three things make it more than
a DI container, and they are the user's promise: **handles are second-class** (rules 2 and 3 — a handle may hold a frame
dead after its discharger returns; Effekt's *effects as capabilities*, with second-class blocks, is the nearest prior
art); **propagation is checked and complete** up to `main`, with no other way to reach the world; and **absence means
something** — purity is the absence of `uses`, totality the absence of `Inf`. So the surface keeps the word *effect*,
while design questions are asked in dependency terms: which binding answers a dependency (the nearest in scope, by
type), who builds a component's dependencies (whoever provides it, at the `with`), what a block receives (what its
callee hands it). An ability is searched globally, like a type class; an effect is passed lexically, like a dependency.

**The findings.**

- *Two parties decide one thing.* §8 items 60 and 61, §7 item 2 and the refusals below share one root: which
  implementation answers a use is decided before monomorphization by comparing spellings (D30), while the type
  arguments that decide it are expressions only the checker evaluates. Reproduced on v0.16:

  ```eliot
  def lookup(n: Int) uses Throw[Option[Int]]: String = if(n == 0) raise(none) else "found"
  // refused, "Nothing binds an implementation of the effect 'Throw' here", with ONE Throw in scope

  def both(n: Int) uses Throw[String], Throw[Int]: String = if(n == 0) raise(1) else if(n == 1) raise("one") else "fine"
  def main uses Console: Unit = printLine((both(0) catch (n -> "int " ++ show(n))) catch (s -> "string " ++ s))
  // refused the same way; compiles with catch[Int, String] and catch[String, String] spelled
  ```

  **eliot-build has 36 declarations with two or more `Throw` entries** (`ShellGit.fetch uses Process, Throw[IoError],
  Throw[GitError]`), so an answer chosen by the effect's *name* (Koka's scoped labels) is out: `raise(GitError(…))`
  must reach the `Throw[GitError]` because of its argument's type, and a type is an expression, so the choice is the
  checker's. Hence: **the desugaring before monomorphization does all the plumbing and decides nothing; the one
  decision — which handle answers a use — is the checker's**, after evaluation.
- *Captured handles make a handle's type generic* (2026-10-18). A handle carries three kinds of data: a `with`'s
  arguments (fields typed by the implementation's declaration), an environment a primitive makes (a frame, a cell), and
  a **handle the implementation captures**, one per clause entry its member does not declare:

  ```eliot
  implement mockConsole: Console { def printLine(s: String) uses State[Recording]: Unit = … }
  // ⤳ data MockConsole[S](state: S)            -- S: whatever State handle is in scope at the `with`
  //   implement[S ~ State[Recording]] Console[MockConsole[S]] { … }
  ```

  So a signature cannot spell the handle a discharger supplies: `mocked`'s block receives `MockConsole[S]`, `S` made
  inside two other dischargers' bodies. The same holds for eliot-build's `against` (`Dep[Universe]`) and the example's
  `transcriptOf` (`Writer[String]`) — every discharger in the corpus that is not the platform's. This is why a block's
  handle types are read off its callee (point 6), and why the first version of point 6, which had a supplying slot
  name its implementation (`Throw[E] by escaping`), was dropped.

**Decided** (Robert, 2026-10-17: "Basically effects desugar to abilities with an additional typed parameter"; revised
2026-10-18: "ok, update doc", and on syntax, "We can not introduce 2 new syntax elements, what is, in the developer's
mind a simple DI problem").

1. **An effect is an ability over a handle type.** It gains one type parameter, its handle's type, and every
   operation takes the handle first:

   ```eliot
   effect Console { def printLine(line: String): Unit }
   // ⤳ ability Console[H] { def printLine(handle: H, line: String): Unit }
   ```

2. **A named implementation is a data type and an ordinary instance of that ability.** Its parameters are the data's
   fields (D25); a parameterless one is a field-less `data`:

   ```eliot
   implement scripted(answer: String): Console { def printLine(line: String): Unit = … answer … }
   // ⤳ data Scripted(answer: String)
   //   implement Console[Scripted] { def printLine(handle: Scripted, line: String): Unit = … handle.answer … }
   ```

   Each implementation has a type of its own, so its instance is unique and colocated by construction: coherence holds
   with nothing to check. The **code** is chosen statically by the handle's type, so a polymorphic operation
   (`raise[A]`, `foldLines[B]`) is an ability method as today and no record of operations exists; only the
   **environment** — the fields — is a value. An implementation's clause entry spelled as its member spells one,
   under the implementation's pattern, is the **call's** — a handle parameter of the instance method, filled at each
   operation's call; any other entry is **captured** at the `with`, a field, generic over its handle type. The method's
   own handle parameters are nearer than the fields, so a misread entry is never chosen over the call's and shows as a
   capture no hole takes: loud. (`mockProcess.run`'s `Throw[IoError]` is the call's, its `State[Recording]` and
   `Throw[AssertionError]` captured; item 61's `Writer[List[T]]` at `T := Box` is the call's.)
3. **A `uses` entry is a handle parameter** — one type parameter and one value parameter per entry:

   ```eliot
   def greet(name: String) uses Console: Unit = printLine("hi " ++ name)
   // ⤳ def greet[C ~ Console](console: C, name: String): Unit = printLine(console, "hi " ++ name)
   ```

   Monomorphization clones by the handle's type: D35's specialisation by constant evidence, done by the ordinary key.
4. **A use takes the nearest handle in lexical scope whose entry answers it — the one new rule, and where *inherited
   automatically* lives.** Every handle position is a **hole** the desugar leaves and the checker fills, at four sites:
   an operation call, a call of a `uses` definition, a run of a code parameter, and an implementation's captures at its
   `with`. The handles in scope are lexically bound values, innermost first: a block's parameters, a `with`'s handle,
   the definition's own handle parameters, and what a block captured from where it was written. A handle **answers**
   an entry when the two entries are definitionally equal after evaluation. No answering handle is an error naming what
   is in scope. **Two at the same distance that both answer are an error, with no syntax to choose between them** (no
   qualifier, `@Named`): `def f[E](…) uses Throw[E], Throw[String]: A` at `E := String` is refused at the use, by
   use-site verification, and the fix is a distinct type; two identically spelled entries in one clause are an error at
   the declaration. A candidate whose entry still holds an unsolved meta after the drain (`catch`'s `E` when nothing
   else decided it) answers only if every hole it could answer would solve the meta the same way, and then that solves
   it; otherwise it is the error asking for the type arguments to be spelled (§7 item 2) — decided over all the holes
   at once, so no outcome depends on visiting order. On the cases above:
   - `fetch`'s `raise(GitError(…))` takes its `Throw[GitError]` handle; `raise(none)` under `Throw[Option[Int]]` takes
     the only `Throw` and solves `A := Int`;
   - `raise(1) catch (n -> …)` inside a definition declaring `Throw[Int]` takes `catch`'s block handle, nearer than the
     definition's own, and solves `E := Int`; a `catch[IoError, …]` around `raise(GitError(…))` does not answer, so the
     raise reaches the outer `Throw[GitError]`;
   - item 60's `guarded(raise(1))` on an open slot reaches the caller's `Throw[Int]`, which the block captured; on a
     closed slot it is "no handle here answers `Throw[Int]`; in scope: `Throw[String]`, the slot of `guarded`";
   - item 61's `Writer[List[T]]` at `T := Box` answers `Writer[List[Box]]`; `Throw[Message]` under
     `type Message = String` answers `Throw[String]`;
   - `both(0)` in an unspelled `catch` offers the block's `Throw[?E]` to two holes that would solve it differently: the
     spelling error, saying so.
5. **`with` binds a handle — *supplied explicitly*.** `greet("Bob") with scripted("x")` makes `Scripted("x")` a handle
   in scope for its subject. D23 stays: a `with` whose handle no hole in its subject takes is an error.
6. **A code parameter's listed entries are its block's parameters, typed by what the callee's body hands them.** What a
   block uses through `*` it captures from where it was written, as a closure does (D22's invisibility as plain
   capture); a closed slot is a block that captures no handle. In the callee each listed entry is a type to solve: a
   run hands the block a handle — a hole at the run, filled by point 4 from the callee's scope — and that decides the
   type, as an initializer gives a `val` its type. Every run, and every pass of the code to another callee's slot, must
   agree (one type per slot entry; a disagreement is a type error at the run). A caller reads the solution at the
   callee's key. So `runThrow` hands `obj` an `Escaping[E]`, `if` hands `value` its own received `Abort` handle, and
   `mocked` hands `body` a `MockConsole[…]` no one writes.
   - **Ride versus supply is no longer a concept**: a block gets whatever its callee passes, its own received handle or
     one it made. Nothing is named in a slot and the base is untouched.
   - The block is **not in the callee's key**: the callee is not cloned per block (D31's cost) and the block is not
     copied per variant (D35 step 5a).
   - **The caller's check does not wait for it.** A hole compares *entries*, read from declarations; a handle's *type*
     is needed only to dispatch an operation, which is post-drain. Inside a block the handle's type stays unreduced
     until the drain has solved the callee's type arguments (`catch`'s `E`, from the block's own `raise`), and is then
     read at the callee's key. No recursion means no cycle. In a session with no platform the callee has no body to
     read; its block's handle type stays abstract, as `ThrowFrame` is there, and nothing is dispatched.
   - A callee that never runs or passes a code parameter leaves its type unsolved: "declares but does not perform" on
     the slot.
7. **Rules 2 and 3 are a second-class rule on handles**: a handle, or a closure capturing one, stands only where code
   may — a run, a slot, a `with`'s subject — never in a field, a result or a value lambda; a handle's own fields may
   hold handles, since the holder is itself second-class. Checked before monomorphization by the walk, as today.
8. **The entry point** passes the `standard…` implementations' handles to `main`, one per entry (D26, generated).
9. **Control effects are the same.** A frame is a handle's field — `escapeInternal((frame: ThrowFrame[E]) ->
   obj(Escaping(frame)), …)` — and a cell is `holding`'s; the three primitives stay platform-private. The compile track
   runs handles as values too, and a guarded signature's `raise` takes the handle `runThrow`'s run gives.
10. **No new syntax.** A developer writes `uses` on a definition and on a parameter, `with`, `effect` and `implement
    name(params): E`, as today; holes, handle parameters, handle types and which handle answers a use are the
    compiler's. No slot names an implementation; the one disambiguation a program can need is ordinary type arguments
    (`catch[IoError, Unit]`).
11. **Erasure follows the switch.** The switch ships with handles as data (about 12% more jar on the spike); the gate
    is behaviour (§10). Erasure is then an optimization of its own: a field-less handle is no parameter, argument,
    allocation or capture; a single-field handle is passed as its field, recursively.

**The scope check splits.** Before monomorphization, by effect name: "performs the effect 'X' but does not declare it"
when no handle of `X` is in scope at all, and "declares … but does not perform it" when no use names `X`. Exactly, by
entry, in the checker: point 4's errors and a handle parameter no hole takes. Still one verifier (D7).

**It reverses** — confirmed by Robert, 2026-10-17 ("I accept"): D35's mechanism (evidence made after
monomorphization, the specialisation pass, the threading), since evidence is an ordinary value before it and
specialisation is the key's; the choice among handles made in the checker, by type; and D35 step 5a.

**To sign off** — readings the conversation did not name; Part I is not rewritten until they are:

1. **D30**, entries compared as spelled: replaced by comparison after evaluation, in the checker.
2. **§12, "a handler as a marker type"**: point 2's data type is a marker type per handler and point 3's type parameter
   a binder in a parameter type. None of what the entry guarded against comes back; the entry needs restating.
3. **§12, "carrier inference", and §5 rule 3**: a hole is filled by lexical lookup by type, with no meta of its own, no
   join and no lattice; a block's handle type is solved by equality at the callee's runs, deciding no binding. Read as
   not inference of a binding.
4. **§12, "a runtime handler stack"**: the search is the checker's, over lexical handles, at compile time.
5. **§3.8 and §8 item 59**: the checker holds exactly one effect rule, the hole.
6. **D35's settled point** that every `with` is lowered to the `$with` binder call: the binder goes.
7. **§12, "a slot's implementation solved from the callee's body"** (D28): its reason — a caller must not depend on a
   callee's body — was already given up by D35 (a block's evidence is read off its callee's body) and point 6 reads a
   block's handle types the same way. Read as restated: what stays closed is a type parameter outside an
   implementation's pattern (D29).

**It keeps** rules 1–6, D22 (as capture), D23, D24, D25 (as fields), D26, D28's clause half, D29, the three primitives
and their frames, `CarrierKindChecker`, and the surface: no program in the corpus changes.

**It deletes**, when built: `row/BindingWriter`'s resolution order and write (keeping its second-class check and the
thunk-and-apply), the `Implementation[A]` mark (§9), `Default` as an effect binding, the `$with`/`$parameters` binders
(`ast/fact/ImplementationParameters`) and `NamedImplementationArguments`, `monomorphize/specialise/`,
`channel/EnvironmentThreading`, `channel/EffectDeclarations`, `MonomorphicEffects` with its variants,
`channel/SuppliedRowArgumentsProcessor`, `DeclaredEntries.forwarded`, `PostDrainQuoter`'s `threaded`/`reachesFrames`/
`forwarded`, and the effect half of `AbilityResolver`. It closes §8 items 59, 60 and 61 and the `raise(none)` refusal.

**Step 1, the spike — done 2026-10-17.** One program written twice on v0.16 — today's surface, and desugared by hand
with every handle passed where the hole rule would put it: an interpretation effect with a parameterless and a
parameterised implementation, a `*` lambda capturing a handle, `fetch` with two `Throw` handles, `guarded` catching one,
`described` catching inside itself what `guarded` passes on, and `mocked`'s shape. Its `Throw` was an ability
`Fail[H, E]` over `data Escape[E](frame: ThrowFrame[E])`, in a scratch file of module `eliot.effect.Throw` so its private
`escapeInternal`/`exitInternal` copies merge with the jvm layer's (a real frame). Findings:

- it compiles unchanged and prints the same eight lines; a block whose parameter is a handle is typed with no
  annotation; a mismatched `fail` is refused by the ordinary resolver (*"No ability implementation found for ability
  'Fail' with type arguments [Escape[Type], String -> Text]"*);
- monomorphization by handle type reproduces the specialisation pass's clones one for one, every operation an
  `invokestatic` to the one implementation;
- the jar is 11.8% larger before erasure (62 935 bytes against 56 300; 113 classes against 98), all of it the handles'
  data representation — hence point 11;
- the compile track runs handle code (`def chosen[B: Bool]: Type = catchFail(…)` types `chosen[true]` as `Number`);
- not covered: the hole rule (by design), a guarded signature's rejection read, cells, the second-class check, and a
  constrained instance over a captured handle (`implement[S ~ State[Recording]] Console[MockConsole[S]]`).

**Implementable now** (assessed against the tree, 2026-10-18). No open decision blocks the start. What the tree already
has: dispatch that waits for a type (an ability reference with unsolved arguments stays pending through the post-drain
fixpoint, `TypeStackLoop.resolveAbilitiesToFixedPoint`); a lambda checked against a `VPi` takes its domain as its
parameter's type, solved or not (`Checker.check`); keyed holes the checker turns into one shared metavariable
(`Checker.explicitTypeArgument`, `CheckState.sharedHoles`); declarations rewritten in `core` (`EffectSugarDesugarer`)
and uses in `row` (`BindingWriter`). What is new:

1. **The declaration desugar, in `core`**: points 1–3, with captured entries as generic fields and the instance
   constrained on them.
2. **The use desugar, in `row`**, replacing `BindingWriter`'s resolution order and write: a hole at every handle
   position carrying its lexical candidates nearest first, grouped by binder, each with its entry; a `with` to a bound
   handle; a run handing its block one handle per listed entry; `EntryPoint` constructing the `standard…` handles.
3. **The hole rule**, a pass in the same post-drain fixpoint beside ability resolution. A candidate is tried by unifying
   entries and discarding the state when it fails (the check state is an immutable state monad).
4. **A block's handle types read off its callee — the one piece with no precedent.** In the callee's own check the
   slot's handle type is a metavariable its runs solve, quoted into its `MonomorphicValue`; in the caller it waits until
   the callee's type arguments are ground, then is read from the callee's checked value, in the same fixpoint. Two
   frictions: the **signature twin** (`flippedCalleeSignature` reads a callee's ground signature from a mono that checks
   no body, so the in-place evaluation must leave the slot's type a metavariable), and **`defaultUnsolvedMetas`**,
   which solves every leftover metavariable to `Type` and must exclude a handle type.
5. **Guarded signatures and the compile track**, which the spike did not reach (`GuardDischargeResolver`,
   `TypeStackLoop.guardRead`, `EffectIntrinsics`) — the most likely place for a surprise.
6. **The deletions** above — several thousand lines (`EnvironmentThreading` about 800, `EvidenceSpecialisation` about
   650, most of `BindingWriter`'s 1,500) — the 68 test files in `lang` that exercise effects, Part I, and the
   dependents.

**Order.**

1. ~~The spike~~ — done 2026-10-17.
2. **The new path beside the old**, behind an internal switch (a system property, not a CLI word — the CLI is a
   compatibility promise), so `master` stays green: **2a**, items 3 and 4, as `ProcessorTest`s on the spike's program
   with its handle arguments replaced by holes; **2b**, items 1, 2 and 5, measured on the corpus under the switch —
   every hole resolves, the agreement rule accepts every unspelled discharger, every block's handle types are read,
   and eliot-test, eliot-build and the examples behave as today, every jar difference explained.

   **Progress (2026-10-18).** 2a and 2b were built together, on the real surface, rather than 2a first on a spelling
   of holes: a test-only spelling of a hole would have been thrown away, and the surface tests survive the switch. The
   switch is `LangProcessors(handles = …)`, defaulting to `-Deliot.effects.handles=true` or
   `ELIOT_EFFECTS_HANDLES=true` (`LangPlugin.handlesRequested`). Under it **all 46 runnable examples build and print
   exactly what the old path prints**, `lang`'s suite differs in 4 of 1038 tests (old-path shapes), and `jvm`'s in
   about 45 of 655; `./mill __.test` on the old path is green. Built: points 1–8 (`row/HandlesTest`), the hole rule
   (`check/HandleHoleResolver`), a block's handle types (`check/HandedBinders`), captures (`row/Captures`). Choices
   made in the code, for sign-off with the readings above:
   - a block's handle type is a **handed binder** (marked `Implementation::Handed`, after every other binder); the
     callee's check at the rest of its key solves it, and a caller reads `CheckedValue.handed` /
     `CompilerMonomorphicValue.handed` there;
   - **captures**: which entries a clause captures needs its member, in another file, so the core desugar gives the
     handle a field per entry any clause writes and the `row` phase decides; a field nothing captures holds `unit`
     until erasure;
   - only a layer that **bodies** a named implementation mints its handle `data`;
   - the `row` phase no longer reads a slot's type arguments off the actual's declared row (A6) on this path;
   - a guarded signature's uncovered `Throw` is read as `runThrow(…)`, the `Either` the guard read already discharges.

   **2b, measured (2026-10-18).** Under the switch **all 46 runnable examples print what the old path prints**,
   **eliot-test's suite and eliot-build's print exactly what they print on the old path** (193 and 368 cases), the
   latter after one declaration is changed (below), and `./mill __.test` on the old path stays green. `lang` differs in 2
   of 1039 tests and `jvm` in 16 of 655, every one an old-path shape, a D36 reversal or the same rejection worded by
   the hole rule:
   - D35 step 5a (5 tests): runs of one code parameter handed different implementations are now the error point 6
     names — "This run of the code is handed another implementation than another run of it";
   - D30 (3) and D35's "a nearer binding stands between them" (1): entries compare after evaluation, and a capture is
     lexical, so these compile or are refused by the hole rule;
   - `SuppliedRowArgumentsProcessor` (1), which this decision deletes;
   - 6 rejections the hole rule words itself ("No handle in scope here answers 'Source[Int]'. In scope: …", "declares
     the effect 'Throw[String]'", with its arguments).

   Fixed for it, each a choice for sign-off with the readings above:
   - a guarded signature reads its callees' handed types and fills its holes before the read-back; an uncovered
     `abort` is read as `… else raise("A type guard rejected this use.")` inside `runThrow`, so both control effects give
     the guard read one verdict shape, with the old path's message;
   - an anonymous `implement` block's marker carries no clause row on this path, and conformance compares member and
     clause without the handles of other effects;
   - **a named implementation's type parameter its handle's value does not fix** — one no parameter type and no clause
     entry mentions — is left out of the handle `data` and keyed afresh at each hole the `with` is offered to, so one
     `with showing` still serves `Source[Int]` and `Source[String]` (§8 item 30); one a field fixes is one per handle;
   - D29's refusal of an implementation type parameter its pattern does not take is made in `core` on this path, since
     nothing demands the named marker's `row` phase;
   - a clause's binders mirror its member's after its instance's, its captured handles' last, and a `~` constraint's
     binder is minted before the row's on this path, so dispatch's positional type arguments land where they belong;
   - a capture with no handle of its effect in scope is the by-name "performs … but does not declare it".

   **For Robert — a corpus change.** eliot-build's `cachedDescriptorTextAt` is typed `Offline[Option[String]]`, a row
   alias including `Throw[GitError]`, and nothing in its body performs `Throw[GitError]`. The old path accepted it
   because its unspelled `raise(NotFetched(…))` consumed every `Throw` binding by name; the hole rule checks by entry and
   refuses it ("declares the effect 'Throw[GitError]' but does not perform it"). Either eliot-build spells that one
   declaration's clause without the alias (what the measurement above did), or an entry that arrives through a row alias
   is exempt from the exact half of the check. Not decided.

   **The jar difference** is all the handles' data representation, which point 11 defers to erasure: over the 46
   examples, +22.2% bytes (693 312 ⤳ 847 542), +22.8% classes (1 217 ⤳ 1 494) and +20.6% instructions — per handle a
   `data` class, its `PatternMatch` instance and accessor lambdas, and per `with` the lambda binding its handle. The
   smallest programs grow most (HelloWorld +50%: `StandardConsole$Handle` and `main`'s binding lambda).

3. **The switch and the deletions together**: the default flips, the switch and D35's machinery go, Part I is rewritten
   in capability terms, §12's restated entries and the cornerstone in `CLAUDE.md`; then a release.
4. **Erasure**, measured against today's jar.

### Open questions

- **D20a — naming a set of effects.** `type Git[A] = {Process, FileSystem, Throw[IoError], Throw[GitError]} A` is a row
  alias, return position only (§2.4). The natural replacement is a name used in a clause, `uses Git` on a definition
  and `uses *, Git` on a parameter — declaration metadata, so it need not cost an `ast.fact.Expression` case; its
  declaration's spelling is undecided. Until then a row alias's body is the one place braces parse.
- **D20c — storing an effectful computation whose effects are handled where it runs** (hooks at process end, a job
  queue, an event loop's table). No spelling today: code cannot be kept (rule 3) and a value lambda may use no effects
  (rule 2), so what is left is a hand-written `data` mirroring the algebra and an interpreter (§2.3). The closed kind
  (effects bound where written) is unsound for a control effect; the open kind (bound by whoever runs it) is the
  question. Options weighed: (1) the binding in the container's type — needs a region binder per discharger call;
  (2) the implementation passed when the block runs — the step-6 limit (§12); (3) the compiler reifies the block into a
  free monad — needs continuation capture and recursive data, though a partial form works today (an implementation that
  `tell`s each operation as data); (4) an open field as a finite product selected at the read, `data Hook(run uses
  Cleanup: Unit)`, the body lifted to a named definition and specialised at the read — it reverses rule 6 and narrows
  rule 2, needs `StrictPositivityChecker` to count a field's entries as negative (Landin's knot returns otherwise), and
  makes the data's layout depend on every implementation. Under D36 a stored open computation is a closure awaiting a
  handle, which rules 2, 3 and 6 still refuse. Until decided: option 3's partial form, or a hand-written interpreter.
- **D3 — `~` and `&` fully in user space.** Blocked on meaning: whether `~` and `where` unify, then the phase-order
  blocker (the superability closure runs at resolve, before operators are structured). An ability denotes no type of
  values, since an implementation's code is not a value.
- **The four choices made in the code while building option 5** (§3.4, "Decided in the code") await sign-off; under D36
  they go with the threading.

### Closed decisions

One entry each, newest first; the full record of each is at `14b61527` or earlier (§13).

- **D35** (decided 2026-10-16, built 2026-10-17; its mechanism reversed by D36, not yet built): **an implementation is
  evidence passed after monomorphization** — code and environment one hidden argument per entry, installed by a `with`
  (which binds what escapes its subject), captured by a block for what it uses through `*`, given to a block by its
  callee for what its slot lists; static code a **specialisation pass** cloning by constant evidence, on every target by
  default; every effect bound the same way (D33 undone). Chosen over a free monad, whose costs are kept in §12. Settled
  with it: the slot `with` spelling goes; every `with` is lowered to the `$with` binder call; a block's evidence is read
  off its callee's specialised body (`MonomorphicEffects.Slot`). Readings of §12 agreed: "an implementation as a
  runtime value" stays closed for the checker; "a runtime free monad" stays closed; "a sum over a monomorphized
  sub-graph" decides code generated, never a binding. **Step 5a** (decided and built 2026-10-17): runs of one parameter
  given different implementations get a block copy per variant (`Slot.variants`), not evidence as a run-time value with
  indirect calls, which was declined as a second code shape for every target. Built in six steps plus 5a; measured on
  eliot-build's suite the pass costs 0.65 s of a 45 s build.
- **D34** (proposed 2026-10-16, withdrawn the same day for D35): a binding on the definition's declaration
  (`def releaseTests with tableGit: Test`), Dagger-style, with no `with` expression. Its measurement of `Mock.els`
  (one definition changes; seven suite lists; eliot-build's 96 call-site `with`s would move to declarations) and why not
  `Dep[MyOutput]` (a value may perform no effect, so a record of operations is refused where it is built) are at
  `14b61527`.
- **D33** (decided 2026-10-16, reversed by D35 the same day, never built): a `with` only on a call of a named value;
  control effects take only a frame; a code parameter lists only control effects. Its measurement: exactly three code
  parameters list an interpretation effect (`mocked`, `against`, `transcriptOf`).
- **D32** (superseded by D33, 2026-10-16): one walk decides both halves of an implementation.
- **D31** (withdrawn 2026-10-16): code compiled where it runs, a definition specialised per code argument. Measured:
  24 definitions with a listing slot and **1,271** call sites (62% eliot-test's `in` and `mocked`), about half again
  eliot-test's jar if every block got its own copy. Finding kept: what a callee gives at a run depends on its body and
  type arguments, never on the block — which is why D36 reads a block's handle types off the callee, not per block.
- **The 2026-10-15 diagnosis**: the open items traced to four structural choices, each deciding one thing in two
  places — **A** code compiled by its caller while its listed effects are the callee's; **B** an implementation's two
  halves decided by two walks at two times; **C** an entry's identity holding type arguments not known where it is
  compared; **D** abilities run on the effect machinery. D29 removed D, D35 removed A and B, D36 removes C.
- **D30** (2026-10-15, built): a slot entry rides or is supplied, and an entry answers another, as the declarations
  spell them — one comparison everywhere (closed §8 43, 46, 47). Replaced by D36's comparison after evaluation, to sign
  off.
- **D29** (2026-10-15, built): an ability is never bound with `with` and has no named implementation; behaviour per
  call is a value parameter (`sortedBy`); every type parameter of an implementation is its pattern's. Reversed the
  parameterised ability implementations and D27.
- **D28** (2026-10-15, built): a `uses` clause lists effects only (`ValueResolver.requireEffect`). Its effect half (a
  slot binding no implementation whose parameter only a `with` decides) went with the slot `with` in D35.
- **D27** (2026-10-15, built; reversed by D29 the same day): a named implementation's type parameter outside its pattern
  read off its `with`'s arguments.
- **Parameterised ability implementations** (built 2026-10-15; reversed by D29 the same day).
- **D26** (2026-10-14, built): no effect has a default; every effect implementation is named; the platform runs `main`
  with a generated `with` per entry naming the base's body-less `standard…` implementations (`row/EntryPoint`); a
  guarded signature is passed explicitly too; an entry an implementation's clause only repeats from its member is the
  call's. (D33's reversal of it for control effects was itself reversed by D35.)
- **D25** (2026-10-14, built): parameters only on a named implementation (`implement [name[(params)]:] Pattern`); a
  control effect's implementation is named (`escaping`, `aborting`, `holding`, `writing`, `providing`) and its platform
  discharger binds it with an ordinary `with` over its primitive's frame. Its rule "a run passed to a slot is bound as
  that slot binds" and the carrying (step 3) went with D35. Rejected: `with Ability(args)` for a default; the platform's
  implementation's name on every pass-through slot.
- **D24** (2026-10-13, built): `uses *` on a definition, written exactly when some parameter's clause has `*` — a
  marker, checked by the parser both ways and dropped. Optional was rejected; Koka's named effect variable stays
  closed.
- **D23** (2026-10-13, built): a `with` binds every effect of its ability that **escapes its subject**; one nothing
  escapes to is an error. Its slot half (a `with` around a run moved onto the slot) went with D35.
- **D22** (2026-10-13): a listed effect is the callee's to handle or to declare; one the code uses through `*` unlisted
  is invisible to it. Rejected: always the caller's frame (makes `describedAs` unwritable) and a handler marker.
- **Compile-track frames** (2026-10-12, built 2026-10-13): the compile track threads frames as the runtime track does,
  and the guarded-return read gives a signature its control effects' frames.
- **Named implementations with parameters** (2026-10-12, built 2026-10-13): supplied at the `with`, `greet with
  scripted("Bob")`; type parameters on them since 2026-10-14.
- **D20c option 5 and (b)** (2026-10-12, built): an implementation's environment threaded after monomorphization; (b)
  "ride by code, give by frame" retired by D35. D20c's open question remains, above.
- **D21** (2026-10-09, built with D20): a function parameter is a value, code is marked `uses *`, a clause without `*`
  is closed, purity is the absence of `uses`.
- **D20** (2026-10-05, built 2026-10-09/10): effects are parameters — the `uses` clause, code used and never kept, a
  field holds a value. **D20b** (dual parse or flag day): both spellings parsed until the migration ended.
- **D19** (an undischarged control effect at `main` is a compile error): closed 2026-10-12 by option 5.
- **D18** (a row alias at a parameter): superseded by D20; what replaces it is D20a.
- **D17** (2026-09-12): a `val` is a bind (§3.5).
- **D13** (eliot-test cases built with their handlers applied, via `with` on a discharge word's slot): moot — the slot
  `with` is gone since D35, and `mocked` installs its doubles with a `with` around its run.
- **D4** dissolved at the flag day; **D5** is rule 2; **D7** (the post-mono verifier) retired by measurement 2026-09-10.

---

# Part III — What is closed, and provenance

## 12. Closed by measurement or decision — do not re-propose

Entries marked *(D36)* are readings D36 asks to restate; they stand until signed off.

- **Carrier inference** — a carrier metavariable, a join solver, an `Id`-headed judgment, a mode obligation, a
  post-drain mode resolver. Of 15 fix commits in the v2 window, the four highest-impact were one failure: a carrier
  metavariable captured by first-contact unification. Restated (§5 rule 3): a binding is filled by `with`,
  declaration, slot or default, never joined. *(D36: a hole's lexical lookup by type and a block's handle type solved
  by equality are read as not inference of a binding.)*
- **A second, post-monomorphization effect verifier.** Retired 2026-09-10 by measurement (D7): a monomorphic body says
  strictly less about effects than the declaration walk did.
- **The carrier as the injection point.** Every one of v5's testing limitations was a symptom. Superseded by §6.
- **An implementation as a runtime value (a record whose fields are the operations).** Measured 2026-09-07: the checker
  cannot represent a Π-typed field (polymorphism lives on named signatures, `Checker.appendTypeArgs`); four operations
  return their own type parameter (`foldLines[B]`, `foldCodePoints[B]`, `handleCases[R]`, `typeMatch[R]`); an ability
  may declare an associated type; a nullary operation as a field is strict. D35's evidence exists only after the
  checker, and D36's handle holds the environment only — the code is chosen by the handle's type.
- **A handler as a marker *type* in the type language** — a marker type per handler, a binder in types that is
  unified, "two handlers meeting is a mismatch", one environment per stored computation, a handler result function, a
  post-mono lowering pass with `handler`/`returning`/`finish`/`resume`/`return`. What is not closed is a phantom binder
  per clause entry as the mono-key component (§3.1). *(D36: its data type per implementation and its handle type
  parameter are the first two in letter; to restate.)*
- **Transitive `with` / summing abilities over a monomorphized sub-graph.** Dynamic scoping resolved at compile time.
  Forwarding is by declaration only.
- **A default with parameters** (`with Ability(args)`). Reversed by D25: a default is what is inserted where nothing is
  written, so it has no arguments; an implementation with parameters is named.
- **Syntax for wiring: naming the implementation a slot supplies, or a qualifier choosing between handles** (2026-10-18,
  D36 point 10). A slot's handle types are its callee's body's (`Throw[E] by escaping`, `escaping[E]` in place of the
  entry, `Throw[E] with escaping` all go), and two equally near handles that both answer are an error whose fix is a
  distinct type, never a name for a handle (`@Named`). Effects are a dependency-injection problem in the developer's
  mind, and `uses` and `with` are its whole surface.
- **A bindings field on `ValueReference`, a scoping node, or a consulted-set component on the mono key** as the carrier
  of a binding (2026-09-08): the key already carries one argument per binder.
- **A runtime handler stack searched by type.** Kills erasure and makes accidental handling the default; a frame
  reaches a definition only as a value passed to it. *(D36: its search is the checker's, over lexical handles, at
  compile time — read as not this entry.)*
- **"No call site is ever dynamically nested inside itself"** (to give frames a compile-time identity). Refuted
  2026-10-12: `catch`'s one `runThrow` installs two live frames when a block under it calls `catch` again.
- **Full unification — every ability passed, none searched.** Puts `{Eq[Int], Combine[String], …}` on most code, or
  infers rows; the compile track dispatches `Meta`/`Numeric`/`PatternMatch`/`TypeMatch` with no call chain.
- **A public cell or escape in the base.** A public cell is Landin's knot; the primitives are platform-private and the
  dischargers abstract in the base.
- **A runtime free monad as the effect representation.** A heap tree walked by an interpreter, a coproduct with
  injection (the row back in the type), no spelling of scoped operations (`forever`); and, measured for D35: row
  unification or subsumption, untypable user handlers (existential continuations, GADT matching), an interpreter
  folding recursive data, a tree node per callback element, a helper's `catch` catching its caller's `raise` again.
- **A `World` token.** Not needed in a strict core; purity is decided by evaluator stuckness.
- **A rule on twin-less natives, or a purity annotation for them** (withdrawn 2026-09-08). A twin proves reduction, not
  purity; a side effect is an `effect` operation, and a native leaf's declaration is axiomatic.
- **An anonymous implementation-literal expression** (`Console(printLine = …)`). A literal is a value.
- **Deleting `CarrierKindChecker`.** It is the only thing rejecting a `[F[_]]` binder at a proper type; a kind system.
- **Putting the row on `VPi`** (Koka-style), and **effects on function types** (`A => B uses Console`), which is the
  same and brings back the closed stored computation.
- **A row alias with its own AST node.** The row alias rides the `EffectfulType` node.
- **Discharge markers (`{-E}`).** Discharge is a frame a discharger installs.
- **Scanning the dictionary for an ability or implementation name.** A name resolves at `resolve` to a `ValueFQN` by
  keyed lookup, and only that FQN flows onward.
- **A third kind of parameter for a function the callee keeps** (`val f: B => C`). A kept function is a pure value.
- **Code parameters as nested `def` signatures**; **`=> A` for a lazy argument**; **a `block`/`impure` keyword**;
  **`A -> B` pure and `A => B` effectful.** `value uses *: A` says it.
- **The `<Ability>Carrier` convention.** Its premise, that an effect has a representation, is gone.
- **A named implementation of an ability, and `with` for one** (D29). Behaviour chosen per call is a value parameter.
- **A slot's implementation solved from the callee's body** (D28, 2026-10-15). *(D36, reading 7: its reason was given
  up by D35, and D36 reads a block's handle types off the callee; what stays closed is a type parameter outside an
  implementation's pattern, D29.)*

**Not now — conveniences deliberately left out.** Each is additive later and none touches the mechanism:

- **A name for several implementations** (`implement mocks = mockConsole & mockFileSystem`), and **a set of abilities
  required of a binder** (`ability Web[F[_] ~ Console & Log]`).
- **`with` on a def's own clause** — a second spelling of `with` around the body.
- **A clause spelling for a ground ability default** — refused since D28.
- **A user-declared boundary default** (`implement Throw[ConfigError]` at `main`).
- **Compile-time reduction of effectful code under a pure implementation.** Wanted by nothing.
- **B — a derived *keeps* property** (a relaxation of rule 3): which function parameters a definition keeps, bottom-up
  over the acyclic reference graph, with *frame-free capture* admitting a kept lambda whose bindings reach no frame.
  Wanted by nothing until a native or a library genuinely keeps callbacks.
- **Derived mocks** (`with mock[Database]`). A feature in its own right, and a consumer for D3.

---

## 13. Provenance, and how to read a citation

**This document's own history.** It was compacted on 2026-10-18; **the full text of every record before that is
`git show 14b61527:docs/effects.md`**, and older condensations are at `f78861cf` (D17–D21's records) and `cb2a9c6d` (the
v6 plan). Citations in source comments resolve as follows:

| citation | now |
| --- | --- |
| D20/D21 rules | §1's six rules (D20 rule 2 and D21 rules 1–2: rule 2; D20 rule 3, D21 rule 3: rule 3; D21 rule 4: rule 4; D20 rules 5, 6: rules 5, 6) |
| D20/D21 steps 1–7 | §3.1, §2.3, §2, Part I |
| D20c option 5, "the `transcriptOf` spike", "Option 5 built" | §3.4 |
| D20c (b) | retired by D35 (§11) |
| D22–D30, D31–D35 and their steps (D35 step 5a included) | §11's closed decisions, and Part I where built (D35: §3.4, §3.1, §2's `with`) |
| D36 and its points, steps and readings | §11, D36 |
| §8 items, by number | §8 (open, or the closed table) |
| §8 items 14–17 on the option-5 line | §8 items 16–19 |
| §7 item 7 before 2026-10-13 | closed, §8 item 5 |
| §1's former four rules (before 2026-10-10) | rule 1; rule 2; reversed by rule 6; rules 2 and 3 |
| "cause A/B/C/D" | §11, the 2026-10-15 diagnosis |

**Retired documents.** Ten documents were merged into this one and deleted (`git log --diff-filter=D -- docs/`).
**Scaladoc comments cite them by their own anchors** — historical pointers, not references into this document:

| retired document | what it was | where its live content is |
| --- | --- | --- |
| `effects-as-channel.md` (v2) | the carrier as a type argument the checker solves | the channel (§3.3); its mistake is §12's first entry |
| `effects-as-rows.md` (v3, `A.x`, `R1`–`R6`) | the elaborator writes the carrier | user model §1–§2, standing rules §5, gate method §10 |
| `effect-row-tails.md` | pinned rows | none: no stored computation (§2.3) |
| `testing-effects.md` (`L1`–`L3`) | substituting implementations | §6 |
| `effects-v5-one-carrier.md` | rows as constraints on one carrier | §2.1, §2.2; the rest §12 |
| `effects-as-channel-v4.md` (`R1`–`R11`, `P0`–`P5`) | the row leaves the type | Part I; §3.1 |
| `effects-v4-p0-spike.md` (`S1`–`S3`) | the `WovenValue` seam | `EffectsSeamGroundnessTest` |
| `effects-v4-p2-sizing.md`, `effects-v4-flag-day-readiness.md` (`B1`–`B3`) | the flag day | history; §12 |
| `effects-syntax-userspace.md` (stages 1–4) | `~` and `&` as values | §2.5, D3 |

Older citations: *D1*/*B1*–*B4* (the v4 decision, now Part I and §12), *D2* (the `<Ability>Carrier` declaration, §12),
*W1*–*W4* (v5 work items, history), *D8*/*D9*/*D10* (§2, §3.4, §12), *D14*/*D15* (§2, §3.1), *D6*, *D11*, *D12*, *D16*
(§12's "not now"); `docs/effect-lift-in-checker.md` and `docs/effectful-signatures.md` are retired. Comments dated
2026-09-07 to 2026-09-10 cite the v6 plan's anchors (§8, §9.1–§9.9, §10.1–§10.3, F1–F9, A2–A11) at `cb2a9c6d`: §9.2 is
now §2, §3.1, §3.5; §9.3 is §2; §9.4 is §3.1 and §4; §9.5 is §3.5 and §4; §9.6 is §3.4; §9.7 is §3.5 and §12; A5 is
§3.3, A6 §2.2, A8 and A9 §7, A2 (a microcontroller's exit primitive, still hypothetical) §3.4's table. Comments dated 2026-09-11 and later that cite §9 mean the binder mark.
