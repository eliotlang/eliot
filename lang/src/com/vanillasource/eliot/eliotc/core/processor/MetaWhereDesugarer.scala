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

/** Desugars a def's `where <predicate>` refinement precondition into its **`^Where` companion** (bounds-as-refinements
  * §4.3) — the `Bool` function the refinement channel evaluates at each call site to demand the precondition.
  *
  * {{{
  *   def useByte(x: Byte): Int where withinByte(range(x))
  *     ⟶  useByte$Where[Meta$x: Type](x: Meta$x): Bool = withinByte(range(x))
  * }}}
  *
  * Inside the predicate a parameter denotes its **meta** — `range(x)` projects the range an `Int`'s meta carries — so
  * each parameter is retyped to the meta type of its type. That meta type is not *named* here: a type is an expression,
  * and naming its meta by its spelling (`Byte` ⤳ `Byte$Meta`) fails for an alias, a type computed by a type-level
  * function, or a type parameter, none of which declares one. Instead every parameter gets a binder of its own
  * ([[metaBinderName]]), and the channel supplies, at each call, the meta type of the parameter's **evaluated** type —
  * `T$Meta` for a slotted type, `Unit` for every other
  * ([[com.vanillasource.eliot.eliotc.monomorphize.channel.MetaInterpreter.metaTypeOf]]). The companion is then
  * monomorphized at those types by the one checker, which is where `range(x)` is checked.
  *
  * The companion is built from the definition **after** its effect sugar is desugared, so its leading binders and
  * parameters are exactly the ones the callee's monomorphization key and signature have — its handles included — and
  * the channel passes the callee's own type arguments through unchanged. Only the constraint a handle's binder carries is
  * dropped: a handle's type answers it by construction, and the two-site search, which would be asked, does not.
  *
  * The body is the predicate itself, unwrapped (a `Bool`), and the declared return type is `Bool` written
  * module-qualified so it resolves without the def importing `Bool` (mirroring [[SourceExpression.trueReference]]).
  * Companions are compiler-pool-only (the channel evaluates them), dead in the runtime pool, never code-generated.
  */
object MetaWhereDesugarer {

  /** Appended to the def's name (in the [[Qualifier.Meta]] namespace) to form its `^Where` companion's name, keeping it
    * distinct from the `^Meta` transfer companion (which is the bare name). `$` is not an identifier character, so the
    * result cannot collide with a user-declared name.
    */
  val whereSuffix: String = "$Where"

  /** The name of the `^Where` companion of the value named `of`: its name plus [[whereSuffix]], in the meta namespace
    * *of its own qualifier*, exactly as [[MetaTransferDesugarer.companionName]] names the transfer companion — so a
    * `where` on an ability-implementation method is that implementation's precondition and no other's. The single
    * source of truth every reader of a `where` companion names it through.
    */
  def companionName(of: QualifiedName): QualifiedName =
    QualifiedName(of.name + whereSuffix, Qualifier.Meta(of.qualifier))

  /** The `^Where` companion generated from `definition`'s `where` clause, or empty when it has none (every ordinary
    * def).
    */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.whereClause.toSeq.map(predicate => whereCompanion(definition, predicate) -> RoleHint.NoHint)

  /** The binder a `^Where` companion types parameter `parameter` by: `Meta$<parameter>`. `$` is not an identifier
    * character, so it collides with no binder the user wrote.
    */
  def metaBinderName(parameter: String): String = s"Meta$$$parameter"

  private def whereCompanion(f: FunctionDefinition, predicate: Sourced[SourceExpression]): FunctionDefinition = {
    val desugared   = EffectSugarDesugarer.desugar(f)
    val handles     = desugared.effectRow.handles.map(_.binder).toSet
    val ownBinders  =
      desugared.genericParameters.map(gp => if (handles.contains(gp.name.value)) gp.copy(abilityConstraints = Seq.empty) else gp)
    val metaBinders = desugared.args.map(arg =>
      GenericParameter(arg.name.map(metaBinderName), arg.name.as(typeReference(arg.name.as("Type"))), Seq.empty)
    )
    FunctionDefinition(
      f.name.map(companionName),
      ownBinders ++ metaBinders,
      desugared.args.map(arg => ArgumentDefinition(arg.name, arg.name.as(typeReference(arg.name.map(metaBinderName))))),
      boolTypeRef(f.name),
      Some(predicate),
      visibility = f.visibility
    )
  }

  /** A bare reference to the type named `name`. */
  private def typeReference(name: Sourced[String]): SourceExpression =
    SourceExpression.FunctionApplication(None, name, None, Seq.empty)

  /** A module-qualified reference to the `Bool` type (`eliot.lang.Bool::Bool`), so the companion's return type resolves
    * without the originating file importing `Bool`.
    */
  private def boolTypeRef(at: Sourced[?]): Sourced[SourceExpression] =
    at.as(
      SourceExpression.FunctionApplication(
        Some(at.as(WellKnownTypes.boolFQN.moduleName.show)),
        at.as(WellKnownTypes.boolFQN.name.name),
        None,
        Seq.empty
      )
    )
}
