package com.vanillasource.eliot.eliotc.monomorphize.fact

import cats.Eq
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}

/** Ground values represent fully evaluated, concrete values with no free variables or unsolved metas. These are the
  * output of quoting NbE semantic values back to a first-order representation.
  *
  * Examples:
  *   - `Direct(42, bigIntType)` — the integer 42 with type BigInteger
  *   - `Structure(functionFQN, Seq(intType, stringType), Type)` — Function[Int, String]
  *   - `Type` — the type of all types
  */
sealed trait GroundValue {
  def valueType: GroundValue
}

object GroundValue {

  /** The payload of a [[Direct]]: the closed set of primitive constants the evaluator can produce.
    *
    * This was an `Any` until 2026-08-06. Only four shapes ever occupied it — the three the read-back documents
    * (`BigInt`, `String`, `Boolean`) plus the unit placeholder — but `Any` said so nowhere, so every consumer had to
    * re-discover the set by type-testing, and the exhaustive arm could only be a defensive fallback. Naming the set
    * makes the matches exhaustive, and makes the ground domain encodable: no codec can be written for `Any`, and
    * `GroundValue` is reachable from most of the monomorphize layer (`docs/incremental-compilation.md` §14).
    */
  enum Literal {
    case IntegerValue(value: BigInt)
    case StringValue(value: String)
    case BooleanValue(value: Boolean)

    /** The placeholder a value of no interest reduces to — `MatchNativesProcessor`'s unit. Never materialises as a
      * runtime constant.
      */
    case UnitValue
  }

  object Literal {
    extension (self: Literal)
      def debugString: String = self match {
        case IntegerValue(value) => value.toString
        case StringValue(value)  => value
        case BooleanValue(value) => value.toString
        case UnitValue           => "()"
      }
  }

  case class Direct(value: Literal, override val valueType: GroundValue) extends GroundValue

  /** Construction sugar, so a call site whose payload type is already statically known does not have to name the
    * [[Literal]] case. Pattern sites deliberately get no such sugar: matching is where the closed set has to be
    * visible.
    */
  object Direct {
    def apply(value: BigInt, valueType: GroundValue): Direct  = Direct(Literal.IntegerValue(value), valueType)
    def apply(value: String, valueType: GroundValue): Direct  = Direct(Literal.StringValue(value), valueType)
    def apply(value: Boolean, valueType: GroundValue): Direct = Direct(Literal.BooleanValue(value), valueType)
    def apply(value: Unit, valueType: GroundValue): Direct    = Direct(Literal.UnitValue, valueType)
  }

  /** A type-constructor or data-constructor application identified by [[typeName]] applied to positional [[args]]. */
  case class Structure(typeName: ValueFQN, args: Seq[GroundValue], override val valueType: GroundValue)
      extends GroundValue

  /** The type of all types and Type itself. This is the root of the type hierarchy where the infinite recursion stops.
    */
  object Type extends GroundValue {
    override def valueType: GroundValue = this
  }

  /** A signature-twin *parameter* (signature-unification C2): a leftover generic binder that a *partial-arity* signature
    * twin received no type argument for, read back **under its binder** rather than defaulted to `Type`. `index` is the
    * binder's position; `args` its applied spine (a higher-kinded binder `F[_]` occurring as `F[Unit]` carries `[Unit]`).
    *
    * A `Param` lives **only** inside a `CompilerMonomorphicValue(*@Signature)` fact at a partial key — it is the honest
    * read-back of a generic signature (a function value) at fewer arguments than binders. Its two consumers:
    *   - the value mono re-inflates each `Param` to a *fresh metavariable* (`Evaluator.groundToSem` with a Param→meta
    *     substitution), preserving the constraint-covered deferral a leftover binder needs (signature-unification §4.7);
    *   - `groundToSem` **without** a substitution hard-errors on a `Param` (fail-safe) — a `Param` must never leak into a
    *     value mono's own published signature (codegen's erased/defaulted form, finding 2) or reach the runtime track.
    */
  case class Param(index: Int, args: Seq[GroundValue], override val valueType: GroundValue) extends GroundValue

  extension (gv: GroundValue) {

    def typeFQN: Option[ValueFQN] =
      gv match {
        case Structure(name, _, _) => Some(name)
        case Type                  => Some(WellKnownTypes.typeFQN)
        case _                     => None
      }

    def asFunctionType: Option[(GroundValue, GroundValue)] =
      gv match {
        case Structure(name, Seq(a, b), Type) if name === WellKnownTypes.functionDataTypeFQN => Some((a, b))
        case _                                                                               => None
      }

    @scala.annotation.tailrec
    def deepReturnType: GroundValue =
      gv.asFunctionType match {
        case Some((_, returnType)) => returnType.deepReturnType
        case None                  => gv
      }

    def functionArity: Int =
      gv.asFunctionType match {
        case Some((_, returnType)) => 1 + returnType.functionArity
        case None                  => 0
      }

    def extractParamAndReturnTypes: (Seq[GroundValue], GroundValue) =
      gv.asFunctionType match {
        case Some((paramType, returnType)) =>
          val (restParams, finalReturn) = returnType.extractParamAndReturnTypes
          (paramType +: restParams, finalReturn)
        case None                          =>
          (Seq.empty, gv)
      }

    /** The runtime *carrier* FQN this value's type erases to — the platform-independent half of type erasure. Function
      * types collapse to [[WellKnownTypes.functionCarrierFQN]]; a concrete data/structure type keeps its FQN with the
      * type-constructor qualifier stripped to [[Qualifier.Default]]; everything else (`Type` itself, or an
      * erased/phantom value with no type FQN) collapses to the opaque top carrier [[WellKnownTypes.anyFQN]]. A backend
      * maps the resulting FQN to a concrete machine type (the JVM via `NativeType.types`); this method holds the
      * collapse logic so no backend reimplements it.
      */
    def carrierFQN: ValueFQN =
      gv match {
        case _ if gv.asFunctionType.isDefined => WellKnownTypes.functionCarrierFQN
        case _                                =>
          gv.typeFQN match {
            case Some(name) if name =!= WellKnownTypes.typeFQN =>
              ValueFQN(name.moduleName, QualifiedName(name.name.name, Qualifier.Default))
            case _                                             => WellKnownTypes.anyFQN
          }
      }
  }

  given Eq[GroundValue] = Eq.fromUniversalEquals

  /** A deliberately terse, one-line debug rendering of a ground value (a plain method, never the `cats.Show`
    * typeclass). It collapses every non-`Type` structure to `Structure(...)`; the user-facing, fully-applied
    * rendering is [[GroundValueRenderer.render]].
    */
  extension (self: GroundValue)
    def debugString: String = self match {
      case Type                                               => "Type"
      case Direct(value, _)                                   => value.debugString
      case Structure(name, _, valueType) if valueType === Type => name.name.name
      case Structure(_, _, _)                                 => "Structure(...)"
      case Param(index, Nil, _)                               => s"?p$index"
      case Param(index, args, _)                              => s"?p$index[${args.map(_.debugString).mkString(", ")}]"
    }
}
