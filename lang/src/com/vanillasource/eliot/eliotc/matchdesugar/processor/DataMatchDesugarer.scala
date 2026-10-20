package com.vanillasource.eliot.eliotc.matchdesugar.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleConstructors,
  ModuleName,
  QualifiedName,
  UnifiedModuleValue,
  ValueFQN
}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, Pattern}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort
import MatchDesugarUtils.*

class DataMatchDesugarer(context: MatchDesugarContext) {

  def desugar(
      scrutinee: Sourced[Expression],
      cases: Seq[Expression.MatchCase]
  )(using platform: Platform): CompilerIO[Expression] =
    for {
      moduleAndType                <- findConstructorModuleAndType(cases)
      (constructorModule, dataType) = moduleAndType
      allConstructors              <- findAllConstructors(constructorModule, dataType)
      _                            <- checkExhaustiveness(cases, allConstructors)
      handleCasesFqn               <-
        findAbilityMethodImpl(
          scrutinee,
          constructorModule,
          "PatternMatch",
          "handleCases",
          platform,
          dataType.name
        )
      orderedHandlers              <- buildOrderedHandlers(scrutinee, cases, allConstructors)
    } yield buildHandleCasesCall(scrutinee, handleCasesFqn, orderedHandlers)

  private def findConstructorModuleAndType(
      cases: Seq[Expression.MatchCase]
  )(using platform: Platform): CompilerIO[(ModuleName, QualifiedName)] =
    cases
      .flatMap(c => firstConstructorPattern(c.pattern.value))
      .headOption match {
      case Some(vfqn) =>
        for {
          umv      <- getFactOrAbort(UnifiedModuleValue.Key(vfqn, platform))
          dataType <- umv.namedValue.roleHint match {
                        case RoleHint.ValueConstructor(dt, _) => dt.pure[CompilerIO]
                        case _                                =>
                          compilerAbort(
                            umv.namedValue.qualifiedName.as("Pattern references non-constructor value.")
                          )
                      }
        } yield (vfqn.moduleName, dataType)
      case None       =>
        cases.headOption match {
          case Some(c) => compilerAbort(c.pattern.as("Match expression must have at least one constructor pattern."))
          case None    => abort
        }
    }

  private def findAllConstructors(
      moduleName: ModuleName,
      dataType: QualifiedName
  )(using platform: Platform): CompilerIO[Seq[ValueFQN]] =
    getFactOrAbort(ModuleConstructors.Key(moduleName, platform)).map(_.of(dataType))

  private def checkExhaustiveness(
      cases: Seq[Expression.MatchCase],
      allConstructors: Seq[ValueFQN]
  ): CompilerIO[Unit] = {
    val explicitConstructors = cases.flatMap { c =>
      c.pattern.value match {
        case Pattern.ConstructorPattern(ctor, _) => Some(ctor.value)
        case _                                   => None
      }
    }.toSet
    val hasWildcard          = cases.exists(c => bindingName(c.pattern.value).isDefined)
    val missing              = allConstructors.filterNot(explicitConstructors.contains)

    if (!hasWildcard && missing.nonEmpty) {
      val missingText = missing.map(_.name.name).mkString(", ")
      compilerAbort(cases.head.pattern.as(s"Non-exhaustive match. Missing constructors: $missingText."))
    } else {
      ().pure[CompilerIO]
    }
  }

  private def buildOrderedHandlers(
      scrutinee: Sourced[Expression],
      cases: Seq[Expression.MatchCase],
      allConstructors: Seq[ValueFQN]
  )(using Platform): CompilerIO[Seq[Sourced[Expression]]] = {
    val casesByConstructor: Map[ValueFQN, Seq[Expression.MatchCase]] =
      cases
        .flatMap { c =>
          c.pattern.value match {
            case Pattern.ConstructorPattern(ctor, _) => Some(ctor.value -> c)
            case _                                   => None
          }
        }
        .groupMap(_._1)(_._2)

    val wildcardCase = cases.find(c => bindingName(c.pattern.value).isDefined)

    allConstructors.traverse { ctorVfqn =>
      casesByConstructor.get(ctorVfqn) match {
        case Some(ctorCases) =>
          buildConstructorHandler(scrutinee, ctorVfqn, ctorCases)
        case None            =>
          wildcardCase match {
            case Some(wc) => buildWildcardHandler(scrutinee, ctorVfqn, wc)
            case None     => compilerAbort(scrutinee.as(s"No handler for constructor ${ctorVfqn.name.name}."))
          }
      }
    }
  }

