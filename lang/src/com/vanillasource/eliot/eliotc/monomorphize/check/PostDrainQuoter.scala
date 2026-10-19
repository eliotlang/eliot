package com.vanillasource.eliot.eliotc.monomorphize.check

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier => CoreQualifier}
import com.vanillasource.eliot.eliotc.module.fact.{
  QualifiedName,
  UnifiedModuleNames,
  UnifiedModuleValue,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.{Env, MetaStore, SemValue}
import com.vanillasource.eliot.eliotc.monomorphize.eval.{Evaluator, Quoter, SemExpressionEvaluator}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CompilerMonomorphicValue,
  GroundValue,
  MonomorphicExpression
}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal
import com.vanillasource.eliot.eliotc.monomorphize.processor.{EffectIntrinsics, EscalatingReducer}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.collection.mutable

/** Post-drain conversion of a [[SemExpression]] tree into a [[MonomorphicExpression]] tree.
  *
  * Every [[SemValue]] slot is quoted via [[Quoter.quote]] against the final metastore. Unresolved metas, neutrals,
  * lambdas, native applications, and unapplied top-level definitions surface as explicit `Cannot resolve type.`
  * compiler errors (with source positions taken from the enclosing expression / name) — there is no silent
  * `GroundValue.Type` fallback.
  *
  * Ability references (value refs whose qualifier is [[CoreQualifier.Ability]]) are rewritten using the pre-computed
  * [[abilityResolutions]] map: refs resolved during the drain loop emit the chosen impl's FQN and type args; refs
  * absent from the map (constraint-covered calls where the concrete impl lives at the caller's level) emit their
  * original ability FQN so downstream dispatch stays abstract.
  *
  * '''Compile→runtime reification (the staging gate).''' A value-position sub-term whose value is fully determined by
  * erased `[]`-bound parameters (e.g. `name(A)` projecting a field of an erased `A: Person`, or an erased `BigInteger`
  * bound referenced directly) has no runtime slot for those parameters, so it cannot be emitted structurally. Instead
  * it is a compile-time constant and is '''materialised''' into a literal / constructor-call tree. The quoter walks the
  * tree top-down threading an evaluation [[Env]] (ρ) seeded with the erased parameters (`monoEnv`) and extended with a
  * fresh neutral for each enclosing runtime lambda binder. For each node:
  *   - references no erased parameter ⟹ recurse structurally; nothing is ever evaluated, so ordinary runtime code
  *     (including recursive defs and large constants) is preserved verbatim and cannot diverge.
  *   - references an erased parameter together with a runtime parameter or an inner lambda ⟹ the erased dependence
  *     resolves deeper; recurse structurally into the children.
  *   - references an erased parameter and nothing runtime ⟹ evaluate, force, and read back. A fully-ground result
  *     materialises to a literal/constructor tree; a result that does not reduce to a materialisable constant (a
  *     partial application's closure, a still-abstract head) recurses into the children if the node is an application,
  *     or — at a leaf that genuinely depends on an erased parameter with no constant value — raises a fail-safe
  *     compiler error (never a silent bad emit of a runtime reference to an erased parameter).
  */
