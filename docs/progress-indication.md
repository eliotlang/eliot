# Progress indication (`--progress`) — steps 1–5 of §6 BUILT

Status: design draft, 2026-09-17; §6 steps 1 (the count), 2 (the total), 3 (the activity), 4 (the time) and 5 (the
measures and the decoration) are built, the rest is not. §2–§3 are measured constraints; §4 reconciles them with
the Claude Design exploration *"Compiler output design exploration"* (three directions — **1a ledger**, **1b phases**,
**1c quiet** — sharing one premise: output is **append-only**, every line final once printed). That premise is adopted;
what each direction assumed that this engine cannot supply is said where it matters.

## 1. What it is for

The compiler is where the work of a build happens: the build tool only starts compiler jobs, and the compiler decides
on demand what a job consists of — read, check, generate, package, and later run a shell command or flash an MCU.
`--progress` answers the two questions Maven's and Mill's output answers, for that environment:

- **how far along is this run**, and
- **roughly how long until it is done** — coarse is fine, an order of magnitude off is not.

It must stay readable (nothing that scrolls faster than a person reads), stay small (a dozen lines for a build, not a
screenful), and cost an ordinary build nothing: like `--statistics` and `--visualize-facts` it is a `diagnosticKey`, off by default, and out of
the cache identity.

## 2. What was measured

`eliot.build.Launcher` (91 files, the largest program at hand), JDK 21, through a temporary trace in
`IncrementalFactGenerator.regenerate` (reverted).

| run | wall | fact generations | in processors | cache load | cache graph build | cache persist |
|---|---|---|---|---|---|---|
| cold | 15.7 s | 27,181 | 8.9 s | — | ~0.1 s | **4.0 s** |
| one-line body change | 7.8 s | 788 | 2.1 s | 0.6 s | **1.6 s** | **2.1 s** |
| no change | 2.2 s | 478 (all world leaves) | 0.1 s | 0.6 s | 0.1 s | 0.6 s |

Seven facts about this engine decide the design.

**F1 — There is no denominator in the run itself.** Computation is demand-driven and depth-first, and nothing runs in
parallel: a requester blocks on the fact it asked for. So *demanded − completed* is the depth of the request stack,
and the Ninja/Bazel ratio `completed / discovered` reads ~100 % from the first millisecond to the last. The pipeline is
also not a sequence of phases: the first completion of a cold build is a `FileStat`, the twelfth is a
`UnifiedModuleNames`, and tokenizing, resolving and checking interleave until the end, all nested under the one root
demand (`GenerateExecutableJar`). A Maven-style "phase 3 of 7" would be fiction.

**F2 — Given a denominator, facts are a good clock.** In the cold trace, after the first fifth of the wall time (few,
expensive facts — tokenizer and parser, under a cold JIT) the raw completion count tracks wall time almost linearly:
32.6 % of facts at 40 % of the time, 59 % at 60 %, 83 % at 80 %. Weighting each fact by its key type's average cost
removes most of the early skew.

**F3 — A third to a half of a run is not fact generation at all.** Cache persistence alone is 25 % of a cold build, and
graph build + persist are 47 % of a one-line incremental one. A display driven by facts only would sit at "100 %" for four
seconds. The run has to be modelled as **phases**, of which generating facts is one.

**F4 — The prior graph's change cone is not an estimate.** For the one-line change the static cone (prior facts
reverse-reachable from the changed leaf) holds 4,483 facts; 309 non-leaf facts actually regenerated, and only 110 of
those were in the cone at all — the other ~200 are *value-less* (`SemValue`-bearing) facts, which regenerate because a
regenerating parent needs their value, not because anything under them moved. Simulating a live cone (pruned as facts
recompute equal) gives 3 % → 100 % → 82 % within the first quarter of the run, when `ModuleValue`'s equality cutoff
lands. Counting the cone is exactly the order-of-magnitude error to avoid. Do not build the estimate on it.

**F5 — A live total, measured.** The obvious history-free design is `completed / requested`, with the total growing as
facts are requested — no profile, only the counting the engine already does. On the cold trace it shows 72.7 % at 5 %
of the wall time, 94.6 % at 10 %, 99.8 % at 30 %: requested minus completed is the request chain, at most 31 deep and
9 on average, against 27,158 facts. This is not the sequential engine's fault alone. A fact's dependencies are known
only once the inputs that name them are computed — the graph is *discovered by computing it* — so requests cannot run
far ahead of completions; parallel generation widens the gap from a chain to a frontier, hundreds at best. Estimating
the undiscovered part from the request graph was simulated too (expected subtree size per key type, learned within the
run from completed generations): it wanders between 69 % and 99 % for the whole run, because the remaining work hangs
under the **singletons at the top of the chain** — the root, `UsedNames`, `main`'s `WovenValue` — and a type with one
instance has no completed sample until the run is over.

