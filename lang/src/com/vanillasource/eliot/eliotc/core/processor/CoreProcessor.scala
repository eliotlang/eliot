package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  Expression as SourceExpression,
  FunctionDefinition,
  SourceAST
}
import com.vanillasource.eliot.eliotc.core.fact.{AST as CoreASTData, Expression as CoreExpression, *}
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor

/** Converts the source AST to the core AST.
  *
  * This processor performs several transformations:
  *   - Curries all functions to have exactly one argument and one result
  *   - Converts data definitions into constructor and accessor functions
  *   - Represents all types as levels in ExpressionStacks
  */
class CoreProcessor
    extends TransformationProcessor[SourceAST.Key, CoreAST.Key](key => SourceAST.Key(key.uri))
    with Logging {

  override protected def generateFromKeyAndFact(
      key: CoreAST.Key,
      sourceAst: SourceAST
  ): CompilerIO[CoreAST] = {
    val sourceAstData = sourceAst.ast.value
    // Each data definition's synthetic PatternMatch/TypeMatch implementations are keyed by the data type's name (a
    // stable per-module identity), so no cross-cutting index bookkeeping is needed — they cannot collide with each
    // other or with user implementations (which are keyed by their own `(ability, pattern)`).
    val desugaredFromData: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.typeDefinitions.flatMap(DataDefinitionDesugarer.desugar)
    // Meta-slot brace on a type declaration (`type Int {range: …}`) → the type's `^Meta` constructor
    // (bounds-as-refinements §4.2, Step 4a). Type aliases are `FunctionDefinition`s, so this runs over them.
    val desugaredMetaConstructors: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.functionDefinitions.flatMap(MetaConstructorDesugarer.desugar)
    // Return-position transfer brace on a def (`def add(…): Int {a.range + b.range}`) → the def's `^Meta` transfer
    // companion (bounds-as-refinements §4.2, Step 4b).
    val desugaredMetaTransfers: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.functionDefinitions.flatMap(MetaTransferDesugarer.desugar)
    // `where <predicate>` precondition on a def (`def useByte(x: Int): Int where withinByte(range(x))`) → the def's
    // `^Where` companion (bounds-as-refinements §4.3), the `Bool` the refinement channel demands at each call site.
    val desugaredWhereCompanions: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.functionDefinitions.flatMap(MetaWhereDesugarer.desugar)
    // A named `implement` (effects v6 §9.3) lowers to its methods, its marker and its name marker, exactly as the
    // anonymous form lowers in `ImplementBlock` — except that the named form is parsed into a node of its own, so the
    // lowering runs here rather than in the parser. See NamedImplementationDesugarer.
    val desugaredNamedImplementations: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.namedImplementations.flatMap(NamedImplementationDesugarer.desugar).map(_ -> RoleHint.NoHint)
    // An `effect` declaration (effects v6 §9.3) lowers exactly as an `ability` block does — the difference is one
    // metadata flag, not a shape — except that it parses into a node of its own. See EffectDefinitionDesugarer.
    val desugaredEffects: Seq[(FunctionDefinition, RoleHint)] =
      sourceAstData.effectDefinitions.flatMap(EffectDefinitionDesugarer.desugar).map(_ -> RoleHint.NoHint)
    val allFunctions  =
      sourceAstData.functionDefinitions.map(_ -> RoleHint.NoHint) ++
        desugaredFromData ++ desugaredMetaConstructors ++ desugaredMetaTransfers ++ desugaredWhereCompanions ++
        desugaredNamedImplementations ++ desugaredEffects
    val coreAstData   = CoreASTData(
      sourceAstData.importStatements,
      // Effect-row sugar (`{E} A`) becomes one marked binding binder per entry before the function is converted, so
      // everything downstream sees ordinary generics (see EffectSugarDesugarer). Each definition splits at birth into
      // its `Runtime` twin and its `Signature` twin (see [[transformFunction]]).
      allFunctions.flatMap { case (fd, hint) => transformFunction(EffectSugarDesugarer.desugar(fd), hint) }
    )

    // Strict-positivity check (termination precondition #2): reject any `data` whose own type constructor appears in a
    // contravariant position of a constructor field (the negative-recursive-datatype route to `Y`). See
    // StrictPositivityChecker. Errors are reported here but the CoreAST is still produced so other checks proceed.
    val positivityErrors = sourceAstData.typeDefinitions.flatMap(StrictPositivityChecker.check)
    // Visibility-order check: a file's public API must be a prefix of its declarations, so no public declaration may
    // follow a private one. Runs on the desugared named values (not the source AST) so `def`/`type`/`data`/`ability`/
    // `implement` all answer to one rule with no per-construct arms. See VisibilityOrderChecker.
    val visibilityErrors = VisibilityOrderChecker.check(coreAstData.namedValues)
    // A `{ … }` block in a *signature*: only bodies are block-desugared, so one written in a type position used to
    // reach match desugaring un-lowered and crash the compiler. See SignatureBlockChecker for why it is reported
    // rather than lowered.
    val signatureErrors  = coreAstData.namedValues.flatMap(SignatureBlockChecker.check)
    (positivityErrors ++ visibilityErrors ++ signatureErrors)
      .traverse_(message => Sourced.compilerError(message)) >>
      debug[CompilerIO](
        s"Core functions in ${key.uri}: ${coreAstData.namedValues.map(_.render).mkString(", ")}"
      ) >>
      CoreAST(sourceAst.uri, sourceAst.ast.as(coreAstData)).pure[CompilerIO]
  }

  /** Convert one definition into its [[NamedValue]] — its `runtime` body (`None` iff abstract) and its `signature`
    * (type) expression.
    *
    * The signature split (S0–S8) once minted a second *signature twin* here — a value whose body was the signature
    * expression, so a signature could be monomorphized on its own — but that twin was an inert duplicate: its content is
    * exactly this value's `signature`, already threaded through the whole front-end. Signature-unification Phase E
    * removed it; `Role.Signature` survives only as a *monomorphize-level* key dimension (`CompilerMonomorphicValue(v@Signature,
    * args)`), whose input is this same runtime value's `SaturatedValue` (its signature checked via `processSignatureTwin`).
    */
  private def transformFunction(function: FunctionDefinition, roleHint: RoleHint): Seq[NamedValue] = {
    import CoreExpressionConverter.*
    val curriedType  = curriedFunctionType(function.args, function.typeDefinition, function.genericParameters)
    val isTypeBody   = function.typeDefinition.value match {
      // A bare `Type` return type (no value args, no — or empty — generic brackets). `genericArguments` is now an
      // `Option`: `None` = no brackets, `Some(Seq())` = `Type[]`; both count, neither carries args.
      case SourceExpression.FunctionApplication(None, fnName, genArgs, Seq()) if genArgs.forall(_.isEmpty) =>
        fnName.value == "Type"
      case _                                                                                               => false
    }
    // A value in the [[Qualifier.Meta]] namespace is a meta companion (a transfer brace's `^Meta`, a `where`
    // predicate's `^Where`): compiler-pool-only code, dead in the runtime pool. Its body is therefore compiler-track,
    // so an integer literal in it is a bare `BigInteger` — the same reading a signature's literal gets — rather than
    // the runtime `integerLiteral[n] : Int` protocol. That is what lets a brace name a number at all: an interval
    // endpoint is a `BigInteger`, and there is no widening from `Int` to one.
    val isMetaBody   = function.name.value.qualifier match {
      case _: Qualifier.Meta => true
      case _                 => false
    }
    val curriedValue = function.body.map(body => buildCurriedBody(function.args, body, isTypeBody, isMetaBody))
    val constraints  = function.genericParameters
      .map(gp =>
        gp.name.value -> gp.abilityConstraints.map(_.map(te => convertExpression(te, typeContext = true).value))
      )
      .filter(_._2.nonEmpty)
      .toMap
    val precedence   = function.precedence.map(convertPrecedenceDeclaration)
    // Effects-as-channel Phase 1 (dark): forward the declared row, converting each entry's ability type-arguments
    // ast→core exactly as `constraints` above does.
    val effectRow    = function.effectRow.map(_.map(te => convertExpression(te, typeContext = true).value))
    Seq(
      NamedValue(
        function.name,
        curriedValue,
        curriedType,
        constraints,
        function.fixity,
        precedence,
        function.visibility,
        roleHint,
        effectRow
      )
    )
  }
}
