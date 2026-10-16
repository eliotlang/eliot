package com.vanillasource.eliot.eliotc.row.processor

import cats.Id
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, Qualifier, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.row.fact.{RowElaboratedValue, SlotBoundValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.row.{BindingWriter, RowChecker}
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

import scala.collection.mutable

/** Effects v6's **write** phase (`docs/effects.md` §9.4 step 3): every reference in a demanded value's runtime body is
  * given the implementation each of its callee's phantom binders stands for, every row-typed slot is thunked, and
  * every `with` is consumed. See [[BindingWriter]] for the rules; this processor only supplies the declared world it
  * needs and reports what it could not answer.
  *
  * Placed after [[SlotBoundValue]] (which is after the recursion gate, which walks the *user's* reference graph) and
  * before `SaturatedValueProcessor`, so everything from saturation onwards sees written bodies and signatures with no
  * `with` left in them. A value that is not [[RowChecker.checkable]] — body-less, a `@Signature` twin, a type constructor or
  * a meta companion — is carried through unchanged.
  *
  * **The universe is built by demand, not guessed.** The write is decision-free but not context-free: it consults the
  * declared signature, declared row and slot rows of every callee it meets, and the resolved qualifier of every
  * implementation a `with` names. Which names those are cannot be read off the body alone. So the write runs against
  * a [[RowChecker.Universe]] that *reports* every name it misses, fetches exactly those, and repeats until a round
  * misses nothing new; the last round's universe is complete for this value by construction.
  *
  * Every name is read as its [[SlotBoundValue]], the declaration whose slots name what their runs are bound by. The
  * fetch is [[getFactIfProduced]] by design: a referenced name legitimately has none when its own definition aborted
  * upstream or was rejected by the recursion gate, and a reference the write cannot read is simply left alone — the
  * fail-safe direction, since an unwritten binder is caught by the checker rather than silently defaulted.
  */
class RowElaborationProcessor
    extends TransformationProcessor[SlotBoundValue.Key, RowElaboratedValue.Key](key =>
      SlotBoundValue.Key(key.vfqn, key.platform)
    ) {

  override protected def generateFromKeyAndFact(
      key: RowElaboratedValue.Key,
      slotBound: SlotBoundValue
  ): CompilerIO[RowElaboratedValue] = {
    val value = slotBound.value
    for {
      universe <- universeFor(value, key.platform)
      written   = BindingWriter.write(value, universe)
      _        <- implementationKind(value, key.platform) >> report(written, value, key.platform)
    } yield RowElaboratedValue(written.value)
  }

  /** The two definition-site rules of an implementation's marker, both of which keep effects and abilities apart
    * (`docs/effects.md` D26, D29):
    *
    *   - an **anonymous** `implement` of an **effect** is an error: an anonymous implementation is its ability's
    *     default, found by the two-site search, and an effect has none — every binding of one is passed explicitly, so a
    *     default would never be reached and would only look like it is used;
    *   - a **named** `implement` of an **ability** is an error: an ability is answered by the two-site search alone and
    *     is never bound with `with`, so a name would never be reached. Behaviour chosen per call is a value parameter.
    *     And a named implementation's type parameters are all its pattern's: one the pattern does not take could only be
    *     read off a `with`'s arguments (D27, reversed by D29).
    *
    * Effect-ness is read where it is recorded: the members' declared clauses, which membership in an `effect` block
    * fills with the effect itself.
    */
  private def implementationKind(value: OperatorResolvedValue, platform: Platform): CompilerIO[Unit] =
    value.name.value.qualifier match {
      case ResolveQualifier.AbilityImplementation(ability, _, named) if value.vfqn.name.name == ability.abilityName =>
        isEffect(ability, platform).flatMap { effect =>
          named match {
            case None if effect        =>
              refuse(
                value,
                platform,
                s"An implementation of the effect '${ability.abilityName}' must be named, since an effect has no default.",
                s"Name it (`implement name: ${ability.abilityName} { … }`) and bind it with `with` where it is used."
              )
            case Some(name) if !effect =>
              refuse(
                value,
                platform,
                s"The implementation '$name' of the ability '${ability.abilityName}' cannot be named: an ability's " +
                  "implementation is found by its type, and never bound with `with`.",
                s"Make it the anonymous `implement ${ability.abilityName}[…]` in the ability's or the type's module, or " +
                  "take the behaviour as a value parameter of the function that needs it."
              )
            case Some(name)            =>
              BindingWriter.unpatternedParameters(value).headOption.fold(().pure[CompilerIO])(parameter =>
                refuse(
                  value,
                  platform,
                  s"The type parameter '$parameter' of the implementation '$name' is not taken by its pattern, so " +
                    "nothing where it runs determines it.",
                  s"Use '$parameter' in the implemented '${ability.abilityName}[…]', or remove it."
                )
              )
            case None                  => ().pure[CompilerIO]
          }
        }
      case _                                                                                                    =>
        ().pure[CompilerIO]
    }

  private def isEffect(ability: AbilityFQN, platform: Platform): CompilerIO[Boolean] =
    for {
      abilities <- getFactOrAbort(ModuleAbilities.Key(ability.moduleName, platform))
      members   <- abilities
                     .declaredMethodsOf(ability.abilityName)
                     .filterNot(_.name.name == ability.abilityName)
                     .traverse(member => getFactIfProduced(OperatorResolvedValue.Key(member, platform)))
    } yield members.flatten.exists(_.effectRow.returnEffects.exists(_.abilityFQN == ability))

  private def refuse(value: OperatorResolvedValue, platform: Platform, message: String, help: String)
      : CompilerIO[Unit] =
    reported(value, platform)(compilerError(value.name.as(message), Seq(help)))

  /** A failed write aborts the value on both tracks, so neither carries a definition whose write failed, and its errors
    * are said once. The runtime track says them. The compile track writes the same definitions, so it demands the
    * runtime track's write of this one, which reports, and stays quiet — demanded rather than assumed, because the
    * runtime check reads compile-track facts, and this abort could otherwise stop the runtime track before anything
    * asked for that write. It reports its own errors only where no runtime write does: a definition the runtime pool
    * does not have, or one it wrote cleanly (the compile track's overlay redefines it).
    */
  private def reported(value: OperatorResolvedValue, platform: Platform)(errors: CompilerIO[Unit]): CompilerIO[Unit] =
    (platform match {
      case Platform.Runtime  => errors
      case Platform.Compiler => reportedAtRuntime(value.vfqn).ifM(().pure[CompilerIO], errors)
    }) >> abort[Unit]

  private def reportedAtRuntime(vfqn: ValueFQN): CompilerIO[Boolean] =
    getFactIfProduced(UnifiedModuleNames.Key(vfqn.moduleName, Platform.Runtime)).flatMap {
      case Some(names) if names.names.contains(vfqn.name) =>
        getFactIfProduced(RowElaboratedValue.Key(vfqn, Platform.Runtime)).map(_.isEmpty)
      case _                                              => false.pure[CompilerIO]
    }

  /** A binding the write could not answer aborts this value on both tracks: nothing downstream runs for it, so the user
    * gets one located error in effect vocabulary rather than the machinery's downstream symptoms — and, crucially, no
    * body is emitted or reduced whose operation silently ran on whatever the two-site search happened to find.
    *
    * An over-declared effect aborts too, but only once its callees are known to be sound ([[calleesWritten]]).
    */
  private def report(written: BindingWriter.Written, value: OperatorResolvedValue, platform: Platform): CompilerIO[Unit] =
    for {
      overDeclarations <- calleesWritten(value, platform).map(if (_) written.overDeclarations else Seq.empty)
      _                <- (written.violations ++ overDeclarations) match {
                            case Seq()      => ().pure[CompilerIO]
                            case violations =>
                              reported(value, platform)(
                                violations.traverse_(violation => compilerError(violation.message, violation.help))
                              )
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
    *
    * An implementation's clauses are read off its module's [[ModuleAbilities]]: the values in the marker's namespace,
    * but the marker. The write asks for them only once it has read the marker, so its module is there to read.
    */
  private def universeFor(value: OperatorResolvedValue, platform: Platform): CompilerIO[RowChecker.Universe] = {
    def loop(universe: RowChecker.Universe, attempted: Set[ValueFQN], attemptedClauses: Set[ValueFQN])
        : CompilerIO[RowChecker.Universe] = {
      val missed        = mutable.Set.empty[ValueFQN]
      val missedClauses = mutable.Set.empty[ValueFQN]
      BindingWriter.write(
        value,
        universe.copy(onMiss = fqn => { missed += fqn; () }, onMissClauses = fqn => { missedClauses += fqn; () })
      )
      val fresh         = missed.toSet -- attempted
      val freshClauses  = missedClauses.toSet -- attemptedClauses
      if (fresh.isEmpty && freshClauses.isEmpty) universe.pure[CompilerIO]
      else
        for {
          fetched <- fresh.toSeq.traverse(fqn => getFactIfProduced(SlotBoundValue.Key(fqn, platform)).map(fqn -> _))
          clauses <- freshClauses.toSeq.traverse(marker => clausesOf(marker, platform).map(marker -> _))
          result  <- loop(
                       universe.copy(
                         values = universe.values ++ fetched.collect { case (fqn, Some(bound)) => fqn -> bound.value },
                         clauses = universe.clauses ++ clauses
                       ),
                       attempted ++ fresh,
                       attemptedClauses ++ freshClauses
                     )
        } yield result
    }

    // The value itself is part of its own universe: the write reads its own declaration for the bindings it receives.
    loop(RowChecker.Universe(Map(value.vfqn -> value)), Set.empty, Set.empty)
  }

  private def clausesOf(marker: ValueFQN, platform: Platform): CompilerIO[Seq[ValueFQN]] =
    getFactOrAbort(ModuleAbilities.Key(marker.moduleName, platform)).map(
      _.implementations
        .filter(impl => impl.vfqn.name.qualifier == marker.name.qualifier && impl.vfqn != marker)
        .map(_.vfqn)
    )
}
