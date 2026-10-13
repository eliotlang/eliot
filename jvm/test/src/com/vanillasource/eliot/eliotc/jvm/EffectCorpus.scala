package com.vanillasource.eliot.eliotc.jvm

/** The permanent effect-system corpus: Eliot programs that exercise the design's worked effect shapes end to end over
  * the real `lang`/`stdlib`/`jvm` layers.
  *
  * This is a **fixture, not a harness** — it deliberately depends on nothing, which is what let the experiment
  * harnesses that read it (the R3 shadow sweep, the R4 shadow compile) be deleted at A.11.9 without taking the corpus
  * with them. Its permanent consumer is [[EffectCorpusIntegrationTest]], which pins what these programs print. Every
  * effect shape the project has had to keep working lives here; add to it rather than inlining a new program into a
  * suite.
  */
object EffectCorpus {

  /** One combined program covering the design's worked shapes: direct-style Console blocks, discharge under a pure
    * return (`catch` and `runStateToPair`), an effectful catch handler, an `Abort` program discharged to an `Option`,
    * the `State` effect in direct style, and a `main` sequencing them all (so demand-driven compilation reaches every
    * definition).
    */
  val combinedProgram: String =
    """import eliot.effect.Console
      |import eliot.effect.Throw
      |import eliot.effect.State
      |import eliot.effect.Abort
      |
      |def shout(s: String) uses Console: Unit = printLine(s)
      |
      |def greet uses Console: Unit = {
      |   shout("a")
      |   printLine("b")
      |}
      |
      |def parsed(raw: String) uses Throw[String]: String = raise("unparseable")
      |
      |def recovered: String = parsed("x") catch (err -> err)
      |
      |def counted: Pair[String, String] = runStateToPair("initial", state)
      |
      |def failUnit uses Throw[String]: Unit = raise("boom")
      |
      |def caught uses Console: Unit = failUnit catch (err -> printLine(err))
      |
      |def allowed uses Abort: String = if(true, "granted")
      |def denied uses Abort: String = abort
      |
      |def testAllowed: Option[String] = runAbort(allowed)
      |def testDenied: Option[String] = runAbort(denied)
      |
      |def swap(next: String) uses State[String]: String = {
      |   val old = state
      |   putState(next)
      |   old
      |}
      |
      |def prog: Pair[String, String] = runStateToPair("before", swap("after"))
      |
      |def main uses Console: Unit = {
      |   greet
      |   printLine(recovered)
      |   printLine(counted.first)
      |   caught
      |   printLine(foldOption("DENIED", s -> s, testAllowed))
      |   printLine(foldOption("DENIED", s -> s, testDenied))
      |   printLine(prog.first)
      |}""".stripMargin

  /** An effect performed while **building** an argument that a carrier-codomain slot receives — §1 rule 1, where the
    * slot's declared type is an arrow and therefore a rowless *value* position, the row belonging to what applying that
    * function later does.
    *
    * `labelOf` is the shape as the user meets it: `s.take(s.indexOf("="))` is `.(s, take(indexOf("=", s)))`, so the
    * `{Abort}` of `indexOf` is performed while constructing the argument handed to `.`'s `f: A => {Effect} B`. It was
    * rejected outright ("No ability implementation found for ability 'Abort' with type arguments [Id]", reported inside
    * `stdlib`'s `Bool.els`, a file the user never wrote), which is why the equivalent plain call `take(indexOf("=", s),
    * s)` was the only spelling that compiled.
    *
    * The `foreach` lines pin **where** the effect runs, which is the half a verdict-only fix gets wrong: `readLine` is
    * written once, outside the traversal, so it must run once and its one line must prefix all three elements. An
    * elaboration that defers the construction under the invented eta binder instead reads per element — the same
    * program, silently mis-sequenced. The pure-construction line beside it is the control: it must keep working
    * unchanged, since nothing there has to be hoisted.
    */
  val argumentConstructionProgram: String =
    """import eliot.effect.Abort
      |import eliot.collection.List
      |
      |def labelOf(s: String) uses Abort: String = s.take(s.indexOf("="))
      |
      |def three: List[String] = append(append(append(empty, "a"), "b"), "c")
      |
      |def tag(mark: String, item: String) uses Console: Unit = printLine(mark ++ item)
      |
      |def main uses Console: Unit = {
      |   printLine(labelOf("host=1") else "none")
      |   printLine(labelOf("nokey") else "none")
      |   three.foreach(tag(readLine.orAbort else "eof:"))
      |   three.foreach(tag("pure:"))
      |}""".stripMargin

