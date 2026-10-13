package com.vanillasource.eliot.eliotc.jvm

/** Regression coverage for two instantiations of one generic value that share a head type but differ inside it:
  * `if..else` at `Option[Name]` and at `Option[String]`. Each instantiation of `else` calls its own `runAbort`
  * (`runAbort$Option$Name`, `runAbort$Option$String`), so both must be walked by `used` and both emitted. The codegen
  * projection once keyed a type argument on its head alone, merged the two, walked one, and the program died with a
  * `NoSuchMethodError` on the other's `runAbort`.
  */
class NestedInstantiationIntegrationTest extends FullIntegrationTest {

  "two instantiations differing only inside a shared head" should "both be emitted" in {
    compileAndRun(
      """import eliot.effect.Console
        |data Name(text: String)
        |
        |def named(present: Bool): Option[Name] = if(present) some(Name("x")) else none
        |
        |def plain(present: Bool): Option[String] = if(present) some("y") else none
        |
        |def main uses Console: Unit = {
        |  printLine(foldOption("no name", found -> found.text, named(true)))
        |  printLine(plain(true) orElse "no text")
        |}""".stripMargin
    ).asserting(_ shouldBe "x\ny")
  }
}
