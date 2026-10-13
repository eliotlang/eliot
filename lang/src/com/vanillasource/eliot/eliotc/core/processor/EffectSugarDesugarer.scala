package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{EffectRow, FunctionDefinition, GenericParameter, UnresolvedAbilityConstraint}
import com.vanillasource.eliot.eliotc.ast.fact.Expression
import com.vanillasource.eliot.eliotc.ast.fact.Expression.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars an effect row, `{ E1, E2, … } A` ([[Expression.EffectfulType]]) — effects v6, `docs/effects.md` §9.4
  * step 2. The user writes it as a `uses` clause, which the parser writes onto this node (D21), and as the body of a
  * row alias; no other spelling parses.
  *
  * There is no carrier. A row entry and a `~` ability constraint each become **one phantom generic binder**: a binder
  * of kind `Type` that occurs in the generic list and in no parameter or return type, so rows still never flow into
  * types (§3.3). Its *value*, written at every reference by the `row` phase (§9.4 step 3), is an implementation —
  * either one a `with` names, or the `Default` sentinel asking for the two-site search.
  *
  * Three rewrites, and nothing else:
  *
  *   - **the return row vanishes.** `def greeting(name: String): {Console} Unit` becomes `def greeting[Impl](name:
  *     String): Unit`, with `Impl` carrying the constraint `Console[Impl]` — exactly the shape the carrier binder had,
  *     at kind `Type` instead of `Type -> Type`.
  *   - **a `~` constraint grows a binder.** `def sort[T ~ Ord[T]](xs: List[T])` becomes `def sort[Impl, T ~ Ord[Impl,
  *     T]](xs: List[T])`. The constraint stays on the binder it was written on — that is what
  *     `resolveParamConstraints` and the superability closure read — and only gains the binding as its **first** type
  *     argument, which is where the ability's marker declares it
  *     ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]) and where
  *     [[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding]] reads it back. Constraint and
  *     marker must agree on the position: a reference's ability-level arguments are matched against the marker's
  *     parameters, so appending here while the marker prepends made every dispatch query the wrong shape.
  *   - **a top-level row in a parameter thunks.** `computation: {Throw[E]} A` becomes `computation: Unit => A`: a slot
  *     that must not run its argument says so by being a function, since there is no carrier left to hold an unrun
  *     computation. Only a *top-level* row thunks; a row in an arrow codomain (`onError: E => {} A`) is the callback's
  *     own row and lowers to the bare payload, exactly as a return row does. A `data` field takes no row at all — it
  *     holds a value, and its parser admits neither a `uses` clause nor a row.
  *
  * **Every minted binder carries the mark** as its declared type — `Impl: Implementation[Console]`,
  * [[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter.implementationMark]] — which is the one place the fact
  * "this binder is a binding" is written down, and what [[com.vanillasource.eliot.eliotc.row.BindingWriter]] reads.
  * Minted binders are still *placed* together, but nothing requires them to be a prefix any more: the write merges
  * them by index.
  * [[mintAt]] places a member's own binders past the whole ability-level run, because the *ability's* binding must
  * stay the first of the ability-level type arguments ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]).
  *
  * **Idempotent**, because [[CoreProcessor]] applies it uniformly to every definition including the ability members
  * [[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]] has already lowered: a definition with no remaining rows
  * and no unprocessed constraint is returned unchanged, and a constraint is "processed" once its first type argument
  * refers to one of this definition's **marked** binders — a shape only this desugar writes.
  */
object EffectSugarDesugarer {

  /** The binder name minted for a row entry or a `~` constraint. Nothing reads it: it is written positionally. */
  private val binderPrefix = "Impl"

