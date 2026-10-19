package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{FunctionDefinition, Expression as SourceExpression}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a def's return-position transfer brace into its `^Meta` **transfer companion** (bounds-as-refinements §4.2,
  * Step 4b) — the function the refinement channel evaluates to propagate meta through a call.
  *
  * {{{
  *   def add(a: Int, b: Int): Int {a.range + b.range}
  *     ⟶  add^Meta[Meta$a, Meta$b, Meta$](a: Meta$a, b: Meta$b): Meta$ = Int$Meta(a.range + b.range)
  * }}}
  *
  * The companion lives in the [[Qualifier.Meta]] namespace of the def's own qualifier ([[companionName]]), so it never
  * collides with `add` — nor, when the def is an ability-implementation method, with the transfer of any other `add` in
  * the same module. It takes the def's binders, one binder per parameter and one for the result, which the channel
  * instantiates at each call with the callee's type arguments and the meta types of its evaluated parameter and result
  * types ([[MetaCompanionLayout]]) — so a generic def's type parameters are bound to what they are, and
  * `integerLiteral`'s `V` is its literal.
  *
  * The body is the meta *value* constructor `T$Meta(...)` applied to the brace's slot expressions when the result type
  * is concrete (slot-producing), or the single brace expression directly when it is a bare type parameter of the def
  * (structure-producing — `fold`'s `join(whenTrue, whenFalse)`, whose `Meta.join` already returns the result meta
  * type). The constructor is still named from the result type's spelling, so a leaf whose result type is not a bare
  * slotted name fails to resolve it — loudly, at the leaf. Companions are compiler-pool-only (the channel evaluates
  * them), dead in the runtime pool.
  */
object MetaTransferDesugarer {

  /** The name of the `^Meta` transfer companion of the value named `of` — its own name, in the meta namespace *of its
    * own qualifier* ([[Qualifier.Meta]]). The nesting is what keeps an ability-implementation leaf's transfer
    * (`Numeric[Int]::add` ⤳ `add^Meta(Numeric#Int…)`) apart from that of a plain `add` declared in the same module;
    * dropping the qualifier — as this companion's name once did — merged the two (`docs/total-meta-transfers.md` §8.1).
    *
    * The single source of truth for the name: every reader that asks "does this value state a transfer?" or reduces one
    * ([[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementChannelProcessor]],
    * [[com.vanillasource.eliot.eliotc.monomorphize.channel.MetaTransferAccountingProcessor]]) names it through here, so
    * emitter and readers cannot drift apart.
    */
  def companionName(of: QualifiedName): QualifiedName = QualifiedName(of.name, Qualifier.Meta(of.qualifier))

  /** The `^Meta` transfer companion generated from `definition`'s return brace, or empty when it has none (every
    * ordinary def) or its result shape is unsupported.
    */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    if (definition.returnMeta.isEmpty) Seq.empty
    else transferCompanion(definition).map(_ -> RoleHint.NoHint).toSeq

  private def transferCompanion(f: FunctionDefinition): Option[FunctionDefinition] = {
    val (metaBinders, metaParameters) = MetaCompanionLayout.metaParameters(f)
    val result                        = MetaCompanionLayout.typeBinder(f.name.as(MetaCompanionLayout.resultBinderName))
    metaBody(MetaCompanionLayout.payloadResult(f), f.returnMeta, f.genericParameters.map(_.name.value).toSet).map(body =>
      FunctionDefinition(
        f.name.map(companionName),
        MetaCompanionLayout.ownBinders(f) ++ metaBinders :+ result,
        metaParameters,
        MetaCompanionLayout.typeReference(result.name),
        Some(body),
        visibility = f.visibility
      )
    )
  }

  /** The companion's body. When the result type is **concrete**, the meta value is the meta *constructor* applied to
    * the brace's slot expressions (`<ResultHead>$Meta(<slots>)`, slot-producing). When the result type is a bare **type
    * parameter** there is no concrete constructor to wrap in, so the (single) brace expression *is* the result meta
    * structure directly (structure-producing — e.g. `fold`'s `join(whenTrue, whenFalse)`, whose `Meta.join` already
    * returns the arm meta type).
    */
  private def metaBody(
      resultType: Sourced[SourceExpression],
      braceExprs: Seq[Sourced[SourceExpression]],
      generics: Set[String]
  ): Option[Sourced[SourceExpression]] =
    resultType.value match {
      case SourceExpression.FunctionApplication(None, fnName, _, args)
          if args.isEmpty && generics.contains(fnName.value) =>
        braceExprs match {
          case Seq(single) => Some(single)
          case _           => None
        }
      case SourceExpression.FunctionApplication(_, fnName, _, _) =>
        Some(
          resultType.as(
            SourceExpression.FunctionApplication(
              None,
              fnName.map(_ + MetaConstructorDesugarer.metaTypeSuffix),
              None,
              braceExprs
            )
          )
        )
      case _                                                     => None
    }
}
