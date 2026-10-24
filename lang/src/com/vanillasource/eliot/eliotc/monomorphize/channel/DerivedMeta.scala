package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.channel.MetaInterpreter.{Store, Value}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** **A derived meta** (`docs/meta.md` §17.3): what one call answers to the meta interpretation — the meta of its
  * result, and what it did to the frames and cells it was handed — computed once and shared by every derivation that
  * makes the same call, across call nodes, processors and builds ([[DerivedMetaProcessor]]).
  *
  * A call is its callee at its type arguments, the type of its result, what is known of its arguments, and the store
  * it can reach: the frames and cells its arguments hold, and the markers standing in for the rest of its caller's
  * store ([[MetaInterpreter]]'s `Reach`). Frames and cells are numbered in the order the arguments, then the store,
  * mention them, so the same call made under any enclosing frames is the same key. A function value is named by its
  * [[MetaInterpreter.Address]], so it is data too.
  *
  * {{{
  *   applyTo(y -> add(y, 1), 10)   // in shift
  *     key      applyTo, [], Int, [Closure(shift@[…], {}), Meta([10, 10])], {}
  *     answer   Meta([11, 11]), {}, made 0
  * }}}
  *
  * A call whose interpretation runs out of the budget has no fact: its processor reports the limit at the callee's
  * definition and declines, and so does everything reading it (decision I, §5.3; Q9 of §17.3).
  *
  * @param answer
  *   What the call answers.
  */
case class DerivedMeta(
    callee: ValueFQN,
    typeArguments: Seq[GroundValue],
    resultType: GroundValue,
    arguments: Seq[Value],
    store: Store,
    answer: DerivedMeta.Answer
) extends CompilerFact {
  override def key(): CompilerFactKey[DerivedMeta] = DerivedMeta.Key(callee, typeArguments, resultType, arguments, store)
}

object DerivedMeta {

  /** What a call answers: its value, the store it ends with, and how many frames and cells it made, numbered after
    * those its key mentions. The store holds the cells it was handed with what they hold when it returns, the frames
    * it was handed with the exits it made through them, and the markers' state, from which its caller restores the
    * rest of its own store.
    */
  case class Answer(value: Value, store: Store, made: Int)

  case class Key(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      store: Store
  ) extends CompilerFactKey[DerivedMeta] {
    override def valueCodec: Option[FactCodec[DerivedMeta]] = Some(LangFactCodecs.derivedMetaCodec)
  }
}
