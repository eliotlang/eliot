package com.vanillasource.eliot.eliotc.compiler

import com.vanillasource.eliot.eliotc.compiler.IncrementalFactGenerator.Slot
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.util.control.NoStackTrace

/** The threads one compilation forks, and who waits for whom among them.
  *
  * A prefetched fact is generated on a **virtual thread** of its own, on the JDK's scheduler, which runs as many at once
  * as there are cores: a blocked one costs nothing, so a read that finds a fact running elsewhere simply blocks on its
  * slot. A virtual thread's stack grows as deep as a platform thread's, `-Xss` bounding both (measured on JDK 25: about
  * 21 000 small frames at the default, 44 million at `-Xss1g`), so a prefetched chain has the JVM's default depth while
  * a root demand keeps [[CompileThread]]'s. An overflow fails the compilation either way; it is never silent.
  *
  * Waiting is where a cycle across threads would hang, so every wait is entered here first ([[await]]): it follows the
  * slot's owner to what that thread waits for, and on, and refuses a wait that comes back to the waiting thread as a
  * [[FactThreads.CyclicWait]]. A cycle on one thread never waits; the request chain refuses it before
  * (`docs/compiler-performance.md` §15.1).
  *
  * A root demand runs through [[settled]]: it returns once every thread forked under it has ended, so the run's maps are
  * complete when the cache and the diagnostics are read. A thread that fails with what the engine does not catch — an
  * overflow, say — fails the demand, and a cancelled demand stops every thread it forked.
  */
final class FactThreads {
  private val started   = new ConcurrentLinkedQueue[Thread]()
  private val failure   = new AtomicReference[Throwable]()
  private val waiting   = mutable.HashMap.empty[Thread, Slot[?]] // guarded by this
  @volatile private var root: Thread       = null
  @volatile private var cancelled: Boolean = false

  /** A virtual thread that runs `body`, not yet started: its slot names it as the owner before anyone can see it. */
  def unstarted(body: () => Unit): Thread =
    Thread
      .ofVirtual()
      .name("eliot-fact")
      .unstarted(() =>
        try body()
        catch {
          case _: InterruptedException => () // the demand was cancelled or failed: nothing to report from here
          case t: Throwable            => fail(t)
        }
      )

  /** Start a thread from [[unstarted]]. One started after the demand was cancelled stops at its first fact read. */
  def start(thread: Thread): Unit = {
    started.add(thread)
    thread.start()
    if (cancelled) thread.interrupt()
  }

  /** Wait until `slot` is done. The wait is refused with a [[FactThreads.CyclicWait]] if the slot's owner waits, through
    * other threads, for a slot this thread owns. A slot found done on the way ends the search: its waiter is about to
    * resume.
    */
  def await(slot: Slot[?]): Unit = {
    val me = Thread.currentThread()

    synchronized {
      @annotation.tailrec
      def cycle(current: Slot[?], path: List[CompilerFactKey[?]]): Option[List[CompilerFactKey[?]]] =
        if (current.done) None
        else
          current.owner match {
            case null                 => None
            case owner if owner eq me => Some(path.reverse)
            case owner                =>
              waiting.get(owner) match {
                case Some(next) => cycle(next, next.key :: path)
                case None       => None
              }
          }

      cycle(slot, List(slot.key)).foreach(keys => throw FactThreads.CyclicWait(keys))
      waiting(me) = slot
    }
    try slot.await()
    finally synchronized { waiting.remove(me): Unit }
  }

  /** Run a root demand on the calling thread and return once every thread forked under it has ended. A demand made
    * while another is running on this thread is that one's, and runs in place.
    */
  def settled[A](body: => A): A =
    if (root eq Thread.currentThread()) body
    else {
      root = Thread.currentThread()
      try {
        val result = body
        joinAll()
        Option(failure.get()).foreach(throw _)
        result
      } catch {
        case t: Throwable =>
          cancelled = true
          started.forEach(_.interrupt())
          joinAllUninterruptibly()
          throw Option(failure.get()).getOrElse(t)
      } finally root = null
    }

  /** A forked thread failed: the first failure is the demand's, and the root is woken to report it. */
  private def fail(t: Throwable): Unit =
    if (failure.compareAndSet(null, t)) Option(root).foreach(_.interrupt())

  /** Join every started thread, oldest first. A joined thread leaves the queue only once it has ended, so a cancellation
    * meanwhile still interrupts it; only this thread removes from the queue, so its head stays the one being joined.
    */
  private def joinAll(): Unit = {
    var thread = started.peek()
    while (thread != null) {
      thread.join()
      started.poll(): Unit
      thread = started.peek()
    }
  }

  /** [[joinAll]], even if this thread is interrupted meanwhile, keeping the interrupt for whoever looks next. */
  private def joinAllUninterruptibly(): Unit = {
    var interrupted = false
    var done        = false
    while (!done)
      try {
        joinAll()
        done = true
      } catch { case _: InterruptedException => interrupted = true }
    if (interrupted) Thread.currentThread().interrupt()
  }
}

object FactThreads {

  /** A wait refused because it would never end: `keys` are the slots waited for, the first by the thread refused, each
    * next by the owner of the one before, the last owned by the refused thread.
    */
  final class CyclicWait(val keys: List[CompilerFactKey[?]]) extends RuntimeException with NoStackTrace {
    def message: String = s"Cyclic fact demand between threads: ${keys.mkString(" waits for ")}"
  }
}
