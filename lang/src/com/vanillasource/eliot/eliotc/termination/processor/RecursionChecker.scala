package com.vanillasource.eliot.eliotc.termination.processor

import cats.{Id, Monad}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesRewrittenValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.foldValueReferences
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** The no-recursion rule (termination M1): a value defined recursively — through a self or mutual cycle in its
  * *body-level* value-reference graph — is a hard error. Eliot's pure core cannot express recursion (every loop lives in
  * a platform native), so a definition that refers back to itself is rejected outright and pointed at the platform loop
  * primitives. There is no "mark it `Inf`" escape: `Inf` originates only on a native, never by annotating one's own
  * recursion.
  *
  * Run by [[RecursionCheckProcessor]] as a standalone gate phase before the `row` phase — the per-value gate the
  * whole compiled program passes through, before monomorphization. A reported cycle leaves the value's
  * [[com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue]] unregistered (the error trips
  * `registerFactIfClear`), so the recursive value never reaches monomorphization — where it would otherwise silently
  * compile to a self-calling method or diverge the type-level computation.
  *
  * The check is precise because it walks *resolved* [[ValueFQN]] references in the runtime body only (never the type
  * signature), so the patterns that merely look self-referential are not flagged:
  *   - a covariantly self-referential `data` (`data Tree(left: Tree, right: Tree)`) references itself only in field
  *     *types*; its constructors are body-less natives, hence leaves here (M0 strict positivity vets the declaration);
  *   - an instance delegating to its own ability at an inner type (`Numeric[Interval[T]]`'s `add` calls `add` on the
  *     `T` endpoints) references the *abstract* ability method (`Qualifier.Ability`, a body-less leaf), a different
  *     FQN from the *implementation* method (`Qualifier.AbilityImplementation`) that contains it. Ability-mediated
  *     potential recursion, if any, is resolved — and its convergence policed — by monomorphization, not here.
  *
  * The check runs on every value's runtime body. A genuinely cyclic type *alias* (`type Foo = Foo`, mutual
  * `type A = B` / `type B = A`) is caught here too, because a `Type`-qualified value's runtime body *is* its alias RHS
  * (the fail-safe property — a recursive alias cannot diverge type-level reduction); a covariant `data Tree` and an
  * instance delegating to its own ability stay accepted, for the reasons above.
  */
class RecursionChecker {

  /** Reports a [[compilerError]] on `value`'s name when it is recursive, and otherwise does nothing. The registered
    * error suppresses the value's downstream [[com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue]]
    * fact, gating it out of the rest of the pipeline.
    */
  def check(value: OperatorResolvedValue, platform: Platform): CompilerIO[Unit] =
    (hint(directCallees(value), platform) >>
      reachesSelf(value.vfqn, directCallees(value).toList, Set(value.vfqn), platform)).flatMap { recursive =>
      compilerError(
        value.name.as(s"Value '${value.name.value.name}' is defined recursively."),
        Seq(
          "It refers back to itself, directly or through other values, and Eliot's pure core cannot express recursion.",
          "Use a platform loop primitive instead: 'fold' for a bounded loop, 'forever' for an endless one."
        )
      ).whenA(recursive)
    }

  /** Whether `target` is reachable from `frontier` through the body-reference graph. `visited` seeds the search with
    * `target` so a callee that refers straight back is detected, and bounds the walk against any *other* cycles in the
    * graph (it reads already-resolved facts, so visiting each node once suffices). Stack-safe via `tailRecM`.
    */
  private def reachesSelf(
      target: ValueFQN,
      frontier: List[ValueFQN],
      visited: Set[ValueFQN],
      platform: Platform
  ): CompilerIO[Boolean] =
    Monad[CompilerIO].tailRecM((frontier, visited)) {
      case (Nil, _)            => false.asRight[(List[ValueFQN], Set[ValueFQN])].pure[CompilerIO]
      case (fqn :: rest, seen) =>
        if (fqn == target) true.asRight[(List[ValueFQN], Set[ValueFQN])].pure[CompilerIO]
        else if (seen.contains(fqn)) (rest, seen).asLeft[Boolean].pure[CompilerIO]
        else
          calleesOf(fqn, platform).flatMap(cs =>
            hint(cs.filterNot(seen.contains), platform).as((rest ++ cs.toList, seen + fqn).asLeft[Boolean])
          )
    }

  /** The search reads the callees of each value it reaches one by one, and each read is that value's whole front end
    * (and its file's, the first time): so a parallel run starts the ones just found while it works through the rest.
    */
  private def hint(callees: Set[ValueFQN], platform: Platform): CompilerIO[Unit] =
    prefetch(callees.toSeq.map(NamedValuesRewrittenValue.Key(_, platform)))

  /** The body callees of an already-resolved value, or the empty set when it is body-less (a native) or cannot be
    * resolved (conservative: a leaf, so the search just stops there).
    *
    * Reads the *rewritten* fact (not the raw [[OperatorResolvedValue]]) so a callee's `namedValues` reflection is
    * expanded into concrete reference edges here too — exactly as it already is for the root value under check. Reading
    * the pre-rewrite fact would leave `namedValues` a body-less dead-end on every transitive node, letting a mutual
    * cycle whose back-path runs through a second reflection (`f = namedValues("bar")`, `g = namedValues("foo")`) slip
    * the gate. A value whose rewrite aborted (a `namedValues` error) produces no fact and is treated as a leaf, which is
    * safe: that value already failed and never reaches monomorphization.
    */
  private def calleesOf(fqn: ValueFQN, platform: Platform): CompilerIO[Set[ValueFQN]] =
    getFactIfProduced(NamedValuesRewrittenValue.Key(fqn, platform))
      .map(_.fold(Set.empty[ValueFQN])(rewritten => directCallees(rewritten.value)))

  /** Every distinct [[ValueFQN]] referenced in a value's runtime body — head positions, arguments and type arguments
    * alike (the resolved value-reference graph). The type signature is deliberately excluded.
    */
  private def directCallees(value: OperatorResolvedValue): Set[ValueFQN] =
    value.runtime.fold(Set.empty[ValueFQN])(body =>
      foldValueReferences[Id, Set[ValueFQN]](body.value, Set.empty)((acc, ref) => acc + ref.value)
    )
}
