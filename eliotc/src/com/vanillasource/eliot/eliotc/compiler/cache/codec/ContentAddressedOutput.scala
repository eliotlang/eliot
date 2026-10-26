package com.vanillasource.eliot.eliotc.compiler.cache.codec

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream, OutputStream}
import java.util.IdentityHashMap
import scala.collection.mutable

/** A [[FactCodec.Output]] that carves the encoding into separately addressed objects instead of one stream — the write
  * half of the content-addressed store (`docs/incremental-compilation.md` §13, §17).
  *
  * Every value written through [[FactCodec.Output.shared]] becomes its own object: its body is encoded into a nested
  * buffer with its children already reduced to references, and the parent records only the reference. An object whose
  * content is already present is not stored again, so structurally identical subtrees from unrelated facts collapse
  * whether or not they are the same instance — and, unlike an in-stream back-reference, whether or not they were
  * written by the same run.
  *
  * **Two identities, deliberately, and the distinction is the whole design (§17):**
  *
  *   - an object's [[ObjectId]] is a Merkle hash — its own bytes, with each child contributing *its id*, never a
  *     position. It therefore means the same thing in any store, which is what lets a recomputed value be compared to
  *     a stored one by 16 bytes without reading the stored side, and what lets dedup span runs.
  *   - an object's on-disk **reference is its byte offset** in the append-only body region, written as a varint. §13
  *     called for the id itself; measured, that costs 5.72× what the shared stream does, because 79% of a fact graph's
  *     objects are referred to exactly once and a 16-byte reference dwarfs the ~15-byte body it points at. An offset
  *     is stable for the same reason a sequence number is — the region only ever grows — and it is *self-locating*, so
  *     no table has to be written or read to resolve one.
  *
  * **The store is untyped, deliberately.** Codecs write nothing that identifies a type, so distinct types routinely
  * encode to identical bytes — a `URI` and its own string form, `Sourced[String]` and `Sourced[Token]`. Such values
  * share one stored object, and that is sound: the reader always supplies the codec, so the same bytes decode
  * correctly for each. What must *not* be shared is a reader's cache of decoded values, which is why
  * [[ContentAddressedInput]] keys its memo by codec as well as offset.
  *
  * Encoding is memoized by **identity**, so an instance written from many places is encoded and digested once. An equal
  * but distinct instance is encoded again, comes to the same id, and so to the bytes already stored: the memo saves
  * work, and storing nothing twice is the id's job. Memoizing by value instead hashed and compared the value
  * structurally at every level of every tree, so a deep value cost its size once per ancestor, which was 29% of a cold
  * build's CPU (`docs/compiler-performance.md` §8).
  *
  * @param existing
  *   where the objects already in the store live, so an encoding reuses them instead of appending them again
  * @param firstOffset
  *   the offset the body region continues at, i.e. its current length
  */
final class ContentAddressedOutput(existing: Map[ObjectId, Int], firstOffset: Int) extends FactCodec.Output {
  private val added   = new ByteArrayOutputStream()
  private val offsets = mutable.HashMap.from(existing)
  private val placed  = new IdentityHashMap[Any, ContentAddressedOutput.Placed]()
  private var current = ContentAddressedOutput.Frame()

  override def raw: DataOutput = current.tee

  override def shared(value: Any)(body: => Unit): Unit = {
    val known = Option(placed.get(value)).getOrElse(remember(value, store(body)))

    FactCodec.writeVarInt(current.bodyOut, known.offset)
    current.hash.write(known.id.bytes)
  }

  /** Encode one value as an object of its own and answer what it is and where it now lives — the store's entry point,
    * as opposed to [[FactCodec.Output.shared]], which is what codecs call on their way down. A value whose content is
    * already in the store is not appended again, so this may well point back into what was there before.
    */
  def write[A](value: A)(using codec: FactCodec[A]): ContentAddressedOutput.Placed = store(codec.write(this, value))

  /** Everything this encoding adds to the body region, to be appended at `firstOffset`. Objects found already present
    * contribute nothing, which is what makes a save write only what is new.
    */
  def appendedBytes: Array[Byte] = added.toByteArray

  /** Where every object now lives, the pre-existing ones included — the dedup index the next run starts from. */
  def objectOffsets: Map[ObjectId, Int] = offsets.toMap

  /** Not `computeIfAbsent`: placing a value places its children, which inserts into this very map, and a map may not
    * be modified while it is deciding where to put an entry.
    */
  private def remember(value: Any, known: ContentAddressedOutput.Placed): ContentAddressedOutput.Placed = {
    placed.put(value, known)
    known
  }

  private def store(body: => Unit): ContentAddressedOutput.Placed = {
    val frame    = ContentAddressedOutput.Frame()
    val previous = current

    current = frame
    try body
    finally current = previous

    val id     = ObjectId.of(frame.hash.toByteArray)
    val offset = offsets.get(id) match {
      case Some(existing) => existing
      case None           =>
        val appended = firstOffset + added.size()

        offsets.update(id, appended)
        added.write(frame.body.toByteArray)
        appended
    }

    ContentAddressedOutput.Placed(id, offset)
  }
}

object ContentAddressedOutput {

  /** Start an encoding against an empty store. */
  def empty: ContentAddressedOutput = new ContentAddressedOutput(Map.empty, 0)

  /** What a value is, and where it ended up. */
  case class Placed(id: ObjectId, offset: Int)

  /** One object's two sinks, written in lockstep: `body` receives the bytes that are stored, `hash` the bytes the
    * object's identity is taken over. They differ in exactly one place — a child contributes its *offset* to the body
    * and its *id* to the hash — which is what keeps an id independent of where anything happens to sit.
    */
  private final class Frame {
    val body: ByteArrayOutputStream = new ByteArrayOutputStream()
    val hash: ByteArrayOutputStream = new ByteArrayOutputStream()
    val bodyOut: DataOutput         = new DataOutputStream(body)
    val tee: DataOutput             = new DataOutputStream(new Frame.Tee(body, hash))
  }

  private object Frame {
    def apply(): Frame = new Frame

    /** Writes every byte to both sinks. */
    private final class Tee(first: OutputStream, second: OutputStream) extends OutputStream {
      override def write(byte: Int): Unit = {
        first.write(byte)
        second.write(byte)
      }

      override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = {
        first.write(bytes, offset, length)
        second.write(bytes, offset, length)
      }
    }
  }
}
