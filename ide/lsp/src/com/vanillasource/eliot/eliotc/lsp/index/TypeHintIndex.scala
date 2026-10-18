package com.vanillasource.eliot.eliotc.lsp.index

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.core.processor.MetaConstructorDesugarer
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal
import com.vanillasource.eliot.eliotc.lsp.virtual.VfsUris
import com.vanillasource.eliot.eliotc.pos.{Position, PositionRange}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import java.net.URI
import java.nio.file.Paths

/** Position → concrete type index, the data behind hover type hints.
  *
  * Unlike [[PositionIndex]] (which renders a referenced value's *declared* signature), this index reports the actual
  * *monomorphic* type each expression node was checked at — the per-node `expressionType` produced by the NbE checker.
  * That information only exists after monomorphization, which is driven from a `main`: the
  * [[com.vanillasource.eliot.eliotc.lsp.plugin.LspPlugin]] demands [[com.vanillasource.eliot.eliotc.used.UsedNames]]
  * for every file that declares its own `main`, forcing a [[MonomorphicValue]] for every reachable instantiation. This
  * index is rebuilt from those facts after each compile.
  *
  * Because a generic value reachable at several instantiations yields several [[MonomorphicValue]]s sharing source
  * ranges, a single node range can carry more than one type; a lookup returns the distinct set at the most specific
  * (innermost) node containing the queried position. Positions are in the compiler's 1-based domain; the protocol layer
  * converts from LSP's 0-based positions before querying.
  */
final class TypeHintIndex private (hintsByUri: Map[String, Seq[TypeHintIndex.Hint]]) {
  import TypeHintIndex.*

  /** The distinct monomorphic type(s) at the most specific expression node containing `position`, paired with that
    * node's range, or `None` if no typed node covers the position. More than one type means the enclosing definition
    * was monomorphized at several instantiations.
    */
  def typeHintsAt(uri: URI, position: Position): Option[(PositionRange, Seq[GroundValue])] = {
    val containing = hintsByUri.getOrElse(uriKey(uri), Seq.empty).filter(hint => contains(hint.range, position))
    containing
      .reduceOption((a, b) => if (moreSpecific(a.range, b.range)) a else b)
      .map(innermost => innermost.range -> containing.filter(_.range == innermost.range).map(_.tpe).distinct)
  }

  /** The refinement channel's value range for the node at exactly `range` in `uri`, or `None` when the channel could
    * not pin one (a ⊤/bignum node — a parameter, a call result, anything outside a
    * literal/arithmetic-transfer/`if`-join spine). When the enclosing definition was monomorphized at several
    * instantiations that pinned *different* ranges at this node, their **union** `[min of mins, max of maxes]` is
    * returned — the sound over-approximation to display. Callers pass the `range` [[typeHintsAt]] returned for the same
    * position, so no new position math is needed.
    */
  def intervalAt(uri: URI, range: PositionRange): Option[(BigInt, BigInt)] = {
    val intervals =
      hintsByUri.getOrElse(uriKey(uri), Seq.empty).filter(_.range == range).flatMap(_.interval)
    Option.when(intervals.nonEmpty)(intervals.map(_._1).min -> intervals.map(_._2).max)
  }
}

object TypeHintIndex {

  /** One typed node: a source range, the concrete type the checker assigned to the expression occupying it, and — when
    * the refinement channel could pin one — the value's range interval `[min, max]` at that node.
    */
  final case class Hint(uri: URI, range: PositionRange, tpe: GroundValue, interval: Option[(BigInt, BigInt)])

  val empty: TypeHintIndex = new TypeHintIndex(Map.empty)

  /** Build the index from all monomorphized values materialised by a compile, grouping the typed nodes per document.
    * Each value's [[RefinementTable]] (keyed identically to its [[MonomorphicValue]]) supplies the per-node value-range
    * intervals, joined here by position so a hover can show the range the channel computed.
    */
  def build(values: Seq[MonomorphicValue], refinements: Seq[RefinementTable]): TypeHintIndex = {
    val intervalsByKey = refinements.map(rt => (rt.vfqn, rt.typeArguments) -> unambiguousIntervals(rt)).toMap
    new TypeHintIndex(
      values
        .flatMap(value => hintsOf(value, intervalsByKey.getOrElse((value.vfqn, value.typeArguments), Map.empty)))
        .groupBy(hint => uriKey(hint.uri))
    )
  }

