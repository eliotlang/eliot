package com.vanillasource.eliot.eliotc.jvm.classgen.asm

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.defaultSystemPackage
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IntRepresentationTest extends AnyFlatSpec with Matchers {

  private def jvm(name: String): ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Int"), QualifiedName(name, Qualifier.Default))

  private val bigIntType: GroundValue =
    GroundValue.Structure(
      ValueFQN(ModuleName(defaultSystemPackage, "BigInteger"), QualifiedName("BigInteger", Qualifier.Type)),
      Seq.empty,
      GroundValue.Type
    )

  private def boundFQN(name: String): ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Bound"), QualifiedName(name, Qualifier.Default))

  private def bounded(value: GroundValue): GroundValue =
    GroundValue.Structure(boundFQN("Bounded"), Seq(value), GroundValue.Type)

  private val unboundedEndpoint: GroundValue =
    GroundValue.Structure(boundFQN("Unbounded"), Seq.empty, GroundValue.Type)

  /** The meta the channel produces for an `Int` node: `Int$Meta(Interval(start, end))` — the `$Meta` wrapper around the
    * `Interval` the range domain is stated in, whose two endpoints are each a [[Bound]].
    * (`intervalBounds`/`representationFor` decode this whole shape, not a bare `Interval`.)
    */
  private def intervalOf(start: GroundValue, end: GroundValue): GroundValue =
    intMeta(
      GroundValue.Structure(
        ValueFQN(ModuleName(defaultSystemPackage, "Interval"), QualifiedName("Interval", Qualifier.Default)),
        Seq(start, end),
        GroundValue.Type
      )
    )

  /** A closed range `[min, max]` — both endpoints `Bounded`. */
  private def interval(min: BigInt, max: BigInt): GroundValue =
    intervalOf(bounded(GroundValue.Direct(min, bigIntType)), bounded(GroundValue.Direct(max, bigIntType)))

  /** The half-open range `[min, ∞)` — bounded below, open above. No fixed width contains it. */
  private def atLeast(min: BigInt): GroundValue =
    intervalOf(bounded(GroundValue.Direct(min, bigIntType)), unboundedEndpoint)

  /** The half-open range `(-∞, max]` — open below, bounded above. */
  private def atMost(max: BigInt): GroundValue =
    intervalOf(unboundedEndpoint, bounded(GroundValue.Direct(max, bigIntType)))

  /** The range domain's *stated* top: `Int$Meta(whole)`, a value nothing bounds — an interval open at both ends, so it
    * is the half-open case twice over rather than a shape of its own. Distinct from an absent meta as a channel
    * verdict, but the same layout question — see [[IntRepresentation.intervalBounds]].
    */
  private val whole: GroundValue = intervalOf(unboundedEndpoint, unboundedEndpoint)

  private def intMeta(range: GroundValue): GroundValue =
    GroundValue.Structure(
      ValueFQN(ModuleName(defaultSystemPackage, "Int"), QualifiedName("Int$Meta", Qualifier.Default)),
      Seq(range),
      GroundValue.Type
    )

  /** A `String`'s size meta: the same shape as an `Int`'s range, under another name. */
  private def stringMeta(intMetaValue: GroundValue): GroundValue = intMetaValue match {
    case GroundValue.Structure(_, arguments, valueType) =>
      GroundValue.Structure(
        ValueFQN(ModuleName(defaultSystemPackage, "String"), QualifiedName("String$Meta", Qualifier.Default)),
        arguments,
        valueType
      )
    case other                                         => other
  }

  "widthOf" should "pick JvmByte for a range within the signed byte bounds" in {
    IntRepresentation.widthOf(0, 127) shouldBe jvm("JvmByte")
  }

  it should "pick JvmByte for the full signed byte range" in {
    IntRepresentation.widthOf(-128, 127) shouldBe jvm("JvmByte")
  }

  it should "step up to JvmShort just past the byte max" in {
    IntRepresentation.widthOf(0, 128) shouldBe jvm("JvmShort")
  }

  it should "step up to JvmShort just past the byte min" in {
    IntRepresentation.widthOf(-129, 0) shouldBe jvm("JvmShort")
  }

  it should "pick JvmShort at the signed short bounds" in {
    IntRepresentation.widthOf(-32768, 32767) shouldBe jvm("JvmShort")
  }

  it should "step up to JvmInt just past the short max" in {
    IntRepresentation.widthOf(0, 32768) shouldBe jvm("JvmInt")
  }

  it should "pick JvmInt at the signed 32-bit bounds" in {
    IntRepresentation.widthOf(-2147483648L, 2147483647L) shouldBe jvm("JvmInt")
  }

  it should "step up to JvmLong just past the 32-bit max" in {
    IntRepresentation.widthOf(0, 2147483648L) shouldBe jvm("JvmLong")
  }

  it should "pick JvmLong at the signed 64-bit bounds" in {
    IntRepresentation.widthOf(Long.MinValue, Long.MaxValue) shouldBe jvm("JvmLong")
  }

  it should "fall back to JvmBigInteger just past the 64-bit max" in {
    IntRepresentation.widthOf(0, BigInt(Long.MaxValue) + 1) shouldBe jvm("JvmBigInteger")
  }

  it should "fall back to JvmBigInteger just past the 64-bit min" in {
    IntRepresentation.widthOf(BigInt(Long.MinValue) - 1, 0) shouldBe jvm("JvmBigInteger")
  }

  "representationFor" should "decode a present interval meta" in {
    IntRepresentation.representationFor(Some(interval(0, 200))) shouldBe jvm("JvmShort")
  }

  it should "be JvmBigInteger for an absent (top) meta" in {
    IntRepresentation.representationFor(None) shouldBe jvm("JvmBigInteger")
  }

  it should "be JvmBigInteger for a stated whole range" in {
    IntRepresentation.representationFor(Some(whole)) shouldBe jvm("JvmBigInteger")
  }

  "intervalBounds" should "read the endpoints of an Interval meta value" in {
    IntRepresentation.intervalBounds(interval(3, 9)) shouldBe Some((BigInt(3), BigInt(9)))
  }

  it should "be None for a non-interval structure" in {
    IntRepresentation.intervalBounds(GroundValue.Type) shouldBe None
  }

  it should "be None for a stated whole range" in {
    IntRepresentation.intervalBounds(whole) shouldBe None
  }

  it should "be None for another domain's meta of the same shape" in {
    IntRepresentation.intervalBounds(stringMeta(interval(5, 5))) shouldBe None
  }

  // A half-open interval is bounded on one side only, so no fixed width contains it however tight that side is —
  // the same ⊤ answer as the stated top `whole`, of which it is the one-sided case.
  it should "be None for a range open above" in {
    IntRepresentation.intervalBounds(atLeast(0)) shouldBe None
  }

  it should "be None for a range open below" in {
    IntRepresentation.intervalBounds(atMost(127)) shouldBe None
  }

  "representationFor" should "be JvmBigInteger for a range open above, however tight its lower endpoint" in {
    IntRepresentation.representationFor(Some(atLeast(0))) shouldBe jvm("JvmBigInteger")
  }

  it should "be JvmBigInteger for a range open below" in {
    IntRepresentation.representationFor(Some(atMost(127))) shouldBe jvm("JvmBigInteger")
  }
}
