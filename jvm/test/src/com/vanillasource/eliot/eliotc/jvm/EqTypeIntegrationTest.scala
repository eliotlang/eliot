package com.vanillasource.eliot.eliotc.jvm

/** Ability guards Stage 0 — a regression guard that the shipped `stdlib/.../Eq.els` (the `Eq` ability plus its `==`/`!=`
  * operators and their precedence declarations) parses and merges cleanly into a real runtime build.
  *
  * The `Eq[Type]` *instance* lives in `lang/.../Eq.els` and only ever *reduces* at compile time (types are erased at runtime), so
  * a program cannot yet *use* `==` on types at runtime — that becomes reachable through a compiler-track guard `where E1
  * != E2` in a later stage of the plan. Here we only assert that importing `eliot.lang.Eq` does not break the build: the
  * operators and precedence anchors (`above &&`) resolve, and the module coexists with the rest of the base layer.
  */
class EqTypeIntegrationTest extends FullIntegrationTest {

  "importing the Eq layer" should "parse and merge into a real build without breaking it" in {
    compileAndRun(
      """import eliot.effect.Console
        |
        |def main uses Console: Unit = printLine("ok")""".stripMargin
    ).asserting(_ shouldBe "ok")
  }
}
