package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.unify.{SemValuePrinter, Unifier, UnifyResult}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** **The hole rule** — the one effect rule the checker holds on the new effect path (`docs/effects.md` D36 point 4): a
  * handle position takes the **nearest handle in lexical scope whose entry answers it**, an entry answering when the
  * two are definitionally equal after evaluation.
  *
  * The `row` phase writes each handle position as a hole naming the entry it asks for and its candidates, nearest
  * first and grouped by the binder that put them in scope
  * ([[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.HandleHole]]). The checker records each
  * one as it meets it ([[Hole]]) and this pass, run in the post-drain fixpoint beside ability resolution, decides them:
  *
  *   - **certain** ([[resolveCertain]]): the nearest group with a candidate that answers has exactly one, and it answers
  *     without solving anything. Nothing solved later can change that — a failed unification stays failed, and an equal
  *     pair stays equal — so it is committed at once.
  *   - **by agreement** ([[resolveByAgreement]]), once nothing else progresses: a hole whose one answering candidate
  *     answers only by solving a metavariable (`catch`'s `E`, read off the block's `raise`) takes it when every hole that
  *     candidate could answer would solve it the same way. Decided over all of them at once, so no outcome depends on the
  *     order the holes are visited in.
  *   - whatever is left is an error ([[reportUndecided]]): no handle answers, two equally near ones do, or the
  *     candidates' type arguments cannot be decided and must be spelled.
  *
  * A committed hole unifies its own type — the parameter type of the position it stands in — with the handle's, and
  * becomes a reference to the handle ([[fill]]). Nothing here is a lattice, a join or a default: a hole is filled by
  * lexical lookup by type, and only definitional equality decides it.
  */
class HandleHoleResolver {
  import HandleHoleResolver.*

  /** Commit every hole whose answer is certain. Returns whether one was committed. */
  def resolveCertain: CheckIO[Boolean] =
    undecided.flatMap(_.toList.foldLeftM(false) { (progress, hole) =>
      get.flatMap { state =>
        nearest(state.unifier, hole) match {
          case Some(Seq((candidate, after))) if unchanged(state.unifier, after) => commit(hole, candidate, after).as(true)
          case _                                                                => pure(progress)
        }
      }
    })

  /** Commit one hole whose single answering candidate every hole it could answer agrees on. Returns whether one was
    * committed; the fixpoint runs the certain pass again before asking for another.
    */
  def resolveByAgreement: CheckIO[Boolean] =
    for {
      holes    <- undecided
      state    <- get
      answering = holes.map(hole => hole -> nearest(state.unifier, hole).getOrElse(Seq.empty))
      agreeing  = answering.iterator.collect { case (hole, Seq((candidate, _))) => (hole, candidate) }.flatMap {
                    case (hole, candidate) =>
                      // Each hole holds its own copy of a candidate: a `with`'s type parameter its handle's type does
                      // not take is keyed apart at every hole, so each copy is asked at its own hole.
                      val sharing = answering.flatMap { (other, answers) =>
                        answers.collectFirst { case (offered, _) if offered.name == candidate.name => other -> offered }
                      }
                      sharing
                        .foldLeft(Option(state.unifier)) { case (acc, (other, offered)) => acc.flatMap(answer(_, other, offered)) }
                        .flatMap(_ => answer(state.unifier, hole, candidate))
                        .map(after => (hole, candidate, after))
                  }
      progress <- agreeing.nextOption() match {
                    case Some((hole, candidate, after)) => commit(hole, candidate, after).as(true)
                    case None                           => pure(false)
                  }
    } yield progress

  /** Report every hole still open once nothing progresses, and abort if there is one: a hole left open is a handle
    * position nothing fills, and the body cannot be read back without it.
    */
  def reportUndecided: CheckIO[Unit] =
    undecided.flatMap {
      case Seq() => pure(())
      case holes =>
        get.flatMap { state =>
          holes.toList.traverse_ { hole =>
            val (message, help) = diagnose(state.unifier, hole)
            liftF(compilerError(hole.at.as(message), help))
          } >>
            liftF(abort[Unit])
        }
    }

  /** Report every handle a definition is **given** — one it receives, or one a `with` in it binds — that some hole could
    * see and none took: the exact, by-entry half of "declares but does not perform" and of a `with` that binds nothing
    * (`docs/effects.md` D23), whose by-name half the `row` phase checks. A handle a block is handed is not held to it: a
    * block need not use what its callee hands it.
    */
  def reportUntaken: CheckIO[Unit] =
    get.flatMap { state =>
      val taken    = state.handleChoices.values.toSet
      val untaken  = state.handleHoles
        .flatMap(_.candidates.flatten)
        .distinctBy(_.name.value)
        .filter(candidate => !taken.contains(candidate.name.value))
      def shown(entry: Entry): String =
        entry.ability.abilityName +
          (if (entry.args.isEmpty) ""
           else entry.args.map(SemValuePrinter.show(_, state.unifier.flexStore)).mkString("[", ", ", "]"))
      val messages = untaken.collect {
        case candidate if Handles.isBinding(candidate.name.value)   =>
          candidate.name.as(s"This `with` binds nothing: no use of '${shown(candidate.entry)}' in its subject takes it.") ->
            Seq("Remove it, or move it to where the effect is performed.")
        case candidate if Handles.isParameter(candidate.name.value) =>
          candidate.name.as(s"This value declares the effect '${shown(candidate.entry)}' but does not perform it.") ->
            Seq(s"Remove '${shown(candidate.entry)}' from its `uses` clause.")
      }
      messages.toList.traverse_((message, help) => liftF(compilerError(message, help))) >>
        (if (messages.nonEmpty) liftF(abort[Unit]) else pure(()))
    }

