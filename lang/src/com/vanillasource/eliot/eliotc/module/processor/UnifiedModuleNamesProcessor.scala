package com.vanillasource.eliot.eliotc.module.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.module.fact.{ModuleNames, UnifiedModuleNames}
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.SingleFactProcessor
import com.vanillasource.eliot.eliotc.source.scan.PathScan

class UnifiedModuleNamesProcessor extends SingleFactProcessor[UnifiedModuleNames.Key] {

  // Total: a module that resolves in no mount of the pool has no `PathScan`, so read it tolerantly and answer with an
  // empty, `present = false` fact rather than declining. The `PathScan` producer still owns the runtime-pool "Could not
  // find path" error (and the compiler-pool silent decline), so tolerating its absence here does not suppress it — it
  // only stops this fact leaving a cache-poison edge. Readers that must distinguish absent from present-but-empty
  // inspect `present`; membership probes read `names` and see the empty map either way.
  override protected def generateSingleFact(key: UnifiedModuleNames.Key): CompilerIO[UnifiedModuleNames] =
    getFactIfProduced(PathScan.Key(key.moduleName.toPath, key.platform)).flatMap {
      case None           =>
        UnifiedModuleNames(key.moduleName, Map.empty, present = false, key.platform).pure[CompilerIO]
      case Some(pathScan) =>
        val keys = pathScan.files.map(ModuleNames.Key(_))
        // Each file's names are its parse: a parallel run parses them all at once.
        (prefetch(keys) >> keys.traverse(getFactOrAbort))
          .map(allNames =>
            UnifiedModuleNames(key.moduleName, allNames.flatMap(_.names.value).toMap, present = true, key.platform)
          )
    }
}
