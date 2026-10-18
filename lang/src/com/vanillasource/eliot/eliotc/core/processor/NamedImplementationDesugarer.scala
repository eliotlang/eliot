package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
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

  /** The lowering on the new effect path (`docs/effects.md` D36 point 2): a named implementation is a **`data`** and an
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
  def desugar(implementation: NamedImplementation, handles: Boolean): Seq[(FunctionDefinition, RoleHint)] =
    if (!handles) desugar(implementation).map(_ -> RoleHint.NoHint)
    else {
      val abilityName = implementation.ability.value
      val implName    = implementation.name.value
      val anchor      = implementation.name
      val generics    = implementation.genericParameters.map(_.copy(abilityConstraints = Seq.empty))
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
              implementation.parameters.map(p => p.copy(name = p.name.map(Handles.field(implName, _))))
            )
          )
        )
      )
      val pattern     = handleType +: implementation.pattern
      val qualifier   = Qualifier.AbilityImplementation(abilityName, pattern.map(_.value.render).mkString(", "), None)
      val methods     = implementation.functions.map(ImplementationRows.own).map { f =>
        if (f.name.value.qualifier == Qualifier.Type)
          f.copy(
            name = f.name.map(n => QualifiedName(n.name, qualifier)),
            genericParameters = implementation.genericParameters ++ f.genericParameters,
            visibility = Visibility.Public
          )
        else
          f.copy(
            name = f.name.map(n => QualifiedName(n.name, qualifier)),
            genericParameters = implementation.genericParameters ++ f.genericParameters,
            args = ArgumentDefinition(anchor.as(Handles.self), handleType) +: f.args,
            body = f.body.map(fieldsBound(implName, implementation.parameters, _)),
            visibility = Visibility.Public
          )
      }
      val instance    = FunctionDefinition(
        implementation.ability.as(QualifiedName(abilityName, qualifier)),
        implementation.genericParameters,
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
      if (bodied) DataDefinitionDesugarer.desugar(data) ++ (methods :+ instance).map(_ -> RoleHint.NoHint) ++ declaration
      else declaration
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

  private def typeExpr(name: Sourced[String]): Expression =
    Expression.FunctionApplication(None, name, None, Seq.empty)

  def desugar(implementation: NamedImplementation): Seq[FunctionDefinition] = {
    val abilityName = implementation.ability.value
    val implName    = implementation.name.value
    // No `where` guard is parsed for a named implementation, so — unlike the anonymous form — the pattern key is the
    // pattern alone. It is still the same canonical, position-independent string, so a named implementation split
    // across layers merges exactly as an anonymous one does.
    val patternKey  = implementation.pattern.map(_.value.render).mkString(", ")
    val qualifier   = Qualifier.AbilityImplementation(abilityName, patternKey, Some(implName))
    val anchor      = implementation.name

    val clauseRow = ImplementationRows.union(implementation.functions)

    // An associated type (`type Cases[R]`) carries no clause row and takes no parameters; a clause carries both.
    val methods = implementation.functions.map(f =>
      f.copy(
        name = f.name.map(n => QualifiedName(n.name, qualifier)),
        genericParameters = implementation.genericParameters ++ f.genericParameters,
        args = (if (f.name.value.qualifier == Qualifier.Type) Seq.empty else implementation.parameters) ++ f.args,
        visibility = Visibility.Public
      )
    )

    val marker = FunctionDefinition(
      implementation.ability.as(QualifiedName(abilityName, qualifier)),
      implementation.genericParameters,
      implementation.pattern.zipWithIndex.map { case (p, i) => ArgumentDefinition(anchor.as(s"arg$i"), p) },
      // The clause row rides the guard slot, exactly as it does for the anonymous form: the row is erased from every
      // type, so the unguarded `true` is untouched, and what the marker gains is the phantom-binder declaration
      // `BindingWriter` reads back to write `recordingConsole[…]` at a `with` ([[ImplementationRows]]).
      ImplementationRows.rowed(clauseRow, Expression.trueReference(implementation.ability)),
      None
    )

    val nameMarker = FunctionDefinition(
      anchor.as(QualifiedName(implName, Qualifier.Implementation(implName))),
      Seq.empty,
      Seq.empty,
      anchor.as(Expression.FunctionApplication(None, anchor.as("Type"), None, Seq.empty)),
      None
    )

    val declarations =
      ImplementationParameters.declaration(anchor, qualifier, implementation.genericParameters, implementation.parameters, clauseRow).toSeq :+
        ImplementationParameters.binder(anchor, qualifier, implementation.genericParameters, implementation.parameters)

    (methods ++ declarations :+ marker) :+ nameMarker
  }
}
