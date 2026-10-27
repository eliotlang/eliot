package com.vanillasource.eliot.eliotc.compiler

import cats.data.Chain
import cats.effect.{IO, SyncIO}
import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator.Slot
import com.vanillasource.eliot.eliotc.compiler.cache.{CacheEntry, DependencyTrackingProcess, FactCacheData}
import com.vanillasource.eliot.eliotc.feedback.{CompilerError, Logging}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.runCompilerIO
import com.vanillasource.eliot.eliotc.processor.*
import com.vanillasource.eliot.eliotc.progress.ProgressTracker

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Cache-aware fact generator implementing incremental compilation by backward, demand-driven validation.
  *
  * It keeps the core of a plain generator — a slot per key, so each fact is computed at most once per run, and an
  * ancestor chain for recursion detection threaded explicitly through the per-generation [[DependencyTrackingProcess]]
  * (the `ancestors` parameter of [[getFact]]) — and decides, per dependency, whether it changed since last run via
  * [[depUnchanged]]:
  *
  *   - a dependency with a **stored value** (every leaf, and every persistable derived fact) is recomputed and compared
  *     against what the cache holds ([[com.vanillasource.eliot.eliotc.compiler.cache.CacheEntry.matches]]) — this is
  *     the equality cutoff: a changed leaf whose derived value recomputes equal stops propagation. A backend that
  *     stores values by content hash answers it without reading the stored side at all;
  *   - a **derived** dependency is validated **structurally** by drilling through its recorded `directDeps` to the
  *     leaves, *without materialising anything*. A fact is a pure function of its recorded inputs, so dependencies that
  *     all hold mean the fact holds — there is nothing to recompute and nothing to compare. A value-less one has its
  *     edges-only entry carried forward so it stays drillable; a value-bearing one simply stays untouched and is
  *     retained. Only when a dependency has genuinely moved is the fact recomputed and compared.
  *
  * Consequently a no-change run materialises **no fact value at all** beyond the world leaves it must re-read — not a
  * `SemValue`, not a monomorphic value, nothing the build does not actually consume. A fact is regenerated only when a
  * genuinely changed dependent reads it.
  *
  * Leaf facts — those whose recorded dependency set is empty, e.g. a `stat` of a source file — are always regenerated,
  * forming the boundary with the external world. Failures are never cached, so a fact that failed has no prior entry
  * and is regenerated (re-emitting its error) on every run until fixed.
  *
  * Validation therefore runs generations the compilation itself never asked for — including, when the source dropped a
  * name, generations that can only fail now. Their diagnostics are not this program's, so every diagnostic is recorded
  * with the generation that produced it ([[AttributedError]]) and only the reachable ones are reported (see
  * [[currentErrors]]).
  *
  * With an empty `prior` (cold start) every fact is regenerated, so behavior matches a non-incremental generator plus
  * harmless dependency recording.
  *
  * An optional [[ProgressTracker]] is told each fact the run comes to have: demanded and answered with a value,
  * accepted from the cache, or proven unchanged by the drill. Acceptance and validation never reach a processor, so
  * this is the one place all three can be seen; without a tracker nothing is recorded. A fact a generation merely
  * *pushed* is not counted until something asks for it — a cold build would otherwise count every name of every file
  * it parsed, and a warm one only those the program uses, and the two counts would not compare. The tracker is also
  * told when working out a fact starts and ends, which generation asked for it, when a generation waits for a fact,
  * and when a fact recomputed for validation came out different — what it needs to say what the run is doing.
  *
  * `registered` is handed each fact the moment it has its value, registered by a generation or accepted from the cache,
  * which is every value [[buildCacheData]] will hold: a cache written behind the compilation encodes them as they come.

  *
  * **It is synchronous.** A fact not yet there is generated on the stack of the read that asks for it, and the read
  * returns when it is done. What is outside the compilation asks through [[demand]], which runs the compilation on a
  * thread of its own ([[CompileThread]]), and reads the results with the `IO` queries below once the compilation has
  * ended. A fact found *in progress on the reading thread* is a cycle the chain did not show — a validation read starts
  * a fresh chain — and is reported as an internal error rather than waited for, which would never end.
  *
  * **It may be parallel.** A generation can hint the facts it will read ([[prefetch]]); when `parallel` is set, each
  * hinted fact not yet started is started on a virtual thread of its own ([[FactThreads]]), and a read that finds a fact
  * running on another thread waits for it. A generation itself never forks, so its reads are recorded in the order it
  * makes them, as in a sequential run. Without `parallel` a hint does nothing, and the run is the sequential one, step for
  * step — what every parallel run is measured against (`docs/compiler-performance.md` §15.1). The maps are concurrent
  * either way.
  */
