package com.vanillasource.eliot.eliotc.resolve.processor

import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ProcessorTest
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.plugin.LangProcessors
import com.vanillasource.eliot.eliotc.resolve.fact.Expression.*
import com.vanillasource.eliot.eliotc.resolve.fact.{Expression, ResolvedValue}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** A **row alias** (`type Talk[A] = {Console} A`) names a row together with its payload, and a definition naming one
  * as its return type receives that row (`docs/effects.md` §2.4).
  *
  * It is an **ordinary name**, which is the whole of the feature and what these assert. The alias declares its row
  * like any definition declaring one; a use is resolved by the ordinary dictionary, so it crosses files, honours
  * import scope and is shadowed by a binder of the same name; and what the use receives is the declared row's
  * *entries*, arguments substituted, the payload left for the evaluator. Nothing downstream can tell a received row
  * from a written-out one — which is why the binder minting, the scope check and codegen know nothing of aliases.
  */
class RowAliasesTest extends ProcessorTest(LangProcessors()*) {
  private val positionViolation =
    "Row alias 'Talk' can only name a definition's return type. " +
      "A row on a parameter is supplied rather than received, so it must be written out."

  private val console = "import eliot.effect.Console\n"

  "a def naming a row alias" should "mint and declare exactly what the written-out row does" in {
    (
      rowLowering(console + "type Talk[A] = {Console} A\ndef greet(name: String): Talk[Unit] = printLine(name)"),
      rowLowering(console + "def greet(name: String) uses Console: Unit = printLine(name)")
    ).mapN(_ === _).asserting(_ shouldBe true)
  }

  "a def naming a parameterless row alias" should "mint and declare exactly what the written-out row does" in {
    (
      rowLowering(console + "type Talk = {Console} Unit\ndef greet(name: String): Talk = printLine(name)"),
      rowLowering(console + "def greet(name: String) uses Console: Unit = printLine(name)")
    ).mapN(_ === _).asserting(_ shouldBe true)
  }

  "a multi-entry row alias" should "mint and declare exactly what the written-out row does" in {
    (
      rowLowering(
        console + "import eliot.effect.Log\ntype Noisy[A] = {Console, Log} A\n" +
          "def greet(name: String): Noisy[Unit] = log(name)"
      ),
      rowLowering(console + "import eliot.effect.Log\ndef greet(name: String) uses Console, Log: Unit = log(name)")
    ).mapN(_ === _).asserting(_ shouldBe true)
  }

  "a row alias argument" should "be substituted into the row's own entries" in {
    rowLowering(
      "import eliot.effect.Throw\ntype Fallible[E, A] = {Throw[E]} A\n" +
        "def greet(name: String): Fallible[String, Unit] = raise(name)"
    ).asserting(_ shouldBe (Seq("Throw"), Seq("Throw[String]")))
  }

  /** The alias's row names `Console`, which the *using* file does not import. A row travels with the declaration that
    * declares it, so it is resolved in that declaration's scope and only then substituted — the same reading
    * [[ValueResolver]] makes of an ability's own `~` constraints.
    */
  "a row alias declared in another module" should "be received through an ordinary import" in {
    rowLowering(
      "import eliot.effect.Console\nimport eliot.talk.Talk\ndef greet(name: String): Talk[Unit] = printLine(name)",
      ambientStubsWith("Talk" -> (console + "type Talk[A] = {Console} A"))
        .map(s => if (s.module === "Talk") s.copy(packages = Seq("eliot", "talk")) else s)
    ).asserting(_ shouldBe (Seq("Console"), Seq("Console")))
  }

  /** A row alias is a name, so a binder of the same name shadows it exactly as it shadows any other value — the use
    * is the binder's, and no row is received.
    */
  "a binder named like a row alias" should "shadow it rather than be read as a use of it" in {
    rowLowering(console + "type Talk[A] = {Console} A\ndef greet[Talk](name: Talk): Talk = name")
      .asserting(_ shouldBe (Seq.empty, Seq.empty))
  }

  /** The alias contributes to the return *position*, which is what a written-out row lowers to its payload — so the
    * two compose, and a definition may name an alias and write entries of its own beside it.
    */
  "a row written out beside a named one" should "declare both" in {
    rowLowering(
      console + "import eliot.effect.Log\ntype Talk[A] = {Console} A\n" +
        "def greet(name: String) uses Log: Talk[Unit] = { log(name) printLine(name) }"
    ).asserting(_ shouldBe (Seq("Log", "Console"), Seq("Log", "Console")))
  }

  it should "declare one entry, not two, when both name the same effect" in {
    rowLowering(console + "type Talk[A] = {Console} A\ndef greet(name: String) uses Console: Talk[Unit] = printLine(name)")
      .asserting(_ shouldBe (Seq("Console"), Seq("Console")))
  }

  "the return type naming a row alias" should "stay the application the user wrote" in {
    returnType(console + "type Talk[A] = {Console} A\ndef greet(name: String): Talk[Unit] = printLine(name)")
      .asserting(_ shouldBe ("Talk", Seq("Unit")))
  }

  "a row alias on a parameter" should "be rejected, since a parameter row is supplied and thunked" in {
    errors(console + "type Talk[A] = {Console} A\ndef greet(body: Talk[Unit]): String = \"\"")
      .asserting(_ shouldBe Seq(positionViolation at "Talk"))
  }

