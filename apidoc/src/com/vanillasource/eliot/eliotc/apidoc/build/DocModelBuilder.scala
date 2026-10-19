package com.vanillasource.eliot.eliotc.apidoc.build

import com.vanillasource.eliot.eliotc.apidoc.model.{DocItem, DocModule}
import com.vanillasource.eliot.eliotc.apidoc.render.SignatureRenderer
import com.vanillasource.eliot.eliotc.ast.fact.{AST, DataDefinition, Expression, FunctionDefinition, Visibility}
import com.vanillasource.eliot.eliotc.module.fact.{ModuleName, QualifiedName, Qualifier, ValueFQN}

/** Builds the presentation [[DocModule]] model from the per-file parsed ASTs, each tagged with the module it belongs to
  * and the layer (source root) it was found under.
  *
  * This is the apidoc-specific merge of Eliot's layering. Unlike the compiler's unifier — which *picks* one declaration
  * and rejects conflicts — this view keeps them all: declarations of the same name across layers collapse into one item
  * showing the canonical (abstract) signature, every layer that declares it, and the layers that implement it. The
  * three shapes are recovered from the qualifier the front end assigned: a `data`/`type` becomes a type-like item
  * (abstract `type IO[A]` merged with the concrete `data IO[A](..)` of a platform layer), an ability marker plus its
  * methods becomes an ability item (with its implementations gathered workspace-wide), and a plain `def` becomes a
  * value item.
  *
  * Documentation is not decided here — it comes from `docFor`, keyed by a name's [[ValueFQN]]. The pipeline supplies a
  * lookup backed by the [[com.vanillasource.eliot.eliotc.apidoc.fact.ValueDoc]] fact (so the site and the language
  * server's hover share one source of truth); the default is the self-contained [[DocText.fromLayerFiles]] over these
  * same files. Either way the choice — lowest layer wins, higher-layer duplicates warned — lives in [[DocText]].
  */
object DocModelBuilder {

  /** The built documentation model plus any layering diagnostics (ignored duplicate doc comments). */
  case class Result(modules: Seq[DocModule], warnings: Seq[String])

  /** Build the model with documentation taken from the files themselves ([[DocText.fromLayerFiles]]) — the
    * self-contained default, used by tests and any caller that is not part of the fact pipeline.
    */
  def build(layerFiles: Seq[(ModuleName, String, AST)]): Result =
    build(layerFiles, DocText.fromLayerFiles(layerFiles))

  def build(layerFiles: Seq[(ModuleName, String, AST)], docFor: ValueFQN => DocText.Selected): Result = {
    // Every public `implement` marker across every layer, tagged (layer, abilityName, marker). The same markers feed
    // two views: grouped by ability they list under the ability page (`implementationsByAbility`), grouped by the
    // concrete type(s) each pattern targets they list under those type pages (`implementationsByType`) — the same
    // collapsed "Implementations" block, so a type reads as more than the sum of its (often abstract) declaration.
    val taggedImplementations: Seq[(String, String, FunctionDefinition)] =
      layerFiles.flatMap { case (_, layer, ast) =>
        ast.functionDefinitions
          .filter(isPublic)
          .flatMap(fn => implMarkerAbility(fn).map(ability => (layer, ability, fn)))
      }

    val implementationsByAbility: Map[String, Seq[(String, String, FunctionDefinition)]] =
      taggedImplementations.groupBy(_._2)

    val implementationsByType: Map[String, Seq[(String, String, FunctionDefinition)]] =
      taggedImplementations
        .flatMap { case impl @ (_, _, marker) => implementedTypeNames(marker).map(_ -> impl) }
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap

    val builtModules = layerFiles
      .groupBy(_._1)
      .toSeq
      .sortBy { case (moduleName, _) => (moduleName.packages.mkString("."), moduleName.name) }
      .map { case (moduleName, files) =>
        buildModule(
          moduleName,
          files.map { case (_, layer, ast) => (layer, ast) },
          implementationsByAbility,
          implementationsByType,
          docFor
        )
      }

    Result(builtModules.map(_._1), builtModules.flatMap(_._2))
  }

