package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.AbilityMatcher
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*

/** The type arguments a **named** implementation runs at, where it is bound for an entry (`docs/effects.md` §8 item
  * 30).
  *
  * A `with` writes the implementation's marker at every reference it binds, and the row phase writes only what a
  * declaration determines there — the bindings of its clauses' entries and of its `~` constraints. Its own type
  * parameters are solved here, the way the two-site search solves an anonymous implementation's: by matching its
  * pattern against the entry's ground arguments. So `with showing`, for
  *
  * {{{
  * implement[T ~ Show[T]] showing: Source[T] { … }
  * }}}
  *
  * runs at `T := Int` where it binds `Source[Int]`, and at `T := String` where the same `with` binds `Source[String]`.
  * A binder its pattern does not mention is a binding binder, which the match cannot trace, and keeps what the binding
  * carries. An implementation whose pattern does not match the entry is [[None]], the demander's to report.
  */
object NamedImplementationArguments {

  def solve(
      binding: ImplementationBinding.Implementation,
      patternArguments: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Option[Seq[GroundValue]]] =
    for {
      marker  <- getFactOrAbort(OperatorResolvedValue.Key(binding.head, platform))
      matched <- AbilityMatcher.matchImpl(marker.signature, patternArguments)
    } yield matched.map { matched =>
      val mentioned = AbilityMatcher.patternParameters(marker.signature)
      SignatureView.of(marker.signature).binders.map(_.name.value).zip(matched.groundArgs).zipWithIndex.map {
        case ((binder, ground), _) if mentioned.contains(binder) => ground
        case (_, index)                                         =>
          binding.typeArguments.lift(index).getOrElse(ImplementationBinding.defaultGround)
      }
    }
}
