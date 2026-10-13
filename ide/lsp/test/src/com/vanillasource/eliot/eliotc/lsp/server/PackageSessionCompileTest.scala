package com.vanillasource.eliot.eliotc.lsp.server

import cats.effect.{Deferred, IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.lsp.virtual.VirtualFileSystem
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Package sessions compiled end to end, the way the server plans them from a project model: a library package whose
  * closure has no platform, and an application package over the same sources with the `jvm` layer. The same `main` is
  * checked in both; only the application can run it, and the merged diagnostics judge it by that one.
  */
class PackageSessionCompileTest extends AsyncFlatSpec with AsyncIOSpec with Matchers {
  private val repoRoot                 =
    Path.of(Option(System.getenv("ELIOT_REPO_ROOT")).getOrElse(System.getProperty("user.dir")))
  private def layer(name: String): Path = repoRoot.resolve(name).resolve("eliot").resolve("src")

  private val program = """import eliot.effect.Console
                          |def main uses Console: Unit = printLine("Hello World!")""".stripMargin

  private val broken = """def broken: String = nosuch"""

  "a package session" should "fail a main that nothing in its closure can run" in {
    compiled(Seq(library))(program).asserting(sessions =>
      sessions.map(session => (session.diagnostics.values.flatten.map(_.getMessage).toSeq, monomorphized(session))) shouldBe
        Seq((Seq("No ability implementation found for ability 'Console' with type arguments []."), Some(false)))
    )
  }

  it should "run the same main where the package's closure has a platform" in {
    compiled(Seq(application))(program).asserting(sessions =>
      sessions.map(session => (session.diagnostics, monomorphized(session))) shouldBe Seq((Map.empty, Some(true)))
    )
  }

  it should "report the errors of the project's own sources" in {
    compiled(Seq(library))(broken).asserting(sessions =>
      sessions.flatMap(_.diagnostics.keys).map(uri => Path.of(URI.create(uri)).getFileName.toString) shouldBe Seq("Main.els")
    )
  }

  "merged diagnostics" should "leave out a package that cannot run a main another package runs" in {
    compiled(Seq(library, application))(program).asserting(sessions =>
      EliotCompilationService.merged(sessions.map(session => (session.diagnostics, session.indices.main))) shouldBe Map.empty
    )
  }

  it should "keep a failing main's errors where no package can run it" in {
    compiled(Seq(library, library))(program).asserting(sessions =>
      EliotCompilationService
        .merged(sessions.map(session => (session.diagnostics, session.indices.main)))
        .values
        .flatten
        .map(_.getMessage)
        .toSeq shouldBe Seq("No ability implementation found for ability 'Console' with type arguments [].")
    )
  }

  it should "keep an error every package reports once" in {
    compiled(Seq(library, application))(broken).asserting(sessions =>
      EliotCompilationService.merged(sessions.map(session => (session.diagnostics, session.indices.main))).values.map(_.size).toSeq shouldBe
        Seq(1)
    )
  }

  private def library: (String, Seq[Path])     = "library" -> Seq(layer("lang"), layer("stdlib"))
  private def application: (String, Seq[Path]) = "application" -> Seq(layer("lang"), layer("stdlib"), layer("jvm"))

  private def monomorphized(session: PackageSession): Option[Boolean] =
    session.indices.main.mainAt(session.plan.ownRoots.head.resolve("Main.els").toUri).map(_.monomorphized)

  /** Compile a one-file project `src/Main.els` once per package — a name and the dependency roots its closure mounts —
    * and answer each finished session.
    */
  private def compiled(packages: Seq[(String, Seq[Path])])(source: String): IO[Seq[PackageSession]] =
    projectDirectory.use { project =>
      val sources = project.resolve("src")
      IO.blocking(Files.writeString(sources.resolve("Main.els"), source)) >>
        packages.zipWithIndex.traverse { case ((name, dependencyRoots), index) =>
          val plan = WorkspacePlan.Session(
            name,
            Seq(sources),
            Seq(sources),
            sources +: dependencyRoots,
            project.resolve(".eliot-lsp").resolve(s"$name-$index"),
            Some(Seq(sources))
          )
          for {
            finished <- Deferred[IO, PackageSession]
            session  <- PackageSession.start(plan, new VirtualFileSystem, finished.complete(_).void)
            _        <- session.requestCompile
            done     <- finished.get.guarantee(session.release)
          } yield done
        }
    }

  private def projectDirectory: Resource[IO, Path] =
    Resource.make(IO.blocking {
      val project = Files.createTempDirectory("eliot-lsp-package")
      Files.createDirectories(project.resolve("src"))
      project
    })(project =>
      IO.blocking(Files.walk(project).sorted(java.util.Comparator.reverseOrder()).iterator().asScala.foreach(Files.delete))
    )
}
