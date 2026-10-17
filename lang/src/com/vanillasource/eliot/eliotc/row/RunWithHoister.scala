package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*

import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
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
  * A run need not be the argument itself: one written anywhere inside an actual at such a slot — `catch({ body;
  * "done" }, …)` — is bound by that slot as a `with` around the whole actual would bind it. The nearest enclosing slot
  * that decides the entry wins: one naming an implementation of it carries that implementation, one supplying it with
  * nothing named stops the search (the run is promised the default there), and one riding it passes the search
  * outward, as the binding itself passes. A `with` for the entry written around an enclosing block stops it too.
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
  * The resolver lowers every `with` in a body to its implementation's binder around it (D35 step 2), so a `with` here
  * is read through that call ([[peel]]), and only its **code** moves: `body with scripted(name)` is
  * `scripted$with(name, body with scripted)`, and the `with` inside moves onto the slot while the binder's call stays
  * at the run, so the arguments are still values computed where they are written, and each run may supply its own
  * (D25 step 2). The environment threading builds the frame there and hands it to the code.
  */
object RunWithHoister {

  /** What a definition looks like with its runs' bindings moved to their slots, and the runs that disagree. */
  case class Hoisted(value: OperatorResolvedValue, errors: Seq[(Sourced[String], Seq[String])])

  /** The values a run of one of `orv`'s code parameters is written at a slot of, directly or inside the actual — the
    * only callees whose bound declarations [[hoist]] needs.
    */
  def passedTo(orv: OperatorResolvedValue): Set[ValueFQN] =
    codeParameters(orv).fold(Set.empty)(parameters =>
      runsIn(orv, parameters).flatMap(_.enclosing.collect { case Enclosing.Slot(callee, _) => callee }).toSet
    )

  /** The implementations a `with` around a run of one of `orv`'s code parameters or around a block holding it names,
    * those its slots already name, and those the slots its runs are written at name — the only ones whose ability
    * [[hoist]] needs to know. `callees` holds the bound declarations of [[passedTo]].
    */
  def candidates(orv: OperatorResolvedValue, callees: Map[ValueFQN, OperatorResolvedValue]): Set[ValueFQN] =
    codeParameters(orv).fold(Set.empty) { parameters =>
      val slots = SignatureView.of(orv.signature).parameters
      val runs  = runsIn(orv, parameters)
      (runs.flatMap(_.withs) ++ runs.flatMap(_.enclosing.flatMap(enclosingImplementations(_, callees))) ++
        parameters.values.flatMap((index, _) =>
          slots.lift(index).toSeq.flatMap(tpe => SlotBindings.of(tpe.value).map(_.implementation))
        ))
        .map(_.value)
        .toSet
    }

