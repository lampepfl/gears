package gears.async

import language.experimental.captureChecking

/** The `CancellationException` that gears itself throws, and fails a cancelled [[Future]] with: a
  * `java.util.concurrent.CancellationException`, so whatever catches that one catches this.
  *
  * It has no stack trace unless they are asked for. A cancellation is not a failure to be traced to where it happened,
  * and one is made for every computation cancelled, where a stack trace is most of what an exception costs (tens to
  * hundreds of microseconds each on Scala Native). To see where cancellations come from while debugging, set the system
  * property `gears.debugCancellationTraces`, or the environment variable `GEARS_DEBUG_CANCELLATION_TRACES`, to `true`
  * before the program starts.
  */
class GearsCancellationException(message: String | Null = null)
    extends java.util.concurrent.CancellationException(message):
  /** Records the stack trace only if `GearsCancellationException.debugTraces`: otherwise the trace is empty. */
  override def fillInStackTrace(): Throwable =
    if GearsCancellationException.debugTraces then super.fillInStackTrace() else this

object GearsCancellationException:
  /** The system property that turns the stack traces on, when `true` (or `1`). */
  inline val TracesProperty = "gears.debugCancellationTraces"

  /** The environment variable read if the system property is not set. */
  inline val TracesVariable = "GEARS_DEBUG_CANCELLATION_TRACES"

  /** Whether a [[GearsCancellationException]] records its stack trace: as configured when the program started (see
    * `TracesProperty`), read once.
    */
  val debugTraces: Boolean =
    var text = System.getProperty(TracesProperty)
    if text == null then text = PlatformEnv.variable(TracesVariable)
    text != null && (text.trim.equalsIgnoreCase("true") || text.trim == "1")
