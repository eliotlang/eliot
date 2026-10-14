# Effects in Eliot — the design, and what is left to do

**Status (2026-10-14).** This is the effect system's single document. What the tree builds is effects v6 (2026-09-09)
in D20/D21's `uses` surface (2026-10-09/10) with D20c option 5's environments and (b) (2026-10-12), and D22's reading
of a listed effect, D23's reading of `with`, D24's `uses *` on a definition, compile-track frames and named
implementations with parameters (2026-10-13), their type parameters solved where they run, their `with` around a
run of a code parameter, and a run bound as the slot it is passed to binds (2026-10-14). An effect is
an ability declared with the `effect` keyword. A definition's `uses` clause says what its caller hands it. An implementation has two halves: its **code**, a
compile-time name written at every reference by a syntax-directed pass that reads declarations only, and its
**environment** (an escape frame, a cell, its own parameters), a value threaded after monomorphization — on the
compile track as on the runtime one — and never searched for across calls. There is no carrier, no monad, no `Id`, and nothing to infer. Effects verify as a
**channel** beside the type, the same architectural move as the `Int` refinement channel, and the NbE checker holds no
effect rule at all.

**How to read it.** Part I is the design as built and is the authority for the tree: where code, a stdlib signature,
an example or a test disagrees with it, the disagreement is either a defect in the artefact or an entry in §8, never
silently tolerated. Part II is what is left: the divergences (§8), the record of the binder mark (§9), the method every
change is run under (§10) and the decisions still open or decided-but-unbuilt (§11). Part III holds what is **closed by
measurement or decision and must not be re-proposed** (§12) and the provenance a source comment needs (§13). A
decision changes Part I when it is *built*; until then it lives in §11. How the design was reached — D17–D21's records,
the option debate of D20c, the work-list logs — is in git history, and §13 maps every citation to where its content
lives now.

**Next, in order** (2026-10-14). The open items below say *what* is open; this says what to do first.

1. ~~**§8 item 14**~~ — closed 2026-10-13: an unapplied reference to a `uses` definition in a value position is the
   rule-2 error.
2. ~~**Make eliot-build's suite compile again.**~~ — done 2026-10-13: the compiler's (§8 item 22). Its pushed suite
   passes (368) under this compiler with eliot-test's pushed master, which passes too (183).
3. ~~**Release the next `v0` tag**~~ — done 2026-10-13: `v0.9` is the first carrying `uses`, and `v0.10` (§8 item 23)
   the first eliot-test's merged `whenFailing` compiles on. eliot-test `v0.4`, eliot-build and the docs site's install
   page depend on `v0.10`.
4. ~~**The loud usability gaps**~~ — §8 items 21 and 15 closed 2026-10-13 (15 by option (a), the checker's hole).
   Building 15 found §8 items 24 (silent) and 25 (a runtime crash), both closed the same day.
5. ~~**Compile-track frames**~~ — built 2026-10-13 (§3.4, "The compile track is threaded too"). It closed §7 item 4
   and §8 items 26 and 28; measuring first found and closed §8 item 27. The dependents were run against it: eliot-test
   (193) and eliot-build (368) pass unchanged and eliot-build's launcher builds, so no dependent needs a change.
6. ~~**Release the next `v0` tag**~~ — done 2026-10-13: `v0.12` carries step 5's two commits and the two non-effect
   fixes below. eliot-test (193) and eliot-build (368, and its launcher) were run against it; no dependent moved.
7. **Named implementations with parameters** — built 2026-10-13 (§2, §3.4): `greet with scripted("Bob")`. One with
   **type** parameters as well was refused where it was bound until D25's step 1 solved them (§8 item 30). Every
   example jar is byte-identical, and eliot-test (193) and eliot-build (368, and its launcher) pass unchanged.
8. **D25** (decided 2026-10-14, §11): parameters only on a named implementation, and the platform's dischargers bind
   theirs with an ordinary `with`. Its steps, in order: ~~§8 item 30~~ (closed 2026-10-14); ~~a `with name(args)`
   around a run of a code parameter~~ (built 2026-10-14, closing §8 item 32 on the way); ~~a slot's binding carried
   to the slot a run is passed from~~ (built 2026-10-14, closing §8 item 33); the control effects
   moved to named implementations; the anonymous parameter list and the giving primitives deleted. User dischargers
   such as `withConfig` then need nothing of their own, and parameterised ability implementations follow by the same
   mechanism, after §8 item 31.

D20a, D20c and D3 stay open until decided; none blocks the steps above.

**Open, in order of severity.**

- **Silent:** none known (§8 items 14, 24 and 26 closed 2026-10-13, item 33 on 2026-10-14).
- **Loud, at compile time:** a
  `catch` over a parameter spells its arguments (§7 item 2); a function-typed code parameter's own entries are never
  supplied (§7 item 1); `runThrow("no failure")` with its argument spelled is accepted (§8 item 4); a `with`'s
  argument typed only by the entry it binds, an unannotated lambda, is refused (§8 item 31).
- **Decided, not built:** D25, parameters only on a named implementation and the platform's dischargers binding
  theirs with `with` (steps 1–3 built 2026-10-14: a named implementation's type parameters solved, its `with`
  around a run of a code parameter, and a run bound as the slot it is passed to binds), then parameterised
  ability implementations (§11, "Decided, not built").
- **Undecided:** naming a set of effects in a clause (D20a), storing an effectful computation (D20c), `~`/`&` in user
  space (D3).
- **Outside this document:** `v0.9` is the first release carrying `uses` (`v0.8` is tagged before it, and its release
  workflow failed, so it publishes no assets); `v0.12` is the latest; eliot-test `v0.5`, eliot-build and the docs site's
  install page depend on `v0.11`, which they need not leave. The site's generated `apidoc/` pages predate effects v6;
  eliot-test's own notes still say a row cannot be closed and list `runThrow` spellings that are now redundant. Two defects found while
  building step 5, neither an effect one, were fixed on 2026-10-13: the jvm backend named a specialisation's method
  with a value-level type argument collapsed to `Any`, so `pick[false]` and `pick[true]` of one `def pick[C: Bool]` were
  one `pick$Any` (a silent miscompile; the constant is now part of the name), and a failing `where` precondition left a
  runnable jar behind (the reconcile pass now reads the channel's table or aborts).

---

# Part I — The design

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
   rule 5's: an effect a code slot lists keeps its writer's implementation but runs on the frame the callee has for it
   where it runs the code ((b), §3.4), which is what lets the callee catch it.

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
   `computation` to `runThrow`), or binding an implementation with `with`, on the slot or around the code's runs —
   or **declares** in its own
   clause, which passes it on to its caller; doing neither is *"'body' may perform the effect 'X' here, which this
   definition neither handles nor declares"*. Declaring does not prevent handling: `describedAs` declares
   `Throw[AssertionError]` because it raises one itself, and still catches its body's failure. Declaring an effect
   the definition handles and never performs itself is the over-declaration error (§4). An effect the code uses
   through `*` without the clause listing it is invisible to the definition: bound where the code was written, it can
   be neither caught nor declared there (§2.2). **Only a body-less declaration gives an effect from nothing**, because
   that is where a frame comes from: the platform's primitives state what they give (§3.4), and the run boundary binds
   `main`'s entries. So **an effect's default is handed out at `main`, and by platform primitives, nowhere else.**

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
`uses` on a `data` field, and `with` after a definition's clause. **The brace spelling is gone**: a row written in any type — a return type, a
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

**An anonymous `implement` is a default; a named one never is.** An anonymous block in one of the **two sites** —
the ability's module or the type's module — is the default for its pattern, subject to the existing coherence and
`where` rules, and is what a declaration with no named implementation binds. It may take **parameters** after its
pattern (`implement[S] State[S](cell: StateCell[S])`), which are its runtime environment; only a platform primitive
can supply them today (§3.4). A **named** `implement` may live anywhere, is never searched, is not checked for
overlap, and its clauses may carry `uses` — what the implementation performs beyond its ability, charged wherever the
name is bound. It closes over nothing, but it may take **parameters**, written between its name and its ability as a
definition's are (`implement scripted(answer: String): Terminal`) and supplied by the `with` that binds it
(`greet with scripted("Bob")`): each clause receives them ahead of its own, as an anonymous implementation's do. They
are its runtime environment, so one specialisation serves every argument. No keyword marks such an implementation —
parameters are an ordinary parameter list, whether they hold a frame or a function. A named implementation's **type
parameters** are what its pattern takes where it runs: `with showing`, for `implement[T ~ Show[T]] showing: Source[T]`,
runs at `T := Int` for a `Source[Int]` and at `T := String` for a `Source[String]` under the same `with`, by the match
the two-site search makes for an anonymous implementation, and a pattern that does not match the entry is *"This
performs 'Source[Int]' on the implementation 'none', which does not implement it"* at that use. With parameters, the
arguments must fit every entry the `with` binds: *"The implementation 'constant' takes (String) where it runs
'Source[String]', but this `with` supplies (Int)"*, at the `with`.

```eliot
implement Console {                                       -- the platform's default
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

**`with` binds a name for every effect that escapes its subject** (D23): infix, subject-first, at the loosest
precedence, left-associative, so `xs.sort.render with reverseOrd` applies to the whole chain and `c with a with b` is
`(c with a) with b`, an inner `with` for the same ability shadowing the outer within its subject. Since every
definition declares what it performs and does not discharge, what *escapes* a subject is read off it: the effects its
calls declare, and those its own code performs. A `with` binds exactly those, for its implementation's ability — an
effect discharged further down never escapes, so no `with` reaches it — and a `with` that nothing escapes to is an
error, never a no-op. It works on an ability exactly as on an effect, and there are **two positions, one construct**:
an expression in a body, and after an entry of a parameter's clause — the same split as `f(x)` and `List[Int]`,
both application under the types-are-values cornerstone.

```eliot
def greetTranscript: String = runWriterToLog(greet with session)
def bobTranscript: String = runWriterToLog(greet with scripted("Bob"))
def sorted: List[Int] = sort(xs) with reverseOrd

def transcriptOf(program uses *, Console with recordingConsole: Unit) uses *: String = runWriterToLog(program)
def mocked(body uses *, Console with mockConsole, …, Mocking with recording, Calls with journal, …: Unit) uses *, …
```

The clause form reads *"this argument, run with `recordingConsole` for its `Console`"*: the actual delivered there
has its binding applied by the callee's signature, and the caller writes nothing. It is how a callee handles a listed
effect of code it cannot see, since an actual's calls are compiled in the caller — and so is a `with` **around a run
of a code parameter**: `runWriterToLog(program with recordingConsole)` is the same declaration written where the code
runs, and the compiler moves it onto the slot, because the caller compiles the code against the slot. It therefore
holds for every run, and every run of that parameter must bind the same implementation. A `with` on a slot handles its
entry even when the definition also declares that effect — the declaration then speaks only for what the definition
performs itself. A `with` around a run binds only what the slot **lists**: an effect the code uses through `*`
unlisted was bound where it was written, and a `with` for it binds nothing. A `with` after a definition's own clause
is rejected: it would be a second spelling of `with` around the body.

**A `with`'s arguments are values computed where it is written**, outside its binding: in
`greet with scripted(answer) with scripted("outer")` the inner `answer` reads from `scripted("outer")`. Only a body
`with` supplies them. A parameter's clause cannot (*"… takes parameters, which a parameter's clause cannot supply"*),
since a signature has no arguments to compute. Around a run of a code parameter whose slot lists its effect, only the
`with`'s **code** moves onto the slot: its arguments stay at the run, computed there, and the frame is built there and
handed to the code (D25's step 2). So a definition can bind an implementation its own parameters configure, and each
run may supply its own arguments, while every run still names the one implementation:

```eliot
def answering(name: String, body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted(name))
```

A `with` naming an implementation that takes parameters must supply exactly them, and one naming an implementation
without parameters supplies none.

**`with` is written almost nowhere.** Most code fixes nothing: a def declaring `uses Console` receives its binding
from its caller, up to `main`. That chain is what makes a fake possible — `greeting` never said which console, so a
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

- **declares it** in its own clause, binding no implementation for it on the slot, which passes it on to its caller:
  `if`'s `value uses *, Abort: T` under `if`'s own `uses Abort`, whose body discharges nothing, so an `abort` in
  `value` reaches `if`'s caller. The walk calls such an entry **ridden**: its implementation is the caller's, and the
  walk continues outward;
- **handles it** where the code runs — it lacks the entry, or names an implementation for it with `with`, so the slot
  **supplies** it, bound by that `with` or, with none, by `Default`, and rule 5 holds the definition to running the
  code inside a discharger of it.

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
`computation` to `runAbort`, `catch` to `runThrow`, and a `with` on a slot's entry is a chosen implementation. A
body-less declaration gives from nothing, and each platform primitive says what in its own clause (§3.4). Doing
neither is *"'body' may perform the effect 'Console' here, which this definition neither handles nor declares"*, at
the run, with the help naming both fixes; where a slot binds another implementation than the default the caller
wrote, it is *"'body' was promised the default 'Console', but runs where a slot binds another implementation for
it"*. So a slot cannot launder an interpretation effect into a pure signature. The same applies to the effects a
slot's **named implementation** performs in its own clauses: `transcriptOf`'s body must run `program` where the
`Writer[String]` that `recordingConsole` performs is handled, or declare it. "Lacks" and "declares" compare whole
**entries**, the slot's and the clauses' alike: eliot-test's `mocked` declares `Throw[AssertionError]`, so the
`Throw[IoError]` that its slot's `mockFileSystem` performs is supplied, and `mocked`'s own `catch[IoError, Unit]`
gives it.

A supplied entry's own **type arguments** are written at the call from the actual's declaration, matched
entry-by-entry against the slot's clause (an actual `bad` declared `uses Throw[String]` against `Throw[E]` gives
`E := String`). Where no declaration answers, the call spells it — `runThrow[AssertionError, Unit](body)` — and an
argument nothing determines is **rejected** rather than defaulted, which is what stops a `catch` from compiling
against a frame it will not meet at runtime. Three shapes reach that rejection honestly:

- the actual is a **parameter reference**. A code parameter's own clause answers in part: `runThrow(body)` over
  `body uses *, Throw[String]: Unit` determines `E` with nothing spelled, while `computation catch (err -> 0)` over
  `computation uses *, Throw[String]: Int` is still *"Cannot tell which 'Throw' this call supplies"*, so a `catch`
  over a parameter spells its arguments or types its handler (§7 item 2);
- the actual **raises nothing**, so it has no entry of that ability at all;
- the slot's clause names the **same ability twice** (`uses *, Throw[IoError], Throw[AssertionError]`), where only the
  call can say which one is being discharged.

**Handling and declaring combine, and an unlisted effect is out of reach.** A definition that declares a listed entry
may still discharge it around the code: the code runs on the innermost frame of that entry where the definition runs
it ((b), §3.4). So `describedAs(body uses *, Throw[AssertionError]: Unit, newMessage: String) uses
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
Nothing ever runs the binder: the environment threading replaces it (§3.4).

A **row alias** (§2.4) adds no mechanism of its own: a definition naming one as its return type is handed the alias's
row *entries* by `resolve/…/ValueResolver`, which mints them exactly as `EffectSugarDesugarer` mints entries written
in a definition's clause.

**`row/BindingWriter`**, run by `RowElaborationProcessor` between the recursion gate and saturation, then
rewrites one definition so that every reference carries the implementation each of the callee's phantom
binders stands for. Three jobs, one walk:

1. **write the bindings** — each binding binder, read off the callee's marks, is given a value by the
   resolution order below, merged by index with whatever else the call determines. A binder is never left to a
   metavariable.
2. **thunk and apply** — an actual delivered to a nullary code slot is wrapped in a lambda, and a reference to one
   of *this* definition's nullary code parameters is applied to `unit`. Doing both unconditionally is what makes a
   pass-through (`runAbort(computation)`, `val restFailures = rest`) come out right with no inspection of the
   argument's shape or type: wrap and apply are inverse, so a pass-through is an η-expansion. (The `unit` is where
   the environment goes after monomorphization, §3.4.)
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
nor is a `with` in the body, except around a run of the parameter, which is the slot's own (below). An entry the
clauses of the implementation its slot names perform is given, too, by a callee's slot that names the same
implementation and supplies that entry (`Scope.clausesGiven`): the callee is held to giving it where it runs the code,
as the definition was. A body `with`
whose named implementation performs an effect in its clauses may not be used below a nearer binding of that effect,
the one shape whose frame would be intercepted (§3.4); "that effect" is the clause's **entry**, looked up at its own
arguments as a frame is chosen, so a nearer `Throw[NotFetched]` does not stand between a `with` and the use of an
implementation performing `Throw[GitError]`. A body `with` no reference in its subject is written from is
*"This `with` binds nothing …"*, with the help naming the slot spelling when its subject is a code parameter.

