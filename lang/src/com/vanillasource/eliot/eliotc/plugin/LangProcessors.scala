package com.vanillasource.eliot.eliotc.plugin

import com.vanillasource.eliot.eliotc.ability.processor.{
  AbilityImplementationCheckProcessor,
  AbilityImplementationProcessor,
  ModuleAbilityOverlapCheckProcessor,
  NamedImplementationCheckProcessor
}
import com.vanillasource.eliot.eliotc.ast.processor.ASTParser
import com.vanillasource.eliot.eliotc.block.processor.BlockDesugaringProcessor
import com.vanillasource.eliot.eliotc.core.processor.CoreProcessor
import com.vanillasource.eliot.eliotc.matchdesugar.processor.MatchDesugaringProcessor
import com.vanillasource.eliot.eliotc.module.fact.ModuleName
import com.vanillasource.eliot.eliotc.module.processor.{
  ModuleAbilitiesProcessor,
  ModuleConstructorsProcessor,
  ModuleNamesProcessor,
  ModuleValueProcessor,
  UnifiedModuleNamesProcessor,
  UnifiedModuleValueProcessor
}
import com.vanillasource.eliot.eliotc.monomorphize.channel.{
  MetaTransferAccountingProcessor,
  RefinementChannelProcessor,
  SuppliedRowArgumentsProcessor,
  WovenValueProcessor
}
import com.vanillasource.eliot.eliotc.namedvalues.processor.{NamedValuesIndexProcessor, NamedValuesRewriteProcessor}
import com.vanillasource.eliot.eliotc.monomorphize.fact.ContributedBinding
import com.vanillasource.eliot.eliotc.monomorphize.specialise.SpecialisationProcessor
import com.vanillasource.eliot.eliotc.monomorphize.processor.{
  BindingMergerProcessor,
  BodyValueReferencesProcessor,
  CompilerMonomorphicTypeCheckProcessor,
  CompilerNativesProcessor,
  DataTypeNativesProcessor,
  MatchNativesProcessor,
  MonomorphicTypeCheckProcessor,
  SystemNativesProcessor,
  UserValueNativesProcessor
}
import com.vanillasource.eliot.eliotc.operator.processor.OperatorResolverProcessor
import com.vanillasource.eliot.eliotc.processor.CompilerProcessor
import com.vanillasource.eliot.eliotc.reconcile.processor.ReconcileProcessor
import com.vanillasource.eliot.eliotc.resolve.processor.ValueResolver
import com.vanillasource.eliot.eliotc.row.processor.RowElaborationProcessor
import com.vanillasource.eliot.eliotc.saturate.processor.SaturatedValueProcessor
import com.vanillasource.eliot.eliotc.termination.processor.RecursionCheckProcessor
import com.vanillasource.eliot.eliotc.token.Tokenizer
import com.vanillasource.eliot.eliotc.uncurry.processor.MonomorphicUncurryingProcessor
import com.vanillasource.eliot.eliotc.used.UsedNamesProcessor

