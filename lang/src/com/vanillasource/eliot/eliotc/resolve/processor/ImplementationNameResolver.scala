package com.vanillasource.eliot.eliotc.resolve.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.ImplementationParameters
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleAbilities,
  ModuleName,
  UnifiedModuleNames,
  ValueFQN
}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.resolve.processor.ValueResolverScope.*
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerAbort

/** Resolves the `h` of a `with h` to the implementation it names — effects v6's resolution step (`docs/effects.md` §9.4
  * step 1). The answer is the implementation's **marker**
  * ([[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding]]'s head), which is the only thing that
  * flows onward: a name is never carried downstream to be searched by, because such a name would ignore import scope,
  * could not be shadowed, and would be decided by hash order (§12).
  *
  * Two keyed steps, no scan: first the implementation's **name marker** in the ordinary dictionary
  * ([[ValueResolverScope.getImplementation]]), which is what makes the lookup honour imports and shadowing exactly as
  * an ability name's does — and which names the module the implementation lives in; then that module's
  * [[ModuleAbilities.markerOfImplementationName]], which is the implementation's real marker.
  *
  * The second step exists because a marker's qualified name also carries the ability name and the pattern key, and
  * `with h` supplies neither. A name that resolves to no implementation is a hard error at the `with` — never a silent
  * fall-back to the default, which would make a mistyped fake silently run the real thing.
  */
object ImplementationNameResolver {

  /** @param moduleName
    *   The `Test::` of a module-qualified `with Test::mock`. Given, it names the module directly and the dictionary
    *   step is skipped — the same bypass an ordinary `module::name` reference makes.
    */
  def resolve(name: Sourced[String], moduleName: Option[Sourced[String]] = None): ScopedIO[ValueFQN] =
    moduleName match {
      case Some(qualifier) => markerIn(ModuleName.parse(qualifier.value), name)
      case None            =>
        getImplementation(name.value).flatMap {
          case None             => compilerAbort[ValueFQN](name.as("Implementation not found.")).liftToScoped
          case Some(nameMarker) => markerIn(nameMarker.moduleName, name)
        }
    }

  /** What the implementation `marker` names takes: nothing, or parameters, read off its parameters declaration
    * ([[ImplementationParameters.declaration]]), which only a named implementation with parameters has.
    */
  def parametersOf(marker: ValueFQN): ScopedIO[Parameters] =
    for {
      platform    <- getPlatform
      declaration  = ImplementationParameters.of(marker)
      names       <- getFactOrAbort(UnifiedModuleNames.Key(marker.moduleName, platform)).liftToScoped
    } yield if (names.names.contains(declaration.name)) Parameters.Present else Parameters.None

  /** What an implementation takes, as a `with` must supply it. */
  enum Parameters {
    case None, Present
  }

  /** The implementation's real marker in `moduleName`, or the error at `name`. */
  private def markerIn(moduleName: ModuleName, name: Sourced[String]): ScopedIO[ValueFQN] =
    for {
      platform  <- getPlatform
      abilities <- getFactOrError(ModuleAbilities.Key(moduleName, platform))(
                     Sourced.compilerError(name.as("Implementation not found."))
                   ).liftToScoped
      marker    <- abilities.markerOfImplementationName(name.value) match {
                     case Some(marker) => marker.pure[ScopedIO]
                     case None         => compilerAbort[ValueFQN](name.as("Implementation not found.")).liftToScoped
                   }
    } yield marker
}
