package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.processor.MetaConstructorDesugarer
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleName,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** **The meta interpretation** (`docs/total-meta-transfers.md` §6.2 / P4) — the answer to the one question *"what is
  * this value's meta-information?"*, and the place the closure invariant of §2 is actually executed:
  *
  *   - a **leaf states** its transfer, so its meta is its `^Meta` companion reduced on the argument metas;
  *   - **everything else derives**, so its meta is obtained by *interpreting its own body* with its parameters bound to
  *     the caller's argument metas.
  *
  * Those are R2 and R3 read as a definition rather than as a prohibition, which is why [[metaOf]] is a two-armed `ifM`
  * on exactly the test [[MetaTransferAccountingProcessor]] enforces. Before this existed only the first arm did: a call
  * to a bodied callee was a ⊤ boundary, so precision was a property of *where code sits relative to a native* rather
  * than of what the code *is* — `def label(s: String): String = s` lost its argument's size, and `String::show`
  * (bodied, therefore forbidden to state) derived nothing at all.
  *
  * **This is Option B of §6, not Option A.** No shadow twin program is emitted: the interpretation walks the very
  * monomorphized body the representation pass will stamp, so node correspondence is free. The stated transfers it
  * bottoms out at stay ordinary Eliot reduced by the one NbE evaluator ([[EscalatingReducer]]), so the single-evaluator
  * cornerstone is intact — this adds a *walk*, not a second evaluator.
  *
  * **What it computes and what it does not.** This object answers only *what the meta is*. It records nothing, demands
  * no `where`, and reports no diagnostic: which nodes may be *stamped* with a meta is representation policy and stays
  * with [[RefinementChannelProcessor]]'s walk, whose records are keyed by source position **in the value being
  * walked**. A derived meta is therefore consumed at the **call node in the caller** and nowhere else — the callee's
  * own body is laid out once, conservatively, from its own table at ⊤ parameters (§7: "get that wrong and it arrives as
  * a `ClassCastException`, not a type error"). Interpreting a callee's body here never records into its table and never
  * re-demands its preconditions; both are made once, at the definition.
  *
  * **Termination is the recursion gate's** (§2.3): user code cannot express recursion, so the derivation walks a DAG
  * and needs no fixpoint, no widening and no ascending-chain condition. The [[interpretationBudget]] is therefore not
  * what makes this terminate — it is the §8.4 fail-safe against *cost*: a diamond-shaped call DAG re-interpreted per
  * path is exponential even though each path is acyclic. The budget is a step count threaded through the whole
  * derivation of one call node, and exhausting it yields **absence**, which is sound ("I know nothing" is always true)
  * and never a wrong answer.
  *
  * **First-order for now.** A lambda is a function value and carries no meta of its own here, so a callee that applies
  * one of its own parameters (`foldLeft`'s `combine`, `Function::apply`) still yields ⊤ — the higher-order rows of §5,
  * which additionally wait on the `List` size domain for anything to iterate over. An *applied* lambda is interpreted,
  * since that is what a pure `val` block lowers to (`(x -> rest)(e)`) and its binding is ordinary parameter binding.
  */
object MetaInterpreter {

  /** What the interpretation knows about the parameters in scope: a parameter bound to the caller's argument meta, or
    * to ⊤ (`None`) when the caller could not pin one. Keyed by name because
    * [[MonomorphicExpression.ParameterReference]] is, which makes *shadowing* this map's one obligation: a binder that
    * is not being applied must not leave an outer binding of the same name readable in its body (see
    * [[bindParameters]], the only place bindings are added).
    */
  private type Env = Map[String, Option[GroundValue]]

  /** The §8.4 cost fail-safe: the number of interpretation steps one call node's derivation may spend, across depth
    * *and* fan-out together (each node visited costs one, and the budget is threaded through siblings rather than
    * reset). Exhaustion yields absence, never a wrong answer, so the only thing a small budget costs is precision on a
    * deep call DAG. Memoization on `(vfqn, typeArgs, argMetas)` (§6.2) is the precision follow-on if this is ever
    * observed to bind in practice; a hard step count is the stronger guarantee of the two and is what must be here.
    */
  private val interpretationBudget: Int = 256

  /** The meta of an ordinary call: the callee **states** its transfer (a native leaf — reduce the companion), or it
    * **derives** one from its body (everything else — interpret it). `resultType` is the *call node's* own ground type,
    * which is what decides whether there is anything to derive at all: an untracked type's meta is the trivial `Unit`
    * (§2.3, meta is total on types but not structural), so a call returning one is skipped before any body is fetched.
    * That gate is what keeps the interpretation off the overwhelming majority of call nodes in a program.
    */
  def metaOfCall(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      argMetas: Seq[Option[GroundValue]]
  ): CompilerIO[Option[GroundValue]] =
    metaOf(callee, typeArguments, resultType, argMetas, interpretationBudget).map(_._2)

  /** The α seed of the `Int` domain: a literal `n`'s singleton range, built in Eliot by
    * `eliot.lang.Runtime::integerLiteral`'s `{closed(V, V)}` brace and reduced here through the *same* stated-transfer
    * path as every other leaf. The interpretation builds no domain structure of its own; it only wraps `n` as the
    * `BigInteger` value to reduce the companion at.
    */
  def integerSeed(value: BigInt): CompilerIO[Option[GroundValue]] =
    reduceTransfer(WellKnownTypes.integerLiteralFQN, Seq(GroundValue.Direct(value, bigIntType)), Seq.empty)

  /** The α seed of the `String` domain (`docs/string-length-meta.md` §3.1): a literal's singleton size, counted in
    * **code points** — what `String::length` counts on every target — through the same path as the integer seed.
    */
  def stringSeed(text: String): CompilerIO[Option[GroundValue]] =
    reduceTransfer(
      WellKnownTypes.stringLiteralFQN,
      Seq(GroundValue.Direct(BigInt(text.codePointCount(0, text.length)), bigIntType)),
      Seq.empty
    )

  /** Whether a concrete type declares meta-information — i.e. its module (in the compiler pool, where
    * [[MetaConstructorDesugarer]] emits meta structures) declares its `T$Meta` structure. A slotless type declares
    * none, so its meta is the trivial `Unit`. The one membership test three readers share: this object's [[metaTypeOf]]
    * and its derivation gate, and [[MetaTransferAccountingProcessor]]'s R2.
    */
  def declaresMetaStructure(typeName: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(typeName.moduleName, Platform.Compiler))
      .map(_.names.contains(metaStructureName(typeName)))

  /** Flatten a curried application into its ultimate head and its arguments in source order. */
  private[channel] def flatten(
      node: Sourced[MonomorphicExpression]
  ): (Sourced[MonomorphicExpression], Seq[Sourced[MonomorphicExpression]]) =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        val (head, args) = flatten(target)
        (head, args :+ argument)
      case _                                                           => (node, Seq.empty)
    }

  /** The closure invariant of §2, executed: **state or derive**, split on the presence of a `^Meta` companion — the
    * same test [[MetaTransferAccountingProcessor]] enforces from the other side, so the two cannot drift into
    * disagreeing about which values summarise and which are read.
    */
  private def metaOf(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      argMetas: Seq[Option[GroundValue]],
      budget: Int
  ): CompilerIO[(Int, Option[GroundValue])] =
    declaresTransfer(callee).ifM(
      reduceTransfer(callee, typeArguments, argMetas).map((budget, _)),
      derive(callee, typeArguments, resultType, argMetas, budget)
    )

  /** Derive a bodied callee's meta by interpreting its body under the caller's argument metas. Three things make this
    * decline (soundly, to ⊤) rather than answer:
    *
    *   - the **result carries no meta** — the cheap gate, taken before any fact is read;
    *   - the callee has **no body on this track** — a leaf that states nothing, which R2 has already made a compile
    *     error for a meta-carrying return, or a value whose monomorphization failed upstream;
    *   - the call is **not saturated** — a partial application is a function value (whose type carries no meta anyway),
    *     and an over-application applies the callee's *result* to further arguments, which is the higher-order case.
    */
  private def derive(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      argMetas: Seq[Option[GroundValue]],
      budget: Int
  ): CompilerIO[(Int, Option[GroundValue])] =
    if (budget <= 0) unknown(0)
    else
      carriesMeta(resultType).ifM(
        getFactIfProduced(MonomorphicValue.Key(callee, typeArguments)).flatMap {
          case Some(mv) if mv.naturalArity.contains(argMetas.size) =>
            mv.runtime.flatMap(b =>
              bindParameters(b.as(MonomorphicExpression(mv.signature, b.value)), argMetas)
            ) match {
              case Some((node, env)) => interpret(node, env, budget - 1)
              case None              => unknown(budget)
            }
          case _                                                   => unknown(budget)
        },
        unknown(budget)
      )

  /** Interpret one node of a body: its meta, and the budget left after computing it. Bottom-up and pure — no record is
    * kept and no diagnostic is raised, so this may be run over a *callee's* body without touching that callee's own
    * table or repeating its checks.
    */
  private def interpret(
      node: Sourced[MonomorphicExpression],
      env: Env,
      budget: Int
  ): CompilerIO[(Int, Option[GroundValue])] =
    if (budget <= 0) unknown(0)
    else
      node.value.expression match {
        case MonomorphicExpression.IntegerLiteral(value)               => integerSeed(value.value).map((budget - 1, _))
        case MonomorphicExpression.StringLiteral(value)                => stringSeed(value.value).map((budget - 1, _))
        case MonomorphicExpression.ParameterReference(parameterName)   =>
          // The one point the caller's knowledge enters: a bound parameter *is* the argument's meta. An unbound one
          // (a lambda's own binder, an unsaturated interior) is ⊤.
          (budget - 1, env.getOrElse(parameterName.value, None)).pure[CompilerIO]
        case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
          // A function value carries no meta of its own — that is the first-order limit, and the higher-order case
          // (§5's table) is where a lambda would instead become a *closure over metas*.
          unknown(budget - 1)
        case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
          // A bare reference is a *saturated* call when the callee takes no arguments, so a nullary def derives here
          // exactly as an applied one does below.
          metaOf(vfqn.value, ta, node.value.expressionType, Seq.empty, budget - 1)
        case _: MonomorphicExpression.FunctionApplication              =>
          val (head, args) = flatten(node)
          head.value.expression match {
            case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
              interpretAll(args, env, budget - 1).flatMap { case (left, argMetas) =>
                metaOf(vfqn.value, ta, node.value.expressionType, argMetas, left)
              }
            case _: MonomorphicExpression.FunctionLiteral                  =>
              // An applied lambda — what a pure `val` block lowers to (`val x = e ; rest` ⤳ `(x -> rest)(e)`,
              // `BlockDesugaringProcessor`). Its binding is ordinary parameter binding, so a block's `val`s carry
              // their metas into the rest of the block.
              interpretAll(args, env, budget - 1).flatMap { case (left, argMetas) =>
                bindParameters(head, argMetas) match {
                  case Some((body, bound)) => interpret(body, env ++ bound, left)
                  case None                => unknown(left)
                }
              }
            case _                                                         =>
              // An applied parameter (a callee calling one of its own function parameters) or an applied `match`
              // result: the higher-order case, ⊤ here.
              unknown(budget - 1)
          }
      }

  /** Interpret every argument in the *caller's* scope, threading the budget through them, so a wide fan-out spends the
    * same budget a deep chain does. Arguments are all interpreted before any binder is added, which is what keeps a
    * binder from shadowing a name a later argument reads.
    */
  private def interpretAll(
      nodes: Seq[Sourced[MonomorphicExpression]],
      env: Env,
      budget: Int
  ): CompilerIO[(Int, Seq[Option[GroundValue]])] =
    nodes.foldLeftM((budget, Seq.empty[Option[GroundValue]])) { case ((left, metas), node) =>
      interpret(node, env, left).map { case (remaining, meta) => (remaining, metas :+ meta) }
    }

  /** Peel one leading [[MonomorphicExpression.FunctionLiteral]] per argument meta, binding each binder's name to it,
    * and return the node under the last one. `None` when the node runs out of binders before the arguments run out — an
    * over-application, which this cannot interpret.
    *
    * This is the only place [[Env]] grows, which is where shadowing is answered: a binder that *is* applied replaces
    * any outer binding of its name (the `+`), and a binder that is not applied is never descended into at all.
    */
  private def bindParameters(
      node: Sourced[MonomorphicExpression],
      argMetas: Seq[Option[GroundValue]]
  ): Option[(Sourced[MonomorphicExpression], Env)] =
    argMetas.foldLeftM((node, Map.empty: Env)) { case ((current, bound), meta) =>
      current.value.expression match {
        case MonomorphicExpression.FunctionLiteral(parameterName, _, body) =>
          (body, bound + (parameterName.value -> meta)).some
        case _                                                             => none
      }
    }

  /** Whether this callee **states** its transfer — a cached [[UnifiedModuleNames]] membership test for its `^Meta`
    * companion in the compiler pool. States ⤳ read the summary; does not ⤳ read the body.
    */
  private def declaresTransfer(callee: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler))
      .map(_.names.contains(RefinementChannelProcessor.metaCompanionName(callee)))

  /** Reduce a **stated** transfer — a `^Meta` companion — on the argument metas, and read back the domain structure it
    * produced. The companion is reduced at the **meta** type arguments (the call's own, mapped through [[metaTypeOf]]),
    * so a generic companion's bare type-parameter parameters bind to the meta type and its `join`/arithmetic always
    * lands on a real `Meta` instance rather than a stuck non-existent `T$Meta`.
    *
    * `None` (⊤) when an input is unknown in a way the companion's body actually projects: an unknown argument is a
    * `SemValue.VType` placeholder the reduction ignores unless it feeds a slot projection, in which case the projection
    * stalls and the result does not quote to a structure. Reduction runs through the post-monomorphize linker-executor
    * ([[EscalatingReducer]]), so a transfer routed through an ability instance resolves through that instance's own
    * monomorphization instead of sticking on the abstract ability method.
    */
  private def reduceTransfer(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      argMetas: Seq[Option[GroundValue]]
  ): CompilerIO[Option[GroundValue]] =
    for {
      metaTypeArgs <- calleeTypeArgs.traverse(metaTypeOf)
      result       <- EscalatingReducer.reduceApplied(
                        RefinementChannelProcessor.metaCompanionFqn(callee),
                        metaTypeArgs,
                        argMetas.map {
                          case Some(gv) => Evaluator.groundToSem(gv) // the argument's own meta value, passed opaquely
                          case None     => SemValue.VType            // ⊤ placeholder: an unknown/untracked argument
                        }
                      )
    } yield result.collect { case s: GroundValue.Structure => s }

  /** The **meta type** of a base type — its `$Meta` meta structure if the type declares one (a slotted type like `Int`
    * ⤳ `Int$Meta`), else the trivial [[unitType]] (an untracked type carries no refinement, so its meta is `Unit`).
    * This is the total-meta rule of R1: a generic `^Meta` companion reduced at these always lands on a real `Meta`
    * instance (`Meta[Int$Meta]` or `Meta[Unit]`). A non-structure type argument is left unchanged.
    */
  private def metaTypeOf(baseType: GroundValue): CompilerIO[GroundValue] = baseType match {
    case GroundValue.Structure(fqn, _, _) =>
      declaresMetaStructure(fqn).map {
        case true  =>
          GroundValue.Structure(ValueFQN(fqn.moduleName, metaStructureName(fqn)), Seq.empty, GroundValue.Type)
        case false => unitType
      }
    case other                            => other.pure[CompilerIO]
  }

  /** Whether a *node's* type carries meta-information at all — [[metaTypeOf]] read as a predicate, and the derivation's
    * cheap gate. Non-structural by design (§2.3): `List[Int]` and `data Counter(n: Int)` carry none, so meta is lost at
    * every data boundary exactly as it is today.
    */
  private def carriesMeta(nodeType: GroundValue): CompilerIO[Boolean] = nodeType match {
    case GroundValue.Structure(fqn, _, _) => declaresMetaStructure(fqn)
    case _                                => false.pure[CompilerIO]
  }

  private def metaStructureName(typeName: ValueFQN): QualifiedName =
    QualifiedName(typeName.name.name + MetaConstructorDesugarer.metaTypeSuffix, Qualifier.Type)

  private def unknown(budget: Int): CompilerIO[(Int, Option[GroundValue])] =
    (budget, none[GroundValue]).pure[CompilerIO]

  /** The `BigInteger` type a literal's seed value carries — the only literal-domain constant the interpretation needs,
    * to wrap a raw `n` as a [[GroundValue.Direct]] when reducing a seed companion at it.
    */
  private val bigIntType: GroundValue =
    GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  private val unitModule: ModuleName = ModuleName(ModuleName.defaultSystemPackage, "Unit")

  /** `Unit` — the meta type of every untracked (slotless) type. Its trivial `Meta[Unit]` instance is the do-nothing
    * join, so a merge over untracked arms reduces cleanly to no refinement (⊤).
    */
  private[channel] val unitType: GroundValue =
    GroundValue.Structure(ValueFQN(unitModule, QualifiedName("Unit", Qualifier.Type)), Seq.empty, GroundValue.Type)
}
