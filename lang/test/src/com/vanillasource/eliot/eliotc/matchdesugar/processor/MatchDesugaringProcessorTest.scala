package com.vanillasource.eliot.eliotc.matchdesugar.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.matchdesugar.fact.{MatchDesugaredExpression, MatchDesugaredValue}
import com.vanillasource.eliot.eliotc.module.fact.{ValueFQN, ModuleName => ModuleName2}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import MatchDesugaredExpressionMatchers.*

class MatchDesugaringProcessorTest
    extends ProcessorTest(LangProcessors(systemModules = Seq(ModuleName2.systemFunctionModuleName))*) {
  private val testMN = ModuleName2(Seq.empty, "Test")

  // A deliberately minimal ambient set (only `Function` is auto-imported, via the `systemModules` override above); the
  // `PatternMatch`/`TypeMatch` declarations are loaded by FQN, hence `compilerInternalPackage`.
  override val systemImports = Seq(
    SystemImport("Function", "data Function[A, B]\ndef apply[A, B](f: Function[A, B], a: A): B"),
    SystemImport("PatternMatch", ProcessorTest.patternMatchAbilityStub, ProcessorTest.compilerInternalPackage),
    SystemImport("TypeMatch", ProcessorTest.typeMatchAbilityStub, ProcessorTest.compilerInternalPackage)
  )

  "match desugaring" should "pass through values without match expressions" in {
    runEngineForValue("data T\ndef integerLiteral[V]: T\ndef main: T = 1").asserting {
      case Some(IntValue(v)) => v shouldBe BigInt(1)
      case x                 => fail(s"unexpected: $x")
    }
  }

  it should "pass through abstract values without runtime" in {
    runEngineForValue("data T\ndef main: T").asserting(_ shouldBe None)
  }

  it should "desugar match on nullary constructors to handleCases call" in {
    runEngineForValue(
      "data Bool = True | False\ndef main: Bool = True match { case True -> True case False -> False }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(ValRef(hc), ValRef(scrutinee)), FunLit("_", ValRef(trueBody))), FunLit("_", ValRef(falseBody)))) =>
        isHandleCases(hc) shouldBe true
        scrutinee shouldBe vfqn("True")
        trueBody shouldBe vfqn("True")
        falseBody shouldBe vfqn("False")
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "order handlers by constructor definition order" in {
    runEngineForValue(
      "data Color = Red | Green | Blue\ndef main: Color = Red match { case Blue -> Blue case Red -> Red case Green -> Green }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(FunApp(ValRef(hc), _), FunLit("_", ValRef(first))), FunLit("_", ValRef(second))), FunLit("_", ValRef(third)))) =>
        isHandleCases(hc) shouldBe true
        first shouldBe vfqn("Red")
        second shouldBe vfqn("Green")
        third shouldBe vfqn("Blue")
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar match with constructor field binding" in {
    runEngineForValue(
      "data Maybe = Nothing | Just(value: Maybe)\ndef main: Maybe = Nothing match { case Nothing -> Nothing case Just(v) -> v }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(ValRef(hc), ValRef(scrutinee)), FunLit("_", ValRef(nothingBody))), FunLit("v", ParamRef(paramName)))) =>
        isHandleCases(hc) shouldBe true
        scrutinee shouldBe vfqn("Nothing")
        nothingBody shouldBe vfqn("Nothing")
        paramName shouldBe "v"
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar match with multiple constructor fields as curried lambdas" in {
    runEngineForValue(
      "data Pair = MkPair(first: Pair, second: Pair) | Empty\ndef main: Pair = Empty match { case MkPair(a, b) -> a case Empty -> Empty }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(ValRef(hc), ValRef(_)), FunLit("a", FunLit("b", ParamRef(innerParam)))), FunLit("_", ValRef(emptyBody)))) =>
        isHandleCases(hc) shouldBe true
        innerParam shouldBe "a"
        emptyBody shouldBe vfqn("Empty")
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "generate wildcard handlers for unmatched constructors" in {
    runEngineForValue(
      "data Color = Red | Green | Blue\ndef main: Color = Red match { case Red -> Red case _ -> Green }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(FunApp(ValRef(hc), _), FunLit("_", ValRef(redBody))), FunLit("_", ValRef(greenBody))), FunLit("_", ValRef(blueBody)))) =>
        isHandleCases(hc) shouldBe true
        redBody shouldBe vfqn("Red")
        greenBody shouldBe vfqn("Green")
        blueBody shouldBe vfqn("Green")
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "generate wildcard handlers with correct arity for non-nullary constructors" in {
    runEngineForValue(
      "data Maybe = Nothing | Just(value: Maybe)\ndef main: Maybe = Nothing match { case Nothing -> Nothing case _ -> Nothing }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(ValRef(hc), _), FunLit("_", ValRef(nothingBody))), FunLit("_", ValRef(justBody)))) =>
        isHandleCases(hc) shouldBe true
        nothingBody shouldBe vfqn("Nothing")
        justBody shouldBe vfqn("Nothing")
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar match expression inside lambda body" in {
    runEngineForValue(
      "data Bool = True | False\ndef main: Bool = b: Bool -> b match { case True -> True case False -> False }"
    ).asserting {
      case Some(FunLit("b", FunApp(FunApp(FunApp(ValRef(hc), ParamRef(scrutinee)), _), _))) =>
        isHandleCases(hc) shouldBe true
        scrutinee shouldBe "b"
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar embedded constructor patterns as nested matches" in {
    runEngineForValue(
      "data Inner = MkInner\ndata Outer = Wrap(inner: Inner) | Empty\ndef main: Outer = Empty match { case Wrap(MkInner) -> Empty case Empty -> Empty }"
    ).asserting {
      case Some(FunApp(FunApp(FunApp(ValRef(outerHc), _), wrapHandler), FunLit("_", ValRef(emptyBody)))) =>
        isHandleCases(outerHc) shouldBe true
        emptyBody shouldBe vfqn("Empty")
        wrapHandler match {
          case FunLit("$match_field", FunApp(FunApp(ValRef(innerHc), ParamRef(innerScrutinee)), FunLit("_", ValRef(mkInnerBody)))) =>
            isHandleCases(innerHc) shouldBe true
            innerScrutinee shouldBe "$match_field"
            mkInnerBody shouldBe vfqn("Empty")
          case x =>
            fail(s"unexpected Wrap handler: $x")
        }
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "report error for non-exhaustive match" in {
    runEngineForErrors(
      "data Color = Red | Green | Blue\ndef main: Color = Red match { case Red -> Red case Blue -> Blue }"
    ).asserting(_ shouldBe Seq("Non-exhaustive match. Missing constructors: Green." at "Red"))
  }

  it should "report error when match has no constructor patterns" in {
    runEngineForErrors(
      "data Bool = True | False\ndef main: Bool = True match { case x -> x }"
    ).asserting(_ shouldBe Seq("Match expression must have at least one constructor pattern." at "x"))
  }

  it should "report error when wildcard-only match has no constructor patterns" in {
    runEngineForErrors(
      "data Bool = True | False\ndef main: Bool = True match { case _ -> True }"
    ).asserting(_ shouldBe Seq("Match expression must have at least one constructor pattern." at "_"))
  }

  it should "desugar type match with single constructor to typeMatch call" in {
    runEngineForValue(
      "data Type\ndata S\ndata Person[NAME: S]\ndef integerLiteral[V]: S\ndef main: S = t: Type -> t match { case Person[name] -> name case _ -> 1 }"
    ).asserting {
      case Some(FunLit("t", FunApp(FunApp(FunApp(ValRef(tm), ParamRef(scrutinee)), FunLit("name", ParamRef(nameParam))), FunLit("_", IntValue(fallback))))) =>
        isTypeMatch(tm) shouldBe true
        scrutinee shouldBe "t"
        nameParam shouldBe "name"
        fallback shouldBe BigInt(1)
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar type match with multiple constructors as chained typeMatch calls" in {
    runEngineForValue(
      "data Type\ndata S\ndata A[X: S]\ndata B[Y: S]\ndef integerLiteral[V]: S\ndef main: S = t: Type -> t match { case A[x] -> x case B[y] -> y case _ -> 1 }"
    ).asserting {
      case Some(FunLit("t", FunApp(FunApp(FunApp(ValRef(tmA), ParamRef(scrutineeA)), FunLit("x", ParamRef(xParam))), FunLit("_", FunApp(FunApp(FunApp(ValRef(tmB), ParamRef(scrutineeB)), FunLit("y", ParamRef(yParam))), FunLit("_", IntValue(fallback))))))) =>
        isTypeMatch(tmA) shouldBe true
        scrutineeA shouldBe "t"
        xParam shouldBe "x"
        isTypeMatch(tmB) shouldBe true
        scrutineeB shouldBe "t"
        yParam shouldBe "y"
        fallback shouldBe BigInt(1)
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "desugar type match with nullary type constructor" in {
    runEngineForValue(
      "data Type\ndata S\ndata Foo\ndef integerLiteral[V]: S\ndef main: S = t: Type -> t match { case Foo[] -> 1 case _ -> 2 }"
    ).asserting {
      case Some(FunLit("t", FunApp(FunApp(FunApp(ValRef(tm), ParamRef(scrutinee)), FunLit("_", IntValue(matchBody))), FunLit("_", IntValue(fallback))))) =>
        isTypeMatch(tm) shouldBe true
        scrutinee shouldBe "t"
        matchBody shouldBe BigInt(1)
        fallback shouldBe BigInt(2)
      case x =>
        fail(s"unexpected: $x")
    }
  }

  it should "report error when type match has no wildcard case" in {
    runEngineForErrors(
      "data Type\ndata S\ndata Person[NAME: S]\ndef main: S = t: Type -> t match { case Person[name] -> name }"
    ).asserting(_ shouldBe Seq("Type match must have a wildcard case." at "Person"))
  }

  private def vfqn(name: String): ValueFQN =
    ValueFQN(testMN, QualifiedName(name, Qualifier.Default))

  private def isHandleCases(vfqn: ValueFQN): Boolean =
    vfqn.moduleName == testMN &&
      vfqn.name.name == "handleCases" &&
      (vfqn.name.qualifier match {
        case Qualifier.AbilityImplementation(abilityName, _, _) => abilityName == "PatternMatch"
        case _                                               => false
      })

  private def isTypeMatch(vfqn: ValueFQN): Boolean =
    vfqn.name.name == "typeMatch" &&
      (vfqn.name.qualifier match {
        case Qualifier.AbilityImplementation(abilityName, _, _) => abilityName == "TypeMatch"
        case _                                               => false
      })

  private def runEngineForValue(source: String): IO[Option[MatchDesugaredExpression]] =
    runGenerator(
      source,
      MatchDesugaredValue.Key(ValueFQN(testMN, QualifiedName("main", Qualifier.Default))),
      systemImports
    ).map { case (_, facts) =>
      facts.values
        .collectFirst { case rv: MatchDesugaredValue if rv.vfqn.name == QualifiedName("main", Qualifier.Default) => rv }
        .flatMap(_.runtime.map(_.value))
    }

  private def runEngineForErrors(source: String): IO[Seq[TestError]] =
    runGenerator(
      source,
      MatchDesugaredValue.Key(ValueFQN(testMN, QualifiedName("main", Qualifier.Default))),
      systemImports
    ).map(result => toTestErrors(result._1))
}
