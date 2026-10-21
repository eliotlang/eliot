package com.vanillasource.eliot.eliotc.monomorphize.domain

import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue

/** The semantic domain for Normalisation by Evaluation (NbE). ORE syntax is evaluated into SemValues via closures. Type
  * equality becomes structural equality of normal forms.
  *
  * - VType: the type of all types.
  * - VConst: non-function ground values (concrete types, literals).
  * - VLam: runtime lambda closure. Produced by the evaluator for FunctionLiteral.
  * - VPi: function type (dependent or non-dependent). Produced by the Checker or Function native.
  * - VNative: primitive that fires on concrete arguments.
  * - VTopDef: lazy top-level definition with a spine.
  * - VStuckNative: a primitive (native) application stuck on not-yet-concrete arguments.
  * - VFlex: a *flex* — an unsolved metavariable unification may still instantiate (as opposed to a rigid
  *   [[VNeutral]]), applied to a spine. Named so as not to read as refinement meta-information.
  * - VNeutral: stuck application with a variable head and a spine.
  */
sealed trait SemValue

object SemValue {
  case object VType extends SemValue

  case class VConst(ground: GroundValue) extends SemValue

  case class VLam(name: String, closure: SemValue => SemValue) extends SemValue

  case class VPi(domain: SemValue, codomain: SemValue => SemValue) extends SemValue

  case class VNative(paramType: SemValue, fire: SemValue => SemValue) extends SemValue

  /** A lazy top-level definition with a spine.
    *
    * @param typeArgFit
    *   How this binding's *body* lines up with the written **type arguments** in its spine, when known. See
    *   [[VTopDef.TypeArgFit]]; [[None]] means "unstated", and the body consumes its whole spine as before.
    */
  case class VTopDef(
      fqn: ValueFQN,
      cached: Option[Lazy[SemValue]],
      spine: Spine,
      typeArgFit: Option[VTopDef.TypeArgFit] = None
  ) extends SemValue

  object VTopDef {

    /** The mismatch between the type arguments a reference *writes* and the ones a body-ful binding can *bind*.
      *
      * A value's generic binders are binders of its **signature**, not lambdas of its runtime body, so
      * [[com.vanillasource.eliot.eliotc.monomorphize.processor.BindingClosure]] wraps the body in lambdas only for the
      * generics it *reifies* (references in value position). `closed[T](start, end) = Interval(Bounded(start), Bounded(end))`
      * reifies nothing: its body is two value lambdas and binds no type argument at all. Checker output nevertheless
      * carries every solved type argument, so the written `[BigInteger]` used to be β-applied to `start` — shifting each
      * value argument one to the left and reducing a well-typed definition to an ill-typed value
      * (`Interval(Bounded(BigInteger), Bounded(n), n)`), which no phase downstream is in a position to notice.
      *
      * The written arguments must still *stay in the spine*: for a bodied definition that does not unfold, the spine is
      * the type application the unifier decomposes and the printer renders. So the arguments are kept, and the ones the
      * body cannot bind are skipped only where they would do harm — when the body is unfolded
      * ([[com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator]] applies its `applySpine` rather than the raw
      * spine).
      *
      * Whether a body binds its generics cannot be counted from the wrap alone: a `def`'s body does not (the wrap is
      * what gives it one), while a *type-level* body binds its own parameters and needs no wrap. Both end up as leading
      * [[VLam]]s, so the two are told apart by **name** — a type argument is β-applied only while the body's head lambda
      * is the generic binder it belongs to. `closed`'s head lambda is `start`, not `T`, which is exactly the mismatch.
      *
      * @param genericNames
      *   The value's leading generic binder names, in declaration order.
      * @param applied
      *   Leading spine entries that are written type arguments. Type arguments are always applied before value
      *   arguments, so these are exactly the first `applied` entries.
      */
    case class TypeArgFit(genericNames: Seq[String], applied: Int)
  }

  /** A stuck primitive (native) application — a built-in operation (`add`, `min`, `&&`, `fold`, …) applied to
    * arguments that are not yet concrete, so it cannot reduce. Carries the native's own FQN and the (renormalised)
    * argument spine.
    *
    * Kept **distinct from [[VTopDef]]** on purpose (D3): a native application is **not injective** (`add(1, 3)` and
    * `add(2, 2)` are equal), so it must never be injectivity-decomposed by the unifier the way a type/data constructor
    * is, and the quoter must fail loudly rather than read it back as a ground `Structure` type if one ever survives to
    * read-back. [[com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator.renormalize]] re-fires it once its
    * arguments become concrete; two such applications are definitionally equal only when their FQNs and spines match.
    */
  case class VStuckNative(fqn: ValueFQN, spine: Spine) extends SemValue

  object VStuckNative {

