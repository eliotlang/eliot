package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*

import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
  DataDefinition,
  FunctionDefinition,
  GenericParameter,
  Expression as SourceExpression
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
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
  * is the trivial `Unit` (its one `Meta[Unit]` instance, declared with `Unit`, is the do-nothing join).
  *
  * The `$Meta` suffix keeps the meta type's name distinct from the ordinary `Int^Type` without a new namespace: `$` is
  * not a valid identifier character (the lexer's `isLetter`/`isLetterOrDigit`), so `Int$Meta` can never collide with a
  * user type. Meta structures are compiler-pool-only (the channel evaluates them) — dead in the runtime pool, never
  * code-generated.
  *
  * **A slot may hold a type parameter's meta** (`docs/meta.md` §17.1, decision L), spelled `MetaOf[A]`, legal only in a
  * slot and naming one of the type's own parameters. Each becomes a binder of the meta structure, and the parameters it
  * names are listed, in the type's own order, by a generated **carried-types signature**, as `$Fields` lists a `data`
  * type's field types (`StructuralMetaDesugarer`):
  *
  * {{{
  *   type Box[A] {size: Interval[BigInteger], item: Reach[MetaOf[A]]}
  *     ⟶  data Box$Meta[M$A](size: Interval[BigInteger], item: Reach[M$A])
  *         implement[M$A ~ Meta] Meta[Box$Meta[M$A]] { … }                -- field-wise, through `joined`/`widening`
  *         Box$Carried[A]: Type = Function[A, Unit]
  * }}}
  *
  * The channel finds the meta type of `Box[Int]` by evaluating `Box$Carried[Int]` and mapping the meta types of what it
  * lists onto the binders: `Box$Meta[Int$Meta]`. A `MetaOf` naming anything else is left as written, and the
  * resolver reports it, since no module declares the name.
  *
  * Reuses [[DataDefinitionDesugarer]] verbatim (per "reuse, don't write parallel generators"), so multi-slot types are
  * multi-field structures for free, and [[StructuralMetaDesugarer.metaInstance]] for the instance.
  */
object MetaConstructorDesugarer {

  /** The `data`-style meta structure generated from `definition`'s meta-slot brace **plus** the auto-derived
    * `Meta[<Name>$Meta]` instance and, when a slot holds a type parameter's meta, the carried-types signature; or empty
    * when it declares no slots (every ordinary `def`/alias/slotless type — whose meta is the trivial `Unit`, supplied by
    * the channel, not a synthetic here).
    */
  def desugar(definition: FunctionDefinition): Seq[(FunctionDefinition, RoleHint)] =
    if (definition.metaSlots.isEmpty) Seq.empty
    else {
      val structure = metaStructure(definition)
      DataDefinitionDesugarer.desugar(structure, structural = false).map(inSlotNamespace) ++
        StructuralMetaDesugarer.metaInstance(structure).map(_ -> RoleHint.NoHint) ++
        carriedTypes(definition).map(_ -> RoleHint.NoHint)
    }

  /** Appended to a type's name to name its carried-types signature, in the meta namespace of the type namespace. */
  val carriedSuffix: String = "$Carried"

  /** The name of the carried-types signature of the slotted type named `typeName`. The single source of truth the channel
    * reads it through.
    */
  def carriedName(typeName: String): QualifiedName =
    QualifiedName(typeName + carriedSuffix, Qualifier.Meta(Qualifier.Type))

  /** The marker a slot names a type parameter's meta with: `MetaOf[A]`. Recognised as written, here only. */
  val metaOfName: String = "MetaOf"

  /** A slot's accessor in [[Qualifier.Slot]], its own namespace (`docs/meta.md` §17.1, decision K), so a slot may be
    * named like a function of the type's module: `List`'s `size` slot beside `List::size`. Code about metas finds it
    * there before an ordinary value of the same name, so a brace still writes `size(list)`.
    */
  private def inSlotNamespace(generated: (FunctionDefinition, RoleHint)): (FunctionDefinition, RoleHint) =
    generated match {
      case (accessor, hint: RoleHint.FieldAccessor) =>
        (accessor.copy(name = accessor.name.map(_.copy(qualifier = Qualifier.Slot))), hint)
      case other                                    => other
    }

  /** The synthetic `data <Name>$Meta[M$A…](<slots>)` whose desugaring *is* the meta structure: one binder per carried
    * parameter, each `MetaOf[A]` in a slot replaced by its binder.
    */
  private def metaStructure(definition: FunctionDefinition): DataDefinition = {
    val metaTypeName = definition.name.map(_.name + metaTypeSuffix)
    val carried      = carriedParameters(definition)
    DataDefinition(
      metaTypeName,
      carried.map(parameter => MetaCompanionLayout.typeBinder(binderName(parameter))),
      Some(
        Seq(
          DataConstructor(
            metaTypeName,
            definition.metaSlots.map(slot => slot.copy(typeExpression = withBinders(slot.typeExpression, carried)))
          )
        )
      ),
      visibility = definition.visibility
    )
  }

  /** `<Name>$Carried[<the type's parameters>]: Type = Function[A, Function[…, Unit]]`, the carried parameters in the
    * type's own order, or nothing when no slot holds a type parameter's meta. It ends in `Unit`, as `$Fields` does.
    */
  private def carriedTypes(definition: FunctionDefinition): Option[FunctionDefinition] = {
    val s       = definition.name
    val carried = carriedParameters(definition)
    Option.when(carried.nonEmpty)(
      FunctionDefinition(
        s.map(name => carriedName(name.name)),
        definition.args.map(arg => GenericParameter(arg.name, arg.typeExpression, Seq.empty)),
        Seq.empty,
        typeReference(s.as("Type")),
        Some(
          carried.foldRight(qualified(s, WellKnownTypes.unitTypeFQN, Seq.empty))((parameter, rest) =>
            qualified(s, WellKnownTypes.functionDataTypeFQN, Seq(typeReference(parameter), rest))
          )
        ),
        visibility = definition.visibility
      )
    )
  }

  /** The type's own parameters some slot names with `MetaOf`, in the type's order. */
  private def carriedParameters(definition: FunctionDefinition): Seq[Sourced[String]] = {
    val named = definition.metaSlots.flatMap(slot => metaOfs(slot.typeExpression)).toSet
    definition.args.map(_.name).filter(parameter => named.contains(parameter.value))
  }

  /** Whether `expr` names a type parameter's meta with `MetaOf[X]` anywhere: legal in a `type`'s slot only. */
  def namesMetaOf(expr: Sourced[SourceExpression]): Boolean = metaOfs(expr).nonEmpty

  /** The names every `MetaOf[X]` in `expr` applies to, where `X` is a bare name. */
  private def metaOfs(expr: Sourced[SourceExpression]): Seq[String] = expr.value match {
    case MetaOf(parameter)                                          => Seq(parameter.value)
    case SourceExpression.FlatExpression(parts)                       => parts.flatMap(metaOfs)
    case SourceExpression.FunctionApplication(_, _, arguments, values) =>
      (arguments.getOrElse(Seq.empty) ++ values).flatMap(metaOfs)
    case _                                                          => Seq.empty
  }

  /** `expr` with every `MetaOf[A]` of a carried parameter `A` replaced by its binder `M$A`. */
  private def withBinders(expr: Sourced[SourceExpression], carried: Seq[Sourced[String]]): Sourced[SourceExpression] =
    expr.value match {
      case MetaOf(parameter) if carried.exists(_.value == parameter.value)               =>
        typeReference(binderName(parameter))
      case SourceExpression.FlatExpression(parts)                                =>
        expr.as(SourceExpression.FlatExpression(parts.map(withBinders(_, carried))))
      case SourceExpression.FunctionApplication(module, name, arguments, values) =>
        expr.as(
          SourceExpression.FunctionApplication(
            module,
            name,
            arguments.map(_.map(withBinders(_, carried))),
            values.map(withBinders(_, carried))
          )
        )
      case _                                                                     => expr
    }

  /** `MetaOf[X]` as written, `X` a bare name; either may stand alone in an operator run of one. */
  private object MetaOf {
    def unapply(expr: SourceExpression): Option[Sourced[String]] = alone(expr) match {
      case SourceExpression.FunctionApplication(None, name, Some(Seq(argument)), Seq()) if name.value == metaOfName =>
        alone(argument.value) match {
          case SourceExpression.FunctionApplication(None, parameter, None, Seq()) => Some(parameter)
          case _                                                                   => None
        }
      case _                                                                                                       => None
    }

    private def alone(expr: SourceExpression): SourceExpression = expr match {
      case SourceExpression.FlatExpression(Seq(single)) => alone(single.value)
      case other                                        => other
    }
  }

  /** The meta structure's binder for carried parameter `parameter`: `M$A`. */
  private def binderName(parameter: Sourced[String]): Sourced[String] = parameter.map(name => s"M$$$name")

  /** A bare-name type `name`. */
  private def typeReference(name: Sourced[String]): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, None, Seq.empty))

  /** The type `vfqn[arguments]`, named by its module so the type's own module needs no import of it. */
  private def qualified(
      at: Sourced[?],
      vfqn: ValueFQN,
      arguments: Seq[Sourced[SourceExpression]]
  ): Sourced[SourceExpression] =
    at.as(
      SourceExpression.FunctionApplication(
        Some(at.as(vfqn.moduleName.show)),
        at.as(vfqn.name.name),
        Option.when(arguments.nonEmpty)(arguments),
        Seq.empty
      )
    )

  /** Appended to a type's name to form its meta structure's name. `$` is not an identifier character, so the result
    * cannot collide with any user-declared type.
    */
  val metaTypeSuffix: String = "$Meta"
}
