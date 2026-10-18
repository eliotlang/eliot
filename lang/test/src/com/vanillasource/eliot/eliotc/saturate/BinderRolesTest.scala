package com.vanillasource.eliot.eliotc.saturate

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.saturate.fact.BinderRoles.Disposition
import com.vanillasource.eliot.eliotc.saturate.fact.{BinderRoles, SaturatedValue}

/** Targeted test for the per-binder role analysis ([[BinderRoles]]) carried on [[SaturatedValue]].
  *
  * Originally (D6 of the monomorphize architecture review) this only distinguished the *reified* binders (referenced in
  * value position) for the monomorphize binding wrap. The monomorphization-keying plan's **B1** extends it into the
  * full codegen-relevance classification — reified (R1), dispatched (R2), representation (R3) — and the derived
  * [[Disposition]] the codegen-key projection consumes.
  *
  * The reified cases pin the binding-wrap behaviour (a reified binder, a non-reified ordinary generic, and reification
  * through a nested value reference's type argument). The disposition cases pin the B1 classification against the keying-plan scenarios:
  * representation-collapse (S2/S3 `id`), reified ⟶ specialize (S4 `tag`, and the self-referential S5 `gen`), dispatch ⟶
  * specialize (S6 `describe`), and an obvious phantom ⟶ erase.
  */
class BinderRolesTest extends ProcessorTest(LangProcessors()*) {

  // --- reified classification (the binding-wrap concern) ---------------------------------------------------------

  "a type-stack binder referenced in value position" should "be classified reified" in {
    reifiedOf("def bigOf[V: BigInteger]: BigInteger = V", "bigOf")
      .asserting(_ shouldBe (Seq.empty, Seq(("V", true))))
  }

  "an ordinary generic never used in value position" should "be classified non-reified" in {
    reifiedOf("def id[A](x: A): A = x", "id")
      .asserting(_ shouldBe (Seq.empty, Seq(("A", false))))
  }

  "a binder reified through a nested value reference's type argument" should "be classified reified" in {
    reifiedOf("def bigOf[V: BigInteger]: BigInteger = V\ndef h[N: BigInteger]: BigInteger = bigOf[N]", "h")
      .asserting(_ shouldBe (Seq.empty, Seq(("N", true))))
  }

  // --- disposition classification (the B1 keying concern) --------------------------------------------------------

  "a type parameter typing a runtime value (S2/S3 id)" should "be disposed collapse-to-representation" in {
    dispositionOf("def id[A](x: A): A = x", "id")
      .asserting(_ shouldBe (Seq.empty, Seq(("A", Disposition.CollapseToRepresentation))))
  }

  "a recursion-invariant reified binder (S4 tag)" should "be disposed specialize" in {
    dispositionOf("def tag[N: BigInteger]: BigInteger = N", "tag")
      .asserting(_ shouldBe (Seq.empty, Seq(("N", Disposition.Specialize))))
  }

  // `gen` once exercised the disposition of a reified binder in a *self-referential* value; that disposition (reified ->
  // Specialize) is covered non-recursively by the S4 `tag` case above. `gen` refers back to itself, so termination M1
  // now rejects the value cycle before the role analysis runs.
  "a reified binder in a self-referential value (S5 gen)" should "be rejected as recursion" in {
    dispositionOf("def one: BigInteger\ndef gen[N: BigInteger]: BigInteger = add(N, gen[subtract(N, one)])", "gen")
      .asserting { case (errors, _) => errors.map(_.message) should contain("Value 'gen' is defined recursively.") }
  }

  "a dispatched (ability-constrained) binder (S6 describe)" should "be disposed specialize, not collapsed" in {
    dispositionOf(
      "ability Render[A] { def render(x: A): String }\ndef describe[A ~ Render](x: A): String = render(x)",
      "describe"
    ).asserting(_ shouldBe (Seq.empty, Seq(("A", Disposition.Specialize))))
  }

  "an obvious phantom binder used in no position" should "be disposed collapse-erase" in {
    dispositionOf("def phantom[P, A](x: A): A = x", "phantom")
      .asserting(_ shouldBe (Seq.empty, Seq(("P", Disposition.CollapseErase), ("A", Disposition.CollapseToRepresentation))))
  }

  // --- helpers ---------------------------------------------------------------------------------------------------

  private def reifiedOf(source: String, name: String): IO[(Seq[TestError], Seq[(String, Boolean)])] =
    rolesOf(source, name)(role => (role.name.value, role.reified))

  private def dispositionOf(source: String, name: String): IO[(Seq[TestError], Seq[(String, Disposition)])] =
    rolesOf(source, name)(role => (role.name.value, role.disposition))

  /** Drive the pipeline to a value's [[SaturatedValue]] and project its binder roles in declaration order, alongside
    * any compilation errors.
    */
  private def rolesOf[A](source: String, name: String)(f: BinderRoles.Role => A): IO[(Seq[TestError], Seq[A])] = {
    val vfqn = ValueFQN(testModuleName, default(name))
    runGenerator(source, SaturatedValue.Key(vfqn), binderRolesImports).map { case (errors, facts) =>
      val roles = facts
        .get(SaturatedValue.Key(vfqn))
        .collect { case sv: SaturatedValue => sv.binderRoles.roles }
        .getOrElse(Seq.empty)
        .map(f)
      (toTestErrors(errors), roles)
    }
  }

  /** Ambient stubs plus `BigInteger` (with the `add`/`subtract` the self-referential `gen` scenario reifies);
    * `Int`/`Runtime`/`Console`/`Log`/`Dep` are in `defaultSystemModules`, so their stubs must be present even though
    * these scenarios only declare on `BigInteger`/`Int`.
    */
  private val binderRolesImports: Seq[SystemImport] = ambientStubsWith(
    "BigInteger" -> "type BigInteger\ndef add(a: BigInteger, b: BigInteger): BigInteger\ndef subtract(a: BigInteger, b: BigInteger): BigInteger"
  )
}
