package com.vanillasource.eliot.eliotc.processor

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec

import scala.util.hashing.MurmurHash3

/** The handle by which a fact is stored and looked up in the running engine.
  *
  * Keys are hashed constantly — the engine holds every fact, every dependency edge and every in-flight computation in a
  * map or set keyed by them, so one fact generation hashes its key a handful of times over — and a key is an ordinary
  * case class, whose synthesized hash walks the whole structure on every call. A key is immutable, so that walk is done
  * once per instance and remembered.
  *
  * The hash is `final` here precisely so that the case classes extending it do **not** synthesize their own, which
  * would override this one and quietly restore the recomputation.
  *
  * It is the synthesized hash seeded with the key's **class name**, because the synthesized one tells classes apart
  * only by their `productPrefix`, and nearly every key is a class named `Key` nested in its fact's companion:
  * `ResolvedValue.Key(vfqn, platform)` and `UnifiedModuleValue.Key(vfqn, platform)` hashed alike, and so did nine more
  * phases of the same name, so most of a build's keys sat in collision buckets every lookup scanned with `equals`. The
  * name, not the `Class`'s identity hash, so that a key hashes the same in every run and anything ordered by hash stays
  * reproducible. `CompilerFactKeyTest` guards both properties.
  */
trait CompilerFactKey[V] extends Product {

  /** How this fact's value is persisted in the incremental cache: its explicit encoding, or `None` when the fact is
    * deliberately **not** persisted.
    *
    * Abstract with no default, because both possible defaults are wrong (`docs/incremental-compilation.md` §6 Step 1).
    * Defaulting to persist is how a value that is *serializable but never equal* — a `SemValue` closure — got written
    * and then reported "value differs" on every warm build (§3.1). Defaulting to decline would silently make a *leaf*
    * unvalidatable: an edge-less declining `FileStat` disables the whole cache while everything still compiles and
    * passes. So every fact states it once, and a new fact type that states nothing does not compile.
    *
    * `None` is a decline, not a failure: the fact still participates in change detection through its dependency edges
    * (the structural drill of §1), it just cannot be handed back from cache. It must be reserved for a value that
    * genuinely cannot satisfy the equality-stability invariant (§2) — today only the two `SemValue`-bearing binding
    * facts.
    */
  def valueCodec: Option[FactCodec[V]]

  /** Neither volatile nor guarded, and zero means "not computed yet": the computation is pure and idempotent, so a race
    * can only recompute the same value, and a key whose hash genuinely is zero merely recomputes it every time. This is
    * what [[java.lang.String]] does.
    *
    * Transient because keys are serialized into the on-disk fact cache and a memo is not part of a key's value: a key
    * read back from the cache simply computes its hash when first asked, as any other key does.
    */
  @transient private var cachedHashCode: Int = 0

  final override def hashCode(): Int = {
    if (cachedHashCode == 0) {
      val hash = MurmurHash3.productHash(this, getClass.getName.hashCode)

      cachedHashCode = if (hash == 0) 1 else hash
    }

    cachedHashCode
  }
}

/** Type-level function to extract the fact type from a key type */
type FactOf[K <: CompilerFactKey[?]] = K match {
  case CompilerFactKey[f] => f
}
