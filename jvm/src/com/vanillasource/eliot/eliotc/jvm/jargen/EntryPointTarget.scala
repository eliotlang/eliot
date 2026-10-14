package com.vanillasource.eliot.eliotc.jvm.jargen

import com.vanillasource.eliot.eliotc.jvm.codec.JvmFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** The user `main` this build's entry point runs — the `-m` choice, as a fact.
  *
  * It is an edge-less leaf ([[EntryPointTargetProcessor]] reads nothing), so the incremental engine recomputes and
  * compares it every run. That is what lets the main be a **demand-scoped** configuration key: the choice enters the
  * fact graph here and only here, and the synthesized entry point ([[SyntheticMainSourceProcessor]]) — whose key is the
  * same for every main — reads it, so a cache shared across mains regenerates exactly what depends on the choice.
  */
case class EntryPointTarget(main: ValueFQN) extends CompilerFact {
  override def key(): CompilerFactKey[EntryPointTarget] = EntryPointTarget.Key()
}

object EntryPointTarget {
  case class Key() extends CompilerFactKey[EntryPointTarget] {
    override def valueCodec: Option[FactCodec[EntryPointTarget]] = Some(JvmFactCodecs.entryPointTargetCodec)
  }
}
