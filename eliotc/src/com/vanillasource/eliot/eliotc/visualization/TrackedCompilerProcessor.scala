package com.vanillasource.eliot.eliotc.visualization

import com.vanillasource.eliot.eliotc.processor.CompilerIO.{withProcess, CompilerIO}
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFactKey, CompilerProcessor}

/** Wraps a CompilerProcessor to track its fact interactions. Intercepts the CompilationProcess to record which facts
  * this processor requests and produces.
  */
final class TrackedCompilerProcessor(
    underlying: CompilerProcessor,
    tracker: FactVisualizationTracker,
    processorName: String
) extends CompilerProcessor {

  /** Delegated, so that tracking a processor does not make it look like it handles every key. */
  override def handles(factKey: CompilerFactKey[?]): Boolean = underlying.handles(factKey)

  override def generate(factKey: CompilerFactKey[?]): CompilerIO[Unit] =
    withProcess(new TrackedCompilationProcess(_, tracker, processorName))(_ => underlying.generate(factKey))
}

object TrackedCompilerProcessor {

  /** Wraps a processor tree to track all interactions.
    */
  def wrapProcessor(processor: CompilerProcessor, tracker: FactVisualizationTracker): CompilerProcessor =
    TrackedCompilerProcessor(processor, tracker, processor.getClass.getName)
}
