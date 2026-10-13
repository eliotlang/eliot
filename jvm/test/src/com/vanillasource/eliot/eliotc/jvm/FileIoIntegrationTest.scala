package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of `eliot.file` — the pure `Path` algebra and the `FileSystem` effect (whole-file, folds,
  * metadata/directories; no handles). The effectful programs round-trip real files under a fresh directory built with
  * `createDirectories`, so the assertions observe genuine `java.nio.file` behaviour driven from Eliot. `FileSystem`
  * rides `Suspend` and requires `Throw[IoError]` of its carrier; each program prints its own result and `main`
  * discharges the failure by `runThrow`-ing the report and folding the `Either` (a `Left` prints "failed").
  *
  * Two library idioms show up: a `List`-returning operation is bound to a `val` before it is folded (`val lines =
  * readLines(..); lines.fold…`) — dot-chaining a fold directly onto an effectful `F[List[..]]` mis-infers the carrier —
  * and the discharge names the error type (`(err: IoError) -> …`) so `runThrow`'s error type is pinned.
  */
class FileIoIntegrationTest extends FullIntegrationTest {

  "the Path algebra" should "join, render, and read the extension purely" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.file.Path
        |
        |def target: Path = path("home") / "user" / "Main.els"
        |
        |def ext(p: Path): String = extension(p).foldOption("<none>", e -> e)
        |
        |def main uses Console: Unit = {
        |   printLine(show(target))
        |   printLine(ext(path("Main.els")) ++ " " ++ ext(path("README")))
        |}""".stripMargin
    ).asserting(_ shouldBe "home/user/Main.els\nels <none>")
  }

  "writeFile then readFile" should "round-trip a file's whole contents" in {
    compileAndRun(reportProgram("eliot-it-whole", """
        |   createDirectories(scratch)
        |   writeFile("hello, file", scratch / "greeting.txt")
        |   printLine(readFile(scratch / "greeting.txt"))""".stripMargin))
      .asserting(_ shouldBe "hello, file")
  }

  "appendFile" should "extend an existing file" in {
    compileAndRun(reportProgram("eliot-it-append", """
        |   createDirectories(scratch)
        |   writeFile("one", scratch / "f.txt")
        |   appendFile("-two", scratch / "f.txt")
        |   printLine(readFile(scratch / "f.txt"))""".stripMargin))
      .asserting(_ shouldBe "one-two")
  }

  "readFile on a missing file" should "raise an IoError recovered at main" in {
    compileAndRun(reportProgram("eliot-it-missing", """
        |   printLine(readFile(scratch / "does-not-exist.txt"))""".stripMargin))
      .asserting(_ shouldBe "failed")
  }

  "readLines" should "read a written multi-line file back in order" in {
    compileAndRun(reportProgram("eliot-it-lines", """
        |   createDirectories(scratch)
        |   writeFile("a\nb\nc", scratch / "lines.txt")
        |   val lines = readLines(scratch / "lines.txt")
        |   printLine(show(lines.foldLeft(0, e -> acc -> add(acc, 1))))""".stripMargin, listImport = true))
      .asserting(_ shouldBe "3")
  }

  "foldCodePoints" should "count the code points of a written file" in {
    compileAndRun(reportProgram("eliot-it-cp", """
        |   createDirectories(scratch)
        |   writeFile("hello", scratch / "data.txt")
        |   printLine(show(foldCodePoints(0, acc -> cp -> add(acc, 1), scratch / "data.txt")))""".stripMargin))
      .asserting(_ shouldBe "5")
  }

  "exists / isDirectory / walk" should "observe the filesystem after writes" in {
    compileAndRun(reportProgram("eliot-it-meta", """
        |   createDirectories(scratch)
        |   writeFile("x", scratch / "one.txt")
        |   writeFile("y", scratch / "two.txt")
        |   val present = exists(scratch / "one.txt")
        |   val isDir = isDirectory(scratch)
        |   val files = walk(scratch)
        |   printLine(show(files.foldLeft(0, e -> acc -> add(acc, 1))) ++ " " ++ fold(present, "y", "n") ++ " " ++ fold(isDir, "y", "n"))""".stripMargin, listImport = true))
      .asserting(_ shouldBe "2 y y")
  }

  "foreachLine" should "run an action on each line in order" in {
    compileAndRun(reportProgram("eliot-it-foreach", """
        |   createDirectories(scratch)
        |   writeFile("x\ny\nz", scratch / "lines.txt")
        |   foreachLine(printLine, scratch / "lines.txt")""".stripMargin))
      .asserting(_ shouldBe "x\ny\nz")
  }

  /** A `uses Console, FileSystem, Throw[IoError]` report body (which prints its own result), wrapped in a program
    * whose `main` builds `scratch`, runs the report, and discharges the failure with `catch` — printing "failed" on an
    * `IoError`. The handler names `IoError` so the discharged error type is pinned; `catch` resolves to a direct call, so
    * the program needs no carrier machinery in scope at all.
    */
  private def reportProgram(dir: String, body: String, listImport: Boolean = false): String =
    s"""
       |import eliot.effect.Console
       |${if (listImport) "import eliot.collection.List\n" else ""}import eliot.file.Path
       |import eliot.file.File
       |
       |def scratch: Path = path("$dir")
       |
       |def report uses Console, FileSystem, Throw[IoError]: Unit = {$body
       |}
       |
       |def main uses Console, FileSystem: Unit = report catch ((err: IoError) -> printLine("failed"))""".stripMargin
}
