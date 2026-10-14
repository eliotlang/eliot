package com.vanillasource.eliot.eliotc.ability

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicValue}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors

class AbilityImplementationCheckProcessorTest
    extends ProcessorTest(LangProcessors(systemModules = ProcessorTest.systemModulesWithoutInt)*) {
  "ability implementation check" should "pass when all methods are provided with correct signatures" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "fail when a required ability method is missing" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A\ndef display(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq("Ability implementation is missing method 'display'." at "Show"))
  }

  it should "fail when an extra method not in the ability is defined" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x\ndef extra(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq("Method not defined in ability." at "extra"))
  }

  it should "fail when an implementation method has the wrong signature" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndata Bool\nimplement Show[Int] { def show(x: Bool): Bool = x }\ndef f(x: Int): Int = show(x)"
    ).asserting { errors =>
      errors.length shouldBe 1
      errors.head.message should include("Signature of implementation does not match the ability definition")
      errors.head.highlight shouldBe "show"
    }
  }

  it should "pass when a non-abstract ability method is not present in the implementation" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A\ndef extra(x: A): A = x }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "resolve default ability implementation when called" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A\ndef extra(x: A): A = x }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = extra(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "resolve default ability implementation that calls another ability method" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A\ndef display(x: A): A = show(x) }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = display(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "fail when no implementations are provided at all for an ability with all-default methods" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A = x }\ndata Int\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq("No ability implementation found for ability 'Show' with type arguments [Int]." at "show"))
  }

  it should "resolve default ability implementation that calls another default ability implementation" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A = x\ndef display(x: A): A = show(x) }\ndata Int\nimplement Show[Int]\ndef f(a: Int): Int = display(a)"
    ).asserting(_ shouldBe Seq.empty)
  }

  // --- Ability call resolution tests (migrated from AbilityCheckProcessorTest) ---

  "ability calls" should "succeed when calling ability with generic type parameter covered by constraint" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f[A ~ Show[A]](x: A): A = show(x)",
      Seq(intType)
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "succeed when calling ability with default generic type parameter covered by constraint" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f[A ~ Show](x: A): A = show(x)",
      Seq(intType)
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "support empty abilities to be declared" in {
    runEngineForErrors(
      "ability Marker[A]\ndata Int\nimplement Marker[Int]\ndef f[A ~ Marker](x: A): A",
      Seq(intType)
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "report overlap at definition time when two impls of the same ability have unifiable patterns" in {
    // A generic `Show[A]` and a specific `Show[Int]` → distinct `(ability, pattern)` identities that coexist, yet their
    // marker signatures unify → overlap. The error fires at the impls, not at the call site. (Two *identical* `Show[Int]`
    // would be the same identity and collide as a duplicate name instead — the lint is about distinct unifiable patterns.)
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement[A] Show[A] { def show(x: A): A = x }\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(errors => errors.count(_.message.contains("Overlapping ability implementation")) shouldBe 2)
  }

  it should "report overlap when one impl's pattern generalises another's" in {
    // `Show[Box[A]]` and `Show[Box[Int]]` both match `Show[Box[Int]]` → overlap at definition time.
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndata Box[A]\nimplement[A] Show[Box[A]] { def show(x: Box[A]): Box[A] = x }\nimplement Show[Box[Int]] { def show(x: Box[Int]): Box[Int] = x }\ndef f(x: Box[Int]): Box[Int] = show(x)"
    ).asserting(errors => errors.count(_.message.contains("Overlapping ability implementation")) shouldBe 2)
  }

  it should "not report overlap for impls of the same ability on distinct type constructors" in {
    // `Show[Int]` and `Show[Bool]` don't overlap structurally.
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndata Bool\nimplement Show[Int] { def show(x: Int): Int = x }\nimplement Show[Bool] { def show(x: Bool): Bool = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(errors => errors.count(_.message.contains("Overlapping ability implementation")) shouldBe 0)
  }

  it should "dispatch to the right impl when two impls of the same ability coexist in one module" in {
    // Two implementations of the same ability are stored under distinct per-ability indices in the
    // qualifier. Selection walks the synthetic marker function of each candidate impl to decide
    // which one matches the concrete call-site type.
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndata Bool\nimplement Show[Int] { def show(x: Int): Int = x }\nimplement Show[Bool] { def show(x: Bool): Bool = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "support empty ability implementations" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\nimplement Show[Int] { def show(x: Int): Int = x }\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq.empty)
  }

  it should "fail when calling ability without constraint and no implementation exists" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndef f[A](x: A): A = show(x)",
      Seq(intType)
    ).asserting(_ shouldBe Seq("No ability implementation found for ability 'Show' with type arguments [Int]." at "show"))
  }

  it should "fail when no implementation exists for the concrete type" in {
    runEngineForErrors(
      "ability Show[A] { def show(x: A): A }\ndata Int\ndef f(x: Int): Int = show(x)"
    ).asserting(_ shouldBe Seq("No ability implementation found for ability 'Show' with type arguments [Int]." at "show"))
  }

  it should "check derived abilities" in {
    runEngineForErrors("""
        def someString: String

        ability Show[A] {
          def show(a: A): String
        }

        implement Show[String] {
          def show(str: String): String = str
        }

        data Box[A](content: A)

        implement[A ~ Show] Show[Box[A]] {
          def show(box: Box[A]): String = show(content(box))
        }

        def f: String = show(Box(someString))
    """).asserting(_ shouldBe Seq.empty)
  }

  // --- AbilityMatcher-focused regression tests ---

  it should "dispatch multi-parameter abilities by position" in {
    // Two ability-level type params — the impl's marker pattern zips `[A, B]` with `[Int, String]` in the
    // correct order, so the dispatch only succeeds if the matcher respects declaration order.
    runEngineForErrors("""
        data Int
        def someString: String

        ability Convert[A, B] {
          def convert(x: A): B
        }

        implement Convert[Int, String] {
          def convert(x: Int): String = someString
        }

        def f(x: Int): String = convert(x)
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "fail a multi-parameter ability dispatch when argument order does not match" in {
    // `Convert[String, Int]` has no impl; matching must not collapse `[Int, String]` to `[String, Int]`.
    runEngineForErrors("""
        data Int
        def someString: String

        ability Convert[A, B] {
          def convert(x: A): B
        }

        implement Convert[Int, String] {
          def convert(x: Int): String = someString
        }

        def f(x: String): Int = convert(x)
    """).asserting(errors => errors.exists(_.message.contains("No ability implementation found for ability 'Convert'")) shouldBe true)
  }

  it should "preserve parameterised type arguments through a generic impl's binding" in {
    // A derived impl binds its type parameter to a *parameterised* type (`Pair[String, String]`). The inner
    // `show(content(box))` can only type-check if the matcher propagates the exact `Pair[String, String]`
    // GroundValue — not a `Type` fallback that would make `Show[A]` lookup fail.
    runEngineForErrors("""
        def someString: String

        ability Show[A] {
          def show(a: A): String
        }

        data Pair[F, S](first: F, second: S)
        data Box[A](content: A)

        implement Show[Pair[String, String]] {
          def show(p: Pair[String, String]): String = someString
        }

        implement[A ~ Show] Show[Box[A]] {
          def show(box: Box[A]): String = show(content(box))
        }

        def f: String = show(Box(Pair(someString, someString)))
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "check signature with a generic impl that binds a type param to a parameterised type" in {
    // Signature compatibility walks the unifier with the impl marker's pattern args bound to the abstract
    // method's ability-level type param. When the pattern arg is parameterised (`Box[A]` here), the abstract
    // `show: Show[A].A -> String` must unify against the impl `show: Box[A] -> String` via structural unification.
    runEngineForErrors("""
        def someString: String

        ability Show[A] {
          def show(a: A): String
        }

        data Box[A](content: A)

        implement[A] Show[Box[A]] {
          def show(box: Box[A]): String = someString
        }

        def f(x: Box[String]): String = show(x)
    """).asserting(_ shouldBe Seq.empty)
  }

  // --- Higher-kinded abilities ---

  "higher-kinded abilities" should "resolve when implementing a higher-kinded ability for a concrete type" in {
    runEngineForErrors("""
        ability Container[F[_]] {
          def wrap(s: String): F[String]
        }

        data Box[A](content: A)

        implement Container[Box] {
          def wrap(s: String): Box[String] = Box(s)
        }

        def someString: String
        def f: Box[String] = wrap(someString)
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "fail when no implementation exists for the higher-kinded type" in {
    runEngineForErrors("""
        ability Container[F[_]] {
          def wrap(s: String): F[String]
        }

        data Box[A](content: A)

        def someString: String
        def f: Box[String] = wrap(someString)
    """).asserting(_.nonEmpty shouldBe true)
  }

  it should "dispatch a higher-kinded ability to the right implementation when multiple are in scope" in {
    // Two impls for the same HKT ability; the expected return type is what drives dispatch.
    // Relies on injectivity decomposition solving `?F := Box` (not `?F := Other`) purely from the call context.
    runEngineForErrors("""
        ability Container[F[_]] {
          def wrap(s: String): F[String]
        }

        data Box[A](content: A)
        data Other[A](value: A)

        implement Container[Box] {
          def wrap(s: String): Box[String] = Box(s)
        }

        implement Container[Other] {
          def wrap(s: String): Other[String] = Other(s)
        }

        def someString: String
        def f: Box[String] = wrap(someString)
        def g: Other[String] = wrap(someString)
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "fail when the higher-kinded ability is called at a type with no matching impl but another impl exists" in {
    // One impl for Container[Box]; the call site expects Container[Other]. Without this working, either the wrong
    // impl would be picked (if decomposition were sloppy) or no impl at all would be checked.
    runEngineForErrors("""
        ability Container[F[_]] {
          def wrap(s: String): F[String]
        }

        data Box[A](content: A)
        data Other[A]

        implement Container[Box] {
          def wrap(s: String): Box[String] = Box(s)
        }

        def someString: String
        def f: Other[String] = wrap(someString)
    """).asserting(errors => errors.exists(_.message.contains("Container")) shouldBe true)
  }

  // --- Higher-kinded ability *constraint on a function parameter* ([F[_] ~ Cap]) — effects M0 ---

  it should "resolve a higher-kinded ability constraint on a function parameter" in {
    // The net-new M0 piece: a `[F[_] ~ Monad]` constraint whose constrained parameter is itself higher-kinded. The
    // `flatMap` call is constraint-covered at the abstract definition and resolved to `Monad[Box]` at the concrete
    // use site (F := Box), mirroring the existing `Container[F[_]]` tests but driving dispatch through a param.
    runEngineForErrors("""
        ability Monad[F[_]] {
          def flatMap[A, B](fa: F[A], f: Function[A, F[B]]): F[B]
        }

        data Box[A](content: A)

        implement Monad[Box] {
          def flatMap[A, B](fa: Box[A], f: Function[A, Box[B]]): Box[B] = f(content(fa))
        }

        def someBox: Box[String]
        def runTwice[F[_] ~ Monad](fa: F[String]): F[String] = flatMap(fa, ignore -> fa)
        def f: Box[String] = runTwice(someBox)
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "fail a higher-kinded ability constraint when the carrier type has no implementation" in {
    // `Bag` implements no `Monad`, so resolving `flatMap` at F := Bag must fail at the concrete use site.
    runEngineForErrors("""
        ability Monad[F[_]] {
          def flatMap[A, B](fa: F[A], f: Function[A, F[B]]): F[B]
        }

        data Box[A](content: A)
        data Bag[A](content: A)

        implement Monad[Box] {
          def flatMap[A, B](fa: Box[A], f: Function[A, Box[B]]): Box[B] = f(content(fa))
        }

        def someBag: Bag[String]
        def runTwice[F[_] ~ Monad](fa: F[String]): F[String] = flatMap(fa, ignore -> fa)
        def f: Bag[String] = runTwice(someBag)
    """).asserting(_.nonEmpty shouldBe true)
  }

  // --- a constrained generic instance, `implement[F[_] ~ Suspend] Console[F]` ---
  //
  // The `{E} A`-desugars-to-a-carrier case that used to open this group is deleted: effects v6 has no carrier, so the
  // sugar's meaning is not the M0 form and there is nothing to compare. The M0 form itself — a constructor class
  // reached through `[F[_] ~ Monad]` — is asserted above and is the durable half. What the two cases below assert is
  // likewise not about effects: an implementation generic over its own pattern and *constrained*, resolving at a
  // concrete argument; and an obligation the signature does not name, deferred to the use site. Neither ability spells
  // a row, because a member of a *parameterised* ability may not carry one (its parameters are inferred at each call
  // and stand between the binding and the member's own).

  it should "resolve a constrained higher-kinded instance through a recursive constraint" in {
    // A generic instance `Console[F]` constrained by another ability of the same parameter (`F ~ Suspend`), never
    // pinned to a concrete argument. Resolving `cprintln` at `F := Mio` must match the `[F[_] ~ Suspend] Console[F]`
    // instance and, in turn, discharge its own `Suspend[Mio]` obligation from the body's `suspend` call.
    runEngineForErrors("""
        ability Suspend[F[_]] {
          def suspend[A](thunk: Function[Unit, A]): F[A]
        }

        ability Console[F[_]] {
          def cprintln(s: String): F[String]
        }

        data Mio[A](block: Function[Unit, A])

        implement Suspend[Mio] {
          def suspend[A](thunk: Function[Unit, A]): Mio[A] = Mio(thunk)
        }

        implement[F[_] ~ Suspend] Console[F] {
          def cprintln(s: String): F[String] = suspend(_ -> s)
        }

        def program[F[_] ~ Console](s: String): F[String] = cprintln(s)
        def f: Mio[String] = program("hello")
    """).asserting(_ shouldBe Seq.empty)
  }

  it should "defer an uncovered ability call to the concrete use site" in {
    // `program` is constrained only by `Console[F]`, yet its body calls `flatMap`, an `Effect[F]` operation on the
    // same parameter that its signature never names. At `F := Mio` the instance exists, so the deferred resolution
    // succeeds — the use-site-verification cornerstone applied to an obligation no declaration discharges abstractly.
    runEngineForErrors("""
        ability Effect[F[_]] {
          def flatMap[A, B](fa: F[A], f: Function[A, F[B]]): F[B]
        }

        ability Suspend[F[_]] {
          def suspend[A](thunk: Function[Unit, A]): F[A]
        }

        ability Console[F[_]] {
          def cprintln(s: String): F[String]
        }

        data Mio[A](block: Function[Unit, A])

        implement Effect[Mio] {
          def flatMap[A, B](fa: Mio[A], f: Function[A, Mio[B]]): Mio[B] = f(apply(block(fa), miounit))
        }

        implement Suspend[Mio] {
          def suspend[A](thunk: Function[Unit, A]): Mio[A] = Mio(thunk)
        }

        implement[F[_] ~ Suspend] Console[F] {
          def cprintln(s: String): F[String] = suspend(_ -> s)
        }

        def miounit: Unit
        def program[F[_] ~ Console](s: String): F[String] = flatMap(cprintln(s), x -> cprintln(x))
        def f: Mio[String] = program("hello")
    """).asserting(_ shouldBe Seq.empty)
  }

  private val intType: GroundValue =
    GroundValue.Structure(
      ValueFQN(testModuleName, QualifiedName("Int", Qualifier.Type)),
      Seq.empty,
      GroundValue.Type
    )

  private def runEngineForErrors(
      source: String,
      typeArgs: Seq[GroundValue] = Seq.empty
  ): IO[Seq[TestError]] =
    runGenerator(
      source,
      MonomorphicValue.Key(ValueFQN(testModuleName, QualifiedName("f", Qualifier.Default)), typeArgs),
      systemImports
    ).map(result => toTestErrors(result._1))
}