**F6 — What a run does learn early is its source files.** 87 % of the files a build will ever read are read by 20 % of
the wall time and 98 % by 30 %; module values follow (80 % at 30 %), resolved values trail (69 % at 50 %), monomorphic
values come last (14 % at 50 %). A projection "files seen × facts per file" is therefore stable from about a fifth of
the way in — *if* the ratio comes from somewhere: it is 298 for `Launcher` and 22 for `HelloWorld`, so it is a property
of a project, not of the language.

**F7 — "Facts delivered" is a usable count on a warm run too.** Count a fact once, the first time the run *has* it —
generated, accepted from the cache, or proven unchanged by the drill. The drill visits every key of a cached subtree
individually (27,890 memoized checks in the one-line-change trace), so nothing has to be stored about how big a cached
tree is; and because the graph is a DAG with heavy sharing (the stdlib sits under nearly everything), stored per-tree
sizes would count shared facts many times over, where counting distinct keys cannot. Nor do cached facts all arrive in
one jump at the start: validation short-circuits at the first moved dependency, and the rest of a regenerating fact's
inputs are validated when it asks for them. Over the working phase of that trace the count reads 2 % at 20 % of the
time, 24 % at 30 %, 32 % at 40 %, 84 % at 50 %, 97 % at 80 % — lumpy, monotone, never early, and it ends on 27,944 of
which 27,487 came from the cache. The cold trace is F2. What the count does *not* cover is F3: it is complete when
`saving cache` begins, which on that run is the half-way point.

## 3. The model

### 3.1 A run is a fixed list of phases, one of which is open-ended

| phase | source of the event | how far along *inside* it |
|---|---|---|
| `starting` | `Compiler.runWithConfiguration` entry (plugin discovery, fingerprint) | time only |
| `loading cache` | around `backend.load()` | time only |
| `working` | the fact engine (§3.2) | facts delivered |
| `saving cache` | around `buildCacheData` + `persist` | time only |
| `running` | `session.execute()` | none — the closing line is already printed (§4.6) |

The phase list is the engine's, not a plugin's, so it is the same whatever the pipeline turns out to be. `working` is
deliberately not called "compiling": with a flashing backend most of it may be an upload.

### 3.2 Inside `working`: facts delivered, out of the facts the last run delivered

The number shown is a **count of facts**, and it is the same kind of number on every run so that runs can be compared:

- **delivered** — distinct fact keys this run has, counted once each at whichever comes first: a demand for it was
  answered with a value, it was accepted from the cache, or the drill proved it unchanged (F7). A cold build delivers
  its 27,000 facts by generating them, a warm one mostly from the cache; both count to the same total. A fact a
  generation merely *pushes* is not counted until something asks for it: counting registrations instead made a cold
  `Strings` build 1,563 facts and its warm rerun 1,272, the difference being 300 `ModuleValue`s every parsed file
  pushes and only the used ones are ever demanded. Counted on demand, cold, changed and unchanged runs of `Strings`
  all end on 3,989, with identical counts per key type.
- **total** — what the previous run of this configuration ended on, read from the **profile file**
  `<target>/.eliot-progress-<configFingerprint>`. The fingerprint is that of the *command line's* configuration, not
  the session's effective one, so the file can be read before any plugin configures a session and the header can say
  `first build`; it is the same digest function, so it names the same configurations. It lives beside the cache but is **not** discarded with it, so the
  cold build after a compiler upgrade still has its total. A few kilobytes of text, written at the end of every
  `--progress` run (a failed run does not update the total). When a program has grown, `delivered` overtakes `total`
  and the total simply follows it — `[28,901/28,901]` and still working is honest, 103 % is not.

A big denominator is what makes this steady: one fact is 0.004 % of it. It needs no run classes and no
reclassification, because cold, changed and unchanged runs all count towards the same figure, and it is 1a's
`[n/total]` column after all — with the total coming from the last run instead of from a task graph.

It costs one hook in the engine, which is the one departure from `--statistics`' "engine untouched": acceptance and
validation never reach a processor, so a wrapper around the processor tree cannot see them. `IncrementalFactGenerator`
takes an optional tracker and tells it one thing, `delivered(key, fromCache)`, from three places (a demand answered, an
acceptance, a drill that held); with no tracker the code path is today's. §3.5's activity adds more events to the
same hook (step 3): working out a fact *started* (key, requester) and *ended* — the requester waits in between — a
generation is *waiting* for a fact someone else is working out and has *resumed*, and a fact recomputed by the drill
came out *changed*. A validation read has an empty ancestor chain on purpose (the recursion guard), so its requester
is passed separately: the fact whose validation asked. With no tracker, a request does exactly what it did before.

### 3.3 The very first build: the count alone

With no profile there is no total and no honest way to invent one (F1, F5). The first build shows the count of facts
delivered, the elapsed time and the current activity — regular feedback that the run is alive and moving, which is
most of what a progress display is for — and ends by writing the total the next run will use.

### 3.4 Time, on top of the count (second stage)

