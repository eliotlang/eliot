package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Handles
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityFQN, ResolvedValue}
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

/** The source of a platform's synthesized entry point: a `main` that runs the user's `main` with one `with` per effect
  * of its clause, naming the platform's **standard implementation** of it (`docs/effects.md` D26):
  *
  * {{{
  * def main uses Console, Log: Unit = …          -- the user's main, in module `app`
  * def main: Unit = app::main with eliot.effect.Console::standardConsole with eliot.effect.Log::standardLog
  * }}}
  *
  * The names are the base's — `implement standardConsole: Console`, declared body-less beside each effect a program may
  * run and bodied per platform — so this generator names no platform, and a test binds the real implementation portably
  * (`body with standardConsole`). An effect with none — a control effect reaching `main` (`uses Throw[String]`), or one
  * the program declares itself — is the error at `main` that it must be handled there.
  */
object EntryPoint {

  /** The standard implementation of `effect`, declared in the effect's own module: `standardConsole` for `Console`. */
  def standardImplementationOf(effect: AbilityFQN): ValueFQN = {
    val name = s"standard${effect.abilityName}"
    ValueFQN(effect.moduleName, QualifiedName(name, Qualifier.Implementation(name)))
  }

  /** The entry point running `main`, or the errors at `main` for each effect of its clause the platform cannot run. */
  def source(main: ValueFQN): CompilerIO[String] =
    for {
      resolved        <- getFactOrAbort(ResolvedValue.Key(main))
      effects          = resolved.effectRow.returnEffects.map(_.abilityFQN).distinct
      implementations <- effects.traverse(standardImplementation)
      _               <- effects.zip(implementations).traverse_ {
                           case (effect, Standard.Missing)           =>
                             compilerError(
                               resolved.name.as(
                                 s"'main' uses the effect '${effect.abilityName}', which the platform has no standard " +
                                   "implementation of."
                               ),
                               Seq("Handle it inside 'main': discharge it, or bind an implementation with `with`.")
                             )
                           case (effect, Standard.Unbodied(name))    =>
                             compilerError(
                               resolved.name.as(
                                 s"'main' uses the effect '${effect.abilityName}', whose standard implementation " +
                                   s"'$name' has no body here."
                               ),
                               Seq(s"No layer on the path bodies '$name'; a platform layer is what bodies it.")
                             )
                           case (_, Standard.Bodied(_))              => ().pure[CompilerIO]
                         }
      bodied           = implementations.collect { case Standard.Bodied(implementation) => implementation }
      _               <- abort[Unit].whenA(bodied.size != implementations.size)
    } yield s"def main: Unit = ${main.moduleName.show}::${main.name.name}" +
      bodied.map(i => s" with ${i.moduleName.show}::${i.name.name}").mkString

  /** What the effect's module offers as its standard implementation. */
  private enum Standard {
    case Missing
    case Unbodied(name: String)
    case Bodied(implementation: ValueFQN)
  }

  /** The base declares `implement standardConsole: Console` body-less; only a layer bodying it mints its handle `data`
    * (`docs/effects.md` D36 point 2), so a program on a path with no platform layer has nothing to run it with.
    */
  private def standardImplementation(effect: AbilityFQN): CompilerIO[Standard] = {
    val implementation = standardImplementationOf(effect)
    val handle         = QualifiedName(Handles.dataType(implementation.name.name), Qualifier.Default)
    getFactOrAbort(UnifiedModuleNames.Key(effect.moduleName)).map { names =>
      if (!names.names.contains(implementation.name)) Standard.Missing
      else if (!names.names.contains(handle)) Standard.Unbodied(implementation.name.name)
      else Standard.Bodied(implementation)
    }
  }
}
