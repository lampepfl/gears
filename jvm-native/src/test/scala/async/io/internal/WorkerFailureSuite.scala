package gears.async.io
package internal

import munit.FunSuite

/** A reactor turn that throws does not end its worker: one that throws now and then is reported and the worker goes on,
  * and one that throws every turn fails the pool, which stops, its run throwing the reactor's error.
  */
class WorkerFailureSuite extends FunSuite {

  /** A reactor whose turns throw while `throwing` says so of the turn's number, counting from 1. */
  private final class FailingReactor(throwing: Int => Boolean) extends Reactor {
    type Op = AnyRef
    type Handle = AnyRef
    @volatile var turns = 0
    def ops: Ops[Op, Handle] = throw new UnsupportedOperationException
    def submit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Unit = ()
    def trySubmit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Boolean = false
    def cancel(op: Op): Boolean = false
    def attachment(op: Op): AnyRef | Null = null
    def attach(op: Op, value: AnyRef | Null): Unit = ()
    def runOnce(block: Boolean): Unit = {
      turns += 1
      if throwing(turns) then throw new IllegalStateException(s"turn $turns failed")
    }
    def wakeup(): Unit = ()
    def run(): Unit = ()
    def stop(): Unit = ()
    def close(): Unit = ()
  }

  private def backendOf(reactor: FailingReactor): ReactorBackend = new ReactorBackend {
    type Op = AnyRef
    type Handle = AnyRef
    type Readable = AnyRef
    type Writable = AnyRef
    type Connection = AnyRef
    type Listener = AnyRef
    type Datagram = AnyRef
    type UnixDatagram = AnyRef
    type File = AnyRef
    def ops: Kinded[Ops[Op, Handle]] = throw new UnsupportedOperationException
    def attachment(op: Op): AnyRef | Null = null
    def attach(op: Op, value: AnyRef | Null): Unit = ()
    def open(): Reactor.Of[Op, Handle] = reactor
    def pool(bufferSize: Int, buffers: Int): BufferPool = throw new UnsupportedOperationException
  }

  test("a turn that throws is reported, and the worker goes on running its tasks and turns") {
    val reactor = new FailingReactor(_ == 2)
    var ran = 0
    ReactorPool.run(backendOf(reactor), 1, TakePolicy.Affine) { pool =>
      // A task that queues itself again, so the worker alternates it with turns, until the turns are past the failure.
      lazy val task: Runnable = () => {
        ran += 1
        if reactor.turns < 5 then pool.execute(task) else pool.stop()
      }
      pool.execute(task)
      pool.runFirstWorker()
    }
    assert(reactor.turns >= 5, reactor.turns)
    assert(ran >= 5, ran)
  }

  test("a reactor that throws every turn fails the pool, whose run throws its error") {
    val reactor = new FailingReactor(_ => true)
    val thrown = intercept[IllegalStateException] {
      ReactorPool.run(backendOf(reactor), 1, TakePolicy.Affine)(_.runFirstWorker())
    }
    assertEquals(reactor.turns, ReactorWorker.BrokenTurns)
    assertEquals(thrown.getMessage, s"turn ${ReactorWorker.BrokenTurns} failed")
  }
}
