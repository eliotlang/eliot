package com.vanillasource.eliot.eliotc.monomorphize.channel

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.core.fact.Expression
import com.vanillasource.eliot.eliotc.core.processor.{
  MetaCompanionLayout,
  MetaConstructorDesugarer,
  MetaParameterDesugarer,
  StructuralMetaDesugarer
}
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
  *     the caller's argument metas — and a bodied value whose return carries a brace then has its result's meta
  *     replaced by that brace (decision O, `docs/meta.md` §8.1), while everything else its body did stands.
  *
  * The first is R2 read as a definition rather than as a prohibition, which is why [[metaOf]] splits on exactly the
  * tests [[MetaTransferAccountingProcessor]] judges. Before this existed only the first arm did: a call
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
  * **Termination is the recursion gate's** (§1): user code cannot express recursion, so the derivation walks a DAG.
  * Every loop lives in a native, which states it (`looped`, `endless`), and its fixpoint is made finite by the meta
  * type's own widening (§11.2). The [[interpretationBudget]] is therefore not what makes this terminate — it is the
  * §5.3 fail-safe against *cost*: a call DAG is acyclic, but a diamond-shaped one has exponentially many paths. Every
  * call is a fact ([[DerivedMeta]], `docs/meta.md` §17.3), so a call reached along many of them with the same
  * arguments is interpreted once; the budget, a step count threaded through one call's own answer, bounds what that
  * one definition does, its calls one step each. Exhausting it is a
  * **compiler limit** (decision I): [[metaOfCall]] answers that it ran out, never a value, and the stamping walk
  * reports it at the call.
  *
  * **Higher-order through closures** (§5.2). A function value is a [[Value.Closure]] — the function-typed node, by its
  * [[Address]], and the environment it was written in — and is interpreted only when something applies it: a callee applying one of its own
  * parameters (`def applyTo(f: Int => Int, x: Int): Int = f(x)`), an over-applied callee whose body returns a lambda,
  * or a pure `val` block, which lowers to an applied lambda (`(x -> rest)(e)`). A **stated** transfer cannot apply a
  * closure — a companion is Eliot over metas — so a code argument reaches it as what its code **yields**, the same
  * reading its payload signature gives a code parameter's meta type: `fold(c, 10, 20)` joins `[10, 10]` and `[20, 20]`.
  * A handler a native runs is applied to what the native **states** it hands it ([[handedMetas]]) — the `match`
  * eliminator's braced fields, `foldLeftInternal`'s accumulator — and to ⊤ where it states nothing, as of a list's
  * element until a `List` carries its elements' meta.
  */
object MetaInterpreter {

  /** What the interpretation knows of one value. */
  enum Value {

    /** Its meta, or ⊤ (`None`) — always ⊤ for a value whose type carries no meta. */
    case Meta(meta: Option[GroundValue])

    /** A function value, deferred until applied: the function-typed node at `address` (a lambda, a partial
      * application, a reference to a def) and the `env` it was written in, which is what the parameters it captures
      * read.
      */
    case Closure(address: Address, env: Env)

    /** ⊥ (`docs/meta.md` §12): evaluating this does not produce a value. It is a fact about control, never a meta: no
      * companion receives it, and it originates only where a leaf states `{never}`.
      */
    case Never

    /** A frame of the call being interpreted (`docs/meta.md` §11.1), by its identity in the [[Store]]. */
    case Frame(id: Int)

    /** A cell of the call being interpreted, by its identity in the [[Store]]. */
    case Cell(id: Int)

    /** A value a `data` constructor built, with its fields as the interpretation holds them: what a `match` on it reads,
      * which is how a frame or a cell reaches the code using it, as a field of a handle. `meta` is its structural meta
      * (`docs/meta.md` §13), what its constructor states of it, which is what is left of it once the call that built it
      * returns.
      */
    case Data(constructor: ValueFQN, fields: Seq[Value], meta: Option[GroundValue])

    /** The meta this value is known to have. A closure, a frame and a cell have types declaring no meta, so theirs is
      * the implicit default's ([[untrackedMeta]]); ⊥ is never materialised.
      */
    def known: Option[GroundValue] = this match {
      case Meta(meta)       => meta
      case Data(_, _, meta) => meta
      case Never            => None
      case _                => Some(untrackedMeta)
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

  /** Where a function value is written (`docs/meta.md` §17.3, F1): the monomorphic value whose body holds it, and the
    * way down to it from that body's root. A closure is named by this, not by its node, so it is data, which a fact can
    * hold, and two closures over the same lambda are equal whenever what they read is. A source position cannot be the
    * address: desugaring makes positions ambiguous (§7).
    *
    * {{{
    *   def twice(x: Int): Int = applyTo(y -> y + y, x)
    *     the lambda   Address(twice, [Target, Argument])   — the first argument of `applyTo(…)(x)`
    * }}}
    */
  case class Address(owner: MonomorphicValue.Key, path: Seq[Address.Step])

  object Address {

    /** One step down a body: into an application's target or argument, or into a lambda's body. */
    enum Step {
      case Target, Argument, Body
    }
  }

  /** A node of a body, with its [[Address]]: what the interpretation and the walks hold, so that a closure made of any
    * node they reach can be named.
    */
  case class Located(node: Sourced[MonomorphicExpression], address: Address) {

    /** The child `child` of this node, one `step` down. */
    def down(step: Address.Step, child: Sourced[MonomorphicExpression]): Located =
      Located(child, address.copy(path = address.path :+ step))
  }

  object Located {

    /** The root of `value`'s body, typed by its signature, or nothing for a value without one. */
    def root(value: MonomorphicValue): Option[Located] =
      value.runtime.map(body =>
        Located(
          body.as(MonomorphicExpression(value.signature, body.value)),
          Address(MonomorphicValue.Key(value.vfqn, value.typeArguments), Vector.empty)
        )
      )

    /** The node at `address`: its owner's body, followed down its path. Only a node of that body was ever given the
      * address, so a path that does not lead to one is an internal error.
      */
    def at(address: Address): CompilerIO[Located] =
      getFactOrAbort(address.owner).map { value =>
        val root = Located.root(value).getOrElse(throw IllegalStateException(s"A closure's owner has no body: ${address.owner}"))
        address.path.foldLeft(root)((located, step) =>
          (step, located.node.value.expression) match {
            case (Address.Step.Target, MonomorphicExpression.FunctionApplication(target, _))     => located.down(step, target)
            case (Address.Step.Argument, MonomorphicExpression.FunctionApplication(_, argument)) => located.down(step, argument)
            case (Address.Step.Body, MonomorphicExpression.FunctionLiteral(_, _, body))         => located.down(step, body)
            case _                                                                              =>
              throw IllegalStateException(s"A closure's path leads to no node of its owner's body: $address")
          }
        )
      }
  }

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

    /** The budget is spent, so nothing more is followed: any cell may be written and any frame left through. A budget
      * below zero marks the run as cut short, which [[metaOfCall]] reads, since a budget of exactly zero may only mean
      * the last step was taken.
      */
    def exhausted: (Run, Value) = (copy(budget = -1, store = store.tainted), Value.unknown)

    /** Whether the budget ran out before the interpretation finished. */
    def cutShort: Boolean = budget < 0

    /** Code the interpretation does not follow was handed `arguments`, so whatever frame or cell they hold may have
      * been reached.
      */
    def unfollowed(arguments: Seq[Value]): Run =
      if (!store.isEmpty && arguments.exists(holdsState)) copy(store = store.tainted) else this
  }

