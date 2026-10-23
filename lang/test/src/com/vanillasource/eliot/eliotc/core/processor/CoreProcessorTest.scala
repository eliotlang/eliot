package com.vanillasource.eliot.eliotc.core.processor

import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.ast.processor.ASTParser
import com.vanillasource.eliot.eliotc.ast.fact.Fixity
import com.vanillasource.eliot.eliotc.core.fact.Expression.*
import com.vanillasource.eliot.eliotc.core.fact.{CoreAST, Expression, NamedValue}
import com.vanillasource.eliot.eliotc.ast.fact.Visibility
import com.vanillasource.eliot.eliotc.token.Tokenizer

class CoreProcessorTest extends ProcessorTest(Tokenizer(), ASTParser(), CoreProcessor()) {
  private val T = Qualifier.Type

  /** `Function` written by its module, as a compiler-generated signature spells it. */
  private val Fn = QualRef("Function", "eliot.lang.Function")
  // The meta namespace of the plain (Default) namespace — where a plain def's `^Meta`/`^Where` companion
  // lives. A companion keeps its callee's qualifier, so an ability implementation's is a different one.
  private val M = Qualifier.Meta(Qualifier.Default)
  private val PatternMatchMeta = patternMatchMeta("Pixel")

  private def patternMatchMeta(data: String): Qualifier = Qualifier.Meta(Qualifier.AbilityImplementation("PatternMatch", data, None))

  "core processor" should "transform a simple constant reference" in {
    namedValue("def a: A = b", QualifiedName("a", Qualifier.Default)).asserting { nv =>
      (nv.qualifiedName.value.name, nv.signature.value.structure, nv.runtimeStructure) shouldBe
        ("a", Ref("A", T), Some(Ref("b")))
    }
  }

  it should "transform an abstract constant without body" in {
    namedValue("def a: A", QualifiedName("a", Qualifier.Default)).asserting { nv =>
      (nv.qualifiedName.value.name, nv.signature.value.structure, nv.runtime) shouldBe ("a", Ref("A", T), None)
    }
  }

  it should "place curried function type in typeStack for function without generics" in {
    namedValue("def f(x: X): R").asserting { nv =>
      nv.signature.value.structure shouldBe App(App(Ref("Function", T), Ref("X", T)), Ref("R", T))
    }
  }

