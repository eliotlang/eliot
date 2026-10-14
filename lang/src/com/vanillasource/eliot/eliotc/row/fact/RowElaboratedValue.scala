package com.vanillasource.eliot.eliotc.row.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** An [[OperatorResolvedValue]] whose runtime body has been **row-elaborated**: the direct-style body rewritten into
  * fully explicit monadic core (effects-as-rows v3, `docs/effects-as-rows.md` §3 — the elaboration *desugar*).
  *
  * It sits at the tail of the front-end value chain, between the recursion gate and saturation (`OperatorResolvedValue`
  * → `NamedValuesRewrittenValue` → `RecursionCheckedValue` → [[SlotBoundValue]] → `RowElaboratedValue` →
  * [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]]), so everything from the monomorphizing checker
  * onwards consumes bodies where every `flatMap`/`pure`/`runId` is already written down.
  *
  * The signature and every other field are untouched — elaboration rewrites only [[OperatorResolvedValue.runtime]].
  * That is why the *sideways* reads later phases perform for a callee's signature, fixity or effect row may keep
  * reading the [[OperatorResolvedValue]] directly: only body readers need this fact. The one exception is a slot's
  * `with`, which this phase erases and the [[OperatorResolvedValue]] does not yet carry: it is read off the
  * [[SlotBoundValue]].
  *
  * @param value
  *   The operator-resolved value with its elaborated runtime body (a body-less or non-elaborable value is carried
  *   through unchanged).
  */
case class RowElaboratedValue(value: OperatorResolvedValue) extends CompilerFact {
  override def key(): CompilerFactKey[RowElaboratedValue] = RowElaboratedValue.Key(value.vfqn, value.platform)
}

object RowElaboratedValue {
  case class Key(vfqn: ValueFQN, platform: Platform = Platform.Runtime) extends CompilerFactKey[RowElaboratedValue] {
    override def valueCodec: Option[FactCodec[RowElaboratedValue]] = Some(LangFactCodecs.rowElaboratedValueCodec)
  }
}