  /** The canonical number of every frame and cell `arguments` and `store` mention, in the order they mention them:
    * what a call's [[DerivedMeta.Key]] numbers them by, so the same call holding different identities is one key.
    */
  private def numbering(arguments: Seq[Value], store: Store): Map[Int, Int] =
    (arguments.flatMap(held) ++ store.cells.keys.toSeq.sorted ++ store.frames.keys.toSeq.sorted ++
      store.frames.keys.toSeq.sorted.flatMap(store.frames(_).flatMap(_.cells.keys.toSeq.sorted))).distinct.zipWithIndex.toMap

  private def renumbered(value: Value, numbering: Int => Int): Value = value match {
    case Value.Frame(id)                    => Value.Frame(numbering(id))
    case Value.Cell(id)                     => Value.Cell(numbering(id))
    case Value.Data(constructor, fields, m) => Value.Data(constructor, fields.map(renumbered(_, numbering)), m)
    case Value.Closure(address, env)        => Value.Closure(address, env.view.mapValues(renumbered(_, numbering)).toMap)
    case other                              => other
  }

  private def renumbered(store: Store, numbering: Int => Int): Store = {
    def cells(all: Map[Int, Store.Content]) = all.map((id, content) => numbering(id) -> content)
    Store(
      cells(store.cells),
      store.frames.map((id, exits) => numbering(id) -> exits.map(exit => exit.copy(cells = cells(exit.cells))))
    )
  }

  /** **The store a call can reach** (`docs/meta.md` §17.3, F2). Frames and cells are second-class, so a call reaches
    * one only through its arguments (§11.1): a call is interpreted over the frames and cells its arguments hold, and
    * the rest of the store is what this keeps aside, to put back when the call returns. Nothing the call does can
    * change it, except that code it does not follow is fail-safe: [[Store.tainted]] makes every cell not computed and
    * leaves through every frame, the ones the call cannot reach included.
    *
    * That one exception is followed exactly by two markers standing in for what is kept aside: a cell holding `unit`,
    * which a taint makes not computed, and a frame, which a taint leaves through. They join, widen and are snapshotted
    * as the cells and frames they stand for would be, so on return the kept cells are not computed exactly where the
    * marker cell is not, each exit records them as they were exactly when it was made, and every kept frame is left
    * through as often as the marker frame was. The markers exist only when something is kept aside.
    *
    * A frame's earlier exits are kept aside too, though the frame is reachable: only the native that made it reads
    * them, after its code returns, and that native is outside the call. So a call is interpreted, and is one
    * [[DerivedMeta]], over the same store however many frames enclose it.
    *
    * {{{
    *   runThrow(… if(c, v) …)     // `if`'s call holds Abort's frame only
    *     store at the call    frames {throw: [e1], abort: []}, cells {}
    *     the call sees        frames {abort: [], marker: []}, cells {marker: unit}
    * }}}
    */
  private case class Reach(earlier: Map[Int, Seq[Store.Exit]], aside: Store, markers: Option[(Int, Int)]) {

    /** The store after the call, from `after`, the store the call ended with. */
    def rejoined(after: Store): Store = markers match {
      case None               =>
        Store(after.cells, after.frames.map((id, exits) => id -> (earlier.getOrElse(id, Seq.empty) ++ exits)))
      case Some((cell, frame)) =>
        def asideAt(cells: Map[Int, Store.Content]): Map[Int, Store.Content] = cells.get(cell) match {
          case Some(Store.Content(Some(_), _)) => aside.cells
          case Some(_)                         => aside.cells.view.mapValues(_.copy(meta = None)).toMap
          case None                            => Map.empty
        }
        def restored(exit: Store.Exit): Store.Exit = Store.Exit(exit.carried, (exit.cells - cell) ++ asideAt(exit.cells))
        val reached = (after.frames - frame).map((id, exits) => id -> (earlier.getOrElse(id, Seq.empty) ++ exits.map(restored)))
        val kept    = after.frames.get(frame).fold(Map.empty[Int, Seq[Store.Exit]])(exits =>
          aside.frames.view.mapValues(_ ++ exits.map(restored)).toMap
        )
        Store((after.cells - cell) ++ asideAt(after.cells), reached ++ kept)
    }
  }

  private object Reach {

    /** The run a call with `arguments` is interpreted in, over the store it can reach, and what is kept aside. */
    def of(arguments: Seq[Value], run: Run): (Run, Reach) =
      if (run.store.isEmpty) (run, Reach(Map.empty, Store.empty, None))
      else {
        val held    = arguments.flatMap(MetaInterpreter.held).toSet
        val frames  = run.store.frames.view.filterKeys(held.contains).toMap
        val reached = Store(run.store.cells.view.filterKeys(held.contains).toMap, frames.view.mapValues(_ => Seq.empty).toMap)
        val aside   = Store(run.store.cells -- held, run.store.frames -- held)
        if (aside.isEmpty) (run.copy(store = reached), Reach(frames, aside, None))
        else {
          val (cell, frame) = (run.fresh, run.fresh + 1)
          (
            run.copy(fresh = run.fresh + 2, store = reached.withCell(cell, Store.Content(Some(untrackedMeta), Some(unitType))).withFrame(frame)),
            Reach(frames, aside, Some((cell, frame)))
          )
        }
      }
  }

  /** The frames and cells a value holds: is one, has one in a field, or captured one, in the order it mentions them. */
  private def held(value: Value): Seq[Int] = value match {
    case Value.Frame(id)          => Seq(id)
    case Value.Cell(id)           => Seq(id)
    case Value.Data(_, fields, _) => fields.flatMap(held)
    case Value.Closure(_, env)    => env.keys.toSeq.sorted.flatMap(name => held(env(name)))
    case _                        => Seq.empty
  }

  /** Whether a value holds a frame or a cell: is one, has one in a field, or captured one. */
  private def holdsState(value: Value): Boolean = held(value).nonEmpty

  /** The §5.3 cost fail-safe: the number of interpretation steps one call's answer ([[DerivedMeta]]) may spend, across
    * depth *and* fan-out together (each node visited costs one, and the budget is threaded through siblings rather than
    * reset). A call the answer makes is a fact of its own, read for one step (`docs/meta.md` §17.3), so this bounds what
    * one definition does in one context, never what a program does. Running out of it is a compiler limit, reported at
    * the call (decision I, §5.3), never a value handed on.
    *
    * It was 256 until loops (T4): a loop runs its code up to about four times — joined, widened, and once more to see
    * it stable — and an endless loop around a cell and a frame (`forever` over `putState` and `raise`) took more than
    * 256 steps. Four times as many changed no example's jar and no example's compile time.
    */
  val interpretationBudget: Int = 1024

  /** The meta of an ordinary call: the callee **states** its transfer (a native leaf — reduce the companion), or it
    * **derives** one from its body (everything else — interpret it, then apply its brace if it states one, [[metaOf]]). `resultType` is the *call node's* own ground type,
    * which is what decides whether there is anything to derive at all: an untracked type's meta is the trivial `Unit`
    * (§1, meta is total on types but not structural), so a call returning one is skipped before any body is fetched.
    * That gate is what keeps the interpretation off the overwhelming majority of call nodes in a program.
    *
    * `None` when the derivation ran out of the [[interpretationBudget]]: what it got that far is not the call's meta,
    * so it is not handed on (decision I, `docs/meta.md` §5.3).
    */
  def metaOfCall(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value]
  ): CompilerIO[Option[Value]] =
    ().pure[CompilerIO]
      .flatMap(_ => metaOf(callee, typeArguments, resultType, arguments, Run(interpretationBudget, 0, Store.empty)))
      .map((run, value) => Option.unless(run.cutShort)(settled(value)))

