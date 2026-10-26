package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Reading an instance's signature off a [[ParametricSignature]]: `GroundValue.substituteParams`. */
class GroundValueSubstitutionTest extends AnyFlatSpec with Matchers {

  private def con(fqn: ValueFQN, args: GroundValue*): GroundValue =
    GroundValue.Structure(fqn, args, GroundValue.Type)

  private val listFQN = ValueFQN(ModuleName(Seq("eliot", "collection"), "List"), QualifiedName("List", Qualifier.Type))
  private val string  = con(WellKnownTypes.stringFQN)
  private val bool    = con(WellKnownTypes.boolFQN)

  private def param(index: Int, spine: GroundValue*): GroundValue = GroundValue.Param(index, spine, GroundValue.Type)

  private def function(from: GroundValue, to: GroundValue): GroundValue =
    con(WellKnownTypes.functionDataTypeFQN, from, to)

  "substituting type arguments" should "put each argument in place of its binder" in {
    function(param(0), param(1)).substituteParams(Seq(string, bool)) shouldBe Some(function(string, bool))
  }

  it should "append an applied binder's spine to its argument's arguments" in {
    param(0, bool).substituteParams(Seq(con(listFQN))) shouldBe Some(con(listFQN, bool))
  }

  it should "keep a binder past the arguments, as a partial-arity read-back does" in {
    function(param(0), param(1)).substituteParams(Seq(string)) shouldBe Some(function(string, param(1)))
  }

  it should "refuse to apply an argument that is not a structure" in {
    param(0, bool).substituteParams(Seq(GroundValue.Type)) shouldBe None
  }
}
