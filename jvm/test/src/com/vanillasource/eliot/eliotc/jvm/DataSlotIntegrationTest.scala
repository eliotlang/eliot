package com.vanillasource.eliot.eliotc.jvm

/** End-to-end proof of a **meta declared on a `data` type** (`docs/meta.md` §17.2, O4b): a program declares a domain of
  * its own (`Sign`, with its `Meta` instance), a `data` type declares a slot of it, and every constructor states the slot
  * from its fields' metas:
  *
  * {{{
  *   data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}
  * }}}
  *
  * The slot then travels as `range` does: a `where` reads it (`sign(t)`), a branch joins it through the program's own
  * `join`, and a loop widens it through its `widen`. It sits beside the structural meta (decision R), so a `match` arm
  * still sees its field's meta. Each case is an accept/reject pair against the same `where`, as in
  * [[DerivedMetaTransferIntegrationTest]].
  */
class DataSlotIntegrationTest extends FullIntegrationTest {
  private val prelude =
    """|import eliot.effect.Console
       |import eliot.compiler.Meta
       |data Sign = Positive | AnySign
       |implement Meta[Sign] {
       |   def join(a: Sign, b: Sign): Sign = a match {
       |      case Positive -> b
       |      case AnySign -> AnySign
       |   }
       |   def widen(previous: Sign, next: Sign): Sign = join(previous, next)
       |}
       |def isPositive(s: Sign): Bool = s match { case Positive -> true case AnySign -> false }
       |def signOf(i: Interval[BigInteger]): Sign = fold(rangeWithin[1, 1000000](i), Positive, AnySign)
       |def withinByte(i: Interval[BigInteger]): Bool = rangeWithin[0, 127](i)
       |def useByte(x: Int) where withinByte(range(x)): Int = x
       |data Temperature {sign: Sign}(degrees: Int) {signOf(range(degrees))}
       |def warmth(t: Temperature) where isPositive(sign(t)): Int = t match { case Temperature(d) -> d }
       |""".stripMargin

  private def program(definitions: String, expression: String) =
    prelude + definitions + s"def main uses Console: Unit = printLine(show($expression))"

  private def accepts(definitions: String, expression: String, output: String) =
    compileAndRun(program(definitions, expression)).asserting(_ shouldBe output)

  private def rejects(definitions: String, expression: String, callee: String = "warmth") =
    compileForErrors(program(definitions, expression))
      .asserting(_ should include(s"The precondition of 'Test::$callee' is not satisfied"))

  private def refuses(definitions: String, message: String) =
    compileForErrors(prelude + definitions + "def main uses Console: Unit = printLine(\"\")")
      .asserting(_ should include(message))

  "a slot a data type declares" should "be stated by its constructor and read by a where" in {
    accepts("", "warmth(Temperature(5))", "5")
  }

  it should "reject a construction whose statement does not satisfy the where" in {
    rejects("", "warmth(Temperature(0))")
  }

  private def pick(other: Int) = s"def pick(c: Bool): Temperature = fold(c, Temperature(5), Temperature($other))\n"

  "a slot joined at a branch" should "go through the program's own join" in {
    accepts(pick(9), "warmth(pick(true))", "5")
  }

  it should "reject when one branch's slot spoils the join" in {
    rejects(pick(0), "warmth(pick(true))")
  }

  // `eliot.collection.List` is named by its module: importing it beside `eliot.compiler.Meta` collides on `joined`.
  private val lastOf =
    "def lastOf(list: eliot.collection.List::List[Int]): Temperature =\n" +
      "   eliot.collection.List::foldLeft(Temperature(1), e -> acc -> Temperature(e), list)\n" +
      "def two(a: Int, b: Int): eliot.collection.List::List[Int] =\n" +
      "   eliot.collection.List::append(eliot.collection.List::append(eliot.collection.List::empty, a), b)\n"

  "a slot carried through a loop" should "go through the program's own widen" in {
    accepts(lastOf, "warmth(lastOf(two(3, 4)))", "4")
  }

  it should "reject when an iteration's slot spoils it" in {
    rejects(lastOf, "warmth(lastOf(two(0, 4)))")
  }

  // Decision R: the slot sits beside the structural meta, which a `match` arm still reads.
  private val degreesOf = "def degreesOf(t: Temperature): Int = t match { case Temperature(d) -> d }\n"

  "a slotted data value" should "still hand a match arm its field's meta" in {
    accepts(degreesOf, "useByte(degreesOf(Temperature(5)))", "5")
  }

  it should "still reject a field's meta that does not fit" in {
    rejects(degreesOf, "useByte(degreesOf(Temperature(200)))", "useByte")
  }

  private val room =
    "data Room {cozy: Sign}(temp: Temperature) {sign(temp)}\n" +
      "def visit(r: Room) where isPositive(cozy(r)): Int = 1\n"

  "a constructor's statement" should "read a slotted field through that field's own slot" in {
    accepts(room, "visit(Room(Temperature(5)))", "1")
  }

  it should "reject what the field's slot says" in {
    rejects(room, "visit(Room(Temperature(0)))", "visit")
  }

  private val shape =
    "data Shape {big: Sign} = Circle(radius: Int) {signOf(range(radius))} | Dot {AnySign}\n" +
      "def draw(s: Shape) where isPositive(big(s)): Int = 2\n"

  "each constructor of a sum" should "state the slot over its own fields" in {
    accepts(shape, "draw(Circle(3))", "2")
  }

  it should "reject the constructor whose statement does not satisfy the where" in {
    rejects(shape, "draw(Dot)", "draw")
  }

  private val box =
    "data Box[A] {full: Sign}(payload: A, count: Int) {signOf(range(count))}\n" +
      "def open[A](b: Box[A]) where isPositive(full(b)): Int = 3\n"

  "a generic data type" should "declare a slot of a closed type" in {
    accepts(box, "open(Box(\"x\", 2))", "3")
  }

  it should "reject what its statement says" in {
    rejects(box, "open(Box(\"x\", 0))", "open")
  }

  "a constructor of a slotted type" should "be refused when it states nothing" in {
    refuses("data Reading {level: Sign}(value: Int)\n", "The constructor 'Reading' does not state the slots of its type 'Reading'.")
  }

  it should "be refused when it states another number of slots" in {
    refuses(
      "data Reading {level: Sign}(value: Int) {AnySign, AnySign}\n",
      "The constructor 'Reading' states 2 slots, but its type 'Reading' declares 1 slot."
    )
  }

  "a constructor of a type declaring no slots" should "be refused when it states some" in {
    refuses("data Reading(value: Int) {AnySign}\n", "The constructor 'Reading' states slots its type 'Reading' does not declare.")
  }

  "a slot" should "be refused when named like a field of its type" in {
    refuses("data Reading {value: Sign}(value: Int) {AnySign}\n", "The slot 'value' is named like a field of 'Reading'.")
  }

  it should "be refused on a type that contains itself" in {
    refuses(
      "data Chain {level: Sign} = End {AnySign} | Link(next: Chain) {AnySign}\n",
      "The type 'Chain' contains itself (field 'next'), so it cannot declare slots yet."
    )
  }

  it should "be refused when it holds a type parameter's meta" in {
    refuses(
      "data Holder[A] {held: MetaOf[A]}(value: A) {value}\n",
      "The slot 'held' of a 'data' type cannot hold a type parameter's meta yet."
    )
  }
}
