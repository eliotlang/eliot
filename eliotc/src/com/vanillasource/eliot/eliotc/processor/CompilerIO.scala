package com.vanillasource.eliot.eliotc.processor

import cats.data.Chain
import cats.effect.kernel.{CancelScope, Poll, Sync}
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, LiftIO}
import com.vanillasource.eliot.eliotc.feedback.CompilerError

import scala.annotation.tailrec
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, NANOSECONDS}
import scala.util.control.{NoStackTrace, NonFatal}

object CompilerIO {

  /** What a running [[CompilerIO]] reads: the compilation process it asks for facts, and the errors it has accumulated.
    *
    * The errors are a plain field, because a generation runs on one thread: nothing inside one computation forks. A
    * processor that starts sub-computations in parallel within one generation would have to make it atomic.
    */
  final class Context(val process: CompilationProcess) {
    var errors: Chain[CompilerError] = Chain.empty
  }

  /** An aborted computation, carrying the errors accumulated up to the abort. It never escapes [[runCompilerIO]]. */
  private final class Aborted(val errors: Chain[CompilerError]) extends RuntimeException with NoStackTrace

  /** The effect all compiler processors run in. It is capable of accumulating errors, short-circuiting, and has access
    * to the CompilationProcess.
    *
    * It is a plain function of the [[Context]], so a bind is a function call: running one runs it, on the caller's
    * stack, with nothing interpreting it. The errors accumulate in the context and an abort is an exception. It used to
    * be a reader over `IO`, and before that `ReaderT[StateT[EitherT[IO, …]]]`; interpreting those binds was a quarter
    * of a cold build's CPU and then a third of what was left (`docs/compiler-performance.md` §7, §14).
    *
    * A computation is a description until it is run, as before: `whenA`, `recover` and `traverse` take one and decide
    * whether and how often it runs. It is not stack-safe the way `IO` is — a deep chain of binds is a deep stack — which
    * is why a compilation runs on a thread with a large one
    * ([[com.vanillasource.eliot.eliotc.compiler.CompileThread]]).
    */
  opaque type CompilerIO[T] = Context => T

  /** A bind as a method, as `Kleisli` had one: what a `for` over [[CompilerIO]] compiles to, so it needs no cats syntax
    * in scope, and it is a call rather than a conversion to cats' syntax wrapper first.
    */
  extension [A](fa: CompilerIO[A]) {
    def map[B](f: A => B): CompilerIO[B] = context => f(fa(context))

    def flatMap[B](f: A => CompilerIO[B]): CompilerIO[B] = context => f(fa(context))(context)
  }

  /** Run a computation against `process`: `Left` with the errors it aborted with, else `Right` with the errors it
    * accumulated and its value.
    */
  def runCompilerIO[T](
      process: CompilationProcess
  )(computation: CompilerIO[T]): Either[Chain[CompilerError], (Chain[CompilerError], T)] = {
    val context = new Context(process)

    try {
      val value = computation(context)
      Right((context.errors, value))
    } catch { case a: Aborted => Left(a.errors) }
  }

  /** Run `body` against the process `wrap` makes of the current one, keeping the current errors. This is how a wrapper
    * observes what one processor does, e.g. timing its fact reads.
    */
  def withProcess[P <: CompilationProcess, T](wrap: CompilationProcess => P)(body: P => CompilerIO[T]): CompilerIO[T] =
    context => {
      val wrapped = wrap(context.process)
      val inner   = new Context(wrapped)
      inner.errors = context.errors
      try body(wrapped)(inner)
      finally context.errors = inner.errors
    }

  /** Returns the fact from the running compiler or short circuits.
    *
    * This is the default read: a missing fact aborts this computation. Use it whenever absence means either "this
    * cannot happen" (the producing processor always succeeds) or "the failure was already reported upstream" — in both
    * cases producing no output is the correct reaction, and the abort is the sanctioned way to *decline* producing this
    * fact (the engine treats an explicit abort as a decline, never as an internal error).
    */
  def getFactOrAbort[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): CompilerIO[V] =
    context =>
      context.process.getFact(key) match {
        case Some(fact) => fact
        case None       => throw new Aborted(context.errors)
      }

  /** Returns the fact from the running compiler, or registers the given error and short circuits. Use when the caller
    * owns a user-facing message for the missing fact (e.g. "Could not find imported module").
    */
  def getFactOrError[V <: CompilerFact, K <: CompilerFactKey[V]](key: K)(error: CompilerIO[Unit]): CompilerIO[V] =
    context =>
      context.process.getFact(key) match {
        case Some(fact) => fact
        case None       =>
          error(context)
          throw new Aborted(context.errors)
      }

  /** Returns the fact if it was produced, `None` otherwise, and *continues* either way. Reserved for sites where
    * absence is an expected, handled outcome by design: a producer that legitimately declines (its decline is an
    * explicit abort on its side), or an upstream failure that was already reported and this computation deliberately
    * proceeds without the input (e.g. skipping a broken callee). Never use this to paper over a missing producer — if
    * the fact "should be there", use [[getFactOrAbort]].
    */
  def getFactIfProduced[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): CompilerIO[Option[V]] =
    context => context.process.getFact(key)

  /** The keys of the fact computations currently in progress on this request chain (the ancestors of the fact being
    * generated now). See [[CompilationProcess.activeFactKeys]]; a processor reads this to detect a cyclic fact-request
    * chain before the engine refuses it.
    */
  def activeFactKeys: CompilerIO[List[CompilerFactKey[?]]] =
    context => context.process.activeFactKeys

