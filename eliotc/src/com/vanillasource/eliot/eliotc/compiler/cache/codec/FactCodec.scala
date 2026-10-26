package com.vanillasource.eliot.eliotc.compiler.cache.codec

import java.io.{DataInput, DataOutput}
import java.math.BigInteger
import java.net.URI
import java.nio.file.{Path, Paths}
import scala.collection.mutable
import scala.compiletime.{erasedValue, summonFrom, summonInline}
import scala.deriving.Mirror

/** An explicit binary encoding for one type, written and read field by field.
  *
  * This is how the incremental cache persists facts (`docs/incremental-compilation.md` §13), in place of the Java
  * serialization it replaced. Two properties are the point, and both are lost the moment encoding is done reflectively
  * or by an `ObjectOutputStream`:
  *
  *   - **Nothing is written that the reader does not need.** A codec emits its own fields; the type is known from the
  *     position in the encoding, so no class descriptor — no class name, no `serialVersionUID`, no field names or
  *     types — appears in the data. Per-stream class descriptors are one of the two terms in the 7.5× / 17× per-entry
  *     blowups measured in §5 and §12.
  *   - **A type with no codec is a compile error, never a silent encoding.** There is no `FactCodec` for a function or
  *     an array, so a value that cannot satisfy the cache's equality-stability invariant (§2,
  *     `read(write(v)) == recompute(v)`) cannot be encoded by accident. That is exactly the defect class of §3.1
  *     (`SemValue.VNative`'s lambda serializes fine and never compares equal) and §4 (`ClassFile`'s array compares by
  *     reference): a reflective walker reproduces both silently, while a missing given cannot.
  *
  * Instances for product and sum types come from [[FactCodec.derived]] via the compiler's `Mirror`, so a case class
  * costs one line and states nothing about its own shape. Every instance is named rather than auto-derived: a type
  * that participates in a reference cycle (`Expression`, `SemValue`, `GroundValue` are all deeply recursive) would
  * otherwise expand its own derivation forever, and the named instance is also what makes coverage a list one can
  * read.
  *
  * Integers are varint-encoded, so the small values that dominate real data (collection sizes, sum-type tags, source
  * positions) cost one byte instead of four.
  *
  * **Structure sharing is a property of the [[Output]], not of the codecs.** A derived codec writes every product and
  * sum through [[Output.shared]]; what that does with a repeated value is the sink's decision. [[Output.Plain]] writes
  * every value out in full — independent frames, the §5 layout, which measured 4.89× the alternative.
  * [[ContentAddressedOutput]] makes each an object of its own and refers to it by offset; that is the one the cache
  * runs on, and it deduplicates across runs as well as within one while letting a reader chase only what it is asked
  * for.
  */
trait FactCodec[A] {

  /** Write `value` to `out`. Must write exactly what [[read]] consumes, and nothing that identifies the type. */
  def write(out: FactCodec.Output, value: A): Unit

  /** Read back a value written by [[write]] from the same position in the stream. */
  def read(in: FactCodec.Input): A
}

object FactCodec {

  def apply[A](using codec: FactCodec[A]): FactCodec[A] = codec

  /** A sink for encoded values. Every product, sum and shared leaf is written through [[Output.shared]], which is the
    * **one** place a repeated value may be collapsed (`docs/incremental-compilation.md` §16).
    */
  trait Output {

    /** Where a codec's own bytes go. Not necessarily the same sink for the whole encoding: an implementation that
      * carves the value into separate objects redirects this while a body is being written.
      */
    def raw: DataOutput

    /** Write `value`'s body, or a reference to an equal one already written. */
    def shared(value: Any)(body: => Unit): Unit
  }

  object Output {

    /** Writes every value out in full — independent frames, no sharing at all (the §5 layout). */
    final class Plain(val raw: DataOutput) extends Output {
      override def shared(value: Any)(body: => Unit): Unit = body
    }

  }

  /** The reading counterpart of [[Output]]. An implementation must be paired with the one that wrote the bytes. */
  trait Input {

    /** Where a codec's own bytes come from — redirected, like [[Output.raw]], while a separately stored object is
      * being read.
      */
    def raw: DataInput

