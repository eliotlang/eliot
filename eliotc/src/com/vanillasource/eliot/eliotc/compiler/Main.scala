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
  *
  * The compute pool has **one** worker. When every fact was a fiber of its own, a worker per core meant each new fiber
  * woke an idle worker that took it to another core with cold caches: a quarter of a cold build
  * (`docs/compiler-performance.md` §14.1). The compilation now runs on a thread of its own
  * ([[CompileThread]]), and what is left on the pool is the edge around it.
  */
object Main extends IOApp with Logging {
  if (System.getProperty("cats.effect.tracing.mode") == null) {
    val _ = System.setProperty("cats.effect.tracing.mode", "none")
  }

  override protected def computeWorkerThreadCount: Int = 1

  override def run(args: List[String]): IO[ExitCode] =
    runCompiler(args)
}