A count is not a clock: cached facts cost nothing, generated ones cost milliseconds, and the time-only phases deliver
no facts at all (F3, F7). So the ETA column is fed by a separate, time-based estimate, added once the count is in
place. It needs per-key-type **self time**, and the tracker already has it: §3.5's events say when a generation starts,
ends and waits, and the time it waits for nothing is its own — the union of its waits left out, never their sum (§3.7).
When a generation ends, that time and a count of one are added to its key type. This covers what a wrapper around the
processor tree (the first plan, `ProgressCompilerProcessor`, not built) would have missed: acceptance and validation
never reach a processor, and on a warm run they are most of the work. The profile file gains one **history per run
class** — `cold` (no prior cache), `changed` (a fact recomputed for validation came out different), `unchanged` — each
an exponentially weighted average (the newest run weighing half) over the successful runs of that class: the facts
delivered, per key type `(facts worked out, total self time)`, and the wall time of each time-only phase. The class is
`cold` or `unchanged` once the cache is loaded, and flips to `changed` at the first recompute that differs (152 ms into
the measured run).

```
size       = total / history facts        -- once working is over: delivered / history facts
doneH      = Σ_type  worked[type] × avgH[type]   +  each in-flight fact's own time, capped at its avgH
expectedH  = Σ_type  max(history[type] × size, worked[type]) × avgH[type]
speed      = (liveElapsedInWorking + prior) / (doneH + prior)      -- prior = expectedH / 10
eta        = (expectedH − doneH) × speed  +  Σ remaining time-only phases (from the history, × speed; saving × size)
```

A single long fact — a 200 ms class generation today, a 12 s firmware upload tomorrow — keeps the ETA falling, because
the fact being generated is interpolated **in time** against its type's average; and a plugin nobody told the progress
system about is estimated correctly anyway, since a key type needs no description to be weighed (one the history does
not know is weighed at its live time). `prior` keeps the first, noisy facts from swinging `speed`. `size` is what lets
an averaged history follow a program that grew: the history's per-type counts lag behind the last run's total, and
scaling them by it puts the estimate right on the very next run (§6, step 4). A run that delivers more facts than the
total has **outgrown** its history, and what it has left is unknown (F5): it shows no ETA until `working` is over,
rather than `finishing` for as long as it keeps growing; the cache it then saves is scaled by its size, since saving is
proportional to the facts. The same self times give the closing `time` line (§4.4). Whether a percentage is printed as
well, and whether it is the count's or time's, waits until both can be looked at side by side.

### 3.5 What the user is told is being done

Key types are internal (`UncurriedMonomorphicValue$Key`); what a user recognises is a **verb and a subject**:
*checking `eliot.build.resolve.Resolution`*, *packaging `Launcher.jar`*, *flashing `firmware.hex`*. Plugins supply
that mapping for the key types they own, as data:

```scala
/** How a plugin's facts read to a user. Partial: a key nobody describes is still counted and weighed. */
trait ProgressDescriber {
  def describe(key: CompilerFactKey[?]): Option[ProgressActivity]   // ProgressActivity(verb, subject, input)
}
```

contributed through `CompilerPlugin` (`def progressDescriber: Option[ProgressDescriber] = None`). Proposed verbs: lang
— `reading` (`FileStat`, `FileContent`, `PathScan`), `parsing` (tokens, AST, core), `resolving` (module values through
row elaboration), `checking` (both monomorphize tracks and their channel riders), `lowering` (used, uncurry); jvm —
`generating` (`GeneratedModule`), `packaging` (`GenerateExecutableJar`). The subject is a **module or a file, never a
value**: values change 2,500 times a second, modules a few times a second. As built, lang's `checking` also covers
saturation, the binding suppliers and the ability checks, and `lowering` covers reconciliation; `apidoc` describes
nothing yet, so its own work shows as `working`.

`input` marks a fact that *is* one of the program's inputs — lang's `FileContent` — so that its change is reported
(trigger 3). It is not the world leaf `FileStat`: a file written within the
settle margin carries a nonce and never compares equal to itself (`FileStat.unsettled`), so every recently saved file
would be reported changed whether it was or not. Its content, one level up, compares honestly.

The tracker keeps the **set of generations in flight** (key, parent, start time, how many facts it is waiting for),
and the activity is **sampled** from it by the line writer rather than pushed by the engine: the longest-running
in-flight generation that is waiting for nothing, walked up its parents to the first described key — its **owner**.
A generation waits while a fact it started is worked out, and also while it waits for one another fiber is working out
(parallel generation, or a validation check already running) — the second is told only when the fact is not already
there, so asking again for a fact the run has costs nothing. The same events give each described generation its **time**: whenever a generation
waits for nothing, the time is charged to its owner. So a described fact's time is its own work plus that of every
undescribed fact it asked for, and never that of a described fact it asked for — which is what trigger 4 and the
heartbeat show. It is wall time spent working, not CPU time, and under parallel generation two working leaves with one
owner both charge it.
The sample is a statistical profile: the label shows where the time is going, which is what the user wants to know,
and a label that flickers is a label over cheap work that the sampler mostly misses. Today the set is one chain; under
parallel generation it is a tree with several leaves, and the same rule picks the one most worth naming (§3.7).

