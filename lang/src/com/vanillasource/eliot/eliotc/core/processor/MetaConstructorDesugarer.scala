package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
  DataDefinition,
  FunctionDefinition,
  Expression as SourceExpression
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a **slotted** type declaration into its **meta structure** — the type of the refinement meta a value of
  * that type carries — and the matching auto-derived `Meta` instance. `Meta` is nonetheless *total*: a type with no
  * slots simply has meta [[Unit]] (there is nothing to refine), which the refinement channel supplies uniformly
  * ([[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementChannelProcessor.metaTypeOf]]) rather than a
  * per-type synthetic — so nothing downstream special-cases "does this type have a meta?", yet no slotless type needs a
  * generated `T$Meta` (which, being a *concrete* alias, would clash for a type declared in more than one layer).
  *
  * `type Int {range: Interval[BigInteger]}` desugars exactly as if the user had written `data Int$Meta(range:
  * Interval[BigInteger])` — a type constructor, a value constructor, a per-slot accessor, and a `PatternMatch`
  * implementation the accessor reduces through — **plus** the auto-derived `Meta[Int$Meta]` instance. So the meta of an
  * `Int` is an `Int$Meta` structure whose `range` field is an `Interval`, joined field-wise, while the meta of a `Bool`
  * or a `String` is the trivial `Unit` (its one `Meta[Unit]` instance, declared with `Unit`, is the do-nothing join).
  *
  * The `$Meta` suffix keeps the meta type's name distinct from the ordinary `Int^Type` without a new namespace: `$` is
  * not a valid identifier character (the lexer's `isLetter`/`isLetterOrDigit`), so `Int$Meta` can never collide with a
  * user type. This makes the transfer companion generatable with **no lookup** — a slotted value of type `T` has meta
  * type `T$Meta`, a pure name transform. Meta structures are compiler-pool-only (the channel evaluates them) — dead in
  * the runtime pool, never code-generated.
  *
  * Reuses [[DataDefinitionDesugarer]] verbatim (per "reuse, don't write parallel generators"), so multi-slot types are
  * multi-field structures for free. Type generic parameters are not yet threaded onto the meta structure — the only
  * slotted type today has a concrete-domain single slot.
  */
object MetaConstructorDesugarer {

  /** The `data`-style meta structure generated from `definition`'s meta-slot brace **plus** the auto-derived
    * `Meta[<Name>$Meta]` instance, or empty when it declares no slots (every ordinary `def`/alias/slotless type — whose
    * meta is the trivial `Unit`, supplied by the channel, not a synthetic here).
    */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    if (definition.metaSlots.isEmpty) Seq.empty
    else DataDefinitionDesugarer.desugar(metaStructure(definition)) ++ metaJoinInstance(definition)

  /** The synthetic `data <Name>$Meta(<slots>)` whose desugaring *is* the meta structure. */
  private def metaStructure(definition: FunctionDefinition): DataDefinition = {
    val metaTypeName = definition.name.map(_.name + metaTypeSuffix)
    DataDefinition(
      metaTypeName,
      Seq.empty,
      Some(Seq(DataConstructor(metaTypeName, definition.metaSlots))),
      visibility = definition.visibility
    )
  }

  /** The auto-derived `Meta[<Name>$Meta]` **instance** — the lattice join and widening over the type's meta structure
    * (Step 1 of `docs/generic-refinement-merges.md`). A *user* declares `Meta` only for their own domain
    * (`Meta[Interval[T]]`); the compiler derives the matching instance for the compound *meta structure* `Int$Meta`
    * field-wise, so `fold`'s generic `^Meta` companion (`join(whenTrue, whenFalse)`, reduced by the channel at the meta
    * type args) has a `Meta[Int$Meta]` to reduce through:
    *
    * {{{
    *   implement Meta[Int$Meta] {
    *     def join(a: Int$Meta, b: Int$Meta): Int$Meta = Int$Meta(join(range(a), range(b)))
    *     def widen(a: Int$Meta, b: Int$Meta): Int$Meta = Int$Meta(widen(range(a), range(b)))
    *   }
    * }}}
    *
    * Each slot is joined (widened) by the `Meta` ability's own `join` (`widen`), dispatched on the slot's domain
    * (`range(a) : Interval[..]` ⤳ the `Meta[Interval[T]]` instance). The compound join thus routes through the domain's
    * *instance*, not a hand-copied plain function: the refinement channel's post-monomorphize executor links each
    * instance at its own monomorphization (`docs/refinement-channel-follow-ups.md`), so a transitively-reached
    * Eliot-body instance dispatches. The result is rewrapped in the meta *constructor* `Int$Meta(..)`.
    *
    * The marker/method shapes mirror [[DataDefinitionDesugarer.createPatternMatchImpl]] and the surface `implement`
    * desugar ([[com.vanillasource.eliot.eliotc.ast.fact.ImplementBlock]]): a body-less marker whose sole arg is the
    * pattern type and whose return slot is the (unguarded) `true`, keyed by `Qualifier.AbilityImplementation("Meta",
    * "<Name>$Meta")` — the meta structure is nullary, so its pattern key is just its name. A slot whose domain head is
    * not a simple type application yields no instance (fail-safe: the channel then hard-errors "No ability
    * implementation found", never accepts a wrong join); the only slotted type today is `Int`'s clean `Interval` slot.
    */
  private def metaJoinInstance(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] = {
    val metaTypeName  = definition.name.map(_.name + metaTypeSuffix)
    val metaTypeRef   = app(metaTypeName)
    val implQualifier = Qualifier.AbilityImplementation("Meta", metaTypeName.value, None)
    val perSlotJoins  = definition.metaSlots.traverse(slotJoin(_, "join"))
    val perSlotWidens = definition.metaSlots.traverse(slotJoin(_, "widen"))
    (perSlotJoins, perSlotWidens).tupled.toSeq.flatMap { (joins, widens) =>
      def method(name: String, slots: Seq[Sourced[SourceExpression]]): FunctionDefinition =
        FunctionDefinition(
          metaTypeName.as(QualifiedName(name, implQualifier)),
          Seq.empty,
          Seq(
            ArgumentDefinition(metaTypeName.as("a"), metaTypeRef),
            ArgumentDefinition(metaTypeName.as("b"), metaTypeRef)
          ),
          metaTypeRef,
          Some(app(metaTypeName, slots)),
          visibility = definition.visibility
        )
      val marker = FunctionDefinition(
        metaTypeName.as(QualifiedName("Meta", implQualifier)),
        Seq.empty,
        Seq(ArgumentDefinition(metaTypeName.as("arg"), metaTypeRef)),
        SourceExpression.trueReference(metaTypeName),
        None,
        visibility = definition.visibility
      )
      Seq(marker, method("join", joins), method("widen", widens)).map(_ -> RoleHint.NoHint)
    }
  }

  /** The per-slot expression `<method>(<slot>(a), <slot>(b))` of the `Meta` method `method` — e.g. `join(range(a),
    * range(b))`, dispatched by the slot's domain type to that domain's `Meta` instance — or `None` when the slot's domain
    * head is not a simple type application (see [[metaJoinInstance]]).
    */
  private def slotJoin(slot: ArgumentDefinition, method: String): Option[Sourced[SourceExpression]] =
    slot.typeExpression.value match {
      case SourceExpression.FunctionApplication(_, domainHead, _, _) =>
        Some(app(domainHead.as(method), Seq(project(slot.name, "a"), project(slot.name, "b"))))
      case _                                                         => None
    }

  /** `<slotName>(<binder>)` — the slot accessor applied to a join parameter (`range(a)`). */
  private def project(slotName: Sourced[String], binder: String): Sourced[SourceExpression] =
    app(slotName, Seq(app(slotName.as(binder))))

  /** A bare-name function application `name(args)` — resolves as the type constructor in a type position and the value
    * constructor / function in a value position, exactly as [[DataDefinitionDesugarer]]'s generated references do.
    */
  private def app(name: Sourced[String], args: Seq[Sourced[SourceExpression]] = Seq.empty): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, None, args))

  /** Appended to a type's name to form its meta structure's name. `$` is not an identifier character, so the result
    * cannot collide with any user-declared type.
    */
  val metaTypeSuffix: String = "$Meta"
}
