package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.processor.{MetaTransferDesugarer, MetaWhereDesugarer}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicEffects, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal
import com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer
import com.vanillasource.eliot.eliotc.platform.Platform
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
  *     is the state-or-derive closure of `docs/total-meta-transfers.md` §2, and this walk names neither a native leaf
  *     nor a branch construct to tell them apart.
  *
  * Everything else is ⊤ (unknown, laid out as a bignum) — a parameter, the body of a lambda, a `match` (`handleCases`)
  * result, a call whose callee neither states nor can be interpreted (a higher-order one, §5's table). A ⊤ node is
  * still *recorded*, as a `None` verdict — see [[recordAt]] for why omitting it is unsound at an aliased position.
  *
  * **What this walk decides, and what it does not.** It decides *what may be stamped*: a record is keyed by source
  * position **in the value being walked**, so it is representation policy for this body and this body only
  * (`docs/total-meta-transfers.md` §7). *What the meta is* belongs to [[MetaInterpreter]], which computes metas
  * through call boundaries but records nothing and reports nothing. A derived meta is therefore consumed at the **call
  * node in the caller**; the callee's own layout stays joined and conservative, computed from its own table at ⊤
  * parameters.
  *
  * The walk **descends everywhere**: into the arguments of ordinary calls (so a literal/arithmetic argument narrows and
  * is reconciled to the callee's parameter representation at the call), into a lambda's body, and into the head of an
  * application the channel cannot recognise (an applied lambda — what a pure `val` block lowers to). Descending is what
  * makes the use-site `where` demand total; *recording* is the narrower privilege, and it stops at a lambda (a narrow
  * value returned through a lambda's `apply` bridge would fail its `CHECKCAST` — see the class note on
  * `LambdaGenerator`), which is why those two subtrees are walked for their checks and their records discarded.
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
    * (⊤ = [[None]]) and every per-node verdict recorded in the subtree (this node's plus its descendants').
    */
  private case class Flow(
      own: Option[GroundValue],
      records: Seq[RefinementTable.NodeMeta]
  )

  override protected def generateFromKeyAndFact(
      key: RefinementTable.Key,
      mv: MonomorphicValue
  ): CompilerIO[RefinementTable] = {
    // The Id-normalization this used to do up front (the uniform-carrier path wrapped every literal in
    // `pure@Effect[Id]`, hiding its `[n,n]` range from the flow) went with the carrier: effects v6 inserts no
    // machinery at a pure boundary, so the flow already sees the bare literal.
    for {
      result <- mv.runtime match {
                  case Some(body) => walkFlow(body.as(MonomorphicExpression(mv.signature, body.value)))
                  case None       => Flow(None, Seq.empty).pure[CompilerIO]
                }
    } yield RefinementTable(key.vfqn, key.typeArguments, result.records)
  }

  /** Compute one node's flow interval and record it (when known), descending per the propagation rules in the class
    * note. Bottom-up: a node's interval is derived from its children's, and a known interval is recorded at the node's
    * source position for representation lowering to read.
    */
  private def walkFlow(node: Sourced[MonomorphicExpression]): CompilerIO[Flow] =
    node.value.expression match {
      case MonomorphicExpression.IntegerLiteral(value)  =>
        // α (the one point a meta *originates*): a literal `n` seeds its singleton range. The seed's construction
        // (`Int$Meta(Interval[n, n])`) lives in Eliot — `eliot.lang.Runtime::integerLiteral`'s return brace
        // `{closed(V, V)}` — and is reduced through the *same* stated-transfer path as every leaf
        // ([[MetaInterpreter.integerSeed]]). The channel builds no domain structure of its own, so even the α origin
        // is domain-agnostic.
        MetaInterpreter.integerSeed(value.value).map(meta => Flow(meta, recordAt(node, meta)))

      case MonomorphicExpression.StringLiteral(value)   =>
        // α for the `String` domain (`docs/string-length-meta.md` §3.1): a literal seeds the singleton size range of
        // its own length, through the same path as the integer seed. That count is the literal's **code points**,
        // which is what `String::length` counts on every platform (`type String`'s documented unit), so the measure is
        // platform-independent rather than a host-representation guess: the seed can never disagree with the function
        // whose unit it reports.
        MetaInterpreter.stringSeed(value.value).map(meta => Flow(meta, recordAt(node, meta)))

      case _: MonomorphicExpression.FunctionApplication =>
        val (head, args) = MetaInterpreter.flatten(node)
        head.value.expression match {
          case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs, _) =>
            // An ordinary call (or constructor). Descend into the arguments (so a literal/arithmetic argument narrows,
            // and a `where` precondition is demanded over their ranges, bounds-as-refinements §4.3). The call's own
            // result is whatever the callee's meta is — **stated** by a leaf (`Numeric[Int]::add`'s transfer, `fold`'s
            // merge) or **derived** from a bodied callee's own body under these argument metas
            // ([[MetaInterpreter.metaOfCall]], `docs/total-meta-transfers.md` §6.2). The channel names no leaf and no
            // branch construct: which of the two applies is the callee's business, not this walk's. A lambda
            // argument's body is skipped by the `FunctionLiteral` case below.
            for {
              argResults <- args.traverse(walkFlow)
              _          <- checkWhere(node, vfqn.value, typeArgs, args.size, argResults.map(_.own))
              meta       <- MetaInterpreter.metaOfCall(
                              vfqn.value,
                              typeArgs,
                              node.value.expressionType,
                              argResults.map(_.own)
                            )
            } yield Flow(meta, argResults.flatMap(_.records) ++ recordAt(node, meta))
          case _ =>
            // Any other application (a `match`, a `typeMatch`, an applied lambda): the result is a ⊤ boundary; descend
            // into the arguments as above — **and into the head**. The head is where a pure `val` block keeps its
            // continuation (`val x = e ; rest` lowers to `(x -> rest)(e)`, `BlockDesugaringProcessor`), so walking only
            // the arguments checked the *bound* expression and silently skipped everything after the binding: a `where`
            // demand there was never made and a `where`-bearing def referenced there was never rejected — the very hole
            // the [[MonomorphicExpression.FunctionLiteral]] arm below exists to close, reappearing one level up. Its
            // records are discarded on the same grounds as that arm's (this node is a ⊤ boundary either way); only the
            // walk's `checkWhere`/rejection effects remain.
            for {
              _          <- walkFlow(head)
              argResults <- args.traverse(walkFlow)
            } yield Flow(None, argResults.flatMap(_.records) ++ recordAt(node, None))
        }

      case MonomorphicExpression.FunctionLiteral(_, _, body) =>
        // A lambda body must not *record* narrow intervals: its `apply` bridge would `CHECKCAST` a narrowed result back
        // to the ⊤/bignum representation the caller expects, so it stays a bignum boundary for representation (Step
        // 6-iii). But it must still be *walked*, so a `where` precondition on a call inside it is demanded (a def's own
        // parameters make its body a leading lambda, so without this every call in a parametered def would escape the
        // check — the §4.3 use-site verification must not have that hole). The records are discarded; only `checkWhere`'s
        // effects during the walk remain.
        walkFlow(body).as(Flow(None, recordAt(node, None)))

      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs, _) =>
        // A **bare** reference to a def — *not* the head of a full application (that path is the `FunctionApplication`
        // arm's `checkWhere`). If the def carries a `where` precondition, passing it as a value silently bypasses that
        // precondition: its eventual call rides a function value whose head the channel never sees as a
        // `MonomorphicValueReference`, so the demand is made nowhere (`docs/refinement-channel-follow-ups.md` §2.1).
        // Reject it loudly — the Use-Site Verification cornerstone requires every manifest use to be checked.
        //
        // Its meta is asked for on the same terms as a call's, because for a **nullary** def that is exactly what this
        // is: a saturated call, spelled without parentheses. A reference that is *not* saturated is a function value,
        // whose type carries no meta, so the same question answers ⊤ there without a special case.
        for {
          _    <- rejectWhereAsValueIfBearing(node, vfqn.value)
          meta <- MetaInterpreter.metaOfCall(vfqn.value, typeArgs, node.value.expressionType, Seq.empty)
        } yield Flow(meta, recordAt(node, meta))

      case _ =>
        // A parameter reference: ⊤ (nothing is known about this node's value here).
        Flow(None, recordAt(node, None)).pure[CompilerIO]
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

  /** Demand a callee's `where` precondition (bounds-as-refinements §4.3) at this call site, when it declares one. A def
    * `def f(x: Int): T where withinByte(range(x))` desugars to a `^Where` companion `f$Where(x: Int$Meta): Bool =
    * withinByte(range(x))` ([[MetaWhereDesugarer]]); at a *full* call to `f` that companion is reduced on the
    * compiler track and evaluated over the arguments' channel intervals. The demand is discharged only when every
    * argument's range is known and the predicate reduces to `true`; an unknown (⊤) argument range or a `false` result is
    * a hard error at the call — the use-site verification the cornerstone prescribes. A partial application is not yet a
    * call (left for the full application); a callee that declares no companion has no `where`. The presence of a
    * companion is a cheap per-module [[UnifiedModuleNames]] membership test, so an ordinary call to a `where`-free callee
    * costs one cached lookup and never demands a non-existent companion fact.
    */
  private def checkWhere(
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      appliedArgs: Int,
      argMetas: Seq[Option[GroundValue]]
  ): CompilerIO[Unit] =
    hasWhereCompanion(callee).flatMap {
      case false => ().pure[CompilerIO]
      case true  =>
        getFactIfProduced(MonomorphicValue.Key(callee, calleeTypeArgs)).flatMap {
          case Some(mv) =>
            // The companion takes the declared parameters: a further variant's block (`docs/effects.md` D35 step 5a)
            // is not one of them.
            val copies   = MonomorphicEffects.copyPositions(mv.effects.slots)
            val declared = argMetas.zipWithIndex.collect { case (meta, index) if !copies.contains(index) => meta }
            val applied  = appliedArgs - copies.count(_ < appliedArgs)
            mv.naturalArity.map(_ - copies.size) match {
              case Some(arity) if arity > 0 && applied >= arity =>
                demandPrecondition(callNode, callee, declared.take(arity))
              case Some(arity) if arity > 0                         =>
                // A *partial* application of a `where`-bearing def is a function value with an undemandable precondition
                // — the same escape as a bare reference (§2.1). Reject it loudly rather than let the partial value flow
                // to a call the channel cannot see.
                rejectWhereAsValue(callNode, callee)
              case _                                                => ().pure[CompilerIO]
            }
          case None     => ().pure[CompilerIO]
        }
    }

  /** Whether `callee` declares a `where` precondition — a cached [[UnifiedModuleNames]] membership test for its
    * [[whereCompanionName]] in the compiler pool (where [[MetaWhereDesugarer]] emits the `^Where` companion).
    */
  private def hasWhereCompanion(callee: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler))
      .map(_.names.contains(whereCompanionName(callee)))

  private def rejectWhereAsValueIfBearing(node: Sourced[MonomorphicExpression], callee: ValueFQN): CompilerIO[Unit] =
    hasWhereCompanion(callee).ifM(rejectWhereAsValue(node, callee), ().pure[CompilerIO])

  /** The §2.1 fail-safe: a reference to a `where`-bearing def that is not the head of a full application — a bare value
    * reference or a partial application — is a use whose precondition can never be demanded. Reject it with a loud,
    * conservative error rather than silently accept the escape. (Lifting the restriction later would need value-level
    * tracking of the precondition through the function value, which the channel deliberately does not have.)
    */
  private def rejectWhereAsValue(node: Sourced[MonomorphicExpression], callee: ValueFQN): CompilerIO[Unit] =
    Sourced.compilerError(
      node.as(s"A def with a `where` precondition ('${callee.show}') cannot be passed as a value."),
      Seq("Call it directly with all its arguments so its precondition is checked at the use site.")
    )

  /** Evaluate a resolved `^Where` companion over the call's argument metas and turn the verdict into a use-site error or
    * a pass. Every argument's meta must be known (⊤ cannot discharge a demand — the fail-safe of §4.3); the predicate
    * then reduces (through the one NbE evaluator, over the arguments' meta values) to a `Bool`: `true` passes, `false` is
    * a violation, and a non-`Bool` result (an unsupported predicate shape) fails loudly rather than silently accepting.
    *
    * The diagnostics speak of *meta-information* rather than of a value range: the channel is domain-agnostic, and since
    * `String` gained its `size` slot a `where` is as likely to be about a string's length as about an integer's range.
    * Naming the `Int` domain here would misdescribe half the failures (`docs/string-length-meta.md`).
    */
  private def demandPrecondition(
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      argMetas: Seq[Option[GroundValue]]
  ): CompilerIO[Unit] =
    argMetas.sequence match {
      case None        =>
        Sourced.compilerError(
          callNode.as(
            s"Cannot prove the precondition of '${callee.show}': an argument's meta-information is not known here."
          ),
          Seq(
            "A `where` precondition demands provable meta-information — pass a value the compiler can pin, such as a literal."
          )
        )
      case Some(metas) =>
        // Reduce the `^Where` companion over the argument metas through the escalating linker-executor (same executor as
        // the transfer/merge path), then read the verdict: `true` passes, `false` is a violation, and anything else — a
        // non-`Bool` predicate shape, or a companion that did not reduce — fails loudly rather than silently accepting.
        EscalatingReducer.reduceApplied(whereCompanionFqn(callee), Seq.empty, metas.map(Evaluator.groundToSem)).flatMap {
          case Some(gv) if isBoolTrue(gv)  => ().pure[CompilerIO]
          case Some(gv) if isBoolFalse(gv) =>
            Sourced.compilerError(
              callNode.as(s"The precondition of '${callee.show}' is not satisfied by the argument's meta-information.")
            )
          case _                           =>
            Sourced.compilerError(callNode.as(s"Cannot evaluate the `where` precondition of '${callee.show}'."))
        }
    }

  private def isBoolTrue(gv: GroundValue): Boolean = gv match {
    case GroundValue.Direct(Literal.BooleanValue(true), _) => true
    case _                           => false
  }

  private def isBoolFalse(gv: GroundValue): Boolean = gv match {
    case GroundValue.Direct(Literal.BooleanValue(false), _) => true
    case _                            => false
  }

}

object RefinementChannelProcessor {

  /** A callee's `^Meta` companion name: its own name in the meta namespace of its own qualifier, named by the
    * desugarer that emits it ([[MetaTransferDesugarer.companionName]]). `fold` ⤳ `fold^Meta` (merge), the
    * `Numeric[Int]` `add` ⤳ `add^Meta(Numeric#Int…)` (transfer) — the ability-implementation callee keeps its
    * `(ability, pattern)` key, so its transfer is that instance's and not a same-named sibling's
    * (`docs/total-meta-transfers.md` §8.1). The channel recognises a refinement operation *only* by the presence of
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