### 3.6 A long-running fact may report its own progress (deferred until one exists)

Time interpolation (§3.4) already keeps the ETA falling through an upload. What it cannot show is *"43 % · 12 kB/s"*.
For that, `CompilationProcess` gains a no-op-by-default `reportProgress(done: Long, total: Long, detail: String)`,
surfaced on `CompilerIO`; the tracker attributes it to the generation that called it and a heartbeat line (§4.2) shows
it while that fact is the sampled one. It changes no fact and records no dependency. Build it with the first processor that
needs it, not before.

## 4. What is displayed

### 3.7 Under parallel generation

Generation is sequential today only because that is easier to debug; a processor may ask for a list of facts in
parallel at any time, so nothing here may rest on one-at-a-time — and the engine it hooks into is already written for
concurrency (a `Deferred` per key, a fiber per generation).

- **The count and the total do not change at all.** A fact is delivered once whatever the interleaving — the engine's
  per-key `Deferred` and memoized unchanged-check guarantee it — and the tracker is a concurrent set and two adders.
  F5 still holds: parallel requests widen *requested − completed* from a chain to a frontier, not to the program.
- **Activity** is sampled from the in-flight set (§3.5), which is why it is a set and not "the current key".
- **Self time must subtract the *union* of a generation's waits, not their sum.** `TimedCompilationProcess` adds up
  every `getFact` wait, which is right only while waits cannot overlap; ten facts requested in parallel would
  subtract ten overlapping waits and drive the self time negative. The tracker counts outstanding waits and
  accumulates a generation's time only while the count is zero. (`--statistics` has the same assumption and would need the
  same change on the day generation goes parallel.)
- **The ETA adapts by itself.** `speed` is live wall time over historical self time, so a run going three ways
  parallel simply measures a speed of about a third; the running-fact interpolation sums over in-flight leaves. What
  degrades is a run whose width changes a lot between its beginning and its end, which the smoothing lags behind.
- **The `time` line becomes CPU time** and can sum to more than the wall time on the `ok` line; it says so
  (`time (cpu)`) once that can happen.
- **Output is unaffected**: only the line writer's fiber prints, from atomically read state.

### 4.1 Append-only lines, never a repaint

The design exploration's premise is taken whole: **every line is final once printed**, so the output is the same bytes
in a terminal, a CI log and a pipe, minus colour. Progress comes from a column that advances and from lines that keep
arriving, not from redrawing. Beyond the reasons the exploration gives, it is the right fit for *this* compiler: it
runs child processes, will run shell commands and flashers, and ends `run` mode by handing the terminal to the user's
program. A repainted region needs one owner of the terminal — replaced `System.out`/`System.err`, a log4j appender
that follows them, a suspend protocol for every child with inherited stdio, terminal-width detection — and an
append-only stream needs none of it: anything else that writes simply lands between two lines. (An earlier draft of
this document proposed a two-line live region; it is withdrawn for exactly that reason.)

What cannot be taken from the mock-ups, by direction:

- **1a's `[n/total]` counter is kept, with a different total.** "Known after resolve" describes a static task graph,
  which this is not (F1); the total is the number of facts the previous run delivered (§3.2), and on a first build the
  column holds the running count alone. **One line per task** is what goes: there are 27,000 generations, and the user-sized units are not tasks either —
  "typecheck `sensor.els`" is never *finished* until the run is, because any later demand may instantiate another of
  its values. Lines are therefore **samples and milestones** of one run, not a ledger of completed tasks. What survives
  is the grammar: fixed columns, every line standing alone, slow steps visible, a heartbeat on a long one.
- **1b's phase headings.** Stages interleave from the first millisecond to the last (F1), so a heading with its
  indented details cannot be printed *as the stage ends*. Its content survives as the **closing block**, printed once
  the run is over: where the time went by verb, what was cached, the artefact's budget with the delta since last time.
- **1c is what a fast build looks like anyway.** A run that ends before its first progress line is due prints only the
  closing line, which is 1c's three lines without a mode switch.

### 4.2 The progress line

```
[14,211/27,181] checking    eliot.build.resolve.Resolution                8.4s   ~7s left
└delivered/total┘ └verb───┘ └subject, then detail─────────────────┘  └elapsed┘ └eta────┘
```

Fixed columns, as in 1a, so the eye can run down any one of them. The counter is §3.2's; verb and subject are §3.5's
activity, **sampled at the moment the line is written**; the right-hand columns are wall time so far and, from the
second stage on (§3.4), the rounded ETA (the nearest second under 10 s, 5 s under a minute, 15 s above; `finishing`
under one second; absent on a first build, for a class with no history yet, and while a run has outgrown its total).

The header waits for the cache to be loaded — whether there was one decides the class, and so the estimate it states —
but never longer than a second.

A line is written when a trigger fires **and at least one second has passed since the last one** — that floor is the
whole of "must not scroll faster than it can be read":

