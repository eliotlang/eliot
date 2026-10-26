package com.vanillasource.eliot.eliotc.processor

import cats.data.{Chain, Kleisli}
import cats.effect.IO
import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.CompilerError

import scala.util.control.NoStackTrace

object CompilerIO {

  /** What a running [[CompilerIO]] reads: the compilation process it asks for facts, and the errors it has accumulated.
    *
    * The errors are a plain field, not a `Ref`, because a generation runs sequentially: nothing inside one computation
    * forks. A processor that starts sub-computations in parallel within one generation would have to make it atomic.
    * Every read of it is deferred to run time (`IO.delay`/`IO.defer`), never done when a [[Kleisli]] is applied, since
    * combinators such as `map2` apply the second computation's function before the first one's effects have run.
    */
  final class Context(val process: CompilationProcess) {
    var errors: Chain[CompilerError] = Chain.empty
  }

  /** An aborted computation, carrying the errors accumulated up to the abort. It never escapes [[runCompilerIO]]. */
  private final class Aborted(val errors: Chain[CompilerError]) extends RuntimeException with NoStackTrace

  /** The effect all compiler processors run in. It is capable of accumulating errors, short-circuiting, and has access
    * to the CompilationProcess.
    *
    * It is one reader over `IO`: the errors accumulate in the [[Context]], and an abort is an exception. It used to be
    * `ReaderT[StateT[EitherT[IO, …]]]`, whose three layers of binds were a quarter of a cold build's CPU
    * (`docs/compiler-performance.md` §7).
    */
  type CompilerIO[T] = Kleisli[IO, Context, T]

  /** Run a computation against `process`: `Left` with the errors it aborted with, else `Right` with the errors it
    * accumulated and its value.
    */
  def runCompilerIO[T](
      process: CompilationProcess
  )(computation: CompilerIO[T]): IO[Either[Chain[CompilerError], (Chain[CompilerError], T)]] =
    IO.defer {
      val context = new Context(process)
      computation.run(context).attempt.flatMap {
        case Right(value)      => IO.pure(Right((context.errors, value)))
        case Left(a: Aborted)  => IO.pure(Left(a.errors))
        case Left(throwable)   => IO.raiseError(throwable)
      }
    }

  /** Run `body` against the process `wrap` makes of the current one, keeping the current errors. This is how a wrapper
    * observes what one processor does, e.g. timing its fact reads.
    */
  def withProcess[P <: CompilationProcess, T](wrap: CompilationProcess => P)(body: P => CompilerIO[T]): CompilerIO[T] =
    Kleisli { context =>
      IO.defer {
        val wrapped = wrap(context.process)
        val inner   = new Context(wrapped)
        inner.errors = context.errors
        body(wrapped).run(inner).guarantee(IO { context.errors = inner.errors })
      }
    }

  /** Returns the fact from the running compiler as an Option, without aborting.
    */
  private def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): CompilerIO[Option[V]] =
    Kleisli(context => context.process.getFact(key))

  /** Returns the fact from the running compiler or short circuits.
    *
    * This is the default read: a missing fact aborts this computation. Use it whenever absence means either "this
    * cannot happen" (the producing processor always succeeds) or "the failure was already reported upstream" — in both
    * cases producing no output is the correct reaction, and the abort is the sanctioned way to *decline* producing this
    * fact (the engine treats an explicit abort as a decline, never as an internal error).
    */
  def getFactOrAbort[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): CompilerIO[V] =
    Kleisli(context =>
      context.process.getFact(key).flatMap {
        case Some(fact) => IO.pure(fact)
        case None       => IO.raiseError(new Aborted(context.errors))
      }
    )

  /** Returns the fact from the running compiler, or registers the given error and short circuits. Use when the caller
    * owns a user-facing message for the missing fact (e.g. "Could not find imported module").
    */
  def getFactOrError[V <: CompilerFact, K <: CompilerFactKey[V]](key: K)(error: CompilerIO[Unit]): CompilerIO[V] =
    getFact(key).flatMap {
      case Some(fact) => fact.pure[CompilerIO]
      case None       => error >> abort[V]
    }

  /** Returns the fact if it was produced, `None` otherwise, and *continues* either way. Reserved for sites where
    * absence is an expected, handled outcome by design: a producer that legitimately declines (its decline is an
    * explicit abort on its side), or an upstream failure that was already reported and this computation deliberately
    * proceeds without the input (e.g. skipping a broken callee). Never use this to paper over a missing producer — if
    * the fact "should be there", use [[getFactOrAbort]].
    */
  def getFactIfProduced[V <: CompilerFact, K <: CompilerFactKey[V]](key: K): CompilerIO[Option[V]] =
    getFact(key)

  /** The keys of the fact computations currently in progress on this request chain (the ancestors of the fact being
    * generated now). See [[CompilationProcess.activeFactKeys]]; a processor reads this to detect a cyclic fact-request
    * chain before it dead-locks on the [[cats.effect.Deferred]]-based fact cache.
    */
  def activeFactKeys: CompilerIO[List[CompilerFactKey[?]]] =
    Kleisli(context => context.process.activeFactKeys)

  /** Register an error.
    */
  def registerCompilerError(error: CompilerError): CompilerIO[Unit] =
    Kleisli(context => IO { context.errors = context.errors :+ error })

  /** Returns true if there are no errors accumulated in the CompilerIO.
    */
  def isClear: CompilerIO[Boolean] = currentErrors.map(_.isEmpty)

  /** Returns the currently accumulated errors.
    */
  def currentErrors: CompilerIO[Chain[CompilerError]] =
    Kleisli(context => IO(context.errors))

  /** Registers the fact, but only if the current compiler process is clean of errors!
    */
  def registerFactIfClear(value: CompilerFact): CompilerIO[Unit] =
    Kleisli(context => IO.defer(if (context.errors.isEmpty) context.process.registerFact(value) else IO.unit))

  /** Aborts the computation with the errors accumulated so far.
    */
  def abort[T]: CompilerIO[T] =
    Kleisli(context => IO.defer(IO.raiseError(new Aborted(context.errors))))

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
    recoverWithAborted(computation)(default).map(_._1)

  /** Like [[recover]], but also reports whether the computation had aborted. Lets a container processor preserve the
    * abort (decline) signal of its children for the engine while still running every child and keeping all errors.
    */
  def recoverWithAborted[T](computation: CompilerIO[T])(default: T): CompilerIO[(T, Boolean)] =
    Kleisli(context =>
      runCompilerIO(context.process)(computation).map {
        case Left(errors)           =>
          context.errors = context.errors ++ errors
          (default, true)
        case Right((errors, value)) =>
          context.errors = context.errors ++ errors
          (value, false)
      }
    )
}
