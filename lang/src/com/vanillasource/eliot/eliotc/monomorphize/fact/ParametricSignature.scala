package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.codec.LangFactCodecs

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** A value's signature checked **once**, with every binder a placeholder: the ground signature with each binder read
  * back as a [[GroundValue.Param]]. Every instance's signature is then this one with the instance's type arguments
  * substituted (`GroundValue.substituteParams`), instead of a check of its own (`docs/compiler-performance.md` §12).
  *
  * For `def foldLeft[A, B](initial: B, combine: (B, A) => B, list: List[A]): B` it is `B => ((B, A) => B) => List[A] =>
  * B` with `A`, `B` as `Param(0)`, `Param(1)`, and the instance `[Int, String]` is that with `Int` and `String` plugged
  * in.
  *
  * The signature is `None` wherever substitution might not be exact, and each instance is then checked as before: where
  * the check **computes** on a binder (`Box[I + 1]`, a `match` on a type parameter — a stuck neutral does not read
  * back), where it would need a fact at a placeholder argument, where the read-back was not plain evaluation, and where
  * the signature is a guard, whose verdict each instance decides for itself. It is a `None` rather than a decline so
  * that the answer is cached: a decline is regenerated on every build. Either way nothing is reported, since a guess
  * the compiler makes for itself has no diagnostics of the program's.
  *
  * @param vfqn
  *   the value's `Signature`-role name, as in the [[CompilerMonomorphicValue.Key]] of its instances
  * @param signature
  *   the parametric ground signature, or `None` where an instance's signature is not a substitution into it
  */
case class ParametricSignature(vfqn: ValueFQN, signature: Option[GroundValue]) extends CompilerFact {
  override def key(): CompilerFactKey[ParametricSignature] = ParametricSignature.Key(vfqn)
}

object ParametricSignature {
  case class Key(vfqn: ValueFQN) extends CompilerFactKey[ParametricSignature] {
    override def valueCodec: Option[FactCodec[ParametricSignature]] = Some(LangFactCodecs.parametricSignatureCodec)
  }
}
