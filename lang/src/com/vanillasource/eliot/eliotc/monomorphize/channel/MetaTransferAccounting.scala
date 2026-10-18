package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** The **meta-transfer accounting** of one monomorphic instance (docs/total-meta-transfers.md §2/§P2, closing the TODO
  * *"A native that produces a meta-carrying type must state its meta-information"*) — the post-monomorphization witness
  * that a value satisfies **R2**: every body-less value (a native leaf) whose return carries non-`Unit`
  * meta-information states a `^Meta` transfer for it.
  *
  * A rider on [[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue]], built on the same template as
  * [[RefinementChannelProcessor]]. Producing the fact is contingent on the check
  * passing: a leaf that originates a meta-carrying return but states no transfer is reported at the value and the fact
  * declines (aborts).
  *
  * '''Armed''' (S5): [[WovenValueProcessor]] demands it as a codegen precondition, so a leaf that
  * states nothing blocks its own `WovenValue` and with it codegen. Arming waited until the meta-carrying stdlib leaves
  * stated their transfers (docs/total-meta-transfers.md §P2).
  *
  * The fact carries no payload beyond its identity — a pass/fail witness — but it is nonetheless **persisted**, like
  * the refinement table and unlike [[com.vanillasource.eliot.eliotc.monomorphize.fact.NativeBinding]]:
  * only a fact that *cannot* be equality-stable may decline a codec (a binding holds a Scala lambda; this holds an FQN
  * and ground type arguments), and a build now materialises this one.
  *
  * @param vfqn
  *   The value this accounting belongs to (the same instance identity as its `MonomorphicValue`).
  * @param typeArguments
  *   The concrete type arguments of the instance.
  */
case class MetaTransferAccounting(
    vfqn: ValueFQN,
    typeArguments: Seq[GroundValue]
) extends CompilerFact {
  override def key(): CompilerFactKey[MetaTransferAccounting] = MetaTransferAccounting.Key(vfqn, typeArguments)
}

object MetaTransferAccounting {

  /** Keyed exactly like [[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue.Key]] — the same `vfqn` at
    * different type arguments is a different instance, hence a different accounting.
    */
  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue]) extends CompilerFactKey[MetaTransferAccounting] {
    override def valueCodec: Option[FactCodec[MetaTransferAccounting]] = Some(
      LangFactCodecs.metaTransferAccountingCodec
    )
  }
}
