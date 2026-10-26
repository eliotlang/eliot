package com.vanillasource.eliot.eliotc.monomorphize.processor

import cats.effect.IO
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue
import com.vanillasource.eliot.eliotc.processor.{CompilationProcess, CompilerFact, CompilerFactKey}

/** The process a [[ParametricSignatureProcessor]] check runs against: it refuses every read the program itself would
  * never make, and remembers that it did, so the check's result is discarded.
  *
  * Two reads are refused, both answered `None` without reaching the engine:
  *   - a key holding a [[GroundValue.Param]]: a fact *at a placeholder* (an ability resolved at `Param(0)`, a callee's
  *     instance at `[Param(0)]`) is computed on a binder, so the parametric signature is not one substitution away from
  *     the instance's; and its own generation would report errors no instance of the program has;
  *   - a key whose generation is already in progress on this chain: the engine would record a cyclic demand as an
  *     error, and a per-value fact can close a cycle that per-instance keys never did.
  */
final class PlaceholderRefusingProcess(underlying: CompilationProcess) extends CompilationProcess {
  private var refusedRead: Boolean = false

  /** Whether any read was refused: the check then saw less than the program does, and its result must not be used. */
  def refused: Boolean = refusedRead

  override def getFact[V <: CompilerFact, K <: CompilerFactKey[V]](
      key: K,
      ancestors: List[CompilerFactKey[?]]
  ): IO[Option[V]] =
    underlying.activeFactKeys.flatMap { active =>
      if (PlaceholderRefusingProcess.holdsPlaceholder(key) || active.contains(key)) IO { refusedRead = true }.as(None)
      else underlying.getFact(key, ancestors)
    }

  override def registerFact(value: CompilerFact): IO[Unit] = underlying.registerFact(value)

  override def activeFactKeys: IO[List[CompilerFactKey[?]]] = underlying.activeFactKeys
}

object PlaceholderRefusingProcess {

  /** Whether a key holds a [[GroundValue.Param]] anywhere in its fields. */
  def holdsPlaceholder(value: Any): Boolean =
    value match {
      case _: GroundValue.Param => true
      case product: Product     => product.productIterator.exists(holdsPlaceholder)
      case iterable: Iterable[?] => iterable.exists(holdsPlaceholder)
      case _                    => false
    }
}