  private def diagnose(unifier: Unifier, hole: Hole): (String, Seq[String]) = {
    val store = unifier.flexStore
    def shown(entry: Entry): String =
      entry.ability.abilityName +
        (if (entry.args.isEmpty) "" else entry.args.map(SemValuePrinter.show(_, store)).mkString("[", ", ", "]"))
    val inScope = hole.candidates.flatten.map(candidate => shown(candidate.entry)).distinct
    nearest(unifier, hole) match {
      case None                                                            =>
        (
          s"No handle in scope here answers '${shown(hole.asked)}'.",
          if (inScope.isEmpty) Seq(s"Nothing in scope provides '${hole.asked.ability.abilityName}'.")
          else Seq(s"In scope: ${inScope.mkString(", ")}.")
        )
      case Some(answers) if answers.forall((_, after) => unchanged(unifier, after)) =>
        (
          s"Two equally near handles answer '${shown(hole.asked)}': " +
            answers.map((candidate, _) => s"'${shown(candidate.entry)}'").mkString(", ") + ".",
          Seq("Give one of them a type of its own.")
        )
      case Some(_)                                                         =>
        (
          s"Which handle answers '${shown(hole.asked)}' depends on type arguments nothing here decides.",
          Seq("Spell the type arguments of the call around it.")
        )
    }
  }

  private def undecided: CheckIO[Seq[Hole]] =
    inspect(state => state.handleHoles.filterNot(hole => state.handleChoices.contains(hole.id)))

  /** The candidates of the nearest group in which any answers, each with the unifier its answer leaves. */
  private def nearest(unifier: Unifier, hole: Hole): Option[Seq[(Candidate, Unifier)]] =
    hole.candidates.iterator
      .map(_.flatMap(candidate => answer(unifier, hole, candidate).map(candidate -> _)))
      .find(_.nonEmpty)

  /** Whether `candidate` answers `hole` on `unifier`: the same effect, and every type argument definitionally equal. */
  private def answer(unifier: Unifier, hole: Hole, candidate: Candidate): Option[Unifier] =
    if (hole.asked.ability != candidate.entry.ability || hole.asked.args.size != candidate.entry.args.size) None
    else
      hole.asked.args.zip(candidate.entry.args).foldLeft(Option(unifier)) { case (acc, (asked, offered)) =>
        acc.flatMap(_.tryUnify(asked, offered, hole.at.as("Type mismatch.")) match {
          case UnifyResult.Unified(after)    => Some(after)
          case UnifyResult.Contradiction(_) => None
        })
      }

  /** Whether an answer solved nothing and left nothing pending: then it can never stop being an answer. */
  private def unchanged(before: Unifier, after: Unifier): Boolean =
    solved(before) == solved(after) && before.postponed.size == after.postponed.size

  private def solved(unifier: Unifier): Int = unifier.flexStore.entries.count(_._2.isDefined)

  /** Take `candidate` for `hole`: the hole's type is the handle's. Only a run of a code parameter can disagree here —
    * its block's handle type is one per slot entry, which every run hands it (`docs/effects.md` D36 point 6), while
    * every other position's type is fresh — so a mismatch is two runs handing it different implementations.
    */
  private def commit(hole: Hole, candidate: Candidate, after: Unifier): CheckIO[Unit] =
    modify(state =>
      state
        .withUnifier(
          after.unify(
            hole.handleType,
            candidate.handleType,
            hole.at.as(
              "This run of the code is handed another implementation than another run of it: every run of one code " +
                "parameter must be handed the same."
            )
          )
        )
        .copy(handleChoices = state.handleChoices.updated(hole.id, candidate.name.value))
    )
}

object HandleHoleResolver {

  /** An effect entry, evaluated: the effect's ability and its type arguments. */
  case class Entry(ability: AbilityFQN, args: Seq[SemValue])

  /** A handle in scope at a hole: its parameter's name, the entry it answers and its type. */
  case class Candidate(name: Sourced[String], entry: Entry, handleType: SemValue)

  /** One handle position, as the checker met it.
    *
    * @param handleType
    *   The type of the position: the hole's own type, which the chosen handle's is unified with.
    */
  case class Hole(id: Int, at: Sourced[?], asked: Entry, candidates: Seq[Seq[Candidate]], handleType: SemValue)

  /** The name a hole's reference carries until it is filled. Not a name the tokenizer can produce. */
  def placeholder(id: Int): String = s"$$hole$$$id"

  /** The checked body with every hole a reference to the handle it took. */
  def fill(expression: SemExpression, choices: Map[Int, String]): SemExpression =
    if (choices.isEmpty) expression else filled(expression, choices.map((id, chosen) => placeholder(id) -> chosen))

  private def filled(expression: SemExpression, chosen: Map[String, String]): SemExpression =
    expression.expression match {
      case SemExpression.ParameterReference(name)              =>
        chosen
          .get(name.value)
          .fold(expression)(handle => expression.copy(expression = SemExpression.ParameterReference(name.as(handle))))
      case SemExpression.FunctionApplication(target, argument) =>
        expression.copy(expression =
          SemExpression.FunctionApplication(target.map(filled(_, chosen)), argument.map(filled(_, chosen)))
        )
      case SemExpression.FunctionLiteral(name, tpe, body)      =>
        expression.copy(expression = SemExpression.FunctionLiteral(name, tpe, body.map(filled(_, chosen))))
      case _                                                   => expression
    }
}
