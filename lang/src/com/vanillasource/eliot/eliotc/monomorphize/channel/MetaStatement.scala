package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.processor.MetaCompanionLayout
import com.vanillasource.eliot.eliotc.module.fact.{ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*

import scala.annotation.tailrec

/** What a meta companion states, read from its resolved body: which of its stating value's parameters the statement
  * reads, and whether it is a **frame or cell statement** (`docs/meta.md` §11.1, §14).
  *
  * A frame or cell statement is a brace that is one application of a primitive of `eliot.compiler.Meta` to the stating
  * value's parameters — `{exit(frame, err)}`, `{content(cell)}`, `{exited(body)}`. Its arguments are frames, cells and
  * runs of code, which have no meta, so the channel never reduces it: it recognises the primitive by name, as it
  * recognises `never`, and follows the frame or cell itself. The channel names no native.
  *
  * @param parameters
  *   The stating value's parameters, in order, as the companion names them (a placeholder for one it cannot read).
  * @param reads
  *   The positions of the parameters the statement reads.
  * @param primitive
  *   The frame or cell primitive the statement is, if it is one.
  * @param joins
  *   The parameters the statement is the join of, when it is one parameter (`{combine}`, `{body}`) or a join of
  *   parameters (`{joined(onExit, onValue)}`, `fold`'s `{join(whenTrue, whenFalse)}`). Since ⊥ is the identity of
  *   every join (`docs/meta.md` §12), such a statement whose parameters but one are ⊥ is that one, whatever the
  *   interpretation holds of it — a constructed value, a frame — and not only its meta.
  */
case class MetaStatement(
    parameters: Seq[String],
    reads: Set[Int],
    primitive: Option[MetaStatement.Primitive],
    joins: Option[Seq[Int]]
)

object MetaStatement {

  /** A frame or cell primitive, over the positions of the parameters it is applied to. */
  enum Primitive {

    /** `frame`: a fresh frame of the stating call, handed to its code. */
    case Frame

    /** `cell(initial)`: a fresh cell of the stating call holding `initial`, handed to its code. */
    case Cell(initial: Int)

    /** `exit(frame, carried)`: `frame` receives `carried`, and the call does not return. */
    case Exit(frame: Int, carried: Int)

    /** `exited(code)`: what the exits of `code` carried through the frame it was handed; ⊥ when none did. */
    case Exited(code: Int)

    /** `stored(code)`: the content of the cell `code` was handed, when it returned. */
    case Stored(code: Int)

    /** `content(cell)`: the content of `cell`. */
    case Content(cell: Int)

    /** `store(cell, value)`: `cell` holds `value` from now on. */
    case Store(cell: Int, value: Int)

    /** `built`: the call is the value its callee, a `data` constructor, built from its arguments. */
    case Built

    /** `fieldOf(value, constructor)`: the field of `value` a `match` hands this argument of `constructor`'s handler, ⊥
      * when another constructor built `value`.
      */
    case FieldOf(value: Int, constructor: ValueFQN)

    /** `fieldOr(value, constructor, otherwise)`: `fieldOf`, and where what built `value` is not known, the statement
      * reduced, which is `otherwise`: a braced field's stated meta.
      */
    case FieldOr(value: Int, constructor: ValueFQN)
  }

  /** What `companion` states. */
  def of(companion: ValueFQN): CompilerIO[MetaStatement] =
    getFactOrAbort(OperatorResolvedValue.Key(companion, Platform.Compiler)).map { value =>
      val (names, body) = value.runtime.fold((Seq.empty[String], none[OperatorResolvedExpression]))(runtime =>
        lambdas(runtime.value, Seq.empty)
      )
      val parameters    = names.filterNot(_ == MetaCompanionLayout.constructorParameterName)
      val brace         = body.map(unwrapped)
      MetaStatement(
        parameters,
        brace.fold(Set.empty[Int])(free(_).flatMap(name => Option(parameters.indexOf(name)).filter(_ >= 0))),
        brace.flatMap(primitive(_, parameters)),
        brace.flatMap(joined(_, parameters))
      )
    }

  @tailrec
  private def lambdas(
      expression: OperatorResolvedExpression,
      names: Seq[String]
  ): (Seq[String], Option[OperatorResolvedExpression]) =
    expression match {
      case OperatorResolvedExpression.FunctionLiteral(name, _, body) => lambdas(body.value, names :+ name.value)
      case other                                                     => (names, Some(other))
    }

  /** The brace a companion states: its body, or, for a slot-producing one, the one slot its meta constructor is applied
    * to.
    */
  private def unwrapped(body: OperatorResolvedExpression): OperatorResolvedExpression = body match {
    case OperatorResolvedExpression.FunctionApplication(target, argument) =>
      target.value match {
        case OperatorResolvedExpression.ParameterReference(name)
            if name.value == MetaCompanionLayout.constructorParameterName =>
          argument.value
        case _ => body
      }
    case other                                                            => other
  }

  /** The parameters an expression reads that it does not bind itself. */
  private def free(expression: OperatorResolvedExpression): Set[String] = expression match {
    case OperatorResolvedExpression.ParameterReference(name)              => Set(name.value)
    case OperatorResolvedExpression.FunctionApplication(target, argument) => free(target.value) ++ free(argument.value)
    case OperatorResolvedExpression.FunctionLiteral(name, _, body)        => free(body.value) - name.value
    case _                                                                => Set.empty
  }

  /** The primitive `brace` is: one application of a primitive's name to parameters, as many as the primitive takes, or
    * for `fieldOf`/`fieldOr` to the scrutinee and the constructor whose handler is handed.
    */
  private def primitive(brace: OperatorResolvedExpression, parameters: Seq[String]): Option[Primitive] = {
    val (head, arguments) = applied(brace, Seq.empty)
    def position(argument: OperatorResolvedExpression): Option[Int] = argument match {
      case OperatorResolvedExpression.ParameterReference(name) => Option(parameters.indexOf(name.value)).filter(_ >= 0)
      case _                                                   => None
    }
    def constructor(argument: OperatorResolvedExpression): Option[ValueFQN] = argument match {
      case OperatorResolvedExpression.ValueReference(name, _) => Some(name.value)
      case _                                                  => None
    }
    head match {
      case OperatorResolvedExpression.ValueReference(name, _) =>
        (primitiveNames.get(name.value), arguments) match {
          case (Some("frame"), Seq())                  => Some(Primitive.Frame)
          case (Some("cell"), Seq(initial))            => position(initial).map(Primitive.Cell(_))
          case (Some("exit"), Seq(frame, carried))     => (position(frame), position(carried)).mapN(Primitive.Exit(_, _))
          case (Some("exited"), Seq(code))             => position(code).map(Primitive.Exited(_))
          case (Some("stored"), Seq(code))             => position(code).map(Primitive.Stored(_))
          case (Some("content"), Seq(cell))            => position(cell).map(Primitive.Content(_))
          case (Some("store"), Seq(cell, value))       => (position(cell), position(value)).mapN(Primitive.Store(_, _))
          case (Some("built"), Seq())                  => Some(Primitive.Built)
          case (Some("fieldOf"), Seq(value, built))    => (position(value), constructor(built)).mapN(Primitive.FieldOf(_, _))
          case (Some("fieldOr"), Seq(value, built, _)) => (position(value), constructor(built)).mapN(Primitive.FieldOr(_, _))
          case _                                       => None
        }
      case _                                                  => None
    }
  }

  /** The parameters `brace` is the join of: a parameter itself, or `joined`/`join` of two such. */
  private def joined(brace: OperatorResolvedExpression, parameters: Seq[String]): Option[Seq[Int]] =
    applied(brace, Seq.empty) match {
      case (OperatorResolvedExpression.ParameterReference(name), Seq()) =>
        Option(parameters.indexOf(name.value)).filter(_ >= 0).map(Seq(_))
      case (OperatorResolvedExpression.ValueReference(name, _), Seq(a, b)) if joinNames.contains(name.value) =>
        (joined(a, parameters), joined(b, parameters)).mapN(_ ++ _)
      case _ => None
    }

  @tailrec
  private def applied(
      expression: OperatorResolvedExpression,
      arguments: Seq[OperatorResolvedExpression]
  ): (OperatorResolvedExpression, Seq[OperatorResolvedExpression]) =
    expression match {
      case OperatorResolvedExpression.FunctionApplication(target, argument) =>
        applied(target.value, argument.value +: arguments)
      case other                                                            => (other, arguments)
    }

  /** `eliot.compiler.Meta::joined` and the `Meta` ability's own `join`. */
  private val joinNames: Set[ValueFQN] =
    Set(WellKnownTypes.metaJoinedFQN, WellKnownTypes.metaJoinFQN)

  private val primitiveNames: Map[ValueFQN, String] =
    Seq("frame", "cell", "exit", "exited", "stored", "content", "store", "built", "fieldOf", "fieldOr")
      .map(name => WellKnownTypes.metaPrimitiveFQN(name) -> name)
      .toMap
}
