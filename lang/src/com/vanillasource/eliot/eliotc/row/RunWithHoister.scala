package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The implementations a definition's **runs of a code parameter** are bound by, moved onto that parameter's slot
  * (`docs/effects.md` §2, D23 and D25).
  *
  * `def h(body uses *, Console: Unit) uses *: String = runWriterToLog(body with rec)` says that the `Console`
  * escaping from `body` — a listed effect, which `h` must handle or declare — is handled by `rec`. But `body` is
  * compiled where its caller wrote it, and its caller reads only `h`'s declaration, so the implementation has to be
  * *in* the declaration: this rewrite moves it there, making `h` the definition its author could have written as
  * `def h(body uses *, Console with rec: Unit)`.
  *
  * A run is bound in two ways. A `with` directly around it binds what the slot **lists**: an effect the code uses
  * through `*` unlisted was bound where the code was written, so a `with` for it binds nothing here and is left in
  * place, for the `row` phase to report as unused. And a run **passed** to a callee's slot that binds one of those
  * entries with `with` is a run under that `with` (D25 step 3): `catch` hands its `computation` to `runThrow`, whose
  * slot names the platform's implementation, so `catch`'s slot names it too, and so does the slot of every
  * discharger written over `catch`. A slot entry the definition also declares, spelled the same, is not carried: it
  * rides the caller's binding (D22, decided (b)), and its frame is given where it runs. A `with` around the run is
  * nearer than the callee's slot and wins.
  *
  * Reading a callee's slots is reading its declaration, never its body — each step is one callee's declaration, read
  * before monomorphization, and the result is written onto the definition's own slot, where its callers read it. It
  * is safe only after the recursion gate: the callees are read as *their* bound declarations, which reach this one
  * again exactly when the program is recursive.
  *
  * A moved binding holds for every run of the parameter, so every run must be bound to the same implementation for
  * it, and a slot that already names one must name that one. Only nullary code parameters move: a callback's listed
  * entries are its own scope's, not the callee's to bind (§7 item 1).
  *
  * A `with` supplying arguments to an implementation that takes parameters moves too, but only its **code**: the
  * resolver lowered `body with scripted(name)` to `scripted$with(name, body with scripted)`, and the `with` inside moves
  * onto the slot while the binder's call stays at the run, so the arguments are still values computed where they are
  * written, and each run may supply its own (D25 step 2). The environment threading builds the frame there and hands
  * it to the code.
  */
object RunWithHoister {

  /** What a definition looks like with its runs' bindings moved to their slots, and the runs that disagree. */
  case class Hoisted(value: OperatorResolvedValue, errors: Seq[(Sourced[String], Seq[String])])

  /** The values a run of one of `orv`'s code parameters is passed to directly — the only callees whose bound
    * declarations [[hoist]] needs.
    */
  def passedTo(orv: OperatorResolvedValue): Set[ValueFQN] =
    codeParameters(orv).fold(Set.empty)(parameters => runsIn(orv, parameters).flatMap(_.passedTo.map(_._1)).toSet)

  /** The implementations a `with` directly around a run of one of `orv`'s code parameters names, those its slots
    * already name, and those the slots its runs are passed to name — the only ones whose ability [[hoist]] needs to
    * know. `callees` holds the bound declarations of [[passedTo]].
    */
  def candidates(orv: OperatorResolvedValue, callees: Map[ValueFQN, OperatorResolvedValue]): Set[ValueFQN] =
    codeParameters(orv).fold(Set.empty) { parameters =>
      val slots = SignatureView.of(orv.signature).parameters
      val runs  = runsIn(orv, parameters)
      (runs.flatMap(_.withs) ++ runs.flatMap(calleeSlot(_, callees)) ++
        parameters.values.flatMap((index, _) => slots.lift(index).toSeq.flatMap(tpe => slotImplementations(tpe.value))))
        .map(_.value)
        .toSet
    }