  private def buildConstructorHandler(
      scrutinee: Sourced[Expression],
      ctorVfqn: ValueFQN,
      cases: Seq[Expression.MatchCase]
  )(using platform: Platform): CompilerIO[Sourced[Expression]] = {
    val Pattern.ConstructorPattern(_, ctorFields) = cases.head.pattern.value: @unchecked

    if (cases.size == 1 || ctorFields.isEmpty)
      context.buildPatternHandler(scrutinee, ctorFields, cases.head.body, platform)
    else
      buildMultiCaseHandler(scrutinee, ctorVfqn, cases, ctorFields.size)
  }

  private def buildMultiCaseHandler(
      scrutinee: Sourced[Expression],
      ctorVfqn: ValueFQN,
      cases: Seq[Expression.MatchCase],
      fieldCount: Int
  )(using Platform): CompilerIO[Sourced[Expression]] = {
    val freshNames = (0 until fieldCount).map(i => scrutinee.as(s"$$match_${ctorVfqn.name.name}_$i"))

    val fieldPatternRows: Seq[Seq[Sourced[Pattern]]] = cases.map { c =>
      val Pattern.ConstructorPattern(_, subs) = c.pattern.value: @unchecked
      subs
    }

    for {
      innerBody <- buildMultiCaseBody(scrutinee, freshNames, fieldPatternRows, cases.map(_.body))
      handler    = freshNames.foldRight(innerBody) { (name, body) =>
                     wrapExpr(scrutinee, Expression.FunctionLiteral(name, None, body))
                   }
    } yield handler
  }

  private def buildMultiCaseBody(
      scrutinee: Sourced[Expression],
      freshNames: Seq[Sourced[String]],
      patternRows: Seq[Seq[Sourced[Pattern]]],
      bodies: Seq[Sourced[Expression]]
  )(using platform: Platform): CompilerIO[Sourced[Expression]] = {
    val constructorColumnIdx = patternRows.head.indices.find { col =>
      patternRows.exists(_(col).value.isInstanceOf[Pattern.ConstructorPattern])
    }

    constructorColumnIdx match {
      case Some(colIdx) =>
        val fieldRef    = wrapExpr(scrutinee, Expression.ParameterReference(freshNames(colIdx)))
        val nestedCases = patternRows.zip(bodies).map { case (row, body) =>
          val bindingPairs = freshNames.zip(row).zipWithIndex.collect { case (pair, i) if i != colIdx => pair }
          Expression.MatchCase(row(colIdx), wrapWithBindings(scrutinee, bindingPairs, body))
        }
        context.desugarMatch(fieldRef, nestedCases, platform).map(wrapExpr(scrutinee, _))

      case None =>
        context.desugarSourced(wrapWithBindings(scrutinee, freshNames.zip(patternRows.head), bodies.head), platform)
    }
  }

  private def wrapWithBindings(
      scrutinee: Sourced[Expression],
      bindingPairs: Seq[(Sourced[String], Sourced[Pattern])],
      body: Sourced[Expression]
  ): Sourced[Expression] =
    bindingPairs.foldRight(body) { case ((freshName, pat), innerBody) =>
      pat.value match {
        case Pattern.VariablePattern(varName) if varName.value != freshName.value =>
          val lambda = Expression.FunctionLiteral(varName, None, innerBody)
          val app    = Expression.FunctionApplication(
            scrutinee.as(lambda),
            scrutinee.as(Expression.ParameterReference(freshName))
          )
          wrapExpr(scrutinee, app)
        case _                                                                    => innerBody
      }
    }

  private def buildWildcardHandler(
      scrutinee: Sourced[Expression],
      ctorVfqn: ValueFQN,
      wildcardCase: Expression.MatchCase
  )(using platform: Platform): CompilerIO[Sourced[Expression]] =
    for {
      umv     <- getFactOrAbort(UnifiedModuleValue.Key(ctorVfqn, platform))
      arity   <- umv.namedValue.roleHint match {
                   case RoleHint.ValueConstructor(_, fieldCount) => fieldCount.pure[CompilerIO]
                   case _                                        =>
                     compilerAbort(
                       umv.namedValue.qualifiedName.as("Wildcard handler built for non-constructor value.")
                     )
                 }
      handler <- context.buildPatternHandler(
                   scrutinee,
                   Seq.fill(arity)(scrutinee.as(Pattern.WildcardPattern(scrutinee.as("_")))),
                   wildcardCase.body,
                   platform
                 )
    } yield handler

  /** `handleCases(scrutinee, h1, …, hn)`: one handler per constructor, in declaration order, each the arm the author
    * wrote — code, at a `uses *` parameter of the eliminator (see DataDefinitionDesugarer).
    */
  private def buildHandleCasesCall(
      scrutinee: Sourced[Expression],
      handleCasesFqn: ValueFQN,
      handlers: Seq[Sourced[Expression]]
  ): Expression =
    buildCurriedCall(scrutinee, Expression.ValueReference(scrutinee.as(handleCasesFqn)), scrutinee +: handlers)

}
