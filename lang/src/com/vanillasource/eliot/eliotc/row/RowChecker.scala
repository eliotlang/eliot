package com.vanillasource.eliot.eliotc.row

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}

/** The declared world the `row` phase reads, and the two small readings of a definition it needs
  * (`docs/effects.md` §3.2).
  *
  * There is no row *derivation* here. An effect is performed because a callee's declaration says so, and the write
  * ([[BindingWriter]]) reads that declaration directly at every reference. What is here is the universe the write
  * consults and the two predicates it shares with its processor.
  */
object RowChecker {

  /** The declared world the write reads: the operator-resolved values by name, and the clauses of the implementations
    * a `with` names, by their marker.
    *
    * A batch consumer (a sweep, a test) holds every value up front. A *demand-driven* consumer (the pipeline processor)
    * cannot: it must know which names the write actually consults before it can fetch them. That is what `onMiss` and
    * `onMissClauses` are for — every consultation of a name the maps do not hold reports it, so the processor can fetch
    * the reported names and re-run until nothing is missing. Without them a demand-driven consumer would have to guess
    * the consulted set, and a wrong guess silently leaves a binder unwritten.
    */
  case class Universe(
      values: Map[ValueFQN, OperatorResolvedValue],
      clauses: Map[ValueFQN, Seq[ValueFQN]] = Map.empty,
      onMiss: ValueFQN => Unit = _ => (),
      onMissClauses: ValueFQN => Unit = _ => ()
  ) {

    /** Consult a *referenced* name (a callee, a type alias, an implementation), reporting a miss. Reading the value
      * currently being written goes directly to [[values]] instead — its absence is not a gap.
      */
    def lookup(fqn: ValueFQN): Option[OperatorResolvedValue] = {
      val found = values.get(fqn)
      if (found.isEmpty) onMiss(fqn)
      found
    }

    /** The clauses of the implementation whose marker is `marker` — every value in its namespace but the marker and its
      * own declarations — reporting a miss.
      */
    def clausesOf(marker: ValueFQN): Option[Seq[ValueFQN]] = {
      val found = clauses.get(marker)
      if (found.isEmpty) onMissClauses(marker)
      found
    }
  }

  /** Peel the leading binders of a runtime body, collecting their names in order — a definition's body is what its
    * lambdas wrap.
    */
  def peelBinders(expr: OperatorResolvedExpression): (Seq[String], OperatorResolvedExpression) = expr match {
    case FunctionLiteral(name, _, body) =>
      val (rest, inner) = peelBinders(body.value)
      (name.value +: rest, inner)
    case other                          => (Seq.empty, other)
  }
}
