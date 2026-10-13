package com.vanillasource.eliot.eliotc.jvm

import java.nio.file.Path

/** End-to-end coverage of `eliot.system` — the `Environment` effect (arguments, variables, working directory) and the
  * `Process` effect (running another program, capturing or inheriting its streams).
  *
  * `Environment` rides `Suspend` alone, like `Console`: nothing it does can fail. `Process` additionally requires
  * `Throw[IoError]` of its carrier — it shares `eliot.file`'s error type, so a program that reads files and runs
  * programs has one failure channel — and each process program is therefore wrapped the way the file tests wrap theirs:
  * `main` runs the report through `runThrow` and folds the `Either`, printing "failed" on an `IoError`.
  *
  * The child process is this test JVM's own `java` binary, located from `java.home` and interpolated into the Eliot
  * source, so the tests spawn something that certainly exists without assuming anything about the machine's `PATH`.
  * `java -version` is the canonical do-nothing invocation: it exits 0 and writes to standard *error*, which is exactly
  * what makes it a good check that both captured streams are wired up separately.
  *
  * One consequence of the harness: it invokes `main(String[])` reflectively with an empty array, so a program under
  * test sees no arguments — which is what the `arguments` case asserts, and is a real property (a program run with no
  * arguments must see an empty list, never a null).
  *
  * The `registerExitCode` cases are the exception: an exit code is a property of a real process and a non-zero one
  * ends in `System.exit`, so they run their program out-of-process through `compileAndRunToCompletion` and assert on
  * the code as well as on the output.
  */
class SystemIoIntegrationTest extends FullIntegrationTest {

  private val javaBinary: String = Path.of(System.getProperty("java.home"), "bin", "java").toString

  "arguments" should "yield the empty list for a program started with none" in {
    compileAndRun(environmentProgram("""
        |   val given = arguments
        |   printLine(show(given.foldLeft(0, e -> acc -> add(acc, 1))))""".stripMargin))
      .asserting(_ shouldBe "0")
  }

  "environmentVariable" should "read a variable that is set" in {
    compileAndRun(environmentProgram("""
        |   val searchPath = environmentVariable("PATH")
        |   printLine(fold(isBlank(searchPath orElse ""), "unset", "set"))""".stripMargin))
      .asserting(_ shouldBe "set")
  }

  it should "yield None for a variable that is not set" in {
    compileAndRun(environmentProgram("""
        |   val absent = environmentVariable("ELIOT_INTEGRATION_DEFINITELY_UNSET")
        |   printLine(absent orElse "<unset>")""".stripMargin))
      .asserting(_ shouldBe "<unset>")
  }

  "workingDirectory" should "be an absolute path" in {
    compileAndRun(environmentProgram("""
        |   val here = workingDirectory
        |   printLine(fold(isAbsolute(here), "absolute", "relative"))""".stripMargin))
      .asserting(_ shouldBe "absolute")
  }

  "run" should "capture a child's output and exit code" in {
    compileAndRun(processProgram(s"""
        |   val here = workingDirectory
        |   val result = run(command("$javaBinary").withArgument("-version"), here)
        |   printLine(show(result.exitCode) ++ " " ++ fold(isBlank(result.standardError), "quiet", "spoke"))""".stripMargin))
      .asserting(_ shouldBe "0 spoke")
  }

  it should "report a non-zero exit as data rather than as a failure" in {
    compileAndRun(processProgram(s"""
        |   val here = workingDirectory
        |   val result = run(command("$javaBinary").withArgument("--no-such-option"), here)
        |   printLine(fold(result.exitCode <= 0, "zero", "non-zero"))""".stripMargin))
      .asserting(_ shouldBe "non-zero")
  }

  it should "raise an IoError when the executable does not exist" in {
    compileAndRun(processProgram("""
        |   val here = workingDirectory
        |   val result = run(command("eliot-no-such-executable"), here)
        |   printLine(show(result.exitCode))""".stripMargin))
      .asserting(_ shouldBe "failed")
  }

  it should "keep the two captured streams apart" in {
    compileAndRun(processProgram(s"""
        |   val here = workingDirectory
        |   val result = run(command("$javaBinary").withArgument("-version"), here)
        |   printLine(fold(isBlank(result.standardOutput), "out-empty", "out-full"))""".stripMargin))
      .asserting(_ shouldBe "out-empty")
  }

  "runInheritingIo" should "yield the child's exit code" in {
    compileAndRun(processProgram(s"""
        |   val here = workingDirectory
        |   val code = runInheritingIo(command("$javaBinary").withArgument("-version"), here)
        |   printLine(show(code))""".stripMargin))
      .asserting(_ shouldBe "0")
  }

  "registerExitCode" should "report the registered code, having run everything written after it" in {
    compileAndRunToCompletion(exitCodeProgram("""
        |   registerExitCode(3)
        |   printLine("after")""".stripMargin))
      .asserting(_ shouldBe ("after", 3))
  }

  it should "report zero for a program that registers nothing" in {
    compileAndRunToCompletion(exitCodeProgram("""
        |   printLine("nothing registered")""".stripMargin, row = "Console"))
      .asserting(_ shouldBe ("nothing registered", 0))
  }

  it should "report the last code registered, not the first" in {
    compileAndRunToCompletion(exitCodeProgram("""
        |   registerExitCode(3)
        |   registerExitCode(7)
        |   printLine("twice")""".stripMargin))
      .asserting(_ shouldBe ("twice", 7))
  }

  it should "report a code registered back to zero as success" in {
    compileAndRunToCompletion(exitCodeProgram("""
        |   registerExitCode(4)
        |   registerExitCode(0)
        |   printLine("reconsidered")""".stripMargin))
      .asserting(_ shouldBe ("reconsidered", 0))
  }

  /** A `uses Console, Process` program: `registerExitCode` performs no I/O that can fail, so unlike the spawning
    * operations it needs no `Throw[IoError]` discharge.
    */
  private def exitCodeProgram(body: String, row: String = "Console, Process"): String =
    s"""
       |import eliot.effect.Console
       |import eliot.system.Process
       |
       |def main uses $row: Unit = {$body
       |}""".stripMargin

  /** A `uses Console, Environment` program — no failure channel, so it needs no discharge at all. */
  private def environmentProgram(body: String): String =
    s"""
       |import eliot.effect.Console
       |import eliot.collection.List
       |import eliot.file.Path
       |import eliot.system.Environment
       |
       |def main uses Console, Environment: Unit = {$body
       |}""".stripMargin

  /** A `uses Console, Environment, Process, Throw[IoError]` report body wrapped in a `main` that discharges the
    * failure with `catch`, printing "failed" on an `IoError` — the `FileIoIntegrationTest` shape, with `Environment`
    * added because every process needs a directory to run in.
    */
  private def processProgram(body: String): String =
    s"""
       |import eliot.effect.Console
       |import eliot.collection.List
       |import eliot.file.Path
       |import eliot.file.File
       |import eliot.system.Environment
       |import eliot.system.Process
       |
       |def report uses Console, Environment, Process, Throw[IoError]: Unit = {$body
       |}
       |
       |def main uses Console, Environment, Process: Unit = report catch ((err: IoError) -> printLine("failed"))""".stripMargin
}