  /** Collapse a table's per-node verdicts to a position-keyed `[min, max]` map, keeping a position only when every
    * entry at it agrees on one verdict: a position carrying two or more distinct metas — or a meta and a ⊤, which is
    * what a desugar-synthesized node sharing a real node's source range produces — is dropped rather than displayed
    * ambiguously. The channel carries an opaque domain meta; the hover (which shows `Int` value ranges) decodes the
    * `Int$Meta(Interval(min, max))` shape with [[boundsOf]], exactly as the JVM backend does for width selection.
    */
  private def unambiguousIntervals(table: RefinementTable): Map[PositionRange, (BigInt, BigInt)] =
    table.metas
      .groupBy(_.position)
      .collect { case (position, entries) if entries.map(_.meta).distinct.sizeIs == 1 => position -> entries.head.meta }
      .flatMap { case (position, meta) => meta.flatMap(boundsOf).map(position -> _) }

  /** Decode `[min, max]` from an `Int$Meta(Interval(Bounded(min), Bounded(max)))` meta value — the value-range
    * domain's shape. [[None]] for any other meta (a half-open interval bounded on one side only, of which the
    * domain's stated top `Int$Meta(whole)` is the twice-over case), which is simply not shown as a range.
    *
    * The [[isIntMeta]] gate is what keeps that promise for a *second* domain, and it is not optional: a `String`'s size
    * meta (`String$Meta(Interval(Bounded(5), Bounded(5)))` for `"hello"`) has exactly the same shape, so without the
    * gate the hover would report a five-character string as having the *value range* `5..5`. Every domain this hover
    * can render is one it must name; the JVM backend gates the identical decode by the node's type instead
    * (`IntRepresentation.isIntegerType`). Recognising a domain by name is sanctioned in a *renderer* precisely because
    * a mistake here is cosmetic (`docs/string-length-meta.md` §3.3).
    */
  private def boundsOf(meta: GroundValue): Option[(BigInt, BigInt)] =
    meta match {
      case GroundValue.Structure(fqn, Seq(GroundValue.Structure(_, Seq(lo, hi), _)), _) if isIntMeta(fqn) =>
        (boundedBigInt(lo), boundedBigInt(hi)).tupled
      case _ => None
    }

  /** The meta structure of the tracked `Int` — `Int$Meta` in `eliot.lang.Int`, the only domain this hover renders. The
    * qualifier is ignored (a constructed structure's head may carry either the type or the value namespace).
    */
  private def isIntMeta(fqn: ValueFQN): Boolean =
    fqn.moduleName == intModuleName && fqn.name.name == intMetaTypeName

  private val intModuleName: ModuleName = ModuleName(ModuleName.defaultSystemPackage, "Int")

  private val intMetaTypeName: String = "Int" + MetaConstructorDesugarer.metaTypeSuffix

  /** The big integer inside a `Bounded(Direct(n))` endpoint, or [[None]] for an `Unbounded` one (a nullary structure,
    * so it never matches the single-argument shape) or any other value.
    */
  private def boundedBigInt(gv: GroundValue): Option[BigInt] = gv match {
    case GroundValue.Structure(_, Seq(GroundValue.Direct(Literal.IntegerValue(value), _)), _) => Some(value)
    case _                                                                                    => None
  }

