package com.vanillasource.eliot.eliotc.jvm.classgen.asm

import com.vanillasource.eliot.eliotc.core.processor.MetaConstructorDesugarer
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.defaultSystemPackage
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal

/** The JVM backend's `Int` width policy: decode a refinement-channel value-range meta (an `Interval[BigInteger]`
  * [[GroundValue]], the `[min, max]` the channel pinned for a node) into the machine layout that carries it — the
  * smallest signed wrapper whose width contains the interval, falling back to a bignum.
  *
  * The `interval → width` decision is JVM-specific codegen knowledge (an ATtiny backend would map the same interval to
  * different widths), so it belongs in the backend, not in an Eliot ability the checker evaluates. The thresholds are
  * the standard signed machine-integer bounds (byte/short/32-bit/64-bit, else bignum). See
  * `docs/generic-refinement-merges.md` (Step 6) and `docs/bounds-as-refinements.md`.
  *
  * The output is a `Jvm*` representation-type [[ValueFQN]] the rest of the backend already understands
  * ([[NativeType.jvmRepresentationType]] → [[NativeType.types]]); the `Jvm*` names stay an internal backend vocabulary.
  */
object IntRepresentation {
  private val byteMin   = BigInt(-128)
  private val byteMax   = BigInt(127)
  private val shortMin  = BigInt(-32768)
  private val shortMax  = BigInt(32767)
  private val mediumMin = BigInt(-2147483648L)
  private val mediumMax = BigInt(2147483647L)
  private val longMin   = BigInt(Long.MinValue)
  private val longMax   = BigInt(Long.MaxValue)

  private val jvmByte       = NativeType.jvmRepresentationType("JvmByte")
  private val jvmShort      = NativeType.jvmRepresentationType("JvmShort")
  private val jvmInt        = NativeType.jvmRepresentationType("JvmInt")
  private val jvmLong       = NativeType.jvmRepresentationType("JvmLong")
  private val jvmBigInteger = NativeType.jvmRepresentationType("JvmBigInteger")

  /** The machine layout carrying every value in `[min, max]`: the narrowest signed wrapper that contains it
    * (`JvmByte`/`JvmShort`/`JvmInt`/`JvmLong`), else `JvmBigInteger`.
    */
  def widthOf(min: BigInt, max: BigInt): ValueFQN =
    if (byteMin <= min && max <= byteMax) jvmByte
    else if (shortMin <= min && max <= shortMax) jvmShort
    else if (mediumMin <= min && max <= mediumMax) jvmInt
    else if (longMin <= min && max <= longMax) jvmLong
    else jvmBigInteger

  /** The `eliot.lang.Int` module — home of both the tracked `Int` type and the `Jvm*` representation types. */
  private val intModuleName: ModuleName = ModuleName(defaultSystemPackage, "Int")

  private val integerTypeNames: Set[String] =
    Set("Int", "JvmByte", "JvmShort", "JvmInt", "JvmLong", "JvmBigInteger")

  /** Whether `gv` is an integer-carrying type — the tracked `Int` (post-flag-day, its width comes from the channel
    * meta) or one of the lowered `Jvm*` representation types (pre-un-lowering). Both live in the `eliot.lang.Int`
    * module; the qualifier is ignored (a type head may carry `Type` or the stripped `Default`). A node of such a type
    * has its machine width decided by its refinement meta ([[representationFor]]); any other type is laid out by
    * [[NativeType]] as usual.
    */
  def isIntegerType(gv: GroundValue): Boolean = gv match {
    case GroundValue.Structure(fqn, _, _) =>
      fqn.moduleName == intModuleName && integerTypeNames.contains(fqn.name.name)
    case _                                => false
  }

  /** The representation for a channel meta: decode its interval when present, else the ⊤/unknown layout
    * (`JvmBigInteger`) — a value the channel could not pin is soundly a bignum. Anything not of the value-range
    * domain's `Int$Meta(Interval[min, max])` shape (a future domain, a malformed meta) also yields the ⊤ layout
    * here: this backend only knows how to lay out the value-range domain.
    */
  def representationFor(meta: Option[GroundValue]): ValueFQN =
    meta.flatMap(intervalBounds).map { case (min, max) => widthOf(min, max) }.getOrElse(jvmBigInteger)

  /** Decode `[min, max]` from an `Int$Meta(Interval(Bounded(min), Bounded(max)))` meta value — the opaque domain
    * [[GroundValue]] the channel carries (an `Int$Meta` wrapper around the two-endpoint `Interval`, each endpoint
    * itself a `Bound` around a `Direct` big integer). The backend owns the `Int` domain, so it unwraps the `$Meta`
    * structure here; the channel and reconcile pass never inspect it.
    *
    * [[None]] for any other shape, which the caller treats as ⊤. That covers three distinct honest answers, all of
    * which a bignum is the right layout for:
    *
    *   - `Int$Meta(whole)` — the *stated* top of the range domain (a value nothing bounds, e.g. a number parsed from
    *     input), which is just the both-endpoints-unbounded case of the next one;
    *   - a **half-open** interval, `Interval(Bounded(lo), Unbounded)` or its mirror — a value bounded on one side only,
    *     which no fixed width contains however tight the bounded side is;
    *   - a future domain, or a malformed meta. A domain is recognised by its meta structure's name, `Int$Meta`, never
    *     by shape: a `String`'s size meta has exactly the shape of an `Int`'s range.
    *
    * A stated top and an absent meta therefore agree here; they differ only in what the meta channel itself may
    * conclude (docs/meta.md §2.1).
    */
  def intervalBounds(meta: GroundValue): Option[(BigInt, BigInt)] =
    meta match {
      case GroundValue.Structure(fqn, Seq(GroundValue.Structure(_, Seq(lo, hi), _)), _) if isIntMeta(fqn) =>
        (boundedBigInt(lo), boundedBigInt(hi)) match {
          case (Some(a), Some(b)) => Some((a, b))
          case _                  => None
        }
      case _ => None
    }

  /** The meta structure of the tracked `Int`, `Int$Meta` in `eliot.lang.Int`. The qualifier is ignored: a constructed
    * structure's head may carry either the type or the value namespace.
    */
  private def isIntMeta(fqn: ValueFQN): Boolean =
    fqn.moduleName == intModuleName && fqn.name.name == "Int" + MetaConstructorDesugarer.metaTypeSuffix

  /** The big integer inside a `Bounded(Direct(n))` endpoint, or [[None]] for an `Unbounded` one (a nullary structure,
    * so it never matches the single-argument shape) or any other value.
    */
  private def boundedBigInt(gv: GroundValue): Option[BigInt] = gv match {
    case GroundValue.Structure(_, Seq(GroundValue.Direct(Literal.IntegerValue(value), _)), _) => Some(value)
    case _                                                                                    => None
  }
}
