package gears.async.io
package poller

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
  *
  * Not at once where the machine is a shared one (`FailsOnStarvation.budgetMillis`, a minute on CI): a worker's thread
  * there may be kept off its processor for longer than the pool's threshold by the machine itself, with nothing wrong
  * in the test. The report is then only printed, and the test is failed as above if it is still running when the budget
  * has passed, which only a test that is stuck is.
  *
  * Only where the platform's pool is one a test can be failed for (`PollerPlatform.failsOnStarvation`): on the JVM a
  * worker is kept for longer than the threshold by the JVM itself, loading and compiling what a test first runs, and
  * there a report is only printed.
  */
trait FailsOnStarvation extends ContractSuite {
  private val starved = new ConcurrentLinkedQueue[Starvation.Report]
  private val testsBegun = new AtomicInteger(0)
  private val testsEnded = new AtomicInteger(0)

  /** How many workers the pool of each of the suite's programs has: `Io.run`'s one for each processor. */
  protected def workers: Int = Runtime.getRuntime.availableProcessors

  /** As `Io.run` over the platform's pool backend, which the suites mixing this in all run over, on a pool that reports
    * its starved workers here.
    */
  override protected def running[T](body: Io[backend.Interface] ?=> Async.Spawn ?=> T): T =
    PollerPlatform.runOn(workers, onStarvation)(io ?=> body(using io.asInstanceOf[Io[backend.Interface]]))

  private val onStarvation: Starvation.Handler = report =>
    if !PollerPlatform.failsOnStarvation then Starvation.printWarning(report) else held(report)

  private def held(report: Starvation.Report): Unit = {
    val during = testsBegun.get()
    if testsEnded.get() < during then
      say(s"a worker was starved during test $during: ${report.message}")
      val budget = FailsOnStarvation.budgetMillis
      if budget <= 0 then failed(report, during)
      else
        val late = new Thread(() => {
          Thread.sleep(budget)
          if testsEnded.get() < during then
            say(s"test $during has not ended in the $budget ms since")
            failed(report, during)
        })
        late.setDaemon(true)
        late.start()
    else failed(report, during)
  }

  /** Fails the test that is running with `report`, ending what blocks the worker if an interrupt does, and the test
    * process if test `during`, which the report was made in, is still running `GraceMillis` later.
    */
  private def failed(report: Starvation.Report, during: Int): Unit = {
    starved.add(report): Unit
    report.worker.interrupt()
    if testsEnded.get() < during then
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

  /** The environment variable that gives `budgetMillis`, in milliseconds. */
  inline val BudgetVariable = "GEARS_IO_TEST_STARVATION_BUDGET_MILLIS"

  /** How long a test may go on after a worker of its was reported before it is failed for it: none unless configured
    * (`BudgetVariable`), which fails it at once, and a minute on CI (where `CI` is set, as GitHub Actions does), whose
    * machines keep a thread waiting for longer than the pool's threshold now and then.
    */
  val budgetMillis: Long = {
    val text = System.getenv(BudgetVariable)
    val onCI = System.getenv("CI") != null
    if text == null then if onCI then 60_000L else 0L
    else
      try text.trim.toLong
      catch
        case _: NumberFormatException => throw new IllegalArgumentException(s"$BudgetVariable is not a number: $text")
  }

  /** Where the reports are also written, under the build's `target`. */
  inline val Log = "target/starvation.log"
}
