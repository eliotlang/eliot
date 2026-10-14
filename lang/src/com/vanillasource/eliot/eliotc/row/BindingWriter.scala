package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{EffectRow, ImplementationParameters}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{Qualifier, Role, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.collection.mutable

/** **The write** — effects v6, `docs/effects.md` §9.4 step 3. Rewrites one definition so that every reference carries
  * the implementation each of the callee's **phantom binders** stands for.
  *
  * It replaces `RowElaborator`. There is no carrier to solve for, so there is no `flatMap` to hoist, no `pure` or
  * `runId` to insert and no discharge stack to derive: an operation call is an ordinary call, and the only thing
  * missing from it is which implementation it runs on. Three jobs, one walk:
  *
  *   1. **write the bindings.** At each reference the callee's binding binders are read off the **marks** its
  *      declaration carries — `Impl: Implementation[Console]` — and each is given a value by the resolution order
  *      below, merged by index with whatever else the call determines. A binder is never left to a metavariable.
  *   2. **thunk and apply.** A row-typed slot lowered to `Unit => A` (§9.4 step 2), so an actual delivered there is
  *      wrapped in a lambda, and a reference to one of *this* definition's row-typed parameters is applied to `unit`.
  *      Doing both unconditionally is what makes a pass-through (`runAbort(computation)`,
  *      `foldOption(fallback, …)`, `val restFailures = rest`) come out right with no inspection of the argument's
  *      shape or type: wrap and apply are inverse, so a pass-through is an η-expansion and nothing more.
  *   3. **erase `with`.** The node exists to put a binding in scope for its subject; once the subject's references
  *      carry it, it is dropped — from a body, and from a slot's type in the signature.
  *
  * **Where a binding comes from — the resolution order** (§9.4), walking outward lexically:
  *
  *   1. the nearest enclosing `with` for that ability;
  *   2. this definition's own phantom binder for it — a *received* binding, filled by its caller. There is no graph to
  *      sum: forwarding is the enclosing signature, read once;
  *   3. for an actual at a row-typed slot, the entries that slot **supplies** — bound by the slot's own `with`, or by
  *      `Default` with none. An entry the callee's own declared row already has is *not* supplied, and the walk
  *      continues outward (Part I's supplied-versus-rides rule, §2.2, unchanged);
  *   4. `Default` — "search at the ground arguments", today's two-site resolution — for an ordinary **ability**. For
  *      an **effect** there is no default: an uncovered one is the "performs but does not declare" error, reported
  *      here at the reference, because this walk is exactly the scope check's walk.
  *
  * **Effect-ness is read from one place only**: the callee's declared row. An ability appearing in
  * `effectRow.returnEffects` is an effect at this reference — which for an `effect`'s member is what membership
  * recorded ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]) and for an ordinary definition is what its
  * `{ … }` says. A `~` constraint's ability is in no row, so it defaults. Nothing keys on a name or a shape.
  *
  * **Code and values** (`docs/effects.md` D21, rules 2 and 3). A parameter whose type carries a row — a top-level
  * one (`body: {} A`) or one in its codomain (`action: A => {} Unit`) — is **code**: the caller's text, run by the
  * callee. Every other position is a **value**, and a value is pure. So:
  *
  *   - a lambda written as the argument of a code parameter, or applied where it stands, is the caller's code and
  *     sees the caller's bindings; a lambda anywhere else — a rowless function parameter, a field, a generic slot, a
  *     `val` — is a value, and may use no effect bound outside it ([[Scope.enterValue]]). It may still bind and
  *     discharge its own, and an ordinary ability (a `~` constraint) is not an effect and stays reachable;
  *   - this definition's own code parameters are **used, never kept**: a reference to one is the head of a call or
  *     the argument of another code parameter, and nothing else — not an argument at a value slot, not a result, and
  *     not a capture by a value lambda, which would carry the code past the call that bound it.
  *
  * A `match` is lowered to lambdas before this phase ([[isMatchMachinery]]): its arms are the author's code in place,
  * so they are walked as code, not as the values their lowering makes them look like.
  */
object BindingWriter {

  /** @param value
    *   The definition with its body written and its signature stripped of slot `with`s.
    * @param violations
    *   What the walk could not answer, each at its own position. A violation aborts the definition: an unwritten
    *   binding would silently run on the platform's default.
    * @param overDeclarations
    *   The declared effects the body never performs ([[overDeclared]]). Kept apart from [[violations]] because their
    *   usual cause is somewhere else — a callee performing an effect it does not declare leaves its caller's
    *   declaration unconsumed — so the processor reports them only once the callees are known to be sound.
    */
  case class Written(value: OperatorResolvedValue, violations: Seq[Violation], overDeclarations: Seq[Violation])

  case class Violation(message: Sourced[String], help: Seq[String] = Seq.empty)

  /** A binding in scope: an implementation for one ability, and the term naming it.
    *
    * @param byWith
    *   Whether a `with` **chose** this implementation here — in a body, or on a slot's type, the construct's two
    *   positions. False for a binding this definition merely *forwards*: its own received binder, and the `Default` a
    *   slot supplies. The difference matters in exactly one place, [[Writer.checkGiven]]: a computation this
    *   definition was handed was promised the default, so a slot that binds another implementation breaks the promise.
    * @param bySlot
    *   Whether a callee's row slot **supplies** this binding to the actual written there — resolution-order step 3.
    *   This is what a frame looks like from the outside, and the one thing a definition may hand to a computation it
    *   was given ([[Writer.checkGiven]]).
    * @param received
    *   Whether this is the definition's own **received** binding — resolution-order step 2, a row entry its caller
    *   fills. Only these are tracked as consumed ([[Writer.consumed]]), because only a declared entry can be declared
    *   for nothing.
    * @param arguments
    *   The type arguments of the row entry this binding answers, where they are **known** ([[knownArguments]]):
    *   `Throw[Refused]` at a `catch[Refused, String]` slot, `Throw[Missing]` received from a row that spells it. A
    *   binding answers a reference to the same ability unless both sides know their arguments and they differ, so the
    *   slot's `Throw[Refused]` does not shadow the received `Throw[Missing]` ([[Scope.binding]]). `None` — an entry still
    *   generic in some binder, or a `with` — answers any.
    * @param clauses
    *   For a body `with`'s implementation, the [[serial]] of the binding in scope at the `with` for each entry its
    *   clauses perform, with the entry's known arguments: the environment threading builds that implementation's frame
    *   where it is used, from the frames in scope there, so they must be the ones at the `with`
    *   ([[Writer.checkClauses]]).
    * @param serial
    *   Tells two bindings apart however alike their terms are, and survives the copies [[Scope.enterValue]] makes.
    */
  private case class Binding(
      ability: AbilityFQN,
      term: OperatorResolvedExpression,
      byWith: Boolean = false,
      received: Boolean = false,
      beyondValue: Boolean = false,
      bySlot: Boolean = false,
      arguments: Option[Seq[String]] = None,
      clauses: Seq[Binding.Clause] = Seq.empty,
      serial: Long = Binding.nextSerial()
  )

  private object Binding {

    /** One entry a `with`'s implementation performs in its clauses, and the binding that answered it at the `with`. */
    case class Clause(ability: AbilityFQN, arguments: Option[Seq[String]], serial: Option[Long])
    private val serials = new java.util.concurrent.atomic.AtomicLong()

    def nextSerial(): Long = serials.incrementAndGet()
  }

  /** An entry a run of a code parameter must be given, and the implementation named on its slot whose clauses perform
    * it, when that is where it comes from.
    */
  private case class Need(ability: AbilityFQN, arguments: Option[Seq[String]], clauseOf: Option[ValueFQN] = None)

  /** The lexical environment at one point. `bindings` is innermost-first, so a nearer `with` shadows an outer one for
    * the same ability; `thunks` are the row-typed parameters in scope, whose references apply.
    *
    * @param uncoveredDefaults
    *   Whether an **effect** with nothing in scope binds `Default` instead of being reported. True in exactly two
    *   regions, and both because something other than this definition's row answers for the effect: a platform **run
    *   boundary**, where every effect's chain ends (§9.5), and a **signature**, whose `raise`/`abort` is the guard
    *   channel's vocabulary and is discharged by the guarded-return read, not performed at runtime at all.
    * @param signature
    *   Whether this is a signature, whose references are evaluated rather than checked, so a type-argument list there
    *   may not hold the checker's hole ([[Writer.mergeTypeArguments]]).
    * @param clausesGiven
    *   Inside an actual at a slot that names an implementation with `with`: each entry that implementation's clauses
    *   perform and the callee supplies, by implementation. The callee gives them where it runs the actual, so a run of
    *   this definition's code whose own slot names the same implementation is given them there
    *   ([[Writer.checkGiven]]).
    */
  private case class Scope(
      bindings: Seq[Binding],
      thunks: Set[String],
      uncoveredDefaults: Boolean = false,
      callbacks: Set[String] = Set.empty,
      captured: Set[String] = Set.empty,
      thunkNeeds: Map[String, Seq[Need]] = Map.empty,
      closed: Boolean = false,
      ridden: Map[String, Set[AbilityFQN]] = Map.empty,
      signature: Boolean = false,
      clausesGiven: Seq[Need] = Seq.empty
  ) {
    def bind(binding: Binding): Scope = copy(bindings = binding +: bindings)
    def shadow(name: String): Scope   =
      copy(thunks = thunks - name, callbacks = callbacks - name, captured = captured - name, ridden = ridden - name)

    /** The scope inside a lambda written in a **value** position. Every binding in scope so far is outside the value,
      * so an effect may no longer be taken from it ([[Writer.bindingFor]]); and every code parameter in scope would be
      * carried off by the value, so a reference to one is a capture. A region whose uncovered effects default — a
      * signature, a run boundary — is not checked: its effects are not performed at runtime by this body.
      */
    def enterValue: Scope =
      if (uncoveredDefaults) this
      else
        copy(
          bindings = bindings.map(_.copy(beyondValue = true)),
          thunks = Set.empty,
          callbacks = Set.empty,
          captured = captured ++ thunks ++ callbacks,
          closed = false
        )

    /** The scope inside an argument at a **closed** slot (`body uses Throw[E]: A`, `docs/effects.md` D21 rule 4): the
      * same cut as [[enterValue]] — nothing bound outside reaches in, and no code parameter of this definition may be
      * run or passed on there — except for the entries in `rides`, which the slot names and the callee's own row has,
      * so they continue to the caller's binding as they do at an open slot. What the slot *supplies* is bound inside
      * by the caller of this, exactly as at an open slot.
      */
    def enterClosed(rides: Set[AbilityFQN]): Scope =
      if (uncoveredDefaults) this
      else
        enterValue.copy(
          bindings = bindings.map(b => if (rides.contains(b.ability)) b else b.copy(beyondValue = true)),
          closed = true
        )

    /** This scope with `entries` bound to `Default`, each at its known arguments — what a slot's `with` resolves its
      * own bindings against, since the scope that covers them is the callee's and not this one.
      */
    def defaulting(entries: Seq[(AbilityFQN, Option[Seq[String]])], at: Sourced[?]): Scope =
      entries.foldLeft(this) { case (acc, (ability, arguments)) =>
        acc.bind(Binding(ability, defaultBinding(at), arguments = arguments))
      }

    /** The nearest binding for `ability` that answers an entry with these `arguments` — every binding for it, but one
      * known to bind the same ability at other arguments. A reference whose own arguments are unknown takes the
      * nearest, which is the fail-safe reading: it is only ever narrowed by two declarations that disagree.
      */
    def binding(ability: AbilityFQN, arguments: Option[Seq[String]] = None): Option[Binding] =
      bindings.find(b => b.ability == ability && !differ(b.arguments, arguments))
  }

  /** Writes both halves of a definition, because both hold references: the **body**, and the **signature**.
    *
    * A signature is not decoration. A guarded return (`def head[COND: Bool]: if(COND, String[]) else raise("empty")`)
    * is compile-time code in type position, and its `if`/`else`/`raise` are ordinary calls with row-typed slots and
    * phantom binders — so the same thunking and the same binding write are needed there, or the guard reaches the
    * checker as a bare `Type` at a `Unit -> Type` slot. What differs is only the scope check: a signature's effects
    * are the guard channel's, discharged by the guarded-return read rather than performed, so an uncovered one
    * defaults instead of being reported ([[Scope.uncoveredDefaults]]).
    *
    * @param atBoundary
    *   True for a platform **run boundary** ([[RunBoundaryFunctions]]) — the synthesized entry point. It is where every
    *   effect's chain ends (§9.5), so an uncovered effect is bound to the two-site `Default` there instead of being
    *   reported undeclared. Everywhere else an uncovered effect in a *body* is the error, which is the whole of the
    *   scope check.
    */
  def write(orv: OperatorResolvedValue, universe: RowChecker.Universe, atBoundary: Boolean = false): Written = {
    val writer     = new Writer(universe)
    val received   = receivedBindings(orv, universe)
    val scope      = Scope(
      received,
      thunkParameters(orv),
      atBoundary,
      callbackParameters(orv),
      thunkNeeds = thunkRequirements(orv, universe),
      ridden = riddenEntries(orv, universe)
    )
    val view       = SignatureView.of(orv.signature)
    val body       = Option
      .when(writableBody(orv))(orv.runtime)
      .flatten
      .map(writer.walkDefinition(_, scope, view.binders.size + view.parameters.size))
    val overstated = body.filterNot(_ => atBoundary).toSeq.flatMap(_ => overDeclared(orv, writer.consumed.toSet))
    val signature  =
      writer.walkDefinition(
        orv.signature,
        Scope(received, Set.empty, uncoveredDefaults = true, signature = true),
        view.binders.size
      )
    Written(
      orv.copy(runtime = body.orElse(orv.runtime), signature = signature),
      writer.violations.toSeq,
      overstated
    )
  }

  /** The entries of this definition's declared row that its body never consumes — the scope check's other direction.
    * A row says "my caller hands me an implementation of these"; an entry no reference in the body is ever written
    * from asks every caller to declare an effect that nothing here performs, and an effect declared for nothing
    * propagates all the way to `main` on no evidence.
    *
    * Consumption is read off the body walk alone, which runs first: a signature's effects are the guard channel's,
    * discharged by the guarded-return read and never performed. A received binding is consumed wherever the write
    * hands it on — to an operation, to a declaring callee, or to a named implementation whose clauses perform it. An
    * actual at a slot that does not supply the entry runs in the *caller's* scope, so running it consumes nothing
    * here: `def run[A](v: {Console} A): {Console} A = v` declares `Console` for nothing, exactly as its `{}`-slot twin
    * would.
    *
    * Only the declared row is checked, never a `~` constraint's binder, which binds an ability the body may well reach
    * only through a callee's own constraint. A body-less value has nothing to check — its row is a contract, stated where a later layer supplies the body — and
    * a run boundary performs `main`'s whole row by definition. An `implement` clause is held only to the entries it
    * [[wrote]]: it declares the union of its block's clause rows, and an entry a sibling wrote is the sibling's to
    * perform.
    */
  private def overDeclared(orv: OperatorResolvedValue, consumed: Set[AbilityFQN]): Seq[Violation] = {
    val binders = SignatureView.of(orv.signature).binders
    val effects = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    phantoms(orv)
      .filter { case (_, ability) => effects.contains(ability) && !consumed.contains(ability) }
      .distinctBy(_._2)
      .map { case (index, ability) => ability -> markPosition(binders(index).parameterType, orv) }
      .filter { case (_, entry) => wrote(orv, entry) }
      .map { case (ability, entry) =>
        Violation(
          entry.as(s"This value declares the effect '${ability.abilityName}' but does not perform it."),
          Seq(s"Remove '${ability.abilityName}' from its `uses` clause.")
        )
      }
  }

  /** Where a binding binder's row entry was written: the desugar sources the mark's ability argument at the entry. */
  private def markPosition(
      mark: Option[Sourced[OperatorResolvedExpression]],
      orv: OperatorResolvedValue
  ): Sourced[?] =
    mark.flatMap(declared => spine(declared.value)._2.headOption).getOrElse(orv.signature)

  /** Whether this value's own declaration wrote the row entry at `entry`. Always, except for an `implement` clause,
    * whose row is its block's union ([[com.vanillasource.eliot.eliotc.ast.fact.ImplementationRows]]): there an entry
    * keeps the source of the clause that wrote it, and a clause's signature runs from its name to its return type, so
    * an entry inside that stretch is its own and one outside it is a sibling's.
    */
  private def wrote(orv: OperatorResolvedValue, entry: Sourced[?]): Boolean =
    orv.vfqn.name.qualifier match {
      case _: Qualifier.AbilityImplementation =>
        val end = SignatureView.of(orv.signature).returnType.range.to
        entry.uri == orv.name.uri && orv.name.range.from <= entry.range.from && entry.range.to <= end
      case _                                  => true
    }

  /** Whether this value's **body** is written: everything with a runtime body except a type constructor, and only on
    * the runtime role — a `@Signature` twin's "body" is its own arrow chain, which the signature write covers.
    *
    * A **meta companion** is written like any other body, unlike under v5's row derivation: a `^Meta` transfer brace or
    * a `^Where` predicate is ordinary compile-track code calling ordinary abilities, and every one of those references
    * needs its binding written or the checker grounds the binder to `Type` and the dispatch fails naming an ability the
    * user never saw.
    */
  private def writableBody(orv: OperatorResolvedValue): Boolean =
    orv.vfqn.name.role == Role.Runtime && (orv.vfqn.name.qualifier match {
      case Qualifier.Type => false
      case _              => true
    })

  /** The bindings a definition receives from its caller — resolution-order step 2 — one per phantom binder of its own
    * signature, naming that binder.
    */
  private def receivedBindings(orv: OperatorResolvedValue, universe: RowChecker.Universe): Seq[Binding] = {
    val binders = SignatureView.of(orv.signature).binders
    val entries = entryArguments(orv)
    phantoms(orv).map { case (index, ability) =>
      Binding(
        ability,
        ParameterReference(binders(index).name),
        received = true,
        arguments = entries.get(index).flatMap(knownArguments(_, Map.empty))
      )
    }
  }

  /** The names of this definition's row-typed value parameters: a reference to one runs it. */
  private def thunkParameters(orv: OperatorResolvedValue): Set[String] =
    orv.effectRow.parameterEffects.flatMap(pe => valueParameterNames(orv).lift(pe.parameterIndex)).toSet

  /** What each of this definition's row-typed parameters must be **given** when it runs (`docs/effects.md` D20 rule
    * 5): its slot's caller wrote the actual's bindings from this definition's declaration, so every entry the slot
    * *supplies* by `Default` — one this definition's own row does not have, and the slot's `with` does not name — was
    * written as a promise that a frame for it is entered before the actual runs. So is every effect a slot's named
    * implementation performs in its own clauses, which the caller also wrote as `Default`
    * ([[Writer.slotImplementation]]). An entry the definition's own row has — the slot's, or one its named
    * implementation's clauses perform — rides the caller's binding and needs nothing here. Entries are compared at
    * their known arguments ([[ridesOwn]]), as the caller's write compared them. A named implementation's `~`
    * constraint is an ability, not an effect: its declaration's row does not list it, its `Default` is the two-site
    * search's wherever it is written, and so it needs no frame and nothing gives it.
    */
  private def thunkRequirements(
      orv: OperatorResolvedValue,
      universe: RowChecker.Universe
  ): Map[String, Seq[Need]] = {
    val own        = ownEntries(orv, Map.empty)
    val parameters = SignatureView.of(orv.signature).parameters
    orv.effectRow.parameterEffects.flatMap { pe =>
      valueParameterNames(orv).lift(pe.parameterIndex).map { name =>
        val markers   = parameters.lift(pe.parameterIndex).toSeq.flatMap(p => slotBindings(p.value))
        val withBound = markers.flatMap(marker => abilityOf(marker.value, universe)).toSet
        val listed    = pe.effects
          .map(entry => Need(entry.abilityFQN, knownArguments(entry.typeArgs, Map.empty)))
          .filterNot(need => withBound.contains(need.ability))
        val clauses   = markers.flatMap(marker =>
          performedEntries(marker.value, universe).map((ability, arguments) => Need(ability, arguments, Some(marker.value)))
        )
        name -> (listed ++ clauses).filterNot(need => ridesOwn(own)(need.ability -> need.arguments)).distinct
      }
    }.toMap
  }

  /** A definition's own declared entries, each at the arguments `substitution` makes known. */
  private def ownEntries(
      orv: OperatorResolvedValue,
      substitution: Map[String, OperatorResolvedExpression]
  ): Seq[(AbilityFQN, Option[Seq[String]])] =
    orv.effectRow.returnEffects.map(entry => entry.abilityFQN -> knownArguments(entry.typeArgs, substitution))

  /** Whether `entry` is one of the definition's `own` entries — the same ability, at arguments not known to differ — and
    * so rides the caller's binding rather than being supplied. `Throw[AssertionError]` in the row does not make a
    * `Throw[IoError]` ride.
    */
  private def ridesOwn(own: Seq[(AbilityFQN, Option[Seq[String]])])(entry: (AbilityFQN, Option[Seq[String]])): Boolean =
    own.exists((ability, known) => ability == entry._1 && !differ(known, entry._2))

  /** The effects each row-typed parameter's block **rides** — entries of this definition's own row that its slot
    * lists without naming an implementation for them, or that the implementation its slot names with `with` performs.
    * An entry the slot binds with `with` is handled by that implementation whatever this definition declares (D23). The block's code for them is the caller's
    * binding, but its frame is given where it runs (`docs/effects.md` D20c, decided (b)), so running it consumes this
    * definition's received binding: `describedAs` declares `Throw[AssertionError]` in order to run its body inside a
    * `catch` of it. An entry is compared whole — `Throw[E]` on a slot is not `Throw[AssertionError]` in the row — as the
    * environment threading compares it.
    */
  private def riddenEntries(orv: OperatorResolvedValue, universe: RowChecker.Universe): Map[String, Set[AbilityFQN]] = {
    def spelled(entry: AbilityConstraint[OperatorResolvedExpression]) = (entry.abilityFQN, entry.typeArgs.map(_.render))
    val names      = valueParameterNames(orv)
    val parameters = SignatureView.of(orv.signature).parameters
    val row        = orv.effectRow.returnEffects.map(spelled).toSet
    orv.effectRow.parameterEffects.flatMap { slot =>
      names.lift(slot.parameterIndex).map { name =>
        val markers   = parameters.lift(slot.parameterIndex).toSeq.flatMap(tpe => slotBindings(tpe.value))
        val withBound = markers.flatMap(marker => abilityOf(marker.value, universe)).toSet
        val clauses   = markers.flatMap(marker => universe.lookup(marker.value).toSeq.flatMap(_.effectRow.returnEffects))
        name -> (slot.effects.filterNot(entry => withBound.contains(entry.abilityFQN)) ++ clauses)
          .map(spelled)
          .filter(row.contains)
          .map(_._1)
          .toSet
      }
    }.toMap
  }

  /** The [[bindingEntries]] of `marker` that are **effects** — its declaration's row lists them — leaving out its `~`
    * constraints, which are abilities.
    */
  private def performedEntries(
      marker: ValueFQN,
      universe: RowChecker.Universe
  ): Seq[(AbilityFQN, Option[Seq[String]])] = {
    val effects = universe.lookup(marker).toSeq.flatMap(_.effectRow.returnEffects.map(_.abilityFQN)).toSet
    bindingEntries(marker, universe).filter(entry => effects.contains(entry._1))
  }

  /** Every entry the implementation `marker` is parameterised by — its phantom binders' abilities, each with the
    * arguments its clause spells where they are ground ([[knownArguments]]), so `Throw[GitError]` is told from a
    * `Throw[NotFetched]` beside it.
    */
  private def bindingEntries(
      marker: ValueFQN,
      universe: RowChecker.Universe
  ): Seq[(AbilityFQN, Option[Seq[String]])] =
    universe.lookup(marker).toSeq.flatMap { orv =>
      val entries = entryArguments(orv)
      phantoms(orv).map { case (index, ability) =>
        ability -> entries.get(index).flatMap(knownArguments(_, Map.empty))
      }
    }

  /** The names of this definition's **callback** parameters — function-typed, with a row in the codomain: code, which
    * may be called or passed on and never kept.
    */
  private def callbackParameters(orv: OperatorResolvedValue): Set[String] =
    orv.effectRow.callbackEffects.flatMap(cb => valueParameterNames(orv).lift(cb.parameterIndex)).toSet

  private def valueParameterNames(orv: OperatorResolvedValue): Seq[String] = {
    val view       = SignatureView.of(orv.signature)
    val (names, _) = RowChecker.peelBinders(orv.runtime.map(_.value).getOrElse(view.returnType.value))
    names.takeRight(view.parameters.size)
  }

  /** Whether `callee` is one of the members a `match` lowers to — `handleCases` of a `PatternMatch` implementation, or
    * `typeMatch` of a `TypeMatch` one. Their arguments are the arms of the `match`, which the author wrote in place:
    * code, whatever the lowering's types say. No user can name them, so nothing else is read as an arm.
    */
  private def isMatchMachinery(callee: ValueFQN): Boolean =
    WellKnownTypes.isPatternMatchHandleCases(callee) || WellKnownTypes.isTypeMatchTypeMatch(callee)

  /** The parameter a `match`'s Church-encoded selector binds ([[isMatchMachinery]]): the lowering applies it to each
    * arm, so its arguments are arms too. The `$` keeps it out of reach of anything a user writes.
    */
  private val matchSelector = "$selector"

  /** A definition's **binding binders**, as `(index, ability)` pairs in index order — read off the **marks** its own
    * declaration carries, and off nothing else.
    *
    * A binder the desugar mints for a row entry, for a `~` constraint or for an `ability` block's implementation slot
    * is declared `Impl: Implementation[Console]`
    * ([[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter.implementationMark]]), and that mark both says "this
    * binder is a binding" and names the ability it binds. Nothing is re-derived here: there is no non-occurrence test,
    * no constraint whose first type argument gives the ability away, and no special case for an ability member's own
    * slot — that slot carries the mark like every other binding (`docs/effects.md` §9.3 step 3).
    *
    * The marked indices are **not** required to be a prefix. A member of a *parameterised* ability declaring effects of
    * its own (`ability Show[T] { def show(t: T): {Log} String }`) has its bindings at indices 0 and 2 with the
    * ability's `T` between them; [[Writer.writeBindings]] merges them with what the call determines for that `T`.
    *
    * Private, and the only definition of where a binding sits. It was public while the post-mono accounting read it
    * back to re-derive an instantiation's effects; that derivation retired with D7 (`docs/effects.md` §11), and the
    * write's own walk — which is the scope check — is now the only reader.
    */
  private def phantoms(orv: OperatorResolvedValue): Seq[(Int, AbilityFQN)] =
    SignatureView.of(orv.signature).binders.zipWithIndex.flatMap { case (binder, index) =>
      binder.parameterType.flatMap(declared => markedAbility(declared.value)).map(index -> _)
    }

  /** The row entry each of a definition's binding binders stands for, by binder index: the `k`-th binder marked for an
    * ability is the `k`-th entry of the declared row naming it, since the desugar mints them in the order written. An
    * ability whose marks and entries do not pair one to one — a `~` constraint's binder has no entry — pairs none.
    */
  private def entryArguments(orv: OperatorResolvedValue): Map[Int, Seq[OperatorResolvedExpression]] =
    phantoms(orv)
      .groupBy(_._2)
      .toSeq
      .flatMap { case (ability, marks) =>
        val entries = orv.effectRow.returnEffects.filter(_.abilityFQN == ability)
        if (entries.size =!= marks.size) Seq.empty
        else marks.map(_._1).zip(entries.map(_.typeArgs))
      }
      .toMap

  /** Whether two entries of one ability are known to stand at different arguments — the one case in which they are not
    * the same entry. Either side unknown, and they may be.
    */
  private def differ(left: Option[Seq[String]], right: Option[Seq[String]]): Boolean =
    left.isDefined && right.isDefined && left != right

  /** An entry's type arguments, once `substitution` has written what this call determines for the callee's binders —
    * **known** only when every one of them is then ground, rendered so that two of them compare by what they say and
    * not by where they were written. One still naming a binder (`Throw[E]` in `E`'s own definition) is unknown.
    */
  private def knownArguments(
      arguments: Seq[OperatorResolvedExpression],
      substitution: Map[String, OperatorResolvedExpression]
  ): Option[Seq[String]] = {
    val written = arguments.map(argument =>
      substitution.foldLeft(argument) { case (acc, (name, value)) => substitute(acc, name, value) }
    )
    Option.when(written.forall(isGround))(written.map(_.render))
  }

  private def isGround(expression: OperatorResolvedExpression): Boolean = expression match {
    case ValueReference(_, typeArgs)           => typeArgs.forall(argument => isGround(argument.value))
    case FunctionApplication(target, argument) => isGround(target.value) && isGround(argument.value)
    case _: IntegerLiteral | _: StringLiteral  => true
    case _                                     => false
  }

  /** The ability a binder's declared type **marks** it as binding, if it is marked at all — the head of the declared
    * type is [[WellKnownTypes.implementationTypeFQN]] and its single argument names an ability
    * ([[com.vanillasource.eliot.eliotc.ast.fact.GenericParameter.implementationMark]]).
    *
    * The mark is read off the *operator-resolved* signature, where the alias is still unexpanded: the evaluator
    * reduces `Implementation[A]` to `Type` at monomorphization, so this is the last phase that can see it — and the
    * phase that erases it ([[Writer.unmarked]]).
    */
  private def markedAbility(declaredType: OperatorResolvedExpression): Option[AbilityFQN] =
    spine(declaredType) match {
      case (ValueReference(head, _), Seq(argument)) if head.value === WellKnownTypes.implementationTypeFQN =>
        argument.value match {
          case ValueReference(ability, _) =>
            ability.value.name.qualifier match {
              case Qualifier.Ability(name) => Some(AbilityFQN(ability.value.moduleName, name))
              case _                       => None
            }
          case _                          => None
        }
      case _                                                                                              => None
    }

  /** The implementations a slot's type binds, outermost `with` last, as written. */
  private def slotBindings(tpe: OperatorResolvedExpression): Seq[Sourced[ValueFQN]] = tpe match {
    case WithBinding(subject, implementation) => slotBindings(subject.value) :+ implementation
    case _                                    => Seq.empty
  }

  /** The ability an implementation marker implements, read off its **resolved qualifier** — never off its name. */
  private def abilityOf(marker: ValueFQN, universe: RowChecker.Universe): Option[AbilityFQN] =
    universe.lookup(marker).flatMap(_.name.value.qualifier match {
      case ResolveQualifier.AbilityImplementation(ability, _, _) => Some(ability)
      case _                                                     => None
    })

  private class Writer(universe: RowChecker.Universe) {
    val violations: mutable.Buffer[Violation] = mutable.Buffer.empty

    /** The abilities whose **received** binding some reference was written from ([[overDeclared]]). */
    val consumed: mutable.Set[AbilityFQN] = mutable.Set.empty

    /** The [[Binding.serial]]s of the bindings some reference was written from, so a body `with` that binds nothing is
      * reported ([[unusedWith]]).
      */
    private val used: mutable.Set[Long] = mutable.Set.empty

    private def consume(binding: Binding): Unit = {
      used += binding.serial
      if (binding.received) consumed += binding.ability
    }

    /** The definition's own leading binders — its generic binders and then its value parameters — are peeled without
      * shadowing, because they are what *put* the row-typed parameters in scope. Only a lambda inside the body proper
      * can shadow one.
      */
    def walkDefinition(
        expr: Sourced[OperatorResolvedExpression],
        scope: Scope,
        remaining: Int
    ): Sourced[OperatorResolvedExpression] =
      expr.value match {
        case FunctionLiteral(paramName, paramType, body) if remaining > 0 =>
          expr.as(FunctionLiteral(paramName, paramType.map(unmarked), walkDefinition(body, scope, remaining - 1)))
        case _                                                           => walk(expr, scope)
      }

    /** Erase a binding binder's **mark**: `Impl: Implementation[Console]` becomes the ordinary `Impl: Type` it is
      * definitionally equal to (`docs/effects.md` §9.2).
      *
      * The mark is written by the desugar and read here ([[phantoms]]), and nothing downstream of this phase has a
      * question it answers, so it is dropped along with the `with` nodes rather than carried into the checker. That is
      * what keeps the promise that a marked binder is an ordinary binder of kind `Type`: the checker is handed the
      * same signature it was handed before the mark existed, and is never asked what kind an ability standing in a
      * type argument has — it has one per ability arity, and the mark takes them all.
      */
    private def unmarked(paramType: Sourced[OperatorResolvedExpression]): Sourced[OperatorResolvedExpression] =
      if (markedAbility(paramType.value).isDefined) paramType.as(ValueReference(paramType.as(WellKnownTypes.typeFQN)))
      else paramType

    /** @param applied
      *   How many arguments the position `expr` stands in applies it to, beyond those written in it: the arity of the
      *   code parameter it is the argument of, and zero in a value position. What a reference is given in total decides
      *   whether it is called there or stands unapplied ([[unappliedEffectful]]).
      */
    def walk(
        expr: Sourced[OperatorResolvedExpression],
        scope: Scope,
        applied: Int = 0
    ): Sourced[OperatorResolvedExpression] =
      expr.value match {
        // A `with` puts its implementation in scope for its subject, innermost first, and disappears.
        case WithBinding(subject, implementation) =>
          abilityOf(implementation.value, universe) match {
            case Some(ability) =>
              val clauses = bindingEntries(implementation.value, universe).map { case (clause, arguments) =>
                Binding.Clause(clause, arguments, scope.binding(clause, arguments).map(_.serial))
              }
              val binding =
                Binding(ability, boundImplementation(implementation, scope), byWith = true, clauses = clauses)
              val written = walk(subject, scope.bind(binding), applied)
              if (!scope.uncoveredDefaults && !used.contains(binding.serial))
                violations += unusedWith(subject, implementation, ability, scope)
              written
            case None          =>
              violations += Violation(implementation.as("This name is not an implementation."))
              walk(subject, scope, applied)
          }

        case ParameterReference(name) if scope.captured.contains(name.value) =>
          violations += capturedCode(name, scope)
          expr

        // A reference to one of this definition's row-typed parameters is a thunk: running it is applying it.
        case ParameterReference(name) if scope.thunks.contains(name.value) =>
          checkGiven(name, scope)
          scope.ridden.getOrElse(name.value, Set.empty).foreach(ability => scope.binding(ability).foreach(consume))
          expr.as(FunctionApplication(expr, unitValue(expr)))

        // A callback here is neither called nor passed on to code: whatever receives it may keep it.
        case ParameterReference(name) if scope.callbacks.contains(name.value) =>
          violations += keptCode(name)
          expr

        // A lambda reaching this case stands in a value position, and a value is pure.
        case FunctionLiteral(paramName, paramType, body) =>
          expr.as(FunctionLiteral(paramName, paramType, walk(body, scope.enterValue.shadow(paramName.value))))

        case _: IntegerLiteral | _: StringLiteral | _: ParameterReference => expr

        case _ =>
          val (head, args) = spine(expr.value)
          head match {
            case ValueReference(callee, existing) if ImplementationParameters.isBinder(callee.value) &&
                  universe.lookup(callee.value).isDefined =>
              walkSupplied(expr, expr.as(head), callee, existing, args, scope, applied)
            case ValueReference(callee, existing)                                   =>
              val written      = writeBindings(expr.as(head), callee.value, existing, scope, args)
              unappliedEffectful(callee, args.size + applied, scope).foreach(violations += _)
              val substitution =
                universe.lookup(callee.value).fold(Map.empty)(orv => callSubstitution(orv, existing, args))
              val adjusted     = args.zipWithIndex.map { case (arg, index) =>
                walkArgument(arg, callee.value, index, scope, substitution)
              }
              expr.as(applyChain(written, adjusted))
            case ParameterReference(selector) if selector.value === matchSelector =>
              expr.as(applyChain(walk(expr.as(head), scope), args.map(walkCode(_, scope, Int.MaxValue))))
            case _                                                                  =>
              expr.as(applyChain(walkCode(expr.as(head), scope, args.size), args.map(walk(_, scope))))
          }
      }

    /** A `with` supplying arguments, as the resolver lowers it: `scripted$with(List("Bob"), greet with scripted)`
      * ([[ImplementationParameters.binder]]). The arguments are values computed where the `with` is written, outside
      * its binding; the subject, the binder's last parameter, stands where the `with` stands and is walked exactly as
      * the `with` itself would be, so a lambda or an unapplied reference there is what it was before the arguments
      * were supplied.
      */
    private def walkSupplied(
        expr: Sourced[OperatorResolvedExpression],
        head: Sourced[OperatorResolvedExpression],
        callee: Sourced[ValueFQN],
        existing: Seq[Sourced[OperatorResolvedExpression]],
        args: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope,
        applied: Int
    ): Sourced[OperatorResolvedExpression] = {
      val declared = universe.lookup(callee.value).fold(0)(orv => SignatureView.of(orv.signature).parameters.size - 1)
      val written  = writeBindings(head, callee.value, existing, scope, args)
      // The resolver puts the `with` right after the arguments it supplies; what follows applies the subject's value.
      val position = args.indexWhere(_.value match {
        case WithBinding(_, named) => ImplementationParameters.binderOf(named.value) == callee.value
        case _                     => false
      })
      if (position < 0) expr.as(applyChain(written, args.map(walk(_, scope))))
      else {
        if (position != declared) {
          val name = callee.value.name.qualifier match {
            case Qualifier.AbilityImplementation(_, _, Some(name)) => name
            case _                                                 => callee.value.name.name
          }
          violations += Violation(
            args(position).as(
              s"The implementation '$name' takes $declared parameter${if (declared == 1) "" else "s"}, but this " +
                s"`with` supplies $position."
            )
          )
        }
        val (supplied, rest) = args.splitAt(position)
        expr.as(
          applyChain(
            written,
            supplied.map(walk(_, scope)) ++ (walk(rest.head, scope, applied + rest.size - 1) +: rest.tail.map(walk(_, scope)))
          )
        )
      }
    }

    /** An effect a code parameter's clause lists is this definition's **to handle or to declare**
      * (`docs/effects.md` §1 rule 5, D22): every listed entry it does not declare ([[thunkRequirements]]) must be
      * handled where the code runs — bound by a callee's slot that supplies it, the frame of a discharger the code is
      * passed on to — and handled the way the caller was promised, by `Default`. Nothing else handles it: no `with` in
      * the body, which cannot rebind bindings the caller already wrote. An entry the definition declares is passed on
      * to its caller instead, and needs nothing here. Only a body-less declaration — a platform primitive — gives an
      * effect from nothing, and it has no body to check, so an effect's default is handed out at `main` and by the
      * primitives, nowhere else.
      */
    private def checkGiven(name: Sourced[String], scope: Scope): Unit =
      if (!scope.uncoveredDefaults)
        scope.thunkNeeds.getOrElse(name.value, Seq.empty).filterNot(givenBySlot(_, scope)).foreach { need =>
          val Need(ability, arguments, _) = need
          scope.binding(ability, arguments).foreach(binding => used += binding.serial)
          scope.binding(ability, arguments) match {
            case Some(binding) if binding.bySlot && !binding.byWith => ()
            case Some(binding) if binding.bySlot                    =>
              violations += Violation(
                name.as(
                  s"'${name.value}' was promised the default '${ability.abilityName}', but runs where a slot binds " +
                    "another implementation for it."
                ),
                Seq(s"Name the same implementation on '${name.value}'s own slot with `with`, or pass it elsewhere.")
              )
            case _                                                  =>
              violations += Violation(
                name.as(
                  s"'${name.value}' may perform the effect '${ability.abilityName}' here, which this definition " +
                    "neither handles nor declares."
                ),
                Seq(
                  s"Handle it by passing '${name.value}' to a parameter that discharges it (such as `catch` or " +
                    "`runThrow`) or by naming an implementation on its slot with `with`, or add " +
                    s"'${ability.abilityName}' to this definition's `uses` clause to pass it on to its caller."
                )
              )
          }
        }

    /** Whether `need`, an entry the clauses of the implementation this definition's slot names perform, is given by the
      * callee whose slot the run is passed to: that slot names the same implementation, and the callee supplies the
      * entry, so it is held to giving it where it runs the code, as this definition was (`docs/effects.md` D25).
      */
    private def givenBySlot(need: Need, scope: Scope): Boolean =
      need.clauseOf.exists(marker =>
        scope.clausesGiven.exists(clause =>
          clause.clauseOf.contains(marker) && clause.ability == need.ability && !differ(clause.arguments, need.arguments)
        )
      )

    /** An expression standing where **code** is expected — the argument of a code parameter, or the head of an
      * application — whose first `arity` lambdas are therefore the caller's text and keep its scope. A lambda past them
      * is what the code hands back, which is a value again; and this definition's own callback, standing here, is being
      * called or passed on rather than kept.
      */
    private def walkCode(
        expr: Sourced[OperatorResolvedExpression],
        scope: Scope,
        arity: Int
    ): Sourced[OperatorResolvedExpression] =
      expr.value match {
        case FunctionLiteral(paramName, paramType, body) if arity > 0          =>
          expr.as(FunctionLiteral(paramName, paramType, walkCode(body, scope.shadow(paramName.value), peeled(arity))))
        case ParameterReference(name) if scope.callbacks.contains(name.value) => expr
        case _                                                                => walk(expr, scope, residual(arity))
      }

    /** The arguments code is still called with once its written lambdas are peeled. A `match` arm's count is not known
      * here ([[isMatchMachinery]]) and is `Int.MaxValue`, never decremented ([[peeled]]); its lambdas are the
      * constructor's fields, so what stands past them is the arm's result: a value.
      */
    private def residual(arity: Int): Int = if (arity === Int.MaxValue) 0 else arity

    /** The arity left once one lambda is peeled; an unknown one stays unknown. */
    private def peeled(arity: Int): Int = if (arity === Int.MaxValue) arity else arity - 1

    /** A reference to a definition that `uses` an effect, given fewer arguments than it declares before its return
      * position: it performs nothing where it stands, and whatever receives it performs its effects later, wherever it
      * calls it. In a value position that is a value which is not pure (`docs/effects.md` §1 rule 2's second half) —
      * exactly the η-expanded `s -> printLine(s)` there, which [[Scope.enterValue]] rejects at the effect. At a code
      * parameter it is the caller's code, and `applied` counts the arguments the callee gives it.
      */
    private def unappliedEffectful(callee: Sourced[ValueFQN], arguments: Int, scope: Scope): Option[Violation] =
      universe
        .lookup(callee.value)
        .filter(orv => !scope.uncoveredDefaults && arguments < orv.effectRow.returnArity)
        .flatMap(_.effectRow.returnEffects.headOption)
        .map { effect =>
          Violation(
            callee.as(
              s"'${callee.value.name.name}' performs the effect '${effect.abilityFQN.abilityName}' when it is called, " +
                "so unapplied it is not a value and cannot stand where one is expected."
            ),
            Seq(
              s"Call '${callee.value.name.name}' here with all its arguments, or pass it to a parameter that takes " +
                "code (`f uses *: A => B`)."
            )
          )
        }

    private def keptCode(name: Sourced[String]): Violation =
      Violation(
        name.as(s"'${name.value}' is code its caller wrote: it can be called or passed on, not kept."),
        Seq(
          "Call it here, or pass it to a parameter that takes code (`f uses *: A => B`); anything else may keep it past " +
            "the call that bound its effects."
        )
      )

    private def capturedCode(name: Sourced[String], scope: Scope): Violation =
      if (scope.closed)
        Violation(
          name.as(
            s"'${name.value}' is code its caller wrote, and an argument whose `uses` clause is closed cannot run it."
          ),
          Seq(
            "A closed clause (no `*`) admits only the effects it names, while this code may use its caller's; pass " +
              s"'${name.value}' to a parameter whose clause is open (`uses *`) instead."
          )
        )
      else
      Violation(
        name.as(s"'${name.value}' is code its caller wrote, and a function value cannot capture it."),
        Seq(
          "A function written where a value is expected may be kept, so it may not carry code; pass the function " +
            "to a parameter that takes code (`f uses *: A => B`) instead."
        )
      )

    /** One actual, at the callee's parameter `index`. A row-typed slot is a thunk, so the actual is wrapped — and the
      * entries that slot *supplies* are bound inside it, which is resolution-order step 3.
      */
    private def walkArgument(
        arg: Sourced[OperatorResolvedExpression],
        callee: ValueFQN,
        index: Int,
        scope: Scope,
        substitution: Map[String, OperatorResolvedExpression]
    ): Sourced[OperatorResolvedExpression] =
      universe.lookup(callee).flatMap(orv => rowSlot(orv, index).map(orv -> _)) match {
        case None if isMatchMachinery(callee) => walkCode(arg, scope, Int.MaxValue)
        case None                             =>
          universe.lookup(callee).flatMap(orv => callback(orv, index).map(orv -> _)) match {
            case Some((calleeOrv, cb)) =>
              val base =
                if (cb.closed) scope.enterClosed(rides(calleeOrv, index, cb.effects.map(_.abilityFQN))) else scope
              walkCode(arg, base, cb.arity)
            case None                  => walk(arg, scope)
          }
        case Some((calleeOrv, slot))          =>
          val name    = thunkParameter
          val base    = if (closedSlot(calleeOrv, index)) scope.enterClosed(rides(calleeOrv, index, slot)) else scope
          val inside  = suppliedScope(calleeOrv, index, base, arg, substitution).shadow(name)
          arg.as(
            FunctionLiteral(
              arg.as(name),
              Some(arg.as(ValueReference(arg.as(WellKnownTypes.unitTypeFQN)))),
              walk(arg, inside)
            )
          )
      }

    /** The scope inside a row-typed slot: every entry the slot **supplies** — one the callee's own declared row does
      * not already have — bound to the slot's `with` for that ability, or to `Default`, and carrying the entry's
      * arguments as this call determines them (`substitution`), so a supplied `Throw[Refused]` answers only a
      * `Throw[Refused]` and a received `Throw[Missing]` is still reached past it. "Already has" is the same comparison:
      * `mocked`'s own `Throw[AssertionError]` does not make its slot's `Throw[IoError]` ride, since the `catch` it
      * hands the slot to is that entry's frame.
      */
    private def suppliedScope(
        calleeOrv: OperatorResolvedValue,
        index: Int,
        scope: Scope,
        anchor: Sourced[?],
        substitution: Map[String, OperatorResolvedExpression]
    ): Scope = {
      val own      = ownEntries(calleeOrv, substitution)
      val rides    = (entry: AbilityConstraint[OperatorResolvedExpression]) =>
        ridesOwn(own)(entry.abilityFQN -> knownArguments(entry.typeArgs, substitution))
      val declared    = slotBindings(SignatureView.of(calleeOrv.signature).parameters(index).value)
        .flatMap(marker => abilityOf(marker.value, universe).map(_ -> marker))
        .toMap
      val entries  = calleeOrv.effectRow.parameterEffects.find(_.parameterIndex === index).toSeq.flatMap(_.effects)
      val clauses  = declared.values.toSeq.flatMap(marker =>
        performedEntries(marker.value, universe)
          .filterNot(ridesOwn(own))
          .map((ability, arguments) => Need(ability, arguments, Some(marker.value)))
      )
      // An entry the slot binds with `with` is handled by that implementation, even one this callee also declares (D23).
      entries.filterNot(entry => rides(entry) && !declared.contains(entry.abilityFQN)).foldLeft(
        scope.copy(clausesGiven = clauses ++ scope.clausesGiven)
      ) { (acc, entry) =>
        val ability   = entry.abilityFQN
        val arguments = knownArguments(entry.typeArgs, substitution)
        acc.bind(
          declared
            .get(ability)
            .fold(Binding(ability, defaultBinding(anchor), bySlot = true, arguments = arguments))(marker =>
              Binding(
                ability,
                slotImplementation(marker, acc, ridesOwn(own)),
                byWith = true,
                bySlot = true,
                arguments = arguments
              )
            )
        )
      }
    }

    /** The term a `with` binds: the implementation's marker **applied to its own clause-row bindings** (§9.4 step 3),
      * resolved in the scope the `with` stands in. A named implementation whose clauses declare a row is itself
      * parameterised by what they perform — `greeting[recordingConsole[cellWriter]]` — and transitivity is nothing
      * more than this being an ordinary written reference: whatever the scope holds for those abilities was written
      * the same way when *it* was bound. An implementation with no clause row writes as the bare marker, exactly as
      * before.
      */
    private def boundImplementation(
        implementation: Sourced[ValueFQN],
        scope: Scope
    ): OperatorResolvedExpression =
      writeBindings(
        implementation.as(ValueReference(implementation)),
        implementation.value,
        Seq.empty,
        scope,
        Seq.empty
      ).value

    /** The term a **slot's** `with` binds (`program uses *, Console with recordingConsole: Unit`). Its clause-row
      * bindings are resolved by §2.2's supplied-versus-rides rule, as a slot's own entries are. One the callee
      * **supplies** — an ability its own declared row lacks — is performed inside the *callee*, which is the whole point
      * of a slot `with`: `transcriptOf`'s `runWriterToLog` gives the double its `Writer[String]`, and the caller writing
      * the actual can neither see nor name it. "Lacks" is by entry: `mocked`'s own `Throw[AssertionError]` does not make
      * `mockFileSystem`'s `Throw[IoError]` ride. So it is written `Default`, "search at the ground arguments", and the
      * callee's body is held to giving it where it runs the block ([[thunkRequirements]]). One the callee **rides** is
      * the caller's own binding, handed on as the callee's received one, so it is written from this scope like any
      * other reference.
      */
    private def slotImplementation(
        implementation: Sourced[ValueFQN],
        scope: Scope,
        rides: ((AbilityFQN, Option[Seq[String]])) => Boolean
    ): OperatorResolvedExpression =
      boundImplementation(
        implementation,
        scope.defaulting(bindingEntries(implementation.value, universe).filterNot(rides), implementation)
      )

    /** How many arguments a callee calls its parameter `index` with, when that parameter is a **callback** — code of
      * function type. A value constructor's parameters are fields, and a field holds a value (D20 rule 6), so it takes
      * none.
      */
    private def callback(
        orv: OperatorResolvedValue,
        index: Int
    ): Option[EffectRow.CallbackEffects[AbilityConstraint[OperatorResolvedExpression]]] =
      orv.roleHint match {
        case _: RoleHint.ValueConstructor => None
        case _                            => orv.effectRow.callbackEffects.find(_.parameterIndex === index)
      }

    /** Whether the callee's parameter `index` is a row slot whose row is **closed** ([[Scope.enterClosed]]). */
    private def closedSlot(orv: OperatorResolvedValue, index: Int): Boolean =
      orv.effectRow.parameterEffects.exists(pe => pe.parameterIndex === index && pe.closed)

    /** The entries of a slot's row that **ride** — the callee's own declared row has them and the slot binds no
      * implementation for them, so the walk continues to the caller's binding instead of the slot supplying one (§2.2).
      */
    private def rides(orv: OperatorResolvedValue, index: Int, slot: Seq[AbilityFQN]): Set[AbilityFQN] = {
      val withBound = SignatureView
        .of(orv.signature)
        .parameters
        .lift(index)
        .toSeq
        .flatMap(tpe => slotBindings(tpe.value))
        .flatMap(marker => abilityOf(marker.value, universe))
        .toSet
      slot.toSet.intersect(orv.effectRow.returnEffects.map(_.abilityFQN).toSet) -- withBound
    }

    /** The abilities a callee's parameter `index` declares, when that parameter is a row position at all. */
    private def rowSlot(orv: OperatorResolvedValue, index: Int): Option[Seq[AbilityFQN]] =
      orv.effectRow.parameterEffects.find(_.parameterIndex === index).map(_.effects.map(_.abilityFQN))

    /** Write the callee's type arguments this call determines, **merged by index**: a binder the callee's declaration
      * marks as a **binding** takes the implementation the resolution order gives it, and every other binder takes, in
      * order, what this call determines for it — the caller's own explicit `typeArgs` where it spelled any, else what
      * a **supplied row slot** settles (A6).
      *
      * Type-argument application is positional, so what is written is the run from index 0 that every slot below it
      * fills. In a body a gap before a binding is written as the checker's hole ([[mergeTypeArguments]]), so every
      * binding is reached; a binder past the last one is left to the checker, which is always the fail-safe direction.
      * In a signature, which has no hole, a binding behind a gap is reported ([[unreachableBinding]]): the checker has
      * no way to solve a binding and would silently ground it to the platform's default.
      */
    private def writeBindings(
        reference: Sourced[OperatorResolvedExpression],
        callee: ValueFQN,
        existing: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope,
        args: Seq[Sourced[OperatorResolvedExpression]]
    ): Sourced[OperatorResolvedExpression] =
      universe.lookup(callee) match {
        case None      => reference
        case Some(orv) =>
          val effects          = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
          val marks            = phantoms(orv).toMap
          val determined       = if (existing.isEmpty) suppliedArguments(orv, args, marks.keySet) else existing
          val entries          = entryArguments(orv)
          val substitution     = substitutionOf(orv, marks.keySet, determined)
          val (written, reach) = mergeTypeArguments(
            SignatureView.of(orv.signature).binders.size,
            marks,
            determined,
            Option.unless(scope.signature)(reference.as(ValueReference(reference.as(WellKnownTypes.inferredTypeArgumentFQN)))),
            (index, ability) =>
              reference.as(
                bindingFor(
                  ability,
                  entries.get(index).flatMap(knownArguments(_, substitution)),
                  effects.contains(ability),
                  reference,
                  scope
                )
              )
          )
          marks.toSeq.filter(_._1 >= reach).sortBy(_._1).foreach { case (_, ability) =>
            violations += unreachableBinding(orv, ability, reference)
          }
          if (written.isEmpty) reference
          else reference.as(ValueReference(nameOf(reference), written))
      }

    /** Merge the bindings with what the call determines, slot by slot: index `i` is `binding(ability)` where the
      * declaration marks it, and the next undetermined value otherwise.
      *
      * Where nothing determines a binder that stands **before** a binding, `hole` fills it — the checker's "infer this
      * one" ([[WellKnownTypes.inferredTypeArgumentFQN]]) — so the binding behind it is still written: `greetAs` of
      * `ability Greeter[A]` with `uses Console` is `[Impl, A, Impl0]`, and only the checker reads `A` off the argument.
      * A binder past the last binding is left off the list as before, which the checker infers the same way. In a
      * signature there is no hole (`None`), and the list stops at the first gap.
      *
      * Returns the arguments to write and how many of the callee's binders they reach. Anything left over once every
      * binder has a value is appended — an over-spelled explicit list is the caller's to answer for, not this write's
      * to truncate — and cannot be a gap, because the fillers run out before a gap can open.
      */
    private def mergeTypeArguments(
        binderCount: Int,
        marks: Map[Int, AbilityFQN],
        determined: Seq[Sourced[OperatorResolvedExpression]],
        hole: Option[Sourced[OperatorResolvedExpression]],
        binding: (Int, AbilityFQN) => Sourced[OperatorResolvedExpression]
    ): (Seq[Sourced[OperatorResolvedExpression]], Int) = {
      val lastBinding       = marks.keys.maxOption.getOrElse(-1)
      val (slots, leftover) = (0 until binderCount).foldLeft(
        (Seq.empty[Option[Sourced[OperatorResolvedExpression]]], determined)
      ) { case ((acc, rest), index) =>
        marks.get(index) match {
          case Some(ability) => (acc :+ Some(binding(index, ability)), rest)
          case None          =>
            (acc :+ rest.headOption.orElse(hole.filter(_ => index < lastBinding)), rest.drop(1))
        }
      }
      val reached = slots.takeWhile(_.isDefined).flatten
      (reached ++ leftover, reached.size)
    }

    /** A binding a **signature**'s reference cannot reach: it sits at a type-argument index behind a binder nothing
      * there determines, a type-argument list applies positionally, and a signature's list holds no hole
      * ([[mergeTypeArguments]]) because its references are evaluated rather than checked.
      *
      * The shape is a member of a **parameterised** ability declaring effects of its own
      * (`ability Show[T] { def show(t: T) uses Log: String }`): the block's `T` stands between the two bindings.
      * Spelling the reference's type arguments (`show[String](x)`) reaches past it; nothing else does, so writing the
      * short prefix silently would leave the second binding on the platform's default. In a body the same call needs
      * nothing spelled.
      */
    private def unreachableBinding(
        orv: OperatorResolvedValue,
        ability: AbilityFQN,
        at: Sourced[?]
    ): Violation =
      Violation(
        at.as(
          s"Cannot pass the implementation of '${ability.abilityName}' to '${orv.vfqn.name.name}': it stands behind " +
            "a type parameter this call does not determine."
        ),
        Seq(
          "Spell this call's type arguments explicitly, or move the member out of the parameterised ability block " +
            "and give it its own row."
        )
      )

    /** What this call determines for the callee's binders, by name — the same values [[writeBindings]] writes for its
      * unmarked binders: the call's explicit type arguments, else what a supplied row slot settles.
      */
    private def callSubstitution(
        orv: OperatorResolvedValue,
        existing: Seq[Sourced[OperatorResolvedExpression]],
        args: Seq[Sourced[OperatorResolvedExpression]]
    ): Map[String, OperatorResolvedExpression] = {
      val marked = phantoms(orv).map(_._1).toSet
      substitutionOf(orv, marked, if (existing.isEmpty) suppliedArguments(orv, args, marked) else existing)
    }

    private def substitutionOf(
        orv: OperatorResolvedValue,
        marked: Set[Int],
        determined: Seq[Sourced[OperatorResolvedExpression]]
    ): Map[String, OperatorResolvedExpression] =
      SignatureView
        .of(orv.signature)
        .binders
        .zipWithIndex
        .collect { case (binder, index) if !marked.contains(index) => binder.name.value }
        .zip(determined.map(_.value))
        .toMap

    /** The run of the callee's *unmarked* binders — those its declaration does not mark as bindings — that this
      * call's **declarations** determine, written explicitly so the checker never mints a metavariable where a
      * declaration already says what belongs there (A6, `docs/effects.md` §3.1's second determination source).
      *
      * A parameter row lowers to a thunk (`{Throw[E]} A` ⤳ `Unit => A`), which **erases the entry's own arguments from
      * the type**. So `catch[E, A](computation: {Throw[E]} A, onError: E => {} A)` leaves `E` to the handler alone, and
      * a handler that ignores its error — `bad catch (err -> "fallback")` — determines nothing: `E` grounded to the
      * defaulted universe and the per-instantiation frames disagreed (`escapeInternal$Any` against
      * `exitInternal$String`). The entry's arguments are read back here from the **actual's own declared row**, which
      * is where they were all along: `bad : {Throw[String]} String` against the slot's `Throw[E]` gives `E := String`.
      *
      * Writing stops at the first binder nothing determines, because `typeArgs` applies positionally — a prefix is all
      * that can be written, and a binder left open is left inferred, which is always the fail-safe direction. And a
      * call that already spells its own arguments is left alone, so the explicit form stays the escape hatch.
      */
    private def suppliedArguments(
        orv: OperatorResolvedValue,
        args: Seq[Sourced[OperatorResolvedExpression]],
        marked: Set[Int]
    ): Seq[Sourced[OperatorResolvedExpression]] =
      SignatureView
        .of(orv.signature)
        .binders
        .zipWithIndex
        .collect { case (binder, index) if !marked.contains(index) => binder }
        .map(binder => suppliedDetermination(orv, args, binder.name.value))
        .takeWhile(_.isDefined)
        .flatten

    /** What a **supplied row slot** determines about one of the callee's binders: the actual delivered there declares
      * its own row, and matching it entry-by-entry against the slot's row reads the entry's arguments straight off a
      * declaration.
      *
      * Only a *call* answers — its callee's declaration states the row. A parameter reference, a block or a lambda
      * declares nothing, and the prefix stops. And an entry whose argument is still one of the *actual callee's* own
      * binders determines nothing either: `state` declares `{State[S]}` in its own `S`, so reading `S` off it would be
      * a rename rather than a determination, and one that grounds to junk instead of letting the checker read `S`
      * off the discharger's other argument.
      */
    private def suppliedDetermination(
        orv: OperatorResolvedValue,
        args: Seq[Sourced[OperatorResolvedExpression]],
        binderName: String
    ): Option[Sourced[OperatorResolvedExpression]] =
      orv.effectRow.parameterEffects.view
        .flatMap { slot =>
          args.lift(slot.parameterIndex).toSeq.flatMap { arg =>
            val supplied  = argumentRow(arg)
            val argCallee = spine(arg.value)._1 match {
              case ValueReference(name, _) => Some(name.value)
              case _                       => Option.empty[ValueFQN]
            }
            slot.effects.flatMap { declared =>
              supplied
                .filter(_.abilityFQN == declared.abilityFQN)
                .flatMap(entry => declared.typeArgs.zip(entry.typeArgs))
                .collect {
                  case (ParameterReference(name), determined)
                      if name.value === binderName && !argCallee.exists(hasFreeCalleeBinder(_, determined)) =>
                    arg.as(determined)
                }
            }
          }
        }
        .headOption

    /** The declared row of an argument expression — available exactly when the argument is a call, whose callee's
      * declaration states it.
      */
    private def argumentRow(
        arg: Sourced[OperatorResolvedExpression]
    ): Seq[AbilityConstraint[OperatorResolvedExpression]] =
      spine(arg.value)._1 match {
        case ValueReference(name, _) =>
          universe
            .lookup(name.value)
            .toSeq
            .flatMap(_.effectRow.returnEffects)
        case _                       => Seq.empty
      }

    /** Whether a determined type argument still mentions one of the argument callee's own binders — in which case it
      * says nothing about *this* call.
      */
    private def hasFreeCalleeBinder(callee: ValueFQN, typeArg: OperatorResolvedExpression): Boolean =
      universe.lookup(callee).exists { orv =>
        SignatureView
          .of(orv.signature)
          .binders
          .exists(binder => OperatorResolvedExpression.containsVar(typeArg, binder.name.value))
      }

    /** One binder's value. An **ability** with nothing in scope defaults to the two-site search; an **effect** with
      * nothing in scope is the "performs but does not declare" error, at this reference.
      */
    private def bindingFor(
        ability: AbilityFQN,
        arguments: Option[Seq[String]],
        isEffect: Boolean,
        at: Sourced[?],
        scope: Scope
    ): OperatorResolvedExpression =
      scope.binding(ability, arguments) match {
        case Some(binding) if isEffect && binding.beyondValue =>
          used += binding.serial
          violations += capturedEffect(ability, at, scope)
          checkClauses(binding, at, scope)
          binding.term
        case Some(binding)                                    =>
          consume(binding)
          checkClauses(binding, at, scope)
          binding.term
        case None                                             =>
          if (isEffect) reportUncovered(ability, at, scope)
          defaultBinding(at)
      }

    /** A body `with` that bound nothing: no reference in its subject was written from it. A `with` applies to what
      * escapes its subject — what the subject's calls declare and its own code performs — and to nothing discharged
      * further down, so a `with` nothing escapes to is a mistake, never a harmless no-op (`docs/effects.md` §4, D23).
      */
    private def unusedWith(
        subject: Sourced[OperatorResolvedExpression],
        implementation: Sourced[ValueFQN],
        ability: AbilityFQN,
        scope: Scope
    ): Violation = {
      def innermost(expr: OperatorResolvedExpression): OperatorResolvedExpression = expr match {
        case WithBinding(inner, _) => innermost(inner.value)
        case other                 => other
      }
      innermost(subject.value) match {
        case ParameterReference(name) if scope.thunks.contains(name.value) || scope.callbacks.contains(name.value) =>
          Violation(
            implementation.as(
              s"This `with` binds nothing: '${name.value}' is code its caller wrote, and its use of " +
                s"'${ability.abilityName}' was bound where it was written."
            ),
            Seq(
              s"To handle '${ability.abilityName}' for '${name.value}', list it in its `uses` clause " +
                s"(`${name.value} uses *, ${ability.abilityName}: …`); a `with` around its runs then binds it."
            )
          )
        case _                                                                                                  =>
          Violation(
            implementation.as(
              s"This `with` binds nothing: no use of '${ability.abilityName}' in its subject escapes into it."
            ),
            Seq(
              s"A `with` binds what its subject performs and does not discharge itself; remove it, or move it to where " +
                s"'${ability.abilityName}' is performed."
            )
          )
      }
    }

    private def capturedEffect(ability: AbilityFQN, at: Sourced[?], scope: Scope): Violation =
      if (scope.closed)
        Violation(
          at.as(
            s"This uses the effect '${ability.abilityName}' inside an argument whose `uses` clause is closed and " +
              "does not name it."
          ),
          Seq(
            s"Discharge '${ability.abilityName}' inside the argument, or pass it to a parameter whose clause is open " +
              "(`uses *`)."
          )
        )
      else
      Violation(
        at.as(
          s"This uses the effect '${ability.abilityName}' inside a function written where a value is expected, and " +
            "a value may use no effect bound outside it."
        ),
        Seq(
          s"Pass the function to a parameter that takes code (`f uses *: A => B`), or discharge '${ability.abilityName}' " +
            "inside it."
        )
      )

    /** A body `with`'s implementation used below a nearer binding of an effect its clauses perform. Its clauses were
      * written to perform that effect on the binding at the `with`, but the frame they would reach at runtime is the
      * nearer one: the implementation's frame is built where it is used (effects D20c option 5), and a discharge between
      * the `with` and the use is exactly what that would intercept. Each entry is looked up at its own arguments, as a
      * frame is chosen by entry: a nearer discharge of `Throw[NotFetched]` does not stand between a `with` and the use
      * of an implementation performing `Throw[GitError]`.
      */
    private def checkClauses(binding: Binding, at: Sourced[?], scope: Scope): Unit =
      binding.clauses.sortBy(_.ability.abilityName).foreach { case Binding.Clause(clause, arguments, serial) =>
        if (scope.binding(clause, arguments).map(_.serial) != serial)
          violations += Violation(
            at.as(
              s"The implementation of '${binding.ability.abilityName}' bound by the `with` around this performs " +
                s"'${clause.abilityName}', and a nearer binding of '${clause.abilityName}' stands between them."
            ),
            Seq(s"Move the `with` inside the nearer binding of '${clause.abilityName}', or that binding outside it.")
          )
      }

    private def reportUncovered(ability: AbilityFQN, at: Sourced[?], scope: Scope): Unit =
      if (!scope.uncoveredDefaults)
        violations += Violation(
          at.as(
            s"This value performs the effect '${ability.abilityName}' but does not declare it; " +
              "add it to its `uses` clause."
          ),
          Seq(s"Or bind an implementation for it here with `with`.")
        )

    private def nameOf(reference: Sourced[OperatorResolvedExpression]): Sourced[ValueFQN] =
      reference.value match {
        case ValueReference(name, _) => name
        case other                   =>
          throw IllegalStateException(s"An application head is not a value reference: ${other.render}")
      }
  }

  /** The `Default` sentinel: "search at the ground arguments", today's two-site resolution. */
  private def defaultBinding(at: Sourced[?]): OperatorResolvedExpression =
    ValueReference(at.as(WellKnownTypes.defaultImplementationFQN))

  private def unitValue(at: Sourced[?]): Sourced[OperatorResolvedExpression] =
    at.as(ValueReference(at.as(WellKnownTypes.unitValueFQN)))

  /** The name a thunk's ignored parameter binds. It shadows nothing a user can write, and every thunk uses the same
    * one because a thunk's body never mentions it.
    */
  private val thunkParameter = "$unit"
}
