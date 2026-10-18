package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Lowers an `ability` or an `effect` block into ordinary [[FunctionDefinition]]s — one per member, plus the block's
  * synthetic **marker**. The two declarations lower identically (`docs/effects.md` §9.3: "a member's row lists what it
  * performs beyond the ability it belongs to — for `effect` and `ability` alike"); they differ in exactly one thing,
  * `performsItself`, and that difference is metadata, not shape.
  *
  * Effects v6 adds one binder. The block mints, **ahead of** its own generic parameters, a **binding binder** of kind
  * `Type`: the implementation slot, and the *first ability-level type argument* of every member reference, which is the
  * contract [[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding]] reads back and
  * [[com.vanillasource.eliot.eliotc.monomorphize.check.AbilityResolver]] slices off. So `effect Console` declares
  * `Console[Impl]` and `effect Throw[E]` declares `Throw[Impl, E]`.
  *
  * It goes first because a type-argument list applies positionally: an ordinary ability call (`show(x)`, `a ++ b`,
  * `sort(xs)`) has its pattern arguments *inferred*, and no declaration determines them, so a binding behind them
  * could not be written unless the call spelled those arguments itself. First, the write is one element long and
  * everything after it is inferred exactly as before. See `ImplementationBinding` for why this reverses §10.1 step 6.
  *
  * The binder is declared with the [[GenericParameter.implementationMark]], which is what the `row` phase reads to
  * know it is a binding at all. It is also flagged [[GenericParameter.abilityLevel]], as are the block's own
  * parameters, so [[com.vanillasource.eliot.eliotc.core.processor.EffectSugarDesugarer]] knows to mint a member's
  * *own* binding binders after it rather than in front of the ability's.
  *
  * The implementation side is unchanged: an `implement` marker still takes one argument per *pattern* element and no
  * binding, which is exactly what the two-site search matches against once the binding is sliced off.
  */
object AbilityMembers {

  /** @param performsItself
    *   True for an `effect`: membership in the block says the member performs the effect, so the row is recorded on
    *   every member — the one place effect-ness is written down, and what the scope check and the write then read. An
    *   `ability`'s members declare nothing of the sort, which is what makes a constructor class expressible (§3.6).
    * @param handles
    *   The new effect path (`docs/effects.md` D36): an `effect` is an ability over a handle type, its binding binder
    *   is that type, and every member takes the handle as its first value parameter ([[Handles.parameter]]).
    */
  def lower(
      name: Sourced[String],
      commonGenericParameters: Seq[GenericParameter],
      functions: Seq[FunctionDefinition],
      performsItself: Boolean,
      handles: Boolean = true
  ): Seq[FunctionDefinition] = {
    val qualifier     = Qualifier.Ability(name.value)
    val bindingBinder = GenericParameter(
      name.as(freshName("Impl", commonGenericParameters.map(_.name.value).toSet)),
      GenericParameter.implementationMark(name, name),
      Seq.empty,
      abilityLevel = true
    )
    val abilityParams = (bindingBinder +: commonGenericParameters).map(_.copy(abilityLevel = true))
    def ownRow(member: FunctionDefinition) =
      if (performsItself)
        EffectRow(
          returnEffects =
            Seq(UnresolvedAbilityConstraint(name, commonGenericParameters.map(gp => gp.name.as(typeExpr(gp.name))))),
          returnArity = member.args.size
        )
      else EffectRow.empty[UnresolvedAbilityConstraint[Sourced[Expression]]]

    def handleOf(member: FunctionDefinition): Seq[ArgumentDefinition] =
      if (handles && performsItself && member.name.value.qualifier != Qualifier.Type)
        Seq(ArgumentDefinition(bindingBinder.name.map(Handles.parameter), bindingBinder.name.as(typeExpr(bindingBinder.name))))
      else Seq.empty

    functions.map(f =>
      // `f.copy`, never a positional rebuild: every field this does not deliberately override — `doc`, `fixity`,
      // `precedence`, the refinement-channel companions — must forward untouched (the trap `ImplementBlock` records).
      f.copy(
        name = f.name.map(n => QualifiedName(n.name, qualifier)),
        genericParameters = abilityParams ++ f.genericParameters,
        args = handleOf(f) ++ f.args,
        visibility = Visibility.Public,
        effectRow = ownRow(f)
      )
    ) :+
      // The marker: the value named after the block, in the block's own namespace, whose leading generic binders *are*
      // the ability's type parameters — which is how `AbilityResolver.abilityArity` reads the length of the
      // ability-level slice. It takes one argument per binder so its signature encodes the pattern, and is body-less.
      FunctionDefinition(
        name.as(QualifiedName(name.value, qualifier)),
        abilityParams,
        abilityParams.zipWithIndex.map { case (gp, i) =>
          ArgumentDefinition(name.as(s"arg$i"), gp.name.as(typeExpr(gp.name)))
        },
        abilityParams.head.name.as(typeExpr(abilityParams.head.name)),
        None
      )
  }

  private def typeExpr(name: Sourced[String]): Expression =
    Expression.FunctionApplication(None, name, None, Seq.empty)

  private def freshName(base: String, existingNames: Set[String]): String =
    if (!existingNames.contains(base)) base
    else Iterator.from(0).map(i => s"$base$i").find(!existingNames.contains(_)).get
}
