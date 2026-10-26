package com.vanillasource.eliot.eliotc.monomorphize.fact

import com.vanillasource.eliot.eliotc.compiler.cache.codec.FactCodec
import com.vanillasource.eliot.eliotc.module.fact.ValueFQN
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.{Spine, VTopDef}
import com.vanillasource.eliot.eliotc.processor.{CompilerFact, CompilerFactKey}

/** A compiler-track value's reduced body at one instantiation, linked once for every reader: each value reference it
  * makes bound to that reference's own reduced instance (or, with none, its raw [[NativeBinding]]).
  *
  * A compile-time reduction reaches a callee in this form, both the checker's escalation and the meta channel's
  * `reduceApplied`, so the same instance used to be linked again at every use, down its whole transitive closure
  * (`docs/compiler-performance.md` §13.3: 139 573 links of 1 379 instances in eliot-build).
  *
  * The link is shared only when it does not depend on who asked. Linking skips a reference whose binding is in progress
  * on the request chain, and the engine refuses to read a fact that is; a link that met either answers
  * [[ReducedInstance.Link.Unshared]], and its reader links afresh in its own context, as before this fact existed. A
  * fact a shared link read has completed, so it is never in progress on a later chain: every later fresh link would
  * read what this one read.
  *
  * @param link
  *   The linked instance, its absence, or that it must be linked by each reader.
  */
case class ReducedInstance(vfqn: ValueFQN, typeArguments: Seq[GroundValue], link: ReducedInstance.Link)
    extends CompilerFact {
  override def key(): CompilerFactKey[ReducedInstance] = ReducedInstance.Key(vfqn, typeArguments)
}

object ReducedInstance {

  /** What linking an instance gave. */
  enum Link {

    /** The instance has no [[CompilerMonomorphicValue]], or one with no reduced body. */
    case Absent

    /** The link met the request chain, so each reader links the instance itself. */
    case Unshared

    /** The linked instance, and its binding: a `VTopDef` whose thunk evaluates the linked body. */
    case Shared(linked: Linked, binding: SemValue)
  }

  /** A reduced body with the bindings of its value references, ready to evaluate. A `MonomorphicEvaluator` looks a
    * reference up by its FQN alone, so when a callee is linked at two instantiations, each instantiation's references
    * are renamed apart in `body` (`f#0`, `f#1`) and `instances` answers each new name with the reference it stands for,
    * the scheme `PostDrainQuoter.reduceThreaded` uses for the same reason.
    *
    * @param bindings
    *   The binding of each reference in `body`, by the name it has there.
    * @param instances
    *   Each renamed reference's original FQN and ground type arguments.
    */
  case class Linked(
      body: MonomorphicExpression.Expression,
      bindings: Map[ValueFQN, SemValue],
      instances: Map[ValueFQN, (ValueFQN, Seq[GroundValue])]
  ) {

    /** The lookup evaluating `body`: `extra` first, then the linked bindings. A renamed reference left unlinked (an
      * ancestor on the fact chain) is the stuck definition of its original name, exactly what the evaluator makes of an
      * unbound reference, never a definition named after the instance.
      */
    def lookup(extra: Map[ValueFQN, SemValue]): ValueFQN => Option[SemValue] =
      name =>
        extra
          .get(name)
          .orElse(bindings.get(name))
          .orElse(instances.get(name).map { case (original, _) => VTopDef(original, None, Spine.SNil) })
  }

  case class Key(vfqn: ValueFQN, typeArguments: Seq[GroundValue]) extends CompilerFactKey[ReducedInstance] {

    /** Declined: its bindings are `SemValue` closures, never equal, as [[NativeBinding]]'s. */
    override def valueCodec: Option[FactCodec[ReducedInstance]] = None
  }
}
