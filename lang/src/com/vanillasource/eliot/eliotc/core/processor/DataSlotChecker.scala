package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*

import com.vanillasource.eliot.eliotc.ast.fact.{DataConstructor, DataDefinition, Expression}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Checks the meta slots a `data` type declares and its constructors' statements of them (`docs/meta.md` §17.2, O4b):
  *
  * {{{
  *   data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}
  * }}}
  *
  * A meta is total, so **every constructor states every slot**, one expression each, and a constructor of a type
  * declaring none states nothing. A slot is not named like a field of its type: code about metas reads the slot by its
  * name (`sign(t)`), where value code reads a field by the same spelling. Two shapes wait for the recursive-data fold
  * (O6) and are refused: a slot on a type whose fields mention the type itself, since the summary a recursive field
  * folds into (§13.3) would join a node's slots with its children's, and a slot naming a type parameter's meta
  * (`MetaOf[A]`), since the type's meta structure has a binder per field, not per type parameter.
  *
  * Each message comes with a help line; an empty result means the declaration is well formed.
  */
object DataSlotChecker {

  def check(definition: DataDefinition): Seq[(Sourced[String], Seq[String])] = {
    val constructors = definition.constructors.getOrElse(Seq.empty)
    if (definition.metaSlots.isEmpty) constructors.flatMap(statesWithoutSlots(definition, _))
    else if (constructors.isEmpty)
      Seq(
        definition.name.as(s"The type '${definition.name.value}' declares slots but has no constructor to state them.") ->
          Seq("Give the type a constructor stating its slots in a brace after its fields.")
      )
    else
      definition.metaSlots.flatMap(slot =>
        namedLikeField(definition, constructors, slot.name) ++ namesMetaOf(slot.name, slot.typeExpression)
      ) ++ recursive(definition, constructors) ++ constructors.flatMap(statement(definition, _))
  }

  private def statesWithoutSlots(definition: DataDefinition, ctor: DataConstructor): Seq[(Sourced[String], Seq[String])] =
    ctor.statement.headOption.toSeq.map(first =>
      first.as(s"The constructor '${ctor.name.value}' states slots its type '${definition.name.value}' does not declare.") ->
        Seq(s"Declare the slots after the type's name, as in 'data ${definition.name.value} {name: Domain}(…)'.")
    )

  private def statement(definition: DataDefinition, ctor: DataConstructor): Seq[(Sourced[String], Seq[String])] = {
    val declared = definition.metaSlots.size
    if (ctor.statement.isEmpty)
      Seq(
        ctor.name.as(s"The constructor '${ctor.name.value}' does not state the slots of its type '${definition.name.value}'.") ->
          Seq(
            s"Every constructor states the type's ${slotCount(declared)} in a brace after its fields: " +
              s"${definition.metaSlots.map(_.name.value).mkString("{", ", ", "}")}, in that order."
          )
      )
    else if (ctor.statement.size != declared)
      Seq(
        ctor.name.as(
          s"The constructor '${ctor.name.value}' states ${slotCount(ctor.statement.size)}, but its type '${definition.name.value}' " +
            s"declares ${slotCount(declared)}."
        ) -> Seq(s"State ${definition.metaSlots.map(_.name.value).mkString(", ")}, in that order.")
      )
    else Seq.empty
  }

  private def namedLikeField(
      definition: DataDefinition,
      constructors: Seq[DataConstructor],
      slot: Sourced[String]
  ): Seq[(Sourced[String], Seq[String])] =
    Option
      .when(constructors.exists(_.fields.exists(_.name.value == slot.value)))(
        slot.as(s"The slot '${slot.value}' is named like a field of '${definition.name.value}'.") ->
          Seq("Code about metas would read the slot where value code reads the field by the same name; rename one.")
      )
      .toSeq

  private def namesMetaOf(slot: Sourced[String], tpe: Sourced[Expression]): Seq[(Sourced[String], Seq[String])] =
    Option
      .when(MetaConstructorDesugarer.namesMetaOf(tpe))(
        tpe.as(s"The slot '${slot.value}' of a 'data' type cannot hold a type parameter's meta yet.") ->
          Seq("A slot of a 'data' type has a type of its own, such as 'Interval[BigInteger]' or a domain the program declares.")
      )
      .toSeq

  private def recursive(definition: DataDefinition, constructors: Seq[DataConstructor]): Seq[(Sourced[String], Seq[String])] =
    constructors
      .flatMap(_.fields)
      .find(field => mentions(field.typeExpression.value, definition.name.value))
      .toSeq
      .map(field =>
        definition.metaSlots.head.name.as(
          s"The type '${definition.name.value}' contains itself (field '${field.name.value}'), so it cannot declare slots yet."
        ) -> Seq("Slots on a recursive type wait for the recursive-data fold.")
      )

  private def mentions(expr: Expression, name: String): Boolean = expr match {
    case Expression.FunctionApplication(None, n, generics, arguments) =>
      n.value == name || (generics.getOrElse(Seq.empty) ++ arguments).exists(a => mentions(a.value, name))
    case Expression.FunctionApplication(_, _, generics, arguments)    =>
      (generics.getOrElse(Seq.empty) ++ arguments).exists(a => mentions(a.value, name))
    case Expression.FlatExpression(parts)                             => parts.exists(p => mentions(p.value, name))
    case _                                                            => false
  }

  private def slotCount(count: Int): String = if (count == 1) "1 slot" else s"$count slots"
}
