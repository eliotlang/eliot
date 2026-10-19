package com.vanillasource.eliot.eliotc.apidoc.build

import com.vanillasource.eliot.eliotc.apidoc.model.{DocItem, DocModule}
import com.vanillasource.eliot.eliotc.ast.fact.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.source.content.Sourced
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

class DocModelBuilderTest extends AnyFlatSpec with Matchers {
  private val uri = URI.create("Test.els")

  private def s[A](a: A): Sourced[A]    = Sourced(uri, PositionRange.zero, a)
  private def ty(name: String, generics: Expression*): Expression =
    Expression.FunctionApplication(None, s(name), Option.when(generics.nonEmpty)(generics.map(s)), Seq.empty)
  private def gp(name: String): GenericParameter           = GenericParameter(s(name), s(ty("Type")), Seq.empty)
  private def arg(name: String, t: Expression)             = ArgumentDefinition(s(name), s(t))

  private def fn(
      name: String,
      qualifier: Qualifier = Qualifier.Default,
      generics: Seq[GenericParameter] = Seq.empty,
      args: Seq[ArgumentDefinition] = Seq.empty,
      ret: Expression = ty("Unit"),
      body: Option[Expression] = None,
      doc: Option[String] = None,
      visibility: Visibility = Visibility.Public
  ): FunctionDefinition =
    FunctionDefinition(s(QualifiedName(name, qualifier)), generics, args, s(ret), body.map(s), visibility = visibility, doc = doc.map(s))

  private def ast(functions: Seq[FunctionDefinition] = Seq.empty, data: Seq[DataDefinition] = Seq.empty): AST =
    AST(Seq.empty, functions, data)

  private def moduleNamed(modules: Seq[DocModule], name: String): DocModule =
    modules.find(_.name.name == name).getOrElse(fail(s"module $name not generated"))

  "doc model builder" should "merge an abstract type with a platform data definition into one item" in {
    val ioModule = ModuleName(Seq("eliot", "lang"), "IO")
    val modules  = DocModelBuilder.build(
      Seq(
        (ioModule, "stdlib", ast(functions = Seq(fn("IO", Qualifier.Type, args = Seq(arg("A", ty("Type"))), doc = Some("the io"))))),
        (
          ioModule,
          "jvm",
          ast(data = Seq(DataDefinition(s("IO"), Seq(gp("A")), Some(Seq(DataConstructor(s("IO"), Seq(arg("block", ty("Function", ty("Unit"), ty("A"))))))))))
        )
      )
    ).modules
    val item     = moduleNamed(modules, "IO").items.head

    item.kind shouldBe DocItem.Kind.TypeLike
    item.signature shouldBe "type IO[A]"
    item.doc shouldBe Some("the io")
    item.layers shouldBe Seq("stdlib", "jvm")
    item.implementedOn shouldBe Seq("jvm")
    item.members shouldBe Seq(DocItem.Member("data IO[A](block: Function[Unit, A])", None, Some("jvm")))
  }

  it should "merge an abstract def with its platform implementation, preferring the abstract signature and doc" in {
    val m       = ModuleName(Seq("eliot", "lang"), "Console")
    val modules = DocModelBuilder.build(
      Seq(
        (m, "stdlib", ast(functions = Seq(fn("printLine", args = Seq(arg("s", ty("String"))), ret = ty("IO", ty("Unit")), doc = Some("print a line"))))),
        (m, "jvm", ast(functions = Seq(fn("printLine", args = Seq(arg("s", ty("String"))), ret = ty("IO", ty("Unit")), body = Some(ty("io"))))))
      )
    ).modules
    val item    = moduleNamed(modules, "Console").items.head

    item.kind shouldBe DocItem.Kind.Value
    item.signature shouldBe "def printLine(s: String): IO[Unit]"
    item.doc shouldBe Some("print a line")
    item.implementedOn shouldBe Seq("jvm")
  }

  it should "gather ability implementations from across modules under the ability" in {
    val showModule = ModuleName(Seq("eliot", "lang"), "Show")
    val userModule = ModuleName(Seq.empty, "App")
    val modules    = DocModelBuilder.build(
      Seq(
        (
          showModule,
          "stdlib",
          ast(functions =
            Seq(
              fn("Show", Qualifier.Ability("Show"), generics = Seq(gp("A"))),
              fn("show", Qualifier.Ability("Show"), generics = Seq(gp("A")), args = Seq(arg("a", ty("A"))), ret = ty("String"))
            )
          )
        ),
        (userModule, "src", ast(functions = Seq(fn("Show", Qualifier.AbilityImplementation("Show", "Hello", None), args = Seq(arg("arg0", ty("Hello")))))))
      )
    ).modules
    val item       = moduleNamed(modules, "Show").items.find(_.kind == DocItem.Kind.Ability).getOrElse(fail("no ability item"))

    item.signature shouldBe "ability Show[A]"
    item.members.map(_.signature) shouldBe Seq("def show(a: A): String")
    item.implementations shouldBe Seq(DocItem.Implementation("implement Show[Hello]", Seq("src"), None))
  }

