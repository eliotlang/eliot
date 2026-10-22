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

/** **R2 + R3 enforcement** (docs/meta.md §1, §3) — the two halves of *"every named value has a
  * meta transfer, and exactly one way of having it"*:
  *
  *   - **R2 — a leaf must state.** Every **body-less** value — a native leaf — that produces a meta-carrying type must
  *     **state** its meta transfer. Without it, a native returning e.g. `Int` (whose value range is meta-information)
  *     and carrying no `{ ... }` transfer brace silently defaults its meta to ⊤ rather than being forced to say what it
  *     does. This closed the TODO *"A native that produces a meta-carrying type must state its meta-information"*.
  *   - **R3 — nothing else may state.** A **bodied** value **derives** its transfer from that body, so a brace on it is
  *     an error: a stated summary would silently override what the code actually does — the one shape in which the
  *     channel can be wrong rather than merely imprecise. Deriving is [[MetaInterpreter]], and the two rules meet
  *     there: its `state or derive` split is this check read as a definition rather than as a prohibition, off the
  *     same body test and the same companion lookup, so the two cannot drift into disagreeing about which values
  *     summarise and which are read.
  *
  * A rider on [[MonomorphicValue]] (runtime track), on the [[RefinementChannelProcessor]] template. The leaf test both
  * rules split on is exactly the body test the fact already carries — `mv.runtime.isEmpty` (no Eliot body on this
  * track), read *after* the layer merge, so §3.2's cross-layer case (the brace in one layer, the body in another) is
  * the same check and needs no separate mechanism.
  *
  * R2's return-meta test uses the value's **declared** (pre-monomorphization) return type ([[OperatorResolvedValue]]),
  * so the origination is distinguished from the pass-through:
  *
  *   - a **concrete return head** (`String::length : Int`) originates the meta — the leaf must state a `^Meta` transfer
  *     for it, or it is a compile error at the leaf;
  *   - a **type-parameter return head** (`foldLeftInternal[F[_], A, B] : F[B]`, `runId[A] : A`) does not originate a
  *     meta — it forwards whatever its argument/instantiation carries — so it is **exempt**. Deriving such a
  *     (higher-order) transfer is the meta-interpretation slice (§5), not this one. `docs/meta.md` §10 reverses this
  *     exemption (decided, not built): such a leaf mostly forwards nothing, so it will state too.
  *
  * R3 needs no such test: a brace is illegal on a bodied value whatever it returns.
  *
  * The check is normatively **use-site, post-mono** (§3): whether a return's meta is `Unit` is a per-instantiation fact,
  * so it rides each `MonomorphicValue`. The fact is produced only when both checks pass; a violation aborts, and that
  * abort is a [[WovenValue]] `getFactOrAbort` precondition (S5's arming step), so a violating value blocks its own
  * codegen — a silently-defaulted or silently-overridden meta never reaches bytecode.
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

  /** The R2/R3 pair for one instance. Both rules judge a **declaration**, so both are keyed off the value's
    * pre-monomorphization [[OperatorResolvedValue]]: an instance with none — a fact injected by a test harness, a value
    * synthesized past the front end — declares nothing and so has nothing to state or over-state. Given one, the single
    * test that separates the rules is the body: a **bodied** value derives, so it may not state (R3); a **body-less**
    * leaf must state when its declared return head is a concrete meta-carrying type (R2).
    */
  private def verifyTransfer(mv: MonomorphicValue): CompilerIO[Unit] =
    getFactIfProduced(OperatorResolvedValue.Key(mv.vfqn, Platform.Runtime)).flatMap {
      case None                            => ().pure[CompilerIO]
      case Some(_) if mv.runtime.isDefined => verifyNotStated(mv)
      case Some(orv)                       => verifyStated(mv, orv)
    }

  /** R3: a bodied value derives its transfer from its body and may not also state one. A value in a
    * [[Qualifier.Meta]] namespace is itself a companion — a `^Meta` transfer or a `^Where` predicate — so it is not a
    * value *carrying* a transfer and is exempt.
    */
  private def verifyNotStated(mv: MonomorphicValue): CompilerIO[Unit] =
    mv.vfqn.name.qualifier match {
      case _: Qualifier.Meta => ().pure[CompilerIO]
      case _                 => declaresTransfer(mv.vfqn).flatMap(states => reportStated(mv).whenA(states))
    }

  /** R2: a body-less leaf whose declared return head is a concrete meta-carrying type must state its transfer, else it
    * is reported at the value. A type-parameter return head (a forwarded, non-originated meta) is exempt.
    *
    * Whether the result carries meta is read from the **evaluated** result type — the instance's signature past as
    * many arrows as the declaration has parameters — never from the declared type's name, which for an alias
    * (`type Byte = Int`) names a type declaring no meta structure and would exempt the leaf. Read the same way, a leaf
    * whose brace lists slots over a result carrying no meta is reported too: there is no meta constructor to apply
    * them to. A frame or cell statement lists no slots, whatever its result (`writeCellInternal`'s
    * `Unit {store(cell, s)}`, [[MetaStatement]]).
    */
  private def verifyStated(mv: MonomorphicValue, orv: OperatorResolvedValue): CompilerIO[Unit] = {
    val view = OperatorResolvedExpression.SignatureView.of(orv.signature)
    returnHead(view.returnType.value) match {
      case OperatorResolvedExpression.ValueReference(typeName, _) =>
        for {
          metaCarrying   <- resultHead(mv.signature, view.parameters.size) match {
                              case GroundValue.Structure(fqn, _, _) => MetaInterpreter.declaresSlots(fqn)
                              case _                                => false.pure[CompilerIO]
                            }
          statesTransfer <- declaresTransfer(mv.vfqn)
          statesSlots    <- if (statesTransfer)
                              (
                                MetaInterpreter.takesConstructor(RefinementChannelProcessor.metaCompanionFqn(mv.vfqn)),
                                MetaStatement.of(RefinementChannelProcessor.metaCompanionFqn(mv.vfqn))
                              ).mapN((slots, statement) => slots && statement.primitive.isEmpty)
                            else false.pure[CompilerIO]
          _              <- if (metaCarrying && !statesTransfer) reportMissing(mv, typeName.value)
                            else if (!metaCarrying && statesSlots) reportSlotsWithoutMeta(mv, typeName.value)
                            else ().pure[CompilerIO]
        } yield ()
      case _                                                      =>
        // A type-parameter (or literal) return head: the meta is forwarded from an argument/instantiation, not
        // originated here — exempt, its (higher-order) transfer is the meta-interpretation slice (§5). Reversed by
        // `docs/meta.md` §10, not yet built.
        ().pure[CompilerIO]
    }
  }

  /** The evaluated result type of a signature with `parameters` parameters: what remains past that many arrows. */
  @tailrec
  private def resultHead(signature: GroundValue, parameters: Int): GroundValue =
    signature.asFunctionType match {
      case Some((_, codomain)) if parameters > 0 => resultHead(codomain, parameters - 1)
      case _                                     => signature
    }

  /** The ultimate application head of a (return) type expression — `F[B]` ⤳ `F`, `Int` ⤳ `Int`. */
  private def returnHead(expr: OperatorResolvedExpression): OperatorResolvedExpression = expr match {
    case OperatorResolvedExpression.FunctionApplication(target, _) => returnHead(target.value)
    case other                                                     => other
  }

  /** Whether this value declares a `^Meta` transfer — its [[RefinementChannelProcessor.metaCompanionName]] in its
    * module's compiler pool, exactly what
    * [[com.vanillasource.eliot.eliotc.core.processor.MetaTransferDesugarer]] emits from a `{ ... }` return brace.
    *
    * The name is the transfer's *exactly*, so a `where` predicate's companion (`name$Where`, the same namespace) never
    * answers here — `where` is a stated contract, never derived, and R3 leaves it alone.
    *
    * Reading the same name the channel reads is deliberate: R3 then flags exactly the situation in which the channel
    * would prefer a companion to a bodied value's own code. Since the name keeps the value's own qualifier
    * (docs/meta.md §4.2), a bodied `add` is no longer answered for by an ability-implementation
    * sibling's brace in the same module — the one shape in which R3 reported a brace the value does not carry.
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

  private def reportSlotsWithoutMeta(mv: MonomorphicValue, typeName: ValueFQN): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        s"This native value states meta-information, but '${typeName.name.name}' carries none; remove the { ... } " +
          s"return brace."
      )
    )

  private def reportStated(mv: MonomorphicValue): CompilerIO[Unit] =
    compilerAbort[Unit](
      mv.name.as(
        "This value has a body, so its meta-information is derived from that body, but it also states a meta " +
          "transfer; remove the { ... } return brace, which only a native value may carry."
      )
    )
}