    /** Read a value's body, or resolve the reference standing in for one already read.
      *
      * `reader` identifies *who* is reading, and an implementation that caches by position must key on it as well.
      * Two types whose encodings coincide legitimately share one stored object — the bytes are the same and each
      * reader supplies its own codec — so position alone does not determine what a decoded value is. `Sourced[String]`
      * and `Sourced[Token]` are the case that proves it: same class, same bytes, different types.
      */
    def shared[A](reader: AnyRef)(body: => A): A
  }

  object Input {

    /** Reads what [[Output.Plain]] wrote: every value in full. */
    final class Plain(val raw: DataInput) extends Input {
      override def shared[A](reader: AnyRef)(body: => A): A = body
    }

  }

  /** Encode a single value to an independent byte array, sharing nothing. The real store is
    * [[ContentAddressedOutput]]; this is the unshared counterpart the conformance harness measures against, and the
    * simplest way to round-trip one value in a test.
    */
  def toBytes[A](value: A)(using codec: FactCodec[A]): Array[Byte] = {
    val bytes = new java.io.ByteArrayOutputStream()
    val out   = new java.io.DataOutputStream(bytes)
    codec.write(new Output.Plain(out), value)
    out.flush()
    bytes.toByteArray
  }

  def fromBytes[A](bytes: Array[Byte])(using codec: FactCodec[A]): A =
    codec.read(new Input.Plain(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))))

  // --- leaves -------------------------------------------------------------------------------------------------

  given FactCodec[Int] = instance((out, value) => writeVarInt(out.raw, value), in => readVarInt(in.raw))

  given FactCodec[Long] = instance((out, value) => writeVarLong(out.raw, value), in => readVarLong(in.raw))

  given FactCodec[Boolean] = instance((out, value) => out.raw.writeBoolean(value), _.raw.readBoolean())

  given FactCodec[Char] = instance((out, value) => writeVarInt(out.raw, value.toInt), in => readVarInt(in.raw).toChar)

  given FactCodec[Double] = instance((out, value) => out.raw.writeDouble(value), _.raw.readDouble())

  given FactCodec[Byte] = instance((out, value) => out.raw.writeByte(value.toInt), _.raw.readByte())

  given FactCodec[Unit] = instance((_, _) => (), _ => ())

  /** Shared, like every product: a compiler's facts repeat the same handful of names, module paths and file URIs
    * thousands of times, and a repeated string that costs its bytes again is the single largest term in an unshared
    * encoding (measured: sharing the *leaves* as well as the products is most of the win, not a refinement of it).
    */
  given FactCodec[String] = shared(
    instance(
      (out, value) => {
        val bytes = value.getBytes("UTF-8")
        writeVarInt(out.raw, bytes.length)
        out.raw.write(bytes)
      },
      in => {
        val bytes = new Array[Byte](readVarInt(in.raw))
        in.raw.readFully(bytes)
        new String(bytes, "UTF-8")
      }
    )
  )

  given FactCodec[BigInteger] = instance(
    (out, value) => {
      val bytes = value.toByteArray
      writeVarInt(out.raw, bytes.length)
      out.raw.write(bytes)
    },
    in => {
      val bytes = new Array[Byte](readVarInt(in.raw))
      in.raw.readFully(bytes)
      new BigInteger(bytes)
    }
  )

  given FactCodec[BigInt] = instance(
    (out, value) => FactCodec[BigInteger].write(out, value.bigInteger),
    in => BigInt(FactCodec[BigInteger].read(in))
  )

  /** A `URI` is encoded as its string form. Note that [[com.vanillasource.eliot.eliotc.source.content.Sourced]]
    * compares URIs by scheme-specific-part, so a round-tripped URI is equal under that comparison too.
    */
  given FactCodec[URI] = shared(
    instance((out, value) => FactCodec[String].write(out, value.toString), in => URI.create(FactCodec[String].read(in)))
  )

  given FactCodec[java.time.Instant] = instance(
    (out, value) => { writeVarLong(out.raw, value.getEpochSecond); writeVarInt(out.raw, value.getNano) },
    in => java.time.Instant.ofEpochSecond(readVarLong(in.raw), readVarInt(in.raw).toLong)
  )

  given FactCodec[java.io.File] = instance(
    (out, value) => FactCodec[String].write(out, value.getPath),
    in => new java.io.File(FactCodec[String].read(in))
  )

  /** A `Path` is encoded as its string form: a `java.nio.file.Path` carries a reference to the filesystem that made
    * it, which is not a thing a cache can store or would want back.
    */
  given FactCodec[Path] = shared(
    instance((out, value) => FactCodec[String].write(out, value.toString), in => Paths.get(FactCodec[String].read(in)))
  )

  // --- containers ---------------------------------------------------------------------------------------------

  given [A](using codec: FactCodec[A]): FactCodec[Option[A]] = instance(
    (out, value) =>
      value match {
        case Some(a) => out.raw.writeBoolean(true); codec.write(out, a)
        case None    => out.raw.writeBoolean(false)
      },
    in => Option.when(in.raw.readBoolean())(codec.read(in))
  )

  given [A](using codec: FactCodec[A]): FactCodec[Seq[A]] = collection(_.toSeq)

  given [A](using codec: FactCodec[A]): FactCodec[List[A]] = collection(_.toList)

  given [A](using codec: FactCodec[A]): FactCodec[Vector[A]] = collection(_.toVector)

  given [A](using codec: FactCodec[A]): FactCodec[Set[A]] = collection(_.toSet)

  /** Shared, unlike the other collections: a file's dictionary rides on every value of the file, and written inline it
    * was 83% of the store (`docs/compiler-performance.md` §9). Sharing the other collections measured no gain.
    */
  given [K, V](using keyCodec: FactCodec[K], valueCodec: FactCodec[V]): FactCodec[Map[K, V]] = shared(
    instance(
      (out, value) => {
        writeVarInt(out.raw, value.size)
        value.foreach { case (k, v) => keyCodec.write(out, k); valueCodec.write(out, v) }
      },
      in => Seq.fill(readVarInt(in.raw))(keyCodec.read(in) -> valueCodec.read(in)).toMap
    )
  )

  given [A, B](using leftCodec: FactCodec[A], rightCodec: FactCodec[B]): FactCodec[(A, B)] = instance(
    (out, value) => { leftCodec.write(out, value._1); rightCodec.write(out, value._2) },
    in => (leftCodec.read(in), rightCodec.read(in))
  )

  private def collection[A, C <: Iterable[A]](build: Seq[A] => C)(using codec: FactCodec[A]): FactCodec[C] = instance(
    (out, value) => { writeVarInt(out.raw, value.size); value.foreach(codec.write(out, _)) },
    in => build(Seq.fill(readVarInt(in.raw))(codec.read(in)))
  )

  // --- derivation ---------------------------------------------------------------------------------------------

  /** Derive a codec for a product (write each field in declaration order) or a sum (write a varint tag from the
    * mirror's ordinal, then the selected case). Both go through [[Output.shared]], so the stream decides whether a
    * repeated value costs its bytes or a back-reference.
    *
    * The element codecs are held in a `lazy val` so a recursive type — a sum whose cases contain the sum again — does
    * not force its own instance while that instance is still being initialised. The instance itself must still be
    * *named* (`given x: FactCodec[T] = FactCodec.derived`); an auto-derived given would re-expand `derived` at every
    * recursive occurrence and never terminate at compile time.
    */
  inline def derived[A](using mirror: Mirror.Of[A]): FactCodec[A] =
    inline mirror match {
      case sum: Mirror.SumOf[A]         => sumCodec(sum, caseCodecs[mirror.MirroredElemTypes])
      case product: Mirror.ProductOf[A] => productCodec(product, summonCodecs[mirror.MirroredElemTypes])
    }

  private def sumCodec[A](sum: Mirror.SumOf[A], cases: => List[FactCodec[?]]): FactCodec[A] = {
    lazy val caseCodecs = cases

    val reader = new Object

    instance(
      (out, value) =>
        out.shared(value) {
          val tag = sum.ordinal(value)
          writeVarInt(out.raw, tag)
          caseCodecs(tag).asInstanceOf[FactCodec[A]].write(out, value)
        },
      in => in.shared(reader)(caseCodecs(readVarInt(in.raw)).asInstanceOf[FactCodec[A]].read(in))
    )
  }

  private def productCodec[A](product: Mirror.ProductOf[A], fields: => List[FactCodec[?]]): FactCodec[A] = {
    lazy val fieldCodecs = fields

    val reader = new Object

    instance(
      (out, value) =>
        out.shared(value) {
          value
            .asInstanceOf[Product]
            .productIterator
            .zip(fieldCodecs.iterator)
            .foreach { case (field, codec) => codec.asInstanceOf[FactCodec[Any]].write(out, field) }
        },
      in => in.shared(reader)(product.fromProduct(Tuple.fromArray(fieldCodecs.map(_.read(in)).toArray)))
    )
  }

  private inline def summonCodecs[T <: Tuple]: List[FactCodec[?]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[FactCodec[t]] :: summonCodecs[ts]
    }

  /** The codecs for a sum's cases. A case gets its codec **built here from its own `Mirror`** rather than summoned,
    * so a sealed hierarchy costs *one* named instance for its root instead of one per case. That matters more than
    * convenience: an `enum` case cannot carry a `derives` clause at all, so requiring an instance per case would make
    * "the decision lives on the type" impossible to express for every sum in the fact model.
    *
    * An explicitly written instance still wins where one exists — that is what lets a case whose derivation would be
    * wrong (a reference-compared array, an untyped slot) be hand-written and picked up here.
    */
  private inline def caseCodecs[T <: Tuple]: List[FactCodec[?]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => caseCodec[t] :: caseCodecs[ts]
    }

  private inline def caseCodec[T]: FactCodec[T] =
    summonFrom {
      case codec: FactCodec[T]           => codec
      case product: Mirror.ProductOf[T]  => productCodec(product, summonCodecs[product.MirroredElemTypes])
    }

  /** A codec for a sum case that is a plain `object` rather than a `case object`, and so has no `Mirror`. It writes
    * nothing: the sum's tag already identifies it.
    */
  def singleton[A](value: A): FactCodec[A] = instance((_, _) => (), _ => value)

  /** Route a codec through the stream's sharing table, so an equal value already written costs a back-reference. */
  private def shared[A](codec: FactCodec[A]): FactCodec[A] = {
    val reader = new Object

    instance((out, value) => out.shared(value)(codec.write(out, value)), in => in.shared(reader)(codec.read(in)))
  }

  def instance[A](writer: (Output, A) => Unit, reader: Input => A): FactCodec[A] =
    new FactCodec[A] {
      override def write(out: Output, value: A): Unit = writer(out, value)
      override def read(in: Input): A                 = reader(in)
    }

  // --- varints ------------------------------------------------------------------------------------------------

  /** Zig-zag encoded so small negative numbers stay short, then written seven bits at a time, low group first, with
    * the high bit marking continuation.
    */
  private[cache] def writeVarInt(out: DataOutput, value: Int): Unit = writeVarLong(out, value.toLong)

  private[cache] def readVarInt(in: DataInput): Int = readVarLong(in).toInt

  private[cache] def writeVarLong(out: DataOutput, value: Long): Unit = {
    var remaining = (value << 1) ^ (value >> 63) // zig-zag
    while ((remaining & ~0x7fL) != 0) {
      out.writeByte(((remaining & 0x7f) | 0x80).toInt)
      remaining >>>= 7
    }
    out.writeByte(remaining.toInt)
  }

  private[cache] def readVarLong(in: DataInput): Long = {
    var shift  = 0
    var result = 0L
    var byte   = in.readByte().toInt
    while ((byte & 0x80) != 0) {
      result |= (byte & 0x7fL) << shift
      shift += 7
      byte = in.readByte().toInt
    }
    result |= byte.toLong << shift
    (result >>> 1) ^ -(result & 1) // un-zig-zag
  }
}
