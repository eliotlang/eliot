package com.vanillasource.eliot.eliotc.core.processor

import com.vanillasource.eliot.eliotc.ast.fact.{AbilityMembers, EffectDefinition, FunctionDefinition}

/** Lowers an `effect` declaration (effects v6, `docs/effects.md` §9.3) into ordinary [[FunctionDefinition]]s.
  *
  * {{{
  * effect Console {
  *    def printLine(s: String): Unit
  *    def readLine: Option[String]
  * }
  * }}}
  *
  * An `effect` *is* an ability — same members, same marker, same binding binder — with exactly one thing added:
  * membership says a member performs the effect, so every member is given `{Console}` as its declared row even though
  * the surface does not write it (and writing it is rejected as a second spelling of one fact, §9.3). That row is the
  * only place effect-ness is recorded, and it is what both the scope check and the write read; an `ability`'s members
  * carry none, which is what keeps a constructor class expressible (§3.6). Everything else is
  * [[AbilityMembers]], shared verbatim with `ability`.
  *
  * The lowering runs here rather than in the parser because an `effect` parses into a node of its own — the members
  * are kept as written so this decides their qualifier and marker.
  */
object EffectDefinitionDesugarer {

  def desugar(effect: EffectDefinition): Seq[FunctionDefinition] =
    AbilityMembers.lower(effect.name, effect.genericParameters, effect.functions, performsItself = true)
}