**A run's binding is moved onto its slot before anyone reads the declaration** (`row/RunWithHoister`, run by
`row/processor/SlotBindingProcessor` into `SlotBoundValue`, which is what every caller's write and the environment
threading read as a declaration). A run is bound by a `with` directly around it, or by being **passed** to a callee's
slot that names an implementation of a listed entry with `with` (D25 step 3): `catch`'s `computation`, handed to
`runThrow`'s slot, is bound as that slot binds, and so is the code of every discharger written over `catch`. The
nearer wins, so a `with` around the run shadows the callee's slot. Only a nullary code parameter's listed abilities
move, and a callee's slot is not carried for an entry the definition also declares, spelled the same: that entry
rides (D22, (b)). The runs must agree, and a slot already naming another implementation of that ability is an error.
It reads the definition's own text and the declarations of the callees its runs are passed to, never a body, so a
binding is still written from declarations. Reading a callee's declaration is what puts it after the recursion gate:
a value reaches it only when its body cannot reach itself, so no callee it reads reads it back, and a callee the gate
rejected carries nothing.

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

There is **no classification left to approximate** — v5's elaborator had to classify each slot as carrier-headed or
payload, pinned or open, and needed a written whitelist to keep those approximations from accreting. A parameter
either has a `uses` clause or it does not, and a lambda is code or a value by the slot it stands at, both read off the
declaration. A rule that inspects a *sibling argument's expression shape* is inference, not desugaring, and is
prohibited; a decision that cannot be made from a declaration is a gap to close **in the declarations**.

The fail-safe direction is built in: a missing write is an aborted definition with a violation at its own
position, never a binding silently taken from the platform's default.

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

A second, post-monomorphization verifier existed and was retired by measurement (D7, 2026-09-10): after
`AbilityResolver` has rewritten `printLine` into the implementation method, which declares no row, a monomorphic body
can only see propagation through a *declaring callee*, never a direct operation call — a strict subset of the scope
check. **Do not reintroduce a post-mono effect verifier.**

What sits at that seam is **not** its shadow: `monomorphize/channel/SuppliedRowArgumentsProcessor`, wired as a
**codegen precondition** via `getFactOrAbort`, rejecting a supplied entry whose type argument nothing determines
(§2.2): *"Cannot tell which 'Throw' this call supplies … Write it out at the call"*. That check needs ground arguments
and so cannot move earlier. The environment threading (§3.4) sits at the same seam and reports the plumbing errors
only it can see — an entry with no frame, two coinciding frames — which are not effect verification either: every
binding they meet was written and checked by the walk.

### 3.4 The three primitives, and frames

A resuming clause is a call and needs nothing. A finishing clause is a **non-local exit**; a stateful
implementation needs a value **threaded through calls that never mention it**. Neither is expressible as a def
in a strict pure core — under v5 both were expressed by the transformer instances, which is the whole reason
the carrier existed. They are now three **platform-private leaves**, one per target, and nothing else:

