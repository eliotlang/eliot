package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.feedback.Logging
import com.vanillasource.eliot.eliotc.module.fact.{UnifiedModuleNames, ValueFQN}
import com.vanillasource.eliot.eliotc.monomorphize.channel.MetaDemands.{Knowledge, Violation}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  GroundValue,
  GroundValueRenderer,
  MonomorphicExpression,
  MonomorphicValue
}
import com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.Literal
import com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.processor.common.TransformationProcessor
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.annotation.tailrec

/** Checks the **meta demands** of one body in one meta context (`docs/total-meta-transfers.md` P5) — every `where`
  * precondition and parameter brace a call in it demands — and produces its [[MetaDemands]].
  *
  * Every meta is a compile-time value known from `main`, so a demand is checked in each context its call is actually
  * reached in, with that context's argument metas, rather than once per body over ⊤ parameters:
  *
  * {{{
  * def useIt(y: Int): Int = useByte(y)   // root context: `y` is symbolic, so `useByte`'s demand is deferred
  * def main uses Console: Unit = {
  *    printLine(show(useIt(42)))         // context [42, 42]: satisfied
  *    printLine(show(useIt(1000)))       // context [1000, 1000]: reported here, naming the demand inside `useIt`
  * }
  * }}}
  *
  * The walk computes what each node's meta is through [[MetaInterpreter]], exactly as the stamping walk
  * ([[RefinementChannelProcessor]]) does, and additionally tracks two things the stamping walk does not need:
  *
  *   - **symbolic**: the meta is unknown *because* it depends on a parameter the context leaves
  *     [[Knowledge.Symbolic]]. A demand reading such a value is **deferred** — its callers supply the contexts that
  *     decide it, and this context's fact says so ([[MetaDemands.deferred]]);
  *   - **depends on**: which of the context's parameter positions the value derives from. A root context depends on
  *     none of them. Any other context reports only what depends on what *it* supplies, since everything else is
  *     decided identically in the root context, which reports it — that is what keeps a violation from being reported
  *     once per context.
  *
  * A call to a bodied callee whose root context defers asks for the callee's demands in the context its arguments make
  * — a fact of its own, so one context is checked once however many calls reach it — and lifts what that context
  * violates to this call, adding where the demand sits and what reached it. Arguments the call does not apply are
  * [[Knowledge.Unknown]]: the function value they would be applied to later is invisible to the channel, so a demand on
  * one of them fails loudly rather than escapes. Everything ⊤ is still the walk's own gaps — a lambda binder, a `match`
  * result, an applied parameter, an exhausted interpretation budget — and fails any demand that reaches it.
  *
  * A **root** context reports its violations as compiler errors, at the node in its own body where the demand was made
  * or where the call that reached it was written. Any other context reports nothing: its violations travel in its fact
  * to the caller that supplied it.
  */
