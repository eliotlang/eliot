package com.vanillasource.eliot.eliotc.ability.util

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.monomorphize.unify.Unifier
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Structural ability-pattern matching via the monomorphize NbE unifier.
  *
  * A query evaluates one or more marker-function ORE signatures into the semantic domain, treating every abstract
  * ability-typed reference as a fresh [[VFlex]] and every type constructor as a [[VTopDef]] head. Leading
  * type-parameter lambdas are peeled into fresh metas and the remaining curried function type is walked to
  * extract pattern arguments. Matching and overlap questions then reduce to [[Unifier]] calls.
  *
  * All ability pattern questions — impl matching at call sites, overlap detection at definition time, and
  * signature compatibility — route through this module.
  */
object AbilityMatcher {

  /** The outcome of a successful [[matchImpl]]: the GroundValue each of the impl's leading type parameters was bound
    * to (declaration order), plus whether every one of those bindings was recovered by the faithful structural trace
    * ([[tracePatternFlexes]]) rather than the lossy [[flexToGround]] fallback. A guard must not be evaluated over a
    * fallback-collapsed binding (it could compare equal wrongly — ability-guards §3.1 fail-safe), so the discharge
    * refuses when `allTraced` is false.
    */
  case class Match(groundArgs: Seq[GroundValue], allTraced: Boolean)

  /** Match an impl's pattern (given as its marker-function ORE signature) against a concrete tuple of query type
    * arguments. On a match, returns the [[Match]] binding each of the impl's leading type parameters to a GroundValue,
    * in declaration order; returns None when there is no match.
    */
  def matchImpl(
      markerSignature: Sourced[OperatorResolvedExpression],
      queryArgs: Seq[GroundValue],
      platform: Platform
  ): CompilerIO[Option[Match]] =
    for {
      setup <- setupEvaluation(Seq(markerSignature), platform)
      peeled = peelPattern(setup, markerSignature)
    } yield attemptMatch(peeled, queryArgs, markerSignature)

  /** The type parameters an impl marker's **pattern** mentions — the ones [[matchImpl]] recovers from the query. A
    * leading binder the pattern does not mention occurs in no pattern argument, so the match has nothing to trace it
    * from; a named implementation may not declare one (`docs/effects.md` D29).
    */
  def patternParameters(markerSignature: Sourced[OperatorResolvedExpression]): Set[String] =
    OperatorResolvedExpression.SignatureView.of(markerSignature).parameters.flatMap(p => mentioned(p.value)).toSet

  private def mentioned(expr: OperatorResolvedExpression): Seq[String] = expr match {
    case OperatorResolvedExpression.ParameterReference(name)     => Seq(name.value)
    case OperatorResolvedExpression.FunctionApplication(t, a)    => mentioned(t.value) ++ mentioned(a.value)
    case OperatorResolvedExpression.ValueReference(_, typeArgs)  => typeArgs.flatMap(ta => mentioned(ta.value))
    case OperatorResolvedExpression.FunctionLiteral(_, pt, body) =>
      pt.toSeq.flatMap(p => mentioned(p.value)) ++ mentioned(body.value)
    case _                                                       => Seq.empty
  }

  /** Do two impl marker patterns overlap — i.e. would some concrete query tuple match both? Used for
    * definition-time overlap detection on implementations of the same ability in the same module.
    *
    * This is a purely *structural* question about the argument patterns; it deliberately ignores the markers' `where`
    * guards. The definition-time lint layers the guard policy on top (defer whenever either marker is guarded, since
    * guard disjointness is undecidable) via [[isUnguarded]] — see ability-guards §3.2.
    */
  def patternsOverlap(
      sigA: Sourced[OperatorResolvedExpression],
      sigB: Sourced[OperatorResolvedExpression],
      platform: Platform
  ): CompilerIO[Boolean] =
    for {
      setup <- setupEvaluation(Seq(sigA, sigB), platform)
      peeledA = peelPattern(setup, sigA)
      peeledB = peelPattern(peeledA.setup, sigB)
    } yield attemptOverlap(peeledB.setup, peeledA.args, peeledB.args, sigA)

