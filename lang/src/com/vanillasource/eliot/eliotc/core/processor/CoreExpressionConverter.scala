package com.vanillasource.eliot.eliotc.core.processor

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{
  GenericParameter,
  ArgumentDefinition as SourceArgument,
  Expression as SourceExpression,
  LambdaParameterDefinition as SourceLambdaParameter,
  PrecedenceDeclaration as AstPrecedenceDeclaration,
  Pattern as SourcePattern
}
import com.vanillasource.eliot.eliotc.core.fact.Expression.*
import com.vanillasource.eliot.eliotc.core.fact.*
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

/** Converts source AST expressions, types, and patterns into core AST expressions. All methods are pure, stateless
  * transformations.
  */
object CoreExpressionConverter {

  /** Converts a source expression to a core expression. In type context, bare uppercase names get Qualifier.Type; in
    * body context, they get Qualifier.Default (unless they have [] args, which always implies type-level). Value-level
    * () args are always converted in body context, [] args always in type context.
    *
    * `compilerTrackContext` marks the whole expression as code that only ever runs on the **compiler track** — a
    * **signature** (a type expression: a return/argument type, a generic-parameter kind), or a **meta** body (a
    * transfer brace's `^Meta` companion, a `where` predicate's `^Where` companion — see
    * [[com.vanillasource.eliot.eliotc.core.processor.MetaTransferDesugarer]]). There an integer literal is a
    * *compile-time* integer — a bare `BigInteger` — never the runtime `integerLiteral[n] : Int` value-literal protocol,
    * exactly as it already is inside `[...]`. It is threaded (invariantly) through the descent so a literal keeps its
    * compile-time reading even inside a `()` value-argument application (e.g. an inline guard `if(MIN > 0, T) else …`,
    * whose `0` must unify with `MIN : BigInteger`; or a brace's `interval(0, end(size(s)))`, whose `0` is an interval
    * endpoint). It is orthogonal to `typeContext`, which still governs the Type/Default namespace of bare names within
    * that application.
    */
  def convertExpression(
      expr: Sourced[SourceExpression],
      typeContext: Boolean,
      compilerTrackContext: Boolean = false
  ): Sourced[Expression] =
    expr.value match {
      case SourceExpression.FunctionApplication(moduleName, fnName, genericArgs, args) =>
        val isUpper = fnName.value.charAt(0).isUpper
        // An upper-case name (not applied to value `()` args) resolves in the Type namespace when it is in type
        // context, OR when `[...]` brackets are present at all — including an explicit empty `[]`. The empty `[]` is
        // the type-level analogue of value-level `()`: it forces a bare type constructor (e.g. `JvmByte[]`) into the
        // Type namespace in value position, where a plain `JvmByte` would default to a (possibly non-existent) value
        // constructor.
        if (isUpper && args.isEmpty && (typeContext || genericArgs.isDefined)) {
          val base = expr.as(NamedValueReference(fnName.map(n => QualifiedName(n, Qualifier.Type)), moduleName))
          curryApplicationWith(
            base,
            genericArgs.getOrElse(Seq.empty),
            convertExpression(_, typeContext = true, compilerTrackContext)
          )
        } else {
          curryApplicationWith(
            expr.as(
              NamedValueReference(
                fnName.map(n => QualifiedName(n, Qualifier.Default)),
                moduleName,
                genericArgs.getOrElse(Seq.empty).map(convertExpression(_, typeContext = true, compilerTrackContext))
              )
            ),
            args,
            // A `()` value argument flips the *namespace* to Default, but stays within the enclosing signature — so a
            // literal in it remains compile-time (`compilerTrackContext` preserved).
            convertExpression(_, typeContext = false, compilerTrackContext)
          )
        }
      case SourceExpression.FunctionLiteral(params, body)                              =>
        curryLambda(params, convertExpression(body, typeContext, compilerTrackContext), compilerTrackContext)
      case SourceExpression.IntegerLiteral(lit)                                        =>
        if (typeContext || compilerTrackContext) {
          // Type/bound position (inside `[...]`) or anywhere in a signature: the literal is a compile-time
          // `BigInteger`, not the runtime `integerLiteral[n] : Int` value-literal protocol.
          expr.as(IntegerLiteral(lit))
        } else {
          // Value position (a body): desugar `n` into `integerLiteral[n] : Int[n, n]`. The inner `[n]` lands in
          // `typeArgs` (type context), so it stays a bare `BigInteger`.
          expr.as(
            NamedValueReference(
              lit.as(QualifiedName("integerLiteral", Qualifier.Default)),
              None,
              Seq(expr.as(IntegerLiteral(lit)))
            )
          )
        }
      case SourceExpression.StringLiteral(lit)                                         =>
        expr.as(StringLiteral(lit))
      case SourceExpression.FlatExpression(Seq(single))                                =>
        convertExpression(single, typeContext, compilerTrackContext)
      case SourceExpression.FlatExpression(parts)                                      =>
        expr.as(FlatExpression(parts.map(p => convertExpression(p, typeContext, compilerTrackContext))))
      case SourceExpression.MatchExpression(scrutinee, cases)                          =>
        expr.as(
          MatchExpression(
            convertExpression(scrutinee, typeContext, compilerTrackContext),
            cases.map(c =>
              MatchCase(
                c.pattern.map(toPattern),
                convertExpression(c.body, typeContext, compilerTrackContext)
              )
            )
          )
        )
      case SourceExpression.BlockExpression(lines)                                     =>
        expr.as(BlockExpression(lines.map { line =>
          BlockLine(
            line.binder.map(_.name),
            line.binder
              .flatMap(_.typeExpression)
              .map(t => convertExpression(t, typeContext = true, compilerTrackContext)),
            convertExpression(line.expression, typeContext, compilerTrackContext)
          )
        }))
      case SourceExpression.WithBinding(subject, implementation)                       =>
        // Effects v6 (`docs/effects.md` §9.3): the binding rides the tree as far as the `row` phase, which writes it
        // into every phantom binder inside the subject and erases the node. The name is carried as written and
        // resolved at `ImplementationNameResolver`; the parser guarantees the shape below (a bare, optionally
        // module-qualified lower-case identifier, applied only to the arguments of an implementation that takes
        // parameters). The arguments are values, computed where the `with` is written.
        val (moduleName, name, arguments) = implementation.value match {
          case SourceExpression.FunctionApplication(module, fnName, None, arguments) => (module, fnName, arguments)
          case other                                                                 =>
            throw IllegalStateException(s"An implementation binding is not a plain name: ${other.render}")
        }
        expr.as(
          WithBinding(
            convertExpression(subject, typeContext, compilerTrackContext),
            name,
            moduleName,
            arguments.map(convertExpression(_, typeContext = false, compilerTrackContext))
          )
        )
      case _: SourceExpression.EffectfulType                                           =>
        // EffectSugarDesugarer rewrites every `{…} A` to `F[A]` across the whole function before conversion, so an
        // EffectfulType reaching here means it was written somewhere the desugarer does not reach (only signature and
        // body positions are handled). Fail loudly rather than mis-convert it.
        throw IllegalStateException(
          s"Effect braces `{…}` are only supported in function signature positions: ${expr.value.render}"
        )
    }

