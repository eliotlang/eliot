package com.vanillasource.eliot.eliotc.processor

/** Passed into processors to access the currently running compilation process. Note, that processors can not have state
  * and are only allowed to interact with the current compilation process by consuming or producing facts that are
  * shared with other processors.
  *
  * Every call is synchronous and runs on the compilation's thread: a fact not yet there is generated on the reader's own
  * stack before the call returns. What is outside a compilation asks for facts through a [[FactDemand]].
  */
trait CompilationProcess {

  /** Get a fact from the currently running compilation process. If the fact is available, it is returned immediately.
    * If the generation of the fact previously failed, this call will return with None. Otherwise, the processors will
    * be asked to generate this fact, and this call returns when that generation ends. If this call returns with None,
    * that means this fact will never be produced.
    *
    * `ancestors` is the active fact-request chain of the caller (the keys whose generation is in progress up this
    * request path, innermost first). Processors never pass it — it defaults to empty and is supplied internally by the
    * per-generation [[com.vanillasource.eliot.eliotc.compiler.cache.DependencyTrackingProcess]] wrapper, which knows
    * the key it is tracking and threads `key :: ancestors` down to the generator, so the generation of the fact it asks
    * for refuses a read of any key up that chain. This is what lets cycle detection work without ambient state.
    *
    * The chain is the engine's alone: a processor cannot see it, so a fact's value is a function of its key and of the
    * facts it reads, never of who asked for it first (`docs/compiler-performance.md` §15.2).
    */
  def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
      key: K,
      ancestors: List[CompilerFactKey[?]] = Nil
  ): Option[V]

  def registerFact(value: CompilerFact): Unit

  /** Hint that the facts of `keys` will be read: the engine may start generating them elsewhere, and returns at once.
    * The reads still have to be made, in the order the processor needs them, and only they are dependencies. A process
    * that cannot start a fact ahead ignores the hint, which is what a sequential run does.
    *
    * `ancestors` is threaded as for [[getFact]]: processors never pass it.
    */
  def prefetch(keys: Seq[CompilerFactKey[?]], ancestors: List[CompilerFactKey[?]] = Nil): Unit = ()
}