1. the run's phase changed (`loading cache` → working → `saving cache`);
2. the count crossed another tenth of the total (on a first build: doubled since the last line);
3. an input was found changed (§3.5's `input`) — the line says which file (`changed  src/A.els · and 2 more`, each
   file named at most once), which is the exploration's *"why was this rebuilt"* answered with the one thing the engine
   knows for certain;
4. a single described fact took a second or more (its time as §3.5 charges it) — it gets a line of its own with its
   duration (`checking  eliot.build.git.Git · 1.2s`), 1a's *"slow steps are visible"*; several are printed one per
   line;
5. **heartbeat**: five seconds with no line. It names the sampled activity (§3.5) and how long that fact has been running,
   with its sub-progress if it reports any (§3.6). The interval stretches to 15 s after a minute and 60 s after ten, so
   a long upload is a few lines, not hundreds.

When several fire, the first in this order decides what the line says: a changed input, a slow step, then the current
activity. A line for triggers 3 or 4 does not count as showing the phase, so a phase change they pre-empt still gets
its line a second later.

That bounds a run at roughly ten milestone lines plus its slow steps and heartbeats, whatever its size.

### 4.3 A whole run

Cold, with a profile (the header carries the estimate, because the most useful moment for an ETA is before anything
has happened):

```
$ eliot exe-jar -m eliot.build.Launcher
eliot 0.5 · jvm exe-jar · eliot.build.Launcher · full build, about 16s
[   335/27,181] parsing     eliot/build/model/Descriptor.els              1.0s  ~15s left
[ 1,298/27,181] resolving   eliot.build.model.Descriptor                  2.1s  ~14s left
[ 4,279/27,181] checking    eliot.build.git.Git                           3.4s  ~12s left
[ 8,900/27,181] checking    eliot.build.resolve.Resolution                5.0s  ~11s left
   ⋮
[25,621/27,181] generating  eliot.build.Launcher                         10.4s   ~5s left
[27,181/27,181] saving cache                                             11.1s   ~4s left
[27,181/27,181] saving cache                                             14.2s   ~1s left
time   parsing 1.5s · resolving 1.9s · checking 3.1s · generating 1.3s · cache 4.1s
ok     Launcher.jar 412 KB (+88 B) · 27,181 facts, 0 from cache · 15.7s
```

One line changed — the same total, reached mostly from the cache (F7's curve):

```
eliot 0.5 · jvm exe-jar · eliot.build.Launcher · about 2s if nothing changed
[   597/27,944] changed     eliot/build/model/Version.els                 0.8s   ~7s left
[ 8,950/27,944] generating  eliot.build.model.Version                     2.6s   ~5s left
[27,010/27,944] generating  eliot.build.Launcher                          3.7s   ~4s left
[27,944/27,944] saving cache                                              4.1s   ~4s left
[27,944/27,944] saving cache                                              6.6s   ~1s left
time   generating 1.0s · parsing 0.3s · checking 0.1s · cache 4.3s
ok     Launcher.jar 412 KB (+0 B) · 27,944 facts, 27,487 from cache · 7.8s
```

Nothing changed — no progress line comes due, so this is 1c:

```
eliot 0.5 · jvm exe-jar · eliot.build.Launcher · about 2s if nothing changed
ok     Launcher.jar 412 KB · up to date · 27,944 facts, all from cache · 2.2s
```

First build ever, and a long single fact on a future target:

```
eliot 0.5 · jvm exe-jar · eliot.build.Launcher · first build
[ 1,298 facts ] parsing     eliot/build/model/Descriptor.els              2.1s
[ 4,279 facts ] checking    eliot.build.git.Git                           3.4s

[   398/   412] flashing    firmware.hex · 43% · 12.4 kB/s  … 10s        16.0s   ~9s left
```

Failure — the exploration's closing vocabulary, which happens to be true of this compiler (a failed build leaves no
artefact behind):

```
failed 3 errors · first at eliot/build/model/Version.els:56 · 4.1s · nothing written
```

### 4.4 The closing block

- **`time`** — one line, self time by verb — each described fact's time as §3.5 charges it, time no described fact
  owns as `working` — longest first, plus `cache` for loading and saving it. It is 1b's per-phase timing and the exploration's *"what took the time"*, moved to the only place it can be
  true. It is also how a user learns that half their incremental build is cache work (§2), which today nothing tells
  them. Omitted when the run printed no progress line.