  /** Move every binding of a run of a code parameter onto its slot. `callees` holds the bound declarations of
    * [[passedTo]] that exist, and `abilityOf` the ability of each of [[candidates]].
    */
  def hoist(
      orv: OperatorResolvedValue,
      callees: Map[ValueFQN, OperatorResolvedValue],
      abilityOf: Map[ValueFQN, AbilityFQN]
  ): Hoisted =
    codeParameters(orv) match {
      case None             => Hoisted(orv, Seq.empty)
      case Some(parameters) =>
        val runs      = runsIn(orv, parameters)
        val slotTypes = SignatureView.of(orv.signature).parameters
        val own       = orv.effectRow.returnEffects.map(spelled).toSet
        // Per parameter, the implementation each hoisted ability is bound to, and the runs that disagree.
        val decided   = parameters.toSeq.map { case (name, (index, entries)) =>
          val listed    = entries.map(_.abilityFQN).toSet
          val carriable = entries.filterNot(entry => own.contains(spelled(entry))).map(_.abilityFQN).toSet
          val bindings  = runs
            .filter(_.parameter.value == name)
            .map(run => run -> bound(run, listed, carriable, callees, abilityOf))
          val slotNamed = slotTypes.lift(index).toSeq.flatMap(tpe => slotImplementations(tpe.value))
          val abilities = bindings.flatMap(_._2.keys).distinct
          val chosen    = abilities.flatMap { ability =>
            bindings.flatMap(_._2.get(ability)).headOption.map(ability -> _)
          }.toMap
          val errors    = abilities.flatMap { ability =>
            val implementation = chosen(ability).implementation
            val disagreeing    = bindings.filterNot(
              _._2.get(ability).map(_.implementation.value).contains(implementation.value)
            )
            val onSlot         = slotNamed
              .filter(named => abilityOf.get(named.value).contains(ability))
              .filterNot(_.value == implementation.value)
            val carried        = bindings.exists(_._2.get(ability).exists(_.carried))
            disagreeing.map((run, _) => disagreement(run.parameter, ability, carried)) ++
              onSlot.map(named => slotDisagreement(named, name, ability))
          }
          (name, index, chosen.view.mapValues(_.implementation).toMap, errors)
        }
        val hoisted   = decided.map { case (name, _, chosen, _) => name -> chosen.values.map(_.value).toSet }.toMap
        val signature = SignatureView
          .of(orv.signature)
          .withParameters(slotTypes.zipWithIndex.map { case (tpe, index) =>
            decided.find(_._2 == index).fold(tpe) { case (_, _, chosen, _) =>
              val present = slotImplementations(tpe.value).map(_.value).toSet
              chosen.values.filterNot(named => present.contains(named.value)).foldLeft(tpe) { (acc, named) =>
                acc.as(WithBinding(acc, named))
              }
            }
          })
          .toExpression
        Hoisted(
          orv.copy(
            signature = orv.signature.as(signature),
            runtime = orv.runtime.map(body => strip(body, hoisted, parameterCount(orv), Set.empty))
          ),
          decided.flatMap(_._4)
        )
    }

  /** One run of a code parameter: the reference, the implementations the `with`s directly around it name, innermost
    * first, and the callee and parameter index it is passed to as an argument, if it is.
    */
  private case class Run(
      parameter: Sourced[String],
      withs: Seq[Sourced[ValueFQN]],
      passedTo: Option[(ValueFQN, Int)]
  )

  /** The implementation a run is bound to for one ability, and whether it was carried from a callee's slot. */
  private case class Bound(implementation: Sourced[ValueFQN], carried: Boolean)

  /** The definition's nullary code parameters by name: their index and the entries their slot lists. */
  private def codeParameters(
      orv: OperatorResolvedValue
  ): Option[Map[String, (Int, Seq[AbilityConstraint[OperatorResolvedExpression]])]] = {
    val names      = parameterNames(orv)
    val parameters = orv.effectRow.parameterEffects.flatMap { slot =>
      names.lift(slot.parameterIndex).map(_ -> (slot.parameterIndex, slot.effects))
    }.toMap
    Option.when(parameters.nonEmpty && orv.runtime.isDefined)(parameters)
  }

  private def spelled(
      entry: AbilityConstraint[OperatorResolvedExpression]
  ): (AbilityFQN, Seq[String]) =
    (entry.abilityFQN, entry.typeArgs.map(_.render))

