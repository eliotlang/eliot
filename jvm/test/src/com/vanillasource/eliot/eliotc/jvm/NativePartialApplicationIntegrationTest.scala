package com.vanillasource.eliot.eliotc.jvm

/** End-to-end regression for under-application of a **body-less native** — the complement of
  * [[OverApplicationIntegrationTest]], and the same kind of arity mismatch seen from the other side.
  *
  * A native is emitted once, at its declared arity, but a call site emits its call at whatever arity the spine has. An
  * under-applied native therefore linked to a method that was never generated: a clean compile and `NoSuchMethodError:
  * 'Function eliot.lang.String.substring(BigInteger, BigInteger)'` at runtime. `NativePartialApplication` now emits the
  * missing variants, one closure per missing argument.
  *
  * Only a partial application that becomes a **value** was affected, which is why this went unnoticed: a direct
  * `s.substring(0, 3)` is flattened by uncurrying into one full-arity call and builds no function at all. The last two
  * programs are the ones that made it reachable in ordinary code — a dot chain whose argument performs, where the
  * effectful part is hoisted out and the partial application left behind is `pure`-wrapped into the chain.
  *
  * These programs must run, not merely compile: the defect was invisible to the compiler.
  */
class NativePartialApplicationIntegrationTest extends FullIntegrationTest {

  "a native missing one argument" should "be usable as a function value" in {
    compileAndRun(
      """def firstThree: String => String = substring(0, 3)
        |
        |def main uses Console: Unit = printLine(firstThree("abcdef"))""".stripMargin
    ).asserting(_ shouldBe "abc")
  }

  "a native missing two arguments" should "chain one closure per missing argument" in {
    compileAndRun(
      """def fromZero: Int => String => String = substring(0)
        |
        |def main uses Console: Unit = printLine(fromZero(3)("abcdef"))""".stripMargin
    ).asserting(_ shouldBe "abc")
  }

  "a bare native reference" should "be a function value applicable one argument at a time" in {
    compileAndRun(
      """def sub: Int => Int => String => String = substring
        |
        |def main uses Console: Unit = printLine(sub(1)(4)("abcdef"))""".stripMargin
    ).asserting(_ shouldBe "bcd")
  }

  "a fully applied native" should "still be one direct call, building no function" in {
    compileAndRun(
      """def main uses Console: Unit = printLine("abcdef".substring(0, 3))""".stripMargin
    ).asserting(_ shouldBe "abc")
  }

  "an ability-implementation native missing its argument" should "be usable as a function value" in {
    compileAndRun(
      """import eliot.file.Path
        |
        |def shown: Path => String = show
        |
        |def main uses Console: Unit = printLine(shown(path("/tmp/one")))""".stripMargin
    ).asserting(_ shouldBe "/tmp/one")
  }

  "an ability-implementation native handed to a combinator" should "link to an emitted method" in {
    compileAndRun(
      """import eliot.collection.List
        |import eliot.file.Path
        |
        |def main uses Console: Unit = printLine(singleton(path("/tmp/one")).map(show).joined(","))""".stripMargin
    ).asserting(_ shouldBe "/tmp/one")
  }

  "a dot chain whose argument performs" should "reach the native through the hoisted chain" in {
    compileAndRun(
      """import eliot.effect.Abort
        |
        |def labelOf(setting: String) uses Abort: String = setting.substring(0, setting.indexOf("="))
        |
        |def main uses Console: Unit = {
        |   printLine(labelOf("host=example.com") else "none")
        |   printLine(labelOf("nokey") else "none")
        |}""".stripMargin
    ).asserting(_ shouldBe "host\nnone")
  }
}
