package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIfAll, optional}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

case class DataConstructor(
    name: Sourced[String],
    fields: Seq[ArgumentDefinition],
    // The **slot statement** after the fields (`docs/meta.md` §17.2, O4b), e.g. the `{signOf(range(degrees))}` in
    // `data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}`: one expression per slot its type
    // declares, over the metas of the constructor's fields. Empty when none is written. Consumed at the core boundary
    // by `StructuralMetaDesugarer`, which puts it into the constructor's transfer.
    statement: Seq[Sourced[Expression]] = Seq.empty
)

object DataConstructor {

  /** A constructor's slot statement, `{expression, …}` after its fields, or empty. */
  val statementParser: Parser[Sourced[Token], Seq[Sourced[Expression]]] =
    optionalBracketedCommaSeparatedItems("{", sourced(component[Expression]), "}")

  given ASTComponent[DataConstructor] = new ASTComponent[DataConstructor] {
    override val parser: Parser[Sourced[Token], DataConstructor] = for {
      name   <- acceptIfAll(isIdentifier, isUpperCase)("constructor name")
      fields    <- bracketedCommaSeparatedItems("(", ArgumentDefinition.field, ")").optional()
      statement <- DataConstructor.statementParser
    } yield DataConstructor(name.map(_.content), fields.getOrElse(Seq.empty), statement)
  }
}