  private def parameterCount(orv: OperatorResolvedValue): Int = {
    val view = SignatureView.of(orv.signature)
    view.binders.size + view.parameters.size
  }

  private def parameterNames(orv: OperatorResolvedValue): Seq[String] =
    orv.runtime.toSeq.flatMap(body => leading(body.value, parameterCount(orv))).takeRight(
      SignatureView.of(orv.signature).parameters.size
    )

  private def leading(expr: OperatorResolvedExpression, remaining: Int): Seq[String] = expr match {
    case FunctionLiteral(name, _, body) if remaining > 0 => name.value +: leading(body.value, remaining - 1)
    case _                                               => Seq.empty
  }

  /** Every run of a code parameter in the body, past the definition's own leading lambdas. */
  private def runsIn(orv: OperatorResolvedValue, parameters: Map[String, ?]): Seq[Run] =
    orv.runtime.toSeq.flatMap(body => runs(body, parameters.keySet, parameterCount(orv)))

  private def runs(expr: Sourced[OperatorResolvedExpression], inScope: Set[String], skip: Int): Seq[Run] =
    expr.value match {
      case FunctionLiteral(_, _, body) if skip > 0 => runs(body, inScope, skip - 1)
      case WithBinding(_, _)                       => run(expr, inScope, None).fold(runs(unwrap(expr)._1, inScope, 0))(Seq(_))
      case ParameterReference(_)                   => run(expr, inScope, None).toSeq
      case FunctionLiteral(name, _, body)          => runs(body, inScope - name.value, 0)
      case FunctionApplication(_, _)               =>
        spine(expr) match {
          case (Sourced(_, _, ValueReference(callee, typeArgs)), arguments) =>
            typeArgs.flatMap(runs(_, inScope, 0)) ++ arguments.zipWithIndex.flatMap { (argument, index) =>
              run(argument, inScope, Some(callee.value -> index)).fold(runs(argument, inScope, 0))(Seq(_))
            }
          case (head, arguments)                                            =>
            runs(head, inScope, 0) ++ arguments.flatMap(runs(_, inScope, 0))
        }
      case ValueReference(_, typeArgs)             => typeArgs.flatMap(runs(_, inScope, 0))
      case _: IntegerLiteral | _: StringLiteral    => Seq.empty
    }

  /** `expr` as a run of a code parameter in scope — the parameter, possibly under `with`s — passed to `passedTo`. */
  private def run(
      expr: Sourced[OperatorResolvedExpression],
      inScope: Set[String],
      passedTo: Option[(ValueFQN, Int)]
  ): Option[Run] = {
    val (subject, withs) = unwrap(expr)
    subject.value match {
      case ParameterReference(name) if inScope.contains(name.value) => Some(Run(name, withs, passedTo))
      case _                                                         => None
    }
  }

  /** An application's head and its arguments, in order. */
  private def spine(
      expr: Sourced[OperatorResolvedExpression]
  ): (Sourced[OperatorResolvedExpression], Seq[Sourced[OperatorResolvedExpression]]) =
    expr.value match {
      case FunctionApplication(target, argument) =>
        val (head, arguments) = spine(target)
        (head, arguments :+ argument)
      case _                                     => (expr, Seq.empty)
    }

  /** A chain of `with`s and the subject it wraps; the innermost `with` first. */
  private def unwrap(
      expr: Sourced[OperatorResolvedExpression]
  ): (Sourced[OperatorResolvedExpression], Seq[Sourced[ValueFQN]]) =
    expr.value match {
      case WithBinding(subject, implementation) =>
        val (inner, withs) = unwrap(subject)
        (inner, withs :+ implementation)
      case _                                    => (expr, Seq.empty)
    }

  /** The implementations the bound slot a run is passed to names, each located at the run, where it binds. */
  private def calleeSlot(run: Run, callees: Map[ValueFQN, OperatorResolvedValue]): Seq[Sourced[ValueFQN]] =
    run.passedTo.toSeq.flatMap { (callee, index) =>
      callees.get(callee).toSeq.flatMap(orv =>
        SignatureView.of(orv.signature).parameters.lift(index).toSeq.flatMap(tpe => slotImplementations(tpe.value))
      ).map(named => run.parameter.as(named.value))
    }

