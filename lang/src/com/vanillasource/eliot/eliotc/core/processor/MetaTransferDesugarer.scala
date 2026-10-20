package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{ArgumentDefinition, FunctionDefinition, Expression as SourceExpression}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a def's return-position transfer brace into its `^Meta` **transfer companion** (bounds-as-refinements §4.2,
  * Step 4b) — the function the refinement channel evaluates to propagate meta through a call.
  *
  * {{{
  *   def add(a: Int, b: Int): Int {a.range + b.range}
  *     ⟶  add^Meta[Meta$a, Meta$b, Meta$, Constructor$](constructor$: Constructor$, a: Meta$a, b: Meta$b): Meta$ =
  *            constructor$(a.range + b.range)
  * }}}
  *
  * The companion lives in the [[Qualifier.Meta]] namespace of the def's own qualifier ([[companionName]]), so it never
  * collides with `add` — nor, when the def is an ability-implementation method, with the transfer of any other `add` in
  * the same module. It takes the def's binders, one binder per parameter and one for the result, which the channel
  * instantiates at each call with the callee's type arguments and the meta types of its evaluated parameter and result
  * types ([[MetaCompanionLayout]]) — so a generic def's type parameters are bound to what they are, and
  * `integerLiteral`'s `V` is its literal.
  *
  * When the result type is concrete the brace lists **slots** (slot-producing), and the body applies the meta *value*
  * constructor to them. That constructor is not named here: naming it by the result type's spelling (`Byte` ⤳
  * `Byte$Meta`) fails for an alias or a computed type, exactly as naming a meta *type* did. It is the companion's first
  * parameter instead ([[MetaCompanionLayout.constructorParameterName]]), typed by a binder of its own, and the channel
  * passes the constructor of the result's evaluated meta type. When the result type is a bare type parameter of the def
  * there is no constructor to apply, so the single brace expression *is* the result meta structure (structure-producing
  * — `fold`'s `join(whenTrue, whenFalse)`, whose `Meta.join` already returns the result meta type). Companions are
  * compiler-pool-only (the channel evaluates them), dead in the runtime pool.
  */
object MetaTransferDesugarer {

  /** The name of the `^Meta` transfer companion of the value named `of` — its own name, in the meta namespace *of its
    * own qualifier* ([[Qualifier.Meta]]). The nesting is what keeps an ability-implementation leaf's transfer
    * (`Numeric[Int]::add` ⤳ `add^Meta(Numeric#Int…)`) apart from that of a plain `add` declared in the same module;
    * dropping the qualifier — as this companion's name once did — merged the two (`docs/meta.md` §4.2).
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

  private def transferCompanion(f: FunctionDefinition): Option[FunctionDefinition] =
    MetaCompanionLayout.payloadResult(f).value match {
      case SourceExpression.FunctionApplication(None, fnName, _, args)
          if args.isEmpty && f.genericParameters.exists(_.name.value === fnName.value) =>
        f.returnMeta match {
          case Seq(single) => Some(companion(f, None, single))
          case _           => None
        }
      case _                                                                          =>
        val constructor = f.name.as(MetaCompanionLayout.constructorParameterName)
        Some(companion(f, Some(constructor), f.name.as(SourceExpression.FunctionApplication(None, constructor, None, f.returnMeta))))
    }

  /** The companion with `body`, taking the meta constructor as its first parameter when `constructor` names one. */
  private def companion(
      f: FunctionDefinition,
      constructor: Option[Sourced[String]],
      body: Sourced[SourceExpression]
  ): FunctionDefinition = {
    val (metaBinders, metaParameters) = MetaCompanionLayout.metaParameters(f)
    val result                        = MetaCompanionLayout.typeBinder(f.name.as(MetaCompanionLayout.resultBinderName))
    val constructorBinder             =
      constructor.map(c => MetaCompanionLayout.typeBinder(c.as(MetaCompanionLayout.constructorBinderName)))
    FunctionDefinition(
      f.name.map(companionName),
      MetaCompanionLayout.ownBinders(f) ++ metaBinders ++ (result +: constructorBinder.toSeq),
      constructor.zip(constructorBinder).map((c, b) => ArgumentDefinition(c, MetaCompanionLayout.typeReference(b.name))).toSeq ++
        metaParameters,
      MetaCompanionLayout.typeReference(result.name),
      Some(body),
      visibility = f.visibility
    )
  }
}