class PostDrainQuoter(
    metaStore: MetaStore,
    abilityResolutions: Map[CheckState.AbilityResolutionKey, (ValueFQN, Seq[GroundValue])],
    abilityArities: Map[ValueFQN, Int],
    monoEnv: Env,
    lookupTopDef: ValueFQN => Option[SemValue],
    platform: Platform,
    // Required (no default): defaulting it to a no-op fetch would be a silent-degradation trap — a construction site
    // that omitted it would get an escalation loop that can never escalate (terms that should reduce stay stuck), with
    // no compile error and nothing to grep for. The one production site (`TypeStackLoop`) always passes the real fetch.
    reduceInstance: (ValueFQN, Seq[GroundValue]) => CompilerIO[Option[SemValue]]
) {

  private val erasedParams: Set[String]            = monoEnv.names.toSet
  private val semEvaluator: SemExpressionEvaluator = new SemExpressionEvaluator(lookupTopDef)

  /** Deep-renormalise `v` (descending under [[SemValue.VPi]] binders, re-firing post-drain-solved natives stuck in a
    * `VPi` codomain like `Int[add(L,R), …]`) then quote it. The shared core of the three deep-quote entry points
    * ([[quoteSem]]/[[quoteSemOption]] over a [[SemValue]], [[reduceSignatureToGroundOption]] over a reduced signature),
    * which differ only in how they treat a `Left`: [[orAbort]] vs. `.toOption`.
    */
  private def quoteDeep(v: SemValue): Either[String, GroundValue] =
    Quoter.quote(0, Evaluator.renormalize(v, metaStore, lookupTopDef, deep = true), metaStore)

  /** A quoted result, or the fail-safe loud `Cannot resolve type.` abort at `at` — never a silent `Type` fallback. */
  private def orAbort(result: Either[String, GroundValue], at: Sourced[?]): CompilerIO[GroundValue] =
    result match {
      case Right(g)  => g.pure[CompilerIO]
      case Left(msg) => compilerAbort(at.as("Cannot resolve type."), Seq(msg))
    }

  /** Quote a [[SemValue]] to a [[GroundValue]]. Raises a sourced compiler error on failure.
    *
    * Deeply renormalises first (descending under [[SemValue.VPi]] binders), so stuck native applications surviving in
    * an intermediate function type — e.g. the codomain `Int[add(L,R), …]` of a curried head reference, never re-fired
    * at its application site — reduce before read-back. This is safe here (and only here): post-drain every meta is
    * solved, so the deep descent collapses no still-open obligation. Any stuck native that genuinely cannot reduce then
    * surfaces as a loud `Cannot resolve type.` instead of a silent nonsense ground `Structure` (D3 / F1).
    */
  def quoteSem(v: SemValue, at: Sourced[?]): CompilerIO[GroundValue] =
    orAbort(quoteDeep(v), at)

  /** Non-aborting variant of [[quoteSem]]: the quoted ground value, or [[None]] when it does not reduce to a quotable
    * ground form (a stuck neutral / residual lambda). The signature-twin guard path tries this first — the retired
    * `orError` combinator guard was already reduced by the shallow signature walk and quoted here — and only
    * deep-reduces (via [[reduceSignatureToGroundOption]]) when it comes back [[None]].
    */
  def quoteSemOption(v: SemValue): Option[GroundValue] =
    quoteDeep(v).toOption

  /** Reduce a checked *signature* [[SemExpression]] (a signature twin's arrow chain — the signature-unification body
    * model, §3.1) to its ground signature, or [[None]] when it does not fully reduce (a guard stuck on a leftover
    * `GroundValue.Param`), so the twin can fall back to publishing the guard's undischarged type instead of aborting
    * (signature-unification C2). Runs the stuck-driven [[reduceWithEscalation]] loop, deep-renormalises the result
    * (re-firing a dependent-bound native stuck in a `VPi` codomain, e.g. `Int[add(L,R), …]`, exactly as [[quoteSem]]
    * does), then quotes. It is the one read-back a twin takes for every return shape — an ordinary type or a guard
    * verdict (`Right(t)`/`Left(msg)`) — the shape falling out of the reduction rather than a per-shape branch.
    */
  def reduceSignatureToGroundOption(expr: SemExpression): CompilerIO[Option[GroundValue]] =
    reduceWithEscalation(monoEnv, expr, EscalatingReducer.typeLevelStuck)
      .map(reduced => quoteDeep(reduced).toOption)

  /** Quote a sourced [[SemExpression]] tree to a sourced [[MonomorphicExpression]] tree, applying the staging gate. */
  def quoteSourced(expr: Sourced[SemExpression]): CompilerIO[Sourced[MonomorphicExpression]] =
    quoteSourcedIn(expr, monoEnv)

  /** Produce the **fully reduced** body of a compiler-platform value (CP-C step b — the compiler backend). A
    * compiler-track value is wholly compile-time, so — unlike the runtime track, whose body must stay structural for
    * codegen — its body is normalised as far as it goes: an ability call resolved during the drain (`show(1)` ⤳ the
    * concrete `Show[Int]`'s `show`) has its concrete-impl *body* folded in by ordinary NbE evaluation, and a `raise`
    * resolved at its handle's type reaches the overlay's `escaping` and exits through its frame, so `runThrow(raise(
    * "boom"))` reduces to `Left("boom")`. This is the reduced value the runtime track's type-level evaluation will plug
    * in as a native.
    *
    *   - rewrite every drain-resolved ability reference to its concrete-impl reference (the [[SemExpression]] analogue
    *     of [[resolveIfAbility]]), so the impl body is reachable to the evaluator (its binding is supplied through
    *     `lookupTopDef`, which the caller augments with the resolved impls);
    *   - evaluate the rewritten body and read it back. A body that reduces to a ground constructor value materialises
    *     to a literal/constructor tree (the reduced native);
    *   - a body that does *not* fully reduce — a compiler-platform *function* with runtime value parameters (e.g.
    *     `foldEither`), whose parameters stay neutral — falls back to the ordinary structural quote, identical to the
    *     runtime track's read-back (with `resolveIfAbility` doing the ability→impl rewrite there).
    */
  def reduceSourced(expr: Sourced[SemExpression]): CompilerIO[Sourced[MonomorphicExpression]] =
    reduceWithEscalation(monoEnv, expr.value, EscalatingReducer.reducibleStuck).flatMap { reduced =>
      Quoter.quote(0, reduced, metaStore) match {
        case Left(_)       => quoteSourced(expr)
        case Right(ground) =>
          materialise(ground, expr).flatMap {
            case Some(mono)                                    => expr.as(mono).pure[CompilerIO]
            // A level-≥1 (type-level) value's body reduces to a *type*, which `materialise` declines (it handles runtime
            // value constants). A type is a legitimate compile-time constant here — the "types are values" cornerstone —
            // so read it back structurally instead of falling to the runtime staging gate, whose fail-safe would abort on
            // an erased type parameter the body references in value position (`Function[X, X]` with `X := A`).
            //
            // The quoter types *every* body-less application `Type`, so the ground's own type cannot tell a type from a
            // value whose normal form is a body-less application — a compile-time list, a `prepend` chain over `empty`
            // (`ListReductions`). Read as a type, such a value's literal elements would collapse to the opaque `Any`
            // (`groundTypeToMono`'s `Direct` arm), and every later consumer of the element would stay stuck. The body's
            // declared type settles it: only a body *typed* `Type` is a type; a value keeps the ordinary structural
            // read-back, which the next evaluation reduces again.
            case None if ground.valueType === GroundValue.Type && isTypedAsType(expr.value.expressionType) =>
              expr.as(groundTypeToMono(ground, expr)).pure[CompilerIO]
            case None                                          => quoteSourced(expr)
          }
      }
    }

  /** Whether a checked expression's type is `Type` itself — the one discriminator between a body that reduces to a
    * type and a value whose normal form merely *quotes* like one (see [[reduceSourced]]).
    */
  private def isTypedAsType(expressionType: SemValue): Boolean =
    Evaluator.force(expressionType, metaStore) match {
      case SemValue.VType => true
      case _              => false
    }

  /** Compile-time reduction with **stuck-driven escalation** (§3.4 of the signature-unification plan). Evaluate `expr`
    * (its ability references resolved to the drain-chosen impls) under `env` with the base one-hop bindings; if the
    * forced result quotes, return it — the common case pays nothing. If it is stuck on a *reducible* head (a bodied
    * top-level definition, a stuck `match`, a stuck native — never a runtime `VLam` or a value-parameter neutral, which
    * are legitimately structural), fetch each of the expression's value references **reduced at its own ground type
    * arguments** ([[reduceInstance]] → the reference's own `CompilerMonomorphicValue`), splice those in, and
    * re-evaluate. Loop until it quotes or no new reduced binding is available.
    *
    * This is the single mechanism the guarded signature, ability-guard markers, and any other compile-time code
    * share: each fetched reduced form was itself produced by a read-back that ran this same loop, so
    * the recursion lives in the cached, `activeFactKeys`-guarded fact graph rather than in a closure-composition flag.
    * It is shape-agnostic and stuck-driven — it never inspects whether the term is a guard. The loop skeleton itself is
    * [[EscalatingReducer.escalatingLoop]], shared with the channel's post-monomorphize executor
    * ([[EscalatingReducer.reduceApplied]]) so the two are one mechanism, not siblings.
    */
  private def reduceWithEscalation(
      env: Env,
      expr: SemExpression,
      stuck: (SemValue, MetaStore) => Boolean
  ): CompilerIO[SemValue] = {
    val resolved = resolveAbilityRefs(expr)
    // Evaluate, then **renormalise** (re-firing a stuck native `add(2,1)` ⤳ `3`, descending under `VPi` binders) before
    // deciding the result is stuck — exactly what `quoteSem` does. Without this a native left stuck at evaluation time
    // (its arguments not yet concrete when the reference was inlined) would wrongly trigger escalation, monomorphizing
    // the *enclosing* value to resolve it — an ability dispatch a native leaf should short-circuit. Escalation is then
    // reserved for what renormalisation cannot re-fire: a stuck `match` / top-def head (the guard tower).
    def evalWith(extra: Map[ValueFQN, SemValue]): SemValue =
      Evaluator.renormalize(
        new SemExpressionEvaluator(fqn => extra.get(fqn).orElse(lookupTopDef(fqn))).eval(env, resolved),
        metaStore,
        lookupTopDef,
        deep = true
      )
    EscalatingReducer.escalatingLoop(metaStore, evalWith, escalationBindings(resolved, _), stuck)
  }

  /** Whether evaluating the checked `expr` can run a control effect — reach an escape frame. An evaluation that does is
    * meaningful only threaded, with its references dispatched: read raw, an operation stays an undispatched call that
    * an escape's handler can turn into a plausible, wrong value.
    */
  def runsControlEffects(expr: SemExpression): CompilerIO[Boolean] = reachesFrames(resolveAbilityRefs(expr))

  /** Whether evaluating `resolved` can reach a control effect's frame: some reference names a definition with an effect
    * entry in its clauses.
    */
  private def reachesFrames(resolved: SemExpression): CompilerIO[Boolean] =
    valueRefsWithArgs(resolved).map(_._1).distinct.existsM { vfqn =>
      getFactIfProduced(UnifiedModuleNames.Key(vfqn.moduleName, platform)).flatMap {
        case Some(names) if names.names.contains(vfqn.name) =>
          getFactIfProduced(OperatorResolvedValue.Key(vfqn, platform)).map(_.exists { orv =>
            orv.effectRow.returnEffects.nonEmpty || orv.effectRow.parameterEffects.exists(_.effects.nonEmpty)
          })
        case _                                              => false.pure[CompilerIO]
      }
    }

  /** The in-checker instantiation of [[EscalatingReducer.escalate]]'s escalation fetch: escalate each value reference
    * in `resolved` whose type arguments quote to a fully-ground form (the [[SemExpression]] carries [[SemValue]] type
    * args, unlike the already-ground [[MonomorphicExpression]] path). Each reduced body has its generic type parameters
    * baked in, so [[absorbTypeArgs]] wraps it in one ignore-lambda per ground type argument — applying the reference's
    * type arguments is then absorbed and the already-instantiated body is reached unchanged.
    */
  private def escalationBindings(
      resolved: SemExpression,
      already: Set[ValueFQN]
  ): CompilerIO[Map[ValueFQN, SemValue]] = {
    // No pre-collapse by FQN: pass every (fqn, ground-type-args) candidate through so `escalate` can *decline* an FQN
    // referenced at two distinct instantiations (§2.3) rather than silently escalating an arbitrary first one. Identical
    // (fqn, args) duplicates are folded by `escalate`'s own `.distinct`.
    val candidates = valueRefsWithArgs(resolved).flatMap { case (fqn, typeArgs) =>
      typeArgs.toList.traverse(a => Quoter.quote(0, a, metaStore)).toOption.map(fqn -> _)
    }
    EscalatingReducer.escalate(candidates, already, reduceInstance, absorbTypeArgs(_, _))
  }

  /** Wrap `binding` in `n` ignore-lambdas, so applying `n` (type) arguments to it is absorbed and the already-
    * instantiated `binding` is reached unchanged — see [[escalationBindings]].
    */
  private def absorbTypeArgs(n: Int, binding: SemValue): SemValue =
    if (n <= 0) binding else SemValue.VLam("_", _ => absorbTypeArgs(n - 1, binding))

  /** Every [[SemExpression.ValueReference]] in a checked expression tree with its (unquoted) type arguments, descending
    * through applications and lambda bodies.
    */
  private def valueRefsWithArgs(expr: SemExpression): Seq[(ValueFQN, Seq[SemValue])] = expr.expression match {
    case SemExpression.ValueReference(vfqn, typeArgs)        => Seq((vfqn.value, typeArgs))
    case SemExpression.FunctionApplication(target, argument) =>
      valueRefsWithArgs(target.value) ++ valueRefsWithArgs(argument.value)
    case SemExpression.FunctionLiteral(_, _, body)           => valueRefsWithArgs(body.value)
    case _                                                   => Seq.empty
  }

  /** Rewrite each drain-resolved ability [[SemExpression.ValueReference]] to its concrete-impl reference, so the
    * evaluator resolves the impl *body* rather than stalling on the abstract ability method (which has no binding). The
    * [[SemExpression]]-level twin of [[resolveIfAbility]]: same [[abilityResolutions]] lookup, but producing a
    * rewritten [[SemExpression]] the evaluator can reduce, not a finished [[MonomorphicExpression]]. Non-ability
    * references and unresolved ability references (constraint-covered, resolved at the caller's level) are left
    * untouched.
    */
  private def resolveAbilityRefs(se: SemExpression): SemExpression = se.expression match {
    case SemExpression.ValueReference(vfqn, typeArgs)              =>
      lookupResolution(vfqn, abilityArgsOf(vfqn.value, typeArgs)) match {
        case Some((implFqn, implTypeArgs)) =>
          SemExpression(
            se.expressionType,
            SemExpression.ValueReference(
              vfqn.as(implFqn),
              forwarded(vfqn.value, implTypeArgs.map(Evaluator.groundToSem), typeArgs)
            )
          )
        case None                          => se
      }
    case SemExpression.FunctionApplication(target, argument)       =>
      SemExpression(
        se.expressionType,
        SemExpression.FunctionApplication(target.map(resolveAbilityRefs), argument.map(resolveAbilityRefs))
      )
    case SemExpression.FunctionLiteral(paramName, paramType, body) =>
      SemExpression(
        se.expressionType,
        SemExpression.FunctionLiteral(paramName, paramType, body.map(resolveAbilityRefs))
      )
    case _                                                         => se
  }

  private def quoteSourcedIn(
      expr: Sourced[SemExpression],
      evalEnv: Env
  ): CompilerIO[Sourced[MonomorphicExpression]] = {
    val node       = expr.value.expression
    val refs       = collectParamRefs(node)
    val hasErased  = refs.exists(erasedParams.contains)
    val hasRuntime = refs.exists(isRuntimeParam(_, evalEnv)) || containsLambda(node)
    if (hasErased && !hasRuntime)
      tryMaterialise(expr, evalEnv).flatMap {
        case Some(mono) => mono.pure[CompilerIO]
        case None       =>
          node match {
            case _: SemExpression.FunctionApplication => structuralQuote(expr, evalEnv)
            case _                                    =>
              compilerAbort(
                expr.as("Value depends on a compile-time parameter but does not reduce to a constant.")
              )
          }
      }
    else structuralQuote(expr, evalEnv)
  }

  /** The normal structural read-back: quote this node's type slot and recurse into its children (unchanged from the
    * pre-reification behaviour, except that `evalEnv` is threaded for the gate to use deeper).
    */
  private def structuralQuote(
      expr: Sourced[SemExpression],
      evalEnv: Env
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    for {
      exprType <- quoteSem(expr.value.expressionType, expr)
      inner    <- quoteExpression(expr.value.expression, expr, evalEnv)
    } yield expr.as(MonomorphicExpression(exprType, inner))

  private def quoteExpression(
      expression: SemExpression.Expression,
      at: Sourced[?],
      evalEnv: Env
  ): CompilerIO[MonomorphicExpression.Expression] = expression match {
    case SemExpression.IntegerLiteral(v) =>
      (MonomorphicExpression.IntegerLiteral(v): MonomorphicExpression.Expression).pure[CompilerIO]

    case SemExpression.StringLiteral(v) =>
      (MonomorphicExpression.StringLiteral(v): MonomorphicExpression.Expression).pure[CompilerIO]

    case SemExpression.ParameterReference(name) =>
      (MonomorphicExpression.ParameterReference(name): MonomorphicExpression.Expression).pure[CompilerIO]

    case SemExpression.FunctionApplication(target, argument) =>
      for {
        t <- quoteSourcedIn(target, evalEnv)
        a <- quoteSourcedIn(argument, evalEnv)
      } yield MonomorphicExpression.FunctionApplication(t, a)

    case SemExpression.FunctionLiteral(paramName, paramType, body) =>
      // Bind the runtime parameter as a neutral in ρ; `isRuntimeParam` then recognises references to it deeper in the
      // body as runtime (not erased), so no separate `runtimeParams` set need be threaded.
      val innerEnv = evalEnv.bind(
        paramName.value,
        SemValue.VNeutral(SemValue.NeutralHead.Param(evalEnv.level, paramName.value), SemValue.Spine.SNil)
      )
      for {
        pt <- quoteSem(paramType, paramName)
        b  <- quoteSourcedIn(body, innerEnv)
      } yield MonomorphicExpression.FunctionLiteral(paramName, pt, b)

    case SemExpression.ValueReference(vfqn, typeArgs) if vfqn.value === WellKnownTypes.integerLiteralFQN =>
      // `integerLiteral[V]` is the platform-independent literal protocol (see `WellKnownTypes.integerLiteralFQN`): a
      // value-position literal `n` desugared so the checker could type it as plain `Int` (post flag-day `Int` carries no
      // bounds — the range lives in the refinement channel). The reference carries the constant as its single erased
      // type-argument `V`. This is a purely syntactic readback rewrite — not evaluation: read `V` (already ground) and
      // emit a plain `IntegerLiteral` node, which `structuralQuote` stamps with the node's own already-computed `Int`
      // type. The backend's ordinary integer-literal path emits it, so no `integerLiteral` reference survives into the
      // monomorphic tree and no backend intrinsic is needed.
      typeArgs.traverse(a => quoteSem(a, vfqn)).flatMap {
        case Seq(GroundValue.Direct(Literal.IntegerValue(v), _)) =>
          (MonomorphicExpression.IntegerLiteral(vfqn.as(v)): MonomorphicExpression.Expression).pure[CompilerIO]
        case _                                                   =>
          compilerAbort(vfqn.as("integerLiteral must have a single integer value argument."))
      }

    case SemExpression.ValueReference(vfqn, typeArgs) =>
      for {
        args <- typeArgs.traverse(a => quoteSem(a, vfqn))
      } yield resolveIfAbility(vfqn, args, typeArgs)
  }

  /** Evaluate a value-position sub-term that is determined by erased parameters, then read it back and materialise it
    * into a literal / constructor-call tree. Returns [[None]] when the result does not reduce to a materialisable
    * constant (a closure, a still-abstract head, a type) — the caller decides whether to recurse or fail.
    */
  private def tryMaterialise(
      expr: Sourced[SemExpression],
      evalEnv: Env
  ): CompilerIO[Option[Sourced[MonomorphicExpression]]] = {
    val forced = Evaluator.force(semEvaluator.eval(evalEnv, expr.value), metaStore)
    Quoter.quote(0, forced, metaStore) match {
      case Left(_)       => Option.empty[Sourced[MonomorphicExpression]].pure[CompilerIO]
      case Right(ground) => materialise(ground, expr).map(_.map(expr.as))
    }
  }

  /** Expand a fully-ground compile-time value into a [[MonomorphicExpression]] tree.
    *
    *   - `Direct(BigInt)` / `Direct(String)` ⟹ the matching literal node.
    *   - `Direct(Boolean)` ⟹ a reference to the platform `Bool` constructor (`true` / `false`).
    *   - `Structure` headed by a concrete value constructor ⟹ a (curried) constructor application; the leading
    *     type-arguments stay type arguments and the trailing field values become applications (Stage 2).
    *
    * Returns [[None]] for anything that is not a runtime constant: a `Type`, a non-primitive `Direct` (e.g. the unit
    * placeholder), or a `Structure` whose head is not a concrete value constructor (an abstract / type-constructor head
    * — "defined on both sides" is not met). The caller surfaces that as a recurse-or-fail decision.
    */
  private def materialise(ground: GroundValue, at: Sourced[?]): CompilerIO[Option[MonomorphicExpression]] =
    ground match {
      case GroundValue.Direct(Literal.IntegerValue(v), vt) =>
        Some(MonomorphicExpression(vt, MonomorphicExpression.IntegerLiteral(at.as(v)))).pure[CompilerIO]
      case GroundValue.Direct(Literal.StringValue(v), vt)  =>
        Some(MonomorphicExpression(vt, MonomorphicExpression.StringLiteral(at.as(v)))).pure[CompilerIO]
      case GroundValue.Direct(Literal.BooleanValue(v), vt) =>
        val ctor = if (v) WellKnownTypes.boolTrueFQN else WellKnownTypes.boolFalseFQN
        Some(MonomorphicExpression(vt, MonomorphicExpression.MonomorphicValueReference(at.as(ctor), Seq.empty)))
          .pure[CompilerIO]
      case GroundValue.Direct(_, _)                        =>
        Option.empty[MonomorphicExpression].pure[CompilerIO]
      case s: GroundValue.Structure                        =>
        materialiseStructure(s, at)
      case GroundValue.Type                                =>
        Option.empty[MonomorphicExpression].pure[CompilerIO]
      case _: GroundValue.Param                            =>
        // A `Param` is a signature-twin artefact (C2) and is re-inflated to a metavariable long before any body reaches
        // read-back; one reaching materialisation would be a leak, so decline (never emit a parametric runtime value).
        Option.empty[MonomorphicExpression].pure[CompilerIO]
    }

  /** Read a fully-ground compile-time **type** back as a structural value-reference spine — the type-level counterpart
    * of [[materialise]] (which handles runtime value constants). A level-≥1 (type-level) value's body reduces to a
    * type: a type constructor `name[arg…]` becomes the curried `name(arg)…` application over its (recursively
    * read-back) arguments, and `Type` itself its own reference. Only ever called for a ground value whose `valueType`
    * is `Type`, so the [[GroundValue.Direct]] arm is unreachable (a Direct is a runtime value, never a type).
    */
  private def groundTypeToMono(g: GroundValue, at: Sourced[?]): MonomorphicExpression = g match {
    case GroundValue.Structure(name, args, _) =>
      args.foldLeft(MonomorphicExpression(g, MonomorphicExpression.MonomorphicValueReference(at.as(name), Seq.empty))) {
        (acc, arg) =>
          MonomorphicExpression(
            g,
            MonomorphicExpression.FunctionApplication(at.as(acc), at.as(groundTypeToMono(arg, at)))
          )
      }
    case GroundValue.Type                     =>
      MonomorphicExpression(
        GroundValue.Type,
        MonomorphicExpression.MonomorphicValueReference(at.as(WellKnownTypes.typeFQN), Seq.empty)
      )
    case GroundValue.Direct(_, _)             =>
      MonomorphicExpression(g, MonomorphicExpression.MonomorphicValueReference(at.as(WellKnownTypes.anyFQN), Seq.empty))
    case _: GroundValue.Param                 =>
      // Unreachable: a `Param` is a signature-twin artefact (C2), never a reduced runtime/compiler body read here. Emit
      // the opaque top carrier as a fail-safe rather than a bogus structural reference.
      MonomorphicExpression(g, MonomorphicExpression.MonomorphicValueReference(at.as(WellKnownTypes.anyFQN), Seq.empty))
  }

  /** Materialise a data value as a constructor application (Stage 2). The constructor's field arity comes from its
    * [[RoleHint.ValueConstructor]] — the one sanctioned read of constructor-shape metadata, as `match` reconstruction
    * also uses (only `fieldCount`, never `typeParamCount`). The quoted structure's leading args are the constructor's
    * type arguments (kept as `MonomorphicValueReference` type args) and the trailing `fieldCount` args are the field
    * values (materialised recursively and applied). A head that is not a concrete value constructor yields [[None]].
    *
    * A constructor holding **fewer** arguments than it has fields cannot be read back at all — it is an *under*-applied
    * constructor in a position that quoted as a finished value, i.e. a reduction that lost an argument. Declining
    * quietly would hand the caller a structural fallback built on the wrong arity, which is how a misaligned reduction
    * travels downstream disguised as a well-formed value; fail loudly instead. Note this catches only the *short*
    * misalignment: a spine that is too long still splits plausibly into "more type arguments", and telling those apart
    * would need the constructor's type-parameter arity ([[RoleHint.TypeConstructor.typeParamCount]]), which no semantic
    * phase may read.
    */
  private def materialiseStructure(
      s: GroundValue.Structure,
      at: Sourced[?]
  ): CompilerIO[Option[MonomorphicExpression]] =
    constructorRole(s.typeName).flatMap {
      case None                                                  =>
        Option.empty[MonomorphicExpression].pure[CompilerIO]
      case Some(RoleHint.ValueConstructor(dataType, fieldCount)) =>
        val splitIndex = s.args.size - fieldCount
        if (splitIndex < 0)
          compilerAbort[Option[MonomorphicExpression]](
            at.as(
              s"Internal error: constructor '${s.typeName.name.name}' reduced to ${s.args.size} argument(s) but declares $fieldCount field(s)."
            ),
            Seq("This is a compiler defect — a reduction lost an argument; please report it.")
          )
        else {
          val (typeArgs, valueArgs)       = s.args.splitAt(splitIndex)
          // The value's type is the data type constructor (same module as the constructor) applied to the type args.
          val valueType                   =
            GroundValue.Structure(ValueFQN(s.typeName.moduleName, dataType), typeArgs, GroundValue.Type)
          val base: MonomorphicExpression =
            MonomorphicExpression(
              valueType,
              MonomorphicExpression.MonomorphicValueReference(at.as(s.typeName), typeArgs)
            )
          valueArgs.foldLeftM(Option(base)) {
            case (None, _)              => Option.empty[MonomorphicExpression].pure[CompilerIO]
            case (Some(acc), argGround) =>
              materialise(argGround, at).map(_.map { argExpr =>
                // Intermediate application types are discarded by the uncurry pass (only the outermost, = `valueType`,
                // and the leaf argument types survive), so reusing `valueType` here is safe.
                MonomorphicExpression(
                  valueType,
                  MonomorphicExpression.FunctionApplication(at.as(acc), at.as(argExpr))
                )
              })
          }
        }
      case Some(_)                                               =>
        Option.empty[MonomorphicExpression].pure[CompilerIO]
    }

  /** The [[RoleHint]] of a value FQN, or [[None]] if no module value is known for it. Read from the track's own pool
    * (`platform`): a compiler-track constructor (e.g. the compile-time `Either`'s `Left`/`Right`) lives in the compiler
    * pool, so the runtime-default lookup would miss it and silently drop materialisation to a structural fallback.
    */
  private def constructorRole(fqn: ValueFQN): CompilerIO[Option[RoleHint]] =
    getFactIfProduced(UnifiedModuleValue.Key(fqn, platform)).map(_.map(_.namedValue.roleHint))

  /** All parameter names referenced anywhere in this expression node's subtree. Type-argument [[SemValue]]s of a value
    * reference are not walked — they are erased and never produce a runtime reference, so they do not gate
    * materialisation of the surrounding value.
    */
  private def collectParamRefs(expression: SemExpression.Expression): Set[String] = expression match {
    case SemExpression.ParameterReference(name)         => Set(name.value)
    case SemExpression.FunctionApplication(target, arg) =>
      collectParamRefs(target.value.expression) ++ collectParamRefs(arg.value.expression)
    case SemExpression.FunctionLiteral(_, _, body)      => collectParamRefs(body.value.expression)
    case _                                              => Set.empty
  }

  /** Whether `name` denotes a runtime value parameter in this eval env (ρ) — i.e. it is bound to a neutral, the fresh
    * rigid variable a runtime `FunctionLiteral` introduces during read-back. Erased type parameters are bound to values
    * (`monoEnv` seeds them) and instantiation metas to `VMeta`, so only runtime lambda binders read back as neutrals.
    * Reading this off ρ replaces a separately-threaded `runtimeParams` set.
    */
  private def isRuntimeParam(name: String, evalEnv: Env): Boolean =
    evalEnv.lookupByName(name).exists {
      case _: SemValue.VNeutral => true
      case _                    => false
    }

  /** Whether this expression node's subtree contains a function literal (an inner runtime lambda). */
  private def containsLambda(expression: SemExpression.Expression): Boolean = expression match {
    case SemExpression.FunctionLiteral(_, _, _)         => true
    case SemExpression.FunctionApplication(target, arg) =>
      containsLambda(target.value.expression) || containsLambda(arg.value.expression)
    case _                                              => false
  }

  private def resolveIfAbility(
      vfqn: Sourced[ValueFQN],
      typeArgs: Seq[GroundValue],
      semTypeArgs: Seq[SemValue]
  ): MonomorphicExpression.Expression =
    vfqn.value.name.qualifier match {
      case CoreQualifier.Ability(abilityName) =>
        val abilityArgs = abilityArgsOf(vfqn.value, semTypeArgs)
        lookupResolution(vfqn, abilityArgs) match {
          case Some((implFqn, implTypeArgs)) =>
            MonomorphicExpression.MonomorphicValueReference(vfqn.as(implFqn), forwarded(vfqn.value, implTypeArgs, typeArgs))
          case None                          =>
            MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs)
        }
      case _                                  =>
        MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs)
    }

  /** The ability-level type arguments of a method reference, derived **exactly** as `AbilityResolver.tryResolveOne`
    * derived them when it recorded the resolution: take the leading `abilityArity` of the reference's own semantic type
    * arguments, then shallow-`Quoter.quote` them. Both halves must match the resolver — a different slice or a
    * different quote (the deep `quoteSem` renormalises first) could produce a ground value that is equal in meaning but
    * not by `==`, and the key would miss. [[None]] when they do not quote, which is the resolver's constraint-covered
    * case. An arity this quoter never saw recorded slices nothing, matching the resolver's `Int.MaxValue` fallback.
    */
  private def abilityArgsOf(methodVfqn: ValueFQN, semTypeArgs: Seq[SemValue]): Option[Seq[GroundValue]] =
    semTypeArgs
      .take(abilityArities.getOrElse(methodVfqn, Int.MaxValue))
      .toList
      .traverse(a => Quoter.quote(0, a, metaStore))
      .toOption

  /** The implementation method's type arguments: its instance's, then the call's method-level ones. A clause's binders
    * follow its member's after its instance's (`docs/effects.md` D36, `row/Captures`), so the call's method-level
    * arguments, its handles' types among them, are simply appended.
    */
  private def forwarded[A](member: ValueFQN, implArguments: Seq[A], callArguments: Seq[A]): Seq[A] =
    implArguments ++ callArguments.drop(abilityArities.getOrElse(member, callArguments.size))

  private def lookupResolution(
      vfqn: Sourced[ValueFQN],
      abilityArgs: Option[Seq[GroundValue]]
  ): Option[(ValueFQN, Seq[GroundValue])] =
    CheckState.lookupAbilityResolution(abilityResolutions, vfqn, abilityArgs)
}
