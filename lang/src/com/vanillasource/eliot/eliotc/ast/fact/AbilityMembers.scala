package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Lowers an `ability` or an `effect` block into ordinary [[FunctionDefinition]]s — one per member, plus the block's
  * synthetic **marker**. The two declarations lower identically (`docs/effects.md` §9.3: "a member's row lists what it
  * performs beyond the ability it belongs to — for `effect` and `ability` alike"); they differ in exactly one thing,
  * `performsItself`, and that difference is metadata, not shape.
  *
  * An `effect` is an ability over a **handle type** (`docs/effects.md` D36 point 1): the block mints, **ahead of** its
  * own generic parameters, one ordinary binder of kind `Type` — the handle's type — and every member takes the handle
  * as its first value parameter ([[Handles.parameter]]). So `effect Console` declares `Console[Impl]` and `effect
  * Throw[E]` declares `Throw[Impl, E]`, and an operation is dispatched by its handle's type like any ability method. An
  * `ability` mints nothing: `ability Show[T]` declares `Show[T]`.
  *
  * Every ability-level binder is flagged [[GenericParameter.abilityLevel]], so
  * [[com.vanillasource.eliot.eliotc.core.processor.EffectSugarDesugarer]] knows to mint a member's *own* binders after
  * the run rather than in front of it: the run is the member reference's ability-level prefix, which
  * [[com.vanillasource.eliot.eliotc.monomorphize.check.AbilityResolver]] slices off.
  */
object AbilityMembers {

  /** @param performsItself
    *   True for an `effect`: membership in the block says the member performs the effect, so the row is recorded on
    *   every member — the one place effect-ness is written down, and what the scope check and the write then read. An
    *   `ability`'s members declare nothing of the sort, which is what makes a constructor class expressible (§3.6).
    *   An `effect` is an ability over a handle type (`docs/effects.md` D36): it mints that type as its first binder,
    *   and every member takes the handle as its first value parameter ([[Handles.parameter]]).
    */
  def lower(
      name: Sourced[String],
      commonGenericParameters: Seq[GenericParameter],
      functions: Seq[FunctionDefinition],
      performsItself: Boolean
  ): Seq[FunctionDefinition] = {
    val qualifier     = Qualifier.Ability(name.value)
    val handleBinder  = Option.when(performsItself)(
      GenericParameter(
        name.as(freshName("Impl", commonGenericParameters.map(_.name.value).toSet)),
        name.as(typeExpr(name.as("Type"))),
        Seq.empty
      )
    )
    val abilityParams = (handleBinder.toSeq ++ commonGenericParameters).map(_.copy(abilityLevel = true))
    def ownRow(member: FunctionDefinition) =
      if (performsItself)
        EffectRow(
          returnEffects =
            Seq(UnresolvedAbilityConstraint(name, commonGenericParameters.map(gp => gp.name.as(typeExpr(gp.name))))),
          returnArity = member.args.size,
          handles = handleBinder
            .filter(_ => member.name.value.qualifier != Qualifier.Type)
            .map(binder => EffectRow.Handle(binder.name.value, 0))
            .toSeq
        )
      else EffectRow.empty[UnresolvedAbilityConstraint[Sourced[Expression]]]

    def handleOf(member: FunctionDefinition): Seq[ArgumentDefinition] =
      handleBinder
        .filter(_ => member.name.value.qualifier != Qualifier.Type)
        .map(binder => ArgumentDefinition(binder.name.map(Handles.parameter), binder.name.as(typeExpr(binder.name))))
        .toSeq

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
        abilityParams.headOption.fold(name.as(typeExpr(name.as("Type"))))(gp => gp.name.as(typeExpr(gp.name))),
        None
      )
  }

  private def typeExpr(name: Sourced[String]): Expression =
    Expression.FunctionApplication(None, name, None, Seq.empty)

  private def freshName(base: String, existingNames: Set[String]): String =
    if (!existingNames.contains(base)) base
    else Iterator.from(0).map(i => s"$base$i").find(!existingNames.contains(_)).get
}