- **`ok` / `failed`** — always printed. `N facts, M from cache` is the exploration's *"caching is stated, not
  hidden"*, and they are §3.2's own two counters, so the closing line and the last progress line agree. The clock starts at
  JVM start (`RuntimeMXBean.getStartTime`; `ProcessHandle`'s start instant is up to a second late on Linux), so the ~40 % of a warm build that precedes `main` is in
  the figure. `failed` repeats the error count and the first error's position so it survives the scroll; the
  diagnostics themselves print exactly as today, above it.
- **The budget** — *"the number an embedded developer came for"*. The target plugin supplies measures of what it
  produced (`CompilerPlugin.progressMeasures`: name, value, unit, optional limit): `jar 412 KB` today, `flash 2.14 KB /
  256 KB · ram 32 B · stack 184 B` on an MCU target. The progress system stores the last values in the history file
  and prints the **delta** — 1b's `Δ last`, for free. One or two measures ride the `ok` line; more become 1b's table
  above it, one `size` line each. As built, a measure reads `name value[ / limit] (±delta)` — `Launcher.jar 412 KB
  (+88 B)`, `flash 2.14 KB / 256 KB (+12 B)`, a value over its limit painted as a failure — in binary units to three
  digits. There is no delta without a previous value of that name, and a run of class `unchanged` says `up to date`
  in its place: nothing was recomputed differently, so the artefact is the one the last run wrote (a jar deleted or
  replaced since is written again by the plugin, from facts that did not change, so the run stays `unchanged`). The profile keeps only the last successful run's measures,
  as `measure <value> <name>` lines, the name last so it may hold spaces. Measuring is fail-safe: a measure that cannot
  be taken is logged and the `ok` line goes without it.

### 4.5 Vocabulary, colour, degradation

From the exploration, unchanged: status words are fixed and lowercase — `ok` `warn` `error` `failed` `cached` — and
carry the meaning on their own; colour is decoration and no signal is glyph-only. ANSI roles follow the design
system's terminal kinds: bracket, times and separators *faint*; verb *muted*; subject default; `ok` signal green
(`#2EC27E` under `COLORTERM=truecolor`, else ANSI green); `changed` and `warn` copper/yellow; `error`/`failed` red.
As built (`ProgressStyle`), the 24-bit colours are the design system's own tokens — `--faint`, `--muted`, `--pass`,
`--warn`, `--fail` — and the 16-colour ones bright black (faint), dim (muted), green, yellow and red. The brand rule
"never green and copper together" is about accents; here copper is the `--warn` *status* colour the same palette
defines beside `--pass`. Columns are laid out on the text first and painted after, so a coloured line has the same
columns as a plain one.
`✓` `·` `…` degrade to `ok` `-` `...` when the locale is not UTF-8. Lines are cut to 100 columns; the subject is what
gives.

Not a terminal, or `TERM=dumb`: the **log form** — no colour, and each line is prefixed with the time of day
(`11:42:03 [14,211/27,181] checking …`), as in the mock-up. `NO_COLOR` (set and not empty, as no-color.org has it) only
takes the colour away; a terminal user who asked for no colour still has a terminal and needs no clock. One deviation:
the mock-up drops heartbeats without a tty, and this keeps them, at least 30 s apart (stretching to 60 s after ten
minutes, as in a terminal) — CI is where a silent ten-minute step gets a job killed. Terminal detection only chooses
decoration now, so getting it wrong is harmless (`System.console() != null`, plus `Console.isTerminal` on JDK ≥ 22,
called reflectively). UTF-8 is read from `stderr.encoding`, else `native.encoding` — the JVM's own reading of the locale.
Run under `./mill examples.run`, the compiler's stderr is a pipe, so it prints the log form; the terminal form shows
when the compiler is started directly (`out/examples/launcher.dest/run …`).

### 4.6 Streams, and `run` mode

Progress lines go to **stderr**. The mock-up puts the build log on stdout so `2>errors.txt` isolates diagnostics; here
`run` mode makes stdout the *compiled program's*, and `eliot run -m Main | jq` has to keep working, which is the
reason Cargo made the same choice. It is D5 below because it is a convention worth deciding once for the whole CLI.
In `run` mode the closing block is printed before `session.execute()`, so the program's output follows a finished
build log. The existing `INFO Generated executable jar` log line is redundant with `ok` and should drop to debug.

### 4.7 Taken later, not now

`--log=json` (one object per line — the same `ProgressSnapshot`s and closing measures, and the natural way for the
build tool to aggregate several compiler jobs); watch mode's timestamped paragraphs (a resident `CompilationSession`
looping `compileOnce` already fits); *"what did it decide about my types"* — effect rows, instance counts, `kept 41 of
58 functions` — which are facts the compiler has but are a report, not progress; and `next:` suggestions.

## 5. Architecture

Everything lives in a new `eliotc/…/progress/` package, created only when asked for. Processors are untouched; the
engine gains the one optional hook of §3.2, whose events the time stage (§3.4) reads as well.

| piece | role |
|---|---|
| `ProgressTracker` | the mutable core: the set of delivered keys with its two counters (delivered, from cache), the phases entered and when, the run class, the set of generations in flight, sub-progress; per-key-type `LongAdder`s (generations, self nanos) and per-verb time. Written by the engine hook and the session, read by the line writer. |
| `ProgressProfile`, `ProgressHistory` | reads/writes the profile file: the total, the per-class histories (`ProgressRunClass`), later the last artefact measures. |
| `ProgressEstimator` | pure: `(total, history, snapshot) ⇒ time left`. All of §3.4's arithmetic, tested against recorded runs with no engine; the tracker puts its answer in the snapshot. |
| `ProgressLineWriter` | the one renderer: a fiber waking a few times a second, asking the tracker for a snapshot and applying §4.2's triggers and one-second floor; prints the header and the closing block. It wakes a few times a second and reads atomics, so it costs the build nothing measurable. It owns nothing — no stream is replaced, no cursor is moved. |
| `ProgressDescriber`, `progressMeasures` | the two plugin-facing pieces: §3.5's verb/subject mapping, and §4.4's measures of the produced artefact. |

