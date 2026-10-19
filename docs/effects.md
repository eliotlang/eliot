# Effects in Eliot

This is the effect system's single document. **Part I** is the design the compiler builds — **D36: effects are
capability passing** — and it is the authority: where code, a stdlib signature, an example or a test disagrees with it,
the artefact is the defect, never silently tolerated. **Part II** is the record: the closed divergences (§8), the handle
record (§9), how a change is run (§10) and the decisions (§11). **Part III** is what must not be re-proposed (§12) and
how to read a citation (§13). A decision changes Part I when it is built; everything superseded lives in git history.

**In one paragraph.** An effect is an ability declared with the `effect` keyword. A definition's `uses` clause says
what its caller hands it, as handles. Before monomorphization one syntax-directed walk does all the plumbing and checks
by name; the checker decides which handle answers each use, by type, on the compile track as on the runtime one. There
is no carrier, no monad, no `Id`, no evidence, no specialisation pass and nothing to infer — a comment or a piece of
code that mentions one is history.

**Status (2026-10-19).** D36 is built, handle erasure included, and released as `v0.18`, which eliot-test, eliot-build
and the docs site depend on. §8 has no open item. What is left:

- **Undecided:** naming a set of effects in a clause (D20a), storing an effectful computation (D20c), `~`/`&` in user
  space (D3) — §11.
- **Loud, at compile time:** a function-typed code parameter's own entries are never handed to it (§7 item 1); a
  discharger whose holes disagree spells its type arguments (§7 item 2).
- **Silent or latent:** none known.
- **Outside this document:** the site's generated `apidoc/` pages predate effects v6; eliot-test's own notes still say
  a row cannot be closed.

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
   `choose(readLine, readLine)` runs both reads and `Box(shout)` runs `shout` and stores its value. An operation, or a
   call to a definition that `uses` an effect, takes the nearest handle *in the text* whose entry answers it — an
   enclosing `with`, a block's handles, the enclosing definition's own clause (§3.3's hole rule, stated as scope). The
   one argument not run where it stands is code (rule 2), and the one refinement is rule 5's: an effect a code slot
   **lists** is answered where the callee runs the code, by the handle the callee hands the block there, which is what
   lets the callee catch it. An effect the code uses through `*` unlisted is answered where it was written, as a
   closure captures.

2. **A parameter is code iff it has a `uses` clause, and a value is pure.** `whenTrue uses *: A` receives the caller's
   text unrun. After desugaring a nullary code slot is a **block** (`Unit => A`, or one taking a handle per entry it
   lists), but the block is the lowering, not the surface: every phase goes by the clause recorded on the declaration
   (`EffectRow.parameterEffects`, `callbackEffects`), never by the shape. Every other parameter is a value, and a
   **plain generic is a payload, always**. A lambda in any value position — an unmarked function parameter, a field, a
   generic slot, a result, a `val` — may use no effect it does not discharge itself: it may bind and discharge locally
   (`x -> runAbort(lookup(x))`), and it reaches no enclosing handle, so an effect taken from outside is *"This uses the
   effect 'X' inside a function written where a value is expected"* at that use. An ordinary ability (a `~`
   constraint) is not an effect and stays reachable. An unapplied reference to a `uses` definition is not a value
   either: given fewer arguments than it declares, it is *"'printLine' performs the effect 'Console' when it is called,
   so unapplied it is not a value …"* in a value position, exactly as `s -> printLine(s)` there is, while at a code
   parameter it is the caller's code (`applyTwice(printLine)`).

3. **Code is used, never kept.** A reference to a code parameter is the head of an application, or the direct argument
   of another code parameter (or stands inside a lambda so placed), and nothing else; anywhere else it is *"'f' is code
   its caller wrote: it can be called or passed on, not kept"*, and a value lambda cannot capture it. Nullary code runs
   when it is mentioned, so it can never be kept, but it can be captured. A pure function value may stand where code is
   expected; code may not stand where a value is expected. The reason is frames, not purity: code run after its
   discharger returned holds a handle whose frame is dead, and since the design keys on no family of effects, one rule
   covers the interpretation effects too — handles are second-class. Swift's non-escaping closure parameters and
   Kotlin's inline-function parameters draw the same line.

4. **A closed clause lists and closes.** A clause without `*` — `body uses Throw[E]: A` — admits the argument's text to
   what the slot lists (§2.2) and what it discharges itself, and to nothing from around it: *"This uses the effect 'X'
   inside an argument whose `uses` clause is closed"*, and the caller's own code passed there *"… cannot run it"*.

5. **An effect a code parameter lists is the definition's to handle or to declare** (D22). Running a code parameter is
   checked like a call to a definition with that signature. Each entry its clause lists the definition either
   **handles** where the code runs — passing the code on to a slot that discharges it (`catch` hands `computation` to
   `runThrow`), or a `with` around the code's runs — or **declares** in its own clause, passing it on to its caller;
   doing neither is *"'body' may perform the effect 'X' here, which this definition neither handles nor declares"*.
   Declaring does not prevent handling: `describedAs` declares `Throw[AssertionError]` because it raises one itself,
   and still catches its body's failure. An effect the code uses through `*` unlisted is invisible to the definition
   (§2.2). **An effect has no default** (D26): every implementation of one is passed as a handle — installed by a
   `with`, handed to a block by the callee running it, or received from the caller. A platform's discharger binds its
   control effect's named implementation with a `with` over the frame its primitive made (§3.4), and the synthesized
   entry point is an ordinary definition binding each of `main`'s entries with one `with`, naming the platform's
   `standard…` implementation (`app::main with eliot.effect.Console::standardConsole`). Nothing is exempt, the entry
   point included.

6. **A field holds a value.** A `data` field takes no clause and no row, and there is no stored computation: store data
   describing the work, and perform it where the effects are in scope (§2.3).

**What the user sees.** **Purity is the absence of one word** on the definition's own clause. No `uses` before the
result type: a call is pure, and total, since `Inf` is an entry like any other — whatever its arguments are, since a
parameter with a clause forces one there (D24). `uses *` alone: the definition adds no effect, and a call performs
exactly what its caller wrote in the code — `foreach`, `catch`, `orElse`, `.`. Entries in the clause: it performs
those, received from its caller. So `xs.foreach(x -> printLine(x))` inside a `uses Console` definition needs no
declaration on `foreach`: the lambda is the caller's code, the `Console` is the caller's, and the collections library
stays effect-oblivious. Every diagnostic speaks the clause's vocabulary ("add it to its `uses` clause").

## 2. The surface

**One clause in two positions, and three constructs.** The `uses` clause stands where a parameter's type would, before
the colon, on a definition and on a parameter alike — `name [uses …]: Type` — so no effect is written inside a type. A
`where` precondition stays after the result type.

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
| `uses E, F` | a definition | what its caller hands it | one handle type parameter and one handle parameter per entry (§3.1) |
| `uses *, E` | a definition | the same, and it runs code a parameter's `uses *` takes, so a call performs what that code does (D24) | nothing for `*`: a marker the parser checks and drops |
| `uses *` | a parameter | the caller's code, open to the effects in scope where it is written | `Unit => A`, or its function type |
| `uses *, E` | a parameter | the same, plus `E`, which the callee handles or declares — a handler's slot | a block taking one handle per listed entry, typed by what the callee's body hands it |
| `uses E` | a parameter | code that may use `E` and nothing from around it — a **closed** clause | the same, capturing nothing |

**A clause lists effects only** (D28). An ability is a `~` constraint on a type parameter, filled by the caller or
answered by its default, and is never handed down a call chain: `def f uses Compare[Int]: Bool` and `body uses *,
Compare[Person]: A` are both *"'Compare' is an ability, and a `uses` clause lists effects only"*, at the entry. Nor is
an ability ever bound with `with` (D29): it has no named implementation.

**`*`** comes first and at most once. On a parameter it opens the argument to the caller's text; on a definition it is
written **exactly when** some parameter's clause has one, and says a call performs what the caller's code there does,
so the result side of a signature is complete without reading the parameters (D24). It is not "any effects" and not a
row variable: it names *the effects in scope where the argument is written*, rule 1's lexical capture, so nothing is
unified. A code parameter's type is read as the function the callee calls it at: one with no arrow takes nothing and
runs when mentioned; for one with an arrow the clause belongs to its final codomain as written — `f uses *: String =>
(String => Unit)` is code of one argument handing back a function value. Code that should *produce* a function is
`g uses *: Unit => F`, applied by the callee.

