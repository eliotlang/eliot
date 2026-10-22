package com.vanillasource.eliot.eliotc.ability.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.ModuleAbilityOverlapCheck
import com.vanillasource.eliot.eliotc.ability.util.AbilityMatcher
import com.vanillasource.eliot.eliotc.module.fact.{ModuleAbilities, ValueFQN}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** Checks, for a given `(module, abilityName)` pair, that no two *unguarded* implementations of that ability in that
  * module have structurally overlapping patterns.
  *
  * Runs at definition time — as soon as any call site triggers ability resolution for this ability — so overlap errors
  * point at the offending implementations rather than at call sites that merely expose the ambiguity.
  *
  * Since ability-guards (§3.2) this is a **conservative lint, not the soundness mechanism**: the real coherence rule is
  * "exactly one candidate survives at every manifest use site", enforced by the use-site discharge + ambiguity check in
  * `AbilityImplementationProcessor`. Guard disjointness (`∀x. ¬(g₁(x) ∧ g₂(x))`) is undecidable, so a `where` guard on
  * either impl makes the pair *undecided* here and it is deferred silently to the use site — only two provably
  * overlapping *unguarded* impls are rejected at definition time (exactly the pre-guards behaviour).
  *
  * Cross-module overlap is likewise detected only at call time by the ambiguity check in
  * `AbilityImplementationProcessor`: the definition-time check here can only see impls in one module at a time, whereas
  * impls can legitimately live in either the ability's module or a type's module.
  */
class ModuleAbilityOverlapCheckProcessor
    extends TransformationProcessor[ModuleAbilities.Key, ModuleAbilityOverlapCheck.Key](key =>
      ModuleAbilities.Key(key.moduleName, key.platform)
    ) {

  override protected def generateFromKeyAndFact(
      key: ModuleAbilityOverlapCheck.Key,
      impls: ModuleAbilities
  ): CompilerIO[ModuleAbilityOverlapCheck] = {
    // Every marker function for this ability in this module. The marker's local name equals the ability name and its
    // qualifier carries the impl's index, which uniquely identifies the implementation.
    val markers: Seq[ValueFQN] = impls.anonymousMarkersOf(key.abilityName)
    for {
      signatures <- markers.traverse(loadMarkerSignature(_, key.platform))
      resolved    = markers.zip(signatures).collect { case (vfqn, Some(sig)) => (vfqn, sig) }
      _          <- reportOverlaps(resolved, key.platform)
    } yield ModuleAbilityOverlapCheck(key.moduleName, key.abilityName, key.platform)
  }

  /** Returns the marker function's ORE signature, or `None` if the marker has no resolved value fact. */
  private def loadMarkerSignature(
      markerVfqn: ValueFQN,
      platform: Platform
  ): CompilerIO[Option[Sourced[OperatorResolvedExpression]]] =
    getFactIfProduced(OperatorResolvedValue.Key(markerVfqn, platform)).map(
      _.map(resolved => resolved.signature)
    )

  private def reportOverlaps(
      withSignatures: Seq[(ValueFQN, Sourced[OperatorResolvedExpression])],
      platform: Platform
  ): CompilerIO[Unit] =
    (for {
      i <- withSignatures.indices
      j <- (i + 1) until withSignatures.size
    } yield (withSignatures(i), withSignatures(j))).toList.traverse_ {
      case ((v1, sig1), (v2, sig2)) =>
        // ability-guards §3.2: the definition-time check is a conservative lint, not the soundness mechanism. Report
        // overlap only when the patterns structurally overlap AND *both* markers are unguarded. A `where` guard on
        // either side makes disjointness undecidable here, so we defer silently to the use-site discharge, which
        // decides applicability exactly at each concrete instantiation.
        if (AbilityMatcher.isUnguarded(sig1) && AbilityMatcher.isUnguarded(sig2))
          for {
            overlaps <- recover(AbilityMatcher.patternsOverlap(sig1, sig2, platform))(false)
            _        <- if (overlaps) reportOverlapError(v1, v2, platform) else ().pure[CompilerIO]
          } yield ()
        else ().pure[CompilerIO]
    }

  private def reportOverlapError(v1: ValueFQN, v2: ValueFQN, platform: Platform): CompilerIO[Unit] =
    // Surface the error at both impls' marker source positions so the user sees both sites.
    for {
      r1 <- getFactOrAbort(OperatorResolvedValue.Key(v1, platform))
      r2 <- getFactOrAbort(OperatorResolvedValue.Key(v2, platform))
      _  <- compilerError(
              r1.name.map(qn =>
                s"Overlapping ability implementation: '${qn.name}' overlaps with another implementation" +
                  s" of the same ability in the same module."
              )
            )
      _  <- compilerError(
              r2.name.map(qn =>
                s"Overlapping ability implementation: '${qn.name}' overlaps with another implementation" +
                  s" of the same ability in the same module."
              )
            )
    } yield ()
}