Phase events come from where `PhaseTimings` already brackets the same code (`CompilationSession.create`,
`compileOnce`, `persist`), so `compileOnce` takes an `Option[ProgressTracker]` beside the tracker and statistics it
already takes. `ProgressSnapshot` is writer-independent on purpose: `--log=json` is a second writer over the same
snapshots, and the LSP can map them to `$/progress` work-done notifications, neither touching the model.

**Cost gate.** `--statistics` inflates a build ~20 % because it wraps every processor. The count is one concurrent-set
insert per fact; the time stage adds a map lookup and two adder updates per generation to §3.5's clock reads. The gate for shipping is a measured **< 2 %** on that cold build, flag on vs. off; with the flag off
the hook is a `None`, and nothing is wrapped.

Measured for step 1 (the count alone), `eliot.build.Launcher`, flag off vs. on, alternated: six cold builds each,
median 14.86 s vs. 14.82 s; eight warm builds each, mean 2.820 s vs. 2.832 s (+0.4 %). Both are inside the run-to-run
noise (±0.5 s cold), which is itself larger than the gate, so what the measurement shows is that the count costs
nothing it can resolve. Both runs count 28,734 facts; the warm one takes 28,192 of them from the cache.

## 6. Work list

Staged so that each step is something a user can already run.

1. **The count — BUILT.** The engine hook, `ProgressTracker`, phase events, `ProgressLineWriter` with its triggers
   and one-second floor (its decision is the pure `ProgressLineWriter.step`, tested against two traces recorded from
   `Strings` — cold and after a one-line change — asserting the exact lines). `--progress` prints the header,
   first-build style lines (`[ 4,279 facts ] working`, the phase as the verb) and the `ok` / `failed` line. The header
   names the target through `CompilerPlugin.progressTarget` (`eliot · jvm exe-jar · HelloWorld`; there is no compiler
   version to print yet). Triggers 3 and 4 wait for step 3, since both need a described key; the failure line has no
   `nothing written`, which the engine cannot vouch for across targets. Gate: the cost measurement above.
2. **The total — BUILT.** `ProgressProfile` reads and writes the profile file (text, `name value` per line, unknown
   lines ignored so step 4 can add to it; both directions fail-safe, the write atomic). `ProgressTracker` is created
   with the expected total and its snapshot carries `total = expected max delivered`. Lines become `[n/total]`, the
   counter padded to the first-build column's width, trigger 2 fires on a crossed tenth of the total, and the header
   says `first build` when there is no total. Only a run that succeeded writes the total. On `Strings`: first build
   `[ 3,975 facts ]` ending on 3,990; its warm rerun 3,990 (3,730 from cache); a cold build with the profile
   `[  176/3,990]` … `[3,355/3,990]`.
