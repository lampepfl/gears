package gears.async.io
package shareable

import java.util.concurrent.ConcurrentHashMap

/** The addresses host names resolved to, kept a while, so that a name looked up again soon is answered where the op is
  * performed, with no lookup and no thread to hand it to (see `AbstractResolveOp`). Only lookups that found addresses
  * are kept: a name that failed is looked up again each time.
  *
  * One cache for the process, as the system's resolver is one. An entry is as old as its lookup, and is not renewed by
  * being read.
  */
object ResolveCache {

  /** How long an entry is kept unless configured: 30 seconds, as the JDK keeps its own. */
  inline val DefaultSeconds = 30L

  /** The system property, and the environment variable read if it is not set, that give how long an entry is kept, in
    * seconds, as the program starts. Zero or less turns the cache off.
    */
  inline val SecondsProperty = "gears.io.resolveCacheSeconds"
  inline val SecondsVariable = "GEARS_IO_RESOLVE_CACHE_SECONDS"

  /** The most names kept: past it, the entries that have expired are dropped, and all of them if none has. */
  inline val MaxEntries = 4096

  private def configuredSeconds: Long = {
    var text = System.getProperty(SecondsProperty)
    if text == null then text = System.getenv(SecondsVariable)
    if text == null then DefaultSeconds
    else
      try text.trim.toLong
      catch
        case _: NumberFormatException =>
          System.err.println(s"[gears] warning: not a number of seconds, ignored: $text")
          DefaultSeconds
  }

  /** How long an entry is kept, as configured when the program started. Zero or less, and nothing is kept. */
  val keptNanos: Long = {
    val seconds = configuredSeconds
    if seconds > Long.MaxValue / 1_000_000_000L then Long.MaxValue else seconds * 1_000_000_000L
  }

  private final class Entry(val addresses: List[IpAddress], val lookedUp: Long)

  private val entries = new ConcurrentHashMap[String, Entry]()

  private def expired(entry: Entry, now: Long, kept: Long): Boolean = now - entry.lookedUp >= kept

  /** The addresses `host` resolved to, if it was looked up less than `kept` nanoseconds before `now`; else null. */
  def get(host: String, now: Long = System.nanoTime(), kept: Long = keptNanos): List[IpAddress] | Null =
    if kept <= 0 then null
    else
      val entry = entries.get(host)
      if entry == null then null
      else if expired(entry, now, kept) then
        entries.remove(host, entry): Unit
        null
      else entry.addresses

  /** `host` was just looked up, and has `addresses`. */
  def put(host: String, addresses: List[IpAddress], now: Long = System.nanoTime(), kept: Long = keptNanos): Unit =
    if kept > 0 then
      if entries.size() >= MaxEntries then
        entries.values().removeIf(entry => expired(entry, now, kept)): Unit
        if entries.size() >= MaxEntries then entries.clear()
      entries.put(host, new Entry(addresses, now)): Unit

  /** Forgets every name: for tests, and for a program that knows the names have changed. */
  def clear(): Unit = entries.clear()
}
