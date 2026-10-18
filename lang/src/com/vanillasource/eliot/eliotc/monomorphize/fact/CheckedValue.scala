package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, ValueFQN}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** What the runtime track's checker makes of one value at type arguments whose **effect bindings are left out** —
  * `docs/effects.md` D35 step 4. Every binder binding an effect entry holds `Default` in the key, whatever the caller
  * wrote, so the checker checks a definition once per instantiation of its ordinary type parameters, not once per
  * implementation it is run with. An operation of an effect is left as a reference to the effect's member, which the
  * checker does not dispatch: nothing in the key says which implementation answers it.
  *
  * The specialisation pass ([[com.vanillasource.eliot.eliotc.monomorphize.specialise.SpecialisationProcessor]]) reads
  * this at the key a [[MonomorphicValue]] is asked for with its bindings erased, writes every reference's bindings
  * from the evidence in scope and dispatches each operation, so the [[MonomorphicValue]] everything downstream reads
  * is exactly the specialisation the checker used to produce.
  *
  * @param vfqn
  *   The value checked.
  * @param typeArguments
  *   Its type arguments, effect bindings at `Default`.
  * @param name
  *   The sourced name of the value.
  * @param signature
  *   The ground signature, which no binding reaches.
  * @param runtime
  *   The checked body, its effects' operations undispatched.
  * @param effects
  *   The value's effect entries at ground arguments, which no binding reaches either.
  * @param handed
  *   On the new effect path, the types of the handles this value's body hands the code of each of its code parameters,
  *   for its handed binders its key leaves open (`docs/effects.md` D36 point 6) — what a caller reads them off.
  */
case class CheckedValue(
    vfqn: ValueFQN,
    typeArguments: Seq[GroundValue],
    name: Sourced[QualifiedName],
    signature: GroundValue,
    runtime: Option[Sourced[MonomorphicExpression.Expression]],
    effects: MonomorphicEffects,
    handed: Seq[GroundValue] = Seq.empty
) extends CompilerFact {
  override def key(): CompilerFactKey[CheckedValue] = CheckedValue.Key(vfqn, typeArguments)
}

object CheckedValue {
  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue]) extends CompilerFactKey[CheckedValue] {
    override def valueCodec: Option[FactCodec[CheckedValue]] = Some(LangFactCodecs.checkedValueCodec)
  }
}
