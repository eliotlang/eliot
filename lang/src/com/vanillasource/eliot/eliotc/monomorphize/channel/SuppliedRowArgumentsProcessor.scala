package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

/** Every **supplied** row entry at every call in a monomorphic body must know what it supplies (`docs/effects.md`
  * §2.2) — a rider on [[MonomorphicValue]] built on the [[MetaTransferAccountingProcessor]] template, and a codegen
  * precondition through [[WovenValueProcessor]]'s `getFactOrAbort`.
  *
  * A parameter row lowers to a thunk (`{Throw[E]} A` ⤳ `Unit -> A`, `docs/effects.md` §3.1 step 2), which erases the
  * entry's own arguments from the *type*. Where nothing else determines them — `catch[E, A](computation:
  * {Throw[E]} A, onError: E => {} A)` called with a handler that ignores its error — `E` is left to default, and the
  * default is the universe. The call then compiles and the per-instantiation frames silently disagree: the discharger
  * installs a frame keyed on the universe while the `raise` inside exits one keyed on the real error type, so the exit
  * unwinds past every frame and reaches the runtime as a bare exception.
  *
  * That is a program that compiles and crashes, which nothing may be. So a defaulted argument at a supplied row entry
  * is **rejected here**, at the call, before any bytecode is emitted.
  *
  * The ordinary case is gone: the write takes the entry's arguments from the actual's own declared row
  * ([[com.vanillasource.eliot.eliotc.row.BindingWriter.suppliedDetermination]]), so `bad catch (err -> "fallback")`
  * comes out with `E := String` written. What is left for this check is what no declaration answers — chiefly an
  * *over-discharge*, a second `catch` over a computation the first already discharged, where the actual declares no
  * row at all. That is a meaningless program rather than a gap, and it is rejected at the call naming the fix.
  *
  * '''This is not an effect verifier''' (D7, `docs/effects.md` §11). Until the D7 decision this processor also
  * re-derived each instance's effect row post-monomorphization and checked `derived ⊆ declared`. The trace that
  * decision asked for measured what that could see — a reference *forwarding a received binding to a callee that
  * declares the ability*, never a direct operation call, whose row `AbilityResolver` has already rewritten away —
  * making it a strict subset of what the pre-mono scope check ([[com.vanillasource.eliot.eliotc.row.BindingWriter]])
  * reports at the reference, with no case of its own. It is retired; the effects channel has one verifier, and it is
  * that scope check, complete before monomorphization. What is left here is not its shadow.
  */
class SuppliedRowArgumentsProcessor
    extends TransformationProcessor[MonomorphicValue.Key, SuppliedRowArguments.Key](key =>
      MonomorphicValue.Key(key.vfqn, key.typeArguments)
    ) {

  override protected def generateFromKeyAndFact(
      key: SuppliedRowArguments.Key,
      mv: MonomorphicValue
  ): CompilerIO[SuppliedRowArguments] =
    verifySuppliedRowArguments(mv).as(SuppliedRowArguments(key.vfqn, key.typeArguments))

  private def verifySuppliedRowArguments(mv: MonomorphicValue): CompilerIO[Unit] =
    mv.runtime.fold(().pure[CompilerIO]) { body =>
      collectReferences(body.value).toList.traverse_ { case (ref, typeArgs) =>
        undeterminedSuppliedArgument(ref, typeArgs).flatMap {
          case None                      => ().pure[CompilerIO]
          case Some((ability, atBinder)) =>
            compilerAbort[Unit](
              mv.name.as(
                s"Cannot tell which '${ability.abilityName}' this call supplies: nothing determines its type " +
                  s"argument '$atBinder', so it would run on a different one than the computation it discharges. " +
                  s"Write it out at the call, as `${ref.name.name}[…]`."
              )
            )
        }
      }
    }

  /** The first supplied row entry of `ref` whose ability argument names one of the callee's own binders and grounds to
    * the **defaulted universe** — the shape above. `None` when every one is determined, when the callee's declaration
    * is unavailable, or when the key does not reach the binder (a partial-arity key, which emits nothing).
    */
  private def undeterminedSuppliedArgument(
      ref: ValueFQN,
      typeArgs: Seq[GroundValue]
  ): CompilerIO[Option[(AbilityFQN, String)]] =
    getFactIfProduced(OperatorResolvedValue.Key(ref, Platform.Runtime)).map {
      case None         => None
      case Some(callee) =>
        val binders = OperatorResolvedExpression.SignatureView.of(callee.signature).binders.map(_.name.value)
        callee.effectRow.parameterEffects.view
          .flatMap(_.effects)
          .flatMap(entry => entry.typeArgs.map(entry.abilityFQN -> _))
          .collectFirst {
            case (ability, OperatorResolvedExpression.ParameterReference(name))
                if binders.indexOf(name.value) >= 0 &&
                  typeArgs.lift(binders.indexOf(name.value)).contains(GroundValue.Type) =>
              (ability, name.value)
          }
    }

  /** Every value reference in a monomorphic body with its ground type arguments, in traversal order (parameter
    * references and literals excluded). The `typeArguments` are the reference's own mono key, which is where its
    * written bindings sit.
    */
  private def collectReferences(expr: MonomorphicExpression.Expression): Seq[(ValueFQN, Seq[GroundValue])] =
    expr match {
      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs, _) => Seq((vfqn.value, typeArgs))
      case MonomorphicExpression.FunctionApplication(target, arg)          =>
        collectReferences(target.value.expression) ++ collectReferences(arg.value.expression)
      case MonomorphicExpression.FunctionLiteral(_, _, body)               => collectReferences(body.value.expression)
      case _                                                               => Seq.empty
    }
}
