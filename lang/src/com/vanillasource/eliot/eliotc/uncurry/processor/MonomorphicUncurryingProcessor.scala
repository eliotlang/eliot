package com.vanillasource.eliot.eliotc.uncurry.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort
import com.vanillasource.eliot.eliotc.uncurry.fact.*
import com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.*

import scala.annotation.tailrec

/** Processor that uncurries monomorphic values to a specific arity.
  *
  * Input: [[WovenValue]] (the post-monomorphization woven view — the effects-as-channel codegen source; off the flag it
  * is the identity image of the `MonomorphicValue`, so this is unchanged) Output: UncurriedMonomorphicValue
  * (multi-parameter form with specified arity)
  */
class MonomorphicUncurryingProcessor
    extends TransformationProcessor[WovenValue.Key, UncurriedMonomorphicValue.Key](key =>
      WovenValue.Key(key.vfqn, key.typeArguments)
    )
    with Logging {

  override protected def generateFromKeyAndFact(
      key: UncurriedMonomorphicValue.Key,
      monomorphicValue: WovenValue
  ): CompilerIO[UncurriedMonomorphicValue] =
    for {
      _                               <- debug[CompilerIO](s"Uncurrying ${key.vfqn} (${key.typeArguments.size} type args) to ${key.arity}")
      (parameterTypes, returnType)    <- extractParameters(monomorphicValue.name, monomorphicValue.signature, key.arity)
      (parameterNames, convertedBody) <- monomorphicValue.runtime match {
                                           case Some(body) =>
                                             convertBody(body, key.arity)
                                           case None       =>
                                             (
                                               Range
                                                 .inclusive(1, key.arity)
                                                 .map(i => monomorphicValue.name.as("$p" + i)),
                                               None
                                             ).pure[CompilerIO]
                                         }
    } yield UncurriedMonomorphicValue(
      // Types are carried through *concrete* — no machine-representation lowering here anymore. Post-flag-day an `Int`
      // has no bounds in its type, so its width is meta-information the refinement channel tracks; the JVM backend
      // decodes it from that channel meta at codegen (`IntRepresentation`, via the reconcile pass), and a bare `Int`
      // descriptor maps to `java.math.BigInteger` (the ⊤ boundary width). The old `Represent[Interval]` / Phase-3
      // `RepresentationLowering` pass is gone (`docs/generic-refinement-merges.md` Step 7).
      vfqn = key.vfqn,
      typeArguments = key.typeArguments,
      arity = key.arity,
      name = monomorphicValue.name,
      signature = monomorphicValue.signature,
      parameters = parameterNames.zip(parameterTypes).map(MonomorphicParameterDefinition(_, _)),
      returnType = returnType,
      body = convertedBody.map(_.map(_.expression))
    )

  /** Extract parameter types from a function type GroundValue up to the specified arity. */
  private def extractParameters(
      name: Sourced[?],
      signature: GroundValue,
      arity: Int
  ): CompilerIO[(Seq[GroundValue], GroundValue)] =
    if (arity === 0) {
      (Seq.empty, signature).pure[CompilerIO]
    } else {
      signature.asFunctionType match {
        case Some((paramType, returnType)) =>
          extractParameters(name, returnType, arity - 1).map { (restParams, restReturn) =>
            (paramType +: restParams, restReturn)
          }
        case None                          =>
          compilerAbort(
            name.as("Could not extract parameters."),
            Seq(s"Remaining arity: $arity", s"Signature: ${signature.debugString}")
          )
      }
    }

  private def convertBody(
      expression: Sourced[MonomorphicExpression.Expression],
      arity: Int
  ): CompilerIO[(Seq[Sourced[String]], Option[Sourced[UncurriedMonomorphicExpression]])] =
    for {
      (parameterNames, body) <- stripLambdas(expression.value, arity, expression)
    } yield (parameterNames, Some(expression.as(convertExpression(body))))

  /** Strip lambda expressions from the body up to a maximum count. */
  private def stripLambdas(
      expression: MonomorphicExpression.Expression,
      arity: Int,
      sourced: Sourced[?]
  ): CompilerIO[(Seq[Sourced[String]], MonomorphicExpression)] =
    if (arity === 0) {
      // Use a dummy type for the remaining expression — it's re-computed during conversion
      (Seq.empty, MonomorphicExpression(GroundValue.Type, expression)).pure[CompilerIO]
    } else {
      expression match {
        case MonomorphicExpression.FunctionLiteral(parameterName, parameterType, body) =>
          for {
            (restParameters, restExpression) <- stripLambdas(body.value.expression, arity - 1, sourced)
          } yield (parameterName +: restParameters, restExpression)
        case _                                                                          =>
          compilerAbort(sourced.as("Could not strip enough parameters from expression."))
      }
    }

  /** Convert a MonomorphicExpression to UncurriedMonomorphicExpression. */
  private def convertExpression(expr: MonomorphicExpression): UncurriedMonomorphicExpression =
    expr.expression match {
      case MonomorphicExpression.IntegerLiteral(value)                              =>
        UncurriedMonomorphicExpression(expr.expressionType, UncurriedMonomorphicExpression.IntegerLiteral(value))
      case MonomorphicExpression.StringLiteral(value)                               =>
        UncurriedMonomorphicExpression(expr.expressionType, UncurriedMonomorphicExpression.StringLiteral(value))
      case MonomorphicExpression.ParameterReference(paramName)                      =>
        UncurriedMonomorphicExpression(expr.expressionType, UncurriedMonomorphicExpression.ParameterReference(paramName))
      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs)          =>
        UncurriedMonomorphicExpression(
          expr.expressionType,
          UncurriedMonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs)
        )
      case MonomorphicExpression.FunctionApplication(target, argument)              =>
        UncurriedMonomorphicExpression(expr.expressionType, convertApplication(target, argument))
      case MonomorphicExpression.FunctionLiteral(paramName, paramType, body)        =>
        UncurriedMonomorphicExpression(expr.expressionType, convertLambda(paramName, paramType, body))
    }

  /** Convert a function application, flattening nested applications. */
  private def convertApplication(
      target: Sourced[MonomorphicExpression],
      argument: Sourced[MonomorphicExpression]
  ): UncurriedMonomorphicExpression.Expression = {
    val (finalTarget, arguments) = flattenApplication(target, Seq(argument))
    val convertedTarget          = finalTarget.as(convertExpression(finalTarget.value))
    val convertedArgs            = arguments.map(se => se.as(convertExpression(se.value)))
    UncurriedMonomorphicExpression.FunctionApplication(convertedTarget, convertedArgs)
  }

  /** Flatten nested function applications into a single application with multiple arguments. */
  @tailrec
  private def flattenApplication(
      target: Sourced[MonomorphicExpression],
      arguments: Seq[Sourced[MonomorphicExpression]]
  ): (Sourced[MonomorphicExpression], Seq[Sourced[MonomorphicExpression]]) =
    target.value.expression match {
      case MonomorphicExpression.FunctionApplication(innerTarget, innerArg) =>
        flattenApplication(innerTarget, innerArg +: arguments)
      case _                                                                  =>
        (target, arguments)
    }

  /** Convert a function literal, flattening nested lambdas. */
  private def convertLambda(
      paramName: Sourced[String],
      paramType: GroundValue,
      body: Sourced[MonomorphicExpression]
  ): UncurriedMonomorphicExpression.Expression = {
    val firstParam          = MonomorphicParameterDefinition(paramName, paramType)
    val (params, finalBody) = flattenLambda(Seq(firstParam), body)
    UncurriedMonomorphicExpression.FunctionLiteral(params, body.as(convertExpression(finalBody.value)))
  }

  /** Flatten nested lambda expressions into a single lambda with multiple parameters. */
  @tailrec
  private def flattenLambda(
      parameters: Seq[MonomorphicParameterDefinition],
      body: Sourced[MonomorphicExpression]
  ): (Seq[MonomorphicParameterDefinition], Sourced[MonomorphicExpression]) =
    body.value.expression match {
      case MonomorphicExpression.FunctionLiteral(paramName, paramType, innerBody) =>
        flattenLambda(parameters :+ MonomorphicParameterDefinition(paramName, paramType), innerBody)
      case _                                                                       =>
        (parameters, body)
    }
}
