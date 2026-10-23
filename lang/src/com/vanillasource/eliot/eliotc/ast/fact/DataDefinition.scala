package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import cats.Eq
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token
import Parser.{acceptIfAll, optional, atLeastOnceSeparatedBy, xor}

case class DataDefinition(
    name: Sourced[String],
    genericParameters: Seq[GenericParameter],
    constructors: Option[Seq[DataConstructor]],
    visibility: Visibility = Visibility.Public,
    // The named meta *slots* the type declares with a `{slots}` brace after its generic parameters (`docs/meta.md`
    // §17.2, O4b), e.g. `data Temperature {sign: Sign}(degrees: Int) {…}`, each stated by every constructor. They are
    // fields of the type's structural meta beside its constructors' reach (`StructuralMetaDesugarer`). Empty for an
    // ordinary `data`.
    metaSlots: Seq[ArgumentDefinition] = Seq.empty,
    // The `/** ... */` documentation comment preceding this definition, if any. Attached by source-position adjacency
    // in `ASTParser` and consumed only by the apidoc tooling; never read by the compiler proper, never part of
    // `signatureEquality`, and dropped at the core boundary.
    doc: Option[Sourced[String]] = None
)

object DataDefinition {
  val signatureEquality: Eq[DataDefinition] = (x: DataDefinition, y: DataDefinition) =>
    x.genericParameters.length === y.genericParameters.length &&
      (x.genericParameters zip y.genericParameters).forall(GenericParameter.signatureEquality.eqv)

  extension (self: DataDefinition) def render: String = s"${self.name.show}"

  given ASTComponent[DataDefinition] = new ASTComponent[DataDefinition] {
    override val parser: Parser[Sourced[Token], DataDefinition] = for {
      vis                  <- component[Visibility].optional().map(_.getOrElse(Visibility.Public))
      _                    <- keyword("data")
      name                 <- acceptIfAll(isIdentifier, isUpperCase)("type name")
      genericParameters    <- component[Seq[GenericParameter]]
      // As on `type`, the slot brace follows the generic parameters, which leave a `{` unconsumed.
      metaSlots            <- optionalBracketedCommaSeparatedItems("{", component[ArgumentDefinition], "}")
      constructorsOrFields <-
        ((symbol("=") >> symbol("|").optional() >> component[DataConstructor].atLeastOnceSeparatedBy(symbol("|"))) xor
          (for {
            fields    <- bracketedCommaSeparatedItems("(", ArgumentDefinition.field, ")")
            statement <- DataConstructor.statementParser
          } yield (fields, statement))).optional()
    } yield {
      val constructors = constructorsOrFields.map {
        case Left(ctors)               => ctors
        case Right((fields, statement)) => Seq(DataConstructor(name.map(_.content), fields, statement))
      }
      DataDefinition(name.map(_.content), genericParameters, constructors, vis, metaSlots = metaSlots)
    }
  }
}
