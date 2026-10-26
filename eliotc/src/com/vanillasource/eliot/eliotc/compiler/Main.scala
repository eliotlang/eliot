package com.vanillasource.eliot.eliotc.compiler

import cats.effect.{ExitCode, IO, IOApp}
import com.vanillasource.eliot.eliotc.compiler.Compiler.runCompiler
import com.vanillasource.eliot.eliotc.feedback.Logging

/** The compiler's command line.
  *
  * cats-effect's fiber tracing is turned off unless the command line set it (`-Dcats.effect.tracing.mode=cached`), in
  * this object's initializer: that runs before `IOApp.main`, which is final, and so before anything touches `IO`, and
  * cats-effect reads the property once, when its tracing class is first loaded. Tracing records where every fiber step
  * was created, for nicer stack traces of the compiler's own failures, and costs a cold build 10%
  * (`docs/compiler-performance.md` §10).
  */
object Main extends IOApp with Logging {
  if (System.getProperty("cats.effect.tracing.mode") == null) {
    val _ = System.setProperty("cats.effect.tracing.mode", "none")
  }

  override def run(args: List[String]): IO[ExitCode] =
    runCompiler(args)
}