  /** What the call `key` names answers ([[DerivedMetaProcessor]]): the call interpreted under a budget of its own, over
    * the store its key holds, its frames and cells made numbered after those the key mentions; or nothing when the
    * budget ran out.
    */
  def answerOf(key: DerivedMeta.Key): CompilerIO[Option[DerivedMeta.Answer]] = {
    val known = numbering(key.arguments, key.store).size
    answered(key.callee, key.typeArguments, key.resultType, key.arguments, Run(interpretationBudget, known, key.store))
      .map((after, value) => Option.unless(after.cutShort)(DerivedMeta.Answer(value, after.store, after.fresh - known)))
  }

  /** What a walk hands the interpretation for an argument it walked: a function-typed node is a closure over
    * `captured`, what the walk knows of the parameters in scope; any other node is the `value` the walk computed for it.
    */
  def argument(
      located: Located,
      value: Value,
      captured: Map[String, Option[GroundValue]]
  ): Value =
    if (isFunction(located.node)) closure(located, captured.view.mapValues(Value.Meta(_)).toMap) else value

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

  /** Whether a concrete type declares **slots** — i.e. its module (in the compiler pool, where
    * [[MetaConstructorDesugarer]] emits meta structures) declares its `T$Meta` structure and it is no `data` type's
    * structural meta. Its meta is then what its slots declare, which a leaf returning it must state (R2,
    * [[MetaTransferAccountingProcessor]]).
    */
  def declaresSlots(typeName: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(typeName.moduleName, Platform.Compiler))
      .map(names => names.names.contains(metaStructureName(typeName)) && !names.names.contains(fieldsName(typeName)))

  /** Whether a concrete type is a `data` type carrying a **structural meta** (`docs/meta.md` §13): its module declares
    * its field-types signature `T$Fields` ([[StructuralMetaDesugarer]]).
    */
  private[channel] def declaresFields(typeName: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(typeName.moduleName, Platform.Compiler))
      .map(_.names.contains(fieldsName(typeName)))

