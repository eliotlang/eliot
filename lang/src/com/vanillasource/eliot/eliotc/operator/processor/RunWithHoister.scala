package com.vanillasource.eliot.eliotc.operator.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** A `with` around a **run of a code parameter** is that parameter's slot `with` (`docs/effects.md` §2, D23).
  *
  * `def h(body uses *, Console: Unit) uses *: String = runWriterToLog(body with rec)` says that the `Console`
  * escaping from `body` — a listed effect, which `h` must handle or declare — is handled by `rec`. But `body` is
  * compiled where its caller wrote it, and its caller reads only `h`'s declaration, so the implementation has to be
  * *in* the declaration: this rewrite moves it there, making `h` the definition its author could have written as
  * `def h(body uses *, Console with rec: Unit)`. It reads the definition's own text and nothing else, so the binding is
  * still written from declarations, never inferred from a callee's body.
  *
  * Only a `with` whose ability the slot **lists** moves: an effect the code uses through `*` unlisted was bound where
  * the code was written, so a `with` for it binds nothing here and is left in place, for the `row` phase to report as
  * unused. A moved `with` holds for every run of the parameter, so every run must bind the same implementation for it,
  * and a slot that already names one must name that one. Only nullary code parameters move: a callback's listed
  * entries are its own scope's, not the callee's to bind (§7 item 1).
  *
  * A `with` supplying arguments to an implementation that takes parameters does not move: its arguments are computed in
  * the body, where a slot cannot name them, so around a run of a listed entry it is an error.
  */
object RunWithHoister {

  /** What a definition looks like with its runs' `with`s moved to their slots, and the runs that disagree. */
  case class Hoisted(value: OperatorResolvedValue, errors: Seq[(Sourced[String], Seq[String])])

  /** The implementations a `with` directly around a run of one of `orv`'s code parameters names, and those its slots
    * already name — the only ones whose ability [[hoist]] needs to know.
    */
  def candidates(orv: OperatorResolvedValue): Set[ValueFQN] =
    codeParameters(orv).fold(Set.empty) { parameters =>
      val slots = SignatureView.of(orv.signature).parameters
      runsIn(orv, parameters).flatMap(run => (run.withs ++ run.parameterised).map(_.value)).toSet ++
        parameters.values.flatMap((index, _) => slots.lift(index).toSeq.flatMap(tpe => slotImplementations(tpe.value)))
          .map(_.value)
    }

