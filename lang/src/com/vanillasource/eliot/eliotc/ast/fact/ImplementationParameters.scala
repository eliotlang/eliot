package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The **parameters** of a named implementation — its runtime environment (effects D20c option 5, `docs/effects.md`
  * §3.4). Only a named implementation takes them (D25): nothing that inserts a default has arguments to give one.
  *
  * {{{
  * implement[S] holding(cell: StateCell[S]): State[S] {
  *    def state: S = readCellInternal(cell)
  *    def putState(s: S): Unit = writeCellInternal(cell, s)
  * }
  * }}}
  *
  * Every clause receives them as **leading value parameters**, so a clause body names them like any other parameter and
  * the checker types them like any other: `state` lowers to `state[S](cell: StateCell[S]): S`. What runs a clause is the
  * environment threading at the `WovenValue` seam, which passes the parameters ahead of the ability member's own
  * arguments — the checker never sees them supplied.
  *
  * Two readers must tell them apart from the member's own parameters: the conformance check, which compares a clause
  * with its ability member, and the threading, which needs their number and their types. Both read this declaration —
  * a body-less value in the implementation's namespace whose parameters *are* the implementation's, written only when
  * there are any. It is the same device as a named implementation's name marker: a declaration that exists to be
  * looked up, never referenced, so it is never monomorphized for code. Its return type carries the clause row, so it
  * declares the same phantom binders as every clause and as the marker, and accepts the same type arguments.
  *
  * It has a second declaration, its **binder** ([[binder]]): what a `with` supplying arguments is lowered to, so the checker types the arguments against the parameters like any call's.
  *
  * {{{
  * implement scripted(lines: List[String]): Terminal { … }
  *   ⟶ def scripted$with[Subject$](lines: List[String], subject$: Subject$): Subject$      -- body-less
  *
  * greet with scripted(List("Bob"))
  *   ⟶ scripted$with(List("Bob"), greet with scripted)
  * }}}
  *
  * The arguments stand outside the `with`, so they are computed where the `with` is written and see none of its
  * bindings. The environment threading replaces the call with the arguments bound to fresh parameters around the
  * subject, which is where the implementation's frame is built from them; nothing ever runs the declaration itself.
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

  /** The binder declaration's local name. */
  val binderName: String = "$with"

  /** The name of the binder's type parameter for its subject, and of the subject parameter. */
  private val subjectType: String = "Subject$"
  private val subject: String     = "subject$"

  /** The binder declaration ([[binder]]) of a named implementation with `parameters`, or [[None]] when it has none.
    * Its type parameters are the implementation's own and then the subject's; it declares no row, so a reference to it
    * is written nothing and the checker infers every type argument from the call.
    */
  def binder(
      anchor: Sourced[?],
      qualifier: Qualifier.AbilityImplementation,
      genericParameters: Seq[GenericParameter],
      parameters: Seq[ArgumentDefinition]
  ): Option[FunctionDefinition] =
    Option.when(parameters.nonEmpty) {
      val subjectTypeReference = anchor.as(Expression.FunctionApplication(None, anchor.as(subjectType), None, Seq.empty))
      FunctionDefinition(
        anchor.as(QualifiedName(binderName, qualifier)),
        genericParameters :+ GenericParameter(
          anchor.as(subjectType),
          anchor.as(Expression.FunctionApplication(None, anchor.as("Type"), None, Seq.empty)),
          Seq.empty
        ),
        parameters :+ ArgumentDefinition(anchor.as(subject), subjectTypeReference),
        subjectTypeReference,
        None
      )
    }

  /** The binder declaration of the implementation `member` belongs to — any value in its namespace. */
  def binderOf(member: ValueFQN): ValueFQN =
    ValueFQN(member.moduleName, QualifiedName(binderName, member.name.qualifier))

  /** Whether `vfqn` is an implementation's binder declaration. */
  def isBinder(vfqn: ValueFQN): Boolean =
    vfqn.name.name == binderName && isImplementation(vfqn)

  /** Whether `name` is the local name of one of an implementation's own declarations rather than of a clause. */
  def isAuxiliary(name: String): Boolean = name == this.name || name == binderName

  /** The parameters declaration of the implementation `member` belongs to — any value in its namespace. */
  def of(member: ValueFQN): ValueFQN =
    ValueFQN(member.moduleName, QualifiedName(name, member.name.qualifier))

  /** Whether `vfqn` is an implementation's parameters declaration rather than one of its clauses. */
  def isDeclaration(vfqn: ValueFQN): Boolean =
    vfqn.name.name == name && isImplementation(vfqn)

  private def isImplementation(vfqn: ValueFQN): Boolean =
    vfqn.name.qualifier match {
      case _: Qualifier.AbilityImplementation => true
      case _                                  => false
    }
}
