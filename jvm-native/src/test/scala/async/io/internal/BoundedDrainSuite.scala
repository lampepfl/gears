package gears.async.io.internal

import java.util.ArrayDeque

class BoundedDrainSuite extends munit.FunSuite {
  test("a batch defers appended work and retries while preserving their order") {
    val queue = new ArrayDeque[String]()
    queue.add("first")
    queue.add("second")
    val seen = scala.collection.mutable.ArrayBuffer.empty[String]
    assert(BoundedDrain.snapshot(queue) { item =>
      seen += item
      queue.add(item) // retry
      queue.add(s"new-$item")
    })
    assertEquals(seen.toList, List("first", "second"))
    BoundedDrain.snapshot(queue)(item => seen += item)
    assertEquals(seen.toList, List("first", "second", "first", "new-first", "second", "new-second"))
    assert(!BoundedDrain.snapshot(queue)(_ => fail("an empty batch invoked its consumer")))
  }

  test("an earlier prefix leaves work added later in the turn for the next batch") {
    val queue = new ArrayDeque[String]()
    queue.add("previous-turn")
    val due = queue.size()
    queue.add("this-turn")
    val seen = scala.collection.mutable.ArrayBuffer.empty[String]
    BoundedDrain.prefix(queue, due) { item =>
      seen += item
      queue.add("during-completion")
    }
    assertEquals(seen.toList, List("previous-turn"))
    BoundedDrain.snapshot(queue)(item => seen += item)
    assertEquals(seen.toList, List("previous-turn", "this-turn", "during-completion"))
  }

  test("a throwing consumer propagates its failure without consuming the remaining work") {
    val queue = new ArrayDeque[String]()
    queue.add("first")
    queue.add("second")
    val failure = new IllegalStateException("consumer failed")
    val observed = intercept[IllegalStateException] {
      BoundedDrain.snapshot(queue) { _ =>
        queue.add("later")
        throw failure
      }
    }
    assert(observed eq failure)
    assertEquals(queue.poll(), "second")
    assertEquals(queue.poll(), "later")
    assert(queue.isEmpty)
  }
}