  /** Whether an impl marker carries no real `where` guard — i.e. its return-type slot is exactly the default
    * `eliot.lang.Bool::true` reference that [[com.vanillasource.eliot.eliotc.ast.fact.ImplementBlock]] installs when
    * there is no `where` clause (or the author wrote `where true`, which is semantically unguarded — ability-guards
    * §2.3).
    *
    * Shared by the two guard clients so they agree on what "unguarded" means: the Stage-2 use-site discharge
    * (`AbilityImplementationProcessor`) keeps an unguarded candidate verbatim without monomorphizing the marker, and
    * the Stage-3 definition-time overlap lint (`ModuleAbilityOverlapCheckProcessor`) reports overlap only when *both*
    * markers are unguarded, deferring any guarded pair to the use site (§3.2).
    */
  def isUnguarded(markerSignature: Sourced[OperatorResolvedExpression]): Boolean =
    OperatorResolvedExpression.SignatureView.of(markerSignature).returnType.value match {
      case OperatorResolvedExpression.ValueReference(name, _) => name.value == WellKnownTypes.boolTrueFQN
      case _                                                  => false
    }

  /** Check that an impl method's signature is compatible with the ability's abstract method signature.
    *
    * The abstract method's leading type parameters are zipped with the impl marker's pattern arguments: the
    * ability-level params get bound to the pattern args, and any remaining method-level params stay as free metas that
    * unify with the impl side's method-level metas by position. The impl method body is then unified against the
    * (substituted) abstract method body.
    */
  def signaturesCompatible(
      abstractMethodSig: Sourced[OperatorResolvedExpression],
      implMethodSig: Sourced[OperatorResolvedExpression],
      implMarkerSig: Sourced[OperatorResolvedExpression],
      platform: Platform
  ): CompilerIO[Boolean] =
    for {
      setup                         <- setupEvaluation(Seq(abstractMethodSig, implMethodSig, implMarkerSig), platform)
      (s1, absParams, absBody)       = peelToBody(setup, abstractMethodSig)
      (s2, _, implBody)              = peelToBody(s1, implMethodSig)
      marker                         = peelPattern(s2, implMarkerSig)
    } yield attemptSigCompat(
      marker.setup,
      absParams,
      marker.args,
      absBody,
      implBody,
      abstractMethodSig
    )

  // ---- Phase 1: classify every transitively-referenced ORE value ----

  private sealed trait Binding
  private object Binding {
    case object Constructor                                    extends Binding
    case object AbstractAbility                                extends Binding
    case class Body(body: Sourced[OperatorResolvedExpression]) extends Binding
  }

  /** Classify every value `expr` references, transitively, in the pool of `platform` — the pool the patterns being
    * matched live in, since a compiler-pool-only name (a structural meta's record, `docs/meta.md` §13.2) has no runtime
    * declaration to look up.
    */
  private def collectBindings(
      expr: OperatorResolvedExpression,
      acc: Map[ValueFQN, Binding],
      platform: Platform
  ): CompilerIO[Map[ValueFQN, Binding]] =
    OperatorResolvedExpression.foldValueReferences(expr, acc) { (map, name) =>
      if (map.contains(name.value) || name.value == WellKnownTypes.typeFQN) map.pure[CompilerIO]
      else classifyValueRef(name.value, map, platform)
    }

  private def classifyValueRef(
      vfqn: ValueFQN,
      acc: Map[ValueFQN, Binding],
      platform: Platform
  ): CompilerIO[Map[ValueFQN, Binding]] =
    getFactIfProduced(OperatorResolvedValue.Key(vfqn, platform)).flatMap {
      case Some(fact) =>
        // A body-less value (e.g. the abstract `Int`, whose machine representation is not a type body) stays a stuck
        // head here, so ability-pattern matching compares by type constructor (`Numeric[Int]`) instead of unfolding a
        // body into some representation and failing to match.
        fact.runtime match {
          case Some(body) =>
            // Recurse into the fetched body to discover further references; the already-in-map check in
            // `collectBindings` guards against cycles because we add the Body entry before recursing.
            collectBindings(body.value, acc + (vfqn -> Binding.Body(body)), platform)
          case None       =>
            (acc + (vfqn -> bodylessBinding(vfqn))).pure[CompilerIO]
        }
      case None       =>
        // The reference resolved to a valid FQN (resolution guarantees that) but has no `OperatorResolvedValue` in the
        // platform looked up here — e.g. a `Default`-qualified value like a marker's default `true` guard, which lives
        // only in the pool being matched and is dropped from the pattern (`extractFunctionArgs` discards the return)
        // before evaluation. Classify it as a stuck leaf, mirroring the body-less case, rather than aborting: pattern
        // matching never needs its value, and a genuinely broken reference is reported at its own definition.
        (acc + (vfqn -> bodylessBinding(vfqn))).pure[CompilerIO]
    }

  /** How to bind a referenced value whose body is unavailable (body-less, or no fact produced in this platform): an
    * ability member stays a fresh meta so equal references unify; anything else is a stuck constructor head.
    */
  private def bodylessBinding(vfqn: ValueFQN): Binding =
    vfqn.name.qualifier match {
      case _: Qualifier.Ability | _: Qualifier.AbilityImplementation => Binding.AbstractAbility
      case _                                                         => Binding.Constructor
    }

