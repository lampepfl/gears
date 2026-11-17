package gears.async.io.shareable

/** What a pool does about a worker that is kept from its tasks and its timers: it moves the tasks elsewhere if it can,
  * and reports the worker either way.
  *
  * A worker runs its tasks one at a time, and comes to the next when the one it runs waits (in `perform`, or an
  * `await`) or ends. A task that blocks its worker's thread, or computes on without yielding, keeps every other task of
  * that worker waiting, with its timers and its handles' events. That is a mistake in the program. So that it does not
  * hang the program where it need not, the pool gives the tasks waiting in that worker's inbox to a worker that is not
  * kept, for as long as the first is; the worker's timers and the events of the handles it listens for can't be given
  * away, and wait. And it tells whoever made the pool, through the handler the pool was given, which by default prints
  * a warning (`printWarning`), saying whether tasks were moved. Tests give their pools one that fails them.
  */
object Starvation {

  /** A worker kept from its tasks or its timers: its thread, for how long they had waited when it was noticed, and how
    * many tasks were then moved from its inbox to another worker, which is none if only its timers waited, if every
    * other worker was kept too, or if the pool has one worker.
    */
  final class Report(val worker: Thread, val keptNanos: Long, val movedTasks: Int = 0) {

    /** Whether tasks were moved to another worker, as those that come to this one will be while it is kept. */
    def relieved: Boolean = movedTasks > 0

    /** Where the worker's thread was when it was noticed, if the platform can say: empty otherwise. */
    val stack: Array[StackTraceElement] =
      try worker.getStackTrace()
      catch case _: Throwable => new Array[StackTraceElement](0)

    def message: String = {
      val text = new StringBuilder
      text.append(worker.getName()).append(" was blocked for ").append(keptNanos / 1_000_000L)
      text.append(" ms with work waiting. Use `io.ops.blocking` for blocking code, or `AsyncOperations.yield` in a")
      text.append(" long computation. ")
      if relieved then text.append("Waiting tasks were moved to another worker; its timers and handles still wait.")
      else text.append("Nothing could be moved to another worker.")
      stack.foreach(frame => text.append("\n\tat ").append(frame))
      text.toString
    }

    override def toString: String = message
  }

  /** The threshold unless configured: 100 ms. */
  inline val DefaultThresholdMillis = 100L

  /** The system property, and the environment variable read if it is not set, that give the threshold in milliseconds
    * as the program starts. Zero or less turns the reports off.
    */
  inline val ThresholdProperty = "gears.io.starvationThresholdMillis"
  inline val ThresholdVariable = "GEARS_IO_STARVATION_THRESHOLD_MILLIS"

  private def configuredMillis: Long = {
    var text = System.getProperty(ThresholdProperty)
    if text == null then text = System.getenv(ThresholdVariable)
    if text == null then DefaultThresholdMillis
    else
      try text.trim.toLong
      catch
        case _: NumberFormatException =>
          System.err.println(s"[gears] warning: not a number of milliseconds, ignored: $text")
          DefaultThresholdMillis
  }

  /** How long a worker may have tasks or due timers waiting, without beginning a turn, before it is reported: as
    * configured when the program started (see `ThresholdProperty`), for every pool. Zero or less turns the reports off,
    * and a pool then has no monitor.
    */
  val thresholdNanos: Long = {
    val millis = configuredMillis
    if millis > Long.MaxValue / 1_000_000L then Long.MaxValue else millis * 1_000_000L
  }

  /** What a pool gives its reports to, on its monitor's thread, once each time a worker is kept. */
  type Handler = Report => Unit

  /** The handler of a pool given no other: a warning on standard error. */
  val printWarning: Handler = report => System.err.println(s"[gears] warning: ${report.message}")
}
