package com.vanillasource.eliot.eliotc.used

import cats.data.StateT
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.used.UsedNames.UsageStats

case class UsedNamesState(
    usedNames: Map[ValueFQN, UsedNamesState.UsageStatsBuilder] = Map.empty,
    naturalArities: Map[ValueFQN, Int] = Map.empty,
    visited: Set[(ValueFQN, Seq[GroundValue])] = Set.empty,
    failed: Boolean = false
)

object UsedNamesState {
  type UsedNamesIO[T] = StateT[CompilerIO, UsedNamesState, T]

  case class UsageStatsBuilder(
      monomorphicTypeParameters: Seq[Seq[GroundValue]] = Seq.empty,
      directCallApplications: Map[Int, Int] = Map.empty
  ) {
    def addTypeParameters(typeParams: Seq[GroundValue]): UsageStatsBuilder =
      copy(monomorphicTypeParameters = monomorphicTypeParameters :+ typeParams)

    def addDirectCallApplication(argCount: Int): UsageStatsBuilder =
      copy(directCallApplications = directCallApplications.updatedWith(argCount) {
        case Some(count) => Some(count + 1)
        case None        => Some(1)
      })

    /** Cap every recorded direct-call arity at the value's natural arity: an over-applied spine is a direct call
      * absorbing `naturalArity` arguments whose *returned function value* absorbs the rest one `apply` at a time (see
      * the backend's over-application split), so the excess must not inflate the arity the value is emitted at.
      */
    def cappedAt(naturalArity: Option[Int]): UsageStatsBuilder =
      naturalArity.fold(this)(cap =>
        copy(directCallApplications = directCallApplications.foldLeft(Map.empty[Int, Int]) {
          case (acc, (arity, count)) =>
            acc.updatedWith(arity min cap)(existing => Some(existing.getOrElse(0) + count))
        })
      )

    def toUsageStats: UsageStats =
      UsageStats(monomorphicTypeParameters, directCallApplications)
  }

  extension [T](io: CompilerIO[T]) {
    def liftToUsedNames: UsedNamesIO[T] = StateT.liftF(io)
  }

  def getUsedNames(rootFQN: ValueFQN, state: UsedNamesState): UsedNames =
    UsedNames(
      rootFQN,
      usedNames =
        state.usedNames.map((vfqn, builder) => vfqn -> builder.cappedAt(state.naturalArities.get(vfqn)).toUsageStats)
    )

  def isVisited(vfqn: ValueFQN, typeArgs: Seq[GroundValue]): UsedNamesIO[Boolean] =
    StateT.get[CompilerIO, UsedNamesState].map(_.visited.contains((vfqn, typeArgs)))

  def markVisited(vfqn: ValueFQN, typeArgs: Seq[GroundValue]): UsedNamesIO[Unit] =
    StateT.modify[CompilerIO, UsedNamesState] { state =>
      state.copy(visited = state.visited + ((vfqn, typeArgs)))
    }

  def recordUsage(vfqn: ValueFQN, typeArgs: Seq[GroundValue], applicationCount: Int): UsedNamesIO[Unit] =
    StateT.modify[CompilerIO, UsedNamesState] { state =>
      val builder = state.usedNames.getOrElse(vfqn, UsageStatsBuilder())
      val updated = builder.addTypeParameters(typeArgs).addDirectCallApplication(applicationCount)
      state.copy(usedNames = state.usedNames.updated(vfqn, updated))
    }

  /** Remember the natural arity of a walked value
    * ([[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue.naturalArity]]), so the final statistics can
    * cap the recorded direct-call arities. Recorded per `vfqn` (the granularity of the usage statistics), taking the
    * **maximum** across walked instances; a body-less value contributes nothing (no cap).
    *
    * Instances of one value may differ in natural arity. The minimum would cap the wider one below the arity its call sites use, so the backend
    * caps each instance at its own natural arity where it emits it, and this cap only removes what no instance absorbs.
    */
  def recordNaturalArity(vfqn: ValueFQN, naturalArity: Option[Int]): UsedNamesIO[Unit] =
    StateT.modify[CompilerIO, UsedNamesState] { state =>
      naturalArity.fold(state)(arity =>
        state.copy(naturalArities =
          state.naturalArities.updatedWith(vfqn)(existing => Some(existing.fold(arity)(_ max arity)))
        )
      )
    }

  def markFailed(): UsedNamesIO[Unit] =
    StateT.modify[CompilerIO, UsedNamesState](_.copy(failed = true))
}
