package com.vanillasource.eliot.eliotc.namedvalues.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.namedvalues.NamedValues
import com.vanillasource.eliot.eliotc.namedvalues.fact.{NamedValuesIndex, NamedValuesRewrittenValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.{
  FunctionApplication,
  StringLiteral,
  ValueReference,
  spine
}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** The reflection rewrite (Option A — a syntactic residual-expression transformation, sufficient because reflection is
  * only used in *runtime* position). It recognises every applied reflection call in a value's operator-resolved runtime
  * body and replaces it with a fully-resolved **right fold** over the sorted [[NamedValuesIndex]] entries:
  *
  *   - `foldNamedValues(name, initial, combine)` ⤳ `combine("Mod::v₁", v₁, combine("Mod::v₂", v₂, initial))`, each
  *     gathered value handed to `combine` as an ordinary argument, labelled with its canonical qualified name;
  *   - `namedValues[V](name)` ⤳ `prepend(prepend(empty[V], v₂), v₁)`, the same fold at the free monoid, carrying the
  *     call's `[V]` type argument onto `prepend`/`empty` so the checker enforces the element-type claim by definitional
  *     equality.
  *
  * Reifying the enumeration as *code* rather than as data is what lets it gather **computations**. A `List[V]` element
  * lands in `prepend`'s plain generic slot — a value (`docs/effects.md` §1 rule 2) — so a gathered value performing
  * effects is performed at the collecting definition, never stored; there is no `V` that works. A fold's element lands
  * in whatever slot `combine` *declares*, so the ordinary `row` machinery answers for it: a code parameter (`test uses
  * *, Throw[String]: Unit`) receives it as code, run on the effects in scope where it was written plus what `combine`
  * hands it for the listed `Throw[String]`, which `combine` may discharge (`catch`). Each element is checked and
  * monomorphized independently, so gathered values may differ in both clause and type — `combine[V ~ Show](name, v,
  * acc)` gathers heterogeneous values sharing an ability, which one `List[V]` cannot hold.
  *
  * Placed after `OperatorResolverProcessor` (FQNs and application structure are final) and before
  * `RecursionCheckProcessor` (whose input is repointed to this fact), so the emitted references and calls are
  * recursion-checked, effect-checked, saturated and monomorphized exactly like hand-written code — reachability
  * included, so the reflected values are marked live with no separate root mechanism. Nothing here knows about effects:
  * the `row` phase reads `combine`'s declaration, exactly as it would had the user typed the chain out.
  *
  * A value that uses no reflection reads no facts here and is wrapped unchanged, so only values that actually reflect
  * depend on the (whole-pool) enumeration.
  *
  * Fail-safes: a non-literal `name`, a `combine` that is not a reference to a declared value, or an under-applied
  * reflection call is a hard error (never a silent empty fold); a name in the index that cannot be enumerated aborts
  * loudly via [[getFactOrError]]. `combine` must be a declared value because the `row` phase decides code-vs-value
  * from a *callee's declaration* — a lambda has none, so its parameters are values and every gathered computation
  * would run eagerly at the fold.
  *
  * The whole emitted chain is attributed to the call's `name` position, so a downstream mismatch points at the
  * reflection call rather than at synthesized code. The cost is that one bad gathered value is not distinguished from
  * another there; the algebra's own declaration is where such a mismatch is read.
  */
class NamedValuesRewriteProcessor
    extends TransformationProcessor[OperatorResolvedValue.Key, NamedValuesRewrittenValue.Key](key =>
      OperatorResolvedValue.Key(key.vfqn, key.platform)
    ) {

  /** How one gathered value is folded onto the rest of the fold: the innermost expression, and the step combining an
    * entry with everything to its right.
    */
  private case class Algebra(
      initial: OperatorResolvedExpression,
      combine: (ValueFQN, OperatorResolvedExpression) => OperatorResolvedExpression
  )

  override protected def generateFromKeyAndFact(
      key: NamedValuesRewrittenValue.Key,
      value: OperatorResolvedValue
  ): CompilerIO[NamedValuesRewrittenValue] =
    value.runtime
      .traverse(body => rewrite(body.value, value.platform).map(body.as))
      .map(newRuntime => NamedValuesRewrittenValue(value.copy(runtime = newRuntime)))

  private def rewrite(
      expr: OperatorResolvedExpression,
      platform: Platform
  ): CompilerIO[OperatorResolvedExpression] = {
    val (head, args) = spine(expr)

    head match {
      case ValueReference(name, typeArgs) if name.value == NamedValues.namedValuesFQN =>
        expandNamedValues(name, typeArgs, args, platform).flatMap(reapply(_, platform))
      case ValueReference(name, _) if name.value == NamedValues.foldNamedValuesFQN    =>
        expandFold(name, args, platform).flatMap(reapply(_, platform))
      case _                                                                          =>
        OperatorResolvedExpression.mapChildrenM(rewrite(_, platform))(expr)
    }
  }

  /** `namedValues[V](name)` — the `List` sugar, the fold at `(prepend, empty[V])`. */
  private def expandNamedValues(
      name: Sourced[ValueFQN],
      typeArgs: Seq[Sourced[OperatorResolvedExpression]],
      args: Seq[Sourced[OperatorResolvedExpression]],
      platform: Platform
  ): CompilerIO[Expansion] =
    args match {
      case nameArg +: rest =>
        for {
          gathered <- literalName("namedValues", nameArg)
          chain    <- buildChain(gathered, platform, listAlgebra(gathered, typeArgs))
        } yield Expansion(chain, rest)
      case _               =>
        compilerError(
          name.as("The 'namedValues' reflection primitive must be applied directly to a literal String name.")
        ) >> abort
    }

  /** `foldNamedValues(name, initial, combine)` — the general primitive. */
  private def expandFold(
      name: Sourced[ValueFQN],
      args: Seq[Sourced[OperatorResolvedExpression]],
      platform: Platform
  ): CompilerIO[Expansion] =
    args match {
      case nameArg +: initialArg +: combineArg +: rest =>
        for {
          gathered <- literalName("foldNamedValues", nameArg)
          combine  <- namedCombine(combineArg)
          initial  <- rewrite(initialArg.value, platform)
          chain    <- buildChain(gathered, platform, foldAlgebra(gathered, initial, combine))
        } yield Expansion(chain, rest)
      case _                                           =>
        compilerError(
          name.as(
            "The 'foldNamedValues' reflection primitive must be applied directly to a literal String name, " +
              "an initial value and a combining value."
          )
        ) >> abort
    }

  /** The gathered values right-folded through the algebra, so the first index entry ends up outermost and the fold runs
    * in the index's canonical qualified-name order.
    */
  private def buildChain(
      gathered: Sourced[String],
      platform: Platform,
      algebra: Algebra
  ): CompilerIO[OperatorResolvedExpression] =
    getFactOrError(NamedValuesIndex.Key(gathered.value, platform))(
      compilerError(gathered.as(s"Could not enumerate values named '${gathered.value}' in the source pool."))
    ).map(index => index.fqns.foldRight(algebra.initial)(algebra.combine))

  private def listAlgebra(
      gathered: Sourced[String],
      typeArgs: Seq[Sourced[OperatorResolvedExpression]]
  ): Algebra =
    Algebra(
      ValueReference(gathered.as(NamedValues.listEmptyFQN), typeArgs),
      (fqn, rest) =>
        applied(
          gathered,
          ValueReference(gathered.as(NamedValues.listPrependFQN), typeArgs),
          rest,
          ValueReference(gathered.as(fqn))
        )
    )

  private def foldAlgebra(
      gathered: Sourced[String],
      initial: OperatorResolvedExpression,
      combine: OperatorResolvedExpression
  ): Algebra =
    Algebra(
      initial,
      (fqn, rest) =>
        applied(
          gathered,
          combine,
          StringLiteral(gathered.as(fqn.show)),
          ValueReference(gathered.as(fqn)),
          rest
        )
    )

  private def literalName(
      primitive: String,
      arg: Sourced[OperatorResolvedExpression]
  ): CompilerIO[Sourced[String]] =
    arg.value match {
      case StringLiteral(name) => name.pure
      case _                   =>
        compilerError(arg.as(s"The '$primitive' reflection primitive requires a literal String name.")) >> abort
    }

  /** The combining argument, which must be a reference to a *declared* value: the row elaborator reads capture-vs-run
    * from a callee's declaration, and a lambda has none.
    */
  private def namedCombine(arg: Sourced[OperatorResolvedExpression]): CompilerIO[OperatorResolvedExpression] =
    arg.value match {
      case reference: ValueReference => reference.pure
      case _                         =>
        compilerError(
          arg.as(
            "The 'foldNamedValues' reflection primitive requires a declared value as its combining argument, " +
              "since only a declaration can state the effect rows of the gathered values it receives."
          )
        ) >> abort
    }

  /** An over-applied reflection call (the built chain applied to further arguments) keeps those arguments, rewritten. */
  private def reapply(expansion: Expansion, platform: Platform): CompilerIO[OperatorResolvedExpression] =
    expansion.remainingArguments
      .traverse(arg => rewrite(arg.value, platform).map(arg.as))
      .map(_.foldLeft(expansion.chain)((acc, arg) => FunctionApplication(arg.as(acc), arg)))

  private def applied(
      at: Sourced[?],
      target: OperatorResolvedExpression,
      arguments: OperatorResolvedExpression*
  ): OperatorResolvedExpression =
    arguments.foldLeft(target)((acc, argument) => FunctionApplication(at.as(acc), at.as(argument)))

  /** A rewritten reflection call: the emitted fold chain, plus any arguments the call was over-applied to. */
  private case class Expansion(
      chain: OperatorResolvedExpression,
      remainingArguments: Seq[Sourced[OperatorResolvedExpression]]
  )
}
