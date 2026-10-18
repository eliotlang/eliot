package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.annotation.tailrec

/** **Handle erasure** (`docs/effects.md` D36 point 11): a handle is an ordinary value of its implementation's `data`,
  * and most carry nothing at all — `standardConsole` has no parameters, so its handle has no fields. This rewrites one
  * monomorphic instance so a handle costs only what it holds:
  *
  *   - a handle with **no field** that survives is no parameter, no argument, no allocation and no capture;
  *   - a handle with **one** is passed as that field, so `escaping(frame)`'s handle is the frame;
  *   - a handle with more stays its `data`.
  *
  * A field survives unless its own type erases, or it is a **capture field** a `with` filled with `unit` because
  * nothing captures it (`row/Captures`). The decision is a function of the handle's ground type alone, so every site
  * that meets a handle of that type — its construction, its field reads, a parameter, a call — agrees on it.
  *
  * {{{
  * def main uses Console: Unit = printLine("Hello")
  *   main[StandardConsole$Handle](h) = printLine$impl(h, "Hello")   ⟶   main() = printLine$impl("Hello")
  * main with standardConsole
  *   (h -> main(h))(StandardConsole$Handle())                      ⟶   main()
  * }}}
  *
  * **Where a handle cannot simply vanish.** A definition's leading parameters — every handle parameter is one — are
  * dropped together with the arguments a direct call passes them, both sides reading the callee's body for how many
  * leading parameters it has. A *function value* keeps its shape: a block taking a handle (`Handed => A`) is still a
  * function, so an erased handle is `unit` there, its parameter typed `Unit`. A `with` is a `let`; one whose handle
  * erases is its subject alone, and one whose handle becomes a variable is that variable substituted into its subject.
  *
  * **Why dropping is safe.** A handle is second class (`docs/effects.md` §1 rules 2–3): it stands only where code may,
  * never in a result or a value, so an expression of a handle's type is a parameter, a handle's construction or a field
  * read off one, none of which does anything. Anything else is refused here rather than dropped.
  */
object HandleErasure {

  /** How a handle of one ground type is represented at run time. */
  private enum Representation {

    /** No field survives: the handle is nothing. */
    case Erased

    /** One field survives, the `index`-th, of `fieldType`: the handle is that field. */
    case Field(index: Int, fieldType: GroundValue)

    /** Several survive: the handle is its `data`. */
    case Data
  }

  /** What a referenced value is, as far as erasure cares. */
  private enum Callee {

    /** A handle's value constructor. */
    case Constructor

    /** The accessor of a handle's `index`-th field. */
    case Accessor(index: Int)

    /** Anything else. */
    case Ordinary
  }

  /** The instance `value` with its handles erased. */
  def erase(value: MonomorphicValue): CompilerIO[WovenValue] =
    value.runtime match {
      case Some(body) =>
        val arity = value.naturalArity.getOrElse(0)
        for {
          signature <- leadingDropped(value.signature, arity)
          erased    <- definitionBody(body.map(MonomorphicExpression(value.signature, _)), arity)
        } yield WovenValue(value.vfqn, value.typeArguments, value.name, signature, Some(erased.map(_.expression)))
      case None       =>
        represented(value.signature).map(WovenValue(value.vfqn, value.typeArguments, value.name, _, None))
    }

