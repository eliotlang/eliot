package com.vanillasource.eliot.eliotc.compiler

import cats.effect.IO

/** Where a compilation runs: a platform thread of its own with a large stack.
  *
  * A fact is generated on the stack of the generation that reads it, and a
  * [[com.vanillasource.eliot.eliotc.processor.CompilerIO]] bind is a function call, so the stack holds the whole request
  * chain — hundreds of facts deep, each a few dozen frames of processor code. The stack is reserved, not committed: only
  * what a compilation uses is ever touched.
  *
  * Cancelling the `IO` interrupts the thread, and the engine stops at its next fact read ([[stopIfInterrupted]]); the
  * cancellation completes once the thread has stopped, so the next compilation never overlaps it.
  */
object CompileThread {

  /** One gigabyte. An eliot-build cold compile needs between 256 and 512 kilobytes (measured 2026-10-26: 256 KB
    * overflows, 512 KB does not), and the stack grows with how deep a program's call graph is, so this is a margin of
    * three orders of magnitude. An overflow is not silent: it fails the compilation.
    */
  private val stackSize: Long = 1L << 30

  /** Run `body` on a compilation thread and wait for it; on one already, run it in place. */
  def run[A](body: => A): IO[A] =
    IO.defer {
      if (Thread.currentThread().isInstanceOf[CompilationThread]) IO.delay(body)
      else
        IO.async[A] { callback =>
          IO.delay {
            val thread = new CompilationThread(() =>
              callback(
                try Right(body)
                catch { case t: Throwable => Left(t) }
              )
            )
            thread.start()
            Some(IO.delay(thread.interrupt()) >> IO.blocking(thread.join()))
          }
        }
    }

  /** Throw an `InterruptedException` if the compilation was cancelled. It is not `NonFatal`, so no processor's error
    * handling catches it, and it unwinds the whole compilation.
    */
  def stopIfInterrupted(): Unit =
    if (Thread.currentThread().isInterrupted) throw new InterruptedException("The compilation was cancelled.")

  private final class CompilationThread(body: Runnable)
      extends Thread(null, body, "eliot-compilation", stackSize) {
    setDaemon(true)
  }
}
