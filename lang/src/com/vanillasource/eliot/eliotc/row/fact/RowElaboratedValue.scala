package com.vanillasource.eliot.eliotc.row.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** An [[OperatorResolvedValue]] past the `row` phase's scope check ([[com.vanillasource.eliot.eliotc.row.BindingWriter]]):
  * every row-typed slot thunked, every binding binder written `Default` and unmarked, every `with` node erased (its
  * binder's call stays, for the evidence after monomorphization).
  *
  * It sits at the tail of the front-end value chain, between the recursion gate and saturation (`OperatorResolvedValue`
  * → `NamedValuesRewrittenValue` → `RecursionCheckedValue` → `RowElaboratedValue` →
  * [[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]]). The *sideways* reads later phases perform for a
  * callee's fixity or effect row keep reading the [[OperatorResolvedValue]] directly.
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
