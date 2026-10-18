package com.vanillasource.eliot.eliotc.ast.fact

import cats.Eq
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent
import com.vanillasource.eliot.eliotc.ast.fact.ASTComponent.component
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.{acceptIf, acceptIfAll, anyTimes, optional, or}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

/** A generic-parameter binder, e.g. `MIN: BigInteger` in `type Int[MIN: BigInteger, MAX: BigInteger]`.
  *
  * @param abilityLevel
  *   True for every binder an `ability` or `effect` block contributes to each of its members ([[AbilityMembers]]): an
  *   effect's handle type and the block's own generic parameters. The run of them is the member reference's
  *   **ability-level prefix**, which [[com.vanillasource.eliot.eliotc.monomorphize.check.AbilityResolver]] slices off.
  *   Marking the whole run is what tells the desugar where a member's *own* minted binders go — after it, so the prefix
  *   stays exactly the ability's.
  */
case class GenericParameter(
    name: Sourced[String],
    typeRestriction: Sourced[Expression],
    abilityConstraints: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]],
    abilityLevel: Boolean = false
)

object GenericParameter {

  val signatureEquality: Eq[GenericParameter] = (x: GenericParameter, y: GenericParameter) =>
    x.name.value === y.name.value && x.typeRestriction.value.render === y.typeRestriction.value.render

  given ASTComponent[Seq[GenericParameter]] = new ASTComponent[Seq[GenericParameter]] {
    override def parser: Parser[Sourced[Token], Seq[GenericParameter]] =
      optionalBracketedCommaSeparatedItems("[", component[GenericParameter], "]")
  }

  given ASTComponent[GenericParameter] = new ASTComponent[GenericParameter] {
    /** The operator joining two ability constraints. Any user operator parses here — which one is legal is a *name*
      * question, answered at resolution against the standard library's `&`, not a symbol the parser recognises. The
      * follow set inside a generic-parameter list is `,` and `]`, both reserved and so never user operators, so this
      * stays unambiguous.
      */
    private val constraintCombinator: Parser[Sourced[Token], Sourced[String]] =
      acceptIf(isUserOperator, "'&' between ability constraints").map(_.map(_.content))

    private val abilityConstraintsParser =
      for {
        _     <- symbol("~")
        first <- component[UnresolvedAbilityConstraint[Sourced[Expression]]]
        rest  <- (for {
                   combinedBy <- constraintCombinator
                   constraint <- component[UnresolvedAbilityConstraint[Sourced[Expression]]]
                 } yield constraint.copy(combinedBy = Some(combinedBy))).anyTimes()
      } yield first +: rest

    override def parser: Parser[Sourced[Token], GenericParameter] =
      for {
        name               <- acceptIfAll(isUpperCase, isIdentifier)("generic type parameter")
        typeRestriction    <- (arityAsTypeRestriction(name.map(_.content)) or explicitTypeRestriction)
                                .optional()
                                .map(_.getOrElse(name.as(typeExpr(name.map(_ => "Type")))))
        abilityConstraints <- abilityConstraintsParser.optional().map(_.getOrElse(Seq.empty))
      } yield GenericParameter(
        name.map(_.content),
        typeRestriction,
        abilityConstraints.map(ac => extendWithDefault(ac, name.map(_.content)))
      )

    /** When an ability constraint is defined [A ~ Show] (with no parameters), we add the generic parameter its declared
      * on as default.
      */
    private def extendWithDefault(
        abilityConstraint: UnresolvedAbilityConstraint[Sourced[Expression]],
        defaultGeneric: Sourced[String]
    ): UnresolvedAbilityConstraint[Sourced[Expression]] =
      if (abilityConstraint.typeArgs.isEmpty) {
        abilityConstraint.copy(typeArgs = Seq(defaultGeneric.as(typeExpr(defaultGeneric))))
      } else {
        abilityConstraint
      }
  }

  /** Parses bracketed arity notation [_, _[_], ...] and converts to an Expression. Only succeeds when brackets are
    * present (consumed). This is the syntactic sugar form: [M[_]] is sugar for [M: Function[Type, Type]].
    */
  private def arityAsTypeRestriction(name: Sourced[String]): Parser[Sourced[Token], Sourced[Expression]] =
    bracketedCommaSeparatedItems("[", symbol("_") >> subArityParser(name), "]")
      .map(subArities => arityParamsToExpression(name, subArities))

  /** Recursive parser for nested arity parameters: handles [_[_]] by parsing the part after each _. */
  private def subArityParser(name: Sourced[String]): Parser[Sourced[Token], Sourced[Expression]] =
    optionalBracketedCommaSeparatedItems("[", symbol("_") >> subArityParser(name), "]")
      .map(subArities => arityParamsToExpression(name, subArities))

  /** Converts parsed arity parameters into an Expression. Empty params -> Type. Non-empty -> nested Function[paramKind,
    * ...Function[paramKind, Type]].
    */
  private def arityParamsToExpression(name: Sourced[String], params: Seq[Sourced[Expression]]): Sourced[Expression] =
    if (params.isEmpty) name.as(typeExpr(name.as("Type")))
    else
      params.foldRight(name.as(typeExpr(name.as("Type")))) { (param, acc) =>
        name.as(typeExpr(name.as("Function"), Seq(param, acc)))
      }

  /** Parses explicit type restriction syntax: `: Expression`. The bound uses `typeRunParser`, so it admits a bare type
    * operator (the run stops at the `~` ability-constraint marker, the `,` separator, and the closing `]`).
    */
  private val explicitTypeRestriction: Parser[Sourced[Token], Sourced[Expression]] =
    symbol(":") >> sourced(Expression.typeRunParser)

  /** Helper to create a type expression from a name and optional generic arguments. */
  private def typeExpr(name: Sourced[String], genericArgs: Seq[Sourced[Expression]] = Seq.empty): Expression =
    Expression.FunctionApplication(None, name, Option.when(genericArgs.nonEmpty)(genericArgs), Seq.empty)
}
