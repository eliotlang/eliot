package com.vanillasource.eliot.eliotc.compiler.cache.codec

import java.security.MessageDigest

/** The identity of a stored object: a Merkle hash over what it encodes, with each child contributing its own id
  * (`docs/incremental-compilation.md` §13, §17).
  *
  * Nothing position-dependent enters it, which is what the store turns on:
  *
  *   - **equal ids mean equal values**, so an object is stored at most once — within a run and, because the id does not
  *     depend on anything run-specific, across runs too;
  *   - a recomputed value can be compared to a stored one by **16 bytes**, without reading the stored side at all — the
  *     equality cutoff that a warm build spends most of its validation in;
  *   - a **collision is a correctness fault**, not a slow path: two different values sharing an id would serve one in
  *     place of the other. That is why the digest is not truncated below 128 bits.
  */
final case class ObjectId(high: Long, low: Long) {

  /** The id as its 16 bytes, for hashing into a parent object. */
  def bytes: Array[Byte] = {
    val result = new Array[Byte](ObjectId.WIDTH)

    (0 until 8).foreach { index =>
      result(index) = (high >>> ((7 - index) * 8)).toByte
      result(index + 8) = (low >>> ((7 - index) * 8)).toByte
    }

    result
  }

  override def toString: String = f"$high%016x$low%016x"
}

object ObjectId {

  /** Bytes of digest kept, i.e. the id's width. */
  val WIDTH: Int = 16

  /** SHA-256 over the object's hash image, truncated to [[WIDTH]] bytes. */
  def of(image: Array[Byte]): ObjectId = {
    val hash = digest.get().digest(image)

    ObjectId(longAt(hash, 0), longAt(hash, 8))
  }

  /** One digest per thread, since a save asks for one per object, millions of them, and looking an algorithm up by name
    * costs a provider search each time. `digest` leaves it reset.
    */
  private val digest: ThreadLocal[MessageDigest] = ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))

  private def longAt(bytes: Array[Byte], offset: Int): Long =
    (0 until 8).foldLeft(0L)((acc, index) => (acc << 8) | (bytes(offset + index) & 0xffL))
}
