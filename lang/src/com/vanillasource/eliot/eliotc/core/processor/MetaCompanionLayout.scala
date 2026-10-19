package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  FunctionDefinition,
  GenericParameter,
  Expression as SourceExpression
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The shape every **meta companion** shares — the `^Where` precondition ([[MetaWhereDesugarer]]), the `^Meta`
  * transfer ([[MetaTransferDesugarer]]) and a parameter's statement ([[MetaParameterDesugarer]]) — and the **payload
  * signature** the refinement channel instantiates them by.
  *
  * Inside a companion a parameter denotes its *meta*, so each parameter is typed by the meta type of its type. That
  * meta type is never *named* here: a type is an expression, and naming its meta by its spelling (`Byte` ⤳ `Byte$Meta`)
  * fails for an alias, a computed type or a type parameter, none of which declares one. Instead a companion takes:
  *
  *   1. the definition's own binders, as its effect desugar leaves them ([[EffectSugarDesugarer]]), so they line up with
  *      the callee's monomorphization key by construction and the channel passes the callee's type arguments through;
  *      only a handle binder's constraint is dropped, since a handle's type answers it by construction and the two-site
  *      search, which would be asked, does not;
  *   2. one binder per parameter ([[metaBinderName]]), typing that parameter;
  *   3. for a transfer, one binder for the result ([[resultBinderName]]), and, when its brace lists slots, one for the
  *      meta constructor ([[constructorBinderName]]) its first parameter ([[constructorParameterName]]) is typed by; for
  *      a parameter's statement, only the constructor's binder, since what it states is typed by its parameter's.
  *
  * {{{
  *   def fold[A](condition: Bool, whenTrue uses *: A, whenFalse uses *: A) uses *: A { join(whenTrue, whenFalse) }
  *     ⟶  fold^Meta[A, Meta$condition, Meta$whenTrue, Meta$whenFalse, Meta$](
  *           condition: Meta$condition, whenTrue: Meta$whenTrue, whenFalse: Meta$whenFalse): Meta$ = join(whenTrue, whenFalse)
  *         fold$Payload[A]: Type = Function[Bool, Function[A, Function[A, A]]]
  * }}}
  *
  * The channel supplies binders 2 and 3 at each call: it evaluates the **payload signature** `<name>$Payload` — the
  * definition's parameter types and result type as a companion sees them — at the callee's type arguments, and maps each
  * through `MetaInterpreter.metaTypeOf` (`T$Meta` for a slotted type, `Unit` for every other). The evaluation is the one
  * evaluator's, so the type is what the definition's type denotes, and a code parameter is the value its code yields
  * (`whenTrue` is `A`, not the thunk the callee receives) — which is how a meta companion reads one
  * ([[EffectSugarDesugarer]]'s payload rule for the meta namespace). The companion is then monomorphized at those types
  * by the one checker, which is where its body is checked.
  */
object MetaCompanionLayout {

  /** The binder a companion types parameter `parameter` by: `Meta$<parameter>`. `$` is not an identifier character, so
    * it collides with no binder the user wrote.
    */
  def metaBinderName(parameter: String): String = s"Meta$$$parameter"

  /** The binder a transfer companion types its result by. No parameter has an empty name, so it collides with no
    * [[metaBinderName]].
    */
  val resultBinderName: String = "Meta$"

  /** The parameter a slot-producing transfer companion takes the meta *value* constructor of its result's meta type by,
    * first. The channel passes it, so the companion never names it by the result type's spelling. `$` is not an
    * identifier character, so it collides with no parameter the user wrote.
    */
  val constructorParameterName: String = "constructor$"

  /** The binder [[constructorParameterName]] is typed by: the constructor's function type, from the slots to the meta
    * type. It does not start with `Meta$`, so it collides with no [[metaBinderName]].
    */
  val constructorBinderName: String = "Constructor$"

  /** The parameter a parameter's statement companion takes in `parameter`'s place: a placeholder its brace cannot name,
    * since `$` is not an identifier character. A statement is about one parameter and reads none, so its parameters are
    * there only to say how many the definition takes and where the stated one is ([[statementParameters]]).
    */
  def placeholderName(parameter: String): String = s"$parameter$$"

  /** Appended to a definition's name (in the [[Qualifier.Meta]] namespace) to name its payload signature. */
  val payloadSuffix: String = "$Payload"

  /** The name of the payload signature of the value named `of`, in the meta namespace of its own qualifier, as its
    * companions are named. The single source of truth the channel names it through.
    */
  def payloadName(of: QualifiedName): QualifiedName = QualifiedName(of.name + payloadSuffix, Qualifier.Meta(of.qualifier))

  /** The payload signature of `definition` when it has a companion — a `where` or a transfer brace — else empty. */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    if (definition.whereClause.isEmpty && definition.returnMeta.isEmpty && definition.args.forall(_.meta.isEmpty))
      Seq.empty
    else Seq(payloadSignature(definition) -> RoleHint.NoHint)

  /** The definition's own binders, first in every companion (point 1 of the layout). */
  def ownBinders(definition: FunctionDefinition): Seq[GenericParameter] = {
    val desugared = EffectSugarDesugarer.desugar(definition)
    val handles   = desugared.effectRow.handles.map(_.binder).toSet
    desugared.genericParameters.map(gp =>
      if (handles.contains(gp.name.value)) gp.copy(abilityConstraints = Seq.empty) else gp
    )
  }

  /** The companion's parameters, each typed by its own binder, with those binders (point 2 of the layout). */
  def metaParameters(definition: FunctionDefinition): (Seq[GenericParameter], Seq[ArgumentDefinition]) = {
    val parameters = EffectSugarDesugarer.desugar(definition).args
    (
      parameters.map(arg => typeBinder(arg.name.map(metaBinderName))),
      parameters.map(arg => ArgumentDefinition(arg.name, typeReference(arg.name.map(metaBinderName))))
    )
  }

  /** [[metaParameters]] with each parameter a [[placeholderName]]: the same binders, the same arity, nothing to read. */
  def statementParameters(definition: FunctionDefinition): (Seq[GenericParameter], Seq[ArgumentDefinition]) = {
    val (binders, parameters) = metaParameters(definition)
    (binders, parameters.map(arg => arg.copy(name = arg.name.map(placeholderName))))
  }

  /** A binder of kind `Type` named `name`. */
  def typeBinder(name: Sourced[String]): GenericParameter =
    GenericParameter(name, typeReference(name.as("Type")), Seq.empty)

  /** A bare reference to the type named `name`. */
  def typeReference(name: Sourced[String]): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, None, Seq.empty))

  /** The definition's result type as a companion reads it: its effect clause lowered to the payload. */
  def payloadResult(definition: FunctionDefinition): Sourced[SourceExpression] =
    payloadView(definition).typeDefinition

  /** `<name>$Payload[<own binders>]: Type = Function[P1, Function[…, R]]` — the parameter and result types a companion
    * reads, as one type the channel evaluates. Its parameters are exactly the companion's, so the channel peels as many
    * arrows as the companion takes parameters, and a function-typed result stays whole.
    */
  private def payloadSignature(definition: FunctionDefinition): FunctionDefinition = {
    val view = payloadView(definition)
    FunctionDefinition(
      definition.name.map(payloadName),
      ownBinders(definition),
      Seq.empty,
      typeReference(definition.name.as("Type")),
      Some(view.args.map(_.typeExpression).foldRight(view.typeDefinition)(function)),
      visibility = definition.visibility
    )
  }

  /** The definition with its effect sugar lowered the way a meta companion's is: a handle per entry, but a code
    * parameter is its payload and takes no handed binder. Its parameters line up with the definition's own lowering
    * one for one, so the payload signature describes exactly the parameters the companion takes.
    */
  private def payloadView(definition: FunctionDefinition): FunctionDefinition =
    EffectSugarDesugarer.desugar(definition.copy(name = definition.name.map(payloadName)))

  /** `Function[domain, codomain]`, module-qualified so it needs no import. */
  private def function(domain: Sourced[SourceExpression], codomain: Sourced[SourceExpression]): Sourced[SourceExpression] =
    domain.as(
      SourceExpression.FunctionApplication(
        Some(domain.as(WellKnownTypes.functionDataTypeFQN.moduleName.show)),
        domain.as(WellKnownTypes.functionDataTypeFQN.name.name),
        Some(Seq(domain, codomain)),
        Seq.empty
      )
    )
}