| primitive | shape | jvm | microcontroller (hypothetical) | compile track |
| --- | --- | --- | --- | --- |
| escape | `escapeInternal[K, A, R](body uses *, Throw[K]: A, onExit uses *: K => R, onValue uses *: A => R): R` — abortive, never re-entered | a fresh frame object handed to `body`; `exitInternal(frame, err)` leaves through it by identity, and any other frame's exit passes through | a jump buffer (`setjmp`/`longjmp`), or a status flag and a jump (§13's A2) | an evaluator intrinsic, `escapeIntrinsic`/`exitIntrinsic`, the same shape: a fresh frame handed to `body`, exited by identity |
| cell | `withCellInternal[S, A, R](initial: S, body uses *, State[S]: A, combine uses *: A => S => R): R` — scoped to one call | a one-slot array made per call and handed to `body` | a slot in the primitive's own stack frame | an evaluator intrinsic still keyed by type (`eliot.compiler.Cell`); no compile-track effect is written over it |
| loop | `foreverInternal` | `while(true)` | the super-loop | never runs (`Inf` is stuck) |

**The control effects' single implementations are anonymous, written over them, and take the frame as a
parameter** — the frame is their environment:

```eliot
implement[E] Throw[E](frame: ThrowFrame[E]) {           -- jvm layer
   def raise[A](err: E): A = exitInternal(frame, err)
}

def runThrow[E, A](obj uses *, Throw[E]: A) uses *: Either[E, A] =
   escapeInternal(obj, (err: E) -> Left(err), a -> Right(a))
```

`Abort(frame: AbortFrame[Unit])` likewise over `escapeInternal`; `State[S](cell: StateCell[S])`,
`Writer[W](cell: WriterCell[W])` and `Dep[X](cell: DepCell[X])` over `withCellInternal`. **Each primitive copy states
in its own clause what it gives its `body`** (rule 5): `Throw`'s `escapeInternal` gives `Throw[K]`, `Abort`'s gives
`Abort`, the cell copies give `State[S]`, `Writer[S]` and `Dep[S]`. Being body-less, a primitive gives that default
from nothing, and **it gives the default's environment too, by handing the thunk a fresh frame**. Every discharger
hands its code to one of them; the dischargers themselves name nothing.

**An implementation is code plus an environment** (D20c option 5, decided (iii) and 2(c), built 2026-10-12). The code
is the binding, as above — a compile-time name, specialised on by monomorphization. The environment is threaded
**after** monomorphization, at the `WovenValue` seam (`monomorphize/channel/EnvironmentThreading`), whose ground
re-check (`WovenRecheck`) checks the plumbing as it checks every woven body. The checker never sees an environment and
the marker does not grow. Three routes carry one:

```
increment[State@Default](env)  = putState(env, state(env) + 1)          -- a received entry is a hidden parameter
runStateToPair(initial, p)     = withCellInternal(initial, cell -> p(cell), …)   -- a slot's block takes its frame
main                           = …(runStateToFinalState(5, cell -> { increment(cell); double(cell) }))
```

- **A received entry is a hidden parameter.** Each value's mono records its entries at ground arguments
  (`MonomorphicEffects`), and a definition receives one frame per entry of its declared row and per `~` constraint,
  ahead of its own parameters; a reference to it passes the frame in scope for each.
- **A block takes what its slot lists.** A row-typed slot's thunk takes the frames of the entries its slot lists in
  place of `unit`, and running the block passes the frames in scope where it runs. For a **supplied** entry (one the
  definition handles) the frame is at the slot's default or `with`; for a **ridden** entry (one it declares and binds
  no implementation for) the
  block's *code* is the caller's binding but its *frame* is still the run site's — **(b), "ride by code, give by
  frame"** — which is what lets a definition catch an effect it also declares (§2.2). Running such a block therefore
  consumes the entry it rides (§4).
- **A dispatched operation gets its frame's parts.** A reference the checker dispatched to an implementation's clause
  (`MonomorphicValueReference.dispatchedFrom`) passes the frame of the entry it was dispatched from: the frames its
  clauses perform on, then the implementation's own parameters, which the clause receives as leading value
  parameters.

**How a frame is chosen.** Within one woven body, the frames in scope are the definition's received ones and those of
every enclosing block; a reference takes the **innermost frame of its entry at ground arguments** (2(c)), so
`uses Throw[IoError], Throw[GitError]` is two frames, and two entries that coincide at one instantiation at the same
depth are a use-site error. The frame's binding must be the binding the reference was written with — code and
environment from the same place — and a disagreement is an error, never a silent pick. An entry with no frame in scope
is given its default only when that default has no parameters (the platform's `Console`, an ability's instance); a
default needing parameters with nothing to give them is *"The effect 'Throw' is performed here with nothing to
discharge it"*, at the operation — which is how an undischarged control effect at `main` became a compile error. A
named implementation bound by a body `with` is not a frame in scope (the walk erased the `with`), so its frame is
built at the reference from the frames there, which the walk's check in §3.1 guarantees are the frames at the `with`.
Its own parameters are the `with`'s arguments: the threading turns `scripted$with(xs, subject)` into
`(answer -> subject)(xs)` and walks the subject with `answer` supplied to `scripted`, so a frame built for `scripted`
there holds it, unless a frame of the entry is nearer — a block whose slot rides the entry is given its frame where it
runs, as (b) says. A slot naming such an implementation — moved there from a `with` around a run of its code — gives
the block the implementation's parameters too, after its clauses' frames, and a run passes the arguments the `with`
around it supplied: the hoisting left the binder's call `scripted$with(name, body)` at the run, so the threading
treats it as any other, and the run finds them there. A run whose binding was carried from the slot it is passed to
runs on the frame that slot gives the block it stands in (a `Frame` marked `bySlot`), clauses and parameters alike,
since that is where the callee built them; a received frame is never taken this way, as its clauses are the caller's.

**What that does and does not remove.** There is no search across calls: a frame reaches a definition only as a value
passed to it, so a helper's own `catch` or `State` cannot intercept the effects of a block its caller wrote with
`uses *` — the block captured its writer's frames (§8 items 16–17). The one choice left is the lexical one above, made
inside the body that runs a block, and it is only ever over an effect the slot **lists**: the frame a ridden entry
runs on is whichever frame of that entry the callee's body has in scope at the run — a handler chosen by the callee,
as in Koka. That is the decided meaning of listing (D22): a listed effect is the callee's to handle or to declare.

**Decided in the code while building, recorded for sign-off** (§11):

- an implementation's **parameters are part of its identity** (its pattern key), and on the compile track a
  parameterised implementation gives way to the overlay's parameterless one of the same pattern, so the jvm `Abort`
  (with a frame) and the overlay's (without) never merge into one value. Where the overlay has none (`Throw`), the
  borrowed one stays resolvable and running it on the compile track is stuck — loud;
- an `implement` clause gets **no frame for an entry it only carries** for a sibling (`ImplementationRows`' union):
  its binder keeps its positional slot but no constraint (`FunctionDefinition.carriedEffects`);
- an effect a **member declares** (`FileSystem.readFile`'s `uses Throw[IoError]`) is per call: its frame comes from the
  call site, and so does its binding — dispatch hands the implementation's method the call's binding at each clause
  binder whose entry the member declares, compared as the declarations spell it (`DeclaredEntries.forwarded`, recorded
  per resolution by `AbilityResolver`, applied per reference by `PostDrainQuoter`) — and an implementation's
  environment holds only the effects a clause performs that its member does not declare;
- supplied-versus-rides is decided **as the declaration spells it**, not at ground arguments (§2.2), and two frames
  that coincide at one depth are a use-site error;
- frame types are public, body-less, constructor-less types (`ThrowFrame`, `StateCell`, …), because `private` reaches
  only `def` and `data`, and they are named apart per module because `eliot.effect` is imported whole.

**The primitives are private, and the dischargers are therefore abstract in the base.** A public cell is Landin's
knot — a cell holding a closure that reads the cell is a loop, and `termination/PurityGuardTest` exists to keep it
out — so `withCell` may not be a base name, and `escape` follows for uniformity. `runThrow`, `runAbort`,
`runStateToPair`, `runWriterToPair` and `provide` are body-less signatures in the base and bodied per platform;
`catch`, `else`, `runStateToValue`, `runStateToFinalState`, `runWriterToValue` and `runWriterToLog` are ordinary
platform-independent bodies over those and stay in the base. Eliot has no *layer*-private visibility — `private` is
module-scoped — so each jvm module needing a primitive declares its own copy: five copies of two shapes, which also
separates the frames for free and lets each copy name the one effect it gives.

**The compile track is threaded too** (built 2026-10-13). Its control effects — `Abort` and `Throw`, the only ones that
run at compile time — are the compile-track overlay's (`stdlib/eliot/compiler/eliot/effect/`), spelled exactly as the
jvm layer's (`implement Abort(frame: AbortFrame[Unit])`, so the two are one implementation and the overlay's supersedes
on that track) over the evaluator's own `escapeIntrinsic`/`exitIntrinsic` (`EffectIntrinsics`): a fresh frame with
identity is handed to the body, and an exit leaves through exactly the frame it was given. The names differ from the
jvm leaves' on purpose — an intrinsic serves both tracks' evaluators, and the runtime track must keep finding no binding
for a jvm leaf. A frame reaches its operation by the same threading, `EnvironmentThreading` read through a track: a
`CompilerMonomorphicValue` records its `MonomorphicEffects`, and every compile-track evaluation that can reach a frame
evaluates threaded code —

- a callee linked into a reduction is its specialisation's reduced body, threaded (`ReducedBindingClosure.threaded`);
- a value's own body, read back, is threaded with the effects it receives (`PostDrainQuoter.reduceSourced`), and so is
  the refinement channel's reduction of a `^Meta`/`^Where` companion (`EscalatingReducer.reduceApplied`);
- a **signature** is threaded with its own control effects given by the **guarded-return read**: a `raise` or `abort`
  nothing in the signature discharges is performed on a frame the read installs around the evaluation and its
  read-back, and an exit through it is the rejection `Left(payload)`, so a rejected guard reports its author's message
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

**Two families, and only one is rebindable.** The **control effects** — `Throw`, `Abort`, `State`, `Writer`,
`Dep`, `Inf` — have exactly one implementation per platform, over the private primitives; `with` has nothing
to choose there. The **interpretation effects** — `Console`, `Log`, `FileSystem`, `Process`, `Environment` —
and every ability are what `with` and a naming slot are for. The families are a description, not a bit in the
language: nothing keys on it. Whether an implementation needs an environment is decided per binding by how it is
written, never by which family its effect is in.

### 3.6 An effect is declared, never read off a shape

Membership in an `effect` block says a member needs that binding; a **constructor class** is simply an `ability`
(`ability Container[F[_]] { def wrap[A](a: A): F[A] }`), and needs no exception.

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
- **a reference** to a callee with phantom binders resolves each binder by §3.1's order. An **ability** with
  nothing in scope binds `Default`; an **effect** with nothing in scope is the error, at that reference.
- **entering an argument** at slot *i*: the walk descends with the slot's supplied entries bound (to the entry's
  `with`, else `Default`) iff parameter *i* has a clause, and in the closed cut iff that clause has no `*`. A
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
  declare is bound at every run of the parameter by a slot that supplies it, as the caller's write promised; otherwise
  it is *"'body' may perform the effect 'X' here, which this definition neither handles nor declares"*.
