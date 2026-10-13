package com.vanillasource.eliot.eliotc.module.fact

import com.vanillasource.eliot.eliotc.module.fact.{QualifiedName, Qualifier}
import com.vanillasource.eliot.eliotc.module.fact.ModuleName.{compilerPackage, defaultSystemPackage, effectPackage}

object WellKnownTypes {
  val typeFQN: ValueFQN =
    ValueFQN(ModuleName(compilerPackage, "Type"), QualifiedName("Type", Qualifier.Type))

  val functionDataTypeFQN: ValueFQN =
    ValueFQN(ModuleName.systemFunctionModuleName, QualifiedName("Function", Qualifier.Type))

  /** The runtime *carrier* a function value erases to: the same `eliot.lang.Function` module as [[functionDataTypeFQN]]
    * but with [[Qualifier.Default]] (the value namespace) instead of the type-constructor qualifier.
    * [[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.carrierFQN]] collapses every function type to this
    * FQN; a backend maps it to its closure representation (the JVM maps it to `java.util.function.Function`).
    */
  val functionCarrierFQN: ValueFQN =
    ValueFQN(ModuleName.systemFunctionModuleName, QualifiedName("Function", Qualifier.Default))

  /** `eliot.lang.Unit` — the domain of a **thunk**, which is what effects v6 lowers a row-typed slot to
    * (`docs/effects.md` §9.4 step 2): `{Abort} T` is `Unit => T`, a computation the callee runs when it chooses.
    */
  val unitTypeFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Unit"), QualifiedName("Unit", Qualifier.Type))

  /** `eliot.lang.Unit::unit` — the value a thunk is applied to. */
  val unitValueFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Unit"), QualifiedName("unit", Qualifier.Default))

  /** The opaque top carrier that erased or `Type`-typed values collapse to under
    * [[com.vanillasource.eliot.eliotc.monomorphize.fact.GroundValue.carrierFQN]]. It is deliberately not a declared
    * stdlib type — it is the erased-representation sentinel every backend needs (the JVM maps it to
    * `java.lang.Object`).
    */
  val anyFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Any"), QualifiedName("Any", Qualifier.Default))

  /** The **`Default`** implementation marker of effects v6 (`docs/effects.md` §9.4 step 3): the value a phantom row or
    * constraint binder carries when no `with` names an implementation — "search at the ground arguments", today's
    * two-site resolution. It is the compiler's own sentinel, like [[anyFQN]]: not declared in any layer, never named by
    * a user, and never a runtime value (a phantom binder occurs in no type and is erased). It occupies a
    * type-argument position, hence the type-constructor qualifier; as a ground value it is the nullary
    * `Structure(defaultImplementationFQN, Nil, Type)`, which
    * [[com.vanillasource.eliot.eliotc.monomorphize.check.ImplementationBinding]] reads back. The other value such a
    * binder can carry is an implementation, headed by a [[Qualifier.AbilityImplementation]] name.
    */
  val defaultImplementationFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Implementation"), QualifiedName("Default", Qualifier.Type))

  /** The **mark** a binding binder carries as its declared type — `type Implementation[A] = Type` in
    * `eliot.lang.Implementation`, beside [[defaultImplementationFQN]] (`docs/effects.md` §9.2).
    *
    * A binder the desugar mints for a row entry, a `~` constraint or an `ability` block's implementation slot is
    * declared `Impl: Implementation[Console]`, and that declaration is the *only* place the fact "this binder is a
    * binding" is written down: [[com.vanillasource.eliot.eliotc.row.BindingWriter]] reads the marks off the
    * operator-resolved signature, where an alias is not yet expanded, and the ability off the mark's argument.
    * Recognised by this FQN exactly as [[abilityCombinatorFQN]] is, and reduced to `Type` by definitional equality, so
    * the checker makes no decision on it.
    */
  val implementationTypeFQN: ValueFQN =
    ValueFQN(
      ModuleName(defaultSystemPackage, "Implementation"),
      QualifiedName("Implementation", Qualifier.Type)
    )

  /** The ability-constraint combinator `&` — `infix left type &[A, B]` in `eliot.lang.Ability`, the one name the `~`
    * constraint syntax resolves rather than recognises (`docs/effects-syntax-userspace.md` §4 stage 1).
    *
    * It sits in [[Qualifier.Default]] because an operator-named `type` does: operators are always referenced bare, so
    * [[com.vanillasource.eliot.eliotc.ast.fact.TypeAliasDefinition]] puts them in the value namespace where a bare
    * reference looks.
    */
  val abilityCombinatorFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Ability"), QualifiedName("&", Qualifier.Default))

