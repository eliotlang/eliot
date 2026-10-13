package com.vanillasource.eliot.eliotc.compiler

import cats.data.Chain
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.cache.{CacheEntry, DependencyTrackingProcess, FactCacheData}
import com.vanillasource.eliot.eliotc.feedback.{CompilerError, Logging}
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey, CompilerProcessor}
import com.vanillasource.eliot.eliotc.progress.ProgressTracker

import scala.annotation.tailrec

/** Cache-aware fact generator implementing incremental compilation by backward, demand-driven validation.
  *
  * It keeps the same concurrent core as a plain generator — a [[Deferred]]-per-key map that computes each fact at most
  * once per run, per-fiber generation, and an ancestor chain for recursion detection threaded explicitly through the
  * per-generation [[DependencyTrackingProcess]] (the `ancestors` parameter of [[getFact]]) — and decides, per
  * dependency, whether it changed since last run via [[depUnchanged]]:
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
  */
final class IncrementalFactGenerator(
    generator: CompilerProcessor,
    prior: Map[CompilerFactKey[?], CacheEntry],
    strictAccounting: Boolean,
    errors: Ref[IO, Chain[AttributedError]],
    facts: Ref[IO, Map[CompilerFactKey[?], Deferred[IO, Option[CompilerFact]]]],
    directDependencies: Ref[IO, Map[CompilerFactKey[?], Set[CompilerFactKey[?]]]],
    producedDuring: Ref[IO, Map[CompilerFactKey[?], CompilerFactKey[?]]],
    carriedForward: Ref[IO, Map[CompilerFactKey[?], CacheEntry]],
    unchangedChecks: Ref[IO, Map[CompilerFactKey[?], Deferred[IO, Boolean]]],
    regeneratedKeysRef: Ref[IO, Set[CompilerFactKey[?]]],
    demandedRoots: Ref[IO, Set[CompilerFactKey[?]]],
    progress: Option[ProgressTracker]
) extends CompilationProcess
    with Logging {

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
  ): IO[Option[V]] =
    demandedRoots.update(_ + key).whenA(ancestors.isEmpty) >>
      request(key, ancestors, ancestors.headOption).map(_.map(_.asInstanceOf[V]))

  /** Answer `key` for `requester`, the generation asking (if any), which waits until the answer arrives. The requester
    * is the head of `ancestors` for a generation's read, and the fact being resolved for a validation read, whose chain
    * is deliberately empty.
    */
  private def request(
      key: CompilerFactKey[?],
      ancestors: List[CompilerFactKey[?]],
      requester: Option[CompilerFactKey[?]]
  ): IO[Option[CompilerFact]] =
    for {
      modifyResult <- modifyAtomicallyFor(key)
      result       <-
        if (modifyResult._2) computed(key, requester, modifyResult._1, resolve(key, modifyResult._1, ancestors))
        else awaited(modifyResult._1, requester) // only the first requester runs the computation
      _            <- delivered(key, fromCache = false).whenA(result.isDefined)
    } yield result

  /** Run `computation` of `key`'s fact in a fiber of its own and wait for the fact. The tracker, if there is one, is told
    * when the computation starts, for `requester`, and when it ends; without one, nothing else happens.
    */
  private def computed(
      key: CompilerFactKey[?],
      requester: Option[CompilerFactKey[?]],
      deferred: Deferred[IO, Option[CompilerFact]],
      computation: IO[Unit]
  ): IO[Option[CompilerFact]] = {
    def inFiber(body: IO[Unit], after: IO[Unit]): IO[Unit] =
      body
        .handleErrorWith(t => error[IO](s"Resolving (${key.getClass.getName}) $key failed.", t))
        .flatMap(_ => after >> deferred.complete(None).void) // safety net; no-op if already completed
        .start
        .void

    progress match {
      case None          => inFiber(computation, IO.unit) >> deferred.get
      case Some(tracker) => inFiber(tracker.started(key, requester) >> computation, tracker.ended(key)) >> deferred.get
    }
  }

  /** Register a fact, completing its [[Deferred]]. Re-registering the *same* value is a no-op (the push pattern:
    * concurrent generations over one file legitimately push identical sibling facts). Registering a **different** value
    * for an already-completed key is a double-produce — two producers computing conflicting values for one key, or a
    * value arriving after the key already concluded empty — and is reported as an internal error instead of being
    * silently dropped.
    */
  override def registerFact(fact: CompilerFact): IO[Unit] =
    modifyAtomicallyFor(fact.key()).flatMap { case (deferred, _) =>
      deferred.complete(Some(fact)).flatMap { won =>
        deferred.tryGet
          .flatMap {
            case Some(Some(existing)) if (existing eq fact) || existing == fact => IO.unit
            case Some(Some(_))                                                  =>
              errors.update(
                _ :+ AttributedError(
                  None, // an engine invariant, not a diagnostic about the program: always reported
                  CompilerError
                    .global(s"Internal error: fact ${fact.key()} was produced twice with different values.")
                )
              )
            case _                                                              =>
              errors.update(
                _ :+ AttributedError(
                  None, // an engine invariant, not a diagnostic about the program: always reported
                  CompilerError.global(
                    s"Internal error: fact ${fact.key()} was registered after its generation already concluded without it."
                  )
                )
              )
          }
          .unlessA(won)
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
      deferred: Deferred[IO, Option[CompilerFact]],
      ancestors: List[CompilerFactKey[?]]
  ): IO[Unit] =
    prior.get(key) match {
      case Some(entry) if entry.hasValue && entry.directDeps.nonEmpty =>
        entry.directDeps.toList.forallM(depUnchanged(_, key)).flatMap {
          case true  => acceptPrior(key, entry, deferred)
          case false => regenerate(key, ancestors)
        }
      case _                                                          => regenerate(key, ancestors)
    }

  /** Whether `key`'s value is unchanged since last run, memoized once per run. The validity oracle a parent uses for
    * each of its dependencies; it never materialises a value-less fact (see class doc). `resolving` is the fact whose
    * validation asks, which is what any work done here is done for.
    */
  private def depUnchanged(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): IO[Boolean] =
    for {
      newDeferred <- Deferred[IO, Boolean]
      modify      <- unchangedChecks.modify { checks =>
                       checks.get(key) match
                         case Some(existing) => (checks, (existing, false))
                         case None           => (checks.updated(key, newDeferred), (newDeferred, true))
                     }
      _           <- computeUnchanged(key, resolving)
                       .handleError(_ => false) // any failure ⇒ treat as changed (regenerate); fail-safe
                       .flatMap(modify._1.complete)
                       .void
                       .whenA(modify._2)
      // a check another validation runs is waited for; one computed right here is this validation's own work
      result      <- if (modify._2) modify._1.get else awaited(modify._1, Some(resolving))
    } yield result

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
  private def computeUnchanged(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): IO[Boolean] =
    prior.get(key) match {
      case None                                    => false.pure[IO]
      case Some(entry) if entry.directDeps.isEmpty => recomputeAndCompare(key, entry, resolving)
      case Some(entry)                             =>
        entry.directDeps.toList.forallM(depUnchanged(_, resolving)).flatMap {
          case true  =>
            carriedForward.update(_.updated(key, entry)).unlessA(entry.hasValue) >>
              delivered(key, fromCache = true).as(true)
          case false => recomputeAndCompare(key, entry, resolving)
        }
    }

  private def recomputeAndCompare(
      key: CompilerFactKey[?],
      entry: CacheEntry,
      resolving: CompilerFactKey[?]
  ): IO[Boolean] =
    if (entry.hasValue)
      getFactUntyped(key, resolving)
        .map {
          case Some(current) => entry.matches(current)
          case None          => false // no longer producible ⇒ changed
        }
        .flatTap(unchanged => progressed(_.changed(key)).unlessA(unchanged))
    else false.pure[IO]

  /** Accept the cached fact and carry its trace forward so it re-persists with the right metadata. This is the one
    * place a stored value is actually read: everything else about an entry — whether it has a value, and whether a
    * recomputed one matches it — is answerable without materialising.
    */
  private def acceptPrior(
      key: CompilerFactKey[?],
      entry: CacheEntry,
      deferred: Deferred[IO, Option[CompilerFact]]
  ): IO[Unit] =
    directDependencies.update(_.updated(key, entry.directDeps)) >>
      delivered(key, fromCache = true) >>
      deferred.complete(entry.value).void

  private def delivered(key: CompilerFactKey[?], fromCache: Boolean): IO[Unit] =
    progressed(_.delivered(key, fromCache))

  private def waiting(requester: Option[CompilerFactKey[?]]): IO[Unit] =
    progressed(tracker => requester.fold(IO.unit)(tracker.waiting))

  private def resumed(requester: Option[CompilerFactKey[?]]): IO[Unit] =
    progressed(tracker => requester.fold(IO.unit)(tracker.resumed))

  /** The value of `deferred`, which someone else computes: `requester` waits for it only if it is not already there,
    * which for a fact asked for again is the common case and is kept cheap.
    */
  private def awaited[A](deferred: Deferred[IO, A], requester: Option[CompilerFactKey[?]]): IO[A] =
    if (progress.isEmpty) deferred.get
    else
      deferred.tryGet.flatMap {
        case Some(value) => IO.pure(value)
        case None        => waiting(requester) >> deferred.get <* resumed(requester)
      }

  private def progressed(event: ProgressTracker => IO[Unit]): IO[Unit] =
    progress.fold(IO.unit)(event)

  /** Run the processor, recording (via [[DependencyTrackingProcess]]) the facts it reads as this key's direct
    * dependencies. The processor completes the fact's [[Deferred]] itself via [[registerFact]]; the caller's safety net
    * handles the "produced nothing" case.
    *
    * The key is marked present in `directDependencies` up front (empty), so that even a generated leaf — which makes no
    * `getFact` calls — is recorded as *generated* (not mistaken for an injected fact, see [[buildCacheData]]). Reads
    * then accumulate into the same entry as they happen, before the fact becomes observable.
    *
    * The generation must account for its outcome: it ends with the fact registered, at least one error recorded, an
    * explicit abort (the sanctioned *decline*), or a missing dependency read (the failure is attributable upstream). A
    * generation with none of these — a missing producer for the key type, or a processor that silently produced nothing
    * — is reported as an internal error instead of being indistinguishable from a legitimate decline.
    */
  private def regenerate(key: CompilerFactKey[?], ancestors: List[CompilerFactKey[?]]): IO[Unit] =
    for {
      _                <- regeneratedKeysRef.update(_ + key)
      _                <- directDependencies.update(deps => deps.updated(key, deps.getOrElse(key, Set.empty)))
      sawMissing       <- Ref.of[IO, Boolean](false)
      // The engine-level diagnostics the tracking wrapper records (a refused cyclic read) belong to this generation
      // exactly as the processor's own do, so they are collected here and attributed with them.
      generationErrors <- Ref.of[IO, Chain[CompilerError]](Chain.empty)
      tracking          = new DependencyTrackingProcess(
                            this,
                            key,
                            directDependencies,
                            producedDuring,
                            generationErrors,
                            sawMissing,
                            ancestors
                          )
      outcome          <- generator.generate(key).run(tracking).runS(Chain.empty).value
      es                = outcome.fold(identity, identity)
      tracked          <- generationErrors.get
      _                <- errors.update(_ ++ (tracked ++ es).map(AttributedError(Some(key), _)))
      _                <- reportUnaccountedOutcome(key, sawMissing).whenA(strictAccounting && outcome.isRight && es.isEmpty)
    } yield ()

  /** The generation neither erred nor declined; unless it registered its fact or read a missing input, nothing accounts
    * for its outcome — report it.
    *
    * Attributed to `key`, unlike the other two engine invariants, because this one is a statement about a *demand*: it
    * assumes something asked for this key, so a producer must exist for it. The validation path breaks that premise on
    * purpose — it asks for keys the previous run's graph had — and a producer that only ever *pushes* a key (one file
    * parse registering every name it declares) answers such a demand with nothing at all, which is the right answer and
    * not an engine fault. Reachability then tells the two apart: a demand the compilation actually made is live.
    */
  private def reportUnaccountedOutcome(key: CompilerFactKey[?], sawMissing: Ref[IO, Boolean]): IO[Unit] =
    for {
      missing    <- sawMissing.get
      registered <- facts.get.flatMap(_.get(key).traverse(_.tryGet)).map(_.flatten.flatten.isDefined)
      _          <- errors
                      .update(
                        _ :+ AttributedError(
                          Some(key),
                          CompilerError.global(
                            s"Internal error: no processor produced a fact, an error, or a decline for $key."
                          )
                        )
                      )
                      .whenA(!missing && !registered)
    } yield ()

  /** A validation-path read (recompute-and-compare). It starts a fresh chain (`Nil`): the recursion guard only matters
    * for facts materialised through a processor generation, and the value-less `SemValue`-bearing facts the guard
    * protects are never materialised here — they take the structural-drill branch of [[computeUnchanged]].
    */
  private def getFactUntyped(key: CompilerFactKey[?], resolving: CompilerFactKey[?]): IO[Option[CompilerFact]] =
    request(key, Nil, Some(resolving))

  private def modifyAtomicallyFor(
      key: CompilerFactKey[?]
  ): IO[(Deferred[IO, Option[CompilerFact]], Boolean)] =
    for {
      newValue <- Deferred[IO, Option[CompilerFact]]
      result   <- facts.modify { internalMap =>
                    internalMap.get(key) match
                      case Some(alreadyPresentValue) => (internalMap, (alreadyPresentValue, false))
                      case None                      => (internalMap.updated(key, newValue), (newValue, true))
                  }
    } yield result

  def currentFacts(): IO[Map[CompilerFactKey[?], CompilerFact]] =
    for {
      currentMap <- facts.get
      resolved   <- currentMap.values.toSeq.traverse(_.tryGet.map(_.flatten)).map(_.flatten)
    } yield resolved.map(fact => fact.key() -> fact).toMap

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
    for {
      materialised <- currentFacts()
      unchanged    <- provenUnchanged
    } yield unchanged
      .filter(key => wanted(key) && !materialised.contains(key))
      .flatMap(key => prior.get(key).flatMap(_.value).map(key -> _))
      .toMap ++ materialised

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
    */
  def currentErrors(): IO[Seq[CompilerError]] =
    for {
      attributed <- errors.get
      roots      <- demandedRoots.get
      deps       <- directDependencies.get
    } yield {
      val live = reachableFrom(roots, deps)

      attributed.filter(_.source.forall(live.contains)).map(_.error).toList
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
  def regeneratedKeys(): IO[Set[CompilerFactKey[?]]] = regeneratedKeysRef.get

  /** The regenerated keys that were **not** world leaves in the prior cache — facts re-run from scratch despite having
    * recorded dependencies (or being newly seen this run). On a warm build over an unchanged tree this MUST be empty:
    * every non-leaf fact validates structurally and is accepted from cache. A non-empty result is a warm-build
    * regression surfacing as needless recomputation — a contributor whose input-less generation lost its `UpToDate`
    * anchor (an edge-less entry read as a leaf), an equality-unstable fact, or a genuinely missing prior — see
    * `docs/incremental-compilation.md` §3 and §8.
    */
  def nonLeafRegenerations(): IO[Set[CompilerFactKey[?]]] =
    regeneratedKeysRef.get.map(_.filterNot(key => prior.get(key).exists(_.directDeps.isEmpty)))

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
    for {
      factMap     <- currentFacts()
      deps        <- directDependencies.get
      pushedBy    <- producedDuring.get
      carried     <- carriedForward.get
      drilled     <- provenUnchanged
      regenerated <- regeneratedKeysRef.get.map(_.size)
      _           <- debug[IO](
                       s"Incremental run: regenerated $regenerated fact(s); ${factMap.size} materialised, " +
                         s"${carried.size} validated unchanged without recompute."
                     )
    } yield {
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

  /** The keys this run's validation proved unchanged. A check still pending proves nothing. */
  private def provenUnchanged: IO[Set[CompilerFactKey[?]]] =
    unchangedChecks.get
      .flatMap(_.toList.traverseFilter { case (key, check) => check.tryGet.map(_.filter(identity).as(key)) })
      .map(_.toSet)
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
    */
  def create(
      generator: CompilerProcessor,
      prior: Option[FactCacheData],
      strictAccounting: Boolean = false,
      progress: Option[ProgressTracker] = None
  ): IO[IncrementalFactGenerator] =
    for {
      errors          <- Ref.of[IO, Chain[AttributedError]](Chain.empty)
      facts           <- Ref.of[IO, Map[CompilerFactKey[?], Deferred[IO, Option[CompilerFact]]]](Map.empty)
      deps            <- Ref.of[IO, Map[CompilerFactKey[?], Set[CompilerFactKey[?]]]](Map.empty)
      producedDuring  <- Ref.of[IO, Map[CompilerFactKey[?], CompilerFactKey[?]]](Map.empty)
      carriedForward  <- Ref.of[IO, Map[CompilerFactKey[?], CacheEntry]](Map.empty)
      unchangedChecks <- Ref.of[IO, Map[CompilerFactKey[?], Deferred[IO, Boolean]]](Map.empty)
      regenerated     <- Ref.of[IO, Set[CompilerFactKey[?]]](Set.empty)
      demandedRoots   <- Ref.of[IO, Set[CompilerFactKey[?]]](Set.empty)
    } yield new IncrementalFactGenerator(
      generator,
      prior.map(_.entries).getOrElse(Map.empty),
      strictAccounting,
      errors,
      facts,
      deps,
      producedDuring,
      carriedForward,
      unchangedChecks,
      regenerated,
      demandedRoots,
      progress
    )
}
