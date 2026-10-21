package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  ArgumentDefinition,
  DataConstructor,
  DataDefinition,
  FunctionDefinition,
  GenericParameter,
  Expression as SourceExpression,
  Pattern as SourcePattern
}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Desugars data definitions into synthetic FunctionDefinitions. Works entirely in the source AST domain.
  *
  * Generates:
  *   - A type function that returns the kind of the type
  *   - Constructor functions for each constructor
  *   - Accessor functions for each field (only if there is exactly one constructor)
  *   - A PatternMatch ability implementation for pattern-matching dispatch
  *   - A TypeMatch ability implementation for type-matching dispatch
  */
object DataDefinitionDesugarer {

  def desugar(
      definition: DataDefinition
  ): Seq[(FunctionDefinition, RoleHint)] = {
    // The synthetic `PatternMatch`/`TypeMatch` implementations are keyed by the data type's own name — there is exactly
    // one of each per data type per module, so the name is a stable, position-independent identity (matching the
    // `(ability, pattern)` scheme user implementations use, with the data type standing in for the pattern).
    val implKey = definition.name.value
    // Every name a `data` mints inherits the declaration's visibility — the type constructor, value constructors and
    // accessors, *and* the synthetic PatternMatch/TypeMatch implementations, which is why the visibility is applied
    // once here rather than at each construction site. A `private data` whose implementations stayed public would leak
    // them out of the module (harmless in practice, since a private type can only be matched inside its own module,
    // but a leak nonetheless) and would report itself to VisibilityOrderChecker as a public declaration sitting among
    // the private ones. Dispatch is unaffected: ModuleAbilitiesProcessor indexes implementations from every name in
    // the module, not from the visibility-filtered import dictionary.
    (createTypeFunction(definition) ++ createConstructors(definition) ++ createAccessors(definition) ++
      createPatternMatchImpl(definition, implKey).map(_ -> RoleHint.NoHint) ++
      createTypeMatch(definition, implKey).map(_ -> RoleHint.NoHint))
      .map { case (fd, hint) => (fd.copy(visibility = definition.visibility), hint) }
  }

  private def createTypeFunction(definition: DataDefinition): Seq[(FunctionDefinition, RoleHint)] =
    Seq(
      (
        FunctionDefinition(
          definition.name.map(n => QualifiedName(n, Qualifier.Type)),
          Seq.empty,
          definition.genericParameters.map(gp => ArgumentDefinition(gp.name, gp.typeRestriction)),
          typeExpr(definition.name.as("Type")),
          None
        ),
        RoleHint.TypeConstructor(definition.genericParameters.size)
      )
    )

