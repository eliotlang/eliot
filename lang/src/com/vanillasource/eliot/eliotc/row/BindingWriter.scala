package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{EffectRow, Handles, ImplementationParameters}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, Role, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.DeclaredEntries
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.collection.mutable

/** **The scope check** — effects v6, `docs/effects.md` §4, and the walk that readies a definition for the checker.
  * Which implementation an effect's operation runs on is not decided here: it is the **evidence** in scope after
  * monomorphization (D35), which the specialisation pass reads. This walk proves, before that, that every use of an
  * effect has something to answer it. Three jobs, one walk:
  *
  *   1. **answer the bindings.** At each reference the callee's binding binders are read off the **marks** its
  *      declaration carries — `Impl: Implementation[Console]` — and each is answered by the resolution order below,
  *      which is the scope check, and written `Default`, merged by index with whatever else the call determines. A
  *      binder is never left to a metavariable.
  *   2. **thunk and apply.** A row-typed slot lowered to `Unit => A` (§9.4 step 2), so an actual delivered there is
  *      wrapped in a lambda, and a reference to one of *this* definition's row-typed parameters is applied to `unit`.
  *      Doing both unconditionally is what makes a pass-through (`runAbort(computation)`,
  *      `foldOption(fallback, …)`, `val restFailures = rest`) come out right with no inspection of the argument's
  *      shape or type: wrap and apply are inverse, so a pass-through is an η-expansion and nothing more.
  *   3. **erase `with`.** The node puts an implementation in scope for its subject; once the subject is checked it is
  *      dropped, and the call of its implementation's binder the resolver left around it is where the evidence after
  *      monomorphization installs the implementation.
  *
  * **What answers a use — the resolution order** (§9.4), walking outward lexically:
  *
  *   1. the nearest enclosing `with` for that ability;
  *   2. this definition's own phantom binder for it — a *received* binding, filled by its caller. There is no graph to
  *      sum: forwarding is the enclosing signature, read once;
  *   3. for an actual at a row-typed slot, the entries that slot **supplies**, which the callee's runs give. An entry
  *      the callee's own declared row already has is *not* supplied, and the walk continues outward (Part I's
  *      supplied-versus-rides rule, §2.2, unchanged);
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
    *   The definition with its body and its signature written.
    * @param violations
    *   What the walk could not answer, each at its own position. A violation aborts the definition: an unwritten
    *   binding would leave the checker to ground it.
    * @param overDeclarations
    *   The declared effects the body never performs ([[overDeclared]]). Kept apart from [[violations]] because their
    *   usual cause is somewhere else — a callee performing an effect it does not declare leaves its caller's
    *   declaration unconsumed — so the processor reports them only once the callees are known to be sound.
    */
  case class Written(value: OperatorResolvedValue, violations: Seq[Violation], overDeclarations: Seq[Violation])

  case class Violation(message: Sourced[String], help: Seq[String] = Seq.empty)

  /** What answers an ability in scope: where the implementation a reference performs on will come from. Nothing is
    * written from it — the evidence after monomorphization carries the implementation (`docs/effects.md` D35) — but the
    * scope check is this walk, so every reference is still answered by one.
    *
    * @param byWith
    *   Whether a `with` in the body installs it, around the reference.
    * @param bySlot
    *   Whether a callee's row slot **supplies** it to the actual written there — resolution-order step 3.
    * @param received
    *   The binder index of this definition's own **received** binding — resolution-order step 2, a row entry its caller
    *   fills. Only these are tracked as consumed ([[Writer.consumed]]), because only a declared entry can be declared
    *   for nothing.
    * @param arguments
    *   The type arguments of the row entry this binding answers, as this definition **spells** them
    *   ([[spelledRendered]], `docs/effects.md` D30): `Throw[Refused]` at a `catch[Refused, String]` slot,
    *   `Throw[E]` received from a row generic in `E`. A binding answers a reference to the same ability unless both
    *   sides are spelled and the spellings differ, so the slot's `Throw[Refused]` does not shadow the received
    *   `Throw[Missing]` ([[Scope.binding]]). `None` — an entry with a binder the call leaves to the checker, or a
    *   `with` — answers any.
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
      byWith: Boolean = false,
      received: Option[Int] = None,
      beyondValue: Boolean = false,
      bySlot: Boolean = false,
      arguments: Option[Seq[String]] = None,
      clauses: Seq[Binding.Clause] = Seq.empty,
      serial: Long = Binding.nextSerial(),
      handle: Option[HandleHole.Candidate] = None,
      group: Option[Long] = None
  ) {

    /** Which bindings are equally near: those put in scope by one binder — a definition's own handles, one block's
      * parameters. A binding of its own is its own group.
      */
    def distance: Long = group.getOrElse(serial)
  }

  /** What [[Writer.sharedHoles]] adds to one reference: the type arguments it writes past the call's own, and the
    * keyed hole each keyed binder stands for, by name.
    */
  private case class SharedHoles(written: Seq[Sourced[OperatorResolvedExpression]], holes: Map[String, OperatorResolvedExpression])

  private object Binding {

    /** One entry a `with`'s implementation performs in its clauses, and the binding that answered it at the `with`. */
    case class Clause(ability: AbilityFQN, arguments: Option[Seq[String]], serial: Option[Long])
    private val serials = new java.util.concurrent.atomic.AtomicLong()

    def nextSerial(): Long = serials.incrementAndGet()
  }

  /** An entry a run of a code parameter must be given: one its slot lists and the definition does not declare, with its
    * arguments as the definition spells them — what a `with` around the run reads its type arguments off
    * ([[Writer.suppliedTypeArguments]]).
    */
  private case class Need(ability: AbilityFQN, arguments: Option[Seq[String]], spelled: Seq[OperatorResolvedExpression])

  /** The lexical environment at one point. `bindings` is innermost-first, so a nearer `with` shadows an outer one for
    * the same ability; `thunks` are the row-typed parameters in scope, whose references apply.
    *
    * @param signature
    *   Whether this is a signature. Its effects are the guard channel's vocabulary, discharged by the guarded-return
    *   read rather than performed at runtime, so an uncovered one is not reported, and the scope check's other cuts do
    *   not apply. Its references are
    *   evaluated rather than checked, so a type-argument list there may not hold the checker's hole
    *   ([[Writer.mergeTypeArguments]]).
    */
  private case class Scope(
      bindings: Seq[Binding],
      thunks: Set[String],
      callbacks: Set[String] = Set.empty,
      captured: Set[String] = Set.empty,
      thunkNeeds: Map[String, Seq[Need]] = Map.empty,
      closed: Boolean = false,
      ridden: Map[String, Seq[Need]] = Map.empty,
      signature: Boolean = false,
      listed: Map[String, Seq[AbilityConstraint[OperatorResolvedExpression]]] = Map.empty
  ) {
    def bind(binding: Binding): Scope = copy(bindings = binding +: bindings)
    def shadow(name: String): Scope   =
      copy(
        thunks = thunks - name,
        callbacks = callbacks - name,
        captured = captured - name,
        ridden = ridden - name,
        listed = listed - name
      )

    /** The scope inside a lambda written in a **value** position. Every binding in scope so far is outside the value,
      * so an effect may no longer be taken from it ([[Writer.bindingFor]]); and every code parameter in scope would be
      * carried off by the value, so a reference to one is a capture. A signature is not checked: its effects are not
      * performed at runtime.
      */
    def enterValue: Scope =
      if (signature) this
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
      * run or passed on there — except for the bindings in `rides`, those answering the entries the slot names and
      * the callee's own row has, spelled the same, so they continue to the caller's binding as they do at an open slot.
      * What the slot *supplies* is bound inside by the caller of this, exactly as at an open slot.
      */
    def enterClosed(rides: Set[Long]): Scope =
      if (signature) this
      else
        enterValue.copy(
          bindings = bindings.map(b => if (rides.contains(b.serial)) b else b.copy(beyondValue = true)),
          closed = true
        )

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
    * are the guard channel's, discharged by the guarded-return read rather than performed, so an uncovered one is bound
    * as its discharger's slot binds instead of being reported ([[Scope.signature]]). In a *body* an uncovered effect
    * is the error, which is the whole of the scope check — the synthesized entry point included, which binds every
    * effect of `main`'s clause with `with` ([[EntryPoint]]).
    */
  def write(original: OperatorResolvedValue, universe: RowChecker.Universe, handles: Boolean = false): Written = {
    // On the new effect path an implementation's clause is shaped as its member is, and what it captures is bound from
    // its handle ([[Captures.clause]]); every other value is written as it is.
    val orv        = Option.when(handles)(Captures.clause(original, universe)).flatten.getOrElse(original)
    val writer     = new Writer(universe, handles)
    val received   = receivedBindings(orv, handles)
    val scope      = Scope(
      received,
      thunkParameters(orv),
      callbackParameters(orv),
      thunkNeeds = thunkRequirements(orv),
      ridden = riddenEntries(orv),
      listed = if (handles) listedEntries(orv) else Map.empty
    )
    val view       = SignatureView.of(orv.signature)
    val body       = Option
      .when(writableBody(orv))(orv.runtime)
      .flatten
      .map(writer.walkDefinition(_, scope, view.binders.size + view.parameters.size))
    val overstated = body.toSeq.flatMap(_ => overDeclared(orv, writer.consumed.toSet))
    // A signature's scope holds none of the definition's handles, which are its value parameters. What its return
    // performs and nothing in it discharges is the guarded-return read's to discharge: on the new effect path a
    // `raise` there is run inside the compile track's `runThrow`, whose `Either` the guard read then reads
    // (`docs/effects.md` D36 point 9).
    val signatureScope = Scope(if (handles) received.map(_.copy(handle = None)) else received, Set.empty, signature = true)
    val guarded        =
      if (!handles) orv.signature
      else {
        val probe = new Writer(universe, handles)
        probe.walkDefinition(orv.signature, signatureScope, view.binders.size)
        guardDischarged(orv.signature, probe.uncovered.toSet)
      }
    val signature  = writer.walkDefinition(guarded, signatureScope, view.binders.size)
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
    * only through a callee's own constraint. A body-less value has nothing to check — its row is a contract, stated
    * where a later layer supplies the body. An `implement` clause is held only to the entries it
    * [[wrote]]: it declares the union of its block's clause rows, and an entry a sibling wrote is the sibling's to
    * perform.
    */
  private def overDeclared(orv: OperatorResolvedValue, consumed: Set[Int]): Seq[Violation] = {
    val binders = SignatureView.of(orv.signature).binders
    val effects = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    phantoms(orv)
      .filter { case (index, ability) => effects.contains(ability) && !consumed.contains(index) }
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
    *
    * On the new effect path (`handles`) an effect's binding is a **handle parameter** too, and every handle the
    * definition receives is equally near ([[Binding.group]]).
    */
  private def receivedBindings(orv: OperatorResolvedValue, handles: Boolean): Seq[Binding] = {
    val entries = entryArguments(orv)
    val binders = SignatureView.of(orv.signature).binders
    val effects = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    val group   = Binding.nextSerial()
    phantoms(orv).map { case (index, ability) =>
      Binding(
        ability,
        received = Some(index),
        arguments = entries.get(index).map(_.map(_.render)),
        handle = Option
          .when(handles && effects.contains(ability))(entries.get(index))
          .flatten
          .map(arguments =>
            HandleHole.Candidate(
              markPosition(binders(index).parameterType, orv).as(Handles.parameter(binders(index).name.value)),
              HandleHole.Entry(ability, arguments.map(binders(index).name.as(_)))
            )
          ),
        group = Some(group)
      )
    }
  }

  /** A signature whose return is run inside the discharger of each control effect it performs uncovered — on the new
    * effect path, the guarded-return read of `Throw` ([[WellKnownTypes.guardDischargers]]): `if(C, T) else raise("…")`
    * is read as `runThrow(if(C, T) else raise("…"))`, whose `Right(t)` or `Left("…")` is the guard's verdict.
    */
  private def guardDischarged(signature: Sourced[OperatorResolvedExpression], uncovered: Set[AbilityFQN])
      : Sourced[OperatorResolvedExpression] =
    WellKnownTypes.guardDischargers
      .collectFirst { case ((module, name), discharger) if name == "Throw" && uncovered(AbilityFQN(module, name)) => discharger }
      .fold(signature) { discharger =>
        val view = SignatureView.of(signature)
        signature.as(
          view
            .withReturnType(
              FunctionApplication(view.returnType.as(ValueReference(view.returnType.as(discharger))), view.returnType)
            )
            .toExpression
        )
      }

  /** The handle parameters a definition takes, in order: its leading value parameters whose type is one of its
    * binding binders for an **effect** (`docs/effects.md` D36 point 3), as the binder's index and ability. A definition
    * on the old effect path takes none.
    */
  private[row] def handleParameters(orv: OperatorResolvedValue): Seq[(Int, AbilityFQN)] = {
    val view    = SignatureView.of(orv.signature)
    val effects = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    val marks   = phantoms(orv).toMap
    view.parameters
      .map(_.value match {
        case ParameterReference(name) =>
          Some(view.binders.indexWhere(_.name.value === name.value)).filter(_ >= 0).flatMap(index =>
            marks.get(index).filter(effects.contains).map(index -> _)
          )
        case _                        => None
      })
      .takeWhile(_.isDefined)
      .flatten
  }

  /** The entries each of this definition's row-typed parameters lists, by name: on the new effect path a run hands the
    * code one handle per entry ([[Writer.walk]]).
    */
  private def listedEntries(orv: OperatorResolvedValue): Map[String, Seq[AbilityConstraint[OperatorResolvedExpression]]] =
    orv.effectRow.parameterEffects.flatMap(pe => valueParameterNames(orv).lift(pe.parameterIndex).map(_ -> pe.effects)).toMap

  /** The names of this definition's row-typed value parameters: a reference to one runs it. */
  private def thunkParameters(orv: OperatorResolvedValue): Set[String] =
    orv.effectRow.parameterEffects.flatMap(pe => valueParameterNames(orv).lift(pe.parameterIndex)).toSet

  /** What each of this definition's row-typed parameters must be **given** when it runs (`docs/effects.md` §1 rule 5,
    * D22): every entry its slot lists that the definition does not declare, spelled the same ([[declaredRow]], D30).
    * The definition handles such an entry where the code runs — a `with` around the run, or a callee's slot that
    * supplies it — and an entry it declares rides its caller's binding and needs nothing here.
    */
  private def thunkRequirements(orv: OperatorResolvedValue): Map[String, Seq[Need]] = {
    val own = declaredRow(orv)
    orv.effectRow.parameterEffects.flatMap { pe =>
      valueParameterNames(orv).lift(pe.parameterIndex).map { name =>
        name -> pe.effects
          .filterNot(entry => own.contains(spelledEntry(entry)))
          .map(entry => Need(entry.abilityFQN, Some(entry.typeArgs.map(_.render)), entry.typeArgs))
          .distinct
      }
    }.toMap
  }

  /** An entry as its declaration **spells** it: the ability, and its arguments rendered before anything is
    * substituted into them, binders by their names (`docs/effects.md` D30). Whether a slot's entry rides the
    * definition's own row or is supplied by the slot is decided on this alone, never at the arguments a call
    * determines: `raising[E](…, body uses *, Throw[E]: Unit) uses Throw[AssertionError]: Unit` supplies `Throw[E]` at
    * every `E`.
    */
  private def spelledEntry(entry: AbilityConstraint[OperatorResolvedExpression]): (AbilityFQN, Seq[String]) =
    (entry.abilityFQN, entry.typeArgs.map(_.render))

  /** A definition's own declared row, as it spells it ([[spelledEntry]]). */
  private def declaredRow(orv: OperatorResolvedValue): Set[(AbilityFQN, Seq[String])] =
    orv.effectRow.returnEffects.map(spelledEntry).toSet

  /** The effects each row-typed parameter's block **rides** — entries of this definition's own row that its slot
    * lists, spelled the same. The block's evidence for them is the caller's, so running it consumes this definition's
    * received binding: `describedAs` declares `Throw[AssertionError]` in order to run its body inside a `catch` of it.
    */
  private def riddenEntries(orv: OperatorResolvedValue): Map[String, Seq[Need]] = {
    val row = declaredRow(orv)
    orv.effectRow.parameterEffects.flatMap { slot =>
      valueParameterNames(orv).lift(slot.parameterIndex).map { name =>
        name -> slot.effects
          .filter(entry => row.contains(spelledEntry(entry)))
          .map(entry => Need(entry.abilityFQN, Some(entry.typeArgs.map(_.render)), entry.typeArgs))
          .distinct
      }
    }.toMap
  }

  /** The [[bindingEntries]] of `marker` that are **effects** — its declaration's row lists them — leaving out its `~`
    * constraints, which are abilities.
    */
  private def performedEntries(marker: ValueFQN, universe: RowChecker.Universe): Seq[ClauseEntry] = {
    val effects = universe.lookup(marker).toSeq.flatMap(_.effectRow.returnEffects.map(_.abilityFQN)).toSet
    bindingEntries(marker, universe).filter(entry => effects.contains(entry.ability))
  }

  /** One entry an implementation is parameterised by: its ability, and its arguments where they are ground
    * ([[knownArguments]]), which a binding in scope where it is bound answers.
    */
  private case class ClauseEntry(ability: AbilityFQN, known: Option[Seq[String]])

  /** Every entry the implementation `marker` is parameterised by — its phantom binders' abilities, each with the
    * arguments its clause spells, so `Throw[GitError]` is told from a `Throw[NotFetched]` beside it.
    */
  private def bindingEntries(
      marker: ValueFQN,
      universe: RowChecker.Universe,
      excluding: Set[Int] = Set.empty
  ): Seq[ClauseEntry] =
    universe.lookup(marker).toSeq.flatMap { orv =>
      val entries = entryArguments(orv)
      phantoms(orv).filterNot((index, _) => excluding.contains(index)).map { case (index, ability) =>
        ClauseEntry(ability, entries.get(index).flatMap(knownArguments(_, Map.empty)))
      }
    }

  /** The binders of the implementation `marker` whose entry is **per call**: every clause performing it implements a
    * member that declares it too (`FileSystem.readFile`'s `Throw[IoError]`, repeated by `standardFileSystem`'s
    * `readFile`). Dispatch hands such a clause the call's binding (`DeclaredEntries.forwarded`, `docs/effects.md`
    * §3.4), so the binding a `with` writes there is never run on, and nothing at the `with` need answer it — the
    * entry point binding `main`'s `FileSystem` holds no `Throw[IoError]`. The rest are the implementation's
    * **environment** ([[DeclaredEntries.environment]]), which the `with` must answer.
    *
    * Empty — every entry answered at the `with` — when any declaration it reads is unknown. Each is read through
    * [[RowChecker.Universe.lookup]], so a demand-driven universe reports it missing and fetches it before the write is
    * kept.
    */
  private def perCallBinders(marker: ValueFQN, ability: AbilityFQN, universe: RowChecker.Universe): Set[Int] =
    (for {
      orv         <- universe.lookup(marker)
      clauses     <- universe.clausesOf(marker)
      // Every clause is consulted before any is missing, so a demand-driven universe fetches them all in one round.
      read         = clauses.map(universe.lookup)
      values      <- read.sequence
      performing   = values.filter(clause => DeclaredEntries.of(clause).nonEmpty)
      implemented <- performing.traverse(clause => universe.lookup(memberOf(clause.vfqn, ability)).map(clause -> _))
    } yield {
      val environment = DeclaredEntries.environment(implemented)
      DeclaredEntries.of(orv).collect { case (index, entry) if !environment.contains(entry) => index }.toSet
    }).getOrElse(Set.empty)

  /** The ability member an implementation's clause implements. */
  private def memberOf(clause: ValueFQN, ability: AbilityFQN): ValueFQN =
    ValueFQN(ability.moduleName, QualifiedName(clause.name.name, Qualifier.Ability(ability.abilityName)))

  /** The names of this definition's **callback** parameters — function-typed, with a row in the codomain: code, which
    * may be called or passed on and never kept.
    */
  private def callbackParameters(orv: OperatorResolvedValue): Set[String] =
    orv.effectRow.callbackEffects.flatMap(cb => valueParameterNames(orv).lift(cb.parameterIndex)).toSet

  /** The names of this definition's value parameters: the body's leading lambdas past its binders, and no further — a
    * body that is itself a function (`def f(body uses *: Unit): Handler = s -> …`) does not shift them (§8 item 48).
    */
  private def valueParameterNames(orv: OperatorResolvedValue): Seq[String] = {
    val view       = SignatureView.of(orv.signature)
    val (names, _) = RowChecker.peelBinders(orv.runtime.map(_.value).getOrElse(view.returnType.value))
    // A handle parameter (`docs/effects.md` D36) is not one of the definition's own, which its row's indices count.
    names.take(view.binders.size + view.parameters.size).takeRight(view.parameters.size).dropWhile(Handles.isParameter)
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

  /** An implementation's type parameters its **pattern does not take**: `K` of `implement[T, K] keyed(f: T => K):
    * Source[T]`. Nothing where the implementation runs determines one, so it is refused at the marker
    * (`docs/effects.md` D29, which reversed D27's reading of it off a `with`'s arguments).
    */
  def unpatternedParameters(implementation: OperatorResolvedValue): Seq[String] = {
    val pattern = SignatureView.of(implementation.signature).parameters.map(_.value)
    unmarkedBinders(implementation).filterNot(name => pattern.exists(OperatorResolvedExpression.containsVar(_, name)))
  }

  /** The names of `orv`'s binders its declaration does not mark as bindings, in order. */
  private def unmarkedBinders(orv: OperatorResolvedValue): Seq[String] = {
    val marked = phantoms(orv).map(_._1).toSet
    SignatureView.of(orv.signature).binders.zipWithIndex.collect {
      case (parameter, index) if !marked.contains(index) => parameter.name.value
    }
  }

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
    * The only definition of where a binding sits. Besides the write, the evidence after monomorphization reads it
    * (`docs/effects.md` D35), to build the binding an installed implementation stands for.
    */
  def phantoms(orv: OperatorResolvedValue): Seq[(Int, AbilityFQN)] =
    SignatureView.of(orv.signature).binders.zipWithIndex.flatMap { case (binder, index) =>
      binder.parameterType.flatMap(declared => markedAbility(declared.value)).map(index -> _)
    }

  /** The row entry each of a definition's binding binders stands for, by binder index: the `k`-th binder marked for an
    * ability is the `k`-th entry of the declared row naming it, since the desugar mints them in the order written. An
    * ability whose marks and entries do not pair one to one — a `~` constraint's binder has no entry — pairs none.
    */
  private[row] def entryArguments(orv: OperatorResolvedValue): Map[Int, Seq[OperatorResolvedExpression]] =
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

  /** An entry's type arguments as the caller **spells** them: the callee's declaration with what `substitution` writes
    * for the callee's binders, and [[None]] when one of them is a binder the call leaves undetermined. Unlike
    * [[knownArguments]], a spelled argument may name the caller's own binders (`Source[A]` in a definition generic in
    * `A`), since it is read where the caller's binders are in scope.
    */
  private def spelledArguments(
      arguments: Seq[OperatorResolvedExpression],
      substitution: Map[String, OperatorResolvedExpression]
  ): Option[Seq[OperatorResolvedExpression]] =
    arguments.traverse(spelledArgument(_, substitution))

  /** [[spelledArguments]], rendered: what a reference's entry and a binding are compared by (`docs/effects.md` D30). */
  private def spelledRendered(
      arguments: Seq[OperatorResolvedExpression],
      substitution: Map[String, OperatorResolvedExpression]
  ): Option[Seq[String]] =
    spelledArguments(arguments, substitution).map(_.map(_.render))

  private def spelledArgument(
      argument: OperatorResolvedExpression,
      substitution: Map[String, OperatorResolvedExpression]
  ): Option[OperatorResolvedExpression] = argument match {
    case ParameterReference(name)              => substitution.get(name.value)
    case ValueReference(name, typeArgs)        =>
      typeArgs.traverse(typeArg => spelledArgument(typeArg.value, substitution).map(typeArg.as)).map(ValueReference(name, _))
    case FunctionApplication(target, applied)  =>
      (spelledArgument(target.value, substitution), spelledArgument(applied.value, substitution)).mapN(
        (spelledTarget, spelledApplied) => FunctionApplication(target.as(spelledTarget), applied.as(spelledApplied))
      )
    case _: IntegerLiteral | _: StringLiteral  => Some(argument)
    case _                                     => None
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

  /** The ability an implementation marker implements, read off its **resolved qualifier** — never off its name. */
  private def abilityOf(marker: ValueFQN, universe: RowChecker.Universe): Option[AbilityFQN] =
    universe.lookup(marker).flatMap(_.name.value.qualifier match {
      case ResolveQualifier.AbilityImplementation(ability, _, _) => Some(ability)
      case _                                                     => None
    })

  private class Writer(universe: RowChecker.Universe, handles: Boolean) {
    val violations: mutable.Buffer[Violation] = mutable.Buffer.empty

    /** The binder indices of the **received** bindings some reference was written from ([[overDeclared]]). */
    val consumed: mutable.Set[Int] = mutable.Set.empty

    /** The [[Binding.serial]]s of the bindings some reference was written from, so a body `with` that binds nothing is
      * reported ([[unusedWith]]).
      */
    private val used: mutable.Set[Long] = mutable.Set.empty

    /** Per [[Binding.serial]], the arguments of every entry a reference was written from it at, as this definition
      * spells them ([[spelledArguments]]) — [[None]] for one it does not spell, or a use that is not a written
      * reference. What a `with` supplying arguments reads its implementation's type parameters off
      * ([[suppliedTypeArguments]]).
      */
    private val answered: mutable.Map[Long, Seq[Option[Seq[OperatorResolvedExpression]]]] = mutable.Map.empty

    private def use(binding: Binding, spelled: Option[Seq[OperatorResolvedExpression]]): Unit = {
      used += binding.serial
      answered(binding.serial) = answered.getOrElse(binding.serial, Seq.empty) :+ spelled
    }

    /** A reference written from `binding`. One whose entry the call does not spell (`raise`'s `Throw[E]`, `E` left to
      * the checker) may stand for any received entry of its ability, so it consumes all of them: only a spelled
      * reference tells two entries of one ability apart.
      */
    private def consume(binding: Binding, spelled: Option[Seq[OperatorResolvedExpression]] = None): Unit = {
      use(binding, spelled)
      binding.received.foreach(consumed += _)
    }

    private def consumeUnspelled(binding: Binding, scope: Scope): Unit =
      if (binding.received.isDefined)
        scope.bindings.filter(b => b.ability == binding.ability).flatMap(_.received).foreach(consumed += _)

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
        case WithBinding(subject, implementation) => walkWith(subject, implementation, scope, applied)._1

        case ParameterReference(name) if scope.captured.contains(name.value) =>
          violations += capturedCode(name, scope)
          expr

        // A reference to one of this definition's row-typed parameters is a thunk: running it is applying it.
        case ParameterReference(name) if scope.thunks.contains(name.value) =>
          checkGiven(name, scope)
          scope.ridden
            .getOrElse(name.value, Seq.empty)
            .foreach(need => scope.binding(need.ability, need.arguments).foreach(consume(_, Some(need.spelled))))
          scope.listed.getOrElse(name.value, Seq.empty) match {
            case Seq()   => expr.as(FunctionApplication(expr, unitValue(expr)))
            // On the new effect path a run hands the code a handle for every entry its clause lists, each the nearest
            // in scope whose entry answers it (`docs/effects.md` D36 point 6).
            case entries => expr.as(applyChain(expr, entries.map(entry => handleHole(expr, entry.abilityFQN, entry.typeArgs.map(expr.as(_)), scope))))
          }

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
              if (handles && !scope.signature) walkHandleWith(expr, callee, args, scope, applied)
              else walkSupplied(expr, expr.as(head), callee, existing, args, scope, applied)
            case ValueReference(callee, existing)                                   =>
              val (written, holes, writtenBy) = writeBindings(expr.as(head), callee.value, existing, scope, args)
              unappliedEffectful(callee, args.size + applied, scope).foreach(violations += _)
              val substitution     =
                if (handles) writtenBy
                else universe.lookup(callee.value).fold(Map.empty)(orv => callSubstitution(orv, existing, args))
              val adjusted         = args.zipWithIndex.map { case (arg, index) =>
                walkArgument(arg, callee.value, index, scope, substitution)
              }
              expr.as(applyChain(written, holes ++ adjusted))
            case ParameterReference(selector) if selector.value === matchSelector =>
              expr.as(applyChain(walk(expr.as(head), scope), args.map(walkCode(_, scope, Int.MaxValue))))
            case _                                                                  =>
              expr.as(applyChain(walkCode(expr.as(head), scope, args.size), args.map(walk(_, scope))))
          }
      }

    /** A `with` puts its implementation in scope for its subject, innermost first, and disappears. Returns the subject
      * written, and the serial of the binding it put in scope, if it put one.
      */
    private def walkWith(
        subject: Sourced[OperatorResolvedExpression],
        implementation: Sourced[ValueFQN],
        scope: Scope,
        applied: Int
    ): (Sourced[OperatorResolvedExpression], Option[Long]) =
      abilityOf(implementation.value, universe) match {
        case Some(ability) =>
          val perCall = perCallBinders(implementation.value, ability, universe)
          val performed = performedEntries(implementation.value, universe).map(_.ability).toSet
          val clauses   = bindingEntries(implementation.value, universe, perCall).collect {
            case entry if performed(entry.ability) =>
              Binding.Clause(entry.ability, entry.known, scope.binding(entry.ability, entry.known).map(_.serial))
          }
          val binding   = Binding(ability, byWith = true, clauses = clauses)
          // The implementation's own clause entries are answered where the `with` stands — the scope check of what
          // installing it performs — except a per-call one, which each operation's call answers.
          writeBindings(implementation.as(ValueReference(implementation)), implementation.value, Seq.empty, scope, Seq.empty, perCall)._1
          val written = walk(subject, scope.bind(binding), applied)
          if (!scope.signature && !used.contains(binding.serial))
            violations += unusedWith(subject, implementation, ability, scope)
          (written, Some(binding.serial))
        case None          =>
          violations += Violation(implementation.as("This name is not an implementation."))
          (walk(subject, scope, applied), None)
      }

    /** A `with` supplying arguments, as the resolver lowers it: `scripted$with(List("Bob"), greet with scripted)`
      * ([[ImplementationParameters.binder]]). The arguments are values computed where the `with` is written, outside
      * its binding; the subject, the binder's last parameter, stands where the `with` stands and is walked exactly as
      * the `with` itself would be, so a lambda or an unapplied reference there is what it was before the arguments
      * were supplied.
      *
      * The binder's type parameters are the implementation's own, and the entries the `with` binds decide them where
      * it runs (`docs/effects.md` §8 item 31): the subject is walked first, and what the entries it answered spell is
      * written as the binder's type arguments ([[suppliedTypeArguments]]), so `pickInt(1) with mapping(x -> show(x))`
      * types the lambda at `Int => String` rather than leaving `x` to its own body. An argument list the call spells
      * itself is left alone.
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
      // The resolver puts the `with` right after the arguments it supplies; what follows applies the subject's value.
      val position = args.indexWhere(_.value match {
        case WithBinding(_, named) => ImplementationParameters.binderOf(named.value) == callee.value
        case _                        => false
      })
      def written(entries: Seq[Option[Seq[OperatorResolvedExpression]]], marker: Option[ValueFQN]) =
        writeBindings(
          head,
          callee.value,
          if (existing.isEmpty) marker.fold(Seq.empty)(suppliedTypeArguments(callee, _, entries)) else existing,
          scope,
          args
        )._1
      if (position < 0) expr.as(applyChain(written(Seq.empty, None), args.map(walk(_, scope))))
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
        val (supplied, rest)                        = args.splitAt(position)
        val WithBinding(subject, implementation) = rest.head.value: @unchecked
        // The subject first: the entries its references are written at decide the binder's type arguments.
        val (bound, serial)                         = walkWith(subject, implementation, scope, applied + rest.size - 1)
        val walkedSupplied                          = supplied.map(walk(_, scope))
        val entries                                 = serial.flatMap(answered.get).getOrElse(Seq.empty)
        expr.as(
          applyChain(
            written(entries, Some(implementation.value)),
            walkedSupplied ++ (bound +: rest.tail.map(walk(_, scope)))
          )
        )
      }
    }

    /** A `with` on the new effect path (`docs/effects.md` D36 point 5): the handle its implementation's `data` holds,
      * bound around its subject as an ordinary `let`, where every hole in the subject may take it.
      *
      * {{{
      * scripted$with(answer, greet("Bob") with scripted)
      *   ⟶ ($with1 -> greet(?Console{$with1: Console | …}, "Bob"))(scripted$Handle(answer))
      * }}}
      *
      * The arguments are computed where the `with` is written, outside its binding, as before. The handle's entry is
      * the implementation's pattern, its type parameters keyed holes written at the data's constructor, so the checker
      * reads them off the arguments and the uses alike.
      */
    private def walkHandleWith(
        expr: Sourced[OperatorResolvedExpression],
        callee: Sourced[ValueFQN],
        args: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope,
        applied: Int
    ): Sourced[OperatorResolvedExpression] = {
      val position = args.indexWhere(_.value match {
        case WithBinding(_, named) => ImplementationParameters.binderOf(named.value) == callee.value
        case _                     => false
      })
      if (position < 0) expr.as(applyChain(expr.as(ValueReference(callee)), args.map(walk(_, scope))))
      else {
        val declared                             =
          universe.lookup(callee.value).fold(0)(orv => SignatureView.of(orv.signature).parameters.size - 1)
        val (supplied, rest)                     = args.splitAt(position)
        val WithBinding(subject, implementation) = rest.head.value: @unchecked
        if (position != declared)
          violations += Violation(
            args(position).as(
              s"The implementation '${implementationName(implementation.value)}' takes $declared parameter" +
                s"${if (declared == 1) "" else "s"}, but this `with` supplies $position."
            )
          )
        (abilityOf(implementation.value, universe), universe.lookup(implementation.value)) match {
          case (Some(ability), Some(marker)) =>
            val view    = SignatureView.of(marker.signature)
            // The data takes the implementation's own type parameters, never the binding a `~` constraint on one mints.
            val own     = unmarkedBinders(marker)
            val keys    = own.map(_ -> keyedHole(implementation)).toMap
            // The handle's capture fields (`docs/effects.md` D36 point 2): a captured entry takes the handle in scope
            // here, outside the binding, and any other `unit`.
            val fields  = Captures.captured(marker, universe).getOrElse(Seq.empty)
            val held    = fields.map { case ((fieldAbility, arguments), isCaptured) =>
              val key = keyedHole(implementation)
              val arg =
                if (isCaptured)
                  handleHole(
                    implementation,
                    fieldAbility,
                    arguments.map(argument => implementation.as(substituted(argument, keys))),
                    scope
                  )
                else unitValue(implementation)
              (key, arg)
            }
            val entry   = HandleHole.Entry(
              ability,
              view.parameters.map(pattern =>
                pattern.as(keys.foldLeft(pattern.value) { case (acc, (name, key)) => substitute(acc, name, key) })
              )
            )
            val name    = implementation.as(Handles.binding(nextWith()))
            val binding = Binding(ability, byWith = true, handle = Some(HandleHole.Candidate(name, entry)))
            val bound   = walk(subject, scope.shadow(name.value).bind(binding), applied + rest.size - 1)
            if (!used.contains(binding.serial)) violations += unusedWith(subject, implementation, ability, scope)
            val handle  = applyChain(
              implementation.as(
                ValueReference(
                  implementation.as(
                    ValueFQN(
                      marker.vfqn.moduleName,
                      QualifiedName(Handles.dataType(implementationName(implementation.value)), Qualifier.Default)
                    )
                  ),
                  own.map(name => implementation.as(keys(name))) ++ held.map((key, _) => implementation.as(key))
                )
              ),
              supplied.map(walk(_, scope)) ++ held.map(_._2)
            )
            val let     = FunctionApplication(implementation.as(FunctionLiteral(name, None, bound)), implementation.as(handle))
            expr.as(applyChain(implementation.as(let), rest.tail.map(walk(_, scope))))
          case _                             =>
            violations += Violation(implementation.as("This name is not an implementation."))
            walk(subject, scope, applied)
        }
      }
    }

    private var withs: Long = 0

    private var blocks: Long = 0

    private def nextBlock(): Long = {
      blocks += 1
      blocks
    }

    private def nextWith(): Long = {
      withs += 1
      withs
    }

    /** A named implementation's own name, off its marker's qualifier. */
    private def implementationName(marker: ValueFQN): String =
      marker.name.qualifier match {
        case Qualifier.AbilityImplementation(_, _, Some(name)) => name
        case _                                                 => marker.name.name
      }

    /** The type arguments of the binder `callee` of the implementation `marker` that the `entries` a `with` binds
      * decide: the implementation's pattern matched against each entry's arguments, as the two-site search matches an
      * anonymous implementation's — here on the arguments as spelled, before monomorphization. `implement[T]
      * mapping(f: T => String): Source[T]` bound for `Source[Int]` runs at `T := Int`, so its binder is written
      * `mapping$with[Int]`.
      *
      * Only the run of the binder's leading type parameters every entry decides alike is written; the rest are the
      * checker's to infer from the arguments, as before. So nothing is written when the entries disagree or when the
      * pattern does not match — which the environment threading reports where the implementation runs. An entry only
      * the checker reads arrives spelled with a keyed hole ([[sharedHoles]]).
      */
    private def suppliedTypeArguments(
        callee: Sourced[ValueFQN],
        marker: ValueFQN,
        entries: Seq[Option[Seq[OperatorResolvedExpression]]]
    ): Seq[Sourced[OperatorResolvedExpression]] = {
      val solution = (for {
        implementation <- universe.lookup(marker)
        view            = SignatureView.of(implementation.signature)
        parameters      = view.binders.map(_.name.value).toSet
        pattern         = view.parameters.map(_.value)
        spelled        <- entries.sequence.filter(_.nonEmpty)
        solutions      <- spelled.traverse(arguments =>
                            Option.when(arguments.size == pattern.size)(()).flatMap(_ =>
                              matchPattern(pattern, arguments, parameters)
                            )
                          )
        solution       <- solutions.foldLeftM(Map.empty[String, OperatorResolvedExpression])(mergeSolutions)
      } yield solution).getOrElse(Map.empty)
      universe.lookup(callee.value).fold(Seq.empty) { binder =>
        unmarkedBinders(binder).map(solution.get).takeWhile(_.isDefined).flatten.map(callee.as(_))
      }
    }

    /** What matching a pattern's arguments against an entry's binds the implementation's `parameters` to, if they match. */
    private def matchPattern(
        pattern: Seq[OperatorResolvedExpression],
        arguments: Seq[OperatorResolvedExpression],
        parameters: Set[String]
    ): Option[Map[String, OperatorResolvedExpression]] =
      pattern
        .zip(arguments)
        .traverse((expected, actual) => matchArgument(expected, actual, parameters))
        .flatMap(_.foldLeftM(Map.empty[String, OperatorResolvedExpression])(mergeSolutions))

    private def matchArgument(
        expected: OperatorResolvedExpression,
        actual: OperatorResolvedExpression,
        parameters: Set[String]
    ): Option[Map[String, OperatorResolvedExpression]] =
      (expected, actual) match {
        case (ParameterReference(name), _) if parameters.contains(name.value)                      =>
          Some(Map(name.value -> actual))
        case (ValueReference(expectedName, expectedArguments), ValueReference(actualName, actualArguments))
            if expectedName.value == actualName.value && expectedArguments.size == actualArguments.size =>
          matchPattern(expectedArguments.map(_.value), actualArguments.map(_.value), parameters)
        case (FunctionApplication(expectedTarget, expectedArgument), FunctionApplication(actualTarget, actualArgument)) =>
          matchPattern(
            Seq(expectedTarget.value, expectedArgument.value),
            Seq(actualTarget.value, actualArgument.value),
            parameters
          )
        case (_: IntegerLiteral | _: StringLiteral, _) if expected.render == actual.render         => Some(Map.empty)
        case _                                                                                     => None
      }

    /** Two solutions of one match, when they agree on every parameter both bind. */
    private def mergeSolutions(
        left: Map[String, OperatorResolvedExpression],
        right: Map[String, OperatorResolvedExpression]
    ): Option[Map[String, OperatorResolvedExpression]] =
      Option.when(right.forall((name, value) => left.get(name).forall(_.render == value.render)))(left ++ right)

    /** An effect a code parameter's clause lists is this definition's **to handle or to declare**
      * (`docs/effects.md` §1 rule 5, D22): every listed entry it does not declare ([[thunkRequirements]]) must be
      * handled where the code runs — by a `with` around the run, or by a callee's slot that supplies it, such as a
      * discharger's the code is passed on to. Nothing gives an effect from nothing (`docs/effects.md` D26).
      */
    private def checkGiven(name: Sourced[String], scope: Scope): Unit =
      if (!scope.signature)
        scope.thunkNeeds.getOrElse(name.value, Seq.empty).foreach { case Need(ability, arguments, spelled) =>
          scope.binding(ability, arguments) match {
            case Some(binding) if binding.bySlot || binding.byWith => use(binding, Some(spelled))
            case _                                                 =>
              violations += Violation(
                name.as(
                  s"'${name.value}' may perform the effect '${ability.abilityName}' here, which this definition " +
                    "neither handles nor declares."
                ),
                Seq(
                  s"Handle it by passing '${name.value}' to a parameter that discharges it (such as `catch` or " +
                    s"`runThrow`) or by binding an implementation around the run with `with`, or add " +
                    s"'${ability.abilityName}' to this definition's `uses` clause to pass it on to its caller."
                )
              )
          }
        }

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
        .filter(orv => !scope.signature && arguments < orv.effectRow.returnArity)
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
                if (cb.closed) scope.enterClosed(rides(calleeOrv, index, cb.effects, scope, substitution)) else scope
              walkCode(arg, base, cb.arity)
            case None                  => walk(arg, scope)
          }
        case Some((calleeOrv, slot)) if handles && slot.nonEmpty =>
          // The new effect path (`docs/effects.md` D36 point 6): the code is a block taking one handle per entry its
          // slot lists, all equally near, of whatever type the callee's body hands it. What it uses through `*` it
          // captures from here; a closed slot's block captures nothing.
          val block   = nextBlock()
          val group   = Binding.nextSerial()
          val handles = slot.zipWithIndex.map { case (entry, position) =>
            Binding(
              entry.abilityFQN,
              bySlot = true,
              handle = Some(
                HandleHole.Candidate(
                  arg.as(s"$$block$block$$$position"),
                  HandleHole.Entry(entry.abilityFQN, entry.typeArgs.map(argument => arg.as(substituted(argument, substitution))))
                )
              ),
              group = Some(group)
            )
          }
          val base    = if (closedSlot(calleeOrv, index)) scope.enterClosed(Set.empty) else scope
          val inside  = handles.foldLeft(base)((acc, binding) => acc.shadow(binding.handle.get.name.value).bind(binding))
          handles.foldRight(walk(arg, inside)) { (binding, body) =>
            arg.as(FunctionLiteral(binding.handle.get.name, None, body))
          }
        case Some((calleeOrv, slot))          =>
          val name    = thunkParameter
          val base    =
            if (closedSlot(calleeOrv, index)) scope.enterClosed(rides(calleeOrv, index, slot, scope, substitution))
            else scope
          val inside  = suppliedScope(calleeOrv, index, base, substitution).shadow(name)
          arg.as(
            FunctionLiteral(
              arg.as(name),
              Some(arg.as(ValueReference(arg.as(WellKnownTypes.unitTypeFQN)))),
              walk(arg, inside)
            )
          )
      }

    /** The scope inside a row-typed slot: every entry the slot **supplies** — one the callee's own declared row does
      * not already have, spelled the same ([[spelledEntry]]) — answered by the callee, at the entry's arguments as this
      * call spells them (`substitution`), so a supplied `Throw[Refused]` answers only a `Throw[Refused]` and a received
      * `Throw[Missing]` is still reached past it. What the callee gives there is read off its body after
      * monomorphization (`docs/effects.md` D35).
      */
    private def suppliedScope(
        calleeOrv: OperatorResolvedValue,
        index: Int,
        scope: Scope,
        substitution: Map[String, OperatorResolvedExpression]
    ): Scope = {
      val own     = declaredRow(calleeOrv)
      val entries = calleeOrv.effectRow.parameterEffects.find(_.parameterIndex === index).toSeq.flatMap(_.effects)
      entries.filterNot(entry => own.contains(spelledEntry(entry))).foldLeft(scope) { (acc, entry) =>
        acc.bind(Binding(entry.abilityFQN, bySlot = true, arguments = spelledRendered(entry.typeArgs, substitution)))
      }
    }

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

    /** The bindings in `scope` that the entries of a slot's row which **ride** answer — entries the callee's own
      * declared row has, spelled the same ([[spelledEntry]]) — so the walk
      * continues to the caller's binding for them instead of the slot supplying one (§2.2). Each is found as a
      * reference to the entry would find it, at the arguments this call spells.
      */
    private def rides(
        orv: OperatorResolvedValue,
        index: Int,
        slot: Seq[AbilityConstraint[OperatorResolvedExpression]],
        scope: Scope,
        substitution: Map[String, OperatorResolvedExpression]
    ): Set[Long] = {
      val row = declaredRow(orv)
      slot
        .filter(entry => row.contains(spelledEntry(entry)))
        .flatMap(entry => scope.binding(entry.abilityFQN, spelledRendered(entry.typeArgs, substitution)).map(_.serial))
        .toSet
    }

    /** The entries a callee's parameter `index` declares, when that parameter is a row position at all. */
    private def rowSlot(
        orv: OperatorResolvedValue,
        index: Int
    ): Option[Seq[AbilityConstraint[OperatorResolvedExpression]]] =
      orv.effectRow.parameterEffects.find(_.parameterIndex === index).map(_.effects)

    /** Write the callee's type arguments this call determines, **merged by index**: a binder the callee's declaration
      * marks as a **binding** takes the implementation the resolution order gives it, and every other binder takes, in
      * order, what this call determines for it — the caller's own explicit `typeArgs` where it spelled any, else what
      * a **supplied row slot** settles (A6).
      *
      * Nothing is written from a binding: every binding binder is written `Default`, and the evidence after
      * monomorphization says which implementation an effect runs on (`docs/effects.md` D35). The binding is still
      * looked up ([[bindingFor]]), because that lookup is the scope check.
      *
      * Type-argument application is positional, so what is written is the run from index 0 that every slot below it
      * fills. In a body a gap before a binding is written as the checker's hole ([[mergeTypeArguments]]), so every
      * binding is reached; a binder past the last one is left to the checker, which is always the fail-safe direction.
      * In a signature, which has no hole, a binding behind a gap is reported ([[unreachableBinding]]): the checker has
      * no way to solve a binding and would silently ground it.
      */
    private def writeBindings(
        reference: Sourced[OperatorResolvedExpression],
        callee: ValueFQN,
        existing: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope,
        args: Seq[Sourced[OperatorResolvedExpression]],
        perCall: Set[Int] = Set.empty
    ): (Sourced[OperatorResolvedExpression], Seq[Sourced[OperatorResolvedExpression]], Map[String, OperatorResolvedExpression]) =
      universe.lookup(callee) match {
        case None      => (reference, Seq.empty, Map.empty)
        case Some(orv) =>
          val effects          = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
          val marks            = phantoms(orv).toMap
          val binders          = SignatureView.of(orv.signature).binders
          val handled          = if (handles) handleParameters(orv) else Seq.empty
          // On the new effect path what a slot's entry stands for is the checker's to decide, by the hole rule, so it is
          // never read off the actual's declared row here (`docs/effects.md` D36, which replaces A6's reading).
          val supplied         =
            if (existing.nonEmpty || handles) existing
            else suppliedArguments(orv, args, marks.keySet)
          val entries          = entryArguments(orv)
          val substitution     = substitutionOf(orv, marks.keySet, supplied)
          val shared           =
            if (handled.nonEmpty) SharedHoles(Seq.empty, Map.empty)
            else sharedHoles(orv, marks, supplied, entries, substitution, reference, scope)
          val determined       = supplied ++ shared.written
          // A handle's entry names the callee's binders, so every binder it names is written — a keyed hole where the
          // call determines nothing — and the entry the hole asks for is spelled with what is written there.
          // So does a block's, which a listing slot hands its code: `catch`'s `E`.
          val named            = (handled.flatMap((index, _) => entries.getOrElse(index, Seq.empty)) ++
            (if (handles) orv.effectRow.parameterEffects.flatMap(_.effects.flatMap(_.typeArgs))
             else Seq.empty))
          val reachTo          = (handled.map(_._1) ++ binders.zipWithIndex.collect {
            case (binder, i) if named.exists(containsVar(_, binder.name.value)) => i
          }).maxOption.getOrElse(-1)
          val (written, reach) = mergeTypeArguments(
            binders.size,
            marks,
            determined,
            Option.when(handles || !scope.signature)(_ =>
              if (handles) reference.as(keyedHole(reference))
              else reference.as(ValueReference(reference.as(WellKnownTypes.inferredTypeArgumentFQN)))
            ),
            reachTo,
            (index, ability) => {
              if (!perCall.contains(index)) bindingFor(
                ability,
                if (handled.nonEmpty) None else entries.get(index).flatMap(spelledRendered(_, substitution)),
                entries.get(index).flatMap(spelledArguments(_, substitution ++ shared.holes)),
                effects.contains(ability),
                reference,
                scope
              )
              if (handled.exists(_._1 == index)) reference.as(keyedHole(reference))
              else reference.as(defaultBinding(reference))
            }
          )
          marks.toSeq.filter(_._1 >= reach).sortBy(_._1).foreach { case (_, ability) =>
            violations += unreachableBinding(orv, ability, reference)
          }
          val writtenBy        = binders.map(_.name.value).zip(written.map(_.value)).toMap
          val holes            = handled.map { case (index, ability) =>
            handleHole(
              reference,
              ability,
              entries.getOrElse(index, Seq.empty).map(argument => reference.as(substituted(argument, writtenBy))),
              scope
            )
          }
          (
            if (written.isEmpty) reference else reference.as(ValueReference(nameOf(reference), written)),
            holes,
            writtenBy
          )
      }

    /** A handle position asking for the entry `ability[arguments]`, offered every handle in `scope` that may answer it.
      * Which of them does is the checker's (`docs/effects.md` D36 point 4); here each may, so a `with` is "used" if any
      * hole could take it, which is D23 by name — the checker holds the exact rule.
      */
    private def handleHole(
        at: Sourced[?],
        ability: AbilityFQN,
        arguments: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope
    ): Sourced[OperatorResolvedExpression] = {
      val offered = candidates(ability, scope)
      offered.flatten.foreach((binding, _) => consume(binding, None))
      at.as(HandleHole(HandleHole.Entry(ability, arguments), offered.map(_.map(_._2))))
    }

    private def substituted(
        expression: OperatorResolvedExpression,
        substitution: Map[String, OperatorResolvedExpression]
    ): OperatorResolvedExpression =
      substitution.foldLeft(expression) { case (acc, (name, value)) => substitute(acc, name, value) }

    /** The handles in `scope` that may answer an entry of `ability`, nearest first, grouped by the binder that put them
      * in scope. A handle beyond a value is left out: a value may use no effect bound outside it, which the scope
      * check reports by name ([[bindingFor]]).
      */
    private def candidates(ability: AbilityFQN, scope: Scope): Seq[Seq[(Binding, HandleHole.Candidate)]] =
      scope.bindings
        .filter(binding => binding.ability == ability && !binding.beyondValue)
        .flatMap(binding => binding.handle.map(handle => (binding.distance, (binding, handle))))
        .foldLeft(Seq.empty[(Long, Seq[(Binding, HandleHole.Candidate)])]) {
          case (groups :+ ((distance, group)), (next, candidate)) if distance == next =>
            groups :+ (distance -> (group :+ candidate))
          case (groups, (next, candidate))                                             =>
            groups :+ (next -> Seq(candidate))
        }
        .map(_._2)

    /** The **keyed holes** of a reference whose entry a `with` binds but whose arguments this call does not spell
      * (`docs/effects.md` §8 item 36): `pick(1) with mapping(x -> show(x))`, for `def pick[A](a: A) uses Source[A]`,
      * reads `A` off `1`, which only the checker does. So `A` is written as a hole with a key, `pick[Inferred[k]]`, and
      * the entry is spelled with that hole (`Source[Inferred[k]]`) for the `with`, whose binder then takes the same
      * hole by matching the implementation's pattern against it ([[suppliedTypeArguments]]): `mapping$with[Inferred[k]]`.
      * The checker reads every occurrence of one key as one metavariable, so the lambda is typed at what `1` gives.
      *
      * Nothing is decided here: a hole is the checker's to solve, as a binder left off the list always was, and the key
      * only says that two places stand for the same type. Only a `with`'s own binding gets them — the one place whose
      * type arguments are read from the entries it binds — and only in a body, which is the only place a hole may stand.
      *
      * `written` is what the call's type-argument list gains past what it determines — a hole for every undetermined
      * binder up to the last keyed one — and `holes` is what spells the entry: each keyed binder's hole, by name.
      */
    private def sharedHoles(
        orv: OperatorResolvedValue,
        marks: Map[Int, AbilityFQN],
        supplied: Seq[Sourced[OperatorResolvedExpression]],
        entries: Map[Int, Seq[OperatorResolvedExpression]],
        substitution: Map[String, OperatorResolvedExpression],
        reference: Sourced[OperatorResolvedExpression],
        scope: Scope
    ): SharedHoles = {
      val open    = SignatureView
        .of(orv.signature)
        .binders
        .zipWithIndex
        .collect { case (binder, index) if !marks.contains(index) => binder.name.value }
        .drop(supplied.size)
      val unspelt = marks.toSeq.flatMap { case (index, ability) =>
        entries
          .get(index)
          .filter(arguments =>
            spelledArguments(arguments, substitution).isEmpty &&
              scope.binding(ability, spelledRendered(arguments, substitution)).exists(_.byWith)
          )
          .toSeq
          .flatten
      }
      val keyed   = open.filter(name => unspelt.exists(OperatorResolvedExpression.containsVar(_, name)))
      if (scope.signature || keyed.isEmpty) SharedHoles(Seq.empty, Map.empty)
      else {
        val holes = keyed.map(name => name -> keyedHole(reference)).toMap
        val hole  = ValueReference(reference.as(WellKnownTypes.inferredTypeArgumentFQN))
        SharedHoles(
          open.take(open.lastIndexWhere(holes.contains) + 1).map(name => reference.as(holes.getOrElse(name, hole))),
          holes
        )
      }
    }

    private var holeKeys: Long = 0

    private def keyedHole(at: Sourced[?]): OperatorResolvedExpression = {
      holeKeys += 1
      ValueReference(
        at.as(WellKnownTypes.inferredTypeArgumentFQN),
        Seq(at.as(IntegerLiteral(at.as(BigInt(holeKeys)))))
      )
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
        hole: Option[Int => Sourced[OperatorResolvedExpression]],
        reachTo: Int,
        binding: (Int, AbilityFQN) => Sourced[OperatorResolvedExpression]
    ): (Seq[Sourced[OperatorResolvedExpression]], Int) = {
      val lastBinding       = math.max(marks.keys.maxOption.getOrElse(-1), reachTo)
      val (slots, leftover) = (0 until binderCount).foldLeft(
        (Seq.empty[Option[Sourced[OperatorResolvedExpression]]], determined)
      ) { case ((acc, rest), index) =>
        marks.get(index) match {
          case Some(ability) => (acc :+ Some(binding(index, ability)), rest)
          case None          =>
            (acc :+ rest.headOption.orElse(hole.filter(_ => index <= reachTo || index < lastBinding).map(_(index))), rest.drop(1))
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
      * short prefix silently would leave the second binding unwritten. In a body the same call needs
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

    /** Answer one binder at a reference — the scope check. An **ability** with nothing in scope is the two-site search's;
      * an **effect** with nothing in scope is the "performs but does not declare" error, at this reference, except in a
      * signature, whose control effects the guarded-return read discharges.
      */
    private def bindingFor(
        ability: AbilityFQN,
        arguments: Option[Seq[String]],
        spelled: Option[Seq[OperatorResolvedExpression]],
        isEffect: Boolean,
        at: Sourced[?],
        scope: Scope
    ): Unit =
      scope.binding(ability, arguments) match {
        case Some(binding) if isEffect && binding.beyondValue =>
          use(binding, spelled)
          violations += capturedEffect(ability, at, scope)
          checkClauses(binding, at, scope)
        case Some(binding)                                    =>
          consume(binding, spelled)
          if (arguments.isEmpty) consumeUnspelled(binding, scope)
          checkClauses(binding, at, scope)
        case None                                             =>
          if (isEffect) reportUncovered(ability, at, scope)
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
      * written to run on the binding at the `with`, but the frame they would
      * reach at runtime is the nearer one: the implementation's frame is built where it is used (effects D20c option 5),
      * and a binding between the `with` and the use is exactly what that would intercept. Each entry is looked up at its
      * own arguments, as a frame is chosen by entry: a nearer discharge of `Throw[NotFetched]` does not stand between a
      * `with` and the use of an implementation performing `Throw[GitError]`.
      *
      * The `with`'s own binding is never between them, since its clauses are what runs under it.
      */
    private def checkClauses(binding: Binding, at: Sourced[?], scope: Scope): Unit =
      binding.clauses.sortBy(_.ability.abilityName).foreach { case Binding.Clause(clause, arguments, serial) =>
        val nearest = scope.binding(clause, arguments).map(_.serial)
        if (nearest != serial && !nearest.contains(binding.serial))
          violations += Violation(
            at.as(
              s"The implementation of '${binding.ability.abilityName}' bound by the `with` around this performs " +
                s"'${clause.abilityName}', and a nearer binding of '${clause.abilityName}' stands between them."
            ),
            Seq(s"Move the `with` inside the nearer binding of '${clause.abilityName}', or that binding outside it.")
          )
      }

    /** The effects some reference performs with nothing in scope answering them — in a signature, what its
      * guarded-return read discharges.
      */
    val uncovered: mutable.Set[AbilityFQN] = mutable.Set.empty

    private def reportUncovered(ability: AbilityFQN, at: Sourced[?], scope: Scope): Unit =
      uncovered += ability
      if (!scope.signature)
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
