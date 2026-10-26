package com.vanillasource.eliot.eliotc.processor

import cats.effect.IO

/** How what is outside a compilation asks it for facts: a target plugin asking for its artefact, a host (the LSP, a
  * test) querying what it needs. Each such demand is a **root** of the run's fact graph, and it is an `IO`, because it
  * runs the compilation it causes on the compilation's own thread
  * ([[com.vanillasource.eliot.eliotc.compiler.CompileThread]]) and waits for it. Cancelling it interrupts that thread.
  */
trait FactDemand {

  /** The fact of `key`, generating it and everything it needs first; `None` if it is never produced. */
  def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): IO[Option[V]]
}
