package com.vanillasource.eliot.eliotc.used

import cats.Monad
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort
import com.vanillasource.eliot.eliotc.used.UsedNamesState.*

import scala.annotation.tailrec

/** Processor that collects all reachable named values from a given root named value.
  *
  * This processor works with monomorphized facts and tracks:
  *   - Type parameters each value is used with
  *   - Statistics about how many arguments are applied directly to each value
  *
  * It is also the home of the **non-convergence backstop** (Deliverable A of the monomorphization-keying plan): because
  * `used` is the codegen driver that walks the *breadth* of the monomorphic fact graph, a divergent type-level
  * computation could make this DFS materialise specializations without bound — a hang/OOM. The breadth lives in this
  * processor's own `processValue` descent (not in the global `activeFactKeys` chain, which only reflects *depth* across
  * fact generations and stays flat here, since every [[com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue]]
  * is requested as a sibling of `used`), so the backstop tracks the chain of enclosing `processValue` frames
  * (`ancestors`) and, when more than [[maxNestedRepeats]] of them share the same `vfqn` with differing type arguments,
  * converts the runaway into a specific diagnostic instead of diverging.
  *
  * It is purely a fail-safe. Eliot user code cannot express recursion (see the "Total by Default" cornerstone in
  * `.claude/CLAUDE.md`), so the reachable instantiation graph is finite and program-shaped, and the codegen projection
  * only ever folds identical code together — neither can drive an unbounded chain. The backstop therefore only fires on
  * a genuinely divergent *type-level* computation (the residual `Type:Type`/Girard case) or a compiler bug, never on a
  * legitimate program.
  *
  * @param maxNestedRepeats
  *   How many nested `processValue` frames for one `vfqn` are tolerated before the specialization is declared
  *   non-converging. Generous on purpose (see [[UsedNamesProcessor.DefaultMaxNestedRepeats]]): a terminating recursion
  *   is bounded by the program's finite type structure and stays far below it, so only genuine divergence crosses it.
  */
