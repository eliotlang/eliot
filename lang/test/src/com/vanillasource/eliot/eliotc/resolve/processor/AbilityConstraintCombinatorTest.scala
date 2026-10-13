package com.vanillasource.eliot.eliotc.resolve.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue

/** `&` — the combinator joining two `~` ability constraints — is a standard-library declaration the resolver looks up
  * by name, not a symbol the parser recognises (`docs/effects-syntax-userspace.md` §4 stage 1). So the ordinary
  * dictionary rules apply to it: it must be in scope, and a module declaring its own `&` takes the name back.
  */
class AbilityConstraintCombinatorTest extends ProcessorTest(LangProcessors()*) {

  "the ability constraint combinator" should "join two constraints when it resolves to the prelude's `&`" in {
    errorsOf("def f[A ~ Show & Eq](a: A): A = a").asserting(_ shouldBe Seq.empty)
  }

  it should "reject an operator that resolves to something other than `&`" in {
    errorsOf("data T\ninfix left def #(a: T, b: T): T\ndef f[A ~ Show # Eq](a: A): A = a")
      .asserting(_ shouldBe Seq("Does not combine ability constraints."))
  }

  it should "reject an operator that resolves to nothing at all" in {
    errorsOf("def f[A ~ Show ## Eq](a: A): A = a")
      .asserting(_ shouldBe Seq("Ability constraint combinator not found."))
  }

  it should "report a locally declared `&` shadowing the prelude's, rather than silently meaning the prelude's" in {
    errorsOf("data T\ninfix left def &(a: T, b: T): T\ndef f[A ~ Show & Eq](a: A): A = a")
      .asserting(_ shouldBe Seq("Does not combine ability constraints."))
  }

  it should "not be required by a single unjoined constraint" in {
    errorsOf("data T\ninfix left def &(a: T, b: T): T\ndef f[A ~ Show](a: A): A = a")
      .asserting(_ shouldBe Seq.empty)
  }

  it should "not be required by a multi-entry effect row, which is comma-separated" in {
    errorsOf("data T\ninfix left def &(a: T, b: T): T\ndef f uses Console, Log: String = \"\"")
      .asserting(_ shouldBe Seq.empty)
  }

  private def errorsOf(source: String): IO[Seq[String]] =
    runGenerator(
      source,
      ResolvedValue.Key(ValueFQN(testModuleName, QualifiedName("f", Qualifier.Default))),
      systemImports
    ).map(_._1.map(_.message))
}