    /** The canonical stuck form of a native applied to `args`: the FQN the residual call must name, the not-yet-concrete
      * arguments as its spine.
      */
    def of(fqn: ValueFQN, args: SemValue*): SemValue = VStuckNative(fqn, args.foldLeft(Spine.SNil: Spine)(_ :+ _))
  }

  case class VFlex(id: FlexId, spine: Spine) extends SemValue

  case class VNeutral(head: NeutralHead, spine: Spine) extends SemValue

  /** Opaque wrapper for metavariable identifiers. */
  opaque type FlexId = Int
  object FlexId {
    def apply(id: Int): FlexId          = id
    extension (id: FlexId) def value: Int = id
  }

  /** Head of a stuck neutral term. Identity is **structural** — the constructor and its fields — so neutrals minted by
    * different subsystems (parameter binders, the unifier's and quoter's binder-descending probes, the reserved
    * markers) can never collide even at a coinciding numeric level/depth, without relying on reserved name strings or
    * magic level numbers (the F3 hardening).
    */
  sealed trait NeutralHead {

    /** A human-readable name for diagnostics (error messages, `SemValuePrinter`). Never an identity — heads compare by
      * constructor.
      */
    def name: String
  }
  object NeutralHead {

    /** A genuine bound variable — a runtime value parameter, an unresolved parameter reference during evaluation, a
      * read-back binder — and the throwaway named placeholders diagnostics/printing substitute under a binder. Identity
      * is `(level, name)`.
      */
    case class Param(level: Int, name: String) extends NeutralHead

    /** A *signature-twin generic binder* left unapplied at a partial-arity key (signature-unification C2): distinct from
      * [[Param]] (a runtime value parameter) precisely because it read-backs differently — a `Param` neutral surviving to
      * read-back is a legitimately-structural runtime parameter that keeps a body structural, whereas a [[SignatureBinder]]
      * neutral quotes to a [[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Param]] (a parametric signature).
      * `index` is the binder's position; `name` its declared name (for diagnostics). Bound in ρ by `bindTwinBinders` for a
      * binder the key carries no argument for.
      */
    case class SignatureBinder(index: Int, name: String) extends NeutralHead

    /** A fresh rigid variable a binder-descending traversal injects to compare or probe under a [[VPi]]/[[VLam]]: the
      * unifier (identity-bearing — both codomains receive the same one) and the quoter (a throwaway probe). Keyed by
      * [[Origin]] and the traversal's local `depth`, so it can never coincide with a [[Param]] or the other traversal's
      * var even at an equal numeric depth — replacing the former `$unify<n>` / `$quote<n>` reserved-name convention.
      */
    case class Fresh(origin: Origin, depth: Int) extends NeutralHead {
      override def name: String = s"$$${origin.tag}$depth"
    }

    /** A reserved, binder-less marker with no scope: the fail-safe bad-apply head, the effectful-guard signature probe,
      * the stuck-match placeholder, the refinement channel's not-computed argument. Identity is the [[Marker]] — recognised by constructor, not by a magic name string.
      */
    case class Reserved(marker: Marker) extends NeutralHead {
      override def name: String = marker.tag
    }

    /** Which binder-descending traversal minted a [[Fresh]] var. */
    enum Origin(val tag: String) {
      case Unify extends Origin("unify")
      case Quote extends Origin("quote")
    }

    /** Which reserved, scope-less marker a [[Reserved]] head stands for. The `tag`s preserve the former display names. */
    enum Marker(val tag: String) {
      case BadApply   extends Marker("$bad-apply")
      case GuardProbe extends Marker("$guard-probe")
      case Match      extends Marker("match")

      /** An argument whose meta the refinement channel has not computed (`docs/meta.md` §13.5). It never quotes, so a
        * meta reduced over it is not computed either, whether the companion projects the argument or stores it.
        */
      case NotComputed extends Marker("$not-computed")
    }
  }

  /** Spine of applied arguments, reversed cons list for O(1) append. */
  sealed trait Spine {
    def :+(arg: SemValue): Spine = Spine.SApp(this, arg)

    def toList: List[SemValue] = {
      @scala.annotation.tailrec
      def go(spine: Spine, acc: List[SemValue]): List[SemValue] = spine match {
        case Spine.SNil          => acc
        case Spine.SApp(tl, hd) => go(tl, hd :: acc)
      }
      go(this, Nil)
    }
  }
  object Spine {
    case object SNil                               extends Spine
    case class SApp(tail: Spine, head: SemValue) extends Spine
  }

  /** A lazy value that is evaluated at most once. */
  class Lazy[A](computation: () => A) {
    lazy val value: A = computation()
  }
  object Lazy {
    def apply[A](computation: => A): Lazy[A] = new Lazy(() => computation)
  }
}
