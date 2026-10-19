package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  EffectRow,
  FunctionDefinition,
  GenericParameter,
  Handles,
  UnresolvedAbilityConstraint
}
import com.vanillasource.eliot.eliotc.ast.fact.Expression
import com.vanillasource.eliot.eliotc.ast.fact.Expression.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars an effect row, `{ E1, E2, … } A` ([[Expression.EffectfulType]]) — `docs/effects.md` §3.1. The user writes
  * it as a `uses` clause, which the parser writes onto this node (D21), and as the body of a row alias; no other
  * spelling parses.
  *
  * A row is declaration metadata and never a type (§3.6), so every rewrite here lowers it to plain parameters:
  *
  *   - **a return-row entry is a handle.** `def greet(name: String) uses Console: Unit` becomes `def greet[Impl ~
  *     Console[Impl]](Impl$handle: Impl, name: String): Unit` (D36 point 3): one binder of kind `Type` — the handle's
  *     type — carrying the entry as its constraint, and one value parameter of that type ([[Handles.parameter]]), after
  *     the handles the definition already takes (an effect member's own) and before its own parameters. The `k`-th
  *     handle parameter stands for the `k`-th entry of the declared row ([[EffectRow.returnEffects]]).
  *   - **a code parameter listing entries is a block.** `body uses *, Throw[E]: A` becomes `body: Handed => A`, one
  *     **handed** binder per entry ([[WellKnownTypes.handedTypeFQN]]), appended after every other binder: its type is
  *     what the callee's body hands the code (point 6).
  *   - **a code parameter listing nothing thunks.** `body uses *: A` becomes `body: Unit => A`. A row in an arrow
  *     codomain (`onError uses *: E => A`) is the callback's own row and lowers to the bare payload, as a return row
  *     does. A `data` field takes no row at all — it holds a value.
  *
  * A `~` constraint is an ability's and is left as it is written: it mints nothing, and the two-site search answers it.
  * A member's own binders are placed past the whole ability-level run ([[mintAt]]), so the run stays exactly the
  * ability's ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]).
  *
  * A definition with no row is returned unchanged.
  */
object EffectSugarDesugarer {

  /** The binder name minted for a row entry. Nothing reads it: it is written positionally. */
  private val binderPrefix = "Impl"

  /** Rewrite one function definition — see the object comment. Each row entry's binder is also the type of a **handle
    * parameter** the definition takes ([[Handles.parameter]], `docs/effects.md` D36), after the handles it already takes
    * (an effect member's own) and before its own parameters, so a call hands it the handle its caller has in scope for
    * the entry.
    */
  def desugar(function: FunctionDefinition): FunctionDefinition =
    if (signatureAndBodyRows(function).isEmpty) function
    else {
      val anchor                       = function.name
      val names                        = NameSource(function.genericParameters.map(_.name.value).toSet)
      // One binder per distinct entry of the declared (return) row, in declared order, each carrying its own
      // constraint with itself appended — `{Console, Log} Unit` mints `Impl ~ Console[Impl]` and `Impl0 ~ Log[Impl0]`.
      val rowBinders                   = returnRowEntries(function).map { entry =>
        // Sourced at the entry, so a diagnostic about the handle points at what the user wrote.
        val binder = entry.abilityName.as(names.fresh(binderPrefix))
        GenericParameter(
          binder,
          anchor.as(typeExpr(anchor.as("Type"))),
          Seq(UnresolvedAbilityConstraint(entry.abilityName, binder.as(typeExpr(binder)) +: entry.typeArgs.map(bare)))
        )
      }
      val adapted                      =
        function.genericParameters.map(gp => gp.copy(typeRestriction = bare(gp.typeRestriction)))
      val (before, after)              = adapted.splitAt(mintAt(function))
      val declared                     = declaredEffectRow(function)
      // A code parameter listing entries takes one handle per entry, each of a type the callee's body hands it: a
      // *handed* binder, appended after every other binder so a key without them is a prefix.
      val (handed, ownArgs)            = function.args.foldLeft((Seq.empty[GenericParameter], Seq.empty[ArgumentDefinition])) {
        case ((minted, done), arg) =>
          handedEntries(function, arg) match {
            case Seq()   => (minted, done :+ arg.copy(typeExpression = parameterType(function)(arg.typeExpression)))
            case entries =>
              val binders = entries.map(entry =>
                GenericParameter(entry.abilityName.as(names.fresh(handedPrefix)), handedMark(entry.abilityName), Seq.empty)
              )
              (minted ++ binders, done :+ arg.copy(typeExpression = handedType(binders, arg.typeExpression)))
          }
      }
      val (heldHandles, userArgs)      = ownArgs.span(arg => Handles.isParameter(arg.name.value))
      val rowHandles                   =
        rowBinders.map(binder => ArgumentDefinition(binder.name.map(Handles.parameter), binder.name.as(typeExpr(binder.name))))

      function.copy(
        genericParameters = before ++ rowBinders ++ after ++ handed,
        args = heldHandles ++ rowHandles ++ userArgs,
        typeDefinition = bare(function.typeDefinition),
        body = function.body.map(bare),
        // An ability member arrives with the row its membership implies already recorded
        // ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]); its own row is added to that, never over it.
        // Each row binder is the handle for its entry, which follows the membership's in the combined row.
        effectRow = declared.copy(
          returnEffects = function.effectRow.returnEffects ++ declared.returnEffects,
          handles = function.effectRow.handles ++ rowBinders.zipWithIndex.map((binder, k) =>
            EffectRow.Handle(binder.name.value, function.effectRow.returnEffects.size + k)
          )
        )
      )
    }

  /** The binder name minted for a code parameter's handle. */
  private val handedPrefix = "Handed"

  /** The entries a parameter's own clause lists, a handle for each: a top-level row with entries, on an ordinary
    * definition (a meta companion's parameters are payloads, never code).
    */
  private def handedEntries(
      function: FunctionDefinition,
      arg: ArgumentDefinition
  ): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    if (isMetaCompanion(function)) Seq.empty else topLevelRowEntries(arg.typeExpression)

  /** A code parameter's type: one handle per entry, then its result — `body uses *, Throw[E]: A`
    * is `Handed => A`.
    */
  private def handedType(binders: Seq[GenericParameter], expr: Sourced[Expression]): Sourced[Expression] =
    expr.value match {
      case EffectfulType(_, resultType) =>
        binders.foldRight(bare(resultType)) { (binder, result) =>
          expr.as(
            FunctionApplication(
              Some(expr.as(WellKnownTypes.functionDataTypeFQN.moduleName.show)),
              expr.as(WellKnownTypes.functionDataTypeFQN.name.name),
              Some(Seq(binder.name.as(typeExpr(binder.name)), result)),
              Seq.empty
            )
          )
        }
      case _                            => bare(expr)
    }

  /** The mark on a handed binder ([[WellKnownTypes.handedTypeFQN]]), module-qualified so it needs no import. */
  private def handedMark(anchor: Sourced[?]): Sourced[Expression] =
    anchor.as(
      FunctionApplication(
        Some(anchor.as(WellKnownTypes.handedTypeFQN.moduleName.show)),
        anchor.as(WellKnownTypes.handedTypeFQN.name.name),
        None,
        Seq.empty
      )
    )

  /** Where the minted binders go in the generic list: `0` for an ordinary definition, and for an **ability member**
    * past the whole run of [[GenericParameter.abilityLevel]] binders
    * [[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]] contributes — so a member's own binders never land
    * inside the ability-level prefix, which must stay exactly the ability's.
    */
  private def mintAt(function: FunctionDefinition): Int =
    function.genericParameters.lastIndexWhere(_.abilityLevel) + 1

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
  ): EffectRow[UnresolvedAbilityConstraint[Sourced[Expression]]] = {
    // A parameter is counted among the definition's own: the handles an effect member already takes are not
    // (`docs/effects.md` D36), so every index here is the same on both effect paths.
    val args = function.args.dropWhile(arg => Handles.isParameter(arg.name.value))
    EffectRow(
      (returnRowEntries(function) ++ namedRowEntries(function)).distinctBy(constraintKey),
      // A meta companion's parameters are not thunked ([[parameterType]]), so they must not be recorded as row
      // positions either: the row record is what tells the `row` phase to wrap an actual and to run a reference, and
      // doing that against a bare parameter is exactly the mismatch it would cause.
      if (isMetaCompanion(function)) Seq.empty
      else
        args.zipWithIndex.collect {
          case (arg, index) if isRow(arg.typeExpression) =>
            EffectRow.ParameterEffects(index, topLevelRowEntries(arg.typeExpression), arg.closedRow)
        },
      callbackEffects =
        if (isMetaCompanion(function)) Seq.empty
        else
          args.zipWithIndex.flatMap { case (arg, index) =>
            codomainRow(arg.typeExpression).map { case (arity, entries) =>
              EffectRow.CallbackEffects(index, arity, entries, arg.closedRow)
            }
          },
      returnArity = args.size
    )
  }

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

  private def topLevelRowEntries(expr: Sourced[Expression]): Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    expr.value match {
      case EffectfulType(effects, _) => effects
      case _                         => Seq.empty
    }

  private def isRow(expr: Sourced[Expression]): Boolean = expr.value match {
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

  /** A parameter or field position: a top-level row becomes the thunk `Unit => A`; everything else is [[bare]]. */
  private def thunked(expr: Sourced[Expression]): Sourced[Expression] = expr.value match {
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
    case WithBinding(subject, implementation)              =>
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
    case WithBinding(subject, _)                    => collectRows(subject)
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
