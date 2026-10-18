package com.vanillasource.eliot.eliotc.ast.fact

/** The names the new effect path (`docs/effects.md` D36, `LangProcessors`' `handles`) mints for **handles**: an effect
  * is an ability over a handle type, every operation takes the handle as its first value parameter, a `uses` entry is
  * a handle parameter, and a named implementation is a `data` whose value is the handle.
  *
  * Every name holds a `$`, which the tokenizer never produces, so none collides with a name a user writes:
  *
  * {{{
  * effect Console { def printLine(line: String): Unit }
  *   ⟶ printLine[Impl](Impl$handle: Impl, line: String): Unit          -- [[parameter]] of the ability's binder
  * def greet(name: String) uses Console: Unit
  *   ⟶ greet[Impl](Impl$handle: Impl, name: String): Unit              -- [[parameter]] of the entry's binder
  * implement scripted(answer: String): Console { … }
  *   ⟶ data Scripted$Handle(scripted$answer: String)                    -- [[dataType]], [[field]]
  * }}}
  */
object Handles {

  /** The handle parameter standing for the binding binder `binder`. */
  def parameter(binder: String): String = s"$binder$$handle"

  /** Whether `name` is a handle parameter's ([[parameter]]). */
  def isParameter(name: String): Boolean = name.endsWith("$handle")

  /** The handle `data` type of the named implementation `implementation`, which is also its value constructor's name.
    * Capitalised, as a type's name is, since that is what puts a bare name in the type namespace; an implementation's
    * name always starts lower-case, so no two implementations share one.
    */
  def dataType(implementation: String): String = s"${implementation.capitalize}$$Handle"

  /** Whether `name` is a handle `data` type's ([[dataType]]). */
  def isDataType(name: String): Boolean = name.endsWith("$Handle")

  /** The named implementation whose handle `data` type is `dataType` ([[dataType]]'s inverse). */
  def implementationOf(dataType: String): String = {
    val capitalised = dataType.stripSuffix("$Handle")
    capitalised.take(1).toLowerCase + capitalised.drop(1)
  }

  /** The field of the handle `data` holding the implementation's parameter `parameter`, which is also its accessor's
    * name: the implementation's name keeps it apart from another implementation's field in the same module.
    */
  def field(implementation: String, parameter: String): String = s"$implementation$$$parameter"

  /** The name an implementation's clause gives its own handle, its first parameter. */
  val self: String = "self$handle"

  /** The type parameter of an implementation's handle `data` for the `j`-th entry its clauses write — the type of the
    * handle a `with` may capture for it (`docs/effects.md` D36 point 2).
    */
  def capture(j: Int): String = s"Capture$$$j"

  /** Whether `name` is a capture type parameter's ([[capture]]). */
  def isCapture(name: String): Boolean = name.startsWith("Capture$")

  /** The field of the handle `data` holding the handle captured for the `j`-th entry its clauses write. */
  def captureField(implementation: String, j: Int): String = field(implementation, s"capture$$$j")

  /** The body-less declaration, in an implementation's namespaces, whose row is the entries its clauses write, in the
    * order of the handle `data`'s capture fields.
    */
  val captures: String = "$captures"

  /** The body-less declaration, in a named implementation's namespace, whose row is the entries its clause `clause`
    * writes: what a `with` reads to tell which entries the implementation captures.
    */
  def clause(clause: String): String = s"$$clause$$$clause"

  /** Whether `name` is one of an implementation's own declarations rather than a clause. */
  def isDeclaration(name: String): Boolean = name == captures || name.startsWith("$clause$")

  /** The name of the handle the `n`-th `with` of a definition binds. */
  def binding(n: Long): String = s"$$with$n"

  /** Whether `name` is the handle a `with` binds ([[binding]]). */
  def isBinding(name: String): Boolean = name.startsWith("$with")
}
