package com.vanillasource.eliot.eliotc.monomorphize.check

/** The compile-time `Throw[String]` guard channel's read-back protocol — the conventions shared by its two
  * interpreters, so they cannot drift:
  *
  *   - [[GuardDischargeResolver.dischargeGuardedReturn]] reads a *signature return* guard in the semantic domain
  *     (`Right(t)` ⤳ the payload type, `Left(msg)` ⤳ the author's diagnostic) — the W2b effectful-signatures discharge;
  *   - [[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationProcessor]] reads an
  *     ability-implementation `where` guard's verdict in the ground domain (`true`/`false`/`Right`/`Left` ⤳
  *     keep/decline/reject — ability-guards §3.1).
  *
  * Both read the same constructed `Either` values, recognised by
  * [[com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.leftFQN]] /
  * [[com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.rightFQN]] heads.
  */
object GuardChannel {

  /** The payload of a constructed `Left`/`Right` value: the constructor's value field is the **last** argument
    * — any leading arguments are the implicit `E`/`A` type arguments. The one place this convention is written down;
    * both interpreters extract through it.
    */
  def payload[A](args: Seq[A]): Option[A] = args.lastOption

  /** Fallback for a rejection whose message did not reduce to a `String` literal (computed, not-yet-reduced — should
    * not occur at a concrete site): the rejection is still reported, never silently dropped (fail-safe).
    */
  val fallbackRejectionMessage: String = "A type guard rejected this use."
}