  private def buildModule(
      moduleName: ModuleName,
      files: Seq[(String, AST)],
      implementationsByAbility: Map[String, Seq[(String, String, FunctionDefinition)]],
      implementationsByType: Map[String, Seq[(String, String, FunctionDefinition)]],
      docFor: ValueFQN => DocText.Selected
  ): (DocModule, Seq[String]) = {
    val taggedFunctions = files.flatMap { case (layer, ast) =>
      ast.functionDefinitions.filter(isPublic).map(layer -> _)
    }
    val taggedData      = files.flatMap { case (layer, ast) => ast.typeDefinitions.filter(isPublic).map(layer -> _) }

    val values         = taggedFunctions.filter(_._2.name.value.qualifier == Qualifier.Default)
    val types          = taggedFunctions.filter(_._2.name.value.qualifier == Qualifier.Type)
    val abilityMethods = taggedFunctions.filter(t => abilityMethodOf(t._2).isDefined)

    val typeItems = (types.map(_._2.name.value.name) ++ taggedData.map(_._2.name.value)).distinct.sorted.map { name =>
      buildTypeItem(
        moduleName,
        name,
        types.filter(_._2.name.value.name == name),
        taggedData.filter(_._2.name.value == name),
        implementationsByType.getOrElse(name, Seq.empty),
        docFor
      )
    }

    val abilityItems = taggedFunctions.flatMap(t => abilityMarkerOf(t._2)).distinct.sorted.map { name =>
      buildAbilityItem(
        moduleName,
        name,
        taggedFunctions.filter(t => abilityMarkerOf(t._2).contains(name)),
        abilityMethods.filter(t => abilityMethodOf(t._2).contains(name)),
        implementationsByAbility.getOrElse(name, Seq.empty),
        docFor
      )
    }

    val valueItems =
      values
        .map(_._2.name.value.name)
        .distinct
        .sorted
        .map(name => buildValueItem(moduleName, name, values.filter(_._2.name.value.name == name), docFor))

    val all = typeItems ++ abilityItems ++ valueItems
    (DocModule(moduleName, None, all.map(_._1)), all.flatMap(_._2))
  }

  private def buildValueItem(
      moduleName: ModuleName,
      name: String,
      decls: Seq[(String, FunctionDefinition)],
      docFor: ValueFQN => DocText.Selected
  ): (DocItem, Seq[String]) = {
    val selected = docFor(ValueFQN(moduleName, QualifiedName(name, Qualifier.Default)))
    val item     = DocItem(
      name = name,
      kind = DocItem.Kind.Value,
      signature = SignatureRenderer
        .forName(QualifiedName(name, Qualifier.Default), decls.map(_._2), Seq.empty, 0)
        .getOrElse(name),
      doc = selected.doc,
      layers = DocText.sortLayers(decls.map(_._1)),
      implementedOn = DocText.sortLayers(decls.filter(_._2.body.isDefined).map(_._1))
    )
    (item, selected.warnings)
  }

  private def buildTypeItem(
      moduleName: ModuleName,
      name: String,
      typeDecls: Seq[(String, FunctionDefinition)],
      dataDecls: Seq[(String, DataDefinition)],
      implementations: Seq[(String, String, FunctionDefinition)],
      docFor: ValueFQN => DocText.Selected
  ): (DocItem, Seq[String]) = {
    val concreteTypes    = typeDecls.filter(_._2.body.isDefined)
    val primarySignature =
      SignatureRenderer
        .forName(QualifiedName(name, Qualifier.Type), typeDecls.map(_._2), dataDecls.map(_._2), 0)
        .getOrElse(name)

    val concreteDefinitions =
      dataDecls.map { case (layer, dd) => SignatureRenderer.data(dd) -> layer } ++
        concreteTypes.map { case (layer, fn) => SignatureRenderer.function(fn) -> layer }

    val members = concreteDefinitions
      .filterNot(_._1 == primarySignature)
      .groupBy(_._1)
      .toSeq
      .sortBy(_._1)
      .map { case (signature, group) =>
        DocItem.Member(signature, None, Some(DocText.sortLayers(group.map(_._2)).mkString(", ")))
      }

    val selected = docFor(ValueFQN(moduleName, QualifiedName(name, Qualifier.Type)))

    val item = DocItem(
      name = name,
      kind = DocItem.Kind.TypeLike,
      signature = primarySignature,
      doc = selected.doc,
      layers = DocText.sortLayers(typeDecls.map(_._1) ++ dataDecls.map(_._1)),
      implementedOn = DocText.sortLayers(concreteTypes.map(_._1) ++ dataDecls.map(_._1)),
      members = members,
      implementations = implementationItems(implementations)
    )
    (item, selected.warnings)
  }

