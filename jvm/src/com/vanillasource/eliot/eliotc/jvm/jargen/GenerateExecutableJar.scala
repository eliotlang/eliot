package com.vanillasource.eliot.eliotc.jvm.jargen

import com.vanillasource.eliot.eliotc.jvm.codec.JvmFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** The executable jar for `vfqn` was written, and `digest` is what the file held right after the write (the SHA-256 of
  * its bytes, as [[JvmProgramGenerator.digestOf]] reads it).
  *
  * A fact accepted from the cache proves the program unchanged, not the file, so the digest is what ties it to the
  * file's real state: the jvm plugin compares it with the file after the demand and writes a jar deleted, truncated or
  * replaced behind the compiler's back again.
  */
case class GenerateExecutableJar(vfqn: ValueFQN, digest: String) extends CompilerFact {
  override def key(): CompilerFactKey[GenerateExecutableJar] = GenerateExecutableJar.Key(vfqn)
}

object GenerateExecutableJar {
  case class Key(vfqn: ValueFQN) extends CompilerFactKey[GenerateExecutableJar] {
    override def valueCodec: Option[FactCodec[GenerateExecutableJar]] = Some(JvmFactCodecs.generateExecutableJarCodec)
  }
}