  /** Every typed node of one monomorphized value. Compound bodies (an application or a function literal) are fully
    * described by their child nodes, which carry their own precise types and ranges; annotating the whole-body range
    * with the signature would only collide with those children (the outermost node's range coincides with a child's).
    * Only a *leaf* body — a bare value reference, literal, or parameter — has no child node to carry its type, so for
    * those the body range is annotated with the value's signature (e.g. `main = greeting` ⇒ `greeting` reads
    * `IO[Unit]`).
    */
  private def hintsOf(value: MonomorphicValue, intervals: Map[PositionRange, (BigInt, BigInt)]): Seq[Hint] =
    value.runtime.toSeq.flatMap { body =>
      val topHint = body.value match {
        case _: MonomorphicExpression.FunctionApplication | _: MonomorphicExpression.FunctionLiteral => Seq.empty
        case _                                                                                       => Seq(Hint(body.uri, body.range, value.signature, intervals.get(body.range)))
      }
      topHint ++ fromExpression(body.value, intervals)
    }

  /** A node's hint is the type the user wrote: a **handle** (`docs/effects.md` D36) is how an effect reaches its use,
    * not something the source spells, so a node of a handle's type — a hole, a `with`'s handle — has none, and a
    * handle parameter is left off the front of a function type: `printLine` reads `String -> Unit`, and a block its
    * callee hands a handle reads as the value it computes.
    */
  private def fromNode(
      node: Sourced[MonomorphicExpression],
      intervals: Map[PositionRange, (BigInt, BigInt)]
  ): Seq[Hint] =
    Option
      .unless(isHandle(node.value.expressionType) || appliesHandle(node.value.expression))(
        // A reference is hinted at its name: the call it heads may give its node the whole call's range.
        node.value.expression match {
          case MonomorphicExpression.MonomorphicValueReference(name, _, _) =>
            Hint(node.uri, name.range, withoutHandles(node.value.expressionType), intervals.get(name.range))
          case _                                                          =>
            Hint(node.uri, node.range, withoutHandles(node.value.expressionType), intervals.get(node.range))
        }
      )
      .toSeq ++ fromExpression(node.value.expression, intervals)

  /** An application handing over a handle: what it computes is its target's, which the target's hint shows. */
  private def appliesHandle(expression: MonomorphicExpression.Expression): Boolean = expression match {
    case MonomorphicExpression.FunctionApplication(_, argument) => isHandle(argument.value.expressionType)
    case _                                                      => false
  }

  private def isHandle(tpe: GroundValue): Boolean = tpe match {
    case GroundValue.Structure(fqn, _, _) => Handles.isDataType(fqn.name.name)
    case _                                => false
  }

  private def withoutHandles(tpe: GroundValue): GroundValue =
    tpe.asFunctionType match {
      case Some((domain, codomain)) if isHandle(domain) => withoutHandles(codomain)
      case _                                            => tpe
    }

  private def fromExpression(
      expression: MonomorphicExpression.Expression,
      intervals: Map[PositionRange, (BigInt, BigInt)]
  ): Seq[Hint] = expression match {
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      fromNode(target, intervals) ++ fromNode(argument, intervals)
    case MonomorphicExpression.FunctionLiteral(_, _, body)           => fromNode(body, intervals)
    case _: MonomorphicExpression.MonomorphicValueReference | _: MonomorphicExpression.IntegerLiteral |
        _: MonomorphicExpression.StringLiteral | _: MonomorphicExpression.ParameterReference =>
      Seq.empty
  }

  /** Inclusive containment in the compiler's 1-based domain (the end is included so a caret immediately after a node
    * still matches), mirroring [[PositionIndex]].
    */
  private def contains(range: PositionRange, position: Position): Boolean =
    range.from <= position && position <= range.to

  /** Whether the first range is more specific (more deeply nested) than the second: it starts later, or starts together
    * but ends sooner.
    */
  private def moreSpecific(a: PositionRange, b: PositionRange): Boolean =
    a.from > b.from || (a.from === b.from && a.to < b.to)

  /** Normalise a URI to a stable key so the editor's `file:///…` URIs match the compiler's `file:/…` URIs, as in
    * [[PositionIndex]]. Non-file URIs fall back to their string form and simply never match a workspace document.
    */
  private def uriKey(uri: URI): String =
    try Paths.get(VfsUris.toFileUri(uri)).toString
    catch { case _: IllegalArgumentException | _: java.nio.file.FileSystemNotFoundException => uri.toString }
}