  /** Move every binding of a run of a code parameter onto its slot. `callees` holds the bound declarations of
    * [[passedTo]] that exist, `abilityOf` the ability of each of [[candidates]], and `clauseRows` the entries each of
    * them performs in its clauses, as its declaration spells them.
    */
  def hoist(
      orv: OperatorResolvedValue,
      callees: Map[ValueFQN, OperatorResolvedValue],
      abilityOf: Map[ValueFQN, AbilityFQN],
      clauseRows: Map[ValueFQN, Seq[AbilityConstraint[OperatorResolvedExpression]]]
  ): Hoisted =
    codeParameters(orv) match {
      case None             => Hoisted(orv, Seq.empty)
      case Some(parameters) =>
        val runs      = runsIn(orv, parameters)
        val slotTypes = SignatureView.of(orv.signature).parameters
        val own       = orv.effectRow.returnEffects.map(spelled).toSet
        // Per parameter, the implementation each hoisted ability is bound to, and the runs that disagree.
        val decided   = parameters.toSeq.map { case (name, (index, entries)) =>
          val declared  = entries.map(entry => own.contains(spelled(entry)))
          val listed    = entries.map(_.abilityFQN).toSet
          val slotNamed = slotTypes.lift(index).toSeq.flatMap(tpe => SlotBindings.of(tpe.value))
          val bindings0 = runs.filter(_.parameter.value == name)
          // What the callee supplies: the listed entries this definition does not declare, and the clause entries of the
          // implementations the slot names or a `with` around a run names (D26).
          // An implementation carried here brings its own clause entries, so the set grows until it is closed.
          def supplied(implementations: Seq[ValueFQN]): Set[AbilityFQN] =
            (entries ++ implementations.distinct.flatMap(clauseRows.getOrElse(_, Seq.empty)))
              .filterNot(entry => own.contains(spelled(entry)))
              .map(_.abilityFQN)
              .toSet
          val written0  = slotNamed.map(_.implementation.value) ++ bindings0.flatMap(_.withs.map(_.value))
          def closed(carriable: Set[AbilityFQN]): Set[AbilityFQN] = {
            val reached = bindings0.flatMap(run => bound(run, listed, carriable, callees, abilityOf).values)
            val next    = supplied(written0 ++ reached.map(_.implementation.value))
            if (next == carriable) carriable else closed(next)
          }
          val carriable = closed(supplied(written0))
          val bindings  = bindings0.map(run => run -> bound(run, listed, carriable, callees, abilityOf))
          val abilities = bindings.flatMap(_._2.keys).distinct
          val chosen    = abilities.flatMap { ability =>
            bindings.flatMap(_._2.get(ability)).headOption.map(ability -> _)
          }.toMap
          // The entries a binding covers: every listed entry of its ability for a `with` around a run, as written after
          // each; only those this definition does not declare for a carried one, which names no entry.
          def covered(ability: AbilityFQN, carried: Boolean): Seq[Int] =
            entries.indices.filter(i => entries(i).abilityFQN == ability && !(carried && declared(i)))
          def covers(named: SlotBindings.Named, index: Int): Boolean =
            named.entry.fold(!declared(index))(_ == index)
          val errors    = abilities.flatMap { ability =>
            val chosenBound    = chosen(ability)
            val implementation = chosenBound.implementation
            val disagreeing    = bindings.filterNot(
              _._2.get(ability).map(_.implementation.value).contains(implementation.value)
            )
            val onSlot         = slotNamed
              .filter(named => abilityOf.get(named.implementation.value).contains(ability))
              .filterNot(_.implementation.value == implementation.value)
              .filter(named => covered(ability, chosenBound.carried).exists(covers(named, _)))
            val carried        = bindings.exists(_._2.get(ability).exists(_.carried))
            disagreeing.map((run, _) => disagreement(run.parameter, ability, carried)) ++
              onSlot.map(named => slotDisagreement(named.implementation, name, ability))
          }
          val written   = chosen.toSeq.flatMap { (ability, chosenBound) =>
            val implementation = chosenBound.implementation
            if (chosenBound.carried)
              Option
                .unless(slotNamed.exists(n => n.entry.isEmpty && n.implementation.value == implementation.value))(
                  SlotBindings.Named(implementation, None)
                )
                .toSeq
            else
              covered(ability, carried = false)
                .filterNot(i => slotNamed.exists(n => n.implementation.value == implementation.value && covers(n, i)))
                .map(i => SlotBindings.Named(implementation, Some(i)))
          }
          (name, index, chosen.view.mapValues(_.implementation).toMap, errors, written)
        }
        val hoisted   = decided.map { case (name, _, chosen, _, _) => name -> chosen.values.map(_.value).toSet }.toMap
        val signature = SignatureView
          .of(orv.signature)
          .withParameters(slotTypes.zipWithIndex.map { case (tpe, index) =>
            decided.find(_._2 == index).fold(tpe) { case (_, _, _, _, written) =>
              written.foldLeft(tpe)((acc, named) => acc.as(WithBinding(acc, named.implementation, named.entry)))
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
    * first, and what encloses it, innermost first.
    */
  private case class Run(
      parameter: Sourced[String],
      withs: Seq[Sourced[ValueFQN]],
      enclosing: Seq[Enclosing]
  )

  /** What a run is written inside of that may bind one of its entries. */
  private enum Enclosing {

    /** The actual at parameter `index` of `callee`, which is the run or holds it. */
    case Slot(callee: ValueFQN, index: Int)

    /** A `with` naming `implementation` around a block that holds the run. */
    case Around(implementation: Sourced[ValueFQN])
  }

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

  private def runs(
      expr: Sourced[OperatorResolvedExpression],
      inScope: Set[String],
      skip: Int,
      enclosing: Seq[Enclosing] = Seq.empty
  ): Seq[Run] =
    expr.value match {
      case FunctionLiteral(_, _, body) if skip > 0 => runs(body, inScope, skip - 1)
      case _ if peel(expr).isDefined               =>
        run(expr, inScope, enclosing).fold {
          val (subject, withs) = unwrap(expr)
          suppliedArguments(expr).flatMap(runs(_, inScope, 0, enclosing)) ++
            runs(subject, inScope, 0, withs.map(Enclosing.Around(_)) ++ enclosing)
        }(run => suppliedArguments(expr).flatMap(runs(_, inScope, 0, enclosing)) :+ run)
      case ParameterReference(_)                   => run(expr, inScope, enclosing).toSeq
      case FunctionLiteral(name, _, body)          => runs(body, inScope - name.value, 0, enclosing)
      case FunctionApplication(_, _)               =>
        spine(expr) match {
          case (Sourced(_, _, ValueReference(callee, typeArgs)), arguments) =>
            typeArgs.flatMap(runs(_, inScope, 0, enclosing)) ++ arguments.zipWithIndex.flatMap { (argument, index) =>
              val atSlot = Enclosing.Slot(callee.value, index) +: enclosing
              run(argument, inScope, atSlot).fold(runs(argument, inScope, 0, atSlot))(Seq(_))
            }
          case (head, arguments)                                            =>
            runs(head, inScope, 0, enclosing) ++ arguments.flatMap(runs(_, inScope, 0, enclosing))
        }
      case ValueReference(_, typeArgs)             => typeArgs.flatMap(runs(_, inScope, 0, enclosing))
      case _: IntegerLiteral | _: StringLiteral    => Seq.empty
    }

  /** `expr` as a run of a code parameter in scope — the parameter, possibly under `with`s — inside `enclosing`. */
  private def run(
      expr: Sourced[OperatorResolvedExpression],
      inScope: Set[String],
      enclosing: Seq[Enclosing]
  ): Option[Run] = {
    val (subject, withs) = unwrap(expr)
    subject.value match {
      case ParameterReference(name) if inScope.contains(name.value) => Some(Run(name, withs, enclosing))
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
    peel(expr) match {
      case Some(layer) =>
        val (inner, withs) = unwrap(layer.subject)
        (inner, withs :+ layer.implementation)
      case None        => (expr, Seq.empty)
    }

  /** One `with` of a chain: the implementation it names, its subject, and how to write it back around a new subject,
    * keeping the `with` node or dropping it. A binder's call stays either way.
    */
  private case class Layer(
      implementation: Sourced[ValueFQN],
      subject: Sourced[OperatorResolvedExpression],
      rebuild: (Boolean, Sourced[OperatorResolvedExpression]) => Sourced[OperatorResolvedExpression]
  )

  /** `expr` as one `with`: the node itself — a slot's, or one the binding writer meets unlowered — or the binder's call
    * the resolver lowered it to, `scripted$with(args…, subject with scripted)`.
    */
  private def peel(expr: Sourced[OperatorResolvedExpression]): Option[Layer] =
    expr.value match {
      case WithBinding(subject, implementation, entry) =>
        Some(Layer(implementation, subject, (keep, inner) => if (keep) expr.as(WithBinding(inner, implementation, entry)) else inner))
      case FunctionApplication(_, _)                   =>
        spine(expr) match {
          case (head @ Sourced(_, _, ValueReference(callee, _)), arguments)
              if ImplementationParameters.isBinder(callee.value) =>
            arguments.lastOption.flatMap(last =>
              last.value match {
                case WithBinding(subject, implementation, entry)
                    if ImplementationParameters.binderOf(implementation.value) == callee.value =>
                  Some(
                    Layer(
                      implementation,
                      subject,
                      (keep, inner) =>
                        expr.as(
                          applyChain(
                            head,
                            arguments.init :+ (if (keep) last.as(WithBinding(inner, implementation, entry)) else inner)
                          )
                        )
                    )
                  )
                case _ => None
              }
            )
          case _ => None
        }
      case _                                           => None
    }

  /** The arguments a `with` supplies its implementation — those of its binder's call ahead of the subject. */
  private def suppliedArguments(expr: Sourced[OperatorResolvedExpression]): Seq[Sourced[OperatorResolvedExpression]] =
    expr.value match {
      case WithBinding(_, _, _) => Seq.empty
      case _                    => spine(expr)._2.dropRight(1)
    }

  /** The implementations what encloses a run names: a bound callee's slot's, or the `with` around a block. */
  private def enclosingImplementations(
      enclosing: Enclosing,
      callees: Map[ValueFQN, OperatorResolvedValue]
  ): Seq[Sourced[ValueFQN]] =
    enclosing match {
      case Enclosing.Slot(callee, index)    =>
        callees.get(callee).toSeq.flatMap(orv =>
          SignatureView.of(orv.signature).parameters.lift(index).toSeq.flatMap(tpe =>
            SlotBindings.of(tpe.value).map(_.implementation)
          )
        )
      case Enclosing.Around(implementation) => Seq(implementation)
    }

  /** What decides `ability` for a run, among what encloses it, innermost first: the implementation a slot names for it,
    * carried and located at the run, where it binds; or [[None]], when a slot supplying it with nothing named or a
    * `with` around a block binds it first, or nothing encloses it that does. A slot that rides it passes it outward.
    */
  private def carried(
      run: Run,
      ability: AbilityFQN,
      callees: Map[ValueFQN, OperatorResolvedValue],
      abilityOf: Map[ValueFQN, AbilityFQN]
  ): Option[Sourced[ValueFQN]] =
    run.enclosing.iterator
      .map {
        case enclosing @ Enclosing.Slot(callee, index) =>
          enclosingImplementations(enclosing, callees).find(named => abilityOf.get(named.value).contains(ability)) match {
            case Some(named) => Some(Some(run.parameter.as(named.value)))
            case None        => Option.when(callees.get(callee).exists(supplies(_, index, ability)))(None)
          }
        case Enclosing.Around(implementation)        =>
          Option.when(abilityOf.get(implementation.value).contains(ability))(None)
      }
      .collectFirst { case Some(decided) => decided }
      .flatten

  /** Whether `callee`'s slot at `index` supplies `ability`: lists it, and `callee`'s own clause does not declare it
    * spelled the same, which would make the slot ride it.
    */
  private def supplies(callee: OperatorResolvedValue, index: Int, ability: AbilityFQN): Boolean = {
    val own = callee.effectRow.returnEffects.map(spelled).toSet
    callee.effectRow.parameterEffects
      .filter(_.parameterIndex == index)
      .flatMap(_.effects)
      .exists(entry => entry.abilityFQN == ability && !own.contains(spelled(entry)))
  }

  /** What a run is bound to among the slot's listed abilities: its own `with`s — the innermost for each, since it
    * shadows — over what an enclosing callee's slot binds among the `carriable` ones.
    */
  private def bound(
      run: Run,
      listed: Set[AbilityFQN],
      carriable: Set[AbilityFQN],
      callees: Map[ValueFQN, OperatorResolvedValue],
      abilityOf: Map[ValueFQN, AbilityFQN]
  ): Map[AbilityFQN, Bound] =
    (carriable.toSeq.flatMap(ability =>
      carried(run, ability, callees, abilityOf).map(ability -> Bound(_, carried = true))
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
    case _ if peel(expr).isDefined                          =>
      val (subject, _) = unwrap(expr)
      subject.value match {
        case ParameterReference(name) if hoisted.contains(name.value) && !shadowed.contains(name.value) =>
          unlayer(expr, hoisted(name.value))
        case _                                                                                       =>
          val layer = peel(expr).get
          stripSupplied(layer.rebuild(true, strip(layer.subject, hoisted, 0, shadowed)), hoisted, shadowed)
      }
    case FunctionLiteral(name, paramType, body)             =>
      expr.as(FunctionLiteral(name, paramType, strip(body, hoisted, 0, shadowed + name.value)))
    case FunctionApplication(target, argument)              =>
      expr.as(FunctionApplication(strip(target, hoisted, 0, shadowed), strip(argument, hoisted, 0, shadowed)))
    case ValueReference(name, typeArgs)                     =>
      expr.as(ValueReference(name, typeArgs.map(strip(_, hoisted, 0, shadowed))))
    case _                                                  => expr
  }

  /** A run's chain of `with`s with every one naming a `moved` implementation dropped, its binder's call kept. */
  private def unlayer(
      expr: Sourced[OperatorResolvedExpression],
      moved: Set[ValueFQN]
  ): Sourced[OperatorResolvedExpression] =
    peel(expr) match {
      case Some(layer) => layer.rebuild(!moved.contains(layer.implementation.value), unlayer(layer.subject, moved))
      case None        => expr
    }

  /** A `with` with the arguments its binder's call supplies stripped, its subject left as it is. */
  private def stripSupplied(
      expr: Sourced[OperatorResolvedExpression],
      hoisted: Map[String, Set[ValueFQN]],
      shadowed: Set[String]
  ): Sourced[OperatorResolvedExpression] =
    expr.value match {
      case WithBinding(_, _, _) => expr
      case _                    =>
        val (head, arguments) = spine(expr)
        expr.as(applyChain(head, arguments.init.map(strip(_, hoisted, 0, shadowed)) :+ arguments.last))
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
