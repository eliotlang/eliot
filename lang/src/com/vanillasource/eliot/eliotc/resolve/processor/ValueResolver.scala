package com.vanillasource.eliot.eliotc.resolve.processor

import cats.Id
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  EffectRow,
  ImplementationParameters,
  PrecedenceDeclaration as AstPrecedenceDeclaration,
  UnresolvedAbilityConstraint,
  Visibility
}
import com.vanillasource.eliot.eliotc.core.fact.Expression.*
import com.vanillasource.eliot.eliotc.core.fact.{
  NamedValue,
  Expression as CoreExpression,
  Pattern as CorePattern,
  PrecedenceDeclaration as CorePrecedenceDeclaration
}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.{
  abilityCombinatorFQN,
  functionDataTypeFQN,
  implementationTypeFQN,
  typeFQN,
  patternMatchAbilityName,
  typeMatchAbilityName
}
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleName,
  UnifiedModuleValue,
  ValueFQN,
  QualifiedName as CoreQualifiedName,
  Qualifier as CoreQualifier
}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.ability.util.AbilityKind
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.resolve.fact.*
import com.vanillasource.eliot.eliotc.resolve.processor.ValueResolverScope.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

class ValueResolver
    extends TransformationProcessor[UnifiedModuleValue.Key, ResolvedValue.Key](key =>
      UnifiedModuleValue.Key(key.vfqn, key.platform)
    )
    with Logging {

  override protected def generateFromKeyAndFact(
      key: ResolvedValue.Key,
      unifiedValue: UnifiedModuleValue
  ): CompilerIO[ResolvedValue] = {
    val namedValue    = unifiedValue.namedValue
    val genericParams = collectGenericParamsFromExpr(namedValue.signature.value)
    val scope         =
      ValueResolverScope(
        key.vfqn.moduleName,
        unifiedValue.dictionary,
        unifiedValue.privateNames,
        genericParams.toSet,
        key.platform
      )

    val resolveProgram = for {
      resolvedRuntime     <-
        namedValue.runtime.traverse(expr => resolveExpression(expr.value, true).map(expr.as))
      resolvedSignature   <- withLocalScope(resolveExpression(namedValue.signature.value, false)).map(namedValue.signature.as)
      resolvedName        <- convertQualifiedName(namedValue.qualifiedName)
      resolvedConstraints <- resolveParamConstraints(namedValue.paramConstraints)
      // A **row alias**'s own row is written over its *value* args (`type Fallible[E, A] = {Throw[E]} A`), because a
      // type alias's parameters are its value args — `type Git[A]` lowers to a `Type`-returning function of one
      // argument. They are in scope for its body and must be in scope for the row it declares over them.
      resolvedEffectRow   <- withLocalScope(
                               aliasParameters(namedValue)
                                 .traverse_(addParameter)
                                 .whenA(namesRow(namedValue)) >> resolveEffectRow(namedValue.effectRow)
                             )
      resolvedPrecedence  <- resolvePrecedenceDeclarations(namedValue.precedence)
      // A **row alias** named as this definition's return type (`def greet(name: String): Talk[Unit]`) hands it the
      // row that alias declares. Nothing is special-cased: the name was resolved above like every other name, and
      // what is read here is the declaration it resolved to — the same reading that lets a `~` constraint stand for
      // the abilities its ability declares (see [[superConstraints]]).
      namedRow            <- receivedNamedRow(resolvedSignature).map(entries =>
                               // An entry the definition already writes out (`def greet: {Console} Talk[Unit]`, where
                               // `Talk` also names `Console`) is one entry, not two: a definition receives one binding
                               // per distinct effect however many ways it declared it.
                               entries.filterNot(entry =>
                                 constraintKeys(resolvedEffectRow.returnEffects).contains(constraintKey(entry))
                               )
                             )
      _                   <- reportNamedRowMisuses(resolvedSignature, resolvedRuntime, namedValue)
      received             = receiveBindings(resolvedSignature, resolvedConstraints, resolvedEffectRow, namedRow)
      _                   <- debug[ScopedIO](s"Resolved ${key.vfqn.show} type: ${received.signature.value.render}")
      _                   <- debug[ScopedIO](
                               s"Resolved ${key.vfqn.show} runtime: ${resolvedRuntime.map(_.value.render).getOrElse("<abstract>")}"
                             )
    } yield ResolvedValue(
      unifiedValue.vfqn,
      resolvedName,
      resolvedRuntime,
      received.signature,
      received.paramConstraints,
      namedValue.fixity,
      resolvedPrecedence,
      namedValue.roleHint,
      key.platform,
      received.effectRow
    )

    resolveProgram.runA(scope)
  }

  /** What naming a **row alias** as a return type leaves on the definition that named it: its signature with one
    * binding binder per received entry, those binders' constraints, and the row recording them.
    */
  private case class ReceivedRow(
      signature: Sourced[Expression],
      paramConstraints: Map[String, Seq[AbilityConstraint[Expression]]],
      effectRow: EffectRow[AbilityConstraint[Expression]]
  )

  /** The row entries a definition **receives** from a row alias naming its return type (`docs/effects.md` §2.4), the
    * use's arguments substituted into them.
    *
    * The alias is reached the way every name is reached: [[resolveExpression]] has already resolved it to a
    * [[ValueFQN]] through the ordinary dictionary, so import scope, shadowing, privacy and qualification are decided
    * there and nothing is matched by spelling here. What this adds is the reading of the declaration that name
    * resolved to — which is the same rule that propagates a callee's declared row to its caller, and the same rule
    * [[superConstraints]] applies to a `~` constraint.
    *
    * **Return position only**, which is the one position that needs nothing of the slot: a parameter row is *supplied*
    * rather than received and must additionally be thunked, which is a rewrite of the slot rather than of the type
    * naming it. Every other position is [[reportNamedRowMisuses]], never a silent widening.
    */
  private def receivedNamedRow(signature: Sourced[Expression]): ScopedIO[Seq[AbilityConstraint[Expression]]] =
    applicationSpine(returnPosition(signature)) match {
      case (Sourced(_, _, Expression.ValueReference(name, typeArgs)), arguments) =>
        declaredRowOf(name, typeArgs ++ arguments)
      case _                                                                    =>
        Seq.empty[AbilityConstraint[Expression]].pure[ScopedIO]
    }

  /** The row a named value declares, with this use's arguments substituted for the parameters it declared them over —
    * empty for every name that is not a row alias, which is nearly all of them.
    *
    * The entries are resolved **in the alias's own scope** before being substituted, exactly as [[superConstraints]]
    * resolves an ability's own constraints in the ability's scope: a row names abilities and types its own file
    * imports, and the file naming the alias need not import any of them.
    *
    * [[getFactIfProduced]] because a return type's head legitimately has no module value at all — `Type` and
    * `Function` are compiler-known FQNs with no declaration — and a head whose own definition aborted upstream is the
    * other expected absence. Neither is a missing producer.
    */
  private def declaredRowOf(
      aliasName: Sourced[ValueFQN],
      arguments: Seq[Sourced[Expression]]
  ): ScopedIO[Seq[AbilityConstraint[Expression]]] =
    for {
      platform <- getPlatform
      alias    <- rowAliasAt(aliasName, platform)
      entries  <- alias.fold(Seq.empty[AbilityConstraint[Expression]].pure[ScopedIO]) { umv =>
                    val parameters = aliasParameters(umv.namedValue)
                    if (parameters.size =!= arguments.size)
                      rejectRowAlias(
                        aliasName.as(
                          s"Row alias '${umv.namedValue.qualifiedName.value.name}' takes ${parameters.size} " +
                            s"type argument(s), but ${arguments.size} were given."
                        )
                      )
                    else
                      for {
                        resolved <- resolveInValueScope(aliasName.value.moduleName, umv, parameters, platform)(
                                      umv.namedValue.effectRow.returnEffects.traverse(resolveConstraint)
                                    )
                        bindings  = parameters.zip(arguments.map(_.value)).toMap
                      } yield resolved.map(c => c.copy(typeArgs = c.typeArgs.map(substituteParameters(bindings))))
                  }
    } yield entries

  /** The declaration this name resolves to, when it is a **row alias** — a declaration that *names* a row rather than
    * performing one.
    *
    * That is read exactly, not guessed from a namespace: the desugar mints a binding binder for every entry of a row
    * a definition **performs**, and none for a row a definition **names**, so a declared row with no constraint of
    * its own naming the same ability is the one and only shape an alias has. A `{Console}`-declaring `def` therefore
    * never reads as one, whatever position it is named in.
    */
  private def rowAliasAt(name: Sourced[ValueFQN], platform: Platform): ScopedIO[Option[UnifiedModuleValue]] =
    getFactIfProduced(UnifiedModuleValue.Key(name.value, platform)).liftToScoped
      .map(_.filter(umv => namesRow(umv.namedValue)))

  private def namesRow(namedValue: NamedValue): Boolean = {
    val performed = namedValue.paramConstraints.values.flatten.map(_.abilityName.value).toSet
    namedValue.effectRow.returnEffects.nonEmpty &&
    !namedValue.effectRow.returnEffects.exists(entry => performed.contains(entry.abilityName.value))
  }

  /** A row alias named where its row cannot be received — any position but a definition's own return type. Reported
    * rather than silently widened away: the row would simply vanish, and the effects it names would be charged to
    * whoever called the definition instead.
    */
  private def reportNamedRowMisuses(
      signature: Sourced[Expression],
      runtime: Option[Sourced[Expression]],
      namedValue: NamedValue
  ): ScopedIO[Unit] = {
    // A *type-level* definition's body is a type expression, and a row alias named in one would drop its row on the
    // floor: only a definition's return type receives a row, and a type is not one. `type Held[A] = Option[Talk[A]]`
    // and `type Loud = Talk` are therefore rejected exactly as a parameter position is. A row alias whose body *is* a
    // row is not this case — that row was recorded as what the alias declares, and erased from the body.
    val body = if (isTypeDefinition(namedValue)) runtime.toSeq.flatMap(valueReferences) else Seq.empty
    (misusablePositions(signature) ++ body)
      .distinctBy(_.value)
      .traverse_(name => declaresRow(name).ifM(reportMisuse(name), ().pure[ScopedIO]))
  }

  /** Whether this definition was declared with `type` (or `data`) — it lives in the **type namespace**, so its body is
    * a type expression and its parameters are its value args.
    *
    * Deliberately *not* "its return position is `Type`": a type is an ordinary value here, so an ordinary `def` may
    * return one (`def raiseGuard: {Throw[String]} Type`) without being a type definition — its body is a term and its
    * parameters are its binders, exactly as for any other `def`.
    */
  private def isTypeDefinition(namedValue: NamedValue): Boolean =
    namedValue.qualifiedName.value.qualifier match {
      case CoreQualifier.Type => true
      case _                  => false
    }

  /** Whether this name is a row alias at all — asked without resolving or substituting anything, because a misused
    * one is rejected rather than received.
    */
  private def declaresRow(name: Sourced[ValueFQN]): ScopedIO[Boolean] =
    getPlatform.flatMap(rowAliasAt(name, _)).map(_.isDefined)

  private def reportMisuse(name: Sourced[ValueFQN]): ScopedIO[Unit] =
    rejectRowAlias(
      name.as(
        s"Row alias '${name.value.name.name}' can only name a definition's return type. " +
          "A row on a parameter is supplied rather than received, so it must be written out."
      )
    )

  /** Reject this value over a misused row alias. The value is never produced on *either* platform — a row that cannot
    * be received is not a runtime-only problem — but the message is printed once, from the runtime platform, since the
    * compiler track resolves the very same declaration (the same guard [[com.vanillasource.eliot.eliotc.row.processor.RowElaborationProcessor]] makes).
    */
  private def rejectRowAlias[T](message: Sourced[String]): ScopedIO[T] =
    getPlatform.flatMap(platform =>
      (if (platform == Platform.Runtime) compilerAbort[T](message) else abort[T]).liftToScoped
    )

  /** Every value reference in a signature except the return position's head — the one place a row alias may stand. */
  private def misusablePositions(signature: Sourced[Expression]): Seq[Sourced[ValueFQN]] = {
    val returnType              = returnPosition(signature)
    val (head, returnArguments) = applicationSpine(returnType)
    val nestedInReturn          = head match {
      case Sourced(_, _, Expression.ValueReference(_, typeArgs)) => (typeArgs ++ returnArguments).flatMap(valueReferences)
      case other                                                 => valueReferences(other)
    }
    (valueReferences(signature).diff(valueReferences(returnType)) ++ nestedInReturn).distinctBy(_.value)
  }

  /** Every value reference anywhere in a type expression, outermost first. */
  private def valueReferences(expr: Sourced[Expression]): Seq[Sourced[ValueFQN]] = expr.value match {
    case Expression.ValueReference(name, typeArgs)        => name +: typeArgs.flatMap(valueReferences)
    case Expression.FunctionApplication(target, argument) => valueReferences(target) ++ valueReferences(argument)
    case Expression.FunctionLiteral(_, parameterType, body) =>
      parameterType.toSeq.flatMap(valueReferences) ++ valueReferences(body)
    case _                                                => Seq.empty
  }

  /** The final, non-arrow return position of a signature: past its generic binders and its curried `Function` chain. */
  private def returnPosition(signature: Sourced[Expression]): Sourced[Expression] = signature.value match {
    case Expression.FunctionLiteral(_, Some(_), body)                                                     =>
      returnPosition(body)
    case Expression.FunctionApplication(Sourced(_, _, Expression.FunctionApplication(arrow, _)), codomain)
        if isArrow(arrow.value) =>
      returnPosition(codomain)
    case _                                                                                                => signature
  }

  private def isArrow(expr: Expression): Boolean = expr match {
    case Expression.ValueReference(name, _) => name.value === functionDataTypeFQN
    case _                                  => false
  }

  /** An application chain read as its head and its arguments in written order. */
  private def applicationSpine(expr: Sourced[Expression]): (Sourced[Expression], Seq[Sourced[Expression]]) =
    expr.value match {
      case Expression.FunctionApplication(target, argument) =>
        val (head, arguments) = applicationSpine(target)
        (head, arguments :+ argument)
      case _                                                => (expr, Seq.empty)
    }

  /** A row alias's parameters: the leading binders of its body. An alias's parameters are its **value** args — `type
    * Git[A]` lowers to a `Type`-returning function of one argument — so they are the body's lambdas, and a type
    * expression has no lambda of its own (an arrow is an application of `Function`), which is what makes the leading
    * run exactly the parameter list.
    */
  private def aliasParameters(alias: NamedValue): Seq[String] =
    alias.runtime.toSeq.flatMap(body => leadingBinderNames(body.value))

  private def leadingBinderNames(expr: CoreExpression): Seq[String] = expr match {
    case FunctionLiteral(name, _, body) => name.value +: leadingBinderNames(body.value)
    case _                              => Seq.empty
  }

  /** Mint one **binding binder** per received entry, exactly as a written-out return row mints one at `core`
    * ([[com.vanillasource.eliot.eliotc.core.processor.EffectSugarDesugarer]]): a binder carrying the mark
    * `Implementation[Console]` as its declared type, the entry's own constraint with that binder as its first type
    * argument, and the entry recorded in the declared row.
    *
    * The binders are appended to the signature's binder run rather than prefixed, because an **ability member's**
    * ability-level binders must keep their positions — the ability's own binding stays the first ability-level type
    * argument. Nothing requires a binding to be a prefix: the write merges the marked indices with what the call
    * determines for the rest.
    */
  private def receiveBindings(
      signature: Sourced[Expression],
      paramConstraints: Map[String, Seq[AbilityConstraint[Expression]]],
      effectRow: EffectRow[AbilityConstraint[Expression]],
      entries: Seq[AbilityConstraint[Expression]]
  ): ReceivedRow =
    if (entries.isEmpty) ReceivedRow(signature, paramConstraints, effectRow)
    else {
      val taken   = resolvedBinderNames(signature.value).toSet ++ paramConstraints.keySet
      val binders = entries.foldLeft(Seq.empty[(String, AbilityConstraint[Expression])]) { (acc, entry) =>
        acc :+ (freshBinderName(taken ++ acc.map(_._1)), entry)
      }
      ReceivedRow(
        signature.map(expr => appendBinders(expr, binders.map { case (name, entry) => (signature.as(name), entry) })),
        paramConstraints ++ binders.map { case (name, entry) =>
          name -> Seq(entry.copy(typeArgs = Expression.ParameterReference(signature.as(name)) +: entry.typeArgs))
        },
        effectRow.copy(returnEffects = effectRow.returnEffects ++ entries)
      )
    }

  /** The generic binder names of an already-resolved signature — its leading annotated [[Expression.FunctionLiteral]]
    * run, the same reading [[collectGenericParamsFromExpr]] makes of the unresolved one.
    */
  private def resolvedBinderNames(expr: Expression): Seq[String] = expr match {
    case Expression.FunctionLiteral(name, Some(_), body) => name.value +: resolvedBinderNames(body.value)
    case _                                               => Seq.empty
  }

  private def freshBinderName(taken: Set[String]): String =
    LazyList.from(0).map(i => if (i === 0) "Impl" else s"Impl$i").find(!taken.contains(_)).get

  private def appendBinders(
      expr: Expression,
      binders: Seq[(Sourced[String], AbilityConstraint[Expression])]
  ): Expression = expr match {
    case Expression.FunctionLiteral(name, Some(parameterType), body) =>
      Expression.FunctionLiteral(name, Some(parameterType), body.map(appendBinders(_, binders)))
    case _                                                           =>
      binders.foldRight(expr) { case ((name, entry), body) =>
        Expression.FunctionLiteral(name, Some(name.as(implementationMark(name, entry.abilityFQN))), name.as(body))
      }
  }

  /** A binding binder's **mark**: `Implementation[Console]`, the one place the fact "this binder is a binding" is
    * written down, read (and erased) by [[com.vanillasource.eliot.eliotc.row.BindingWriter]]. Its argument is the
    * ability's own marker value, which is what that reader matches on.
    */
  private def implementationMark(at: Sourced[?], abilityFQN: AbilityFQN): Expression =
    Expression.FunctionApplication(
      at.as(Expression.ValueReference(at.as(implementationTypeFQN))),
      at.as(Expression.ValueReference(at.as(markerOf(abilityFQN))))
    )

  private def convertQualifiedName(
      name: Sourced[CoreQualifiedName]
  ): ScopedIO[Sourced[QualifiedName]] =
    convertQualifier(name.value.qualifier, name).map(q => name.map(n => QualifiedName(n.name, q)))

  private def convertQualifier(qualifier: CoreQualifier, at: Sourced[?]): ScopedIO[Qualifier] =
    qualifier match {
      case CoreQualifier.Default                               => (Qualifier.Default: Qualifier).pure[ScopedIO]
      case CoreQualifier.Type                                  => (Qualifier.Type: Qualifier).pure[ScopedIO]
      // The meta namespace shadows another one, so it converts by converting what it shadows — a meta companion of an
      // ability-implementation method resolves that implementation's ability name exactly as the method itself does
      // (the companion is generated in the same file, hence the same import scope).
      case CoreQualifier.Meta(of)                              => convertQualifier(of, at).map(Qualifier.Meta(_))
      case CoreQualifier.Ability(n)                            => (Qualifier.Ability(n): Qualifier).pure[ScopedIO]
      case CoreQualifier.AbilityImplementation(name, pattern, implementationName) =>
        // The module qualifier carries the ability name as a bare string (identity is position-independent); borrow the
        // enclosing name's position for any "Ability not found" diagnostic.
        resolveAbilityName(at.as(name))
          .map(resolvedName => Qualifier.AbilityImplementation(resolvedName, pattern, implementationName))
      // A named implementation's name marker (effects v6 §9.4 step 1). The name is the implementation's own and needs
      // no resolution: the marker *is* what a `with` looks up, so it carries its own name and nothing else.
      case CoreQualifier.Implementation(n)                     => (Qualifier.Implementation(n): Qualifier).pure[ScopedIO]
    }

  /** The argument of a binding binder's **mark** (`Impl: Implementation[Console]`,
    * [[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter.implementationMark]]) — `Some` exactly when this
    * application is a mark, and then the ability's own marker value.
    *
    * The mark names an **ability**, and an ability resolves by [[resolveAbilityName]] and by nothing else (§2.5): it
    * is not in the type namespace, so the ordinary value path would find it only through the ability fallback, would
    * miss the fixed-FQN ones entirely, and would report a mistyped effect as "Name not defined." rather than "Ability
    * not found.". Recognised by the well-known FQN of its head, exactly as `&` is recognised.
    */
  private def markedAbility(
      resolvedTarget: Expression,
      argument: Sourced[CoreExpression]
  ): Option[ScopedIO[Sourced[Expression]]] = (resolvedTarget, argument.value) match {
    case (Expression.ValueReference(head, _), NamedValueReference(name, None, Seq()))
        if head.value === implementationTypeFQN =>
      Some(
        resolveAbilityName(name.map(_.name)).map(ability =>
          argument.as(
            Expression.ValueReference(
              argument.as(
                ValueFQN(
                  ability.moduleName,
                  CoreQualifiedName(ability.abilityName, CoreQualifier.Ability(ability.abilityName))
                )
              )
            )
          )
        )
      )
    case _                                      => None
  }

  private def resolveAbilityName(name: Sourced[String]): ScopedIO[AbilityFQN] =
    getAbility(name.value).flatMap {
      case Some(abilityName) => abilityName.pure[ScopedIO]
      case None              =>
        // The machinery abilities (`PatternMatch`/`TypeMatch`, and the `Effect` an empty row `{}` synthesizes) are
        // referenced by compiler-generated code, which should not depend on the user's import scope. They live at
        // fixed FQNs, so resolve them directly instead of requiring an (auto-)import.
        ValueResolver.fixedFqnAbilities.get(name.value) match {
          case Some(abilityFQN) => abilityFQN.pure[ScopedIO]
          case None             => compilerAbort(name.as(s"Ability not found.")).liftToScoped
        }
    }

  /** Resolve the effects-as-channel declared row (Phase 1, dark): each entry's ability name and type-arguments are
    * resolved exactly as [[resolveConstraint]] resolves a constraint, positions preserved.
    */
  private def resolveEffectRow(
      effectRow: EffectRow[UnresolvedAbilityConstraint[CoreExpression]]
  ): ScopedIO[EffectRow[AbilityConstraint[Expression]]] =
    effectRow.traverse(entry =>
      resolveConstraint(entry).flatTap(resolved => requireEffect(entry.abilityName, resolved.abilityFQN))
    )

  /** A `uses` clause lists **effects** only (`docs/effects.md` D28). An ability is a `~` constraint: filled by the
    * caller or answered by its default, and never handed down a call chain the way a clause's entries are, so no
    * callee can fix an ability's implementation for code its caller wrote.
    *
    * Effect-ness is read where it is recorded ([[AbilityKind]]). An ability whose members are not there to read is let
    * through: its own errors are reported where they arise.
    */
  private def requireEffect(name: Sourced[String], ability: AbilityFQN): ScopedIO[Unit] =
    getPlatform.flatMap { platform =>
      (for {
        effect    <- AbilityKind.isEffect(ability, platform)
        _         <- compilerAbort(
                       name.as(s"'${ability.abilityName}' is an ability, and a `uses` clause lists effects only."),
                       Seq(
                         s"Take it as a constraint on a type parameter (`[A ~ ${ability.abilityName}]`), which the " +
                           "caller fills or its default implementation answers."
                       )
                     ).whenA(effect.contains(false))
      } yield ()).liftToScoped
    }

  /** Resolve a value's `~` ability constraints, per generic parameter, **closed under what each named ability itself
    * requires of that parameter** ([[superConstraints]]).
    */
  private def resolveParamConstraints(
      paramConstraints: Map[String, Seq[UnresolvedAbilityConstraint[CoreExpression]]]
  ): ScopedIO[Map[String, Seq[AbilityConstraint[Expression]]]] =
    paramConstraints.toSeq
      .traverse { case (paramName, constraints) =>
        constraints
          .traverse(c =>
            for {
              resolved <- resolveConstraint(c)
              inherited <- superConstraints(resolved, paramName, Set.empty)
            } yield resolved +: inherited
          )
          .map(cs => paramName -> distinctConstraints(cs.flatten))
      }
      .map(_.toMap)

  private def resolveConstraint(
      constraint: UnresolvedAbilityConstraint[CoreExpression]
  ): ScopedIO[AbilityConstraint[Expression]] =
    for {
      _            <- constraint.combinedBy.traverse_(resolveCombinator)
      abilityFQN   <- resolveAbilityName(constraint.abilityName)
      resolvedArgs <- constraint.typeArgs.traverse(resolveExpression(_, false))
    } yield AbilityConstraint(abilityFQN, resolvedArgs)

  /** Resolve the operator that joined this constraint to the previous one, and require it to be the standard library's
    * `&` ([[abilityCombinatorFQN]]).
    *
    * This is the whole of what makes `&` a **name** rather than a symbol the parser recognises
    * (`docs/effects-syntax-userspace.md` §4 stage 1): the lookup is the ordinary dictionary one, so it honours import
    * scope and a module that declares its own `&` takes the name back — and then says so here instead of silently
    * meaning the built-in. The combinator is dropped after this check; nothing downstream of resolution knows a
    * constraint list was written with an operator at all.
    */
  private def resolveCombinator(combinator: Sourced[String]): ScopedIO[Unit] =
    getValue(CoreQualifiedName(combinator.value, CoreQualifier.Default)).flatMap {
      case Some(vfqn) if vfqn === abilityCombinatorFQN => ().pure[ScopedIO]
      case Some(vfqn)                                  =>
        compilerAbort(
          combinator.as(s"Does not combine ability constraints."),
          Seq(
            s"Resolved to: ${vfqn.show}",
            s"Ability constraints are combined with '&' (${abilityCombinatorFQN.moduleName.show})."
          )
        ).liftToScoped
      case None                                        =>
        compilerAbort(
          combinator.as("Ability constraint combinator not found."),
          Seq(
            s"'&' is declared in ${abilityCombinatorFQN.moduleName.show}, which is part of the prelude.",
            "A module declaring its own '&' shadows it."
          )
        ).liftToScoped
    }

  /** The constraints a named ability itself declares on the parameter this use bound to `paramName` — the superability
    * relation, and the one rule that lets a name stand for a set of effects.
    *
    * `ability Web[F[_] ~ Console & Log]` says a carrier that has `Web` has `Console` and `Log`, so `{Web}` — which
    * desugars to the constraint `Web[F]` — declares all three, and `derived ⊆ declared` sees the set the user named
    * (docs/effects-v5-one-carrier.md §7). The same rule states a relation the tree could not express before: `ability
    * Console[F[_] ~ Suspend]` puts "Console rides Suspend" in the ability instead of repeating it on every instance.
    *
    * Nothing here is read off a shape. **Which** of the ability's parameters to inherit from is decided by the use: the
    * one whose argument *is* this binder (`Web[F]` binds `Web`'s `F`, `Fallible[String, F]` binds `Fallible`'s second),
    * so a constraint the ability declares on an unrelated parameter (`E ~ Show`) stays there and never lands on the
    * carrier. The ability's own constraints are resolved **in the ability's own scope**, then its parameters are
    * substituted by the arguments this use wrote — the ordinary reading of a declaration.
    *
    * Closure is transitive and `expanding` makes it idempotent: an ability already inherited from contributes nothing a
    * second time, so mutually-requiring abilities close instead of looping. Only an ability found *in scope* is looked
    * up — the fixed-FQN machinery (`Effect`, `PatternMatch`, `TypeMatch`) is compiler-written and requires nothing.
    */
  private def superConstraints(
      use: AbilityConstraint[Expression],
      paramName: String,
      expanding: Set[AbilityFQN]
  ): ScopedIO[Seq[AbilityConstraint[Expression]]] =
    if (expanding.contains(use.abilityFQN)) Seq.empty[AbilityConstraint[Expression]].pure[ScopedIO]
    else
      // The ability's marker carries its common generic parameters, so its presence in scope is both the lookup and
      // the "is this a real, in-scope ability" test — a direct dictionary hit rather than a scan.
      getValue(markerOf(use.abilityFQN).name).flatMap {
        case None    => Seq.empty[AbilityConstraint[Expression]].pure[ScopedIO]
        case Some(_) =>
          for {
            platform <- getPlatform
            marker   <- getFactOrAbort(UnifiedModuleValue.Key(markerOf(use.abilityFQN), platform)).liftToScoped
            params    = collectGenericParamsFromExpr(marker.namedValue.signature.value)
            inherited = params
                          .zip(use.typeArgs)
                          .collectFirst {
                            case (param, Expression.ParameterReference(n)) if n.value === paramName => param
                          }
                          .toSeq
                          .flatMap(marker.namedValue.paramConstraints.getOrElse(_, Seq.empty))
            resolved <- inherited.traverse(c =>
                          resolveInAbilityScope(use.abilityFQN, marker, params, platform)(resolveConstraint(c))
                        )
            bindings  = params.zip(use.typeArgs).toMap
            direct    = resolved.map(c => c.copy(typeArgs = c.typeArgs.map(substituteParameters(bindings))))
            deeper   <- direct.traverse(superConstraints(_, paramName, expanding + use.abilityFQN))
          } yield direct ++ deeper.flatten
      }

  /** An ability's marker value — the synthetic `Foo^Foo` [[AbilityBlock]] emits, which is where its common generic
    * parameters (and so its `~` constraints) live.
    */
  private def markerOf(abilityFQN: AbilityFQN): ValueFQN =
    ValueFQN(
      abilityFQN.moduleName,
      CoreQualifiedName(abilityFQN.abilityName, CoreQualifier.Ability(abilityFQN.abilityName))
    )

  private def resolveInAbilityScope[T](
      abilityFQN: AbilityFQN,
      marker: UnifiedModuleValue,
      params: Seq[String],
      platform: Platform
  )(computation: ScopedIO[T]): ScopedIO[T] =
    resolveInValueScope(abilityFQN.moduleName, marker, params, platform)(computation)

  /** Run a resolution in the scope of the *declaring* value rather than this one — its module, its dictionary, its
    * private names and its own parameters. What a declaration says is written in the names its own file has.
    */
  private def resolveInValueScope[T](
      moduleName: ModuleName,
      declaring: UnifiedModuleValue,
      params: Seq[String],
      platform: Platform
  )(computation: ScopedIO[T]): ScopedIO[T] =
    computation
      .runA(ValueResolverScope(moduleName, declaring.dictionary, declaring.privateNames, params.toSet, platform))
      .liftToScoped

  /** Replace every reference to one of the ability's own parameters by the argument this use wrote for it. */
  private def substituteParameters(bindings: Map[String, Expression])(expr: Expression): Expression =
    expr match {
      case Expression.ParameterReference(name) if bindings.contains(name.value) => bindings(name.value)
      case other                                                                =>
        Expression.mapChildrenM[Id](substituteParameters(bindings))(other)
    }

  /** The resolved `with` `bound` as its implementation's binder applied to `arguments` and then to it. */
  private def installed(
      marker: ValueFQN,
      implementationName: Sourced[String],
      arguments: Seq[Sourced[Expression]],
      bound: Expression
  ): Expression =
    (arguments :+ implementationName.as(bound))
      .foldLeft[Sourced[Expression]](
        implementationName.as(
          Expression.ValueReference(implementationName.as(ImplementationParameters.binderOf(marker)))
        )
      )((target, argument) => argument.as(Expression.FunctionApplication(target, argument)))
      .value

  private def constraintKeys(constraints: Seq[AbilityConstraint[Expression]]): Set[(AbilityFQN, Seq[String])] =
    constraints.map(constraintKey).toSet

  private def constraintKey(constraint: AbilityConstraint[Expression]): (AbilityFQN, Seq[String]) =
    (constraint.abilityFQN, constraint.typeArgs.map(_.render))

  private def distinctConstraints(
      constraints: Seq[AbilityConstraint[Expression]]
  ): Seq[AbilityConstraint[Expression]] =
    constraints.distinctBy(c => (c.abilityFQN, c.typeArgs.map(_.render)))

  /** Collects generic parameter names from the signature. Generic params are FunctionLiterals with a type annotation
    * (paramType is Some). All FunctionLiterals in type position are universal intros.
    */
  private def collectGenericParamsFromExpr(expr: CoreExpression): Seq[String] =
    expr match {
      case FunctionLiteral(paramName, Some(_), body) =>
        paramName.value +: collectGenericParamsFromExpr(body.value)
      case _                                         => Seq.empty
    }

  private def resolveExpression(
      expression: CoreExpression,
      runtime: Boolean
  ): ScopedIO[Expression] =
    expression match {
      // Effects v6 §9.4 step 1: the name is resolved here, to the implementation's marker, and only that marker
      // flows onward — a name carried downstream would ignore import scope and be decided by hash order.
      //
      // Every `with` in a body is lowered here to a call of the implementation's binder declaration around the resolved
      // `with`, so the environment threading sees each place an implementation is installed (`docs/effects.md` D35
      // step 2): `greet with recordingConsole` is `recordingConsole$with(greet with recordingConsole)`. One supplying
      // arguments (`greet with scripted(List("Bob"))`) passes them ahead of the subject
      // (`scripted$with(List("Bob"), greet with scripted)`, [[ImplementationParameters]]): the checker types them as any
      // call's arguments, and they stand outside the `with`, so they see none of its bindings. A `with` on a parameter's
      // clause entry is not an install site and stays as it is written.
      case WithBinding(subject, implementationName, moduleName, arguments, entry) =>
        for {
          resolvedSubject <- resolveExpression(subject.value, runtime).map(subject.as)
          marker          <- ImplementationNameResolver.resolve(implementationName, moduleName)
          parameters      <- ImplementationNameResolver.parametersOf(marker)
          bound            = Expression.WithBinding(resolvedSubject, implementationName.as(marker), entry)
          lowered         <- parameters match {
            case ImplementationNameResolver.Parameters.None if arguments.isEmpty && !runtime =>
              bound.pure[ScopedIO]
            case ImplementationNameResolver.Parameters.None if arguments.isEmpty             =>
              installed(marker, implementationName, Seq.empty, bound).pure[ScopedIO]
            case ImplementationNameResolver.Parameters.None                      =>
              compilerAbort[Expression](
                arguments.head.as(s"The implementation '${implementationName.value}' takes no parameters.")
              ).liftToScoped
            case _ if arguments.isEmpty                                           =>
              compilerAbort[Expression](
                implementationName.as(
                  s"The implementation '${implementationName.value}' takes parameters, and they are supplied where " +
                    s"it is bound: `with ${implementationName.value}(…)`."
                )
              ).liftToScoped
            case _ if !runtime                                                    =>
              compilerAbort[Expression](
                implementationName.as(
                  s"The implementation '${implementationName.value}' takes parameters, which a parameter's clause " +
                    "cannot supply."
                ),
                Seq(s"Bind it with `with ${implementationName.value}(…)` in the body, where its arguments are computed.")
              ).liftToScoped
            case ImplementationNameResolver.Parameters.Present                    =>
              arguments
                .traverse(argument => resolveExpression(argument.value, runtime).map(argument.as))
                .map(installed(marker, implementationName, _, bound))
          }
        } yield lowered
      case NamedValueReference(nameSrc, None, typeArgExprs)          =>
        isParameter(nameSrc.value.name).flatMap { isParam =>
          if (isParam) {
            // This is a parameter defined in the expression - parameters can't have explicit type args
            Expression.ParameterReference(nameSrc.map(_.name)).pure[ScopedIO]
          } else if (nameSrc.value.name === "Type") {
            // Type is a special builtin for type-level parameters (same on all plains)
            Expression.ValueReference(nameSrc.as(typeFQN)).pure[ScopedIO]
          } else {
            // This should be a referenced value
            getValue(nameSrc.value).flatMap {
              case Some(vfqn) =>
                // This is a normal value; resolve any explicit type args
                typeArgExprs
                  .traverse(arg => resolveExpression(arg.value, false).map(arg.as(_)))
                  .map(resolvedTypeArgs => Expression.ValueReference(nameSrc.as(vfqn), resolvedTypeArgs))
              case None       =>
                // Not a locally-defined value; it might be coming from an ability. An explicitly-applied name
                // (`keep[42]` — explicit bracket args) is a dispatch through the ability at those arguments: the
                // member is an ordinary named value whose leading parameters are the ability's.
                searchAbilities(nameSrc.value.name).flatMap {
                  case Nil         =>
                    // Check if the name exists as a private import
                    getPrivateName(nameSrc.value).flatMap {
                      case Some(_) => compilerAbort(nameSrc.as("Name is private.")).liftToScoped
                      case None    => compilerAbort(nameSrc.as("Name not defined.")).liftToScoped
                    }
                  case head :: Nil =>
                    // Explicit type arguments are the ability-level dispatch arguments — resolve and keep them
                    // (they were formerly dropped here, leaving the reference unconstrained).
                    typeArgExprs
                      .traverse(arg => resolveExpression(arg.value, false).map(arg.as(_)))
                      .map(resolved => Expression.ValueReference(nameSrc.as(head), resolved))
                  case as          =>
                    compilerAbort(
                      nameSrc.as("Name defined in multiple abilities."),
                      Seq(
                        s"Abilities: ${as.map(_.name.qualifier.asInstanceOf[CoreQualifier.Ability].name).mkString(", ")}"
                      )
                    ).liftToScoped
                }
            }
          }
        }
      case NamedValueReference(nameSrc, Some(qualSrc), typeArgExprs) =>
        val moduleName = ModuleName.parse(qualSrc.value)
        val vfqn       = ValueFQN(moduleName, nameSrc.value)
        val outline    = Sourced.outline(Seq(qualSrc, nameSrc))

        getPlatform.flatMap(platform => getFactIfProduced(UnifiedModuleValue.Key(vfqn, platform)).liftToScoped).flatMap {
          case Some(umv) =>
            getCurrentModule.flatMap { currentMod =>
              if (umv.namedValue.visibility == Visibility.Private && moduleName != currentMod) {
                compilerAbort(nameSrc.as("Name is private.")).liftToScoped
              } else {
                typeArgExprs
                  .traverse(arg => resolveExpression(arg.value, false).map(arg.as(_)))
                  .map(resolvedTypeArgs => Expression.ValueReference(outline.as(vfqn), resolvedTypeArgs))
              }
            }
          case None      => compilerAbort(nameSrc.as("Qualified named value not available.")).liftToScoped
        }
      case FunctionApplication(target, arg)                          =>
        for {
          resolvedTarget <- resolveExpression(target.value, runtime).map(target.as)
          resolvedArg    <- markedAbility(resolvedTarget.value, arg).getOrElse(
                              resolveExpression(arg.value, runtime).map(arg.as)
                            )
        } yield Expression.FunctionApplication(resolvedTarget, resolvedArg)
      case FunctionLiteral(paramName, paramType, body)               =>
        for {
          resolvedParamType <- paramType.traverse(t => resolveExpression(t.value, false).map(t.as))
          resolvedBody      <- withLocalScope {
                                 for {
                                   _    <- addParameter(paramName.value)
                                   body <- resolveExpression(body.value, runtime).map(body.as)
                                 } yield body
                               }
        } yield Expression.FunctionLiteral(paramName, resolvedParamType, resolvedBody)
      case IntegerLiteral(s @ Sourced(_, _, value))                  =>
        Expression.IntegerLiteral(s.as(BigInt(value))).pure[ScopedIO]
      case StringLiteral(s @ Sourced(_, _, value))                   =>
        Expression.StringLiteral(s.as(value)).pure[ScopedIO]
      case FlatExpression(parts)                                     =>
        parts.traverse(part => resolveExpression(part.value, runtime).map(part.as)).map(Expression.FlatExpression(_))
      case CoreExpression.MatchExpression(scrutinee, cases)          =>
        for {
          resolvedScrutinee <- resolveExpression(scrutinee.value, runtime).map(scrutinee.as)
          resolvedCases     <- cases.traverse { c =>
                                 withLocalScope {
                                   for {
                                     resolvedPattern <- resolvePattern(c.pattern)
                                     resolvedBody    <- resolveExpression(c.body.value, runtime).map(c.body.as)
                                   } yield Expression.MatchCase(resolvedPattern, resolvedBody)
                                 }
                               }
        } yield Expression.MatchExpression(resolvedScrutinee, resolvedCases)
      case BlockExpression(lines)                                    =>
        withLocalScope(resolveBlockLines(lines, runtime)).map(Expression.BlockExpression.apply)
    }

  /** Resolves a block's lines in order, threading the binder scope: each binder is added to scope *before* its own line
    * (and every later line) is resolved, so a self-reference resolves to that local — caught as a hard error at
    * lowering — and later lines can reference it. The whole block is wrapped in a [[withLocalScope]] by the caller so
    * the binders do not leak past the block.
    */
  private def resolveBlockLines(lines: Seq[CoreExpression.BlockLine], runtime: Boolean): ScopedIO[Seq[Expression.BlockLine]] =
    lines match {
      case Nil          => Seq.empty[Expression.BlockLine].pure[ScopedIO]
      case line :: rest =>
        for {
          resolvedType <- (line.binderName, line.binderType) match {
                            case (Some(n), Some(t)) => resolveExpression(t.value, false).map(e => Some(n.as(e)))
                            case _                  => None.pure[ScopedIO]
                          }
          _            <- line.binderName.traverse(n => addParameter(n.value))
          resolvedExpr <- resolveExpression(line.expression.value, runtime).map(line.expression.as)
          resolvedRest <- resolveBlockLines(rest, runtime)
        } yield Expression.BlockLine(line.binderName, resolvedType, resolvedExpr) +: resolvedRest
    }

  private def resolvePattern(pattern: Sourced[CorePattern]): ScopedIO[Sourced[Pattern]] =
    pattern.value match {
      case CorePattern.ConstructorPattern(None, nameSrc, subPatterns)          =>
        getValue(nameSrc.value).flatMap {
          case Some(vfqn) =>
            subPatterns
              .traverse(resolvePattern)
              .map(resolved => pattern.as(Pattern.ConstructorPattern(nameSrc.as(vfqn), resolved)))
          case None       =>
            compilerAbort(nameSrc.as("Constructor not defined.")).liftToScoped
        }
      case CorePattern.ConstructorPattern(Some(qualSrc), nameSrc, subPatterns) =>
        val moduleName = ModuleName.parse(qualSrc.value)
        val vfqn       = ValueFQN(moduleName, nameSrc.value)
        val outline    = Sourced.outline(Seq(qualSrc, nameSrc))

        getPlatform.flatMap(platform => getFactIfProduced(UnifiedModuleValue.Key(vfqn, platform)).liftToScoped).flatMap {
          case Some(_) =>
            subPatterns
              .traverse(resolvePattern)
              .map(resolved => pattern.as(Pattern.ConstructorPattern(outline.as(vfqn), resolved)))
          case None    =>
            compilerAbort(nameSrc.as("Qualified constructor not available.")).liftToScoped
        }
      case CorePattern.VariablePattern(name)                                   =>
        addParameter(name.value).as(pattern.as(Pattern.VariablePattern(name)))
      case CorePattern.WildcardPattern(source)                                 =>
        (pattern.as(Pattern.WildcardPattern(source)): Sourced[Pattern]).pure[ScopedIO]
    }

  private def resolvePrecedenceDeclarations(
      decls: Seq[CorePrecedenceDeclaration]
  ): ScopedIO[Seq[PrecedenceDeclaration]] =
    decls.traverse(resolvePrecedenceDeclaration)

  private def resolvePrecedenceDeclaration(
      decl: CorePrecedenceDeclaration
  ): ScopedIO[PrecedenceDeclaration] =
    decl.targets
      .traverse(target =>
        getValue(CoreQualifiedName(target.value, CoreQualifier.Default)).flatMap {
          case Some(vfqn) => target.as(vfqn).pure[ScopedIO]
          case None       => compilerAbort(target.as("Precedence target name not defined.")).liftToScoped
        }
      )
      .flatMap(validateApplyPrecedence(decl.relation, _))

  private def validateApplyPrecedence(
      relation: AstPrecedenceDeclaration.Relation,
      resolvedTargets: Seq[Sourced[ValueFQN]]
  ): ScopedIO[PrecedenceDeclaration] =
    resolvedTargets.find(_.value === ValueFQN.applyFQN) match {
      case Some(applyTarget) if relation == AstPrecedenceDeclaration.Relation.Above =>
        compilerAbort(applyTarget.as("Infix operator cannot have higher precedence than application.")).liftToScoped
      case Some(applyTarget) if relation == AstPrecedenceDeclaration.Relation.At    =>
        compilerAbort(applyTarget.as("Infix operator cannot have the same precedence as application.")).liftToScoped
      case _                                                                        =>
        PrecedenceDeclaration(relation, resolvedTargets).pure[ScopedIO]
    }
}

object ValueResolver {

  /** The machinery abilities resolved by **fixed FQN** rather than via import scope, because only the compiler ever
    * writes the reference:
    *
    *   - `PatternMatch`/`TypeMatch` (in `eliot.compiler.internal`), named by compiler-generated `implement` markers and
    *     by the `match` desugaring;
    *   - `Effect` (in the import-required `eliot.carrier`), synthesized by the **empty row** `{}`
    *     ([[com.vanillasource.eliot.eliotc.core.processor.EffectSugarDesugarer]]) — a definition writing `{}` names
    *     nothing, so it must not need `import eliot.carrier.Effect` to say "on my own ambient carrier".
    *
    * None of these is auto-imported. An explicit import (or a local declaration) of the same name still wins: this map
    * is only reached when the name is not in scope at all. */
  private val fixedFqnAbilities: Map[String, AbilityFQN] =
    Seq(patternMatchAbilityName, typeMatchAbilityName)
      .map(name => name -> AbilityFQN(ModuleName(ModuleName.compilerInternalPackage, name), name))
      .toMap
}
