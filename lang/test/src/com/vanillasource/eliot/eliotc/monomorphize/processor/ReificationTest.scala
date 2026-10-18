package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Compile-time → runtime reification: a value-position sub-term that is fully determined by erased `[]`-bound
  * parameters is materialised into a runtime literal / constructor tree (no `bigInt`/explicit reification primitive).
  * See `docs/compile-runtime-reification-plan.md` (Stages 1 and 2).
  *
  * Uses the full match machinery ([[MatchNativesProcessor]] + concrete `PatternMatch`/`TypeMatch` abilities) so that
  * field accessors — desugared to `match` — reduce during checking, which is how `name(A)` folds to its constant.
  */
class ReificationTest extends ProcessorTest(LangProcessors()*) {

  // The canonical ambient set with the real `PatternMatch`/`TypeMatch` ability declarations so field accessors
  // (desugared to `match`) reduce during checking.
  private val matchImports =
    ambientStubsWith("PatternMatch" -> ProcessorTest.patternMatchAbilityStub, "TypeMatch" -> ProcessorTest.typeMatchAbilityStub)

  private val personSource = "data Person(name: String, age: BigInteger)\n"

  // --- Stage 1: primitive leaves ---

  "Stage 1 reification" should "materialise a projected field of an erased data parameter into a String constant" in {
    bodyOf(personSource + "def staticName[A: Person]: String = name(A)", "staticName", Seq(personValue))
      .asserting(render(_) shouldBe "Str(Alice)")
  }

  it should "materialise an erased BigInteger bound referenced in value position into a full-precision constant" in {
    bodyOf("def staticBound[N: BigInteger]: BigInteger = N", "staticBound", Seq(direct(BigInt("123456789012345678"))))
      .asserting(render(_) shouldBe "Int(123456789012345678)")
  }

  // --- Stage 2: whole data structures ---

  "Stage 2 reification" should "materialise a whole erased data parameter into a constructor application" in {
    bodyOf(personSource + "def whole[A: Person]: Person = A", "whole", Seq(personValue))
      .asserting(render(_) shouldBe "Person(Str(Alice), Int(30))")
  }

  // --- Regression: ordinary runtime code is never evaluated/folded ---

  "ordinary runtime code" should "keep a runtime value parameter as a reference even with an erased param in scope" in {
    bodyOf(personSource + "def keepX[A: Person](x: String): String = x", "keepX", Seq(personValue))
      .asserting(render(_) shouldBe "\\x.Param(x)")
  }

  // --- Mixed: materialise only the erased-determined argument ---

  "a mixed application" should "materialise the compile-time argument and keep the runtime argument" in {
    bodyOf(
      personSource + "def combine(a: String, b: String): String\ndef mixed[A: Person](x: String): String = combine(name(A), x)",
      "mixed",
      Seq(personValue)
    ).asserting(render(_) shouldBe "\\x.combine(Str(Alice), Param(x))")
  }

  // --- Fail-safe: an erased-dependent leaf that does not reduce to a constant is a hard error ---

  "a non-reducible erased reference" should "be a fail-safe compiler error rather than a bad emit" in {
    errorsFor("def bad[A: Type]: Type = A", "bad", Seq(GroundValue.Type))
      .asserting(_ shouldBe Seq("Value depends on a compile-time parameter but does not reduce to a constant." at "A"))
  }

  // --- Helpers ---

  private val stringType: GroundValue = GroundValue.Structure(WellKnownTypes.stringFQN, Seq.empty, GroundValue.Type)
  private val bigIntType: GroundValue = GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  private def direct(value: BigInt): GroundValue = GroundValue.Direct(value, bigIntType)

  private val personValue: GroundValue =
    GroundValue.Structure(
      ValueFQN(testModuleName, QualifiedName("Person", Qualifier.Default)),
      Seq(GroundValue.Direct("Alice", stringType), GroundValue.Direct(BigInt(30), bigIntType)),
      GroundValue.Structure(ValueFQN(testModuleName, QualifiedName("Person", Qualifier.Type)), Seq.empty, GroundValue.Type)
    )

  private def bodyOf(source: String, name: String, typeArgs: Seq[GroundValue]): IO[MonomorphicExpression.Expression] =
    runGenerator(source, MonomorphicValue.Key(ValueFQN(testModuleName, default(name)), typeArgs), matchImports)
      .flatMap { case (errors, facts) =>
        if (errors.nonEmpty) IO.raiseError(new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}"))
        else
          facts.values
            .collectFirst { case v: MonomorphicValue if v.vfqn.name.name == name => v }
            .flatMap(v => v.runtime.map(idNormalized(v, _))) match {
            case Some(expr) => IO.pure(expr)
            case None       => IO.raiseError(new Exception(s"No runtime body for '$name'"))
          }
      }

  /** Id-normalize a monomorphic value's runtime body exactly as [[com.vanillasource.eliot.eliotc.monomorphize.channel.
    * WovenValueProcessor]] does before codegen (docs/effects-as-channel.md §6), so the structural asserts see the
    * effects-as-channel normalized shape rather than the raw uniform-carrier mono body's `pure@Id`/`runId` wrapper
    * noise. Under the legacy carrier path (or a compile-time-track body) no `Id` machinery is inserted, so this is a
    * no-op — the same body either way.
    */
  private def idNormalized(
      v: MonomorphicValue,
      body: Sourced[MonomorphicExpression.Expression]
  ): MonomorphicExpression.Expression =
    body.value

  private def errorsFor(source: String, name: String, typeArgs: Seq[GroundValue]): IO[Seq[TestError]] =
    runGenerator(source, MonomorphicValue.Key(ValueFQN(testModuleName, default(name)), typeArgs), matchImports)
      .map(result => toTestErrors(result._1))

  /** Render a monomorphic expression to a compact, position-free string for single-line structural asserts. */
  private def render(expr: MonomorphicExpression.Expression): String = expr match {
    case MonomorphicExpression.IntegerLiteral(v)                  => s"Int(${v.value})"
    case MonomorphicExpression.StringLiteral(v)                   => s"Str(${v.value})"
    case MonomorphicExpression.ParameterReference(n)              => s"Param(${n.value})"
    case MonomorphicExpression.MonomorphicValueReference(fqn, ts) =>
      fqn.value.name.name + (if (ts.isEmpty) "" else ts.map(showType).mkString("[", ", ", "]"))
    case MonomorphicExpression.FunctionLiteral(p, _, b)           => s"\\${p.value}.${render(b.value.expression)}"
    case app: MonomorphicExpression.FunctionApplication          =>
      val (head, args) = flatten(app, Seq.empty)
      render(head.expression) + args.map(a => render(a.value.expression)).mkString("(", ", ", ")")
  }

  private def flatten(
      app: MonomorphicExpression.FunctionApplication,
      acc: Seq[Sourced[MonomorphicExpression]]
  ): (MonomorphicExpression, Seq[Sourced[MonomorphicExpression]]) =
    app.target.value.expression match {
      case inner: MonomorphicExpression.FunctionApplication => flatten(inner, app.argument +: acc)
      case _                                                => (app.target.value, app.argument +: acc)
    }

  private def showType(value: GroundValue): String = value match {
    case GroundValue.Structure(typeName, args, _) =>
      if (args.isEmpty) typeName.name.name else s"${typeName.name.name}[${args.map(showType).mkString(", ")}]"
    case GroundValue.Type                         => "Type"
    case GroundValue.Direct(v, _)                 => v.toString
  }
}
