package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, ValueFQN}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** A compiler-platform monomorphized value: the compiler track's analogue of [[MonomorphicValue]].
  *
  * It is produced by [[com.vanillasource.eliot.eliotc.monomorphize.processor.CompilerMonomorphicTypeCheckProcessor]] by
  * running the same checker in the `Platform.Compiler` source pool, so its bodies, ability instances, and native leaves
  * come from the compiler layer (never the runtime target). It is a **distinct fact type** from [[MonomorphicValue]] on
  * purpose: the compiler processor cannot even name `MonomorphicValue.Key`, so the `compiler-mono → runtime-mono` edge
  * is impossible by construction and acyclicity is enforced rather than merely disciplined.
  *
  * It is keyed per-instantiation, exactly like [[MonomorphicValue]]: the same value reduced at different type arguments
  * is a different fact. A compile-time ability dispatched on a generic parameter can only reduce once that generic is
  * concrete, so the runtime track requests this fact at the concrete type arguments of a use site.
  *
  * The key's `vfqn` also carries the signature-split **role** (`Runtime` / `Signature`). For a `Runtime`-role key this
  * is the ordinary value mono described above. For a **`Signature`-role key** (the signature twin, whose body *is* the
  * sibling's signature expression) the checker runs in `signatureOnly` mode: [[signature]] is then the sibling value's
  * *reduced ground signature* at `typeArguments` (the "reduced, ground signature" a consumer reads), and [[reduced]] is
  * `None` (there is no separate body). A calculated-return (W3) signature twin declines — it produces no fact — because
  * only the body can solve its return; the runtime twin owns that back-edge.
  *
  * @param vfqn
  *   The fully qualified name of the original value
  * @param typeArguments
  *   The concrete type arguments used for specialization
  * @param name
  *   The sourced name of the value
  * @param signature
  *   The concrete ground type of this specialized instance, checked in the compiler platform
  * @param reduced
  *   The optional reduced compile-time body — the compiler track's analogue of [[MonomorphicValue.runtime]]. It is not
  *   destined for a code generator; it is the reduced value the runtime track's evaluator plugs in as a native.
  * @param handed
  *   As [[MonomorphicValue.handed]].
  */
case class CompilerMonomorphicValue(
    vfqn: ValueFQN,
    typeArguments: Seq[GroundValue],
    name: Sourced[QualifiedName],
    signature: GroundValue,
    reduced: Option[Sourced[MonomorphicExpression.Expression]],
    handed: Seq[GroundValue] = Seq.empty
) extends CompilerFact {
  override def key(): CompilerFactKey[CompilerMonomorphicValue] =
    CompilerMonomorphicValue.Key(vfqn, typeArguments)
}

object CompilerMonomorphicValue {

  /** Composite key mirroring [[MonomorphicValue.Key]]: same value, different type arguments → different key. */
  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue]) extends CompilerFactKey[CompilerMonomorphicValue] {
    override def valueCodec: Option[FactCodec[CompilerMonomorphicValue]] = Some(
      LangFactCodecs.compilerMonomorphicValueCodec
    )
  }
}
