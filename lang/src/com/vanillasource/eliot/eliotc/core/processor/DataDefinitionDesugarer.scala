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
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
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
    val fd   = FunctionDefinition(
      ctor.name.map(n => QualifiedName(n, Qualifier.Default)),
      definition.genericParameters,
      ctor.fields,
      typeExpr(
        definition.name,
        definition.genericParameters.map(gp => typeExpr(gp.name))
      ),
      None
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

  /** Create a PatternMatch ability implementation for data types with constructors. Generates:
    *   1. Implementation marker function (signals this type implements PatternMatch) 2. Cases[R] associated type alias
    *      (Church encoding wrapper type) 3. handleCases[R] method (abstract - JVM backend generates implementation)
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

        // Build the Church encoding selector type: Function[H0, Function[H1, ..., Function[Hn, R]...]]
        val selectorType = ctors.foldRight(typeExpr(s.as(resultParamName))) { (ctor, acc) =>
          typeExpr(s.as("Function"), Seq(eliminatorHandlerType(definition, ctor, resultParamName), acc))
        }

        // Church Cases type: Function[SelectorType, R]
        val churchCasesType = typeExpr(
          s.as("Function"),
          Seq(selectorType, typeExpr(s.as(resultParamName)))
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

        val casesTypeDef = FunctionDefinition(
          s.as(
            QualifiedName(
              "Cases",
              Qualifier.AbilityImplementation("PatternMatch", implKey, None)
            )
          ),
          definition.genericParameters,
          Seq(ArgumentDefinition(s.as(resultParamName), typeExpr(s.as("Type")))),
          typeExpr(s.as("Type")),
          None
        )

        val handleCasesDef = FunctionDefinition(
          s.as(
            QualifiedName(
              "handleCases",
              Qualifier.AbilityImplementation("PatternMatch", implKey, None)
            )
          ),
          definition.genericParameters :+ resultParam,
          Seq(
            ArgumentDefinition(s.as("value"), dataTypeRef),
            ArgumentDefinition(s.as("cases"), churchCasesType)
          ),
          typeExpr(s.as(resultParamName)),
          None
        )

        Seq(implMarker, casesTypeDef, handleCasesDef)
      }

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

  /** Build the handler type for an eliminator. Zero fields: Function[Unit, R]. One field: Function[FieldType, R].
    * Multiple fields: curried Function[Field1, Function[Field2, ..., R]].
    */
  private def eliminatorHandlerType(
      definition: DataDefinition,
      ctor: DataConstructor,
      resultParamName: String
  ): Sourced[SourceExpression] =
    if (ctor.fields.isEmpty) {
      typeExpr(
        definition.name.as("Function"),
        Seq(
          typeExpr(definition.name.as("Unit")),
          typeExpr(definition.name.as(resultParamName))
        )
      )
    } else {
      ctor.fields.foldRight(typeExpr(definition.name.as(resultParamName))) { (field, acc) =>
        typeExpr(
          definition.name.as("Function"),
          Seq(field.typeExpression, acc)
        )
      }
    }

  /** Helper to create a sourced type expression from a name and optional generic arguments. */
  private def typeExpr(
      name: Sourced[String],
      genericArgs: Seq[Sourced[SourceExpression]] = Seq.empty
  ): Sourced[SourceExpression] =
    name.as(SourceExpression.FunctionApplication(None, name, Option.when(genericArgs.nonEmpty)(genericArgs), Seq.empty))
}
