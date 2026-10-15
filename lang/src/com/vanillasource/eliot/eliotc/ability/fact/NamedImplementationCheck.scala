package com.vanillasource.eliot.eliotc.ability.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** Marker fact: the named implementation whose marker is `marker` conforms to its ability — the check
  * [[AbilityImplementationCheck]] makes of an anonymous one, which a named implementation never reaches since it is
  * never searched (`docs/effects.md` §8 item 42).
  */
case class NamedImplementationCheck(marker: ValueFQN, platform: Platform = Platform.Runtime) extends CompilerFact {
  override def key(): CompilerFactKey[NamedImplementationCheck] = NamedImplementationCheck.Key(marker, platform)
}

object NamedImplementationCheck {
  case class Key(marker: ValueFQN, platform: Platform = Platform.Runtime)
      extends CompilerFactKey[NamedImplementationCheck] {
    override def valueCodec: Option[FactCodec[NamedImplementationCheck]] = Some(
      LangFactCodecs.namedImplementationCheckCodec
    )
  }
}
