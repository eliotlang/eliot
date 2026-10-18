package com.vanillasource.eliot.eliotc.plugin

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ability.fact.{
  AbilityImplementation,
  AbilityImplementationCheck,
  ModuleAbilityOverlapCheck
}
import com.vanillasource.eliot.eliotc.ast.fact.SourceAST
import com.vanillasource.eliot.eliotc.block.fact.BlockDesugaredValue
import com.vanillasource.eliot.eliotc.core.fact.CoreAST
import com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredValue
import com.vanillasource.eliot.eliotc.module.fact.*
import com.vanillasource.eliot.eliotc.monomorphize.channel.{
  MetaTransferAccounting,
  RefinementTable,
  WovenValue
}
import com.vanillasource.eliot.eliotc.monomorphize.fact.*
import com.vanillasource.eliot.eliotc.namedvalues.fact.{NamedValuesIndex, NamedValuesRewrittenValue}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue
import com.vanillasource.eliot.eliotc.processor.CompilerFactKey
import com.vanillasource.eliot.eliotc.progress.{ProgressActivity, ProgressDescriber}
import com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicValue
import com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue
import com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue
import com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue
import com.vanillasource.eliot.eliotc.source.content.SourceContent
import com.vanillasource.eliot.eliotc.source.file.FileContent
import com.vanillasource.eliot.eliotc.source.scan.{PathScan, PoolModules}
import com.vanillasource.eliot.eliotc.source.stat.FileStat
import com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue
import com.vanillasource.eliot.eliotc.token.SourceTokens
import com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicValue
import com.vanillasource.eliot.eliotc.used.UsedNames

import java.net.URI
import java.nio.file.Path

/** How the language front end's facts read to a user (`docs/progress-indication.md` §3.5), as five verbs over the
  * pipeline: `reading` the sources, `parsing` them, `resolving` modules into values up to the effect phase, `checking`
  * both monomorphization tracks and their channel riders, and `lowering` what the program uses for a backend. A file's
  * work is named by the file, a value's by its module. A source file's content is the input a user edits, so its change
  * is reported.
  */
object LangProgressDescriber extends ProgressDescriber {

  override def describe(key: CompilerFactKey[?]): Option[ProgressActivity] =
    Some(key).collect {
      case FileContent.Key(file)                           => reading(file.toPath).asInput()
      case FileStat.Key(file)                              => reading(file.toPath)
      case SourceContent.Key(uri)                          => reading(uri)
      case PathScan.Key(path, _)                           => ProgressActivity("reading", path.toString)
      case _: PoolModules.Key                              => ProgressActivity("reading", "source roots")
      case SourceTokens.Key(uri)                           => parsing(uri)
      case SourceAST.Key(uri)                              => parsing(uri)
      case CoreAST.Key(uri)                                => parsing(uri)
      case ModuleNames.Key(uri)                            => parsing(uri)
      case ModuleValue.Key(_, vfqn, _)                     => resolving(vfqn.moduleName)
      case UnifiedModuleNames.Key(moduleName, _)           => resolving(moduleName)
      case UnifiedModuleValue.Key(vfqn, _)                 => resolving(vfqn.moduleName)
      case ModuleConstructors.Key(moduleName, _)           => resolving(moduleName)
      case ModuleAbilities.Key(moduleName, _)              => resolving(moduleName)
      case ResolvedValue.Key(vfqn, _)                      => resolving(vfqn.moduleName)
      case BlockDesugaredValue.Key(vfqn, _)                => resolving(vfqn.moduleName)
      case MatchDesugaredValue.Key(vfqn, _)                => resolving(vfqn.moduleName)
      case OperatorResolvedValue.Key(vfqn, _)              => resolving(vfqn.moduleName)
      case NamedValuesRewrittenValue.Key(vfqn, _)          => resolving(vfqn.moduleName)
      case NamedValuesIndex.Key(name, _)                   => ProgressActivity("resolving", s"values named $name")
      case RecursionCheckedValue.Key(vfqn, _)              => resolving(vfqn.moduleName)
      case RowElaboratedValue.Key(vfqn, _)                 => resolving(vfqn.moduleName)
      case SaturatedValue.Key(vfqn, _)                     => checking(vfqn.moduleName)
      case BodyValueReferences.Key(vfqn, _)                => checking(vfqn.moduleName)
      case ContributedBinding.Key(vfqn, _)                 => checking(vfqn.moduleName)
      case NativeBinding.Key(vfqn, _)                      => checking(vfqn.moduleName)
      case MonomorphicValue.Key(vfqn, _)                   => checking(vfqn.moduleName)
      case CompilerMonomorphicValue.Key(vfqn, _)           => checking(vfqn.moduleName)
      case AbilityImplementation.Key(ability, _, _)        => checking(ability.moduleName)
      case AbilityImplementationCheck.Key(ability, _, _)   => checking(ability.moduleName)
      case ModuleAbilityOverlapCheck.Key(moduleName, _, _) => checking(moduleName)
      case RefinementTable.Key(vfqn, _)                    => checking(vfqn.moduleName)
      case MetaTransferAccounting.Key(vfqn, _)             => checking(vfqn.moduleName)
      case WovenValue.Key(vfqn, _)                         => checking(vfqn.moduleName)
      case UsedNames.Key(root)                             => lowering(root.moduleName)
      case UncurriedMonomorphicValue.Key(vfqn, _, _)       => lowering(vfqn.moduleName)
      case ReconciledMonomorphicValue.Key(vfqn, _, _)      => lowering(vfqn.moduleName)
    }

  private def reading(path: Path): ProgressActivity = ProgressActivity("reading", ProgressDescriber.fileSubject(path))

  private def reading(uri: URI): ProgressActivity = ProgressActivity("reading", ProgressDescriber.uriSubject(uri))

  private def parsing(uri: URI): ProgressActivity =
    ProgressActivity("parsing", ProgressDescriber.uriSubject(uri))

  private def resolving(moduleName: ModuleName): ProgressActivity = ProgressActivity("resolving", moduleName.show)

  private def checking(moduleName: ModuleName): ProgressActivity = ProgressActivity("checking", moduleName.show)

  private def lowering(moduleName: ModuleName): ProgressActivity = ProgressActivity("lowering", moduleName.show)
}
