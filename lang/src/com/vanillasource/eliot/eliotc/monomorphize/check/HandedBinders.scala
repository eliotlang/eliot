package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.CheckIO.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.{Spine, VMeta, VTopDef}
import com.vanillasource.eliot.eliotc.monomorphize.eval.{Evaluator, Quoter}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** **A block's handle types** — the one piece of the new effect path with no precedent (`docs/effects.md` D36 point 6).
  *
  * A code parameter's listed entries are its block's parameters, and the type of each is what the callee's body hands
  * it. The desugar makes each such type a *handed* binder of the callee, appended after every other binder and marked
  * [[WellKnownTypes.handedTypeFQN]]. Nothing declares one, and no caller writes one:
  *
  *   - in the callee's own check, at a key that leaves them open, each is a metavariable its runs solve — a run hands
  *     the block a handle, and that decides the type, as an initializer gives a `val` its type — read back as the
  *     checked value's `handed` types ([[own]]);
  *   - in a caller, each is an instantiation metavariable, which waits until the callee's other type arguments are
  *     ground and is then read off the callee's checked value at them ([[read]]), in the same fixpoint as ability
  *     resolution and the hole rule. No recursion means no cycle.
  *
  * Nothing is decided here: a handed type is solved by equality, at the callee's runs, and a caller reads the solution.
  */
object HandedBinders {

  /** The indices of a signature's handed binders. */
  def of(signature: Sourced[OperatorResolvedExpression]): Seq[Int] =
    SignatureView.of(signature).binders.zipWithIndex.collect {
      case (binder, index) if binder.parameterType.exists(isMark) => index
    }

  private def isMark(declared: Sourced[OperatorResolvedExpression]): Boolean =
    declared.value match {
      case OperatorResolvedExpression.ValueReference(name, Seq()) => name.value == WellKnownTypes.handedTypeFQN
      case _                                                     => false
    }

  /** A reference to a callee with handed binders, at its type arguments so far. */
  case class Reference(callee: Sourced[ValueFQN], typeArguments: Seq[SemValue], handed: Seq[Int])

  /** Every reference in a checked body to a callee with handed binders. */
  def references(expression: SemExpression, handed: Map[ValueFQN, Seq[Int]]): Seq[Reference] =
    expression.expression match {
      case SemExpression.ValueReference(name, typeArguments)    =>
        handed.get(name.value).map(Reference(name, typeArguments, _)).toSeq
      case SemExpression.FunctionApplication(target, argument) =>
        references(target.value, handed) ++ references(argument.value, handed)
      case SemExpression.FunctionLiteral(_, _, body)           => references(body.value, handed)
      case _                                                   => Seq.empty
    }

  /** Read every reference's handed types whose callee's other type arguments are ground and which are still open.
    * Returns whether one was read.
    */
  def read(
      references: Seq[Reference],
      readHanded: (ValueFQN, Seq[GroundValue]) => CompilerIO[Seq[GroundValue]]
  ): CheckIO[Boolean] =
    references.toList.foldLeftM(false) { (progress, reference) =>
      get.flatMap { state =>
        val store   = state.unifier.metaStore
        val first   = reference.handed.min
        val open    = reference.handed.exists(index =>
          reference.typeArguments.lift(index).exists(argument => Evaluator.force(argument, store).isInstanceOf[VMeta])
        )
        val leading = reference.typeArguments.take(first).toList.traverse(Quoter.quote(0, _, store))
        (open, leading) match {
          case (true, Right(ground)) if reference.typeArguments.sizeIs > reference.handed.max && ground.sizeIs == first =>
            liftF(readHanded(reference.callee.value, ground)).flatMap { handed =>
              reference.handed.zip(handed).traverse_ { (index, value) =>
                modify(s =>
                  s.withUnifier(
                    s.unifier.unify(
                      reference.typeArguments(index),
                      Evaluator.groundToSem(value),
                      reference.callee.as("Type mismatch.")
                    )
                  )
                )
              }
            }.as(true)
          case _                                                                                                       =>
            pure(progress)
        }
      }
    }

  /** This value's own open handed types, read back once its runs have solved them. A body-less value has no runs: its
    * blocks' handles stay the opaque mark, which no implementation answers, so an operation on one is reported where it
    * is performed rather than silently dispatched. A bodied value whose runs never solve one never runs that code.
    */
  def own(hasBody: Boolean, at: Sourced[?]): CheckIO[Seq[SemValue]] =
    inspect(_.ownHanded).flatMap(_.toList.traverse[CheckIO, SemValue] { (name, meta) =>
      inspect(s => Evaluator.force(meta, s.unifier.metaStore)).flatMap {
        case unsolved: VMeta if !hasBody =>
          val mark = VTopDef(WellKnownTypes.handedTypeFQN, None, Spine.SNil)
          modify(s => s.withUnifier(s.unifier.unify(unsolved, mark, at.as("Type mismatch.")))).as(mark: SemValue)
        case _: VMeta                    =>
          liftF(
            compilerError(
              at.as(s"This value never runs the code whose handle '$name' its clause lists, so the handle has no type."),
              Seq("Run the code parameter, or remove the effects its `uses` clause lists.")
            ) >> abort[SemValue]
          )
        case solved                      => pure(solved)
      }
    }.map(_.toSeq))
}
