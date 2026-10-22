package com.vanillasource.eliot.eliotc.resolve.fact

import cats.Show
import cats.kernel.Eq

sealed trait Qualifier

object Qualifier {
  case object Default                                                 extends Qualifier
  case object Type                                                    extends Qualifier

  /** The meta namespace of another namespace — see [[com.vanillasource.eliot.eliotc.module.fact.Qualifier.Meta]], of
    * which this is the resolved twin.
    */
  case class Meta(of: Qualifier)                                      extends Qualifier

  /** The namespace of a slot accessor — see [[com.vanillasource.eliot.eliotc.module.fact.Qualifier.Slot]], of which
    * this is the resolved twin.
    */
  case object Slot                                                    extends Qualifier
  case class Ability(name: String)                                    extends Qualifier

  /** The resolved twin of [[com.vanillasource.eliot.eliotc.module.fact.Qualifier.AbilityImplementation]]: the same
    * `(ability, pattern, implementation name)` identity, with the ability name resolved to its [[AbilityFQN]].
    */
  case class AbilityImplementation(name: AbilityFQN, pattern: String, implementationName: Option[String])
      extends Qualifier

  /** The resolved twin of [[com.vanillasource.eliot.eliotc.module.fact.Qualifier.Implementation]]: the lookup
    * namespace of a named `implement`, whose name needs no resolution.
    */
  case class Implementation(name: String) extends Qualifier

  /** Renders a qualifier for user-facing messages. An [[AbilityImplementation]] shows its ability name and pattern key
    * (e.g. `PatternMatch#Person`) rather than the full [[AbilityFQN]] structure, a named one prefixed by its name.
    */
  given Show[Qualifier] with {
    override def show(qualifier: Qualifier): String = qualifier match {
      case Default                                          => "Default"
      case Type                                             => "Type"
      case Meta(Default)                                    => "Meta"
      case Slot                                             => "Slot"
      case Meta(of)                                         => s"Meta(${show(of)})"
      case Ability(name)                                    => name
      case AbilityImplementation(name, pattern, None)       => s"${name.abilityName}#$pattern"
      case AbilityImplementation(name, pattern, Some(impl)) => s"$impl: ${name.abilityName}#$pattern"
      case Implementation(name)                             => s"$name:"
    }
  }

  given Eq[Qualifier] = Eq.fromUniversalEquals
}
