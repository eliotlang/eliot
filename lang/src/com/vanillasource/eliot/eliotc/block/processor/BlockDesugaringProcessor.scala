package com.vanillasource.eliot.eliotc.block.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Fixity
import com.vanillasource.eliot.eliotc.block.fact.BlockDesugaredValue
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, ResolvedValue}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerAbort, compilerError}

/** Lowers every `{ … }` block ([[Expression.BlockExpression]]) in a value's runtime body, producing a
  * [[BlockDesugaredValue]] the rest of the pipeline (match desugaring onward) consumes. This is the only place block
  * syntax is given meaning. It runs *after* resolution (so it can read each boundary part's declared fixity by FQN, the
  * same fixity the operator resolver will use) and *before* match desugaring. Two steps per block:
  *
  *   1. **merge** — the parser over-separates a block on every newline; adjacent lines one newline apart are re-joined
  *      when the boundary demands it (the upper line's last part is `Infix`/`Prefix`, or the lower line's first part is
  *      `Infix`/`Postfix`), never across a blank line and never into a following `val` binding.
  *   2. **lower** — `val x = e ; rest` ⟹ `(x -> rest)(e)` and a bare statement `e ; rest` ⟹ `(_ -> rest)(e)`, with the
  *      final line as the result. Effect threading then falls out of the existing auto-lift over these
  *      immediately-applied lambdas.
  */
