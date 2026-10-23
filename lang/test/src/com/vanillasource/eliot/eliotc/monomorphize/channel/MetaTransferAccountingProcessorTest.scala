package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

/** What a return brace may say (docs/meta.md §3, §8.1):
  *
  *   - **R2** — a **body-less** value (a native leaf) that produces a meta-carrying type must **state** a `^Meta`
  *     transfer, a leaf whose return is a type parameter included, at every instantiation where it is one
  *     (`docs/meta.md` §10).
  *   - **Decision O** — a **bodied** value may state its result's meta, which replaces the derived one, and nothing
  *     else: no control, no slots over a result carrying none, and nothing over a `data` result. A `where`
  *     precondition is untouched by this: it is a stated contract, and its `^Where` companion is a different name.
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

  private val statedControl = "its { ... } return brace may state only the meta-information of its result"

  private def statedSlotsWithoutMeta(result: String) = s"but $result carries none"

  private val statedData = "is a data type, whose meta-information is derived from its fields"

  private val metaPrimitives = SystemImport("Meta", "def never[D]: D", ModuleName.compilerPackage)

  private def errorsOf(source: String, name: String) =
    runGenerator(prelude + source, accountingKey(name), ambientStubsWith() :+ metaPrimitives).map((errors, facts) =>
      errors.map(_.message).mkString("\n") + facts.keySet.contains(accountingKey(name)).toString
    )

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

  "a bodied value that states its result" should "be accepted (decision O: the brace replaces the derived meta)" in {
    runGenerator(prelude + "def echo(g: Gauge): Gauge {level(g)} = g", accountingKey("echo"), ambientStubsWith()).map {
      case (_, facts) => facts.keySet should contain(accountingKey("echo"))
    }
  }

  "the codegen precondition" should "let a stating bodied value's WovenValue through" in {
    runGenerator(prelude + "def echo(g: Gauge): Gauge {level(g)} = g", wovenKey("echo"), ambientStubsWith()).map {
      case (_, facts) => facts.keySet should contain(wovenKey("echo"))
    }
  }

  "a bodied value stating control" should "be rejected, since its body's control is derived" in {
    errorsOf("def echo(g: Gauge): Gauge {eliot.compiler.Meta::never} = g", "echo").asserting(_ should include(statedControl))
  }

  "a bodied value stating slots over a result carrying no meta" should "be rejected" in {
    errorsOf("type Plain\ndef echo(p: Plain): Plain {0} = p", "echo").asserting(_ should include(statedSlotsWithoutMeta("'Plain'")))
  }

  "a bodied value stating a function result" should "be rejected" in {
    errorsOf("def lift(g: Gauge): Function[Gauge, Gauge] {level(g)} = h -> h", "lift")
      .asserting(_ should include(statedSlotsWithoutMeta("'Function'")))
  }

  "a bodied value stating a data result" should "be rejected, since a data type declares no slots" in {
    errorsOf("data Box(v: Gauge)\ndef box(g: Gauge): Box {level(g)} = Box(g)", "box").asserting(_ should include(statedData))
  }

  "a bodied value carrying a where precondition" should "be accepted (a `where` is stated, never derived)" in {
    runGenerator(
      prelude + "def bounded(l: Interval[BigInteger]): Bool\ndef echo(g: Gauge) where bounded(level(g)): Gauge = g",
      accountingKey("echo"),
      ambientStubsWith()
    ).map { case (errors, facts) =>
      errors.map(_.message).mkString("\n") shouldBe ""
      facts.keySet should contain(accountingKey("echo"))
    }
  }

  "a bodied value" should "not be answered for by an ability implementation's brace on the same bare name" in {
    // §4.2: a companion keeps its callee's qualifier, so the `Sum[Gauge]` implementation's brace is that
    // implementation's transfer and not the plain `combine`'s. Its `never` is legal on a native and refused on a bodied
    // value, so the plain `combine` is accepted only if the brace is not read as its own.
    runGenerator(
      prelude +
        "ability Sum[T] { def combine(a: T, b: T): T }\n" +
        "implement Sum[Gauge] { def combine(a: Gauge, b: Gauge): Gauge {eliot.compiler.Meta::never} }\n" +
        "def combine(a: Gauge, b: Gauge): Gauge = a",
      accountingKey("combine"),
      ambientStubsWith() :+ metaPrimitives
    ).map { case (errors, facts) =>
      errors.map(_.message).mkString("\n") shouldBe ""
      facts.keySet should contain(accountingKey("combine"))
    }
  }

  "a body-less leaf whose return is a type parameter" should "be rejected at a meta-carrying instantiation" in {
    runGenerator(prelude + "def forward[A](a: A): A", accountingKey("forward", Seq(gauge)), ambientStubsWith()).map {
      case (errors, _) => errors.map(_.message).mkString("\n") should include(missingTransfer)
    }
  }

  it should "be accepted at a meta-carrying instantiation when it states what it forwards" in {
    runGenerator(prelude + "def forward[A](a: A): A {a}", accountingKey("forward", Seq(gauge)), ambientStubsWith())
      .map { case (errors, facts) =>
        errors.map(_.message).mkString("\n") should not include missingTransfer
        facts.keySet should contain(accountingKey("forward", Seq(gauge)))
      }
  }
}
