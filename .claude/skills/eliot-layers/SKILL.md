---
name: eliot-layers
description: Use when adding, moving, splitting, renaming, or deleting Eliot `.els` source files — i.e. deciding which layer (stdlib / jvm / compiler / lang) a `type`, `def`, `data`, `ability`, or `implement` belongs in, and what must be kept abstract or duplicated. Covers the layer/platform model, the two-pool (compiler vs runtime) resolution that decides what must resolve where, the abstract↔concrete merge, signature-merge gotchas, sanctioned duplication, and the verify recipe.
---

# Eliot layers: placing & moving `.els` files

Read the **"Platform-Independence via Layers"** cornerstone in CLAUDE.md first — it owns the *why* (layers =
redefinition not inheritance; merge prefers the concrete). This skill is the *operational* layer: where things
go, what must resolve where, and the mechanical traps.

## The layers (each is a `--path` root shipping an `eliot/src` source root; some also ship a `compiler/` sibling)

A module `eliot.lang.X` maps to `<layer>/eliot/src/eliot/lang/X.els` (path = `ModuleName.toPath`). Each layer is an
Eliot package in the build system's standard layout (`src/`, `compiler/`), parked at `<layer>/eliot` because Mill
already owns `<layer>/src` for the Scala — which is what that module's `at` clause in `eliot.pkg` says. The same name
in several layers/roots is **merged**. There is **no standalone `compiler` Mill module** — a layer's compile-time
contribution is a sibling `compiler/` root next to its `src/` root (today only `stdlib` ships one:
`Either`/`Option`/`Pair`/`Bound`/`Interval` and
the compile-time `Abort` and `Throw`, spelled as the jvm layer's over their own private `escapeIntrinsic`/
`exitIntrinsic`, which is what makes an `if..else` return guard reduce and a guard's `raise("…")` carry its message).

| Layer / root | Holds | On which pool |
|-------|-------|---------------|
| `lang/eliot/src` | core compiler (Scala) **and** compiler-owned `.els` (`eliot.compiler.{Meta,Reflect,Type}`, `eliot.compiler.internal.{PatternMatch,TypeMatch}`, the `Eq[Type]` instance, `Bool`'s `true`/`false`, `eliot.collection.List`) | **both** |
| `stdlib/eliot/src` | the platform-independent base: abstract `type`s, body-less `def` signatures, `ability` decls, pure platform-independent bodies/instances | **both** |
| `jvm/eliot/src` | the runtime platform: concrete `data`, `def` bodies, native leaves, ability and effect `implement`s, the three private control-flow primitives (`escapeInternal`/`withCellInternal`/`foreverInternal`) and the discharger bodies over them | runtime only (**borrowed** into the compiler pool) |
| `stdlib/eliot/compiler` | the compile-time overlay: `data`/instances the NbE checker must *evaluate at compile time* that **aren't borrowable** — the self-sufficient `Either`/`Option`/`Pair`, the `Interval` `Meta`/`Numeric` instances, the `Cell` intrinsic declarations, and the compile-time `Abort`/`Throw` over their own private escape intrinsics | compiler only (**override**) |

## The two pools — the operational key

There is **one** list of source roots (each `--path`, plus the positional program → `LangPlugin.pathKey`). Both
pools derive from it:

- **runtime pool** (codegen + the user program) = every root itself (each is a package's `src/`).
- **compiler pool** (type checking / NbE) = **the whole runtime pool, *borrowed*** *plus* each root's sibling
  `compiler/` overlay, which **override-supersedes** the borrowed definition of the same name.

Merge happens **per pool** (`UnifiedModuleValueProcessor`, keyed on `platform`; overlay files carry
`PathScan.overrideFiles`, so an overlay def wins). Two consequences drive every placement decision:

> **Anything in `stdlib` must resolve on BOTH pools.** Because the compiler pool contains the whole runtime track,
> a stdlib reference to a **pure** runtime name (a jvm `data`/fold, a base body) resolves at compile time by
> **borrowing** it — no compiler copy needed. Borrowing fails only for a name that reaches a *runtime-only leaf* (a
> bytecode op) when forced at compile time; that **stalls loudly** (the native-leaf boundary), never silently wrong.

> **A layer's compile-time track must be self-sufficient from base + its own `compiler/`.** It may borrow the
> program and pure base bodies, but **not** a sibling target (jvm) that might be absent. So a `data` the checker
> genuinely needs — the `Either`/`Option` the escape intrinsic and the guards answer through, the `Pair` the cell
> intrinsic answers — lives in `stdlib/eliot/compiler/` and duplicates jvm's runtime copy (sanctioned). Anything pure
> and already on the path (base bodies, a program's pure helpers) is **borrowed**, not duplicated.

## Where does X go?

**Quick heuristic:** *would a backend with a totally different representation (JavaScript — one numeric type;
an 8-bit MCU) need this?* If no, it's platform-specific → `jvm` (or the relevant runtime layer), never stdlib.

| You're adding… | Put it in | Notes |
|---|---|---|
| abstract `type X` (or `type X = alias`) | **stdlib** | so any signature can mention `X` on both pools |
| concrete `data X` (representation + ctor) | **jvm** (runtime); **+ `stdlib/eliot/compiler`** only if the checker needs it *self-sufficiently* at compile time (e.g. `Either`) | never stdlib base |
| field accessor / `foldX` eliminator | with the `data` (jvm; + `stdlib/eliot/compiler` for a self-sufficient one) | pure ones are **borrowed** at compile time — add a copy only for self-sufficiency, an abstract twin only if a non-borrowable name is referenced |
| pure body, same on every target (composition over abstract/native ops) | **stdlib** | e.g. `catch`, `else`, `updateState`, Function `.` |
| native leaf (backend/compiler supplies the body: bytecode op, arbitrary-precision arith, `printLineInternal`) | **jvm** (runtime) / Scala `*NativesProcessor` (compiler leaves) | the layer "bottom" |
| representation-dependent body (layout choice) | **jvm** | e.g. a `data`'s field accessor, or a native leaf's wrapper |
| `ability X` | **stdlib** | re-declared (copied) in any layer file hosting an `implement X` — see duplication below |
| `implement X[T]` with a runtime/value payload | with `T`'s `data` (jvm) or `X`'s module | body needs `T`'s ctor, which lives with the `data` |
| pure compile-time-only `implement` (no runtime payload) | **stdlib** | e.g. `Meta[Unit]` in `Unit.els` |
| **body-less** `implement X[T]` (methods are native leaves attached per-platform) | **stdlib** (base) | representation-free *declaration*, so it lives once and both pools borrow it; each platform attaches the leaf (jvm backend + `*NativesProcessor`). e.g. `Eq[String]` in `String.els`, `Compare[BigInteger]`/`Numeric[BigInteger]` — even though value-level |
| an **effect**'s default `implement` with an Eliot body | **jvm** | e.g. `implement Console { … }` over `printLineInternal`, `implement[E] Throw[E] { def raise[A](err: E): A = exitInternal(err) }`. The base declares the `effect` and nothing else; the *named* `implement` a test writes lives in the test module and is never a layer question |
| a **discharger** (`runThrow`, `catch`, `else`, `runState*`, `provide`) | body-less in **stdlib** if it touches a primitive; an ordinary **stdlib** body if it composes other dischargers | the three primitives are layer-private, so `runThrow`/`runAbort`/`runStateToPair`/`runWriterToPair`/`provide` are abstract in the base and bodied in **jvm**; `catch`/`else`/`runStateToValue`/`runStateToFinalState`/`runWriterToValue`/`runWriterToLog` are base bodies over those |
| something the compiler must evaluate at compile time, expressible in Eliot | **borrow** it if pure & already on the path; else the owning layer's **`compiler/`** (base names → `stdlib/eliot/compiler`) | not Scala `SemValue`s — the one NbE evaluator runs it |

### Ability module vs. type module — which of the two allowed homes

An `implement X[T]` may sit with ability `X` *or* with type `T` (the coherence rule above). **Default to the
type's module.** The organizing principle:

- **The ability's module holds the ability declaration + the convenience/feature functions built *on top of*
  it** — the derived combinators every implementation inherits for free (e.g. `Compare`'s `<`/`>`/`min`/`max`
  derive over `lessThanOrEqual`; `Numeric`'s `+`/`-`/`*` operators delegate to `add`/`subtract`/`multiply`).
  These are generic over the ability, not tied to any one instance.
- **A data type's module holds *that type's* implementations of the various abilities it supports** — `Int`'s
  `implement Numeric[Int]`/`Show[Int]`/`Compare[Int]` live in `Int.els`; `BigInteger`'s
  `implement Numeric[BigInteger]`/`Compare[BigInteger]` live in `BigInteger.els`. This keeps each ability module
  instance-agnostic and each type module the one place to see everything that type can do.

Put an instance in the **ability's** module only when it genuinely can't colocate with the type — an instance for
a bare abstract type declared in another layer, or an `effect`'s own default, which has no type argument to
colocate with at all and so always sits with the effect.

## The merge, mechanically

For each name, `UnifiedModuleValueProcessor.unifyValues` collects every layer's `ModuleValue` for that pool:
- **>1 concrete** (`runtime.isDefined`) → `"Has multiple implementations."`
- **signatures differ** → `"Has multiple different definitions."`
- else keep the concrete one (or the abstract one if none).

So a layer may **add a body but must not change the signature** — and the concrete def must **repeat the
whole signature**, including fixity/precedence (`infix left …`), because the merge takes the entire
`NamedValue`, not just the body.

### An `implement` can be *split* across layers

An ability implementation's identity is `(ability, type-args pattern + guard)` — not source order or position (see
[[reference_ability_impl_identity]]). So the **same `implement X[T]` may be split across layers** and its members merge
exactly like plain names: put the associated types + abstract method signatures in the base, the method *bodies* in a
platform layer. Both `implement` blocks must spell the **same pattern with the same generic names** (character-exact, so
their keys and signatures match — the usual merge discipline). Per member: `type Assoc = …` concrete in the base +
`type Assoc` abstract in the platform → merges to the base's; `def m(…): Assoc` abstract in the base + `def m(…): Assoc =
body` concrete in the platform → merges to the platform's. `Numeric[Interval[T]]` is the worked example (base owns the
body-less method signatures, jvm owns the endpoint-wise bodies). A **bare** associated-type reference inside a method
signature (`def m(…): Assoc`) auto-resolves to the concrete type (the resolver applies the impl generics) — you do
*not* re-apply the impl generics to it. Two *identical* patterns in one file are the **same** identity → a duplicate-name
error, not two instances; distinct-but-unifiable patterns are what the overlap lint checks.

## Mechanical gotchas

- **`signatureEquality` is core-structural** (`NamedValue.signatureEquality` → `Expression.structuralEquality`
  on `typeStack.signature`), and **binder + generic-parameter names participate** (`FunctionLiteral` compares
  `p.value`; `GenericParameter`/`ArgumentDefinition` compare `.name.value`). To unify an abstract declaration
  with a `data`-generated member you must mirror it **exactly**.
- **Abstract twin of a `data` accessor**: the generated accessor (`DataDefinitionDesugarer`) names its single
  parameter **`obj`** and reuses the data's generic names, so an abstract twin must spell `(obj: …)` and the same
  generic names or it will **not** unify.
- **Sanctioned duplication**: an `implement X` must be colocated with ability `X` (its module) or with the
  target type. Name resolution is per-file, so that file must **re-declare (copy)** `ability X` itself; the
  merge verifies the copies agree. This is correct — do **not** "fix" it by widening the resolver across
  sibling files. (Likewise an `implement` that uses a `data`'s ctor must sit with that `data`.) The same applies to
  an `effect`: a layer file hosting `implement Console { … }` re-declares `effect Console { … }` above it.
- **stdlib's real rule is "no platform *representation*," not "no bodies."** Pure platform-independent bodies
  (`catch`/`else`, `updateState`, `.`) and pure compile-time-only instances belong in stdlib. What stdlib must
  never carry: `data`, native leaves, or representation-dependent bodies.
- **Import cycles between an effect and its users are fine** (no cyclic facts). Two `implement`s sharing a method
  name in one module collide their generated lambda classes at JAR time — keep each type's instances in its own
  module ([[gotcha_lambda_class_collision_same_module]]). Never bundle layers into one fat/assembly jar — it
  collapses same-path layer resources ([[gotcha_assembly_jar_breaks_layers]]).

## Moving / adding a file — checklist

1. Decide the layer(s) from the table above; split abstract (`stdlib`) vs concrete (`jvm`, + `stdlib/eliot/compiler`
   only for something the checker needs self-sufficiently) if needed.
2. If a stdlib body references, at compile time, a name that **can't be borrowed** (it reaches a runtime-only leaf)
   and has **no `compiler` overlay**, add the abstract declaration in stdlib (exact-match signature for any
   `data` member — see gotchas), or an overlay copy. A *pure* referenced name is borrowed and needs nothing.
3. Keep `ability` + its `implement`s colocated; copy the `ability` decl into any file that hosts an instance.
4. If you emptied a file by moving its contents, **delete** it.
5. Centralized Scala FQN sites a *renamed/moved* module may appear in (only if the module name/package changes):
   `module/fact/ModuleName.scala` (`defaultSystemModules`), `module/fact/WellKnownTypes.scala`,
   `effect/processor/EffectMachinery.scala`, jvm `classgen/processor/NativeImplementation.scala`,
   `resolve/.../ValueResolver` (`compilerInternalAbilities`). Pure same-package adds/moves need none of these.

## Verify

- `./mill jvm.test` and `./mill lang.test` (or `./mill __.test`) — runs the whole pipeline.
- **The true two-pool check** (catches compiler-path-only breaks `jvm.test` can miss): build an end-to-end jar
  via the real driver and run it —
  `./mill examples.run jvm exe-jar examples/src/ -m <Module>` then `java -jar target/<Module>.jar`. Pick an
  example exercising the touched names (the `Effects*` programs for the effect layer).

## See also

- CLAUDE.md "Platform-Independence via Layers" (the *why*) and "The compiler is itself a platform."
- Memories: [[project_library_restructure]],
  [[feedback_minimize_scala_decompose_in_eliot]] (keep Scala thin; representation ≠ type identity),
  [[gotcha_brackets_type_namespace_marker]], [[gotcha_eliot_grammar_case_rules]],
  [[gotcha_lambda_class_collision_same_module]], [[gotcha_assembly_jar_breaks_layers]].
