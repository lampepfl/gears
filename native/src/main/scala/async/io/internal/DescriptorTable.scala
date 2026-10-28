package gears.async.io
package internal

import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** What every reactor in the process can see about a descriptor, for reactors that share descriptors, each on its own
  * thread: how many times its number has been closed, so a reactor can tell a reused number from the descriptor it
  * knew, and which reactor last registered a read filter for it, so one that registered one earlier can tell it should
  * drop its own. Descriptors are the process's, so the table is too.
  *
  * Why it is needed: a kqueue reactor keeps what it knows of each descriptor it has used, notably whether its read
  * filter is registered, which it registers once and keeps. When tasks move between reactors, a descriptor may be read
  * on one reactor, then on another, and closed on a third. A reactor told of a close only later (`KqueueHandles.close`
  * queues it) would meanwhile still believe its filters registered after the kernel had removed them, and a read of a
  * reused number would wait for ever; and a reactor that a descriptor has moved away from would still wake for its
  * data.
  *
  * The generation is a well-worn answer to the first problem, the ABA problem as it appears for descriptors: the same
  * number names a different file once it has been closed and reopened. Event loops that index their state by descriptor
  * all meet it. libev's epoll backend keeps a generation per descriptor, since epoll silently drops closed descriptors;
  * Go's netpoller tags what it registers with a per-descriptor sequence number so that events for a closed and reused
  * descriptor are recognised as stale; Tokio's I/O driver packs a generation into its registration tokens for the same
  * reason. It is the generational index of slot maps and generational arenas: an index paired with a generation,
  * checked on each use. The read owner, for the second problem, has less of a precedent, since those runtimes either
  * poll every descriptor from one place or never move descriptors between threads; dropping a filter lazily, when it
  * next reports an event that turns out to be someone else's, follows the usual event-loop practice of treating events
  * as hints to check rather than facts to keep in step.
  *
  * Each descriptor has one `Long`: its generation, counting closes, in the high 32 bits; whether reads of it must
  * always be tried (`markAlwaysTry`) in bit 31; and its read owner, the id of a reactor, in the low 31 bits, 0 for
  * none; packed so that one compare-and-swap updates them all. Pages of descriptors are allocated as numbers are first
  * used.
  */
object DescriptorTable {
  private inline val PageBits = 10
  private inline val PageSize = 1 << PageBits
  private inline val Pages = 4096 // so descriptors below 4 million

  private val pages = new AtomicReferenceArray[AtomicLongArray](Pages)

  private def page(fd: Int): AtomicLongArray = {
    val index = fd >>> PageBits
    require(index < Pages, s"Descriptor $fd is beyond the descriptor table")
    val existing = pages.get(index)
    if existing != null then existing
    else {
      val fresh = new AtomicLongArray(PageSize)
      if pages.compareAndSet(index, null, fresh) then fresh else pages.get(index)
    }
  }

  // A note per descriptor, a reference, for what the backend keeps about a descriptor while it is open
  // (`KqueueHandles`): kept here rather than in a map keyed by number, which would box the number on every access.
  private val notePages = new AtomicReferenceArray[AtomicReferenceArray[AnyRef | Null]](Pages)

  private def notePage(fd: Int): AtomicReferenceArray[AnyRef | Null] = {
    val index = fd >>> PageBits
    require(index < Pages, s"Descriptor $fd is beyond the descriptor table")
    val existing = notePages.get(index)
    if existing != null then existing
    else {
      val fresh = new AtomicReferenceArray[AnyRef | Null](PageSize)
      if notePages.compareAndSet(index, null, fresh) then fresh else notePages.get(index).nn
    }
  }

  /** `fd`'s note, or null if none has been set since it was opened. */
  def note(fd: Int): AnyRef | Null = notePage(fd).get(fd & (PageSize - 1))

  /** Sets `fd`'s note; `closing` does not clear it, so the closer must. */
  def setNote(fd: Int, value: AnyRef | Null): Unit = notePage(fd).set(fd & (PageSize - 1), value)

  /** `fd`'s entry: see `generation` and `readOwner`. */
  def get(fd: Int): Long = page(fd).get(fd & (PageSize - 1))

  inline def generation(entry: Long): Int = (entry >>> 32).toInt

  inline def readOwner(entry: Long): Int = (entry & OwnerMask).toInt

  /** Whether reads of the descriptor must always be tried, rather than wait for its read filter after a short read. */
  inline def alwaysTry(entry: Long): Boolean = (entry & AlwaysTry) != 0L

  private inline val AlwaysTry = 0x80000000L
  private inline val OwnerMask = 0x7fffffffL

  /** Records that reactor `id` holds `fd`'s read filter from now on. */
  def claimRead(fd: Int, id: Int): Unit = {
    require(id > 0 && id <= OwnerMask, s"Not a reactor id: $id")
    val entries = page(fd)
    val i = fd & (PageSize - 1)
    var entry = entries.get(i)
    while !entries.compareAndSet(i, entry, (entry & ~OwnerMask) | id.toLong) do entry = entries.get(i)
  }

  /** Records that reads of `fd`, until it is closed, must always be tried: it is a regular file or a FIFO, whose read
    * filter may never report the end of the file once a read has come back short.
    */
  def markAlwaysTry(fd: Int): Unit = {
    val entries = page(fd)
    val i = fd & (PageSize - 1)
    var entry = entries.get(i)
    while !entries.compareAndSet(i, entry, entry | AlwaysTry) do entry = entries.get(i)
  }

  /** Records that `fd` is being closed, before its number can be reused: a new generation, with no read owner
    * (`KqueueHandles.close`).
    */
  def closing(fd: Int): Unit = {
    val entries = page(fd)
    val i = fd & (PageSize - 1)
    var entry = entries.get(i)
    while !entries.compareAndSet(i, entry, (generation(entry) + 1).toLong << 32) do entry = entries.get(i)
  }
}
