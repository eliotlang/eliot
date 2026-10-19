package com.vanillasource.eliot.eliotc.row.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** An [[OperatorResolvedValue]] past the `row` phase's scope check ([[com.vanillasource.eliot.eliotc.row.BindingWriter]]):
  * every handle position a hole, every handle binder written a keyed hole, every code parameter's actual a block or a
  * thunk, every `with` node a `let` of its handle (`docs/effects.md` §3.2).
  *
  * It sits at the tail of the front-end value chain, between the recursion gate and saturation (`OperatorResolvedValue`
  * → `NamedValuesRewrittenValue` → `RecursionCheckedValue` → `RowElaboratedValue` →
  * [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]]). The *sideways* reads later phases perform for a
  * callee's fixity or effect row keep reading the [[OperatorResolvedValue]] directly.
  *
  * @param value
  *   The operator-resolved value with its signature and runtime body written (a `@Signature` twin's or a type
  *   constructor's body is carried through unchanged).
  */
case class RowElaboratedValue(value: OperatorResolvedValue) extends CompilerFact {
  override def key(): CompilerFactKey[RowElaboratedValue] = RowElaboratedValue.Key(value.vfqn, value.platform)
}

object RowElaboratedValue {
  case class Key(vfqn: ValueFQN, platform: Platform = Platform.Runtime) extends CompilerFactKey[RowElaboratedValue] {
    override def valueCodec: Option[FactCodec[RowElaboratedValue]] = Some(LangFactCodecs.rowElaboratedValueCodec)
  }
}
