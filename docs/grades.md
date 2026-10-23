# Grades: cycles, stack and peak memory

Status: **DESIGN ONLY** (2026-10-23). Nothing here is built, and no backend exists yet where a cycle means anything.
This document takes over the *flow grades* sketch that `TODO.md` carried since 2026-07-10, and re-anchors it on the
effect system as it now stands (D36, `docs/effects.md`) and on the meta channel as built through T6 (`docs/meta.md`).

## 1. The question

Robert, 2026-10-23: metas were meant to calculate *"memory allocation and runtime time / instruction count on
microcontrollers. The last one may be not metas at all. Since runtime instruction count would be modified by
optimizations and such, and would not be final at the compile stage."*

The doubt is right, and it holds for more than instruction count:

- **A value's size is a meta.** An `Int`'s width follows from its `range`, and a buffer's capacity from `List::size` or
  `String::length`. That is information the value carries, and the backend already derives a width from it
  (`docs/meta.md` §7). Sizing a static buffer on an MCU is the same move.
- **Cycles, stack depth and peak memory are grades, not metas.** They belong to a *computation*, not to a value. They
  compose along control flow (in sequence, across branches), not by joining two values. And they are final only after
  lowering, inlining and folding, so they are measured on the code a backend emits.

A grade is to a call what a meta is to a value. The two meet at one point: a grade's loop bounds are metas.

## 2. Three providers

| What | Who provides it | Example |
|---|---|---|
| **bounds** | the meta channel | a fold runs `size(list)` times; an addition is 8 bits wide because of its `range` |
| **costs** | the backend, for the code it emitted | this basic block is 14 cycles; this frame is 6 bytes |
| **budgets** | the program | `onTick` must finish in 800 cycles |

The compiler checks, for every manifest call, that the cost computed under its bounds fits the budget. That check is
post-lowering and use-site, as every other check in Eliot is (*Use-Site Verification*), and failing it is an error at
the definition holding the budget.

## 3. The grades and their algebra

Each grade is a quantity with an algebra, registered per platform, not a type and not an effect:

```eliot
implement Grade[D] { seq (+), branch (hull), zero, within (≤) }
```

| Grade | `seq` | `branch` | Notes |
|---|---|---|---|
| cycles | sum | interval hull (BCET .. WCET) | exact on a cache-less, in-order core |
| stack | a frame plus the deepest callee | max | follows from the call DAG alone, since nothing recurses |
| peak memory | `(n₁, p₁) ; (n₂, p₂) = (n₁ + n₂, max(p₁, n₁ + p₂))` | max per component | a monoid on (net, peak) pairs |

The effect clause is the powerset special case of this shape (union in sequence and across branches, `⊆` for
`within`). That is the analogy that first suggested grades, and §6 explains why a grade still cannot be a clause entry.

## 4. Why Eliot can compute them

WCET analysis is hard in most languages for reasons Eliot's cornerstones already removed:

- **No recursion** (*Total by Default*). There is no recurrence to solve, and the stack bound is a longest path in a
  DAG.
- **Every loop is a native**, and its iteration count is a `size` meta (`docs/meta.md` §17.1, built in T6). Loop-bound
  annotation, the classical hard part, is the channel's existing output.
- **Whole-program monomorphization from `main`.** Every call is known. A function value is a closure the interpretation
  already follows (`docs/meta.md` §5.2), so the closures reaching an indirect call are known too.
- **Simple cores are deterministic.** On an AVR an instruction's cycle count is in the datasheet.

## 5. Where a cost is measured

**On the residual program, after lowering.** Code folded away at compile time costs zero (`docs/reduce-and-reify.md`),
inlining moves cost from callee to caller, and register allocation decides frame sizes. A grade computed before those
would be a guess.

**A function's cost is symbolic.** The backend emits one body per monomorphization key, while metas vary per context
(`docs/meta.md` §7: metas are invisible to monomorphization identity). So the backend derives, per emitted function, a
cost expression whose free variables are the iteration counts at its loop sites and the costs of the code passed to it.
Each P5 context from `main` (`docs/meta.md` §6) then evaluates it with that context's metas.

