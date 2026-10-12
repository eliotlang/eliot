package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The **parameters** of an implementation — its runtime environment (effects D20c option 5, `docs/effects.md` §11):
  *
  * {{{
  * implement[S] State[S](cell: Cell[S]) {
  *    def state: S = readCellInternal(cell)
  *    def putState(s: S): Unit = writeCellInternal(cell, s)
  * }
  * }}}
  *
  * Every clause receives them as **leading value parameters**, so a clause body names them like any other parameter and
  * the checker types them like any other: `state` lowers to `state[S](cell: Cell[S]): S`. What runs a clause is the
  * environment threading at the `WovenValue` seam, which passes the parameters ahead of the ability member's own
  * arguments — the checker never sees them supplied.
  *
  * Two readers must tell them apart from the member's own parameters: the conformance check, which compares a clause
  * with its ability member, and the threading, which needs their number and their types. Both read this declaration —
  * a body-less value in the implementation's namespace whose parameters *are* the implementation's, written only when
  * there are any. It is the same device as a named implementation's name marker: a declaration that exists to be
  * looked up, never referenced, so it is never monomorphized for code. Its return type carries the clause row, so it
  * declares the same phantom binders as every clause and as the marker, and accepts the same type arguments.
  */
object ImplementationParameters {

  /** The declaration's local name. Not a name the tokenizer can produce, so it collides with no clause. */
  val name: String = "$parameters"

  /** The declaration for an implementation with `parameters`, or [[None]] when it has none. */
  def declaration(
      anchor: Sourced[?],
      qualifier: Qualifier.AbilityImplementation,
      genericParameters: Seq[GenericParameter],
      parameters: Seq[ArgumentDefinition],
      clauseRow: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]]
  ): Option[FunctionDefinition] =
    Option.when(parameters.nonEmpty)(
      FunctionDefinition(
        anchor.as(QualifiedName(name, qualifier)),
        genericParameters,
        parameters,
        ImplementationRows.rowed(
          clauseRow,
          anchor.as(Expression.FunctionApplication(None, anchor.as("Unit"), None, Seq.empty))
        ),
        None
      )
    )

  /** What the parameters add to an implementation's pattern key: nothing when there are none, so every existing
    * implementation keeps its identity.
    */
  def identity(parameters: Seq[ArgumentDefinition]): String =
    if (parameters.isEmpty) ""
    else parameters.map(parameter => s"${parameter.name.value}: ${parameter.typeExpression.value.render}").mkString("(", ", ", ")")

  /** The parameters declaration of the implementation `member` belongs to — any value in its namespace. */
  def of(member: ValueFQN): ValueFQN =
    ValueFQN(member.moduleName, QualifiedName(name, member.name.qualifier))

  /** Whether `vfqn` is an implementation's parameters declaration rather than one of its clauses. */
  def isDeclaration(vfqn: ValueFQN): Boolean =
    vfqn.name.name == name && (vfqn.name.qualifier match {
      case _: Qualifier.AbilityImplementation => true
      case _                                  => false
    })
}