**The parser refuses the rest**: a definition with a `uses *` parameter whose own clause does not start with `*`
(*"Expected `uses *` before the colon, since a parameter runs code its caller wrote and the call performs what that
code does"*), `*` on a definition with no such parameter (a closed clause is not one), `*` anywhere but first, an
entry-less clause, `uses` on a `data` field, `with` after a definition's clause, and `with` on a parameter or a field.
A row written in braces in any type — a return type, a parameter, an arrow codomain, the empty `{}` — is *"Expected a
type, with its effects in a `uses` clause before the colon (`def f uses Console: Unit`, `body uses *, Throw[E]: A`), but
encountered symbol '{'"*, and the pinned form `{E | G} A` does not parse at all. The one place braces still parse is a
row alias's body (§2.4).

**An `effect`** is declared with its own keyword. **A member's clause lists what it performs *beyond* the effect it
belongs to** — membership already says the member needs that handle, exactly as `show` inside `ability Show[T]` does not
repeat `~ Show[T]`. A function that needs no handle of the effect is not a member: it is an ordinary def beside the
block, as `updateState`, `orRaise` and `orAbort` are beside the primitives `state`, `putState` and `raise`.

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

**An anonymous `implement` is an ability's; an effect's is named.** An anonymous block in one of the **two sites** —
the ability's module or the type's module — is the implementation for its pattern, subject to coherence and `where`,
and is what every reference to the **ability** binds (`Show[Int]`, `Eq[Path]`). It takes no parameters, since nothing
that finds it has arguments to give: a parameter list there is *"Expected `{`: an implementation with parameters is
named, and a `with` supplies them …"* (D25). An anonymous `implement` of an **effect** is *"An implementation of the
effect 'Console' must be named, since an effect has no default"* (D26). A named `implement` of an **ability** is the
mirror error, *"The implementation 'byKey' of the ability 'Compare' cannot be named: an ability's implementation is
found by its type, and never bound with `with`"* (D29): behaviour chosen per call is a value parameter
(`people.sortBy(p -> p.age)`).

A **named** `implement` of an effect may live anywhere, is never searched and is not checked for overlap. Its clauses
may carry `uses`: an entry a clause only repeats from its member (`standardFileSystem.readFile`'s `Throw[IoError]`,
which `FileSystem.readFile` declares) is the **call's**, handed at each operation; any other is **captured** where the
implementation is bound — the handle in scope at the `with` (§3.1). It may take **parameters**, written between its
name and its effect (`implement scripted(answer: String): Terminal`) and supplied by the `with` that binds it (`greet
with scripted("Bob")`): they are its handle's fields, values, so one specialisation serves every argument. The
arguments are typed by the entry the `with`'s handle answers, whether the code spells it or the checker reads it off
the call: in `pick(1) with mapping(x -> show(x))`, for `implement[T] mapping(f: T => String): Source[T]`, `x` is an
`Int` because `1` is. Its **type parameters** are its pattern's: one no field fixes is solved at each use, so `with
showing`, for `implement[T ~ Show[T]] showing: Source[T]`, answers a `Source[Int]` and a `Source[String]` under one
`with`, while a handle whose entry does not answer a use is *"No handle in scope here answers 'Source[Int]'. In scope:
…"* there (`pick(1) with none` for `implement[T] none: Source[Option[T]]`). A type parameter the pattern does not take
is *"The type parameter 'K' of the implementation 'keyed' is not taken by its pattern, so nothing where it runs
determines it"*, at the declaration (D29).

```eliot
implement standardConsole: Console                        -- the base: body-less, beside the effect
implement standardConsole: Console {                      -- the jvm layer: the same name, bodied (the layer merge)
   def printLine(s: String): Unit = printLineInternal(s)
   def readLine: Option[String] = lineOrNone(readLineInternal)
}

implement scripted(answer: String): Terminal {           -- a test's, with a parameter
   def write(line: String) uses Writer[String]: Unit = tell(line ++ ";")
   def read: String = answer
}
```

**`with` installs an implementation around its subject** (D23): infix, subject-first, at the loosest precedence,
left-associative, so `xs.map(greet).joined with session` applies to the whole chain and `c with a with b` is `(c with
a) with b`, the inner shadowing the outer. Its handle is in scope for every hole in its subject; a `with` no hole takes
is an error, never a no-op. **It is an expression in a body and nothing else**: on a parameter or a field it is *"… no
`with` on a parameter, since code's effects are bound where it runs, by a `with` around the run in the body (`body with
fake`)"*, and after a definition's own clause it would be a second spelling of a `with` around the body. A callee
handles a listed effect of its caller's code by a `with` **around the run**:

```eliot
def greetTranscript: String = runWriterToLog(greet with session)
def transcriptOf(program uses *, Console: Unit) uses *: String = runWriterToLog(program with recordingConsole)
def answering(name: String, body uses *, Terminal: Unit) uses *: String = runWriterToLog(body with scripted(name))
```

`transcriptOf`'s caller writes nothing: its block is handed `recordingConsole`'s handle for the `Console` the slot
lists (§3.3). A `with` around a run handles its entry even when the definition also declares that effect. It binds only
what the slot **lists**: an effect the code uses through `*` was answered where it was written, and a `with` for it is
*"This `with` binds nothing: 'program' is code its caller wrote, and its use of 'X' was bound where it was written"*.
Every run of one parameter must be handed the same implementation (*"This run of the code is handed another
implementation than another run of it"*, §3.3).

**A `with`'s arguments are values computed where it is written**, outside its binding: in `greet with
scripted(answer) with scripted("outer")` the inner `answer` reads from `scripted("outer")`. Around a run they are
computed at the run, so a definition can bind an implementation its own parameters configure (`answering` above). A
`with` must supply exactly the parameters its implementation takes.

**`with` is written almost nowhere.** A def declaring `uses Console` receives its handle from its caller, up to `main`,
which the platform runs with a `with` per entry. That chain is what makes a fake possible — `greet` never said which
console, so a test may. A `with` in production code is the same mistake as a hard-coded dependency.

### 2.1 `uses *` — the caller's code

`uses *` alone says *"the caller's code, and I add nothing to it"*, and the definition repeats it in its own clause
(D24). It is the spelling of every effect-transparent code slot — `fold`'s arms, `orElse`'s fallback, `catch`'s
handler, `.`'s `f`, `foldLeft`'s `combine`, `forever`'s `step`. An operation inside such an argument is answered by
whatever the *caller* has in scope, and the argument is not run until the callee runs it.

```eliot
def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A) uses *: A { join(whenTrue, whenFalse) }
def foldLeft[A, B](initial: B, combine uses *: A => B => B, list: List[A]) uses *: B
infix left below apply def .[A, B](a: A, f uses *: A => B) uses *: B = f(a)
```

**Which a function parameter is, is the base's decision, per signature.** `updateState`'s `f: S => S` and
`foldLines`' `step: B => String => B` are pure values; `map`'s `f uses *: A => B` and `foldLeft`'s `combine` take the
caller's code, so `names.map(n -> setting(n))` inside a `uses Abort` definition aborts that definition. Making every
function parameter pure would need a `map` and a `mapM`; making every one code would leave purity unsayable.

### 2.2 A slot's listed entries — handle or declare (D22)

Listing an effect says *"I run this code with this effect in hand"*. The definition then either

- **declares it** in its own clause, passing it on to its caller: `if`'s `value uses *, Abort: T` under `if`'s own
  `uses Abort`, whose body discharges nothing, so a run of `value` is handed `if`'s received `Abort` handle and an
  `abort` in `value` reaches `if`'s caller; or
- **handles it** where the code runs — a `with` around the run, or passing the code on to a discharger — so the run is
  handed a nearer handle the definition made.

Which handle a run hands is the hole rule's (§3.3); a block gets whatever its callee hands it, and nothing is named in
a slot.

```eliot
def if[T](condition: Bool, value uses *, Abort: T) uses *, Abort: T                  -- declares Abort: passed on
def else[A](computation uses *, Abort: A, fallback uses *: A) uses *: A              -- handles Abort here
def catch[E, A](computation uses *, Throw[E]: A, onError uses *: E => A) uses *: A   -- handles Throw[E]
def runStateToPair[S, A](initial: S, p uses *, State[S]: A) uses *: Pair[A, S]       -- handles State[S]
```

Read as English they are already right, and that is the whole of what a discharger is. Only a **nullary** code
parameter's clause is handled this way; a function-typed one's is the callback's own scope (`onError uses *: E => A`),
bound where the callback is written (§7 item 1).

A definition with a body handles only with what it has: `else` hands `computation` to `runAbort`, `catch` to
`runThrow`, and a `with` around the run installs a chosen implementation; the platform's `runThrow` binds `escaping`
with a `with` around its run (§3.4). So a slot cannot launder an interpretation effect into a pure signature. The
effects a `with`'s implementation captures are answered **where the `with` stands**: `transcriptOf`'s `program with
recordingConsole` captures the `Writer[String]` handle `runWriterToLog` hands it. Entries compare whole, after
evaluation: eliot-test's `mocked` declares `Throw[AssertionError]` and lists `Throw[IoError]` too, and runs its body
under `with mockFileSystem` inside its own `catch[IoError, Unit]`, which hands the body its `Throw[IoError]` handle, while
the body's `Throw[AssertionError]` is handed `mocked`'s own received handle.

A listed entry's **type arguments** are the checker's, read off what the code does: `raise(1) catch (n -> …)` is handed
`catch`'s block handle for `Throw[E]`, and the `raise`'s hole solves `E := Int`. Where the code's holes would solve one
differently — a `raise(1)` and a `raise("one")` under one `catch` — or nothing solves it, it is *"Which handle answers
'Throw[Int]' depends on type arguments nothing here decides. Spell the type arguments of the call around it."*, and the
call spells them (`catch[Int, String](…)`, §7 item 2). A slot's entry is a permission, not an obligation.

**Handling and declaring combine; an unlisted effect is out of reach.** `describedAs(body uses *,
Throw[AssertionError]: Unit, newMessage: String) uses Throw[AssertionError]: Unit` catches its body's failure and
re-raises a better one, declaring the effect because it raises it; a `guarded(body uses Throw[String]: Unit) uses
Console: Unit` running `body` inside a private `catch[String, …]` catches its caller's `raise`, which its signature says
by listing `Throw[String]` and not declaring it. An effect the code uses through `*` and the slot does not list was bound
where the code was written: `guarded(body uses *: Unit)` can neither catch a `Throw` its caller's code performs nor needs
to declare it. A pass-through the definition cannot touch is spelled by not listing.

### 2.3 A field holds a value

A `data` field takes no clause and no row (rule 6). A brace in a field's type is *"Expected a value type, since a data
field holds a value and not a computation (store data describing the work, and perform it where the effects are in
scope)"*, and the parser stops a field's binder at `uses`. A function-typed field is a pure function value, stored,
passed and returned like any other (`data Handler(name: String, f: Event => Response)`).

**An effectful computation has no stored form**, and the reason is frames, not purity. `Throw`, `Abort`, `State`,
`Writer` and `Dep` are frames a discharger installs, and frame dependence is a property of the *implementation* — a
test's `recordingConsole` is written over `Writer`, so a stored closure bound to it is a stored `tell` with the same
escape risk. A program keeps **data describing the work** instead, performed where the effects are in scope:

```eliot
data Step = Skip | Print(line: String)

def perform(step: Step) uses Console: Unit = step match {
   case Skip -> unit
   case Print(line) -> printLine(line)
}
```

It is testable with a double, and on a microcontroller it is what is wanted anyway: a fixed-size record, not a heap
closure. A native that keeps a callback — an event loop's handler table — stays the platform's axiom. Storing a
computation whose effects are handled *where it runs* is the open D20c; frame-free capture is §12's "not now" entry B.

### 2.4 Naming a row

A **row alias** is a type alias whose body is a row, naming the row together with its payload. It has no `uses` form
yet, so its body is the one place the brace spelling parses, as the whole body only (`Expression.rowAliasBodyParser`):

```eliot
type Talking[A] = {Console} A

