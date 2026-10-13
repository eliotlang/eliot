package com.vanillasource.eliot.eliotc.jvm.codec

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.vanillasource.eliot.eliotc.codec.LangFactCodecs
import com.vanillasource.eliot.eliotc.compiler.cache.codec.{
  ContentAddressedInput,
  ContentAddressedOutput,
  CoreFactCodecs,
  FactCodec,
  FactKeyCodecs
}
import com.vanillasource.eliot.eliotc.compiler.Compiler
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** What the codecs and the store must hold true for, checked against the facts of a real build
  * (`docs/incremental-compilation.md` §13–§18).
  *
  * Coverage is proven by the *compiler*, twice over, so nothing here has to check it. `CompilerFactKey.valueCodec` is
  * abstract with no default, so a fact type that states no persistence decision does not compile; and the structural
  * instances (`LangFactCodecs` and its layer peers) are named per type, so a field whose type has no encoding does not
  * compile either. What compilation cannot decide is answered here:
  *
  *   - **the law** (§2): `read(write(v))` must equal the value, through a plain frame and through the store. Round-trip
  *     inequality is what catches a field compared by reference (`ClassFile`'s `Array[Byte]`, §14); the other half of
  *     §2's defect class — a value that round-trips equal but *recomputes* different, i.e. `SemValue.VNative`'s lambda
  *     — cannot reach here at all, because no `FactCodec` exists for a function. That is the property the whole
  *     approach turns on: a reflective walker would encode both silently.
  *   - **the sharing**: the store must come out far below what the same facts cost as independent frames. That gap is
  *     the finding of §14 — structure sharing, not class descriptors, is what the size depends on — and the property
  *     §17's layout rests on, so it is worth a standing guard rather than a one-off measurement.
  *   - **the declines**: exactly the fact types that cannot be equality-stable, and no others, may decline.
  *   - **the key tag table**: complete for every key type a build materialises, since it cannot be compile-time
  *     complete (§16).
  */
class FactCodecConformanceTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val program =
    """def greet(name: String): String = "hello " ++ name
      |
      |def main uses Console: Unit = printLine(greet("world"))""".stripMargin

  private val expectedDeclines = Set("ContributedBinding", "NativeBinding")

  "the fact model" should "decline to persist only the values that cannot be equality-stable" in {
    withFacts(facts => IO.pure(facts.filter(_.key().valueCodec.isEmpty).map(simpleName).toSet shouldBe expectedDeclines))
  }

  "the explicit codecs" should "round-trip every fact they cover back to an equal value" in {
    withFacts(facts => IO.pure(facts.filterNot(roundTrips).map(simpleName).distinct shouldBe Nil))
  }

  /** The guard §16 asked for. `CompilerFactKey.valueCodec` is compile-time-complete for a fact's *value*; a key's own
    * codec cannot be stated that way, because decoding a dependency has no key to read it from. The tag table is
    * therefore a runtime map, and this is what makes an omission loud: every key type a real build materialises must
    * be registered. Only that direction is checkable here — a build exercises a subset of the fact model, so a
    * registration with no live key type is not evidence of anything.
    */
  "the key tag table" should "cover every fact key type a build materialises" in {
    withFacts(facts => IO.pure(facts.map(fact => FactKeyCodecs.nameOf(fact.key())).toSet -- keyCodecs.keySet shouldBe Set.empty))
  }

  it should "read back every key it registers" in {
    withFacts(facts => IO.pure(facts.map(_.key()).distinct.filterNot(roundTripsAsKey) shouldBe Nil))
  }

  private val keyCodecs: FactKeyCodecs.Registry = CoreFactCodecs.keyCodecs ++ LangFactCodecs.keyCodecs ++ JvmFactCodecs.keyCodecs

  private def roundTripsAsKey(key: CompilerFactKey[?]): Boolean = {
    val codec = keyCodecs(FactKeyCodecs.nameOf(key))

    FactCodec.fromBytes(FactCodec.toBytes(key)(using codec))(using codec) == key
  }

  /** The store the codecs are actually for (§17). Both properties are checked here because they are one design: the
    * objects are content-addressed so that equal subtrees collapse, and a fact is read back by following byte offsets
    * so that nothing is decoded unless something asks for it.
    *
    * The size is measured against the *shared stream*, not against Java, because §14 already established that as the
    * bar: it is the encoding with in-stream back-references, which is the most a single-stream format can share. A
    * store that beats it does so by deduplicating across the whole build rather than within one write.
    */
  it should "round-trip a build's facts through a content-addressed object store" in {
    withFacts { facts =>
      val encodable = facts.filter(_.key().valueCodec.isDefined)
      val restored  = roundTripThroughStore(encodable)

      IO.pure(restored.zip(encodable).filterNot(pair => pair._1 == pair._2).map(pair => simpleName(pair._2)) shouldBe Nil)
    }
  }

  it should "store a build's facts in a fraction of what unshared frames cost" in {
    withFacts { facts =>
      val encodable = facts.filter(_.key().valueCodec.isDefined)
      val frames    = encodable.map(encodedSize).sum
      val store     = storeOf(encodable)

      reportStore(frames, store).as(store.appendedBytes.length should be < frames / 4)
    }
  }

  private def storeOf(facts: Seq[CompilerFact]): ContentAddressedOutput = {
    val store = ContentAddressedOutput.empty

    facts.foreach(fact => store.write(fact)(using codecOf(fact)))
    store
  }

  private def roundTripThroughStore(facts: Seq[CompilerFact]): Seq[CompilerFact] = {
    val store   = ContentAddressedOutput.empty
    val offsets = facts.map(fact => store.write(fact)(using codecOf(fact)).offset)
    val input   = new ContentAddressedInput(store.appendedBytes)

    offsets.zip(facts).map((offset, fact) => input.read(offset)(using codecOf(fact)))
  }

  private def reportStore(frames: Int, store: ContentAddressedOutput): IO[Unit] = IO.delay {
    val stored = store.appendedBytes.length

    info(f"independent per-fact frames:            ${frames}%,12d bytes  (baseline)")
    info(f"content-addressed object store:         ${stored}%,12d bytes  ${stored.toDouble / frames}%.2f×")
    info(f"distinct objects:                       ${store.objectOffsets.size}%,12d")
  }

  /** A fact's own codec, as its key states it. Widened because the harness holds facts erased to [[CompilerFact]]. */
  private def codecOf(fact: CompilerFact): FactCodec[CompilerFact] =
    fact
      .key()
      .valueCodec
      .getOrElse(fail(s"${simpleName(fact)} declines to persist"))
      .asInstanceOf[FactCodec[CompilerFact]]

  private def simpleName(fact: CompilerFact): String = fact.getClass.getSimpleName

  private def encodedSize(fact: CompilerFact): Int = FactCodec.toBytes(fact)(using codecOf(fact)).length

  private def roundTrips(fact: CompilerFact): Boolean =
    fact.key().valueCodec.isEmpty || {
      val codec = codecOf(fact)
      FactCodec.fromBytes(FactCodec.toBytes(fact)(using codec))(using codec) == fact
    }

  /** Print the per-type breakdown the measurement is for; the assertions above are the part that must not regress. */
  private def report(facts: Seq[CompilerFact], frames: Int, shared: Int, java: Int): IO[Unit] = IO.delay {
    info(f"facts encoded: ${facts.size}%,d")
    info(f"java serialization, one shared graph:   ${java}%,12d bytes  (baseline)")
    info(f"explicit codecs, independent frames:    ${frames}%,12d bytes  ${frames.toDouble / java}%.2f×")
    info(f"explicit codecs, shared sub-values:     ${shared}%,12d bytes  ${shared.toDouble / java}%.2f×")
    facts
      .groupBy(simpleName)
      .view
      .mapValues(group => (group.size, group.map(encodedSize).sum))
      .toSeq
      .sortBy { case (_, (_, bytes)) => -bytes }
      .take(8)
      .foreach { case (name, (count, bytes)) => info(f"  $bytes%,9d B  $count%,5d ×  $name") }
  }

  /** Compile a small program in a dedicated session and hand every materialised fact to `use`. */
  private def withFacts(use: Seq[CompilerFact] => IO[Assertion]): IO[Assertion] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-codec-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-codec-target"))
      _          <- IO.blocking(Files.writeString(sourceDir.resolve("Test.els"), program))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- session.compileOnce()
      cache      <- result.generator.buildCacheData()
      assertion  <- use(cache.entries.values.flatMap(_.value).toSeq)
    } yield assertion

  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
