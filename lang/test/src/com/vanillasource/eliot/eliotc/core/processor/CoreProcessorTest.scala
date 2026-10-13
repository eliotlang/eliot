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
  // The meta namespace of the plain (Default) namespace — where a plain def's `^Meta`/`^Where` companion
  // lives. A companion keeps its callee's qualifier, so an ability implementation's is a different one.
  private val M = Qualifier.Meta(Qualifier.Default)

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

  it should "generate impl marker, Cases type, and handleCases" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      findByNameAndImplQualifier(nvs, "PatternMatch") should not be empty
      findByNameAndImplQualifier(nvs, "Cases") should not be empty
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
      // handleCases[A, R](value: Box[A], cases: Function[Function[Function[A, R], R], R]): R
      val churchCasesType = App(App(Ref("Function", T), App(App(Ref("Function", T), App(App(Ref("Function", T), Ref("A", T)), Ref("R", T))), Ref("R", T))), Ref("R", T))
      nv.signature.value.structure shouldBe Lambda(
        "A",
        Ref("Type", T),
        Lambda(
          "R",
          Ref("Type", T),
          App(
            App(Ref("Function", T), App(Ref("Box", T), Ref("A", T))),
            App(App(Ref("Function", T), churchCasesType), Ref("R", T))
          )
        )
      )
    }
  }

  it should "have abstract Cases type for single-constructor data" in {
    namedValues("data Box[A](value: A)").asserting { nvs =>
      val nv = findByNameAndImplQualifier(nvs, "Cases").head
      nv.runtime shouldBe None
    }
  }

  it should "have abstract Cases type for enum-like data" in {
    namedValues("data Color = Red | Green | Blue").asserting { nvs =>
      val nv = findByNameAndImplQualifier(nvs, "Cases").head
      nv.runtime shouldBe None
    }
  }

  it should "avoid clashing result parameter name with existing generic parameter R" in {
    namedValues("data Foo[R](value: R)").asserting { nvs =>
      val nv = findByNameAndImplQualifier(nvs, "handleCases").head
      // handleCases[R, R0](value: Foo[R], cases: Function[Function[Function[R, R0], R0], R0]): R0
      val churchCasesType = App(App(Ref("Function", T), App(App(Ref("Function", T), App(App(Ref("Function", T), Ref("R", T)), Ref("R0", T))), Ref("R0", T))), Ref("R0", T))
      nv.signature.value.structure shouldBe Lambda(
        "R",
        Ref("Type", T),
        Lambda(
          "R0",
          Ref("Type", T),
          App(
            App(Ref("Function", T), App(Ref("Foo", T), Ref("R", T))),
            App(App(Ref("Function", T), churchCasesType), Ref("R0", T))
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

  it should "generate a slot accessor from the meta structure onto the domain" in {
    namedValue("type Int {range: Interval}", QualifiedName("range", Qualifier.Default)).asserting { nv =>
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

  "transfer brace (bounds Step 4b)" should "generate a ^Meta transfer companion in the Meta namespace" in {
    namedValues("type Foo {bar: D}\ndef f(a: Foo): Foo {a.bar}").asserting { nvs =>
      nvs.map(_.qualifiedName.value) should contain(QualifiedName("f", M))
    }
  }

  it should "type the transfer companion over the meta types (T$Meta suffix), with no lookup" in {
    namedValue("type Foo {bar: D}\ndef f(a: Foo): Foo {a.bar}", QualifiedName("f", M)).asserting { nv =>
      nv.signature.value.structure shouldBe App(
        App(Ref("Function", T), Ref("Foo$Meta", T)),
        Ref("Foo$Meta", T)
      )
    }
  }

  it should "build the result via the meta value constructor" in {
    namedValue("type Foo {bar: D}\ndef f(a: Foo): Foo {a}", QualifiedName("f", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Empty, App(Ref("Foo$Meta"), Ref("a"))))
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

  it should "keep a parameter the brace never mentions at its own type (a slotless type has no T$Meta)" in {
    namedValue("type Foo {bar: D}\ndef f(h: Handle): Foo {g(0)}", QualifiedName("f", M)).asserting { nv =>
      nv.signature.value.structure shouldBe App(App(Ref("Function", T), Ref("Handle", T)), Ref("Foo$Meta", T))
    }
  }

  it should "still retype a parameter the brace does mention" in {
    namedValue("type Foo {bar: D}\ndef f(h: Handle, a: Foo): Foo {a.bar}", QualifiedName("f", M))
      .asserting { nv =>
        nv.signature.value.structure shouldBe App(
          App(Ref("Function", T), Ref("Handle", T)),
          App(App(Ref("Function", T), Ref("Foo$Meta", T)), Ref("Foo$Meta", T))
        )
      }
  }

  it should "keep an integer literal in the brace a compile-time BigInteger" in {
    namedValue("type Foo {bar: D}\ndef f(a: Foo): Foo {g(0)}", QualifiedName("f", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Empty, App(Ref("Foo$Meta"), App(Ref("g"), IntLit("0")))))
    }
  }

  "where clause (bounds Step 4c)" should "keep an integer literal in the predicate a compile-time BigInteger" in {
    namedValue("def f(a: Foo): Foo where g(0)", QualifiedName("f$Where", M)).asserting { nv =>
      nv.runtimeStructure shouldBe Some(Lambda("a", Empty, App(Ref("g"), IntLit("0"))))
    }
  }

  it should "keep a parameter the predicate never mentions at its own type" in {
    namedValue("def f(h: Handle, a: Foo): Foo where g(a)", QualifiedName("f$Where", M)).asserting { nv =>
      nv.signature.value.structure shouldBe App(
        App(Ref("Function", T), Ref("Handle", T)),
        App(App(Ref("Function", T), Ref("Foo$Meta", T)), QualRef("Bool", "eliot.lang.Bool"))
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

  // Effects v6 (`docs/effects.md` §9): a row entry and a `~` constraint each become **one phantom generic binder**,
  // declared `Implementation[A]` — the **mark** that says it is a binding and names the ability it binds (§9.2) — and
  // occurring in no type, so rows still never flow into types. The binder's value, written later by the `row` phase,
  // is an implementation. Three rewrites and nothing else: the return row vanishes onto a binder, a `~` constraint
  // gains that binder as its **first** type argument, and a top-level row in a parameter or a `data` field thunks to
  // `Unit => A`.
  "effect-row sugar" should "mint one marked binder for the row and thunk a rowed parameter" in {
    namedValue("def f(x uses *, Suspend: String) uses Suspend: Unit").asserting { nv =>
      nv.signature.value.structure shouldBe Lambda(
        "Impl",
        App(QualRef("Implementation", "eliot.lang.Implementation"), Ref("Suspend", T)),
        App(App(Ref("Function", T), thunk(Ref("String", T))), Ref("Unit", T))
      )
    }
  }

  it should "record the binder as a binding in its declared type, and nowhere else" in {
    namedValue("def f(x uses *, Suspend: String) uses Suspend: Unit")
      .asserting(binderMarks(_) shouldBe Seq("Impl" -> Some("Suspend")))
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

  // A `~` constraint keeps the binder it was written on as its subject — that is what constraint scoping and the
  // superability closure read — and only gains the binding ahead of it.
  "a `~` constraint" should "keep its subject binder and gain the binding as its first argument" in {
    namedValue("def f[T ~ Show[T]](x: T): String").asserting { nv =>
      constraintShapes(nv) shouldBe Map("T" -> Seq(("Show", Seq(Ref("Impl", T), Ref("T", T)))))
    }
  }

  it should "mint an unconstrained binder for it, marked for the constraint's ability" in {
    namedValue("def f[T ~ Show[T]](x: T): String")
      .asserting(binderMarks(_) shouldBe Seq("Impl" -> Some("Show"), "T" -> None))
  }

  // The **empty row** `{}` names no ability, so it mints nothing. What it still says is "a value or a computation":
  // a top-level parameter row thunks whether or not it has entries.
  "the empty effect row" should "mint no binder and add no constraint" in {
    namedValue("def f[A](x uses *: A): A").asserting { nv =>
      (binderMarks(nv), constraintShapes(nv)) shouldBe (Seq("A" -> None), Map.empty)
    }
  }

  it should "thunk its parameter all the same" in {
    namedValue("def f[A](x uses *: A): A").asserting { nv =>
      nv.signature.value.structure shouldBe
        Lambda("A", Ref("Type", T), App(App(Ref("Function", T), thunk(Ref("A", T))), Ref("A", T)))
    }
  }

  // Only a *top-level* row thunks. A row in an arrow codomain (`onError: E => {} A`) is the callback's own row and
  // lowers to the bare payload, exactly as a return row does.
  it should "leave a row in an arrow codomain alone" in {
    (namedValue("def f[A](x uses *: A => A): A"), namedValue("def f[A](x: A => A): A"))
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

  // What a top-level row lowers to: a thunk `Unit => A`, the shape a slot takes when it must not run its argument.
  private def thunk(payload: ExprStructure): ExprStructure =
    App(App(QualRef("Function", "eliot.lang.Function"), QualRef("Unit", "eliot.lang.Unit")), payload)

  /** Each generic binder of a definition as `name -> the ability its mark names`, in order — `None` for an ordinary
    * binder. The mark (`Impl: Implementation[Suspend]`) is the one record that a binder is a **binding**, so this is
    * how a test asks which binders the desugar minted.
    */
  private def binderMarks(nv: NamedValue): Seq[(String, Option[String])] = {
    def peel(structure: ExprStructure): Seq[(String, Option[String])] = structure match {
      case Lambda(name, paramType, body) =>
        val marked = paramType match {
          case App(QualRef("Implementation", "eliot.lang.Implementation"), Ref(ability, _, _)) => Some(ability)
          case _                                                                              => None
        }
        (name -> marked) +: peel(body)
      case _                             => Seq.empty
    }
    peel(nv.signature.value.structure)
  }

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
