package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import ASTComponent.component
import Primitives.*
import com.vanillasource.eliot.eliotc.ast.*
import com.vanillasource.eliot.eliotc.ast.parser.{Parser, ParserError}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token
import Parser.*

/** The parsed shape of one source file.
  *
  * `effectDefinitions` and `namedImplementations` are effects v6's surface, landed dark (`docs/effects.md` §10.1 step
  * 4): they are parsed and carried here beside the ordinary definitions, and the core processor rejects every one of
  * them as not supported yet. Nothing else reads them until the flag day.
  */
case class AST(
    importStatements: Seq[ImportStatement],
    functionDefinitions: Seq[FunctionDefinition],
    typeDefinitions: Seq[DataDefinition],
    effectDefinitions: Seq[EffectDefinition] = Seq.empty,
    namedImplementations: Seq[NamedImplementation] = Seq.empty
)

object AST {

  /** One top-level item, as the file-level alternation yields it. */
  private enum Item {
    case Import(statement: ImportStatement)
    case Function(definition: FunctionDefinition)
    case Data(definition: DataDefinition)
    case Block(result: (Seq[ParserError], Seq[FunctionDefinition]))
    case Effect(result: (Seq[ParserError], EffectDefinition))
    case Named(result: (Seq[ParserError], NamedImplementation))
  }

  extension (self: AST)
    def render: String =
      s"import statements: ${self.importStatements
          .map(_.render)
          .mkString(", ")}, function definitions: ${self.functionDefinitions
          .map(_.render)
          .mkString(", ")}, type definitions: ${self.typeDefinitions.map(_.render).mkString(", ")}, effects: ${self.effectDefinitions
          .map(_.render)
          .mkString(", ")}, named implementations: ${self.namedImplementations.map(_.render).mkString(", ")}"

  given ASTComponent[(Seq[ParserError], AST)] = new ASTComponent[(Seq[ParserError], AST)] {
    override def parser: Parser[Sourced[Token], (Seq[ParserError], AST)] =
      for {
        (errors, items) <-
          (component[ImportStatement].map(Item.Import.apply) or
            component[FunctionDefinition].map(Item.Function.apply) or
            component[DataDefinition].map(Item.Data.apply) or
            TypeAliasDefinition.typeAliasDefinition.parser.map(Item.Function.apply) or
            AbilityBlock.abilityBlock.parser.map(Item.Block.apply) or
            EffectDefinition.effectDefinition.parser.map(Item.Effect.apply) or
            // The named form must be tried first: its head is atomic through the `name:` that tells it apart.
            NamedImplementation.namedImplementation.parser.map(Item.Named.apply) or
            ImplementBlock.implementBlock.parser.map(Item.Block.apply))
            .recoveringAnyTimes(isItemBoundary)
      } yield {
        // Each implement block already carries its identity in the `AbilityImplementation` qualifier — a canonical
        // `(pattern + guard)` key assigned by `ImplementBlock` — so no source-order numbering is needed here; the
        // block's functions pass through unchanged.
        val blockErrors = items.collect { case Item.Block((errors, _)) => errors }.flatten ++
          items.collect { case Item.Effect((errors, _)) => errors }.flatten ++
          items.collect { case Item.Named((errors, _)) => errors }.flatten
        (
          errors ++ blockErrors,
          AST(
            items.collect { case Item.Import(i) => i },
            items.collect { case Item.Function(f) => f } ++ items.collect { case Item.Block((_, fs)) => fs }.flatten,
            items.collect { case Item.Data(d) => d },
            items.collect { case Item.Effect((_, e)) => e },
            items.collect { case Item.Named((_, n)) => n }
          )
        )
      }
  }
}