3. **What is being done — BUILT.** `ProgressDescriber` (`CompilerPlugin.progressDescriber`, every discovered plugin
   asked) for `lang` (`LangProgressDescriber`) and `jvm` (`JvmProgressDescriber`); the tracker's in-flight set and
   owner time (§3.5), tested against the engine's own graph harness and a hand-driven clock; progress lines name the
   activity while the run is `working`, triggers 3 and 4, and the heartbeat's `… 4.2s`. On `Launcher`, cold: `parsing
   lang/eliot/src/eliot/lang/Eq.els`, `checking eliot.build.Launcher`, `checking eliot.build.git.Git`, `generating
   eliot.build.resolve.Resolution`, … — the deepest described fact wins, so resolution nested inside a check reads
   `resolving`. After a one-line edit of `Strings`: `[4,046/4,046] changed examples/src/Strings.els`. Cost, `eliot.build.Launcher`,
   ten alternated cold builds each: flag off 14.83 s mean / 14.75 s median, on 15.08 s / 15.05 s — +1.7 % / +2.0 %, at
   the gate, with the minimums equal and a run-to-run spread (±1 %) as large as the difference. Against step 2,
   interleaved in one session, warm builds cost +1 % more with the flag and nothing without it. Measured on the way,
   and worth keeping: the tracker's own methods take ~120 ms of a cold build (plus step 1's ~85 ms of `delivered`);
   building every activity's subject up front was a third of that, so a subject is worked out when read; looking
   generations up by key *identity* instead of by (deep) key equality changed nothing measurable and was not kept; a
   repeated request for a fact already there is never recorded as a wait.
4. **Time — BUILT.** Self time and per-verb time from the tracker's own events (§3.4; no processor wrapper), the run
   class (`cacheLoaded`, flipped by `changed`), the per-class histories in the profile file (`cold facts …`,
   `cold phase SavingCache …`, `cold type <key class> <count> <nanos>`, every line prefixed by its class),
   `ProgressEstimator` tested as a pure function against five runs of `eliot.build.Launcher` recorded to
   `eliotc/test/resources/progress/` (cold, unchanged, a one-line change, a cold build with the history of `Strings`
   — seven times smaller — and the cold build after it); the ETA column, the header's estimate, the `time` line.
   Measured on those runs, the estimate against the time actually left, from a fifth of the way in: cold within 12 %
   (at 1.4 s, still in the cold JIT, 43 % over); changed within 9 % once the change is found (until then it is
   estimated as unchanged, 1.5 s); unchanged within 0.3 s; after the seven-fold growth no estimate while working and
   the save 37 % under; the run after it within 28 %, the header saying `about 15s` for a 15.9 s run. Headers: `full
   build, about 15s` (15.1 s), `about 3s if nothing changed` (2.8 s). Rejected on the way: counting the history's
   per-type facts as they are — an averaged history of a grown program undercounts, and the ETA read `finishing`
   half-way; scaling by the last total fixed it. Cost, `eliot.build.Launcher`, eight alternated cold builds each, step 3
   and step 4 in one session: flag off 15.13 s / 15.12 s mean (equal, as it must be); flag on 15.47 s at step 3
   (+2.3 %, above the +1.7 % step 3 measured on its own day) and 15.59 s at step 4 (+3.1 %, median +3.5 %). Step 4's own
   share is +0.8 %, inside one standard deviation (0.24 s), but the flag as a whole is now **over the 2 % gate** in this
   measurement; with the flag off it costs nothing.
5. **Measures and decoration — BUILT.** `CompilerPlugin.progressMeasures` (asked of the selected target after a
   successful compilation, before `execute`) and `ProgressMeasure` (`Bytes` or `Count`, optional limit); `jvm` measures
   its jar, named by its file. The profile keeps the last values (§4.4), and the closing block shows them with their
   delta, or `up to date`. `ProgressStyle` decides the decoration from the environment (§4.5): colour by role, 24-bit or
   ANSI, ASCII separators outside UTF-8, and the log form with its time of day and its 30 s heartbeat. The rendering
   functions stay pure and take the style; the time of day is the one thing added at print time. On `Strings`, in a
   terminal: `ok Strings.jar 32.2 KB (+0 B) · 3,990 facts, 3,725 from cache · 1.7s`; under `./mill examples.run`:
   `13:36:04 eliot · jvm exe-jar · Strings`. Neither piece touches the engine, so the cost measurement of step 4 stands.
6. Deferred: `reportProgress` (§3.6) with the first long-running processor; `--log=json`; LSP `$/progress`.

## 7. Open decisions

- **D1 — what bare `eliotc` prints.** The exploration makes quiet (1c) the default and detail opt-in. This document
  only adds `--progress`; whether the `ok` / `failed` line becomes the compiler's ordinary output without the flag is a
  separate, smaller change — it needs no tracker, only the generator's counts.
- **D2 — a better `changed` ETA.** History is within ~2× here. If larger programs show worse, the candidate is a
  **cost-weighted** live cone *plus* the value-less dependencies of its members (F4 shows why the plain cone fails),
  which also needs world leaves validated up front so the cone is known early — an engine ordering change, made only
  on a measurement that history is not enough.
- **D3 — the build tool.** `eliot <package>` runs one compiler per `compiler` line and inherits stdio, so each job
  prints its own header and closing line, which reads naturally one after the other; the tool's own work (resolve, fetch, assets) precedes them
  unannounced. Whether it passes `--progress` by default on a terminal, and whether it adopts the same line
  grammar for its own phases, is that repository's decision; `--log=json` is what it would aggregate from.
- **D4 — a live error count.** Deliberately absent: diagnostics are only known to be *this program's* after the run
  (`currentErrors` filters by reachability), so a live count would show errors that then vanish. For the same reason
  1a's inline `warn` lines are not taken: a diagnostic is printed once, after the run, when it is known to be real.
- **D6 — fewer first builds, and something for the ones left.** §3.2 keys the profile by configuration, so every new
  `-m` is a first build, and keeps it under `<target>`, so every clean is one. Candidate: look a profile up in tiers —
  this configuration, then **this project** (any configuration: per-type costs and F6's facts-per-file ratio carry
  over), then a user-wide one, then a default shipped with the compiler — and below the first tier *project* the
  total from what the run has discovered (F6), printed as rough (`[4,279/~27,000]`). Not needed for the first cut:
  a first build showing its count alone (§3.3) is accepted as the starting point. F5 is why the
  fallback is a looser history and not a live total.
- **D5 — stderr or stdout** for the progress lines (§4.6). Proposed: stderr, because of `run` mode.

Two things the measurements showed that are not this feature's to fix: a one-line body change regenerates **all 44**
`GeneratedModule`s, and spends 3.7 s building and persisting a cache graph in which 788 facts moved.