  /** Rewrite one function definition — see the object comment. */
  def desugar(function: FunctionDefinition): FunctionDefinition =
    if (signatureAndBodyRows(function).isEmpty && unprocessedConstraints(function).isEmpty) function
    else {
      val anchor                       = function.name
      val names                        = NameSource(function.genericParameters.map(_.name.value).toSet)
      // One binder per distinct entry of the declared (return) row, in declared order, each carrying its own
      // constraint with itself appended — `{Console, Log} Unit` mints `Impl ~ Console[Impl]` and `Impl0 ~ Log[Impl0]`.
      // An `implement` clause's entry it only carries for a sibling gets its binder and no constraint
      // ([[FunctionDefinition.carriedEffects]]).
      val rowBinders                   = returnRowEntries(function).map { entry =>
        val binder = anchor.as(names.fresh(binderPrefix))
        GenericParameter(
          binder,
          GenericParameter.implementationMark(anchor, entry.abilityName),
          if (function.carriedEffects.contains(UnresolvedAbilityConstraint.key(entry))) Seq.empty
          else Seq(UnresolvedAbilityConstraint(entry.abilityName, binder.as(typeExpr(binder)) +: entry.typeArgs.map(bare)))
        )
      }
      // One unconstrained binder per `~` constraint, appended to that constraint in place. The constraint keeps the
      // binder it was written on as its subject, so nothing about constraint scoping changes.
      val (constraintBinders, adapted) = function.genericParameters.foldLeft(
        (Seq.empty[GenericParameter], Seq.empty[GenericParameter])
      ) { case ((minted, done), gp) =>
        val (gpMinted, constraints) = gp.abilityConstraints.foldLeft(
          (Seq.empty[GenericParameter], Seq.empty[UnresolvedAbilityConstraint[Sourced[Expression]]])
        ) { case ((mintedHere, kept), constraint) =>
          if (isProcessed(constraint, function)) (mintedHere, kept :+ constraint)
          else {
            val binder = anchor.as(names.fresh(binderPrefix))
            (
              mintedHere :+ GenericParameter(
                binder,
                GenericParameter.implementationMark(anchor, constraint.abilityName),
                Seq.empty
              ),
              kept :+ constraint.copy(typeArgs = binder.as(typeExpr(binder)) +: constraint.typeArgs.map(bare))
            )
          }
        }
        (minted ++ gpMinted, done :+ gp.copy(typeRestriction = bare(gp.typeRestriction), abilityConstraints = constraints))
      }
      val (before, after)              = adapted.splitAt(mintAt(function))
      val declared                     = declaredEffectRow(function)

      function.copy(
        genericParameters = before ++ rowBinders ++ constraintBinders ++ after,
        args = function.args.map(arg => arg.copy(typeExpression = parameterType(function)(arg.typeExpression))),
        typeDefinition = bare(function.typeDefinition),
        body = function.body.map(bare),
        // An ability member arrives with the row its membership implies already recorded
        // ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]); its own row is added to that, never over it.
        effectRow = declared.copy(returnEffects = function.effectRow.returnEffects ++ declared.returnEffects)
      )
    }

  /** Where the minted binders go in the generic list: `0` for an ordinary definition, and for an **ability member**
    * past the whole run of [[GenericParameter.abilityLevel]] binders
    * [[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]] contributes — so a member's own binding binders never
    * land inside the ability-level prefix, whose first argument must stay the ability's own binding.
    */
  private def mintAt(function: FunctionDefinition): Int =
    function.genericParameters.lastIndexWhere(_.abilityLevel) + 1

  /** The constraints of this definition that have not yet had a binder minted for them. */
  private def unprocessedConstraints(
      function: FunctionDefinition
  ): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    function.genericParameters.flatMap(_.abilityConstraints).filterNot(isProcessed(_, function))

  /** Whether a constraint already carries its phantom binder: its **first** type argument is a bare reference to one of
    * this definition's binders that carries the **implementation mark**
    * ([[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter.isBinding]]), which is the one shape [[desugar]]
    * writes and no source can.
    */
  private def isProcessed(
      constraint: UnresolvedAbilityConstraint[Sourced[Expression]],
      function: FunctionDefinition
  ): Boolean = constraint.typeArgs.headOption.exists(_.value match {
    case FunctionApplication(None, name, None, Seq()) =>
      function.genericParameters.exists(gp => gp.name.value === name.value && GenericParameter.isBinding(gp))
    case _                                            => false
  })

