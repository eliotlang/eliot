package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIfAll, atomic, between, optional, or, recoveringAtLeastOnce}
import com.vanillasource.eliot.eliotc.ast.parser.{Parser, ParserError}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

/** A **named** `implement` — effects v6's addressable implementation (`docs/effects.md` §9.3):
  *
  * {{{
  * implement recordingConsole: Console {
  *    def printLine(s: String): {Writer[String]} Unit = tell(s ++ ";")
  *    def readLine: Option[String] = None
  * }
  * }}}
  *
  * It is never a default: it may live anywhere, is never searched, and is bound to a subject only by `with`
  * ([[Expression.WithBinding]]). The anonymous form stays [[ImplementBlock]]. Landed dark (§10.1 step 4): parsed into
  * this node, carried on the [[AST]], and rejected at core as not supported yet.
  *
  * @param name
  *   The implementation's own (lower-case) name, the one `with` refers to.
  * @param ability
  *   The ability or effect implemented, with its pattern's type arguments in `pattern`.
  * @param parameters
  *   The implementation's **parameters** (`docs/effects.md` §11, "named implementations with parameters"), its runtime
  *   environment, supplied where it is bound: `implement scripted(lines: List[String]): Terminal { … }`, bound by
  *   `greet with scripted(List("Bob"))`. Each clause receives them as leading value parameters, exactly as an
  *   anonymous implementation's ([[ImplementationParameters]]).
  * @param doc
  *   The `/** ... */` documentation comment preceding the declaration, attached by position in `ASTParser`.
  */
case class NamedImplementation(
    name: Sourced[String],
    genericParameters: Seq[GenericParameter],
    ability: Sourced[String],
    pattern: Seq[Sourced[Expression]],
    functions: Seq[FunctionDefinition],
    doc: Option[Sourced[String]] = None,
    parameters: Seq[ArgumentDefinition] = Seq.empty
)

object NamedImplementation {
  extension (self: NamedImplementation) def render: String = s"implement ${self.name.show}: ${self.ability.show}"

  val namedImplementation: ASTComponent[(Seq[ParserError], NamedImplementation)] =
    new ASTComponent[(Seq[ParserError], NamedImplementation)] {
      // The head is atomic up to and including the `:`, which is what tells a named implementation
      // (`implement name: Ability`) from an anonymous one (`implement Ability`) without consuming the latter's input.
      // The parameters sit between the name and the `:`, as a definition's do: `implement scripted(lines: List[String]):
      // Terminal`.
      private val head: Parser[Sourced[Token], (Seq[GenericParameter], Sourced[Token], Seq[ArgumentDefinition])] = (for {
        _                 <- keyword("implement")
        genericParameters <- component[Seq[GenericParameter]]
        name              <- acceptIfAll(isIdentifier, isLowerCase)("implementation name")
        parameters        <- optionalArgumentListOf(component[ArgumentDefinition])
        _                 <- symbol(":")
      } yield (genericParameters, name, parameters)).atomic()

      override val parser: Parser[Sourced[Token], (Seq[ParserError], NamedImplementation)] =
        for {
          (genericParameters, name, parameters) <- head
          ability                   <- acceptIfAll(isIdentifier, isUpperCase)("ability name")
          pattern                   <- optionalBracketedCommaSeparatedItems("[", sourced(Expression.typeRunParser), "]")
          // Tagged by branch: a `def` is a **clause** and carries the block's clause row, a `type` is an associated
          // type and must not ([[ImplementationRows]]).
          (errors, members)         <-
            (component[FunctionDefinition].map(f => (f, true)) or
              TypeAliasDefinition.typeAliasDefinition.parser.map(f => (f, false)))
              .recoveringAtLeastOnce(t => isKeyword(t) && (hasContent("def")(t) || hasContent("type")(t)))
              .between(symbol("{"), symbol("}"))
              .optional()
              .map(_.getOrElse(Seq.empty, Seq.empty))
          // What the implementation itself performs, on every clause: `with recordingConsole` writes not only the
          // marker but the implementations its clauses' `{Writer[String]}` runs on (effects v6 §9.4 step 3).
          clauseRow                  = ImplementationRows.union(members.collect { case (f, true) => f })
          functions                  = members.map { case (f, isClause) =>
                                         if (isClause) ImplementationRows.carrying(clauseRow, f) else f
                                       }
        } yield (
          errors,
          NamedImplementation(
            name.map(_.content),
            genericParameters,
            ability.map(_.content),
            pattern,
            functions,
            parameters = parameters
          )
        )
    }
}
