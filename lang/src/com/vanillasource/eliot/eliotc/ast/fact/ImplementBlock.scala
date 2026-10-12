package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIfAll, between, optional, or, recoveringAtLeastOnce}
import com.vanillasource.eliot.eliotc.ast.parser.{Parser, ParserError}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

object ImplementBlock {
  val implementBlock: ASTComponent[(Seq[ParserError], Seq[FunctionDefinition])] =
    new ASTComponent[(Seq[ParserError], Seq[FunctionDefinition])] {
      override val parser: Parser[Sourced[Token], (Seq[ParserError], Seq[FunctionDefinition])] =
        for {
          _                   <- keyword("implement")
          genericParameters   <- component[Seq[GenericParameter]]
          name                <- acceptIfAll(isIdentifier, isUpperCase)("ability name")
          // The pattern is optional so a **nullary** ability implements with no brackets at all — `implement Console`,
          // which is the shape every effects-v6 `effect` takes once its carrier binder is gone (`docs/effects.md` §9.3).
          // No source in the tree writes a pattern-less `implement` today, so accepting one changes nothing until the
          // flag day; the named form (`NamedImplementation`) has always read its pattern optionally.
          pattern             <- optionalBracketedCommaSeparatedItems("[", sourced(Expression.typeRunParser), "]")
          // The implementation's **parameters** (effects D20c option 5, `docs/effects.md` §11): its runtime
          // environment, `implement[S] State[S](cell: Cell[S]) { … }`. Each clause receives them as leading value
          // parameters, and [[ImplementationParameters]] records them for the readers that must tell them apart from
          // the ability member's own.
          parameters          <- optionalArgumentListOf(component[ArgumentDefinition])
          // Optional `where <guard>` clause (ability-guards Stage 1). `where` is a hard keyword (so a `def`'s
          // greedily-parsed return-type run stops cleanly at it, just as it stops at `infix`/`def` — the same reason
          // `where`-on-defs needs it hard). The guard is parsed with `typeRunParser` — the same parser as a return
          // type — so an infix guard like `E1 != E2` reads without parentheses and the run stops cleanly at the body's
          // `{` (`{` is not a type-atom start), giving the parse boundary of §2.1 for free. It rides the marker's
          // return-type slot below.
          guard               <- (keyword("where") *> sourced(Expression.typeRunParser)).optional()
          // Tagged by branch: a `def` is a **clause** and carries the block's clause row, a `type` is an associated
          // type and must not ([[ImplementationRows]]).
          (errors, members)   <-
            (component[FunctionDefinition].map(f => (f, true)) or
              TypeAliasDefinition.typeAliasDefinition.parser.map(f => (f, false)))
              .recoveringAtLeastOnce(t => isKeyword(t) && (hasContent("def")(t) || hasContent("type")(t)))
              .between(symbol("{"), symbol("}"))
              .optional()
              .map(_.getOrElse(Seq.empty, Seq.empty))
          // What the implementation itself performs: one phantom binder per entry on the marker and on every clause,
          // so `with` can write the implementations those clauses run on (effects v6 §9.4 step 3).
          clauseRow            = ImplementationRows.union(members.collect { case (f, true) => f })
          functions            = members.map { case (f, isClause) =>
                                   if (isClause) (ImplementationRows.carrying(clauseRow, f), isClause) else (f, isClause)
                                 }
          // The implementation's identity: a canonical, position-independent string of *what* it implements — the
          // surface form of its type-argument pattern plus its `where` guard. Two `implement` blocks are the same
          // instance iff this key matches (independent of file/position/order), which is what lets an instance be split
          // across layers and merged, while keeping distinct patterns (`Eq[Type]` vs `Eq[String]`) and a guarded
          // instance (whose `where` clause, e.g. the `Coerce[Int, Int]` widening guard, is part of its identity) apart
          // from an otherwise same-pattern sibling. `Show[Expression]` drops
          // source positions, so the key is stable across layers that spell the pattern identically (the same
          // character-exact discipline the layer merge already requires).
          patternKey           = pattern.map(_.value.render).mkString(", ") +
                                   guard.map(g => s" where ${g.value.render}").getOrElse("")
        } yield (
          errors,
          functions.map((f, isClause) =>
            // Transform the function into an "implement" function via `f.copy` — only the three deliberate overrides are
            // spelled out (name → implement qualifier including the instantiation type parameters, prepend the common
            // generic parameters, always-public visibility); everything else forwards by default. A *positional* rebuild
            // was the bug class here: it silently dropped every field it did not restate — the refinement-channel
            // companions (`returnMeta`/`whereClause`, a transfer brace or `where` precondition vanishing rather than
            // being demanded at the call site — bounds-as-refinements §4.2/§4.3), plus `doc`, `fixity`, `precedence` —
            // and every future `FunctionDefinition` field would re-open it. `f.copy` closes it.
            f.copy(
              name =
                f.name.map(n => QualifiedName(n.name, Qualifier.AbilityImplementation(name.value.content, patternKey, None))),
              genericParameters = genericParameters ++ f.genericParameters,
              args = (if (isClause) parameters else Seq.empty) ++ f.args,
              visibility = Visibility.Public
            )
          ) ++
            ImplementationParameters
              .declaration(
                name,
                Qualifier.AbilityImplementation(name.value.content, patternKey, None),
                genericParameters,
                parameters,
                clauseRow
              )
              .toSeq :+
            // We add the implementation to the default method as a marker, that this type implements the marker.
            // The marker takes one argument per pattern element so its signature fully encodes the pattern,
            // which is what drives implementation selection during dispatch.
            //
            // The marker is a pure signature vessel: its return-type slot carries the guard (the `where <expr>`, or
            // the literal `true` when absent) and it is body-less. This co-opts the return slot rather than threading
            // a new `guard` field through the whole front-end; matching is unaffected because `AbilityMatcher` drops
            // the return before unifying the pattern arguments. The former identity body (`arg0`) is retired
            // uniformly so it never fights the guard at concrete discharge (ability-guards §2.3).
            FunctionDefinition(
              name.as(
                QualifiedName(name.value.content, Qualifier.AbilityImplementation(name.value.content, patternKey, None))
              ),
              genericParameters,
              pattern.zipWithIndex.map { case (p, i) => ArgumentDefinition(name.as(s"arg$i"), p) },
              // The guard rides the marker's return slot, and the clause row rides it too: a row is erased from
              // every type by `EffectSugarDesugarer`, so the guard reaches the discharge untouched while the marker
              // gains the binder declaration a `with` and the pattern match read back.
              ImplementationRows.rowed(clauseRow, guard.getOrElse(Expression.trueReference(name))),
              None
            )
        )
    }
}
