package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.core.processor.{MetaTransferDesugarer, MetaWhereDesugarer}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** The refinement channel's **flow analysis** — Step 6-iii of `docs/bounds-as-refinements.md` ("narrow representations
  * from the channel's flow analysis"). Post-flag-day (Step 6-ii) `Int` has lost its type parameters, so a node's value
  * range is no longer in its type; the channel *computes* it by flow and records it into a [[RefinementTable]], keyed by
  * source position. The reconcile pass ([[com.vanillasource.eliot.eliotc.reconcile.processor.ReconcileProcessor]]) then
  * stamps those intervals onto the body as per-node metas, and the JVM backend decodes each `Int`'s machine layout from
  * its meta (a narrow wrapper instead of the ⊤/bignum fallback).
  *
  * A post-pass over each [[MonomorphicValue]] (runtime track): it walks the fully-ground body bottom-up and, for every
  * node whose value range it can pin, records that interval. The propagation rules (the value channel of §4):
  *
  *   - **α (literal seeding):** a literal seeds the singleton range it knows about itself — an integer literal `n` its
  *     value `[n, n]`, a string literal the size `[k, k]` of its `k` code points. Both go through the *same* path (the
  *     literal protocol's own `^Meta` companion in `eliot.lang.Runtime`), so the second domain needed no second
  *     mechanism — only its own seed declaration.
  *   - **Calls:** a call's result is the callee's meta, asked for by [[MetaInterpreter.metaOfCall]] — **stated** by a
  *     leaf (an `Int` `+`/`-`/`*`'s `add^Meta`/…; `fold`'s merge companion `join(whenTrue, whenFalse)`, mechanically
  *     identical to a transfer, so no branch construct is ever named) or **derived** by interpreting a bodied callee's
  *     own body under these argument metas. Which of the two applies is the callee's business, not this walk's — that
  *     is the state-or-derive closure of `docs/meta.md` §1, and this walk names neither a native leaf
  *     nor a branch construct to tell them apart.
  *
  * Everything else is ⊤ (unknown, laid out as a bignum) — a parameter, the body of a lambda, a `match` (`handleCases`)
  * result, a call whose callee neither states nor can be interpreted (`docs/meta.md` §8). A ⊤ node is
  * still *recorded*, as a `None` verdict — see [[recordAt]] for why omitting it is unsound at an aliased position.
  *
  * **What this walk decides, and what it does not.** It decides *what may be stamped*: a record is keyed by source
  * position **in the value being walked**, so it is representation policy for this body and this body only
  * (`docs/meta.md` §7). *What the meta is* belongs to [[MetaInterpreter]], which computes metas
  * through call boundaries but records nothing and reports nothing. A derived meta is therefore consumed at the **call
  * node in the caller**; the callee's own layout stays joined and conservative, computed from its own table at ⊤
  * parameters.
  *
  * The walk descends into the arguments of every application (so a literal/arithmetic argument narrows and is
  * reconciled to the callee's parameter representation at the call) and stops at a lambda: a narrow value returned
  * through a lambda's `apply` bridge would fail its `CHECKCAST` (see the class note on `LambdaGenerator`), so nothing
  * under one may be recorded. It checks no demand itself: every `where` and parameter brace is checked in context by
  * [[MetaDemandsProcessor]] (`docs/meta.md` §6), whose root context this table waits on.
  *
  * Why a post-pass and not a rider inside the checker: refinements are, by the design's held invariant, strictly
  * *downstream* of type formation (they flow into checks and codegen, never back into a type), so the channel can run
  * entirely over the checker's output with zero risk to the checker's invariants. See the design doc §3.
  *
  * A transfer and a merge are recognised the *same* way: solely by the callee declaring a `^Meta` companion
  * ([[metaCompanionFqn]]), never by naming a native leaf or a branch construct (`docs/generic-refinement-merges.md`).
  * The companion's body may itself route through ability instances (a transfer's `Numeric[Interval]`, a merge's
  * `Meta.join`); the post-monomorphize linker-executor
  * ([[com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer]]) resolves those through each
  * instance's own monomorphization, so the former "a transfer must bottom out at natives" restriction is repealed
  * (`docs/refinement-channel-follow-ups.md`).
  */
class RefinementChannelProcessor
    extends TransformationProcessor[MonomorphicValue.Key, RefinementTable.Key](key =>
      MonomorphicValue.Key(key.vfqn, key.typeArguments)
    )
    with Logging {

  import RefinementChannelProcessor.*

  /** The result of walking one node: the opaque meta [[GroundValue]] the channel knows for the node's *own* value
    * (⊤ = [[None]]) and every per-node verdict recorded in the subtree (this node's plus its descendants'). A node that
    * does not return (⊥, `docs/meta.md` §12) is never materialised, so it is stamped at the boundary width: [[None]].
    */
  private case class Flow(
      own: Option[GroundValue],
      records: Seq[RefinementTable.NodeMeta]
  )

  override protected def generateFromKeyAndFact(
      key: RefinementTable.Key,
      mv: MonomorphicValue
  ): CompilerIO[RefinementTable] = {
    for {
      // The body's meta demands, checked in its root context and through it in every context its calls reach
      // (docs/meta.md §6). A table is produced only for a body whose demands hold; demanding them here
      // rather than at the `WovenValue` seam keeps a violation from withholding the woven value, whose absence would
      // stop the walk of the used graph and with it every later value's own diagnostics.
      _      <- MetaDemands.rootOf(mv).flatMap(getFactOrAbort)
      result <- MetaInterpreter.Located.root(mv) match {
                  case Some(body) => walkDefinition(body)
                  case None       => Flow(None, Seq.empty).pure[CompilerIO]
                }
    } yield RefinementTable(key.vfqn, key.typeArguments, result.records)
  }

  /** The meta of the call at `node`: its callee's statement reduced, or its body interpreted ([[MetaInterpreter]]).
    * A call whose derivation ran out of the interpretation's budget leaves this table unproduced, so no code is laid
    * out from it; the limit is reported once, at the definition whose own work ran out ([[DerivedMetaProcessor]],
    * decision I, `docs/meta.md` §5.3).
    */
  private def metaOfCall(
      node: Sourced[MonomorphicExpression],
      vfqn: Sourced[ValueFQN],
      typeArgs: Seq[GroundValue],
      arguments: Seq[MetaInterpreter.Value]
  ): CompilerIO[Option[GroundValue]] =
    MetaInterpreter.metaOfCall(vfqn.value, typeArgs, node.value.expressionType, arguments).map(_.known)

  /** A definition's leading **handle** parameters (`docs/effects.md` D36) are walked through as the parameters they
    * are, not as a lambda: an effect reaches a body as a handle, which neither carries a meta nor makes the body a
    * lambda's, so a body taking only handles records exactly as one taking nothing (`main`).
    */
  private def walkDefinition(located: MetaInterpreter.Located): CompilerIO[Flow] =
    located.node.value.expression match {
      case MonomorphicExpression.FunctionLiteral(_, parameterType, body) if isHandle(parameterType) =>
        walkDefinition(located.down(MetaInterpreter.Address.Step.Body, body))
          .map(inner => Flow(None, inner.records ++ recordAt(located.node, None)))
      case _                                                                                        => walkFlow(located)
    }

  private def isHandle(tpe: GroundValue): Boolean = tpe match {
    case GroundValue.Structure(fqn, _, _) => Handles.isDataType(fqn.name.name)
    case _                                => false
  }

  /** Compute one node's flow interval and record it (when known), descending per the propagation rules in the class
    * note. Bottom-up: a node's interval is derived from its children's, and a known interval is recorded at the node's
    * source position for representation lowering to read.
    */
  private def walkFlow(located: MetaInterpreter.Located): CompilerIO[Flow] = {
    val node = located.node
    node.value.expression match {
      case MonomorphicExpression.IntegerLiteral(value)  =>
        // α (the one point a meta *originates*): a literal `n` seeds its singleton range. The seed's construction
        // (`Int$Meta(Interval[n, n])`) lives in Eliot — `eliot.lang.Runtime::integerLiteral`'s return brace
        // `{closed(V, V)}` — and is reduced through the *same* stated-transfer path as every leaf
        // ([[MetaInterpreter.integerSeed]]). The channel builds no domain structure of its own, so even the α origin
        // is domain-agnostic.
        MetaInterpreter.integerSeed(value.value).map(meta => Flow(meta, recordAt(node, meta)))

      case MonomorphicExpression.StringLiteral(value)   =>
        // α for the `String` domain (`docs/meta.md` §2.2): a literal seeds the singleton size range of
        // its own length, through the same path as the integer seed. That count is the literal's **code points**,
        // which is what `String::length` counts on every platform (`type String`'s documented unit), so the measure is
        // platform-independent rather than a host-representation guess: the seed can never disagree with the function
        // whose unit it reports.
        MetaInterpreter.stringSeed(value.value).map(meta => Flow(meta, recordAt(node, meta)))

      case _: MonomorphicExpression.FunctionApplication =>
        val (head, args) = MetaInterpreter.flatten(located)
        head.node.value.expression match {
          case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
            // An ordinary call (or constructor). Descend into the arguments, so a literal/arithmetic argument narrows.
            // The call's own result is whatever the callee's meta is — **stated** by a leaf (`Numeric[Int]::add`'s
            // transfer, `fold`'s merge) or **derived** from a bodied callee's own body under these argument metas
            // ([[MetaInterpreter.metaOfCall]], `docs/meta.md` §5). The channel names no leaf and no
            // branch construct: which of the two applies is the callee's business, not this walk's.
            for {
              argResults <- args.traverse(walkFlow)
              meta       <- metaOfCall(
                              node,
                              vfqn,
                              typeArgs,
                              args.zip(argResults).map((arg, result) =>
                                MetaInterpreter.argument(arg, MetaInterpreter.Value.Meta(result.own), Map.empty)
                              )
                            )
            } yield Flow(meta, argResults.flatMap(_.records) ++ recordAt(node, meta))
          case _ =>
            // Any other application (a `match`, a `typeMatch`, an applied lambda): the result is a ⊤ boundary, and so
            // is its head, whose records would be discarded on the same grounds as a lambda's; the arguments are
            // descended into as above.
            args.traverse(walkFlow).map(argResults => Flow(None, argResults.flatMap(_.records) ++ recordAt(node, None)))
        }

      case MonomorphicExpression.FunctionLiteral(_, _, _) =>
        // A lambda body must not *record* narrow intervals: its `apply` bridge would `CHECKCAST` a narrowed result back
        // to the ⊤/bignum representation the caller expects, so it stays a bignum boundary for representation (Step
        // 6-iii). Its demands are checked by [[MetaDemandsProcessor]], which walks it.
        Flow(None, recordAt(node, None)).pure[CompilerIO]

      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
        // A **bare** reference to a def — *not* the head of a full application. Its meta is asked for on the same terms
        // as a call's, because for a **nullary** def that is exactly what this is: a saturated call, spelled without
        // parentheses. A reference that is *not* saturated is a function value, whose type carries no meta, so the same
        // question answers ⊤ there without a special case.
        metaOfCall(node, vfqn, typeArgs, Seq.empty).map(meta => Flow(meta, recordAt(node, meta)))

      case MonomorphicExpression.ParameterReference(_) =>
        // A parameter arrives at the width of its boundary, whatever is known of its value.
        Flow(None, recordAt(node, None)).pure[CompilerIO]
    }
  }

  /** Record this node's verdict — its pinned meta, or ⊤. Every *walked* node records, ⊤ included: a consumer can only
    * match this table against its own tree by source position, and desugaring makes positions non-unique (a pure `val`
    * block's synthesized lambda and application both carry the bound expression's range), so a ⊤ node that omitted its
    * verdict would let a sibling's meta be read as its own. Recording ⊤ makes such a position ambiguous, which the
    * reconcile pass already drops. See [[RefinementTable.NodeMeta]].
    */
  private def recordAt(
      node: Sourced[MonomorphicExpression],
      meta: Option[GroundValue]
  ): Seq[RefinementTable.NodeMeta] =
    Seq(RefinementTable.NodeMeta(node.range, meta))

}

object RefinementChannelProcessor {

  /** A callee's `^Meta` companion name: its own name in the meta namespace of its own qualifier, named by the
    * desugarer that emits it ([[MetaTransferDesugarer.companionName]]). `fold` ⤳ `fold^Meta` (merge), the
    * `Numeric[Int]` `add` ⤳ `add^Meta(Numeric#Int…)` (transfer) — the ability-implementation callee keeps its
    * `(ability, pattern)` key, so its transfer is that instance's and not a same-named sibling's
    * (`docs/meta.md` §4.2). The channel recognises a refinement operation *only* by the presence of
    * this companion — no leaf or branch construct is named.
    */
  private[channel] def metaCompanionName(callee: ValueFQN): QualifiedName =
    MetaTransferDesugarer.companionName(callee.name)

  private[channel] def metaCompanionFqn(callee: ValueFQN): ValueFQN =
    ValueFQN(callee.moduleName, metaCompanionName(callee))

  /** The name / FQN of a def's `^Where` companion (bounds-as-refinements §4.3), in the def's own module — named by the
    * desugarer that emits it ([[MetaWhereDesugarer.companionName]]), so the channel finds the precondition companion
    * `MetaWhereDesugarer` generated.
    */
  private[channel] def whereCompanionName(callee: ValueFQN): QualifiedName =
    MetaWhereDesugarer.companionName(callee.name)

  private[channel] def whereCompanionFqn(callee: ValueFQN): ValueFQN =
    ValueFQN(callee.moduleName, whereCompanionName(callee))
}
