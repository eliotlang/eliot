package com.vanillasource.eliot.eliotc.jvm

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.compiler.{CompilationSession, Compiler}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** What a resident [[CompilationSession]] compiles must not depend on what it compiled before: a program's diagnostics
  * and its jar must be the ones a cold compiler would produce, whatever the warm cache happens to hold.
  *
  * This is the invariant the shared-session integration suites ([[FullIntegrationTest]]) stand on — every one of them
  * compiles its programs into a session that already holds whatever the *other* suites left there, in an order the test
  * distribution decides — so a break here reads as unrelated suites failing when a test is added or moved. It is also
  * the invariant an ordinary user build stands on, since a warm cache read from disk is the same situation: the session
  * is the seam the CLI and the LSP share.
  *
  * The oracle is a per-program *fresh* session, and the comparison covers all three of a compilation's observable
  * outputs — whether the target was produced, the reported diagnostics, and the jar's bytes (deterministic by
  * construction, see `JvmProgramGenerator.timestamped`). The corpus is small but deliberately mixed: each program
  * declares names the next one does not, which is what leaves the previous program's facts in the cache with nothing
  * in the new program to demand them, and one of them **fails to compile** — a run that updates facts and then dies
  * before its dependents is what mixes generations in the cache (`docs/incremental-compilation.md` §25, §26).
  */
class SharedSessionIsolationTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  /** Everything one compilation is observable by. */
  private case class Outcome(targetProduced: Boolean, errors: Seq[String], jarDigest: Option[String])

  private case class Session(session: CompilationSession, sourceFile: Path, jarPath: Path) {
    def compile(source: String): IO[Outcome] =
      IO.blocking(Files.writeString(sourceFile, source)) >>
        session.compileOnce().flatMap { result =>
          digestOf(jarPath).map(Outcome(result.targetProduced, result.errors.map(_.message).sorted, _))
        }
  }

  private val corpus: Seq[(String, String)] = Seq(
    "hello"   -> """def main uses Console: Unit = printLine("hello")""",
    "arith"   -> """def total: Int = add(40, 2)
                   |
                   |def main uses Console: Unit = printLine(show(total))""".stripMargin,
    "list"    -> """import eliot.collection.List
                   |
                   |def numbers: List[Int] = append(append(empty, 42), 8)
                   |
                   |def total(list: List[Int]): Int = list.foldLeft(0, e -> acc -> add(e, acc))
                   |
                   |def main uses Console: Unit = printLine(show(total(numbers)))""".stripMargin,
    "strings" -> """def main uses Console: Unit = printLine("abc".toUpperCase ++ show("abc".length))""",
    "data"    -> """data Point(x: Int, y: Int)
                   |
                   |def sum(p: Point): Int = add(p.x, p.y)
                   |
                   |def main uses Console: Unit = printLine(show(sum(Point(1, 2))))""".stripMargin,
    "broken"  -> """def main uses Console: Unit = printLine(nonExistentName)""",
    "overlap" -> """ability Display[A] {
                   |   def display(a: A): String
                   |}
                   |
                   |data Database(url: String)
                   |
                   |implement[A] Display[A] { def display(a: A): String = "one" }
                   |implement Display[Database] { def display(d: Database): String = "two" }
                   |
                   |def useDb: String = display(Database("x"))
                   |
                   |def main uses Console: Unit = printLine(useDb)""".stripMargin,
    "runAbort" -> """import eliot.effect.Abort
                   |
                   |def safe uses Abort: String = "config-value"
                   |
                   |def main uses Console: Unit = flatMap(o -> printLine(foldOption("<absent>", s -> s, o)), runAbort(safe))""".stripMargin,
    "runThrow" -> """import eliot.effect.Throw
                   |
                   |def parseBad uses Throw[String]: String = raise("malformed input")
                   |
                   |def main uses Console: Unit = flatMap(e -> printLine(foldEither(err -> err, v -> v, e)), runThrow(parseBad))""".stripMargin,
    "catch"    -> """import eliot.effect.Throw
                   |
                   |def parseOk uses Throw[String]: String = "parsed-value"
                   |def parseBad uses Throw[String]: String = raise("malformed input")
                   |
                   |def main uses Console: Unit = {
                   |   printLine(parseOk catch (err -> err))
                   |   printLine(parseBad catch (err -> err))
                   |}""".stripMargin,
    "catchNonId" -> """import eliot.effect.Throw
                   |
                   |def parseOk uses Throw[String]: String = "ok-value"
                   |def parseBad uses Throw[String]: String = raise("boom")
                   |
                   |def recovered: String = parseBad catch (err -> "recovered-default")
                   |
                   |def main uses Console: Unit = {
                   |   printLine(recovered)
                   |   printLine(parseOk catch (err -> "unused"))
                   |   printLine(parseBad catch (err -> "ambient-default"))
                   |}""".stripMargin,
    "catchEff" -> """import eliot.effect.Throw
                   |
                   |def parseOk uses Throw[String]: String = "parsed-value"
                   |def failUnit uses Throw[String]: Unit = raise("boom")
                   |
                   |def caught uses Console: Unit = failUnit catch (err -> printLine(err))
                   |
                   |def main uses Console: Unit = {
                   |   printLine(parseOk catch (err -> err))
                   |   caught
                   |}""".stripMargin,
    "effects" -> """import eliot.effect.Abort
                   |
                   |def line uses Console: String = readLine.orAbort else "none"
                   |
                   |def main uses Console: Unit = printLine(line)""".stripMargin
  )

  "a program compiled in a shared session" should "have the outcome it has in a fresh one, whatever ran before it" in {
    for {
      fresh   <- freshOutcomes
      forward <- withSession(compileAll(corpus))
      reverse <- withSession(compileAll(corpus.reverse))
    } yield (forward.toMap, reverse.toMap) shouldBe (fresh.toMap, fresh.toMap)
  }

  "a corpus compiled twice in one session" should "have the fresh outcome on both passes" in {
    freshOutcomes.flatMap(fresh =>
      withSession(session => (1 to 2).toList.traverse(_ => compileAll(corpus)(session))).asserting(
        _.map(_.toMap) shouldBe List.fill(2)(fresh.toMap)
      )
    )
  }

  private def compileAll(programs: Seq[(String, String)])(session: Session): IO[Seq[(String, Outcome)]] =
    programs.traverse { case (name, source) => session.compile(source).map(name -> _) }

  /** The oracle: every program compiled by a compiler that has seen nothing else. */
  private def freshOutcomes: IO[Seq[(String, Outcome)]] =
    corpus.traverse { case (name, source) => withSession(_.compile(source)).map(name -> _) }

  private def digestOf(jar: Path): IO[Option[String]] = IO.blocking {
    Option.when(Files.exists(jar))(
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)).map("%02x".format(_)).mkString
    )
  }

  private def withSession[A](use: Session => IO[A]): IO[A] =
    for {
      sourceDir  <- IO.blocking(Files.createTempDirectory("eliot-isolation-src"))
      targetDir  <- IO.blocking(Files.createTempDirectory("eliot-isolation-target"))
      args        = List("jvm", "exe-jar", sourceDir.toString, "-o", targetDir.toString, "-m", "Test") ++ layerPathArgs
      sessionOpt <- Compiler.createSession(args)
      session    <- IO.fromOption(sessionOpt)(new IllegalStateException("Could not create the compilation session."))
      result     <- use(Session(session, sourceDir.resolve("Test.els"), targetDir.resolve("Test.jar")))
    } yield result

  /** One `--path` per layer `eliot/src` root, exactly as [[FullIntegrationTest.SharedSession]] builds them. */
  private def layerPathArgs: List[String] = {
    val repoRoot             =
      Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
    def root(module: String) = repoRoot.resolve(module).resolve("eliot").resolve("src").toString
    List("--path", root("lang"), "--path", root("stdlib"), "--path", root("jvm"))
  }
}