/** The canonical ordered language compilation pipeline, from tokenization through uncurrying — the single source of
  * truth for the processor sequence and its order.
  *
  * [[LangPlugin]] prepends the source-reading processors (file/resource readers and the path scanner, which depend on
  * the CLI configuration) and runs this; test harnesses register their source as facts directly and run this list as-is
  * via `ProcessorTest`. Sharing one definition means a new phase is added in exactly one place rather than being
  * re-listed across every harness that compiles past it.
  *
  * Processor order within the list is irrelevant to behaviour — `SequentialCompilerProcessors` dispatches each demanded
  * fact key to the one processor that handles its type, and computation is demand-driven, so a harness that triggers
  * only an early fact never runs the later processors. The order here is kept readable (pipeline order) for the human
  * reader, not because the engine requires it.
  *
  * The two genuinely varying knobs are exposed as parameters:
  *   - `systemModules` — the ambient/auto-imported modules `ModuleValueProcessor` loads (production and most tests use
  *     the default; tests that declare local versions of ambient names pass a narrower set, e.g.
  *     `systemModulesWithoutInt`, or `Seq.empty`);
  *   - `maxNestedRepeats` — the `UsedNamesProcessor` non-convergence backstop bound.
  *   - `extraNativeBindingLabels` — the native-category [[ContributedBinding]] labels contributed by layers *beyond*
  *     this base one (e.g. stdlib's arithmetic natives,
  *     [[com.vanillasource.eliot.eliotc.stdlib.plugin.StdlibNativesProcessor.stdlibLabel]]). `LangPlugin` passes the
  *     roster the other plugins registered through [[ContributedBinding.extraNativeLabelsKey]]; a test that composes an
  *     extra native processor onto this list passes that processor's label here so the [[BindingMergerProcessor]]
  *     consults it. The base labels ([[ContributedBinding.langNativeLabels]]) are always included — the contributors
  *     that own them are always in this list.
  *   - `handles` — the **new effect path** being built beside the old one (`docs/effects.md` D36, order step 2): an
  *     effect desugars to an ability over a handle type, a `uses` entry to a handle parameter, a named implementation to
  *     a `data` and that ability's instance, and the checker fills each handle position with the nearest handle in
  *     lexical scope whose entry answers it. Off unless the internal system property [[LangPlugin.handlesProperty]] is
  *     set — never a CLI word, since the CLI is a compatibility promise — so `master` builds as before, and every
  *     harness built on this list can be run on the new path by setting it.
  */
object LangProcessors {
  def apply(
      systemModules: Seq[ModuleName] = ModuleName.defaultSystemModules,
      maxNestedRepeats: Int = UsedNamesProcessor.DefaultMaxNestedRepeats,
      extraNativeBindingLabels: Seq[String] = Seq.empty,
      handles: Boolean = LangPlugin.handlesRequested
  ): Seq[CompilerProcessor] = Seq(
    Tokenizer(),
    ASTParser(),
    CoreProcessor(handles),
    ModuleNamesProcessor(),
    ModuleValueProcessor(systemModules),
    UnifiedModuleNamesProcessor(),
    UnifiedModuleValueProcessor(),
    ModuleConstructorsProcessor(),
    ModuleAbilitiesProcessor(),
    ValueResolver(handles),
    BlockDesugaringProcessor(),
    MatchDesugaringProcessor(),
    OperatorResolverProcessor(),
    NamedValuesIndexProcessor(),
    NamedValuesRewriteProcessor(),
    RecursionCheckProcessor(),
    RowElaborationProcessor(handles),
    SaturatedValueProcessor(),
    AbilityImplementationProcessor(),
    AbilityImplementationCheckProcessor(),
    NamedImplementationCheckProcessor(),
    ModuleAbilityOverlapCheckProcessor(),
    SystemNativesProcessor(),
    DataTypeNativesProcessor(),
    MatchNativesProcessor(),
    CompilerNativesProcessor(),
    UserValueNativesProcessor(),
    BindingMergerProcessor(
      ContributedBinding.langNativeLabels ++ extraNativeBindingLabels,
      ContributedBinding.userLabels
    ),
    MonomorphicTypeCheckProcessor(handles),
    SpecialisationProcessor(handles),
    CompilerMonomorphicTypeCheckProcessor(handles),
    RefinementChannelProcessor(),
    SuppliedRowArgumentsProcessor(),
    // R2 meta-transfer accounting. Landed but not yet armed: nothing demands its fact in a normal build, so it is
    // dormant until wired as a `WovenValue` precondition (docs/total-meta-transfers.md §P2).
    MetaTransferAccountingProcessor(),
    WovenValueProcessor(handles),
    UsedNamesProcessor(maxNestedRepeats),
    BodyValueReferencesProcessor(),
    MonomorphicUncurryingProcessor(),
    ReconcileProcessor()
  )
}
