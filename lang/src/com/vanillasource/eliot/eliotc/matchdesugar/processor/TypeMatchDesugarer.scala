package com.vanillasource.eliot.eliotc.matchdesugar.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.Qualifier
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, Pattern}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort
import MatchDesugarUtils.*

class TypeMatchDesugarer(context: MatchDesugarContext) {

  def desugar(
      scrutinee: Sourced[Expression],
      cases: Seq[Expression.MatchCase]
  )(using platform: Platform): CompilerIO[Expression] = {
    val constructorCases = cases.filter(_.pattern.value.isInstanceOf[Pattern.ConstructorPattern])
    val wildcardCase     = cases.find(c => bindingName(c.pattern.value).isDefined)

    wildcardCase match {
      case None     =>
        val Pattern.ConstructorPattern(ctorName, _) = constructorCases.head.pattern.value: @unchecked
        compilerAbort(ctorName.as("Type match must have a wildcard case."))
      case Some(wc) =>
        for {
          wildcardBody <- context.desugarSourced(wc.body, platform)
          handlers     <- constructorCases.traverse { ctorCase =>
                            val Pattern.ConstructorPattern(ctor, subPatterns) = ctorCase.pattern.value: @unchecked
                            for {
                              handler      <- context.buildPatternHandler(scrutinee, subPatterns, ctorCase.body, platform)
                              typeMatchFqn <- findAbilityMethodImpl(
                                                ctor,
                                                ctor.value.moduleName,
                                                "TypeMatch",
                                                "typeMatch",
                                                platform,
                                                ctor.value.name.name
                                              )
                            } yield (typeMatchFqn, handler)
                          }
        } yield chainTypeMatches(scrutinee, handlers, wildcardBody)
    }
  }

  /** Chain type match expressions left to right with the wildcard body as the innermost fallback. */
  private def chainTypeMatches(
      scrutinee: Sourced[Expression],
      cases: Seq[(ValueFQN, Sourced[Expression])],
      wildcardBody: Sourced[Expression]
  ): Expression = {
    val (typeMatchFqn, handler) = cases.head
    val elseLambdaBody          =
      if (cases.tail.isEmpty) wildcardBody
      else wrapExpr(scrutinee, chainTypeMatches(scrutinee, cases.tail, wildcardBody))
    val elseCase                = wrapExpr(scrutinee, Expression.FunctionLiteral(scrutinee.as("_"), None, elseLambdaBody))
    buildTypeMatchExpression(scrutinee, typeMatchFqn, handler, elseCase)
  }

  private def buildTypeMatchExpression(
      scrutinee: Sourced[Expression],
      typeMatchFqn: ValueFQN,
      handler: Sourced[Expression],
      elseCase: Sourced[Expression]
  ): Expression =
    buildCurriedCall(
      scrutinee,
      Expression.ValueReference(scrutinee.as(typeMatchFqn)),
      Seq(scrutinee, handler, elseCase)
    )
}

object TypeMatchDesugarer {

  def isTypeMatch(cases: Seq[Expression.MatchCase]): Boolean =
    cases
      .flatMap(c => firstConstructorPattern(c.pattern.value))
      .headOption
      .exists(_.name.qualifier == Qualifier.Type)
}
