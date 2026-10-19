package com.vanillasource.eliot.eliotc.monomorphize.check

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Immutable state for the bidirectional type checker.
  *
  * The checker keeps the **typing context Γ** and the **evaluation environment ρ** separate (the textbook NbE-checker
  * shape), instead of overloading one `Env` for both:
  *   - [[gamma]] (Γ) maps a parameter name to its **type** — read by [[Checker.infer]]'s `ParameterReference`.
  *   - [[rho]] (ρ) maps a parameter name to its **value** for the evaluator: an erased type parameter to its
  *     concrete value, a runtime value parameter to a **fresh neutral** standing for its not-yet-known runtime value
  *     (so a dependent type stays abstract in that parameter — genuine dependent Π), a peeled instantiation meta to the
  *     meta itself.
  *
  * The two grow in lockstep (every `bind*` extends both), so their de Bruijn levels stay in sync.
  *
  * Per-metavariable kind bookkeeping (which instantiation metas are higher-kinded, and to what kind) is **not** held
  * here as a separate side-table: it lives in the
  * [[com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier.higherKindedMetas]] map on the [[unifier]], and
  * [[recordHigherKindedMeta]] below delegates into it.
  *
  * @param gamma
  *   Γ: the typing context — parameter name → its type (de Bruijn level environment).
  * @param rho
  *   ρ: the evaluation environment — parameter name → its value (de Bruijn level environment); consumed by the
  *   [[Evaluator]].
  * @param unifier
  *   The unifier (carries meta store, depth, postponed, errors, and the per-meta role map)
  * @param bindingCache
  *   Cache of fetched NativeBinding SemValues, keyed by ValueFQN.
  * @param abilityResolutions
  *   Map from each ability-qualified value reference to its resolved concrete impl. Filled by the drain-resolution
  *   loop; absence means the ref stays abstract (constraint-covered) at quoting time.
  *
  * The key is [[CheckState.AbilityResolutionKey]] — the source-positioned FQN **and the ability-level type arguments
  * the impl was chosen for**. Both halves are load-bearing: the position+FQN is what the post-drain quoter can
  * re-derive while walking the tree, but it is *not* a node identity. A compiler-synthesized reference inherits the
  * span of the expression it is written for (the `row` phase builds its references with `value.as(...)`), so two
  * references to one ability method, resolved at *different* type arguments, can share an identical position and FQN.
  * Keyed by position alone they collapse to one entry: the second reference is filtered out as "already resolved" and
  * silently inherits the first one's impl — a miscompilation with no diagnostic anywhere.
  *
  * @param abilityArities
  *   Ability-method FQN → its ability's arity (how many leading type arguments of a method reference belong to the
  *   ability rather than the method). Recorded by [[AbilityResolver]], which fetches it anyway to slice the impl query;
  *   read back by the post-drain quoter, which must slice a reference's type arguments the same way to rebuild the
  *   [[abilityResolutions]] key and has no `getFact` of its own.
  * @param sharedHoles
  *   The metavariable each **keyed** hole stands for — `Inferred[k]`, which the `row` phase writes at every place that
  *   must agree on one type the checker reads off the call (`docs/effects.md` §8 item 36): `pick`'s `A` in
  *   `pick(1) with mapping(x -> show(x))` and `mapping$with`'s `T`. The first occurrence of a key mints the meta.
  * @param handleHoles
  *   Every handle position the checker met, in the order it met them (`docs/effects.md` D36 point 4,
  *   [[HandleHoleResolver]]).
  * @param handleChoices
  *   The handle each decided hole took, by hole id.
  * @param handedBinders
  *   The indices of each referenced callee's *handed* binders (`docs/effects.md` D36 point 6), the types of the handles
  *   its body hands its blocks, which this check reads off the callee's checked value.
  * @param ownHanded
  *   This value's own handed binders its key leaves open, by name, each the metavariable its runs solve.
  */
