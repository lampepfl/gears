package gears.async.io
package eventpolling

import gears.async.Async
import gears.async.io.shareable.Starvation
import gears.async.io.testkit.ContractSuite

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Failure

/** Fails a test during which a worker was kept from its tasks (see `Starvation`): one that blocked a worker's thread,
  * or computed on it, while tasks waited there.
  *
  * The worker's thread is interrupted, which ends a task that blocks it, on a lock or a sleep: its future fails as
  * cancelled, the test goes on to end, and fails with the report. A task that takes no notice, computing for ever,
  * can't be ended: the report is written to `FailsOnStarvation.Log`, and if the test is still running `GraceMillis`
  * later the test process is ended, failing the run rather than hanging it.
  */
trait FailsOnStarvation extends ContractSuite {
  private val starved = new ConcurrentLinkedQueue[Starvation.Report]
  private val testsBegun = new AtomicInteger(0)
  private val testsEnded = new AtomicInteger(0)

  /** How many workers the pool of each of the suite's programs has: `Io.run`'s one for each processor. */
  protected def workers: Int = Runtime.getRuntime.availableProcessors

  /** As `Io.run` over `EventPollingBackend`, which the suites mixing this in all run over, on a pool that reports its starved
    * workers here.
    */
  override protected def running[T](body: Io[backend.Interface] ?=> Async.Spawn ?=> T): T =
    EventPollingBackend.runOn(workers, onStarvation)(io ?=> body(using io.asInstanceOf[Io[backend.Interface]]))

  private val onStarvation: Starvation.Handler = report => {
    starved.add(report): Unit
    report.worker.interrupt()
    val during = testsBegun.get()
    if testsEnded.get() < during then
      say(s"a worker was starved during test $during: ${report.message}")
      val watchdog = new Thread(() => {
        Thread.sleep(FailsOnStarvation.GraceMillis)
        if testsEnded.get() < during then
          say(s"test $during has not ended since; ending the test process")
          System.exit(1)
      })
      watchdog.setDaemon(true)
      watchdog.start()
  }

  /** To `FailsOnStarvation.Log` as well as standard output: Scala Native's test runner forwards nothing printed from a
    * thread other than the test's, and the process may be about to end.
    */
  private def say(what: String): Unit = {
    val line = s"${getClass.getName}: $what"
    println(line)
    try
      java.nio.file.Files.write(
        java.nio.file.Paths.get(FailsOnStarvation.Log),
        (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      ): Unit
    catch case _: java.io.IOException => ()
  }

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "starvation",
      test =>
        test.withBody { () =>
          starved.clear()
          testsBegun.incrementAndGet()
          test
            .body()
            .transform { outcome =>
              testsEnded.set(testsBegun.get())
              val report = starved.poll()
              if report == null then outcome
              else Failure(new AssertionError(s"a worker was starved during the test: ${report.message}"))
            }(using munitExecutionContext)
        }
    )
}

object FailsOnStarvation {
  inline val GraceMillis = 5000L

  /** Where the reports are also written, under the build's `target`. */
  inline val Log = "target/starvation.log"
}
