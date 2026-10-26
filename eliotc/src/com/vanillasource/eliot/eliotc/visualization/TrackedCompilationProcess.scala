package com.vanillasource.eliot.eliotc.visualization

import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}

/** Wraps a CompilationProcess to track fact requests and registrations. Records which processor is
  * requesting/registering facts for visualization.
  */
final class TrackedCompilationProcess(
    underlying: CompilationProcess,
    tracker: FactVisualizationTracker,
    processorName: String
) extends CompilationProcess {

  override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
      key: K,
      ancestors: List[CompilerFactKey[?]]
  ): Option[V] = {
    tracker.recordFactRequest(processorName, key)
    underlying.getFact(key, ancestors)
  }

  override def registerFact(value: CompilerFact): Unit = {
    tracker.recordFactProduction(processorName, value.key())
    underlying.registerFact(value)
  }

  override def activeFactKeys: List[CompilerFactKey[?]] = underlying.activeFactKeys
}
