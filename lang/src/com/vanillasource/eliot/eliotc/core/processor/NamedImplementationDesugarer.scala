package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  Expression,
  FunctionDefinition,
  ImplementationParameters,
  ImplementationRows,
  NamedImplementation,
  Visibility
}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}

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
  * A named implementation that takes **parameters** (`implement scripted(lines: List[String]): Terminal`) adds two
  * declarations, as [[ImplementationParameters]] describes: the parameters declaration, and the binder a `with`
  * supplying arguments is lowered to. Each clause receives the parameters ahead of its own.
  *
  * A named implementation is always public: it exists to be named from elsewhere.
  */
object NamedImplementationDesugarer {

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
      ImplementationParameters.declaration(anchor, qualifier, implementation.genericParameters, implementation.parameters, clauseRow).toSeq ++
        ImplementationParameters.binder(anchor, qualifier, implementation.genericParameters, implementation.parameters)

    (methods ++ declarations :+ marker) :+ nameMarker
  }
}
