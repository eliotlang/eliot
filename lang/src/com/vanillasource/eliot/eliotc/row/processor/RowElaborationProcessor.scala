package com.vanillasource.eliot.eliotc.row.processor

import cats.Id
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, Role, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue
import com.vanillasource.eliot.eliotc.row.{BindingWriter, RowChecker}
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError
import com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue

import scala.collection.mutable

/** Effects v6's **write** phase (`docs/effects.md` §9.4 step 3): every reference in a demanded value's runtime body is
  * given the implementation each of its callee's phantom binders stands for, every row-typed slot is thunked, and
  * every `with` is consumed. See [[BindingWriter]] for the rules; this processor only supplies the declared world it
  * needs and reports what it could not answer.
  *
  * Placed after [[RecursionCheckedValue]] (the recursion gate walks the *user's* reference graph) and before
  * `SaturatedValueProcessor`, so everything from saturation onwards sees written bodies and signatures with no `with`
  * left in them. A value that is not [[RowChecker.checkable]] — body-less, a `@Signature` twin, a type constructor or
  * a meta companion — is carried through unchanged.
  *
  * **The universe is built by demand, not guessed.** The write is decision-free but not context-free: it consults the
  * declared signature, declared row and slot rows of every callee it meets, and the resolved qualifier of every
  * implementation a `with` names. Which names those are cannot be read off the body alone. So the write runs against
  * a [[RowChecker.Universe]] that *reports* every name it misses, fetches exactly those, and repeats until a round
  * misses nothing new; the last round's universe is complete for this value by construction.
  *
  * @param runBoundaryFunctions
  *   The platform-contributed values at which every effect's chain ends ([[com.vanillasource.eliot.eliotc.row.RunBoundaryFunctions]]) —
  *   the synthesized entry point. Only the platform knows which value that is.
  *
  * The fetch is [[getFactIfProduced]] by design: a referenced name legitimately has no [[OperatorResolvedValue]] when
  * its own definition aborted upstream, and a reference the write cannot read is simply left alone — the fail-safe
  * direction, since an unwritten binder is caught by the checker rather than silently defaulted.
  */
class RowElaborationProcessor(isRunBoundary: ValueFQN => Boolean = _ => false)
    extends TransformationProcessor[RecursionCheckedValue.Key, RowElaboratedValue.Key](key =>
      RecursionCheckedValue.Key(key.vfqn, key.platform)
    ) {

  override protected def generateFromKeyAndFact(
      key: RowElaboratedValue.Key,
      recursionChecked: RecursionCheckedValue
  ): CompilerIO[RowElaboratedValue] = {
    val value = recursionChecked.value
    for {
      universe <- universeFor(value, key.platform)
      written   = BindingWriter.write(value, universe, isRunBoundary(value.vfqn))
      _        <- report(written, value, key.platform).whenA(key.platform == Platform.Runtime)
    } yield RowElaboratedValue(written.value)
  }

  /** A binding the write could not answer aborts this value: nothing downstream runs for it, so the user gets one
    * located error in effect vocabulary rather than the machinery's downstream symptoms — and, crucially, no body is
    * emitted whose operation silently ran on whatever the two-site search happened to find.
    *
    * An over-declared effect aborts too, but only once its callees are known to be sound ([[calleesWritten]]).
    */
  private def report(written: BindingWriter.Written, value: OperatorResolvedValue, platform: Platform): CompilerIO[Unit] =
    for {
      overDeclarations <- calleesWritten(value, platform).map(if (_) written.overDeclarations else Seq.empty)
      _                <- (written.violations ++ overDeclarations) match {
                            case Seq()      => ().pure[CompilerIO]
                            case violations =>
                              violations.traverse_(violation => compilerError(violation.message, violation.help)) >>
                                abort[Unit]
                          }
    } yield ()

  /** Whether every value this one's body calls was itself written without error. Asked only when an effect is
    * over-declared, because that is the error a broken callee most often causes: a callee that performs `Console`
    * without declaring it leaves its caller's `{Console}` unconsumed. Aborting here would stop the pipeline before the
    * callee's own write is ever demanded, so its error — the real one — would never be reported. So the callees are
    * demanded first, and a failed one withholds the caller's over-declaration: the callee's error explains it, and the
    * build fails on it either way.
    *
    * A type is not demanded: it has no body to have failed in, and its signature may refer back to this value. The
    * body references are acyclic, which the recursion gate guarantees before this phase.
    */
  private def calleesWritten(value: OperatorResolvedValue, platform: Platform): CompilerIO[Boolean] =
    value.runtime.toSeq
      .flatMap(body =>
        OperatorResolvedExpression.foldValueReferences[Id, Set[ValueFQN]](body.value, Set.empty)(_ + _.value)
      )
      .filterNot(fqn => fqn == value.vfqn || fqn.name.qualifier == Qualifier.Type)
      .traverse(fqn => getFactIfProduced(RowElaboratedValue.Key(fqn, platform)))
      .map(_.forall(_.isDefined))

  /** The complete declared world for writing this one value: fetch what the write misses, re-run, repeat. Terminates
    * because each round strictly grows the set of names already attempted, over the finite set reachable from the
    * body.
    */
  private def universeFor(value: OperatorResolvedValue, platform: Platform): CompilerIO[RowChecker.Universe] = {
    def loop(
        known: Map[ValueFQN, OperatorResolvedValue],
        attempted: Set[ValueFQN]
    ): CompilerIO[RowChecker.Universe] = {
      val missed    = mutable.Set.empty[ValueFQN]
      val reporting = RowChecker.Universe(known, Set.empty, fqn => { missed += fqn; () })
      BindingWriter.write(value, reporting, isRunBoundary(value.vfqn))
      val fresh     = missed.toSet -- attempted
      if (fresh.isEmpty) RowChecker.Universe(known).pure[CompilerIO]
      else
        fresh.toSeq
          .traverse(fqn => getFactIfProduced(OperatorResolvedValue.Key(fqn, platform)).map(fqn -> _))
          .flatMap(fetched =>
            loop(known ++ fetched.collect { case (fqn, Some(orv)) => fqn -> orv }, attempted ++ fresh)
          )
    }

    // The value itself is part of its own universe: the write reads its own declaration for the bindings it receives.
    loop(Map(value.vfqn -> value), Set.empty)
  }
}
