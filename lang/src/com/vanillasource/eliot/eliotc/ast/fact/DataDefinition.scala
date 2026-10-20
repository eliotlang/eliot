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
      constructorsOrFields <-
        ((symbol("=") >> symbol("|").optional() >> component[DataConstructor].atLeastOnceSeparatedBy(symbol("|"))) xor
          bracketedCommaSeparatedItems("(", ArgumentDefinition.field, ")")).optional()
    } yield {
      val constructors = constructorsOrFields.map {
        case Left(ctors)   => ctors
        case Right(fields) => Seq(DataConstructor(name.map(_.content), fields))
      }
      DataDefinition(name.map(_.content), genericParameters, constructors, vis)
    }
  }
}
