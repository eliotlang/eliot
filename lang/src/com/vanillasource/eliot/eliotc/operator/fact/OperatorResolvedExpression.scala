package com.vanillasource.eliot.eliotc.operator.fact

import cats.{Applicative, Monad}
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression
import com.vanillasource.eliot.eliotc.module.fact.{ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

sealed trait OperatorResolvedExpression

object OperatorResolvedExpression {
  case class FunctionApplication(
      target: Sourced[OperatorResolvedExpression],
      argument: Sourced[OperatorResolvedExpression]
  ) extends OperatorResolvedExpression
  case class IntegerLiteral(integerLiteral: Sourced[BigInt])    extends OperatorResolvedExpression
  case class StringLiteral(stringLiteral: Sourced[String])      extends OperatorResolvedExpression
  case class ParameterReference(parameterName: Sourced[String]) extends OperatorResolvedExpression
  case class ValueReference(
      valueName: Sourced[ValueFQN],
      typeArgs: Seq[Sourced[OperatorResolvedExpression]] = Seq.empty
  ) extends OperatorResolvedExpression
  case class FunctionLiteral(
      parameterName: Sourced[String],
      parameterType: Option[Sourced[OperatorResolvedExpression]],
      body: Sourced[OperatorResolvedExpression]
  ) extends OperatorResolvedExpression

  /** `subject with implementation` — effects v6's binding of a named implementation for the calls lexically inside
    * `subject` (`docs/effects.md` §9.3), as the resolver leaves it: inside the call of its implementation's binder
    * ([[com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters]]). This is the last fact that carries it: the
    * `row` phase checks the scope it opens and erases the node, keeping the binder's call, where the evidence after
    * monomorphization installs the implementation (`docs/effects.md` D35).
    */
  case class WithBinding(
      subject: Sourced[OperatorResolvedExpression],
      implementation: Sourced[ValueFQN]
  ) extends OperatorResolvedExpression

  /** Replace every free occurrence of the parameter named `paramName` with `replacement`. Respects shadowing by
    * `FunctionLiteral` parameters of the same name.
    */
  def substitute(
      expr: OperatorResolvedExpression,
      paramName: String,
      replacement: OperatorResolvedExpression
  ): OperatorResolvedExpression = expr match {
    case ParameterReference(name) if name.value == paramName =>
      replacement
    case _: ParameterReference                               =>
      expr
    case FunctionApplication(target, argument)               =>
      FunctionApplication(
        target.map(substitute(_, paramName, replacement)),
        argument.map(substitute(_, paramName, replacement))
      )
    case ValueReference(name, typeArgs)                      =>
      ValueReference(name, typeArgs.map(_.map(substitute(_, paramName, replacement))))
    case FunctionLiteral(pn, paramType, body)                =>
      val newParamType = paramType.map(_.map(substitute(_, paramName, replacement)))
      val newBody      =
        if (pn.value == paramName) body
        else body.map(substitute(_, paramName, replacement))
      FunctionLiteral(pn, newParamType, newBody)
    case WithBinding(subject, implementation)         =>
      WithBinding(subject.map(substitute(_, paramName, replacement)), implementation)
    case _: IntegerLiteral | _: StringLiteral                =>
      expr
  }

  /** Returns true if `expr` contains a free `ParameterReference` named `varName`. Used for occurs check during
    * unification.
    */
  def containsVar(expr: OperatorResolvedExpression, varName: String): Boolean = expr match {
    case ParameterReference(name)              => name.value == varName
    case FunctionApplication(target, argument) =>
      containsVar(target.value, varName) || containsVar(argument.value, varName)
    case ValueReference(_, typeArgs)           =>
      typeArgs.exists(ta => containsVar(ta.value, varName))
    case FunctionLiteral(pn, paramType, body)  =>
      val inParamType = paramType.exists(pt => containsVar(pt.value, varName))
      val inBody      = pn.value != varName && containsVar(body.value, varName)
      inParamType || inBody
    case WithBinding(subject, _)            => containsVar(subject.value, varName)
    case _: IntegerLiteral | _: StringLiteral  => false
  }

  /** Monadically fold over every [[ValueReference]] in the expression, including references nested in
    * type-argument lists and in `FunctionLiteral` parameter type annotations.
    *
    * The callback receives the running state plus each [[Sourced]] reference as it is visited; the walk itself
    * does not deduplicate — callers that want to avoid visiting the same vfqn twice should short-circuit inside
    * the callback by inspecting the state they thread.
    */
  def foldValueReferences[F[_]: Monad, S](
      expr: OperatorResolvedExpression,
      initial: S
  )(
      onVfqn: (S, Sourced[ValueFQN]) => F[S]
  ): F[S] = {
    def go(e: OperatorResolvedExpression, s: S): F[S] = e match {
      case ValueReference(name, typeArgs)      =>
        onVfqn(s, name).flatMap(s1 => typeArgs.foldLeft(s1.pure[F])((a, ta) => a.flatMap(go(ta.value, _))))
      case FunctionApplication(t, a)           =>
        go(t.value, s).flatMap(go(a.value, _))
      case FunctionLiteral(_, paramType, body) =>
        val withParam: F[S] = paramType match {
          case Some(pt) => go(pt.value, s)
          case None     => s.pure[F]
        }
        withParam.flatMap(go(body.value, _))
      case WithBinding(subject, _)          => go(subject.value, s)
      case _: IntegerLiteral | _: StringLiteral | _: ParameterReference =>
        s.pure[F]
    }
    go(expr, initial)
  }

  def mapChildrenM[F[_]: Applicative](f: OperatorResolvedExpression => F[OperatorResolvedExpression])(
      expr: OperatorResolvedExpression
  ): F[OperatorResolvedExpression] =
    expr match {
      case FunctionApplication(target, arg)                             =>
        (f(target.value).map(target.as), f(arg.value).map(arg.as)).mapN(FunctionApplication.apply)
      case FunctionLiteral(paramName, paramType, body)                  =>
        (paramType.traverse(pt => f(pt.value).map(pt.as)), f(body.value).map(body.as))
          .mapN(FunctionLiteral(paramName, _, _))
      case ValueReference(name, typeArgs)                               =>
        typeArgs.traverse(ta => f(ta.value).map(ta.as)).map(ValueReference(name, _))
      case WithBinding(subject, implementation)                  =>
        f(subject.value).map(sub => WithBinding(subject.as(sub), implementation))
      case _: IntegerLiteral | _: StringLiteral | _: ParameterReference => expr.pure[F]
    }

  /** True iff `expr` is a reference to the built-in `Function` type constructor (the arrow former). */
  def isFunctionReference(expr: OperatorResolvedExpression): Boolean = expr match {
    case ValueReference(name, _) => name.value == WellKnownTypes.functionDataTypeFQN
    case _                       => false
  }

  /** View an expression as a curried `Function[dom, cod]` arrow
    * (`FunctionApplication(FunctionApplication(Function, dom), cod)`), returning its domain and codomain, or [[None]] if
    * it is not an arrow. Mirrors `GroundValue.asFunctionType` at the operator-resolved level.
    */
  def asArrow(
      expr: OperatorResolvedExpression
  ): Option[(Sourced[OperatorResolvedExpression], Sourced[OperatorResolvedExpression])] = expr match {
    case FunctionApplication(target, cod) =>
      target.value match {
        case FunctionApplication(fn, dom) if isFunctionReference(fn.value) => Some((dom, cod))
        case _                                                             => None
      }
    case _                                => None
  }

  /** Build a curried `Function[dom, cod]` arrow, attributing the synthesized nodes to `dom`'s source position. */
  def arrow(
      dom: Sourced[OperatorResolvedExpression],
      cod: Sourced[OperatorResolvedExpression]
  ): OperatorResolvedExpression =
    FunctionApplication(
      dom.as(FunctionApplication(dom.as(ValueReference(dom.as(WellKnownTypes.functionDataTypeFQN))), dom)),
      cod
    )

  /** Decompose a curried application into its head and left-to-right argument list. */
  def spine(
      expr: OperatorResolvedExpression
  ): (OperatorResolvedExpression, Seq[Sourced[OperatorResolvedExpression]]) = expr match {
    case FunctionApplication(target, arg) =>
      val (head, args) = spine(target.value)
      (head, args :+ arg)
    case other                            => (other, Seq.empty)
  }

  /** Re-apply a head to arguments via left-associated [[FunctionApplication]]s, attributing each wrapper to that
    * argument's source position.
    */
  def applyChain(
      head: Sourced[OperatorResolvedExpression],
      args: Seq[Sourced[OperatorResolvedExpression]]
  ): OperatorResolvedExpression =
    args.foldLeft(head)((acc, arg) => arg.as(FunctionApplication(acc, arg))).value

  /** A structural view of a value signature: its leading generic binders, its curried `Function`
    * parameter domains, and its final non-arrow return position. Round-trips via [[SignatureView.toExpression]]; this is
    * the shared curried-arrow view used by the implicit-generics saturation rewrites, mirroring
    * `GroundValue.extractParamAndReturnTypes` at the operator-resolved level.
    *
    * Note an abstract type constructor's signature *is* its kind chain (`Function[BigInteger, …, Type]`), so it has no
    * [[binders]] and its parameter kinds appear as [[parameters]].
    */
  case class SignatureView(
      binders: Seq[SignatureView.Binder],
      parameters: Seq[Sourced[OperatorResolvedExpression]],
      returnType: Sourced[OperatorResolvedExpression]
  ) {
    def withReturnType(replacement: OperatorResolvedExpression): SignatureView =
      copy(returnType = returnType.as(replacement))

    def withParameters(newParameters: Seq[Sourced[OperatorResolvedExpression]]): SignatureView =
      copy(parameters = newParameters)

    /** Reconstruct the signature: wrap the parameters back into a curried `Function` chain ending in the return
      * position, then prepend the generic binders as [[FunctionLiteral]]s.
      */
    def toExpression: OperatorResolvedExpression = {
      val arrows = parameters.foldRight(returnType)((dom, acc) => acc.as(arrow(dom, acc)))
      binders.foldRight(arrows.value)((b, body) => FunctionLiteral(b.name, b.parameterType, b.name.as(body)))
    }
  }

  object SignatureView {

    /** One leading generic binder of a signature: its parameter name and optional kind annotation. */
    case class Binder(
        name: Sourced[String],
        parameterType: Option[Sourced[OperatorResolvedExpression]]
    )

    /** Decompose a signature into its leading generic [[FunctionLiteral]] binders, its curried `Function` parameter
      * domains, and its final non-arrow return position.
      */
    def of(signature: Sourced[OperatorResolvedExpression]): SignatureView =
      signature.value match {
        case FunctionLiteral(name, parameterType, body) =>
          val rest = of(body)
          rest.copy(binders = Binder(name, parameterType) +: rest.binders)
        case _                                          =>
          val (parameters, returnType) = arrowChain(signature)
          SignatureView(Seq.empty, parameters, returnType)
      }

    private def arrowChain(
        expr: Sourced[OperatorResolvedExpression]
    ): (Seq[Sourced[OperatorResolvedExpression]], Sourced[OperatorResolvedExpression]) =
      asArrow(expr.value) match {
        case Some((dom, cod)) =>
          val (rest, returnType) = arrowChain(cod)
          (dom +: rest, returnType)
        case None             => (Seq.empty, expr)
      }
  }

  def fromExpression(expr: MatchDesugaredExpression): OperatorResolvedExpression = expr match {
    case MatchDesugaredExpression.FunctionApplication(target, arg)            =>
      FunctionApplication(target.map(fromExpression), arg.map(fromExpression))
    case MatchDesugaredExpression.IntegerLiteral(v)                           => IntegerLiteral(v)
    case MatchDesugaredExpression.StringLiteral(v)                            => StringLiteral(v)
    case MatchDesugaredExpression.ParameterReference(v)                       => ParameterReference(v)
    case MatchDesugaredExpression.ValueReference(name, typeArgs)              =>
      ValueReference(name, typeArgs.map(ta => ta.map(fromExpression)))
    case MatchDesugaredExpression.FunctionLiteral(paramName, paramType, body) =>
      FunctionLiteral(paramName, paramType.map(_.map(fromExpression)), body.map(fromExpression))
    case MatchDesugaredExpression.WithBinding(subject, implementation) =>
      WithBinding(subject.map(fromExpression), implementation)
    case MatchDesugaredExpression.FlatExpression(_)                           =>
      throw IllegalStateException("FlatExpression should not exist after operator resolution")
  }

  extension (self: OperatorResolvedExpression)
    def render: String = self match {
      case IntegerLiteral(Sourced(_, _, value))                               => value.toString()
      case StringLiteral(Sourced(_, _, value))                                => s"\"$value\""
      case FunctionApplication(Sourced(_, _, target), Sourced(_, _, argument)) =>
        s"${target.render}(${argument.render})"
      case FunctionLiteral(param, paramType, body)                            =>
        s"(${paramType.map(_.value.render).getOrElse("<n/a>")} :: ${param.value}) -> ${body.value.render}"
      case ParameterReference(name)                                           => name.value
      case ValueReference(name, typeArgs)                                     =>
        name.value.show +
          (if (typeArgs.isEmpty) "" else typeArgs.map(ta => ta.value.render).mkString("[", ", ", "]"))
      case WithBinding(subject, implementation)                            =>
        s"${subject.value.render} with ${implementation.value.show}"
    }
}
