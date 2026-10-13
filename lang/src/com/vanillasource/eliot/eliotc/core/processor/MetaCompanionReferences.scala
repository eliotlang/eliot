package com.vanillasource.eliot.eliotc.core.processor

import com.vanillasource.eliot.eliotc.ast.fact.Expression as SourceExpression
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Which of a definition's parameters a **meta companion**'s body actually mentions — the test that decides whether
  * the companion retypes a parameter to its meta structure `T$Meta` ([[MetaTransferDesugarer]],
  * [[MetaWhereDesugarer]]).
  *
  * The retyping is a name transform with no lookup (`Int` ⤳ `Int$Meta`), and `T$Meta` exists only for a **slotted**
  * type — a slotless type's meta is the trivial `Unit`, which the channel supplies rather than a generated structure
  * ([[MetaConstructorDesugarer]]). So retyping every parameter fails on the first definition that carries a brace over
  * an untracked parameter:
  *
  * {{{
  *   private def outcomeExitCode(outcome: ProcessOutcome): Int {closed(0, 255)}
  *     ⟶  outcomeExitCode^Meta(outcome: ProcessOutcome$Meta): Int$Meta = …   // "Name not defined."
  * }}}
  *
  * A parameter the body never mentions is **dead in the companion**, so its type is irrelevant there and is kept
  * verbatim — the rule a generic companion already relies on for its concrete pass-through parameters (`fold`'s
  * `condition: Bool`, which needs no non-existent `Bool$Meta`). A parameter the body *does* mention is retyped as
  * before, so a projection like `size(s)` still reads a meta structure, and a projection over a genuinely untracked
  * parameter still fails loudly rather than being silently accepted.
  */
object MetaCompanionReferences {

  /** Every bare name applied or referenced anywhere in `expressions` — an over-approximation (a name shadowed by a
    * lambda parameter counts too), which is the safe direction: an extra name only retypes a parameter that was already
    * being retyped before this rule existed.
    */
  def namesIn(expressions: Seq[Sourced[SourceExpression]]): Set[String] =
    expressions.flatMap(expression => namesOf(expression.value)).toSet

  private def namesOf(expression: SourceExpression): Seq[String] = expression match {
    case SourceExpression.FunctionApplication(_, functionName, genericArguments, arguments) =>
      functionName.value +: (genericArguments.toSeq.flatten ++ arguments).flatMap(argument => namesOf(argument.value))
    case SourceExpression.FunctionLiteral(_, body)                                          => namesOf(body.value)
    case SourceExpression.FlatExpression(parts)                                             =>
      parts.flatMap(part => namesOf(part.value))
    case SourceExpression.MatchExpression(scrutinee, cases)                                 =>
      namesOf(scrutinee.value) ++ cases.flatMap(matchCase => namesOf(matchCase.body.value))
    case SourceExpression.BlockExpression(lines)                                            =>
      lines.flatMap(line => namesOf(line.expression.value))
    case SourceExpression.EffectfulType(_, resultType)                                      => namesOf(resultType.value)
    case SourceExpression.WithBinding(subject, _)                                           => namesOf(subject.value)
    case _                                                                                  => Seq.empty
  }
}
