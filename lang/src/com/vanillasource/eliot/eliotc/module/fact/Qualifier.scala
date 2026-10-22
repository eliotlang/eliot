package com.vanillasource.eliot.eliotc.module.fact

import cats.Show
import cats.kernel.Eq

/** A qualifier is a sort-of additional namespace names can live in. It is used as technical namespaces as opposed to
  * user defined ones.
  */
sealed trait Qualifier

object Qualifier {

  /** A name in the "default" namespace, like all "normal" user-defined values.
    */
  case object Default extends Qualifier

  /** Denotes a name that points to a Type constructor.
    */
  case object Type extends Qualifier

  /** Denotes a name in the meta-information channel (bounds-as-refinements §4): the `^Meta` transfer companion or
    * `^Where` precondition companion of a value. Erased before codegen (compiler-pool only).
    *
    * It is the meta namespace **of another namespace**, not a flat one: a companion shadows exactly one value, and a
    * value's identity is its name *and* its qualifier, so the companion's is `(name, Meta(qualifier))`. Nesting is what
    * keeps `Numeric[Int]::add`'s transfer apart from a plain `add` declared in the same module — a collision the flat
    * spelling had, since it dropped the `(ability, pattern)` key on the way in (`docs/meta.md` §4.2).
    * The alternative that section first proposed — the companion in the callee's *own* qualifier, distinguished by a
    * name suffix — puts an extra member into an implementation's namespace, which
    * [[com.vanillasource.eliot.eliotc.ability.processor.AbilityImplementationCheckProcessor]] rejects as a method the
    * ability does not declare. Nesting keeps the ability machinery's view of an implementation exactly as it was.
    */
  case class Meta(of: Qualifier) extends Qualifier

  /** The namespace of a **slot accessor**: `range` of `type Int {range: Interval[BigInteger]}`, which reads a slot out
    * of a meta (`docs/meta.md` §17.1, decision K). Its own namespace, so a slot may be named like a function of its
    * type's module (`List`'s `size` slot beside `List::size`). [[Meta]] is not free for it: `Meta(Default)` holds a
    * definition's transfer companion, `length` for `String::length`'s brace. Code about metas (a brace, a `where`, a
    * `Meta` instance) resolves a bare name to a slot accessor before an ordinary value.
    */
  case object Slot extends Qualifier

  /** Functions belonging to a given ability.
    */
  case class Ability(name: String) extends Qualifier

  /** Function belongs to the given ability implementation.
    *
    * `pattern` is a canonical, position-independent key of *what* the implementation implements — the surface string of
    * its type-argument pattern plus its `where` guard, e.g. `Int[L1, H1], Int[L2, H2]` or
    * `Int[Smin, Smax], Int[Tmin, Tmax] where lessThanOrEqual…`.
    * It is the implementation's identity: two `implement` blocks are the same instance iff they share `(ability,
    * pattern)`, independent of source file, position, or order. This is what lets an implementation be split across
    * layers (abstract signatures in the base, bodies in a platform layer) and merged like any other name, and it keeps
    * two genuinely distinct instances of one ability in a module (e.g. `Eq[Type]` vs `Eq[String]`, or a guarded
    * instance whose `where` clause is part of its identity) apart. The pattern is *also* recoverable from the synthetic
    * marker function's signature;
    * this key is that same information, lifted into the name so the merge and dispatch can compare it directly.
    *
    * `implementationName` is the third component of that identity (effects v6, `docs/effects.md` §9.4 step 1): a
    * **named** `implement` (`implement recordingConsole: Console { … }`) is addressable by `with` and is never a
    * default, so it must stay apart from the anonymous default of the same `(ability, pattern)` — under v6 every
    * implementation of a nullary effect shares the empty pattern, and the name is what keeps them distinct. An
    * anonymous `implement` block carries [[None]] and keeps exactly the identity it had.
    */
  case class AbilityImplementation(name: String, pattern: String, implementationName: Option[String]) extends Qualifier

  /** The **lookup namespace of a named `implement`** (effects v6, `docs/effects.md` §9.4 step 1). A named
    * implementation mints, beside its methods and its `AbilityImplementation` marker, one synthetic **name marker**
    * `QualifiedName(implName, Implementation(implName))` — exactly mirroring the ability marker
    * `QualifiedName(abilityName, Ability(abilityName))` that [[com.vanillasource.eliot.eliotc.ast.fact.AbilityBlock]]
    * emits, and for exactly the same reason: it makes `with h` a **keyed** dictionary lookup
    * ([[com.vanillasource.eliot.eliotc.resolve.processor.ValueResolverScope.getImplementation]]) that honours import
    * scope and shadowing, rather than a scan of the dictionary for a matching `AbilityImplementation` qualifier —
    * which would resolve by `Map` iteration order and ignore both (the mistake `getAbility` already corrected).
    *
    * It carries no methods and is never referenced after resolution: the resolver maps it, through its module's
    * [[ModuleAbilities.markerOfImplementationName]], to the implementation's real
    * [[AbilityImplementation]]-qualified marker, and only that marker flows onward as the binding a phantom binder
    * carries. The indirection exists because the real marker's qualified name cannot be built from the surface name
    * alone — it also needs the ability name and the pattern key.
    */
  case class Implementation(name: String) extends Qualifier

  /** Renders a qualifier for user-facing messages. An [[AbilityImplementation]] shows its ability name and pattern key
    * (e.g. `PatternMatch#Person` or `Arithmetic#Int[L1, H1], Int[L2, H2]`), a named one prefixed by its name as the
    * surface writes it (`recordingConsole: Console#`); a [[Meta]] over the plain [[Default]] namespace shows as bare
    * `Meta` (`add^Meta`), and over any other one names the namespace it shadows
    * (`add^Meta(Arithmetic#Int[L1, H1], Int[L2, H2])`).
    */
  given Show[Qualifier] with {
    override def show(qualifier: Qualifier): String = qualifier match {
      case Default                                          => "Default"
      case Type                                             => "Type"
      case Meta(Default)                                    => "Meta"
      case Slot                                             => "Slot"
      case Meta(of)                                         => s"Meta(${show(of)})"
      case Ability(name)                                    => name
      case AbilityImplementation(name, pattern, None)       => s"$name#$pattern"
      case AbilityImplementation(name, pattern, Some(impl)) => s"$impl: $name#$pattern"
      case Implementation(name)                             => s"$name:"
    }
  }

  given Eq[Qualifier] = Eq.fromUniversalEquals
}
