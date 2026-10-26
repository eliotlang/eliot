package com.vanillasource.eliot.eliotc.compiler.cache.codec

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.util.IdentityHashMap

/** Computes a value's [[ObjectId]] without storing anything — what the equality cutoff asks
  * (`docs/incremental-compilation.md` §13 step 4).
  *
  * Deciding whether a recomputed fact is the one the cache holds used to mean materialising the stored side and
  * comparing field by field. Here only the *recomputed* side is touched: it is hashed exactly as
  * [[ContentAddressedOutput]] would have hashed it, and the answer is 16 bytes against 16 bytes.
  *
  * This is simpler than the storing writer for a reason worth stating: an id has no positions in it. The storing
  * writer keeps two buffers per object because a child contributes its *offset* to the stored body and its *id* to
  * the hash; with nothing stored there is one buffer, and a child contributes its id straight into its parent.
  *
  * Memoized by identity, as [[ContentAddressedOutput]] is and for the same reason.
  */
final class ObjectIdOutput extends FactCodec.Output {
  private val ids                  = new IdentityHashMap[Any, ObjectId]()
  private var image                = new ByteArrayOutputStream()
  private var current: DataOutput  = new DataOutputStream(image)

  override def raw: DataOutput = current

  override def shared(value: Any)(body: => Unit): Unit =
    image.write(Option(ids.get(value)).getOrElse(remember(value, hash(body))).bytes)

  /** The id `value` would be stored under. */
  def identify[A](value: A)(using codec: FactCodec[A]): ObjectId = hash(codec.write(this, value))

  /** Not `computeIfAbsent`: hashing a value hashes its children, which inserts into this very map, and a map may not
    * be modified while it is deciding where to put an entry.
    */
  private def remember(value: Any, id: ObjectId): ObjectId = {
    ids.put(value, id)
    id
  }

  private def hash(body: => Unit): ObjectId = {
    val outerImage   = image
    val outerCurrent = current

    image = new ByteArrayOutputStream()
    current = new DataOutputStream(image)

    val id =
      try {
        body
        ObjectId.of(image.toByteArray)
      } finally {
        image = outerImage
        current = outerCurrent
      }

    id
  }
}