  "a row alias nested inside a return type" should "be rejected" in {
    errors(console + "type Talk[A] = {Console} A\ndef greet(name: String): Option[Talk[Unit]] = none")
      .asserting(_ shouldBe Seq(positionViolation at "Talk"))
  }

  /** Demanded at the alias itself, because that is where it is reported: a type alias is resolved when something
    * needs the type it computes, which in a whole build is the checker reducing a use of it.
    */
  "a row alias named by another type alias" should "be rejected, since no type receives a row" in {
    errorsResolving(
      console + "type Talk[A] = {Console} A\ntype Held[A] = Option[Talk[A]]",
      ValueFQN(testModuleName, QualifiedName("Held", Qualifier.Type))
    ).asserting(_ shouldBe Seq(positionViolation at "Talk"))
  }

  "a row alias applied to the wrong number of arguments" should "be rejected" in {
    errors(console + "type Talk[A] = {Console} A\ndef greet(name: String): Talk = printLine(name)")
      .asserting(_ shouldBe Seq("Row alias 'Talk' takes 1 type argument(s), but 0 were given." at "Talk"))
  }

  "a type alias whose body is not a row" should "name a return type like any other" in {
    errors("type Name = String\ndef greet(name: String): Name = name").asserting(_ shouldBe empty)
  }

  /** A definition's **row-relevant** lowering: the abilities its binding binders mark, and its declared return row.
    * Together they are everything a received row leaves behind — what the marks say, and what the row records.
    */
  private def rowLowering(source: String, imports: Seq[SystemImport] = systemImports): IO[(Seq[String], Seq[String])] =
    resolvedValue(source, imports).map(value => (bindings(value), declaredRow(value)))

  /** The abilities this definition's binding binders mark — the binders whose declared type is the implementation
    * mark `Implementation[Console]`.
    */
  private def bindings(value: ResolvedValue): Seq[String] =
    binderTypes(value.signature.value).collect {
      case FunctionApplication(Sourced(_, _, ValueReference(marker, _)), Sourced(_, _, ValueReference(ability, _)))
          if marker.value.name.name === "Implementation" =>
        ability.value.name.name
    }

  /** The definition's declared return row, each entry as `Ability` or `Ability[args]`. */
  private def declaredRow(value: ResolvedValue): Seq[String] =
    value.effectRow.returnEffects.map { entry =>
      val arguments = entry.typeArgs.map(argument => simplified(argument.render))
      entry.abilityFQN.abilityName + (if (arguments.isEmpty) "" else arguments.mkString("[", ",", "]"))
    }

  /** The head and arguments of the definition's return position — the alias application the user wrote, which the
    * evaluator reduces later like any other.
    */
  private def returnType(source: String): IO[(String, Seq[String])] =
    resolvedValue(source, systemImports).map(value => applicationSpine(returnPosition(value.signature.value)))

  private def applicationSpine(expr: Expression): (String, Seq[String]) = expr match {
    case FunctionApplication(target, argument) =>
      val (head, arguments) = applicationSpine(target.value)
      (head, arguments :+ nameOf(argument.value))
    case other                                 => (nameOf(other), Seq.empty)
  }

  private def nameOf(expr: Expression): String = expr match {
    case ValueReference(name, _) => name.value.name.name
    case other                   => other.render
  }

  private def returnPosition(expr: Expression): Expression = expr match {
    case FunctionLiteral(_, Some(_), body)                                                            =>
      returnPosition(body.value)
    case FunctionApplication(Sourced(_, _, FunctionApplication(arrow, _)), codomain) if isArrow(arrow) =>
      returnPosition(codomain.value)
    case other                                                                                        => other
  }

  private def isArrow(expr: Sourced[Expression]): Boolean = expr.value match {
    case ValueReference(name, _) => name.value.name.name === "Function"
    case _                       => false
  }

  private def errorsResolving(source: String, value: ValueFQN): IO[Seq[TestError]] =
    runGenerator(source, ResolvedValue.Key(value), systemImports).map(result => toTestErrors(result._1))

  private def binderTypes(expr: Expression): Seq[Expression] = expr match {
    case FunctionLiteral(_, parameterType, body) => parameterType.map(_.value).toSeq ++ binderTypes(body.value)
    case _                                       => Seq.empty
  }

  private def simplified(rendered: String): String =
    rendered.replaceAll("\\^Type", "").replaceAll("[A-Za-z0-9_.]*::", "")

  private def resolvedValue(source: String, imports: Seq[SystemImport]): IO[ResolvedValue] =
    runGenerator(source, ResolvedValue.Key(greetVfqn), imports).map { case (errors, facts) =>
      if (errors.nonEmpty) throw new Exception(s"Compilation errors: ${errors.map(_.message).mkString(", ")}")
      facts.values.collectFirst { case value: ResolvedValue if value.vfqn === greetVfqn => value }.get
    }

  private def errors(source: String): IO[Seq[TestError]] =
    runGenerator(source, ResolvedValue.Key(greetVfqn), systemImports).map(result => toTestErrors(result._1))

  private val greetVfqn = ValueFQN(testModuleName, QualifiedName("greet", Qualifier.Default))
}