  /** Register an error.
    */
  def registerCompilerError(error: CompilerError): CompilerIO[Unit] =
    context => context.errors = context.errors :+ error

  /** Returns true if there are no errors accumulated in the CompilerIO.
    */
  def isClear: CompilerIO[Boolean] = context => context.errors.isEmpty

  /** Returns the currently accumulated errors.
    */
  def currentErrors: CompilerIO[Chain[CompilerError]] =
    context => context.errors

  /** Registers the fact, but only if the current compiler process is clean of errors!
    */
  def registerFactIfClear(value: CompilerFact): CompilerIO[Unit] =
    context => if (context.errors.isEmpty) context.process.registerFact(value)

  /** Aborts the computation with the errors accumulated so far.
    */
  def abort[T]: CompilerIO[T] =
    context => throw new Aborted(context.errors)

  /** Recovers from a potentially aborted computation, keeping its errors. The computation runs in an isolated error
    * context, and all of its errors are merged back into the current context, whether it aborted or not.
    *
    * @param computation
    *   The computation to run in isolation
    * @param default
    *   The value to return if the computation aborted
    * @return
    *   A computation that cannot abort (all errors are captured in state)
    */
  def recover[T](computation: CompilerIO[T])(default: T): CompilerIO[T] =
    context => recoverWithAborted(computation)(default)(context)._1

  /** Like [[recover]], but also reports whether the computation had aborted. Lets a container processor preserve the
    * abort (decline) signal of its children for the engine while still running every child and keeping all errors.
    */
  def recoverWithAborted[T](computation: CompilerIO[T])(default: T): CompilerIO[(T, Boolean)] =
    context =>
      runCompilerIO(context.process)(computation) match {
        case Left(errors)           =>
          context.errors = context.errors ++ errors
          (default, true)
        case Right((errors, value)) =>
          context.errors = context.errors ++ errors
          (value, false)
      }

  /** Everything cats asks of [[CompilerIO]]: a monad whose errors are exceptions (an abort among them, as it was an
    * `IO` error before), `Sync` for the code generator's `[F[_]: Sync]` code and for logging, and `LiftIO` for the few
    * reads of the world written as `IO` ([[runIO]]).
    *
    * There is nothing to cancel: a compilation is stopped by interrupting its thread, which no handler here catches,
    * since `InterruptedException` is not `NonFatal`. So `uncancelable` just runs its body and a finalizer runs on an
    * error only.
    */
  given compilerIOInstances: Sync[CompilerIO] with LiftIO[CompilerIO] with {
    override def pure[A](x: A): CompilerIO[A] = _ => x

    override def map[A, B](fa: CompilerIO[A])(f: A => B): CompilerIO[B] = context => f(fa(context))

    override def flatMap[A, B](fa: CompilerIO[A])(f: A => CompilerIO[B]): CompilerIO[B] =
      context => f(fa(context))(context)

    override def tailRecM[A, B](a: A)(f: A => CompilerIO[Either[A, B]]): CompilerIO[B] =
      context => {
        @tailrec
        def loop(current: A): B = f(current)(context) match {
          case Left(next)   => loop(next)
          case Right(value) => value
        }

        loop(a)
      }

    override def raiseError[A](e: Throwable): CompilerIO[A] = _ => throw e

    override def handleErrorWith[A](fa: CompilerIO[A])(f: Throwable => CompilerIO[A]): CompilerIO[A] =
      context =>
        try fa(context)
        catch { case NonFatal(e) => f(e)(context) }

    override def rootCancelScope: CancelScope = CancelScope.Uncancelable

    override def forceR[A, B](fa: CompilerIO[A])(fb: CompilerIO[B]): CompilerIO[B] =
      context => {
        try { val _ = fa(context) }
        catch { case NonFatal(_) => () }
        fb(context)
      }

    override def uncancelable[A](body: Poll[CompilerIO] => CompilerIO[A]): CompilerIO[A] =
      body(new Poll[CompilerIO] { def apply[B](fb: CompilerIO[B]): CompilerIO[B] = fb })

    override def canceled: CompilerIO[Unit] = _ => ()

    override def onCancel[A](fa: CompilerIO[A], fin: CompilerIO[Unit]): CompilerIO[A] = fa

    override def monotonic: CompilerIO[FiniteDuration] = _ => FiniteDuration(System.nanoTime(), NANOSECONDS)

    override def realTime: CompilerIO[FiniteDuration] = _ => FiniteDuration(System.currentTimeMillis(), MILLISECONDS)

    override def suspend[A](hint: Sync.Type)(thunk: => A): CompilerIO[A] = _ => thunk

    override def liftIO[A](ioa: IO[A]): CompilerIO[A] = _ => runIO(ioa)
  }

  /** Run `io` on the calling thread, which is a compilation's. A read of the world is written as `IO`
    * (`IO.blocking { Files… }`), and `syncStep` runs it here, `blocking` included, up to the first asynchronous
    * boundary; one that has one is handed to the runtime and waited for.
    */
  private def runIO[A](io: IO[A]): A =
    io.syncStep(Int.MaxValue).unsafeRunSync() match {
      case Right(value)    => value
      case Left(remaining) => remaining.unsafeRunSync()(using IORuntime.global)
    }
}
