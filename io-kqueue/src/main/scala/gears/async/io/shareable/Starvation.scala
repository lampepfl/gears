package gears.async.io.shareable

/** What a pool does about a worker that is kept from its tasks and its timers: it reports it, and nothing more.
  *
  * A worker runs its tasks one at a time, and comes to the next when the one it runs waits (in `perform`, or an
  * `await`) or ends. A task that blocks its worker's thread, or computes on without yielding, keeps every other task of
  * that worker waiting, with its timers and its handles' events. That is a mistake in the program, which the pool does
  * not paper over by moving the tasks elsewhere: it tells whoever made the pool, through the handler the pool was
  * given, which by default prints a warning (`printWarning`). Tests give their pools one that fails them.
  */
object Starvation {

  /** A worker kept from its tasks or its timers: its thread, and for how long they had waited when it was noticed. */
  final class Report(val worker: Thread, val keptNanos: Long) {

    /** Where the worker's thread was when it was noticed, if the platform can say: empty otherwise. */
    val stack: Array[StackTraceElement] =
      try worker.getStackTrace()
      catch case _: Throwable => new Array[StackTraceElement](0)

    def message: String = {
      val text = new StringBuilder
      text.append(worker.getName())
      text.append(" has had tasks or due timers waiting for ").append(keptNanos / 1_000_000L)
      text.append(" ms without coming to them: what it is running blocks its thread, or computes without yielding. ")
      text.append("Code that blocks belongs in a blocking op (`io.ops.blocking`), which runs it off the workers; ")
      text.append("a long computation should yield now and then (`AsyncOperations.yield`).")
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
