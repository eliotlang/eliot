package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIfAll, between, optional, or, recoveringAtLeastOnce}
import com.vanillasource.eliot.eliotc.ast.parser.{Parser, ParserError}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

/** An `effect` declaration (`docs/effects.md` §2):
  *
  * {{{
  * effect Console {
  *    def printLine(s: String): Unit
  *    def readLine: Option[String]
  * }
  * }}}
  *
  * Its members are the effect's operations; a member's own clause lists what it performs *beyond* this effect. Carried
  * on the [[AST]] beside the ordinary definitions. Unlike [[AbilityBlock]] the members are kept as written —
  * `EffectDefinitionDesugarer` lowers them at core as an ability's, each marked as performing the effect, and so
  * decides their qualifier and marker.
  *
  * @param doc
  *   The `/** ... */` documentation comment preceding the declaration, attached by position in `ASTParser`.
  */
case class EffectDefinition(
    name: Sourced[String],
    genericParameters: Seq[GenericParameter],
    functions: Seq[FunctionDefinition],
    doc: Option[Sourced[String]] = None
)

object EffectDefinition {
  extension (self: EffectDefinition) def render: String = s"effect ${self.name.show}"

  val effectDefinition: ASTComponent[(Seq[ParserError], EffectDefinition)] =
    new ASTComponent[(Seq[ParserError], EffectDefinition)] {
      override val parser: Parser[Sourced[Token], (Seq[ParserError], EffectDefinition)] =
        for {
          _                   <- keyword("effect")
          name                <- acceptIfAll(isIdentifier, isUpperCase)("effect name")
          genericParameters   <- optionalBracketedCommaSeparatedItems("[", component[GenericParameter], "]")
          (errors, functions) <-
            (component[FunctionDefinition] or TypeAliasDefinition.typeAliasDefinition.parser)
              .recoveringAtLeastOnce(t => isKeyword(t) && (hasContent("def")(t) || hasContent("type")(t)))
              .between(symbol("{"), symbol("}"))
              .optional()
              .map(_.getOrElse(Seq.empty, Seq.empty))
        } yield (errors, EffectDefinition(name.map(_.content), genericParameters, functions))
    }
}
