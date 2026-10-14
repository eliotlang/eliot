package com.vanillasource.eliot.eliotc.jvm.jargen

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.processor.CompilerIO.CompilerIO
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

/** Leaf processor for [[EntryPointTarget]]: answers the configured `main`, reading no fact, so it is recomputed and
  * compared on every build.
  */
class EntryPointTargetProcessor(mainVfqn: ValueFQN) extends SingleFactProcessor[EntryPointTarget.Key] {
  override protected def generateSingleFact(key: EntryPointTarget.Key): CompilerIO[EntryPointTarget] =
    EntryPointTarget(mainVfqn).pure[CompilerIO]
}
