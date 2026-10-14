package com.vanillasource.eliot.eliotc.row.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** A value past the recursion gate whose code parameters' slots name the implementations their runs are bound by
  * ([[com.vanillasource.eliot.eliotc.row.RunWithHoister]], `docs/effects.md` D23 and D25): a `with` around a run, and
  * the `with` of a callee's slot a run is passed to, are moved onto the slot, where every caller reads them.
  *
  * It sits between the gate and the write (`RecursionCheckedValue` → `SlotBoundValue` → [[RowElaboratedValue]]), and
  * it is the **declaration** the write and the environment threading read for any value whose slots they consult —
  * never the [[OperatorResolvedValue]], whose slots name only what their author wrote. Being after the gate is what
  * makes reading a callee's slots safe: a value reaches this fact only when its body cannot reach itself, so the
  * callees it reads never read it back.
  *
  * @param value
  *   The certified value, with its slots' bindings and its body's hoisted `with`s removed; a value with no code
  *   parameter is carried through unchanged.
  */
case class SlotBoundValue(value: OperatorResolvedValue) extends CompilerFact {
  override def key(): CompilerFactKey[SlotBoundValue] = SlotBoundValue.Key(value.vfqn, value.platform)
}

object SlotBoundValue {
  case class Key(vfqn: ValueFQN, platform: Platform = Platform.Runtime) extends CompilerFactKey[SlotBoundValue] {
    override def valueCodec: Option[FactCodec[SlotBoundValue]] = Some(LangFactCodecs.slotBoundValueCodec)
  }
}
