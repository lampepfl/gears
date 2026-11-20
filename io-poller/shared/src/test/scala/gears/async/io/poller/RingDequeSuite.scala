package gears.async.io
package poller

import gears.async.io.shareable.RingDeque

class RingDequeSuite extends munit.FunSuite {
  // Its elements are of a class of the suite's, not strings: an array of strings made by a function that is handed
  // over does not compile to valid WebAssembly on Scala.js 1.22.0.
  private final case class E(name: String)
  private def e(name: String): E = E(name)
  private def strings(queue: RingDeque[E]): List[String] = queue.toArray(new Array[E](_)).toList.map(_.name)
  private def take(queue: RingDeque[E]): String | Null = {
    val taken = queue.pollFirst()
    if taken == null then null else taken.name
  }

  test("what is added last is taken first to last") {
    val queue = new RingDeque[E]
    for i <- 0 until 5 do queue.addLast(e(s"e$i"))
    assertEquals(queue.size, 5)
    assertEquals(List.fill(5)(take(queue)), List("e0", "e1", "e2", "e3", "e4"))
    assert(queue.isEmpty)
    assertEquals(take(queue), null)
  }

  // What `java.util.ArrayDeque` gets wrong on Scala.js 1.22.0: its elements read back as null.
  test("a queue that grows after its head has been taken keeps what it holds, in order") {
    val queue = new RingDeque[E]
    queue.addLast(e("x"))
    assertEquals(take(queue), "x")
    for i <- 0 until 31 do queue.addLast(e(s"e$i"))
    assertEquals(queue.size, 31)
    assertEquals(strings(queue), List.tabulate(31)(i => s"e$i"))
    for i <- 0 until 31 do assertEquals(take(queue), s"e$i")
    assert(queue.isEmpty)
  }

  test("a queue grows again and again, wherever its head is") {
    val queue = new RingDeque[E]
    var next = 0
    var taken = 0
    for round <- 0 until 200 do {
      for _ <- 0 until 7 do { queue.addLast(e(s"e$next")); next += 1 }
      for _ <- 0 until 3 do { assertEquals(take(queue), s"e$taken"); taken += 1 }
    }
    assertEquals(queue.size, next - taken)
    while taken < next do { assertEquals(take(queue), s"e$taken"); taken += 1 }
  }

  test("what is added first is taken first, and the queue grows from that end too") {
    val queue = new RingDeque[E]
    for i <- 0 until 40 do queue.addFirst(e(s"e$i"))
    assertEquals(strings(queue), List.tabulate(40)(i => s"e${39 - i}"))
    queue.addLast(e("last"))
    queue.addFirst(e("first"))
    assertEquals(take(queue), "first")
    assertEquals(strings(queue).last, "last")
  }

  test("an element is removed from wherever it is, the others keeping their order") {
    val queue = new RingDeque[E]
    val elements = List.tabulate(20)(i => e(s"e$i"))
    // Round the end of the array: some taken first, so that the elements wrap.
    for i <- 0 until 10 do queue.addLast(e("gone"))
    for i <- 0 until 10 do take(queue)
    elements.foreach(queue.addLast)
    assert(queue.remove(elements(0)))
    assert(queue.remove(elements(19)))
    assert(queue.remove(elements(7)))
    assert(!queue.remove(elements(7)))
    assert(!queue.remove(e("e7")), "an equal element that is not the one")
    assertEquals(strings(queue), elements.map(_.name).filterNot(Set("e0", "e19", "e7")))
    assertEquals(queue.size, 17)
  }

  test("a cleared queue is empty and takes elements again") {
    val queue = new RingDeque[E]
    for i <- 0 until 20 do queue.addLast(e(s"e$i"))
    take(queue)
    queue.clear()
    assert(queue.isEmpty)
    assertEquals(strings(queue), Nil)
    for i <- 0 until 40 do queue.addLast(e(s"n$i"))
    assertEquals(strings(queue), List.tabulate(40)(i => s"n$i"))
  }
}