final class IncrementalFactGenerator(
    generator: CompilerProcessor,
    prior: Map[CompilerFactKey[?], CacheEntry],
    strictAccounting: Boolean,
    progress: Option[ProgressTracker],
    registered: CompilerFact => Unit,
    parallel: Boolean
) extends CompilationProcess
    with Logging {
  private val errors             = mutable.ArrayBuffer.empty[AttributedError] // guarded by itself
  private val facts              = new ConcurrentHashMap[CompilerFactKey[?], Slot[Option[CompilerFact]]]()
  private val directDependencies = new ConcurrentHashMap[CompilerFactKey[?], mutable.Set[CompilerFactKey[?]]]()
  private val producedDuring     = new ConcurrentHashMap[CompilerFactKey[?], CompilerFactKey[?]]()
  private val carriedForward     = new ConcurrentHashMap[CompilerFactKey[?], CacheEntry]()
  private val unchangedChecks    = new ConcurrentHashMap[CompilerFactKey[?], Slot[Boolean]]()
  private val regeneratedKeysSet = ConcurrentHashMap.newKeySet[CompilerFactKey[?]]()
  private val demandedRoots      = ConcurrentHashMap.newKeySet[CompilerFactKey[?]]()
  private val threads            = new FactThreads

  /** A demand from outside any generation — the target plugin asking for its artefact, a host (LSP, a test harness)
    * querying a fact — is a **root** of this run's graph. Everything else reaches a fact through a recorded dependency
    * edge, so roots plus those edges are the whole of what this run actually built, and that is what decides which
    * diagnostics it reports (see [[currentErrors]]). Reads made by a generation arrive with a non-empty ancestor chain;
    * the validation path deliberately bypasses this entry point ([[getFactUntyped]]), because asking whether a fact
    * changed is not building it.
    */
  override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
      key: K,
      ancestors: List[CompilerFactKey[?]]
  ): Option[V] =
    if (ancestors.isEmpty) {
      demandedRoots.add(key)
      try request(key, ancestors, None).map(_.asInstanceOf[V])
      catch {
        case cycle: FactThreads.CyclicWait =>
          report(AttributedError(None, CompilerError.global(cycle.message)))
          None
      }
    } else request(key, ancestors, ancestors.headOption).map(_.asInstanceOf[V])

  /** Start the generation of each of `keys` not yet started, each on a virtual thread of its own, when the run is
    * parallel; do nothing otherwise. A started one runs under `ancestors`, the chain of the generation that hinted it,
    * which is the chain it would have had if that generation had read it first. Its requester is not that generation:
    * the hinting generation does not wait for it until it reads it.
    */
  override def prefetch(keys: Seq[CompilerFactKey[?]], ancestors: List[CompilerFactKey[?]]): Unit =
    if (parallel)
      keys.foreach { key =>
        if (!facts.containsKey(key)) {
          val slot   = new Slot[Option[CompilerFact]](key, null)
          val thread = threads.unstarted(() => computed(key, None, slot, resolve(key, slot, ancestors)))
          slot.owner = thread
          if (facts.putIfAbsent(key, slot) == null) threads.start(thread)
        }
      }

  /** What is outside the compilation asks for facts through: each demand is a root ([[getFact]] with no ancestors), run
    * on a compilation thread, and it returns once every fact it caused to be started has ended.
    */
  val demand: FactDemand = new FactDemand {
    override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): IO[Option[V]] =
      CompileThread.run(threads.settled(IncrementalFactGenerator.this.getFact(key, Nil)))
  }

  /** Answer `key` for `requester`, the generation asking (if any). The requester is the head of `ancestors` for a
    * generation's read, and the fact being resolved for a validation read, whose chain is deliberately empty.
    */
  private def request(
      key: CompilerFactKey[?],
      ancestors: List[CompilerFactKey[?]],
      requester: Option[CompilerFactKey[?]]
  ): Option[CompilerFact] = {
    CompileThread.stopIfInterrupted()
    val existing = facts.get(key)
    val result   =
      if (existing != null) awaited(existing, requester)
      else {
        val slot  = new Slot[Option[CompilerFact]](key, Thread.currentThread())
        val raced = facts.putIfAbsent(key, slot)
        if (raced != null) awaited(raced, requester)
        else {
          computed(key, requester, slot, resolve(key, slot, ancestors))
          slot.value
        }
      }
    if (result.isDefined) delivered(key, fromCache = false)
    result
  }

  /** The fact of a slot someone else made: at once if it is done, an internal error if this very thread is generating
    * it ([[inProgress]]), and otherwise once the thread generating it is done. Waiting throws a
    * [[FactThreads.CyclicWait]] if that thread waits for this one.
    */
  private def awaited(slot: Slot[Option[CompilerFact]], requester: Option[CompilerFactKey[?]]): Option[CompilerFact] =
    if (slot.done) slot.value
    else if (slot.owner eq Thread.currentThread()) inProgress(slot.key)
    else {
      requester.foreach(r => progress.foreach(_.waiting(r)))
      try threads.await(slot)
      finally requester.foreach(r => progress.foreach(_.resumed(r)))
      slot.value
    }

  private def report(error: AttributedError): Unit = errors.synchronized { errors += error }: Unit

  /** Run `computation` of `key`'s fact, which is generated by the time it returns. The tracker, if there is one, is told
    * when the computation starts, for `requester`, and when it ends; without one, nothing else happens. A computation
    * that throws is logged and concludes without a fact, as one that produced nothing does.
    */
  private def computed(
      key: CompilerFactKey[?],
      requester: Option[CompilerFactKey[?]],
      slot: Slot[Option[CompilerFact]],
      computation: => Unit
  ): Unit = {
    progress.foreach(_.started(key, requester))
    try computation
    catch {
      case NonFatal(t) => error[SyncIO](s"Resolving (${key.getClass.getName}) $key failed.", t).unsafeRunSync()
    }
    progress.foreach(_.ended(key))
    val _ = slot.complete(None) // safety net; no-op if already completed
  }

  /** A read of a fact whose generation is still running, which on one thread is a generation up this very stack that a
    * fresh validation chain did not show as an ancestor. Waiting for it would never end, so it is reported and answered
    * as missing.
    */
  private def inProgress(key: CompilerFactKey[?]): Option[CompilerFact] = {
    report(
      AttributedError(
        None, // an engine invariant, not a diagnostic about the program: always reported
        CompilerError.global(s"Internal error: fact $key was demanded while it was being generated.")
      )
    )
    None
  }

  /** Register a fact, completing its slot. Re-registering the *same* value is a no-op (the push pattern: generations
    * over one file legitimately push identical sibling facts). Registering a **different** value for an already-completed
    * key is a double-produce — two producers computing conflicting values for one key, or a value arriving after the
    * key already concluded empty — and is reported as an internal error instead of being silently dropped.
    */
  override def registerFact(fact: CompilerFact): Unit = {
    val slot = facts.computeIfAbsent(fact.key(), key => new Slot[Option[CompilerFact]](key, null))
    if (slot.complete(Some(fact))) registered(fact)
    else
      slot.value match {
        case Some(existing) if (existing eq fact) || existing == fact => ()
        case Some(_)                                                  =>
          report(
            AttributedError(
              None, // an engine invariant, not a diagnostic about the program: always reported
              CompilerError.global(s"Internal error: fact ${fact.key()} was produced twice with different values.")
            )
          )
        case None                                                     =>
          report(
            AttributedError(
              None, // an engine invariant, not a diagnostic about the program: always reported
              CompilerError.global(
                s"Internal error: fact ${fact.key()} was registered after its generation already concluded without it."
              )
            )
          )
      }
  }

  /** Satisfy a first-time request: accept the cached value if it is still valid, otherwise regenerate.
    *
    *   - A fact with a stored value and recorded dependencies is accepted iff every dependency is unchanged.
    *   - Anything else (a value-less fact whose value is now actually needed, a leaf — including a fact that was
    *     registered outside any generation, which has no recorded dependencies — or a fact with no prior) is
    *     regenerated.
    */
  private def resolve(
      key: CompilerFactKey[?],
      slot: Slot[Option[CompilerFact]],
      ancestors: List[CompilerFactKey[?]]
  ): Unit =
    prior.get(key) match {
      case Some(entry) if entry.hasValue && entry.directDeps.nonEmpty =>
        if (entry.directDeps.forall(depUnchanged(_, key))) acceptPrior(key, entry, slot)
        else regenerate(key, ancestors)
      case _                                                          => regenerate(key, ancestors)
    }

  /** Whether `key`'s value is unchanged since last run, memoized once per run. The validity oracle a parent uses for
    * each of its dependencies; it never materialises a value-less fact (see class doc). `resolving` is the fact whose
    * validation asks, which is what any work done here is done for. A check still running on this thread is one this
    * very drill is inside of, a cycle the previous run's graph cannot hold, and answers "changed", the fail-safe answer;
    * one running on another thread is waited for, unless that thread waits for this one, which answers "changed" too.
    */
  private def depUnchanged(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): Boolean = {
    val existing = unchangedChecks.get(key)
    if (existing != null) verdictOf(existing)
    else {
      val check = new Slot[Boolean](key, Thread.currentThread())
      val raced = unchangedChecks.putIfAbsent(key, check)
      if (raced != null) verdictOf(raced)
      else {
        val unchanged =
          try computeUnchanged(key, resolving)
          catch { case NonFatal(_) => false } // any failure ⇒ treat as changed (regenerate); fail-safe
        check.complete(unchanged)
        unchanged
      }
    }
  }

  private def verdictOf(check: Slot[Boolean]): Boolean =
    if (check.done) check.value
    else if (check.owner eq Thread.currentThread()) false
    else
      try {
        threads.await(check)
        check.value
      } catch { case _: FactThreads.CyclicWait => false }

  /** Whether the prior entry for `key` still stands, decided the cheapest way that is sound for its shape.
    *
    *   - **No prior entry** — new, or previously failed, so changed.
    *   - **A world leaf** (no recorded dependencies) — the only thing that can tell is running it: recompute and
    *     compare against what the cache holds. This is the boundary with the outside world and the one place the
    *     equality cutoff earns its name.
    *   - **A derived fact whose dependencies all hold** — unchanged *by construction*, with nothing recomputed and
    *     nothing materialised. A fact is a pure function of its recorded inputs, which is the assumption
    *     [[acceptPrior]] already rests on; recomputing it here would compare the stored value against itself. A
    *     value-less one is carried forward so it stays drillable, a value-bearing one needs nothing — untouched keys
    *     retain their prior entry ([[buildCacheData]]).
    *   - **A derived fact whose dependencies moved** — recompute and compare if there is a value to compare against.
    *     Equality here is what stops propagation: a changed input whose result recomputes the same invalidates nothing
    *     further. With no value the drill has failed and there is nothing left to try.
    */
  private def computeUnchanged(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): Boolean =
    prior.get(key) match {
      case None                                    => false
      case Some(entry) if entry.directDeps.isEmpty => recomputeAndCompare(key, entry, resolving)
      case Some(entry)                             =>
        if (entry.directDeps.forall(depUnchanged(_, resolving))) {
          if (!entry.hasValue) carriedForward.put(key, entry): Unit
          delivered(key, fromCache = true)
          true
        } else recomputeAndCompare(key, entry, resolving)
    }

  private def recomputeAndCompare(
      key: CompilerFactKey[?],
      entry: CacheEntry,
      resolving: CompilerFactKey[?]
  ): Boolean =
    if (entry.hasValue) {
      val unchanged = getFactUntyped(key, resolving) match {
        case Some(current) => entry.matches(current)
        case None          => false // no longer producible ⇒ changed
      }
      if (!unchanged) progress.foreach(_.changed(key))
      unchanged
    } else false

  /** Accept the cached fact and carry its trace forward so it re-persists with the right metadata. This is the one
    * place a stored value is actually read: everything else about an entry — whether it has a value, and whether a
    * recomputed one matches it — is answerable without materialising.
    */
  private def acceptPrior(key: CompilerFactKey[?], entry: CacheEntry, slot: Slot[Option[CompilerFact]]): Unit = {
    directDependencies.put(key, mutable.HashSet.from(entry.directDeps)): Unit
    delivered(key, fromCache = true)
    if (slot.complete(entry.value)) entry.value.foreach(registered)
  }

  private def delivered(key: CompilerFactKey[?], fromCache: Boolean): Unit =
    progress.foreach(_.delivered(key, fromCache))

  /** Run the processor, recording (via [[DependencyTrackingProcess]]) the facts it reads as this key's direct
    * dependencies. The processor completes the fact's slot itself via [[registerFact]]; the caller's safety net handles
    * the "produced nothing" case.
    *
    * The key's own dependency set is entered in `directDependencies` up front (empty), so that even a generated leaf —
    * which makes no `getFact` calls — is recorded as *generated* (not mistaken for an injected fact, see
    * [[buildCacheData]]). Reads then accumulate into that set as they happen, before the fact becomes observable, and
    * touch nothing another generation updates: a read is the engine's most frequent operation, and one map of every
    * key's dependencies updated at each read was a tenth of a build's fact administration.
    *
    * The generation must account for its outcome: it ends with the fact registered, at least one error recorded, an
    * explicit abort (the sanctioned *decline*), or a missing dependency read (the failure is attributable upstream). A
    * generation with none of these — a missing producer for the key type, or a processor that silently produced nothing
    * — is reported as an internal error instead of being indistinguishable from a legitimate decline.
    */
  private def regenerate(key: CompilerFactKey[?], ancestors: List[CompilerFactKey[?]]): Unit = {
    regeneratedKeysSet.add(key)
    val recorded = directDependencies.computeIfAbsent(key, _ => mutable.HashSet.empty)
    val tracking = new DependencyTrackingProcess(this, key, recorded, producedDuring, ancestors)
    val outcome  = runCompilerIO(tracking)(generator.generate(key)).map(_._1)
    val es       = outcome.fold(identity, identity)
    // The engine-level diagnostics the tracking wrapper records (a refused cyclic read) belong to this generation
    // exactly as the processor's own do, so they are attributed with them.
    errors.synchronized { errors ++= (tracking.errors ++ es).map(AttributedError(Some(key), _)).iterator }: Unit
    if (strictAccounting && outcome.isRight && es.isEmpty) reportUnaccountedOutcome(key, tracking.sawMissing)
  }

  /** The generation neither erred nor declined; unless it registered its fact or read a missing input, nothing accounts
    * for its outcome — report it.
    *
    * Attributed to `key`, unlike the other two engine invariants, because this one is a statement about a *demand*: it
    * assumes something asked for this key, so a producer must exist for it. The validation path breaks that premise on
    * purpose — it asks for keys the previous run's graph had — and a producer that only ever *pushes* a key (one file
    * parse registering every name it declares) answers such a demand with nothing at all, which is the right answer and
    * not an engine fault. Reachability then tells the two apart: a demand the compilation actually made is live.
    */
  private def reportUnaccountedOutcome(key: CompilerFactKey[?], missing: Boolean): Unit = {
    val registeredFact = Option(facts.get(key)).exists(slot => slot.done && slot.value.isDefined)
    if (!missing && !registeredFact)
      report(
        AttributedError(
          Some(key),
          CompilerError.global(s"Internal error: no processor produced a fact, an error, or a decline for $key.")
        )
      )
  }

  /** A validation-path read (recompute-and-compare). It starts a fresh chain (`Nil`): the recursion guard only matters
    * for facts materialised through a processor generation, and the value-less `SemValue`-bearing facts the guard
    * protects are never materialised here — they take the structural-drill branch of [[computeUnchanged]].
    */
  private def getFactUntyped(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): Option[CompilerFact] =
    request(key, Nil, Some(resolving))

  def currentFacts(): IO[Map[CompilerFactKey[?], CompilerFact]] = IO.delay(materialisedFacts)

  private def materialisedFacts: Map[CompilerFactKey[?], CompilerFact] =
    facts.values.asScala.iterator.flatMap(slot => if (slot.done) slot.value else None).map(f => f.key() -> f).toMap

  /** The facts this run built *or proved still current*, among the keys `wanted` accepts.
    *
    * [[currentFacts]] holds only what the run materialised, and an incremental run materialises little: a derived fact
    * whose dependencies all hold is unchanged *by construction* and is neither recomputed nor read, so it is in no map
    * of this run although it is exactly as current as one that is. A host that wants *every* fact of some kind — an
    * editor rebuilding its indices after each edit — would see only the few the edit touched. Those facts are here too:
    * each key this run's validation proved unchanged, with the value the prior cache holds for it.
    *
    * `wanted` selects by key, before any value is read, because reading a stored value is the cost the incremental run
    * avoided; a caller names the kinds it needs rather than paying for every fact.
    */
  def currentFactsIncludingUnchanged(
      wanted: CompilerFactKey[?] => Boolean
  ): IO[Map[CompilerFactKey[?], CompilerFact]] =
    IO.delay {
      val materialised = materialisedFacts

      provenUnchanged
        .filter(key => wanted(key) && !materialised.contains(key))
        .flatMap(key => prior.get(key).flatMap(_.value).map(key -> _))
        .toMap ++ materialised
    }

  /** The diagnostics of the graph this run actually built.
    *
    * A run reaches a fact two ways, and only one of them is compilation. Beyond the facts demanded from the roots, the
    * incremental validation path regenerates facts to answer *whether they changed*, drilling the **previous** run's
    * dependency graph — so it re-runs generations for values the program being compiled no longer has. Such a
    * generation failing is the answer "changed", not an error in this program: reporting it turns a rename or a
    * deletion into spurious diagnostics ("Could not find 'X'.") about a name the source no longer mentions, and — since
    * a successful build is `errors.isEmpty && targetProduced` — fails a build whose artefact is correct.
    *
    * So a diagnostic is reported iff the generation it came from is reachable from this run's roots through the edges
    * this run recorded ([[AttributedError]]). This hides nothing a compilation reached: a fact the program genuinely
    * needs is read by its dependent, which records the edge before the read — including a read that comes back empty
    * because the generation failed.
    *
    * They are **sorted** by source, position and message: a parallel run appends them in the order its generations
    * finish, which differs from run to run, and a build's report must not depend on the schedule.
    */
  def currentErrors(): IO[Seq[CompilerError]] =
    IO.delay {
      val live = reachableFrom(demandedRoots.asScala.toSet, dependencyGraph)

      errors
        .synchronized(errors.filter(_.source.forall(live.contains)).map(_.error).toList)
        .sortBy(error =>
          (
            error.contentSource,
            error.sourceRange.from.line,
            error.sourceRange.from.col,
            error.sourceRange.to.line,
            error.sourceRange.to.col,
            error.message
          )
        )
    }

  /** The keys reachable from `roots` through `deps` — this run's live fact graph. */
  private def reachableFrom(
      roots: Set[CompilerFactKey[?]],
      deps: Map[CompilerFactKey[?], Set[CompilerFactKey[?]]]
  ): Set[CompilerFactKey[?]] = {
    @tailrec
    def walk(frontier: Set[CompilerFactKey[?]], seen: Set[CompilerFactKey[?]]): Set[CompilerFactKey[?]] =
      if (frontier.isEmpty) seen
      else {
        val next = frontier.flatMap(key => deps.getOrElse(key, Set.empty)) -- seen

        walk(next, seen ++ next)
      }

    walk(roots, roots)
  }

  /** The keys this run regenerated (ran a processor for from scratch) rather than accepting from the prior cache. On a
    * warm build over an unchanged tree this is exactly the set of world leaves — facts with no recorded dependencies,
    * e.g. every `FileStat`, the `OutputFileStat` digest, and the `UpToDate` anchor constant — which are always
    * regenerated as the boundary with the external world.
    */
  def regeneratedKeys(): IO[Set[CompilerFactKey[?]]] = IO.delay(regeneratedKeysSet.asScala.toSet)

  /** The regenerated keys that were **not** world leaves in the prior cache — facts re-run from scratch despite having
    * recorded dependencies (or being newly seen this run). On a warm build over an unchanged tree this MUST be empty:
    * every non-leaf fact validates structurally and is accepted from cache. A non-empty result is a warm-build
    * regression surfacing as needless recomputation — a contributor whose input-less generation lost its `UpToDate`
    * anchor (an edge-less entry read as a leaf), an equality-unstable fact, or a genuinely missing prior — see
    * `docs/incremental-compilation.md` §3 and §8.
    */
  def nonLeafRegenerations(): IO[Set[CompilerFactKey[?]]] =
    IO.delay(regeneratedKeysSet.asScala.toSet.filterNot(key => prior.get(key).exists(_.directDeps.isEmpty)))

  /** Build the cache to persist after this run. Three sources are merged, later winning on key collision:
    *
    *   - **retained prior** entries — every prior entry for a key this run did **not** touch (never demanded: neither
    *     resolved, materialised, nor drilled). This is what makes the cache *accumulate* rather than replace: facts
    *     reachable only from a *different* configuration (e.g. another example's `main`) survive a build that never
    *     asked for them, so switching back stays warm. Retained entries are never served stale — the next demand
    *     re-validates their dependencies down to the world leaves before accepting them.
    *   - facts **validated structurally but not materialised** (value-less facts proven unchanged) — their prior
    *     edges-only entry, carried forward so the graph stays drillable.
    *   - facts **materialised** this run (regenerated or accepted) — a fresh entry with their value and recorded
    *     dependencies. Only facts that resolved to a value are included, so failures are never cached and re-surface.
    *
    * The retention explicitly **excludes** every key this run resolved (`directDependencies`) or carried, so a key that
    * was regenerated this run and *failed* (or recomputed to a new value) can never keep its stale prior value: its
    * fresh outcome — a new entry, or, for a failure, no entry at all — is authoritative. Only genuinely untouched keys
    * fall through to the prior. This is the one subtlety that keeps accumulation sound: without it, a fact that started
    * failing would be masked by its own stale success next run.
    *
    * It excludes one more: an untouched entry whose recorded dependency this run **moved** ([[movedKeys]]). Retention
    * is what lets the cache hold more than one generation at once, and validation compares a dependency against the
    * cache's *entry* rather than against what the dependent consumed — so an entry left behind while its input advanced
    * would be accepted against a value it never saw.
    *
    * A materialised fact gets its dependency set from one of two places: its own `directDependencies` entry (it was
    * generated), or — for a fact **pushed** by a processor for a key other than the one being generated — the final
    * dependency set of the generation that pushed it (recorded in `producedDuring`): a pushed fact is a function of
    * exactly what its producing generation read, so those edges validate it soundly next run. A fact registered with
    * neither record (a direct out-of-generation registration, e.g. a test harness seeding source facts) persists with
    * an empty dependency set — leaf semantics, always regenerated, never accepted blindly.
    */
  def buildCacheData(): IO[FactCacheData] =
    debug[IO](
      s"Incremental run: regenerated ${regeneratedKeysSet.size} fact(s); ${materialisedFacts.size} materialised, " +
        s"${carriedForward.size} validated unchanged without recompute."
    ) >> IO.delay {
      val factMap  = materialisedFacts
      val deps     = dependencyGraph
      val pushedBy = producedDuring.asScala
      val carried  = carriedForward.asScala.toMap
      val drilled  = provenUnchanged

      val fresh    = factMap.map { case (key, fact) =>
        val recordedDeps = deps.get(key).orElse(pushedBy.get(key).map(producer => deps.getOrElse(producer, Set.empty)))
        key -> CacheEntry(Some(fact), recordedDeps.getOrElse(Set.empty))
      }
      val touched  = factMap.keySet ++ deps.keySet ++ carried.keySet // resolved, materialised, or drilled this run
      val moved    = movedKeys(factMap, deps, drilled)
      val kept     = prior.view.filterKeys(k => !touched(k))         // untouched prior facts accumulate…
      val retained =                                                 // …unless an input moved under them
        if (moved.isEmpty) kept.toMap else kept.filter { case (_, entry) => !entry.directDeps.exists(moved) }.toMap

      FactCacheData(retained ++ carried ++ fresh)
    }

  /** The keys whose value this run *moved*: it resolved them and got something other than what the cache held — a
    * different value, or nothing at all (a failure, which drops the entry) — or a generation **pushed** them with a value
    * other than the cache's. A pushed key is never resolved, so it has no `directDependencies` entry of its own, but its
    * entry advances all the same (one file parse registering a `ModuleValue` for every name it declares): leaving it
    * out let a run that re-pushed a name with a new definition and never reached its dependents — a failing compile —
    * retain those dependents against an entry they never consumed, which the next run then accepted (§28).
    *
    * This is what makes retention safe. Validation asks "is this dependency the same as the cache's entry for it?",
    * while what a dependent actually needs to know is "is it the same as what *I* consumed". Those coincide only while
    * the cache is one consistent generation. A run that updates a fact but never reaches its dependents — it failed
    * first, or simply never demanded them — leaves the two a generation apart, and the dependent is then accepted
    * against a value it never saw. So a dependent recorded against a moved input is dropped rather than retained; it
    * regenerates on its next demand.
    *
    * Only *direct* dependents need dropping: a retained entry pointing at one of them now finds no prior entry, which
    * validation reads as changed — but only for as long as that entry stays absent. A later run that **creates** it
    * afresh, without reaching the dependent, would give the dependent an intact-looking input again, one generation
    * newer than the value it consumed. So a key with no prior entry that this run materialised counts as moved too, and
    * whatever is still recorded against it is dropped (§28). And a run that moved nothing (the warm no-change build)
    * does no work here.
    *
    * A **value-less** entry has nothing to compare a regenerated value against, so it moved unless this run's drill
    * proved it unchanged. That exception is not a nicety: a `SemValue` fact is regenerated whenever anything needs its
    * value, typically in the same run whose drill just proved it unchanged, and counting that as a move dropped every
    * dependent the run did not demand. The next run rebuilt them, regenerating other value-less facts and dropping
    * other dependents — a warm build over an unchanged tree alternating between fast and slow indefinitely.
    */
  private def movedKeys(
      factMap: Map[CompilerFactKey[?], CompilerFact],
      deps: Map[CompilerFactKey[?], Set[CompilerFactKey[?]]],
      drilled: Set[CompilerFactKey[?]]
  ): Set[CompilerFactKey[?]] =
    (deps.keySet ++ factMap.keySet).filter { key =>
      prior.get(key) match {
        case None        => true // created afresh: whatever is still recorded against it saw an older generation
        case Some(entry) =>
          if (entry.hasValue) !factMap.get(key).exists(entry.matches)
          else !drilled(key)
      }
    }

  /** Every key this run resolved, with the dependencies recorded for it so far. */
  private def dependencyGraph: Map[CompilerFactKey[?], Set[CompilerFactKey[?]]] =
    directDependencies.asScala.view.mapValues(_.toSet).toMap

  /** The keys this run's validation proved unchanged. A check still pending proves nothing. */
  private def provenUnchanged: Set[CompilerFactKey[?]] =
    unchangedChecks.asScala.collect { case (key, check) if check.done && check.value => key }.toSet
}

