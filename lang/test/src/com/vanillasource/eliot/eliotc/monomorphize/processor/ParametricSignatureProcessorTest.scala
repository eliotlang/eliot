package com.vanillasource.eliot.eliotc.monomorphize.processor

import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{CompilerMonomorphicValue, GroundValue, ParametricSignature}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerFact
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI
import java.nio.file.Path

/** A value's signature checked once with its binders as placeholders ([[ParametricSignatureProcessor]]), and an
  * instance's signature read off it by substitution instead of a check of its own.
  */
class ParametricSignatureProcessorTest extends ProcessorTest(LangProcessors(systemModules = Seq.empty)*) {

  private def compilerScan(pkg: Seq[String], name: String, content: String): Seq[SourceContent | PathScan] = {
    val path = (pkg :+ s"$name.els").foldLeft(Path.of(""))(_ `resolve` _)
    val uri  = URI.create((pkg :+ s"$name.els").mkString("/"))
    Seq(
      PathScan(path, Seq(uri), Platform.Compiler),
      SourceContent(uri, Sourced(uri, PositionRange.zero, content))
    )
  }

  private val mContent =
    """import eliot.lang.Function
      |import eliot.lang.Bool
      |
      |type A
      |
      |def id[X](x: X): X = x
      |
      |def flag[X]: true
      |""".stripMargin

  private val facts: Seq[CompilerFact] =
    compilerScan(Seq("eliot", "compiler"), "Type", "type Type") ++
      compilerScan(Seq("eliot", "lang"), "Function", "type Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B") ++
      compilerScan(Seq("eliot", "lang"), "Bool", "type Bool\ndef true: Bool\ndef false: Bool") ++
      compilerScan(Seq("eliot", "lang"), "Implementation", ProcessorTest.implementationStubContent) ++
      compilerScan(Seq("test"), "M", mContent)

  private def signatureOf(name: String): ValueFQN =
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName(name, Qualifier.Default).signatureTwin)

  private val typeA = GroundValue.Structure(
    ValueFQN(ModuleName(Seq("test"), "M"), QualifiedName("A", Qualifier.Type)),
    Seq.empty,
    GroundValue.Type
  )

  private def function(from: GroundValue, to: GroundValue): GroundValue =
    GroundValue.Structure(WellKnownTypes.functionDataTypeFQN, Seq(from, to), GroundValue.Type)

  private def parametricOf(name: String) =
    runGeneratorWithFacts(facts, ParametricSignature.Key(signatureOf(name))).map(_._1.map(_.signature))

  "a parametric signature" should "read each binder back as a placeholder" in {
    val x = GroundValue.Param(0, Seq.empty, GroundValue.Type)
    parametricOf("id").asserting(_ shouldBe Some(Some(function(x, x))))
  }

  it should "give an instance its signature by substitution" in {
    runGeneratorWithFacts(facts, CompilerMonomorphicValue.Key(signatureOf("id"), Seq(typeA)))
      .asserting(_._1.map(_.signature) shouldBe Some(function(typeA, typeA)))
  }

  it should "be withheld from a guard, whose verdict each instance decides" in {
    parametricOf("flag").asserting(_ shouldBe Some(None))
  }
}