  val bigIntFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "BigInteger"), QualifiedName("BigInteger", Qualifier.Type))

  val stringFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "String"), QualifiedName("String", Qualifier.Type))

  val boolFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Bool"), QualifiedName("Bool", Qualifier.Type))

  /** `eliot.collection.List` — the base's abstract list, a native `java.util.List` on the jvm and a `prepend` chain over
    * `empty` on the compiler track ([[com.vanillasource.eliot.eliotc.monomorphize.processor.ListReductions]]). The
    * value names below are the module's four platform leaves plus its two string-splitting leaves, shared by the
    * reflection rewrite (`NamedValues`, which lowers an enumeration to a `prepend` chain) and the compile-time twins.
    */
  val listModule: ModuleName = ModuleName(Seq("eliot", "collection"), "List")

  val listFQN: ValueFQN = ValueFQN(listModule, QualifiedName("List", Qualifier.Type))

  val listEmptyFQN: ValueFQN            = ValueFQN(listModule, QualifiedName("empty", Qualifier.Default))
  val listAppendFQN: ValueFQN           = ValueFQN(listModule, QualifiedName("append", Qualifier.Default))
  val listPrependFQN: ValueFQN          = ValueFQN(listModule, QualifiedName("prepend", Qualifier.Default))
  val listFoldLeftInternalFQN: ValueFQN = ValueFQN(listModule, QualifiedName("foldLeftInternal", Qualifier.Default))
  val listSplitFQN: ValueFQN            = ValueFQN(listModule, QualifiedName("split", Qualifier.Default))
  val listWordsFQN: ValueFQN            = ValueFQN(listModule, QualifiedName("words", Qualifier.Default))

  private val boolModule: ModuleName = ModuleName(defaultSystemPackage, "Bool")

  val boolTrueFQN: ValueFQN  = ValueFQN(boolModule, QualifiedName("true", Qualifier.Default))
  val boolFalseFQN: ValueFQN = ValueFQN(boolModule, QualifiedName("false", Qualifier.Default))

  /** `fold[A](condition: Bool, whenTrue: A, whenFalse: A): A` — the two-armed `Bool` eliminator, and the one *lazy*
    * value-level primitive: only the selected arm is ever run. The effects-as-channel weaver recognises it (and
    * [[boolIfFQN]]) by FQN so it does **not** eagerly sequence both arms the way it sequences a strict function's
    * effectful arguments.
    */
  val boolFoldFQN: ValueFQN = ValueFQN(boolModule, QualifiedName("fold", Qualifier.Default))

  /** `if[T](condition: Bool, value: {Abort} T): {Abort} T` — `fold(condition, value, abort)`; likewise lazy in its arm.
    * Recognised by the effects-as-channel weaver alongside [[boolFoldFQN]] to keep conditional arms unsequenced.
    */
  val boolIfFQN: ValueFQN = ValueFQN(boolModule, QualifiedName("if", Qualifier.Default))

  private val eitherModule: ModuleName = ModuleName(defaultSystemPackage, "Either")

  /** The `Either[E, A]` type constructor — the discharge carrier of the `Throw[E]` effect (`runThrow` reflects a
    * `{Throw[E]}` computation into an `Either[E, A]`). Abstract in the base layer (`type Either[E, A]`), redefined
    * concretely per platform (the `jvm` layer for the runtime phase, the compiler platform for the compile-time phase).
    * The effectful-signatures discharge (W2) reads back a `{Throw[String]} Type` signature as a ground `Either[String,
    * Type]` and inspects its head by [[leftFQN]]/[[rightFQN]].
    */
  val eitherFQN: ValueFQN = ValueFQN(eitherModule, QualifiedName("Either", Qualifier.Type))

  /** The `Left` constructor of [[eitherFQN]] (the error case, by convention). A value constructor, so
    * [[Qualifier.Default]] (the value namespace). The discharge step recognises `Left(msg)` as a guard rejection —
    * `compilerAbort` with `msg`.
    */
  val leftFQN: ValueFQN = ValueFQN(eitherModule, QualifiedName("Left", Qualifier.Default))

  /** The `Right` constructor of [[eitherFQN]] (the success case). A value constructor, so [[Qualifier.Default]]. The
    * discharge step reads `Right(t)` as the resolved return type `t`.
    */
  val rightFQN: ValueFQN = ValueFQN(eitherModule, QualifiedName("Right", Qualifier.Default))

  private val pairModule: ModuleName = ModuleName(defaultSystemPackage, "Pair")

  /** The value constructor of the concrete `Pair` — `data Pair[A, B](first: A, second: B)`, the representation both the
    * jvm layer and the compile-track overlay (`stdlib/eliot/compiler/eliot/lang/Pair.els`) give the base's abstract
    * `Pair`. What the cell intrinsic answers, so the overlay's `foldPair` takes it apart like any `match`; the
    * `Left`/`Right` of [[eitherFQN]] play the same role for the escape intrinsic.
    */
  val pairConstructorFQN: ValueFQN = ValueFQN(pairModule, QualifiedName("Pair", Qualifier.Default))

  /** The compiler platform's two control-flow primitives (effects v6, `docs/effects.md` §9.6 and §10.1 step 7) — the
    * evaluator intrinsics the compile-track dischargers are bodied over at the flag day, replacing the `Either`-based
    * `AbortCarrier` overlay. Compiler-owned like `Type` and `Meta`, so they live in the [[compilerPackage]]: never
    * ambient, and declared only by the compile-track overlay that bodies the dischargers over them.
    *
    * '''Every one of them takes its instantiation as a leading type *value*.''' The frame an operation reaches is the
    * nearest enclosing one *of its instantiation* — `exit` at `E := String` must pass through an `escape` at `E := Unit`
    * (`if(c) T else raise(msg)` is exactly that nesting) — and the evaluator cannot read that instantiation off type
    * arguments: the post-mono `MonomorphicEvaluator` erases them, and bindings are looked up by FQN alone. Types are
    * values, so the overlay passes the type itself (`escape(E, body)`), and the intrinsic compares keys by definitional
    * equality of concrete normal forms. See
    * [[com.vanillasource.eliot.eliotc.monomorphize.processor.EffectIntrinsics]].
    */
  private val escapeModule: ModuleName = ModuleName(compilerPackage, "Escape")

  /** `escape[E, A](key: Type, body: Unit => A): Either[E, A]` — installs an escape frame keyed by `key` (the
    * instantiation `E`), runs the thunk, and answers `Right(a)`, or `Left(e)` if the body `exit`ed to this frame.
    */
  val escapeFQN: ValueFQN = ValueFQN(escapeModule, QualifiedName("escape", Qualifier.Default))

  /** `exit[E, A](key: Type, e: E): A` — the abortive non-local exit to the nearest enclosing [[escapeFQN]] frame with an
    * equal key; the only way a compile-track computation finishes early.
    */
  val exitFQN: ValueFQN = ValueFQN(escapeModule, QualifiedName("exit", Qualifier.Default))

  private val cellModule: ModuleName = ModuleName(compilerPackage, "Cell")

  /** `withCell[S, A](key: Type, initial: S, body: Unit => A): Pair[A, S]` — installs a cell keyed by `key` (the
    * instantiation `S`) holding `initial`, runs the thunk, and answers the result paired with the cell's final content.
    * Scoped to the one call: the cell is gone when the call returns or is exited through.
    */
  val withCellFQN: ValueFQN = ValueFQN(cellModule, QualifiedName("withCell", Qualifier.Default))

  /** `read[S](key: Type): S` — the content of the nearest enclosing [[withCellFQN]] cell with an equal key. */
  val cellReadFQN: ValueFQN = ValueFQN(cellModule, QualifiedName("read", Qualifier.Default))

  /** `write[S](key: Type, s: S): Unit` — replaces the content of the nearest enclosing [[withCellFQN]] cell with an
    * equal key.
    */
  val cellWriteFQN: ValueFQN = ValueFQN(cellModule, QualifiedName("write", Qualifier.Default))

  /** `integerLiteral[V]: Int` — the platform-independent literal protocol. `CoreExpressionConverter` desugars a
    * value-position integer literal `n` into `integerLiteral[n]` so that the checker assigns it plain `Int` (post
    * flag-day `Int` carries no bounds; the range lives in the refinement channel, not the type). The literal value
    * itself is a compile-time constant carried as the erased type-argument `V`, read downstream two ways:
    * `PostDrainQuoter` recognizes this FQN at the `SemExpression → MonomorphicExpression` readback and rewrites the
    * reference into a plain `IntegerLiteral(V)` node (which every backend emits via its ordinary integer-literal path —
    * so no backend needs an `integerLiteral` intrinsic); and the refinement channel reduces the def's own
    * `integerLiteral^Meta` companion (from its `{closed(V, V)}` return brace) at `V` to *seed* the literal's value
    * range — the one place a meta originates, kept in Eliot on this protocol (`docs/generic-refinement-merges.md`).
    */
  val integerLiteralFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Runtime"), QualifiedName("integerLiteral", Qualifier.Default))

  /** `stringLiteral[N]: String` — the string literal's *size* protocol, the [[integerLiteralFQN]] twin for the `String`
    * refinement domain. A string literal needs no conversion (its characters are already the value), so unlike
    * `integerLiteral` this declaration is never referenced by a program and no desugaring rewrites into it: it exists
    * solely so the refinement channel can reduce its `stringLiteral^Meta` companion (from the `{closed(N, N)}`
    * return brace) at the literal's measured length, *seeding* the string's size meta. Keeping the seed's construction
    * in Eliot is the same discipline the integer seed follows — the channel supplies only the raw count
    * (`docs/string-length-meta.md` §3.1).
    */
  val stringLiteralFQN: ValueFQN =
    ValueFQN(ModuleName(defaultSystemPackage, "Runtime"), QualifiedName("stringLiteral", Qualifier.Default))

  /** The `PatternMatch` ability (`eliot.lang.PatternMatch`) drives surface `match`: `matchdesugar` lowers a `match`
    * into a call to the ability's `handleCases` eliminator, and a backend recognises each implementation by this name
    * to emit the constructor's pattern-match dispatch. An implementation method carries
    * `Qualifier.AbilityImplementation(name, index)` with `name.value == patternMatchAbilityName`; the data type it
    * matches on is recovered from the marker signature via `ImplementationMarkerUtils.firstPatternTypeConstructorName`.
    */
  val patternMatchAbilityName: String = "PatternMatch"

  /** The eliminator method of [[patternMatchAbilityName]] — `handleCases(value, cases)`. */
  val patternMatchHandleCasesName: String = "handleCases"

  /** The `TypeMatch` ability (`eliot.lang.TypeMatch`) drives surface type-pattern matching: an implementation's
    * `typeMatch` matcher dispatches a `Type` value against one type constructor. Recognised by name the same way as
    * [[patternMatchAbilityName]].
    */
  val typeMatchAbilityName: String = "TypeMatch"

  /** The matcher method of [[typeMatchAbilityName]] — `typeMatch(value, matched, notMatched)`. */
  val typeMatchMethodName: String = "typeMatch"

  /** The ability name an implementation method belongs to, if its qualifier is an ability implementation. */
  private def abilityImplementationName(vfqn: ValueFQN): Option[String] =
    vfqn.name.qualifier match {
      case Qualifier.AbilityImplementation(name, _, _) => Some(name)
      case _                                        => None
    }

  /** True when `vfqn` is any method of a [[patternMatchAbilityName]] implementation. */
  def isPatternMatchImplementation(vfqn: ValueFQN): Boolean =
    abilityImplementationName(vfqn).contains(patternMatchAbilityName)

  /** True when `vfqn` is the `handleCases` eliminator of a [[patternMatchAbilityName]] implementation. */
  def isPatternMatchHandleCases(vfqn: ValueFQN): Boolean =
    isPatternMatchImplementation(vfqn) && vfqn.name.name == patternMatchHandleCasesName

  /** True when `vfqn` is any method of a [[typeMatchAbilityName]] implementation. */
  def isTypeMatchImplementation(vfqn: ValueFQN): Boolean =
    abilityImplementationName(vfqn).contains(typeMatchAbilityName)

  /** True when `vfqn` is the `typeMatch` matcher of a [[typeMatchAbilityName]] implementation. */
  def isTypeMatchTypeMatch(vfqn: ValueFQN): Boolean =
    isTypeMatchImplementation(vfqn) && vfqn.name.name == typeMatchMethodName
}
