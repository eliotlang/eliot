package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{ArgumentDefinition, FunctionDefinition, Expression as SourceExpression}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a parameter's **meta brace** into its **statement companion** (`docs/total-meta-transfers.md` §8.9) — the
  * meta-information the value at that position is stated to have, which the refinement channel demands of the argument
  * at every call and gives the parameter inside the body.
  *
  * {{{
  *   def writeByte(b: Int {byte}): Unit
  *     ⟶  writeByte$Parameter$b[Meta$b, Constructor$](constructor$: Constructor$, b$: Meta$b): Meta$b = constructor$(byte)
  * }}}
  *
  * A brace lists the **slots** of the parameter's meta, as a return brace on a concrete result does, and the meta
  * constructor is passed by the channel the same way ([[MetaCompanionLayout.constructorParameterName]]), so a parameter
  * typed by an alias needs no name. The statement reads no parameter: its parameters are placeholders a brace cannot
  * name ([[MetaCompanionLayout.placeholderName]]), there so the channel can tell how many the definition takes and which
  * one is stated without knowing their names — a native has no body to read them from. Its result is typed by the stated
  * parameter's own meta binder. Companions are compiler-pool-only (the channel evaluates them), dead in the runtime pool.
  */
object MetaParameterDesugarer {

  /** Between a definition's name and its parameter's in a statement companion's name. `$` is not an identifier
    * character, so the result collides with no user-declared name, nor with a `^Where` companion or a payload signature.
    */
  val parameterInfix: String = "$Parameter$"

  /** The name of the statement companion of parameter `parameter` of the value named `of`, in the meta namespace of its
    * own qualifier, as [[MetaWhereDesugarer.companionName]] names a `where`. The single source of truth the channel reads
    * it through, with [[statedParameter]].
    */
  def companionName(of: QualifiedName, parameter: String): QualifiedName =
    QualifiedName(of.name + parameterInfix + parameter, Qualifier.Meta(of.qualifier))

  /** The parameter `name` is the statement companion of, when it is one of the value named `of`. */
  def statedParameter(of: QualifiedName, name: QualifiedName): Option[String] =
    Option.when(name.qualifier == Qualifier.Meta(of.qualifier) && name.name.startsWith(of.name + parameterInfix))(
      name.name.drop(of.name.length + parameterInfix.length)
    )

  /** Between a definition's name and its code parameter's in a **handed** statement's name, followed by the parameter,
    * a `$` and the index of the argument the statement is about.
    */
  val handedInfix: String = "$Handed$"

  /** The name of the handed statement about argument `argument` that the value named `of` hands its code parameter
    * `parameter`, in the meta namespace of its own qualifier. The single source of truth the channel reads it through,
    * with [[handedArgument]].
    */
  def handedName(of: QualifiedName, parameter: String, argument: Int): QualifiedName =
    QualifiedName(s"${of.name}$handedInfix$parameter$$$argument", Qualifier.Meta(of.qualifier))

  /** The code parameter and argument index `name` is the handed statement of, when it is one of the value named `of`. */
  def handedArgument(of: QualifiedName, name: QualifiedName): Option[(String, Int)] =
    Option
      .when(name.qualifier == Qualifier.Meta(of.qualifier) && name.name.startsWith(of.name + handedInfix))(
        name.name.drop(of.name.length + handedInfix.length)
      )
      .flatMap { rest =>
        val separator = rest.lastIndexOf('$')
        rest.drop(separator + 1).toIntOption.filter(_ => separator > 0).map(rest.take(separator) -> _)
      }

  /** A **handed** statement (`docs/total-meta-transfers.md` §8.10): the meta of the `argument`-th value the body-less
    * `definition` hands its code parameter `parameter` when it runs it — a supplier the compiler cannot see, so stated.
    * Its shape is a parameter statement's, with one more binder, typing what it states: the meta type of that argument,
    * which the channel reads off the code argument's own function type.
    *
    * {{{
    *   data Pixel(r: Int {byte}, g: Int)
    *     ⟶  handleCases$Handed$case0$0[R, Meta$value, Meta$case0, Meta$, Constructor$](
    *           constructor$: Constructor$, value$: Meta$value, case0$: Meta$case0): Meta$ = constructor$(byte)
    * }}}
    *
    * Only [[DataDefinitionDesugarer]] writes one, for a braced field: the `match` eliminator hands each handler its
    * constructor's fields, and the constructor proved each brace when the value was built.
    */
  def handedStatement(
      definition: FunctionDefinition,
      parameter: Sourced[String],
      argument: Int,
      brace: Seq[Sourced[SourceExpression]]
  ): FunctionDefinition = {
    val (metaBinders, placeholders) = MetaCompanionLayout.statementParameters(definition)
    val constructor                 = parameter.as(MetaCompanionLayout.constructorParameterName)
    val handedBinder                = MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.resultBinderName))
    val constructorBinder           =
      MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.constructorBinderName))
    FunctionDefinition(
      definition.name.map(handedName(_, parameter.value, argument)),
      MetaCompanionLayout.ownBinders(definition) ++ metaBinders :+ handedBinder :+ constructorBinder,
      ArgumentDefinition(constructor, MetaCompanionLayout.typeReference(constructorBinder.name)) +: placeholders,
      MetaCompanionLayout.typeReference(handedBinder.name),
      Some(parameter.as(SourceExpression.FunctionApplication(None, constructor, None, brace))),
      visibility = definition.visibility
    )
  }

  /** One statement companion per parameter of `definition` carrying a meta brace, or empty (every ordinary def). */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.args.filter(_.meta.nonEmpty).map(statementCompanion(definition, _) -> RoleHint.NoHint)

  private def statementCompanion(f: FunctionDefinition, parameter: ArgumentDefinition): FunctionDefinition = {
    val (metaBinders, placeholders) = MetaCompanionLayout.statementParameters(f)
    val constructor                 = parameter.name.as(MetaCompanionLayout.constructorParameterName)
    val constructorBinder           =
      MetaCompanionLayout.typeBinder(parameter.name.as(MetaCompanionLayout.constructorBinderName))
    FunctionDefinition(
      f.name.map(companionName(_, parameter.name.value)),
      MetaCompanionLayout.ownBinders(f) ++ metaBinders :+ constructorBinder,
      ArgumentDefinition(constructor, MetaCompanionLayout.typeReference(constructorBinder.name)) +: placeholders,
      MetaCompanionLayout.typeReference(parameter.name.map(MetaCompanionLayout.metaBinderName)),
      Some(parameter.name.as(SourceExpression.FunctionApplication(None, constructor, None, parameter.meta))),
      visibility = f.visibility
    )
  }
}
