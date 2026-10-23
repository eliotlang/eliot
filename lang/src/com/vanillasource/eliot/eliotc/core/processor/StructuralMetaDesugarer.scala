package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*

import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
  DataDefinition,
  FunctionDefinition,
  GenericParameter,
  UnresolvedAbilityConstraint,
  Expression as SourceExpression
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars a `data` type's **structural meta** (`docs/meta.md` §13): the meta of a value of the type is, per
  * constructor, whether that constructor can have built it and, if so, the metas of the fields it was built with.
  *
  * {{{
  *   data Option[A] = None | Some(value: A)
  *     ⟶  data Option$Meta$None
  *         data Option$Meta$Some[M$value](meta$Option$Some$value: M$value)
  *         data Option$Meta[M$Some$value](reach$Option$None: Reach[Option$Meta$None],
  *                                        reach$Option$Some: Reach[Option$Meta$Some[M$Some$value]])
  *         implement Meta[Option$Meta$None] { … }                          -- field-wise, through `joined`/`widening`
  *         implement[M$value ~ Meta] Meta[Option$Meta$Some[M$value]] { … }
  *         implement[M$Some$value ~ Meta] Meta[Option$Meta[M$Some$value]] { … }
  *         Option$Fields[A]: Type = Function[A, Unit]                     -- every constructor's field types, in order
  * }}}
  *
  * plus what the data's own desugar states with them ([[constructorStatement]], [[fieldRead]]): each constructor's
  * call is the value it built, with the meta its slot reaches, and each `match` handler is handed its field read out of
  * the slot, or nothing when the slot is unreached.
  *
  * The structures are named after the type, which no companion may do for a *meta type* (§4.1), but these are
  * generated beside the type and read by nothing else: the channel finds the meta type of `D[T…]` by evaluating
  * `D$Fields[T…]` and mapping the meta types of the field types onto `D$Meta`'s binders, one per field
  * (`MetaInterpreter.metaTypeOf`). A field-less constructor has a record too, so that every slot's type names its
  * constructor: the overlay's and the platform's copies of a `data` must then agree on constructor names, order and
  * field count (decision H), or the two `D$Meta` constructors' signatures differ and their merge is the ordinary "Has
  * multiple different definitions" error.
  *
  * **A recursive field gets no slot** (§13.3): a field typed by the data itself, at its own type parameters, holds a
  * meta of the same type, which the constructor joins into its own, so the summary folds every node's field metas.
  * Recursion through another type (`Option[Tree]`) or across a group of types is not folded; the channel finds no
  * finite meta type for it and leaves its meta not computed.
  *
  * **A `data` type may declare slots of its own** (§17.2, O4b), `data Temperature {sign: Sign}(degrees: Int)
  * {signOf(range(degrees))}`: they are the summary's first fields, before the constructors' slots, each read by an
  * accessor in [[Qualifier.Slot]] (`sign(t)`), joined and widened through the slot type's own instance, and stated by
  * every constructor ([[constructorStatement]]). They sit beside the structural meta and replace nothing: what a slot
  * needs of a field, the constructor's statement says, and no code about metas reads a field's meta (decision R).
  * [[DataSlotChecker]] refuses the shapes this does not cover.
  *
  * The structures themselves, and a slotted type's meta structure, are desugared without one (a regress otherwise).
  */
object StructuralMetaDesugarer {

  /** Appended to a type's name to name its field-types signature, in the meta namespace of the type namespace. */
  val fieldsSuffix: String = "$Fields"

  /** The name of the field-types signature of the `data` named `typeName`. The single source of truth the channel
    * reads it through.
    */
  def fieldsName(typeName: String): QualifiedName = QualifiedName(typeName + fieldsSuffix, Qualifier.Meta(Qualifier.Type))

