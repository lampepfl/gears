import gears.async.{Cancellable, CompletionGroup}

class CompletionGroupIdentitySuite extends munit.FunSuite {
  private case class Member(var value: Int) extends Cancellable {
    var cancellations = 0
    def cancel(): Unit = cancellations += 1
  }

  test("a spill member unlinks after its hash changes") {
    val group = new CompletionGroup
    val first = Member(1).link(group)
    val paired = Member(2).link(group)
    val changing = Member(3).link(group)
    changing.value = 4
    changing.unlink()
    group.cancel()
    assertEquals(first.cancellations, 1)
    assertEquals(changing.cancellations, 0)
    assertEquals(paired.cancellations, 1)
  }

  test("equal but distinct members are cancelled independently") {
    val group = new CompletionGroup
    val first = Member(1).link(group)
    val second = Member(1).link(group)
    group.cancel()
    assertEquals(first.cancellations, 1)
    assertEquals(second.cancellations, 1)
  }

  test("unlinking an equal member does not remove another member") {
    val group = new CompletionGroup
    val first = Member(1).link(group)
    val second = Member(1).link(group)
    second.unlink()
    group.cancel()
    assertEquals(first.cancellations, 1)
    assertEquals(second.cancellations, 0)
  }

  test("registering the same member twice still cancels it once") {
    val group = new CompletionGroup
    val first = Member(1).link(group)
    val second = Member(2).link(group)
    group.add(second)
    first.unlink() // Leave the single-member slot empty while second is still in the spill map.
    group.add(second)
    group.cancel()
    assertEquals(first.cancellations, 0)
    assertEquals(second.cancellations, 1)
  }
  test("a vacant single slot does not duplicate a remaining spill member") {
    val group = new CompletionGroup
    val first = Member(1).link(group)
    val second = Member(2).link(group)
    val spill = Member(3).link(group)
    first.unlink()
    second.unlink()
    group.add(spill)
    group.cancel()
    assertEquals(spill.cancellations, 1)
    spill.unlink()
  }
}
