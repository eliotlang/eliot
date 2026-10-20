package com.vanillasource.eliot.eliotc.used

import cats.effect.IO
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.channel.{MetaTransferAccountingProcessor, WovenValueProcessor}
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerFact
import com.vanillasource.eliot.eliotc.source.content.Sourced

// `used` demands the post-mono `WovenValue` (the codegen source), so this manual-fact-injection harness runs the
// `WovenValueProcessor` too, which is the identity image of each injected `MonomorphicValue`. `WovenValue` in turn
// demands its codegen precondition `MetaTransferAccounting` (docs/meta.md §3), which rides along: the
// injected bodies have no `OperatorResolvedValue` to read a declared return from, so it passes with nothing to check.
class UsedNamesProcessorTest
    extends ProcessorTest(
      UsedNamesProcessor(),
      WovenValueProcessor(),
      MetaTransferAccountingProcessor()
    ) {
  private val intVfqn = ValueFQN(testModuleName, QualifiedName("Int", Qualifier.Default))
  private val intType = GroundValue.Structure(intVfqn, Seq.empty, GroundValue.Type)

  /** `Int -> Int`. The injected bodies must be *well-typed*, not merely well-formed: `WovenValueProcessor` re-checks
    * the body it weaves (effects-as-channel v4 §11 P3), so a hand-written fixture annotating a lambda or an applied
    * head with a non-function type is rejected exactly as a mis-woven body would be.
    */
  private val intToIntType =
    GroundValue.Structure(WellKnownTypes.functionDataTypeFQN, Seq(intType, intType), GroundValue.Type)

  "UsedNamesProcessor" should "include root name in used names for value with no body" in {
    val valueVfqn = ValueFQN(testModuleName, default("value"))
    val mv        = MonomorphicValue(valueVfqn, Seq.empty, sourced(default("value")), intType, None)

    runProcessor(UsedNames.Key(valueVfqn), Seq(mv))
      .asserting(_.usedNames should contain key valueVfqn)
  }

  it should "include root name in used names when it references another value" in {
    val fVfqn = ValueFQN(testModuleName, default("f"))
    val gVfqn = ValueFQN(testModuleName, default("g"))

    val gMv = MonomorphicValue(gVfqn, Seq.empty, sourced(default("g")), intType, None)
    val fMv = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intType, runtime(valueRef(gVfqn)))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv, gMv))
      .asserting(_.usedNames should contain key fVfqn)
  }

  it should "include referenced value in used names" in {
    val fVfqn = ValueFQN(testModuleName, default("f"))
    val gVfqn = ValueFQN(testModuleName, default("g"))

    val gMv = MonomorphicValue(gVfqn, Seq.empty, sourced(default("g")), intType, None)
    val fMv = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intType, runtime(valueRef(gVfqn)))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv, gMv))
      .asserting(_.usedNames should contain key gVfqn)
  }

  it should "track direct call application count" in {
    val fVfqn = ValueFQN(testModuleName, default("f"))
    val gVfqn = ValueFQN(testModuleName, default("g"))

    val gMv  = MonomorphicValue(gVfqn, Seq.empty, sourced(default("g")), intToIntType, None)
    val gRef = MonomorphicExpression(intToIntType, valueRef(gVfqn))
    val arg  = MonomorphicExpression(intType, MonomorphicExpression.IntegerLiteral(sourced(BigInt(42))))
    val app  = MonomorphicExpression.FunctionApplication(sourced(gRef), sourced(arg))
    val fMv  = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intType, runtime(app))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv, gMv))
      .asserting(_.usedNames(gVfqn).directCallApplications shouldBe Map(1 -> 1))
  }

  it should "follow references through function literal bodies" in {
    val fVfqn = ValueFQN(testModuleName, default("f"))
    val gVfqn = ValueFQN(testModuleName, default("g"))

    val gMv      = MonomorphicValue(gVfqn, Seq.empty, sourced(default("g")), intType, None)
    val innerRef = MonomorphicExpression(intType, valueRef(gVfqn))
    val lambda   = MonomorphicExpression.FunctionLiteral(sourced("x"), intType, sourced(innerRef))
    val fMv      = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intToIntType, runtime(lambda))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv, gMv))
      .asserting(_.usedNames should contain key gVfqn)
  }

  it should "handle recursive value without infinite loop" in {
    import scala.concurrent.duration.*

    val fVfqn = ValueFQN(testModuleName, default("f"))
    val fMv   = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intType, runtime(valueRef(fVfqn)))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv))
      .timeout(1.seconds)
      .asserting(_.usedNames should contain key fVfqn)
  }

  it should "handle mutual recursion without infinite loop" in {
    import scala.concurrent.duration.*

    val fVfqn = ValueFQN(testModuleName, default("f"))
    val gVfqn = ValueFQN(testModuleName, default("g"))

    val fMv = MonomorphicValue(fVfqn, Seq.empty, sourced(default("f")), intType, runtime(valueRef(gVfqn)))
    val gMv = MonomorphicValue(gVfqn, Seq.empty, sourced(default("g")), intType, runtime(valueRef(fVfqn)))

    runProcessor(UsedNames.Key(fVfqn), Seq(fMv, gMv))
      .timeout(1.seconds)
      .asserting { result =>
        result.usedNames should contain key fVfqn
        result.usedNames should contain key gVfqn
      }
  }

  private def valueRef(vfqn: ValueFQN): MonomorphicExpression.MonomorphicValueReference =
    MonomorphicExpression.MonomorphicValueReference(sourced(vfqn), Seq.empty)

  private def runtime(expr: MonomorphicExpression.Expression): Option[Sourced[MonomorphicExpression.Expression]] =
    Some(sourced(expr))

  private def runProcessor(
      key: UsedNames.Key,
      facts: Seq[CompilerFact]
  ): IO[UsedNames] =
    runGeneratorWithFacts(facts, key).flatMap { case (result, errors) =>
      if (errors.nonEmpty) IO.raiseError(new Exception(s"Errors: ${errors.map(_.message).mkString(", ")}"))
      else IO.pure(result.getOrElse(throw new Exception("UsedNames not produced")))
    }
}
