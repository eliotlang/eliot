package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
import com.vanillasource.eliot.eliotc.ast.fact.{EffectRow, Handles}
import com.vanillasource.eliot.eliotc.core.fact.RoleHint
import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier, Role, ValueFQN, WellKnownTypes}
import com.vanillasource.eliot.eliotc.monomorphize.check.GuardChannel
import com.vanillasource.eliot.eliotc.operator.fact.OperatorResolvedExpression.*
import com.vanillasource.eliot.eliotc.operator.fact.{OperatorResolvedExpression, OperatorResolvedValue}
import com.vanillasource.eliot.eliotc.resolve.fact.{AbilityConstraint, AbilityFQN, Qualifier as ResolveQualifier}
import com.vanillasource.eliot.eliotc.source.content.Sourced

import scala.collection.mutable
/** **The `row` walk** (`docs/effects.md` §3.2): the plumbing of effects as capability passing, and the scope check's
  * by-name half. It decides nothing about which implementation runs — that is the checker's hole rule (§3.3) — and it
  * rewrites every use so the checker has something to decide:
  *
  *   1. **holes.** Every handle position — an operation's, a `uses` callee's, a run of a code parameter listing
  *      entries, a `with`'s captures — becomes a [[HandleHole]] carrying its entry and the handles lexically in scope,
  *      nearest first, grouped by the binder that put them there. The checker fills each with the nearest whose entry
  *      is definitionally equal after evaluation.
  *   2. **`with` is a `let`.** A `with` binds its implementation's handle around its subject ([[Writer.walkHandleWith]]);
  *      a `with` no hole in its subject may take is an error (D23).
  *   3. **blocks and thunks.** An actual at a code parameter listing entries is a lambda taking one handle per entry
  *      (point 6), one at a `uses *`-only parameter a thunk `Unit => A`, and a reference to one of *this* definition's
  *      code parameters is applied to its handles or to `unit`. Wrap and apply are inverse, so a pass-through is an
  *      η-expansion and nothing more.
  *
  * **The by-name half of the scope check.** An effect performed with no handle of it in scope is "performs the effect
  * 'X' but does not declare it", at the reference; a declared effect no hole is offered is "declares … but does not
  * perform it"; a run of a code parameter whose listed effect has no handle of it in scope is "neither handles nor
  * declares". Everything finer — which of several handles of one effect answers — is the hole rule's, by entry.
  *
  * **Effect-ness is read from one place only**: the callee's declared row. An ability appearing in
  * `effectRow.returnEffects` is an effect at this reference — which for an `effect`'s member is what membership
  * recorded ([[com.vanillasource.eliot.eliotc.ast.fact.AbilityMembers]]) and for an ordinary definition is what its
  * `uses` clause says. A `~` constraint's ability is in no row, so it is written `Default`. Nothing keys on a name or a
  * shape.
  *
  * **Code and values** (`docs/effects.md` §1 rules 2 and 3). A parameter with a clause — a top-level one (`body uses *:
  * A`) or one on its codomain (`action uses *: A => Unit`) — is **code**: the caller's text, run by the callee. Every
  * other position is a **value**, and a value is pure. So:
  *
  *   - a lambda written as the argument of a code parameter, or applied where it stands, is the caller's code and
  *     sees the caller's handles; a lambda anywhere else — a function parameter without a clause, a field, a generic
  *     slot, a `val` — is a value, and may use no effect bound outside it ([[Scope.enterValue]]). It may still bind and
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

  /** A handle in scope, or what stands for one: the ability it is of, and where it comes from.
    *
    * @param received
    *   The binder index of this definition's own **received** handle — a `uses` entry its caller fills. Only these are
    *   tracked as consumed ([[Writer.consumed]]), because only a declared entry can be declared for nothing.
    * @param beyondValue
    *   Whether a value lambda stands between it and the reference: a value may use no effect bound outside it.
    * @param serial
    *   Tells two bindings apart, and survives the copies [[Scope.enterValue]] makes.
    * @param handle
    *   The handle a hole may take, with its entry. A signature's received bindings hold none: a signature's scope holds
    *   none of the definition's handles, which are its value parameters.
    * @param perUse
    *   The keys of a `with`'s type parameters its handle's type does not take, made fresh at each hole it is offered to.
    */
  private case class Binding(
      ability: AbilityFQN,
      received: Option[Int] = None,
      beyondValue: Boolean = false,
      serial: Long = Binding.nextSerial(),
      handle: Option[HandleHole.Candidate] = None,
      group: Option[Long] = None,
      perUse: Set[BigInt] = Set.empty
  ) {

    /** Which bindings are equally near: those put in scope by one binder — a definition's own handles, one block's
      * parameters. A binding of its own is its own group.
      */
    def distance: Long = group.getOrElse(serial)
  }

  private object Binding {
    private val serials = new java.util.concurrent.atomic.AtomicLong()

    def nextSerial(): Long = serials.incrementAndGet()
  }

  /** The lexical environment at one point. `bindings` is innermost-first, so a nearer `with` shadows an outer one;
    * `thunks` are the code parameters in scope, whose references run them.
    *
    * @param signature
    *   Whether this is a signature. Its effects are the guard channel's vocabulary, discharged by the guarded-return
    *   read rather than performed at runtime, so an uncovered one is not reported, and the scope check's other cuts do
    *   not apply. Its references are evaluated rather than checked, so a type-argument list there may not hold the
    *   checker's hole ([[Writer.mergeTypeArguments]]).
    * @param listed
    *   The entries each code parameter's clause lists, by name: a run hands the code one handle per entry.
    */
  private case class Scope(
      bindings: Seq[Binding],
      thunks: Set[String],
      callbacks: Set[String] = Set.empty,
      captured: Set[String] = Set.empty,
      closed: Boolean = false,
      signature: Boolean = false,
      listed: Map[String, Seq[AbilityConstraint[OperatorResolvedExpression]]] = Map.empty
  ) {
    def bind(binding: Binding): Scope = copy(bindings = binding +: bindings)
    def shadow(name: String): Scope   =
      copy(thunks = thunks - name, callbacks = callbacks - name, captured = captured - name, listed = listed - name)

    /** The scope inside a lambda written in a **value** position. Every binding in scope so far is outside the value,
      * so an effect may no longer be taken from it ([[Writer.bindingFor]], [[Writer.candidates]]); and every code
      * parameter in scope would be carried off by the value, so a reference to one is a capture. A signature is not
      * checked: its effects are not performed at runtime.
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

    /** The scope inside an argument at a **closed** slot (`body uses Throw[E]: A`, `docs/effects.md` §1 rule 4): the
      * same cut as [[enterValue]] — nothing bound outside reaches in, and no code parameter of this definition may be
      * run or passed on there. What the slot lists is the block's own parameters, bound inside by the caller of this.
      */
    def enterClosed: Scope = if (signature) this else enterValue.copy(closed = true)

    /** The nearest binding of `ability`: the by-name half of the scope check reads no more than that. */
    def binding(ability: AbilityFQN): Option[Binding] = bindings.find(_.ability == ability)
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
  def write(original: OperatorResolvedValue, universe: RowChecker.Universe): Written = {
    // An implementation's clause is shaped as its member is, and what it captures is bound from its handle
    // ([[Captures.clause]]); every other value is written as it is.
    val orv            = Captures.clause(original, universe).getOrElse(original)
    val writer         = new Writer(universe)
    val received       = receivedBindings(orv)
    val scope          = Scope(received, thunkParameters(orv), callbackParameters(orv), listed = listedEntries(orv))
    val view           = SignatureView.of(orv.signature)
    val body           = Option
      .when(writableBody(orv))(orv.runtime)
      .flatten
      .map(writer.walkDefinition(_, scope, view.binders.size + view.parameters.size))
    val overstated     = body.toSeq.flatMap(_ => overDeclared(orv, writer.consumed.toSet))
    // A signature's scope holds none of the definition's handles, which are its value parameters. What its return
    // performs and nothing in it discharges is the guarded-return read's to discharge: a `raise` there is run inside
    // the compile track's `runThrow`, whose `Either` the guard read then reads (`docs/effects.md` D36 point 9).
    val signatureScope = Scope(received.map(_.copy(handle = None)), Set.empty, signature = true)
    val guarded        = {
      val probe = new Writer(universe)
      probe.walkDefinition(orv.signature, signatureScope, view.binders.size)
      guardDischarged(orv.signature, probe.uncovered.toSet)
    }
    val signature      = writer.walkDefinition(guarded, signatureScope, view.binders.size)
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
    * An effect's binding is a **handle parameter** too, and every handle the definition receives is equally near
    * ([[Binding.group]]).
    */
  private def receivedBindings(orv: OperatorResolvedValue): Seq[Binding] = {
    val entries = entryArguments(orv)
    val binders = SignatureView.of(orv.signature).binders
    val effects = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    val group   = Binding.nextSerial()
    phantoms(orv).map { case (index, ability) =>
      Binding(
        ability,
        received = Some(index),
        handle = Option
          .when(effects.contains(ability))(entries.get(index))
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
    * effect path, the guarded-return read ([[WellKnownTypes.guardDischargers]]): `if(C, T) else raise("…")` is read as
    * `runThrow(if(C, T) else raise("…"))`, whose `Right(t)` or `Left("…")` is the guard's verdict. An uncovered `abort`
    * is a rejection with no message of its author's, so `if(C, T)` is read as `runThrow(if(C, T) else raise("A type
    * guard rejected this use."))`: one verdict shape for both control effects.
    */
  private def guardDischarged(signature: Sourced[OperatorResolvedExpression], uncovered: Set[AbilityFQN])
      : Sourced[OperatorResolvedExpression] = {
    def discharger(name: String): Option[ValueFQN] =
      WellKnownTypes.guardDischargers.collectFirst {
        case ((module, effect), fqn) if effect == name && uncovered(AbilityFQN(module, effect)) => fqn
      }
    val view                                                                                = SignatureView.of(signature)
    val at                                                                                  = view.returnType
    def call(fqn: ValueFQN, arguments: Sourced[OperatorResolvedExpression]*): Sourced[OperatorResolvedExpression] =
      arguments.foldLeft(at.as(ValueReference(at.as(fqn)): OperatorResolvedExpression))((target, argument) =>
        at.as(FunctionApplication(target, argument))
      )
    val aborting = discharger("Abort").map(_ =>
      call(
        WellKnownTypes.abortElseFQN,
        at,
        call(WellKnownTypes.throwRaiseFQN, at.as(StringLiteral(at.as(GuardChannel.fallbackRejectionMessage))))
      )
    )
    val throwing = discharger("Throw").orElse(aborting.flatMap(_ =>
      WellKnownTypes.guardDischargers.collectFirst { case ((_, "Throw"), fqn) => fqn }
    ))
    throwing.fold(signature)(runThrow =>
      signature.as(view.withReturnType(call(runThrow, aborting.getOrElse(at)).value).toExpression)
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

  private class Writer(universe: RowChecker.Universe) {
    val violations: mutable.Buffer[Violation] = mutable.Buffer.empty

    /** The binder indices of the **received** handles some hole was offered ([[overDeclared]]). */
    val consumed: mutable.Set[Int] = mutable.Set.empty

    /** The [[Binding.serial]]s of the bindings some reference was offered, so a `with` that binds nothing is reported
      * ([[unusedWith]]).
      */
    private val used: mutable.Set[Long] = mutable.Set.empty

    /** A reference offered `binding`: it is used, and a received handle is consumed. */
    private def consume(binding: Binding): Unit = {
      used += binding.serial
      binding.received.foreach(consumed += _)
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
        case WithBinding(subject, implementation, supplied) =>
          walkHandleWith(expr, subject, implementation, supplied, scope, applied)

        case ParameterReference(name) if scope.captured.contains(name.value) =>
          violations += capturedCode(name, scope)
          expr

        // A reference to one of this definition's row-typed parameters is a thunk: running it is applying it.
        case ParameterReference(name) if scope.thunks.contains(name.value) =>
          checkGiven(name, scope)
          scope.listed.getOrElse(name.value, Seq.empty) match {
            case Seq()   => expr.as(FunctionApplication(expr, unitValue(expr)))
            // A run hands the code a handle for every entry its clause lists, each the nearest in scope whose entry
            // answers it (`docs/effects.md` D36 point 6).
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
            case ValueReference(callee, existing)                                   =>
              val (written, holes, writtenBy) = writeBindings(expr.as(head), callee.value, existing, scope)
              unappliedEffectful(callee, args.size + applied, scope).foreach(violations += _)
              val adjusted                    = args.zipWithIndex.map { case (arg, index) =>
                walkArgument(arg, callee.value, index, scope, writtenBy)
              }
              expr.as(applyChain(written, holes ++ adjusted))
            case ParameterReference(selector) if selector.value === matchSelector =>
              expr.as(applyChain(walk(expr.as(head), scope), args.map(walkCode(_, scope, Int.MaxValue))))
            case _                                                                  =>
              expr.as(applyChain(walkCode(expr.as(head), scope, args.size), args.map(walk(_, scope))))
          }
      }

    /** A `with` (`docs/effects.md` D36 point 5): the handle its implementation's `data` holds, bound around its
      * subject as an ordinary `let`, where every hole in the subject may take it.
      *
      * {{{
      * greet("Bob") with scripted(answer)
      *   ⟶ ($with1 -> greet(?Console{$with1: Console | …}, "Bob"))(Scripted$Handle(answer))
      * }}}
      *
      * The arguments are computed where the `with` is written, outside its binding. The handle's entry is the
      * implementation's pattern, its type parameters keyed holes written at the data's constructor, so the checker
      * reads them off the arguments and the uses alike.
      */
    private def walkHandleWith(
        expr: Sourced[OperatorResolvedExpression],
        subject: Sourced[OperatorResolvedExpression],
        implementation: Sourced[ValueFQN],
        supplied: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope,
        applied: Int
    ): Sourced[OperatorResolvedExpression] =
      (abilityOf(implementation.value, universe), universe.lookup(implementation.value)) match {
        case (Some(ability), Some(marker)) =>
          universe.lookup(handleConstructor(marker, implementation.value)) match {
            // Only a layer that bodies the implementation mints its handle `data`: without one on the path — the
            // base's `implement standardConsole: Console` and no platform layer — there is nothing to install.
            case None              =>
              val name = implementationName(implementation.value)
              violations += Violation(
                implementation.as(s"The implementation '$name' has no body here."),
                Seq(s"No layer on the path bodies '$name'; a platform layer is what bodies it.")
              )
              // The value is refused; the subject is left unwritten rather than reported again for what it performs.
              expr
            case Some(constructor) =>
              val view    = SignatureView.of(marker.signature)
              // The data takes the implementation's own type parameters, never the binding a `~` constraint on one mints.
              val own     = unmarkedBinders(marker)
              // The handle's capture fields (`docs/effects.md` D36 point 2): a captured entry takes the handle in scope
              // here, outside the binding, and any other `unit`.
              val fields  = Captures.captured(marker, universe).getOrElse(Seq.empty)
              // The handle's leading fields are the implementation's parameters, and its capture fields the rest.
              val types   = SignatureView.of(constructor.signature).parameters.dropRight(fields.size)
              checkSupplied(implementation, types.size, supplied)
              // A type parameter the handle's value fixes — one its parameters' types or its clauses' entries mention —
              // is one keyed hole, written at the data's constructor and in the entry alike. Any other is the pattern's
              // alone: the handle's type does not take it, so its key is fresh at each hole the handle is offered to,
              // which solves it from what it asks (`docs/effects.md` §8 item 30, as the core desugar's handle `data`
              // reads it).
              val fixed   = own.filter(name =>
                types.exists(t => containsVar(t.value, name)) || fields.exists(_._1._2.exists(containsVar(_, name)))
              )
              val keys    = own.map(_ -> keyedHole(implementation)).toMap
              val perUse  = own.filterNot(fixed.contains).flatMap(name => holeKey(keys(name))).toSet
              val held    = fields.map { case ((fieldAbility, arguments), isCaptured) =>
                val key = keyedHole(implementation)
                val arg =
                  if (isCaptured) {
                    // The by-name half of the scope check (`docs/effects.md` D36): a captured entry with no handle of
                    // its effect in scope at all is what this definition performs and does not declare.
                    if (candidates(fieldAbility, scope).isEmpty) reportUncovered(fieldAbility, implementation, scope)
                    handleHole(
                      implementation,
                      fieldAbility,
                      arguments.map(argument => implementation.as(substituted(argument, keys))),
                      scope
                    )
                  } else unitValue(implementation)
                (key, arg)
              }
              val entry   = HandleHole.Entry(
                ability,
                view.parameters.map(pattern =>
                  pattern.as(keys.foldLeft(pattern.value) { case (acc, (name, key)) => substitute(acc, name, key) })
                )
              )
              val name    = implementation.as(Handles.binding(nextWith()))
              val binding = Binding(ability, handle = Some(HandleHole.Candidate(name, entry)), perUse = perUse)
              val bound   = walk(subject, scope.shadow(name.value).bind(binding), applied)
              if (!used.contains(binding.serial)) violations += unusedWith(subject, implementation, ability, scope)
              val handle  = applyChain(
                implementation.as(
                  ValueReference(
                    implementation.as(constructor.vfqn),
                    fixed.map(name => implementation.as(keys(name))) ++ held.map((key, _) => implementation.as(key))
                  )
                ),
                supplied.map(walk(_, scope)) ++ held.map(_._2)
              )
              expr.as(FunctionApplication(implementation.as(FunctionLiteral(name, None, bound)), implementation.as(handle)))
          }
        case _                             =>
          violations += Violation(implementation.as("This name is not an implementation."))
          walk(subject, scope, applied)
      }

    /** Whether a `with` supplies as many arguments as its implementation takes parameters. */
    private def checkSupplied(
        implementation: Sourced[ValueFQN],
        declared: Int,
        supplied: Seq[Sourced[OperatorResolvedExpression]]
    ): Unit = {
      val name = implementationName(implementation.value)
      if (declared == 0 && supplied.nonEmpty)
        violations += Violation(supplied.head.as(s"The implementation '$name' takes no parameters."))
      else if (declared > 0 && supplied.isEmpty)
        violations += Violation(
          implementation.as(
            s"The implementation '$name' takes parameters, and they are supplied where it is bound: `with $name(…)`."
          )
        )
      else if (declared != supplied.size)
        violations += Violation(
          implementation.as(
            s"The implementation '$name' takes $declared parameter${if (declared == 1) "" else "s"}, but this `with` " +
              s"supplies ${supplied.size}."
          )
        )
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

    /** The constructor of the handle `data` the implementation `marker` lowers to, in its module. */
    private def handleConstructor(marker: OperatorResolvedValue, implementation: ValueFQN): ValueFQN =
      ValueFQN(marker.vfqn.moduleName, QualifiedName(Handles.dataType(implementationName(implementation)), Qualifier.Default))

    /** A named implementation's own name, off its marker's qualifier. */
    private def implementationName(marker: ValueFQN): String =
      marker.name.qualifier match {
        case Qualifier.AbilityImplementation(_, _, Some(name)) => name
        case _                                                 => marker.name.name
      }

    /** An effect a code parameter's clause lists is this definition's **to handle or to declare**
      * (`docs/effects.md` §1 rule 5, D22): at a run of the code, a handle of every listed effect must be in scope — the
      * definition's own, received because it declares the effect, or one it installs around the run with `with` or by
      * passing the code on to a discharger. Nothing gives an effect from nothing (D26). This is the by-name half: which
      * handle of the effect answers the entry is the hole's at the run.
      */
    private def checkGiven(name: Sourced[String], scope: Scope): Unit =
      if (!scope.signature)
        scope.listed.getOrElse(name.value, Seq.empty).map(_.abilityFQN).distinct.foreach { ability =>
          if (candidates(ability, scope).isEmpty)
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
              walkCode(arg, if (cb.closed) scope.enterClosed else scope, cb.arity)
            case None                  => walk(arg, scope)
          }
        case Some((calleeOrv, slot)) if slot.nonEmpty =>
          // The code is a block taking one handle per entry its slot lists (`docs/effects.md` D36 point 6), all equally
          // near, of whatever type the callee's body hands it. What it uses through `*` it
          // captures from here; a closed slot's block captures nothing.
          val block   = nextBlock()
          val group   = Binding.nextSerial()
          val handles = slot.zipWithIndex.map { case (entry, position) =>
            Binding(
              entry.abilityFQN,
              handle = Some(
                HandleHole.Candidate(
                  arg.as(s"$$block$block$$$position"),
                  HandleHole.Entry(entry.abilityFQN, entry.typeArgs.map(argument => arg.as(substituted(argument, substitution))))
                )
              ),
              group = Some(group)
            )
          }
          val base    = if (closedSlot(calleeOrv, index)) scope.enterClosed else scope
          val inside  = handles.foldLeft(base)((acc, binding) => acc.shadow(binding.handle.get.name.value).bind(binding))
          handles.foldRight(walk(arg, inside)) { (binding, body) =>
            arg.as(FunctionLiteral(binding.handle.get.name, None, body))
          }
        case Some((calleeOrv, _))             =>
          val name   = thunkParameter
          val inside = (if (closedSlot(calleeOrv, index)) scope.enterClosed else scope).shadow(name)
          arg.as(
            FunctionLiteral(
              arg.as(name),
              Some(arg.as(ValueReference(arg.as(WellKnownTypes.unitTypeFQN)))),
              walk(arg, inside)
            )
          )
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

    /** The entries a callee's parameter `index` declares, when that parameter is a row position at all. */
    private def rowSlot(
        orv: OperatorResolvedValue,
        index: Int
    ): Option[Seq[AbilityConstraint[OperatorResolvedExpression]]] =
      orv.effectRow.parameterEffects.find(_.parameterIndex === index).map(_.effects)

    /** Write the callee's type arguments this call determines, **merged by index**: a binder the callee's declaration
      * marks as a **binding** of an effect is a handle's type, written as a keyed hole the checker solves from the hole
      * at the handle's position; one of an ability (a `~` constraint) is written `Default`, the two-site search; every
      * other binder takes, in order, what the caller spelled. Each binding is still looked up ([[bindingFor]]), because
      * that lookup is the by-name scope check.
      *
      * Type-argument application is positional, so what is written is the run from index 0 that every slot below it
      * fills. In a body a gap before a binding is written as the checker's hole ([[mergeTypeArguments]]), so every
      * binding is reached; a binder past the last one is left to the checker, which is always the fail-safe direction.
      * In a signature, which has no hole, a binding behind a gap is reported ([[unreachableBinding]]): the checker has
      * no way to solve a binding and would silently ground it.
      *
      * Returns the reference written, the holes at its handle positions, and what was written for each binder.
      */
    private def writeBindings(
        reference: Sourced[OperatorResolvedExpression],
        callee: ValueFQN,
        existing: Seq[Sourced[OperatorResolvedExpression]],
        scope: Scope
    ): (Sourced[OperatorResolvedExpression], Seq[Sourced[OperatorResolvedExpression]], Map[String, OperatorResolvedExpression]) =
      universe.lookup(callee) match {
        case None      => (reference, Seq.empty, Map.empty)
        case Some(orv) =>
          val effects          = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
          val marks            = phantoms(orv).toMap
          val binders          = SignatureView.of(orv.signature).binders
          val handled          = handleParameters(orv)
          val entries          = entryArguments(orv)
          // A handle's entry names the callee's binders, so every binder it names is written — a keyed hole where the
          // call determines nothing — and the entry the hole asks for is spelled with what is written there.
          // So does a block's, which a listing slot hands its code: `catch`'s `E`.
          val named            = handled.flatMap((index, _) => entries.getOrElse(index, Seq.empty)) ++
            orv.effectRow.parameterEffects.flatMap(_.effects.flatMap(_.typeArgs))
          val reachTo          = (handled.map(_._1) ++ binders.zipWithIndex.collect {
            case (binder, i) if named.exists(containsVar(_, binder.name.value)) => i
          }).maxOption.getOrElse(-1)
          val (written, reach) = mergeTypeArguments(
            binders.size,
            marks,
            existing,
            Some(_ => reference.as(keyedHole(reference))),
            reachTo,
            (index, ability) => {
              bindingFor(ability, effects.contains(ability), reference, scope)
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
      offered.flatten.foreach((binding, _) => consume(binding))
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
        .flatMap(binding => binding.handle.map(handle => (binding.distance, (binding, rekeyed(handle, binding.perUse)))))
        .foldLeft(Seq.empty[(Long, Seq[(Binding, HandleHole.Candidate)])]) {
          case (groups :+ ((distance, group)), (next, candidate)) if distance == next =>
            groups :+ (distance -> (group :+ candidate))
          case (groups, (next, candidate))                                             =>
            groups :+ (next -> Seq(candidate))
        }
        .map(_._2)


    private var holeKeys: Long = 0

    /** The key of a keyed hole ([[keyedHole]]). */
    private def holeKey(expression: OperatorResolvedExpression): Option[BigInt] =
      expression match {
        case ValueReference(name, Seq(Sourced(_, _, IntegerLiteral(key)))) if name.value == WellKnownTypes.inferredTypeArgumentFQN =>
          Some(key.value)
        case _                                                                                                                  => None
      }

    /** A candidate with each keyed hole of `keys` in its entry replaced by a fresh one, the same for every occurrence:
      * a `with`'s type parameter its handle's type does not take is solved at each hole on its own.
      */
    private def rekeyed(candidate: HandleHole.Candidate, keys: Set[BigInt]): HandleHole.Candidate =
      if (keys.isEmpty) candidate
      else {
        val fresh = keys.map(key => key -> keyedHole(candidate.name)).toMap
        def go(expression: OperatorResolvedExpression): OperatorResolvedExpression =
          holeKey(expression).flatMap(fresh.get).getOrElse(expression match {
            case FunctionApplication(target, argument) => FunctionApplication(target.map(go), argument.map(go))
            case ValueReference(name, typeArgs)         => ValueReference(name, typeArgs.map(_.map(go)))
            case FunctionLiteral(name, tpe, body)       => FunctionLiteral(name, tpe.map(_.map(go)), body.map(go))
            case other                                  => other
          })
        candidate.copy(entry = candidate.entry.map(_.map(go)))
      }

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

    /** Answer one binder at a reference — the by-name half of the scope check. An **ability** is the two-site search's
      * and needs nothing in scope; an **effect** with no handle of it in scope is the "performs but does not declare"
      * error, at this reference, except in a signature, whose control effects the guarded-return read discharges. A
      * reference consumes every received handle of its effect: telling them apart is the hole's, by entry.
      */
    private def bindingFor(ability: AbilityFQN, isEffect: Boolean, at: Sourced[?], scope: Scope): Unit =
      scope.binding(ability) match {
        case Some(binding) if isEffect && binding.beyondValue =>
          used += binding.serial
          violations += capturedEffect(ability, at, scope)
        case Some(binding)                                    =>
          consume(binding)
          if (binding.received.isDefined)
            scope.bindings.filter(_.ability == ability).flatMap(_.received).foreach(consumed += _)
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
        case WithBinding(inner, _, _) => innermost(inner.value)
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
