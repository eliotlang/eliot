package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.util.ImplementationMarkerUtils
import com.vanillasource.eliot.eliotc.compiler.cache.UpToDate
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleConstructors,
  ModuleName,
  QualifiedName,
  Qualifier,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{BindingContribution, ContributedBinding, GroundValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor

/** The `match` native contributor: emits the total [[ContributedBinding]] under [[ContributedBinding.matchLabel]] for
  * the abstract `match`-dispatch ability implementations — and `None` for every other name. These FQNs are body-less,
  * so the user supplier contributes `None` for them; the merger's native precedence makes this contributor's reduction
  * win for checking, with no ordering to arrange.
  *
  *   - **`PatternMatch.handleCases`** (one impl per data type): a curried [[VNative]] collecting `(value, h1, …, hn)`.
  *     The data type's value constructors, in source-declaration order, are baked in at fact-generation time. On a
  *     concrete `value` (a body-less constructor [[VTopDef]]), it applies the handler at the constructor's declaration
  *     index to the constructor's fields (or to a unit placeholder for a field-less constructor).
  *   - **`TypeMatch.typeMatch`** (one impl per constructor): a curried [[VNative]] collecting `(obj, matched,
  *     notMatched)`. The target type-constructor name is baked in. On a concrete `obj`, if its head matches the target
  *     it applies `matched` to `obj`'s fields, otherwise applies `notMatched` to a unit placeholder.
  *
  * On a symbolic (non-concrete) scrutinee the native stays stuck — open-term match dispatch is deferred (plan P4). All
  * dispatch is pure ([[SemValue]] `applyValue` only); the constructor metadata IO happens here, at fact-generation time.
  */
class MatchNativesProcessor extends SingleFactProcessor[ContributedBinding.Key] {

  override def generateSingleFact(key: ContributedBinding.Key): CompilerIO[ContributedBinding] =
    if (key.label =!= ContributedBinding.matchLabel) abort
    // A `None` contribution (every name that is not a match-dispatch impl — the common case) reads no fact, so without
    // an anchor its entry has an empty dependency set and the incremental cache treats it as a source leaf, regenerating
    // it on every no-change build. Depending on the always-clean `UpToDate` leaf (the value is immaterial, only the edge
    // matters) makes it a drillable non-leaf the cache proves unchanged instead. Same pattern, and same deliberate
    // tolerance (`getFactIfProduced`), as `SystemNativesProcessor`.
    else
      getFactIfProduced(UpToDate.Key()) >>
        matchReduction(key.vfqn).map(red => ContributedBinding(key.vfqn, key.label, red.map(BindingContribution.Leaf(_))))

  /** The match-dispatch native reduction for `vfqn`, or `None` (totality) if `vfqn` is not a `handleCases`/`typeMatch`
    * implementation method.
    *
    * The type-name / constructor reads are **pool-guarded** ([[DeclaringPool]]): the impl marker and constructors are
    * read only on the pool that actually declares `vfqn`, so a match impl present in one pool but absent from the other
    * never trips the `UnifiedModuleValueProcessor` "Could not find" build error. Today every `data` type (and its
    * generated match impls) lives in the runtime pool, so this always selects `Platform.Runtime` and is byte-identical
    * to the former unconditional runtime reads; the compiler fallback future-proofs a compiler-pool-only `data` type.
    */
  private def matchReduction(vfqn: ValueFQN): CompilerIO[Option[SemValue]] =
    if (WellKnownTypes.isPatternMatchHandleCases(vfqn) || WellKnownTypes.isTypeMatchTypeMatch(vfqn))
      DeclaringPool.of(vfqn).flatMap {
        case Some(platform) =>
          (if (WellKnownTypes.isPatternMatchHandleCases(vfqn)) buildHandleCases(vfqn, platform)
           else buildTypeMatch(vfqn, platform)).map(_.some)
        case None           => none[SemValue].pure[CompilerIO]
      }
    else none[SemValue].pure[CompilerIO]

  private def buildHandleCases(vfqn: ValueFQN, platform: Platform): CompilerIO[SemValue] =
    for {
      typeName <- requiredTypeName(vfqn, WellKnownTypes.patternMatchAbilityName, platform)
      ordered  <- orderedConstructors(vfqn.moduleName, QualifiedName(typeName, Qualifier.Type), platform)
    } yield handleCasesNative(ordered)

  private def buildTypeMatch(vfqn: ValueFQN, platform: Platform): CompilerIO[SemValue] =
    requiredTypeName(vfqn, WellKnownTypes.typeMatchAbilityName, platform).map(typeMatchNative)

  private def requiredTypeName(vfqn: ValueFQN, abilityName: String, platform: Platform): CompilerIO[String] =
    ImplementationMarkerUtils.firstPatternTypeConstructorName(vfqn, abilityName, platform).flatMap {
      case Some(name) => name.pure[CompilerIO]
      case None       => abort
    }

  /** The value constructors of a data type, in source-declaration order (= handler order). The constructor
    * identity / ordering derivation lives in [[ModuleConstructors]] (the sanctioned [[RoleHint.ValueConstructor]]
    * consumer); this native-emitting half of match desugaring just reads the shared index.
    */
  private def orderedConstructors(
      moduleName: ModuleName,
      dataType: QualifiedName,
      platform: Platform
  ): CompilerIO[Seq[ValueFQN]] =
    getFactOrAbort(ModuleConstructors.Key(moduleName, platform)).map(_.of(dataType))

  /** `handleCases(value, h1, …, hn)`: dispatch a concrete constructor `value` to its own handler, one per constructor
    * in declaration order.
    */
  private def handleCasesNative(ordered: Seq[ValueFQN]): SemValue =
    VNative(VType, value => collectHandlers(ordered, value, Vector.empty))

  private def collectHandlers(ordered: Seq[ValueFQN], value: SemValue, handlers: Vector[SemValue]): SemValue =
    if (handlers.size === ordered.size) dispatchHandleCases(ordered, value, handlers)
    else VNative(VType, handler => collectHandlers(ordered, value, handlers :+ handler))

  private def dispatchHandleCases(ordered: Seq[ValueFQN], value: SemValue, handlers: Vector[SemValue]): SemValue =
    value match {
      case VTopDef(ctorFqn, None, spine, _) if ordered.contains(ctorFqn) =>
        applyHandlerToFields(handlers(ordered.indexOf(ctorFqn)), spine.toList)
      case _                                                          => stuck(value)
    }

  /** `typeMatch(obj, matched, notMatched)`: if `obj`'s head is the target type constructor, run `matched` on its
    * fields, otherwise run `notMatched`.
    */
  private def typeMatchNative(targetName: String): SemValue =
    VNative(
      VType,
      obj => VNative(VType, matched => VNative(VType, notMatched => dispatchTypeMatch(targetName, obj, matched, notMatched)))
    )

  private def dispatchTypeMatch(targetName: String, obj: SemValue, matched: SemValue, notMatched: SemValue): SemValue =
    obj match {
      case VTopDef(headFqn, None, spine, _) if headFqn.name.name === targetName =>
        applyHandlerToFields(matched, spine.toList)
      case VTopDef(_, None, _, _)                                               =>
        Evaluator.applyValue(notMatched, Evaluator.unitValue)
      case _                                                                 => stuck(obj)
    }

  /** Apply a pattern handler to a constructor's fields. A field-less constructor's handler still takes one (ignored)
    * argument — the desugarer always emits a wildcard lambda — so it is applied to a unit placeholder.
    */
  private def applyHandlerToFields(handler: SemValue, fields: List[SemValue]): SemValue =
    if (fields.isEmpty) Evaluator.applyValue(handler, Evaluator.unitValue)
    else fields.foldLeft(handler)(Evaluator.applyValue)

  /** Stuck dispatch on a symbolic scrutinee. Open-term re-firing is deferred (plan P4); for now this collapses to an
    * opaque neutral, mirroring [[Evaluator.applyValue]]'s `VNative`-on-neutral behaviour.
    */
  private def stuck(scrutinee: SemValue): SemValue =
    VNeutral(NeutralHead.Reserved(NeutralHead.Marker.Match), Spine.SNil :+ scrutinee)

  /** Placeholder argument supplied to field-less constructor handlers (mirrors the JVM backend passing `null`). */
}
