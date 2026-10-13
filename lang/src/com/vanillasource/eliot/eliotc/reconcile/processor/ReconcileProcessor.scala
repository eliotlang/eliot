package com.vanillasource.eliot.eliotc.reconcile.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.defaultSystemPackage
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.reconcile.fact.{ReconciledMonomorphicExpression, ReconciledMonomorphicValue}
import com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.uncurry.fact.{UncurriedMonomorphicExpression, UncurriedMonomorphicValue}

/** The representation-reconcile pass (`docs/bounds-as-refinements.md`): a platform-generic post-pass over
  * [[UncurriedMonomorphicValue]] that **stamps** each body node with its refinement-channel meta, so the backend can
  * lay out each `Int` from the range with no width policy of its own.
  *
  * It does exactly one thing: read the instance's [[RefinementTable]] and attach the channel's per-node interval (as an
  * `Interval` [[GroundValue]]) onto every node whose value range the channel pinned; a ⊤/unknown node gets [[None]]
  * (the backend lays it out as a bignum). It inserts **no** conversion nodes and knows **no** width or representation:
  * width selection *and* the re-encodes at meta-change edges (a call argument's ⊤ parameter boundary, a `fold` arm's
  * merged width, the method return) are both derived by the backend from these per-node ranges + the descriptors — see
  * `docs/generic-refinement-merges.md`. So this pass names no construct (not an arithmetic leaf, not a branch) and
  * carries no `widthTransparentLeaves`: the backend keeps arithmetic operands at their own width simply by reading each
  * operand's stamped meta.
  */
class ReconcileProcessor
    extends TransformationProcessor[UncurriedMonomorphicValue.Key, ReconciledMonomorphicValue.Key](key =>
      UncurriedMonomorphicValue.Key(key.vfqn, key.typeArguments, key.arity)
    )
    with Logging {

  import ReconcileProcessor.*

  override protected def generateFromKeyAndFact(
      key: ReconciledMonomorphicValue.Key,
      umv: UncurriedMonomorphicValue
  ): CompilerIO[ReconciledMonomorphicValue] =
    for {
      // Read or abort: the channel declines its table exactly when it reported an error — a failing `where` among them —
      // and a body laid out at ⊤ in its place compiled, leaving a runnable jar behind a failed build.
      refinementTable <- getFactOrAbort(RefinementTable.Key(key.vfqn, key.typeArguments))
      metas            = metaByPosition(refinementTable.metas)
      reconciledBody   = umv.body.map(b => walk(b.as(UncurriedMonomorphicExpression(umv.returnType, b.value)), metas))
    } yield ReconciledMonomorphicValue(
      vfqn = key.vfqn,
      typeArguments = key.typeArguments,
      arity = key.arity,
      name = umv.name,
      signature = umv.signature,
      parameters = umv.parameters,
      returnType = umv.returnType,
      body = reconciledBody
    )

  /** Walk one uncurried node into its reconciled form: stamp its channel meta and recurse structurally. Pure — no fact
    * reads, no conversion nodes (the backend derives conversions from the stamped metas).
    */
  private def walk(
      node: Sourced[UncurriedMonomorphicExpression],
      metas: Map[PositionRange, GroundValue]
  ): Sourced[ReconciledMonomorphicExpression] = {
    val own            = metas.get(node.range)
    val expressionType = node.value.expressionType
    val reconciled     = node.value.expression match {
      case UncurriedMonomorphicExpression.IntegerLiteral(value)          => IntegerLiteral(value)
      case UncurriedMonomorphicExpression.StringLiteral(value)           => StringLiteral(value)
      case UncurriedMonomorphicExpression.ParameterReference(name)       => ParameterReference(name)
      case UncurriedMonomorphicExpression.MonomorphicValueReference(v, t) => MonomorphicValueReference(v, t)
      case UncurriedMonomorphicExpression.FunctionLiteral(params, body)  => FunctionLiteral(params, walk(body, metas))
      case UncurriedMonomorphicExpression.FunctionApplication(t, args)   =>
        FunctionApplication(walk(t, metas), args.map(walk(_, metas)))
    }
    node.as(ReconciledMonomorphicExpression(own, expressionType, reconciled))
  }
}

object ReconcileProcessor {

  /** Collapse the channel's per-node verdicts to a position-keyed map, keeping a position only when every entry at it
    * agrees on **one** verdict, and that verdict is a pinned meta. A position carrying two distinct metas is dropped, and
    * so is one carrying a meta *and* a ⊤ — the shape a desugar-synthesized node produces by sharing its source range with
    * a real one (`BlockDesugaringProcessor` anchors a `val`'s lambda and application on the bound expression's range), in
    * which case neither node may take the other's meta. Both drops leave the node ⊤, which is always sound. The meta is
    * an opaque domain [[GroundValue]] the pass carries verbatim — it neither builds nor inspects it.
    */
  private[reconcile] def metaByPosition(entries: Seq[RefinementTable.NodeMeta]): Map[PositionRange, GroundValue] =
    entries
      .groupBy(_.position)
      .collect {
        case (position, es) if es.map(_.meta).distinct.sizeIs == 1 => position -> es.head.meta
      }
      .collect { case (position, Some(meta)) => position -> meta }
}
