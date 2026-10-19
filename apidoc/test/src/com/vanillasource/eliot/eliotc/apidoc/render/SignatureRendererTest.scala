package com.vanillasource.eliot.eliotc.apidoc.render

import com.vanillasource.eliot.eliotc.ast.fact.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class SignatureRendererTest extends AnyFlatSpec with Matchers {
  private val uri = URI.create("Test.els")

  private def s[A](a: A): Sourced[A] = Sourced(uri, PositionRange.zero, a)

  private def ty(name: String, generics: Expression*): Expression =
    Expression.FunctionApplication(None, s(name), Option.when(generics.nonEmpty)(generics.map(s)), Seq.empty)

  private def gp(
      name: String,
      restriction: Expression = ty("Type"),
      constraints: Seq[UnresolvedAbilityConstraint[Sourced[Expression]]] = Seq.empty
  ): GenericParameter = GenericParameter(s(name), s(restriction), constraints)

  private def arg(name: String, t: Expression): ArgumentDefinition =
    ArgumentDefinition(s(name), s(t))

  private def fn(
      name: String,
      qualifier: Qualifier = Qualifier.Default,
      generics: Seq[GenericParameter] = Seq.empty,
      args: Seq[ArgumentDefinition] = Seq.empty,
      ret: Expression = ty("Unit"),
      body: Option[Expression] = None,
      fixity: Fixity = Fixity.Application,
      precedence: Seq[PrecedenceDeclaration] = Seq.empty,
      visibility: Visibility = Visibility.Public,
      metaSlots: Seq[ArgumentDefinition] = Seq.empty
  ): FunctionDefinition =
    FunctionDefinition(
      s(QualifiedName(name, qualifier)),
      generics,
      args,
      s(ret),
      body.map(s),
      fixity,
      precedence,
      visibility,
      metaSlots = metaSlots
    )

  "signature renderer" should "render a nullary value with no parentheses" in {
    SignatureRenderer.function(fn("main", ret = ty("IO", ty("Unit")))) shouldBe "def main: IO[Unit]"
  }

  it should "render generics and value arguments" in {
    SignatureRenderer.function(
      fn(
        "map",
        generics = Seq(gp("A"), gp("B")),
        args = Seq(arg("fa", ty("F", ty("A"))), arg("f", ty("Function", ty("A"), ty("B")))),
        ret = ty("F", ty("B"))
      )
    ) shouldBe "def map[A, B](fa: F[A], f: Function[A, B]): F[B]"
  }

  it should "recover the higher-kinded [_] sugar" in {
    SignatureRenderer.function(fn("run", generics = Seq(gp("F", ty("Function", ty("Type"), ty("Type")))))) shouldBe
      "def run[F[_]]: Unit"
  }

  it should "render an ability constraint without the redundant self type parameter" in {
    SignatureRenderer.function(
      fn(
        "showIt",
        generics = Seq(gp("A", constraints = Seq(UnresolvedAbilityConstraint(s("Show"), Seq(s(ty("A"))))))),
        args = Seq(arg("a", ty("A"))),
        ret = ty("String")
      )
    ) shouldBe "def showIt[A ~ Show](a: A): String"
  }

  it should "render fixity and precedence before an operator definition" in {
    SignatureRenderer.function(
      fn(
        "+",
        args = Seq(arg("l", ty("Int")), arg("r", ty("Int"))),
        ret = ty("Int"),
        fixity = Fixity.Infix(Fixity.Associativity.Left),
        precedence = Seq(PrecedenceDeclaration(PrecedenceDeclaration.Relation.At, Seq(s("+"))))
      )
    ) shouldBe "infix left at + def +(l: Int, r: Int): Int"
  }

  it should "render the compiler-tracked meta-slot brace of a type" in {
    SignatureRenderer.function(
      fn("Int", Qualifier.Type, metaSlots = Seq(arg("range", ty("Interval", ty("BigInteger")))))
    ) shouldBe "type Int {range: Interval[BigInteger]}"
  }

  it should "render a type alias body" in {
    SignatureRenderer.function(fn("Id", Qualifier.Type, args = Seq(arg("A", ty("Type"))), body = Some(ty("A")))) shouldBe
      "type Id[A] = A"
  }

  it should "render an abstract data declaration" in {
    SignatureRenderer.data(DataDefinition(s("Color"), Seq.empty, None)) shouldBe "data Color"
  }

  it should "render a sum-type data declaration" in {
    SignatureRenderer.data(
      DataDefinition(
        s("Maybe"),
        Seq(gp("A")),
        Some(Seq(DataConstructor(s("Nothing"), Seq.empty), DataConstructor(s("Just"), Seq(arg("value", ty("A"))))))
      )
    ) shouldBe "data Maybe[A] = Nothing | Just(value: A)"
  }

  it should "render a single-constructor record in record form" in {
    SignatureRenderer.data(
      DataDefinition(s("Hello"), Seq.empty, Some(Seq(DataConstructor(s("Hello"), Seq(arg("name", ty("String")))))))
    ) shouldBe "data Hello(name: String)"
  }

  it should "render an ability header with a higher-kinded parameter" in {
    SignatureRenderer.abilityHeader("Monad", Seq(gp("F", ty("Function", ty("Type"), ty("Type"))))) shouldBe
      "ability Monad[F[_]]"
  }

  it should "reconstruct an implementation from its marker" in {
    SignatureRenderer.implementation(
      "Show",
      fn("Show", Qualifier.AbilityImplementation("Show", "Hello", None), args = Seq(arg("arg0", ty("Hello"))))
    ) shouldBe "implement Show[Hello]"
  }
}
