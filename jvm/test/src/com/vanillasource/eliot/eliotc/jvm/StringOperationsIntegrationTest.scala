package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of the `eliot.lang.String` operations, which are body-less leaves realised twice: as
  * `java.lang.String` calls by the JVM backend (`StringNatives`) and as compile-time reductions on the compiler track
  * (`StringReductions`). Both realisations are exercised here, because the whole point of the pair is that a string
  * operation answers the same on either track.
  *
  * The runtime cases drive every operation from `line` (`readLine` with its end-of-input `Option` discharged, since the
  * subject here is the string operations rather than the effect), so no operand is a literal the compiler could fold away and
  * the emitted `java.lang.String` call must fire. The compiler-track cases put the same operations in an ability `where`
  * guard — a position that has no runtime at all: an unreduced guard leaves the implementation unresolved and the
  * program does not compile, so a guard that discharges *is* the proof that the reduction ran while checking.
  */
class StringOperationsIntegrationTest extends FullIntegrationTest {

  private val prelude =
    """import eliot.effect.Console
      |import eliot.effect.Abort
      |
      |def yn(b: Bool): String = fold(b, "y", "n")
      |
      |def line uses Console: String = readLine.orAbort else ""
      |""".stripMargin

  "the string predicates" should "run against a line read at runtime" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(show(s.length) ++ yn(s.isEmpty) ++ yn(s.isBlank) ++
          |             yn(startsWith("ab", s)) ++ yn(endsWith("yz", s)) ++ yn(contains("cd", s)))
          |}""".stripMargin,
      stdin = "abcdefyz\n"
    ).asserting(_ shouldBe "8nnyyy")
  }

  "the string transformations" should "run against a line read at runtime" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(s.trim ++ "|" ++ s.toUpperCase ++ "|" ++ s.toLowerCase ++ "|" ++
          |             replace("b", "B", s) ++ "|" ++ repeat(2, s))
          |}""".stripMargin,
      stdin = " aBc \n"
    ).asserting(_ shouldBe "aBc| ABC | abc | aBc | aBc  aBc ")
  }

  "the index operations" should "slice a line read at runtime" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(substring(2, 5, s) ++ "|" ++ take(3, s) ++ "|" ++ drop(3, s))
          |}""".stripMargin,
      stdin = "abcdefyz\n"
    ).asserting(_ shouldBe "cde|abc|defyz")
  }

  they should "be total — out-of-range and inverted indices yield a string rather than failing" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(substring(0 - 5, 99, s) ++ "|" ++ substring(3, 1, s) ++ "." ++
          |             take(0 - 1, s) ++ "." ++ drop(99, s) ++ "." ++ repeat(0 - 2, s))
          |}""".stripMargin,
      stdin = "abc\n"
    ).asserting(_ shouldBe "abc|...")
  }

  /** Eliot counts and indexes a string in '''code points''', which is a property of the text rather than of a
    * platform's storage, so the same program answers the same numbers everywhere. This platform is the one that has to
    * translate: a `java.lang.String` measures in UTF-16 units, and the emoji below occupies two of them — so every
    * number here would be one larger, and `substring(1, 2)` would answer half a character, without the translation.
    *
    * The string is *built* at runtime (a literal would be folded by the compile-time twin instead) so the assertions
    * land on the emitted `java.lang.String` calls.
    */
  "the index operations" should "count a runtime string in code points, not in storage units" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line ++ "😀b"
          |   printLine(show(s.length) ++ "|" ++ show(indexOf("b", s) else (0 - 1)) ++ "|" ++
          |             yn(s.substring(1, 2) == "😀") ++ yn(s.take(2) == "a😀") ++ yn(s.drop(1) == "😀b"))
          |}""".stripMargin,
      stdin = "a\n"
    ).asserting(_ shouldBe "3|2|yyy")
  }

  /** The other half of the same rule: the compile-time twin must measure exactly as the runtime leaf does, or a
    * program would slice one way while being checked and another while running.
    *
    * Every clause discriminates the two units on `"a😀b"` — `substring(2, length)` is `"b"` at three code points and
    * half an emoji plus `"b"` at four UTF-16 units — and `before` reaches `indexOfInternal`, whose answer has to be a
    * valid `substring` start in the same unit. The indices come from paramless defs because a literal in a `where`
    * guard is type-level (a `BigInteger`), not an `Int`; the checker reduces those defs just as it reduces the guard.
    */
  they should "count a compile-time string in code points, exactly as at runtime" in {
    compileAndRun(
      prelude +
        """
          |def one: Int = 1
          |def two: Int = 2
          |
          |ability Counted[S: String] {
          |   def verdict: String
          |}
          |
          |implement[S: String] Counted[S] where
          |   substring(one, two, S) == "😀" && substring(two, length(S), S) == "b" &&
          |   drop(one, S) == "😀b" && before("b", S) == "a😀" {
          |   def verdict: String = "counted"
          |}
          |
          |def main uses Console: Unit = printLine(verdict["a😀b"])""".stripMargin
    ).asserting(_ shouldBe "counted")
  }

  /** `replace` with an empty `target` fills every gap between characters, and a surrogate pair is one character, so it
    * is one gap. The host's own `replace` would find two.
    */
  "replace" should "treat a surrogate pair as one gap when the target is empty" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line ++ "😀b"
          |   printLine(yn(replace("", "-", s) == "-a-😀-b-") ++ yn(replace("😀", "x", s) == "axb"))
          |}""".stripMargin,
      stdin = "a\n"
    ).asserting(_ shouldBe "yy")
  }

  "indexOf" should "yield the position of a match and abort when there is none" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(show(indexOf("cd", s) else (0 - 1)) ++ "|" ++ show(indexOf("zz", s) else (0 - 1)))
          |}""".stripMargin,
      stdin = "abcdefyz\n"
    ).asserting(_ shouldBe "2|-1")
  }

  "before and after" should "cut a runtime line at its first separator" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(s.before("=") ++ "|" ++ s.after("=") ++ "|" ++ before("?", s) ++ "|" ++ after("?", s) ++ ".")
          |}""".stripMargin,
      stdin = "a=b=c\n"
    ).asserting(_ shouldBe "a|b=c|a=b=c|.")
  }

  "parseInt" should "read a runtime numeral and abort on anything else" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(show(parseInt(s) else (0 - 1)) ++ "|" ++ yn(s.isInteger) ++ yn(isInteger(s ++ "x")))
          |}""".stripMargin,
      stdin = "-1024\n"
    ).asserting(_ shouldBe "-1024|yn")
  }

  /** The guard is what makes the leaf's totality invisible: `parseIntInternal` answers zero for a malformed numeral, and
    * `parseInt` has to turn exactly those strings into an abort rather than into a `0`.
    */
  it should "abort rather than answer zero on a malformed numeral" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(show(parseInt(s) else (0 - 1)) ++ "|" ++ show(parseInt("0") else (0 - 1)))
          |}""".stripMargin,
      stdin = "1a\n"
    ).asserting(_ shouldBe "-1|0")
  }

  "Compare[String]" should "order two runtime strings lexicographically" in {
    compileAndRun(
      prelude +
        """
          |def main uses Console: Unit = {
          |   val s = line
          |   printLine(yn(s < "b") ++ yn(s < "a") ++ yn(s <= s))
          |}""".stripMargin,
      stdin = "ab\n"
    ).asserting(_ shouldBe "yny")
  }

  "every non-index string operation" should "reduce on the compiler track inside an ability guard" in {
    compileAndRun(
      prelude +
        """
          |ability Reduced[S: String] {
          |   def verdict: String
          |}
          |
          |implement[S: String] Reduced[S] where
          |   startsWith("ab", S) && endsWith("yz", S) && contains("cd", S) &&
          |   !isEmpty(S) && !isBlank(S) && trim(S) == S &&
          |   toUpperCase(S) == "ABCDEFYZ" && toLowerCase(S) == "abcdefyz" &&
          |   replace("b", "B", S) == "aBcdefyz" && S < "b" && (S ++ "!") == "abcdefyz!" {
          |   def verdict: String = "reduced"
          |}
          |
          |def main uses Console: Unit = printLine(verdict["abcdefyz"])""".stripMargin
    ).asserting(_ shouldBe "reduced")
  }

  "the index operations" should "reduce on the compiler track inside an ability guard" in {
    compileAndRun(
      prelude +
        """
          |ability Sliced[S: String] {
          |   def verdict: String
          |}
          |
          |implement[S: String] Sliced[S] where
          |   drop(length(S), S) == "" && take(length(S), S) == S &&
          |   substring(length(S), length(S), S) == "" {
          |   def verdict: String = "sliced"
          |}
          |
          |def main uses Console: Unit = printLine(verdict["abcdefyz"])""".stripMargin
    ).asserting(_ shouldBe "sliced")
  }

  /** A guard is a decision, so the *other* implementation must be the one that survives when the same reduction comes
    * out `false` — otherwise a guard that merely failed to reduce would look identical to one that reduced to `true`.
    */
  "a compiler-track guard" should "select the other implementation when the reduction is false" in {
    compileAndRun(
      prelude +
        """
          |ability Route[S: String] {
          |   def handler: String
          |}
          |
          |implement[S: String] Route[S] where startsWith("/api", S) {
          |   def handler: String = "api"
          |}
          |
          |implement[S: String] Route[S] where !startsWith("/api", S) {
          |   def handler: String = "page"
          |}
          |
          |def main uses Console: Unit = printLine(handler["/api/users"] ++ "/" ++ handler["/about"])""".stripMargin
    ).asserting(_ shouldBe "api/page")
  }

  "the parsing operations" should "reduce on the compiler track inside an ability guard" in {
    compileAndRun(
      prelude +
        """
          |ability Numeral[S: String] {
          |   def verdict: String
          |}
          |
          |implement[S: String] Numeral[S] where isInteger(S) && before("=", S) == S && after("=", S) == "" {
          |   def verdict: String = "numeral"
          |}
          |
          |implement[S: String] Numeral[S] where !isInteger(S) {
          |   def verdict: String = "text"
          |}
          |
          |def main uses Console: Unit = printLine(verdict["42"] ++ "/" ++ verdict["v1"])""".stripMargin
    ).asserting(_ shouldBe "numeral/text")
  }

  /** The compiler track computing a *value*, not just a verdict: `S` is an erased type parameter, so the body cannot
    * survive to runtime as written — the operation runs while checking and the result is materialised as a literal.
    */
  it should "materialise a string computed from an erased type parameter" in {
    compileAndRun(
      prelude +
        """
          |ability Shout[S: String] {
          |   def shouted: String
          |}
          |
          |implement[S: String] Shout[S] {
          |   def shouted: String = toUpperCase(S)
          |}
          |
          |def main uses Console: Unit = printLine(shouted["quiet"])""".stripMargin
    ).asserting(_ shouldBe "QUIET")
  }
}
