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
    * @param parameterIndex
    *   The parameter's index as the definition **declares** it. Where a slot has more than one variant, the
    *   specialisation's own parameters hold one block per variant ([[positions]]), so a later parameter sits further
    *   along than its declared index.
    * @param variants
    *   What this specialisation's body **runs** the parameter with (`docs/effects.md` D35): per distinct run, the
    *   binding of the evidence each entry finds there, in the order of `entries`, the variants in the order of their
    *   first run. A block written at the slot is compiled once per variant, against that variant's evidence — read off
    *   the callee's body rather than its declaration. Empty where nothing runs it — a body-less value, a parameter its
    *   body never runs, or a specialisation the pass has not produced yet ([[CheckedValue]]).
    */
  case class Slot(parameterIndex: Int, entries: Seq[Entry], variants: Seq[Seq[GroundValue]] = Seq.empty) {

    /** How many blocks the specialisation takes for this slot: one per variant, and one where nothing runs it. */
    def copies: Int = variants.size max 1

    /** What variant `variant` gives each entry, `default` where nothing runs the parameter. */
    def offered(variant: Int, default: GroundValue): Seq[(Entry, GroundValue)] =
      variants.lift(variant).fold(entries.map(_ -> default))(entries.zip(_))
  }

  /** The specialisation's parameter positions holding blocks, each with its slot and the variant whose block it takes:
    * variant 0 at the slot's position, each further variant right after it, every later parameter shifted along
    * (`docs/effects.md` D35 step 5a).
    *
    * {{{
    * def twice(body uses *, Terminal: Unit, n: Int)   -- two variants of body's runs
    * positions = {0 -> (body, 0), 1 -> (body, 1)}      -- n is at position 2
    * }}}
    */
  def positions(slots: Seq[Slot]): Map[Int, (Slot, Int)] =
    slots
      .sortBy(_.parameterIndex)
      .foldLeft((0, Map.empty[Int, (Slot, Int)])) { case ((shift, acc), slot) =>
        val start = slot.parameterIndex + shift
        (shift + slot.copies - 1, acc ++ (0 until slot.copies).map(variant => (start + variant) -> (slot, variant)))
      }
      ._2

  /** The parameter positions that hold a further variant's block, which a declared-arity reading drops. */
  def copyPositions(slots: Seq[Slot]): Set[Int] =
    positions(slots).collect { case (position, (_, variant)) if variant > 0 => position }.toSet

  /** The name of the parameter holding variant `variant`'s block of the code parameter `name`; variant 0 is `name`. */
  def variantName(name: String, variant: Int): String =
    if (variant == 0) name else s"$name$$variant$variant"

  /** Where a reference the checker **dispatched** to an implementation's clause came from: the ability member's entry
    * at its ground ability-level arguments, and the binding it was dispatched on. The clause's own name says which
    * implementation answered, never which entry asked.
    */
  case class Dispatch(entry: Entry, binding: GroundValue)
}
