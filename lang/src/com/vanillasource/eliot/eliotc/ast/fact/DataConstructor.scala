package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIfAll, optional}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

case class DataConstructor(
    name: Sourced[String],
    fields: Seq[ArgumentDefinition]
)

object DataConstructor {
  given ASTComponent[DataConstructor] = new ASTComponent[DataConstructor] {
    override val parser: Parser[Sourced[Token], DataConstructor] = for {
      name   <- acceptIfAll(isIdentifier, isUpperCase)("constructor name")
      fields <- bracketedCommaSeparatedItems("(", ArgumentDefinition.field, ")").optional()
    } yield DataConstructor(name.map(_.content), fields.getOrElse(Seq.empty))
  }
}
