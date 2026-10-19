package com.vanillasource.eliot.eliotc.saturate.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** The [[OperatorResolvedValue]] republished as the fact every checker-phase reader of a value's *signature* consumes
  * (the monomorphize entry point and the `ValueReference` read in `Checker`). It is a distinct fact from
  * [[OperatorResolvedValue]] only to break a fact cycle: [[OperatorResolvedValue]] is produced upstream by the
  * `operator` phase, so a phase reading and republishing it cannot reuse the same key.
  *
  * The name is historical: this fact once carried a *saturated* signature (the retired implicit-generics `auto` feature
  * rewrote bare omittable type-constructor references into explicit applications over fresh binders). With no user
  * surface for omittable binders, [[SaturatedValueProcessor]] is a pass-through and this value equals its
  * [[OperatorResolvedValue]] verbatim.
  *
  * @param value
  *   The operator-resolved value carrying the signature.
  */
case class SaturatedValue(value: OperatorResolvedValue) extends CompilerFact {
  override def key(): CompilerFactKey[SaturatedValue] = SaturatedValue.Key(value.vfqn, value.platform)

  /** The codegen-relevance classification of each leading generic binder, computed once on this saturated value — the
    * monomorphization-keying plan's B1 analysis (grown from the D6 reified-binder flag). Each binder carries its
    * reified/dispatched/representation roles and a derived [[BinderRoles.Disposition]]. Two consumers use it: the
    * monomorphize binding cache (`BindingClosure.reifyingWrap`, via [[BinderRoles.reifiedPrefixBinders]]) and the
    * codegen-key dedup (`used/CodegenProjection.scala`). Computed on the *saturated* signature so the binder indices
    * align with the type arguments the checker applies.
    */
  lazy val binderRoles: BinderRoles = BinderRoles.of(value)
}

object SaturatedValue {
  case class Key(vfqn: ValueFQN, platform: Platform = Platform.Runtime) extends CompilerFactKey[SaturatedValue] {
    override def valueCodec: Option[FactCodec[SaturatedValue]] = Some(LangFactCodecs.saturatedValueCodec)
  }
}
