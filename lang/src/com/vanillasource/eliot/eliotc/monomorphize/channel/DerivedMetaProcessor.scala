package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor

/** Answers one call to the meta interpretation ([[DerivedMeta]], `docs/meta.md` §17.3) by interpreting it
  * ([[MetaInterpreter.answerOf]]): a callee stating its transfer reduces its companion, any other interprets its body.
  * Every call that answer makes is another [[DerivedMeta]], read for one step of this one's budget, so the budget bounds
  * what one definition does in one context, never what a program does.
  *
  * It reads its own fact type only along the call graph, which is acyclic because user code cannot recurse, as
  * [[MetaDemandsProcessor]] reads its own.
  */
class DerivedMetaProcessor extends SingleKeyTypeProcessor[DerivedMeta.Key] {

  override protected def generateFact(key: DerivedMeta.Key): CompilerIO[Unit] =
    MetaInterpreter
      .answerOf(key)
      .flatMap(answer =>
        registerFactIfClear(DerivedMeta(key.callee, key.typeArguments, key.resultType, key.arguments, key.store, answer))
      )
}
