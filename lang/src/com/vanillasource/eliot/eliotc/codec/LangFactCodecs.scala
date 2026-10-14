package com.vanillasource.eliot.eliotc.codec

import com.vanillasource.eliot.eliotc.compiler.cache.codec.{FactCodec, FactKeyCodecs}
import com.vanillasource.eliot.eliotc.compiler.cache.codec.CoreFactCodecs.given

/** [[FactCodec]] instances for every type reachable from a `lang` compiler fact — the bulk of the fact model.
  *
  * One line per type, and nothing states a shape: `FactCodec.derived` reads each from the compiler's own `Mirror`, and
  * a sum's cases are built inside its derivation rather than listed. A field whose type has no instance is a **compile
  * error**, so this file is a proof of coverage rather than a claim of it — which is the property the cache needs
  * (`docs/incremental-compilation.md` §13): a value that cannot be equality-stable must not encode by accident.
  *
  * The instances are grouped here rather than written on each declaration because these are *structural* types, not
  * facts: they have no persistence decision to make. The decision lives where it belongs, on
  * `CompilerFactKey.valueCodec`, which every fact states once with no default.
  */
object LangFactCodecs {
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_MonomorphicParameterDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.MonomorphicParameterDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.Expression] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.FunctionApplication] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.FunctionLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_IntegerLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.IntegerLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_StringLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.StringLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_ParameterReference
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.ParameterReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicExpression_MonomorphicValueReference
      : FactCodec[
        com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicExpression.MonomorphicValueReference
      ] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicValue
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_processor_ValueResolverScope
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.processor.ValueResolverScope] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_AbilityFQN
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.AbilityFQN] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicEffects_Entry
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Entry] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicEffects_Received
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Received] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicEffects_Slot
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Slot] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicEffects_Dispatch
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects.Dispatch] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicEffects
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicEffects] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.FunctionApplication] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_ValueReference
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.ValueReference] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.FunctionLiteral] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_MatchExpression
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.MatchExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_MatchCase
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.MatchCase] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_WithBinding
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.WithBinding] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Expression_BlockLine
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Expression.BlockLine] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Pattern
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Pattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Pattern_ConstructorPattern
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Pattern.ConstructorPattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_PrecedenceDeclaration
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.PrecedenceDeclaration] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_QualifiedName
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.QualifiedName] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_Qualifier
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.Qualifier] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_ResolvedValue
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_ResolvedValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_used_UsedNames: FactCodec[com.vanillasource.eliot.eliotc.used.UsedNames] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_used_UsedNames_Key
      : FactCodec[com.vanillasource.eliot.eliotc.used.UsedNames.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_used_UsedNames_UsageStats
      : FactCodec[com.vanillasource.eliot.eliotc.used.UsedNames.UsageStats] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_termination_fact_RecursionCheckedValue
      : FactCodec[com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_termination_fact_RecursionCheckedValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredExpression
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredExpression_WithBinding
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression.WithBinding] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredExpression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression.FunctionApplication] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredExpression_ValueReference
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression.ValueReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredExpression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredExpression.FunctionLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredValue
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_AST: FactCodec[com.vanillasource.eliot.eliotc.core.fact.AST]   =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_CoreAST
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.CoreAST] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_CoreAST_Key
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.CoreAST.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_NamedValueReference
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.NamedValueReference] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.FunctionApplication] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.FunctionLiteral] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_MatchExpression
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.MatchExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_MatchCase
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.MatchCase] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_WithBinding
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.WithBinding] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Expression_BlockLine
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Expression.BlockLine] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_NamedValue
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.NamedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Pattern
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Pattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_Pattern_ConstructorPattern
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.Pattern.ConstructorPattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_PrecedenceDeclaration
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.PrecedenceDeclaration] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_core_fact_RoleHint
      : FactCodec[com.vanillasource.eliot.eliotc.core.fact.RoleHint] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementation
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementation_Resolution
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation.Resolution] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementation_Key
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementationCheck
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementationCheck] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementationCheck_Key
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementationCheck.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_ModuleAbilityOverlapCheck
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.ModuleAbilityOverlapCheck] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ability_fact_ModuleAbilityOverlapCheck_Key
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.ModuleAbilityOverlapCheck.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities_DeclaredMethod
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities.DeclaredMethod] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities_Impl
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities.Impl] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleConstructors
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleConstructors] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleConstructors_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleConstructors.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleName
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleName] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleNames
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleNames] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleNames_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleNames.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleValue
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ModuleValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_QualifiedName
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.QualifiedName] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_Qualifier
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.Qualifier] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_Role
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.Role] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleNames
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleNames] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleNames_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleNames.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleValue
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_module_fact_ValueFQN
      : FactCodec[com.vanillasource.eliot.eliotc.module.fact.ValueFQN] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.Expression] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.FunctionApplication] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.FunctionLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_IntegerLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.IntegerLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_StringLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.StringLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_ParameterReference
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.ParameterReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicExpression_MonomorphicValueReference
      : FactCodec[
        com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicExpression.MonomorphicValueReference
      ] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicValue
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_platform_Platform
      : FactCodec[com.vanillasource.eliot.eliotc.platform.Platform] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_ContributedBinding_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.ContributedBinding.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_NativeBinding_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.NativeBinding.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_token_SourceTokens
      : FactCodec[com.vanillasource.eliot.eliotc.token.SourceTokens] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_token_SourceTokens_Key
      : FactCodec[com.vanillasource.eliot.eliotc.token.SourceTokens.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_token_Token: FactCodec[com.vanillasource.eliot.eliotc.token.Token]       =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_file_FileContent
      : FactCodec[com.vanillasource.eliot.eliotc.source.file.FileContent] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_file_FileContent_Key
      : FactCodec[com.vanillasource.eliot.eliotc.source.file.FileContent.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_content_SourceContent
      : FactCodec[com.vanillasource.eliot.eliotc.source.content.SourceContent] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_content_SourceContent_Key
      : FactCodec[com.vanillasource.eliot.eliotc.source.content.SourceContent.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_content_Sourced[T: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.source.content.Sourced[T]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_scan_FilesystemMount
      : FactCodec[com.vanillasource.eliot.eliotc.source.scan.FilesystemMount] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_scan_PathScan
      : FactCodec[com.vanillasource.eliot.eliotc.source.scan.PathScan] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_scan_PathScan_Key
      : FactCodec[com.vanillasource.eliot.eliotc.source.scan.PathScan.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_scan_PoolModules
      : FactCodec[com.vanillasource.eliot.eliotc.source.scan.PoolModules] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_scan_PoolModules_Key
      : FactCodec[com.vanillasource.eliot.eliotc.source.scan.PoolModules.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_stat_FileStat
      : FactCodec[com.vanillasource.eliot.eliotc.source.stat.FileStat] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_source_stat_FileStat_Key
      : FactCodec[com.vanillasource.eliot.eliotc.source.stat.FileStat.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_block_fact_BlockDesugaredValue
      : FactCodec[com.vanillasource.eliot.eliotc.block.fact.BlockDesugaredValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_block_fact_BlockDesugaredValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.block.fact.BlockDesugaredValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_AnnotatedPart
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.AnnotatedPart] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_Token
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.Token] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_PrefixOp
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.PrefixOp] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_PostfixOp
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.PostfixOp] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_InfixOp
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.InfixOp] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_processor_TokenClassifier_Operand
      : FactCodec[com.vanillasource.eliot.eliotc.operator.processor.TokenClassifier.Operand] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_WithBinding
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.WithBinding] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.FunctionApplication] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_ValueReference
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.ValueReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.FunctionLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_SignatureView
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedExpression_SignatureView_Binder
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.SignatureView.Binder] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedValue
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_saturate_fact_BinderRoles
      : FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.BinderRoles] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_saturate_fact_BinderRoles_Disposition
      : FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.BinderRoles.Disposition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_saturate_fact_BinderRoles_Role
      : FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.BinderRoles.Role] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_saturate_fact_SaturatedValue
      : FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_saturate_fact_SaturatedValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_AST: FactCodec[com.vanillasource.eliot.eliotc.ast.fact.AST]     =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_ArgumentDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.ArgumentDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_DataConstructor
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.DataConstructor] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_DataDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.DataDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_EffectRow[C: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.EffectRow[C]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_UnresolvedAbilityConstraint[E: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.UnresolvedAbilityConstraint[E]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_resolve_fact_AbilityConstraint[E: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.AbilityConstraint[E]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_EffectRow_ParameterEffects[C: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.EffectRow.ParameterEffects[C]] = FactCodec.derived

  given codec_com_vanillasource_eliot_eliotc_ast_fact_EffectRow_CallbackEffects[C: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.EffectRow.CallbackEffects[C]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression.FunctionApplication] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression_BlockLine
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression.BlockLine] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression_EffectfulType
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression.EffectfulType] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression_WithBinding
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression.WithBinding] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_EffectDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.EffectDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_NamedImplementation
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.NamedImplementation] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Expression_MatchCase
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Expression.MatchCase] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Fixity
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Fixity] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Fixity_Associativity
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Fixity.Associativity] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_FunctionDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.FunctionDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_GenericParameter
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_ImportStatement
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.ImportStatement] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_LambdaParameterDefinition
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.LambdaParameterDefinition] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Pattern
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Pattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Pattern_ConstructorPattern
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Pattern.ConstructorPattern] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_PrecedenceDeclaration
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.PrecedenceDeclaration] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_PrecedenceDeclaration_Relation
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.PrecedenceDeclaration.Relation] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_SourceAST
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.SourceAST] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_SourceAST_Key
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.SourceAST.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_fact_Visibility
      : FactCodec[com.vanillasource.eliot.eliotc.ast.fact.Visibility] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_parser_InputStream[I: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.parser.InputStream[I]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_parser_ParserError
      : FactCodec[com.vanillasource.eliot.eliotc.ast.parser.ParserError] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_parser_ParserResult[A: FactCodec]
      : FactCodec[com.vanillasource.eliot.eliotc.ast.parser.ParserResult[A]] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_ast_parser_ParserResult_Consume
      : FactCodec[com.vanillasource.eliot.eliotc.ast.parser.ParserResult.Consume] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_row_fact_RowElaboratedValue
      : FactCodec[com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_row_fact_RowElaboratedValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_row_fact_SlotBoundValue
      : FactCodec[com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_row_fact_SlotBoundValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesIndex
      : FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesIndex] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesIndex_Key
      : FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesIndex.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesRewrittenValue
      : FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesRewrittenValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesRewrittenValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesRewrittenValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_SuppliedRowArguments
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.SuppliedRowArguments] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_SuppliedRowArguments_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.SuppliedRowArguments.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_MetaTransferAccounting
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.MetaTransferAccounting] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_MetaTransferAccounting_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.MetaTransferAccounting.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_RefinementTable
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_RefinementTable_NodeMeta
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable.NodeMeta] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_RefinementTable_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_WovenValue
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_channel_WovenValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_BodyValueReferences
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.BodyValueReferences] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_BodyValueReferences_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.BodyValueReferences.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_CompilerMonomorphicValue
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_CompilerMonomorphicValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_GroundValue
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_GroundValue_Literal
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_GroundValue_Structure
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Structure] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_Expression
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.Expression] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_FunctionApplication
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.FunctionApplication] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_FunctionLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.FunctionLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_IntegerLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.IntegerLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_StringLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.StringLiteral] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_ParameterReference
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.ParameterReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicExpression_MonomorphicValueReference
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicExpression.MonomorphicValueReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicValue
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicValue_Key
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue.Key] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_check_SemExpression_IntegerLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.check.SemExpression.IntegerLiteral] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_check_SemExpression_StringLiteral
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.check.SemExpression.StringLiteral] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_check_SemExpression_ParameterReference
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.check.SemExpression.ParameterReference] =
    FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_check_Track
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.check.Track] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_check_TypeStackLoop_Result
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.check.TypeStackLoop.Result] = FactCodec.derived
  given codec_com_vanillasource_eliot_eliotc_monomorphize_fact_GroundValue_Type
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Type.type] =
    FactCodec.singleton(com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Type)

  // Aliases the fact keys use to state their own persistence decision.
  val abilityImplementationCodec: FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementation]         =
    codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementation
  val abilityImplementationCheckCodec
      : FactCodec[com.vanillasource.eliot.eliotc.ability.fact.AbilityImplementationCheck] =
    codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementationCheck
  val moduleAbilityOverlapCheckCodec: FactCodec[com.vanillasource.eliot.eliotc.ability.fact.ModuleAbilityOverlapCheck] =
    codec_com_vanillasource_eliot_eliotc_ability_fact_ModuleAbilityOverlapCheck
  val sourceASTCodec: FactCodec[com.vanillasource.eliot.eliotc.ast.fact.SourceAST]                                     =
    codec_com_vanillasource_eliot_eliotc_ast_fact_SourceAST
  val blockDesugaredValueCodec: FactCodec[com.vanillasource.eliot.eliotc.block.fact.BlockDesugaredValue]               =
    codec_com_vanillasource_eliot_eliotc_block_fact_BlockDesugaredValue
  val coreASTCodec: FactCodec[com.vanillasource.eliot.eliotc.core.fact.CoreAST]                                        =
    codec_com_vanillasource_eliot_eliotc_core_fact_CoreAST
  val matchDesugaredValueCodec: FactCodec[com.vanillasource.eliot.eliotc.matchdesugar.fact.MatchDesugaredValue]        =
    codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredValue
  val moduleAbilitiesCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleAbilities]                      =
    codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities
  val moduleConstructorsCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleConstructors]                =
    codec_com_vanillasource_eliot_eliotc_module_fact_ModuleConstructors
  val moduleNamesCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleNames]                              =
    codec_com_vanillasource_eliot_eliotc_module_fact_ModuleNames
  val moduleValueCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.ModuleValue]                              =
    codec_com_vanillasource_eliot_eliotc_module_fact_ModuleValue
  val unifiedModuleNamesCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleNames]                =
    codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleNames
  val unifiedModuleValueCodec: FactCodec[com.vanillasource.eliot.eliotc.module.fact.UnifiedModuleValue]                =
    codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleValue
  val suppliedRowArgumentsCodec: FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.SuppliedRowArguments]   =
    codec_com_vanillasource_eliot_eliotc_monomorphize_channel_SuppliedRowArguments
  val metaTransferAccountingCodec
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.MetaTransferAccounting] =
    codec_com_vanillasource_eliot_eliotc_monomorphize_channel_MetaTransferAccounting
  val refinementTableCodec: FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.RefinementTable]             =
    codec_com_vanillasource_eliot_eliotc_monomorphize_channel_RefinementTable
  val wovenValueCodec: FactCodec[com.vanillasource.eliot.eliotc.monomorphize.channel.WovenValue]                       =
    codec_com_vanillasource_eliot_eliotc_monomorphize_channel_WovenValue
  val bodyValueReferencesCodec: FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.BodyValueReferences]        =
    codec_com_vanillasource_eliot_eliotc_monomorphize_fact_BodyValueReferences
  val compilerMonomorphicValueCodec
      : FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.CompilerMonomorphicValue] =
    codec_com_vanillasource_eliot_eliotc_monomorphize_fact_CompilerMonomorphicValue
  val monomorphicValueCodec: FactCodec[com.vanillasource.eliot.eliotc.monomorphize.fact.MonomorphicValue]              =
    codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicValue
  val namedValuesIndexCodec: FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesIndex]               =
    codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesIndex
  val namedValuesRewrittenValueCodec
      : FactCodec[com.vanillasource.eliot.eliotc.namedvalues.fact.NamedValuesRewrittenValue] =
    codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesRewrittenValue
  val operatorResolvedValueCodec: FactCodec[com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedValue]        =
    codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedValue
  val reconciledMonomorphicValueCodec
      : FactCodec[com.vanillasource.eliot.eliotc.reconcile.fact.ReconciledMonomorphicValue] =
    codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicValue
  val resolvedValueCodec: FactCodec[com.vanillasource.eliot.eliotc.resolve.fact.ResolvedValue]                         =
    codec_com_vanillasource_eliot_eliotc_resolve_fact_ResolvedValue
  val rowElaboratedValueCodec: FactCodec[com.vanillasource.eliot.eliotc.row.fact.RowElaboratedValue]                   =
    codec_com_vanillasource_eliot_eliotc_row_fact_RowElaboratedValue
  val slotBoundValueCodec: FactCodec[com.vanillasource.eliot.eliotc.row.fact.SlotBoundValue]                           =
    codec_com_vanillasource_eliot_eliotc_row_fact_SlotBoundValue
  val saturatedValueCodec: FactCodec[com.vanillasource.eliot.eliotc.saturate.fact.SaturatedValue]                      =
    codec_com_vanillasource_eliot_eliotc_saturate_fact_SaturatedValue
  val sourceContentCodec: FactCodec[com.vanillasource.eliot.eliotc.source.content.SourceContent]                       =
    codec_com_vanillasource_eliot_eliotc_source_content_SourceContent
  val fileContentCodec: FactCodec[com.vanillasource.eliot.eliotc.source.file.FileContent]                              =
    codec_com_vanillasource_eliot_eliotc_source_file_FileContent
  val pathScanCodec: FactCodec[com.vanillasource.eliot.eliotc.source.scan.PathScan]                                    =
    codec_com_vanillasource_eliot_eliotc_source_scan_PathScan
  val poolModulesCodec: FactCodec[com.vanillasource.eliot.eliotc.source.scan.PoolModules]                              =
    codec_com_vanillasource_eliot_eliotc_source_scan_PoolModules
  val fileStatCodec: FactCodec[com.vanillasource.eliot.eliotc.source.stat.FileStat]                                    =
    codec_com_vanillasource_eliot_eliotc_source_stat_FileStat
  val recursionCheckedValueCodec: FactCodec[com.vanillasource.eliot.eliotc.termination.fact.RecursionCheckedValue]     =
    codec_com_vanillasource_eliot_eliotc_termination_fact_RecursionCheckedValue
  val sourceTokensCodec: FactCodec[com.vanillasource.eliot.eliotc.token.SourceTokens]                                  =
    codec_com_vanillasource_eliot_eliotc_token_SourceTokens
  val uncurriedMonomorphicValueCodec: FactCodec[com.vanillasource.eliot.eliotc.uncurry.fact.UncurriedMonomorphicValue] =
    codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicValue
  val usedNamesCodec: FactCodec[com.vanillasource.eliot.eliotc.used.UsedNames]                                         =
    codec_com_vanillasource_eliot_eliotc_used_UsedNames

  /** This layer's contribution to the key tag table; see [[FactKeyCodecs]]. The two declining facts appear here too: a
    * decline withholds the *value*, never the edges, so its key is still stored and still walked.
    */
  val keyCodecs: FactKeyCodecs.Registry = Map(
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_fact_ContributedBinding_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_fact_NativeBinding_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_uncurry_fact_UncurriedMonomorphicValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_resolve_fact_ResolvedValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_used_UsedNames_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_termination_fact_RecursionCheckedValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_matchdesugar_fact_MatchDesugaredValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_core_fact_CoreAST_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementation_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_ability_fact_AbilityImplementationCheck_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_ability_fact_ModuleAbilityOverlapCheck_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_ModuleAbilities_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_ModuleConstructors_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_ModuleNames_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_ModuleValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleNames_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_module_fact_UnifiedModuleValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_reconcile_fact_ReconciledMonomorphicValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_token_SourceTokens_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_source_file_FileContent_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_source_content_SourceContent_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_source_scan_PathScan_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_source_scan_PoolModules_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_source_stat_FileStat_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_block_fact_BlockDesugaredValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_operator_fact_OperatorResolvedValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_saturate_fact_SaturatedValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_ast_fact_SourceAST_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_row_fact_RowElaboratedValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_row_fact_SlotBoundValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesIndex_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_namedvalues_fact_NamedValuesRewrittenValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_channel_SuppliedRowArguments_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_channel_MetaTransferAccounting_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_channel_RefinementTable_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_channel_WovenValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_fact_BodyValueReferences_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_fact_CompilerMonomorphicValue_Key),
    FactKeyCodecs.of(codec_com_vanillasource_eliot_eliotc_monomorphize_fact_MonomorphicValue_Key)
  )
}
