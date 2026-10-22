package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of the **list domain** (`docs/meta.md` §17.1): a `List`'s meta-information is how many items it
  * has, its `size`, and the meta-information every one of its items has, its `item` — a `Reach`, `Unreached` for a list
  * that can have no item. `empty`, `append` and `prepend` state both, a fold hands its step each item's meta, and a
  * native producing a list states its items with `MetaOf[T](…)`.
  *
  * Each case is an accept/reject pair against the same `where`, as in [[DerivedMetaTransferIntegrationTest]]: the
  * accepting half also pins the output, the rejecting half that the bound is not merely unknown.
  */
class ListMetaIntegrationTest extends FullIntegrationTest {
  private val prelude =
    """|import eliot.effect.Console
       |import eliot.collection.List
       |import eliot.lang.Reach
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def useByte(x: Int) where withinByte(range(x)): Int = x
       |def nonEmpty(i: Interval[BigInteger]): Bool = rangeWithin[1, 2147483647](i)
       |""".stripMargin

  private def program(definitions: String, expression: String) =
    prelude + definitions + s"def main uses Console: Unit = printLine($expression)"

  // The step's element is every item's meta, so the last item read out of a fold is bounded by the items.
  private val lastOf = "def lastOf(list: List[Int]): Int = list.foldLeft(0, e -> acc -> e)\n"

  "a fold over a list" should "hand its step each item's meta" in {
    compileAndRun(program(lastOf, "show(useByte(lastOf(append(append(empty, 3), 7))))")).asserting(_ shouldBe "7")
  }

  it should "reject where an item does not fit" in {
    compileForErrors(program(lastOf, "show(useByte(lastOf(append(empty, 300))))"))
      .asserting(_ should include("The precondition of 'Test::useByte' is not satisfied"))
  }

  private val count = "def count(list: List[Int]) where nonEmpty(size(list)): Int = list.size\n"

  "a where over a list's size" should "accept a list with an item" in {
    compileAndRun(program(count, "show(count(prepend(empty, 1)))")).asserting(_ shouldBe "1")
  }

  it should "reject the empty list" in {
    compileForErrors(program(count, "show(count(empty))"))
      .asserting(_ should include("The precondition of 'Test::count' is not satisfied"))
  }

  // `item` is a `Reach`, so a `where` says what a list that can have no item means: `forAll` holds of it.
  private val allBytes = "def allBytes(list: List[Int]) where list.item.forAll(m -> withinByte(m.range)): Int = 0\n"

  "a where over every item of a list" should "accept items that fit" in {
    compileAndRun(program(allBytes, "show(allBytes(append(empty, 5)))")).asserting(_ shouldBe "0")
  }

  it should "hold of a list that can have no item" in {
    compileAndRun(program(allBytes, "show(allBytes(empty))")).asserting(_ shouldBe "0")
  }

  it should "reject an item that does not fit" in {
    compileForErrors(program(allBytes, "show(allBytes(append(empty, 300)))"))
      .asserting(_ should include("The precondition of 'Test::allBytes' is not satisfied"))
  }

  it should "not hold of an empty list's items when another conjunct does not" in {
    compileForErrors(
      program(
        "def check(list: List[Int]) where list.item.forAll(m -> withinByte(m.range)) && nonEmpty(size(list)): Int = 0\n",
        "show(check(empty))"
      )
    ).asserting(_ should include("The precondition of 'Test::check' is not satisfied"))
  }

  // `split` states its pieces with `MetaOf[String](…)`: none is longer than what was split.
  private val firstShort =
    "def fits(i: Interval[BigInteger]): Bool = rangeWithin[0, 5](i)\n" +
      "def firstShort(list: List[String]) where list.item.forAll(m -> fits(m.length)): String = list.head orElse \"\"\n"

  "a native producing a list of strings" should "state each item's size" in {
    compileAndRun(program(firstShort, "firstShort(\"ab cd\".split(\" \"))")).asserting(_ shouldBe "ab")
  }

  it should "reject where an item may not fit" in {
    compileForErrors(program(firstShort, "firstShort(\"abcdefg h\".split(\" \"))"))
      .asserting(_ should include("The precondition of 'Test::firstShort' is not satisfied"))
  }
}
