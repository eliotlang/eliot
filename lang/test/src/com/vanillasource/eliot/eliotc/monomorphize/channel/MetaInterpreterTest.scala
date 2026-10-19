package com.vanillasource.eliot.eliotc.monomorphize.channel

import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

/** A leaf's **stated** transfer, read through the channel. A brace lists its result's slots, and the meta constructor
  * they are applied to is the one of the result's *evaluated* meta type, which the channel passes the companion — so a
  * result type spelled as an alias (`type Reading = Gauge`) states its meta exactly as `Gauge` itself would, where
  * naming the constructor by spelling (`Reading$Meta`) did not resolve.
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

  private def recordedMetas(source: String) =
    runGenerator(prelude + source + "\ndef probe: Gauge = readGauge", tableKey("probe"), ambientStubsWith())
      .map(_._2.get(tableKey("probe")).collect { case table: RefinementTable => table.metas.flatMap(_.meta) })

  "a stated transfer" should "build its result's meta from the slots it lists" in {
    recordedMetas("def readGauge: Gauge {5}").asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }

  it should "build it the same way when the result type is an alias" in {
    recordedMetas("def readGauge: Reading {5}").asserting(_ shouldBe Some(Seq(gaugeMeta(5))))
  }
}