  /** Every effect row anywhere in a definition's signature or body — what decides whether it carries the sugar. */
  private def signatureAndBodyRows(function: FunctionDefinition): Seq[Sourced[EffectfulType]] = {
    val signatureExprs =
      function.args.map(_.typeExpression) ++
        function.genericParameters.map(_.typeRestriction) :+
        function.typeDefinition
    (signatureExprs ++ function.body.toSeq).flatMap(collectRows)
  }

  /** The effects-as-channel **declared row**: the return position's entries, and one record per value parameter that
    * *is* a row position — including the empty row `{}`, which under v6 carries no entries but still marks the slot as
    * a computation whose calls the caller's bindings cover (§9.4's resolution order reads exactly this).
    */
  private def declaredEffectRow(
      function: FunctionDefinition
  ): EffectRow[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    EffectRow(
      (returnRowEntries(function) ++ namedRowEntries(function)).distinctBy(constraintKey),
      // A meta companion's parameters are not thunked ([[parameterType]]), so they must not be recorded as row
      // positions either: the row record is what tells the `row` phase to wrap an actual and to run a reference, and
      // doing that against a bare parameter is exactly the mismatch it would cause.
      if (isMetaCompanion(function)) Seq.empty
      else
        function.args.zipWithIndex.collect {
          case (arg, index) if isRow(arg.typeExpression) =>
            EffectRow.ParameterEffects(index, topLevelRowEntries(arg.typeExpression), arg.closedRow)
        },
      callbackEffects =
        if (isMetaCompanion(function)) Seq.empty
        else
          function.args.zipWithIndex.flatMap { case (arg, index) =>
            codomainRow(arg.typeExpression).map { case (arity, entries) =>
              EffectRow.CallbackEffects(index, arity, entries, arg.closedRow)
            }
          }
    )

  /** The row in a function type's **final codomain** — `A => {} B`, `A => B => {Log} C` — with the number of arrows
    * before it, when the type is an arrow chain ending in one. That row marks the parameter as code the callee calls,
    * not a function value it may keep ([[com.vanillasource.eliot.eliotc.ast.fact.EffectRow.callbackEffects]]). The
    * `=>` chain is still a flat run at this phase, so its codomain is the run's last part, and a parenthesised last part
    * (`A => (B => {} C)`) is a run of its own.
    */
  private def codomainRow(
      expr: Sourced[Expression]
  ): Option[(Int, Seq[UnresolvedAbilityConstraint[Sourced[Expression]]])] =
    expr.value match {
      case WithBinding(subject, _)                 => codomainRow(subject)
      case FlatExpression(parts) if parts.size > 1 =>
        val arrows = parts.count(isArrow)
        parts.last.value match {
          case EffectfulType(effects, _) => Option.when(arrows > 0)(arrows -> effects)
          case _                               => codomainRow(parts.last).map { case (n, e) => (arrows + n, e) }
        }
      case _                                       => None
    }

  private def isArrow(part: Sourced[Expression]): Boolean = part.value match {
    case FunctionApplication(None, name, None, Seq()) => name.value === "=>"
    case _                                            => false
  }

  /** The distinct entries a definition writes out in its **return position** — the ones it mints a binding binder for.
    * A row is declaration metadata, so the return type itself lowers to its payload.
    */
  private def returnRowEntries(
      function: FunctionDefinition
  ): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    topLevelRowEntries(function.typeDefinition).distinctBy(constraintKey)

  /** The entries a **row alias** names (`type Git[A] = {Process, FileSystem} A`) — the row in the body of a definition
    * that returns a `Type`.
    *
    * They are **declared and not minted**, which is the whole of what makes the alias an ordinary name. An alias does
    * not *perform* the row: it names one, for a definition elsewhere to receive by naming the alias in its own return
    * type. So the entries are recorded on this definition exactly as a written-out row records its own — where
    * [[com.vanillasource.eliot.eliotc.resolve.processor.ValueResolver]] reads them back at a use, through the ordinary
    * dictionary — and no binder is minted here.
    *
    * Minting one *was* the plan, and it is measured not to work (`docs/effects.md` §9.3 step 5): an alias's parameters
    * are its **value** args, so a mark minted among them lands on an arrow domain, which
    * [[com.vanillasource.eliot.eliotc.row.BindingWriter]] does not erase — it erases a *binder's* declared type — and
    * the checker then meets an ability of kind `Type -> Type` inside `Implementation[…]`.
    */
  private def namedRowEntries(
      function: FunctionDefinition
  ): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    if (isTypeLevel(function)) function.body.toSeq.flatMap(topLevelRowEntries) else Seq.empty

  /** Whether this definition's declared return type is the bare `Type` — it computes a type, so its body is a type
    * expression and a row there names a row rather than performing one.
    */
  private def isTypeLevel(function: FunctionDefinition): Boolean =
    function.typeDefinition.value match {
      case FunctionApplication(None, name, genericArguments, Seq()) if genericArguments.forall(_.isEmpty) =>
        name.value === "Type"
      case _                                                                                              => false
    }

  /** A slot's `with` chain wraps its row (`program: {Console} Unit with recordingConsole`), so both readers look
    * *through* it — exactly as [[thunked]] does. Reading the row off the outside instead would leave the slot
    * unrecorded, and the `row` phase reads that record to decide a slot is a computation at all: the actual would then
    * be written in the caller's scope, and the very effects the `with` binds would be reported undeclared there.
    */
  private def topLevelRowEntries(expr: Sourced[Expression]): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    expr.value match {
      case WithBinding(subject, _)         => topLevelRowEntries(subject)
      case EffectfulType(effects, _) => effects
      case _                         => Seq.empty
    }

  private def isRow(expr: Sourced[Expression]): Boolean = expr.value match {
    case WithBinding(subject, _) => isRow(subject)
    case _: EffectfulType        => true
    case _                       => false
  }

  /** How a definition's parameter types are rewritten: [[thunked]] ordinarily, and [[bare]] for a **meta companion**.
    *
    * A `^Meta` transfer brace or `^Where` predicate is compiler-track code about a value's *payload* — `def fold[A](…,
    * whenTrue: {} A, whenFalse: {} A): A { join(whenTrue, whenFalse) }` joins the two arms' refinement metas, not two
    * thunks. Thunking there would hand `join` a `Unit -> A` and the brace would stop type-checking; the row says
    * nothing about a refinement, so erasing it is what the companion means.
    */
  private def parameterType(function: FunctionDefinition): Sourced[Expression] => Sourced[Expression] =
    if (isMetaCompanion(function)) bare else thunked

  private def isMetaCompanion(function: FunctionDefinition): Boolean =
    function.name.value.qualifier match {
      case _: Qualifier.Meta => true
      case _                 => false
    }

  /** A parameter or field position: a top-level row becomes the thunk `Unit => A`; everything else is [[bare]].
    *
    * A slot's `with` chain (`body: {Console} Unit with mockConsole`, §9.3) is **kept wrapped around the thunk**: it is
    * a declaration about the slot, and the `row` phase both reads it — to bind those implementations for the actual
    * delivered there — and strips it from the signature. Keeping it in the type rather than in [[EffectRow]] is what
    * lets it be a resolved `ValueFQN` by the time the write needs one, on the same fact chain every other reference
    * rides.
    */
  private def thunked(expr: Sourced[Expression]): Sourced[Expression] = expr.value match {
    case WithBinding(subject, implementation) =>
      expr.as(WithBinding(thunked(subject), implementation))
    case EffectfulType(_, resultType)         =>
      expr.as(
        FunctionApplication(
          Some(expr.as(WellKnownTypes.functionDataTypeFQN.moduleName.show)),
          expr.as(WellKnownTypes.functionDataTypeFQN.name.name),
          Some(Seq(unitReference(expr), bare(resultType))),
          Seq.empty
        )
      )
    case _                                    => bare(expr)
  }

  /** Erase every effect row from an expression, keeping its payload: a row is declaration metadata and never a type. */
  private def bare(expr: Sourced[Expression]): Sourced[Expression] = expr.value match {
    case EffectfulType(_, resultType)                             => bare(resultType)
    case WithBinding(subject, implementation)                     =>
      expr.as(WithBinding(bare(subject), implementation))
    case FunctionApplication(moduleName, name, genericArgs, args) =>
      expr.as(FunctionApplication(moduleName, name, genericArgs.map(_.map(bare)), args.map(bare)))
    case FunctionLiteral(parameters, body)                        =>
      expr.as(FunctionLiteral(parameters.map(p => p.copy(typeExpression = p.typeExpression.map(thunked))), bare(body)))
    case FlatExpression(parts)                                    => expr.as(FlatExpression(parts.map(bare)))
    case MatchExpression(scrutinee, cases)                        =>
      expr.as(MatchExpression(bare(scrutinee), cases.map(c => c.copy(body = bare(c.body)))))
    case BlockExpression(lines)                                   =>
      expr.as(BlockExpression(lines.map { line =>
        line.copy(
          binder = line.binder.map(b => b.copy(typeExpression = b.typeExpression.map(bare))),
          expression = bare(line.expression)
        )
      }))
    case _: IntegerLiteral | _: StringLiteral                     => expr
  }

  private def unitReference(at: Sourced[?]): Sourced[Expression] =
    at.as(
      FunctionApplication(
        Some(at.as(WellKnownTypes.unitTypeFQN.moduleName.show)),
        at.as(WellKnownTypes.unitTypeFQN.name.name),
        None,
        Seq.empty
      )
    )

  /** Collects, in source order, every effect-row node within the expression, with its source position. */
  private def collectRows(expr: Sourced[Expression]): Seq[Sourced[EffectfulType]] = expr.value match {
    case et @ EffectfulType(effects, resultType)       =>
      (expr.as(et) +: effects.flatMap(_.typeArgs.flatMap(collectRows))) ++ collectRows(resultType)
    case WithBinding(subject, _)                       => collectRows(subject)
    case FunctionApplication(_, _, genericArgs, args)  =>
      genericArgs.getOrElse(Seq.empty).flatMap(collectRows) ++ args.flatMap(collectRows)
    case FunctionLiteral(parameters, body)             =>
      parameters.flatMap(_.typeExpression.toSeq.flatMap(collectRows)) ++ collectRows(body)
    case FlatExpression(parts)                         => parts.flatMap(collectRows)
    case MatchExpression(scrutinee, cases)             =>
      collectRows(scrutinee) ++ cases.flatMap(c => collectRows(c.body))
    case BlockExpression(lines)                        =>
      lines.flatMap(l => l.binder.flatMap(_.typeExpression).toSeq.flatMap(collectRows) ++ collectRows(l.expression))
    case _: IntegerLiteral | _: StringLiteral          => Seq.empty
  }

  private def constraintKey(ac: UnresolvedAbilityConstraint[Sourced[Expression]]): String =
    UnresolvedAbilityConstraint.key(ac)

  private def typeExpr(name: Sourced[String], genericArgs: Seq[Sourced[Expression]] = Seq.empty): Expression =
    FunctionApplication(None, name, Option.when(genericArgs.nonEmpty)(genericArgs), Seq.empty)

  /** Hands out binder names colliding with nothing already taken, and with nothing it has handed out before. */
  private class NameSource(private var taken: Set[String]) {
    def fresh(base: String): String = {
      val name = if (!taken.contains(base)) base else Iterator.from(0).map(i => s"$base$i").find(!taken.contains(_)).get
      taken = taken + name
      name
    }
  }
}