  it should "list a type's ability implementations on its own page, gathered from across modules" in {
    val bigIntModule = ModuleName(Seq("eliot", "lang"), "BigInteger")
    val numericModule = ModuleName(Seq("eliot", "lang"), "Numeric")
    val modules       = DocModelBuilder.build(
      Seq(
        (
          numericModule,
          "stdlib",
          ast(functions = Seq(fn("Numeric", Qualifier.Ability("Numeric"), generics = Seq(gp("A")))))
        ),
        (
          bigIntModule,
          "stdlib",
          ast(functions =
            Seq(
              fn("BigInteger", Qualifier.Type),
              fn("Numeric", Qualifier.AbilityImplementation("Numeric", "BigInteger", None), args = Seq(arg("arg0", ty("BigInteger")))),
              fn("Compare", Qualifier.AbilityImplementation("Compare", "BigInteger", None), args = Seq(arg("arg0", ty("BigInteger"))))
            )
          )
        )
      )
    ).modules
    val item          = moduleNamed(modules, "BigInteger").items.find(_.kind == DocItem.Kind.TypeLike).getOrElse(fail("no type item"))

    item.implementations shouldBe Seq(
      DocItem.Implementation("implement Compare[BigInteger]", Seq("stdlib"), None),
      DocItem.Implementation("implement Numeric[BigInteger]", Seq("stdlib"), None)
    )
  }

  it should "not list an implementation generic over its own pattern on any type page" in {
    val consoleModule = ModuleName(Seq("eliot", "effect"), "Console")
    val modules       = DocModelBuilder.build(
      Seq(
        (
          consoleModule,
          "jvm",
          ast(functions =
            Seq(
              fn("Console", Qualifier.Ability("Console"), generics = Seq(gp("F"))),
              fn(
                "Console",
                Qualifier.AbilityImplementation("Console", "F", None),
                generics = Seq(gp("F")),
                args = Seq(arg("arg0", ty("F")))
              )
            )
          )
        )
      )
    ).modules

    moduleNamed(modules, "Console").items.filter(_.kind == DocItem.Kind.TypeLike) shouldBe Seq.empty
  }

  it should "keep the lowest layer's doc and warn that a higher layer's duplicate doc is ignored" in {
    val m      = ModuleName(Seq("eliot", "lang"), "Function")
    val result = DocModelBuilder.build(
      Seq(
        (m, "lang", ast(functions = Seq(fn("apply", ret = ty("B"), body = Some(ty("apply")), doc = Some("the canonical apply doc"))))),
        (m, "stdlib", ast(functions = Seq(fn("apply", ret = ty("B"), doc = Some("a duplicate that should be ignored")))))
      )
    )
    val item   = moduleNamed(result.modules, "Function").items.head

    item.doc shouldBe Some("the canonical apply doc")
    result.warnings shouldBe Seq(
      "Doc comment on def 'apply' in layer 'stdlib' is ignored; 'apply' is already documented in layer 'lang'."
    )
  }

  it should "omit private defs, types and data, keeping only public declarations" in {
    val m       = ModuleName(Seq("eliot", "lang"), "String")
    val modules = DocModelBuilder.build(
      Seq(
        (
          m,
          "jvm",
          ast(
            functions = Seq(
              fn("length", args = Seq(arg("s", ty("String"))), ret = ty("Int")),
              fn("lengthInternal", args = Seq(arg("s", ty("String"))), ret = ty("Int"), body = Some(ty("native")), visibility = Visibility.Private),
              fn("Alias", Qualifier.Type, args = Seq(arg("A", ty("Type"))), body = Some(ty("A")), visibility = Visibility.Private)
            ),
            data = Seq(DataDefinition(s("Secret"), Seq.empty, Some(Seq(DataConstructor(s("Secret"), Seq.empty))), Visibility.Private))
          )
        )
      )
    ).modules

    moduleNamed(modules, "String").items.map(_.name) shouldBe Seq("length")
  }

  it should "hide an entire ability whose marker is private" in {
    val m       = ModuleName(Seq("eliot", "lang"), "Secret")
    val modules = DocModelBuilder.build(
      Seq(
        (
          m,
          "stdlib",
          ast(functions =
            Seq(
              fn("Hidden", Qualifier.Ability("Hidden"), generics = Seq(gp("A")), visibility = Visibility.Private),
              fn("hidden", Qualifier.Ability("Hidden"), generics = Seq(gp("A")), args = Seq(arg("a", ty("A"))), ret = ty("String"), visibility = Visibility.Private)
            )
          )
        )
      )
    ).modules

    moduleNamed(modules, "Secret").items shouldBe Seq.empty
  }

  it should "not warn when only one layer documents a name" in {
    val m      = ModuleName(Seq("eliot", "lang"), "Function")
    val result = DocModelBuilder.build(
      Seq(
        (m, "lang", ast(functions = Seq(fn("apply", ret = ty("B"), body = Some(ty("apply")), doc = Some("only here"))))),
        (m, "stdlib", ast(functions = Seq(fn("apply", ret = ty("B")))))
      )
    )

    moduleNamed(result.modules, "Function").items.head.doc shouldBe Some("only here")
    result.warnings shouldBe Seq.empty
  }
}
