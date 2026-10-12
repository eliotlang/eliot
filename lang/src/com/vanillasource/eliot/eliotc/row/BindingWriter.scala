package com.vanillasource.eliot.eliotc.row

import cats.syntax.all.*
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
  * **A block is used, never kept** (D20 rules 3 and 4, `docs/effects.md` §11). A function-typed parameter of a
  * definition is a *block*: code its caller wrote, seeing the caller's effects. The walk carries whether each position
  * is *kept* — a field, a generic slot, a result, a `val` — and a block may stand only where it is not: called, or
  * handed straight to another definition's function-typed parameter. A lambda in a kept position is walked *sealed*:
  * any block of this definition or any effect binding from outside it would be carried into a value that runs after
  * the scope it came from is gone, so both are reported. A lambda handed to a definition's function parameter is a
  * block itself, and sees the effects around it.
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
    * @param received
    *   Whether this is the definition's own **received** binding — resolution-order step 2, a row entry its caller
    *   fills. Only these are tracked as consumed ([[Writer.consumed]]), because only a declared entry can be declared
    *   for nothing.
    * @param captured
    *   Whether this binding is in scope only from *outside* a lambda that is kept as a value (D20 rule 4). Using it
    *   there would carry the binding into a value that runs after the scope it came from is gone.
    */
  private case class Binding(
      ability: AbilityFQN,
      term: OperatorResolvedExpression,
      received: Boolean = false,
      captured: Boolean = false
  )

  /** What a row-typed parameter's declaration promises its block for one ability (D20 rule 5).
    *
    * @param implementation
    *   The implementation the slot names with `with`, or [[None]] for `Default`.
    * @param through
    *   The implementation the slot names whose **clauses** perform this ability, when the promise is one of those
    *   clause rows rather than an entry of the slot itself. The caller writes that implementation's clause bindings
    *   from the declaration ([[Writer.slotImplementation]]), so whoever runs the block must give them.
    */
  private case class Promise(implementation: Option[ValueFQN], through: Option[ValueFQN] = None)

  /** The lexical environment at one point. `bindings` is innermost-first, so a nearer `with` shadows an outer one for
    * the same ability; `thunks` are the row-typed parameters in scope, whose references apply.
    *
    * `functions` are this definition's function-typed parameters — its **blocks** (D20 rule 2) — with the number of
    * arguments each takes; `captured` are the blocks, of either kind, in scope only from outside a lambda kept as a
    * value. A block may be called or passed on, never kept (D20 rule 3). Both are empty, and `checksBlocks` false,
    * wherever the rules do not apply: a signature, and a platform run boundary. `selectors` are the `match` selector
    * parameters in scope, whose arguments are the arms ([[Writer.walkArgument]]). `promises` are what each row-typed
    * parameter's declaration promised its block ([[promisedBindings]]), which running it is checked against.
    *
    * @param uncoveredDefaults
    *   Whether an **effect** with nothing in scope binds `Default` instead of being reported. True in exactly two
    *   regions, and both because something other than this definition's row answers for the effect: a platform **run
    *   boundary**, where every effect's chain ends (§9.5), and a **signature**, whose `raise`/`abort` is the guard
    *   channel's vocabulary and is discharged by the guarded-return read, not performed at runtime at all.
    */
  private case class Scope(
      bindings: Seq[Binding],
      thunks: Set[String],
      uncoveredDefaults: Boolean = false,
      functions: Map[String, Int] = Map.empty,
      captured: Set[String] = Set.empty,
      checksBlocks: Boolean = false,
      selectors: Set[String] = Set.empty,
      promises: Map[String, Map[AbilityFQN, Promise]] = Map.empty
  ) {
    def bind(binding: Binding): Scope = copy(bindings = binding +: bindings)
    def shadow(name: String): Scope   =
      copy(
        thunks = thunks - name,
        functions = functions - name,
        captured = captured - name,
        selectors = selectors - name,
        promises = promises - name
      )

    /** The scope inside a lambda kept as a value: everything in scope so far is outside it. */
    def seal: Scope =
      if (checksBlocks)
        copy(bindings = bindings.map(_.copy(captured = true)), captured = captured ++ thunks ++ functions.keySet)
      else this

    /** This scope with `abilities` bound to `Default` — what a slot's `with` resolves its own bindings against, since
      * the scope that covers them is the callee's and not this one.
      */
    def defaulting(abilities: Seq[AbilityFQN], at: Sourced[?]): Scope =
      abilities.foldLeft(this)((acc, ability) => acc.bind(Binding(ability, defaultBinding(at))))

    def binding(ability: AbilityFQN): Option[Binding] = bindings.find(_.ability == ability)
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
    val thunks     = thunkParameters(orv)
    val scope      = Scope(
      received,
      thunks,
      uncoveredDefaults = atBoundary,
      functions = functionParameters(orv, thunks, universe),
      checksBlocks = !atBoundary,
      promises = if (atBoundary) Map.empty else promisedBindings(orv, universe)
    )
    val view       = SignatureView.of(orv.signature)
    val body       = Option
      .when(writableBody(orv))(orv.runtime)
      .flatten
      .map(writer.walkDefinition(_, scope, view.binders.size + view.parameters.size, kept = true))
    val overstated = body.filterNot(_ => atBoundary).toSeq.flatMap(_ => overDeclared(orv, writer.consumed.toSet))
    val signature  =
      writer.walkDefinition(
        orv.signature,
        Scope(received, Set.empty, uncoveredDefaults = true),
        view.binders.size,
        kept = true
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
    * actual at a slot that does not supply the entry runs in the *caller's* scope, so running it consumes nothing here:
    * `def run[A](v: {Console} A): {Console} A = v` declares `Console` for nothing, exactly as its `{}`-slot twin would.
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
          Seq(s"Remove '${ability.abilityName}' from its { ... } effect set.")
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
    phantoms(orv).map { case (index, ability) =>
      Binding(ability, ParameterReference(binders(index).name), received = true)
    }
  }

  /** How many **value** parameters a definition takes, which its signature alone cannot say: a signature's arrow chain
    * runs straight through a returned function, so a field accessor handing back a function (`Box -> String -> Unit`)
    * reads as two parameters. The body's own leading lambdas past the generic binders are what actually say it.
    */
  private def valueParameterCount(orv: OperatorResolvedValue): Int = {
    val view = SignatureView.of(orv.signature)
    orv.runtime match {
      case Some(body) =>
        val binderNames = view.binders.map(_.name.value).toSet
        RowChecker.peelBinders(body.value)._1.dropWhile(binderNames.contains).size
      case None       => view.parameters.size
    }
  }

  /** The names of this definition's value parameters, in declaration order: the body's leading lambdas past the
    * generic binders (as [[valueParameterCount]] reads them — a binder the desugar minted into the signature has no
    * lambda in the body), matched to the declared parameters from the front. A body returning a lambda
    * (`compose(f, g) = a -> f(g(a))`) has more leading lambdas than parameters, and the extra ones are the result's.
    */
  private def valueParameterNames(orv: OperatorResolvedValue): Seq[String] = {
    val view        = SignatureView.of(orv.signature)
    val binderNames = view.binders.map(_.name.value).toSet
    val (names, _)  = RowChecker.peelBinders(orv.runtime.map(_.value).getOrElse(view.returnType.value))
    names.dropWhile(binderNames.contains).take(view.parameters.size)
  }

  /** The names of this definition's row-typed value parameters: a reference to one runs it. */
  private def thunkParameters(orv: OperatorResolvedValue): Set[String] = {
    val valueParamNames = valueParameterNames(orv)
    orv.effectRow.parameterEffects.flatMap(pe => valueParamNames.lift(pe.parameterIndex)).toSet
  }

  /** What each row-typed parameter's declaration **promises** its block, per entry the slot *supplies* — an entry
    * this definition's own row lacks (§2.2): the implementation the slot names with `with`, or `Default`. Running the
    * parameter is checked against it (D20 rule 5).
    *
    * A slot's `with` promises more than its own entry. The implementation it names may perform effects in its clauses
    * (`recordingConsole`'s `printLine` declares `{Writer[String]}`), and the caller writes those clause bindings as
    * `Default` wherever this definition does not ride them ([[Writer.slotImplementation]]). So each such clause
    * ability is promised as `Default` too: the block may only run where something gives it, or the double would
    * perform it into whatever frame happens to enclose the run — or into none.
    */
  private def promisedBindings(
      orv: OperatorResolvedValue,
      universe: RowChecker.Universe
  ): Map[String, Map[AbilityFQN, Promise]] = {
    val names      = valueParameterNames(orv)
    val parameters = SignatureView.of(orv.signature).parameters
    val rides      = orv.effectRow.returnEffects.map(_.abilityFQN).toSet
    orv.effectRow.parameterEffects.flatMap { slot =>
      names.lift(slot.parameterIndex).map { name =>
        val named   = parameters
          .lift(slot.parameterIndex)
          .toSeq
          .flatMap(tpe => slotBindings(tpe.value))
          .flatMap(marker => abilityOf(marker.value, universe).map(_ -> marker.value))
          .toMap
        val entries =
          slot.effects.map(_.abilityFQN).filterNot(rides).map(ability => ability -> Promise(named.get(ability))).toMap
        val clauses = entries.values.flatMap(_.implementation).toSeq.distinct.flatMap { marker =>
          clauseAbilities(marker, universe).filterNot(rides).map(_ -> Promise(None, Some(marker)))
        }
        name -> (clauses.toMap ++ entries)
      }
    }.toMap
  }

  /** The abilities an implementation's clauses perform — its marker's phantom binders. */
  private def clauseAbilities(marker: ValueFQN, universe: RowChecker.Universe): Seq[AbilityFQN] =
    universe.lookup(marker).toSeq.flatMap(orv => phantoms(orv).map(_._2))

  /** This definition's **blocks** of function type (D20 rule 2), each with the number of arguments it takes: every
    * value parameter whose declared type is a function, except a row-typed one, which is a thunk and runs when
    * mentioned.
    */
  private def functionParameters(
      orv: OperatorResolvedValue,
      thunks: Set[String],
      universe: RowChecker.Universe
  ): Map[String, Int] =
    valueParameterNames(orv)
      .zip(SignatureView.of(orv.signature).parameters)
      .map { case (name, tpe) => name -> functionArity(tpe.value, universe) }
      .filter { case (name, arity) => arity > 0 && !thunks.contains(name) }
      .toMap

  /** Whether a declared parameter type is a function — a block, D20 rule 2. */
  private def isFunctionType(tpe: OperatorResolvedExpression, universe: RowChecker.Universe): Boolean =
    functionArity(tpe, universe) > 0

  /** How many arguments a declared type takes before it stops being a function, read through **one level of alias
    * expansion** at each step — `A => B` is the alias `=>[A, B] = Function[A, B]` — which is all a declaration read is
    * allowed (`docs/effects.md` §3.2).
    */
  private def functionArity(tpe: OperatorResolvedExpression, universe: RowChecker.Universe): Int =
    asArrow(tpe).orElse(expandAlias(tpe, universe).flatMap(asArrow)) match {
      case Some((_, codomain)) => 1 + functionArity(codomain.value, universe)
      case None                => 0
    }

  /** A type alias applied to its arguments, with the arguments substituted into its body; [[None]] for anything else.
    * An infix alias such as `=>` resolves in the value namespace, so the alias is recognised by having a body to expand,
    * not by its qualifier.
    */
  private def expandAlias(
      tpe: OperatorResolvedExpression,
      universe: RowChecker.Universe
  ): Option[OperatorResolvedExpression] =
    spine(tpe) match {
      case (ValueReference(name, _), args) if args.nonEmpty =>
        universe.lookup(name.value).flatMap(_.runtime).flatMap { body =>
          val (binders, inner) = RowChecker.peelBinders(body.value)
          Option.when(binders.size === args.size)(binders.zip(args).foldLeft(inner) { case (acc, (binder, arg)) =>
            OperatorResolvedExpression.substitute(acc, binder, arg.value)
          })
        }
      case _                                                 => None
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
    * Private, and the only definition of where a binding sits. It was public while the post-mono accounting read it
    * back to re-derive an instantiation's effects; that derivation retired with D7 (`docs/effects.md` §11), and the
    * write's own walk — which is the scope check — is now the only reader.
    */
  private def phantoms(orv: OperatorResolvedValue): Seq[(Int, AbilityFQN)] =
    SignatureView.of(orv.signature).binders.zipWithIndex.flatMap { case (binder, index) =>
      binder.parameterType.flatMap(declared => markedAbility(declared.value)).map(index -> _)
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

    private def consume(binding: Binding): Unit =
      if (binding.received) consumed += binding.ability

    /** The definition's own leading binders — its generic binders and then its value parameters — are peeled without
      * shadowing, because they are what *put* the row-typed parameters in scope. Only a lambda inside the body proper
      * can shadow one.
      */
    def walkDefinition(
        expr: Sourced[OperatorResolvedExpression],
        scope: Scope,
        remaining: Int,
        kept: Boolean
    ): Sourced[OperatorResolvedExpression] =
      expr.value match {
        case FunctionLiteral(paramName, paramType, body) if remaining > 0 =>
          expr.as(
            FunctionLiteral(paramName, paramType.map(unmarked), walkDefinition(body, scope, remaining - 1, kept))
          )
        case _                                                           => walk(expr, scope, kept)
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

    /** Walk one expression. `kept` says whether its value may outlive the call it is written in — a field, a generic
      * slot, a result, a `val` — as opposed to being called, or handed to a definition's function parameter, where it is
      * a **block** (D20 rules 3 and 4).
      */
    def walk(
        expr: Sourced[OperatorResolvedExpression],
        scope: Scope,
        kept: Boolean
    ): Sourced[OperatorResolvedExpression] =
      expr.value match {
        // A `with` puts its implementation in scope for its subject, innermost first, and disappears.
        case WithBinding(subject, implementation) =>
          abilityOf(implementation.value, universe) match {
            case Some(ability) =>
              walk(
                subject,
                scope.bind(Binding(ability, boundImplementation(implementation, scope))),
                kept
              )
            case None          =>
              violations += Violation(implementation.as("This name is not an implementation."))
              walk(subject, scope, kept)
          }

        // A block of this definition, written inside a lambda that is kept: the lambda keeps the block.
        case ParameterReference(name) if scope.captured.contains(name.value) =>
          violations += keptBlock(name, scope)
          if (scope.thunks.contains(name.value)) expr.as(FunctionApplication(expr, unitValue(expr))) else expr

        // A reference to one of this definition's row-typed parameters is a thunk: running it is applying it.
        case ParameterReference(name) if scope.thunks.contains(name.value) =>
          checkGiven(name, scope)
          expr.as(FunctionApplication(expr, unitValue(expr)))

        case ParameterReference(name) if kept && scope.functions.contains(name.value) =>
          violations += keptBlock(name, scope)
          expr

        // A lambda kept as a value sees nothing from around it but values (D20 rule 4). A curried chain of lambdas is
        // one function, so the inner ones share the outer one's position.
        case FunctionLiteral(paramName, paramType, body) =>
          val inner = (if (kept) scope.seal else scope).shadow(paramName.value)
          val curry = body.value match {
            case _: FunctionLiteral => kept
            case _                  => true
          }
          expr.as(FunctionLiteral(paramName, paramType, walk(body, inner, curry)))

        case _: IntegerLiteral | _: StringLiteral | _: ParameterReference => expr

        case _ =>
          val (head, args) = spine(expr.value)
          head match {
            case ValueReference(callee, existing) =>
              val written  = writeBindings(expr.as(head), callee.value, existing, scope, args)
              val adjusted = argumentsKept(callee.value, args.size, kept).zip(args).zipWithIndex.map {
                case ((argumentKept, arg), index) => walkArgument(arg, callee.value, index, scope, argumentKept)
              }
              expr.as(applyChain(written, adjusted))
            case _                                =>
              head match {
                // A block applied to fewer arguments than it takes is a function still holding the block.
                case ParameterReference(name) if kept && scope.functions.get(name.value).exists(_ > args.size) =>
                  violations += keptBlock(name, scope)
                case _                                                                                         => ()
              }
              val arms = head match {
                case ParameterReference(name) => scope.selectors.contains(name.value)
                case _                        => false
              }
              expr.as(applyChain(walk(expr.as(head), scope, kept = false), args.map(walk(_, scope, kept = !arms))))
          }
      }

    /** Whether each of a call's arguments is kept: everything except an argument at a **definition's** function-typed
      * parameter, which is a block. A constructor's parameters are fields and a row-typed slot hands its payload back
      * as a value, so neither is a block. A call applied to fewer arguments than its callee takes is itself a function
      * holding its arguments, so when that function is kept, so are they.
      *
      * A callee the universe does not hold failed upstream and has reported why; its arguments are taken as blocks,
      * which only ever accepts more.
      */
    private def argumentsKept(callee: ValueFQN, count: Int, kept: Boolean): Seq[Boolean] =
      universe.lookup(callee) match {
        case None      => Seq.fill(count)(false)
        case Some(orv) =>
          val taken      = valueParameterCount(orv)
          val parameters = SignatureView.of(orv.signature).parameters
          val holds      = kept && count < taken
          (0 until count).map { index =>
            val block = !universe.constructors.contains(callee) && index < taken && rowSlot(orv, index).isEmpty &&
              parameters.lift(index).exists(tpe => isFunctionType(tpe.value, universe))
            !block || holds
          }
      }

    /** Running a row-typed parameter is checked like a call (D20 rule 5): every entry its declaration promised the
      * block must be given here, and by the implementation promised. The block's bindings were written at the outer
      * call from that declaration, so what runs is fixed; a body that gives something else, or nothing, would make the
      * declaration a lie. A slot that names its implementation with `with` gives it by that declaration. An entry
      * promised as `Default` — the slot's own, or one its named implementation's clauses perform — must be given by a
      * further slot that supplies it, which ends at a body-less declaration: the only kind that gives an effect from
      * nothing.
      */
    private def checkGiven(name: Sourced[String], scope: Scope): Unit =
      scope.promises.getOrElse(name.value, Map.empty).toSeq.sortBy(_._1.abilityName).foreach { case (ability, promise) =>
        val giving = scope.binding(ability).map(binding => implementationOf(binding.term))
        (promise.implementation, giving) match {
          case (Some(_), None)                                   => ()
          case (None, None)                                      =>
            violations += Violation(
              name.as(
                s"'${name.value}' is promised the effect '${ability.abilityName}' by this definition's declaration" +
                  performedBy(promise) + ", but nothing here gives it."
              ),
              Seq(
                s"Declare '${ability.abilityName}' in this definition's { ... } effect set, or pass '${name.value}' to " +
                  "a parameter that gives it."
              )
            )
          case (_, Some(actual)) if actual != promise.implementation =>
            violations += mismatchedGiving(name, ability, promise, actual)
          case _                                                 => ()
        }
      }

    private def mismatchedGiving(
        name: Sourced[String],
        ability: AbilityFQN,
        promise: Promise,
        actual: Option[ValueFQN]
    ): Violation = {
      def describe(implementation: Option[ValueFQN]): String =
        implementation.fold("the default implementation")(fqn => s"the implementation '${implementationName(fqn)}'")
      Violation(
        name.as(
          s"'${name.value}' is promised ${describe(promise.implementation)} of '${ability.abilityName}' by this " +
            s"definition's declaration${performedBy(promise)}, but is given ${describe(actual)} here."
        ),
        Seq("Name the implementation on the parameter with `with`, so the caller binds the same one.")
      )
    }

    /** Which named implementation's clauses a promise comes from, as a clause of a message; empty for the slot's own. */
    private def performedBy(promise: Promise): String =
      promise.through.fold("")(marker => s" (its implementation '${implementationName(marker)}' performs it)")

    /** The implementation a binding term names: [[None]] for `Default`, else the marker at its head. */
    private def implementationOf(term: OperatorResolvedExpression): Option[ValueFQN] =
      spine(term)._1 match {
        case ValueReference(name, _) if name.value != WellKnownTypes.defaultImplementationFQN => Some(name.value)
        case _                                                                               => None
      }

    private def implementationName(marker: ValueFQN): String =
      marker.name.qualifier match {
        case Qualifier.AbilityImplementation(_, _, Some(implementationName)) => implementationName
        case _                                                               => marker.name.name
      }

    /** A block of this definition kept where only a value may stand (D20 rule 3). */
    private def keptBlock(name: Sourced[String], scope: Scope): Violation =
      if (scope.thunks.contains(name.value))
        Violation(
          name.as(s"'${name.value}' is a suspended parameter: it can be run or passed on, not kept."),
          Seq("Run it here, or pass it directly to a parameter that takes a computation.")
        )
      else
        Violation(
          name.as(s"'${name.value}' is a function parameter: it can be called or passed on, not kept."),
          Seq("Call it here, or pass it directly to a function parameter of a definition.")
        )

    /** One actual, at the callee's parameter `index`. A row-typed slot is a thunk, so the actual is wrapped — and the
      * entries that slot *supplies* are bound inside it, which is resolution-order step 3.
      */
    private def walkArgument(
        arg: Sourced[OperatorResolvedExpression],
        callee: ValueFQN,
        index: Int,
        scope: Scope,
        kept: Boolean
    ): Sourced[OperatorResolvedExpression] =
      universe.lookup(callee).flatMap(orv => rowSlot(orv, index).map(orv -> _)) match {
        case None                    =>
          arg.value match {
            // The arms of a `match` run inside it, as `if`'s do, so they are blocks. `match` lowers to
            // `handleCases(scrutinee, $selector -> $selector(arm₁)…(armₙ))`, which puts the arms at the selector rather
            // than at a definition's parameter; this reads through it to the arms the author wrote.
            case FunctionLiteral(selector, selectorType, body) if WellKnownTypes.isPatternMatchHandleCases(callee) =>
              val inside = scope.shadow(selector.value)
              arg.as(
                FunctionLiteral(
                  selector,
                  selectorType,
                  walk(body, inside.copy(selectors = inside.selectors + selector.value), kept = true)
                )
              )
            case _                                                                                              =>
              walk(arg, scope, kept)
          }
        case Some((calleeOrv, slot)) =>
          val name    = thunkParameter
          val inside  = suppliedScope(calleeOrv, index, slot, scope, arg).shadow(name)
          arg.as(
            FunctionLiteral(
              arg.as(name),
              Some(arg.as(ValueReference(arg.as(WellKnownTypes.unitTypeFQN)))),
              walk(arg, inside, kept)
            )
          )
      }

    /** The scope inside a row-typed slot: every entry the slot **supplies** — one the callee's own declared row does
      * not already have — bound to the slot's `with` for that ability, or to `Default`.
      */
    private def suppliedScope(
        calleeOrv: OperatorResolvedValue,
        index: Int,
        slot: Seq[AbilityFQN],
        scope: Scope,
        anchor: Sourced[?]
    ): Scope = {
      val rides    = calleeOrv.effectRow.returnEffects.map(_.abilityFQN).toSet
      val declared = slotBindings(SignatureView.of(calleeOrv.signature).parameters(index).value)
        .flatMap(marker => abilityOf(marker.value, universe).map(_ -> marker))
        .toMap
      slot.filterNot(rides).foldLeft(scope) { (acc, ability) =>
        acc.bind(
          declared
            .get(ability)
            .fold(Binding(ability, defaultBinding(anchor)))(marker =>
              Binding(ability, slotImplementation(marker, acc, rides))
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

    /** The term a **slot's** `with` binds (`program: {Console} Unit with recordingConsole`). Its clause-row bindings
      * are resolved by §2.2's supplied-versus-rides rule, as a slot's own entries are. One the callee **supplies** —
      * an ability its own declared row lacks — is performed inside the *callee*, which is the whole point of a slot
      * `with`: `transcriptOf`'s `runWriterToLog` gives the double its `{Writer[String]}`, and the caller writing the
      * actual can neither see nor name it. So it is written `Default`, and the callee's body is held to giving it
      * where it runs the block ([[promisedBindings]]). One the callee **rides** is the caller's own binding, handed on
      * as the callee's received one, so it is written from this scope like any other reference.
      */
    private def slotImplementation(
        implementation: Sourced[ValueFQN],
        scope: Scope,
        rides: Set[AbilityFQN]
    ): OperatorResolvedExpression =
      boundImplementation(
        implementation,
        scope.defaulting(clauseAbilities(implementation.value, universe).filterNot(rides), implementation)
      )

    /** The abilities a callee's parameter `index` declares, when that parameter is a row position at all. */
    private def rowSlot(orv: OperatorResolvedValue, index: Int): Option[Seq[AbilityFQN]] =
      orv.effectRow.parameterEffects.find(_.parameterIndex === index).map(_.effects.map(_.abilityFQN))

    /** Write the callee's type arguments this call determines, **merged by index**: a binder the callee's declaration
      * marks as a **binding** takes the implementation the resolution order gives it, and every other binder takes, in
      * order, what this call determines for it — the caller's own explicit `typeArgs` where it spelled any, else what
      * a **supplied row slot** settles (A6).
      *
      * Type-argument application is positional, so what is written is the run from index 0 that every slot below it
      * fills. A binder past the first gap is left to the checker, which is always the fail-safe direction — except
      * for a **binding**, which the checker has no way to solve and would silently ground to the platform's default;
      * that one is reported ([[unreachableBinding]]).
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
          val (written, reach) = mergeTypeArguments(
            SignatureView.of(orv.signature).binders.size,
            marks,
            determined,
            ability => reference.as(bindingFor(ability, effects.contains(ability), reference, scope))
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
      * Returns the arguments to write and how many of the callee's binders they reach. Anything left over once every
      * binder has a value is appended — an over-spelled explicit list is the caller's to answer for, not this write's
      * to truncate — and cannot be a gap, because the fillers run out before a gap can open.
      */
    private def mergeTypeArguments(
        binderCount: Int,
        marks: Map[Int, AbilityFQN],
        determined: Seq[Sourced[OperatorResolvedExpression]],
        binding: AbilityFQN => Sourced[OperatorResolvedExpression]
    ): (Seq[Sourced[OperatorResolvedExpression]], Int) = {
      val (slots, leftover) = (0 until binderCount).foldLeft(
        (Seq.empty[Option[Sourced[OperatorResolvedExpression]]], determined)
      ) { case ((acc, rest), index) =>
        marks.get(index) match {
          case Some(ability) => (acc :+ Some(binding(ability)), rest)
          case None          => (acc :+ rest.headOption, rest.drop(1))
        }
      }
      val reached = slots.takeWhile(_.isDefined).flatten
      (reached ++ leftover, reached.size)
    }

    /** A binding this call cannot reach: it sits at a type-argument index behind a binder nothing here determines, and
      * a type-argument list applies positionally.
      *
      * The shape is a member of a **parameterised** ability declaring effects of its own
      * (`ability Show[T] { def show(t: T): {Log} String }`): the block's `T` stands between the two bindings, and `T`
      * is inferred from the argument at every call rather than written. Spelling the call's type arguments
      * (`show[String](x)`) reaches past it; nothing else does, so writing the short prefix silently would leave the
      * second binding on the platform's default.
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
        isEffect: Boolean,
        at: Sourced[?],
        scope: Scope
    ): OperatorResolvedExpression =
      scope.binding(ability) match {
        case Some(binding) =>
          if (isEffect) use(binding, at)
          else consume(binding)
          binding.term
        case None          =>
          if (isEffect) reportUncovered(ability, at, scope)
          defaultBinding(at)
      }

    /** Consume an **effect's** binding at `at`, unless it is in scope only from outside a lambda kept as a value: a
      * kept function runs after the scope that bound the effect is gone, so it may use none from around it (D20 rule
      * 4). Binding and discharging one inside the lambda is fine, since that binding is the lambda's own.
      */
    private def use(binding: Binding, at: Sourced[?]): Unit =
      if (binding.captured)
        violations += Violation(
          at.as(
            s"This function is kept as a value, so it cannot use the effect '${binding.ability.abilityName}' from " +
              "around it."
          ),
          Seq(
            "Pass the function directly to a function parameter of a definition, or bind and discharge the effect " +
              "inside it."
          )
        )
      else consume(binding)

    private def reportUncovered(ability: AbilityFQN, at: Sourced[?], scope: Scope): Unit =
      if (!scope.uncoveredDefaults)
        violations += Violation(
          at.as(
            s"This value performs the effect '${ability.abilityName}' but does not declare it; " +
              "add it to its { ... } effect set."
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
