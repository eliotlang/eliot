package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN

/** What one specialisation's effects are at its ground arguments — the facts the **specialisation pass** and the
  * **environment threading** at the `WovenValue` seam read (`docs/effects.md` §3.4, D35).
  *
  * An implementation is **evidence**: its code and its environment, one value, found after monomorphization. The
  * specialisation pass writes the code half into a [[MonomorphicValue]]'s type arguments from the evidence in scope;
  * the threading passes the environment half from the same evidence. Both find *which entry in scope* answers a use
  * at the entry's ground arguments, so that `{Throw[IoError], Throw[GitError]}` is two frames, not one. Those
  * arguments are type expressions over the definition's binders, and only the checker's one evaluator may reduce them,
  * so they are reduced here, at the end of the value's own monomorphization, and recorded.
  *
  * @param received
  *   The entries this definition **receives** from its caller — one per binding binder, which its declared row and its
  *   `~` constraints mint — each with the binder's index, whose type argument holds the caller's evidence.
  * @param slots
  *   The entries each **row-typed parameter** declares, by parameter index: what a block passed there may be given.
  */
case class MonomorphicEffects(received: Seq[MonomorphicEffects.Received], slots: Seq[MonomorphicEffects.Slot])

object MonomorphicEffects {

  val empty: MonomorphicEffects = MonomorphicEffects(Seq.empty, Seq.empty)

  /** One row entry or constraint at ground arguments: `Throw[String]` is `Entry(Throw, Seq(String))`. */
  case class Entry(ability: AbilityFQN, arguments: Seq[GroundValue])

  /** An entry the definition receives, bound by its type argument at `binderIndex`. */
  case class Received(binderIndex: Int, entry: Entry)

  /** The entries the row-typed parameter at `parameterIndex` declares.
    *
    * @param offered
    *   The binding of the evidence each entry finds where this specialisation's body **runs** the parameter, in the
    *   order of `entries` (`docs/effects.md` D35): what a block written at the slot is compiled against, read off the
    *   callee's body rather than its declaration. [[None]] where nothing runs it — a body-less value, a parameter its
    *   body never runs, or a specialisation the pass has not produced yet ([[CheckedValue]]).
    */
  case class Slot(parameterIndex: Int, entries: Seq[Entry], offered: Option[Seq[GroundValue]] = None)

  /** Where a reference the checker **dispatched** to an implementation's clause came from: the ability member's entry
    * at its ground ability-level arguments, and the binding it was dispatched on. The clause's own name says which
    * implementation answered, never which entry asked.
    */
  case class Dispatch(entry: Entry, binding: GroundValue)
}