**A native states counts, not cycles.** `looped(initial, code)` (`docs/meta.md` §11.2) says what a loop's accumulator
is; it would also say how many times `code` runs, in meta terms (`size(list)` for `foldLeftInternal`). `endless(code)`
has no count, and that is `Inf`. The cycles of the instructions around the loop are the backend's, because it emitted
them. Only a native that is opaque machine code states its own cycles (datasheet numbers), and that statement is
axiomatic like every leaf statement (`docs/meta.md` §3.2).

## 6. A budget is a demand

A budget is written by the program and checked against the derived cost, the way a `where` is. Its spelling is open.
What it is not:

- **Not a `uses` entry.** The 2026-07-10 sketch wrote `def onTick(s: State): {Timer, cycles: ≤800} Unit`. The brace row
  is now a parse error, and under D36 a clause entry *is* a handle the caller passes (`docs/effects.md` §3.1). A grade
  passes nothing. D28 already keeps abilities out of a clause for the same reason: a clause lists effects only.
- **Not a brace on the return.** A brace states a meta of the value returned, and a grade is a property of the call.

The nearest existing form is a `where`, a stated contract the checker verifies in each context. An illustrative
spelling only: `def onTick(s: State) uses Timer: Unit within cycles(800)`.

**`Inf`'s step carries the deadline.** A firmware super-loop is `forever(step)`, whose own cost is unbounded by
design. The real-time contract is the step's: one tick of the loop must fit its budget.

## 7. Consequences to accept

- **Whether a program compiles depends on the optimizer.** A compiler upgrade can move a function across its budget,
  in either direction. This is the same exposure as "fits in flash", and it is inherent once costs are measured on
  emitted code.
- **Grades are platform-scoped.** Cycles are a target's, and time is cycles divided by the clock, which is a board
  fact. A budget therefore lives where a platform is known. What a target that cannot answer a grade (the JVM) does
  with a budget must be fail-safe: it cannot silently pass one.
- **Statements are axiomatic at leaves only.** A count stated by a native is trusted like its meta. A bodied
  definition's cost is always derived, never asserted (*"axiomatic on natives, checked contracts on bodied defs"*).

## 8. What changed from the 2026-07-10 sketch

- The surface: the brace row and the grade-as-row-entry are gone (§6).
- The accounting: the sketch generalised the effect walk (`EffectUsageCollector` and its discharge-summary DAG). That
  machinery no longer exists. Accounting is a backend pass over lowered code, reading counts from the channel.
- Leaf cycle counts: the sketch had natives state them on their signatures. On a backend that emits its own code,
  natives state iteration counts instead, and the backend owns cycles (§5).
- The ladder *ranges → sizes → grades*: ranges shipped, and sizes shipped with T6's `List` domain, so the meta side is
  no longer what grades wait on.

## 9. Prerequisites

- **A backend where a grade means something**: an AVR backend, with register allocation (stack frames) and a cycle
  table.
- **The residual program**: `docs/reduce-and-reify.md` §2–4, so that code computed at compile time costs nothing.
- **Counts reaching the backend**: `looped` carrying an iteration count, and per-context metas available after
  lowering.
- **Static memory allocation** (the bootloader north star) makes peak memory mostly a static sum, with in-place reuse
  (`docs/in-place-mutation.md`) deciding what may share storage.

## 10. Out of scope

- **In-place reuse.** Linearity is a type discipline, not a quantity (`docs/in-place-mutation.md`).
- **Hardware beyond simple cores.** Caches, pipelines and branch prediction make cycle counts interval-valued at best;
  the mechanism would hold, the precision would not.

## 11. Open questions

- The budget's spelling, and whether a combinator states its cost over its code's (`ls.size.end * step.cycles + 7` in
  the sketch) or whether every such statement is a native's.
- What a target without a cycle model does with a budget: an error, or a budget that is platform-scoped code by
  construction.
- Whether peak memory is a grade at all on a target with static allocation only, or a link-time sum.