  /** Whether a `data` type declares slots of its own (`docs/meta.md` §17.2, O4b): its module declares its slot-types
    * signature `D$Slots` ([[StructuralMetaDesugarer]]).
    */
  private[channel] def declaresDataSlots(typeName: ValueFQN): CompilerIO[Boolean] =
    getFactOrAbort(UnifiedModuleNames.Key(typeName.moduleName, Platform.Compiler))
      .map(_.names.contains(slotsName(typeName)))

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
        parameters.traverse(metaTypeOf(_)).map(_.sequence).flatMap {
          case Some(metaTypes) =>
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
          case None            => (Stated.Unknown: Stated).pure[CompilerIO]
        }
      case None                  => (Stated.Unknown: Stated).pure[CompilerIO]
    }

  /** What a body-less `callee` hands the code argument at parameter `position` when it runs it, as far as it can be
    * read without running anything, from `arguments`, what is known of the call's arguments: per argument of that
    * code's `functionType`, what a **handed** statement states of it, or unknown where none does
    * ([[MetaParameterDesugarer.handedStatement]], `docs/meta.md` §4.5). The supplier is the callee, which the compiler
    * cannot see, so the statement is axiomatic. A `match` eliminator's is read as [[fieldHanded]] reads it, ⊥ for a
    * handler that does not run. Any other is reduced as a parameter statement is, plus the meta type of the argument it
    * states, read off `functionType`, so a generic field is stated at its instantiation. A statement about a frame, a
    * cell or a run (§14) says something only where the interpretation follows the call ([[runCode]]); here it is
    * unknown.
    */
  def handedMetas(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      position: Int,
      functionType: GroundValue,
      arguments: Seq[Value]
  ): CompilerIO[Seq[Value]] = {
    val domains = peel(functionType, functionType.functionArity).map(_._1).getOrElse(Seq.empty)
    handedStatementsOf(callee).flatMap { statements =>
      domains.indices.toList
        .traverse { argument =>
          statements.get((position, argument)) match {
            case Some((companion, statement)) =>
              statement.primitive match {
                case Some(field: MetaStatement.Primitive.FieldOf) =>
                  fieldHanded(callee, calleeTypeArgs, companion, statement, field, argument, domains(argument), arguments)
                case None                                         =>
                  handedMeta(
                    callee,
                    calleeTypeArgs,
                    companion,
                    statement.parameters.size,
                    domains(argument),
                    Seq.fill(statement.parameters.size)(notComputed)
                  ).map(Value.Meta(_))
                case Some(_)                                      => Value.unknown.pure[CompilerIO]
              }
            case None                         => Value.unknown.pure[CompilerIO]
          }
        }
        .map(_.toSeq)
    }
  }

  /** What a `match` eliminator hands argument `argument` of a handler, by its `fieldOf` statement (`docs/meta.md`
    * §13.4): the very field when the interpretation built the scrutinee, ⊥ when it built it with another constructor;
    * otherwise the statement reduced over the scrutinee's structural meta, which is the field's meta read out of its
    * constructor's slot, ⊥ when that slot is unreached. Where the scrutinee's meta is not computed, a braced field is
    * what its constructor states of it, which every construction proved (§4.5).
    */
  private def fieldHanded(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      companion: ValueFQN,
      statement: MetaStatement,
      field: MetaStatement.Primitive.FieldOf,
      argument: Int,
      domain: GroundValue,
      arguments: Seq[Value]
  ): CompilerIO[Value] =
    arguments.lift(field.value) match {
      case Some(Value.Data(built, fields, _)) =>
        (if (built == field.constructor) fields.lift(argument).getOrElse(Value.unknown) else Value.Never).pure[CompilerIO]
      case Some(scrutinee)                    =>
        scrutinee.known match {
          case Some(meta) =>
            handedMeta(
              callee,
              calleeTypeArgs,
              companion,
              statement.parameters.size,
              domain,
              statement.parameters.indices.map(index =>
                if (index == field.value) Evaluator.groundToSem(meta) else notComputed
              )
            ).map(fromReduced)
          case None       => fieldBrace(callee, calleeTypeArgs, field.constructor, argument).map(Value.Meta(_))
        }
      case None                               => Value.unknown.pure[CompilerIO]
    }

  /** What `constructor` states of its field `argument`, its parameter's statement (§4.5), at the type arguments of the
    * eliminator `callee`'s scrutinee — a `data` type's constructors take its own type parameters — or nothing when the
    * field is not braced.
    */
  private def fieldBrace(
      callee: ValueFQN,
      calleeTypeArgs: Seq[GroundValue],
      constructor: ValueFQN,
      argument: Int
  ): CompilerIO[Option[GroundValue]] =
    parameterStatements(constructor).flatMap {
      _.find(_.position == argument) match {
        case Some(statement) =>
          payloadTypes(callee, calleeTypeArgs, 1).flatMap {
            case Some((Seq(GroundValue.Structure(_, typeArguments, _)), _)) =>
              statedMeta(constructor, typeArguments, statement).map {
                case Stated.Meta(meta, _) => Some(meta)
                case _                    => None
              }
            case _                                                        => none[GroundValue].pure[CompilerIO]
          }
        case None            => none[GroundValue].pure[CompilerIO]
      }
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
        (parameters.traverse(metaTypeOf(_)).map(_.sequence), metaTypeOf(argumentType)).flatMapN {
          case (Some(metaTypes), Some(handedType)) =>
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
          case _                                   => none[GroundValue].pure[CompilerIO]
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
  private[channel] def flatten(located: Located): (Located, Seq[Located]) =
    located.node.value.expression match {
      case MonomorphicExpression.FunctionApplication(target, argument) =>
        val (head, args) = flatten(located.down(Address.Step.Target, target))
        (head, args :+ located.down(Address.Step.Argument, argument))
      case _                                                           => (located, Seq.empty)
    }

  /** The closure invariant of §1, executed, in three arms split on two tests, a `^Meta` companion and a body on this
    * track — the same two [[MetaTransferAccountingProcessor]] judges, so the two cannot drift into disagreeing about
    * which values summarise and which are read:
    *
    *   - a **native** with a companion is *stated*: the companion is its only meta ([[stated]]);
    *   - a **bodied** value with a companion is *derived, then stated*: its body is walked, and the brace replaces the
    *     result's meta (decision O, [[restated]]);
    *   - anything else is *derived* ([[derive]]).
    *
    * The body is read after the layer merge, so a brace in one layer over a body in another is the second arm.
    *
    * A call is a fact ([[DerivedMeta]], `docs/meta.md` §17.3), read for one step: its key holds the store it can reach
    * ([[Reach]]), every frame and cell numbered canonically ([[numbering]]), and its answer is numbered back, what it
    * made after this run's identities, and the rest of the store put back. A call whose answer ran out of the budget
    * makes this one run out too.
    */
  private def metaOf(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (arguments.contains(Value.Never)) (run, Value.Never).pure[CompilerIO]
    else {
      val (inner, reach) = Reach.of(arguments, run)
      val canonical      = numbering(arguments, inner.store)
      val key            = DerivedMeta.Key(
        callee,
        typeArguments,
        resultType,
        arguments.map(renumbered(_, canonical)),
        renumbered(inner.store, canonical)
      )
      if (inner.budget <= 0) inner.exhausted.pure[CompilerIO]
      else
        getFactOrAbort(key).map { derived =>
          val (after, value) = derived.answer match {
            case Some(answer) =>
              val back = canonical.map(_.swap)
              val made = (id: Int) => back.getOrElse(id, inner.fresh + id - canonical.size)
              (Run(inner.budget - 1, inner.fresh + answer.made, renumbered(answer.store, made)), renumbered(answer.value, made))
            case None         => inner.exhausted
          }
          (after.copy(store = reach.rejoined(after.store)), value)
        }
    }

  /** [[metaOf]]'s answer to the call, interpreted in `run`. */
  private def answered(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    declaresTransfer(callee).ifM(
      isBodied(callee, typeArguments).ifM(
        restated(callee, typeArguments, resultType, arguments, run),
        stated(callee, typeArguments, arguments, run)
      ),
      derive(callee, typeArguments, resultType, arguments, run)
    )

  /** Whether `callee` has a body on this track at these type arguments. One whose monomorphization failed upstream has
    * none to walk, so it is read as its statement alone.
    */
  private def isBodied(callee: ValueFQN, typeArguments: Seq[GroundValue]): CompilerIO[Boolean] =
    getFactIfProduced(MonomorphicValue.Key(callee, typeArguments)).map(_.exists(_.runtime.isDefined))

  /** A bodied value stating its result's meta (decision O, `docs/meta.md` §8.1). Its body is derived as any other
    * body is, so the cells it wrote, the frames it left through and the steps it spent are what the body did; only the
    * meta of the value it returns is replaced, by its brace reduced over the argument metas. The brace is trusted:
    * nothing compares it with what was derived. A call that does not return stays ⊥, having no result to state, and a
    * derivation cut short stays cut short, since the brace replaces a result and not a walk.
    *
    * A code argument is passed to the brace as not computed: the body runs it, and what its runs yield is not recorded
    * for the brace yet (`docs/meta.md` §17.2, O2).
    *
    * Over a `data` result the brace states the type's declared slots only (§17.2, O5), so it is passed a constructor
    * that keeps the rest of the derived meta ([[restatingConstructorOf]]), and a value the body built keeps its fields.
    */
  private def restated(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      resultType: GroundValue,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    derive(callee, typeArguments, resultType, arguments, run).flatMap {
      case derived @ (after, value) if value == Value.Never || after.cutShort => derived.pure[CompilerIO]
      case (after, value)                                                     =>
        reduceTransfer(
          callee,
          typeArguments,
          arguments.map(argument => if (isCode(argument)) None else argument.known),
          restatingConstructorOf(resultType, value.known)
        ).map(meta =>
          value match {
            case Value.Data(constructor, fields, _) => (after, Value.Data(constructor, fields, meta))
            case _                                  => (after, Value.Meta(meta))
          }
        )
    }

  /** The meta constructor a bodied value's brace over `resultType` is passed. For a `data` type declaring slots it
    * takes the slots alone and rebuilds `derived` with them: the constructors' reach records stay what the body derived,
    * so a `match` on the result still hands its arms the fields' metas (`docs/meta.md` §17.2, O5). Not computed when
    * `derived` is not. Any other type's is its own meta constructor, as for a native ([[plainConstructorOf]]).
    *
    * {{{
    *   def warm: Temperature {Positive} = Temperature(5)
    *     derived  Temperature$Meta(Positive, Reached(Temperature$Meta$Temperature(5…5)))
    *     passed   slot$1 -> Temperature$Meta(slot$1, Reached(Temperature$Meta$Temperature(5…5)))
    * }}}
    */
  private def restatingConstructorOf(resultType: GroundValue, derived: Option[GroundValue])(
      metaType: GroundValue
  ): CompilerIO[Option[(SemValue, GroundValue)]] =
    resultType match {
      case GroundValue.Structure(fqn, typeArguments, _) =>
        declaresDataSlots(fqn).ifM(
          (
            EscalatingReducer.reduceApplied(ValueFQN(fqn.moduleName, slotsName(fqn)), typeArguments, Seq.empty),
            metaConstructorOf(metaType)
          ).mapN {
            case (Some(slots), Some(constructor)) =>
              derived.collect { case meta: GroundValue.Structure => restating(constructor, fieldTypes(slots), meta, metaType) }
            case _                                => None
          },
          plainConstructorOf(metaType)
        )
      case _                                            => plainConstructorOf(metaType)
    }

  /** `constructor` taking the `slotTypes` first, then the rest of `derived`'s fields, as a function of the slots alone,
    * with its type.
    */
  private def restating(
      constructor: GroundValue.Structure,
      slotTypes: Seq[GroundValue],
      derived: GroundValue.Structure,
      metaType: GroundValue
  ): (SemValue, GroundValue) = {
    val kept = derived.args.takeRight(constructor.valueType.functionArity - slotTypes.size).map(Evaluator.groundToSem)
    def taking(remaining: Int, slots: Seq[SemValue]): SemValue =
      if (remaining === 0) (slots ++ kept).foldLeft(Evaluator.groundToSem(constructor))(Evaluator.applyValue)
      else SemValue.VLam(s"slot$$${slots.size + 1}", slot => taking(remaining - 1, slots :+ slot))
    (
      taking(slotTypes.size, Seq.empty),
      slotTypes.foldRight(metaType)((slot, rest) =>
        GroundValue.Structure(WellKnownTypes.functionDataTypeFQN, Seq(slot, rest), GroundValue.Type)
      )
    )
  }

  /** The meta constructor of `metaType` ([[metaConstructorOf]]) as a companion is passed it, with its type. */
  private def plainConstructorOf(metaType: GroundValue): CompilerIO[Option[(SemValue, GroundValue)]] =
    metaConstructorOf(metaType).map(_.map(constructor => (Evaluator.groundToSem(constructor), constructor.valueType)))

  /** A stated transfer over the call's arguments. The code the callee runs is followed run by run ([[runCode]]), each
    * run handed what the callee states it hands it, and the transfer reads each as what it yields: a companion is Eliot
    * over metas and cannot apply a closure. A frame or cell statement is followed rather than reduced ([[follow]]), and
    * a loop statement is its loop's accumulator, or ⊥ for an endless one (`docs/meta.md` §11.2).
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
      def metaTypes: CompilerIO[Seq[GroundValue]] =
        companionTypeArguments(callee, companion, typeArguments, arguments.size, withResult = false)
          .map(_.getOrElse(Seq.empty))
      statement.primitive match {
        case Some(MetaStatement.Primitive.Looped(_, code)) =>
          metaTypes.flatMap(types =>
            runCode(callee, typeArguments, arguments, types, statement.primitive, run).map((after, values) =>
              (after, values.lift(code).getOrElse(Value.unknown))
            )
          )
        case Some(MetaStatement.Primitive.Endless(_))      =>
          metaTypes.flatMap(types =>
            runCode(callee, typeArguments, arguments, types, statement.primitive, run).map((after, _) =>
              (after, Value.Never)
            )
          )
        case Some(primitive)                               =>
          follow(callee, typeArguments, statement, primitive, arguments, run).map((after, value) =>
            (after.unfollowed(arguments.filter(isCode)), value)
          )
        case None                                          =>
          metaTypes
            .flatMap { metaTypes =>
              runCode(callee, typeArguments, arguments, metaTypes, None, run).flatMap { (after, values) =>
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
    * writing one, or building a value, whose meta is the statement reduced over its fields' (§13.4). One the
    * interpretation cannot place — a frame or cell it does not hold — is followed as unknown, so every frame may have
    * been left through and every cell written ([[Store.tainted]]).
    */
  private def follow(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      statement: MetaStatement,
      primitive: MetaStatement.Primitive,
      arguments: Seq[Value],
      run: Run
  ): CompilerIO[(Run, Value)] =
    primitive match {
      case MetaStatement.Primitive.Built if arguments.size == statement.parameters.size =>
        reduceTransfer(callee, typeArguments, arguments.map(_.known)).map(meta => (run, Value.Data(callee, arguments, meta)))
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

  /** Follow every code argument of a stated call, each run once (`docs/meta.md` §11.1, §14), or a loop's code until the
    * loop is stable (§11.2), and answer the call's argument values with each code argument replaced by what its run
    * yields, a loop's code by its loop's accumulator.
    *
    * A run is handed, per argument of its code, what the callee's handed statement says: a fresh frame or cell, what
    * another run's exits carried or its cell held, or a meta reduced over the arguments, a code argument read as what its
    * run yields. A run reading another starts where that one left off, after its return or after its exits, and is not
    * made at all when it is handed ⊥: a handler whose frame nothing left through. Every other run starts at the call,
    * and those are the callee's alternatives, one of which runs: `fold`'s arms, an eliminator's. The store after the
    * call joins what the runs no other run continues end with.
    *
    * A code argument a loop statement names, `looped(initial, code)` or `endless(code)` on the result (`result`) or on
    * what some code is handed, is the loop's: it is not one of the alternatives but runs again and again from the call
    * ([[loop]]), and what reads `looped` reads the accumulator the loop may end with.
    */
  private def runCode(
      callee: ValueFQN,
      typeArguments: Seq[GroundValue],
      arguments: Seq[Value],
      metaTypes: Seq[GroundValue],
      result: Option[MetaStatement.Primitive],
      run: Run
  ): CompilerIO[(Run, Seq[Value])] = {
    val code = arguments.indices.filter(index => isCode(arguments(index))).toList
    if (code.isEmpty) (run, arguments).pure[CompilerIO]
    else
      handedStatementsOf(callee).flatMap { statements =>
        // The code each loop runs, with the position of the loop's initial accumulator (none for an endless one).
        val loops: Map[Int, Option[Int]] =
          (result.toSeq ++ statements.values.flatMap(_._2.primitive)).collect {
            case MetaStatement.Primitive.Looped(initial, code) => code -> Some(initial)
            case MetaStatement.Primitive.Endless(code)         => code -> None
          }.toMap


        def ensure(position: Int, runs: Runs, visiting: Set[Int]): CompilerIO[Runs] =
          if (runs.made.contains(position) || visiting.contains(position)) runs.pure[CompilerIO]
          else {
            val closure = arguments(position).asInstanceOf[Value.Closure]
            Located.at(closure.address).flatMap { located =>
              val functionType = located.node.value.expressionType
              val domains      = peel(functionType, functionType.functionArity).map(_._1).getOrElse(Seq.empty)
              val handed       = domains.indices.map(argument => statements.get((position, argument)).map(_._2))
              handed.flatten
                .flatMap(readsRuns(_, position))
                .filter(other => other != position && code.contains(other))
                .distinct
                .toList
                .foldLeftM(runs)((before, other) => ensure(other, before, visiting + position))
                .flatMap(ready => made(position, closure, functionType.deepReturnType, domains, handed, ready))
            }
          }

        def made(
            position: Int,
            closure: Value.Closure,
            returned: GroundValue,
            domains: Seq[GroundValue],
            handed: Seq[Option[MetaStatement]],
            runs: Runs
        ): CompilerIO[Runs] =
          if (loops.contains(position)) loop(position, closure, returned, domains, handed, runs)
          else starts(handed.flatten, position, runs) match {
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
                      case Handed.Is(value)   => value
                      case Handed.NewFrame    => Value.Frame(frame.get)
                      case Handed.NewCell(_)  => Value.Cell(cell.get)
                      case Handed.Accumulator => Value.unknown
                    }
                    applyValue(closure, handedTo, returned, Run(runs.budget, runs.fresh + 2, prepared))
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

        // A loop's code, run from the call until neither the accumulator nor any cell changes (`docs/meta.md` §11.2):
        // each run is handed the accumulator so far, and what it yields and the cells it ends with are joined into the
        // accumulator and the cells the next run starts with after the first run, which keeps a step yielding a
        // constant exact, and widened into them after every later run, which makes the loop stop. A run that does not
        // return continues nothing, but its exits are kept, each with the cells it left with. The loop may stop after
        // any number of runs, none included, so it ends with the stable cells and accumulator. A loop that cannot be
        // followed — its code is handed a frame or a cell of its own — is not.
        def loop(
            position: Int,
            closure: Value.Closure,
            returned: GroundValue,
            domains: Seq[GroundValue],
            handed: Seq[Option[MetaStatement]],
            runs: Runs
        ): CompilerIO[Runs] = {
          val initial = loops(position)
          val start   = initial.fold[Value](Value.unknown)(index => arguments.lift(index).getOrElse(Value.unknown))
          val accType = initial.flatMap(metaTypes.lift)
          val alive   = run.store.cells.keySet
          def ended(yields: Value, end: Store, budget: Int, fresh: Int): Runs =
            Runs(runs.made + (position -> CodeRun(yields, end, Seq.empty, None, Start.AtCall)), budget, fresh)
          def iterate(acc: Value, store: Store, budget: Int, fresh: Int, values: Seq[Handed], first: Boolean)
              : CompilerIO[Runs] = {
            val handedTo = values.map {
              case Handed.Is(value)   => value
              case Handed.Accumulator => acc
              case _                  => Value.unknown
            }
            applyValue(closure, handedTo, returned, Run(budget, fresh, store)).flatMap { (after, yields) =>
              val frames = after.store.frames.view
                .mapValues(_.map(exit => exit.copy(cells = exit.cells.view.filterKeys(alive.contains).toMap)).distinct)
                .toMap
              val next   =
                if (yields == Value.Never) (acc, store.cells).pure[CompilerIO]
                else
                  (
                    if (initial.isEmpty) acc.pure[CompilerIO] else widenValue(acc, yields, accType, !first),
                    widenCells(store.cells, after.store.cells, !first)
                  ).tupled
              next.flatMap { (nextAcc, cells) =>
                val nextStore = Store(cells, frames)
                if (nextAcc == acc && nextStore == store)
                  ended(initial.fold(Value.Never)(_ => nextAcc), nextStore, after.budget, after.fresh).pure[CompilerIO]
                else if (after.budget <= 0)
                  // Not stable, and no step is left to make it so: what the loop has so far is not its fixpoint.
                  ended(Value.unknown, nextStore.tainted, -1, after.fresh).pure[CompilerIO]
                else iterate(nextAcc, nextStore, after.budget, after.fresh, values, first = false)
              }
            }
          }
          handedValues(position, domains, handed, runs).flatMap { values =>
            if (values.contains(Handed.Is(Value.Never)))
              ended(initial.fold(Value.Never)(_ => start), run.store, runs.budget, runs.fresh).pure[CompilerIO]
            else if (values.exists(value => value == Handed.NewFrame || value.isInstanceOf[Handed.NewCell]))
              ended(Value.unknown, run.store.tainted, runs.budget, runs.fresh).pure[CompilerIO]
            else iterate(start, run.store, runs.budget, runs.fresh, values, first = true)
          }
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
                handedValue(companion, statement, position, argument, domains(argument), runs)
            }
          }.map(_.toSeq)

        def handedValue(
            companion: ValueFQN,
            statement: MetaStatement,
            position: Int,
            argument: Int,
            domain: GroundValue,
            runs: Runs
        ): CompilerIO[Handed] =
          statement.primitive match {
            case Some(field: MetaStatement.Primitive.FieldOf) =>
              fieldHanded(callee, typeArguments, companion, statement, field, argument, domain, arguments)
                .map(Handed.Is(_))
            case Some(MetaStatement.Primitive.Frame)         => (Handed.NewFrame: Handed).pure[CompilerIO]
            case Some(MetaStatement.Primitive.Cell(initial)) =>
              (Handed.NewCell(Store.Content(arguments.lift(initial).flatMap(_.known), metaTypes.lift(initial))): Handed)
                .pure[CompilerIO]
            case Some(MetaStatement.Primitive.Exited(other)) =>
              runs.made.get(other) match {
                case Some(made) if made.exits.isEmpty => (Handed.Is(Value.Never): Handed).pure[CompilerIO]
                case Some(made)                       =>
                  metaTypeOf(domain).flatMap(metaType =>
                    joinAll(made.exits.map(_.carried), metaType).map(meta => Handed.Is(Value.Meta(meta)))
                  )
                case None                             => (Handed.Is(Value.unknown): Handed).pure[CompilerIO]
              }
            case Some(MetaStatement.Primitive.Stored(other)) =>
              (runs.made.get(other) match {
                case Some(made) if made.yields == Value.Never => Handed.Is(Value.Never)
                case Some(made)                               => Handed.Is(Value.Meta(made.content.flatMap(_.meta)))
                case None                                     => Handed.Is(Value.unknown)
              }: Handed).pure[CompilerIO]
            case Some(MetaStatement.Primitive.Looped(_, other)) if other == position =>
              (Handed.Accumulator: Handed).pure[CompilerIO]
            case Some(MetaStatement.Primitive.Looped(_, other)) =>
              (Handed.Is(runs.made.get(other).fold(Value.unknown)(_.yields)): Handed).pure[CompilerIO]
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

        def valueOf(index: Int, runs: Runs): Value =
          runs.made.get(index).fold(arguments.lift(index).getOrElse(Value.unknown))(_.yields)

        def starts(handed: Seq[MetaStatement], position: Int, runs: Runs): Option[Start] =
          handed.flatMap { statement =>
            statement.primitive match {
              case Some(MetaStatement.Primitive.Exited(other))    => Seq(Start.AfterExitsOf(other))
              case Some(MetaStatement.Primitive.Stored(other))    => Seq(Start.AfterReturnOf(other))
              case Some(MetaStatement.Primitive.Looped(_, other)) =>
                Seq(other).filter(_ != position).map(Start.AfterReturnOf(_))
              case Some(_)                                        => Seq.empty
              case None                                           =>
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

  /** What a callee hands one argument of a run of code: a value, a frame or cell to make for the run, or the
    * accumulator of the loop the run is an iteration of.
    */
  private enum Handed {
    case Is(value: Value)
    case NewFrame
    case NewCell(content: Store.Content)
    case Accumulator
  }

  /** The runs of code a handed statement, about the code at `position`, continues: the code whose exits or cell it
    * reads, whose yield, or the loop whose accumulator it is handed when that loop is not its own.
    */
  private def readsRuns(statement: MetaStatement, position: Int): Seq[Int] = statement.primitive match {
    case Some(MetaStatement.Primitive.Exited(other))     => Seq(other)
    case Some(MetaStatement.Primitive.Stored(other))     => Seq(other)
    case Some(MetaStatement.Primitive.Looped(_, other)) => Seq(other).filter(_ != position)
    case Some(_)                                         => Seq.empty
    case None                                            => statement.reads.toSeq
  }

  /** A loop's accumulator after one more run yielded `next` (`docs/meta.md` §11.2): unchanged when `next` is the same
    * value, else the two metas joined, or `widening` widened, by the meta type's own instance at `metaType` — a
    * constructed value's included, which is its structural meta from then on (§13). A pair one of whose metas is not
    * computed is not computed.
    */
  private def widenValue(previous: Value, next: Value, metaType: Option[GroundValue], widening: Boolean)
      : CompilerIO[Value] =
    (previous.known, next.known, metaType) match {
      case _ if previous == next                     => previous.pure[CompilerIO]
      case (Some(before), Some(after), Some(tpe))    => widened(before, after, tpe, widening).map(Value.Meta(_))
      case _                                         => Value.unknown.pure[CompilerIO]
    }

  /** The cells a loop's next run starts with: each of `previous`'s, joined with, or `widening` widened by, what the last
    * run left in it.
    */
  private def widenCells(
      previous: Map[Int, Store.Content],
      next: Map[Int, Store.Content],
      widening: Boolean
  ): CompilerIO[Map[Int, Store.Content]] =
    previous.toList
      .traverse { (id, before) =>
        (before.meta, next.get(id).flatMap(_.meta), before.metaType) match {
          case (Some(was), Some(is), _) if was == is          => (id -> before).pure[CompilerIO]
          case (Some(was), Some(is), Some(tpe))               =>
            widened(was, is, tpe, widening).map(meta => id -> before.copy(meta = meta))
          case _                                              => (id -> before.copy(meta = None)).pure[CompilerIO]
        }
      }
      .map(_.toMap)

  /** `previous` joined with `next`, or `widening` widened by it: `eliot.compiler.Meta::joined` or `widened` at
    * `metaType`.
    */
  private def widened(
      previous: GroundValue,
      next: GroundValue,
      metaType: GroundValue,
      widening: Boolean
  ): CompilerIO[Option[GroundValue]] =
    EscalatingReducer.reduceApplied(
      if (widening) WellKnownTypes.metaWidenedFQN else WellKnownTypes.metaJoinedFQN,
      Seq(metaType),
      Seq(previous, next).map(Evaluator.groundToSem)
    )

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
    * — **no body on this track** (a leaf that states nothing, or a value whose monomorphization failed upstream), or
    * **not saturated** (a partial application is a function value) — is not followed ([[Run.unfollowed]]). A leaf
    * stating nothing states the implicit default ([[implicitStatement]]).
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
        case Some(mv) =>
          (Located.root(mv), mv.naturalArity) match {
            case (Some(body), Some(arity)) if arity <= arguments.size =>
              applyLambda(body, Map.empty, arguments, resultType, run.spent)
            case (None, _)                                           =>
              implicitStatement(resultType).map(meta => (run.unfollowed(arguments), Value.Meta(meta)))
            case _                                                   => (run.unfollowed(arguments), Value.unknown).pure[CompilerIO]
          }
        case None     => (run.unfollowed(arguments), Value.unknown).pure[CompilerIO]
      }

  /** Interpret one node of a body: what is known of its value, and the interpretation's progress after it. Bottom-up and
    * pure — no record is kept and no diagnostic is raised, so this may be run over a *callee's* body without touching
    * that callee's own table or repeating its checks. A function-typed node is not interpreted here but deferred as a
    * closure, so only a function value that is applied costs anything.
    */
  private def interpret(
      located: Located,
      env: Env,
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (run.budget <= 0) run.exhausted.pure[CompilerIO]
    else
      located.node.value.expression match {
        case MonomorphicExpression.ParameterReference(parameterName)   =>
          // The one point the caller's knowledge enters: a bound parameter *is* the argument. An unbound one (a lambda's
          // own binder that nothing applied) is ⊤.
          (run.spent, env.getOrElse(parameterName.value, Value.unknown)).pure[CompilerIO]
        case _ if isFunction(located.node)                             =>
          (run.spent, closure(located, env)).pure[CompilerIO]
        case MonomorphicExpression.IntegerLiteral(value)               =>
          integerSeed(value.value).map(meta => (run.spent, Value.Meta(meta)))
        case MonomorphicExpression.StringLiteral(value)                =>
          stringSeed(value.value).map(meta => (run.spent, Value.Meta(meta)))
        case MonomorphicExpression.MonomorphicValueReference(vfqn, ta) =>
          // A bare reference is a *saturated* call when the callee takes no arguments, so a nullary def derives here
          // exactly as an applied one does below.
          metaOf(vfqn.value, ta, located.node.value.expressionType, Seq.empty, run.spent)
        case MonomorphicExpression.FunctionLiteral(_, _, _)            =>
          (run.spent, closure(located, env)).pure[CompilerIO]
        case _: MonomorphicExpression.FunctionApplication              =>
          val (head, args) = flatten(located)
          interpretAll(args, env, run.spent).flatMap((after, arguments) =>
            applyHead(head, env, arguments, located.node.value.expressionType, after)
          )
      }

  /** Apply the head of an application, written in `env`, to `arguments`: a def is called, a lambda binds its binders, and
    * a parameter is applied as whatever it is bound to. `resultType` is the type of the whole application. Evaluation is
    * strict, so an application one of whose arguments is ⊥ is never made, and is ⊥ itself — a `val` block included,
    * which lowers to an applied lambda. A code argument is a closure, never ⊥: whether it returns is its callee's
    * business.
    */
  private def applyHead(
      head: Located,
      env: Env,
      arguments: Seq[Value],
      resultType: GroundValue,
      run: Run
  ): CompilerIO[(Run, Value)] =
    if (arguments.contains(Value.Never)) (run, Value.Never).pure[CompilerIO]
    else
      head.node.value.expression match {
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
      located: Located,
      env: Env,
      arguments: Seq[Value],
      resultType: GroundValue,
      run: Run
  ): CompilerIO[(Run, Value)] = {
    val (body, bound, rest) = bindParameters(located, arguments)
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
      case Value.Closure(address, env)            =>
        Located.at(address).flatMap { located =>
          val (head, applied) = flatten(located)
          interpretAll(applied, env, run.spent).flatMap((after, values) =>
            applyHead(head, env, values ++ arguments, resultType, after)
          )
        }
      case _                                      => (run.unfollowed(value +: arguments), Value.unknown).pure[CompilerIO]
    }

  /** Interpret every argument in the *caller's* scope, in order, threading the interpretation's progress through them,
    * so a wide fan-out spends the same budget a deep chain does and a later argument sees the cells an earlier one
    * wrote. Evaluation is strict, so the arguments after one that is ⊥ are never evaluated. Arguments are all
    * interpreted before any binder is added, which is what keeps a binder from shadowing a name a later argument reads.
    */
  private def interpretAll(
      nodes: Seq[Located],
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
      located: Located,
      arguments: Seq[Value],
      bound: Env = Map.empty
  ): (Located, Env, Seq[Value]) =
    (located.node.value.expression, arguments) match {
      case (MonomorphicExpression.FunctionLiteral(parameterName, _, body), argument +: rest) =>
        bindParameters(located.down(Address.Step.Body, body), rest, bound + (parameterName.value -> argument))
      case _                                                                                 => (located, bound, arguments)
    }

  /** A function value written in `env`, which keeps only what it can read: the parameters its node mentions. Two
    * closures over the same node are then equal whenever what they read is, which is what lets one [[DerivedMeta]]
    * answer every call taking one.
    */
  private def closure(located: Located, env: Env): Value.Closure = {
    val mentioned = parametersMentioned(located.node.value.expression)
    Value.Closure(located.address, env.view.filterKeys(mentioned.contains).toMap)
  }

  private def parametersMentioned(expression: MonomorphicExpression.Expression): Set[String] = expression match {
    case MonomorphicExpression.ParameterReference(name)               => Set(name.value)
    case MonomorphicExpression.FunctionApplication(target, argument) =>
      parametersMentioned(target.value.expression) ++ parametersMentioned(argument.value.expression)
    case MonomorphicExpression.FunctionLiteral(_, _, body)            => parametersMentioned(body.value.expression)
    case _                                                            => Set.empty
  }

  private def isFunction(node: Sourced[MonomorphicExpression]): Boolean =
    node.value.expressionType.asFunctionType.isDefined

  /** A finished interpretation's value, as a caller outside it reads one: its meta, or ⊥. A closure, a frame, a cell
    * and a constructed value are the interpretation's own, gone with the call that made them, so only their meta is
    * left.
    */
  private def settled(value: Value): Value = value match {
    case Value.Never => value
    case other       => Value.Meta(other.known)
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
    * that result meta type, first ([[MetaCompanionLayout.constructorParameterName]]), at its own type, as
    * `constructorOf` gives it — [[metaConstructorOf]]'s, but for a bodied value's brace over a slotted `data` result
    * ([[restatingConstructorOf]]); a result whose meta type is `Unit` has none, so its transfer is ⊤ — R2 reports a leaf
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
      argMetas: Seq[Option[GroundValue]],
      constructorOf: GroundValue => CompilerIO[Option[(SemValue, GroundValue)]] = plainConstructorOf
  ): CompilerIO[Option[GroundValue]] = {
    val companion = RefinementChannelProcessor.metaCompanionFqn(callee)
    companionTypeArguments(callee, companion, calleeTypeArgs, argMetas.size, withResult = true).flatMap {
      case Some(metaTypes) =>
        val arguments = argMetas.map(companionArgument)
        def reduce(typeArguments: Seq[GroundValue], leading: Seq[SemValue]): CompilerIO[Option[GroundValue]] =
          EscalatingReducer
            .reduceApplied(companion, typeArguments, leading ++ arguments)
            .map(_.collect { case s: GroundValue.Structure => s })
        takesConstructor(companion).ifM(
          constructorOf(metaTypes.last).flatMap {
            case Some((constructor, constructorType)) =>
              reduce(calleeTypeArgs ++ metaTypes :+ constructorType, Seq(constructor))
            case None                                 => none[GroundValue].pure[CompilerIO]
          },
          reduce(calleeTypeArgs ++ metaTypes, Seq.empty)
        )
      case None            => none[GroundValue].pure[CompilerIO]
    }
  }

  /** What a companion is passed for an argument: its meta when computed, otherwise [[notComputed]], never a value that
    * could quote.
    */
  private[channel] def companionArgument(meta: Option[GroundValue]): SemValue =
    meta.fold(notComputed)(Evaluator.groundToSem)

  /** The argument a companion is passed for a meta that is not computed (`docs/meta.md` §13.5): a dedicated neutral that
    * never quotes, so a result holding it, projected or stored, is not computed either.
    */
  private val notComputed: SemValue =
    SemValue.VNeutral(SemValue.NeutralHead.Reserved(SemValue.NeutralHead.Marker.NotComputed), SemValue.Spine.SNil)

  /** The meta *value* constructor of `metaType`, as a ground reference typed by its own signature at the meta type's
    * arguments (a function from the slots to the meta type) — or `None` for a meta type with no slots to construct it
    * from. It is the value constructor [[MetaConstructorDesugarer]] or [[StructuralMetaDesugarer]] declares beside a meta
    * structure, of the same name, so a meta type without one has no slots.
    */
  private def metaConstructorOf(metaType: GroundValue): CompilerIO[Option[GroundValue.Structure]] = metaType match {
    case GroundValue.Structure(fqn, typeArguments, _) =>
      val constructor = ValueFQN(fqn.moduleName, QualifiedName(fqn.name.name, Qualifier.Default))
      getFactOrAbort(UnifiedModuleNames.Key(fqn.moduleName, Platform.Compiler)).flatMap { names =>
        if (names.names.contains(constructor.name))
          getFactOrAbort(CompilerMonomorphicValue.Key(constructor, typeArguments))
            .map(cmv => GroundValue.Structure(constructor, Seq.empty, cmv.signature).some)
        else none[GroundValue.Structure].pure[CompilerIO]
      }
    case _                                            => none[GroundValue.Structure].pure[CompilerIO]
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
            (if (withResult) parameters :+ result else parameters).traverse(metaTypeOf(_)).map(_.sequence)
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

  /** The **meta type** of a base type: its `$Meta` meta structure if the type declares slots (`Int` ⤳ `Int$Meta`); for
    * a `data` type its structural meta (`docs/meta.md` §13.2), `D$Meta` at the meta types of its fields' types, which
    * its `D$Fields` signature gives at the type's arguments; else the implicit default's [[unitType]] (an untracked type
    * carries no refinement). This is the total-meta rule of R1: a generic `^Meta` companion reduced at these always
    * lands on a real `Meta` instance. A non-structure type argument is left unchanged.
    *
    * `None` where there is no finite meta type: a `data` type reaching itself other than through a field typed by the
    * type itself (§13.3) — through another type (`Option[Tree]`), or across a group — whose meta is then not computed.
    */
  private[channel] def metaTypeOf(baseType: GroundValue, expanding: Set[ValueFQN] = Set.empty): CompilerIO[Option[GroundValue]] =
    baseType match {
      case GroundValue.Structure(fqn, typeArguments, _) if expanding.contains(fqn) => none[GroundValue].pure[CompilerIO]
      case GroundValue.Structure(fqn, typeArguments, _)                            =>
        declaresFields(fqn).flatMap {
          case true  =>
            EscalatingReducer
              .reduceApplied(ValueFQN(fqn.moduleName, fieldsName(fqn)), typeArguments, Seq.empty)
              .flatMap {
                case Some(fields) =>
                  fieldTypes(fields)
                    .traverse(metaTypeOf(_, expanding + fqn))
                    .map(_.sequence.map(GroundValue.Structure(metaStructureFqn(fqn), _, GroundValue.Type)))
                case None         => none[GroundValue].pure[CompilerIO]
              }
          case false =>
            declaresSlots(fqn).flatMap {
              case true  => slottedMetaType(fqn, typeArguments, expanding)
              case false => Some(unitType).pure[CompilerIO]
            }
        }
      case other                                                                   => other.some.pure[CompilerIO]
    }

  /** The meta type of a slotted type `fqn[typeArguments]`: its meta structure, applied to the meta types of the
    * parameters its slots carry (`docs/meta.md` §17.1, decision L), which its `T$Carried` signature lists — `List$Meta[
    * Int$Meta]` for `List[Int]`. A type whose slots carry none has no such signature, and its structure takes nothing.
    */
  private def slottedMetaType(
      fqn: ValueFQN,
      typeArguments: Seq[GroundValue],
      expanding: Set[ValueFQN]
  ): CompilerIO[Option[GroundValue]] = {
    val carried = ValueFQN(fqn.moduleName, MetaConstructorDesugarer.carriedName(fqn.name.name))
    getFactOrAbort(UnifiedModuleNames.Key(fqn.moduleName, Platform.Compiler)).flatMap { names =>
      if (!names.names.contains(carried.name))
        Some(GroundValue.Structure(metaStructureFqn(fqn), Seq.empty, GroundValue.Type)).pure[CompilerIO]
      else
        EscalatingReducer.reduceApplied(carried, typeArguments, Seq.empty).flatMap {
          case Some(types) =>
            fieldTypes(types)
              .traverse(metaTypeOf(_, expanding + fqn))
              .map(_.sequence.map(GroundValue.Structure(metaStructureFqn(fqn), _, GroundValue.Type)))
          case None        => none[GroundValue].pure[CompilerIO]
        }
    }
  }

  /** The field types a reduced `D$Fields` signature lists: the domains of its arrows, which end in `Unit`. */
  @tailrec
  private def fieldTypes(fields: GroundValue, types: Seq[GroundValue] = Seq.empty): Seq[GroundValue] =
    fields.asFunctionType match {
      case Some((field, rest)) => fieldTypes(rest, types :+ field)
      case None                => types
    }

  /** Whether a *node's* type carries meta-information at all: it declares slots, or it is a `data` type, whose values
    * carry a structural meta (`docs/meta.md` §13).
    */
  private[channel] def carriesMeta(nodeType: GroundValue): CompilerIO[Boolean] = nodeType match {
    case GroundValue.Structure(fqn, _, _) => (declaresSlots(fqn), declaresFields(fqn)).mapN(_ || _)
    case _                                => false.pure[CompilerIO]
  }

  private def metaStructureName(typeName: ValueFQN): QualifiedName =
    QualifiedName(typeName.name.name + MetaConstructorDesugarer.metaTypeSuffix, Qualifier.Type)

  private def metaStructureFqn(typeName: ValueFQN): ValueFQN = ValueFQN(typeName.moduleName, metaStructureName(typeName))

  private def fieldsName(typeName: ValueFQN): QualifiedName = StructuralMetaDesugarer.fieldsName(typeName.name.name)

  private def slotsName(typeName: ValueFQN): QualifiedName = StructuralMetaDesugarer.slotsName(typeName.name.name)

  /** The `BigInteger` type a literal's seed value carries — the only literal-domain constant the interpretation needs,
    * to wrap a raw `n` as a [[GroundValue.Direct]] when reducing a seed companion at it.
    */
  private val bigIntType: GroundValue =
    GroundValue.Structure(WellKnownTypes.bigIntFQN, Seq.empty, GroundValue.Type)

  /** What a leaf stating nothing states of a call returning `resultType`: the implicit default when that type declares
    * no meta, and nothing otherwise — R2 makes a leaf returning a meta-carrying type state, so a leaf left here is one R2
    * exempts, whose meta is not computed.
    */
  private def implicitStatement(resultType: GroundValue): CompilerIO[Option[GroundValue]] =
    carriesMeta(resultType).map(carries => Option.unless(carries)(untrackedMeta))

  private val unitModule: ModuleName = ModuleName(ModuleName.defaultSystemPackage, "Unit")

  /** **The implicit default** (`docs/meta.md` §1): a type declaring no meta has the meta type `Unit`, and every value of
    * it the meta `unit`. These two are the only place the channel knows `Unit`; everything else reads them as an
    * ordinary meta type and an ordinary meta.
    */
  private val unitType: GroundValue =
    GroundValue.Structure(ValueFQN(unitModule, QualifiedName("Unit", Qualifier.Type)), Seq.empty, GroundValue.Type)

  /** The meta of every value whose type declares none: `unit`, of the implicit default's meta type ([[unitType]]). */
  private[channel] val untrackedMeta: GroundValue =
    GroundValue.Structure(WellKnownTypes.unitValueFQN, Seq.empty, unitType)
}