  /** Builds a curried function type. So f[A, B](d: D, e: E): F type becomes: A -> B -> Function^Type(D,
    * Function^Type(E, F)). Note: f[A, M[_]]... becomes: A -> M -> ..., where M has a type expression on it: X -> Y \->
    * Function^Type(X, Y)
    */
  def curriedFunctionType(
      args: Seq[SourceArgument],
      returnType: Sourced[SourceExpression],
      genericParams: Seq[GenericParameter]
  ): Sourced[Expression] = {
    // The whole signature is a compile-time type expression: `compilerTrackContext = true` keeps every integer literal a
    // bare `BigInteger`, even inside a `()` value-argument application the return type may contain (an inline guard).
    val withArgs =
      args.foldRight[Sourced[Expression]](
        convertExpression(returnType, typeContext = true, compilerTrackContext = true)
      ) { (arg, acc) =>
        val argType     = convertExpression(arg.typeExpression, typeContext = true, compilerTrackContext = true)
        val functionRef =
          arg.name.as(NamedValueReference(arg.name.as(QualifiedName("Function", Qualifier.Type))))
        val withArgType = arg.name.as(
          FunctionApplication(
            functionRef,
            argType
          )
        )
        arg.name.as(
          FunctionApplication(
            withArgType,
            acc
          )
        )
      }
    genericParams.foldRight[Sourced[Expression]](withArgs) { (param, acc) =>
      val kindType = convertExpression(param.typeRestriction, typeContext = true, compilerTrackContext = true)
      param.name.as(FunctionLiteral(param.name, Some(kindType), acc))
    }
  }

