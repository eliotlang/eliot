package com.vanillasource.eliot.eliotc.compiler.cache

import cats.data.Chain
import com.vanillasource.eliot.eliotc.feedback.CompilerError
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}

import scala.collection.mutable

/** Wraps a [[CompilationProcess]] so that every fact read via `getFact` while `key` is being generated is recorded into
  * `dependencies`, `key`'s own set of direct dependencies, which the generator holds under `key`.
  *
  * Recording happens **eagerly, on each read** — before the generated fact is registered (a processor reads its inputs
  * before registering its output). This matters for correctness: a fact's dependency set must be fully recorded by the
  * time the fact becomes observable, otherwise a consumer (or the end-of-run cache build) could see an incomplete set. A
  * fresh wrapper is used per generation, so recording is per generation.
  *
  * This wrapper is also the explicit home of the **active fact-request chain**: built once per generation with `key`
  * and the `ancestors` it was requested under, it precomputes `chain = key :: ancestors` (innermost first). It serves
  * that chain as [[activeFactKeys]] and threads it as the `ancestors` of every read it forwards, so the next generation
  * down sees `key` as its ancestor. The chain therefore flows explicitly through ordinary values — no ambient
  * thread-local state.
  *
  * The chain is enforced here, not just published: a read of a key **already on the chain** asks for a fact the reading
  * computation is itself in the middle of generating, so such a read is refused — a "cyclic fact demand" error is recorded and `None` is returned, which the demanding processor's
  * `getFactOrAbort` turns into an abort. Processors that legitimately walk potentially-cyclic graphs still consult
  * [[activeFactKeys]] *before* demanding, so they can report a domain-specific error; this refusal is the engine-level
  * backstop for the unguarded case. The refused edge is deliberately **not** recorded as a dependency, so a cyclic edge
  * can never enter the persisted cache (where it would dead-lock validation).
  *
  * Facts a processor **pushes** — registered for keys other than the one being generated (e.g. one file-parse
  * registering every name's value) — are recorded in `producedDuring` as produced by this generation. The cache build
  * gives them this generation's dependency set: a pushed fact is a function of exactly what the generation that pushed
  * it read.
  *
  * What the generation itself caused is kept here for the engine to read when it ends: the engine-level [[errors]] (a
  * refused cyclic read) and whether a read came back empty ([[sawMissing]]).
  */
final class DependencyTrackingProcess(
    underlying: CompilationProcess,
    key: CompilerFactKey[?],
    dependencies: mutable.Set[CompilerFactKey[?]],
    producedDuring: mutable.Map[CompilerFactKey[?], CompilerFactKey[?]],
    ancestors: List[CompilerFactKey[?]]
) extends CompilationProcess {

  /** The diagnostics the engine records for this generation: a refused cyclic read. */
  var errors: Chain[CompilerError] = Chain.empty

  /** Whether a read of this generation came back empty, including a refused cyclic read. */
  var sawMissing: Boolean = false

  /** The active fact-request chain for the generation this wrapper tracks: `key` plus its ancestors, innermost first.
    */
  private val chain: List[CompilerFactKey[?]] = key :: ancestors

  /** Reads forward `chain` as their `ancestors`. The incoming `ancestors` argument is ignored: a processor reading a
    * fact does not know the chain — this wrapper is its source of truth for the generation of `key`.
    *
    * Every read that comes back empty (including a refused cyclic read) is noted in `sawMissing`, so the engine can
    * attribute this generation's own lack of output to a missing input rather than flagging it as an internal error.
    */
  override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
      dependency: K,
      ancestors: List[CompilerFactKey[?]]
  ): Option[V] =
    if (onChain(dependency)) {
      errors = errors :+ CompilerError.global(cyclicDemandMessage(dependency))
      sawMissing = true
      None
    } else {
      dependencies += dependency
      val result = underlying.getFact(dependency, chain)
      if (result.isEmpty) sawMissing = true
      result
    }

  /** Whether `dependency` is on the chain, comparing a key's remembered hash before its structure: the chain can be
    * hundreds of keys deep, and most of them are of the same kind as the dependency and differ only deep inside.
    */
  private def onChain(dependency: CompilerFactKey[?]): Boolean = {
    val hash = dependency.hashCode()

    chain.exists(ancestor => ancestor.hashCode() == hash && ancestor == dependency)
  }

  private def cyclicDemandMessage(dependency: CompilerFactKey[?]): String =
    s"Cyclic fact demand: ${(dependency :: chain.takeWhile(_ != dependency) ::: List(dependency)).mkString(" <- ")}"

  override def registerFact(value: CompilerFact): Unit = {
    if (value.key() != key && !producedDuring.contains(value.key())) producedDuring(value.key()) = key
    underlying.registerFact(value)
  }

  override def activeFactKeys: List[CompilerFactKey[?]] = chain
}
