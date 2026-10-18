package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.WellKnownTypes
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced

class MonomorphicTypeCheckProcessorTest
    extends ProcessorTest(
      LangProcessors(systemModules =
        Seq(
          ModuleName.systemFunctionModuleName,
          ModuleName(ModuleName.defaultSystemPackage, "String"),
          ModuleName(ModuleName.defaultSystemPackage, "BigInteger"),
          ModuleName(ModuleName.defaultSystemPackage, "Int"),
          ModuleName(ModuleName.defaultSystemPackage, "Runtime")
        )
      )*
    ) {

  override val systemImports: Seq[SystemImport] = Seq(
    SystemImport("Function", "type Function[A, B]"),
    SystemImport("Type", "type Type", ModuleName.compilerPackage),
    SystemImport("String", "type String"),
    SystemImport("BigInteger", "type BigInteger"),
    SystemImport("Int", ProcessorTest.intStubContent),
    SystemImport("Runtime", ProcessorTest.runtimeStubContent),
    // A synthesized `implement`/`data` marker's default `true` guard resolves to `eliot.lang.Bool::true`
    // (ability-guards §2.3), so Bool must be loadable — as it always is in a real layer.
    SystemImport("Bool", ProcessorTest.boolImportContent),
    SystemImport("Implementation", ProcessorTest.implementationStubContent)
  )

  private def dummySourced[T](v: T) = Sourced[T](file, PositionRange.zero, v)

  "MonomorphicTypeCheckProcessor" should "monomorphize non-generic value" in {
    runEngineForMonomorphicValue("def f: BigInteger")
      .asserting { result =>
        showType(result.signature) shouldBe "BigInteger"
        result.runtime shouldBe None
      }
  }

  it should "monomorphize function literal in body" in {
    runEngineForMonomorphicValue("def f: Function[BigInteger, BigInteger] = (x: BigInteger) -> x")
      .asserting(_.runtime.get.value shouldBe a[MonomorphicExpression.FunctionLiteral])
  }

  it should "monomorphize integer literal in body" in {
    // A value-position `42` desugars to `integerLiteral[42] : Int[42, 42]`; `PostDrainQuoter` rewrites the
    // `integerLiteral` reference back into a plain integer-literal node at the readback boundary (Stage 3b), so the
    // monomorphic runtime is an `IntegerLiteral`, not a value reference.
    runEngineForMonomorphicValue("def f: Int[42, 42] = 42")
      .asserting(normalizedBody(_) shouldBe a[MonomorphicExpression.IntegerLiteral])
  }

  it should "monomorphize string literal in body" in {
    runEngineForMonomorphicValue("def f: String = \"hello\"")
      .asserting(normalizedBody(_) shouldBe a[MonomorphicExpression.StringLiteral])
  }

  it should "monomorphize value reference to non-generic value" in {
    runEngineForMonomorphicValue("def constVal: BigInteger\ndef f: BigInteger = constVal")
      .asserting(normalizedBody(_) shouldBe a[MonomorphicExpression.MonomorphicValueReference])
  }

  // --- Generic tests (Step 5) ---

  it should "monomorphize identity function with Int" in {
    runEngineForMonomorphicValue("def id[A](a: A): A = a", "id", Seq(intType))
      .asserting { result =>
        showType(result.signature) shouldBe "Function[BigInteger, BigInteger]"
        result.runtime.get.value shouldBe a[MonomorphicExpression.FunctionLiteral]
      }
  }

  it should "monomorphize identity function with String" in {
    runEngineForMonomorphicValue("def id[A](a: A): A = a", "id", Seq(stringType))
      .asserting(result => showType(result.signature) shouldBe "Function[String, String]")
  }

  it should "monomorphize function with multiple type parameters" in {
    runEngineForMonomorphicValue(
      "def f[A, B](a: A, b: B): A = a",
      "f",
      Seq(intType, stringType)
    ).asserting(result => showType(result.signature) shouldBe "Function[BigInteger, Function[String, BigInteger]]")
  }

  it should "monomorphize function application" in {
    runEngineForMonomorphicValue("def id[A](a: A): A = a\ndef f: Int[42, 42] = id(42)")
      .asserting(_.runtime.get.value shouldBe a[MonomorphicExpression.FunctionApplication])
  }

  it should "monomorphize value with phantom type parameter" in {
    runEngineForMonomorphicValue("def f[I: BigInteger]: String")
      .asserting(result => showType(result.signature) shouldBe "String")
  }

  // --- Ability implementation resolution (Step 8) ---

  it should "resolve ability ref to concrete implementation when monomorphizing with concrete type" in {
    val source =
      """ability Show[A] {
        |  def show(x: A): A
        |}
        |implement Show[BigInteger] {
        |  def show(x: BigInteger): BigInteger = x
        |}
        |def f[A ~ Show](x: A): A = show(x)""".stripMargin
    // `f`'s `~ Show` constraint mints a phantom binder ahead of `A` (effects v6 §9.4), so the mono key is
    // `[<implementation>, A]` — here the `Default` sentinel, meaning "search at the ground arguments", which is what
    // the write puts at a reference no `with` names.
    runEngineForMonomorphicValue(source, "f", Seq(defaultBinding, intType))
      .asserting { result =>
        unwrapFunctionLiterals(normalizedBody(result)) match {
          case MonomorphicExpression.FunctionApplication(target, _) =>
            target.value.expression match {
              case MonomorphicExpression.MonomorphicValueReference(name, typeArgs) =>
                name.value.name.qualifier shouldBe a[Qualifier.AbilityImplementation]
                typeArgs shouldBe Seq.empty
              case other                                                            =>
                fail(s"Expected MonomorphicValueReference, got $other")
            }
          case other                                                 =>
            fail(s"Expected FunctionApplication, got $other")
        }
      }
  }

  it should "fail on type argument count mismatch" in {
    runGenerator(
      "def id[A](a: A): A = a",
      MonomorphicValue.Key(
        ValueFQN(testModuleName, default("id")),
        Seq(intType, intType) // 2 args for 1-param function
      ),
      systemImports
    ).asserting { case (errors, _) =>
      errors.map(_.message) should contain("Too many type arguments.")
    }
  }

  // --- Recursion (termination M1: rejected, not unrolled) ---

  it should "reject a directly self-recursive value" in {
    runGenerator(
      "def f: Function[BigInteger, BigInteger] = f",
      MonomorphicValue.Key(ValueFQN(testModuleName, default("f")), Seq.empty),
      systemImports
    ).asserting { case (errors, _) =>
      errors.map(_.message) should contain("Value 'f' is defined recursively.")
    }
  }

  it should "reject mutually-recursive values" in {
    runGenerator(
      "def f: Function[BigInteger, BigInteger] = g\ndef g: Function[BigInteger, BigInteger] = f",
      MonomorphicValue.Key(ValueFQN(testModuleName, default("f")), Seq.empty),
      systemImports
    ).asserting { case (errors, _) =>
      errors.map(_.message) should contain("Value 'f' is defined recursively.")
    }
  }

  private val intType: GroundValue =
    GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  /** The `Default` implementation sentinel, as a mono-key argument. */
  private val defaultBinding: GroundValue =
    GroundValue.Structure(WellKnownTypes.defaultImplementationFQN, Seq.empty, GroundValue.Type)

  private val stringType: GroundValue =
    GroundValue.Structure(WellKnownTypes.stringFQN, Seq.empty, GroundValue.Type)

  private def runEngineForMonomorphicValue(
      source: String,
      name: String = "f",
      typeArgs: Seq[GroundValue] = Seq.empty
  ): IO[MonomorphicValue] =
    runGenerator(
      source,
      MonomorphicValue.Key(ValueFQN(testModuleName, default(name)), typeArgs),
      systemImports
    ).flatMap { case (errors, facts) =>
      if (errors.nonEmpty)
        IO.raiseError(new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}"))
      else
        facts.values.collectFirst { case v: MonomorphicValue if v.vfqn.name.name == name => v } match {
          case Some(v) => IO.pure(v)
          case None    => IO.raiseError(new Exception(s"No MonomorphicValue found for '$name'"))
        }
    }

  /** The value's runtime body, Id-normalized exactly as `WovenValueProcessor` does before codegen
    * (docs/effects-as-channel.md §6) — so a body-shape assert sees the normalized leaf (`IntegerLiteral`, the resolved
    * ability-impl reference) rather than the uniform-carrier `pure@Id`/`runId` wrapper. A no-op on the legacy path.
    */
  private def normalizedBody(result: MonomorphicValue): MonomorphicExpression.Expression =
    result.runtime.get.value

  private def unwrapFunctionLiterals(expr: MonomorphicExpression.Expression): MonomorphicExpression.Expression =
    expr match {
      case MonomorphicExpression.FunctionLiteral(_, _, body) => unwrapFunctionLiterals(body.value.expression)
      case other                                              => other
    }

  private def showType(value: GroundValue): String = value match {
    case GroundValue.Structure(typeName, args, GroundValue.Type) =>
      if (args.isEmpty) typeName.name.name
      else s"${typeName.name.name}[${args.map(showType).mkString(", ")}]"
    case GroundValue.Type                                        => "Type"
    case _                                                       => value.toString
  }
}