- **a `with`** binds its ability for what escapes its subject, crossing a def boundary only through a declaration.
  One that no reference in its subject is written from is *"This `with` binds nothing …"*, never a silent no-op;
  one around a run of a code parameter is that slot's (§3.1).
- **a clause on a named implementation is charged at the binding site**: `greeting("Bob") with recordingConsole`
  performs `Writer[String]` there, because the name's clauses declare it, and the binding for it comes from the same
  order.
- **two exemptions, both because something else answers**: a **signature**, whose `raise`/`abort` is the guard
  channel's vocabulary and is discharged by the guarded-return read; and a platform **run boundary**, where
  every effect's chain ends and each of `main`'s entries is bound to the two-site `Default`. Neither is cut into
  value or closed regions.
- **a declared entry must be consumed**: an entry of a definition's own clause that no reference in its **body** is
  written from is *"This value declares the effect 'X' but does not perform it"*, at the entry, with the help
  *"Remove 'X' from its `uses` clause"*. A clause says *my caller hands me an implementation of these*; one nothing
  here uses asks every caller to declare an effect on no evidence, all the way to `main`. Consumed means written from
  at least once — by an operation, a declaring callee, or a `with`'s clause bindings — on any path, so `if(ok, v)`
  consumes `Abort` whatever `ok` is. Running a code parameter consumes exactly the entries its slot **rides**, since
  the block runs on the definition's received frame ((b), §3.4): `def passThrough[A](v uses *, Console: A) uses *,
  Console: A = v` is accepted, while its twin over `v uses *: A` lists nothing, consumes nothing and is the error. Exempt: a
  body-less value (its clause is a contract a later layer bodies), a run boundary, and a signature's own references.
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
3. **There is nothing to infer.** A phantom binder is written by a `with`, by the enclosing declaration, by a
   supplying slot, or as `Default`, in that order — never left to a metavariable, a join, a lattice, an
   ordering-sensitive slot decision, a row variable unified across arguments, or a sum over a monomorphized
   sub-graph. Carrier inference was the historical bug class (carrier theft, premature commitment); binding
   inference would be the same bug in the new vocabulary, and **dynamic scoping resolved at compile time** is its
   exact shape. Both are prohibited. `*` is not a variable: it names the scope the argument is written in. What
   ground arguments choose after monomorphization (§3.4) is only *which declared entry's frame* a reference runs on —
   no binding — and where that choice and the written binding disagree it is an error, never a pick.
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

- **A double is one declaration.** It needs no type to hang on, no colocation, and no coherence question: a named
  implementation is never searched, so it may freely overlap a default. *Never searched* is enforced rather than
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
  doubles and leaves everything else at its default.

**A discharge word binds doubles on its slot's entries**, so a faked case writes no fixture at all:
`transcriptOf(program uses *, Console with recordingConsole: Unit)` in `examples/src/EffectsTestFramework.els`, and
eliot-test's `mocked`, whose slot binds all seven of its doubles and recorders entry by entry (`body uses *, Console
with mockConsole, …, Mocking with recording, Calls with journal, …`). Rule 5 is why `recording` and `journal` are
named rather than anonymous: a definition with a body handles its slot's listed effects only with what it has, and a
`with` is what it has. A case then reads `"…" should "…" in mocked { … }`, a suite's whole declaration is its return
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

Each item is a defect to close or a decision to make, never a doc fix. Numbers are kept so citations resolve; closed
items keep one line, and their full record is in git history (§13).

**Open.**

4. **`runThrow("no failure")` is accepted** when the call spells the argument, although §2.2 lists an actual that
   raises nothing among the shapes rejected. Loud by construction (the argument is written by hand), so last.
29. **A rejected `main` reports a second, misleading error.** Found 2026-10-13, present before named implementations
    took parameters. When `main`'s runtime write fails (any row-phase violation), the synthesized entry point falls
    back through `NativeBinding` and `ContributedBinding` to `main`'s **compile-track** monomorphization at no type
    arguments, where its own binding binders are therefore `Type`; a named implementation bound in `main` whose clause
    performs `Console` then adds *"No ability implementation found for ability 'Console' with type arguments [Type]"*
    at that clause, and a `with` supplying the wrong number of arguments adds *"Not a function."* Loud, and only after a
    real error, which is reported first.
31. **A `with`'s arguments are typed from themselves alone.** The resolver lowers `with mapping(x -> show(x))` to a
    call of the binder `mapping$with[T, …](f: T => String, subject)`, so the checker infers `T` from the arguments and
    nothing else; the entry the `with` binds, which decides `T` where the implementation runs (§2), is not consulted
    until the environment threading. An argument whose type only the entry determines — a lambda with an unannotated
    parameter, `None` — is therefore typed at `Type` and refused at the `with`: *"The implementation 'mapping' takes
    (Int -> String) where it runs 'Source[Int]', but this `with` supplies (Type -> String)"* (measured 2026-10-14;
    `(x: Int) -> show(x)` compiles). Loud, never wrong. The fix is the row phase writing the binder's type arguments
    from the entries the `with` binds, matched against the implementation's pattern, wherever those entries'
    arguments are spelled; parameterised ability implementations need it (`people.sort with byKey(p -> p.age)`).
**Closed.**

| # | defect | closed |
| --- | --- | --- |
| 1 | an unused `with` was a silent no-op — the likeliest case a `with` around a code parameter's run | 2026-10-13 by D23: an error, and a `with` around a run of a listed parameter is the slot's |
| 2 | a dot-read of a row-typed field handed back the thunk | 2026-09-12; its subject (stored computations) deleted by D20 step 3 |
| 3 | `val x = comp` then `x else …` failed | 2026-09-12 by D17 — the tree was right, §3.5 was wrong |
| 5 | an undischarged control effect at `main` failed at runtime | 2026-10-12 by option 5 (D19): a compile error at the operation |
| 6 | a row alias works in return position only | moved to §7 item 5 / D20a; the file-local limit was lifted 2026-09-12 (§9.3 step 8) |
| 7 | a named implementation answered the two-site search | 2026-09-12: the search and both coherence checks read the anonymous ones only |
| 8 | a slot's `with` was missing from the layer merge's signature comparison | 2026-09-12: `structuralEquality` gained the `WithBinding` arm |
| 9 | a lambda at a rowless arrow reached the enclosing bindings | 2026-10-09 by D21 step 1, by rejecting it (rule 2) |
| 10 | a function parameter could be kept, and its lambda run after its discharger returned | 2026-10-09 by D21 step 1 (rule 3) |
| 11 | a lambda in a value position captured the enclosing bindings | 2026-10-09 by D21 step 1 (rule 2) |
| 12 | a data constructor supplied a field's row by `Default` | 2026-10-09 by D20 step 3 (rule 6) |
| 13 | a slot laundered an interpretation effect into a pure signature | 2026-10-09 by D20 step 2 (rule 5) |
| 14 | an unapplied reference to a `uses` definition passed as a value (`Holder(printLine)` printed from a pure signature) | 2026-10-13: the rule-2 error at the reference, beside `Scope.enterValue` |
| 15 | an effectful member of a parameterised ability needed its type arguments spelled (`greetAs[Bob](…)`), since a positional list cannot reach the member's own binding past the ability's undetermined `A` | 2026-10-13 by option (a): in a body the write puts the checker's hole, `eliot.lang.Implementation::Inferred`, at such a binder, and the checker infers it with a fresh meta |
| 16 | a helper's own `catch` swallowed its caller's `raise` | 2026-10-12 by option 5, runtime track (§3.4, §7 item 7) |
| 17 | a helper's own `State` received its caller's `putState` | 2026-10-12 by option 5, runtime track |
| 18 | a slot's named implementation performed its clause effects into whatever frame enclosed the run | 2026-10-12: its clause effects are promised to the block (§2.2) |
| 19 | two entries of one effect were one binding in the walk | 2026-10-12: entry-level keying (§3.1) and frames chosen by entry (§3.4) |
| 20 | a slot `with` on an entry the definition also declares was silently dropped, and the code ran its caller's implementation | 2026-10-13 by D23: a slot `with` always handles its entry |
| 21 | a definition the effect check rejected was "Name not defined." at every caller | 2026-10-13: the checker names it — *"Cannot use 'X' here: its own definition was rejected."* |
| 22 | a body `with`'s implementation was rejected below a nearer discharge of the same effect at *other* arguments (eliot-build's `CacheTests`: `offline`'s `Throw[NotFetched]` read as intercepting `tableGit`'s `Throw[GitError]`; its `Writer[List[TestResult]]` error was a knock-on) | 2026-10-13: the clause check keys by entry, as frames are chosen |
| 23 | a slot's named implementation's clause entry rode the caller whenever the definition declared the same *ability* at other arguments (eliot-test's `mocked`: `mockFileSystem`'s `Throw[IoError]` against `mocked`'s `Throw[AssertionError]`), and the definition was not held to giving it | 2026-10-13: supplied-versus-rides and the given-check compare by entry |
| 24 | an ability member's own clause binding was dropped at dispatch: `printLine(describe("x")) with recordingConsole`, `describe` declaring `uses Console`, printed the implementation's output to the real console, since a dispatched reference kept only the implementation's own type arguments | 2026-10-13: the clause binders whose entries the member declares take the call's binding, per reference |
| 25 | a definition specialised on a binding with an environment and on one without was emitted at one shared arity, so `shout("a")` beside `shout("b") with recordingConsole` failed at runtime with `NoSuchMethodError` (in `v0.10` too) | 2026-10-13: the backend caps each specialisation at its own natural arity; `used` caps at the widest |
| 26 | on the compile track a helper's own discharge caught its caller's effect: `tryOr(if(C, String[]), Int[]) else Bool[]`, with `def tryOr[A](body uses *: A, fallback: A) uses *: A = body else fallback`, compiled with `pick[false]: Int` — the compile track found a frame by its type. Nothing in the corpus met it (measured: no compile-track exit ever had two frames of one key installed) | 2026-10-13 by compile-track frames (§3.4): a frame is a value with identity, threaded along the declared chain |
| 27 | a guarded return's `else` arm never reduced: `if(C, String[]) else Bool[]` at `C := false` read back as `abort(Default)`, since the signature's raw read dispatches no ability and the escape answered `Right` of the undispatched call (present at `v0.0`; a fallback reached only through a nested discharge happened to work) | 2026-10-13: a type-level read-back treats a result holding an ability method's call as stuck (`EscalatingReducer.typeLevelStuck`), so the raw read falls through and the checked one escalates to the callee reduced at its instantiation |
| 28 | a `where` over a control effect could not be evaluated: `where (if(c, true) else false)`, and even an inline `where rangeWithin[0, 255](range(x))`, reported *"Cannot evaluate the `where` precondition"* while the same predicate behind a helper worked (present at `v0.0`) | 2026-10-13 by compile-track frames (§3.4): the channel's reduction of the `^Where` companion is threaded and links its callees reduced at their instantiations. A failing precondition still leaves a runnable jar behind (exit 1) — the build-artifact invariant's, not this document's |
| 30 | a named implementation's own type parameters were never solved for a binding: dispatch ran its clauses at `T := Type`, so `pick(42) with showing`, for `implement[T ~ Show[T]] showing: Source[T]`, lost `Show[Int]` (reported as *"This native value produces 'String' … states no meta transfer"* at `Show`), and one taking parameters as well was refused where it was bound | 2026-10-14 (D25 step 1): solved where the implementation runs, by matching its pattern against the entry's ground arguments as the two-site search does (`NamedImplementationArguments`), at dispatch and in the environment threading alike; a pattern that does not match is an error at the use, and arguments that do not fit are one at the `with` |
| 32 | a slot naming a **constrained** named implementation was rejected: `shown(body uses *, Source[A] with showing: String) uses *: String = body`, for `implement[T ~ Show[T]] showing: Source[T]`, was *"'body' may perform the effect 'Show' here, which this definition neither handles nor declares"*, since the given-check read the implementation's `~` constraint, an ability, as one of the effects its clauses perform (found building D25 step 2, where a `with` around a run lands on the slot; Writer's `W ~ Combine[W]` implementation meets it at step 4) | 2026-10-14: the given-check reads only what the implementation's row lists, as the threading does (`BindingWriter.performedEntries`); a constraint's `Default` is the two-site search's wherever it is written |
| 33 | a run passed to a callee's slot that names an implementation was compiled against the definition's own slot: `def passing(body uses *, Terminal with other: Unit) uses *: String = run(body)`, for `run(body uses *, Terminal with fixed: Unit)`, ran `other` and silently dropped `fixed` (found building D25 step 3) | 2026-10-14 (D25 step 3): the run is bound as the slot it is passed to binds, and a definition's slot naming another implementation is the hoisting's *"The slot of 'body' names another implementation of 'Terminal' than the `with` around its runs."* |

Items 9–13 were found 2026-10-05 while designing D20 and share one cause: a row on a slot both *marked a block* and
*minted bindings*, and nothing kept the block inside the call or checked that the callee had the binding it handed
over. Items 16–19 were numbered 14–17 on the line option 5 was built on.

## 9. The binding binder's mark (built 2026-09-11/12)

A binder the desugar mints — for a clause entry, for a `~` constraint, or for an ability block's binding slot — is
declared with the type `Implementation[A]`, `A` being the ability it binds, and every phase that needs to know which
binders are bindings reads that declared type and nothing else. Part I §3.1 states it. Before it, the same fact was
encoded four times (`GenericParameter.inferable`, the forwarded count `inferableArity`, `BindingWriter.mintedPhantoms`'
re-derivation, and a `Qualifier.Ability` special case), which forced a prefix write, a textual splice of row aliases
and hand-kept consistency. What is kept here is what the build measured, because each is a natural reading that is
wrong. **Do not re-propose the original readings.**

### 9.2 The form chosen, and the corrections

- **Chosen: the existing `parameterType` slot**, with `type Implementation[A] = Type` in `eliot.lang.Implementation`
  (FQN in `WellKnownTypes`), minted module-qualified so the mark never depends on a file's imports. Not chosen: a flag
  on the signature's `FunctionLiteral` (lands on every phase's case class, on cost), and a typed marker the checker
  verifies (catches only compiler bugs; deferred, and contained if ever taken).
- **The alias does not reduce away for the checker; the mark is erased at `row`.** The checker kind-checks the
  argument before reducing: an ability's marker has kind `Type -> Type` or above, so `Implementation[Console]` fails
  ("Expected: Type, Actual: Type -> Type"). No kind for the alias's parameter accepts every ability, so
  `BindingWriter.Writer.unmarked` rewrites a marked binder's type back to `Type` at the end of `row`.
- **The mark's argument resolves as an ability** (`ValueResolver.markedAbility` → `resolveAbilityName`), not as a type,
  or the fixed-FQN abilities are missed and a mistyped effect reads "Name not defined.". Every pool declaring a row,
  a constraint or an ability therefore needs `eliot.lang.Implementation`, test stubs included
  (`ProcessorTest.implementationStubContent`).
- **The prefix's diagnostic does not go with the prefix.** A type-argument list had no hole, so the merge stopped at
  the first slot nothing fills; the diagnostic moved from the declaration to the **call**
  (`BindingWriter.Writer.unreachableBinding`), raised only when that call's own arguments leave a binding out of reach.
  Since §8 item 15 (2026-10-13) a **body**'s list has one — `eliot.lang.Implementation::Inferred`, a fresh meta to the
  checker — at an undetermined binder before a binding, so the diagnostic is left to a signature, whose references are
  evaluated rather than checked.
- **An alias mints no binders of its own** (measured building step 5). Its parameters are value arguments, where a
  mark is never erased; and an alias is a name for a row, not a definition that performs one, so it has nothing to
  receive. It *declares* its row (step 8), which needed a type definition's own row resolved with its value args in
  scope — reversing step 5's "the alias cannot state its own row".

### 9.3 The steps

1. `type Implementation[A] = Type` and its FQN. 2. Mint with the declared type (`GenericParameter.implementationMark`).
3. The write reads marks (`BindingWriter.phantoms`, the positional merge `mergeTypeArguments`). 4. Delete the dead
encodings. 5. The row alias is an ordinary type alias whose use hands over entries. 6. Tests (`RowAliasesTest`,
`RowAliasIntegrationTest`). 7. Documents. 8. The alias is an ordinary *name*, resolved at `resolve` — lifting the
file-local limit and the by-spelling misreadings. Every step held §10's byte-identity gate.

## 10. How a change is run

**The gate.** `./mill __.test` green, every example program carrying a `main` compiles, and every example jar
`md5sum`-identical to the pre-change build — `scripts/example-sweep.sh`, whose `jar.md5` lines are the gate.
Byte-identity is a **safety oracle, not a hard gate**: when the output legitimately changes wholesale it is
replaced by **behavioural identity**, the same script's `exit`/`stdout` lines compared before and after, with
the size and instruction-count difference stated in the commit. A regression there is a finding to explain,
not a cost to accept. A report is compared with its `#` header lines stripped; the cache under `target/` must
go between compiles; stdin must be `/dev/null`; and never run `./mill` while the sweep runs, or it rebuilds
`out/` underneath and the report reads as a large regression that is not there. Changes reaching eliot-test or
eliot-build are also run against their suites under this compiler.

