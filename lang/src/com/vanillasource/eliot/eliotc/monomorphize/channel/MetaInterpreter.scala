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

/** **The meta interpretation** (`docs/meta.md` §5) — the answer to the one question *"what is
  * this value's meta-information?"*, and the place the closure invariant of §1 is actually executed:
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
  * **This is not a shadow twin program (§20).** No shadow twin program is emitted: the interpretation walks the very
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
  * **Termination is the recursion gate's** (§1): user code cannot express recursion, so the derivation walks a DAG
  * and needs no fixpoint, no widening and no ascending-chain condition. The [[interpretationBudget]] is therefore not
  * what makes this terminate — it is the §5.3 fail-safe against *cost*: a diamond-shaped call DAG re-interpreted per
  * path is exponential even though each path is acyclic. The budget is a step count threaded through the whole
  * derivation of one call node, and exhausting it yields **absence**, which is sound ("I know nothing" is always true)
  * and never a wrong answer.
  *
  * **Higher-order through closures** (§5.2). A function value is a [[Value.Closure]] — the function-typed node and the
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

    /** ⊥ (`docs/meta.md` §12): evaluating this does not produce a value. It is a fact about control, never a meta: no
      * companion receives it, and it originates only where a leaf states `{never}`.
      */
    case Never

    /** A frame of the call being interpreted (`docs/meta.md` §11.1), by its identity in the [[Store]]. */
    case Frame(id: Int)

    /** A cell of the call being interpreted, by its identity in the [[Store]]. */
    case Cell(id: Int)

    /** A value a `data` constructor built, with its fields as the interpretation holds them: what a `match` on it reads,
      * which is how a frame or a cell reaches the code using it, as a field of a handle.
      */
    case Data(constructor: ValueFQN, fields: Seq[Value])

    /** The meta this value is known to have: none is known of a closure, a frame, a cell or a constructed value, whose
      * types carry no meta, and ⊥ is never materialised.
      */
    def known: Option[GroundValue] = this match {
      case Meta(meta) => meta
      case _          => None
    }
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

  /** The frames and cells alive in a call being interpreted (`docs/meta.md` §11.1), each by an identity local to the
    * interpretation. A frame and a cell are made by one call of their native, handed to that call's code and gone when
    * it returns; they are private and second-class, so nothing else ever holds one. A cell holds its content, and a
    * frame every exit made through it so far, with the cells as they were when it was made.
    */
  case class Store(cells: Map[Int, Store.Content], frames: Map[Int, Seq[Store.Exit]]) {
    def withFrame(id: Int): Store = copy(frames = frames + (id -> Seq.empty))

    def withCell(id: Int, content: Store.Content): Store = copy(cells = cells + (id -> content))

    /** The store once the call that made `frame` and `cell` returned. */
    def without(frame: Option[Int], cell: Option[Int]): Store = Store(cells -- cell, frames -- frame)

    /** `frame` was left through, carrying `carried`. */
    def received(frame: Int, carried: Option[GroundValue]): Store =
      copy(frames = frames.updated(frame, frames(frame) :+ Store.Exit(carried, cells)))

    /** `cell` holds `meta` from now on. */
    def written(cell: Int, meta: Option[GroundValue]): Store =
      copy(cells = cells.updated(cell, cells(cell).copy(meta = meta)))

    /** The store after code the interpretation did not follow, which may have written any cell and left through any
      * frame it could reach: every content and whatever left is not computed, never a guess.
      */
    def tainted: Store = {
      val unknown = cells.view.mapValues(_.copy(meta = None)).toMap
      Store(unknown, frames.view.mapValues(_ :+ Store.Exit(None, unknown)).toMap)
    }

    def isEmpty: Boolean = cells.isEmpty && frames.isEmpty
  }

  object Store {

    /** What a cell holds: the meta of its content, of meta type `metaType`. */
    case class Content(meta: Option[GroundValue], metaType: Option[GroundValue])

    /** An exit through a frame: what it carried, and the cells as they were. */
    case class Exit(carried: Option[GroundValue], cells: Map[Int, Content])

    val empty: Store = Store(Map.empty, Map.empty)

    /** The cells every one of `all` holds, each the join of its contents. */
    def joinCells(all: Seq[Map[Int, Content]]): CompilerIO[Map[Int, Content]] =
      all.headOption.fold(Map.empty[Int, Content].pure[CompilerIO]) { first =>
        first.keys.toList
          .filter(id => all.forall(_.contains(id)))
          .traverse { id =>
            val contents = all.map(_(id))
            joinAll(contents.map(_.meta), first(id).metaType).map(meta => id -> Content(meta, first(id).metaType))
          }
          .map(_.toMap)
      }

    /** The store after a call whose code ended as `ends`, each flagged by whether it returned: the cells are the join
      * of those the returning ends hold, since control continues from one of them, and the frames hold every exit any
      * of them made. Where none returned the call does not either, and its cells are those at the call, `atCall`.
      */
    def joined(ends: Seq[(Store, Boolean)], atCall: Store): CompilerIO[Store] = {
      val frames = ends.flatMap(_._1.frames.toSeq).foldLeft(Map.empty[Int, Seq[Exit]]) { case (all, (id, exits)) =>
        all.updated(id, (all.getOrElse(id, Seq.empty) ++ exits).distinct)
      }
      val live   = ends.filter(_._2).map(_._1.cells)
      (if (live.isEmpty) atCall.cells.pure[CompilerIO] else joinCells(live)).map(Store(_, frames))
    }
  }

  /** The interpretation's progress: the steps left of its budget, the next identity to give a frame or a cell, and the
    * frames and cells alive.
    */
  private case class Run(budget: Int, fresh: Int, store: Store) {
    def spent: Run = copy(budget = budget - 1)

    /** The budget is spent, so nothing more is followed: any cell may be written and any frame left through. */
    def exhausted: (Run, Value) = (copy(budget = 0, store = store.tainted), Value.unknown)

    /** Code the interpretation does not follow was handed `arguments`, so whatever frame or cell they hold may have
      * been reached.
      */
    def unfollowed(arguments: Seq[Value]): Run =
      if (!store.isEmpty && arguments.exists(holdsState)) copy(store = store.tainted) else this
  }

  /** Whether a value holds a frame or a cell: is one, has one in a field, or captured one. */
  private def holdsState(value: Value): Boolean = value match {
    case _: Value.Frame | _: Value.Cell => true
    case Value.Data(_, fields)          => fields.exists(holdsState)
    case Value.Closure(_, env)          => env.values.exists(holdsState)
    case _                              => false
  }

  /** The §5.3 cost fail-safe: the number of interpretation steps one call node's derivation may spend, across depth
    * *and* fan-out together (each node visited costs one, and the budget is threaded through siblings rather than
    * reset). Exhaustion yields absence, never a wrong answer, so the only thing a small budget costs is precision on a
    * deep call DAG. Memoization on `(vfqn, typeArgs, argMetas)` (§5.3) is the precision follow-on if this is ever
    * observed to bind in practice; a hard step count is the stronger guarantee of the two and is what must be here.
    */
  private val interpretationBudget: Int = 256

  /** The meta of an ordinary call: the callee **states** its transfer (a native leaf — reduce the companion), or it
    * **derives** one from its body (everything else — interpret it). `resultType` is the *call node's* own ground type,
    * which is what decides whether there is anything to derive at all: an untracked type's meta is the trivial `Unit`
    * (§1, meta is total on types but not structural), so a call returning one is skipped before any body is fetched.
    * That gate is what keeps the interpretation off the overwhelming majority of call nodes in a program.
    */
  def metaOfCall(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value]
  ): CompilerIO[Value] =
    metaOf(callee, typeArguments, resultType, arguments, Run(interpretationBudget, 0, Store.empty))
      .map((_, value) => settled(value))

  /** What a walk hands the interpretation for an argument it walked: a function-typed `node` is a closure over
    * `captured`, what the walk knows of the parameters in scope; any other node is the `value` the walk computed for it.
    */
  def argument(
      node: Sourced[MonomorphicExpression],
      value: Value,
      captured: Map[String, Option[GroundValue]]
  ): Value =
    if (isFunction(node)) Value.Closure(node, captured.view.mapValues(Value.Meta(_)).toMap) else value

  /** The α seed of the `Int` domain: a literal `n`'s singleton range, built in Eliot by
    * `eliot.lang.Runtime::integerLiteral`'s `{closed(V, V)}` brace and reduced here through the *same* stated-transfer
    * path as every other leaf. The interpretation builds no domain structure of its own; it only wraps `n` as the
    * `BigInteger` value to reduce the companion at.
    */
  def integerSeed(value: BigInt): CompilerIO[Option[GroundValue]] =
    reduceTransfer(WellKnownTypes.integerLiteralFQN, Seq(GroundValue.Direct(value, bigIntType)), Seq.empty)

  /** The α seed of the `String` domain (`docs/meta.md` §2.2): a literal's singleton size, counted in
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

  /** A parameter's **statement** (`docs/meta.md` §4.4): the companion stating the meta of the value at
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
    * slot-producing transfer does ([[reduceTransfer]]). It reads no parameter, so each is passed as [[notComputed]]:
    * were one read, the statement would not reduce.
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
                  Evaluator.groundToSem(constructor) +: Seq.fill(statement.arity)(notComputed)
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

  /** What a body-less `callee` hands the code argument at parameter `position` when it runs it, as far as it can be
    * read without running anything: per argument of that code's `functionType`, the meta a **handed** statement states
    * of it, or ⊤ where none does ([[MetaParameterDesugarer.handedStatement]], `docs/meta.md` §4.5). The supplier is the
    * callee, which the compiler cannot see, so the statement is axiomatic — the `match` eliminator's, for a braced field,
    * holds because the constructor proved it. Each is reduced as a parameter statement is, plus the meta type of the
    * argument it states, read off `functionType`, so a generic field is stated at its instantiation. A statement about
    * a frame, a cell or a run (§14), or a field of a value the interpretation built (§11.1), says something only where
    * the interpretation follows the call ([[runCode]]); here it is ⊤, except a braced field's, which is its brace.
    */
  def handedMetas(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      position: Int,
      functionType: GroundValue
  ): CompilerIO[Seq[Option[GroundValue]]] = {
    val domains = peel(functionType, functionType.functionArity).map(_._1).getOrElse(Seq.empty)
    handedStatementsOf(callee).flatMap { statements =>
      domains.indices.toList
        .traverse { argument =>
          statements.get((position, argument)) match {
            case Some((companion, statement)) if reducible(statement) =>
              handedMeta(
                callee,
                calleeTypeArgs,
                companion,
                statement.parameters.size,
                domains(argument),
                Seq.fill(statement.parameters.size)(notComputed)
              )
            case _                                                   =>
              none[GroundValue].pure[CompilerIO]
          }
        }
        .map(_.toSeq)
    }
  }

  /** Whether a handed statement says anything reduced on its own: one about frames, cells or a run says it only of a
    * run, while a braced field's `fieldOr` reduces to its brace.
    */
  private def reducible(statement: MetaStatement): Boolean = statement.primitive match {
    case None | Some(_: MetaStatement.Primitive.FieldOr) => true
    case _                                               => false
  }

  /** The handed statements of `callee`, by the position of the code parameter they are about and the index of the
    * argument they state, each with its companion and what it states.
    */
  private def handedStatementsOf(callee: ValueFQN): CompilerIO[Map[(Int, Int), (ValueFQN, MetaStatement)]] =
    getFactOrAbort(UnifiedModuleNames.Key(callee.moduleName, Platform.Compiler)).flatMap { names =>
      names.names.keys.toSeq
        .flatMap(name => MetaParameterDesugarer.handedArgument(callee.name, name).map(ValueFQN(callee.moduleName, name) -> _))
        .traverse { case (companion, (parameter, argument)) =>
          MetaStatement.of(companion).map { statement =>
            val position = statement.parameters.indexWhere(name =>
              name == parameter || name == MetaCompanionLayout.placeholderName(parameter)
            )
            (position, argument) -> (companion, statement)
          }
        }
        .map(_.filter(_._1._1 >= 0).toMap)
    }

  /** Reduce a handed statement over `arguments`, what it is passed for the callee's parameters, at the meta type of the
    * argument it states. A slot-producing statement takes that type's meta constructor first, which a type carrying no
    * meta does not have.
    */
  private def handedMeta(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      companion: ValueFQN,
      arity: Int,
      argumentType: GroundValue,
      arguments: Seq[SemValue]
  ): CompilerIO[Option[GroundValue]] =
    payloadTypes(callee, calleeTypeArgs, arity).flatMap {
      case Some((parameters, _)) =>
        (parameters.traverse(metaTypeOf), metaTypeOf(argumentType)).flatMapN { (metaTypes, handedType) =>
          def reduce(typeArguments: Seq[GroundValue], leading: Seq[SemValue]): CompilerIO[Option[GroundValue]] =
            EscalatingReducer
              .reduceApplied(companion, calleeTypeArgs ++ metaTypes ++ typeArguments, leading ++ arguments)
              .map(_.collect { case meta: GroundValue.Structure => meta })
          takesConstructor(companion).ifM(
            metaConstructorOf(handedType).flatMap {
              case Some(constructor) =>
                reduce(Seq(handedType, constructor.valueType), Seq(Evaluator.groundToSem(constructor)))
              case None              => none[GroundValue].pure[CompilerIO]
            },
            reduce(Seq(handedType), Seq.empty)
          )
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

  /** The closure invariant of §1, executed: **state or derive**, split on the presence of a `^Meta` companion — the
    * same test [[MetaTransferAccountingProcessor]] enforces from the other side, so the two cannot drift into
    * disagreeing about which values summarise and which are read.
    */
  private def metaOf(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (arguments.contains(Value.Never)) (run, Value.Never).pure[CompilerIO]
    else
      declaresTransfer(callee).ifM(
        stated(callee, typeArguments, arguments, run),
        derive(callee, typeArguments, resultType, arguments, run)
      )

  /** A stated transfer over the call's arguments. The code the callee runs is followed run by run ([[runCode]]), each
    * run handed what the callee states it hands it, and the transfer reads each as what it yields: a companion is Eliot
    * over metas and cannot apply a closure. A frame or cell statement is followed rather than reduced ([[follow]]).
    *
    * A statement that is the join of parameters is exact where all of them but one are ⊥: it is that one, whatever the
    * interpretation holds of it, so a value built in the one arm that returns passes through whole. Every such statement
    * in the tree says that its callee's result is one of those values: `fold`'s, the eliminators', the escape's and the
    * cell's. Otherwise a yield may be ⊥, which no companion receives ([[withoutNever]]), and a companion reducing to
    * `never` is ⊥ itself ([[fromReduced]]).
    */
  private def stated(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] = {
    val companion = RefinementChannelProcessor.metaCompanionFqn(callee)
    MetaStatement.of(companion).flatMap { statement =>
      statement.primitive match {
        case Some(primitive) =>
          follow(callee, statement, primitive, arguments, run).map((after, value) =>
            (after.unfollowed(arguments.filter(isCode)), value)
          )
        case None            =>
          companionTypeArguments(callee, companion, typeArguments, arguments.size, withResult = false)
            .map(_.getOrElse(Seq.empty))
            .flatMap { metaTypes =>
              runCode(callee, typeArguments, arguments, metaTypes, run).flatMap { (after, values) =>
                statement.joins.map(_.filter(index => values.lift(index).exists(_ != Value.Never))) match {
                  case Some(Seq(only)) => (after, values(only)).pure[CompilerIO]
                  case Some(Seq())     => (after, Value.Never).pure[CompilerIO]
                  case _               =>
                    withoutNever(values, metaTypes, arguments.map(isCode)) match {
                      case Some(metas) =>
                        reduceTransfer(callee, typeArguments, metas).map(meta => (after, fromReduced(meta)))
                      case None        => (after, Value.Never).pure[CompilerIO]
                    }
                }
              }
            }
      }
    }
  }

  /** A frame or cell statement on a callee's result (`docs/meta.md` §11.1): leaving through a frame, reading a cell,
    * writing one, or building a value. One the interpretation cannot place — a frame or cell it does not hold — is
    * followed as unknown, so every frame may have been left through and every cell written ([[Store.tainted]]).
    */
  private def follow(
      callee: ValueFQN,
      statement: MetaStatement,
      primitive: MetaStatement.Primitive,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    primitive match {
      case MetaStatement.Primitive.Built if arguments.size == statement.parameters.size =>
        (run, Value.Data(callee, arguments)).pure[CompilerIO]
      case MetaStatement.Primitive.Exit(frame, carried) =>
        (arguments.lift(frame), arguments.lift(carried)) match {
          case (Some(Value.Frame(id)), Some(value)) if run.store.frames.contains(id) =>
            (run.copy(store = run.store.received(id, value.known)), Value.Never).pure[CompilerIO]
          case _                                                                      =>
            (run.copy(store = run.store.tainted), Value.Never).pure[CompilerIO]
        }
      case MetaStatement.Primitive.Content(cell)        =>
        arguments.lift(cell) match {
          case Some(Value.Cell(id)) if run.store.cells.contains(id) =>
            (run, Value.Meta(run.store.cells(id).meta)).pure[CompilerIO]
          case _                                                     => (run, Value.unknown).pure[CompilerIO]
        }
      case MetaStatement.Primitive.Store(cell, value)   =>
        (arguments.lift(cell), arguments.lift(value)) match {
          case (Some(Value.Cell(id)), Some(written)) if run.store.cells.contains(id) =>
            (run.copy(store = run.store.written(id, written.known)), Value.unknown).pure[CompilerIO]
          case _                                                                      =>
            (run.copy(store = run.store.tainted), Value.unknown).pure[CompilerIO]
        }
      case _                                            => (run, Value.unknown).pure[CompilerIO]
    }

  /** One run of a code argument by the callee running it (`docs/meta.md` §11.1, §14): what it yields; the store it ends
    * with, whose frames hold every exit made on the way even when it does not return; what left through the frame the
    * callee made for it, and what the cell it made for it held when it returned; and where it started.
    */
  private case class CodeRun(
      yields: Value,
      end: Store,
      exits: Seq[Store.Exit],
      content: Option[Store.Content],
      start: Start
  )

  /** Where a run of code starts: where the callee was called, after another run returned, or after its exits. */
  private enum Start {
    case AtCall
    case AfterReturnOf(code: Int)
    case AfterExitsOf(code: Int)
  }

  /** The runs made so far of a call's code arguments, by position, and the interpretation's progress after them. */
  private case class Runs(made: Map[Int, CodeRun], budget: Int, fresh: Int)

  /** Follow every code argument of a stated call, each run once (`docs/meta.md` §11.1, §14), and answer the call's
    * argument values with each code argument replaced by what its run yields.
    *
    * A run is handed, per argument of its code, what the callee's handed statement says: a fresh frame or cell, what
    * another run's exits carried or its cell held, or a meta reduced over the arguments, a code argument read as what its
    * run yields. A run reading another starts where that one left off, after its return or after its exits, and is not
    * made at all when it is handed ⊥: a handler whose frame nothing left through. Every other run starts at the call,
    * and those are the callee's alternatives, one of which runs: `fold`'s arms, an eliminator's. The store after the
    * call joins what the runs no other run continues end with.
    */
  private def runCode(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      arguments: Seq[Value],
      metaTypes: Seq[GroundValue],
      run: Run
  ): CompilerIO[(Run, Seq[Value])] = {
    val code = arguments.indices.filter(index => isCode(arguments(index))).toList
    if (code.isEmpty) (run, arguments).pure[CompilerIO]
    else
      handedStatementsOf(callee).flatMap { statements =>
        def ensure(position: Int, runs: Runs, visiting: Set[Int]): CompilerIO[Runs] =
          if (runs.made.contains(position) || visiting.contains(position)) runs.pure[CompilerIO]
          else {
            val closure      = arguments(position).asInstanceOf[Value.Closure]
            val functionType = closure.node.value.expressionType
            val domains      = peel(functionType, functionType.functionArity).map(_._1).getOrElse(Seq.empty)
            val handed       = domains.indices.map(argument => statements.get((position, argument)).map(_._2))
            handed.flatten
              .flatMap(readsRuns)
              .filter(other => other != position && code.contains(other))
              .distinct
              .toList
              .foldLeftM(runs)((before, other) => ensure(other, before, visiting + position))
              .flatMap(ready => made(position, closure, domains, handed, ready))
          }

        def made(
            position: Int,
            closure: Value.Closure,
            domains: Seq[GroundValue],
            handed: Seq[Option[MetaStatement]],
            runs: Runs
        ): CompilerIO[Runs] =
          starts(handed.flatten, runs) match {
            case Some(start) =>
              val from = startStore(start, runs)
              from.flatMap { store =>
                handedValues(position, domains, handed, runs).flatMap { values =>
                  if (values.contains(Handed.Is(Value.Never)))
                    runs.copy(made = runs.made + (position -> CodeRun(Value.Never, store, Seq.empty, None, start))).pure[CompilerIO]
                  else {
                    val frame    = Option.when(values.contains(Handed.NewFrame))(runs.fresh)
                    val cell     = values.collectFirst { case Handed.NewCell(content) => content }.map(_ => runs.fresh + 1)
                    val prepared = Seq(
                      frame.map(id => (s: Store) => s.withFrame(id)),
                      cell.zip(values.collectFirst { case Handed.NewCell(content) => content })
                        .map((id, content) => (s: Store) => s.withCell(id, content))
                    ).flatten.foldLeft(store)((s, add) => add(s))
                    val handedTo = values.map {
                      case Handed.Is(value)  => value
                      case Handed.NewFrame   => Value.Frame(frame.get)
                      case Handed.NewCell(_) => Value.Cell(cell.get)
                    }
                    applyValue(closure, handedTo, functionType(closure).deepReturnType, Run(runs.budget, runs.fresh + 2, prepared))
                      .map { (after, yields) =>
                        val codeRun = CodeRun(
                          yields,
                          after.store.without(frame, cell),
                          frame.fold(Seq.empty)(id => after.store.frames.getOrElse(id, Seq.empty)),
                          cell.flatMap(after.store.cells.get),
                          start
                        )
                        Runs(runs.made + (position -> codeRun), after.budget, after.fresh)
                      }
                  }
                }
              }
            case None        =>
              // A run reading two others continues neither: it is not followed.
              runs.copy(made = runs.made + (position -> CodeRun(Value.unknown, run.store.tainted, Seq.empty, None, Start.AtCall)))
                .pure[CompilerIO]
          }

        def handedValues(
            position: Int,
            domains: Seq[GroundValue],
            handed: Seq[Option[MetaStatement]],
            runs: Runs
        ): CompilerIO[Seq[Handed]] =
          domains.indices.toList.traverse { argument =>
            statements.get((position, argument)) match {
              case None                         => (Handed.Is(Value.unknown): Handed).pure[CompilerIO]
              case Some((companion, statement)) =>
                handedValue(companion, statement, argument, domains(argument), runs)
            }
          }.map(_.toSeq)

        def handedValue(
            companion: ValueFQN,
            statement: MetaStatement,
            argument: Int,
            domain: GroundValue,
            runs: Runs
        ): CompilerIO[Handed] =
          statement.primitive match {
            case Some(MetaStatement.Primitive.FieldOf(value, constructor)) =>
              (fieldOf(value, constructor, argument).getOrElse(Handed.Is(Value.unknown)): Handed).pure[CompilerIO]
            case Some(MetaStatement.Primitive.FieldOr(value, constructor)) =>
              fieldOf(value, constructor, argument) match {
                case Some(handed) => handed.pure[CompilerIO]
                case None         =>
                  handedMeta(
                    callee,
                    typeArguments,
                    companion,
                    statement.parameters.size,
                    domain,
                    Seq.fill(statement.parameters.size)(notComputed)
                  ).map(meta => Handed.Is(Value.Meta(meta)))
              }
            case Some(MetaStatement.Primitive.Frame)         => (Handed.NewFrame: Handed).pure[CompilerIO]
            case Some(MetaStatement.Primitive.Cell(initial)) =>
              (Handed.NewCell(Store.Content(arguments.lift(initial).flatMap(_.known), metaTypes.lift(initial))): Handed)
                .pure[CompilerIO]
            case Some(MetaStatement.Primitive.Exited(other)) =>
              runs.made.get(other) match {
                case Some(made) if made.exits.isEmpty => (Handed.Is(Value.Never): Handed).pure[CompilerIO]
                case Some(made)                       =>
                  metaTypeOf(domain).flatMap(metaType =>
                    joinAll(made.exits.map(_.carried), Some(metaType)).map(meta => Handed.Is(Value.Meta(meta)))
                  )
                case None                             => (Handed.Is(Value.unknown): Handed).pure[CompilerIO]
              }
            case Some(MetaStatement.Primitive.Stored(other)) =>
              (runs.made.get(other) match {
                case Some(made) if made.yields == Value.Never => Handed.Is(Value.Never)
                case Some(made)                               => Handed.Is(Value.Meta(made.content.flatMap(_.meta)))
                case None                                     => Handed.Is(Value.unknown)
              }: Handed).pure[CompilerIO]
            case Some(_)                                     => (Handed.Is(Value.unknown): Handed).pure[CompilerIO]
            case None                                        =>
              val read = statement.reads.toSeq.map(index => index -> valueOf(index, runs))
              statement.joins match {
                case Some(Seq(only)) => (Handed.Is(valueOf(only, runs)): Handed).pure[CompilerIO]
                case _ if read.exists(_._2 == Value.Never) => (Handed.Is(Value.Never): Handed).pure[CompilerIO]
                case _               =>
                  handedMeta(
                    callee,
                    typeArguments,
                    companion,
                    statement.parameters.size,
                    domain,
                    statement.parameters.indices.map(index =>
                      read.toMap.get(index).flatMap(_.known).fold(notComputed)(Evaluator.groundToSem)
                    )
                  ).map(meta => Handed.Is(Value.Meta(meta)))
              }
          }

        // The field a `match` hands, when the interpretation built the value: none when another constructor built it.
        def fieldOf(value: Int, constructor: ValueFQN, argument: Int): Option[Handed] =
          arguments.lift(value).collect { case Value.Data(built, fields) =>
            if (built == constructor) Handed.Is(fields.lift(argument).getOrElse(Value.unknown)) else Handed.Is(Value.Never)
          }

        def valueOf(index: Int, runs: Runs): Value =
          runs.made.get(index).fold(arguments.lift(index).getOrElse(Value.unknown))(_.yields)

        def starts(handed: Seq[MetaStatement], runs: Runs): Option[Start] =
          handed.flatMap { statement =>
            statement.primitive match {
              case Some(MetaStatement.Primitive.Exited(other)) => Seq(Start.AfterExitsOf(other))
              case Some(MetaStatement.Primitive.Stored(other)) => Seq(Start.AfterReturnOf(other))
              case Some(_)                                     => Seq.empty
              case None                                        =>
                statement.reads.toSeq.filter(runs.made.contains).map(Start.AfterReturnOf(_))
            }
          }.distinct match {
            case Seq()      => Some(Start.AtCall)
            case Seq(start) => Some(start)
            case _          => None
          }

        def startStore(start: Start, runs: Runs): CompilerIO[Store] = start match {
          case Start.AtCall              => run.store.pure[CompilerIO]
          case Start.AfterReturnOf(other) => runs.made(other).end.pure[CompilerIO]
          case Start.AfterExitsOf(other)  =>
            val made = runs.made(other)
            Store
              .joinCells(made.exits.map(_.cells.view.filterKeys(run.store.cells.contains).toMap))
              .map(cells => made.end.copy(cells = if (made.exits.isEmpty) made.end.cells else cells))
        }

        code
          .foldLeftM(Runs(Map.empty, run.budget, run.fresh))((runs, position) => ensure(position, runs, Set.empty))
          .flatMap { runs =>
            val continued = runs.made.values.map(_.start).collect {
              case Start.AfterReturnOf(other) => other
              case Start.AfterExitsOf(other)  => other
            }.toSet
            val last      = runs.made.view.filterKeys(position => !continued.contains(position)).values.toSeq
            Store.joined(last.map(made => (made.end, made.yields != Value.Never)), run.store).map { store =>
              (
                Run(runs.budget, runs.fresh, store),
                arguments.indices.map(index => runs.made.get(index).fold(arguments(index))(_.yields))
              )
            }
          }
      }
  }

  /** What a callee hands one argument of a run of code: a value, or a frame or cell to make for the run. */
  private enum Handed {
    case Is(value: Value)
    case NewFrame
    case NewCell(content: Store.Content)
  }

  /** The runs of code a handed statement continues: the code whose exits or cell it reads, or whose yield. */
  private def readsRuns(statement: MetaStatement): Seq[Int] = statement.primitive match {
    case Some(MetaStatement.Primitive.Exited(other)) => Seq(other)
    case Some(MetaStatement.Primitive.Stored(other)) => Seq(other)
    case Some(_)                                     => Seq.empty
    case None                                        => statement.reads.toSeq
  }

  private def functionType(closure: Value.Closure): GroundValue = closure.node.value.expressionType

  private def isCode(value: Value): Boolean = value.isInstanceOf[Value.Closure]

  /** The join of metas of one meta type: not computed when any is, or when the type is not known. */
  private[channel] def joinAll(metas: Seq[Option[GroundValue]], metaType: Option[GroundValue]): CompilerIO[Option[GroundValue]] =
    (metas.sequence, metaType) match {
      case (Some(first +: rest), Some(tpe)) =>
        rest.foldLeftM(first.some)((joined, meta) =>
          joined.flatTraverse(sofar =>
            EscalatingReducer.reduceApplied(WellKnownTypes.metaJoinedFQN, Seq(tpe), Seq(sofar, meta).map(Evaluator.groundToSem))
          )
        )
      case _                                => none[GroundValue].pure[CompilerIO]
    }

  /** The argument metas a stated transfer is reduced over when some of its code yields ⊥ (`docs/meta.md` §12), or
    * `None` when every yield is ⊥, so the call does not return. A ⊥ yield is replaced by another argument of the **same
    * meta type**: statements are monotone, so `f(…⊥…) ⊑ f(…v…)` for any `v`, which makes the replacement sound, and it is
    * exact for a join, which is idempotent — `fold(c, 5, abort)` is `[5, 5]`. A ⊥ yield with no such sibling is passed
    * as not computed. Only code yields ⊥ here: a ⊥ *value* argument made the call ⊥ before it got this far.
    */
  private def withoutNever(
      values: Seq[Value],
      metaTypes: Seq[GroundValue],
      isCode: Seq[Boolean]
  ): Option[Seq[Option[GroundValue]]] = {
    val returning = values.indices.filter(values(_) != Value.Never)
    if (values.indices.forall(index => returning.contains(index))) Some(values.map(_.known))
    else if (values.indices.filter(isCode).forall(index => !returning.contains(index))) None
    else
      Some(values.indices.map { index =>
        if (returning.contains(index)) values(index).known
        else returning.find(other => metaTypes.lift(other) == metaTypes.lift(index)).flatMap(values(_).known)
      })
  }

  /** What a reduced stated transfer is: ⊥ when it reduced to `eliot.compiler.Meta::never`, else the meta it reduced
    * to, or not computed.
    */
  private def fromReduced(meta: Option[GroundValue]): Value = meta match {
    case Some(GroundValue.Structure(fqn, _, _)) if fqn == WellKnownTypes.metaNeverFQN => Value.Never
    case other                                                                         => Value.Meta(other)
  }

  /** Derive a bodied callee's meta by interpreting its body under the caller's arguments. A callee that cannot be read
    * — **no body on this track** (a leaf that states nothing, which R2 has already made a compile error for a
    * meta-carrying return, or a value whose monomorphization failed upstream), or **not saturated** (a partial
    * application is a function value, whose type carries no meta) — is not followed ([[Run.unfollowed]]).
    *
    * An over-application applies the callee's *result* to the remaining arguments, which is a closure its body returned.
    */
  private def derive(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (run.budget <= 0) run.exhausted.pure[CompilerIO]
    else
      getFactIfProduced(MonomorphicValue.Key(callee, typeArguments)).flatMap {
        case Some(mv) if mv.naturalArity.exists(_ <= arguments.size) =>
          mv.runtime match {
            case Some(body) =>
              applyLambda(body.as(MonomorphicExpression(mv.signature, body.value)), Map.empty, arguments, resultType, run.spent)
            case None       => (run.unfollowed(arguments), Value.unknown).pure[CompilerIO]
          }
        case _                                                       => (run.unfollowed(arguments), Value.unknown).pure[CompilerIO]
      }

  /** Interpret one node of a body: what is known of its value, and the interpretation's progress after it. Bottom-up and
    * pure — no record is kept and no diagnostic is raised, so this may be run over a *callee's* body without touching
    * that callee's own table or repeating its checks. A function-typed node is not interpreted here but deferred as a
    * closure, so only a function value that is applied costs anything.
    */
  private def interpret(
      node: Sourced[MonomorphicExpression],
      env: Env,
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (run.budget <= 0) run.exhausted.pure[CompilerIO]
    else
      node.value.expression match {
        case MonomorphicExpression.ParameterReference(parameterName)   =>
          // The one point the caller's knowledge enters: a bound parameter *is* the argument. An unbound one (a lambda's
          // own binder that nothing applied) is ⊤.
          (run.spent, env.getOrElse(parameterName.value, Value.unknown)).pure[CompilerIO]
        case _ if isFunction(node)                                     =>
          (run.spent, Value.Closure(node, env)).pure[CompilerIO]
        case MonomorphicExpression.IntegerLiteral(value)               =>
          integerSeed(value.value).map(meta => (run.spent, Value.Meta(meta)))
        case MonomorphicExpression.StringLiteral(value)                =>
          stringSeed(value.value).map(meta => (run.spent, Value.Meta(meta)))
        case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
          // A bare reference is a *saturated* call when the callee takes no arguments, so a nullary def derives here
          // exactly as an applied one does below.
          metaOf(vfqn.value, ta, node.value.expressionType, Seq.empty, run.spent)
        case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
          (run.spent, Value.Closure(node, env)).pure[CompilerIO]
        case _: MonomorphicExpression.FunctionApplication              =>
          val (head, args) = flatten(node)
          interpretAll(args, env, run.spent).flatMap((after, arguments) =>
            applyHead(head, env, arguments, node.value.expressionType, after)
          )
      }

  /** Apply the head of an application, written in `env`, to `arguments`: a def is called, a lambda binds its binders, and
    * a parameter is applied as whatever it is bound to. `resultType` is the type of the whole application. Evaluation is
    * strict, so an application one of whose arguments is ⊥ is never made, and is ⊥ itself — a `val` block included,
    * which lowers to an applied lambda. A code argument is a closure, never ⊥: whether it returns is its callee's
    * business.
    */
  private def applyHead(
      head: Sourced[MonomorphicExpression],
      env: Env,
      arguments: Seq[Value],
      resultType: GroundValue,
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (arguments.contains(Value.Never)) (run, Value.Never).pure[CompilerIO]
    else
      head.value.expression match {
        case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
          metaOf(vfqn.value, ta, resultType, arguments, run)
        case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
          applyLambda(head, env, arguments, resultType, run)
        case MonomorphicExpression.ParameterReference(parameterName)   =>
          applyValue(env.getOrElse(parameterName.value, Value.unknown), arguments, resultType, run)
        case _                                                         =>
          (run.unfollowed(arguments), Value.unknown).pure[CompilerIO]
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
      run: Run
  ): CompilerIO[(Run, Value)] = {
    val (body, bound, rest) = bindParameters(node, arguments)
    interpret(body, env ++ bound, run).flatMap((after, value) =>
      if (rest.isEmpty) (after, value).pure[CompilerIO] else applyValue(value, rest, resultType, after)
    )
  }

  /** Apply a value to `arguments`: a closure is its node applied in its own environment, which also appends to a
    * partial application's arguments; anything else is not known to be a function, so it is not followed.
    */
  private def applyValue(
      value: Value,
      arguments: Seq[Value],
      resultType: GroundValue,
      run: Run
  ): CompilerIO[(Run, Value)] =
    value match {
      case Value.Closure(_, _) if run.budget <= 0 => run.exhausted.pure[CompilerIO]
      case Value.Closure(node, env)               =>
        val (head, applied) = flatten(node)
        interpretAll(applied, env, run.spent).flatMap((after, values) =>
          applyHead(head, env, values ++ arguments, resultType, after)
        )
      case _                                      => (run.unfollowed(value +: arguments), Value.unknown).pure[CompilerIO]
    }

  /** Interpret every argument in the *caller's* scope, in order, threading the interpretation's progress through them,
    * so a wide fan-out spends the same budget a deep chain does and a later argument sees the cells an earlier one
    * wrote. Evaluation is strict, so the arguments after one that is ⊥ are never evaluated. Arguments are all
    * interpreted before any binder is added, which is what keeps a binder from shadowing a name a later argument reads.
    */
  private def interpretAll(
      nodes: Seq[Sourced[MonomorphicExpression]],
      env: Env,
      run: Run
  ): CompilerIO[(Run, Seq[Value])] =
    nodes.foldLeftM((run, Seq.empty[Value])) { case ((before, values), node) =>
      if (values.contains(Value.Never)) (before, values).pure[CompilerIO]
      else interpret(node, env, before).map((after, value) => (after, values :+ value))
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

  /** A finished interpretation's value, as a caller outside it reads one: its meta, or ⊥. A closure left unapplied is a
    * function value, whose type carries no meta, and a frame, a cell and a constructed value are the interpretation's
    * own, gone with the call that made them.
    */
  private def settled(value: Value): Value = value match {
    case _: Value.Meta | Value.Never => value
    case _                           => Value.unknown
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
    * Each argument is passed by [[companionArgument]]. `None` when the result depends on an argument whose meta is not
    * computed, whether the companion projects it or stores it (`docs/meta.md` §13.5). `None` too for a call that does
    * not apply exactly the
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
    companionTypeArguments(callee, companion, calleeTypeArgs, argMetas.size, withResult = true).flatMap {
      case Some(metaTypes) =>
        val arguments = metaTypes.zip(argMetas).map(companionArgument)
        def reduce(typeArguments: Seq[GroundValue], leading: Seq[SemValue]): CompilerIO[Option[GroundValue]] =
          EscalatingReducer
            .reduceApplied(companion, typeArguments, leading ++ arguments)
            .map(_.collect { case s: GroundValue.Structure => s })
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

  /** What a companion is passed for an argument of meta type `metaType`: its meta when computed; `unit` when its meta
    * type is `Unit`, since that meta is known (§1); otherwise [[notComputed]], never a value that could quote.
    */
  private[channel] def companionArgument(metaType: GroundValue, meta: Option[GroundValue]): SemValue =
    meta match {
      case Some(gv)                    => Evaluator.groundToSem(gv)
      case None if metaType == unitType => unitMeta
      case None                        => notComputed
    }

  /** The argument a companion is passed for a meta that is not computed (`docs/meta.md` §13.5): a dedicated neutral that
    * never quotes, so a result holding it, projected or stored, is not computed either.
    */
  private val notComputed: SemValue =
    SemValue.VNeutral(SemValue.NeutralHead.Reserved(SemValue.NeutralHead.Marker.NotComputed), SemValue.Spine.SNil)

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
    * cheap gate. Non-structural today (§1, §13): `List[Int]` and `data Counter(n: Int)` carry none, so meta is lost at
    * every data boundary exactly as it is today.
    */
  private[channel] def carriesMeta(nodeType: GroundValue): CompilerIO[Boolean] = nodeType match {
    case GroundValue.Structure(fqn, _, _) => declaresMetaStructure(fqn)
    case _                                => false.pure[CompilerIO]
  }

  private def metaStructureName(typeName: ValueFQN): QualifiedName =
    QualifiedName(typeName.name.name + MetaConstructorDesugarer.metaTypeSuffix, Qualifier.Type)

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

  /** `unit`, the meta of every value whose type carries none. */
  private val unitMeta: SemValue =
    Evaluator.groundToSem(GroundValue.Structure(WellKnownTypes.unitValueFQN, Seq.empty, unitType))
}