  private def createConstructors(definition: DataDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.constructors
      .getOrElse(Seq.empty)
      .map(ctor => createConstructor(definition, ctor))

  private def createConstructor(
      definition: DataDefinition,
      ctor: DataConstructor
  ): (FunctionDefinition, RoleHint) = {
    // A constructor states that its call is the value it built (`docs/meta.md` §11.1), which is what lets the channel
    // follow a value into a `match` with no rule about `match`.
    val fd   = FunctionDefinition(
      ctor.name.map(n => QualifiedName(n, Qualifier.Default)),
      definition.genericParameters,
      ctor.fields,
      typeExpr(
        definition.name,
        definition.genericParameters.map(gp => typeExpr(gp.name))
      ),
      None,
      returnMeta = Seq(metaPrimitive(ctor.name, "built", Seq.empty))
    )
    val hint = RoleHint.ValueConstructor(QualifiedName(definition.name.value, Qualifier.Type), ctor.fields.size)
    (fd, hint)
  }

  /** Accessors are only created when there is exactly one constructor. Each accessor is implemented as a match
    * expression that deconstructs the object and returns the target field.
    */
  private def createAccessors(definition: DataDefinition): Seq[(FunctionDefinition, RoleHint)] =
    definition.constructors
      .filter(_.size === 1)
      .getOrElse(Seq.empty)
      .flatMap(ctor =>
        ctor.fields.zipWithIndex.map { case (field, fieldIndex) =>
          (
            createAccessor(definition, ctor, field),
            RoleHint.FieldAccessor(QualifiedName(definition.name.value, Qualifier.Type), fieldIndex)
          )
        }
      )

  private def createAccessor(
      definition: DataDefinition,
      ctor: DataConstructor,
      field: ArgumentDefinition
  ): FunctionDefinition = {
    val matchBody = SourceExpression.MatchExpression(
      field.name.as(SourceExpression.FunctionApplication(None, field.name.as("obj"), None, Seq.empty)),
      Seq(
        SourceExpression.MatchCase(
          field.name.as(
            SourcePattern.ConstructorPattern(
              None,
              ctor.name,
              ctor.fields.map { f =>
                if (f.name.value == field.name.value)
                  f.name.as(SourcePattern.VariablePattern(field.name))
                else
                  f.name.as(SourcePattern.WildcardPattern(f.name.as("_")))
              }
            )
          ),
          field.name.as(SourceExpression.FunctionApplication(None, field.name, None, Seq.empty))
        )
      )
    )
    FunctionDefinition(
      field.name.map(n => QualifiedName(n, Qualifier.Default)),
      definition.genericParameters,
      Seq(
        ArgumentDefinition(
          field.name.as("obj"),
          typeExpr(
            definition.name,
            definition.genericParameters.map(gp => typeExpr(gp.name))
          )
        )
      ),
      field.typeExpression,
      Some(field.name.as(matchBody))
    )
  }

  /** Create a PatternMatch ability implementation for data types with constructors. Generates the implementation
    * marker (signals this type implements PatternMatch) and the `handleCases` eliminator, body-less — every backend
    * generates its implementation:
    *
    * {{{
    *   data Shape = Circle(r: Int) | Empty
    *     ⟶  def handleCases[R](value: Shape, case0 uses *: Int => R, case1 uses *: Unit => R) uses *: R
    *            {joined(case0, case1)}
    * }}}
    *
    * One handler per constructor, in declaration order, each taking that constructor's fields (a field-less one a
    * `Unit`). A handler is **code** — the arm the author wrote in place, on the effects in scope there — and the result
    * is the join of what the arms yield, stated as the eliminator's transfer exactly as `Bool::fold` states its own, so
    * a `match` derives its meta through the one mechanism every leaf uses (`docs/meta.md` §4.6).
    */
  private def createPatternMatchImpl(definition: DataDefinition, implKey: String): Seq[FunctionDefinition] =
    definition.constructors
      .filter(_.nonEmpty)
      .toSeq
      .flatMap { ctors =>
        val s               = definition.name
        val existingNames   = definition.genericParameters.map(_.name.value).toSet
        val resultParamName = freshName("R", existingNames)
        val resultParam     = GenericParameter(
          s.as(resultParamName),
          typeExpr(s.as("Type")),
          Seq.empty
        )
        val dataTypeRef     = typeExpr(
          definition.name,
          definition.genericParameters.map(gp => typeExpr(gp.name))
        )

        // Like every implementation marker, this is a body-less signature vessel whose return-type slot carries the
        // guard — here the default (unguarded) `true`, so the use-site guard discharge keeps it unconditionally
        // (ability-guards §2.3). The former identity body (`arg`) is retired uniformly with `ImplementBlock`.
        val implMarker = FunctionDefinition(
          s.as(
            QualifiedName(
              "PatternMatch",
              Qualifier.AbilityImplementation("PatternMatch", implKey, None)
            )
          ),
          definition.genericParameters,
          Seq(ArgumentDefinition(s.as("arg"), dataTypeRef)),
          SourceExpression.trueReference(s),
          None
        )

        val handlers       = ctors.zipWithIndex.map { (ctor, index) =>
          ArgumentDefinition(s.as(s"case$index"), handlerType(definition, ctor, resultParamName), openRow = true)
        }
        val handleCasesDef = FunctionDefinition(
          s.as(
            QualifiedName(
              "handleCases",
              Qualifier.AbilityImplementation("PatternMatch", implKey, None)
            )
          ),
          definition.genericParameters :+ resultParam,
          ArgumentDefinition(s.as("value"), dataTypeRef) +: handlers,
          typeExpr(s.as(resultParamName)),
          None,
          returnMeta = Seq(handlers.map(handler => reference(handler.name)).reduceRight(joined))
        )

        // What the eliminator hands each argument of each handler (`docs/meta.md` §4.5, §11.1): the field of `value` when
        // the handler's constructor built it, and nothing — the handler does not run — when another one did. A braced
        // field is stated where what built `value` is not known: the constructor proved it.
        val constructorTypeArgs = Option.when(definition.genericParameters.nonEmpty)(
          definition.genericParameters.map(gp => typeExpr(gp.name))
        )
        val handed              = ctors.zip(handlers).flatMap { (ctor, handler) =>
          val constructor = ctor.name.as(SourceExpression.FunctionApplication(None, ctor.name, None, Seq.empty))
          val arguments   = if (ctor.fields.isEmpty) Seq(Seq.empty) else ctor.fields.map(_.meta)
          arguments.zipWithIndex.map { (brace, index) =>
            MetaParameterDesugarer.fieldStatement(handleCasesDef, s.as("value"), handler.name, index, constructor, brace)
          }
        }

        Seq(implMarker, handleCasesDef) ++ handed
      }

  /** A `match` handler's parameter type — `case uses *: F1 => F2 => R`: the constructor's fields (a `Unit` for none)
    * curried into `Function`s, with the open row on the final codomain. Every name is written by its module, so a data
    * type's module needs nothing in scope for it.
    */
  private def handlerType(
      definition: DataDefinition,
      ctor: DataConstructor,
      resultParamName: String
  ): Sourced[SourceExpression] = {
    val s                                  = definition.name
    val domains                            =
      if (ctor.fields.isEmpty) Seq(qualified(s, WellKnownTypes.unitTypeFQN, Seq.empty)) else ctor.fields.map(_.typeExpression)
    val result: Sourced[SourceExpression] = s.as(SourceExpression.EffectfulType(Seq.empty, typeExpr(s.as(resultParamName))))
    domains.foldRight(result)((domain, codomain) => qualified(s, WellKnownTypes.functionDataTypeFQN, Seq(domain, codomain)))
  }

  /** A type named by its module: `vfqn` applied to `genericArgs`. */
  private def qualified(
      at: Sourced[?],
      vfqn: ValueFQN,
      genericArgs: Seq[Sourced[SourceExpression]]
  ): Sourced[SourceExpression] =
    at.as(
      SourceExpression.FunctionApplication(
        Some(at.as(vfqn.moduleName.show)),
        at.as(vfqn.name.name),
        Option.when(genericArgs.nonEmpty)(genericArgs),
        Seq.empty
      )
    )

  /** A reference to the parameter `name`. */
  private def reference(name: Sourced[String]): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, None, Seq.empty))

  /** A primitive of `eliot.compiler.Meta` applied to `arguments`, named by its module so the data's own module needs no
    * import of it.
    */
  private def metaPrimitive(
      at: Sourced[?],
      name: String,
      arguments: Seq[Sourced[SourceExpression]]
  ): Sourced[SourceExpression] =
    at.as(
      SourceExpression.FunctionApplication(
        Some(at.as(WellKnownTypes.metaPrimitiveFQN(name).moduleName.show)),
        at.as(name),
        None,
        arguments
      )
    )

  /** `eliot.compiler.Meta::joined(a, b)`, named by its module so the data's own module needs no import of it. */
  private def joined(a: Sourced[SourceExpression], b: Sourced[SourceExpression]): Sourced[SourceExpression] =
    a.as(
      SourceExpression.FunctionApplication(
        Some(a.as(WellKnownTypes.metaJoinedFQN.moduleName.show)),
        a.as(WellKnownTypes.metaJoinedFQN.name.name),
        None,
        Seq(a, b)
      )
    )

  /** Create a TypeMatch ability implementation for each data definition. This is a binary discriminator that checks if
    * a Type value was constructed by this data type's type constructor. Generates:
    *   1. Implementation marker function (signals this type implements TypeMatch) 2. Fields[R] associated type alias
    *      (handler type for matched generic parameters) 3. typeMatch[R] method (abstract - JVM backend generates
    *      implementation)
    *
    * Example: data Person[NAME: String](content: String) generates: implement TypeMatch[Person[NAME]] { type Fields[R]
    * \= Function[String, R] def typeMatch[R](obj: Type, matchCase: Function[String, R], elseCase: Function[Unit, R]): R
    * }
    */
  private def createTypeMatch(definition: DataDefinition, implKey: String): Seq[FunctionDefinition] = {
    val s               = definition.name
    val existingNames   = definition.genericParameters.map(_.name.value).toSet
    val resultParamName = freshName("R", existingNames)
    val resultParam     = GenericParameter(
      s.as(resultParamName),
      typeExpr(s.as("Type")),
      Seq.empty
    )
    val dataTypeRef     = typeExpr(
      definition.name,
      definition.genericParameters.map(gp => typeExpr(gp.name))
    )

    val abilityQualifier = Qualifier.AbilityImplementation("TypeMatch", implKey, None)

    val matchCaseType = typeMatchHandlerType(definition, resultParamName)
    val elseCaseType  = typeExpr(
      s.as("Function"),
      Seq(typeExpr(s.as("Unit")), typeExpr(s.as(resultParamName)))
    )

    // Body-less signature vessel with the default (unguarded) `true` guard in the return-type slot, as above.
    val implMarker = FunctionDefinition(
      s.as(QualifiedName("TypeMatch", abilityQualifier)),
      definition.genericParameters,
      Seq(ArgumentDefinition(s.as("arg"), dataTypeRef)),
      SourceExpression.trueReference(s),
      None
    )

    val fieldsTypeDef = FunctionDefinition(
      s.as(QualifiedName("Fields", abilityQualifier)),
      definition.genericParameters,
      Seq(ArgumentDefinition(s.as(resultParamName), typeExpr(s.as("Type")))),
      typeExpr(s.as("Type")),
      None
    )

    val typeMatchDef = FunctionDefinition(
      s.as(QualifiedName("typeMatch", abilityQualifier)),
      definition.genericParameters :+ resultParam,
      Seq(
        ArgumentDefinition(s.as("obj"), typeExpr(s.as("Type"))),
        ArgumentDefinition(s.as("matchCase"), matchCaseType),
        ArgumentDefinition(s.as("elseCase"), elseCaseType)
      ),
      typeExpr(s.as(resultParamName)),
      None
    )

    Seq(implMarker, fieldsTypeDef, typeMatchDef)
  }

  /** Build the match case handler type for a type match. Zero generic params: Function[Unit, R]. Otherwise: curried
    * Function[GP1.typeRestriction, Function[GP2.typeRestriction, ..., R]].
    */
  private def typeMatchHandlerType(definition: DataDefinition, resultParamName: String): Sourced[SourceExpression] =
    if (definition.genericParameters.isEmpty) {
      typeExpr(
        definition.name.as("Function"),
        Seq(
          typeExpr(definition.name.as("Unit")),
          typeExpr(definition.name.as(resultParamName))
        )
      )
    } else {
      definition.genericParameters.foldRight(typeExpr(definition.name.as(resultParamName))) { (gp, acc) =>
        typeExpr(definition.name.as("Function"), Seq(gp.typeRestriction, acc))
      }
    }

  private def freshName(base: String, existingNames: Set[String]): String =
    if (!existingNames.contains(base)) base
    else Iterator.from(0).map(i => s"$base$i").find(!existingNames.contains(_)).get

  /** Helper to create a sourced type expression from a name and optional generic arguments. */
  private def typeExpr(
      name: Sourced[String],
      genericArgs: Seq[Sourced[SourceExpression]] = Seq.empty
  ): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, Option.when(genericArgs.nonEmpty)(genericArgs), Seq.empty))
}
