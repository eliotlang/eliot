package com.vanillasource.eliot.eliotc.compiler.cache.codec

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters.*

class ContentAddressedInputTest extends AnyFlatSpec with Matchers {
  private val values: Seq[List[List[String]]] =
    (0 until 2000).map(i => List.tabulate(8)(j => List(s"value $i item $j", s"shared $j", "common")))

  "read" should "decode every value correctly when many threads read from one input at once" in {
    val output  = new ContentAddressedOutput(Map.empty, 0)
    val offsets = values.map(value => output.write(value).offset)
    val input   = new ContentAddressedInput(output.appendedBytes)
    val reads   = offsets.map(offset => (() => input.read[List[List[String]]](offset)): Callable[List[List[String]]])
    val threads = Executors.newVirtualThreadPerTaskExecutor()

    try threads.invokeAll(reads.asJava).asScala.map(_.get()).toSeq shouldBe values
    finally threads.shutdown()
  }
}