  /** What a run is bound to among the slot's listed abilities: its own `with`s — the innermost for each, since it
    * shadows — over what the callee's slot it is passed to binds among the `carriable` ones.
    */
  private def bound(
      run: Run,
      listed: Set[AbilityFQN],
      carriable: Set[AbilityFQN],
      callees: Map[ValueFQN, OperatorResolvedValue],
      abilityOf: Map[ValueFQN, AbilityFQN]
  ): Map[AbilityFQN, Bound] =
    (calleeSlot(run, callees).flatMap(named =>
      abilityOf.get(named.value).filter(carriable.contains).map(_ -> Bound(named, carried = true))
    ) ++ run.withs.reverse.flatMap(named =>
      abilityOf.get(named.value).filter(listed.contains).map(_ -> Bound(named, carried = false))
    )).toMap

  /** The body with every hoisted `with` removed from around the runs of its parameter. */
  private def strip(
      expr: Sourced[OperatorResolvedExpression],
      hoisted: Map[String, Set[ValueFQN]],
      skip: Int,
      shadowed: Set[String]
  ): Sourced[OperatorResolvedExpression] = expr.value match {
    case FunctionLiteral(name, paramType, body) if skip > 0 =>
      expr.as(FunctionLiteral(name, paramType, strip(body, hoisted, skip - 1, shadowed)))
    case WithBinding(_, _)                                  =>
      val (subject, withs) = unwrap(expr)
      subject.value match {
        case ParameterReference(name) if hoisted.contains(name.value) && !shadowed.contains(name.value) =>
          val kept = withs.filterNot(named => hoisted(name.value).contains(named.value))
          if (kept.size == withs.size) expr
          else kept.foldLeft(subject)((acc, named) => named.as(WithBinding(acc, named)))
        case _                                                                                       =>
          expr.value match {
            case WithBinding(inner, implementation) =>
              expr.as(WithBinding(strip(inner, hoisted, 0, shadowed), implementation))
            case _                                  => expr
          }
      }
    case FunctionLiteral(name, paramType, body)             =>
      expr.as(FunctionLiteral(name, paramType, strip(body, hoisted, 0, shadowed + name.value)))
    case FunctionApplication(target, argument)              =>
      expr.as(FunctionApplication(strip(target, hoisted, 0, shadowed), strip(argument, hoisted, 0, shadowed)))
    case ValueReference(name, typeArgs)                     =>
      expr.as(ValueReference(name, typeArgs.map(strip(_, hoisted, 0, shadowed))))
    case _                                                  => expr
  }

  private def slotImplementations(tpe: OperatorResolvedExpression): Seq[Sourced[ValueFQN]] = tpe match {
    case WithBinding(subject, implementation) => slotImplementations(subject.value) :+ implementation
    case _                                    => Seq.empty
  }

  private def disagreement(run: Sourced[String], ability: AbilityFQN, carried: Boolean): (Sourced[String], Seq[String]) =
    (
      run.as(
        s"'${run.value}' runs here with a different implementation of '${ability.abilityName}' than at its other runs."
      ),
      Seq(
        s"A `with` around '${run.value}' binds the effect '${ability.abilityName}' of code its caller wrote, so it is " +
          s"bound once, for every run: write the same `with` around each run of '${run.value}', or once on its slot " +
          s"(`${run.value} uses *, ${ability.abilityName} with …`)."
      ) ++ Option.when(carried)(
        s"Passing '${run.value}' to a parameter whose slot names an implementation of '${ability.abilityName}' runs " +
          "it under that implementation, as a `with` around it would."
      )
    )

  private def slotDisagreement(
      named: Sourced[ValueFQN],
      parameter: String,
      ability: AbilityFQN
  ): (Sourced[String], Seq[String]) =
    (
      named.as(
        s"The slot of '$parameter' names another implementation of '${ability.abilityName}' than the `with` around " +
          s"its runs."
      ),
      Seq(s"Name one implementation of '${ability.abilityName}' for '$parameter', on its slot or around its runs.")
    )
}