case class CheckState(
    gamma: Env,
    rho: Env,
    unifier: Unifier,
    bindingCache: Map[ValueFQN, Option[SemValue]],
    abilityResolutions: Map[CheckState.AbilityResolutionKey, (ValueFQN, Seq[GroundValue])],
    abilityArities: Map[ValueFQN, Int] = Map.empty,
    sharedHoles: Map[BigInt, VMeta] = Map.empty,
    handleHoles: Seq[HandleHoleResolver.Hole] = Seq.empty,
    handleChoices: Map[Int, String] = Map.empty,
    handedBinders: Map[ValueFQN, Seq[Int]] = Map.empty,
    ownHanded: Seq[(String, SemValue)] = Seq.empty
) {

  /** Record a higher-kinded type-parameter instantiation meta with its expected kind, for post-drain verification. */
  def recordHigherKindedMeta(id: MetaId, expectedKind: SemValue, context: Sourced[String]): CheckState =
    withUnifier(unifier.recordHigherKindedMeta(id, expectedKind, context))

  /** The neutral a runtime value parameter binds to in ρ: a fresh rigid variable at the current ρ level, standing for
    * the parameter's not-yet-known runtime value. Read *before* [[bindValueParam]] so the checker can substitute it into
    * a dependent codomain (`codomain(neutral)`, genuine dependent Π).
    */
  def paramNeutral(name: String): SemValue =
    VNeutral(NeutralHead.Param(rho.level, name), Spine.SNil)

  /** Bind a runtime value parameter: its declared type in Γ and a fresh neutral standing for its runtime value in ρ. A
    * value-position reference reads the type from Γ; the evaluator reads the neutral from ρ, so a dependent type stays
    * abstract in the parameter.
    */
  def bindValueParam(name: String, tpe: SemValue): CheckState =
    copy(gamma = gamma.bind(name, tpe), rho = rho.bind(name, paramNeutral(name)))

  /** Bind an erased type parameter: its type in Γ and its evaluable value in ρ. Both are computed by the caller
    * from the ground argument ([[TypeStackLoop.establishSignature]]) — Γ from the argument's declared type, ρ from its
    * `groundToSem` form (so the reification gate and type-level code see a data value as its constructor `VTopDef`).
    */
  def bindTypeStackParam(name: String, tpe: SemValue, value: SemValue): CheckState =
    copy(gamma = gamma.bind(name, tpe), rho = rho.bind(name, value))

  /** Bind a peeled instantiation meta (a leftover type parameter): the meta in both Γ and ρ — its value is the meta
    * (the evaluator keeps it abstract until unification solves it) and, referenced in value position, its type slot is
    * the meta too (it resolves through the metastore).
    */
  def bindTypeParam(name: String, meta: SemValue): CheckState =
    copy(gamma = gamma.bind(name, meta), rho = rho.bind(name, meta))

  def withUnifier(u: Unifier): CheckState = copy(unifier = u)

  def cacheBinding(vfqn: ValueFQN, value: Option[SemValue]): CheckState =
    copy(bindingCache = bindingCache + (vfqn -> value))

  def recordAbilityResolution(
      key: CheckState.AbilityResolutionKey,
      impl: (ValueFQN, Seq[GroundValue])
  ): CheckState =
    copy(abilityResolutions = abilityResolutions + (key -> impl))

  /** Record an ability's arity under one of its method FQNs. See [[abilityArities]]. */
  def recordAbilityArity(methodVfqn: ValueFQN, arity: Int): CheckState =
    copy(abilityArities = abilityArities.updated(methodVfqn, arity))

  /** Build an [[Evaluator]] from this state. Pure — only reads `bindingCache`. */
  def makeEvaluator: Evaluator =
    new Evaluator(vfqn => bindingCache.getOrElse(vfqn, None))
}

object CheckState {
  def initial: CheckState = CheckState(
    Env.empty,
    Env.empty,
    Unifier.create(MetaStore.empty, 0),
    Map.empty,
    Map.empty
  )

  /** The identity of one resolved ability reference: the source-positioned method FQN, plus the **ability-level** type
    * arguments the impl was chosen for — the reference's own, ground, which pin it. See
    * [[CheckState.abilityResolutions]] for why the position alone is not an identity.
    */
  type AbilityResolutionKey = (Sourced[ValueFQN], Seq[GroundValue])

  /** Look one reference's resolved impl out of `resolutions`, given the ability-level type arguments the reader could
    * derive for it (`None` if they would not quote).
    *
    * Two arms, tried in order, and the second is a **fail-safe rather than a guess**:
    *   - the exact key — the reference's own ground arguments pinned this impl;
    *   - the unique entry for this position+FQN, *only if every entry recorded there agrees*. This is what preserves
    *     the pre-fix behaviour in the ordinary case (one reference, one entry, arguments re-derived identically), while
    *     a genuine collision — two impls at one span — yields `None` and the reference stays abstract, which the
    *     checker reports downstream, instead of silently picking whichever resolved first.
    */
  def lookupAbilityResolution(
      resolutions: Map[AbilityResolutionKey, (ValueFQN, Seq[GroundValue])],
      ref: Sourced[ValueFQN],
      abilityArgs: Option[Seq[GroundValue]]
  ): Option[(ValueFQN, Seq[GroundValue])] =
    abilityArgs
      .flatMap(args => resolutions.get((ref, args)))
      .orElse {
        val atRef = resolutions.collect { case ((r, _), impl) if r == ref => impl }.toSeq.distinct
        Option.when(atRef.sizeIs == 1)(atRef.head)
      }
}
