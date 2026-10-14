package com.vanillasource.eliot.eliotc.lsp.mainroot

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}
import com.vanillasource.eliot.eliotc.pos.PositionRange
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.{SourceContent, Sourced}
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.row.EntryPoint

import java.net.URI
import java.nio.file.Path

/** Serves the sources of the LSP's synthesized per-module monomorphization roots (the `lspmain.*` wrapper modules
  * mounted by [[LspMainRootMount]]).
  *
  * The idiomatic user `main` declares a clause (`def main uses Console: Unit`), which lowers to a phantom binder per
  * entry — so it cannot be a monomorphization root by itself: nothing has written those bindings. The type-hint driver
  * roots at a wrapper that is exactly the jvm target's synthesized entry point (`jvm`'s `SyntheticMainSourceProcessor`):
  * `def main: Unit = M::main with eliot.effect.Console::standardConsole`, one `with` per entry naming the standard
  * implementation the base declares for it ([[com.vanillasource.eliot.eliotc.row.EntryPoint]], `docs/effects.md` D26).
  * A workspace whose path carries no body for one simply fails to monomorphize the wrapper, and hints degrade exactly as
  * for any failing `main`. Contents are a function of the wrapped module's name and its `main`'s declaration.
  */
class LspMainRootSourceProcessor extends SingleKeyTypeProcessor[SourceContent.Key] {
  import LspMainRootSourceProcessor.*

  override protected def generateFact(key: SourceContent.Key): CompilerIO[Unit] =
    if (key.uri.getScheme != scheme) ().pure[CompilerIO]
    else
      targetModuleOf(key.uri) match {
        case Some(target) =>
          EntryPoint
            .source(ValueFQN(target, QualifiedName("main", Qualifier.Default)))
            .flatMap(source => registerFactIfClear(SourceContent(key.uri, Sourced(key.uri, PositionRange.zero, source))))
        case None         => ().pure[CompilerIO]
      }
}

object LspMainRootSourceProcessor {

  /** The URI scheme namespace this processor owns. */
  val scheme: String = "lsp-main"

  /** The reserved module package the wrapper modules live under: module `M` is wrapped by module `lspmain.M`. A user
    * source tree that also declares a module under `lspmain` collides in the ordinary layer merge, loudly.
    */
  val reservedPackage: String = "lspmain"

  /** The wrapper module wrapping `target`. */
  def wrapperModule(target: ModuleName): ModuleName = ModuleName(reservedPackage +: target.packages, target.name)

  /** The `main` of the wrapper module wrapping `target` — the value the type-hint driver roots monomorphization at. */
  def wrapperVfqn(target: ModuleName): ValueFQN =
    ValueFQN(wrapperModule(target), QualifiedName("main", Qualifier.Default))

  /** The module a wrapper `main` wraps — the inverse of [[wrapperVfqn]] — or `None` for any other value. */
  def wrappedModule(vfqn: ValueFQN): Option[ModuleName] =
    Option.when(isWrapperMain(vfqn))(ModuleName(vfqn.moduleName.packages.tail, vfqn.moduleName.name))

  /** Whether a module-relative scan path names a wrapper module (`lspmain/.../M.els`). */
  def isWrapperPath(path: Path): Boolean =
    path.getNameCount >= 2 && path.getName(0).toString == reservedPackage && path.toString.endsWith(".els")

  /** The URI a wrapper scan path resolves to (separator-normalized, so the mapping is OS-independent). */
  def uriFor(path: Path): URI =
    URI.create(s"$scheme:${(0 until path.getNameCount).map(path.getName(_).toString).mkString("/")}")

  /** The module a wrapper URI wraps, when the URI is well-formed (`lsp-main:lspmain/.../M.els`). */
  def targetModuleOf(uri: URI): Option[ModuleName] = {
    val wrapper = ModuleName.fromPath(Path.of(uri.getSchemeSpecificPart))
    Option.when(wrapper.packages.headOption.contains(reservedPackage))(
      ModuleName(wrapper.packages.tail, wrapper.name)
    )
  }

  /** Whether `vfqn` is a wrapper's **own** `main`, the one per wrapped module. */
  def isWrapperMain(vfqn: ValueFQN): Boolean =
    vfqn.name.name == "main" && vfqn.moduleName.packages.headOption.contains(reservedPackage)
}