class UsedNamesProcessor(maxNestedRepeats: Int = UsedNamesProcessor.DefaultMaxNestedRepeats)
    extends SingleKeyTypeProcessor[UsedNames.Key]
    with Logging {

  override protected def generateFact(key: UsedNames.Key): CompilerIO[Unit] =
    for {
      state <- (recordUsage(key.rootFQN, Seq.empty, 0) >> processValue(key.rootFQN, Seq.empty, Seq.empty, Nil)).runS(
                 UsedNamesState()
               )
      _     <- registerFactIfClear(getUsedNames(key.rootFQN, state)).whenA(!state.failed)
    } yield ()

  /** Walk one monomorphic instance, deduping on its **codegen projection** (Deliverable B2/B3): the traversal — the
    * `visited` set and the [[com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue]] demand — is keyed on
    * [[CodegenProjection.codegenProject]] of the type arguments, so two instances that generate identical code (e.g.
    * `id[Int[0, 100]]` and `id[Int[0, 50]]`, both a byte) type-check and materialise only one representative woven
    * value instead of one per exact bound. The exact `typeArgs` are still used to fetch that representative (the
    * checker's keys stay full and exact) and are recorded verbatim by [[recordUsage]] (so the backend, which already
    * collapses these cases via its method-signature dedup, sees the full picture).
    *
    * The demand is the woven value rather than the raw `MonomorphicValue` because `used` is the codegen driver: it must
    * walk the *woven* body so effect-operation references resolved by the weaver (and its inserted `flatMap`/`pure`
    * calls) are the ones whose callees get materialised. For pure code the `Id`-normalization the woven stage applies
    * erases all effect machinery, so the walk sees plain calls.
    *
    * @param typeArgs
    *   The full, type-checking-exact type arguments — used to fetch the representative woven value.
    * @param projected
    *   The codegen projection of `typeArgs`, used as the dedup identity.
    * @param ancestors
    *   The `vfqn`s of the enclosing `processValue` frames (the DFS stack of values currently being walked, innermost
    *   first), used by the non-convergence backstop to detect a runaway recursion.
    */
  private def processValue(
      vfqn: ValueFQN,
      typeArgs: Seq[GroundValue],
      projected: Seq[GroundValue],
      ancestors: List[ValueFQN]
  ): UsedNamesIO[Unit] =
    isVisited(vfqn, projected).ifM(
      Monad[CompilerIO].unit.liftToUsedNames,
      for {
        _          <- markVisited(vfqn, projected)
        wovenMaybe <- getFactIfProduced(WovenValue.Key(vfqn, typeArgs)).liftToUsedNames
        _          <- wovenMaybe.fold(markFailed()) { mv =>
                        recordNaturalArity(vfqn, mv.naturalArity) >>
                          checkConvergence(vfqn, mv.name, ancestors) >>
                          processMonomorphicValue(mv, vfqn :: ancestors)
                      }
      } yield ()
    )

  /** Trip the backstop when this `vfqn` already occurs more than [[maxNestedRepeats]] times among its enclosing frames
    * — a chain of nested specializations of the same value with differing type arguments, i.e. a non-converging
    * recursion.
    */
  private def checkConvergence(
      vfqn: ValueFQN,
      name: Sourced[QualifiedName],
      ancestors: List[ValueFQN]
  ): UsedNamesIO[Unit] = {
    val nestedRepeats = ancestors.count(_ == vfqn)
    if (nestedRepeats > maxNestedRepeats) reportNonConvergence(name, nestedRepeats + 1).liftToUsedNames
    else Monad[CompilerIO].unit.liftToUsedNames
  }

  private def reportNonConvergence(name: Sourced[QualifiedName], depth: Int): CompilerIO[Unit] =
    compilerAbort(
      name.as(
        s"Monomorphization of '${name.value.name}' is not converging: specialized $depth levels deep with differing type arguments."
      ),
      Seq(
        "Type argument(s) change on every step, so a new specialization is produced each time and the chain never closes.",
        "Eliot user code cannot recurse, so this indicates a non-terminating type-level computation (a Type:Type/Girard paradox) or a compiler issue, not ordinary code."
      )
    )

  private def processMonomorphicValue(mv: WovenValue, ancestors: List[ValueFQN]): UsedNamesIO[Unit] =
    mv.runtime.traverse_(sourcedExpr => processExpression(sourcedExpr.value, ancestors))

  @tailrec
  private def processExpression(expr: MonomorphicExpression.Expression, ancestors: List[ValueFQN]): UsedNamesIO[Unit] =
    expr match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        processApplicationChain(target.value.expression, 1, Seq(argument), ancestors)

      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
        processValueReference(vfqn, typeArgs, 0, ancestors)

      case MonomorphicExpression.FunctionLiteral(_, _, body) =>
        processExpression(body.value.expression, ancestors)

      case MonomorphicExpression.IntegerLiteral(_) =>
        Monad[CompilerIO].unit.liftToUsedNames

      case MonomorphicExpression.StringLiteral(_) =>
        Monad[CompilerIO].unit.liftToUsedNames

      case MonomorphicExpression.ParameterReference(_) =>
        Monad[CompilerIO].unit.liftToUsedNames
    }

  /** Process a chain of function applications to count how many arguments are applied directly to a value reference.
    *
    * @param expr
    *   The current expression in the chain (target of an application)
    * @param applicationCount
    *   Number of applications counted so far
    * @param nestedArguments
    *   Arguments collected from nested applications (need to be processed after finding the base)
    * @param ancestors
    *   The enclosing-frame chain threaded for the non-convergence backstop (see [[processValue]])
    */
  @tailrec
  private def processApplicationChain(
      expr: MonomorphicExpression.Expression,
      applicationCount: Int,
      nestedArguments: Seq[Sourced[MonomorphicExpression]],
      ancestors: List[ValueFQN]
  ): UsedNamesIO[Unit] = {
    def processNestedArguments: UsedNamesIO[Unit] =
      nestedArguments.traverse_(arg => processExpression(arg.value.expression, ancestors))

    expr match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        processApplicationChain(target.value.expression, applicationCount + 1, nestedArguments :+ argument, ancestors)

      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
        processValueReference(vfqn, typeArgs, applicationCount, ancestors) >> processNestedArguments

      case MonomorphicExpression.FunctionLiteral(_, _, body) =>
        processExpression(body.value.expression, ancestors) >> processNestedArguments

      case MonomorphicExpression.ParameterReference(_) =>
        processNestedArguments

      case _ =>
        processNestedArguments
    }
  }

  private def processValueReference(
      sourcedVfqn: Sourced[ValueFQN],
      typeArgs: Seq[GroundValue],
      applicationCount: Int,
      ancestors: List[ValueFQN]
  ): UsedNamesIO[Unit] =
    for {
      _         <- recordUsage(sourcedVfqn.value, typeArgs, applicationCount)
      projected <- CodegenProjection.codegenProject(sourcedVfqn, typeArgs).liftToUsedNames
      _         <- processValue(sourcedVfqn.value, typeArgs, projected, ancestors)
    } yield ()
}

object UsedNamesProcessor {

  /** Default nesting tolerance for the non-convergence backstop. 500 is deliberately generous: a terminating recursion
    * is bounded by the program's finite type structure and stays far below this, while a genuinely divergent recursion
    * is unbounded and always crosses it. Configurable per the keying plan (Deliverable A).
    */
  val DefaultMaxNestedRepeats: Int = 500
}
