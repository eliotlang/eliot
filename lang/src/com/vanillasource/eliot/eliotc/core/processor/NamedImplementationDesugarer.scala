package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
  GenericParameter,
  UnresolvedAbilityConstraint,
  DataDefinition,
  Expression,
  FunctionDefinition,
  Handles,
  ImplementationParameters,
  ImplementationRows,
  LambdaParameterDefinition,
  NamedImplementation,
  Visibility
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Lowers a **named** `implement` (effects v6, `docs/effects.md` §9.3) into ordinary [[FunctionDefinition]]s, the way
  * [[com.vanillasource.eliot.eliotc.ast.fact.ImplementBlock]] lowers the anonymous form — with one addition.
  *
  * {{{
  * implement recordingConsole: Console {
  *    def printLine(s: String): {Writer[String]} Unit = tell(s ++ ";")
  *    def readLine: Option[String] = None
  * }
  * }}}
  *
  * yields three kinds of value:
  *
  *   - one **method** per clause, in the implementation's namespace `Qualifier.AbilityImplementation(ability, pattern,
  *     Some(name))` — the same shape an anonymous `implement` produces, distinguished from it (and from every other
  *     named implementation of the same pattern) by the name component alone. Under v6 every implementation of a
  *     nullary effect shares the empty pattern, which is precisely why the name is part of the identity (§10.1 step 6).
  *   - the implementation's **marker**, the member whose local name is the ability's — what
  *     [[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding]] recognises as a binding, and what a
  *     phantom binder carries at a reference. It takes one argument per pattern element, exactly as the anonymous
  *     form's does. Its return slot carries the guard, and a named implementation has none: it is never searched and
  *     never checked for overlap (§9.3), so the slot is the unguarded `true`.
  *   - the implementation's **name marker**, `QualifiedName(name, Qualifier.Implementation(name))` — a body-less type
  *     declaration that exists only to make `with name` a keyed dictionary lookup
  *     ([[com.vanillasource.eliot.eliotc.resolve.processor.ValueResolverScope.getImplementation]]), mirroring the
  *     ability marker `Foo^Ability(Foo)` that makes an ability name resolve like any other name. The marker above
  *     cannot serve that role: its qualified name needs the ability name and the pattern key, neither of which the
  *     surface `with recordingConsole` supplies. The resolver maps the name marker to the real marker through its
  *     module's [[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities.markerOfImplementationName]], and only the
  *     real marker flows onward.
  *
  * Every named implementation adds the binder a `with` naming it is lowered to, and one that takes **parameters**
  * (`implement scripted(lines: List[String]): Terminal`) the parameters declaration too, as [[ImplementationParameters]]
  * describes. Each clause receives the parameters ahead of its own.
  *
  * A named implementation is always public: it exists to be named from elsewhere.
  */
object NamedImplementationDesugarer {

  /** A named implementation is a **`data`** and an
    * **ordinary instance** of its effect's ability, whose first pattern argument is that data type.
    *
    * {{{
    * implement scripted(answer: String): Console { def printLine(line: String): Unit = … answer … }
    *   ⟶ data scripted$Handle(scripted$answer: String)
    *     implement Console[scripted$Handle] {
    *        def printLine(self$handle: scripted$Handle, line: String): Unit = { val answer = scripted$answer(self$handle); … }
    *     }
    * }}}
    *
    * The data's value is the **handle** a `with` puts in scope, and its fields are the implementation's parameters, so a
    * clause reads them off its own handle. Every implementation has a type of its own, so its instance is the only one
    * at its pattern: the two-site search finds it in the data's module, and nothing overlaps it. The name marker and the
    * `with` binder stay, unchanged, and so does its parameters declaration, so a `with` naming the implementation
    * resolves and supplies its arguments as before; the `row` phase turns the
    * binder's call into the handle in scope. So does the implementation's marker under its own name, with no clause
    * beside it: it is what the resolver reaches the binder through.
    */
  def desugar(implementation: NamedImplementation): Seq[(FunctionDefinition, RoleHint)] = {
    val abilityName = implementation.ability.value
    val implName    = implementation.name.value
    val anchor      = implementation.name
    val clauses     = implementation.functions.map(ImplementationRows.own)
    // Every entry a clause writes may be captured where the implementation is bound (`docs/effects.md` D36 point 2):
    // which ones are — those no member declares — is the `row` phase's to read, since the members are declared in
    // another file. The handle has a field for each, of a type of its own, and a `with` fills the ones not captured
    // with `unit`.
    val written     = ImplementationRows.union(clauses.filter(_.name.value.qualifier != Qualifier.Type))
    val captures    = written.indices.map(j =>
      GenericParameter(anchor.as(Handles.capture(j)), anchor.as(typeExpr(anchor.as("Type"))), Seq.empty)
    )
    // The handle takes only the type parameters its value fixes: those its fields' types or its clauses' entries
    // mention. Any other is the pattern's alone, so the instance's pattern leaves it free and each use solves it — one
    // `with showing` serves `Source[Int]` and `Source[String]` (`docs/effects.md` §8 item 30).
    val fixed       = implementation.genericParameters.filter(gp =>
      implementation.parameters.exists(p => mentions(p.typeExpression, gp.name.value)) ||
        written.exists(_.typeArgs.exists(mentions(_, gp.name.value)))
    )
    val generics    = fixed.map(_.copy(abilityConstraints = Seq.empty)) ++ captures
    val handleType  = anchor.as(
      Expression.FunctionApplication(
        None,
        anchor.as(Handles.dataType(implName)),
        // Always bracketed: the name is lower-case, and only brackets put a lower-case name in the type namespace.
        Some(generics.map(gp => gp.name.as(typeExpr(gp.name)))),
        Seq.empty
      )
    )
    val data        = DataDefinition(
      anchor.as(Handles.dataType(implName)),
      generics,
      Some(
        Seq(
          DataConstructor(
            anchor.as(Handles.dataType(implName)),
            implementation.parameters.map(p => p.copy(name = p.name.map(Handles.field(implName, _)))) ++
              captures.indices.map(j =>
                ArgumentDefinition(anchor.as(Handles.captureField(implName, j)), anchor.as(typeExpr(anchor.as(Handles.capture(j)))))
              )
          )
        )
      )
    )
    val pattern     = handleType +: implementation.pattern
    val qualifier   = Qualifier.AbilityImplementation(abilityName, pattern.map(_.value.render).mkString(", "), None)
    // A prefix of every clause's binders, ahead of those the clause's own row mints: dispatch hands a clause its
    // instance's type arguments by position.
    val instanceGenerics = (implementation.genericParameters ++ captures).map(_.copy(abilityLevel = true))
    val methods     = clauses.map { f =>
      if (f.name.value.qualifier == Qualifier.Type)
        f.copy(
          name = f.name.map(n => QualifiedName(n.name, qualifier)),
          genericParameters = instanceGenerics ++ f.genericParameters,
          visibility = Visibility.Public
        )
      else
        f.copy(
          name = f.name.map(n => QualifiedName(n.name, qualifier)),
          genericParameters = instanceGenerics ++ f.genericParameters,
          args = ArgumentDefinition(anchor.as(Handles.self), handleType) +: f.args,
          body = f.body.map(fieldsBound(implName, implementation.parameters, _)),
          visibility = Visibility.Public
        )
    }
    val instance    = FunctionDefinition(
      implementation.ability.as(QualifiedName(abilityName, qualifier)),
      instanceGenerics,
      pattern.zipWithIndex.map { case (p, i) => ArgumentDefinition(anchor.as(s"arg$i"), p) },
      Expression.trueReference(implementation.ability),
      None
    )
    val named       = Qualifier.AbilityImplementation(
      abilityName,
      implementation.pattern.map(_.value.render).mkString(", "),
      Some(implName)
    )
    val namedMarker = FunctionDefinition(
      implementation.ability.as(QualifiedName(abilityName, named)),
      implementation.genericParameters,
      implementation.pattern.zipWithIndex.map { case (p, i) => ArgumentDefinition(anchor.as(s"arg$i"), p) },
      Expression.trueReference(implementation.ability),
      None
    )
    val nameMarker  = FunctionDefinition(
      anchor.as(QualifiedName(implName, Qualifier.Implementation(implName))),
      Seq.empty,
      Seq.empty,
      anchor.as(Expression.FunctionApplication(None, anchor.as("Type"), None, Seq.empty)),
      None
    )
    // Only a layer that bodies the implementation mints its handle: the `data` lowers to values with bodies of their
    // own, which two layers could not both supply. The base's `implement standardConsole: Console`, and its
    // `standardFileSystem` whose clauses carry no body, only declare the name a `with` resolves to.
    val bodied      = implementation.functions.exists(f => f.name.value.qualifier != Qualifier.Type && f.body.isDefined)
    val declaration =
      (ImplementationParameters
        .declaration(anchor, named, implementation.genericParameters, implementation.parameters, Seq.empty)
        .toSeq :+ namedMarker :+ nameMarker :+
        ImplementationParameters.binder(anchor, named, implementation.genericParameters, implementation.parameters))
        .map(_ -> RoleHint.NoHint)
    def recording(name: QualifiedName, genericParameters: Seq[GenericParameter], row: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]]) =
      FunctionDefinition(
        anchor.as(name),
        genericParameters,
        Seq.empty,
        ImplementationRows.rowed(row, anchor.as(Expression.FunctionApplication(None, anchor.as("Unit"), None, Seq.empty))),
        None
      )
    val recorded    =
      Seq(
        recording(QualifiedName(Handles.captures, named), implementation.genericParameters, written),
        recording(QualifiedName(Handles.captures, qualifier), instanceGenerics, written)
      ) ++ clauses.filter(_.name.value.qualifier != Qualifier.Type).map(f =>
        recording(
          QualifiedName(Handles.clause(f.name.value.name), named),
          implementation.genericParameters ++ f.genericParameters,
          ImplementationRows.entriesOf(f.typeDefinition)
        )
      )
    if (bodied)
      DataDefinitionDesugarer.desugar(data) ++ (methods ++ recorded :+ instance).map(_ -> RoleHint.NoHint) ++ declaration
    else declaration
  }

  /** A named implementation's type parameters its pattern does not take (`docs/effects.md` D29), each as the error
    * at the implementation's name: nothing where it runs determines one. The check is made here, at the declaration,
    * since nothing demands the named marker's `row` phase.
    */
  def unpatterned(implementation: NamedImplementation): Seq[(Sourced[String], Seq[String])] =
    implementation.genericParameters
      .filterNot(gp => implementation.pattern.exists(mentions(_, gp.name.value)))
      .map { gp =>
        implementation.name.as(
          s"The type parameter '${gp.name.value}' of the implementation '${implementation.name.value}' is not taken by " +
            "its pattern, so nothing where it runs determines it."
        ) -> Seq(s"Use '${gp.name.value}' in the implemented '${implementation.ability.value}[…]', or remove it.")
      }

  /** A clause's body with each of the implementation's parameters bound to its field of the clause's own handle, so
    * the body names them as it always did. Each binding line stands on the body's own line, so a block never joins it
    * to the body as a continuation.
    */
  private def fieldsBound(
      implName: String,
      parameters: Seq[ArgumentDefinition],
      body: Sourced[Expression]
  ): Sourced[Expression] =
    if (parameters.isEmpty) body
    else
      body.as(
        Expression.BlockExpression(
          parameters.map { p =>
            Expression.BlockLine(
              Some(LambdaParameterDefinition(p.name, Some(p.typeExpression))),
              body.as(
                Expression.FlatExpression(
                  Seq(
                    body.as(
                      Expression.FunctionApplication(
                        None,
                        body.as(Handles.field(implName, p.name.value)),
                        None,
                        Seq(body.as(typeExpr(body.as(Handles.self))))
                      )
                    )
                  )
                )
              )
            )
          } :+ Expression.BlockLine(None, body)
        )
      )

  /** Whether a type expression mentions the type parameter `name`. A shape a type does not take answers `true`, which
    * keeps the parameter on the handle: one handle then fixes it, which is never wrong, only less general.
    */
  private def mentions(expression: Sourced[Expression], name: String): Boolean =
    expression.value match {
      case Expression.FunctionApplication(module, function, generics, arguments) =>
        (module.isEmpty && function.value == name) ||
        generics.exists(_.exists(mentions(_, name))) || arguments.exists(mentions(_, name))
      case Expression.FlatExpression(parts)                                      => parts.exists(mentions(_, name))
      case Expression.FunctionLiteral(parameters, body)                          =>
        parameters.exists(_.typeExpression.exists(mentions(_, name))) || mentions(body, name)
      case Expression.EffectfulType(effects, result)                             =>
        effects.exists(_.typeArgs.exists(mentions(_, name))) || mentions(result, name)
      case Expression.IntegerLiteral(_) | Expression.StringLiteral(_)            => false
      case _                                                                     => true
    }

  private def typeExpr(name: Sourced[String]): Expression =
    Expression.FunctionApplication(None, name, None, Seq.empty)
}
