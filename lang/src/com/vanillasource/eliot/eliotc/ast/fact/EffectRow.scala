package com.vanillasource.eliot.eliotc.ast.fact

import cats.Applicative
import cats.syntax.all.*

/** The structured **effect-row metadata** of a value's signature — the effects-as-channel "declared row + row
  * positions" (docs/effects-as-channel.md §4, Phase 1). Captured at [[core.processor.EffectSugarDesugarer]] from the
  * *open* effect rows (`{Console} A`, no `| base` tail) of a signature **before** they are rewritten onto the carrier
  * generic, and forwarded down the value fact chain (FunctionDefinition → NamedValue → ResolvedValue →
  * BlockDesugaredValue → MatchDesugaredValue → OperatorResolvedValue) alongside `paramConstraints`, converting the
  * entry representation at each hop exactly as `paramConstraints` is converted.
  *
  * **Rendering metadata** (U4-c-0b): the row is not what anything verifies against — the scope check reads a
  * definition's minted binders — it is the declaration the desugar consumes and the LSP's declared-row vocabulary. The carrier desugar still
  * runs, so the same row information is *also* present in the desugared signature (as `F ~ E` constraints); this field
  * is the explicit, position-attributed copy, and is deliberately **not** part of any `signatureEquality` (the
  * desugared signature already carries the character-exact merge check).
  *
  * A **pinned** row (`{Throw[E] | Id} A`) named a carrier stack and has no v6 meaning; it is rejected at the desugar
  * rather than recorded, so the three fields that carried its positions and entries are gone with it. Body rows are
  * excluded (the body is the runtime, not the declared signature); so are generic-parameter-bound rows (they are
  * neither the value's ambient nor a value parameter).
  *
  * @param returnEffects
  *   Open-row entries at the **return** position — the value's own declared ambient row (what §5.1's `derived ⊆
  *   declared` check verifies the body against). Empty when the return carries no open row.
  * @param parameterEffects
  *   One entry per value parameter that itself carried an open row — an arrow-codomain callback row like `action: A =>
  *   {} Unit` — holding that parameter's index (into the definition's value args) and its open-row entries.
  *   Effect-transparent under §5. Empty for a first-order signature. An **empty** row `{}` carries the single
  *   machinery entry `Effect` here (see [[core.processor.EffectSugarDesugarer.rowEntries]]), so the position is tagged
  *   exactly as the older `{Effect}` spelling tagged it — the tag is what tells a row position from a carrier-typed
  *   one, and an entry-less tag would silently turn every migrated slot into the latter.
  * @param callbackEffects
  *   One entry per value parameter whose type is a **function with a row in its codomain** — `action: A => {} Unit`,
  *   `combine: A => B => {} B` — holding the parameter's index, how many arguments the callee calls it with, and that
  *   row's entries. Such a parameter is **code**:
  *   the callee calls it, so a lambda written at it is the caller's text and sees the caller's bindings, while a
  *   function parameter with no row is a **value**, and a value is pure (`docs/effects.md` D21, rules 2 and 3 — the
  *   later `f uses *: A => B`). Unlike [[parameterEffects]] the type is not thunked: the codomain row lowers to its
  *   payload, so nothing but this record tells the two spellings apart.
  * @tparam C
  *   The phase's ability-constraint representation: [[UnresolvedAbilityConstraint]] over the ast's and then the core's
  *   expression type, and from resolution onwards
  *   [[com.vanillasource.eliot.eliotc.resolve.fact.AbilityConstraint]] over each phase's expression type.
  */
case class EffectRow[C](
    returnEffects: Seq[C] = Seq.empty[C],
    parameterEffects: Seq[EffectRow.ParameterEffects[C]] = Seq.empty[EffectRow.ParameterEffects[C]],
    callbackEffects: Seq[EffectRow.CallbackEffects[C]] = Seq.empty[EffectRow.CallbackEffects[C]]
) {

  /** Convert every entry with a pure function, preserving positions — the pure fact-chain hops (ast→core,
    * resolve→matchdesugar) use this, mirroring their `paramConstraints` conversion.
    */
  def map[D](f: C => D): EffectRow[D] =
    EffectRow(
      returnEffects.map(f),
      parameterEffects.map(_.map(f)),
      callbackEffects.map(_.map(f))
    )

  /** Convert every entry with an effectful function, preserving positions — the resolving fact-chain hops
    * (core→resolve, matchdesugar→operator) use this, mirroring their monadic `paramConstraints` conversion.
    */
  def traverse[F[_]: Applicative, D](f: C => F[D]): F[EffectRow[D]] =
    (
      returnEffects.traverse(f),
      parameterEffects.traverse(_.traverse(f)),
      callbackEffects.traverse(_.traverse(f))
    ).mapN(EffectRow.apply)
}

object EffectRow {

  /** A single value parameter's open-row entries, tagged with the parameter's positional index into the definition's
    * value args (stable through the fact chain — no phase reorders value parameters).
    *
    * @param closed
    *   Whether the row is **closed** — written `body uses Throw[E]: A`, with no `*` (`docs/effects.md` D21 rule 4): the
    *   argument may use what the slot supplies and must discharge everything else itself, reaching nothing from the
    *   scope it is written in.
    */
  case class ParameterEffects[C](parameterIndex: Int, effects: Seq[C], closed: Boolean = false) {
    def map[D](f: C => D): ParameterEffects[D] = ParameterEffects(parameterIndex, effects.map(f), closed)

    def traverse[F[_]: Applicative, D](f: C => F[D]): F[ParameterEffects[D]] =
      effects.traverse(f).map(ParameterEffects(parameterIndex, _, closed))
  }

  /** A **code** parameter of function type — the callback `combine: A => B => {} B` — tagged with its positional index
    * and its **arity**, the number of arrows before the row: that many nested lambdas written at the slot are the
    * caller's code, and a lambda past them is the value the code hands back. [[closed]] is as for
    * [[ParameterEffects.closed]]: the code may reach nothing from where it is written.
    */
  case class CallbackEffects[C](parameterIndex: Int, arity: Int, effects: Seq[C], closed: Boolean = false) {
    def map[D](f: C => D): CallbackEffects[D] = CallbackEffects(parameterIndex, arity, effects.map(f), closed)

    def traverse[F[_]: Applicative, D](f: C => F[D]): F[CallbackEffects[D]] =
      effects.traverse(f).map(CallbackEffects(parameterIndex, arity, _, closed))
  }

  def empty[C]: EffectRow[C] = EffectRow(Seq.empty, Seq.empty)
}
