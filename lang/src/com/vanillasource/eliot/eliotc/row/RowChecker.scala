package com.vanillasource.eliot.eliotc.row

import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, Role, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}

/** The declared world the `row` phase reads, and the two small readings of a definition it needs
  * (`docs/effects.md` §9.4).
  *
  * Under v6 there is no row *derivation* here. An effect is performed because a callee's declaration says so, and the
  * write ([[BindingWriter]]) reads that declaration directly at every reference; the row-set algebra that used to
  * derive `derived ⊆ declared` from a body went with the carrier it was written for. What is left is the universe the
  * write consults and the two predicates it shares with its processor.
  */
object RowChecker {

  /** The declared world the write reads: the operator-resolved values by name, plus the platform-registered values at
    * which every effect's chain ends ([[RunBoundaryFunctions]]).
    *
    * A batch consumer (a sweep, a test) holds every value up front. A *demand-driven* consumer (the pipeline processor)
    * cannot: it must know which names the write actually consults before it can fetch them. That is what `onMiss` is
    * for — every consultation of a name the map does not hold reports it, so the processor can fetch the reported names
    * and re-run until nothing is missing. Without it a demand-driven consumer would have to guess the consulted set,
    * and a wrong guess silently leaves a binder unwritten.
    */
  case class Universe(
      values: Map[ValueFQN, OperatorResolvedValue],
      runBoundaries: Set[ValueFQN] = Set.empty,
      onMiss: ValueFQN => Unit = _ => ()
  ) {

    /** Consult a *referenced* name (a callee, a type alias, a run boundary), reporting a miss. Reading the value
      * currently being written goes directly to [[values]] instead — its absence is not a gap.
      */
    def lookup(fqn: ValueFQN): Option[OperatorResolvedValue] = {
      val found = values.get(fqn)
      if (found.isEmpty) onMiss(fqn)
      found
    }
  }

  /** Whether a value carries a term-level body: a body (abstract signatures have nothing to write), a runtime-role
    * value (never a `@Signature` twin), and not a type constructor / alias / meta companion, whose "bodies" are types.
    *
    * The write itself uses a *wider* predicate — a meta companion is written too — so this stays the narrow reading
    * for consumers that mean "an ordinary definition's body".
    */
  def checkable(orv: OperatorResolvedValue): Boolean =
    orv.runtime.isDefined &&
      orv.vfqn.name.role == Role.Runtime &&
      (orv.vfqn.name.qualifier match {
        case Qualifier.Type | _: Qualifier.Meta => false
        case _                                  => true
      })

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