**What the gate cannot see.** The corpus does not cover the type-level surface: no example writes a guarded return
type, a `catch` with an ignoring handler, or a stored computation, and four real defects hid in exactly that gap at
the flag day. A shape with no example has no gate; the jvm end-to-end suites are what separate it.

**When the question is "is this still load-bearing?"** — reuse the method rather than re-inventing it:

- **Arm-liveness tracing, not inspection.** A temporary env-gated tracer with a `fire(arm, sample)` call on
  every arm under consideration; run the whole gate *and* a compile of all examples; delete only zero-fire
  arms, at outcome granularity (a router's entry count is mostly routing).
- **Switch it off, part by part.** An env-gated bypass answers in behaviour. Gate each part **separately**: an
  all-off run once said "43 failures, delete nothing" while the per-part runs said one part costs 1 test and
  another 36, which was the whole finding.
- **An examples-only audit under-reports**, because elaboration is demand-driven: disabling *every* effect
  arm once still compiled every example to byte-identical jars.
- **A firing arm is a question, not a verdict**; **measure twice, before and after** — one deletion's first
  cut looked like an improvement while dropping a fail-safe.
- **Tracer gotchas**: mill prefixes every forwarded line with a worker id, so never anchor a grep; a sample key
  must carry the range **end** as well as the start and, for an argument, its spine **head**; delete the cache
  before every run or the pipeline replays facts and the trace comes back empty.

**Two traps in the test harness.** A `ProcessorTest` needs an `Implementation` stub declaring `type Default` and the
`Implementation[A]` alias, or every snippet calling an ability method loses its monomorphization **silently, with no
error at all** — the write puts `Default` at the reference as an ordinary type argument and saturation demands the
value it names. And a test stub that diverges from the real declaration (`fold`'s suspended arms) fails silently too.

## 11. Decisions: open, and decided but not built

Only rule decisions are listed; conveniences are in §12 under "not now". Every decision is Robert's.

### D20a — naming a set of effects

`type Git[A] = {Process, FileSystem, Throw[IoError], Throw[GitError]} A` is a row alias, return position only (§2.4).
The natural replacement is a name used in a clause, `uses Git` on a definition and `uses *, Git` on a parameter. Being
declaration metadata rather than a type, it need not cost an `ast.fact.Expression` case; its declaration's spelling
is undecided. Until then a row alias's body is the one place braces parse.

### D20c — storing an effectful computation whose effects are handled where it runs

Declaring an algebra as an `effect`, writing code against it in direct style, storing that code and interpreting it
later under a handler chosen *then* — hooks run at the end of a process, a job queue, an event loop's handler table —
has no spelling: code cannot be kept (rule 3) and a value lambda may use no effects (rule 2), so what is left is a
hand-written `data` mirroring the algebra and an interpreter over it (§2.3). The rejection of stored computations
argues against the **closed** kind (effects bound where written: unsound for a control effect, whose frame may be
gone). The **open** kind (effects bound by whoever runs it) was never weighed. The question is whether Eliot stores
open computations at all, and if so how. Compiling it is the obstacle, not soundness: an implementation is a
compile-time name, and a stored block must be compiled against the implementation it will meet at a run site
somewhere else.

- **Option 1 — the binding travels in the container's type** (`Hooks[I]`). Refuted as first argued by the spike of
  2026-10-11: a binding names an *implementation*, never a *frame* (every frame of `Throw[String]` shares one name), so
  it needs a region binder per discharger call that the result may not mention. Unmeasured.
- **Option 2 — the implementation is passed when the block runs.** Implementations as runtime values in structures:
  reverses 2026-09-06 and meets the step-6 limit (§12, "an implementation as a runtime value").
- **Option 3 — the compiler reifies the block** into a free monad (`reify(body uses Cleanup: A): Program[Cleanup,
  A]`). Needs continuation capture, a recursive `Program` (blocked like `docs/recursive-data.md`) and a native loop. A
  partial form works today for algebras whose results nothing branches on: a named implementation that `tell`s each
  operation as data, interpreted at the end.
- **Option 4 — an open field is a finite product, selected at the read.** The whole program is monomorphized from
  `main`, so the compiler knows every implementation of an effect. The surface is one more position for the clause,
  `data Hook(run uses Cleanup: Unit)`: the body written at the constructor is checked as a `def` with that clause and
  captures no effect from around it; reading the field is calling such a `def`, so `Cleanup` is charged and bound
  **at the read**. The desugar lifts the body to a synthesised named definition (polymorphism stays on a named
  signature). It must be **specialised at the read, not at the constructor**: a constructor would need every chain of
  clause bindings, which is unbounded, while the read is where the ground binding exists — so a stored value is one
  closure with one entry point per binding the program reads it at. The control half is simply a closure taking an
  environment (§3.4). Costs and conditions:
  - it reverses rule 6's rejection of `uses` on a field and narrows rule 2 to "may use only what its own declaration
    gives it" — reversals to sign off;
  - whether the read-site set is standing rule 3's prohibited "sum over a monomorphized sub-graph" is a reading for
    Robert: it decides what code is generated, never a binding;
  - **Landin's knot returns** — `data Knot(run uses State[Knot]: Unit)` with `runStateToValue(Knot(state.run),
    state.run)` loops with no recursion — so **`StrictPositivityChecker` must count a field's `uses` entries as
    negative positions**;
  - §4's "declares but does not perform" must not fire on a field's clause (a no-op hook is legitimate);
  - the product is part of the data type's layout, so adding a named implementation anywhere changes it.
  Measure first: how a named implementation's clause row reaches the lifted definition's instantiation.

Until decided, the answer is option 3's partial form, or a hand-written `data` and interpreter.

### D3 — `~` and `&` fully in user space

Still blocked on meaning, not difficulty. What is left is whether `~` and `where` unify, and the phase-order
blocker (the superability closure runs at resolve, before operators are structured) still lands first if that
is ever answered yes. "What does an ability denote as a value?" is answered by *nothing*: an implementation's code is
not a value, so an ability denotes no type of values.

### Decided, not built

In the order they would be built:

- **D25** (2026-10-14): *parameters only on a named implementation, and the platform binds its control effects with
  `with`.* **Reverses** D20c option 5's point 3 (decided 2026-10-12) as far as it reached a default — `with
  Ability(args)`, `p with State(cell)` — and with it the planned "user defaults with parameters", which was to amend
  §1 rule 5 by *a body gives a default only by supplying its parameters*. Raised from the spelling: `with Config(s)`
  is capitalized, reads as a constructor or a type application, names the effect rather than an implementation, and
  makes an implementation look like an object.

  **The rule.** A default is what is inserted where nothing is written, and nothing that inserts one has arguments to
  give: the two-site search finds an ability's instance by pattern, and the run boundary holds none. So an
  implementation with parameters is never a default, and it is always **named**. One shape for every implementation:

  ```eliot
  implement Console { … }                                  -- no name: a default, never with parameters
  implement session: Terminal { … }                        -- a name: never searched
  implement scripted(answer: String): Terminal { … }       -- a name and parameters: given by `with scripted("Bob")`
  ```

  `implement [name[(params)]:] Pattern`, the colon written exactly when there is a name. An anonymous implementation
  with a parameter list (`implement[S] State[S](cell: StateCell[S])` today) is a parse error naming the named form. A
  member refers to a parameter by name, as today.

  **The platform uses the user's mechanism.** A control effect's implementation is named, and the platform's body of
  the base's body-less discharger binds it with an ordinary `with`, supplying the frame the primitive made:

  ```eliot
  implement[E] escaping(frame: ThrowFrame[E]): Throw[E] {
     def raise[A](err: E): A = exitInternal(frame, err)
  }

  def runThrow[E, A](obj uses *, Throw[E]: A) uses *: Either[E, A] =
     escapeInternal(frame -> obj with escaping(frame), err -> Left(err), a -> Right(a))

  private def escapeInternal[K, A, R](body uses *: ThrowFrame[K] => A, onExit uses *: K => R, onValue uses *: A => R) uses *: R
  ```

  A primitive then gives no effect: it hands a frame to a function. **A `with` is the only thing that gives an
  implementation**, and a default — always parameterless — is given only at `main`. That is §1 rule 5 shortened, not
  amended: *"only a body-less declaration gives an effect from nothing"* goes, with §3.4's *"each platform primitive
  says what it gives in its own clause"*. The base stays platform-independent: `runThrow` stays body-less there, the
  `with escaping` lives in the jvm body, and a `with` around a run is moved onto the slot on the **merged** value, so
  the base and jvm signatures stay textually equal and a caller reads `escaping` on the slot per platform (and the
  overlay's own spelling on the compile track). A user discharger is the same code:
  `def withConfig[A](s: Settings, body uses *, Config: A) uses *: A = body with fixed(s)` over
  `implement fixed(s: Settings): Config`. An undischarged control effect at `main` stays a compile error, now because
  the effect has no default at all.

  **A run passed to a slot is bound as that slot binds** (the one new rule; Robert chose it 2026-10-14). The base's
  pass-through dischargers — `catch` hands `computation` to `runThrow`, likewise `else`, `runStateToValue`,
  `runStateToFinalState`, `runWriterToValue`, `runWriterToLog` — and every user discharger over them (eliot-test's
  `mocked`, eliot-build's) write no `with`, and the base cannot name `escaping`. Their caller compiles the code, so it
  must read the implementation off their declaration. So: **a run of a code parameter at a callee's slot that binds
  `X` for an entry the run's slot supplies is a run under `with X`**, and D23's hoisting moves `X` onto the slot,
  exactly as for a written `with` — `escaping` onto `catch`'s slot, then onto `mocked`'s. Runs that disagree are
  D23's error. A slot entry the definition also declares rides (D22, (b)) and is not hoisted; its frame is the run
  site's, its code the caller's. This is **not** §12's transitive `with`: each step reads one callee's declaration,
  before monomorphization, and writes the result onto the definition's own slot, where every caller reads it; nothing
  is summed over a monomorphized sub-graph and nothing is overridden.

  **Steps**, each gated by byte-identity as an oracle (the specialisations' names change, so the jars will not be
  identical; §10) and by eliot-test and eliot-build:
  1. ~~§8 item 30~~ — built 2026-10-14: a named implementation's type parameters are what its pattern takes at the
     entry's ground arguments, at dispatch and in the threading (`NamedImplementationArguments`).
  2. ~~A `with name(args)` around a run of a code parameter~~ — built 2026-10-14: the **code** moves onto the slot,
     the **arguments** stay at the run, where the frame is built (§2, §3.4). A parameter's clause still cannot spell
     arguments; the slot carries code only. It found and closed §8 item 32.
  3. ~~A slot's binding carried to the slot a run is passed from~~ — built 2026-10-14: the hoisting moved out of
     operator resolution to its own fact after the recursion gate (`SlotBoundValue`, §3.1), where reading a callee's
     declaration cannot lead back. A decision on the engine's in-progress chain (`activeFactKeys`) was the
     alternative and was not taken: the cached fact would depend on demand order and miss the edge it declined to
     read.
  4. The control effects — `Throw`, `Abort`, `State`, `Writer`, `Dep`, on both tracks — moved to named
     implementations bound by their platform dischargers; the primitives' clauses lose the entry they give.
  5. The anonymous parameter list, `ImplementationParameters`' anonymous route, and the giving primitives deleted; §1
     rule 5, §2 and §3.4 rewritten. The first sign-off item of §3.4's "Decided in the code" (an anonymous
     implementation's parameters as part of its pattern key) lapses.

  **On its path:** `Writer[W]`'s `W ~ Combine[W]` makes its implementation a *constrained* named one, where an
  undiagnosed codegen failure was measured — "Function not implemented." at `Combine`'s own marker, 2026-09-12, with
  an abstract `implement[W ~ Combine[W]] writerByCell: Writer[W]`.

  **Rejected:** `with Ability(args)` for a default (the reversed point); naming the platform's implementation on every
  pass-through slot (`computation uses *, Throw[E] with escaping`, declared abstractly in the base), which leaks the
  frame mechanism into every user discharger's signature; keeping parameters on the platform's anonymous
  implementations alone, a construct no user could ever supply.
- **Parameterised ability implementations**: `people.sort with byKey(p -> p.age)`, by D25's mechanism, after §8 item
  31 (the lambda is typed by the entry). Runtime track first.

**Awaiting sign-off:** the five choices made in the code while building option 5 (§3.4, "Decided in the code").

### Closed decisions

Numbers are kept so citations resolve; the record of each is in git history (§13).

- **D4** (`Suspend`-riding effects) dissolved at the flag day. **D5** (a lambda body at a rowless arrow) closed
  2026-09-08, now rule 2. **D7** (the post-mono verifier) retired by measurement 2026-09-10 (§3.3, §12). **D13**
  (eliot-test cases built with their handlers applied) closed 2026-09-08: `with` on a discharge word's slot.
- **D17** (2026-09-12): a `val` is a bind (§3.5).
- **D18** (a row alias at a parameter) superseded by D20: no effect is written in a type, so there is nothing to move;
  what replaces it is D20a.
- **D19** (an undischarged control effect at `main` must be a compile error) closed 2026-10-12 by option 5, by neither
  of its costed routes: the platform's default needs a frame and `main` holds none (§3.4).
- **D20** (2026-10-05; built 2026-10-09/10): effects are parameters — the `uses` clause, code used and never kept,
  give only what you have, a field holds a value. **D20b** (dual-parse window or flag day) answered by the sequencing:
  both spellings parsed until step 6.
- **D21** (2026-10-09; built with D20): a function parameter is a value, code is marked `uses *`, a clause without `*`
  is closed, and purity is the absence of `uses`.
- **D20c option 5 and (b)** (2026-10-12, built): an implementation's environment threaded after monomorphization;
  ride by code, give by frame (§3.4). D20c's open question remains, above.
- **Named implementations with parameters** (decided 2026-10-12, built 2026-10-13): supplied at the `with`,
  `greet with scripted("Bob")`; one construct chooses code and supplies arguments, and no keyword marks such an
  implementation (§2, §3.4). Type parameters on the same implementation since 2026-10-14 (§8 item 30).
- **Compile-track frames** (decided 2026-10-12, built 2026-10-13): the compile track threads its frames as the runtime
  track does, after monomorphization, and the guarded-return read gives a signature's own control effects (§3.4).
- **D23** (2026-10-13): *what does a `with` apply to?* **Every effect of its ability that escapes its subject** — what
  the subject's calls declare and its own code performs, since every definition declares what it does not discharge —
  and nothing discharged further down. So: a `with` nothing escapes to is an error (closing §8 item 1); a `with`
  around a run of a code parameter binds what the slot lists, and is moved onto the slot, because the caller compiles
  that code against the slot (every run must agree); and a slot `with` handles its entry even when the definition also
  declares the effect (closing §8 item 20). An implementation stays a compile-time choice: each distinct one is its
  own specialisation of every definition it reaches through declarations.
- **D22** (2026-10-13): *a slot listing an entry its definition also declares — handler or pass-through?* Found by a
  review of the plan on the option-5 line (`1034c8db`, R1, there D21d): under (b) a `guarded(body uses Throw[String]:
  Unit) uses Throw[String], Console: Unit` that runs `body` inside a private `catch[String, …]` catches its caller's
  `raise`, with the same signature shape as `describedAs`, which means to. **Decided: both are handlers, and that is
  what listing means.** A listed effect is the callee's to handle or to declare; one it does not handle it causes
  itself and must declare; one the code uses through `*` without the clause listing it is invisible to the callee,
  which can neither catch nor declare it (§1 rule 5, §2.2). No behaviour changed — the tree already did this since
  (b) — so the decision is the rule and its vocabulary, plus the rule-5 diagnostic's wording ("… neither handles nor
  declares"). Given up: a closed clause cannot promise that a listed effect reaches the caller untouched; nothing
  needs that today, and eliot-test's `pure { … }`, if reinstated, catches and re-raises explicitly. Rejected: always
  the caller's frame (makes `describedAs` unwritable — the "(a)" of 2026-10-12) and a new handler marker (a second
  spelling beside `uses`).
- **D24** (2026-10-13): *should a definition's own clause say that a call performs what its code parameters do?*
  Raised as a readability problem with D22: a reader of `map(f uses *: A => B, list: List[A]): List[B]` sees a pure
  result and must read the parameters to learn otherwise. **Decided: `uses *` on a definition, written exactly when
  some parameter's clause has `*`** — first, as on a parameter — so the result side of a signature is complete:
  `map(…) uses *: List[B]`, `if[T](…, value uses *, Abort: T) uses *, Abort: T`, and no `uses` there means a pure
  call. It is a **marker**, checked by the parser in both directions and dropped: it mints no binder, adds no entry,
  and changes no semantics — an effect the code uses through `*` is still bound where the code was written and still
  invisible to the callee (D22), so the body may not perform an unlisted effect because of it. Reverses §2.6's
  "`uses *` on a definition has no spelling" (there because it repeats what the parameters state; the repetition is
  now the point, where the reader looks). Optional was rejected, since then its absence would say nothing; Koka's
  spelling — a named effect variable in both places — remains closed (§2.6), since nothing here is unified.

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
  an operation call is by then a call to an implementation method, which declares no row. (Not about the
  *precondition* or the environment threading at that seam, §3.3.)
- **The carrier as the injection point.** It chose an interpretation by instantiating a type, and every one of v5's
  testing limitations was a symptom. Superseded by §6.
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
  would be computed once. What option 5 recovered *without* it: an implementation's parameters, as a monomorphic
  runtime environment beside compile-time code (§3.4) — the class's code versus an instance's fields.
- **A handler as a marker *type* in the type language** — a marker type per handler, a binder that occurs in
  types and is unified, a "two handlers meeting is a mismatch" rule, one environment per stored computation,
  a handler result function, and a post-mono lowering pass with `handler`/`returning`/`finish`/`resume`/
  `return`. What is closed is each of those. What is **not** closed is a *phantom* binder per clause entry as the
  mono-key component (§3.1): it occurs in no parameter or return type of any value, so nothing unifies it. Its
  declared type `Implementation[A]` (§9) is erased at `row`, before the checker sees it.
- **Transitive `with` / summing abilities over a monomorphized sub-graph.** Dynamic scoping resolved at
  compile time; the carrier's third job in a new costume. Forwarding is by declaration only (§3.1).
- **A default with parameters** — `with Ability(args)`, `implement Config(s: Settings)` given by a user body.
  Reversed 2026-10-14 by D25 (§11): a default is what is inserted where nothing is written, and nothing that inserts
  one has arguments; an implementation with parameters is named. Nor the platform's control effects as the one
  exception, nor the platform's implementation's name written on every pass-through slot (D25, "Rejected").
- **A bindings field on `ValueReference`, a scoping node, or a consulted-set component on the mono key** as
  the carrier of a binding. Decided 2026-09-08 against, for the phantom binder (§3.1): the key already
  carries one argument per binder, and transitivity is a ground-value tree.
- **A runtime handler stack searched by type, as the language's mechanism.** Kills erasure and makes accidental
  handling the default. Since option 5 even the primitives search nothing on the runtime track: a frame reaches a
  definition only as a value passed to it. The one choice left — the innermost frame of an entry within one woven
  body (§3.4) — is lexical, and is only ever over an effect the slot lists, which is the callee's to handle (D22).
- **"No call site is ever dynamically nested inside itself"** (proposed 2026-10-11 to give each frame a compile-time
  identity). Refuted 2026-10-12: a higher-order argument nests a site inside itself with no cycle in the reference
  graph — `catch`'s one `runThrow` installs two live `Throw` frames when a block run under it calls `catch` again. No
  compile-time identity tells two live frames apart; the identity has to exist at runtime, which is option 5.
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
  twin-less and legitimately called from plain defs. A side effect is declared as an operation of an `effect`, and a
  native leaf's declaration is axiomatic. The hole the rule aimed at (`def now: Int = currentTimeMillisInternal`) is a
  layer bug with a named fix — an effect such as `Time` — not a compiler check.
- **An anonymous implementation-literal expression** (`Console(printLine = …)`). A named `implement`
  covers every case in the tree, and a literal is a value.
- **Deleting `CarrierKindChecker`.** Measured per arm under v5: `verifyCarrierKinds` is the only thing
  rejecting a `[F[_]]` binder instantiated at a proper type. It is a kind system and stays.
- **Putting the row on `VPi`** (Koka-style). Teaches every unification site, the printer and the `Function`
  native about rows. The row stays declaration metadata beside the signature.
- **A row alias with its own AST node.** An `ast.fact.Expression` case is the most expensive thing this language can
  add. The row alias that exists (§2.4) rides the `EffectfulType` node the parser already has.
- **Discharge markers (`{-E}`).** There is no negative-effect surface; discharge is a frame a discharger
  installs.
- **Scanning the dictionary for an ability or implementation name.** Replaced by the keyed marker lookup,
  and the same closure covers `with`: an implementation name resolves at `resolve` to a `ValueFQN` by keyed
  lookup, and only that FQN flows onward — never a string carried downstream for `AbilityResolver` to search
  by, which would ignore import scope, could not be shadowed, and would be decided by hash order.
- **A third kind of parameter for a function the callee keeps** (`val f: B => C`). Rejected 2026-10-05; D21 then
  admitted `compose` without one, since a kept function is the *first* kind, a pure value. Keeping *code* stays
  forbidden; keeping a frame-bound closure is "not now" entry B.
- **Code parameters written as nested `def` signatures** (`def f(a: A): B`, `def body uses Throw[E]: A`). Dropped
  2026-10-05: the parameter names are never referred to, and a function type with a clause says everything the form
  said.
- **`=> A` for a lazy argument, and a `block`/`impure` keyword** (2026-10-09). A second arrow that is not a type, and
  a second word beside `uses`; `value uses *: A` says it. Also **`A -> B` pure and `A => B` effectful** (Scala 3's
  capture-checking spelling): `->` is a lambda and a type is an expression here, and the unmarked arrow would be the
  effectful one.
- **Effects on function types** (`A => B uses Console`), so a kept function could carry its effects and charge
  whoever calls it later. It is "putting the row on `VPi`" above, and it brings back the closed stored computation —
  the binding fixed where the lambda is written while the effect is charged where it runs (§8 item 12).
- **The `<Ability>Carrier` convention as an explicit declaration.** Its premise — that an effect *has* a
  representation — is gone; the property it named ("has a canonical monad transformer") is exactly what
  §3.4's primitives replace.

**Not now — conveniences deliberately left out (2026-09-08).** Each is additive later and none touches the mechanism:

- **A name for several implementations** (`implement mocks = mockConsole & mockFileSystem`), and **a set of
  abilities required of a binder** (v5's `ability Web[F[_] ~ Console & Log]`). A row *with its payload* has a name —
  the row alias, §2.4 — and the superability closure stays for ordinary binders.
- **`with` on a def's own clause.** Rejected as a second spelling of `with` around the body.
- **A clause spelling for a ground ability default** (`def printAll(xs: List[Int]) uses Ord[Int]: Unit`). `with`
  on an ability reaches a callee only through a `~` constraint on a generic.
- **A user-declared boundary default** (`implement Throw[ConfigError]` handling an undischarged
  `Throw[ConfigError]` at `main`). It would overlap the platform's single `implement[E] Throw[E]`, which
  coherence rejects; the boundary binds the platform layer's defaults only.
- **Compile-time reduction of effectful code under a pure implementation** (a quiet-stall evaluator entry
  with fuel). Possible under the model, wanted by nothing.
- **B — a derived *keeps* property** (2026-10-05, a relaxation of rule 3). The compiler records, from each
  definition's body, which function parameters it keeps — stores, returns, wraps in a kept lambda, or passes to a
  parameter that keeps — bottom-up over the reference graph, which the recursion gate makes acyclic. A call
  passing a lambda that uses effects to a kept parameter is the error at that call. Its variant **frame-free capture**
  admits a kept lambda whose written bindings reach no frame (every platform default, no `Writer`- or `State`-backed
  double), checked after monomorphization — Scala 3's capture checking for a fraction of the machinery, at the cost
  that a program storing `Console` closures cannot bind a `Writer`-backed double to them. Wanted by nothing until a
  platform native or a library genuinely keeps callbacks.
- **Derived mocks** (`with mock[Database]`, EasyMock-style): compile-time reflection over an ability's methods,
  a derived implementation, a derived per-ability answer store and a recorder derivation. A feature in its
  own right, and a consumer for D3 if it is ever wanted.

---

## 13. Provenance, and how to read a citation

**Citations into this document's own history.** Source comments cite decision records that are now condensed. Where
each lives:

| citation | now |
| --- | --- |
| D20 rule 1 | §1 rule 1 |
| D21 rule 5 (D20 rules 1, 5 and 6 unchanged) | §1 rules 1, 5 and 6 |
| D20 rule 2 (reversed by D21 rule 1); D21 rules 1 and 2; D20 rule 4 (widened by D21 rule 2) | §1 rule 2; §3.1 "Code and values" |
| D20 rule 3; D21 rule 3 | §1 rule 3 |
| D21 rule 4 | §1 rule 4; `Scope.enterClosed` in §3.1 |
| D20 rule 5 | §1 rule 5, §2.2 "Who gives"; `checkGiven` in §3.1 |
| D20 rule 6 | §1 rule 6, §2.3 |
| D20/D21 step 1 (code and value), step 2 (rule 5), step 3 (fields), step 4 (the parser), steps 5–6 (migration, the old surface refused), step 7 (documents) | §3.1; §3.1; §2.3; §3.1 and §2; §2; Part I |
| D20c option 5, "the `transcriptOf` spike", (iii) and 2(c), "Option 5 built" | §3.4 |
| D20c (b), "decided (b)" | §3.4 and §2.2; what it means for a listed effect is D22 (§1 rule 5) |
| D22 | §1 rule 5, §2.2; the record in §11's closed decisions |
| D23 | §2's `with`, §3.1, §4; the record in §11's closed decisions |
| D24 | §2's table and the parser's refusals, §2.1; the record in §11's closed decisions |
| D25 | §11's "Decided, not built" until built |
| D20c (the open question), option 4 | §11 D20c |
| §8 items 14–17 in comments and tests written on the option-5 line | §8 items 16–19 |
| §7 item 7 before 2026-10-13 (undischarged control effect at `main`) | closed, §8 item 5 |
| §1's former four rules (before 2026-10-10): rule 1, 2, 3, 4 | rule 1; rule 2 (the clause in place of the row); reversed by rule 6; rules 2 and 3 |

The full text of every condensed record is `git show f78861cf:docs/effects.md`.

**Retired documents.** Ten documents were merged into this one and deleted. Their full text is in git history (`git
log --diff-filter=D -- docs/`), and the table below says what each was and where its subject now lives. **Scaladoc
comments across the compiler cite them by their own section anchors** — those are *historical pointers* ("this code
came out of X §N"), not live references, and they do not index this document.

| retired document | what it was | anchor scheme in comments | where its live content is |
| --- | --- | --- | --- |
| `effects-as-channel.md` (v2, "uniform carriers") | the first shipped design: the carrier as a type argument the *checker solves* | `§0`–`§13`, `finding N`, `U1`/`U4-x` | superseded in direction and in code. What it got right and still holds: the **channel** (§3.3) and rows never flowing into types. What it got wrong is §12's first entry |
| `effects-as-rows.md` (v3) | the previous landed design + its A.1–A.11 record: the elaborator writes the carrier | `§1`–`§9`, `A.x`, `R1`–`R6` | its **user model** is Part I §1–§2 and its **standing rules** are §5; its mechanism (the elaborator, the derivation spec, the whitelist) is superseded by §3. §10's gate method is its A.9.4 |
| `effect-row-tails.md` | pinned rows as the one spelling of a carrier stack | prose only | no live content: there is no stored computation (§2.3) and D4 dissolved |
| `testing-effects.md` | substituting effect implementations | `L1`–`L3`, `§2.x` | §6 — the question is the same and the answer changed: a name, not a carrier |
| `effects-v5-one-carrier.md` | rows as constraints on one carrier — the subtraction from v3 | `§4 step N`, `§5 Q1`–`Q4`, `§7` | §2.1 (step 1) and §2.2 (step 2) survive in meaning; its §7 (an ability requiring abilities) has no spelling (§2.4); step 4 and Q1 are §12 |
| `effects-as-channel-v4.md` | the row leaves the type, the carrier leaves the language | `R1`–`R11`, `P0`–`P5`, `Q1`–`Q4`, `§0`–`§11` | superseded by Part I; its seam finding — specialisation is the mechanism, not a follow-up — is §3.1 |
| `effects-v4-p0-spike.md` | does the `WovenValue` seam know the carrier? | `S1`–`S3` | the seam test (`EffectsSeamGroundnessTest`); the test is permanent |
| `effects-v4-p2-sizing.md` | sizing the flag day | `§1`–`§5` | history |
| `effects-v4-flag-day-readiness.md` | is the flag day ready? (no) | `B1`–`B3` | §12 |
| `effects-syntax-userspace.md` | `~` and `&` as ordinary values | `stage 1`–`stage 4`, `§7.x` | §2.5 (stages 1–2, landed), **D3** (stages 3–4) |

**Citations to Part II's former numbering** (in commits and comments dated before 2026-09-07): *D1*/*B1*–*B4*
were the v4 decision and its blockers (now Part I and §12); *D2* the `<Ability>Carrier` declaration (§12's last
entry); *W1*–*W4* the v5 work items (history); *D8*/*D9*/*D10* the surface spelling, the cell and the purity
annotation (decided: §2, §3.4, §12); *D14*/*D15* (2026-09-07 only) were the slot `with` and the binding's
carrier (decided: §2, §3.1); *D6*, *D11*, *D12* and *D16* (grades, the ground-ability row spelling, the
user boundary default, bundles and effect sets) are §12's "not now" group. Two older citations in the tree —
`docs/effect-lift-in-checker.md` and `docs/effectful-signatures.md` — point at documents retired before these and are
likewise historical.

**Citations to the retired v6 plan.** Scala comments dated 2026-09-07 to 2026-09-10 cite the v6 plan's own anchors:
**§8** (how the plan was run), **§9** (the model — §9.1 in five sentences, §9.2 what was decided and why, §9.3 the
surface, §9.4 the core desugar, §9.5 semantics, §9.6 the three primitives, §9.7 specialisation and purity, §9.8 what
it deleted/kept/added, §9.9 the standing rules re-read) and **§10** (§10.1 the nine pre-flag-day steps, §10.2 the
flag day **F1–F9**, §10.3 the follow-ups **A2–A11**). Those are historical pointers into the document as it stood at
`cb2a9c6d` (`git show cb2a9c6d:docs/effects.md`). Where the live content is: §9.2's decisions are Part I §2, §3.1
and §3.5; §9.3 is §2; §9.4 is §3.1 and §4; §9.5 is §3.5 and §4; §9.6 is §3.4; §9.7 is §3.5 and §12; §9.8 is
history; §10.1's steps and §10.2's F-items name their commits; and of §10.3's A-items, A5 is §3.3, A6 is §2.2, A7
and A11 were the stored computation (deleted, §2.3), A8 and A9 are §7, A3 and A4 are absorbed by §7 and §11, and A2
(a microcontroller target's exit primitive) is still hypothetical and is §3.4's table. Comments dated 2026-09-11 and
later that cite §9 mean the binder mark (§9 now).