  // ---- Phase 2: build a pure Evaluator over the classifications ----

  private case class Setup(evaluator: Evaluator, flexStore: FlexStore)

  private def setupEvaluation(sigs: Seq[Sourced[OperatorResolvedExpression]], platform: Platform): CompilerIO[Setup] =
    sigs.foldLeft(Map.empty[ValueFQN, Binding].pure[CompilerIO])((acc, sig) =>
      acc.flatMap(collectBindings(sig.value, _, platform))
    ).map(buildSetup)

  private def buildSetup(classifications: Map[ValueFQN, Binding]): Setup = {
    // Pre-allocate a stable meta per abstract ability-typed reference so that multiple references to the same
    // abstract associated type unify with each other without a substitution pass.
    val (store0, abilityFlexIds) = classifications.foldLeft((FlexStore.empty, Map.empty[ValueFQN, FlexId])) {
      case ((s, ids), (vfqn, Binding.AbstractAbility)) =>
        val (id, next) = s.fresh
        (next, ids + (vfqn -> id))
      case (acc, _)                                    => acc
    }

    // Mutual recursion: bindings for runtime-bodied values evaluate their body through `evaluator`, and a `Lazy`
    // defers evaluation until the VTopDef is forced. Both vals resolve in any order at first access.
    lazy val bindings: Map[ValueFQN, SemValue]      = classifications.map {
      case (vfqn, Binding.Constructor)     => vfqn -> VTopDef(vfqn, None, Spine.SNil)
      case (vfqn, Binding.AbstractAbility) => vfqn -> VFlex(abilityFlexIds(vfqn), Spine.SNil)
      case (vfqn, Binding.Body(body))      =>
        vfqn -> VTopDef(vfqn, Some(Lazy(evaluator.eval(Env.empty, body.value))), Spine.SNil)
    } + (WellKnownTypes.typeFQN -> VType)
    lazy val evaluator: Evaluator                    = new Evaluator(vfqn => bindings.get(vfqn))

    Setup(evaluator, store0)
  }

  // ---- Phase 3: peel leading VLams into metas and extract pattern args ----

  private case class Peeled(
      setup: Setup,
      paramFlexes: Seq[(String, FlexId)],
      args: Seq[SemValue]
  )

  private def peelPattern(setup: Setup, sig: Sourced[OperatorResolvedExpression]): Peeled = {
    val evaluated                     = setup.evaluator.eval(Env.empty, sig.value)
    val (body, nextStore, paramFlexes) = peelLambdas(evaluated, setup.flexStore, Seq.empty)
    val args                          = extractFunctionArgs(body, nextStore)
    Peeled(Setup(setup.evaluator, nextStore), paramFlexes, args)
  }

  /** Peel leading lambdas but return the whole body (not decomposed into function args). Used for signature
    * compatibility checks where the body is unified whole.
    */
  private def peelToBody(
      setup: Setup,
      sig: Sourced[OperatorResolvedExpression]
  ): (Setup, Seq[(String, FlexId)], SemValue) = {
    val evaluated                     = setup.evaluator.eval(Env.empty, sig.value)
    val (body, nextStore, paramFlexes) = peelLambdas(evaluated, setup.flexStore, Seq.empty)
    (Setup(setup.evaluator, nextStore), paramFlexes, body)
  }

  @scala.annotation.tailrec
  private def peelLambdas(
      sv: SemValue,
      flexStore: FlexStore,
      acc: Seq[(String, FlexId)]
  ): (SemValue, FlexStore, Seq[(String, FlexId)]) =
    Evaluator.force(sv, flexStore) match {
      case VLam(name, closure) =>
        val (id, nextStore) = flexStore.fresh
        val flexVal         = VFlex(id, Spine.SNil)
        peelLambdas(closure(flexVal), nextStore, acc :+ (name, id))
      case other               => (other, flexStore, acc)
    }

  /** Walk a curried function type (a [[VTopDef]] at the Function FQN with spine `[paramType, returnType]`) and
    * collect argument positions, dropping the final return type.
    */
  private def extractFunctionArgs(sv: SemValue, flexStore: FlexStore): Seq[SemValue] =
    Evaluator.force(sv, flexStore) match {
      case VTopDef(fqn, _, spine, _) if fqn == WellKnownTypes.functionDataTypeFQN =>
        spine.toList match {
          case paramType :: returnType :: Nil => paramType +: extractFunctionArgs(returnType, flexStore)
          case _                              => Seq.empty
        }
      case _                                                                   => Seq.empty
    }

