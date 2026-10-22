package com.vanillasource.eliot.eliotc.module.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.Visibility
import com.vanillasource.eliot.eliotc.module.fact.QualifiedName
import com.vanillasource.eliot.eliotc.core.fact.CoreAST
import com.vanillasource.eliot.eliotc.module.fact.*
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.defaultSystemModules
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleKeyTypeProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.source.content.Sourced.compilerError

class ModuleValueProcessor(systemModules: Seq[ModuleName] = defaultSystemModules)
    extends SingleKeyTypeProcessor[ModuleValue.Key] {

  private case class ImportResult(
      dictionary: Map[QualifiedName, ValueFQN],
      privateNames: Map[QualifiedName, ValueFQN]
  )

  override protected def generateFact(key: ModuleValue.Key): CompilerIO[Unit] =
    for {
      coreAST        <- getFactOrAbort(CoreAST.Key(key.uri))
      moduleNames    <- getFactOrAbort(ModuleNames.Key(key.uri))
      sourcedImports  = coreAST.ast.as(coreAST.ast.value.importStatements)
      explicitImports =
        sourcedImports.value
          .map(importStatement => importStatement.outline.as(ModuleName.fromImportStatement(importStatement)))
      // The auto-imported prelude is a *weak* tier: a module the file also imports explicitly is dropped here (the
      // explicit import stands alone), and in extractSystemNames an ambient name colliding with a local declaration
      // or an explicitly imported name is silently skipped — locals and explicit imports always win, so the prelude
      // can grow without breaking existing code. Explicit imports keep the strict shadowing errors below.
      ambientModules  = systemModules.filter(m => m =!= key.vfqn.moduleName && !explicitImports.exists(_.value === m))
      importResult   <- extractImportedNames(explicitImports, moduleNames.names.value.keySet, key.platform)
      systemResult   <- extractSystemNames(
                          ambientModules.map(sourcedImports.as(_)),
                          moduleNames.names.value.keySet ++ importResult.dictionary.keySet,
                          key.platform
                        )
      localDictionary = moduleNames.names.value.collect { case (name, _) if importable(name) =>
                          (name, ValueFQN(key.vfqn.moduleName, name))
                        }
      dictionary      = systemResult.dictionary ++ importResult.dictionary ++ localDictionary
      namedValuesMap  = coreAST.ast.value.namedValues.map(nv => nv.qualifiedName.value -> nv).toMap
      _              <- moduleNames.names.value.keys.toSeq
                          .flatMap(name => namedValuesMap.get(name).map(nv => (name, nv)))
                          .map { (name, namedValue) =>
                            registerFactIfClear(
                              ModuleValue(
                                key.uri,
                                ValueFQN(key.vfqn.moduleName, name),
                                dictionary,
                                namedValue,
                                systemResult.privateNames ++ importResult.privateNames,
                                key.platform
                              )
                            )
                          }
                          .sequence_
    } yield ()

  /** The weak prelude tier: collects the auto-imported modules' names, silently skipping any name already taken by a
    * local declaration, an explicitly imported name, or an earlier system module (first wins, in [[ModuleName]] list
    * order). No shadowing errors — ambient names must always be reclaimable by user code. A system module that cannot
    * be found still errors: the prelude is part of the layer contract, and its absence is a build-environment bug to
    * surface loudly, never skip.
    */
  private def extractSystemNames(
      systemModules: Seq[Sourced[ModuleName]],
      takenNames: Set[QualifiedName],
      platform: Platform
  ): CompilerIO[ImportResult] =
    systemModules.foldLeftM(ImportResult(Map.empty, Map.empty)) { (acc, m) =>
      for {
        moduleNames <- getFactOrAbort(UnifiedModuleNames.Key(m.value, platform))
        result      <- if (moduleNames.present) {
                         val free = (name: QualifiedName) =>
                           !takenNames.contains(name) && !acc.dictionary.contains(name) && !acc.privateNames.contains(name)
                         ImportResult(
                           acc.dictionary ++ moduleNames.names.collect {
                             case (name, Visibility.Public) if free(name) && importable(name) =>
                               (name, ValueFQN(moduleNames.moduleName, name))
                           },
                           acc.privateNames ++ moduleNames.names.collect {
                             case (name, Visibility.Private) if free(name) && importable(name) =>
                               (name, ValueFQN(moduleNames.moduleName, name))
                           }
                         ).pure[CompilerIO]
                       } else compilerError(m.as(s"Could not find imported module: `${m.value.show}`")).as(acc)
      } yield result
    }

  /** Whether a dictionary carries `name`: every name a user can write. A name holding a `$` — which is no identifier
    * character — is one the compiler generated (a meta companion, a payload signature, a `data` type's structural meta,
    * `docs/meta.md` §13.2). Generated code names one in its own module unqualified, which the resolver finds there
    * without a dictionary (`ValueResolverScope.getValue`), and one in another module module-qualified, so carrying it
    * would only grow every value's fact, which holds its file's dictionary, by names no file can write.
    */
  private def importable(name: QualifiedName): Boolean = !name.name.contains('$')

  private def extractImportedNames(
      importedModules: Seq[Sourced[ModuleName]],
      localNames: Set[QualifiedName],
      platform: Platform
  ): CompilerIO[ImportResult] =
    importedModules.foldLeftM(ImportResult(Map.empty, Map.empty))((acc, m) =>
      importModuleNames(localNames, acc, m, platform)
    )

  private def importModuleNames(
      localNames: Set[QualifiedName],
      accumulated: ImportResult,
      module: Sourced[ModuleName],
      platform: Platform
  ): CompilerIO[ImportResult] =
    for {
      moduleNames <- getFactOrAbort(UnifiedModuleNames.Key(module.value, platform))
      result      <- if (moduleNames.present) {
                       val publicNames       = moduleNames.names.collect {
                         case (name, Visibility.Public) if importable(name) => name
                       }.toSet
                       val privateNameSet    = moduleNames.names.collect {
                         case (name, Visibility.Private) if importable(name) => name
                       }.toSet
                       val shadowingLocal    = publicNames.intersect(localNames)
                       val shadowingImported = publicNames.intersect(accumulated.dictionary.keySet)

                       if (shadowingLocal.nonEmpty) {
                         compilerError(
                           module.as(
                             s"Imported names shadow local names: ${shadowingLocal.toSeq.map(_.show).mkString(", ")}"
                           )
                         )
                           .as(accumulated)
                       } else if (shadowingImported.nonEmpty) {
                         compilerError(
                           module.as(
                             s"Imported names shadow other imported names: ${shadowingImported.flatMap(accumulated.dictionary.get).mkString(", ")}"
                           )
                         ).as(accumulated)
                       } else {
                         ImportResult(
                           accumulated.dictionary ++ publicNames
                             .map(name => (name, ValueFQN(moduleNames.moduleName, name)))
                             .toMap,
                           accumulated.privateNames ++ privateNameSet
                             .map(name => (name, ValueFQN(moduleNames.moduleName, name)))
                             .toMap
                         ).pure[CompilerIO]
                       }
                     } else
                       compilerError(module.as(s"Could not find imported module: `${module.value.show}`"))
                         .as(accumulated)
    } yield result
}