  /** An **explicit lambda whose body performs**, at a slot declared `A => {Effect} B`.
    *
    * The sibling of [[argumentConstructionProgram]] and the half a placement-only fix leaves: here the argument is
    * already a lambda, so nothing is hoisted and nothing is eta-lifted — what has to be right is the *verdict*, the row
    * the call instantiates `applyTo`'s row variable at. The lambda is a payload by kind, because `take` returns a plain
    * `String`, so reading the kind called the row empty and wrote the call at `Id`; the body's `{Abort}` was then
    * demanded of `Id` and reported inside `stdlib`'s `Bool.els`, a file the user never wrote.
    *
    * `applyTo` is spelled out rather than reached through `.` on purpose: the shape has nothing to do with the dot
    * operator, which is only the most common way to meet it.
    */
  val effectfulLambdaProgram: String =
    """import eliot.effect.Abort
      |
      |def applyTo[A, B](a: A, f uses *: A => B): B = f(a)
      |
      |def labelOf(s: String) uses Abort: String = applyTo(s, x -> take(indexOf("=", s), x))
      |
      |def main uses Console: Unit = {
      |   printLine(labelOf("host=1") else "none")
      |   printLine(labelOf("nokey") else "none")
      |}""".stripMargin

  /** A **row-polymorphic callback whose result is consumed strictly** — `append(acc, f(e))` with `f: A => {Effect} B`.
    *
    * The third of the arrow-slot family, and the one that closes it. [[effectfulLambdaProgram]] applies such a callback
    * in *tail* position, where its computation is the region's own result and nothing has to be sequenced; here the
    * result has to become a plain `B` before `append` can take it, so the call must **hoist** — §1 rule 1, the effect
    * runs where it is written. It did not: a row-polymorphic callback declares the row variable ρ, and a row is a set
    * of concrete abilities, so deriving `f(e)`'s row yielded the empty set and read the call as pure. The computation
    * was then passed inline to `append`'s payload slot and rejected as a rule-4 violation — a hard error, so the whole
    * shape (`map`, `filter`, `groupBy`, any combinator taking a user function) was unwritable. See
    * `RowChecker.rowDeclaringParameters`.
    *
    * Both kinds of callback are exercised, and they check opposite halves: `announce` performs `{Console}`, where the
    * "visiting" lines must appear once per element and *before* the mapped results (the callback runs inside
    * `eachInto`'s traversal, not at the caller's later `foreach`); the pure lambda performs nothing, and must run
    * identically — the slot's `{}` says the callback's effects are the caller's, whatever they turn out to be.
    */
  val rowPolymorphicCallbackProgram: String =
    """import eliot.collection.List
      |
      |def eachInto[A, B](f uses *: A => B, list: List[A]): List[B] =
      |   list.foldLeft(empty, e -> acc -> append(acc, f(e)))
      |
      |def three: List[String] = append(append(append(empty, "a"), "b"), "c")
      |
      |def announce(s: String) uses Console: String = {
      |   printLine("visiting " ++ s)
      |   s ++ "!"
      |}
      |
      |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
      |
      |def main uses Console: Unit = {
      |   val announced = eachInto(announce, three)
      |   announced.foreach(printLine)
      |   printLine(joinAll(eachInto(s -> s ++ "?", three)))
      |}""".stripMargin

  /** Repeated `readLine`: the `Console` effect must be able to read more than one line.
    *
    * A `BufferedReader` reads ahead, so a reader built per call takes the whole of standard input into its buffer and
    * discards it with itself — which is what this native used to do, leaving every read after the first at
    * end-of-stream. The single shared reader is what makes the second and third lines arrive. The leading `printLine`
    * is part of the test, not scenery: the reader is a field on the same generated class, so a print is what triggers
    * that class's initialisation, and a reader captured there rather than at the first read would bind standard input
    * too early.
    */
  val stdinProgram: String =
    """def line uses Console: String = readLine.orAbort else "<eof>"
      |
      |def main uses Console: Unit = {
      |   printLine("start")
      |   printLine("1:" ++ line)
      |   printLine("2:" ++ line)
      |   printLine("3:" ++ line)
      |}""".stripMargin

  /** A deliberately non-terminating program: `Inf` is an ordinary row entry riding the same union. Compile-only. */
  val infProgram: String =
    """import eliot.effect.Console
      |import eliot.effect.Inf
      |
      |def main uses Inf, Console: Unit = forever(printLine("tick"))""".stripMargin
}