def greet(name: String): Talking[Unit] = printLine("Hello, " ++ name ++ "!")
def announce(name: String) uses Log: Talking[Unit] = …
```

The alias is an **ordinary type alias** and a use of it an **ordinary application**: `Talking` lowers to `type
Talking[A] = A` and `Talking[Unit]` stays in the signature for the evaluator. What crosses the use site is the row's
**entries**, with the use's arguments substituted: the definition naming the alias mints one handle per entry, exactly
as if they were written in its clause. The alias *declares* its row on its own declaration (`EffectSugarDesugarer`
records it and mints nothing); `ValueResolver` reads the declaration the name resolved to, resolving the entries in the
alias's own scope before substituting — the same reading `superConstraints` makes of an ability's own `~` constraints.
So import scope, shadowing, privacy and qualification are the ordinary ones.

A clause and a named row **compose**, since the alias contributes to the return *position*: `def announce(n: String)
uses Log: Talking[Unit]` declares both, and an effect named twice is declared once. That is how eliot-test's `type Test
= {Writer[List[TestResult]]} Unit` is widened where a suite's cases perform for real: `def testCases uses Console:
Test`. It works in **return position only**, every other position being an error; a set named *in* a clause (`uses
Git`) is **D20a**.

A set of abilities required *of a binder* (v5's `ability Web[F[_] ~ Console & Log]`) has no spelling, and neither has a
name for a set of implementations (§12, "not now"). What survives is the ordinary **superability closure**: `~ A` is
closed under what `A` itself requires of the parameter this use bound to this binder (`ValueResolver.superConstraints`,
transitive and idempotent).

### 2.5 `~` and `&`

`&` is a standard-library name: `infix left type &[A, B]` in `eliot.lang.Ability` (prelude, so ambient). The parser
accepts *any* operator between two `~` constraints and `ValueResolver.resolveCombinator` looks the name up in the
ordinary dictionary, requiring `WellKnownTypes.abilityCombinatorFQN` — so a module declaring its own `&` takes the name
back and gets a diagnostic. The combinator rides ast→core on the unresolved constraint's `combinedBy` purely to reach
that check and is dropped there.

An ability name resolves like every other: `ValueResolverScope.getAbility` is a keyed lookup of the ability's
**marker** (`QualifiedName(n, Qualifier.Ability(n))`), with the same `privateNames` fallback a value name gets. **Do not
reintroduce the scan**, and do not add a second lookup path for ability names. `~` stays reserved — a binder marker like
`:`, not a name; taking it into user space is **D3**.

Constraints travel in two generic types, one per resolution state: `ast/fact/UnresolvedAbilityConstraint[E]` (ast,
core) and `resolve/fact/AbilityConstraint[E]` (resolve through operators). A `uses` entry is parsed as the same
`UnresolvedAbilityConstraint`, which is why an entry and a `~` constraint resolve alike.

### 2.6 What deliberately has no spelling

- **A stored effectful computation** (§2.3), and **a native that keeps a callback** — the platform's to declare.
- **`with` after a definition's own clause, or on a parameter or a field**: an implementation is installed in one
  place, around a run or a subject in a body.
- **An effect variable** (`f uses E: A => B` with `uses E` on the callee, Koka's spelling) and **effects on a function
  type** (`A => B uses Console`). The first needs row unification, the closed inference class, and is unnecessary:
  code is resolved lexically and never kept, so every code argument of one call is written in one scope and `*` is the
  one implicit variable there ever is. The second is the row on `VPi` (§12).
- **A row alias with its own AST node**, and a bare set of abilities with no payload (`type Web = {Console, Log}`).
- **A name for a set of implementations**, and a `with` binding several at once (§12, "not now").
- **A negative effect.** Discharge is a frame, so a discharged entry simply never joins the clause.
- **Inference of a binding, of any kind** (§5 rule 3).

## 3. The mechanism

**An effect is capability passing** (D36): dependency injection, checked statically and scoped lexically, with
second-class handles. An implementation is a component; its `with` arguments are its configuration; an implementation
whose clauses use other effects is constructor injection; a `uses` entry declares a dependency; a `with` provides one
for a scope; `main`'s generated `with standard…` is the composition root. Three things make it more than a DI
container, and they are the user's promise: **handles are second-class** (rules 2–3; Effekt's *effects as
capabilities* is the nearest prior art), **propagation is checked and complete** up to `main`, and **absence means
something** — purity is the absence of `uses`, totality the absence of `Inf`. Before monomorphization the desugar does
all the plumbing and decides nothing; the one decision — which handle answers a use — is the checker's, by type, after
evaluation (§3.3). An ability is searched globally, like a type class; an effect is passed lexically, like a dependency.

### 3.1 The desugar: an effect is an ability over a handle type

**The parser writes a clause onto the row node the rest of the compiler always read** (`Expression.EffectfulType`): a
definition's onto its return type; a parameter's onto the slot's type, or for a function type onto its final codomain
as written. So `body uses *, Mocking: Unit` reaches `core` as the slot `body: {Mocking} Unit`, and no phase past `ast`
learned the clause — except one bit the row node cannot carry, **closed**, which rides `ArgumentDefinition.closedRow`
into `ParameterEffects.closed` and `CallbackEffects.closed`.

**An `effect` is an ability over a handle type** (`ast/fact/AbilityMembers`): one ability-level binder, the handle's
type, and every operation takes the handle as its first value parameter.

```
effect Console { def printLine(line: String): Unit }
  ⟶ ability Console[Impl] { def printLine(Impl$handle: Impl, line: String): Unit }
```

**A named implementation is a `data` and an ordinary instance of that ability** (`core/…/NamedImplementationDesugarer`).
Its parameters are the data's fields (D25), so a parameterless one is a field-less `data`, and every clause takes its
own handle first and reads the fields off it:

```
implement scripted(answer: String): Console { def printLine(line: String): Unit = … answer … }
  ⟶ data Scripted$Handle(scripted$answer: String)
    implement Console[Scripted$Handle] {
       def printLine(self$handle: Scripted$Handle, line: String): Unit = { val answer = scripted$answer(self$handle); … }
    }
```

Each implementation has a type of its own, so its instance is the only one at its pattern and colocated by
construction: coherence holds with nothing to check. The **code** is chosen statically by the handle's type, so a
polymorphic operation (`raise[A]`, `foldLines[B]`) is an ability method like any other and no record of operations
exists; only the **environment** — the fields — is a value.

A clause entry its member spells too, under the implementation's pattern, is the **call's**: a handle parameter of the
instance method, filled at each operation's call. Any other is **captured**: a field of a type of its own
(`Capture$j`), filled at the `with` with the handle in scope there (`recordingConsole.printLine`'s `Writer[String]`).
Which is which needs the member's declaration, in another file, so the desugar gives the handle a field for every entry
any clause writes, records them on a `$captures` declaration, and the `row` phase decides (`row/Captures`); a field
nothing captures holds `unit`, which erasure drops. A method's own handle parameters are nearer than the fields, so a
misread entry is never chosen over the call's and shows as a capture no hole takes. A type parameter no field and no
captured entry mentions is left out of the handle and is the pattern's alone, solved at each use (one `with showing`
serves `Source[Int]` and `Source[String]`); one a field fixes is one per handle. Only a layer that **bodies** the
implementation mints its handle `data` and instance — the base's body-less `implement standardConsole: Console` declares
only the name a `with` resolves to — and every named implementation keeps its name marker (`QualifiedName(name,
Qualifier.Implementation(name))`, what `with name` looks up).

**A `uses` entry is a handle parameter** (`core/…/EffectSugarDesugarer`): one binder of kind `Type` — the handle's type
— and one value parameter per entry, after the handles the definition already takes (an effect member's own) and before
its own parameters. A `~` constraint mints nothing: an ability declares only its own parameters.

```
def greet(name: String) uses Console: Unit   ⟶  def greet[Impl ~ Console[Impl]](Impl$handle: Impl, name: String): Unit
def sort[T ~ Ord[T]](xs: List[T])            ⟶  def sort[T ~ Ord[T]](xs: List[T])      -- unchanged
computation uses *, Throw[E]: A              ⟶  computation: Handed => A        -- one handed binder per listed entry
action uses *: A => Unit                     ⟶  action: A => Unit               -- EffectRow.callbackEffects, arity 1
body uses *: A                               ⟶  body: Unit => A                 -- a thunk: it lists nothing
```

**A code parameter listing entries is a block taking one handle per entry**, each of a type its callee's body hands it:
a **handed** binder (`Implementation::Handed`), placed after every other binder so that a key without it is a prefix. A
row alias (§2.4) adds nothing of its own: `ValueResolver` hands a definition naming one as its return type the alias's
entries, as handle parameters exactly as a clause's.

**Every handle is recorded in its declaration** — `EffectRow.handles` (§9). The binder itself is an ordinary binder of
kind `Type`. A clause entry, a handle and its binder are declaration metadata and a value parameter: no row ever flows
into a type, so `unify` sees `Function[A, B]` on both sides of a code parameter and a value alike.

**A `with` carries its arguments.** `greet with scripted("Bob")` reaches the `row` phase as one node,
`WithBinding(subject, marker, arguments)`, through resolution, match desugaring and operators, each treating the
arguments as ordinary expressions outside the subject. The `row` phase turns it into the handle (§3.2): the arguments
are the handle constructor's, so they are typed as any call's. How many the implementation takes is read off that
constructor — its fields less its capture fields — and a wrong count is refused at the `with`.

### 3.2 The walk: holes, handles, blocks and the second-class rule

**`row/BindingWriter`**, run by `RowElaborationProcessor` between the recursion gate and saturation, over a
definition's body **and** signature, rewrites every use into plumbing and checks what can be checked by name. Nothing
it writes decides which implementation runs.

- **A hole at every handle position.** A call of an operation or of a `uses` definition gets one `HandleHole` per handle
  parameter the callee takes, carrying the entry it asks for (with what the call determines substituted) and its
  lexical **candidates**, nearest first, grouped by the binder that put them in scope — the definition's own handle
  parameters, a block's parameters, each `with`'s handle, and what a block captured from where it was written. The
  checker fills it (§3.3). A type argument the call leaves to the checker is a keyed hole (`CheckState.sharedHoles`),
  one metavariable wherever the key appears, so an entry and the type it is written from stay one type.
- **A `with` binds a handle**: the node becomes a `let` of the implementation's handle around the subject,
  `($with1 -> greet(?Console{$with1 …}, "Bob"))(Scripted$Handle("Bob"))`; its arguments are computed outside, and each
  captured field is a hole of its own, answered **at the `with`**. A `with` no hole in its subject could take is *"This
  `with` binds nothing …"*; one whose implementation no layer on the path bodies is *"The implementation 'x' has no body
  here"*.
- **A run hands its block handles.** A reference to one of this definition's code parameters that lists entries is
  applied to one hole per listed entry, filled from this definition's scope where the run stands; one listing nothing is
  applied to `unit`. An actual at a listing slot is wrapped in a lambda taking one handle per entry (`$block…`), all
  equally near; a nullary actual at a `uses *` slot in one taking `unit`. Wrap and apply are inverse, so a pass-through
  (`runAbort(computation)`) is an η-expansion, with no inspection of the argument's shape. What a block uses through `*`
  unlisted it captures from where it was written; a closed slot's block captures nothing.
- **An implementation's clause is shaped as its member** (`row/Captures`): after its own handle, the handles of the
  entries its member declares, in the member's order, then its own parameters, with each captured entry bound from its
  handle's field around its body.
- **The second-class rule** (rules 2–3): a lambda is code only as the argument of a code parameter (up to that
  parameter's arity), the head of an application, or an arm of a lowered `match`; anywhere else it is a value, walked in
  `Scope.enterValue`, where every handle from outside is out of reach, and a code parameter referenced inside is the
  rule-3 error. An argument at a closed slot is walked in `Scope.enterClosed`. A handle, or a closure capturing one,
  therefore stands only where code may — a run, a slot, a `with`'s subject — never in a field, a result or a value
  lambda; a handle's own fields may hold handles, since the holder is itself second-class.
- **The scope check's by-name half** (§4).
- **A signature's guard.** A guarded return (`def head[COND: Bool]: if(COND, String[]) else raise("empty")`) is
  compile-time code in type position, outside the value and closed cuts. A `raise` or `abort` nothing in it discharges
  is read inside the compile track's discharger: the return is rewritten `runThrow(if(COND, String[]) else
  raise("empty"))`, and an uncovered `abort` as `… else raise("A type guard rejected this use.")` inside it, so the
  guarded-return read gets one verdict shape, `Right(t)` or `Left(message)` (`WellKnownTypes.guardDischargers`).

**Effect-ness is read from one place only**: the callee's declaration. An ability in `effectRow.returnEffects` is an
effect at this reference — for an `effect`'s member what membership recorded, for an ordinary definition its `uses`
clause. A `~` constraint's ability is in no row and has no handle: the walk writes nothing for it, and the two-site
search answers it. Nothing keys on a name or a shape.

Two mechanical invariants, both learned from real bugs:

- **Position fidelity.** The walk returns the **original** nodes when nothing changed. Rebuilding an equal spine
  re-attributes it to per-argument positions, silently moving every diagnostic anchored at a call and duplicating LSP
  hover hints.
- **The universe is built by demand, not guessed.** `RowChecker.Universe.onMiss` reports every name consulted but
  absent, and `onMissClauses` every implementation whose clauses were asked for; the processor fetches exactly those and
  repeats until a round misses nothing new. A probe of a name only a bodying layer mints (a handle `data`,
  `$captures`) asks the module's names first, so its absence is an answer, not a "Could not find".

**What the walk may consult**: declarations, and nothing else — the callee's declared parameter and return types, its
declared row and which of its parameters are code (with their arity and whether closed), its membership in an
`effect`/`ability` block, the implementations a `with` resolved to with their clauses and the members those implement,
and one level of type-alias expansion inside those signatures. A rule that inspects a *sibling argument's expression
shape* is inference, not desugaring, and is prohibited; a decision that cannot be made from a declaration is the
checker's (§3.3) or a gap to close **in the declarations**.

### 3.3 The hole rule — the checker's one effect rule

**A use takes the nearest handle in lexical scope whose entry answers it** (D36 point 4,
`monomorphize/check/HandleHoleResolver`, a pass in the post-drain fixpoint beside ability resolution). A handle
**answers** an entry when the two are definitionally equal after evaluation — one comparison, by the one evaluator, so
`Throw[Message]` under `type Message = String` answers `Throw[String]`, and `Writer[List[T]]` at `T := Box` answers
`Writer[List[Box]]`. A candidate is tried by unifying entries and discarding the state when it fails.

- **None answers**: *"No handle in scope here answers 'Throw[Missing]'. In scope: Throw[Refused]."*
- **Two at the same distance both answer**: an error, with no syntax to choose between them (D36 points 4 and 10) — the
  fix is a distinct type; two identically spelled entries in one clause are an error at the declaration.
- **A candidate whose entry still holds an unsolved metavariable** after the drain (`catch`'s `E` when nothing else
  decided it) answers only if every hole it could answer would solve the metavariable the same way, and then that
  solves it; otherwise it is *"Which handle answers 'Source[Int]' depends on type arguments nothing here decides. Spell
  the type arguments of the call around it."* — decided over all the holes at once, so no outcome depends on visiting
  order.

So `fetch`'s `raise(GitError(…))` takes its `Throw[GitError]` handle beside a `Throw[IoError]`; `raise(none)` under one
`Throw[Option[Int]]` takes it and solves `A := Int`; `raise(1) catch (n -> …)` inside a definition declaring `Throw[Int]`
takes `catch`'s block handle, nearer than the definition's own, and solves `E := Int`; and a `catch[IoError, …]` around
`raise(GitError(…))` does not answer, so the raise reaches the outer `Throw[GitError]`. This is lexical lookup by type:
no metavariable of its own, no join, no lattice, nothing inferred about a binding (§5 rule 3).

**An operation is then an ordinary ability method**, dispatched by `check/AbilityResolver` by its handle's type to the
instance of that handle's `data`; the method's type arguments are its instance's, then the call's method-level ones, its
handles' types among them (`PostDrainQuoter.forwarded`). Monomorphization clones by the handle's type in the ordinary
key, and the checker produces the `MonomorphicValue` everything downstream reads.

**A block's handle types are read off its callee** (D36 point 6, `check/HandedBinders`). In the callee each listed
entry's handed binder is a type to solve: a run hands the block a handle — a hole, filled from the callee's scope — and
that decides the type, as an initializer gives a `val` its type. Every run, and every pass of the code to another
callee's slot, must agree: *"This run of the code is handed another implementation than another run of it: every run of
one code parameter must be handed the same."* The callee's check at the rest of its key solves its handed binders, and
its `MonomorphicValue.handed` (`CompilerMonomorphicValue.handed` on the compile track) records them; a caller reads them
at the callee's key once its other type arguments are ground, in the same fixpoint (`readHanded`). So `runThrow` hands
`obj` an `Escaping$Handle[E]`, `if` hands `value` its own received `Abort` handle, and `mocked` hands `body` a
`MockConsole$Handle[…]` no signature could spell. The block is not in the callee's key: the callee is not cloned per
block, and the block is not copied per variant. No recursion means no cycle. A bodied callee that never runs a code
parameter leaves its handed type unsolved: *"This value never runs the code whose handle 'Handed' its clause lists, so
the handle has no type."* In a session with no platform the callee has no body to read; the block's handle type stays
abstract, and nothing is dispatched.

### 3.4 The three primitives, frames and the compile track

A resuming clause is a call and needs nothing. A finishing clause is a **non-local exit**; a stateful implementation
needs a value **threaded through calls that never mention it**. Neither is expressible in a strict pure core, so they
are three **platform-private leaves**, one per target, and nothing else:

| primitive | shape | jvm | microcontroller (hypothetical) | compile track |
| --- | --- | --- | --- | --- |
| escape | `escapeInternal[K, A, R](body uses *: ThrowFrame[K] => A, onExit uses *: K => R, onValue uses *: A => R): R` — abortive, never re-entered | a fresh frame object handed to `body`; `exitInternal(frame, err)` leaves through it by identity, any other frame's exit passes through | a jump buffer (`setjmp`/`longjmp`), or a status flag and a jump | an evaluator intrinsic, `escapeIntrinsic`/`exitIntrinsic`, the same shape |
| cell | `withCellInternal[S, A, R](initial: S, body uses *: StateCell[S] => A, combine uses *: A => S => R): R` — scoped to one call | a one-slot array made per call and handed to `body` | a slot in the primitive's own stack frame | none: no compile-track effect is stateful (a `State` or `Writer` discharge is stuck there) |
| loop | `foreverInternal` | `while(true)` | the super-loop | never runs (`Inf` is stuck) |

**A frame is a handle's field.** The control effects' single implementations are named, written over the primitives,
and take the frame as a parameter; the platform's discharger binds one with an ordinary `with`, supplying the frame its
primitive made:

```eliot
implement[E] escaping(frame: ThrowFrame[E]): Throw[E] {     -- jvm layer
   def raise[A](err: E): A = exitInternal(frame, err)
}

