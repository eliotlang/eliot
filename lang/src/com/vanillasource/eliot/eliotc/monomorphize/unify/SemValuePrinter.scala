package com.vanillasource.eliot.eliotc.monomorphize.unify

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.domain.*
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue.*
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{GroundValue, GroundValueRenderer}

/** Pretty-prints [[SemValue]]s for user-facing error messages. Forces through the given [[FlexStore]] so that solved
  * metas display their solution.
  *
  * Function types (both [[VPi]] at the semantic level and [[GroundValue.Structure]] encoding `Function[A, B]`) are
  * rendered with arrow notation `A -> B`. A constructor application is bracketed by its head's qualifier — `[]` for a
  * type constructor, `()` for a value constructor — so a type reads `Box[String]` and a value reads `Box("a")`,
  * agreeing with [[GroundValueRenderer]], to which ground values delegate. Unsolved metas appear as `?N`. Nothing in a
  * type is compiler machinery, so `Expected:` / `Actual:` show exactly the names the user wrote.
  */
object SemValuePrinter {

  /** Render a semantic value as a readable string, forcing through [[flexStore]]. */
  def show(v: SemValue, flexStore: FlexStore): String = go(v, flexStore, 0, topLevel = true)

  private def go(v: SemValue, flexStore: FlexStore, depth: Int, topLevel: Boolean): String = {
    val forced = Evaluator.force(v, flexStore)
    forced match {
      case VType =>
        "Type"

      case VConst(g) =>
        if (topLevel) GroundValueRenderer.render(g) else GroundValueRenderer.renderOperand(g)

      case VPi(domain, codomain) =>
        val placeholder = VNeutral(NeutralHead.Param(depth, s"$$p$depth"), Spine.SNil)
        val body        = codomain(placeholder)
        val domStr      = go(domain, flexStore, depth, topLevel = false)
        val codStr      = go(body, flexStore, depth + 1, topLevel = true)
        parenIf(!topLevel, s"$domStr -> $codStr")

      case VLam(name, closure) =>
        val placeholder = VNeutral(NeutralHead.Param(depth, name), Spine.SNil)
        val body        = closure(placeholder)
        parenIf(!topLevel, s"$name => ${go(body, flexStore, depth + 1, topLevel = true)}")

      case VFlex(id, spine) =>
        val args = spine.toList.map(go(_, flexStore, depth, topLevel = false))
        if (args.isEmpty) s"?${id.value}"
        else s"?${id.value}(${args.mkString(", ")})"

      case VNeutral(head, spine) =>
        applied(head.name, spine, flexStore, depth)

      case VTopDef(fqn, _, spine, _) =>
        showHeaded(fqn, spine, flexStore, depth)

      case VStuckNative(fqn, spine) =>
        showHeaded(fqn, spine, flexStore, depth)

      case VNative(_, _) =>
        "<native>"
    }
  }

  /** An FQN-headed application: `name[args]` for a **type constructor** ([[Qualifier.Type]]) and `name(args)` for a
    * **value constructor**, matching how the user writes them (`Box[String]` the type, `Box("a")` the value) and
    * agreeing with [[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValueRenderer]] on the ground side.
    */
  private def showHeaded(fqn: ValueFQN, spine: Spine, flexStore: FlexStore, depth: Int): String =
    appliedHeaded(fqn, spine, flexStore, depth)

  private def applied(name: String, spine: Spine, flexStore: FlexStore, depth: Int): String = {
    val args = spine.toList.map(go(_, flexStore, depth, topLevel = false))
    if (args.isEmpty) name else s"$name(${args.mkString(", ")})"
  }

  /** Like [[applied]], but bracketed by the head's qualifier: `[]` for a type constructor, `()` otherwise. */
  private def appliedHeaded(fqn: ValueFQN, spine: Spine, flexStore: FlexStore, depth: Int): String = {
    val name = fqn.name.name
    val args = spine.toList.map(go(_, flexStore, depth, topLevel = false))
    if (args.isEmpty) name
    else if (fqn.name.qualifier === Qualifier.Type) s"$name[${args.mkString(", ")}]"
    else s"$name(${args.mkString(", ")})"
  }

  private def parenIf(cond: Boolean, s: String): String =
    if (cond) s"($s)" else s
}