class BlockDesugaringProcessor
    extends TransformationProcessor[ResolvedValue.Key, BlockDesugaredValue.Key](key =>
      ResolvedValue.Key(key.vfqn, key.platform)
    )
    with Logging {

  override protected def generateFromKeyAndFact(
      key: BlockDesugaredValue.Key,
      resolvedValue: ResolvedValue
  ): CompilerIO[BlockDesugaredValue] = {
    given Platform = resolvedValue.platform
    for {
      desugaredRuntime <- resolvedValue.runtime.traverse(desugar)
    } yield BlockDesugaredValue(
      resolvedValue.vfqn,
      resolvedValue.name,
      desugaredRuntime,
      resolvedValue.signature,
      resolvedValue.paramConstraints,
      resolvedValue.fixity,
      resolvedValue.precedence,
      resolvedValue.roleHint,
      resolvedValue.platform,
      resolvedValue.effectRow
    )
  }

  /** A line during merging: its binder (if any) and its accumulated flat parts. */
  private case class Merged(
      binderName: Option[Sourced[String]],
      binderType: Option[Sourced[Expression]],
      parts: Seq[Sourced[Expression]]
  )

  /** Walk the resolved expression tree, lowering any block and preserving every node's source position. */
  private def desugar(expr: Sourced[Expression])(using Platform): CompilerIO[Sourced[Expression]] =
    expr.value match {
      case Expression.BlockExpression(lines)            => desugarBlock(expr, lines)
      case Expression.FunctionApplication(target, arg)  =>
        (desugar(target), desugar(arg)).mapN((t, a) => expr.as(Expression.FunctionApplication(t, a)))
      case Expression.FunctionLiteral(pn, pt, body)     =>
        (pt.traverse(desugar), desugar(body)).mapN((rpt, rb) =>
          expr.as(Expression.FunctionLiteral(pn, rpt, rb))
        )
      case Expression.FlatExpression(parts)             =>
        parts.traverse(desugar).map(ps => expr.as(Expression.FlatExpression(ps)))
      case Expression.MatchExpression(scrutinee, cases) =>
        (
          desugar(scrutinee),
          cases.traverse(c => desugar(c.body).map(b => Expression.MatchCase(c.pattern, b)))
        ).mapN((s, cs) => expr.as(Expression.MatchExpression(s, cs)))
      case Expression.ValueReference(name, typeArgs)    =>
        typeArgs.traverse(desugar).map(tas => expr.as(Expression.ValueReference(name, tas)))
      case Expression.WithBinding(subject, impl) =>
        desugar(subject).map(s => expr.as(Expression.WithBinding(s, impl)))
      case _: Expression.IntegerLiteral | _: Expression.StringLiteral | _: Expression.ParameterReference =>
        expr.pure[CompilerIO]
    }

  private def desugarBlock(
      blockSourced: Sourced[Expression],
      lines: Seq[Expression.BlockLine]
  )(using Platform): CompilerIO[Sourced[Expression]] =
    for {
      desugaredLines <- lines.traverse(desugarLine)
      merged         <- mergeLines(desugaredLines)
      lowered        <- lowerLines(blockSourced, merged)
    } yield lowered

  private def desugarLine(line: Expression.BlockLine)(using Platform): CompilerIO[Expression.BlockLine] =
    for {
      expr <- desugar(line.expression)
      bt   <- line.binderType.traverse(desugar)
    } yield Expression.BlockLine(line.binderName, bt, expr)

  // ── Merge ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Re-join over-separated lines left to right. Accumulating the boundary as we go means a chain of continuations
    * (`foo` ↵ `.bar` ↵ `.baz`) collapses in a single pass, because each merge updates the running last/first parts.
    */
  private def mergeLines(lines: Seq[Expression.BlockLine])(using Platform): CompilerIO[Seq[Merged]] =
    lines.foldLeftM(Seq.empty[Merged]) { (acc, line) =>
      val current = Merged(line.binderName, line.binderType, partsOf(line.expression))
      acc.lastOption match {
        case Some(previous) =>
          canMerge(previous, current).map { merge =>
            if (merge) acc.init :+ previous.copy(parts = previous.parts ++ current.parts)
            else acc :+ current
          }
        case None           => (acc :+ current).pure[CompilerIO]
      }
    }

  private def canMerge(upper: Merged, lower: Merged)(using Platform): CompilerIO[Boolean] =
    if (lower.binderName.isDefined) false.pure[CompilerIO]                                  // never merge into a `val`
    else if (lower.parts.head.range.from.line - upper.parts.last.range.to.line != 1)
      false.pure[CompilerIO]                                                                // blank line never merges
    else
      for {
        upperLast  <- boundaryFixity(upper.parts.last)
        lowerFirst <- boundaryFixity(lower.parts.head)
      } yield demandsRightOperand(upperLast) || demandsLeftOperand(lowerFirst)

  /** The declared fixity of a boundary part: read by FQN for a bare value reference (an operator), else `Application`. */
  private def boundaryFixity(part: Sourced[Expression])(using platform: Platform): CompilerIO[Fixity] =
    part.value match {
      case Expression.ValueReference(fqn, _) => getFactOrAbort(ResolvedValue.Key(fqn.value, platform)).map(_.fixity)
      case _                                 => (Fixity.Application: Fixity).pure[CompilerIO]
    }

  private def demandsRightOperand(fixity: Fixity): Boolean = fixity match {
    case Fixity.Infix(_) | Fixity.Prefix => true
    case _                               => false
  }

  private def demandsLeftOperand(fixity: Fixity): Boolean = fixity match {
    case Fixity.Infix(_) | Fixity.Postfix => true
    case _                                => false
  }

  // ── Lower ─────────────────────────────────────────────────────────────────────────────────────────────────────

  private def lowerLines(blockSourced: Sourced[Expression], merged: Seq[Merged]): CompilerIO[Sourced[Expression]] =
    merged match {
      case Nil => compilerAbort(blockSourced.as("A block must contain at least one expression."))
      case _   =>
        merged.last.binderName match {
          case Some(name) =>
            compilerAbort(name.as("A block must end in an expression, not a binding."))
          case None       =>
            merged.traverse_(checkNoSelfReference).as(buildTower(merged))
        }
    }

  /** Build the lambda tower from the result line up: each preceding line wraps the accumulated continuation as
    * `(binder -> continuation)(line-expression)`, a statement using `_` as the discarded binder.
    */
  private def buildTower(merged: Seq[Merged]): Sourced[Expression] =
    merged.init.foldRight(expressionOf(merged.last)) { (line, continuation) =>
      val anchor    = Sourced.outline(line.parts)
      val paramName = line.binderName.getOrElse(anchor.as("_"))
      val lambda    =
        anchor.as(Expression.FunctionLiteral(paramName, line.binderType, continuation))
      anchor.as(Expression.FunctionApplication(lambda, expressionOf(line)))
    }

  private def checkNoSelfReference(line: Merged): CompilerIO[Unit] =
    line.binderName match {
      case Some(name) if line.parts.exists(p => referencesParameter(p.value, name.value)) =>
        compilerError(name.as(s"'${name.value}' is referenced in its own definition."))
      case _                                                                                        =>
        ().pure[CompilerIO]
    }

  // ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

  private def partsOf(expr: Sourced[Expression]): Seq[Sourced[Expression]] =
    expr.value match {
      case Expression.FlatExpression(parts) => parts
      case other                            => Seq(expr.as(other))
    }

  private def expressionOf(line: Merged): Sourced[Expression] =
    line.parts match {
      case Seq(single) => single
      case parts       => Sourced.outline(parts).as(Expression.FlatExpression(parts))
    }

  /** Whether `name` occurs as a free [[Expression.ParameterReference]] in `expr`, respecting shadowing by inner lambdas
    * of the same name. Used to reject a `val` whose right-hand side references its own binder.
    */
  private def referencesParameter(expr: Expression, name: String): Boolean = expr match {
    case Expression.ParameterReference(n)             => n.value == name
    case Expression.FunctionApplication(target, arg)  =>
      referencesParameter(target.value, name) || referencesParameter(arg.value, name)
    case Expression.FunctionLiteral(pn, pt, body)     =>
      pt.exists(p => referencesParameter(p.value, name)) || (pn.value != name && referencesParameter(body.value, name))
    case Expression.FlatExpression(parts)             => parts.exists(p => referencesParameter(p.value, name))
    case Expression.ValueReference(_, typeArgs)       => typeArgs.exists(ta => referencesParameter(ta.value, name))
    case Expression.MatchExpression(scrutinee, cases) =>
      referencesParameter(scrutinee.value, name) || cases.exists(c => referencesParameter(c.body.value, name))
    case Expression.WithBinding(subject, _)        => referencesParameter(subject.value, name)
    case Expression.BlockExpression(lines)            => lines.exists(l => referencesParameter(l.expression.value, name))
    case _: Expression.IntegerLiteral | _: Expression.StringLiteral => false
  }
}