  /** The structures, their `Meta` instances and the field-types signature of `definition`, or empty for a type with no
    * constructors.
    */
  def desugar(definition: DataDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.constructors.filter(_.nonEmpty).toSeq.flatMap { ctors =>
      val records = ctors.map(record(definition, _))
      val summary = summaryData(definition, ctors)
      (records.flatMap(DataDefinitionDesugarer.desugar(_, structural = false)) ++
        DataDefinitionDesugarer.desugar(summary, structural = false).map(slotsInSlotNamespace(definition))) ++
        (records :+ summary).flatMap(metaInstance).map(_ -> RoleHint.NoHint) :+
        (fieldTypes(definition, ctors) -> RoleHint.NoHint)
    }

  /** What a constructor's call states (`docs/meta.md` §13.4): the value it built, whose meta reaches its own slot with
    * the record of its fields' metas, and no other, joined with the summaries its recursive fields hold. A type's
    * declared slots come first, each what the constructor's own statement says of it (§17.2, O4b).
    *
    * {{{
    *   Some(value)              {built(constructor$(Unreached, Reached(Option$Meta$Some(value))))}
    *   Node(left, value, right) {built(joined(left, joined(right, constructor$(Unreached, Reached(Tree$Meta$Node(value))))))}
    *   data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}
    *     ⟶  Temperature(degrees) {built(constructor$(signOf(range(degrees)), Reached(Temperature$Meta$Temperature(degrees))))}
    * }}}
    */
  def constructorStatement(definition: DataDefinition, ctor: DataConstructor): Sourced[SourceExpression] = {
    val at     = ctor.name
    val slots  = definition.constructors.getOrElse(Seq.empty).map { other =>
      if (other.name.value == ctor.name.value)
        qualified(
          at,
          WellKnownTypes.reachFQN("Reached"),
          Seq(reference(at, recordName(definition, ctor), slotFields(definition, ctor).map(f => reference(f.name))))
        )
      else qualified(at, WellKnownTypes.reachFQN("Unreached"), Seq.empty)
    }
    val built  = reference(at, MetaCompanionLayout.constructorParameterName, ctor.statement ++ slots)
    val joined = ctor.fields.filter(isRecursive(definition, _)).foldRight(built) { (field, rest) =>
      qualified(at, WellKnownTypes.metaJoinedFQN, Seq(reference(field.name), rest))
    }
    qualified(at, WellKnownTypes.metaPrimitiveFQN("built"), Seq(joined))
  }

  /** What a `match` hands argument `argument` of the handler for `ctor`, read out of the eliminator's scrutinee meta
    * `value` (`docs/meta.md` §13.4): the field's meta out of the constructor's slot, the whole summary for a recursive
    * field, and a field-less constructor's unit — each only when the slot is reached, and nothing otherwise.
    */
  def fieldRead(
      definition: DataDefinition,
      ctor: DataConstructor,
      argument: Int,
      value: Sourced[String]
  ): Sourced[SourceExpression] = {
    val at   = ctor.name
    val slot = reference(at, slotName(definition, ctor), Seq(reference(value)))
    ctor.fields.lift(argument) match {
      case None                                         =>
        qualified(at, WellKnownTypes.reachFQN("reachedThen"), Seq(slot, qualified(at, WellKnownTypes.unitValueFQN, Seq.empty)))
      case Some(field) if isRecursive(definition, field) =>
        qualified(at, WellKnownTypes.reachFQN("reachedThen"), Seq(slot, reference(value)))
      case Some(field)                                  =>
        qualified(
          at,
          WellKnownTypes.reachFQN("reached"),
          Seq(slot, reference(at, fieldName(definition, ctor, field), Seq.empty))
        )
    }
  }

  /** `data D$Meta$C[M$f…](meta$D$C$f: M$f, …)`: the metas of a constructor's fields, a recursive field's excepted. */
  private def record(definition: DataDefinition, ctor: DataConstructor): DataDefinition = {
    val fields = slotFields(definition, ctor)
    val name   = ctor.name.as(recordName(definition, ctor))
    DataDefinition(
      name,
      fields.map(field => typeBinder(field.name.as(s"M$$${field.name.value}"))),
      Some(
        Seq(
          DataConstructor(
            name,
            fields.map(field =>
              ArgumentDefinition(field.name.as(fieldName(definition, ctor, field)), typeReference(field.name.as(s"M$$${field.name.value}")))
            )
          )
        )
      ),
      visibility = definition.visibility
    )
  }

  /** `data D$Meta[M$C$f…](<declared slots>, reach$D$C: Reach[D$Meta$C[M$C$f…]], …)`: the type's declared slots, then
    * one slot per constructor, and one binder per field.
    */
  private def summaryData(definition: DataDefinition, ctors: Seq[DataConstructor]): DataDefinition = {
    val name = definition.name.as(metaName(definition))
    DataDefinition(
      name,
      ctors.flatMap(ctor => slotFields(definition, ctor).map(field => typeBinder(summaryBinder(ctor, field)))),
      Some(
        Seq(
          DataConstructor(
            name,
            definition.metaSlots ++ ctors.map { ctor =>
              ArgumentDefinition(
                ctor.name.as(slotName(definition, ctor)),
                qualifiedType(
                  ctor.name,
                  WellKnownTypes.reachFQN("Reach", Qualifier.Type),
                  Seq(
                    typeReference(
                      ctor.name.as(recordName(definition, ctor)),
                      slotFields(definition, ctor).map(field => typeReference(summaryBinder(ctor, field)))
                    )
                  )
                )
              )
            }
          )
        )
      ),
      visibility = definition.visibility
    )
  }

  /** `implement[M… ~ Meta] Meta[S[M…]] { join; widen }` for a generated structure `S`, field-wise through
    * `eliot.compiler.Meta::joined` and `widening`, which dispatch to each field's own instance. A structure with no field
    * has one meta, which its join and widening leave as it is.
    */
  def metaInstance(structure: DataDefinition): Seq[FunctionDefinition] = {
    val s          = structure.name
    val binders    = structure.genericParameters.map(binder =>
      binder.copy(abilityConstraints = Seq(UnresolvedAbilityConstraint(binder.name.as("Meta"), Seq(typeReference(binder.name)))))
    )
    val pattern    = typeReference(s, structure.genericParameters.map(binder => typeReference(binder.name)))
    val qualifier  = Qualifier.AbilityImplementation("Meta", pattern.value.render, None)
    val fields     = structure.constructors.toSeq.flatten.flatMap(_.fields)
    def method(name: String, combine: ValueFQN): FunctionDefinition =
      FunctionDefinition(
        s.as(QualifiedName(name, qualifier)),
        binders,
        Seq(ArgumentDefinition(s.as("a"), pattern), ArgumentDefinition(s.as("b"), pattern)),
        pattern,
        Some(
          if (fields.isEmpty) reference(s.as("a"))
          else
            reference(
              s,
              s.value,
              fields.map(field =>
                qualified(
                  s,
                  combine,
                  Seq(reference(field.name, field.name.value, Seq(reference(s.as("a")))), reference(field.name, field.name.value, Seq(reference(s.as("b")))))
                )
              )
            )
        ),
        visibility = structure.visibility
      )
    val marker = FunctionDefinition(
      s.as(QualifiedName("Meta", qualifier)),
      binders,
      Seq(ArgumentDefinition(s.as("arg0"), pattern)),
      SourceExpression.trueReference(s),
      None,
      visibility = structure.visibility
    )
    Seq(method("join", WellKnownTypes.metaJoinedFQN), method("widen", WellKnownTypes.metaWideningFQN), marker)
  }

  /** `D$Fields[<D's binders>]: Type = Function[F1, Function[…, Unit]]`, the field types of every constructor in order,
    * a recursive field's excepted. It ends in `Unit` rather than in the last field, so a function-typed field is never
    * mistaken for more fields.
    */
  private def fieldTypes(definition: DataDefinition, ctors: Seq[DataConstructor]): FunctionDefinition = {
    val s = definition.name
    FunctionDefinition(
      s.as(fieldsName(s.value)),
      definition.genericParameters,
      Seq.empty,
      typeReference(s.as("Type")),
      Some(
        ctors
          .flatMap(slotFields(definition, _))
          .map(_.typeExpression)
          .foldRight(qualifiedType(s, WellKnownTypes.unitTypeFQN, Seq.empty))((field, rest) =>
            qualifiedType(s, WellKnownTypes.functionDataTypeFQN, Seq(field, rest))
          )
      ),
      visibility = definition.visibility
    )
  }

  /** A declared slot's accessor of the summary in [[Qualifier.Slot]], as a `type`'s slot is
    * ([[MetaConstructorDesugarer]]), so code about metas reads it by the slot's name: `sign(t)`. The constructors'
    * slots, which follow the declared ones, keep their generated names.
    */
  private def slotsInSlotNamespace(definition: DataDefinition)(
      generated: (FunctionDefinition, RoleHint)
  ): (FunctionDefinition, RoleHint) = generated match {
    case (accessor, hint @ RoleHint.FieldAccessor(_, index)) if index < definition.metaSlots.size =>
      (accessor.copy(name = accessor.name.map(_.copy(qualifier = Qualifier.Slot))), hint)
    case other                                                                                  => other
  }

  /** Whether `field` is typed by the data itself at its own type parameters, in order: a recursive field. */
  private def isRecursive(definition: DataDefinition, field: ArgumentDefinition): Boolean =
    field.typeExpression.value match {
      case SourceExpression.FunctionApplication(None, name, arguments, Seq()) if name.value == definition.name.value =>
        arguments.getOrElse(Seq.empty).map(_.value).map {
          case SourceExpression.FunctionApplication(None, parameter, None | Some(Seq()), Seq()) => Some(parameter.value)
          case _                                                                                => None
        } == definition.genericParameters.map(parameter => Some(parameter.name.value))
      case _                                                                                                       => false
    }

  /** The fields of `ctor` its record holds: every one but a recursive field. */
  private def slotFields(definition: DataDefinition, ctor: DataConstructor): Seq[ArgumentDefinition] =
    ctor.fields.filterNot(isRecursive(definition, _))

  private def metaName(definition: DataDefinition): String = definition.name.value + MetaConstructorDesugarer.metaTypeSuffix

  private def recordName(definition: DataDefinition, ctor: DataConstructor): String =
    s"${metaName(definition)}$$${ctor.name.value}"

  private def slotName(definition: DataDefinition, ctor: DataConstructor): String =
    s"reach$$${definition.name.value}$$${ctor.name.value}"

  private def fieldName(definition: DataDefinition, ctor: DataConstructor, field: ArgumentDefinition): String =
    s"meta$$${definition.name.value}$$${ctor.name.value}$$${field.name.value}"

  private def summaryBinder(ctor: DataConstructor, field: ArgumentDefinition): Sourced[String] =
    field.name.as(s"M$$${ctor.name.value}$$${field.name.value}")

  private def typeBinder(name: Sourced[String]): GenericParameter = MetaCompanionLayout.typeBinder(name)

  /** A bare-name type `name[arguments]`. */
  private def typeReference(name: Sourced[String], arguments: Seq[Sourced[SourceExpression]] = Seq.empty): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, Option.when(arguments.nonEmpty)(arguments), Seq.empty))

  /** A bare-name application `name(arguments)`, a reference when there are none. */
  private def reference(at: Sourced[?], name: String, arguments: Seq[Sourced[SourceExpression]]): Sourced[SourceExpression] =
    at.as(SourceExpression.FunctionApplication(None, at.as(name), None, arguments))

  private def reference(name: Sourced[String]): Sourced[SourceExpression] = reference(name, name.value, Seq.empty)

  /** `vfqn(arguments)`, named by its module so the data's own module needs no import of it. */
  private def qualified(at: Sourced[?], vfqn: ValueFQN, arguments: Seq[Sourced[SourceExpression]]): Sourced[SourceExpression] =
    at.as(SourceExpression.FunctionApplication(Some(at.as(vfqn.moduleName.show)), at.as(vfqn.name.name), None, arguments))

  /** The type `vfqn[arguments]`, named by its module. */
  private def qualifiedType(
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
}