class MetaDemandsProcessor
    extends TransformationProcessor[MonomorphicValue.Key, MetaDemands.Key](key =>
      MonomorphicValue.Key(key.vfqn, key.typeArguments)
    )
    with Logging {

  import MetaDemandsProcessor.*

  override protected def generateFromKeyAndFact(key: MetaDemands.Key, mv: MonomorphicValue): CompilerIO[MetaDemands] =
    for {
      root   <- MetaDemands.rootOf(mv)
      scope   = Scope(key.context == root.context)
      walked <- mv.runtime.fold(Walked.nothing.pure[CompilerIO])(body =>
                  referencesDemanding(body.value).ifM(
                    walkParameters(
                      scope,
                      body.as(MonomorphicExpression(mv.signature, body.value)),
                      key.context.zip(root.context).zipWithIndex.toList,
                      Map.empty
                    ),
                    Walked.nothing.pure[CompilerIO]
                  )
                )
      _      <- walked.violations.traverse_(violation => Sourced.compilerError(violation.at, violation.description))
                  .whenA(scope.isRoot)
    } yield MetaDemands(
      key.vfqn,
      key.typeArguments,
      key.context,
      walked.deferred,
      if (scope.isRoot) Seq.empty else walked.violations
    )

  /** Whether the body references anything that can demand: a callee with a `where` or a parameter brace, or a bodied
    * callee whose root context defers. A body referencing none has nothing to check in any context, so its metas are
    * never computed — the overwhelming majority of bodies.
    */
  private def referencesDemanding(body: MonomorphicExpression.Expression): CompilerIO[Boolean] =
    references(body).distinct.existsM((callee, typeArgs) =>
      hasWhereCompanion(callee).ifM(
        true.pure[CompilerIO],
        MetaInterpreter
          .parameterStatements(callee)
          .flatMap(statements => if (statements.nonEmpty) true.pure[CompilerIO] else rootDefers(callee, typeArgs))
      )
    )

  /** Every value the expression references, with its type arguments. */
  private def references(expression: MonomorphicExpression.Expression): Seq[(ValueFQN, Seq[GroundValue])] =
    expression match {
      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) => Seq((vfqn.value, typeArgs))
      case MonomorphicExpression.FunctionApplication(target, argument)     =>
        references(target.value.expression) ++ references(argument.value.expression)
      case MonomorphicExpression.FunctionLiteral(_, _, body)               => references(body.value.expression)
      case _                                                               => Seq.empty
    }

  /** Whether a bodied callee taking parameters has demands its root context defers to its callers. */
  private def rootDefers(callee: ValueFQN, typeArgs: Seq[GroundValue]): CompilerIO[Boolean] =
    getFactIfProduced(MonomorphicValue.Key(callee, typeArgs)).flatMap {
      case Some(calleeValue) if calleeValue.naturalArity.exists(_ > 0) =>
        MetaDemands.rootOf(calleeValue).flatMap(getFactIfProduced).map(_.exists(_.deferred))
      case _                                                           => false.pure[CompilerIO]
    }

  /** Bind the body's leading parameters to what the context says of them, then walk what is under them. */
  private def walkParameters(
      scope: Scope,
      node: Sourced[MonomorphicExpression],
      parameters: List[((Knowledge, Knowledge), Int)],
      env: Env
  ): CompilerIO[Walked] =
    (node.value.expression, parameters) match {
      case (MonomorphicExpression.FunctionLiteral(name, _, body), ((entry, root), position) :: rest) =>
        walkParameters(scope, body, rest, env.updated(name.value, parameterInfo(scope, entry, root, position)))
      case _                                                                                        =>
        walk(scope, node, env)
    }

  /** What the walk knows of a parameter the context describes. An [[Knowledge.Unknown]] entry at a position whose type
    * carries no meta is the same in every context, so nothing depends on it.
    */
  private def parameterInfo(scope: Scope, entry: Knowledge, root: Knowledge, position: Int): Info =
    entry match {
      case Knowledge.Symbolic    => Info(None, symbolic = true, Set.empty)
      case Knowledge.Known(meta) => Info(Some(meta), symbolic = false, scope.supplied(position))
      case Knowledge.Unknown     =>
        Info(None, symbolic = false, if (root == Knowledge.Unknown) Set.empty else scope.supplied(position))
    }

  private def walk(scope: Scope, node: Sourced[MonomorphicExpression], env: Env): CompilerIO[Walked] =
    node.value.expression match {
      case MonomorphicExpression.IntegerLiteral(value)                     =>
        MetaInterpreter.integerSeed(value.value).map(meta => Walked.leaf(Info.of(meta)))
      case MonomorphicExpression.StringLiteral(value)                      =>
        MetaInterpreter.stringSeed(value.value).map(meta => Walked.leaf(Info.of(meta)))
      case MonomorphicExpression.ParameterReference(name)                  =>
        Walked.leaf(env.getOrElse(name.value, Info.unknown)).pure[CompilerIO]
      case MonomorphicExpression.FunctionLiteral(name, _, body)            =>
        // A function value carries no meta; what a *code* argument yields is its innermost body's meta, which is what a
        // demand at a code parameter reads ([[Walked.yields]]). The binder is supplied by whoever applies the lambda,
        // which the channel does not see, so it is unknown. A callee applying the lambda reads the parameters it
        // captures, so its own info is theirs ([[captured]]).
        walk(scope, body, env - name.value).map(inner => inner.copy(info = captured(node, env)))
      case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
        // A bare reference: a saturated call of a nullary def, or a function value whose arguments the channel never
        // sees — its demands are rejected as a value, and its body's demands are checked with every argument unknown.
        for {
          rejected <- rejectedAsValue(scope, node, vfqn.value)
          internal <- internalDemands(scope, node, vfqn.value, typeArgs, Seq.empty)
          meta     <- MetaInterpreter.metaOfCall(vfqn.value, typeArgs, node.value.expressionType, Seq.empty)
        } yield Walked(Info.of(meta), Info.of(meta), internal.deferred, rejected ++ internal.violations)
      case _: MonomorphicExpression.FunctionApplication                    =>
        val (head, args) = MetaInterpreter.flatten(node)
        head.value.expression match {
          case MonomorphicExpression.MonomorphicValueReference(vfqn, typeArgs) =>
            for {
              walkedArgs <- args.traverse(walk(scope, _, env))
              own        <- ownDemands(scope, node, vfqn.value, typeArgs, walkedArgs)
              internal   <- internalDemands(scope, node, vfqn.value, typeArgs, walkedArgs.map(_.info))
              meta       <- MetaInterpreter.metaOfCall(
                              vfqn.value,
                              typeArgs,
                              node.value.expressionType,
                              args
                                .zip(walkedArgs)
                                .map((arg, walked) => MetaInterpreter.argument(arg, walked.info.meta, env.view.mapValues(_.meta).toMap))
                            )
            } yield {
              val info = Info.derived(meta, walkedArgs.map(_.info))
              Walked(info, info, false, Seq.empty).andAll(walkedArgs :+ own :+ internal)
            }
          case _: MonomorphicExpression.FunctionLiteral                        =>
            // An applied lambda — what a pure `val` block lowers to — binds its binders to its arguments, so a block's
            // `val`s carry what is known of them, symbolic or not, into the rest of the block.
            for {
              walkedArgs <- args.traverse(walk(scope, _, env))
              applied    <- applyLambda(scope, head, walkedArgs.map(_.info), env)
            } yield applied.andAll(walkedArgs)
          case _                                                               =>
            // A `match`, a `typeMatch`, an applied parameter: the result is unknown in every context.
            (walk(scope, head, env) +: args.map(walk(scope, _, env))).sequence
              .map(walked => Walked.leaf(Info.unknown).andAll(walked))
        }
    }

  /** What is known of a function value written in `env`: no meta of its own, but whatever a callee applying it may read
    * — symbolic, and dependent on the context's positions, as the parameters it captures are.
    */
  private def captured(node: Sourced[MonomorphicExpression], env: Env): Info = {
    val infos = freeParameters(node.value.expression).toSeq.flatMap(env.get)
    Info(None, infos.exists(_.symbolic), infos.flatMap(_.dependsOn).toSet)
  }

  /** The parameters an expression reads that it does not bind itself. */
  private def freeParameters(expression: MonomorphicExpression.Expression): Set[String] = expression match {
    case MonomorphicExpression.ParameterReference(name)              => Set(name.value)
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      freeParameters(target.value.expression) ++ freeParameters(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(name, _, body)        => freeParameters(body.value.expression) - name.value
    case _                                                           => Set.empty
  }

  /** Walk the body under as many of the lambda's binders as there are arguments, each bound to its argument. An
    * over-application applies the result to further arguments, which is unknown.
    */
  private def applyLambda(
      scope: Scope,
      node: Sourced[MonomorphicExpression],
      arguments: Seq[Info],
      env: Env
  ): CompilerIO[Walked] =
    (node.value.expression, arguments) match {
      case (_, Seq())                                                     => walk(scope, node, env)
      case (MonomorphicExpression.FunctionLiteral(name, _, body), _)      =>
        applyLambda(scope, body, arguments.tail, env.updated(name.value, arguments.head))
      case _                                                              =>
        walk(scope, node, env).map(_.copy(info = Info.unknown, yields = Info.unknown))
    }

  /** What a call demands itself: its callee's `where` and the metas its callee's parameters state. */
  private def ownDemands(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      args: Seq[Walked]
  ): CompilerIO[Walked] =
    for {
      where      <- whereDemand(scope, callNode, callee, calleeTypeArgs, args)
      statements <- statementDemands(scope, callNode, callee, calleeTypeArgs, args)
    } yield Walked.leaf(Info.unknown).andAll(Seq(where, statements))

  /** Demand a callee's `where` precondition at a *full* call: its `^Where` companion, reduced on the compiler track
    * over the arguments' metas, must be `true`. The companion takes every parameter of the callee's signature, handles
    * included, so a call is full when it applies that many arguments; a partial application is a function value whose
    * precondition can never be demanded, and is rejected (`docs/refinement-channel-follow-ups.md` §2.1).
    */
  private def whereDemand(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      args: Seq[Walked]
  ): CompilerIO[Walked] =
    hasWhereCompanion(callee).flatMap {
      case false => Walked.nothing.pure[CompilerIO]
      case true  =>
        MetaInterpreter.companionArity(whereCompanionFqn(callee)).flatMap {
          case arity if arity > 0 && args.size >= arity =>
            demandPrecondition(scope, callNode, callee, calleeTypeArgs, args.take(arity))
          case arity if arity > 0                       =>
            Walked.violating(scope, whereAsValue(callNode, callee)).pure[CompilerIO]
          case _                                        => Walked.nothing.pure[CompilerIO]
        }
    }

  /** Reduce a callee's `^Where` companion over the arguments' metas, at the callee's own type arguments followed by the
    * meta type of each parameter's type ([[MetaInterpreter.companionTypeArguments]]). A parameter whose meta type is
    * `Unit` carries nothing to know and is passed as a placeholder; every other argument's meta must be known, since ⊤
    * cannot discharge a demand. A code argument is read as what its code yields.
    */
  private def demandPrecondition(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      args: Seq[Walked]
  ): CompilerIO[Walked] =
    MetaInterpreter
      .companionTypeArguments(callee, whereCompanionFqn(callee), calleeTypeArgs, args.size, withResult = false)
      .flatMap {
        case None            => Walked.violating(scope, cannotEvaluate(callNode, callee)).pure[CompilerIO]
        case Some(metaTypes) =>
          val read =
            metaTypes.zip(args).map((metaType, arg) => Option.when(metaType != MetaInterpreter.unitType)(arg.yields))
          decide(scope, read.flatten) { dependsOn =>
            read.traverse {
              case None       => Some(SemValue.VType)
              case Some(info) => info.meta.map(Evaluator.groundToSem)
            } match {
              case None            =>
                Seq(
                  Violation(
                    callNode.as(
                      s"Cannot prove the precondition of '${callee.show}': an argument's meta-information is not known here."
                    ),
                    Seq(
                      "A `where` precondition demands provable meta-information — pass a value the compiler can pin, such as a literal."
                    ),
                    dependsOn
                  )
                ).pure[CompilerIO]
              case Some(arguments) =>
                EscalatingReducer
                  .reduceApplied(whereCompanionFqn(callee), calleeTypeArgs ++ metaTypes, arguments)
                  .map {
                    case Some(GroundValue.Direct(Literal.BooleanValue(true), _))  => Seq.empty
                    case Some(GroundValue.Direct(Literal.BooleanValue(false), _)) =>
                      Seq(
                        Violation(
                          callNode.as(
                            s"The precondition of '${callee.show}' is not satisfied by the argument's meta-information."
                          ),
                          Seq.empty,
                          dependsOn
                        )
                      )
                    case _                                                        =>
                      Seq(cannotEvaluate(callNode, callee).copy(dependsOn = dependsOn))
                  }
            }
          }
      }

  /** Demand what a callee's parameters state at a *full* call (`docs/total-meta-transfers.md` §8.9): each braced
    * parameter's argument must have a known meta that fits the stated one ([[MetaInterpreter.fits]]). A partial
    * application is rejected as a bare reference is.
    */
  private def statementDemands(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      args: Seq[Walked]
  ): CompilerIO[Walked] =
    MetaInterpreter.parameterStatements(callee).flatMap {
      case statements if statements.exists(_.arity > args.size) =>
        Walked.violating(scope, statementsAsValue(callNode, callee)).pure[CompilerIO]
      case statements                                          =>
        statements
          .traverse(statement =>
            demandStatement(scope, callNode, callee, calleeTypeArgs, statement, args(statement.position))
          )
          .map(Walked.nothing.andAll)
    }

  private def demandStatement(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      statement: MetaInterpreter.Statement,
      arg: Walked
  ): CompilerIO[Walked] = {
    val parameter = s"the parameter '${statement.parameter}' of '${callee.show}'"
    decide(scope, Seq(arg.info)) { dependsOn =>
      MetaInterpreter.statedMeta(callee, calleeTypeArgs, statement).flatMap {
        case MetaInterpreter.Stated.Meta(stated, metaType) =>
          arg.info.meta match {
            case Some(actual) =>
              MetaInterpreter
                .fits(actual, stated, metaType)
                .map(fits =>
                  Seq(
                    Violation(
                      callNode.as(
                        s"The argument's meta-information ${GroundValueRenderer.render(actual)} does not fit " +
                          s"${GroundValueRenderer.render(stated)}, which $parameter states."
                      ),
                      Seq.empty,
                      dependsOn
                    )
                  ).filterNot(_ => fits)
                )
            case None         =>
              Seq(
                Violation(
                  callNode.as(s"Cannot prove what $parameter states: the argument's meta-information is not known here."),
                  Seq(
                    "A stated parameter demands provable meta-information — pass a value the compiler can pin, such as a literal."
                  ),
                  dependsOn
                )
              ).pure[CompilerIO]
          }
        case MetaInterpreter.Stated.Untracked(parameterType) =>
          Seq(
            Violation(
              callNode.as(
                s"${parameter.capitalize} states meta-information, but its type " +
                  s"'${GroundValueRenderer.render(parameterType)}' carries none; remove the { ... } brace."
              ),
              Seq.empty,
              Seq.empty
            )
          ).pure[CompilerIO]
        case MetaInterpreter.Stated.Unknown                  =>
          Seq(
            Violation(callNode.as(s"Cannot evaluate the meta-information stated by $parameter."), Seq.empty, Seq.empty)
          ).pure[CompilerIO]
      }
    }
  }

  /** The common shape of a demand over the metas it reads: deferred when one is symbolic, decided by `evaluate`
    * otherwise — except that a context other than the root skips a demand depending on nothing it supplies, which the
    * root context decides identically and reports.
    */
  private def decide(scope: Scope, read: Seq[Info])(
      evaluate: Seq[Int] => CompilerIO[Seq[Violation]]
  ): CompilerIO[Walked] =
    if (read.exists(_.symbolic)) Walked.nothing.copy(deferred = true).pure[CompilerIO]
    else {
      val dependsOn = read.flatMap(_.dependsOn).toSet
      if (!scope.isRoot && dependsOn.isEmpty) Walked.nothing.pure[CompilerIO]
      else evaluate(dependsOn.toSeq.sorted).map(violations => Walked.nothing.copy(violations = violations))
    }

  /** The demands inside a bodied callee's body, in the context this call makes: the callee's parameters as its
    * arguments are known, those it does not apply unknown. Asked only of a callee whose root context defers — any other
    * decides every demand of its body the same in every context, and reports it there.
    *
    * What that context violates is lifted to this call ([[lift]]). A context identical to the callee's root is this
    * body's own parameters passed straight through, so it defers here too.
    */
  private def internalDemands(
      scope: Scope,
      callNode: Sourced[MonomorphicExpression],
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      args: Seq[Info]
  ): CompilerIO[Walked] =
    getFactIfProduced(MonomorphicValue.Key(callee, calleeTypeArgs)).flatMap {
      case Some(calleeValue) if calleeValue.naturalArity.exists(_ > 0) =>
        MetaDemands.rootOf(calleeValue).flatMap { root =>
          getFactIfProduced(root).flatMap {
            case Some(rootDemands) if rootDemands.deferred =>
              val context = root.context.zipWithIndex.map {
                case (Knowledge.Unknown, _) => Knowledge.Unknown
                case (_, position)          => args.lift(position).fold(Knowledge.Unknown)(_.knowledge)
              }
              if (context == root.context) Walked.nothing.copy(deferred = true).pure[CompilerIO]
              else
                getFactIfProduced(MetaDemands.Key(callee, calleeTypeArgs, context)).map {
                  case Some(demands) =>
                    Walked.nothing.copy(
                      deferred = demands.deferred,
                      violations = scope.keep(
                        demands.violations.map(lift(callNode, calleeValue, root.context, context, args, _))
                      )
                    )
                  case None          => Walked.nothing
                }
            case _                                         => Walked.nothing.pure[CompilerIO]
          }
        }
      case _                                                           => Walked.nothing.pure[CompilerIO]
    }

  /** A violation of a callee's context, reported at this call instead: where the demand sits and what this call
    * supplied are added to its description, and what it depends on is read through this call's arguments.
    */
  private def lift(
      callNode: Sourced[MonomorphicExpression],
      callee: MonomorphicValue,
      root: Seq[Knowledge],
      context: Seq[Knowledge],
      args: Seq[Info],
      violation: Violation
  ): Violation = {
    val supplied = parameterNames(callee).zip(root).zip(context).collect {
      case ((name, Knowledge.Symbolic), Knowledge.Known(meta)) => s"'$name' is ${GroundValueRenderer.render(meta)}"
      case ((name, Knowledge.Symbolic), Knowledge.Unknown)     => s"'$name' is not known"
    }
    val where    = if (supplied.isEmpty) "" else s", where ${supplied.mkString(", ")}"
    Violation(
      callNode.as(violation.at.value),
      violation.description :+ s"In '${callee.vfqn.show}' at ${location(violation.at)}$where.",
      violation.dependsOn.flatMap(position => args.lift(position).fold(Set.empty)(_.dependsOn)).distinct.sorted
    )
  }

  /** The names of a value's leading parameters, handles included. */
  private def parameterNames(value: MonomorphicValue): Seq[String] = {
    @tailrec
    def loop(expression: MonomorphicExpression.Expression, names: Seq[String]): Seq[String] = expression match {
      case MonomorphicExpression.FunctionLiteral(name, _, body) => loop(body.value.expression, names :+ name.value)
      case _                                                    => names
    }
    value.runtime.fold(Seq.empty)(body => loop(body.value, Seq.empty))
  }

  private def location(at: Sourced[?]): String = {
    val path = Option(at.uri.getPath).getOrElse(at.uri.toString)
    s"${path.substring(path.lastIndexOf('/') + 1)}:${at.range.from.line}:${at.range.from.col}"
  }

  private def hasWhereCompanion(callee: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler))
      .map(_.names.contains(RefinementChannelProcessor.whereCompanionName(callee)))

  private def whereCompanionFqn(callee: ValueFQN): ValueFQN = RefinementChannelProcessor.whereCompanionFqn(callee)

  /** A reference to a def with a `where` or a parameter brace that is not the head of a full call — a bare value or a
    * partial application — is a use whose demand can never be made.
    */
  private def rejectedAsValue(
      scope: Scope,
      node: Sourced[MonomorphicExpression],
      callee: ValueFQN
  ): CompilerIO[Seq[Violation]] =
    for {
      where      <- hasWhereCompanion(callee)
      statements <- MetaInterpreter.parameterStatements(callee)
    } yield scope.keep(
      Seq(whereAsValue(node, callee)).filter(_ => where) ++
        Seq(statementsAsValue(node, callee)).filter(_ => statements.nonEmpty)
    )

  private def whereAsValue(node: Sourced[MonomorphicExpression], callee: ValueFQN): Violation =
    Violation(
      node.as(s"A def with a `where` precondition ('${callee.show}') cannot be passed as a value."),
      Seq("Call it directly with all its arguments so its precondition is checked at the use site."),
      Seq.empty
    )

  private def statementsAsValue(node: Sourced[MonomorphicExpression], callee: ValueFQN): Violation =
    Violation(
      node.as(s"A def stating meta-information on a parameter ('${callee.show}') cannot be passed as a value."),
      Seq("Call it directly with all its arguments so what it states is checked at the use site."),
      Seq.empty
    )

  private def cannotEvaluate(callNode: Sourced[MonomorphicExpression], callee: ValueFQN): Violation =
    Violation(callNode.as(s"Cannot evaluate the `where` precondition of '${callee.show}'."), Seq.empty, Seq.empty)
}

