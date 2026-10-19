package com.vanillasource.eliot.eliotc.ast.fact

import cats.Applicative
import cats.syntax.all.*

/** The structured **effect-row metadata** of a value's declaration (`docs/effects.md` §3.1): what its `uses` clauses
  * say, captured at [[core.processor.EffectSugarDesugarer]] before the clauses are lowered to handle binders, handle
  * parameters, blocks and thunks, and forwarded down the value fact chain (FunctionDefinition → NamedValue →
  * ResolvedValue → BlockDesugaredValue → MatchDesugaredValue → OperatorResolvedValue) alongside `paramConstraints`,
  * converting the entry representation at each hop exactly as `paramConstraints` is converted.
  *
  * It is declaration metadata, never a type (§3.6): `unify` sees the lowered signature, and this record is what the
  * `row` phase, the hole rule and the LSP read for what a declaration performs and where. It is deliberately **not**
  * part of any `signatureEquality` — the lowered signature already carries the character-exact merge check. A body's
  * clauses are not recorded (the body is the runtime, not the declaration), nor are a generic parameter's.
  *
  * @param returnEffects
  *   The entries of the definition's own `uses` clause — what it performs, each one handle it receives. Empty for a
  *   pure definition.
  * @param parameterEffects
  *   One entry per **code** parameter whose clause is on the parameter itself — `body uses *: A`, `body uses *,
  *   Throw[E]: A` — holding that parameter's index (into the definition's value args), the entries its clause lists and
  *   whether it is closed. A clause listing nothing is recorded too, with no entries: the record's presence is what
  *   makes the position code (a thunk), so it is never dropped for being empty. Empty for a first-order signature.
  * @param callbackEffects
  *   One entry per value parameter whose type is a **function with a clause on its codomain** — `action uses *: A =>
  *   Unit`, `combine uses *: A => B => B` — holding the parameter's index, how many arguments the callee calls it with,
  *   and the clause's entries. Such a parameter is **code**: the callee calls it, so a lambda written at it is the
  *   caller's text and sees the caller's handles, while a function parameter with no clause is a **value**, and a value
  *   is pure (`docs/effects.md` §1 rules 2 and 3). Unlike [[parameterEffects]] the type is not thunked: the codomain's
  *   clause lowers to its payload, so nothing but this record tells the two apart.
  * @param returnArity
  *   How many value parameters the definition declares before its return position — where [[returnEffects]] are
  *   performed. A function-typed return is a value, so `def printer uses Console: String => Unit` performs `Console`
  *   with no argument, while `def printLine(line: String) uses Console: Unit` needs one: a reference given fewer is
  *   unapplied, and is code rather than a value (`docs/effects.md` §1 rule 2). The signature's arrow chain cannot
  *   tell the two apart once the row is desugared away.
  * @param handles
  *   The definition's **handles** (`docs/effects.md` D36 point 3), in parameter order: each the binder typing a handle
  *   — the handle's type — and the entry of [[returnEffects]] it stands for, by index. Written where a handle is minted
  *   (an effect member's own, [[AbilityMembers]]; a `uses` entry's, the effect desugar; a row alias's, the resolver),
  *   and the one place a phase reads which binders are handle types: `def greet(name: String) uses Console: Unit`
  *   is `greet[Impl ~ Console[Impl]](Impl$handle: Impl, name: String)` with the handle `Impl` for entry 0.
  * @tparam C
  *   The phase's ability-constraint representation: [[UnresolvedAbilityConstraint]] over the ast's and then the core's
  *   expression type, and from resolution onwards
  *   [[com.vanillasource.eliot.eliotc.resolve.fact.AbilityConstraint]] over each phase's expression type.
  */
case class EffectRow[C](
    returnEffects: Seq[C] = Seq.empty[C],
    parameterEffects: Seq[EffectRow.ParameterEffects[C]] = Seq.empty[EffectRow.ParameterEffects[C]],
    callbackEffects: Seq[EffectRow.CallbackEffects[C]] = Seq.empty[EffectRow.CallbackEffects[C]],
    returnArity: Int = 0,
    handles: Seq[EffectRow.Handle] = Seq.empty
) {

  /** Convert every entry with a pure function, preserving positions — the pure fact-chain hops (ast→core,
    * resolve→matchdesugar) use this, mirroring their `paramConstraints` conversion.
    */
  def map[D](f: C => D): EffectRow[D] =
    EffectRow(
      returnEffects.map(f),
      parameterEffects.map(_.map(f)),
      callbackEffects.map(_.map(f)),
      returnArity,
      handles
    )

  /** Convert every entry with an effectful function, preserving positions — the resolving fact-chain hops
    * (core→resolve, matchdesugar→operator) use this, mirroring their monadic `paramConstraints` conversion.
    */
  def traverse[F[_]: Applicative, D](f: C => F[D]): F[EffectRow[D]] =
    (
      returnEffects.traverse(f),
      parameterEffects.traverse(_.traverse(f)),
      callbackEffects.traverse(_.traverse(f))
    ).mapN(EffectRow(_, _, _, returnArity, handles))
}

object EffectRow {

  /** A single code parameter's clause entries, tagged with the parameter's positional index into the definition's value
    * args (stable through the fact chain — no phase reorders value parameters).
    *
    * @param closed
    *   Whether the row is **closed** — written `body uses Throw[E]: A`, with no `*` (`docs/effects.md` D21 rule 4): the
    *   argument may use what the slot lists and must discharge everything else itself, reaching nothing from the scope
    *   it is written in.
    */
  case class ParameterEffects[C](parameterIndex: Int, effects: Seq[C], closed: Boolean = false) {
    def map[D](f: C => D): ParameterEffects[D] = ParameterEffects(parameterIndex, effects.map(f), closed)

    def traverse[F[_]: Applicative, D](f: C => F[D]): F[ParameterEffects[D]] =
      effects.traverse(f).map(ParameterEffects(parameterIndex, _, closed))
  }

  /** A **code** parameter of function type — the callback `combine uses *: A => B => B` — tagged with its positional
    * index and its **arity**, the number of arrows before the clause: that many nested lambdas written at the slot are the
    * caller's code, and a lambda past them is the value the code hands back. [[closed]] is as for
    * [[ParameterEffects.closed]]: the code may reach nothing from where it is written.
    */
  case class CallbackEffects[C](parameterIndex: Int, arity: Int, effects: Seq[C], closed: Boolean = false) {
    def map[D](f: C => D): CallbackEffects[D] = CallbackEffects(parameterIndex, arity, effects.map(f), closed)

    def traverse[F[_]: Applicative, D](f: C => F[D]): F[CallbackEffects[D]] =
      effects.traverse(f).map(CallbackEffects(parameterIndex, arity, _, closed))
  }

  /** One handle of a definition: the binder that is its type, and the index of the [[EffectRow.returnEffects]] entry it
    * stands for.
    */
  case class Handle(binder: String, entry: Int)

  def empty[C]: EffectRow[C] = EffectRow(Seq.empty, Seq.empty)
}
