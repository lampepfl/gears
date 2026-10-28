package gears.async.io
package internal

import gears.async.io.kqueue.KqueueBackend

import munit.FunSuite

/** Checks of the pool's parts under contention, each across several threads. */
class ReactorPoolSuite extends FunSuite {
  given ReactorBackend = KqueueBackend

  test("tasks queued for a worker in particular run on its thread, while another worker takes tasks") {
    val rounds = 500
    val outcome = InboxCheck.run(rounds)
    assertEquals(outcome.ran, rounds.toLong)
    assertEquals(outcome.elsewhere, 0L)
    assert(outcome.taken > 0, "no task was taken, so the check proves little")
  }

  test("a suspended future resumes on another thread with its state and that thread's signal state") {
    val outcome = MigrateCheck.run(futures = 4, resumes = 20_000)
    assertEquals(outcome.failures, 0L)
    assert(outcome.moved >= outcome.resumes / 2, s"only ${outcome.moved} of ${outcome.resumes} resumes moved threads")
  }
}