object MetaDemandsProcessor {

  /** What the walk knows of the parameters in scope, by name. */
  private type Env = Map[String, Info]

  /** Whether the walked context is its value's root, which decides and reports every demand not deferred. */
  private case class Scope(isRoot: Boolean) {

    /** What a value read from the parameter at `position` depends on: nothing in a root context. */
    def supplied(position: Int): Set[Int] = if (isRoot) Set.empty else Set(position)

    /** The violations this context reports or returns: a context other than the root keeps only those depending on
      * what it supplies.
      */
    def keep(violations: Seq[Violation]): Seq[Violation] =
      if (isRoot) violations else violations.filter(_.dependsOn.nonEmpty)
  }

  /** What is known of one value's meta: the meta itself, whether it is unknown because a symbolic parameter decides it,
    * and which of the context's parameter positions it derives from.
    */
  private case class Info(meta: Option[GroundValue], symbolic: Boolean, dependsOn: Set[Int]) {
    def knowledge: Knowledge =
      if (symbolic) Knowledge.Symbolic else meta.fold(Knowledge.Unknown)(Knowledge.Known(_))
  }

  private object Info {
    val unknown: Info = Info(None, symbolic = false, Set.empty)

    def of(meta: Option[GroundValue]): Info = Info(meta, symbolic = false, Set.empty)

    /** A call's result: known metas carry what their arguments depend on; an unknown one is symbolic when an argument
      * is, since a context that knows that argument may know it.
      */
    def derived(meta: Option[GroundValue], args: Seq[Info]): Info =
      Info(meta, meta.isEmpty && args.exists(_.symbolic), args.flatMap(_.dependsOn).toSet)
  }

  /** One walked node: what is known of its meta, of what it yields as code (its innermost lambda body's — itself for a
    * node that is not a lambda), and what the demands under it came to.
    */
  private case class Walked(info: Info, yields: Info, deferred: Boolean, violations: Seq[Violation]) {
    def andAll(others: Seq[Walked]): Walked =
      copy(deferred = deferred || others.exists(_.deferred), violations = violations ++ others.flatMap(_.violations))
  }

  private object Walked {
    val nothing: Walked = leaf(Info.unknown)

    def leaf(info: Info): Walked = Walked(info, info, deferred = false, Seq.empty)

    def violating(scope: Scope, violation: Violation): Walked = nothing.copy(violations = scope.keep(Seq(violation)))
  }
}
