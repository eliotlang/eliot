package com.vanillasource.eliot.eliotc.monomorphize.domain

import scala.collection.immutable.IntMap

/** Mutable-style metavariable store for unification. Backed by IntMap for efficient allocation and lookup.
  *
  * @param entries
  *   Map from meta id to its solution (None = unsolved, Some = solved)
  * @param nextId
  *   Next fresh meta id to allocate
  */
case class FlexStore(entries: IntMap[Option[SemValue]], nextId: Int) {

  /** Allocate a fresh unsolved metavariable. Returns the new id and updated store. */
  def fresh: (SemValue.FlexId, FlexStore) = {
    val id = SemValue.FlexId(nextId)
    (id, FlexStore(entries.updated(nextId, None), nextId + 1))
  }

  /** Solve a metavariable. */
  def solve(id: SemValue.FlexId, value: SemValue): FlexStore =
    FlexStore(entries.updated(id.value, Some(value)), nextId)

  /** Look up a metavariable's current solution. */
  def lookup(id: SemValue.FlexId): Option[SemValue] =
    entries.getOrElse(id.value, None)
}

object FlexStore {
  val empty: FlexStore = FlexStore(IntMap.empty, 0)
}
