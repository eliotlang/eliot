package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.Expression
import com.vanillasource.eliot.eliotc.core.processor.{MetaCompanionLayout, MetaConstructorDesugarer, MetaParameterDesugarer}
import com.vanillasource.eliot.eliotc.module.fact.{
  ModuleName,
  QualifiedName,
  Qualifier,
  UnifiedModuleNames,
  UnifiedModuleValue,
  ValueFQN,
  WellKnownTypes
}
import com.vanillasource.eliot.eliotc.monomorphize.domain.SemValue
import com.vanillasource.eliot.eliotc.monomorphize.eval.Evaluator
import com.vanillasource.eliot.eliotc.monomorphize.fact.{
  CompilerMonomorphicValue,
  GroundValue,
  MonomorphicExpression,
  MonomorphicValue
}
import com.vanillasource.eliot.eliotc.monomorphize.processor.EscalatingReducer
import com.vanillasource.eliot.eliotc.platform.Platform
import com.vanillasource.eliot.eliotc.processor.CompilerIO.*
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.annotation.tailrec

/** **The meta interpretation** (`docs/total-meta-transfers.md` §6.2 / P4) — the answer to the one question *"what is
  * this value's meta-information?"*, and the place the closure invariant of §2 is actually executed:
  *
  *   - a **leaf states** its transfer, so its meta is its `^Meta` companion reduced on the argument metas;
  *   - **everything else derives**, so its meta is obtained by *interpreting its own body* with its parameters bound to
  *     the caller's argument metas.
  *
  * Those are R2 and R3 read as a definition rather than as a prohibition, which is why [[metaOf]] is a two-armed `ifM`
  * on exactly the test [[MetaTransferAccountingProcessor]] enforces. Before this existed only the first arm did: a call
  * to a bodied callee was a ⊤ boundary, so precision was a property of *where code sits relative to a native* rather
  * than of what the code *is* — `def label(s: String): String = s` lost its argument's size, and `String::show`
  * (bodied, therefore forbidden to state) derived nothing at all.
  *
  * **This is Option B of §6, not Option A.** No shadow twin program is emitted: the interpretation walks the very
  * monomorphized body the representation pass will stamp, so node correspondence is free. The stated transfers it
  * bottoms out at stay ordinary Eliot reduced by the one NbE evaluator ([[EscalatingReducer]]), so the single-evaluator
  * cornerstone is intact — this adds a *walk*, not a second evaluator.
  *
  * **What it computes and what it does not.** This object answers only *what the meta is*. It records nothing, demands
  * no `where`, and reports no diagnostic: which nodes may be *stamped* with a meta is representation policy and stays
  * with [[RefinementChannelProcessor]]'s walk, whose records are keyed by source position **in the value being
  * walked**. A derived meta is therefore consumed at the **call node in the caller** and nowhere else — the callee's
  * own body is laid out once, conservatively, from its own table at ⊤ parameters (§7: "get that wrong and it arrives as
  * a `ClassCastException`, not a type error"). Interpreting a callee's body here never records into its table and never
  * demands its preconditions: those are checked per context by [[MetaDemandsProcessor]], which asks this object what
  * each argument's meta is.
  *
  * **Termination is the recursion gate's** (§2.3): user code cannot express recursion, so the derivation walks a DAG
  * and needs no fixpoint, no widening and no ascending-chain condition. The [[interpretationBudget]] is therefore not
  * what makes this terminate — it is the §8.4 fail-safe against *cost*: a diamond-shaped call DAG re-interpreted per
  * path is exponential even though each path is acyclic. The budget is a step count threaded through the whole
  * derivation of one call node, and exhausting it yields **absence**, which is sound ("I know nothing" is always true)
  * and never a wrong answer.
  *
  * **Higher-order through closures** (§6.2). A function value is a [[Value.Closure]] — the function-typed node and the
  * environment it was written in — and is interpreted only when something applies it: a callee applying one of its own
  * parameters (`def applyTo(f: Int => Int, x: Int): Int = f(x)`), an over-applied callee whose body returns a lambda,
  * or a pure `val` block, which lowers to an applied lambda (`(x -> rest)(e)`). A **stated** transfer cannot apply a
  * closure — a companion is Eliot over metas — so a code argument reaches it as what its code **yields**, the same
  * reading its payload signature gives a code parameter's meta type: `fold(c, 10, 20)` joins `[10, 10]` and `[20, 20]`.
  * A handler a native runs is applied to what the native **states** it hands it ([[handedMetas]]) — the `match`
  * eliminator's braced fields — and to ⊤ elsewhere, so a native applying a function argument with nothing stated
  * (`foldLeft`) reaches what it hands only as ⊤.
  */
