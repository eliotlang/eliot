package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIf, acceptIfAll, optional, or}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

object TypeAliasDefinition {
  val typeAliasDefinition: ASTComponent[FunctionDefinition] = new ASTComponent[FunctionDefinition] {
    // A type alias accepts the same modifier prefix as a `def` (via `FunctionDefinition.modifierPrefix("type")`), so it
    // can carry a fixity: `infix right type =>[A, B] = Function[A, B]` desugars to the same FunctionDefinition an
    // `infix right def =>(...)` would. The name may be an upper-case identifier (an ordinary type alias) or an operator
    // symbol (a type-level operator such as `=>`).
    private val typeName: Parser[Sourced[Token], Sourced[Token]] =
      acceptIfAll(isIdentifier, isUpperCase)("type name") or acceptIf(isUserOperator, "type name")

    override val parser: Parser[Sourced[Token], FunctionDefinition] = for {
      (vis, fixity, prec) <- FunctionDefinition.modifierPrefix("type")
      name                <- typeName
      genericParameters   <- component[Seq[GenericParameter]]
      // The meta-slot brace (bounds-as-refinements §4.2): `type Int {range: Interval[BigInteger, BigInteger]}` declares
      // the named meta slots the type carries, each `name: Domain` (an `ArgumentDefinition`). Parsed here — after the
      // generic params `[…]`, before the optional `= body` — because `component[Seq[GenericParameter]]` sees `{` (not
      // `[`) and yields empty, leaving the brace unconsumed exactly at this point. Absent for an ordinary alias.
      metaSlots           <- optionalBracketedCommaSeparatedItems("{", component[ArgumentDefinition], "}")
      // A type-alias body is a type position, so parse it with `typeRunParser` (an operator run of type atoms), NOT the
      // greedy full expression parser. The full parser would consume the following top-level definition's leading
      // `left`/`right`/… fixity identifiers as an application chain, silently dropping its fixity; `typeRunParser` stops
      // cleanly at the next definition because every definition-introducing token (`infix`/`prefix`/`postfix`/`def`/
      // `type`/…) is a hard keyword and so is not a type-atom start. This lets an alias body carry a bare type operator,
      // e.g. `type Pred = A => Bool`. It is also the one place an effect row is still written: a row alias,
      // `type Test = {Writer[List[TestResult]]} Unit` (see `Expression.rowAliasBodyParser`).
      body                <- (symbol("=") *> sourced(Expression.rowAliasBodyParser)).optional()
    } yield {
      val args      = genericParameters.map(gp => ArgumentDefinition(gp.name, gp.typeRestriction))
      val typeExpr  = name.as(Expression.FunctionApplication(None, name.map(_ => "Type"), None, Seq.empty))
      // Operators are never upper-case and are always referenced bare (no `[]`), so their references resolve in the
      // Default namespace (see `CoreExpressionConverter`). An operator-named alias must therefore live in the Default
      // namespace too — exactly like the equivalent `def` — or its uses would never find it; an ordinary (upper-case)
      // alias stays in the Type namespace as before.
      val qualifier = if (isUserOperator(name)) Qualifier.Default else Qualifier.Type
      FunctionDefinition(
        name.map(n => QualifiedName(n.content, qualifier)),
        Seq.empty,
        args,
        typeExpr,
        body,
        fixity = fixity,
        precedence = prec,
        visibility = vis,
        metaSlots = metaSlots
      )
    }
  }
}
