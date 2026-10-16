package com.vanillasource.eliot.eliotc.matchdesugar.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.ImplementationMarkerUtils
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, Pattern}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

object MatchDesugarUtils {

  def wrapExpr(src: Sourced[?], expr: Expression): Sourced[Expression] =
    src.as(expr)

  def bindingName(pattern: Pattern): Option[Sourced[String]] =
    pattern match {
      case Pattern.VariablePattern(name)   => Some(name)
      case Pattern.WildcardPattern(source) => Some(source)
      case _                               => None
    }

  def buildCurriedCall(
      scrutinee: Sourced[Expression],
      ref: Expression,
      args: Seq[Sourced[Expression]]
  ): Expression =
    args.foldLeft(ref) { (acc, arg) =>
      Expression.FunctionApplication(scrutinee.as(acc), arg)
    }

  def firstConstructorPattern(pattern: Pattern): Option[ValueFQN] =
    pattern match {
      case Pattern.ConstructorPattern(ctor, _) => Some(ctor.value)
      case _                                   => None
    }

  def collectConstructorPatterns(pattern: Pattern): Seq[ValueFQN] =
    pattern match {
      case Pattern.ConstructorPattern(ctor, subs) =>
        ctor.value +: subs.flatMap(s => collectConstructorPatterns(s.value))
      case _                                      => Seq.empty
    }

  /** The method `methodName` of the implementation of `abilityName` whose pattern is the data type `dataTypeName` —
    * the `PatternMatch`/`TypeMatch` implementation `data` desugars to. Only **anonymous** implementations are candidates,
    * as for the two-site search: a named one is reached only by `with`, so a user's `implement x: PatternMatch[…]` in
    * the data's module is never what a `match` runs (`docs/effects.md` §8 item 55).
    */
  def findAbilityMethodImpl(
      errorSource: Sourced[?],
      moduleName: ModuleName,
      abilityName: String,
      methodName: String,
      platform: Platform,
      dataTypeName: String
  ): CompilerIO[ValueFQN] =
    for {
      impls    <- getFactOrAbort(ModuleAbilities.Key(moduleName, platform))
      selected <- impls
                    .anonymousImplementationMethodsOf(abilityName, methodName)
                    .findM(vfqn =>
                      ImplementationMarkerUtils
                        .firstPatternTypeConstructorName(moduleName, abilityName, vfqn.name.qualifier, platform)
                        .map(_.contains(dataTypeName))
                    )
      result   <- selected match
                    case Some(vfqn) => vfqn.pure[CompilerIO]
                    case None       =>
                      compilerAbort(
                        errorSource.as(s"No $abilityName.$methodName implementation found in module $moduleName.")
                      )
    } yield result
}