  /** A definition's body: its leading `count` lambdas are its parameters, and one whose type erases is dropped. */
  private def definitionBody(
      node: Sourced[MonomorphicExpression],
      count: Int
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    node.value.expression match {
      case FunctionLiteral(parameter, parameterType, body) if count > 0 =>
        erased(parameterType).ifM(
          definitionBody(body, count - 1),
          for {
            domain <- represented(parameterType)
            inner  <- definitionBody(body, count - 1)
          } yield lambda(node, parameter, domain, inner)
        )
      case _                                                            => materialized(node)
    }

  /** A node whose value must exist: one of an erased type is `unit`. */
  private def materialized(node: Sourced[MonomorphicExpression]): CompilerIO[Sourced[MonomorphicExpression]] =
    erased(node.value.expressionType).ifM(dropped(node).as(unitAt(node)), rewritten(node))

  /** A node of a type that does not erase. */
  private def rewritten(node: Sourced[MonomorphicExpression]): CompilerIO[Sourced[MonomorphicExpression]] =
    node.value.expression match {
      case IntegerLiteral(_) | StringLiteral(_)          => node.pure[CompilerIO]
      case ParameterReference(name)                      =>
        represented(node.value.expressionType).map(t => node.as(MonomorphicExpression(t, ParameterReference(name))))
      case FunctionLiteral(parameter, parameterType, body) =>
        for {
          domain <- represented(parameterType)
          inner  <- materialized(body)
        } yield lambda(node, parameter, domain, inner)
      case MonomorphicValueReference(_, _) | FunctionApplication(_, _) =>
        val (head, applications) = spine(node, Seq.empty)
        application(node, head, applications)
    }

  /** An application spine, `head` applied to each of `applications`' arguments in turn; `node` is the outermost. */
  private def application(
      node: Sourced[MonomorphicExpression],
      head: Sourced[MonomorphicExpression],
      applications: Seq[Sourced[FunctionApplication]]
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    head.value.expression match {
      case reference @ MonomorphicValueReference(name, _) =>
        val tpe = head.value.expressionType
        callee(name.value, tpe).flatMap {
          case Callee.Constructor     => construction(node, head.as(reference), tpe, applications)
          case Callee.Accessor(index) => fieldRead(node, head.as(reference), tpe, applications, index)
          case Callee.Ordinary        => call(node, head.as(reference), tpe, applications)
        }
      case FunctionLiteral(parameter, parameterType, body) if applications.nonEmpty =>
        let(head, applications.head, parameter, parameterType, body).flatMap(applied(node, _, applications.tail))
      case _                                  =>
        materialized(head).flatMap(applied(node, _, applications))
    }

  /** A handle's construction, saturated: its surviving field, or the `data` itself. */
  private def construction(
      node: Sourced[MonomorphicExpression],
      reference: Sourced[MonomorphicValueReference],
      tpe: GroundValue,
      applications: Seq[Sourced[FunctionApplication]]
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    representationOf(tpe.deepReturnType).flatMap {
      case Representation.Field(index, _) if applications.size === tpe.functionArity =>
        applications.zipWithIndex.filter(_._2 =!= index).traverse_(a => dropped(a._1.value.argument)) >>
          materialized(applications(index).value.argument)
      case Representation.Data                                                      =>
        call(node, reference, tpe, applications)
      case _                                                                        =>
        refused(node, "a handle constructed partially")
    }

  /** A field read off a handle: the handle itself when it is that field, `unit` when the field does not survive. */
  private def fieldRead(
      node: Sourced[MonomorphicExpression],
      reference: Sourced[MonomorphicValueReference],
      tpe: GroundValue,
      applications: Seq[Sourced[FunctionApplication]],
      index: Int
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    applications.headOption match {
      case None              => refused(node, "a field accessor of a handle used as a value")
      case Some(application) =>
        val handle = application.value.argument
        representationOf(handle.value.expressionType).flatMap {
          case Representation.Data                         => call(node, reference, tpe, applications)
          case Representation.Field(kept, _) if kept === index =>
            materialized(handle).flatMap(applied(node, _, applications.tail))
          case _                                           =>
            // A field that does not survive is a `unit` capture field: one of an erased type is never read here,
            // since the read itself is of that type and so already `unit` (`materialized`).
            dropped(handle).as(unitAt(node))
        }
    }

  /** A call of an ordinary value: the arguments its leading parameters of an erased type take are dropped. */
  private def call(
      node: Sourced[MonomorphicExpression],
      reference: Sourced[MonomorphicValueReference],
      tpe: GroundValue,
      applications: Seq[Sourced[FunctionApplication]]
  ): CompilerIO[Sourced[MonomorphicExpression]] = {
    val domains = tpe.extractParamAndReturnTypes._1
    for {
      erasedAt  <- domains.zipWithIndex.filterA((domain, _) => erased(domain)).map(_.map(_._2).toSet)
      arity     <- if (erasedAt.isEmpty) 0.pure[CompilerIO]
                   else naturalArityOf(reference.value)
      dropping   = erasedAt.filter(_ < arity)
      _         <- refused(node, "a value taking a handle referenced without it")
                     .whenA(dropping.exists(_ >= applications.size))
      headType  <- leadingDropped(tpe, arity)
      arguments <- applications.zipWithIndex.traverseFilter { (application, index) =>
                     if (dropping.contains(index)) dropped(application.value.argument).as(None)
                     else materialized(application.value.argument).map(a => Some(application.as(a)))
                   }
    } yield rebuilt(node, reference.as(MonomorphicExpression(headType, reference.value)), arguments)
  }

  private def naturalArityOf(reference: MonomorphicValueReference): CompilerIO[Int] =
    getFactOrAbort(MonomorphicValue.Key(reference.valueName.value, reference.typeArguments))
      .map(_.naturalArity.getOrElse(0))

  /** A `let`, `(parameter -> body)(argument)`: its subject alone when the bound value erases, the bound variable
    * substituted into it when erasure made the bound value one.
    */
  private def let(
      head: Sourced[MonomorphicExpression],
      application: Sourced[FunctionApplication],
      parameter: Sourced[String],
      parameterType: GroundValue,
      body: Sourced[MonomorphicExpression]
  ): CompilerIO[Sourced[MonomorphicExpression]] = {
    val argument = application.value.argument
    erased(parameterType).ifM(
      dropped(argument) >> materialized(body),
      for {
        bound  <- materialized(argument)
        domain <- represented(parameterType)
        inner  <- materialized(body)
      } yield (argument.value.expression, bound.value.expression) match {
        case (ParameterReference(_), _)                                    =>
          letOf(head, application, parameter, domain, inner, bound)
        case (_, ParameterReference(variable)) if !binds(inner, variable.value) =>
          substituted(inner, parameter.value, variable.value)
        case _                                                             =>
          letOf(head, application, parameter, domain, inner, bound)
      }
    )
  }

  private def letOf(
      head: Sourced[MonomorphicExpression],
      application: Sourced[FunctionApplication],
      parameter: Sourced[String],
      domain: GroundValue,
      inner: Sourced[MonomorphicExpression],
      bound: Sourced[MonomorphicExpression]
  ): Sourced[MonomorphicExpression] = {
    val function = lambda(head, parameter, domain, inner)
    application.as(MonomorphicExpression(inner.value.expressionType, FunctionApplication(function, bound)))
  }

  /** `target`, already rewritten, applied to the rest of a spine. */
  private def applied(
      node: Sourced[MonomorphicExpression],
      target: Sourced[MonomorphicExpression],
      applications: Seq[Sourced[FunctionApplication]]
  ): CompilerIO[Sourced[MonomorphicExpression]] =
    applications
      .traverse(application => materialized(application.value.argument).map(application.as))
      .map(rebuilt(node, target, _))

  /** `target` applied to `arguments`, each at its own application's position, and the last at `node`'s: when erasure
    * dropped the outermost argument, the call is still the node the refinement channel recorded its result at.
    */
  private def rebuilt(
      node: Sourced[MonomorphicExpression],
      target: Sourced[MonomorphicExpression],
      arguments: Seq[Sourced[Sourced[MonomorphicExpression]]]
  ): Sourced[MonomorphicExpression] = {
    val built = arguments.foldLeft(target) { (function, argument) =>
      argument.as(
        MonomorphicExpression(
          function.value.expressionType.asFunctionType.map(_._2).getOrElse(function.value.expressionType),
          FunctionApplication(function, argument.value)
        )
      )
    }
    node.as(built.value)
  }

  /** The head of an application spine, and its applications innermost first. */
  @tailrec
  private def spine(
      node: Sourced[MonomorphicExpression],
      applications: Seq[Sourced[FunctionApplication]]
  ): (Sourced[MonomorphicExpression], Seq[Sourced[FunctionApplication]]) =
    node.value.expression match {
      case application @ FunctionApplication(target, _) => spine(target, node.as(application) +: applications)
      case _                                            => (node, applications)
    }

  /** Refuse a dropped expression that is not one of a handle's pure shapes: dropping it would drop what it does. */
  private def dropped(node: Sourced[MonomorphicExpression]): CompilerIO[Unit] =
    droppable(node).ifM(().pure[CompilerIO], refused(node, "an expression of a handle's type that does something"))

  private def droppable(node: Sourced[MonomorphicExpression]): CompilerIO[Boolean] =
    node.value.expression match {
      case ParameterReference(_)                => true.pure[CompilerIO]
      case MonomorphicValueReference(name, _)
          if name.value === WellKnownTypes.unitValueFQN => true.pure[CompilerIO]
      case MonomorphicValueReference(_, _) | FunctionApplication(_, _) =>
        val (head, applications) = spine(node, Seq.empty)
        head.value.expression match {
          case MonomorphicValueReference(name, _) =>
            callee(name.value, head.value.expressionType).flatMap {
              case Callee.Ordinary => false.pure[CompilerIO]
              case _               => applications.forallM(a => droppable(a.value.argument))
            }
          case _                                  => false.pure[CompilerIO]
        }
      case _                                    => false.pure[CompilerIO]
    }

  private def refused[A](node: Sourced[?], what: String): CompilerIO[A] =
    compilerAbort(node.as(s"Internal compiler error: cannot erase $what."))

  private def callee(name: ValueFQN, tpe: GroundValue): CompilerIO[Callee] =
    if (name.name.qualifier === Qualifier.Default && Handles.isDataType(name.name.name))
      Callee.Constructor.pure[CompilerIO]
    else
      tpe.asFunctionType match {
        case Some((domain, _)) if isHandle(domain) =>
          getFactOrAbort(OperatorResolvedValue.Key(name)).map(_.roleHint match {
            case RoleHint.FieldAccessor(dataType, index) if Handles.isDataType(dataType.name) => Callee.Accessor(index)
            case _                                                                           => Callee.Ordinary
          })
        case _                                     => Callee.Ordinary.pure[CompilerIO]
      }

  /** `tpe` with its first `count` parameters of an erased type dropped, as a definition taking them is called. */
  private def leadingDropped(tpe: GroundValue, count: Int): CompilerIO[GroundValue] =
    tpe.asFunctionType match {
      case Some((domain, codomain)) if count > 0 =>
        erased(domain).ifM(
          leadingDropped(codomain, count - 1),
          (represented(domain), leadingDropped(codomain, count - 1)).mapN(functionType)
        )
      case _                                     => represented(tpe)
    }

  /** The type a value of `tpe` has at run time, where it must exist: a handle that erases is `unit` there. */
  private def represented(tpe: GroundValue): CompilerIO[GroundValue] =
    tpe match {
      case GroundValue.Structure(name, Seq(domain, codomain), valueType)
          if name === WellKnownTypes.functionDataTypeFQN =>
        (represented(domain), represented(codomain)).mapN((d, c) => GroundValue.Structure(name, Seq(d, c), valueType))
      case _ if isHandle(tpe) =>
        representationOf(tpe).flatMap {
          case Representation.Erased              => unitType.pure[CompilerIO]
          case Representation.Field(_, fieldType) => represented(fieldType)
          case Representation.Data                => tpe.pure[CompilerIO]
        }
      case _                  => tpe.pure[CompilerIO]
    }

  private def erased(tpe: GroundValue): CompilerIO[Boolean] =
    if (isHandle(tpe)) representationOf(tpe).map(_ == Representation.Erased) else false.pure[CompilerIO]

  /** A handle type's representation, read off its constructor at the handle's type arguments. */
  private def representationOf(handle: GroundValue): CompilerIO[Representation] =
    handle match {
      case GroundValue.Structure(name, arguments, _) =>
        val constructor = ValueFQN(name.moduleName, QualifiedName(name.name.name, Qualifier.Default))
        for {
          declaration <- getFactOrAbort(OperatorResolvedValue.Key(constructor))
          instance    <- getFactOrAbort(MonomorphicValue.Key(constructor, arguments))
          declared     = SignatureView.of(declaration.signature).parameters
          fields       = instance.signature.extractParamAndReturnTypes._1.zip(declared).zipWithIndex
          surviving   <- fields.filterA { case ((fieldType, declaredType), _) => survives(fieldType, declaredType) }
        } yield surviving match {
          case Seq()                      => Representation.Erased
          case Seq(((fieldType, _), index)) => Representation.Field(index, fieldType)
          case _                          => Representation.Data
        }
      case _                                         => Representation.Data.pure[CompilerIO]
    }

  /** A field survives unless its type erases, or it is a capture field nothing captured, which holds `unit`. */
  private def survives(fieldType: GroundValue, declaredType: Sourced[OperatorResolvedExpression]): CompilerIO[Boolean] =
    declaredType.value match {
      case OperatorResolvedExpression.ParameterReference(name)
          if Handles.isCapture(name.value) && fieldType === unitType =>
        false.pure[CompilerIO]
      case _ =>
        erased(fieldType).map(!_)
    }

  private def isHandle(tpe: GroundValue): Boolean = tpe match {
    case GroundValue.Structure(name, _, _) =>
      name.name.qualifier === Qualifier.Type && Handles.isDataType(name.name.name)
    case _                                 => false
  }

  /** Whether some lambda in `node` binds `name`, which a substitution of it would capture. */
  private def binds(node: Sourced[MonomorphicExpression], name: String): Boolean =
    node.value.expression match {
      case FunctionLiteral(parameter, _, body) => parameter.value === name || binds(body, name)
      case FunctionApplication(target, argument) => binds(target, name) || binds(argument, name)
      case _                                   => false
    }

  /** `node` with every free reference to `from` made a reference to `to`. */
  private def substituted(
      node: Sourced[MonomorphicExpression],
      from: String,
      to: String
  ): Sourced[MonomorphicExpression] =
    node.value.expression match {
      case ParameterReference(name) if name.value === from                  =>
        node.as(node.value.copy(expression = ParameterReference(name.as(to))))
      case FunctionLiteral(parameter, _, _) if parameter.value === from       => node
      case FunctionLiteral(parameter, parameterType, body)                   =>
        node.as(node.value.copy(expression = FunctionLiteral(parameter, parameterType, substituted(body, from, to))))
      case FunctionApplication(target, argument)                             =>
        node.as(
          node.value.copy(expression =
            FunctionApplication(substituted(target, from, to), substituted(argument, from, to))
          )
        )
      case _                                                                 => node
    }

  private def lambda(
      at: Sourced[?],
      parameter: Sourced[String],
      domain: GroundValue,
      body: Sourced[MonomorphicExpression]
  ): Sourced[MonomorphicExpression] =
    at.as(
      MonomorphicExpression(functionType(domain, body.value.expressionType), FunctionLiteral(parameter, domain, body))
    )

  private def unitAt(node: Sourced[?]): Sourced[MonomorphicExpression] =
    node.as(MonomorphicExpression(unitType, MonomorphicValueReference(node.as(WellKnownTypes.unitValueFQN), Seq.empty)))

  private val unitType: GroundValue = GroundValue.Structure(WellKnownTypes.unitTypeFQN, Seq.empty, GroundValue.Type)

  private def functionType(domain: GroundValue, codomain: GroundValue): GroundValue =
    GroundValue.Structure(WellKnownTypes.functionDataTypeFQN, Seq(domain, codomain), GroundValue.Type)
}
