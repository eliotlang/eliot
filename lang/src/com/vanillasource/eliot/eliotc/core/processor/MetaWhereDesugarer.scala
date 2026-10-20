package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{FunctionDefinition, Expression as SourceExpression}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a def's `where <predicate>` refinement precondition into its **`^Where` companion** (bounds-as-refinements
  * §4.3) — the `Bool` function the refinement channel evaluates at each call site to demand the precondition.
  *
  * {{{
  *   def useByte(x: Byte) where withinByte(range(x)): Int
  *     ⟶  useByte$Where[Meta$x: Type](x: Meta$x): Bool = withinByte(range(x))
  * }}}
  *
  * Inside the predicate a parameter denotes its **meta** — `range(x)` projects the range an `Int`'s meta carries. The
  * companion takes the definition's binders and one binder per parameter, which the channel instantiates at each call
  * with the meta type of the parameter's evaluated type; that shape, shared with the transfer companion, is
  * [[MetaCompanionLayout]]'s. The body is the predicate itself, unwrapped (a `Bool`), and the declared return type is
  * `Bool` written module-qualified so it resolves without the def importing `Bool` (mirroring
  * [[SourceExpression.trueReference]]). Companions are compiler-pool-only (the channel evaluates them), dead in the
  * runtime pool, never code-generated.
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

  private def whereCompanion(f: FunctionDefinition, predicate: Sourced[SourceExpression]): FunctionDefinition = {
    val (metaBinders, metaParameters) = MetaCompanionLayout.metaParameters(f)
    FunctionDefinition(
      f.name.map(companionName),
      MetaCompanionLayout.ownBinders(f) ++ metaBinders,
      metaParameters,
      boolTypeRef(f.name),
      Some(predicate),
      visibility = f.visibility
    )
  }

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
