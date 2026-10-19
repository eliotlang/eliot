package com.vanillasource.eliot.eliotc.monomorphize.fact

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.Qualifier

/** Renders a [[GroundValue]] — a fully evaluated, concrete monomorphic type — as a compact, human-readable type string
  * for anything a user reads: LSP hover / type hints, and the type arguments of an ability-demand diagnostic.
  *
  * The compiler's own `GroundValue.debugString` is deliberately terse (it collapses every non-`Type` structure to
  * `"Structure(...)"` and drops type arguments), which is fine for debug logging but useless in front of a user. This
  * renderer instead shows the structure a reader expects:
  *
  *   - a data/type-constructor application as `Name[arg, …]` (`List[String]`, `Either[String, Int]`), or just `Name`
  *     with no args;
  *   - a function type as a right-associative arrow (`String -> Option[Int]`), parenthesising a function on the left of
  *     an arrow so `(A -> B) -> C` stays unambiguous;
  *   - a direct (literal) value by its underlying `toString` (e.g. the `"hello"` of `Tag["hello"]`).
  *
  * A type contains no compiler machinery to hide — a handle's type is an ordinary type parameter, and LSP hover leaves
  * handles out itself — so every type prints as what the user wrote.
  */
object GroundValueRenderer {

  /** Render a ground type as a one-line string. */
  def render(value: GroundValue): String = value match {
    case GroundValue.Type                 => "Type"
    case GroundValue.Direct(direct, _)    => direct.toString
    case structure: GroundValue.Structure =>
      structure.asFunctionType match {
        case Some((from, to)) => s"${renderOperand(from)} -> ${render(to)}"
        case None             => application(structure)
      }
    case GroundValue.Param(index, Nil, _)  => s"?p$index"
    case GroundValue.Param(index, args, _) => s"?p$index[${args.map(render).mkString(", ")}]"
  }

  /** A function type used as the left operand of an arrow is parenthesised so the arrow nesting reads unambiguously. */
  def renderOperand(value: GroundValue): String =
    value.asFunctionType match {
      case Some(_) => s"(${render(value)})"
      case None    => render(value)
    }

  /** A plain application, bracketed by the head's qualifier so it reads the way the user writes it: `[]` for a type
    * constructor ([[Qualifier.Type]], e.g. `Box[String]` the type), `()` for a value constructor (e.g. `Box("a")` the
    * value). [[SemValuePrinter]] applies the same rule on the semantic side, so the two printers agree.
    */
  private def application(structure: GroundValue.Structure): String =
    if (structure.args.isEmpty) structure.typeName.name.name
    else if (structure.typeName.name.qualifier === Qualifier.Type)
      s"${structure.typeName.name.name}[${structure.args.map(render).mkString(", ")}]"
    else s"${structure.typeName.name.name}(${structure.args.map(render).mkString(", ")})"

}
