package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

/** A leaf's **stated** transfer, read through the channel. A brace lists its result's slots, and the meta constructor
  * they are applied to is the one of the result's *evaluated* meta type, which the channel passes the companion — so a
  * result type spelled as an alias (`type Reading = Gauge`) states its meta exactly as `Gauge` itself would, where
  * naming the constructor by spelling (`Reading$Meta`) did not resolve.
  *
  * A **bodied** value's brace replaces the meta its body derives (decision O, `docs/meta.md` §8.1), wherever the brace
  * and the body are declared.
  */
class MetaInterpreterTest extends ProcessorTest(LangProcessors()*) {
  private val prelude =
    """type Gauge {level: BigInteger}
      |type Reading = Gauge
      |""".stripMargin

  private def tableKey(name: String): RefinementTable.Key =
    RefinementTable.Key(ValueFQN(testModuleName, QualifiedName(name, Qualifier.Default)), Seq.empty)

  private def gaugeMeta(level: Int): GroundValue =
    GroundValue.Structure(
      ValueFQN(testModuleName, QualifiedName("Gauge$Meta", Qualifier.Default)),
      Seq(
        GroundValue.Direct(
          BigInt(level),
          GroundValue.Structure(
            ValueFQN(ModuleName(ModuleName.defaultSystemPackage, "BigInteger"), QualifiedName("BigInteger", Qualifier.Type)),
            Seq.empty,
            GroundValue.Type
          )
        )
      ),
      GroundValue.Type
    )

  private def recordedMetas(source: String, layers: String*) =
    runGenerator(
      prelude + source + "\ndef probe: Gauge = readGauge",
      tableKey("probe"),
      ambientStubsWith() :+ SystemImport("Meta", "def never[D]: D", ModuleName.compilerPackage),
      layers
    )
      .map(_._2.get(tableKey("probe")).collect { case table: RefinementTable => table.metas.flatMap(_.meta) })

  "a stated transfer" should "build its result's meta from the slots it lists" in {
    recordedMetas("def readGauge: Gauge {5}").asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }

  it should "build it the same way when the result type is an alias" in {
    recordedMetas("def readGauge: Reading {5}").asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }

  private val sensor = "def sensor: Gauge {7}\n"

  "a bodied value" should "derive its result's meta from its body" in {
    recordedMetas(sensor + "def readGauge: Gauge = sensor").asserting(_ shouldBe Some(Seq(gaugeMeta(7))))
  }

  it should "have its derived meta replaced by its brace" in {
    recordedMetas(sensor + "def readGauge: Gauge {5} = sensor").asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }

  it should "take a brace wider than the derivation as written" in {
    recordedMetas(sensor + "def readGauge: Gauge {9} = sensor").asserting(_ shouldBe Some(Seq(gaugeMeta(9))))
  }

  // The layer re-declares what its body names, since a file resolves its own names (`type String` is declared bare in
  // `lang` beside `stdlib`'s slotted one the same way).
  it should "have its derived meta replaced by a brace declared in another layer than its body" in {
    recordedMetas(sensor + "def readGauge: Gauge {5}", "type Gauge\ndef sensor: Gauge\ndef readGauge: Gauge = sensor")
      .asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }

  // ⊥ tells the two arms apart: a stated call is its brace, a derived one is what its body does, and a body that never
  // returns has no result for a brace to state. `sensor` states `never` as the exits do, over a type-parameter result.
  private val stuck = "def sensor[A]: A {eliot.compiler.Meta::never}\n"

  it should "stay ⊥ when its body never returns, whatever its brace says" in {
    recordedMetas(stuck + "def readGauge: Gauge {5} = sensor").asserting(_ shouldBe Some(Seq.empty))
  }

  it should "stay ⊥ when its body in another layer never returns" in {
    recordedMetas(stuck + "def readGauge: Gauge {5}", "type Gauge\ndef sensor[A]: A\ndef readGauge: Gauge = sensor")
      .asserting(_ shouldBe Some(Seq.empty))
  }
}