  /** Converts the body into core expression and embeds it as a lambda with the "function" parameters.
    *
    * The parameters are left *unannotated*: a value's signature is the single source of truth for its parameter types,
    * and the body is always *checked* against that signature, so each parameter takes its type from the signature's
    * corresponding `Function` domain (see the `check(FunctionLiteral, VPi)` case in the monomorphize `Checker`).
    * Re-stating the type on the body lambda would only duplicate it — and would force later passes that refine the
    * signature (e.g. `auto`-parameter saturation) to keep a redundant body copy in sync.
    *
    * `compilerTrackContext` marks a body that only ever runs on the compiler track — a meta companion's — so its
    * integer literals stay bare `BigInteger`s (see [[convertExpression]]).
    */
  def buildCurriedBody(
      args: Seq[SourceArgument],
      value: Sourced[SourceExpression],
      typeContext: Boolean = false,
      compilerTrackContext: Boolean = false
  ): Sourced[Expression] =
    args.foldRight(convertExpression(value, typeContext, compilerTrackContext)) { (arg, acc) =>
      arg.name.as(FunctionLiteral(arg.name, None, acc))
    }

  def toPattern(pattern: SourcePattern): Pattern =
    pattern match {
      case SourcePattern.ConstructorPattern(moduleName, constructorName, subPatterns, isTypePattern) =>
        val qualifier = if (isTypePattern) Qualifier.Type else Qualifier.Default
        Pattern.ConstructorPattern(
          moduleName,
          constructorName.map(n => QualifiedName(n, qualifier)),
          subPatterns.map(_.map(toPattern))
        )
      case SourcePattern.VariablePattern(name)                                                       =>
        Pattern.VariablePattern(name)
      case SourcePattern.WildcardPattern(source)                                                     =>
        Pattern.WildcardPattern(source)
    }

  def convertPrecedenceDeclaration(pd: AstPrecedenceDeclaration): PrecedenceDeclaration =
    PrecedenceDeclaration(pd.relation, pd.targets)

  /** Curries lambda expressions into core format. In core, lambdas have exactly one argument, so a lambda: (a,b,c) ->
    * body, needs to be converted into: a -> b -> c -> body.
    */
  private def curryLambda(
      params: Seq[SourceLambdaParameter],
      body: Sourced[Expression],
      compilerTrackContext: Boolean = false
  ): Sourced[Expression] =
    params.foldRight(body) { (param, acc) =>
      param.name.as(
        FunctionLiteral(
          param.name,
          param.typeExpression.map(convertExpression(_, typeContext = true, compilerTrackContext)),
          acc
        )
      )
    }

  /** Curries applications into core format. f(a, b, c) becomes f(a)(b)(c). The convert function determines how each
    * argument is converted (body context for () args, type context for [] args).
    *
    * Each intermediate application node is sourced over the *combined* span of its target and argument (never just the
    * argument's), so a whole construction `Database("…")` attributes an error to `Database("…")` rather than to only
    * its constructor parameter `"…"`. `target` already carries the full application span (`expr.as(...)` at the call
    * site), so the outline reaches the closing `)`.
    */
  private def curryApplicationWith(
      target: Sourced[Expression],
      args: Seq[Sourced[SourceExpression]],
      convert: Sourced[SourceExpression] => Sourced[Expression]
  ): Sourced[Expression] =
    args.foldLeft(target) { (acc, arg) =>
      val applied = convert(arg)
      Sourced.outline(Seq(acc, applied)).as(FunctionApplication(acc, applied))
    }
}