  // ---- Matching and overlap, delegating to Unifier ----

  private def attemptMatch(
      peeled: Peeled,
      queryArgs: Seq[GroundValue],
      sourceRef: Sourced[?]
  ): Option[Match] =
    if (peeled.args.size != queryArgs.size) None
    else {
      val querySems = queryArgs.map(Evaluator.groundToSem)
      val context   = sourceRef.as("ability-match")
      val unified   = peeled.args.zip(querySems).foldLeft(Unifier.create(peeled.setup.flexStore, 0)) {
        case (u, (pat, q)) => u.unify(pat, q, context)
      }.drain()
      if (unified.errors.nonEmpty) None
      else {
        // For each impl type parameter meta, prefer its original query slot (walked structurally from the pattern
        // tree alongside the raw GroundValue) so that data-type field naming is preserved verbatim. Fall back to
        // quoting the flex store for cases we can't structurally trace (runtime-bodied aliases, etc.).
        val traced: Map[FlexId, GroundValue] =
          peeled.args.zip(queryArgs).flatMap { case (p, q) => tracePatternFlexes(p, q) }.toMap
        val groundArgs                       = peeled.paramFlexes.map { case (_, id) =>
          traced.getOrElse(id, flexToGround(id, unified.flexStore))
        }
        val allTraced                        = peeled.paramFlexes.forall { case (_, id) => traced.contains(id) }
        Some(Match(groundArgs, allTraced))
      }
    }

  /** Walk a pattern [[SemValue]] alongside the original query [[GroundValue]] that it was unified against, and
    * record every [[FlexId]] → [[GroundValue]] pairing that appears at a structurally-aligned position.
    *
    * Preserves the exact query-side ground values on complex bindings rather than re-quoting the meta's solution
    * through the flex store, so the returned GroundValue matches the one present on the call site.
    */
  private def tracePatternFlexes(
      pattern: SemValue,
      query: GroundValue
  ): Seq[(FlexId, GroundValue)] = pattern match {
    case VFlex(id, Spine.SNil)  =>
      Seq(id -> query)
    case VTopDef(fqn, _, spine, _) =>
      query match {
        case GroundValue.Structure(queryFqn, args, _) if queryFqn == fqn =>
          spine.toList.zip(args).flatMap { case (s, g) => tracePatternFlexes(s, g) }
        case _                                                           => Seq.empty
      }
    case _                      => Seq.empty
  }

  private def attemptSigCompat(
      setup: Setup,
      absParams: Seq[(String, FlexId)],
      markerArgs: Seq[SemValue],
      absBody: SemValue,
      implBody: SemValue,
      sourceRef: Sourced[?]
  ): Boolean = {
    val context = sourceRef.as("ability-sig-compat")
    // Bind the leading (ability-level) abstract type params to the impl marker's pattern args via unification.
    // Any remaining abstract params (method-level) stay as free metas and will unify with the impl method's
    // corresponding metas during body unification.
    val bound   = absParams.zip(markerArgs).foldLeft(Unifier.create(setup.flexStore, 0)) {
      case (u, ((_, absFlexId), patternArg)) =>
        u.unify(VFlex(absFlexId, Spine.SNil), patternArg, context)
    }
    bound.unify(absBody, implBody, context).drain().errors.isEmpty
  }

  private def attemptOverlap(
      setup: Setup,
      argsA: Seq[SemValue],
      argsB: Seq[SemValue],
      sourceRef: Sourced[?]
  ): Boolean =
    if (argsA.size != argsB.size) false
    else {
      val context = sourceRef.as("ability-overlap")
      val unified = argsA.zip(argsB).foldLeft(Unifier.create(setup.flexStore, 0)) {
        case (u, (a, b)) => u.unify(a, b, context)
      }.drain()
      unified.errors.isEmpty
    }

  /** Read back a meta's solution as a GroundValue, mirroring the previous implementation's behaviour of defaulting
    * complex (non-simple-constructor, non-primitive) bindings to [[GroundValue.Type]].
    */
  private def flexToGround(id: FlexId, flexStore: FlexStore): GroundValue =
    flexStore.lookup(id) match {
      case None      => GroundValue.Type
      case Some(sem) =>
        Evaluator.force(sem, flexStore) match {
          case VType                       => GroundValue.Type
          case VConst(g)                   => g
          case VTopDef(fqn, _, Spine.SNil, _) =>
            GroundValue.Structure(fqn, Seq.empty, GroundValue.Type)
          case _                           => GroundValue.Type
        }
    }
}
