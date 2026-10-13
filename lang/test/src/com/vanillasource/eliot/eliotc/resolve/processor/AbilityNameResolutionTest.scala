package com.vanillasource.eliot.eliotc.resolve.processor

import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue

/** An ability name resolves like every other name: a keyed lookup of the ability's own marker in the module
  * dictionary (`docs/effects-syntax-userspace.md` §4 stage 2), rather than a scan for any value carrying the
  * `Ability(name)` qualifier.
  *
  * These pin the *contract* — which entry the dictionary is asked for, and that locals win over the prelude — not the
  * difference from the scan it replaced. That difference is deliberately not testable: the scan was a `collectFirst`
  * over `Map.values`, so where the two disagree at all the old answer depended on hash-iteration order, and a test
  * asserting either answer would be asserting that order. Everything constructible from the stub prelude agrees.
  */
class AbilityNameResolutionTest extends ProcessorTest(LangProcessors()*) {

  "an ability name" should "resolve to the local declaration that took an ambient ability's name" in {
    abilityModules("effect Console {\ndef beep: Unit\n}\ndef f uses Console: String = \"\"")
      .asserting(_ shouldBe Seq(testModuleName))
  }

  it should "resolve to the ambient ability when nothing local takes its name" in {
    abilityModules("def f uses Console: String = \"\"")
      .asserting(_ shouldBe Seq(ModuleName(ModuleName.effectPackage, "Console")))
  }

  it should "report an ability that is in no dictionary at all" in {
    errorsOf("def f[G ~ Nonexistent](g: G): String = \"\"")
      .asserting(_ shouldBe Seq("Ability not found."))
  }

  /** The modules of the resolved constraints on the binder the row minted for its single entry (effects v6: a row
    * entry is a phantom binder, and the constraint on it is what names the ability).
    */
  private def abilityModules(source: String): IO[Seq[ModuleName]] =
    runGenerator(source, ResolvedValue.Key(fVfqn), systemImports).map { case (errors, facts) =>
      if (errors.nonEmpty) throw new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}")
      facts.values
        .collectFirst { case rv: ResolvedValue if rv.vfqn === fVfqn => rv }
        .get
        .paramConstraints
        .getOrElse("Impl", Seq.empty)
        .map(_.abilityFQN.moduleName)
    }

  private def errorsOf(source: String): IO[Seq[String]] =
    runGenerator(source, ResolvedValue.Key(fVfqn), systemImports).map(_._1.map(_.message))

  private val fVfqn = ValueFQN(testModuleName, QualifiedName("f", Qualifier.Default))
}
