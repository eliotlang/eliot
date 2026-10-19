package com.vanillasource.eliot.eliotc.ast.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Primitives.*
import com.vanillasource.eliot.eliotc.ast.parser.Parser
import com.vanillasource.eliot.eliotc.ast.parser.Parser.acceptIfAll
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.token.Token

/** One `~` ability constraint on a generic binder, **before** its ability name is resolved — the ast and core
  * spelling of [[com.vanillasource.eliot.eliotc.resolve.fact.AbilityConstraint]].
  *
  * It is parametric in the phase's expression type exactly as [[EffectRow]] is parametric in the constraint type, so
  * the ast→core hop is a `map` rather than a second case class: `E` is `Sourced[Expression]` at ast (positions still
  * attached to every argument) and `com.vanillasource.eliot.eliotc.core.fact.Expression` at core.
  *
  * @param combinedBy
  *   The infix operator that joined this constraint to the one before it — `None` for the first of a list, and for
  *   every constraint the `{E}` effect-row sugar mints (nothing wrote an operator for those). It is kept as the name
  *   the user typed, not as a recognised symbol:
  *   [[com.vanillasource.eliot.eliotc.resolve.processor.ValueResolver]] resolves it through the ordinary dictionary and
  *   requires the standard library's combinator
  *   ([[com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes.abilityCombinatorFQN]]). That it lives on *this*
  *   type and not on the resolved one is what makes "no phase past resolve knows a combinator existed"
  *   (`docs/effects-syntax-userspace.md` §4 stage 1) a property of the model rather than a convention.
  */
case class UnresolvedAbilityConstraint[E](
    abilityName: Sourced[String],
    typeArgs: Seq[E],
    combinedBy: Option[Sourced[String]] = None
) {

  /** Convert every type argument, keeping the ability name and combinator — the ast→core hop. */
  def map[F](f: E => F): UnresolvedAbilityConstraint[F] =
    UnresolvedAbilityConstraint(abilityName, typeArgs.map(f), combinedBy)
}

object UnresolvedAbilityConstraint {

  /** The identity of a row entry: its ability and the rendered arguments. Two entries with this key are the same
    * effect, so a row carries only one of them — the one place `{Console, Console} Unit` and the entries a named
    * implementation's clauses capture are made distinct.
    */
  def key(constraint: UnresolvedAbilityConstraint[Sourced[Expression]]): String =
    constraint.abilityName.value + "|" + constraint.typeArgs.map(_.value.render).mkString(",")

  given ASTComponent[UnresolvedAbilityConstraint[Sourced[Expression]]] =
    new ASTComponent[UnresolvedAbilityConstraint[Sourced[Expression]]] {
      override def parser: Parser[Sourced[Token], UnresolvedAbilityConstraint[Sourced[Expression]]] =
        for {
          name     <- acceptIfAll(isUpperCase, isIdentifier)("ability name")
          typeArgs <- optionalBracketedCommaSeparatedItems("[", sourced(Expression.typeRunParser), "]")
        } yield UnresolvedAbilityConstraint(name.map(_.content), typeArgs)
    }
}
