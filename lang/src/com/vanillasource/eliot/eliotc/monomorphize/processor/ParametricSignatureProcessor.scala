package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.Role
import com.vanillasource.eliot.eliotc.monomorphize.check.MarkerGuardSignature
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, ParametricSignature}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue

/** Checks a value's signature once with its binders as placeholders, producing its [[ParametricSignature]]: the same
  * compile-track check an instance's signature twin runs ([[CompilerMonomorphicTypeCheckProcessor.checkOnCompilerTrack]]),
  * at no type arguments, so every binder reads back as a [[GroundValue.Param]].
  *
  * Its signature is `None`, with no error reported, wherever substituting into the result might not be exactly what an
  * instance's own check reads back, and each instance is then checked as before:
  *   - the check fails or reports anything, which is what a binder something computes on does (`Box[I + 1]`'s `I + 1`
  *     is stuck, and a stuck neutral does not read back); its errors are not the program's, which never has a value at
  *     a placeholder, so they are dropped;
  *   - the check needed a fact at a placeholder argument, or one already in progress up the chain
  *     ([[PlaceholderRefusingProcess]]);
  *   - the signature was not read back by plain evaluation, or is a guard (`TypeStackLoop.Result.substitutable`): the
  *     escalation runs control effects and dispatches abilities, and with a binder a placeholder it can settle a guard
  *     to a definite verdict that is wrong for some instance; each instance decides its own.
  */
class ParametricSignatureProcessor
    extends TransformationProcessor[SaturatedValue.Key, ParametricSignature.Key](key =>
      SaturatedValue.Key(key.vfqn.copy(name = key.vfqn.name.copy(role = Role.Runtime)), Platform.Compiler)
    ) {

  override protected def generateFromKeyAndFact(
      key: ParametricSignature.Key,
      saturatedValue: SaturatedValue
  ): CompilerIO[ParametricSignature] =
    parametricSignature(MarkerGuardSignature.strippedForGuard(saturatedValue.value))
      .map(ParametricSignature(key.vfqn, _))

  /** The check at no type arguments, against a [[PlaceholderRefusingProcess]] and in an error context of its own, so
    * nothing it reports reaches this generation. `None` unless it ran clean and settled to an ordinary signature.
    */
  private def parametricSignature(value: OperatorResolvedValue): CompilerIO[Option[GroundValue]] =
    Kleisli { context =>
      IO.defer {
        val process = PlaceholderRefusingProcess(context.process)
        runCompilerIO(process)(
          CompilerMonomorphicTypeCheckProcessor.checkOnCompilerTrack(Seq.empty, value, signatureOnly = true, None)
        ).map {
          case Right((errors, result)) if errors.isEmpty && !process.refused && result.substitutable =>
            Some(result.signature)
          case _                                                                                      => None
        }
      }
    }
}