  private def buildAbilityItem(
      moduleName: ModuleName,
      name: String,
      markers: Seq[(String, FunctionDefinition)],
      methods: Seq[(String, FunctionDefinition)],
      implementations: Seq[(String, String, FunctionDefinition)],
      docFor: ValueFQN => DocText.Selected
  ): (DocItem, Seq[String]) = {
    val commonParameters = markers.head._2.genericParameters

    val methodMembersWithWarnings = methods
      .groupBy(_._2.name.value.name)
      .toSeq
      .sortBy(_._1)
      .map { case (methodName, group) =>
        // `forName` drops the ability's common generics (prepended to every method) so the method reads as written.
        val signature = SignatureRenderer
          .forName(
            QualifiedName(methodName, Qualifier.Ability(name)),
            group.map(_._2),
            Seq.empty,
            commonParameters.length
          )
          .getOrElse(methodName)
        val selected  = docFor(ValueFQN(moduleName, QualifiedName(methodName, Qualifier.Ability(name))))
        (DocItem.Member(signature, selected.doc), selected.warnings)
      }

    val selected = docFor(ValueFQN(moduleName, QualifiedName(name, Qualifier.Ability(name))))

    val item = DocItem(
      name = name,
      kind = DocItem.Kind.Ability,
      signature = SignatureRenderer
        .forName(QualifiedName(name, Qualifier.Ability(name)), markers.map(_._2), Seq.empty, 0)
        .getOrElse(name),
      doc = selected.doc,
      layers = DocText.sortLayers(markers.map(_._1) ++ methods.map(_._1)),
      members = methodMembersWithWarnings.map(_._1),
      implementations = implementationItems(implementations)
    )
    (item, selected.warnings ++ methodMembersWithWarnings.flatMap(_._2))
  }

  /** Collapses a set of `(layer, abilityName, marker)` implementations into presentation items: each distinct
    * `implement Ability[pattern]` signature once, carrying the sorted layers that provide it. Shared by the ability
    * page (its own implementations) and every type page (the implementations that target that type).
    */
  private def implementationItems(
      implementations: Seq[(String, String, FunctionDefinition)]
  ): Seq[DocItem.Implementation] =
    implementations
      .map { case (layer, ability, marker) => SignatureRenderer.implementation(ability, marker) -> layer }
      .groupBy(_._1)
      .toSeq
      .sortBy(_._1)
      .map { case (signature, group) => DocItem.Implementation(signature, DocText.sortLayers(group.map(_._2)), None) }

  /** The distinct concrete type names an `implement` marker targets — the head name of each pattern argument, minus the
    * implementation's own generic parameters. A generic `implement[A] Show[A]` targets no concrete type (its only
    * pattern head is its own generic `A`), so it is listed under its ability only, never a type page. A heterogeneous
    * `implement Arithmetic[Int, BigInteger]` targets both `Int` and `BigInteger`.
    */
  private def implementedTypeNames(marker: FunctionDefinition): Seq[String] = {
    val ownGenerics = marker.genericParameters.map(_.name.value).toSet
    marker.args.flatMap(arg => headTypeName(arg.typeExpression.value)).filterNot(ownGenerics).distinct
  }

  private def headTypeName(expr: Expression): Option[String] = expr match {
    case Expression.FunctionApplication(_, name, _, _) => Some(name.value)
    case _                                             => None
  }

  /** A declaration appears in the docs only if it is `public`; `private` names are module-local and are omitted,
    * mirroring the compiler's own rule that private names are neither importable nor resolvable outside their module.
    */
  private def isPublic(fn: FunctionDefinition): Boolean = fn.visibility == Visibility.Public
  private def isPublic(dd: DataDefinition): Boolean     = dd.visibility == Visibility.Public

  private def abilityMarkerOf(fn: FunctionDefinition): Option[String] = fn.name.value.qualifier match {
    case Qualifier.Ability(abilityName) if fn.name.value.name == abilityName => Some(abilityName)
    case _                                                                   => None
  }

  private def abilityMethodOf(fn: FunctionDefinition): Option[String] = fn.name.value.qualifier match {
    case Qualifier.Ability(abilityName) if fn.name.value.name != abilityName => Some(abilityName)
    case _                                                                   => None
  }

  private def implMarkerAbility(fn: FunctionDefinition): Option[String] = fn.name.value.qualifier match {
    case Qualifier.AbilityImplementation(abilityName, _, _) if fn.name.value.name == abilityName => Some(abilityName)
    case _                                                                                    => None
  }
}
