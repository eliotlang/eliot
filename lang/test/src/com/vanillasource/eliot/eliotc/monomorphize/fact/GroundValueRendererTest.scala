package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** User-facing rendering of ground types ([[GroundValueRenderer]]): a type prints the way the user writes it, since
  * there is no machinery in a type to hide.
  */
class GroundValueRendererTest extends AnyFlatSpec with Matchers {

  private def typeFQN(packages: Seq[String], module: String, name: String): ValueFQN =
    ValueFQN(ModuleName(packages, module), QualifiedName(name, Qualifier.Type))

  private def con(fqn: ValueFQN, args: GroundValue*): GroundValue =
    GroundValue.Structure(fqn, args, GroundValue.Type)

  private val listFQN = typeFQN(Seq("eliot", "collection"), "List", "List")
  private val string  = con(WellKnownTypes.stringFQN)

  private def render(value: GroundValue): String = GroundValueRenderer.render(value)

  "ground value renderer" should "render a plain type constructor application" in {
    render(con(listFQN, string)) shouldBe "List[String]"
  }

  it should "render a value constructor application with parentheses, not brackets" in {
    val boxValue = ValueFQN(ModuleName(Seq("app"), "Boxes"), QualifiedName("Box", Qualifier.Default))
    render(GroundValue.Structure(boxValue, Seq(string), GroundValue.Type)) shouldBe "Box(String)"
  }

  it should "render a function type as a right-associative arrow" in {
    render(con(WellKnownTypes.functionDataTypeFQN, string, con(listFQN, string))) shouldBe "String -> List[String]"
  }

}