  it should "transform a single-parameter function body into lambda" in {
    // Body parameters are unannotated: the type stack (signature) is the single source of truth for parameter types
    // (the body is always checked against it), so the value lambda carries no redundant type annotation.
    namedValue("def f(x: X): R = y").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("x", Empty, Ref("y")))
    }
  }

  it should "place curried function type in typeStack for multi-parameter function" in {
    namedValue("def f(x: X, y: Y): R").asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("X", T)), App(App(Ref("Function", T), Ref("Y", T)), Ref("R", T)))
    }
  }

  it should "transform a two-parameter function body into nested lambdas" in {
    namedValue("def f(x: X, y: Y): R = z").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("x", Empty, Lambda("y", Empty, Ref("z"))))
    }
  }

  it should "transform three-parameter function body into deeply nested lambdas" in {
    namedValue("def f(a: A, b: B, c: C): R = r").asserting { nv =>
      nv.runtimeStructure shouldBe
        Some(Lambda("a", Empty, Lambda("b", Empty, Lambda("c", Empty, Ref("r")))))
    }
  }

  "generic parameters" should "become outer lambdas with Type reference in typeStack" in {
    namedValue("def f[A]: R").asserting { nv =>
      nv.signature.value.structure shouldBe Lambda("A", Ref("Type", T), Ref("R", T))
    }
  }

  it should "preserve order for multiple generic parameters" in {
    namedValue("def f[A, B]: R").asserting { nv =>
      nv.signature.value.structure shouldBe Lambda("A", Ref("Type", T), Lambda("B", Ref("Type", T), Ref("R", T)))
    }
  }

  it should "place generics then function args in typeStack" in {
    namedValue("def f[A](x: X): R").asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda("A", Ref("Type", T), App(App(Ref("Function", T), Ref("X", T)), Ref("R", T)))
    }
  }

  it should "keep function args only in the value" in {
    namedValue("def f[A](x: X): R = y").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("x", Empty, Ref("y")))
    }
  }

  it should "handle multiple generics with multiple parameters" in {
    namedValue("def f[A, B](x: X, y: Y): R").asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda(
          "A",
          Ref("Type", T),
          Lambda(
            "B",
            Ref("Type", T),
            App(App(Ref("Function", T), Ref("X", T)), App(App(Ref("Function", T), Ref("Y", T)), Ref("R", T)))
          )
        )
    }
  }

  it should "nest function args in value for generic functions" in {
    namedValue("def f[A, B](x: X, y: Y): R = z").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("x", Empty, Lambda("y", Empty, Ref("z"))))
    }
  }

  it should "produce same structure with explicit Type restriction as implicit" in {
    namedValue("def f[A: Type]: R").asserting { nv =>
      nv.signature.value.structure shouldBe Lambda("A", Ref("Type", T), Ref("R", T))
    }
  }

  it should "produce same structure with explicit Function restriction as arity syntax" in {
    namedValue("def f[M: Function[Type, Type]]: R").asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda("M", App(App(Ref("Function", T), Ref("Type", T)), Ref("Type", T)), Ref("R", T))
    }
  }

  "type references with generics" should "convert to function applications" in {
    namedValue("def f: A[B]").asserting { nv =>
      nv.signature.value.structure shouldBe App(Ref("A", T), Ref("B", T))
    }
  }

  it should "convert two-parameter type references to chained applications" in {
    namedValue("def f: A[B, C]").asserting { nv =>
      nv.signature.value.structure shouldBe App(App(Ref("A", T), Ref("B", T)), Ref("C", T))
    }
  }

  it should "convert deeply nested type references" in {
    namedValue("def f: A[B[C]]").asserting { nv =>
      nv.signature.value.structure shouldBe App(Ref("A", T), App(Ref("B", T), Ref("C", T)))
    }
  }

  "function applications" should "have plain expression (not TypeStack) as target and argument" in {
    namedValue("def f: R = g(a)").asserting { nv =>
      val FunctionApplication(target, argument) = nv.runtime.get.value: @unchecked
      target.value shouldBe a[Expression]
      argument.value shouldBe a[Expression]
    }
  }

  it should "convert simple call to function application" in {
    namedValue("def f: R = g(a)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(Ref("g"), Ref("a")))
    }
  }

  it should "curry multi-argument calls" in {
    namedValue("def f: R = g(a, b)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(App(Ref("g"), Ref("a")), Ref("b")))
    }
  }

  it should "curry three-argument calls" in {
    namedValue("def f: R = g(a, b, c)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(App(App(Ref("g"), Ref("a")), Ref("b")), Ref("c")))
    }
  }

  it should "handle nested function calls" in {
    namedValue("def f: R = g(h(a))").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(Ref("g"), App(Ref("h"), Ref("a"))))
    }
  }

  "qualified function applications" should "preserve module qualifier" in {
    namedValue("def f: R = mod.sub.Mod::func").asserting { nv =>
      nv.runtimeStructure shouldBe Some(QualRef("func", "mod.sub.Mod"))
    }
  }

  it should "curry qualified calls with arguments" in {
    namedValue("def f: R = Mod::func(a)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(QualRef("func", "Mod"), Ref("a")))
    }
  }

  "lambda expressions" should "convert single-parameter lambda" in {
    namedValue("def f: R = (a: A) -> b").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Ref("A", T), Ref("b")))
    }
  }

  it should "curry multi-parameter lambda" in {
    namedValue("def f: R = (a: A, b: B) -> c").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Ref("A", T), Lambda("b", Ref("B", T), Ref("c"))))
    }
  }

  it should "handle lambda without parentheses" in {
    namedValue("def f: R = a: A -> b").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Ref("A", T), Ref("b")))
    }
  }

  it should "handle nested lambdas" in {
    namedValue("def f: R = (a: A) -> (b: B) -> c").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Ref("A", T), Lambda("b", Ref("B", T), Ref("c"))))
    }
  }

  "literals" should "desugar value-position integer literals into integerLiteral[n]" in {
    namedValue("def f: R = 42").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Ref("integerLiteral", typeArgs = Seq(IntLit("42"))))
    }
  }

  it should "convert string literals without quotes" in {
    namedValue("def f: R = \"hello\"").asserting { nv =>
      nv.runtimeStructure shouldBe Some(StrLit("hello"))
    }
  }

  "data definitions" should "generate type function with Type qualifier" in {
    namedValues("data Person").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("Person", Qualifier.Type))
    }
  }

  it should "generate type function returning Type" in {
    namedValue("data Person", QualifiedName("Person", Qualifier.Type)).asserting { nv =>
      nv.signature.value.structure shouldBe Ref("Type", T)
    }
  }

  // Note: Type constructor generic params are now runtime arguments, so signature is Function[Type, Type]
  it should "generate type function with generic param as argument in typeStack" in {
    namedValue("data Box[A]", QualifiedName("Box", Qualifier.Type)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("Type", T)), Ref("Type", T))
    }
  }

  it should "generate constructor for data with fields" in {
    namedValues("data Person(name: String)").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("Person", Qualifier.Default))
    }
  }

  it should "generate constructor with curried arguments in typeStack" in {
    namedValue("data Person(name: Name, age: Age)", QualifiedName("Person", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(
          App(Ref("Function", T), Ref("Name", T)),
          App(App(Ref("Function", T), Ref("Age", T)), Ref("Person", Qualifier.Type))
        )
    }
  }

  it should "not generate constructor for abstract data without fields" in {
    namedValues("data Abstract").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should not contain QualifiedName("Abstract", Qualifier.Default)
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("Abstract", Qualifier.Type))
    }
  }

  it should "generate accessor functions for each field" in {
    namedValues("data Person(name: Name, age: Age)").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain allOf (QualifiedName("name", Qualifier.Default), QualifiedName(
        "age",
        Qualifier.Default
      ))
    }
  }

  it should "generate accessor with argument in typeStack" in {
    namedValue("data Person(name: Name)", QualifiedName("name", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("Person", Qualifier.Type)), Ref("Name", T))
    }
  }

  it should "generate accessor with generic param and argument in typeStack" in {
    namedValue("data Box[A](value: A)", QualifiedName("value", Qualifier.Default)).asserting { nv =>
      // The accessor type is (A :: Type) -> Function(Box^Type(A), A)
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        App(App(Ref("Function", T), App(Ref("Box", Qualifier.Type), Ref("A", T))), Ref("A", T))
      )
    }
  }

  "union data definitions" should "generate constructor for each variant" in {
    namedValues("data Maybe = Nothing | Just(value: A)").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain allOf (
        QualifiedName("Maybe", Qualifier.Type),
        QualifiedName("Nothing", Qualifier.Default),
        QualifiedName("Just", Qualifier.Default)
      )
    }
  }

  it should "not generate accessors for multi-constructor data" in {
    namedValues("data Maybe = Nothing | Just(value: A)").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should not contain QualifiedName("value", Qualifier.Default)
    }
  }

  it should "generate fieldless constructor returning data type" in {
    namedValue("data Maybe = Nothing | Just(value: A)", QualifiedName("Nothing", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe Ref("Maybe", Qualifier.Type)
    }
  }

  it should "generate constructor with fields returning data type" in {
    namedValue("data Maybe = Nothing | Just(value: A)", QualifiedName("Just", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("A", T)), Ref("Maybe", Qualifier.Type))
    }
  }

  it should "generate generic union constructors with shared type params" in {
    namedValue("data Maybe[A] = Nothing | Just(value: A)", QualifiedName("Just", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda(
          "A",
          Ref("Type", T),
          App(App(Ref("Function", T), Ref("A", T)), App(Ref("Maybe", Qualifier.Type), Ref("A", T)))
        )
    }
  }

  it should "generate generic fieldless constructor with type params" in {
    namedValue("data Maybe[A] = Nothing | Just(value: A)", QualifiedName("Nothing", Qualifier.Default)).asserting {
      nv =>
        nv.signature.value.structure shouldBe
          Lambda("A", Ref("Type", T), App(Ref("Maybe", Qualifier.Type), Ref("A", T)))
    }
  }

  it should "generate accessors for single constructor with = syntax" in {
    namedValues("data Box[A] = Box(value: A)").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("value", Qualifier.Default))
    }
  }

  it should "generate three fieldless constructors" in {
    namedValues("data Color = Red | Green | Blue").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain allOf (
        QualifiedName("Color", Qualifier.Type),
        QualifiedName("Red", Qualifier.Default),
        QualifiedName("Green", Qualifier.Default),
        QualifiedName("Blue", Qualifier.Default)
      )
    }
  }

  "PatternMatch ability implementation" should "be generated for union data" in {
    namedValues("data Maybe = Nothing | Just(value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases") should not be empty
    }
  }

  it should "be generated for single-constructor data with fields" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases") should not be empty
    }
  }

  it should "generate impl marker and handleCases, and no Cases type" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "PatternMatch") should not be empty
      findByNameAndImplQualifier(nvs, "Cases") shouldBe empty
      findByNameAndImplQualifier(nvs, "handleCases") should not be empty
    }
  }

  it should "not be generated for abstract data" in {
    namedValues("data Abstract").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases") shouldBe empty
    }
  }

  it should "have public visibility for handleCases" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases").head.visibility shouldBe Visibility.Public
    }
  }

  // A private `data` is private in *every* name it mints, its synthetic implementations included — otherwise those leak
  // out of the module, and the visibility-order rule sees public declarations sitting among the private ones.
  it should "have private visibility for handleCases of a private data" in {
    namedValues("private data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases").head.visibility shouldBe Visibility.Private
    }
  }

  it should "make every name a private data mints private" in {
    namedValues("private data Box[A](value: A)").asserting { nvs =>
      nvs.map(_.visibility).distinct shouldBe Seq(Visibility.Private)
    }
  }

  it should "have correct type for handleCases on single-constructor data" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      val nv = findByNameAndImplQualifier(nvs, "handleCases").head
      // handleCases[A, R](value: Box[A], case0 uses *: A => R): R — one handler per constructor
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        Lambda(
          "R",
          Ref("Type", T),
          App(
            App(Ref("Function", T), App(Ref("Box", T), Ref("A", T))),
            App(App(Ref("Function", T), App(App(Fn, Ref("A", T)), Ref("R", T))), Ref("R", T))
          )
        )
      )
    }
  }

  it should "take one handler per constructor, a field-less one taking Unit" in {
    namedValues("data Color = Red | Green").asserting { nvs =>
      val unitHandler = (rest: ExprStructure) => App(App(Ref("Function", T), App(App(Fn, QualRef("Unit", "eliot.lang.Unit")), Ref("R", T))), rest)
      findByNameAndImplQualifier(nvs, "handleCases").head.signature.value.structure shouldBe Lambda(
        "R",
        Ref("Type", T),
        App(App(Ref("Function", T), Ref("Color", T)), unitHandler(unitHandler(Ref("R", T))))
      )
    }
  }

  it should "state the join of its handlers as a transfer" in {
    namedValues("data Color = Red | Green").asserting(
      _.exists(nv =>
        nv.qualifiedName.value.name == "handleCases" && nv.qualifiedName.value.qualifier.isInstanceOf[Qualifier.Meta]
      ) shouldBe true
    )
  }

  it should "avoid clashing result parameter name with existing generic parameter R" in {
    namedValues("data Foo[R](value: R)").asserting { nvs =>
      val nv = findByNameAndImplQualifier(nvs, "handleCases").head
      // handleCases[R, R0](value: Foo[R], case0 uses *: R => R0): R0
      nv.signature.value.structure shouldBe Lambda(
        "R",
        Ref("Type", T),
        Lambda(
          "R0",
          Ref("Type", T),
          App(
            App(Ref("Function", T), App(Ref("Foo", T), Ref("R", T))),
            App(App(Ref("Function", T), App(App(Fn, Ref("R", T)), Ref("R0", T))), Ref("R0", T))
          )
        )
      )
    }
  }

  it should "have abstract body for handleCases" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases").head.runtime shouldBe None
    }
  }

  "meta-slot brace (bounds Step 4a)" should "generate the meta structure's type and value constructors" in {
    namedValues("type Int {range: Interval}").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain allOf (
        QualifiedName("Int$Meta", Qualifier.Type),
        QualifiedName("Int$Meta", Qualifier.Default)
      )
    }
  }

  it should "type the meta value constructor as domain -> metaType" in {
    namedValue("type Int {range: Interval}", QualifiedName("Int$Meta", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("Interval", T)), Ref("Int$Meta", Qualifier.Type))
    }
  }

  it should "generate a slot accessor from the meta structure onto the domain, in the slot namespace" in {
    namedValue("type Int {range: Interval}", QualifiedName("range", Qualifier.Slot)).asserting { nv =>
      nv.signature.value.structure shouldBe
        App(App(Ref("Function", T), Ref("Int$Meta", Qualifier.Type)), Ref("Interval", T))
    }
  }

  it should "generate a PatternMatch implementation for the meta structure" in {
    namedValues("type Int {range: Interval}").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "handleCases") should not be empty
    }
  }

  it should "keep the ordinary type constructor alongside the meta structure" in {
    namedValues("type Int {range: Interval}").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain allOf (
        QualifiedName("Int", Qualifier.Type),
        QualifiedName("Int$Meta", Qualifier.Type)
      )
    }
  }

  it should "not generate a meta structure for a slotless type (its meta is Unit, supplied by the channel)" in {
    namedValues("type Foo").asserting { nvs =>
      nvs.map(_.qualifiedName.value.name) should not contain "Foo$Meta"
    }
  }

  "a slot holding a type parameter's meta (decision L)" should "generate the carried-types signature" in {
    namedValues("type Box[A] {size: Interval, item: MetaOf[A]}").asserting(
      _.map(_.qualifiedName.value) should contain(QualifiedName("Box$Carried", Qualifier.Meta(T)))
    )
  }

  it should "list the carried parameters in it, ending in Unit" in {
    namedValue("type Box[A] {size: Interval, item: MetaOf[A]}", QualifiedName("Box$Carried", Qualifier.Meta(T)))
      .asserting(
        _.runtime.map(_.value.structure) shouldBe
          Some(App(App(QualRef("Function", "eliot.lang.Function"), Ref("A", T)), QualRef("Unit", "eliot.lang.Unit")))
      )
  }

  it should "type the slot by the meta structure's binder" in {
    namedValue("type Box[A] {size: Interval, item: MetaOf[A]}", QualifiedName("item", Qualifier.Slot))
      .asserting(
        _.signature.value.structure shouldBe
          Lambda("M$A", Ref("Type", T), App(App(Ref("Function", T), App(Ref("Box$Meta", T), Ref("M$A", T))), Ref("M$A", T)))
      )
  }

  it should "not generate a carried-types signature for a slot naming no type parameter" in {
    namedValues("type Int {range: Interval}").asserting(
      _.map(_.qualifiedName.value.name) should not contain "Int$Carried"
    )
  }

  "transfer brace (bounds Step 4b)" should "generate a ^Meta transfer companion in the Meta namespace" in {
    namedValues("type Foo {bar: D}\ndef f(a: Foo): Foo {a.bar}").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("f", M))
    }
  }

  it should "type each parameter, the result and the meta constructor by a binder of its own, after the definition's binders" in {
    namedValue("type Foo {bar: D}\ndef f[A](h: A, a: Foo): Foo {a.bar}", QualifiedName("f", M)).asserting { nv =>
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        Lambda(
          "Meta$h",
          Ref("Type", T),
          Lambda(
            "Meta$a",
            Ref("Type", T),
            Lambda(
              "Meta$",
              Ref("Type", T),
              Lambda(
                "Constructor$",
                Ref("Type", T),
                App(
                  App(Ref("Function", T), Ref("Constructor$", T)),
                  App(
                    App(Ref("Function", T), Ref("Meta$h", T)),
                    App(App(Ref("Function", T), Ref("Meta$a", T)), Ref("Meta$", T))
                  )
                )
              )
            )
          )
        )
      )
    }
  }

  it should "take no meta constructor when the result is a type parameter" in {
    namedValue("def f[A](a: A): A {a}", QualifiedName("f", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Empty, Ref("a")))
    }
  }

  it should "describe the parameter and result types it is instantiated by in its payload signature" in {
    namedValue("type Foo {bar: D}\ndef f[A](h: A, a: Foo): Foo {a.bar}", QualifiedName("f$Payload", M)).asserting {
      nv =>
        nv.runtimeStructure shouldBe Some(arrow(Ref("A", T), arrow(Ref("Foo", T), Ref("Foo", T))))
    }
  }

  it should "read a code parameter in its payload signature as the value its code yields" in {
    namedValue("def f[A](c: Bool, w uses *: A) uses *: A {w}", QualifiedName("f$Payload", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(arrow(Ref("Bool", T), arrow(Ref("A", T), Ref("A", T))))
    }
  }

  /** A payload signature's `Function[domain, codomain]`, written module-qualified. */
  private def arrow(domain: ExprStructure, codomain: ExprStructure): ExprStructure =
    App(App(QualRef("Function", "eliot.lang.Function"), domain), codomain)

  it should "build the result by the meta constructor it takes, never by naming it" in {
    namedValue("type Foo {bar: D}\ntype Bar = Foo\ndef f(a: Foo): Bar {a}", QualifiedName("f", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(
        Lambda("constructor$", Empty, Lambda("a", Empty, App(Ref("constructor$"), Ref("a"))))
      )
    }
  }

  it should "not generate a transfer companion for a def without a brace" in {
    namedValues("def f(a: Foo): Foo").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should not contain QualifiedName("f", M)
    }
  }

  it should "generate an implement method's transfer companion in that implementation's own namespace" in {
    val companion =
      QualifiedName("add", Qualifier.Meta(Qualifier.AbilityImplementation("Num", "Foo", None)))
    namedValues("implement Num[Foo] { def add(a: Foo, b: Foo): Foo {a} }").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(companion)
    }
  }

  it should "keep an implementation's transfer companion apart from a same-named plain def's" in {
    namedValues("implement Num[Foo] { def add(a: Foo, b: Foo): Foo {a} }\ndef add(a: Foo, b: Foo): Foo = a")
      .asserting { nvs =>
        nvs.map(_.qualifiedName.value) should not contain QualifiedName("add", M)
      }
  }

  it should "keep an integer literal in the brace a compile-time BigInteger" in {
    namedValue("type Foo {bar: D}\ndef f(a: Foo): Foo {g(0)}", QualifiedName("f", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(
        Lambda("constructor$", Empty, Lambda("a", Empty, App(Ref("constructor$"), App(Ref("g"), IntLit("0")))))
      )
    }
  }

  "parameter brace" should "state its parameter's meta by the meta constructor it takes, reading no parameter" in {
    namedValue("def f(a: Foo, b: Foo {g(0)}): Foo", QualifiedName("f$Parameter$b", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(
        Lambda("constructor$", Empty, Lambda("a$", Empty, Lambda("b$", Empty, App(Ref("constructor$"), App(Ref("g"), IntLit("0"))))))
      )
    }
  }

  it should "type its result by its own parameter's meta binder, after the meta constructor's" in {
    namedValue("def f[A](h: A, a: Foo {g}): Foo", QualifiedName("f$Parameter$a", M)).asserting { nv =>
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        Lambda(
          "Meta$h",
          Ref("Type", T),
          Lambda(
            "Meta$a",
            Ref("Type", T),
            Lambda(
              "Constructor$",
              Ref("Type", T),
              App(
                App(Ref("Function", T), Ref("Constructor$", T)),
                App(
                  App(Ref("Function", T), Ref("Meta$h", T)),
                  App(App(Ref("Function", T), Ref("Meta$a", T)), Ref("Meta$a", T))
                )
              )
            )
          )
        )
      )
    }
  }

  it should "describe its definition's types in a payload signature" in {
    namedValue("def f(a: Foo {g}): Bar", QualifiedName("f$Payload", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(arrow(Ref("Foo", T), Ref("Bar", T)))
    }
  }

  it should "generate no statement for a parameter without a brace" in {
    namedValues("def f(a: Foo {g}, b: Foo): Foo").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should not contain QualifiedName("f$Parameter$b", M)
    }
  }

  "data field brace" should "state the field on its constructor, as a parameter brace" in {
    namedValue("data Pixel(r: Foo {g}, b: Foo)", QualifiedName("Pixel$Parameter$r", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(
        Lambda("constructor$", Empty, Lambda("r$", Empty, Lambda("b$", Empty, App(Ref("constructor$"), Ref("g")))))
      )
    }
  }

  it should "state what the match eliminator hands a field's handler: the field read out of its constructor's slot" in {
    namedValue("data Pixel(r: Foo {g}, b: Foo)", QualifiedName("handleCases$Handed$case0$1", PatternMatchMeta)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda("value", Empty, Lambda("case0", Empty, fieldOf("Pixel", reached(slot("Pixel", "Pixel"), Ref("meta$Pixel$Pixel$b")))))
      )
    )
  }

  it should "state a braced field's handler as an unbraced one's, its brace being its constructor's statement" in {
    namedValue("data Pixel(r: Foo {g}, b: Foo)", QualifiedName("handleCases$Handed$case0$0", PatternMatchMeta)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda("value", Empty, Lambda("case0", Empty, fieldOf("Pixel", reached(slot("Pixel", "Pixel"), Ref("meta$Pixel$Pixel$r")))))
      )
    )
  }

  it should "state what the match eliminator hands a field-less constructor's handler: unit, when its slot is reached" in {
    namedValue("data AB = A | B", QualifiedName("handleCases$Handed$case1$0", patternMatchMeta("AB"))).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda(
          "value",
          Empty,
          Lambda(
            "case0",
            Empty,
            Lambda("case1", Empty, fieldOf("B", App(App(QualRef("reachedThen", "eliot.lang.Reach"), slot("AB", "B")), QualRef("unit", "eliot.lang.Unit"))))
          )
        )
      )
    )
  }

  it should "hand a recursive field's handler the whole summary, when its constructor's slot is reached" in {
    namedValue("data Tree = Leaf | Node(left: Tree, v: Foo)", QualifiedName("handleCases$Handed$case1$0", patternMatchMeta("Tree")))
      .asserting(
        _.runtimeStructure shouldBe Some(
          Lambda(
            "value",
            Empty,
            Lambda(
              "case0",
              Empty,
              Lambda("case1", Empty, fieldOf("Node", App(App(QualRef("reachedThen", "eliot.lang.Reach"), slot("Tree", "Node")), Ref("value"))))
            )
          )
        )
      )
  }

  it should "number a handed statement by the field's position in its own constructor, a field-less one's unit too" in {
    namedValues("data Shape = Circle(r: Foo) | Square(a: Foo, b: Foo {g}) | Empty").asserting(
      _.map(_.qualifiedName.value.name).filter(_.startsWith("handleCases$Handed$")) shouldBe
        Seq("handleCases$Handed$case0$0", "handleCases$Handed$case1$0", "handleCases$Handed$case1$1", "handleCases$Handed$case2$0")
    )
  }

  "a slot a data type declares (O4b)" should "be read by an accessor in the slot namespace, from the type's meta" in {
    namedValue("data Pixel {g: Foo}(r: Foo) {r}", QualifiedName("g", Qualifier.Slot)).asserting(
      _.signature.value.structure shouldBe
        Lambda("M$Pixel$r", Ref("Type", T), App(App(Ref("Function", T), App(Ref("Pixel$Meta", T), Ref("M$Pixel$r", T))), Ref("Foo", T)))
    )
  }

  it should "come first in the type's meta, before its constructors' reach" in {
    namedValue("data Pixel {g: Foo}(r: Foo) {r}", QualifiedName("Pixel$Meta", Qualifier.Default)).asserting(
      _.signature.value.structure shouldBe
        Lambda(
          "M$Pixel$r",
          Ref("Type", T),
          App(
            App(Ref("Function", T), Ref("Foo", T)),
            App(
              App(Ref("Function", T), App(QualRef("Reach", "eliot.lang.Reach"), App(Ref("Pixel$Meta$Pixel", T), Ref("M$Pixel$r", T)))),
              App(Ref("Pixel$Meta", T), Ref("M$Pixel$r", T))
            )
          )
        )
    )
  }

  it should "be stated by the constructor's own statement, before its slot's reach" in {
    namedValue("data Pixel {g: Foo}(r: Foo) {r}", QualifiedName("Pixel", M)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda(
          "constructor$",
          Empty,
          Lambda("r", Empty, App(meta("built"), App(App(Ref("constructor$"), Ref("r")), reachedSlot(App(Ref("Pixel$Meta$Pixel"), Ref("r"))))))
        )
      )
    )
  }

  "data constructor" should "state that its call is the value it built, its slot reached with its fields' metas" in {
    namedValue("data Pixel(r: Foo, b: Foo)", QualifiedName("Pixel", M)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda(
          "constructor$",
          Empty,
          Lambda("r", Empty, Lambda("b", Empty, App(meta("built"), App(Ref("constructor$"), reachedSlot(App(App(Ref("Pixel$Meta$Pixel"), Ref("r")), Ref("b")))))))
        )
      )
    )
  }

  it should "leave every other constructor's slot unreached" in {
    namedValue("data AB = A | B", QualifiedName("B", M)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda("constructor$", Empty, App(meta("built"), App(App(Ref("constructor$"), QualRef("Unreached", "eliot.lang.Reach")), reachedSlot(Ref("AB$Meta$B")))))
      )
    )
  }

  it should "join a recursive field's meta into its own, which has no slot for it" in {
    namedValue("data Tree = Leaf | Node(left: Tree, v: Foo)", QualifiedName("Node", M)).asserting(
      _.runtimeStructure shouldBe Some(
        Lambda(
          "constructor$",
          Empty,
          Lambda(
            "left",
            Empty,
            Lambda(
              "v",
              Empty,
              App(
                meta("built"),
                App(
                  App(meta("joined"), Ref("left")),
                  App(App(Ref("constructor$"), QualRef("Unreached", "eliot.lang.Reach")), reachedSlot(App(Ref("Tree$Meta$Node"), Ref("v"))))
                )
              )
            )
          )
        )
      )
    )
  }

  "data structural meta" should "generate a record per constructor, a summary, their Meta instances and the field types" in {
    namedValues("data Option[A] = None | Some(value: A)").asserting(
      _.map(_.qualifiedName.value.name) should contain allOf (
        "Option$Meta$None",
        "Option$Meta$Some",
        "Option$Meta",
        "meta$Option$Some$value",
        "reach$Option$None",
        "reach$Option$Some"
      )
    )
  }

  it should "list every constructor's field types in order, ending in Unit" in {
    namedValue("data E[A, B] = L(a: A) | R(b: B, c: Foo)", QualifiedName("E$Fields", Qualifier.Meta(Qualifier.Type))).asserting(
      _.runtimeStructure shouldBe Some(arrow(Ref("A", T), arrow(Ref("B", T), arrow(Ref("Foo", T), QualRef("Unit", "eliot.lang.Unit")))))
    )
  }

  it should "leave a recursive field out of the field types" in {
    namedValue("data Tree = Leaf | Node(left: Tree, v: Foo)", QualifiedName("Tree$Fields", Qualifier.Meta(Qualifier.Type))).asserting(
      _.runtimeStructure shouldBe Some(arrow(Ref("Foo", T), QualRef("Unit", "eliot.lang.Unit")))
    )
  }

  it should "generate no structural meta of the structures themselves" in {
    namedValues("data Pixel(r: Foo)").asserting(
      _.map(_.qualifiedName.value.name).filter(_.contains("$Meta$Meta")) shouldBe Seq.empty
    )
  }

  "arrow-domain brace" should "state what its definition hands its code, reading the definition's parameters" in {
    namedValue(
      "def f[A, R](body uses *: A, onValue uses *: A {body} => R) uses *: R",
      QualifiedName("f$Handed$onValue$0", M)
    ).asserting(_.runtimeStructure shouldBe Some(Lambda("body", Empty, Lambda("onValue", Empty, Ref("body")))))
  }

  it should "list slots on a domain a type parameter does not name" in {
    namedValue("def f[R](onValue uses *: Foo {g} => R) uses *: R", QualifiedName("f$Handed$onValue$0", M)).asserting(
      _.runtimeStructure shouldBe Some(Lambda("constructor$", Empty, Lambda("onValue", Empty, App(Ref("constructor$"), Ref("g")))))
    )
  }

  it should "number its statement by the arrow whose domain it follows" in {
    namedValues("def f[A, S, R](combine uses *: A => S {g} => R) uses *: R").asserting(
      _.map(_.qualifiedName.value.name).filter(_.contains("$Handed$")) shouldBe Seq("f$Handed$combine$1")
    )
  }

  it should "be refused on a definition with a body" in {
    errorsOf("def f[R](onValue uses *: Foo {g} => R) uses *: R = onValue(x)").asserting(
      _ shouldBe Seq("A definition with a body cannot state what it hands its code; only a body-less definition states it.")
    )
  }

  it should "write a statement for a value parameter's arrow domain as for a code parameter's" in {
    namedValues("def f[B](initial: B, step: B {looped(initial, step)} => Foo => B): B").asserting(
      _.map(_.qualifiedName.value.name).filter(_.contains("$Handed$")) shouldBe Seq("f$Handed$step$0")
    )
  }

  it should "be refused on a value parameter of a definition with a body" in {
    errorsOf("def f[B](initial: B, step: B {g} => B): B = step(initial)").asserting(
      _ shouldBe Seq("A definition with a body cannot state what it hands its code; only a body-less definition states it.")
    )
  }

  "where clause (bounds Step 4c)" should "keep an integer literal in the predicate a compile-time BigInteger" in {
    namedValue("def f(a: Foo) where g(0): Foo", QualifiedName("f$Where", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Empty, App(Ref("g"), IntLit("0"))))
    }
  }

  it should "type each parameter by a meta binder of its own, after the definition's binders" in {
    namedValue("def f[A](h: A, a: Foo) where g(a): Foo", QualifiedName("f$Where", M)).asserting { nv =>
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        Lambda(
          "Meta$h",
          Ref("Type", T),
          Lambda(
            "Meta$a",
            Ref("Type", T),
            App(
              App(Ref("Function", T), Ref("Meta$h", T)),
              App(App(Ref("Function", T), Ref("Meta$a", T)), QualRef("Bool", "eliot.lang.Bool"))
            )
          )
        )
      )
    }
  }

  "bracket-aware qualifiers" should "make uppercase-only-brackets a type application" in {
    namedValue("data Box[A]\ndef f: R = Box[A]").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(Ref("Box", T), Ref("A", T)))
    }
  }

  it should "keep lowercase with brackets as Default with type args" in {
    namedValue("def f[C]: A = b[C]", QualifiedName("f", Qualifier.Default)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Ref("b", Qualifier.Default, Seq(Ref("C", T))))
    }
  }

  it should "make lowercase with brackets and parens Default with type args and value args" in {
    namedValue("def f: R = g[A](x)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(Ref("g", Qualifier.Default, Seq(Ref("A", T))), Ref("x")))
    }
  }

  it should "make uppercase with parens Default" in {
    namedValue("data Box[A](value: A)\ndef f: R = Box(x)").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(Ref("Box"), Ref("x")))
    }
  }

  it should "convert nested type application in brackets" in {
    namedValue("data Box[A]\ndef f: R = g[Box[A]]").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Ref("g", Qualifier.Default, Seq(App(Ref("Box", T), Ref("A", T)))))
    }
  }

  it should "convert integer literal as type argument" in {
    namedValue("def f: R = g[1]").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Ref("g", Qualifier.Default, Seq(IntLit("1"))))
    }
  }

  it should "convert multi-arg type application" in {
    namedValue("data Map[A, B]\ndef f: R = Map[A, B]").asserting { nv =>
      nv.runtimeStructure shouldBe Some(App(App(Ref("Map", T), Ref("A", T)), Ref("B", T)))
    }
  }

  // `docs/effects.md` D36: a row entry's binder is the handle's type, an ordinary binder of kind `Type` taken by a handle
  // parameter ahead of the others, and recorded as the entry's handle; the rowed parameter is a block taking the handle
  // its slot lists, of the type the callee's body hands it (`Handed`). A row never flows into a type.
  "effect-row sugar" should "mint one binder for the row, take its handle, and hand a rowed parameter one" in {
    namedValue("def f(x uses *, Suspend: String) uses *, Suspend: Unit").asserting { nv =>
      nv.signature.value.structure shouldBe Lambda(
        "Impl",
        Ref("Type", T),
        Lambda(
          "Handed",
          QualRef("Handed", "eliot.lang.Implementation"),
          App(
            App(Ref("Function", T), Ref("Impl", T)),
            App(
              App(Ref("Function", T), App(App(QualRef("Function", "eliot.lang.Function"), Ref("Handed", T)), Ref("String", T))),
              Ref("Unit", T)
            )
          )
        )
      )
    }
  }

  it should "record the binder as the entry's handle, and the handed binder as none" in {
    namedValue("def f(x uses *, Suspend: String) uses *, Suspend: Unit")
      .asserting(handleRecords(_) shouldBe Seq("Impl" -> "Suspend"))
  }

  it should "constrain the binder by the row's ability, applied to itself" in {
    namedValue("def f(x: String) uses Suspend: Unit").asserting { nv =>
      constraintShapes(nv) shouldBe Map("Impl" -> Seq(("Suspend", Seq(Ref("Impl", T)))))
    }
  }

  // The binding is the constraint's **first** type argument, where the ability's marker declares it — constraint and
  // marker must agree on the position, or every dispatch queries the wrong shape.
  it should "put the binding first among a parameterized effect's arguments" in {
    namedValue("def f(x: String) uses State[Account]: String").asserting { nv =>
      constraintShapes(nv) shouldBe Map("Impl" -> Seq(("State", Seq(Ref("Impl", T), Ref("Account", T)))))
    }
  }

  it should "mint one binder per distinct entry of a multi-effect row" in {
    namedValue("def f(x: String) uses Suspend, Abort: String").asserting { nv =>
      constraintShapes(nv) shouldBe Map(
        "Impl"  -> Seq(("Suspend", Seq(Ref("Impl", T)))),
        "Impl0" -> Seq(("Abort", Seq(Ref("Impl0", T))))
      )
    }
  }

  it should "avoid clashing a minted name with an existing generic parameter" in {
    namedValue("def f[Impl](x: Impl) uses Suspend: Impl").asserting { nv =>
      constraintShapes(nv).keySet shouldBe Set("Impl0")
    }
  }

  // A `~` constraint is an ability's, answered by the two-site search: it is left as written and mints nothing.
  "a `~` constraint" should "keep its subject binder and its arguments as written" in {
    namedValue("def f[T ~ Show[T]](x: T): String").asserting { nv =>
      constraintShapes(nv) shouldBe Map("T" -> Seq(("Show", Seq(Ref("T", T)))))
    }
  }

  it should "mint no binder for it" in {
    namedValue("def f[T ~ Show[T]](x: T): String").asserting(binderNames(_) shouldBe Seq("T"))
  }

  // The **empty row** `{}` names no ability, so it mints nothing. What it still says is "a value or a computation":
  // a top-level parameter row thunks whether or not it has entries.
  "the empty effect row" should "mint no binder and add no constraint" in {
    namedValue("def f[A](x uses *: A) uses *: A").asserting { nv =>
      (binderNames(nv), constraintShapes(nv)) shouldBe (Seq("A"), Map.empty)
    }
  }

  it should "thunk its parameter all the same" in {
    namedValue("def f[A](x uses *: A) uses *: A").asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda("A", Ref("Type", T), App(App(Ref("Function", T), thunk(Ref("A", T))), Ref("A", T)))
    }
  }

  // Only a *top-level* row thunks. A row in an arrow codomain (`onError: E => {} A`) is the callback's own row and
  // lowers to the bare payload, exactly as a return row does.
  it should "leave a row in an arrow codomain alone" in {
    (namedValue("def f[A](x uses *: A => A) uses *: A"), namedValue("def f[A](x: A => A): A"))
      .mapN { (rowed, plain) => rowed.signature.value.structure shouldBe plain.signature.value.structure }
  }

  "a data type" should "keep its fields' types as written" in {
    namedValue("data Box(value: A)", QualifiedName("Box", Qualifier.Default)).asserting { nv =>
      nv.signature.value.structure shouldBe App(App(Ref("Function", T), Ref("A", T)), Ref("Box", Qualifier.Type))
    }
  }

  "flat expressions" should "pass through as FlatExpression in core" in {
    namedValue("def f: T = b + c").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Flat(Seq(Ref("b"), Ref("+"), Ref("c"))))
    }
  }

  it should "pass through multi-atom expression" in {
    namedValue("def f: T = g x y").asserting { nv =>
      nv.runtimeStructure shouldBe Some(Flat(Seq(Ref("g"), Ref("x"), Ref("y"))))
    }
  }

  "fixity" should "be Application for plain def" in {
    namedValue("def f: T = b").asserting(_.fixity shouldBe Fixity.Application)
  }

  it should "be Prefix for prefix def" in {
    namedValue("prefix def !(a: T): T = a", QualifiedName("!", Qualifier.Default)).asserting {
      _.fixity shouldBe Fixity.Prefix
    }
  }

  it should "be Infix(Left) for infix left def" in {
    namedValue("infix left def +(a: T, b: T): T = a", QualifiedName("+", Qualifier.Default)).asserting {
      _.fixity shouldBe Fixity.Infix(Fixity.Associativity.Left)
    }
  }

  it should "be Infix(Left) for infix def with default associativity" in {
    namedValue("infix def or(a: T, b: T): T = a", QualifiedName("or", Qualifier.Default)).asserting {
      _.fixity shouldBe Fixity.Infix(Fixity.Associativity.Left)
    }
  }

  it should "be Postfix for postfix def" in {
    namedValue("postfix def ++(a: T): T = a", QualifiedName("++", Qualifier.Default)).asserting {
      _.fixity shouldBe Fixity.Postfix
    }
  }

  "complex scenarios" should "handle mixed functions and data" in {
    runEngineForCoreAST("data A\ndef f: A").asserting { ast =>
      ast.namedValues.map(_.qualifiedName.value) should contain allOf (QualifiedName(
        "A",
        Qualifier.Type
      ), QualifiedName("f", Qualifier.Default))
    }
  }

  it should "handle data with generic constructor" in {
    namedValue("data Pair[A, B](fst: A, snd: B)", QualifiedName("Pair", Qualifier.Default)).asserting { nv =>
      // The constructor type is (A :: Type) -> (B :: Type) -> Function(A, Function(B, Pair^Type(A)(B)))
      nv.signature.value.structure shouldBe
        Lambda(
          "A",
          Ref("Type", T),
          Lambda(
            "B",
            Ref("Type", T),
            App(
              App(Ref("Function", T), Ref("A", T)),
              App(App(Ref("Function", T), Ref("B", T)), App(App(Ref("Pair", Qualifier.Type), Ref("A", T)), Ref("B", T)))
            )
          )
        )
    }
  }

  it should "preserve import statements in core AST" in {
    runEngineForCoreAST("import a.b.C\ndef f: R").asserting { ast =>
      ast.importStatements.map(i => (i.packageNames.map(_.value) :+ i.moduleName.value).mkString(".")) shouldBe
        Seq("a.b.C")
    }
  }

  // Structural representation of expressions (ignores source positions)
  sealed trait ExprStructure
  case class Ref(name: String, qualifier: Qualifier = Qualifier.Default, typeArgs: Seq[ExprStructure] = Seq.empty)
      extends ExprStructure
  case class QualRef(name: String, module: String)                                extends ExprStructure
  case class App(target: ExprStructure, arg: ExprStructure)                       extends ExprStructure
  case class Lambda(param: String, paramType: ExprStructure, body: ExprStructure) extends ExprStructure
  case class IntLit(value: String)                                                extends ExprStructure
  case class StrLit(value: String)                                                extends ExprStructure
  case class Flat(parts: Seq[ExprStructure])                                      extends ExprStructure
  case object Empty                                                               extends ExprStructure

  extension (nv: NamedValue) {
    def runtimeStructure: Option[ExprStructure] = nv.runtime.map(_.value.structure)
  }

  extension (expr: Expression) {
    def structure: ExprStructure = expr match {
      case NamedValueReference(name, None, typeArgs)  =>
        Ref(name.value.name, name.value.qualifier, typeArgs.map(_.value.structure))
      case NamedValueReference(name, Some(qual), _) => QualRef(name.value.name, qual.value)
      case FunctionApplication(target, arg)         => App(target.value.structure, arg.value.structure)
      case FunctionLiteral(param, paramType, body)  =>
        Lambda(param.value, paramType.map(_.value.structure).getOrElse(Empty), body.value.structure)
      case IntegerLiteral(lit)                      => IntLit(lit.value)
      case StringLiteral(lit)                       => StrLit(lit.value)
      case FlatExpression(parts)                    => Flat(parts.map(_.value.structure))
    }
  }

  /** A primitive of `eliot.compiler.Meta`, as a generated statement names it. */
  private def meta(name: String): ExprStructure = QualRef(name, "eliot.compiler.Meta")

  /** `fieldOf(value, <constructor>, <read>)`, what a `match` eliminator hands a handler argument. */
  private def fieldOf(constructor: String, read: ExprStructure): ExprStructure =
    App(App(App(meta("fieldOf"), Ref("value")), Ref(constructor)), read)

  /** `reached(<slot>, <accessor>)`, a field read out of a constructor's slot. */
  private def reached(slot: ExprStructure, accessor: ExprStructure): ExprStructure =
    App(App(QualRef("reached", "eliot.lang.Reach"), slot), accessor)

  /** The scrutinee's slot of `constructor` in the structural meta of `data`. */
  private def slot(data: String, constructor: String): ExprStructure = App(Ref(s"reach$$$data$$$constructor"), Ref("value"))

  /** `Reached(<record>)`, a constructor's own slot. */
  private def reachedSlot(record: ExprStructure): ExprStructure = App(QualRef("Reached", "eliot.lang.Reach"), record)

  // What a top-level row lowers to: a thunk `Unit => A`, the shape a slot takes when it must not run its argument.
  private def thunk(payload: ExprStructure): ExprStructure =
    App(App(QualRef("Function", "eliot.lang.Function"), QualRef("Unit", "eliot.lang.Unit")), payload)

  /** Each generic binder of a definition as `name -> the ability its mark names`, in order — `None` for an ordinary
    * binder. The mark (`Impl: Implementation[Suspend]`) is the one record that a binder is a **binding**, so this is
    * how a test asks which binders the desugar minted.
    */
  private def binderNames(nv: NamedValue): Seq[String] = {
    def peel(structure: ExprStructure): Seq[String] = structure match {
      case Lambda(name, _, body) => name +: peel(body)
      case _                     => Seq.empty
    }
    peel(nv.signature.value.structure)
  }

  /** Each handle the declaration records, as its binder and the ability of the entry it stands for. */
  private def handleRecords(nv: NamedValue): Seq[(String, String)] =
    nv.effectRow.handles.map(handle => handle.binder -> nv.effectRow.returnEffects(handle.entry).abilityName.value)

  private def constraintShapes(nv: NamedValue): Map[String, Seq[(String, Seq[ExprStructure])]] =
    nv.paramConstraints.view
      .mapValues(_.map(c => (c.abilityName.value, c.typeArgs.map(_.structure))))
      .toMap

  private def findByNameAndImplQualifier(nvs: Seq[NamedValue], name: String): Seq[NamedValue] =
    nvs.filter { nv =>
      nv.qualifiedName.value.name == name && nv.qualifiedName.value.qualifier.isInstanceOf[Qualifier.AbilityImplementation]
    }

  // --- a `{ … }` block in a signature (SignatureBlockChecker) ---

  it should "reject an effect row written inside a type argument" in {
    // Type arguments are parsed by the value expression parser, where a leading `{` is always a block, so
    // `List[{Console} Unit]` reads as a block juxtaposed with `Unit` and reached match desugaring un-lowered —
    // an IllegalStateException. It is a located error now; lowering it instead would silently mean `List[Unit]`.
    errorsOf("def gathered: List[{Console} Unit] = empty")
      .asserting(_ shouldBe Seq("A `{ … }` block may not appear in a type. An effect row is written on a " +
        "definition's return type, or on a parameter's or field's type — never inside a type argument."))
  }

  it should "accept a block in a runtime body" in {
    namedValue("def f: A = { val x = b\n x }").asserting(_.runtime.isDefined shouldBe true)
  }

  private def errorsOf(source: String): IO[Seq[String]] =
    runGenerator(source, CoreAST.Key(file)).map { case (errors, _) => errors.map(_.message) }

  private def namedValue(source: String, name: QualifiedName = QualifiedName("f", Qualifier.Default)): IO[NamedValue] =
    runEngineForCoreAST(source).map(_.namedValues.find(_.qualifiedName.value == name).get)

  private def namedValues(source: String): IO[Seq[NamedValue]] =
    runEngineForCoreAST(source).map(_.namedValues)

  private def runEngineForCoreAST(source: String): IO[com.vanillasource.eliot.eliotc.core.fact.AST] =
    runGenerator(source, CoreAST.Key(file)).flatMap { case (errors, facts) =>
      if (errors.nonEmpty) {
        IO.raiseError(new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}"))
      } else {
        facts.values.collectFirst { case CoreAST(_, ast) => ast.value } match {
          case Some(ast) => IO.pure(ast)
          case None      => IO.raiseError(new Exception("No CoreAST found"))
        }
      }
    }
}
