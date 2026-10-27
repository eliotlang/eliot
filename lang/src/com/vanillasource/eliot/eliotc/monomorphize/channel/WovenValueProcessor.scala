package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, MonomorphicExpression, MonomorphicValue}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

import scala.collection.mutable

/** The **`WovenValue` codegen seam** — the step between checking and code generation that everything downstream (`used`
  * / `uncurry` / the jvm backend) reads instead of [[MonomorphicValue]].
  *
  * It rewrites one thing, **handles** ([[HandleErasure]]): an effect's environment is a handle, an ordinary value the
  * checker passed (`docs/effects.md` D36), and here a handle that holds nothing stops being passed at all, while one
  * holding one field is passed as that field. It is also the place two **preconditions** are checked before any
  * bytecode is emitted:
  *
  *   - **meta-transfer accounting** — a native leaf producing a meta-carrying type states what it does to it
  *     ([[MetaTransferAccountingProcessor]]);
  *   - **the woven re-check** — the body is type-checked once more on ground types, with no metavariables and no
  *     unification ([[WovenRecheck]]).
  *
  * Each is mandatory rather than advisory: a machine-generated core that nothing re-checks is exactly the
  * silent-miscompile surface the fail-safe rule forbids.
  */
class WovenValueProcessor
    extends TransformationProcessor[MonomorphicValue.Key, WovenValue.Key](key =>
      MonomorphicValue.Key(key.vfqn, key.typeArguments)
    ) {

  override protected def generateFromKeyAndFact(
      key: WovenValue.Key,
      mv: MonomorphicValue
  ): CompilerIO[WovenValue] = {
    for {
      // Meta-transfer accounting (R2) as the same kind of codegen precondition (docs/meta.md §3): a
      // native leaf producing a meta-carrying type without stating what it does to the meta-information fails
      // accounting, whose abort here blocks its `WovenValue` and so its codegen. Without it such a leaf silently
      // defaults its meta to ⊤, which is indistinguishable downstream from "nobody has computed this yet" — the
      // channel's one remaining source of untotality.
      _     <- getFactOrAbort(MetaTransferAccounting.Key(mv.vfqn, mv.typeArguments))
      woven <- HandleErasure.erase(mv)
      _     <- prefetch(woven.runtime.toSeq.flatMap(body => WovenValueProcessor.referencesOf(body.value)))
      // The **woven re-check**: the body that leaves this seam is type-checked once more, on ground types, with no
      // metavariables and no unification — after erasure, so a rewrite that disagreed with itself is caught here.
      _     <- assertWovenRechecks(mv, woven.signature, woven.runtime)
    } yield woven
  }

  /** Report every disagreement [[WovenRecheck]] found in the woven body as a compiler error at the offending node, and
    * abort — a weave that does not type-check must not reach codegen.
    */
  private def assertWovenRechecks(
      mv: MonomorphicValue,
      signature: GroundValue,
      body: Option[Sourced[MonomorphicExpression.Expression]]
  ): CompilerIO[Unit] = {
    val problems = WovenRecheck.check(signature, body)
    problems.headOption.traverse_(problem => compilerAbort[Unit](problem.at.as(problem.message)))
  }
}

object WovenValueProcessor {

  /** The woven value of each distinct value reference in `expression`, in the order code generation's walk meets them.
    *
    * A woven value hints them as soon as it has its body: the walk (`used`) descends into its callees one by one, and
    * each callee's own callees are known only once its body is woven, so a parallel run discovers the program as a
    * cascade instead of in the walk's order (`docs/compiler-performance.md` §15.8). A hinted key the walk then skips,
    * its projection visited already under other type arguments, is generated for nothing; nothing reads it, so nothing
    * reports it.
    */
  def referencesOf(expression: MonomorphicExpression.Expression): Seq[WovenValue.Key] = {
    val keys = mutable.LinkedHashSet.empty[WovenValue.Key]

    def collect(expr: MonomorphicExpression.Expression): Unit =
      expr match {
        case MonomorphicExpression.FunctionApplication(target, argument)     =>
          collect(target.value.expression)
          collect(argument.value.expression)
        case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) => keys += WovenValue.Key(vfqn.value, typeArgs)
        case MonomorphicExpression.FunctionLiteral(_, _, body)               => collect(body.value.expression)
        case _                                                               => ()
      }

    collect(expression)
    keys.toSeq
  }
}
