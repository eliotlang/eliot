package com.vanillasource.eliot.eliotc.lsp.index

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.lsp.virtual.VfsUris
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.pos.{Position, PositionRange}
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, Pattern, QualifiedName, ResolvedValue}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import java.net.URI
import java.nio.file.Paths

/** Reverse position index: maps a source position back to the compilation fact at that position.
  *
  * The forward compiler works name → fact; position-based LSP features (go-to-definition, hover) need the inverse,
  * position → fact. This index is rebuilt from the workspace's [[ResolvedValue]] facts after every compile and collects
  * two things from each resolved value:
  *
  *   - every value/constructor reference (a `Sourced[ValueFQN]`) appearing in the value's body and type, with the
  *     precise source range of the referenced *name* — these are the navigable occurrences;
  *   - the value's own definition, keyed by its [[ValueFQN]] — the navigation target.
  *
  * A lookup finds the most specific (innermost) reference whose range contains the queried position, then resolves its
  * `ValueFQN` to the defining value. Positions are in the compiler's 1-based domain; the protocol layer converts from
  * LSP's 0-based positions before querying.
  */
final class PositionIndex private (
    referencesByUri: Map[String, Seq[Sourced[ValueFQN]]],
    definitions: Map[ValueFQN, ResolvedValue]
) {

  /** The most specific value/constructor reference whose source range contains `position` in the given document. */
  def referenceAt(uri: URI, position: Position): Option[Sourced[ValueFQN]] =
    referencesByUri
      .getOrElse(PositionIndex.uriKey(uri), Seq.empty)
      .filter(reference => PositionIndex.contains(reference.range, position))
      .reduceOption((a, b) => if (PositionIndex.moreSpecific(a.range, b.range)) a else b)

  /** The resolved value identified by a fully qualified name, or `None` if it is not in the workspace. */
  def definitionOf(fqn: ValueFQN): Option[ResolvedValue] = definitions.get(fqn)

  /** The definition site (the defining name's location) of whatever is referenced at `position`, for go-to-definition.
    */
  def definitionAt(uri: URI, position: Position): Option[Sourced[QualifiedName]] =
    referenceAt(uri, position).flatMap(reference => definitionOf(reference.value)).map(_.name)

  /** The reference at `position` paired with the value it resolves to, for hover. */
  def hoverAt(uri: URI, position: Position): Option[(Sourced[ValueFQN], ResolvedValue)] =
    referenceAt(uri, position).flatMap(reference => definitionOf(reference.value).map(reference -> _))
}

object PositionIndex {
  val empty: PositionIndex = new PositionIndex(Map.empty, Map.empty)

  /** Build the index from all resolved values in the workspace (typically the [[ResolvedValue]] facts materialised by a
    * compile). References are grouped per document so a lookup only scans the relevant file.
    */
  def build(values: Seq[ResolvedValue]): PositionIndex =
    new PositionIndex(
      values.flatMap(referencesOf).groupBy(reference => uriKey(reference.uri)),
      values.map(value => value.vfqn -> value).toMap
    )

  /** All value/constructor references appearing in a resolved value's body and type signature. */
  private def referencesOf(value: ResolvedValue): Seq[Sourced[ValueFQN]] =
    value.runtime.toSeq.flatMap(body => fromExpression(body.value)) ++ fromExpression(value.signature.value)

  private def fromExpression(expression: Expression): Seq[Sourced[ValueFQN]] = expression match {
    case Expression.ValueReference(name, typeArgs)                                                     =>
      name +: typeArgs.flatMap(typeArg => fromExpression(typeArg.value))
    case Expression.FunctionApplication(target, argument)                                              =>
      fromExpression(target.value) ++ fromExpression(argument.value)
    case Expression.FunctionLiteral(_, parameterType, body)                                            =>
      parameterType.toSeq.flatMap(pt => fromExpression(pt.value)) ++ fromExpression(body.value)
    case Expression.FlatExpression(parts)                                                              =>
      parts.flatMap(part => fromExpression(part.value))
    // A `with` binding is a reference like any other: the implementation's marker is where go-to-definition should
    // land, and the subject carries the rest.
    case Expression.WithBinding(subject, implementation, arguments)                                 =>
      implementation +: (fromExpression(subject.value) ++ arguments.flatMap(argument => fromExpression(argument.value)))
    case Expression.MatchExpression(scrutinee, cases)                                                  =>
      fromExpression(scrutinee.value) ++
        cases.flatMap(matchCase => fromPattern(matchCase.pattern.value) ++ fromExpression(matchCase.body.value))
    case Expression.BlockExpression(lines)                                                             =>
      lines.flatMap(line =>
        line.binderType.toSeq.flatMap(bt => fromExpression(bt.value)) ++ fromExpression(line.expression.value)
      )
    case _: Expression.IntegerLiteral | _: Expression.StringLiteral | _: Expression.ParameterReference =>
      Seq.empty
  }

  private def fromPattern(pattern: Pattern): Seq[Sourced[ValueFQN]] = pattern match {
    case Pattern.ConstructorPattern(constructor, subPatterns)    =>
      constructor +: subPatterns.flatMap(subPattern => fromPattern(subPattern.value))
    case _: Pattern.VariablePattern | _: Pattern.WildcardPattern =>
      Seq.empty
  }

  /** Inclusive containment in the compiler's 1-based domain (`to` is the position right after the last character, so
    * the end is included to allow a caret placed immediately after an identifier).
    */
  private def contains(range: PositionRange, position: Position): Boolean =
    range.from <= position && position <= range.to

  /** Whether the first range is more specific (more deeply nested) than the second: it starts later, or starts together
    * but ends sooner.
    */
  private def moreSpecific(a: PositionRange, b: PositionRange): Boolean =
    a.from > b.from || (a.from === b.from && a.to < b.to)

  /** Normalise a URI to a stable key so the editor's `file:///…` URIs match the compiler's `file:/…` URIs (the latter
    * produced by `java.io.File.toURI`). Since CP1.5 every source URI is a `file:` URI — the base and platform layers
    * are filesystem roots too, no longer `jar:` classpath resources — so this keys all of them by path; the catch is a
    * defensive fallback for any unexpected non-`file:` URI, which then simply never matches a workspace document.
    */
  private def uriKey(uri: URI): String =
    try Paths.get(VfsUris.toFileUri(uri)).toString
    catch { case _: IllegalArgumentException | _: java.nio.file.FileSystemNotFoundException => uri.toString }
}
