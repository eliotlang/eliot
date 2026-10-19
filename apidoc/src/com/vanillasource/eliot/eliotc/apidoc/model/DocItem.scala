package com.vanillasource.eliot.eliotc.apidoc.model

/** One documented top-level entity of a module, already merged across every layer that declares it.
  *
  * The merge is the apidoc-specific view of Eliot's platform layering: an abstract base declaration (e.g. `type IO[A]`
  * or a body-less `def printLine`) and its concrete per-platform implementations (the `jvm` `data IO[A](..)`, the
  * compiler-platform body) collapse into a single item showing the canonical (abstract) signature, the set of layers
  * that declare it, and which layers actually *implement* it. Abilities additionally carry their methods and the known
  * implementations.
  *
  * @param signature
  *   The canonical signature as Eliot source text (highlighted at render time), preferring the abstract declaration.
  * @param doc
  *   The margin-stripped Markdown documentation, preferring the abstract declaration's.
  * @param layers
  *   Every layer that declares this name.
  * @param implementedOn
  *   The layers that provide a concrete definition (a body, a `data`, or an alias) — empty for a purely abstract name.
  */
case class DocItem(
    name: String,
    kind: DocItem.Kind,
    signature: String,
    doc: Option[String],
    layers: Seq[String],
    implementedOn: Seq[String] = Seq.empty,
    members: Seq[DocItem.Member] = Seq.empty,
    implementations: Seq[DocItem.Implementation] = Seq.empty
) {
  def anchor: String = DocItem.anchor(kind, name)
}

object DocItem {

  /** The three top-level shapes a documented name can take, in the order they are presented within a module: abilities
    * lead (they are the most interesting surface for a reader), then types, then plain definitions.
    */
  enum Kind(val label: String) {
    case Ability  extends Kind("ability")
    case TypeLike extends Kind("type")
    case Value    extends Kind("def")
  }

  /** A nested signature shown under an item: an ability method, or a per-layer concrete definition of a type. */
  case class Member(signature: String, doc: Option[String], layer: Option[String] = None)

  /** A known ability implementation, e.g. `implement Show[Hello]`, with the layers that provide it. */
  case class Implementation(signature: String, layers: Seq[String], doc: Option[String])

  /** A stable, HTML-id-safe anchor for an item. Includes a hash of the raw name so operator names (which collapse to
    * the same letters-and-digits skeleton) never collide.
    */
  def anchor(kind: Kind, name: String): String =
    s"${kind.toString.toLowerCase}-${name.filter(_.isLetterOrDigit)}-${Integer.toHexString(name.hashCode)}"
}
