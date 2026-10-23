package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicValue}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.annotation.tailrec

/** **What a return brace may say** (docs/meta.md §3, §8.1): every named value's result has a meta, and a brace is how
  * it is stated where the compiler cannot see the supplier, or chooses to be told.
  *
  *   - **R2 — a leaf must state.** Every **body-less** value — a native leaf — that produces a meta-carrying type must
  *     **state** its meta transfer. Without it, a native returning e.g. `Int` (whose value range is meta-information)
  *     and carrying no `{ ... }` transfer brace silently defaults its meta to ⊤ rather than being forced to say what it
  *     does. This closed the TODO *"A native that produces a meta-carrying type must state its meta-information"*.
  *   - **A bodied value may state its result** (decision O, which reversed R3). Its body is still derived, and the
  *     brace replaces only the meta of the value it returns ([[MetaInterpreter]]), trusted and never checked. So the
  *     brace may say only that: not something about control (a frame, a cell, a loop, an exit, `never`), which the
  *     walk of the body derives; not slots over a result carrying no meta, nothing being there to state; and not the
  *     meta of a `data` result, whose structure has no surface (§13.6) and which declares no slots yet (§17.2, O4/O5).
  *
  * A rider on [[MonomorphicValue]] (runtime track), on the [[RefinementChannelProcessor]] template. The leaf test the
  * rules split on is exactly the body test the fact already carries — `mv.runtime.isEmpty` (no Eliot body on this
  * track), read *after* the layer merge, so §3.2's cross-layer case (the brace in one layer, the body in another) is
  * the same check and needs no separate mechanism. [[MetaInterpreter]]'s three arms split on the same test.
  *
  * Both arms read the instance's **evaluated** result type, so a value returning a type parameter is judged at what each
  * instantiation makes it: `typeMatch[R]` at `R = String` must state, exactly as `String::length` must. There is no
  * exemption for a type-parameter result (`docs/meta.md` §10 reversed it): "forwarded" is a claim the leaf states.
  *
  * The check is normatively **use-site, post-mono** (§3): whether a return's meta is `Unit` is a per-instantiation fact,
  * so it rides each `MonomorphicValue`. The fact is produced only when the check passes; a violation aborts, and that
  * abort is a [[WovenValue]] `getFactOrAbort` precondition (S5's arming step), so a violating value blocks its own
  * codegen — a silently-defaulted meta never reaches bytecode.
  */
