package com.vanillasource.eliot.eliotc.compiler.cache.codec

import java.io.{ByteArrayInputStream, DataInput, DataInputStream}
import scala.collection.mutable

/** The read half of the content-addressed store: decodes a value from the body region by following byte offsets
  * (`docs/incremental-compilation.md` §17).
  *
  * Reading is **demand-driven all the way down**. Nothing is decoded because it is present, only because something
  * asked for it: a value is read from its own offset, and each child is read only when its codec reaches the reference.
  * That is what makes the load side cheap on a warm build, where validation mostly compares ids and never materialises
  * the values behind them.
  *
  * A decoded object is memoized, so a subtree referred to from a thousand places is decoded once and shared in memory
  * as it was on disk. The memo is keyed by offset **and reader**, because an offset does not determine a type: two
  * types whose encodings coincide legitimately share one stored object, and `Sourced[String]` / `Sourced[Token]` are
  * enough to make that routine rather than exotic.
  *
  * **One input serves every thread that materialises a stored fact**, and a parallel run materialises many at once (a
  * warm build whose jar is gone reads every module's bytecode back on threads of their own). Its cursor and memo are
  * plain mutable state, so a top-level [[read]] holds the input's monitor for the whole decode; the nested reads it
  * makes are on the same thread and re-enter. A decode reads only bytes, never a fact, so it waits on no other thread
  * while holding it.
  *
  * @param bodies
  *   the whole body region; offsets index into it directly
  */
final class ContentAddressedInput(bodies: Array[Byte]) extends FactCodec.Input {
  private val decoded = mutable.HashMap.empty[(Int, AnyRef), Any]
  private var current = ContentAddressedInput.streamAt(bodies, 0)

  override def raw: DataInput = current

  override def shared[A](reader: AnyRef)(body: => A): A = {
    val offset = FactCodec.readVarInt(current)
    val key    = (offset, reader)

    decoded.getOrElse(key, remember(key, at(offset)(body))).asInstanceOf[A]
  }

  /** Decode the object stored at `offset` — the store's entry point, as opposed to [[FactCodec.Input.shared]], which is
    * what codecs call on their way down.
    */
  def read[A](offset: Int)(using codec: FactCodec[A]): A = synchronized(at(offset)(codec.read(this)))

  /** Not `getOrElseUpdate`: decoding an object decodes its children, which inserts into this very map, and a
    * `mutable.HashMap` may not be modified while it is deciding where to put an entry.
    */
  private def remember[A](at: (Int, AnyRef), value: A): A = {
    decoded.update(at, value)
    value
  }

  private def at[A](offset: Int)(body: => A): A = {
    val previous = current

    current = ContentAddressedInput.streamAt(bodies, offset)
    try body
    finally current = previous
  }
}

object ContentAddressedInput {
  private def streamAt(bodies: Array[Byte], offset: Int): DataInput =
    new DataInputStream(new ByteArrayInputStream(bodies, offset, bodies.length - offset))
}
