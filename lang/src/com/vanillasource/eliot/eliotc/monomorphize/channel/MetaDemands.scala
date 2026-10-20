package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.annotation.tailrec

/** The **meta demands** of one monomorphic instance's body in one **meta context** (`docs/total-meta-transfers.md` P5):
  * every `where` precondition and parameter brace its calls demand, checked with what the context says of the body's
  * parameters ([[MetaDemandsProcessor]]).
  *
  * A demand is checked in every context its call is actually reached in from `main`, because every meta is a
  * compile-time value known there: a body is no longer checked once with its parameters at ⊤. The **root context** of
  * a value ([[MetaDemands.rootOf]]) leaves each meta-carrying parameter [[MetaDemands.Knowledge.Symbolic]]; it is what
  * the `WovenValue` seam demands for every value, and what reports. A demand that reads a symbolic parameter is
  * **deferred** to the contexts its callers supply, which return what they find as [[violations]] for the caller to
  * report at its own call.
  *
  * @param deferred
  *   Whether some demand in this body, or in a body it calls, depends on a [[MetaDemands.Knowledge.Symbolic]] entry of
  *   the context, and so is decided only in the contexts this body's callers supply.
  * @param violations
  *   What this context violates, for the caller to report at its call. Always empty for a root context, which reports
  *   its own.
  */
case class MetaDemands(
    vfqn: ValueFQN,
    typeArguments: Seq[GroundValue],
    context: Seq[MetaDemands.Knowledge],
    deferred: Boolean,
    violations: Seq[MetaDemands.Violation]
) extends CompilerFact {
  override def key(): CompilerFactKey[MetaDemands] = MetaDemands.Key(vfqn, typeArguments, context)
}

object MetaDemands {

  /** What a context says of one parameter's meta, by position among the body's leading parameters, handles included.
    */
  enum Knowledge {

    /** The caller's argument has this meta. */
    case Known(meta: GroundValue)

    /** Nothing is known of it: the caller could not pin it, or its type carries no meta (always so for a handle). */
    case Unknown

    /** It is the walked body's own parameter, decided by whoever calls it — the root context's meta-carrying
      * parameters.
      */
    case Symbolic
  }

  /** A demand this context does not satisfy, as it would be reported (`at`, `description`), and which of the context's
    * parameter positions it depends on — what lets a caller tell a violation its own arguments caused from one its root
    * context already reports.
    */
  case class Violation(at: Sourced[String], description: Seq[String], dependsOn: Seq[Int])

  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue], context: Seq[Knowledge])
      extends CompilerFactKey[MetaDemands] {
    override def valueCodec: Option[FactCodec[MetaDemands]] = Some(LangFactCodecs.metaDemandsCodec)
  }

  /** The root context of `mv`: one entry per leading parameter of its body, [[Knowledge.Symbolic]] where its type
    * carries meta and [[Knowledge.Unknown]] where it does not. A body-less value has none.
    */
  def rootOf(mv: MonomorphicValue): CompilerIO[Key] =
    mv.runtime
      .fold(Seq.empty)(body => parameterTypes(body.value))
      .traverse(tpe => MetaInterpreter.carriesMeta(tpe).map(if (_) Knowledge.Symbolic else Knowledge.Unknown))
      .map(Key(mv.vfqn, mv.typeArguments, _))

  /** The parameter types of a body's leading lambdas — its parameters, as [[MonomorphicValue.naturalArity]] counts
    * them.
    */
  @tailrec
  private def parameterTypes(
      expression: MonomorphicExpression.Expression,
      types: Seq[GroundValue] = Seq.empty
  ): Seq[GroundValue] = expression match {
    case MonomorphicExpression.FunctionLiteral(_, parameterType, body) =>
      parameterTypes(body.value.expression, types :+ parameterType)
    case _                                                             => types
  }
}