class MetaTransferAccountingProcessor
    extends TransformationProcessor[MonomorphicValue.Key, MetaTransferAccounting.Key](key =>
      MonomorphicValue.Key(key.vfqn, key.typeArguments)
    )
    with Logging {

  override protected def generateFromKeyAndFact(
      key: MetaTransferAccounting.Key,
      mv: MonomorphicValue
  ): CompilerIO[MetaTransferAccounting] =
    verifyTransfer(mv).as(MetaTransferAccounting(key.vfqn, key.typeArguments))

  /** The check for one instance. Both arms judge a **declaration**, so both are keyed off the value's
    * pre-monomorphization [[OperatorResolvedValue]]: an instance with none — a fact injected by a test harness, a value
    * synthesized past the front end — declares nothing and so has nothing to state. Given one, the test that separates
    * the arms is the body: a **bodied** value may state its result ([[verifyOverride]]); a **body-less** leaf must state
    * when its result carries meta (R2, [[verifyStated]]).
    */
  private def verifyTransfer(mv: MonomorphicValue): CompilerIO[Unit] =
    getFactIfProduced(OperatorResolvedValue.Key(mv.vfqn, Platform.Runtime)).flatMap {
      case None      => ().pure[CompilerIO]
      case Some(_) if mv.runtime.isDefined => verifyOverride(mv)
      case Some(orv)                       =>
        verifyStated(mv, resultHead(mv.signature, OperatorResolvedExpression.SignatureView.of(orv.signature).parameters.size))
    }

  /** A bodied value's brace, if it has one (decision O): it states its result's meta and nothing else. A value in a
    * [[Qualifier.Meta]] namespace is itself a companion — a `^Meta` transfer or a `^Where` predicate — so it carries no
    * brace of its own and is exempt.
    *
    * The result is read past as many arrows as the companion takes parameters, as the channel reads it, so a
    * function-typed result is that function type and not what it returns.
    */
  private def verifyOverride(mv: MonomorphicValue): CompilerIO[Unit] =
    mv.vfqn.name.qualifier match {
      case _: Qualifier.Meta => ().pure[CompilerIO]
      case _                 =>
        val companion = RefinementChannelProcessor.metaCompanionFqn(mv.vfqn)
        declaresTransfer(mv.vfqn).flatMap { states =>
          (MetaStatement.of(companion).product(MetaInterpreter.companionArity(companion)).flatMap { (statement, arity) =>
            if (statement.controls) reportControl(mv)
            else
              resultHead(mv.signature, arity) match {
                case GroundValue.Structure(typeName, _, _) =>
                  MetaInterpreter.declaresFields(typeName).flatMap { data =>
                    if (data) reportData(mv, typeName)
                    else
                      (
                        MetaInterpreter.declaresSlots(typeName),
                        MetaInterpreter.takesConstructor(companion)
                      ).mapN((metaCarrying, statesSlots) => !metaCarrying && statesSlots)
                        .ifM(reportSlotsWithoutMeta(mv, s"'${typeName.name.name}'"), ().pure[CompilerIO])
                  }
                case _                                     => reportSlotsWithoutMeta(mv, "its result")
              }
          }).whenA(states)
        }
    }

  /** R2: a body-less leaf whose result carries meta-information must state its transfer, else it is reported at the
    * value. That includes a result that is a type parameter (`exitInternal[K, A]: A`, `typeMatch[R]: R`): "forwarded"
    * is a claim, so the leaf states it too (`docs/meta.md` §10).
    *
    * Whether the result carries meta is read from the **evaluated** result type — the instance's signature past as
    * many arrows as the declaration has parameters — never from the declared type's name, which for an alias
    * (`type Byte = Int`) names a type declaring no meta structure, and for a type parameter names none at all. Read the
    * same way, a leaf whose brace lists slots over a result carrying no meta is reported too: there is no meta
    * constructor to apply them to. A frame or cell statement lists no slots, whatever its result
    * (`writeCellInternal`'s `Unit {store(cell, s)}`, [[MetaStatement]]).
    */
  private def verifyStated(mv: MonomorphicValue, result: GroundValue): CompilerIO[Unit] =
    result match {
      case GroundValue.Structure(typeName, _, _) =>
        for {
          metaCarrying   <- MetaInterpreter.declaresSlots(typeName)
          statesTransfer <- declaresTransfer(mv.vfqn)
          statesSlots    <- if (statesTransfer)
                              (
                                MetaInterpreter.takesConstructor(RefinementChannelProcessor.metaCompanionFqn(mv.vfqn)),
                                MetaStatement.of(RefinementChannelProcessor.metaCompanionFqn(mv.vfqn))
                              ).mapN((slots, statement) => slots && statement.primitive.isEmpty)
                            else false.pure[CompilerIO]
          _              <- if (metaCarrying && !statesTransfer) reportMissing(mv, typeName)
                            else if (!metaCarrying && statesSlots) reportSlotsWithoutMeta(mv, s"'${typeName.name.name}'")
                            else ().pure[CompilerIO]
        } yield ()
      case _                                     =>
        // A result that is no type structure — `Type` itself, or a function type — carries no meta to state.
        ().pure[CompilerIO]
    }

  /** The evaluated result type of a signature with `parameters` parameters: what remains past that many arrows. */
  @tailrec
  private def resultHead(signature: GroundValue, parameters: Int): GroundValue =
    signature.asFunctionType match {
      case Some((_, codomain)) if parameters > 0 => resultHead(codomain, parameters - 1)
      case _                                     => signature
    }

  /** Whether this value declares a `^Meta` transfer — its [[RefinementChannelProcessor.metaCompanionName]] in its
    * module's compiler pool, exactly what
    * [[com.vanillasource.eliot.eliotc.core.processor.MetaTransferDesugarer]] emits from a `{ ... }` return brace.
    *
    * The name is the transfer's *exactly*, so a `where` predicate's companion (`name$Where`, the same namespace) never
    * answers here — `where` is a stated contract, never derived, and this check leaves it alone.
    *
    * Reading the same name the channel reads is deliberate: this then judges exactly the brace the channel applies.
    * Since the name keeps the value's own qualifier (docs/meta.md §4.2), a bodied `add` is not answered for by an
    * ability-implementation sibling's brace in the same module.
    */
  private def declaresTransfer(vfqn: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(vfqn.moduleName, Platform.Compiler)).map(
      _.names.contains(RefinementChannelProcessor.metaCompanionName(vfqn))
    )

  private def reportMissing(mv: MonomorphicValue, typeName: ValueFQN): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        s"This native value produces '${typeName.name.name}', which carries meta-information, but states no meta " +
          s"transfer; add a { ... } return brace saying what it does to the meta-information."
      )
    )

  private def reportSlotsWithoutMeta(mv: MonomorphicValue, result: String): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        s"This value states meta-information, but $result carries none; remove the { ... } return brace."
      )
    )

  private def reportControl(mv: MonomorphicValue): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        "This value has a body, so what it does to control (frames, cells, loops, exits, never) is derived from that " +
          "body; its { ... } return brace may state only the meta-information of its result."
      )
    )

  private def reportData(mv: MonomorphicValue, typeName: ValueFQN): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        s"This value states meta-information, but '${typeName.name.name}' is a data type, whose meta-information is " +
          s"derived from its fields and has nothing a { ... } return brace can state; remove the brace."
      )
    )
}
