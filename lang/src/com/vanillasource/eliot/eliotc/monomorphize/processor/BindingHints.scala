package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.Id
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.NativeBinding
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.foldValueReferences
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The hint a check gives before it starts: the binding of every value its expression references.
  *
  * A check reads a callee's [[NativeBinding]] when it reaches the reference, and the first read of one is that callee's
  * whole front end. The binding is keyed by name and platform alone, so the check can name them all before it starts,
  * and a parallel run then works out the callees side by side instead of one at a time as the check meets them
  * (`docs/compiler-performance.md` §15.8). A sequential run ignores the hint.
  */
object BindingHints {

  /** Hint the bindings, on `platform`, of every value `expression` references. */
  def hint(expression: Option[Sourced[OperatorResolvedExpression]], platform: Platform): CompilerIO[Unit] =
    prefetch(expression.toSeq.flatMap(references).map(NativeBinding.Key(_, platform)))

  private def references(expression: Sourced[OperatorResolvedExpression]): Set[ValueFQN] =
    foldValueReferences[Id, Set[ValueFQN]](expression.value, Set.empty)((acc, ref) => acc + ref.value)
}