def runThrow[E, A](obj uses *, Throw[E]: A) uses *: Either[E, A] =
   escapeInternal((frame: ThrowFrame[E]) -> obj with escaping(frame), (err: E) -> Left(err), a -> Right(a))
```

`escaping(frame)` is an `Escaping$Handle[E]` holding the frame, `obj`'s block is handed it, and a `raise` in the block
is `Throw.raise(handle, err)`, dispatched by the handle's type to `escaping`'s clause, which exits through the handle's
frame. `aborting(frame: AbortFrame[Unit]): Abort` likewise over `escapeInternal`; `holding(cell: StateCell[S]):
State[S]`, `writing(cell: WriterCell[W]): Writer[W]` and `providing(cell: DepCell[X]): Dep[X]` over `withCellInternal`.
**A primitive gives no effect**: it hands a fresh frame to a function, and what runs on that frame is the `with` its
caller writes. Every other discharger (`catch`, `else`, `runStateToValue`, …, and every user discharger over them)
writes no `with` and names nothing: it passes its code on to a slot, and `escaping`'s handle reaches `catch`'s block,
then `mocked`'s.

**There is no search across calls**: a handle reaches a definition only as a value passed to it, so a helper's own
`catch` or `State` cannot intercept the effects of a block its caller wrote with `uses *` — the block captured its
writer's handles. The one choice left is the lexical one, made inside the body that runs a block, and only over an
effect the slot **lists**: a handler chosen by the callee, as in Koka (whose handlers compile to the same evidence
passing, Xie and Leijen) and Effekt, whose second-class blocks are rule 3.

**The primitives are private, so the dischargers are abstract in the base.** A public cell is Landin's knot — a cell
holding a closure that reads the cell is a loop, which `termination/PurityGuardTest` keeps out — so `withCell` may not
be a base name, and `escape` follows for uniformity. `runThrow`, `runAbort`, `runStateToPair`, `runWriterToPair` and
`provide` are body-less in the base and bodied per platform; `catch`, `else`, `runStateToValue`,
`runStateToFinalState`, `runWriterToValue` and `runWriterToLog` are platform-independent bodies over those, in the base.
Eliot has no *layer*-private visibility — `private` is module-scoped — so each jvm module needing a primitive declares
its own copy, which also separates the frames and lets each copy name the frame type it makes. Frame types are public,
body-less, constructor-less types (`ThrowFrame`, `StateCell`, …), because `private` reaches only `def` and `data`.

**The compile track runs handles as values too.** Its control effects — `Abort` and `Throw`, the only ones that run at
compile time — are the compile-track overlay's (`stdlib/eliot/compiler/eliot/effect/`), spelled exactly as the jvm
layer's, so the overlay's supersedes on that track, over the evaluator's own `escapeIntrinsic`/`exitIntrinsic`
(`EffectIntrinsics`): a fresh frame with identity is handed to the body, and an exit leaves through exactly the frame
it was given. The names differ from the jvm leaves' on purpose: an intrinsic serves both tracks' evaluators, and the
runtime track must keep finding no binding for a jvm leaf. A guarded signature's `raise` takes the handle `runThrow`'s
run gives (§3.2), so a rejected guard reports its author's message. A signature that can run a control effect is never
read raw — raw, an operation stays an undispatched call, and a handler that ignores its argument (`_ -> false`) would
turn that into a plausible wrong value — and the refinement channel's reduction of a `^Meta`/`^Where` companion links
every callee reduced at its own instantiation.

**Erasure** (D36 point 11, `monomorphize/channel/HandleErasure`, at the `WovenValue` seam so every backend reads it): a
handle costs only what it holds. A field survives unless its own type erases or it is a capture field nothing captured;
a handle with **no** surviving field is no parameter, argument, allocation or capture, one with **one** is that field —
`escaping(frame)`'s handle is the frame, `recordingConsole`'s the `Writer` cell it captured — and one with more stays
its `data`. The decision is a function of the handle's ground type alone, so its construction, its field reads, a
parameter of it and a call passing it all agree. A definition's leading handle parameters are dropped with the
arguments a direct call passes them; a function value keeps its shape, so a block taking an erased handle takes
`unit`. A `with` is a `let`: one whose handle erases is its subject alone. Dropping is safe because a handle is
second-class: an expression of a handle's type is a parameter, a construction or a field read, and anything else is
refused there rather than dropped. Measured over the 46 examples (2026-10-18), every program printing what it printed
before: 845 914 ⤳ 657 477 bytes (−22.3%), 1 494 ⤳ 1 184 classes, 24 049 ⤳ 18 870 instructions — 5.2% under D35's
evidence passing. HelloWorld is 2 603 ⤳ 1 679 bytes: `main` takes nothing and calls `printLine`'s clause directly.

### 3.5 Discharge

**Discharge is a frame, not a layer.** A discharger installs the frame its effect's operations exit to or thread
through, and the entry simply never joins the clause — so there is nothing to spell as a negative effect, and a
wrapper-reached discharge inside a `uses Console` body just compiles.

**Nesting order at the run site decides interaction**: `runStateToPair(s, runThrow(c))` versus
`runThrow(runStateToPair(s, c))` is the difference between state surviving a `raise` and not. There is no canonical
form and no ordering for the compiler to choose.

A discharger may be called any way a function can, since `.`'s `f uses *: A => B` is a code slot. A discharger's
**handler may itself perform effects** (`onError uses *: E => A`, bound where it is written).

**A `val` is a bind, and binds the value** (D17). A `val`'s right-hand side is a value position, so `val x =
lookupConfig("db.url")` *runs* the lookup there and binds `x` to the `String`; `val x = comp` followed by `x else
fallback` is the error it looks like, reported on the right-hand side, where the discharge belongs. A `val`-bound lambda
is a value, so it may use no effect from around it.

**Every effect is bound the same way; only what is bound differs** (D26). A control effect — `Throw`, `Abort`, `State`,
`Writer`, `Dep` — has one named implementation per platform, taking the frame its discharger's primitive made and bound
by that discharger's `with`; `Inf` and the interpretation effects — `Console`, `Log`, `FileSystem`, `Process`,
`Environment` — have the base's `standard…` ones, which the entry point binds and a test replaces. That describes what
the platform ships, not a distinction in the language: nothing keys on it.

### 3.6 An effect is declared, never read off a shape

Membership in an `effect` block says a member needs that handle; a **constructor class** is simply an `ability`
(`ability Container[F[_]] { def wrap[A](a: A): F[A] }`). Whether an ability is an effect is read off its members' rows
in one place (`ability.util.AbilityKind`), and an `effect` with no operation, which would leave no record, is refused at
its declaration. **No phase reads effect-ness from a name, a shape, or a higher-kinded binder**, and whether a parameter
is code is read off its clause, never its type's shape. The `<Ability>Carrier` convention, an LSP reverse table and "has
an `Effect` instance" all miscompiled in both directions under v5; the prohibition is kept so none is reinvented.

What is **not** about effects and lives next door is `check/CarrierKindChecker`, which rejects a `[F[_]]` binder
instantiated at a fully-applied proper type. It is a kind system, and soundness (§12).

### 3.7 One verifier, and what the checker holds

**The verifier is the scope check**, in two halves and one verifier (D7): before monomorphization, by effect name, in
the walk (§3.2); exactly, by entry, in the checker — the hole rule's errors and a handle parameter no hole takes (§3.3).
It is complete for every instantiation that manifests, since the whole program is monomorphized from `main`.
*Forward what is declared, derive what is done.* A post-monomorphization verifier existed and was retired by
measurement (D7, 2026-09-10): a monomorphic body sees a strict subset of what the scope check sees. **Do not
reintroduce one.**

**The checker holds exactly one effect rule, the hole** (§3.3), and the read of a block's handed types beside it.
Everything else an effect does there is an ordinary ability method on an ordinary type parameter: no lift, no `pure`, no
`Id`, no binding to solve and no other effect diagnostic.

What sits at the `WovenValue` seam decides nothing: `WovenValueProcessor` checks meta-transfer accounting, erases
handles (§3.4) and re-checks the erased body on ground types before codegen. The refinement channel walks a
definition's leading handle parameters as the parameters they are, so an effectful body records its ranges as one taking
nothing does, and a `where` companion is matched against the declared arguments, a callee's handles left out.

### 3.8 Rendering and hover

A type contains no effect machinery, so `GroundValueRenderer` prints what the user wrote. LSP hover leaves handles out
(`lsp/index/TypeHintIndex`): a node of a handle's type has no hint, a handle parameter is left off the front of a
function type, and a reference is hinted at its name — `printLine` reads `String -> Unit`, and a block its callee hands
a handle reads as the value it computes.

## 4. The scope check, as a spec

Per definition, over the operator-resolved body **and signature**, reading only *declared* information, in two halves
(§3.7): **by name** in the `row` walk, **by entry** in the checker.

- **The environment** at a point is its handles — innermost first, grouped by the binder that put them in scope: a
  `with`'s, a block's, the definition's own — this definition's code parameters in scope (nullary `thunks` and
  function-typed `callbacks`), and whether the region is a **value** or a **closed** argument.
- **A reference** to a callee taking handles has one hole per handle. By name: an **effect** with no handle of it in
  scope at all is *"This value performs the effect 'X' but does not declare it; add it to its `uses` clause"*, at the
  reference; an **ability** (`~`) is the two-site search's. By entry: the nearest handle whose entry answers, or the
  hole rule's errors (§3.3).
- **Entering an argument** at a slot whose clause lists entries makes a block whose handles are the nearest group; a
  closed clause cuts off everything from around it. A lambda at a parameter with no clause, or in any other value
  position, is entered in the value cut: every handle from outside is out of reach.
- **An unapplied reference to a definition with a clause** — fewer arguments than it declares before its return position
  (`EffectRow.returnArity`), counting those a code parameter it is the argument of calls it with — performs nothing
  where it stands, so in a value position it is the rule-2 error. A function-typed return is a value: `def greeter uses
  Console: String => String` is called by a bare `greeter`.
- **A code parameter is used, never kept**: it stands as the head of a call or as the argument of a code parameter;
  anywhere else is the error at the reference, and so is a reference inside a value lambda or a closed argument.
- **A listed effect is handled or declared**: each run of a code parameter is handed a handle for every entry its slot
  lists, from the definition's scope at the run; one with no handle of its effect there is *"'body' may perform the
  effect 'X' here, which this definition neither handles nor declares"*. Every run must hand the same.
- **A `with`** puts its handle in scope for its subject; one no hole in its subject could take is *"This `with` binds
  nothing …"*.
- **A clause on a named implementation is answered at the binding site**: `greeting("Bob") with recordingConsole`
  captures a `Writer[String]` handle there. An entry a clause only repeats from its member is the call's.
- **One exemption, because something else answers**: a **signature**, whose `raise`/`abort` is the guard channel's
  vocabulary, read inside its discharger (§3.2). It is not cut into value or closed regions. The synthesized entry point
  is not exempt.
- **A declared entry must be consumed**: a handle parameter no hole in the **body** could take is *"This value declares
  the effect 'X' but does not perform it"*, at the entry, with the help *"Remove 'X' from its `uses` clause"* — by name
  in the walk and by entry in the checker, so a `Throw[String]` beside a used `Throw[Int]` is reported. A clause that
  nothing uses would ask every caller to declare an effect on no evidence, all the way to `main`. Consumed means taken
  at least once — by an operation, a declaring callee, a run of a code parameter handed it, or a `with`'s captured
  field — on any path, so `if(ok, v)` consumes `Abort` whatever `ok` is. `def passThrough[A](v uses *, Console: A) uses
  *, Console: A = v` is accepted, its run of `v` handed its own `Console`, while its twin over `v uses *: A` lists
  nothing, consumes nothing and is the error. An entry arriving through a row alias is held to the same. Exempt: a
  body-less value (its clause is a contract a later layer bodies) and a signature's own references. An `implement`
  clause is held to the entries it wrote. The by-name error waits for the definition's callees: a callee performing the
  effect *undeclared* is its usual cause, and that callee's error is reported instead
  (`RowElaborationProcessor.calleesWritten`).

**Suspension is row-neutral.** Whether a parameter is a value or code changes only *when* the effect runs and *whose*
handle answers it. What the caller must declare changes only by what the callee **handles**.

**Two same-ability entries** at different arguments are two entries; two identically spelled in one clause are an
error at the declaration, and two that coincide at an instantiation are the hole rule's tie at the use.

## 5. Standing rules

These bind every future change to the effect system.

1. **Where decisions live.** Part I states the design as built. A decision is recorded in §11 until it is built, and
   building it changes Part I. A finding that contradicts a decided rule is a **reversal**, recorded as one and signed
   off — never an amendment in place, an appendix or a commit message. (A reversal once read as a refinement for six
   days.)
2. **Stop on conflict; do not route around it.** If Part I's rules appear to conflict with each other, with the tree,
   or with a measurement — or a step finds itself *narrowing*, *bounding*, *deferring*, *approximating* or *exempting*
   one of them — **stop and surface it.** The tells, in this project's vocabulary: "bounded staging", "the corpus
   forced", "narrowed to ~nothing", "a small local concession", "keeps the shipped idiom", "it usually holds". A
   conflict is a decision for Robert, not a judgement call in flight.
3. **There is nothing to infer.** A use of an effect is answered by the nearest handle in lexical scope whose entry
   answers it, and an ability's by the two-site search: never by a metavariable of its own, a join, a lattice, an
   ordering-sensitive slot decision, a row variable unified across arguments, or a sum over a monomorphized sub-graph.
   Carrier inference was the historical bug class (carrier theft, premature commitment); binding inference would be the
   same bug in new vocabulary, and **dynamic scoping resolved at compile time** is its exact shape. Both are
   prohibited. `*` is not a variable: it names the scope the argument is written in. The hole rule is lexical lookup by
   type, and a block's handle type is solved by equality at its callee's runs: neither decides a binding (signed off,
   2026-10-18). A type argument decides only *which* handle answers, and monomorphization by a handle's type only what
   code is generated.
4. *(Retired: carrier-ness by tag. §3.6 keeps what it protected.)*
5. *(Retired: the elaborator whitelist. §3.2 says what replaced it.)*
6. **A component may read solved metas and splice rewrites; it may never run inside unification, never retract a
   solution, never grow an ordering arm.** A shape that needs mid-drain resolution is a stop-and-redecide signal.
7. **A rule invented so that one idiom elaborates is a rule shaped by that idiom, whatever its stated generality.**
   Delete the rule; fix the declaration.
8. **Fail-safe direction.** Every bound, deferral and declination may only *withhold* a permission, never grant one
   silently.

**Why rules 2 and 7 exist.** Rule 2's predicate (then stated on the row: *"a binding passes into a position iff that
position declares a row"*) was agreed and then worked around four times, and every stall in this design's history traces
to that erosion: bare-generic slots exempted from rule 1 (six days of a mode resolver and obligations, all reversed); a
row declined on `foldLeft`/`foldOption` for ergonomics (which kept the whole payload router alive); a derived discharge
stack routing a computation through `.`'s rowless slot "as data" (five `State` miscompiles); and `foldOption` left with a
strict `ifNone` (a silent lazy-branch failure). A fifth — the elaborator's payload test accepting a generic-headed
return — was rejected because it **approximates** the predicate instead of **declaring** it. Moving the predicate into
the clause (D21) is what made the declaration say it.

## 6. Testing — a named implementation is the injection point

Production code that declares a clause names no implementation, so whoever runs it decides: in production the
synthesized entry point, binding each of `main`'s entries to the platform's `standard…` implementation; in a test, one
`with`.

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

Four properties fall out of the design rather than being added for testing:

- **A double is one declaration.** No type to hang on, no colocation, no coherence question: a named implementation is
  never searched, so it may freely overlap another. *Never searched* is enforced: the two-site search and both coherence
  checks read `ModuleAbilities.anonymousImplementationMethodsOf` / `anonymousMarkersOf`.
- **A double cannot cheat.** A user module cannot declare a native, and the platform's natives and primitives are
  private to its layer, so an implementation reaches the world only through effects **its own clauses declare** —
  charged and bound at the binding site.
- **A double keeps its state through an effect**: `session` writes to `Writer[String]`, charged where `session` is
  bound and discharged there by `runWriterToLog`; it never appears in `greet`'s clause. Parameters (`greet with
  scripted("Bob")`) configure a double per binding; state that changes as the code runs is still an effect's.
- **Interpretation is per effect, not per program.** `body with mockConsole with mockFileSystem` binds two doubles and
  leaves everything else bound as its caller binds it.

**A discharge word binds doubles around its run of the code**, so a faked case writes no fixture: `transcriptOf` in
`examples/src/EffectsTestFramework.els`, and eliot-test's `mocked`, whose slot lists every effect it fakes (`body uses
*, Console, Log, FileSystem, Process, Environment, Mocking, Calls, Throw[IoError], Throw[AssertionError]: Unit`) and
whose body runs it as `body with mockConsole with mockLog with mockFileSystem with mockProcess with mockEnvironment with
recording with journal`, inside the recording's `runStateToValue` and its own `catch[IoError, Unit]`. A case reads
`"…" should "…" in mocked { … }`; a suite's whole declaration is its return type, the row alias `type Test =
{Writer[List[TestResult]]} Unit`, widened with a clause where its cases perform for real (`def testCases uses Console:
Test`). A case that must perform nothing beyond its assertions can say so with a closed slot (`body uses
Throw[AssertionError]: Unit`). `examples/src/EffectsNamedEffect.els` is the minimal version, and
`EffectsFakeConsole.els` does it for a stdlib effect. `Dep[X]` + `provide` remains for a seam stated in the signature,
and swapping the platform layer remains the whole-program integration answer.

## 7. Live limitations

Each is stated and fail-safe, and is either a deliberate trade or tied to an open decision.

1. **A function-typed code parameter's own entries are not handed to it.** `f uses *, E: A => B` writes `E` on the
   codomain, where the callee never hands a handle for it, so code using `E` there is rejected rather than run on a
   handle nobody gave. Nothing in the corpus needs it.
2. **A discharger's type arguments are sometimes written by hand**, where the code's holes would solve a listed entry's
   arguments differently or nothing solves them (§2.2). The rejection is loud and names the fix; the alternative — a
   choice by visiting order, or a default — would answer a use with a handle its author did not mean.
3. **An under-applied backend *intrinsic* has nowhere to link.** An intrinsic is emitted inline at each call site, so
   `digits.map(show)` for such a name is a hard error naming the fix (`digits.map(n -> show(n))`). A gap in the backend,
   not a rule of the language; an ordinary native is supported.
4. **A row alias works in return position only, and has no `uses` form** (§2.4) — D20a.
5. **An effectful computation cannot be stored** (§2.3). Deliberate: what a frame-bound implementation captures cannot
   outlive its frame. Storing one handled where it runs is D20c; frame-free capture is §12's entry B.

---

# Part II — The record

## 8. Where the tree diverged from Part I — closed

**None open.** Each item was a defect where the tree diverged from Part I, closed in code, never by a doc fix. Numbers
are kept so citations resolve; the full record of items 1–62 is at `14b61527`, and of 63–65 in their commits.

| # | defect | closed |
| --- | --- | --- |
| 1 | an unused `with` was a silent no-op | 2026-10-13, D23: an error |
| 2 | a dot-read of a row-typed field handed back the thunk | 2026-09-12; stored computations deleted by D20 |
| 3 | `val x = comp` then `x else …` failed | 2026-09-12, D17 |
| 4 | `runThrow[String, String]("no failure")` accepted | 2026-10-15, not a defect: a slot entry is a permission |
| 5 | an undischarged control effect at `main` failed at runtime | 2026-10-12 (D19): a compile error |
| 6 | a row alias works in return position only | moved to §7 item 4 / D20a |
| 7 | a named implementation answered the two-site search | 2026-09-12 |
| 8 | a slot's `with` missing from the layer merge's comparison | 2026-09-12 |
| 9–13 | a row on a slot both marked a block and minted bindings (lambdas reaching enclosing bindings, kept function parameters, a field's row, a slot laundering an effect) | 2026-10-09, D20/D21 |
| 14 | an unapplied `uses` definition passed as a value | 2026-10-13: the rule-2 error |
| 15 | an effectful member of a parameterised ability needed its type arguments spelled | 2026-10-13: the checker's hole |
| 16–19 | a helper's `catch`/`State` intercepting its caller's; a slot implementation's clause effects; two entries of one effect one binding | 2026-10-12, option 5 |
| 20 | a slot `with` on a declared entry silently dropped | 2026-10-13, D23 |
| 21 | a rejected definition was "Name not defined." at callers | 2026-10-13: *"Cannot use 'X' here: its own definition was rejected."* |
| 22–23 | a `with` compared by ability instead of entry | 2026-10-13: keyed by entry |
| 24 | an ability member's own clause binding dropped at dispatch | 2026-10-13 |
| 25 | one arity for specialisations with and without an environment (`NoSuchMethodError`) | 2026-10-13 |
| 26 | on the compile track a helper's discharge caught its caller's effect | 2026-10-13, compile-track frames |
| 27 | a guarded return's `else` arm never reduced | 2026-10-13, `EscalatingReducer.typeLevelStuck` |
| 28 | a `where` over a control effect could not be evaluated | 2026-10-13, compile-track frames |
| 29 | a rejected `main` reported a second, misleading error | 2026-10-15, `CompilerNativesProcessor.runtimeConcrete` |
| 30 | a named implementation's own type parameters never solved | 2026-10-14; under D36 left out of the handle and solved per use (§3.1) |
| 31 | a `with`'s arguments typed from themselves alone | 2026-10-14 |
| 32 | a slot naming a constrained named implementation rejected | 2026-10-14 |
| 33 | a run passed to a slot naming an implementation compiled against the definition's own slot | 2026-10-14, D25 |
| 34 | the compile track's raw read of a `where` answered a wrong value (silent) | 2026-10-14, `EffectIntrinsics.undecided` |
| 35 | a slot's `with` bound an ability, not an entry | 2026-10-14 |
| 36 | a `with`'s arguments untyped where its entry is not spelled | 2026-10-15: keyed holes, `CheckState.sharedHoles` |
| 37 | two named implementations of one ability mangled to one method (silent, releases ≤ `v0.12`) | 2026-10-15: a binding mangled as its whole identity |
| 38–39 | constrained / parameterised named ability implementations | 2026-10-15; the feature reversed by D29 |
| 40 | a named implementation's type parameter outside its pattern unsolved | 2026-10-15, D27; reversed by D29 |
| 41 | a slot-moved `with` could not read such a parameter | 2026-10-15, D28: refused |
| 42 | a named implementation never checked against its ability (silent) | 2026-10-15 |
| 43 | ride versus supply decided two ways (silent) | 2026-10-15, D30; the concept gone with D36 |
| 44–45 | the threading hid a binding/frame disagreement; a slot implementation's clauses at `Default` | 2026-10-17, D35 |
| 46–47 | a closed slot kept a binding by ability; over-declaration checked per ability (silent) | 2026-10-15, D30 |
| 48 | the row phase found value-parameter names two ways | 2026-10-17, D35 |
| 49 | compile-track linking kept one binding per FQN (silent) | 2026-10-16, `ReducedBindingClosure.Linked` |
| 50 | an anonymous implementation's clause performing an undeclared effect failed late | 2026-10-16, `RowElaborationProcessor.anonymousClauseEntries` |
| 51 | `with` accepted on a value parameter and a field | 2026-10-17: refused |
| 52 | the row write aborted on the runtime track only | 2026-10-16, `RowElaborationProcessor.reported` |
| 53 | "is this ability an effect?" answered three ways | 2026-10-16, `ability.util.AbilityKind` |
| 54 | a not-ground reference's arguments taken from the first same-named constraint | 2026-10-16, proved dead and deleted |
| 55 | `findAbilityMethodImpl` searched named implementations too | 2026-10-16 |
| 56–57 | the environment-entries rule implemented twice; five silent `Default` fallbacks | 2026-10-17, D35 |
| 58 | a binding's mangled identity hashed with 32 bits | 2026-10-16, `CommonPatterns.stableHash` |
| 59 | the checker said to hold no effect rule while holding several | 2026-10-18, D36: it holds one, the hole (§3.7) |
| 60 | a reference whose entry the call does not spell answered before the checker typed it | 2026-10-18, D36: the hole rule (`HoleRuleIntegrationTest`) |
| 61 | an anonymous implementation's clause could not repeat a member's entry at the pattern's type | 2026-10-18: read at the implementation's pattern (`RowElaborationProcessor.memberEntriesAtPattern`); an entry spelled through an alias is still refused, loudly |
| 62 | runs of one code parameter given different implementations refused | 2026-10-17, D35 step 5a; refused again by D36 point 6 |
| 63 | a `with`'s arguments rode a `$with` binder declaration through every phase | 2026-10-18: the `with` node carries them (`WithBinding.arguments`) |
| 64 | the `Implementation[A]` mark said which binders bind, for effects, `~` constraints and every ability's binding slot | 2026-10-18: handles recorded in the declaration (§9); the binding slot and `Default` deleted |
| 65 | `BindingWriter`'s spelled-entry bookkeeping (D30's `Need`s and rides) ran beside the holes | 2026-10-18: deleted; a run's listed effect is checked by name, its entry by the hole |

## 9. Which binders are handles

A definition records its **handles** in its declaration — `EffectRow.handles`, each the binder that is a handle's type
and the index of the `returnEffects` entry it stands for — written where a handle is minted: an effect member's own
(`ast/fact/AbilityMembers`), a `uses` entry's (`core/…/EffectSugarDesugarer`), a row alias's (`resolve/…/ValueResolver`).
Every phase that asks which binders are handle types reads that and nothing else (`BindingWriter.phantoms`,
`ImplementationConformance`); the binder itself is an ordinary binder of kind `Type`. A handed binder keeps its own
mark, `Implementation::Handed`, which is also the opaque type a body-less callee's block is typed at (§3.3).

From 2026-09-11 to 2026-10-18 the fact was a **mark** on the binder's type, `Impl: Implementation[Console]`, minted also
for a `~` constraint and for every ability's **binding slot**, whose value was the `Default` sentinel; it went with D36
(§8 item 64). **Do not re-propose:**

- reading handle-ness off a signature's **shape** — a binder typing a leading parameter, a constraint's first argument,
  a name prefix, a count: the mark replaced four such encodings, and the record replaced the mark;
- a **binding slot on an ability**, or a sentinel standing for "use the search": an ability is answered by the two-site
  search and nothing else (D29);
- a row alias minting binders of its own: it *declares* its row, and is an ordinary name resolved at `resolve`.

## 10. How a change is run

**The gate.** `./mill __.test` green, every example program carrying a `main` compiles, and every example jar
`md5sum`-identical to the pre-change build — `scripts/example-sweep.sh`, whose `jar.md5` lines are the gate.
Byte-identity is a **safety oracle, not a hard gate**: when the output legitimately changes wholesale it is replaced by
**behavioural identity**, the same script's `exit`/`stdout` lines compared before and after, with the size and
instruction-count difference stated in the commit. A regression there is a finding to explain, not a cost to accept.
Compare reports with their `#` header lines stripped; clear the cache under `target/` between compiles; give stdin
`/dev/null`; never run `./mill` while the sweep runs. Changes reaching eliot-test or eliot-build are run against their
suites under this compiler.