  /** Move every `with` around a run of a code parameter that binds one of the slot's listed entries onto the slot.
    * `abilityOf` gives the ability of each of [[candidates]].
    */
  def hoist(orv: OperatorResolvedValue, abilityOf: Map[ValueFQN, AbilityFQN]): Hoisted =
    codeParameters(orv) match {
      case None             => Hoisted(orv, Seq.empty)
      case Some(parameters) =>
        val runs      = runsIn(orv, parameters)
        val slotTypes = SignatureView.of(orv.signature).parameters
        // Per parameter, the implementation each hoisted ability is bound to, and the runs that disagree.
        val decided   = parameters.toSeq.map { case (name, (index, listed)) =>
          val own       = runs.filter(_.parameter.value == name)
          val bindings  = own.map(run => run -> hoistable(run, listed, abilityOf))
          val supplied  = own.flatMap(run =>
            run.parameterised.toSeq
              .filter(named => abilityOf.get(named.value).exists(listed.contains))
              .map(named => parameterisedAroundRun(named, run.parameter))
          )
          val slotNamed = slotTypes.lift(index).toSeq.flatMap(tpe => slotImplementations(tpe.value))
          val abilities = bindings.flatMap(_._2.keys).distinct
          val chosen    = abilities.flatMap { ability =>
            bindings.flatMap(_._2.get(ability)).headOption.map(ability -> _)
          }.toMap
          val errors    = abilities.flatMap { ability =>
            val implementation = chosen(ability)
            val disagreeing    = bindings.collect {
              case (run, bound) if !bound.get(ability).map(_.value).contains(implementation.value) => run
            }
            val onSlot         = slotNamed
              .filter(named => abilityOf.get(named.value).contains(ability))
              .filterNot(_.value == implementation.value)
            disagreeing.map(run => disagreement(run.parameter, ability)) ++
              onSlot.map(named => slotDisagreement(named, name, ability))
          }
          (name, index, chosen, errors ++ supplied)
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

  /** One run of a code parameter: the reference, and the implementations the `with`s directly around it name,
    * innermost first, apart from those supplied arguments there.
    */
  private case class Run(
      parameter: Sourced[String],
      withs: Seq[Sourced[ValueFQN]],
      parameterised: Seq[Sourced[ValueFQN]] = Seq.empty
  )

  /** The definition's nullary code parameters by name: their index and the abilities their slot lists. */
  private def codeParameters(orv: OperatorResolvedValue): Option[Map[String, (Int, Set[AbilityFQN])]] = {
    val names      = parameterNames(orv)
    val parameters = orv.effectRow.parameterEffects.flatMap { slot =>
      names.lift(slot.parameterIndex).map(_ -> (slot.parameterIndex, slot.effects.map(_.abilityFQN).toSet))
    }.toMap
    Option.when(parameters.nonEmpty && orv.runtime.isDefined)(parameters)
  }

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
  private def runsIn(orv: OperatorResolvedValue, parameters: Map[String, (Int, Set[AbilityFQN])]): Seq[Run] =
    orv.runtime.toSeq.flatMap(body => runs(body, parameters.keySet, parameterCount(orv)))

  private def runs(expr: Sourced[OperatorResolvedExpression], inScope: Set[String], skip: Int): Seq[Run] =
    expr.value match {
      case FunctionLiteral(_, _, body) if skip > 0 => runs(body, inScope, skip - 1)
      case WithBinding(_, _)                       =>
        val (subject, withs) = unwrap(expr)
        subject.value match {
          case ParameterReference(name) if inScope.contains(name.value) => Seq(Run(name, withs))
          case _                                                         => runs(subject, inScope, 0)
        }
      case ParameterReference(name)                =>
        Option.when(inScope.contains(name.value))(Run(name, Seq.empty)).toSeq
      case FunctionLiteral(name, _, body)          => runs(body, inScope - name.value, 0)
      case FunctionApplication(target, argument)   =>
        runs(target, inScope, 0) ++ (suppliedTo(target.value, argument.value) match {
          case Some(named) =>
            runs(argument, inScope, 0).map(run =>
              if (run.withs.lastOption.exists(_.value == named.value))
                run.copy(withs = run.withs.init, parameterised = run.parameterised :+ run.withs.last)
              else run
            )
          case None        => runs(argument, inScope, 0)
        })
      case ValueReference(_, typeArgs)             => typeArgs.flatMap(runs(_, inScope, 0))
      case _: IntegerLiteral | _: StringLiteral    => Seq.empty
    }

  /** The `with` an application supplies arguments to: `argument` is the `with` and `target` the implementation's binder
    * applied to its arguments ([[ImplementationParameters.binder]]), which is what the resolver lowers
    * `subject with scripted(xs)` to.
    */
  private def suppliedTo(
      target: OperatorResolvedExpression,
      argument: OperatorResolvedExpression
  ): Option[Sourced[ValueFQN]] =
    argument match {
      case WithBinding(_, named) if headOf(target).contains(ImplementationParameters.binderOf(named.value)) => Some(named)
      case _                                                                                                 => None
    }

  private def headOf(expr: OperatorResolvedExpression): Option[ValueFQN] = expr match {
    case FunctionApplication(target, _) => headOf(target.value)
    case ValueReference(name, _)        => Some(name.value)
    case _                              => None
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

  /** What a run's `with`s bind among the slot's listed abilities — the innermost for each, since it shadows. */
  private def hoistable(
      run: Run,
      listed: Set[AbilityFQN],
      abilityOf: Map[ValueFQN, AbilityFQN]
  ): Map[AbilityFQN, Sourced[ValueFQN]] =
    run.withs.reverse.flatMap(named => abilityOf.get(named.value).filter(listed.contains).map(_ -> named)).toMap

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

  private def disagreement(run: Sourced[String], ability: AbilityFQN): (Sourced[String], Seq[String]) =
    (
      run.as(
        s"'${run.value}' runs here with a different implementation of '${ability.abilityName}' than at its other runs."
      ),
      Seq(
        s"A `with` around '${run.value}' binds the effect '${ability.abilityName}' of code its caller wrote, so it is " +
          s"bound once, for every run: write the same `with` around each run of '${run.value}', or once on its slot " +
          s"(`${run.value} uses *, ${ability.abilityName} with …`)."
      )
    )

  private def parameterisedAroundRun(named: Sourced[ValueFQN], run: Sourced[String]): (Sourced[String], Seq[String]) =
    (
      named.as(
        s"This `with` supplies arguments to an implementation of an effect '${run.value}' lists, but code its caller " +
          "wrote is bound by its slot, and a slot cannot supply arguments."
      ),
      Seq(
        s"Bind an implementation without parameters around '${run.value}', or let the caller bind this one around the " +
          "code it writes."
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
