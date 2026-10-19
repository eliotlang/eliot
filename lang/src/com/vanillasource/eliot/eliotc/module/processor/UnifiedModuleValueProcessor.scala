package com.vanillasource.eliot.eliotc.module.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.NamedValue
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{ModuleNames, ModuleValue, UnifiedModuleValue, ValueFQN}
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.{compilerError, compilerAbort}
import com.vanillasource.eliot.eliotc.source.scan.PathScan

import java.net.URI

class UnifiedModuleValueProcessor extends SingleFactProcessor[UnifiedModuleValue.Key] with Logging {

  override protected def generateSingleFact(key: UnifiedModuleValue.Key): CompilerIO[UnifiedModuleValue] =
    for {
      pathScan     <- getFactOrAbort(PathScan.Key(key.vfqn.moduleName.toPath, key.platform))
      allNames     <- pathScan.files.traverse(file => getFactOrAbort(ModuleNames.Key(file)).map(file -> _))
      filesWithName = allNames.collect { case (file, names) if names.names.value.contains(key.vfqn.name) => file }
      allValues    <- filesWithName.traverse(file => getFactOrAbort(ModuleValue.Key(file, key.vfqn, key.platform)))
      _            <- compilerAbort(allNames.head._2.names.as(s"Could not find '${key.vfqn.name.show}'."))
                        .whenA(allValues.isEmpty)
      unifiedValue <- unifyValues(key.vfqn, allValues, pathScan.overrideFiles, key.platform)
    } yield unifiedValue

  /** Reconciles every co-located declaration of a name across the pool's layers into one value, preferring the
    * implementation. Every declaration must first agree on signature (`hasSameSignatures`, else "Has multiple different
    * definitions.").
    *
    * The rules stay order-free with one exception, the compiler-as-platform override: among the
    * *implementations* (`runtime.isDefined`), an implementation that comes from an **override file** (the compiler
    * overlay, per [[PathScan.overrideFiles]]) supersedes the platform's — so the compiler track can redefine a name the
    * platform also implements without it counting as a conflict. Absent any override (always so on the runtime track,
    * where `overrideFiles` is empty), the ordinary rule applies: more than one implementation is a hard error. Every
    * declaration must still agree on signature.
    */
  private def unifyValues(
      vfqn: ValueFQN,
      values: Seq[ModuleValue],
      overrideFiles: Set[URI],
      platform: Platform
  ): CompilerIO[UnifiedModuleValue] =
    if (values.isEmpty) {
      abort
    } else if (!hasSameSignatures(values)) {
      compilerError(values.head.namedValue.qualifiedName.as("Has multiple different definitions.")) *> abort
    } else {
      val implementations = values.filter(_.namedValue.runtime.isDefined)
      val overriding      = implementations.filter(value => overrideFiles.contains(value.uri))
      val effective       = if (overriding.nonEmpty) overriding else implementations

      if (effective.sizeIs > 1) {
        compilerError(values.head.namedValue.qualifiedName.as("Has multiple implementations.")) *> abort
      } else {
        val chosen = effective.headOption.getOrElse(values.head)

        logOverride(vfqn, overriding, implementations) *>
          UnifiedModuleValue(
            chosen.vfqn,
            chosen.dictionary,
            chosen.namedValue,
            chosen.privateNames,
            platform
          ).pure[CompilerIO]
      }
    }

  /** Trace an *actual* override — an overlay implementation superseding at least one platform implementation. */
  private def logOverride(
      vfqn: ValueFQN,
      overriding: Seq[ModuleValue],
      implementations: Seq[ModuleValue]
  ): CompilerIO[Unit] =
    if (overriding.nonEmpty && implementations.sizeIs > overriding.size)
      debug[CompilerIO](
        s"OVERRIDE ${vfqn.show}: overlay ${overriding.map(_.uri).mkString(", ")} supersedes " +
          implementations.filterNot(overriding.contains).map(_.uri).mkString(", ")
      )
    else ().pure[CompilerIO]

  private def hasSameSignatures(values: Seq[ModuleValue]): Boolean = {
    val first = values.head

    values.tail.forall(v => NamedValue.signatureEquality.eqv(first.namedValue, v.namedValue))
  }
}