**What the gate cannot see.** No example writes a guarded return type, a `catch` with an ignoring handler, or a stored
computation; a shape with no example has no gate, and the jvm end-to-end suites are what separate it.

**Is this still load-bearing?** Arm-liveness tracing, not inspection: an env-gated `fire(arm, sample)` tracer over the
whole gate *and* a compile of all examples and the dependents' suites, deleting only zero-fire arms. Switch parts off
**separately** — an all-off run once said "43 failures, delete nothing" where per-part runs said 1 and 36. An
examples-only audit under-reports (elaboration is demand-driven). A firing arm is a question, not a verdict. Tracer
gotchas: mill prefixes forwarded lines with a worker id; a sample key carries the range end and the spine head; delete
the cache before every run.

**A trap in the test harness.** A test stub that diverges from the real declaration fails silently. A `ProcessorTest`
snippet using an effect needs the `Implementation` stub (`Inferred`, `Handed`), which `ProcessorTest`'s prelude carries.

## 11. Decisions

Every decision is Robert's. This section holds D36, the open questions, and every earlier decision one entry each.
Conveniences deliberately left out are in §12.

### D36 — effects are capability passing (decided 2026-10-17, revised and built 2026-10-18)

**The question** (Robert, 2026-10-17, rejecting the rules proposed for §8 item 60): how can the effect system be
simple, run before monomorphization, and desugar into ordinary constructors and abilities? An effect should differ from
an ability in exactly two ways: it is **inherited automatically** along a call chain, and it is **supplied
explicitly**, never found by a search.

