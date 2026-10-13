package com.vanillasource.eliot.eliotc.jvm

/** End-to-end coverage of `eliot.collection.List` — the immutable list represented as a native `java.util.List`. Its
  * `empty`/`append`/`foldLeft` operations are *generic* natives: emitted once, erased, and resolved from every
  * monomorphic call site by plain name + erased signature ([[NativeImplementation.genericNativeSignatures]],
  * [[ExpressionCodeGenerator]]). The cases exercise distinct element types (`Int`, `String`) to show the one erased
  * method set serves every instantiation, plus ordering, the empty list, and append's value semantics. `foreach` — the
  * stdlib-layer derived effectful consumer over `foldLeft` — is exercised through a `{Console}` caller.
  */
class ListIntegrationTest extends FullIntegrationTest {

  "a list of ints" should "build with append and reduce with foldLeft" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def numbers: List[Int] = append(append(empty, 42), 8)
        |
        |def total(list: List[Int]): Int = list.foldLeft(0, e -> acc -> add(e, acc))
        |
        |def main uses Console: Unit = printLine(show(total(numbers)))""".stripMargin
    ).asserting(_ shouldBe "50")
  }

  "a list of strings" should "build and fold at a different element type through the same erased natives" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def entries: List[String] = append(append(empty, "first"), "last")
        |
        |def lastOr(list: List[String], start: String): String = list.foldLeft(start, e -> acc -> e)
        |
        |def main uses Console: Unit = printLine(lastOr(entries, "none"))""".stripMargin
    ).asserting(_ shouldBe "last")
  }

  "foldLeft" should "visit elements front to back (append order)" in {
    // A non-commutative combine `acc*10 + e` turns the list into a decimal number, so the result reveals the visiting
    // order: append(append(append(empty, 1), 2), 3) folds to 123, not 321.
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 2), 3)
        |
        |def toNumber(list: List[Int]): Int = list.foldLeft(0, e -> acc -> add(multiply(acc, 10), e))
        |
        |def main uses Console: Unit = printLine(show(toNumber(digits)))""".stripMargin
    ).asserting(_ shouldBe "123")
  }

  "foldLeft over the empty list" should "return the initial value untouched" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def emptyInts: List[Int] = empty
        |
        |def sumFrom(list: List[Int], start: Int): Int = list.foldLeft(start, e -> acc -> add(e, acc))
        |
        |def main uses Console: Unit = printLine(show(sumFrom(emptyInts, 7)))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  "empty" should "produce a usable empty list, foldable and appendable" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def justOne: List[Int] = append(empty, 9)
        |
        |def sum(list: List[Int]): Int = list.foldLeft(0, e -> acc -> add(e, acc))
        |
        |def main uses Console: Unit = printLine(show(sum(justOne)))""".stripMargin
    ).asserting(_ shouldBe "9")
  }

  "a list" should "report its length via a counting foldLeft" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def items: List[String] = append(append(append(empty, "a"), "b"), "c")
        |
        |def count(list: List[String]): Int = list.foldLeft(0, e -> acc -> add(acc, 1))
        |
        |def main uses Console: Unit = printLine(show(count(items)))""".stripMargin
    ).asserting(_ shouldBe "3")
  }

  "foreach" should "run an effectful action per element, front to back, propagating its effects to the caller" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 2), 3)
        |
        |def printAll(list: List[Int]) uses Console: Unit = list.foreach(e -> printLine(show(e)))
        |
        |def main uses Console: Unit = printAll(digits)""".stripMargin
    ).asserting(_ shouldBe "1\n2\n3")
  }

  "prepend" should "put the element at the front, leaving its source list unchanged" in {
    // `base` is prepended to twice; if `prepend` mutated its source the second call would see the first's element.
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def base: List[String] = append(empty, "x")
        |
        |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
        |
        |def main uses Console: Unit = printLine(joinAll(prepend(base, "a")) ++ joinAll(prepend(base, "b")))""".stripMargin
    ).asserting(_ shouldBe "axbx")
  }

  "map" should "apply a pure function to every element, in order" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 2), 3)
        |
        |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
        |
        |def main uses Console: Unit = printLine(joinAll(digits.map(n -> show(n))))""".stripMargin
    ).asserting(_ shouldBe "123")
  }

  // The interleaving is the assertion: `map`'s effects run once per element, front to back, as the list is built —
  // not all at the end, and not once. This is the row-polymorphic callback shape (`RowChecker.parameterEnvironment`).
  "map" should "run an effectful function once per element, front to back, propagating its effects" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(empty, 1), 2)
        |
        |def announce(n: Int) uses Console: String = {
        |   printLine("at " ++ show(n))
        |   show(n)
        |}
        |
        |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
        |
        |def main uses Console: Unit = {
        |   val labels = digits.map(announce)
        |   printLine(joinAll(labels))
        |}""".stripMargin
    ).asserting(_ shouldBe "at 1\nat 2\n12")
  }

  "filter" should "keep exactly the satisfying elements, in their original order" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(append(empty, 3), 1), 4), 2)
        |
        |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
        |
        |def main uses Console: Unit = printLine(joinAll(digits.filter(e -> e > 2).map(n -> show(n))))""".stripMargin
    ).asserting(_ shouldBe "34")
  }

  "reverse" should "yield the elements back to front" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 2), 3)
        |
        |def joinAll(list: List[String]): String = list.foldLeft("", e -> acc -> acc ++ e)
        |
        |def main uses Console: Unit = printLine(joinAll(digits.reverse.map(n -> show(n))) ++ joinAll(empty.reverse))""".stripMargin
    ).asserting(_ shouldBe "321")
  }

  "isEmpty" should "distinguish the empty list from a populated one" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(empty, 1)
        |
        |def emptyInts: List[Int] = empty
        |
        |def main uses Console: Unit = printLine(fold(digits.isEmpty, "yes", "no") ++ fold(emptyInts.isEmpty, "yes", "no"))""".stripMargin
    ).asserting(_ shouldBe "noyes")
  }

  "find" should "return the first satisfying element, or none when there is none" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 4), 2)
        |
        |def firstOver(list: List[Int], limit: Int): String =
        |   list.find(e -> e > limit).foldOption("none", v -> show(v))
        |
        |def main uses Console: Unit = printLine(firstOver(digits, 1) ++ ":" ++ firstOver(digits, 9))""".stripMargin
    ).asserting(_ shouldBe "4:none")
  }

  // `find`'s predicate sits in `foldOption`'s suspended `ifNone` arm, so it stops being applied once something is
  // found: only the elements up to and including the match are probed, even though the fold walks the whole list.
  "find" should "stop applying an effectful predicate once a match is found" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 4), 2)
        |
        |def probe(n: Int, limit: Int) uses Console: Bool = {
        |   printLine("probing " ++ show(n))
        |   n > limit
        |}
        |
        |def main uses Console: Unit = {
        |   val found = digits.find(e -> probe(e, 1))
        |   printLine(found.foldOption("none", v -> show(v)))
        |}""".stripMargin
    ).asserting(_ shouldBe "probing 1\nprobing 4\n4")
  }

  // Groups come out in first-occurrence order and each group keeps its elements' original order — the property an
  // association list has and a hash table does not, and the reason `groupBy` returns one.
  "groupBy" should "group by key equality, in first-occurrence order" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def animals: List[String] =
        |   append(append(append(append(append(empty, "ant"), "bee"), "ape"), "cow"), "bat")
        |
        |def showGroup(group: Pair[String, List[String]]): String =
        |   group.foldPair(key -> members -> key ++ "=" ++ members.foldLeft("", e -> acc -> acc ++ e ++ ","))
        |
        |def main uses Console: Unit = animals.groupBy(w -> w.substring(0, 1)).foreach(g -> printLine(showGroup(g)))""".stripMargin
    ).asserting(_ shouldBe "a=ant,ape,\nb=bee,bat,\nc=cow,")
  }

  "groupBy over the empty list" should "produce no groups at all" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def noAnimals: List[String] = empty
        |
        |def main uses Console: Unit = printLine(fold(noAnimals.groupBy(w -> w).isEmpty, "no groups", "some groups"))""".stripMargin
    ).asserting(_ shouldBe "no groups")
  }

  // The key function is effect-transparent like every other callback here: its effects run once per element, front to
  // back, and the grouping is unaffected by them.
  "groupBy" should "run an effectful key function once per element, front to back" in {
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def digits: List[Int] = append(append(append(empty, 1), 2), 3)
        |
        |def parity(n: Int) uses Console: String = {
        |   printLine("keying " ++ show(n))
        |   fold(n > 2, "big", "small")
        |}
        |
        |def showGroup(group: Pair[String, List[Int]]): String =
        |   group.foldPair(key -> members -> key ++ "=" ++ members.foldLeft("", e -> acc -> acc ++ show(e)))
        |
        |def main uses Console: Unit = {
        |   val groups = digits.groupBy(parity)
        |   groups.foreach(g -> printLine(showGroup(g)))
        |}""".stripMargin
    ).asserting(_ shouldBe "keying 1\nkeying 2\nkeying 3\nsmall=12\nbig=3")
  }

  "append" should "not mutate its source list (value semantics)" in {
    // `two` and `three` both extend `base`. If `append` mutated its source instead of copying, the shared `base` would
    // accumulate both elements and the sums would differ; because it copies, sum(two)=1+2=3 and sum(three)=1+3=4, so 7.
    compileAndRun(
      """
        |import eliot.effect.Console
        |import eliot.collection.List
        |
        |def base: List[Int] = append(empty, 1)
        |def two: List[Int] = append(base, 2)
        |def three: List[Int] = append(base, 3)
        |
        |def sum(list: List[Int]): Int = list.foldLeft(0, e -> acc -> add(e, acc))
        |
        |def main uses Console: Unit = printLine(show(add(sum(two), sum(three))))""".stripMargin
    ).asserting(_ shouldBe "7")
  }

  private val sampleProgram =
    """
      |import eliot.effect.Console
      |import eliot.collection.List
      |
      |def sample: List[String] = append(append(append(empty, "beta"), "alpha"), "gamma")
      |
      |def table: List[Pair[String, String]] =
      |   append(append(empty, pair("alpha", "first")), pair("beta", "second"))
      |
      |def keyOfEntry(entry: Pair[String, String]): String = entry.foldPair(k -> _ -> k)
      |
      |def yn(flag: Bool): String = fold(flag, "y", "n")
      |""".stripMargin

  "size and singleton" should "count and build" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine(show(sample.size) ++ show(singleton("only").size) ++ show(empty.size))""".stripMargin
    ).asserting(_ shouldBe "310")
  }

  "head, last and at" should "read positions, answering none past the end" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine((sample.head orElse "-") ++ "|" ++ (sample.last orElse "-") ++ "|" ++
          |             (sample.at(1) orElse "-") ++ "|" ++ (sample.at(9) orElse "-") ++ "|" ++
          |             (empty.at(0) orElse "-"))""".stripMargin
    ).asserting(_ shouldBe "beta|gamma|alpha|-|-")
  }

  "tail, dropLast and replaceLast" should "take a list apart from either end" in {
    compileAndRun(
      sampleProgram +
        """
          |def noWords: List[String] = empty
          |
          |def main uses Console: Unit =
          |   printLine(sample.tail.joined(",") ++ "|" ++ sample.dropLast.joined(",") ++ "|" ++
          |             sample.replaceLast("omega").joined(",") ++ "|" ++ noWords.dropLast.joined(",") ++ "." ++
          |             noWords.replaceLast("x").joined(","))""".stripMargin
    ).asserting(_ shouldBe "alpha,gamma|beta,alpha|beta,alpha,omega|.")
  }

  "any and all" should "answer whether some or every element matches" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine(yn(sample.any(w -> w == "alpha")) ++ yn(sample.any(w -> w == "delta")) ++
          |             yn(sample.all(w -> w.contains("a"))) ++ yn(sample.all(w -> w != "beta")) ++
          |             yn(empty.any(w -> w == "x")) ++ yn(empty.all(w -> w == "x")))""".stripMargin
    ).asserting(_ shouldBe "ynynny")
  }

  "flatMap and flatten" should "run lists together end to end" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine(sample.flatMap(w -> append(singleton(w), w.take(1))).joined(",") ++ "|" ++
          |             append(append(empty, sample), singleton("delta")).flatten.joined(","))""".stripMargin
    ).asserting(_ shouldBe "beta,b,alpha,a,gamma,g|beta,alpha,gamma,delta")
  }

  "sorted" should "put the elements in ascending order and leave a sorted list alone" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine(sample.sorted.joined(",") ++ "|" ++ sample.sorted.sorted.joined(",") ++ "|" ++
          |             insert("beta", sample.sorted).joined(","))""".stripMargin
    ).asserting(_ shouldBe "alpha,beta,gamma|alpha,beta,gamma|alpha,beta,beta,gamma")
  }

  "joined" should "put the separator between elements only" in {
    compileAndRun(
      sampleProgram +
        """
          |def noWords: List[String] = empty
          |
          |def main uses Console: Unit =
          |   printLine(sample.joined(", ") ++ "|" ++ singleton("one").joined(", ") ++ "|" ++ noWords.joined(", ") ++ ".")""".stripMargin
    ).asserting(_ shouldBe "beta, alpha, gamma|one|.")
  }

  "an association list" should "be read with lookup and updated with put" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit =
          |   printLine((table.lookup("beta") else "-") ++ "|" ++ (table.lookup("delta") else "-") ++ "|" ++
          |             (table.lookupOption("alpha") orElse "-") ++ "|" ++
          |             put("gamma", "third", table).map(keyOfEntry).joined(",") ++ "|" ++
          |             (put("alpha", "changed", table).lookup("alpha") else "-"))""".stripMargin
    ).asserting(_ shouldBe "second|-|first|alpha,beta,gamma|changed")
  }

  "split" should "cut a runtime string at every literal separator" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit = {
          |   val line = readLine orElse ""
          |   printLine(line.split(" ").joined("|") ++ "/" ++ split("  ", line).joined("|") ++ "/" ++
          |             split(",", line).joined("|"))
          |}""".stripMargin,
      stdin = "a  b\n"
    ).asserting(_ shouldBe "a||b/a|b/a  b")
  }

  it should "yield the characters of a string when the separator is empty" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit = {
          |   val line = readLine orElse ""
          |   printLine(split("", line).joined("-") ++ "/" ++ split("", "").joined("-") ++ ".")
          |}""".stripMargin,
      stdin = "abc\n"
    ).asserting(_ shouldBe "a-b-c/.")
  }

  /** A character is a code point, so the empty separator cuts between code points — never between the two halves of
    * one. The count is the discriminator: this platform stores the emoji as two UTF-16 units, so cutting at storage
    * boundaries would answer four pieces instead of three.
    */
  it should "cut between code points, not between storage units" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit = {
          |   val line = readLine orElse ""
          |   val text = line ++ "😀b"
          |   printLine(show(split("", text).size) ++ "/" ++ show(split("", text).joined("").length))
          |}""".stripMargin,
      stdin = "a\n"
    ).asserting(_ shouldBe "3/3")
  }

  "words" should "drop every empty piece, whatever the whitespace" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit = {
          |   val line = readLine orElse ""
          |   printLine(line.words.joined("|") ++ "/" ++ words("   ").joined("|") ++ ".")
          |}""".stripMargin,
      stdin = "  dep \t github.com/x/foo   v1.3 \n"
    ).asserting(_ shouldBe "dep|github.com/x/foo|v1.3/.")
  }

  "split and joined" should "be inverses, empty pieces included" in {
    compileAndRun(
      sampleProgram +
        """
          |def main uses Console: Unit = {
          |   val line = readLine orElse ""
          |   printLine(line.split(",").joined(","))
          |}""".stripMargin,
      stdin = "a,,b,\n"
    ).asserting(_ shouldBe "a,,b,")
  }
}
