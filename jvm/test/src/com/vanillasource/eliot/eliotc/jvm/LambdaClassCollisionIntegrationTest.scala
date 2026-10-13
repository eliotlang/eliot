package com.vanillasource.eliot.eliotc.jvm

/** Regression coverage for the lambda-class naming collision: two implementations of the *same* ability, co-located in
  * one module, each with a method whose body is a capturing lambda. Both methods share the ability method's local name
  * (`wrap`), so before the fix their generated closure classes both mangled to `Test$wrap$lambda$1` — a
  * `java.util.zip.ZipException: duplicate entry` at JAR assembly (then a `NoClassDefFoundError`).
  *
  * The generated JVM *method* names already disambiguate the two impls (`wrap$Wrap$impl$0$Foo` vs
  * `wrap$Wrap$impl$1$Bar`, via [[CommonPatterns.mangledMethodName]]); the closure-class prefix must use that same
  * disambiguated name rather than the bare local name, so the two impls' lambdas land in distinct classes. This is what
  * lets both instances be co-located in a single module (the workaround was to split every carrier's instances into its
  * own module).
  */
class LambdaClassCollisionIntegrationTest extends FullIntegrationTest {

  "two impls of one ability, each with a capturing lambda, in one module" should "not collide their lambda classes" in {
    compileAndRun(
      """import eliot.effect.Console
        |ability Wrap[A] {
        |  def wrap(a: A): Function[Unit, String]
        |}
        |
        |data Foo(fooTag: String)
        |data Bar(barTag: String)
        |
        |implement Wrap[Foo] {
        |  def wrap(a: Foo): Function[Unit, String] = ignore -> fooTag(a)
        |}
        |
        |implement Wrap[Bar] {
        |  def wrap(a: Bar): Function[Unit, String] = ignore -> barTag(a)
        |}
        |
        |def main uses Console: Unit = {
        |  printLine(apply(wrap(Foo("foo")), unit))
        |  printLine(apply(wrap(Bar("bar")), unit))
        |}""".stripMargin
    ).asserting(_ shouldBe "foo\nbar")
  }
}