**The findings.**

- *Two parties decided one thing.* §8 items 60 and 61, §7 item 2 and the `raise(none)` refusal shared one root: which
  implementation answers a use was decided before monomorphization by comparing spellings (D30), while the type
  arguments that decide it are expressions only the checker evaluates. **eliot-build has 36 declarations with two or
  more `Throw` entries** (`ShellGit.fetch uses Process, Throw[IoError], Throw[GitError]`), so a choice by the effect's
  *name* (Koka's scoped labels) is out: `raise(GitError(…))` must reach the `Throw[GitError]` because of its argument's
  type. Hence: **the desugar does all the plumbing and decides nothing; the one decision is the checker's**, after
  evaluation.
- *Captured handles make a handle's type unspellable.* `implement mockConsole: Console { def printLine(s: String) uses
  State[Recording]: Unit = … }` becomes `data MockConsole[S](state: S)`, `S` whatever `State` handle is in scope at the
  `with` — inside two other dischargers' bodies, for `mocked`. The same holds for eliot-build's `against` and the
  example's `transcriptOf`: every discharger in the corpus that is not the platform's. So a block's handle types are
  read off its callee (point 6), and a first version of point 6, a supplying slot naming its implementation (`Throw[E]
  by escaping`), was dropped.

**Decided** (Robert, 2026-10-17: "Basically effects desugar to abilities with an additional typed parameter"; revised
2026-10-18: "ok, update doc", and on syntax, "We can not introduce 2 new syntax elements, what is, in the developer's
mind a simple DI problem"). The points, as built:

1. **An effect is an ability over a handle type**; every operation takes the handle first (§3.1).
2. **A named implementation is a `data` and an ordinary instance of that ability**, its parameters the fields; a clause
   entry spelled as its member's is the call's, any other is captured at the `with` (§3.1).
3. **A `uses` entry is a handle parameter** — one type parameter and one value parameter per entry; monomorphization
   clones by the handle's type (§3.1).
4. **A use takes the nearest handle in lexical scope whose entry answers it** — the one new rule, and where *inherited
   automatically* lives. A hole at an operation call, a call of a `uses` definition, a run of a code parameter and an
   implementation's captures at its `with`; two equally near answering handles are an error with no syntax to choose
   (§3.3).
5. **`with` binds a handle — *supplied explicitly*.** D23 stays: one no hole takes is an error (§2, §3.2).
6. **A code parameter's listed entries are its block's parameters, typed by what the callee's body hands them**; what a
   block uses through `*` it captures (D22's invisibility as plain capture); every run must agree; the block is not in
   the callee's key (§3.3).
7. **Rules 2 and 3 are a second-class rule on handles**, checked by the walk (§3.2).
8. **The entry point** passes the `standard…` implementations' handles to `main` (D26).
9. **Control effects are the same**: a frame is a handle's field, the three primitives stay platform-private, and the
   compile track runs handles as values too (§3.4).
10. **No new syntax.** `uses`, `with`, `effect` and `implement name(params): E` are the whole surface; the one
    disambiguation a program can need is ordinary type arguments (`catch[IoError, Unit]`).
11. **Erasure follows the switch**, as an optimization of its own (§3.4).

**The scope check splits** into a by-name half in the walk and a by-entry half in the checker — still one verifier (D7).

**Signed off** (Robert, 2026-10-18) — seven readings of earlier decisions, which Part I is written to:

1. **D30** (entries compared as spelled) is replaced by comparison after evaluation, in the checker.
2. **§12, "a handler as a marker type"**: restated — an implementation's handle `data` is ordinary.
3. **§12, "carrier inference", and §5 rule 3**: a hole's lexical lookup by type and a block's handle type solved by
   equality are not inference of a binding.
4. **§12, "a runtime handler stack"**: the hole rule's search is the checker's, over lexical handles, at compile time.
5. **§8 item 59**: the checker holds exactly one effect rule, the hole.
6. **D35's lowering of every `with` to a `$with` binder call**: the binder goes.
7. **§12, "a slot's implementation solved from the callee's body"** (D28): restated — its concern, a callee fixing an
   implementation that escapes upstream, cannot arise, since handles flow down only; what stays closed is a type
   parameter outside an implementation's pattern (D29).

**It reverses** (confirmed 2026-10-17, "I accept") D35's mechanism — evidence made after monomorphization, the
specialisation pass, the threading — and D35 step 5a's block copies. **It keeps** rules 1–6, D22 (as capture), D23, D24,
D25 (as fields), D26, D28's clause half, D29, the three primitives and their frames, `CarrierKindChecker`, and the
surface: no program in the corpus changed.

**Built** (2026-10-18). A spike on 2026-10-17 — one program hand-desugared on v0.16 — compiled unchanged, cloned one for
one with the specialisation pass and grew the jar 11.8%, all of it the handles' data representation (hence point 11).
The new path was then built beside the old behind an internal switch and measured: all 46 runnable examples,
eliot-test's suite (193 cases) and eliot-build's (368) printed exactly what the old path printed, every test difference
being an old-path shape, a D36 reversal or the same rejection worded by the hole rule. One corpus change was **decided
(Robert, 2026-10-18)**: eliot-build's `cachedDescriptorTextAt`, typed through a row alias including a `Throw[GitError]`
its body never performs, is refused by the exact check, and **the check stays exact** — eliot-build spells that clause
without the alias. The default was flipped, the switch and D35's machinery deleted (`monomorphize/specialise/`,
`EnvironmentThreading`, `EffectDeclarations`, `SuppliedRowArgumentsProcessor`, `MonomorphicEffects`, the compile
track's threaded reduction), then §8 items 63–65, every jar byte-identical; released as `v0.17` (+22.3% jar, handles
as data). Erasure followed the same day (§3.4) and was released as `v0.18`. The step-by-step record is at `671d2b57`.

### Open questions

- **D20a — naming a set of effects.** `type Git[A] = {Process, FileSystem, Throw[IoError], Throw[GitError]} A` is a row
  alias, return position only (§2.4). The natural replacement is a name used in a clause, `uses Git` on a definition and
  `uses *, Git` on a parameter — declaration metadata, so it need not cost an `ast.fact.Expression` case; its
  declaration's spelling is undecided. Until then a row alias's body is the one place braces parse.
- **D20c — storing an effectful computation whose effects are handled where it runs** (hooks at process end, a job
  queue, an event loop's table). Code cannot be kept (rule 3) and a value lambda may use no effects (rule 2), so what is
  left is a hand-written `data` and an interpreter (§2.3). Under D36 a stored open computation is a closure awaiting a
  handle, which rules 2, 3 and 6 refuse. Options weighed: (1) the binding in the container's type — needs a region
  binder per discharger call; (2) the implementation passed when the block runs — the Π-typed-field limit (§12); (3)
  the compiler reifies the block into a free monad — needs continuation capture and recursive data, though a partial
  form works today (an implementation that `tell`s each operation as data); (4) an open field as a finite product
  selected at the read, `data Hook(run uses Cleanup: Unit)` — reverses rule 6, narrows rule 2, needs
  `StrictPositivityChecker` to count a field's entries as negative, and makes the data's layout depend on every
  implementation. Until decided: option 3's partial form, or a hand-written interpreter.
- **D3 — `~` and `&` fully in user space.** Blocked on meaning: whether `~` and `where` unify, then the phase-order
  blocker (the superability closure runs at resolve, before operators are structured). An ability denotes no type of
  values, since an implementation's code is not a value.

### Closed decisions

One entry each, newest first; the full record of each is at `14b61527` or earlier (§13).

- **D35** (decided 2026-10-16, built 2026-10-17; mechanism reversed and deleted by D36): an implementation is evidence
  passed after monomorphization, static code a specialisation pass cloning by constant evidence; step 5a gave a block a
  copy per variant. Chosen over a free monad, whose costs are kept in §12.
- **D34** (withdrawn 2026-10-16 for D35): a binding on the definition's declaration (`def releaseTests with tableGit:
  Test`), Dagger-style. Its measurement of `Mock.els` is at `14b61527`.
- **D33** (reversed by D35 the same day, never built): a `with` only on a call of a named value; control effects take
  only a frame. Measured: exactly three code parameters list an interpretation effect (`mocked`, `against`,
  `transcriptOf`).
- **D32** (superseded by D33): one walk decides both halves of an implementation.
- **D31** (withdrawn 2026-10-16): a definition specialised per code argument. Measured: 24 definitions with a listing
  slot and **1,271** call sites. Finding kept: what a callee gives at a run depends on its body and type arguments,
  never on the block — why D36 reads a block's handle types off the callee.
- **The 2026-10-15 diagnosis**: the open items traced to four structural choices, each deciding one thing in two places
  — **A** code compiled by its caller while its listed effects are the callee's; **B** an implementation's two halves
  decided by two walks; **C** an entry's identity holding type arguments not known where it is compared; **D**
  abilities run on the effect machinery. D29 removed D, D35 removed A and B, D36 removed C.
- **D30** (2026-10-15): entries compared as the declarations spell them. Replaced by D36's comparison after evaluation.
- **D29** (2026-10-15, built): an ability is never bound with `with` and has no named implementation; behaviour per call
  is a value parameter; every type parameter of an implementation is its pattern's. Reversed the parameterised ability
  implementations and D27.
- **D28** (2026-10-15, built): a `uses` clause lists effects only (`ValueResolver.requireEffect`).
- **D27** (reversed by D29 the same day): a named implementation's type parameter outside its pattern read off its
  `with`'s arguments.
- **D26** (2026-10-14, built): no effect has a default; every effect implementation is named; the platform runs `main`
  with a generated `with` per entry naming the base's body-less `standard…` implementations (`row/EntryPoint`).
- **D25** (2026-10-14, built): parameters only on a named implementation; a control effect's implementation is named
  (`escaping`, `aborting`, `holding`, `writing`, `providing`) and its discharger binds it with a `with` over its
  primitive's frame. Rejected: `with Ability(args)` for a default.
- **D24** (2026-10-13, built): `uses *` on a definition exactly when some parameter's clause has `*` — a marker. Koka's
  named effect variable stays closed.
- **D23** (2026-10-13, built): a `with` binds what escapes its subject; one nothing escapes to is an error.
- **D22** (2026-10-13, built): a listed effect is the callee's to handle or to declare; one the code uses through `*`
  unlisted is invisible to it. Rejected: always the caller's frame (makes `describedAs` unwritable) and a handler marker.
- **Compile-track frames** (built 2026-10-13): the compile track runs frames as the runtime track does, and the
  guarded-return read gives a signature its control effects' frames.
- **Named implementations with parameters** (built 2026-10-13): supplied at the `with`, `greet with scripted("Bob")`.
- **D20c option 5 and (b)** (built 2026-10-12): an implementation's environment threaded after monomorphization; gone
  with D35 and D36. D20c's open question remains.
- **D21** (2026-10-09, built): a function parameter is a value, code is marked `uses *`, a clause without `*` is closed,
  purity is the absence of `uses`.
- **D20** (2026-10-05, built 2026-10-09/10): effects are parameters — the `uses` clause, code used and never kept, a
  field holds a value. **D20b**: both spellings parsed until the migration ended.
- **D19**: an undischarged control effect at `main` is a compile error (closed 2026-10-12).
- **D18** (a row alias at a parameter): superseded by D20; what replaces it is D20a.
- **D17** (2026-09-12): a `val` is a bind (§3.5).
- **D13** (eliot-test cases built via `with` on a discharge word's slot): moot; `mocked` uses a `with` around its run.
- **D4** dissolved at the flag day; **D5** is rule 2; **D7** (the post-mono verifier) retired by measurement
  2026-09-10.

---

# Part III — What is closed, and provenance

## 12. Closed by measurement or decision — do not re-propose

- **Carrier inference** — a carrier metavariable, a join solver, an `Id`-headed judgment, a mode obligation, a
  post-drain mode resolver. Of 15 fix commits in the v2 window, the four highest-impact were one failure: a carrier
  metavariable captured by first-contact unification. Restated as §5 rule 3; a hole's lexical lookup by type is not
  this (D36 reading 3).
- **A second, post-monomorphization effect verifier.** Retired 2026-09-10 by measurement (D7).
- **The carrier as the injection point.** Every one of v5's testing limitations was a symptom. Superseded by §6.
- **An implementation as a runtime value (a record whose fields are the operations).** Measured 2026-09-07: the checker
  cannot represent a Π-typed field (polymorphism lives on named signatures, `Checker.appendTypeArgs`); four operations
  return their own type parameter (`foldLines[B]`, `foldCodePoints[B]`, `handleCases[R]`, `typeMatch[R]`); an ability
  may declare an associated type; a nullary operation as a field is strict. D36's handle holds the environment only —
  the code is chosen by the handle's type.
- **A handler as a marker *type* unified in the type language** — "two handlers meeting is a mismatch", one environment
  per stored computation, a handler result function, a post-mono lowering with `handler`/`returning`/`finish`/`resume`.
  An implementation's handle `data`, chosen by definitional equality, is not this (D36 reading 2): no handler meets
  another, no stored computation holds an environment, nothing resumes.
- **Transitive `with` / summing abilities over a monomorphized sub-graph.** Dynamic scoping resolved at compile time.
- **A default with parameters** (`with Ability(args)`). A default is what is inserted where nothing is written (D25).
- **Syntax for wiring**: naming the implementation a slot supplies (`Throw[E] by escaping`, `escaping[E]` in place of the
  entry, `Throw[E] with escaping`), or a qualifier choosing between handles (`@Named`). Two equally near answering
  handles are an error whose fix is a distinct type (D36 point 10).
- **A bindings field on `ValueReference`, a scoping node, or a consulted-set component on the mono key** as the carrier
  of a binding (2026-09-08): the key already carries one argument per binder.
- **A runtime handler stack searched by type.** Kills erasure and makes accidental handling the default (D36 reading 4).
- **"No call site is ever dynamically nested inside itself"** (to give frames a compile-time identity). Refuted
  2026-10-12: `catch`'s one `runThrow` installs two live frames when a block under it calls `catch` again.
- **Full unification — every ability passed, none searched.** Puts `{Eq[Int], Combine[String], …}` on most code; the
  compile track dispatches `Meta`/`Numeric`/`PatternMatch`/`TypeMatch` with no call chain.
- **A public cell or escape in the base.** Landin's knot (§3.4).
- **A runtime free monad as the effect representation.** A heap tree walked by an interpreter, a coproduct with
  injection (the row back in the type), no spelling of scoped operations (`forever`), row unification, untypable user
  handlers (existential continuations, GADT matching), an interpreter folding recursive data, and a helper's `catch`
  catching its caller's `raise` again.
- **A `World` token.** Not needed in a strict core.
- **A rule on twin-less natives, or a purity annotation for them** (withdrawn 2026-09-08). A twin proves reduction, not
  purity; a side effect is an `effect` operation, and a native leaf's declaration is axiomatic.
- **An anonymous implementation-literal expression** (`Console(printLine = …)`). A literal is a value.
- **Deleting `CarrierKindChecker`.** It is the only thing rejecting a `[F[_]]` binder at a proper type; a kind system.
- **Putting the row on `VPi`** (Koka-style), and **effects on function types** (`A => B uses Console`), which is the
  same and brings back the closed stored computation.
- **A row alias with its own AST node.** It rides the `EffectfulType` node.
- **Discharge markers (`{-E}`).** Discharge is a frame a discharger installs.
- **Scanning the dictionary for an ability or implementation name.** A name resolves at `resolve` by keyed lookup.
- **A third kind of parameter for a function the callee keeps** (`val f: B => C`). A kept function is a pure value.
- **Code parameters as nested `def` signatures**; **`=> A` for a lazy argument**; **a `block`/`impure` keyword**;
  **`A -> B` pure and `A => B` effectful.** `value uses *: A` says it.
- **The `<Ability>Carrier` convention.** Its premise, that an effect has a representation, is gone.
- **A named implementation of an ability, and `with` for one** (D29). Behaviour chosen per call is a value parameter.
- **An implementation's type parameter outside its pattern** (D29). D28's "a slot's implementation solved from the
  callee's body" is restated by D36 reading 7.

**Not now — conveniences deliberately left out.** Each is additive later and none touches the mechanism:

- **A name for several implementations** (`implement mocks = mockConsole & mockFileSystem`), and **a set of abilities
  required of a binder** (`ability Web[F[_] ~ Console & Log]`).
- **`with` on a def's own clause** — a second spelling of `with` around the body.
- **A clause spelling for a ground ability default** — refused since D28.
- **A user-declared boundary default** (`implement Throw[ConfigError]` at `main`).
- **Compile-time reduction of effectful code under a pure implementation.** Wanted by nothing.
- **B — a derived *keeps* property** (a relaxation of rule 3): which function parameters a definition keeps, bottom-up
  over the acyclic reference graph, with *frame-free capture* admitting a kept lambda whose handles reach no frame.
  Wanted by nothing until a native or a library genuinely keeps callbacks.
- **Derived mocks** (`with mock[Database]`). A feature in its own right, and a consumer for D3.

---

## 13. Provenance, and how to read a citation

**This document's history.** Compacted on 2026-10-19 once D36 was complete; the text before that, with D36's
step-by-step build record, is `git show 671d2b57:docs/effects.md`. The full text of every record before the 2026-10-18
compaction is at `14b61527`; older condensations are at `f78861cf` (D17–D21's records) and `cb2a9c6d` (the v6 plan).
Section numbers and D36's point numbers are kept stable because source comments cite them. Citations resolve as
follows:

| citation | now |
| --- | --- |
| D20/D21 rules | §1's six rules (D20 rule 2 and D21 rules 1–2: rule 2; D20 rule 3, D21 rule 3: rule 3; D21 rule 4: rule 4; D20 rules 5, 6: rules 5, 6) |
| D20/D21 steps 1–7 | §3.1, §2.3, §2, Part I |
| D20c option 5, "the `transcriptOf` spike", "Option 5 built" | §3.4 (its primitives and frames); the threading went with D36 — D35's Part I is at `e03aa15c` |
| D20c (b) | retired by D35 (§11) |
| D22–D35 and their steps | §11's closed decisions, and Part I where built; D35's own Part I is at `e03aa15c` |
| D36, its points and readings | §11, D36; its build steps (2a, 2b, the flip, the deletions) at `671d2b57` |
| §8 items, by number | §8 |
| §8 items 14–17 on the option-5 line | §8 items 16–19 |
| §7 items before 2026-10-19 | 1–3 unchanged; 5 is 4, 6 is 5; 4 (a guarded return's message), 7 (§8 item 26) and 8 (handles as data) were closed, by compile-track frames and by erasure |
| §3.9 (what the checker holds) | §3.7 |
| §1's former four rules (before 2026-10-10) | rule 1; rule 2; reversed by rule 6; rules 2 and 3 |
| "cause A/B/C/D" | §11, the 2026-10-15 diagnosis |

**Retired documents.** Ten documents were merged into this one and deleted (`git log --diff-filter=D -- docs/`).
**Scaladoc comments cite them by their own anchors** — historical pointers, not references into this document:

| retired document | what it was | where its live content is |
| --- | --- | --- |
| `effects-as-channel.md` (v2) | the carrier as a type argument the checker solves | its mistake is §12's first entry |
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
§3.3, A6 §2.2, A8 and A9 §7, A2 (a microcontroller's exit primitive) §3.4's table. Comments dated 2026-09-11 and later
that cite §9 mean the handle record (formerly the binder mark).