object IncrementalFactGenerator {

  /** @param strictAccounting
    *   when true, a generation that ends with no fact, no error, no explicit abort (decline), and no missing dependency
    *   read is reported as an internal error ("no processor produced a fact, an error, or a decline"). This invariant
    *   only holds for a *complete* processor bundle — a session running all plugins — so it is enabled by
    *   [[CompilationSession]] and off by default for partial bundles (test harnesses that inject source-phase facts
    *   instead of carrying their processors).
    * @param progress
    *   told each fact the run comes to have, for `--progress`.
    * @param registered
    *   handed each fact the moment it has its value, for a cache written behind the compilation. It runs on the
    *   compilation's thread, so it must only hand the fact on. In a parallel run it is handed facts from several
    *   threads at once.
    * @param parallel
    *   start the facts a generation hints ([[IncrementalFactGenerator.prefetch]]) on threads of their own; without it, a
    *   hint does nothing and the run is sequential.
    */
  def create(
      generator: CompilerProcessor,
      prior: Option[FactCacheData],
      strictAccounting: Boolean = false,
      progress: Option[ProgressTracker] = None,
      registered: CompilerFact => Unit = _ => (),
      parallel: Boolean = false
  ): IO[IncrementalFactGenerator] =
    IO.delay(
      new IncrementalFactGenerator(
        generator,
        prior.map(_.entries).getOrElse(Map.empty),
        strictAccounting,
        progress,
        registered,
        parallel
      )
    )

  /** What a key's computation will have once it is done: its fact (or that it has none), or a validation's verdict. It
    * is completed once; the first completion wins, as a `Deferred`'s did, and a thread on another computation may wait
    * for it ([[await]]).
    *
    * @param owner
    *   the thread computing it, which is what a wait follows to find a cycle ([[FactThreads.await]]); `null` for a slot a
    *   push completes as it makes it. A prefetched slot names its thread before the thread starts.
    */
  private[compiler] final class Slot[A](val key: CompilerFactKey[?], @volatile var owner: Thread) {
    @volatile var done: Boolean = false
    var value: A                = scala.compiletime.uninitialized // written before `done`, read after it

    /** Complete it with `result`, answering whether this completion was the one that counted. */
    def complete(result: A): Boolean =
      synchronized {
        if (done) false
        else {
          value = result
          done = true
          notifyAll()
          true
        }
      }

    /** Block until it is completed. */
    def await(): Unit = synchronized { while (!done) wait() }
  }
}