object MetaInterpreter {

  /** What the interpretation knows of one value. */
  enum Value {

    /** Its meta, or ⊤ (`None`) — always ⊤ for a value whose type carries no meta. */
    case Meta(meta: Option[GroundValue])

    /** A function value, deferred until applied: a function-typed `node` (a lambda, a partial application, a reference
      * to a def) and the `env` it was written in, which is what the parameters it captures read.
      */
    case Closure(node: Sourced[MonomorphicExpression], env: Env)
  }

  object Value {
    val unknown: Value = Meta(None)
  }

  /** What the interpretation knows about the parameters in scope: each bound to the caller's argument, ⊤ when the caller
    * could not pin one. Keyed by name because [[MonomorphicExpression.ParameterReference]] is, which makes *shadowing*
    * this map's one obligation: a binder that is not being applied must not leave an outer binding of the same name
    * readable in its body (see [[bindParameters]], the only place bindings are added).
    */
  type Env = Map[String, Value]

  /** The §8.4 cost fail-safe: the number of interpretation steps one call node's derivation may spend, across depth
    * *and* fan-out together (each node visited costs one, and the budget is threaded through siblings rather than
    * reset). Exhaustion yields absence, never a wrong answer, so the only thing a small budget costs is precision on a
    * deep call DAG. Memoization on `(vfqn, typeArgs, argMetas)` (§6.2) is the precision follow-on if this is ever
    * observed to bind in practice; a hard step count is the stronger guarantee of the two and is what must be here.
    */
  private val interpretationBudget: Int = 256

