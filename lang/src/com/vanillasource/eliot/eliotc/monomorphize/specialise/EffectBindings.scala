package com.vanillasource.eliot.eliotc.monomorphize.specialise

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*

/** Which of a value's type arguments are **effect bindings**, and the arguments with those left out — the key the
  * checker checks a value at (`docs/effects.md` D35 step 4, [[com.vanillasource.eliot.eliotc.monomorphize.fact.CheckedValue]]).
  *
  * An effect binding is a binder the desugar put an effect's constraint on — `Impl ~ Console[Impl]` for an entry
  * `uses Console` — read off the declaration, as the checker reads a received entry. A `~` constraint's binder binds
  * an ability, which the two-site search answers and nothing installs, so it stays. Leaving one out writes `Default`
  * there: `greet[recordingConsole[writing[Default]]]` is checked as `greet[Default]`.
  */
object EffectBindings {

  def erased(vfqn: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Seq[GroundValue]] =
    if (!typeArguments.exists(isNamed)) typeArguments.pure[CompilerIO]
    else
      getFactOrAbort(OperatorResolvedValue.Key(vfqn, Platform.Runtime)).flatMap(indices).map { effects =>
        typeArguments.zipWithIndex.map { case (argument, index) =>
          if (effects.contains(index) && isNamed(argument)) ImplementationBinding.defaultGround else argument
        }
      }

  /** The indices of `orv`'s binders binding an effect entry. */
  def indices(orv: OperatorResolvedValue): CompilerIO[Set[Int]] = {
    val binders = SignatureView.of(orv.signature).binders.map(_.name.value)
    orv.paramConstraints.values.flatten.toList
      .flatTraverse { constraint =>
        constraint.typeArgs.headOption
          .collect { case OperatorResolvedExpression.ParameterReference(name) => binders.indexOf(name.value) }
          .filter(_ >= 0)
          .toList
          .flatTraverse(index =>
            AbilityKind.isEffect(constraint.abilityFQN, Platform.Runtime).map(effect => Option.when(effect.contains(true))(index).toList)
          )
      }
      .map(_.toSet)
  }

  private def isNamed(argument: GroundValue): Boolean =
    ImplementationBinding.fromGround(argument) match {
      case Some(_: ImplementationBinding.Implementation) => true
      case _                                             => false
    }
}
