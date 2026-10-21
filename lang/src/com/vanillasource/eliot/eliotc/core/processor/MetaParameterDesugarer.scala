package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{ArgumentDefinition, FunctionDefinition, Expression as SourceExpression}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a parameter's **meta brace** into its **statement companion** (`docs/meta.md` §4.4) — the
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

  /** What the `match` eliminator `definition` hands argument `argument` of its handler `parameter`
    * (`docs/meta.md` §4.5, §11.1) — a supplier the compiler cannot see, so stated, by the data desugarer only. The
    * handler runs for `constructor`, so it is handed that field of the eliminator's `scrutinee` when `constructor` built
    * it, and nothing when another one did: the handler does not run. A braced field adds what is handed where it is not
    * known what built the value, its brace: the constructor proved it.
    *
    * {{{
    *   data Pixel(r: Int {byte}, g: Int)
    *     ⟶  handleCases$Handed$case0$0[R, Meta$value, Meta$case0, Meta$, Constructor$](
    *           constructor$: Constructor$, value: Meta$value, case0: Meta$case0): Meta$ =
    *              fieldOr(value, Pixel, constructor$(byte))
    *         handleCases$Handed$case0$1[R, Meta$value, Meta$case0, Meta$](value: Meta$value, case0: Meta$case0): Meta$ =
    *              fieldOf(value, Pixel)
    * }}}
    */
  def fieldStatement(
      definition: FunctionDefinition,
      scrutinee: Sourced[String],
      parameter: Sourced[String],
      argument: Int,
      constructor: Sourced[SourceExpression],
      brace: Seq[Sourced[SourceExpression]]
  ): FunctionDefinition = {
    val (metaBinders, parameters) = MetaCompanionLayout.metaParameters(definition)
    val handedBinder              = MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.resultBinderName))
    val value                     = scrutinee.as(SourceExpression.FunctionApplication(None, scrutinee, None, Seq.empty))
    val name                      = definition.name.map(handedName(_, parameter.value, argument))
    val own                       = MetaCompanionLayout.ownBinders(definition)
    val result                    = MetaCompanionLayout.typeReference(handedBinder.name)
    if (brace.isEmpty)
      FunctionDefinition(
        name,
        own ++ metaBinders :+ handedBinder,
        parameters,
        result,
        Some(parameter.as(metaPrimitive(parameter, "fieldOf", Seq(value, constructor)))),
        visibility = definition.visibility
      )
    else {
      val constructorParameter = parameter.as(MetaCompanionLayout.constructorParameterName)
      val constructorBinder    =
        MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.constructorBinderName))
      val stated               =
        parameter.as(SourceExpression.FunctionApplication(None, constructorParameter, None, brace))
      FunctionDefinition(
        name,
        own ++ metaBinders :+ handedBinder :+ constructorBinder,
        ArgumentDefinition(constructorParameter, MetaCompanionLayout.typeReference(constructorBinder.name)) +: parameters,
        result,
        Some(parameter.as(metaPrimitive(parameter, "fieldOr", Seq(value, constructor, stated)))),
        visibility = definition.visibility
      )
    }
  }

  /** A primitive of `eliot.compiler.Meta` applied to `arguments`, named by its module so no import is needed. */
  private def metaPrimitive(
      at: Sourced[?],
      name: String,
      arguments: Seq[Sourced[SourceExpression]]
  ): SourceExpression =
    SourceExpression.FunctionApplication(
      Some(at.as(WellKnownTypes.metaPrimitiveFQN(name).moduleName.show)),
      at.as(name),
      None,
      arguments
    )

  /** The handed statement an **arrow-domain brace** writes (`docs/meta.md` §14): what a body-less `definition` hands its
    * code parameter as one argument, stated by the definition itself rather than by the data desugarer. It has a handed
    * statement's name and binders, but its brace may read the definition's parameters, each as its meta — a code
    * parameter as what its code yields — and a brace on a domain the definition's own type parameter names is the meta
    * itself, as a return brace on such a result is ([[MetaTransferDesugarer]]):
    *
    * {{{
    *   private def escapeInternal[K, A, R](body uses *: Frame[K] => A, onExit uses *: K => R, onValue uses *: A {body} => R) uses *: R
    *     ⟶  escapeInternal$Handed$onValue$0[K, A, R, Meta$body, Meta$onExit, Meta$onValue, Meta$](
    *           body: Meta$body, onExit: Meta$onExit, onValue: Meta$onValue): Meta$ = body
    * }}}
    */
  def arrowStatement(
      definition: FunctionDefinition,
      parameter: Sourced[String],
      handed: ArgumentDefinition.Handed
  ): FunctionDefinition = {
    val (metaBinders, parameters) = MetaCompanionLayout.metaParameters(definition)
    val handedBinder              = MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.resultBinderName))
    val name                      = definition.name.map(handedName(_, parameter.value, handed.argument))
    val own                       = MetaCompanionLayout.ownBinders(definition)
    val result                    = MetaCompanionLayout.typeReference(handedBinder.name)
    handed.domain.value match {
      case SourceExpression.FunctionApplication(None, typeName, _, Seq())
          if handed.brace.size == 1 && definition.genericParameters.exists(_.name.value == typeName.value) =>
        FunctionDefinition(
          name,
          own ++ metaBinders :+ handedBinder,
          parameters,
          result,
          Some(handed.brace.head),
          visibility = definition.visibility
        )
      case _ =>
        val constructor       = parameter.as(MetaCompanionLayout.constructorParameterName)
        val constructorBinder =
          MetaCompanionLayout.typeBinder(parameter.as(MetaCompanionLayout.constructorBinderName))
        FunctionDefinition(
          name,
          own ++ metaBinders :+ handedBinder :+ constructorBinder,
          ArgumentDefinition(constructor, MetaCompanionLayout.typeReference(constructorBinder.name)) +: parameters,
          result,
          Some(parameter.as(SourceExpression.FunctionApplication(None, constructor, None, handed.brace))),
          visibility = definition.visibility
        )
    }
  }

  /** One statement companion per parameter of `definition` carrying a meta brace, and one handed statement per arrow-domain
    * brace of its code parameters, or empty (every ordinary def).
    */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.args.filter(_.meta.nonEmpty).map(statementCompanion(definition, _) -> RoleHint.NoHint) ++
      definition.args.flatMap(arg => arg.handed.map(arrowStatement(definition, arg.name, _) -> RoleHint.NoHint))

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
