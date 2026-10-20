package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

/** R2 + R3 enforcement (docs/total-meta-transfers.md §P2/§P3), the two halves of one rule:
  *
  *   - **R2** — a **body-less** value (a native leaf) that produces a meta-carrying type must **state** a `^Meta`
  *     transfer; a leaf whose return is a type parameter (a forwarded, not originated, meta) is exempt.
  *   - **R3** — a **bodied** value derives its transfer from its body and may therefore **not** state one. A `where`
  *     precondition is untouched by this: it is a stated contract, never derived, and its `^Where` companion is a
  *     different name.
  *
  * A meta-carrying type here is one declaring `$Meta` slots (`type Gauge {level: Interval[BigInteger]}`, mirroring
  * production's `type Int {range: Interval[BigInteger]}`); the check is demanded directly at each value's
  * [[MetaTransferAccounting]] key, which is also how codegen demands it since S5 armed the processor as a
  * [[WovenValueProcessor]] precondition.
  */
class MetaTransferAccountingProcessorTest extends ProcessorTest(LangProcessors()*) {

  private val prelude =
    """type Gauge {level: Interval[BigInteger]}
      |""".stripMargin

  // Not named `key`: that collides with ScalaTest's `contain key(...)` DSL word, which silently wins the single-argument
  // call and never equals the produced fact key.
  private def accountingKey(name: String, typeArgs: Seq[GroundValue] = Seq.empty): MetaTransferAccounting.Key =
    MetaTransferAccounting.Key(ValueFQN(testModuleName, QualifiedName(name, Qualifier.Default)), typeArgs)

  private def wovenKey(name: String): WovenValue.Key =
    WovenValue.Key(ValueFQN(testModuleName, QualifiedName(name, Qualifier.Default)), Seq.empty)

  private val gauge: GroundValue =
    GroundValue.Structure(ValueFQN(testModuleName, QualifiedName("Gauge", Qualifier.Type)), Seq.empty, GroundValue.Type)

  private val missingTransfer = "carries meta-information, but states no meta transfer"

  private val statedTransfer = "but it also states a meta transfer"

  "a body-less leaf producing a meta-carrying type" should "be rejected when it states no transfer" in {
    runGenerator(prelude + "def readGauge: Gauge", accountingKey("readGauge"), ambientStubsWith()).map {
      case (errors, _) => errors.map(_.message).mkString("\n") should include(missingTransfer)
    }
  }

  "a body-less leaf producing a meta-carrying type" should "be accepted when it states a transfer" in {
    runGenerator(prelude + "def readGauge: Gauge {level(readGauge)}", accountingKey("readGauge"), ambientStubsWith())
      .map { case (errors, facts) =>
        errors.map(_.message).mkString("\n") should not include missingTransfer
        facts.keySet should contain(accountingKey("readGauge"))
      }
  }

  "a body-less leaf producing a meta-carrying type through an alias" should "be rejected when it states no transfer" in {
    runGenerator(prelude + "type Reading = Gauge\ndef readGauge: Reading", accountingKey("readGauge"), ambientStubsWith())
      .map { case (errors, _) => errors.map(_.message).mkString("\n") should include(missingTransfer) }
  }

  it should "be accepted when it states a transfer" in {
    runGenerator(
      prelude + "type Reading = Gauge\ndef readGauge: Reading {level(readGauge)}",
      accountingKey("readGauge"),
      ambientStubsWith()
    ).map { case (_, facts) => facts.keySet should contain(accountingKey("readGauge")) }
  }

  "a body-less leaf stating slots over a non-meta type" should "be rejected" in {
    runGenerator(prelude + "def readName: String {0}", accountingKey("readName"), ambientStubsWith()).map {
      case (errors, _) => errors.map(_.message).mkString("\n") should include("but 'String' carries none")
    }
  }

  "a bodied value producing a meta-carrying type" should "be accepted (it derives, states nothing)" in {
    runGenerator(prelude + "def echo(g: Gauge): Gauge = g", accountingKey("echo"), ambientStubsWith()).map {
      case (errors, facts) =>
        errors.map(_.message).mkString("\n") should not include missingTransfer
        facts.keySet should contain(accountingKey("echo"))
    }
  }

  "a body-less leaf producing a non-meta type" should "be accepted" in {
    runGenerator(prelude + "def readName: String", accountingKey("readName"), ambientStubsWith()).map {
      case (errors, facts) =>
        errors.map(_.message).mkString("\n") should not include missingTransfer
        facts.keySet should contain(accountingKey("readName"))
    }
  }

  "the codegen precondition" should "block a violating leaf's WovenValue (S5 arming)" in {
    runGenerator(prelude + "def readGauge: Gauge", wovenKey("readGauge"), ambientStubsWith()).map { case (_, facts) =>
      facts.keySet should not contain wovenKey("readGauge")
    }
  }

  it should "let a stating leaf's WovenValue through" in {
    runGenerator(prelude + "def readGauge: Gauge {level(readGauge)}", wovenKey("readGauge"), ambientStubsWith()).map {
      case (_, facts) => facts.keySet should contain(wovenKey("readGauge"))
    }
  }

  "a bodied value that states a transfer" should "be rejected (R3: it derives, it may not state)" in {
    runGenerator(prelude + "def echo(g: Gauge): Gauge {level(g)} = g", accountingKey("echo"), ambientStubsWith()).map {
      case (errors, _) => errors.map(_.message).mkString("\n") should include(statedTransfer)
    }
  }

  "the codegen precondition" should "block a stating bodied value's WovenValue" in {
    runGenerator(prelude + "def echo(g: Gauge): Gauge {level(g)} = g", wovenKey("echo"), ambientStubsWith()).map {
      case (_, facts) => facts.keySet should not contain wovenKey("echo")
    }
  }

  "a bodied value carrying a where precondition" should "be accepted (a `where` is stated, never derived)" in {
    runGenerator(
      prelude + "def bounded(l: Interval[BigInteger]): Bool\ndef echo(g: Gauge) where bounded(level(g)): Gauge = g",
      accountingKey("echo"),
      ambientStubsWith()
    ).map { case (errors, facts) =>
      errors.map(_.message).mkString("\n") should not include statedTransfer
      facts.keySet should contain(accountingKey("echo"))
    }
  }

  "a bodied value" should "not be answered for by an ability implementation's brace on the same bare name" in {
    // §8.1: a companion keeps its callee's qualifier, so the `Sum[Gauge]` implementation's brace is that
    // implementation's transfer and not the plain `combine`'s — which derives, states nothing, and is accepted.
    runGenerator(
      prelude +
        "ability Sum[T] { def combine(a: T, b: T): T }\n" +
        "implement Sum[Gauge] { def combine(a: Gauge, b: Gauge): Gauge {level(a)} }\n" +
        "def combine(a: Gauge, b: Gauge): Gauge = a",
      accountingKey("combine"),
      ambientStubsWith()
    ).map { case (errors, facts) =>
      errors.map(_.message).mkString("\n") should not include statedTransfer
      facts.keySet should contain(accountingKey("combine"))
    }
  }

  "a body-less leaf whose return is a type parameter" should "be exempt even at a meta-carrying instantiation" in {
    runGenerator(prelude + "def forward[A](a: A): A", accountingKey("forward", Seq(gauge)), ambientStubsWith()).map {
      case (errors, facts) =>
        errors.map(_.message).mkString("\n") should not include missingTransfer
        facts.keySet should contain(accountingKey("forward", Seq(gauge)))
    }
  }
}