  /** The meta of an ordinary call: the callee **states** its transfer (a native leaf — reduce the companion), or it
    * **derives** one from its body (everything else — interpret it). `resultType` is the *call node's* own ground type,
    * which is what decides whether there is anything to derive at all: an untracked type's meta is the trivial `Unit`
    * (§2.3, meta is total on types but not structural), so a call returning one is skipped before any body is fetched.
    * That gate is what keeps the interpretation off the overwhelming majority of call nodes in a program.
    */
  def metaOfCall(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value]
  ): CompilerIO[Option[GroundValue]] =
    metaOf(callee, typeArguments, resultType, arguments, interpretationBudget).map((_, value) => metaOfValue(value))

  /** What a walk hands the interpretation for an argument it walked: a function-typed `node` is a closure over
    * `captured`, what the walk knows of the parameters in scope; any other node is the `meta` the walk computed for it.
    */
  def argument(
      node: Sourced[MonomorphicExpression],
      meta: Option[GroundValue],
      captured: Map[String, Option[GroundValue]]
  ): Value =
    if (isFunction(node)) Value.Closure(node, captured.view.mapValues(Value.Meta(_)).toMap) else Value.Meta(meta)

  /** The α seed of the `Int` domain: a literal `n`'s singleton range, built in Eliot by
    * `eliot.lang.Runtime::integerLiteral`'s `{closed(V, V)}` brace and reduced here through the *same* stated-transfer
    * path as every other leaf. The interpretation builds no domain structure of its own; it only wraps `n` as the
    * `BigInteger` value to reduce the companion at.
    */
  def integerSeed(value: BigInt): CompilerIO[Option[GroundValue]] =
    reduceTransfer(WellKnownTypes.integerLiteralFQN, Seq(GroundValue.Direct(value, bigIntType)), Seq.empty)

  /** The α seed of the `String` domain (`docs/string-length-meta.md` §3.1): a literal's singleton size, counted in
    * **code points** — what `String::length` counts on every target — through the same path as the integer seed.
    */
  def stringSeed(text: String): CompilerIO[Option[GroundValue]] =
    reduceTransfer(
      WellKnownTypes.stringLiteralFQN,
      Seq(GroundValue.Direct(BigInt(text.codePointCount(0, text.length)), bigIntType)),
      Seq.empty
    )

  /** Whether a concrete type declares meta-information — i.e. its module (in the compiler pool, where
    * [[MetaConstructorDesugarer]] emits meta structures) declares its `T$Meta` structure. A slotless type declares
    * none, so its meta is the trivial `Unit`. The one membership test three readers share: this object's [[metaTypeOf]]
    * and its derivation gate, and [[MetaTransferAccountingProcessor]]'s R2.
    */
  def declaresMetaStructure(typeName: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(typeName.moduleName, Platform.Compiler))
      .map(_.names.contains(metaStructureName(typeName)))

  /** A parameter's **statement** (`docs/total-meta-transfers.md` §8.9): the companion stating the meta of the value at
    * parameter `parameter`, which is the `position`th of the `arity` its definition takes, handles included.
    */
  case class Statement(companion: ValueFQN, parameter: String, position: Int, arity: Int)

  /** What a parameter's statement states at a callee's type arguments: a meta, or why there is none. */
  enum Stated {

    /** The stated meta, of meta type `metaType`. */
    case Meta(value: GroundValue, metaType: GroundValue)

    /** The parameter's type, `parameterType` once evaluated, carries no meta, so there is no constructor to apply the
      * brace's slots to.
      */
    case Untracked(parameterType: GroundValue)

    /** The statement did not reduce to a meta. */
    case Unknown
  }

  /** The parameters of `callee` that carry a meta brace, in parameter order — each found as its statement companion in
    * the callee's module ([[MetaParameterDesugarer.statedParameter]]), and placed by that companion's own placeholders,
    * so a native, which has no body to name its parameters, is read the same way as a bodied value.
    */
  def parameterStatements(callee: ValueFQN): CompilerIO[Seq[Statement]] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler)).flatMap { names =>
      names.names.keys.toSeq
        .flatMap(name => MetaParameterDesugarer.statedParameter(callee.name, name).map(ValueFQN(callee.moduleName, name) -> _))
        .traverse { (companion, parameter) =>
          companionParameters(companion).map { names =>
            val placeholders = names.filter(_ =!= MetaCompanionLayout.constructorParameterName)
            Statement(companion, parameter, placeholders.indexOf(MetaCompanionLayout.placeholderName(parameter)), placeholders.size)
          }
        }
        .map(_.sortBy(_.position))
    }

  /** What `statement` states at the callee's type arguments. The companion is reduced at those, the meta types of the
    * callee's parameters and the type of the stated parameter's meta constructor, which it takes first — as a
    * slot-producing transfer does ([[reduceTransfer]]). It reads no parameter, so each is passed as a placeholder.
    */
  def statedMeta(callee: ValueFQN, calleeTypeArgs: Seq[GroundValue], statement: Statement): CompilerIO[Stated] =
    payloadTypes(callee, calleeTypeArgs, statement.arity).flatMap {
      case Some((parameters, _)) =>
        parameters.traverse(metaTypeOf).flatMap { metaTypes =>
          metaConstructorOf(metaTypes(statement.position)).flatMap {
            case Some(constructor) =>
              EscalatingReducer
                .reduceApplied(
                  statement.companion,
                  calleeTypeArgs ++ metaTypes :+ constructor.valueType,
                  Evaluator.groundToSem(constructor) +: Seq.fill(statement.arity)(SemValue.VType)
                )
                .map {
                  case Some(meta: GroundValue.Structure) => Stated.Meta(meta, metaTypes(statement.position))
                  case _                                 => Stated.Unknown
                }
            case None              => (Stated.Untracked(parameters(statement.position)): Stated).pure[CompilerIO]
          }
        }
      case None                  => (Stated.Unknown: Stated).pure[CompilerIO]
    }

  /** What a body-less `callee` hands the code argument at parameter `position` when it runs it: per argument of that
    * code's `functionType`, the meta a **handed** statement states of it, or ⊤ where none does
    * ([[MetaParameterDesugarer.handedStatement]], `docs/total-meta-transfers.md` §8.10). The supplier is the callee, which
    * the compiler cannot see, so the statement is axiomatic — the `match` eliminator's, for a braced field, holds because
    * the constructor proved it. Each is reduced as a parameter statement is, plus the meta type of the argument it states,
    * read off `functionType`, so a generic field is stated at its instantiation.
    */
  def handedMetas(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      position: Int,
      functionType: GroundValue
  ): CompilerIO[Seq[Option[GroundValue]]] = {
    val domains = peel(functionType, functionType.functionArity).map(_._1).getOrElse(Seq.empty)
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler)).flatMap { names =>
      names.names.keys.toSeq
        .flatMap(name => MetaParameterDesugarer.handedArgument(callee.name, name).map(ValueFQN(callee.moduleName, name) -> _))
        .flatTraverse { case (companion, (parameter, argument)) =>
          companionParameters(companion).map { names =>
            val placeholders = names.filter(_ =!= MetaCompanionLayout.constructorParameterName)
            Option
              .when(placeholders.indexOf(MetaCompanionLayout.placeholderName(parameter)) === position)(
                (companion, argument, placeholders.size)
              )
              .toSeq
          }
        }
        .flatMap { handed =>
          domains.indices.toList.traverse { argument =>
            handed.find(_._2 === argument) match {
              case Some((companion, _, arity)) => handedMeta(callee, calleeTypeArgs, companion, arity, domains(argument))
              case None                        => none[GroundValue].pure[CompilerIO]
            }
          }.map(_.toSeq)
        }
    }
  }

  private def handedMeta(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      companion: ValueFQN,
      arity: Int,
      argumentType: GroundValue
  ): CompilerIO[Option[GroundValue]] =
    payloadTypes(callee, calleeTypeArgs, arity).flatMap {
      case Some((parameters, _)) =>
        (parameters.traverse(metaTypeOf), metaTypeOf(argumentType)).flatMapN { (metaTypes, handedType) =>
          metaConstructorOf(handedType).flatMap {
            case Some(constructor) =>
              EscalatingReducer
                .reduceApplied(
                  companion,
                  calleeTypeArgs ++ metaTypes :+ handedType :+ constructor.valueType,
                  Evaluator.groundToSem(constructor) +: Seq.fill(arity)(SemValue.VType)
                )
                .map(_.collect { case meta: GroundValue.Structure => meta })
            case None              => none[GroundValue].pure[CompilerIO]
          }
        }
      case None                  => none[GroundValue].pure[CompilerIO]
    }

  /** Whether a value of meta `actual` fits where meta `stated` is stated: joining it into `stated` leaves `stated`, the
    * structural comparison the reconcile pass makes. The join is the meta type's own `Meta` instance, reached through
    * `eliot.compiler.Meta::joined` at that type by the one evaluator. Anything that does not reduce does not fit.
    */
  def fits(actual: GroundValue, stated: GroundValue, metaType: GroundValue): CompilerIO[Boolean] =
    EscalatingReducer
      .reduceApplied(WellKnownTypes.metaJoinedFQN, Seq(metaType), Seq(actual, stated).map(Evaluator.groundToSem))
      .map(_.contains(stated))

  /** Flatten a curried application into its ultimate head and its arguments in source order. */
  private[channel] def flatten(
      node: Sourced[MonomorphicExpression]
  ): (Sourced[MonomorphicExpression], Seq[Sourced[MonomorphicExpression]]) =
    node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        val (head, args) = flatten(target)
        (head, args :+ argument)
      case _                                                           => (node, Seq.empty)
    }

  /** The closure invariant of §2, executed: **state or derive**, split on the presence of a `^Meta` companion — the
    * same test [[MetaTransferAccountingProcessor]] enforces from the other side, so the two cannot drift into
    * disagreeing about which values summarise and which are read.
    */
  private def metaOf(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      budget: Int
  ): CompilerIO[(Int, Value)] =
    declaresTransfer(callee).ifM(
      stated(callee, typeArguments, arguments, budget),
      derive(callee, typeArguments, resultType, arguments, budget)
    )

  /** A stated transfer over the call's arguments. A companion is Eliot over metas and cannot apply a closure, so a
    * function-typed argument is passed as what it **yields** ([[yieldOf]]) where the companion reads a meta for it — a
    * code parameter, whose payload type is what its code yields — and as a placeholder where it reads none.
    */
  private def stated(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      arguments: Seq[Value],
      budget: Int
  ): CompilerIO[(Int, Value)] =
    companionTypeArguments(callee, RefinementChannelProcessor.metaCompanionFqn(callee), typeArguments, arguments.size, withResult = false)
      .map(_.getOrElse(Seq.empty))
      .flatMap { metaTypes =>
        arguments.zipWithIndex.foldLeftM((budget, Seq.empty[Option[GroundValue]])) { case ((left, metas), (argument, index)) =>
          argument match {
            case Value.Meta(meta)                                                => (left, metas :+ meta).pure[CompilerIO]
            case closure: Value.Closure if metaTypes.lift(index).exists(_ != unitType) =>
              handedMetas(callee, typeArguments, index, closure.node.value.expressionType)
                .flatMap(handed => yieldOf(closure, handed, left))
                .map((remaining, meta) => (remaining, metas :+ meta))
            case _: Value.Closure                                                => (left, metas :+ None).pure[CompilerIO]
          }
        }
      }
      .flatMap((left, metas) => reduceTransfer(callee, typeArguments, metas).map(meta => (left, Value.Meta(meta))))

  /** What a function value yields: its result once applied to as many arguments as its type takes — a code argument's
    * thunk or handle block applied to the unit or handles it is run with, which carry no meta, or a handler applied to
    * what its callee states it hands it (`handed`, ⊤ where nothing is stated).
    */
  private def yieldOf(
      closure: Value.Closure,
      handed: Seq[Option[GroundValue]],
      budget: Int
  ): CompilerIO[(Int, Option[GroundValue])] = {
    val functionType = closure.node.value.expressionType
    val arguments    = Seq.tabulate(functionType.functionArity)(index => Value.Meta(handed.lift(index).flatten))
    applyValue(closure, arguments, functionType.deepReturnType, budget).map((left, value) => (left, metaOfValue(value)))
  }

  /** Derive a bodied callee's meta by interpreting its body under the caller's arguments. Three things make this
    * decline (soundly, to ⊤) rather than answer:
    *
    *   - the **result carries no meta** — the cheap gate, taken before any fact is read. A function-typed result never
    *     reaches here: a function value is deferred as a closure and only ever asked for applied;
    *   - the callee has **no body on this track** — a leaf that states nothing, which R2 has already made a compile
    *     error for a meta-carrying return, or a value whose monomorphization failed upstream;
    *   - the call is **not saturated** — a partial application is a function value, whose type carries no meta.
    *
    * An over-application applies the callee's *result* to the remaining arguments, which is a closure its body returned.
    */
  private def derive(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      budget: Int
  ): CompilerIO[(Int, Value)] =
    if (budget <= 0) unknown(0)
    else
      carriesMeta(resultType).ifM(
        getFactIfProduced(MonomorphicValue.Key(callee, typeArguments)).flatMap {
          case Some(mv) if mv.naturalArity.exists(_ <= arguments.size) =>
            mv.runtime match {
              case Some(body) =>
                applyLambda(body.as(MonomorphicExpression(mv.signature, body.value)), Map.empty, arguments, resultType, budget - 1)
              case None       => unknown(budget)
            }
          case _                                                       => unknown(budget)
        },
        unknown(budget)
      )

  /** Interpret one node of a body: what is known of its value, and the budget left after computing it. Bottom-up and
    * pure — no record is kept and no diagnostic is raised, so this may be run over a *callee's* body without touching
    * that callee's own table or repeating its checks. A function-typed node is not interpreted here but deferred as a
    * closure, so only a function value that is applied costs anything.
    */
  private def interpret(
      node: Sourced[MonomorphicExpression],
      env: Env,
      budget: Int
  ): CompilerIO[(Int, Value)] =
    if (budget <= 0) unknown(0)
    else
      node.value.expression match {
        case MonomorphicExpression.ParameterReference(parameterName)   =>
          // The one point the caller's knowledge enters: a bound parameter *is* the argument. An unbound one (a lambda's
          // own binder that nothing applied) is ⊤.
          (budget - 1, env.getOrElse(parameterName.value, Value.unknown)).pure[CompilerIO]
        case _ if isFunction(node)                                     =>
          (budget - 1, Value.Closure(node, env)).pure[CompilerIO]
        case MonomorphicExpression.IntegerLiteral(value)               =>
          integerSeed(value.value).map(meta => (budget - 1, Value.Meta(meta)))
        case MonomorphicExpression.StringLiteral(value)                =>
          stringSeed(value.value).map(meta => (budget - 1, Value.Meta(meta)))
        case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
          // A bare reference is a *saturated* call when the callee takes no arguments, so a nullary def derives here
          // exactly as an applied one does below.
          metaOf(vfqn.value, ta, node.value.expressionType, Seq.empty, budget - 1)
        case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
          (budget - 1, Value.Closure(node, env)).pure[CompilerIO]
        case _: MonomorphicExpression.FunctionApplication              =>
          val (head, args) = flatten(node)
          interpretAll(args, env, budget - 1).flatMap((left, arguments) =>
            applyHead(head, env, arguments, node.value.expressionType, left)
          )
      }

  /** Apply the head of an application, written in `env`, to `arguments`: a def is called, a lambda binds its binders, and
    * a parameter is applied as whatever it is bound to. `resultType` is the type of the whole application.
    */
  private def applyHead(
      head: Sourced[MonomorphicExpression],
      env: Env,
      arguments: Seq[Value],
      resultType: GroundValue,
      budget: Int
  ): CompilerIO[(Int, Value)] =
    head.value.expression match {
      case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
        metaOf(vfqn.value, ta, resultType, arguments, budget)
      case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
        applyLambda(head, env, arguments, resultType, budget)
      case MonomorphicExpression.ParameterReference(parameterName)   =>
        applyValue(env.getOrElse(parameterName.value, Value.unknown), arguments, resultType, budget)
      case _                                                         =>
        unknown(budget)
    }

  /** Apply a body written in `env` to `arguments`: its leading lambdas bind as many of them as they can, what is under
    * them is interpreted, and its value is applied to any arguments left over. A body taking no parameters is simply
    * interpreted.
    */
  private def applyLambda(
      node: Sourced[MonomorphicExpression],
      env: Env,
      arguments: Seq[Value],
      resultType: GroundValue,
      budget: Int
  ): CompilerIO[(Int, Value)] = {
    val (body, bound, rest) = bindParameters(node, arguments)
    interpret(body, env ++ bound, budget).flatMap((left, value) =>
      if (rest.isEmpty) (left, value).pure[CompilerIO] else applyValue(value, rest, resultType, left)
    )
  }

  /** Apply a value to `arguments`: a closure is its node applied in its own environment, which also appends to a
    * partial application's arguments; anything else is not known to be a function, so ⊤.
    */
  private def applyValue(
      value: Value,
      arguments: Seq[Value],
      resultType: GroundValue,
      budget: Int
  ): CompilerIO[(Int, Value)] =
    value match {
      case Value.Closure(node, env) if budget > 0 =>
        val (head, applied) = flatten(node)
        interpretAll(applied, env, budget - 1).flatMap((left, values) =>
          applyHead(head, env, values ++ arguments, resultType, left)
        )
      case _                                      => unknown(budget)
    }

  /** Interpret every argument in the *caller's* scope, threading the budget through them, so a wide fan-out spends the
    * same budget a deep chain does. Arguments are all interpreted before any binder is added, which is what keeps a
    * binder from shadowing a name a later argument reads.
    */
  private def interpretAll(
      nodes: Seq[Sourced[MonomorphicExpression]],
      env: Env,
      budget: Int
  ): CompilerIO[(Int, Seq[Value])] =
    nodes.foldLeftM((budget, Seq.empty[Value])) { case ((left, values), node) =>
      interpret(node, env, left).map((remaining, value) => (remaining, values :+ value))
    }

  /** Peel one leading [[MonomorphicExpression.FunctionLiteral]] per argument, binding each binder's name to it, and
    * return the node under the last one peeled with the arguments left over — an over-application, which applies that
    * node's value to them.
    *
    * This is the only place [[Env]] grows, which is where shadowing is answered: a binder that *is* applied replaces
    * any outer binding of its name (the `++` at the caller), and a binder that is not applied is never descended into
    * at all.
    */
  @tailrec
  private def bindParameters(
      node: Sourced[MonomorphicExpression],
      arguments: Seq[Value],
      bound: Env = Map.empty
  ): (Sourced[MonomorphicExpression], Env, Seq[Value]) =
    (node.value.expression, arguments) match {
      case (MonomorphicExpression.FunctionLiteral(parameterName, _, body), argument +: rest) =>
        bindParameters(body, rest, bound + (parameterName.value -> argument))
      case _                                                                                 => (node, bound, arguments)
    }

  private def isFunction(node: Sourced[MonomorphicExpression]): Boolean =
    node.value.expressionType.asFunctionType.isDefined

  private def metaOfValue(value: Value): Option[GroundValue] = value match {
    case Value.Meta(meta)       => meta
    case _: Value.Closure       => None
  }

  /** Whether this callee **states** its transfer — a cached [[UnifiedModuleNames]] membership test for its `^Meta`
    * companion in the compiler pool. States ⤳ read the summary; does not ⤳ read the body.
    */
  private def declaresTransfer(callee: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler))
      .map(_.names.contains(RefinementChannelProcessor.metaCompanionName(callee)))

  /** Reduce a **stated** transfer — a `^Meta` companion — on the argument metas, and read back the domain structure it
    * produced. The companion is reduced at [[companionTypeArguments]] — the callee's own type arguments, then the meta
    * types of its parameters and result — so a generic companion's `join`/arithmetic always lands on a real `Meta`
    * instance rather than a stuck non-existent `T$Meta`. A slot-producing companion also takes the meta *constructor* of
    * that result meta type, first ([[MetaCompanionLayout.constructorParameterName]]), at its own type
    * ([[metaConstructorOf]]); a result whose meta type is `Unit` has none, so its transfer is ⊤ — R2 reports a leaf
    * stating slots over such a result ([[MetaTransferAccountingProcessor]]).
    *
    * `None` (⊤) when an input is unknown in a way the companion's body actually projects: an unknown argument is a
    * `SemValue.VType` placeholder the reduction ignores unless it feeds a slot projection, in which case the projection
    * stalls and the result does not quote to a structure. `None` too for a call that does not apply exactly the
    * companion's parameters — a partial application is a function value, whose type carries no meta. Reduction runs
    * through the post-monomorphize linker-executor ([[EscalatingReducer]]), so a transfer routed through an ability
    * instance resolves through that instance's own monomorphization instead of sticking on the abstract ability method.
    */
  private def reduceTransfer(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      argMetas: Seq[Option[GroundValue]]
  ): CompilerIO[Option[GroundValue]] = {
    val companion = RefinementChannelProcessor.metaCompanionFqn(callee)
    val arguments = argMetas.map {
      case Some(gv) => Evaluator.groundToSem(gv) // the argument's own meta value, passed opaquely
      case None     => SemValue.VType            // ⊤ placeholder: an unknown/untracked argument
    }
    def reduce(typeArguments: Seq[GroundValue], leading: Seq[SemValue]): CompilerIO[Option[GroundValue]] =
      EscalatingReducer
        .reduceApplied(companion, typeArguments, leading ++ arguments)
        .map(_.collect { case s: GroundValue.Structure => s })
    companionTypeArguments(callee, companion, calleeTypeArgs, argMetas.size, withResult = true).flatMap {
      case Some(metaTypes) =>
        takesConstructor(companion).ifM(
          metaConstructorOf(metaTypes.last).flatMap {
            case Some(constructor) =>
              reduce(calleeTypeArgs ++ metaTypes :+ constructor.valueType, Seq(Evaluator.groundToSem(constructor)))
            case None              => none[GroundValue].pure[CompilerIO]
          },
          reduce(calleeTypeArgs ++ metaTypes, Seq.empty)
        )
      case None            => none[GroundValue].pure[CompilerIO]
    }
  }

  /** The meta *value* constructor of `metaType`, as a ground reference typed by its own signature (a function from the
    * slots to the meta type) — or `None` for a meta type that declares no structure (`Unit`). It is the value
    * constructor [[MetaConstructorDesugarer]] declares beside the meta type, of the same name.
    */
  private def metaConstructorOf(metaType: GroundValue): CompilerIO[Option[GroundValue.Structure]] = metaType match {
    case GroundValue.Structure(fqn, Seq(), _) if metaType != unitType =>
      val constructor = ValueFQN(fqn.moduleName, QualifiedName(fqn.name.name, Qualifier.Default))
      getFactOrAbort(CompilerMonomorphicValue.Key(constructor, Seq.empty))
        .map(cmv => GroundValue.Structure(constructor, Seq.empty, cmv.signature).some)
    case _                                                            => none[GroundValue.Structure].pure[CompilerIO]
  }

  /** The meta types a companion of `callee` is instantiated at after the callee's own type arguments — one per parameter
    * and, `withResult`, one for the result ([[com.vanillasource.eliot.eliotc.core.processor.MetaCompanionLayout]]) — or
    * `None` when the call does not apply exactly the `companion`'s parameters.
    *
    * The types are read from the callee's **payload signature**, evaluated at its type arguments by the one evaluator: so
    * an alias, a computed type or a type parameter has the meta type of what it denotes, and a code parameter that of the
    * value its code yields. As many arrows are peeled as the companion takes parameters, so a function-typed result stays
    * whole.
    */
  private[channel] def companionTypeArguments(
      callee: ValueFQN,
      companion: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      appliedArgs: Int,
      withResult: Boolean
  ): CompilerIO[Option[Seq[GroundValue]]] =
    companionArity(companion).flatMap {
      case arity if arity === appliedArgs =>
        payloadTypes(callee, calleeTypeArgs, arity).flatMap {
          case Some((parameters, result)) =>
            (if (withResult) parameters :+ result else parameters).traverse(metaTypeOf).map(_.some)
          case None                       => none[Seq[GroundValue]].pure[CompilerIO]
        }
      case _                              => none[Seq[GroundValue]].pure[CompilerIO]
    }

  /** The callee's first `arity` parameter types and its result type, read from its **payload signature** evaluated at
    * its type arguments ([[MetaCompanionLayout]]), or `None` if it does not reduce to that many arrows.
    */
  private def payloadTypes(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      arity: Int
  ): CompilerIO[Option[(Seq[GroundValue], GroundValue)]] =
    EscalatingReducer
      .reduceApplied(ValueFQN(callee.moduleName, MetaCompanionLayout.payloadName(callee.name)), calleeTypeArgs, Seq.empty)
      .map(_.flatMap(peel(_, arity)))

  /** How many of its callee's parameters `companion` takes: its body's leading lambdas, one per parameter as the
    * desugarer writes them, less a meta constructor's. The arrow chain of a signature cannot say, since a function-typed
    * result is an arrow too.
    */
  private[channel] def companionArity(companion: ValueFQN): CompilerIO[Int] =
    companionParameters(companion).map(_.count(_ =!= MetaCompanionLayout.constructorParameterName))

  /** Whether `companion` takes the meta constructor of its result meta type — a slot-producing transfer. */
  private[channel] def takesConstructor(companion: ValueFQN): CompilerIO[Boolean] =
    companionParameters(companion).map(_.headOption.contains(MetaCompanionLayout.constructorParameterName))

  private def companionParameters(companion: ValueFQN): CompilerIO[Seq[String]] =
    getFactOrAbort(UnifiedModuleValue.Key(companion, Platform.Compiler))
      .map(_.namedValue.runtime.fold(Seq.empty)(body => leadingLambdas(body.value)))

  @tailrec
  private def leadingLambdas(expression: Expression, names: Seq[String] = Seq.empty): Seq[String] = expression match {
    case Expression.FunctionLiteral(name, _, body) => leadingLambdas(body.value, names :+ name.value)
    case _                                         => names
  }

  /** The first `count` parameter types of a function type and what remains, or `None` if it has fewer arrows. */
  private def peel(functionType: GroundValue, count: Int): Option[(Seq[GroundValue], GroundValue)] =
    if (count === 0) Some((Seq.empty, functionType))
    else functionType.asFunctionType.flatMap((parameter, rest) => peel(rest, count - 1).map((ps, r) => (parameter +: ps, r)))

  /** The **meta type** of a base type — its `$Meta` meta structure if the type declares one (a slotted type like `Int`
    * ⤳ `Int$Meta`), else the trivial [[unitType]] (an untracked type carries no refinement, so its meta is `Unit`).
    * This is the total-meta rule of R1: a generic `^Meta` companion reduced at these always lands on a real `Meta`
    * instance (`Meta[Int$Meta]` or `Meta[Unit]`). A non-structure type argument is left unchanged.
    */
  private[channel] def metaTypeOf(baseType: GroundValue): CompilerIO[GroundValue] = baseType match {
    case GroundValue.Structure(fqn, _, _) =>
      declaresMetaStructure(fqn).map {
        case true  =>
          GroundValue.Structure(ValueFQN(fqn.moduleName, metaStructureName(fqn)), Seq.empty, GroundValue.Type)
        case false => unitType
      }
    case other                            => other.pure[CompilerIO]
  }

  /** Whether a *node's* type carries meta-information at all — [[metaTypeOf]] read as a predicate, and the derivation's
    * cheap gate. Non-structural by design (§2.3): `List[Int]` and `data Counter(n: Int)` carry none, so meta is lost at
    * every data boundary exactly as it is today.
    */
  private[channel] def carriesMeta(nodeType: GroundValue): CompilerIO[Boolean] = nodeType match {
    case GroundValue.Structure(fqn, _, _) => declaresMetaStructure(fqn)
    case _                                => false.pure[CompilerIO]
  }

  private def metaStructureName(typeName: ValueFQN): QualifiedName =
    QualifiedName(typeName.name.name + MetaConstructorDesugarer.metaTypeSuffix, Qualifier.Type)

  private def unknown(budget: Int): CompilerIO[(Int, Value)] =
    (budget, Value.unknown).pure[CompilerIO]

  /** The `BigInteger` type a literal's seed value carries — the only literal-domain constant the interpretation needs,
    * to wrap a raw `n` as a [[GroundValue.Direct]] when reducing a seed companion at it.
    */
  private val bigIntType: GroundValue =
    GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  private val unitModule: ModuleName = ModuleName(ModuleName.defaultSystemPackage, "Unit")

  /** `Unit` — the meta type of every untracked (slotless) type. Its trivial `Meta[Unit]` instance is the do-nothing
    * join, so a merge over untracked arms reduces cleanly to no refinement (⊤).
    */
  private[channel] val unitType: GroundValue =
    GroundValue.Structure(ValueFQN(unitModule, QualifiedName("Unit", Qualifier.Type)), Seq.empty, GroundValue.Type)
}
