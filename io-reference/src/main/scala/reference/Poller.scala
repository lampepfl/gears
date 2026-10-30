package gears.async.io
package reference

import gears.async.Cancellable

import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/** What an op performed on the reference backend waits with, on the thread performing it: parked until it is signalled,
  * by the poller, a close, a turn passed on or a moved deadline, or until its time limit, or until the caller's group
  * cancels it, which it is linked to while the op is performed.
  */
final class Wait extends Cancellable {
  private val thread = Thread.currentThread()
  @volatile private var signalled = false
  @volatile private var stopped = false

  /** Set once the op's `perform` has returned: the wait is then used no more. */
  @volatile var retired = false

  /** Whether the caller's group has cancelled the op. */
  def cancelled: Boolean = stopped

  /** Forgets earlier signals: called before whatever is to signal it is looked at, so no signal is lost. */
  def reset(): Unit = signalled = false

  /** Wakes the thread waiting, from any thread. */
  def signal(): Unit = {
    signalled = true
    LockSupport.unpark(thread)
  }

  def cancel(): Unit = {
    stopped = true
    LockSupport.unpark(thread)
  }

  /** Parks until signalled, cancelled, or `deadline` on `System.nanoTime`'s clock, `Long.MaxValue` for none. */
  def park(deadline: Long): Unit =
    if deadline == Long.MaxValue then while !signalled && !stopped do LockSupport.park(this)
    else {
      var left = deadline - System.nanoTime()
      while !signalled && !stopped && left > 0 do {
        LockSupport.parkNanos(this, left)
        left = deadline - System.nanoTime()
      }
    }

  /** Parks until signalled, whether cancelled or not: for what must finish once begun. */
  def parkUntilSignalled(): Unit = while !signalled do LockSupport.park(this)
}

/** Ops taking turns on a handle, first come first served: a datagram socket's receives, or its sends, or a listener's
  * accepts. The op at the head holds the turn; the others wait for it to be passed on.
  */
final class Turns {
  private val queue = new java.util.ArrayDeque[Wait]()

  /** Waits for `ctx`'s turn, failing as `ctx.stopped` says while it waits, out of the queue. */
  def take(ctx: Ctx, handle: RefHandle, op: AnyRef)(using gears.async.Async): Unit = {
    val wait = ctx.waiter
    synchronized(queue.addLast(wait))
    try
      while {
        wait.reset()
        !synchronized(queue.peekFirst() eq wait)
      } do {
        ctx.park()
        handle.checkOpen()
        ctx.checkStop(op)
      }
    catch {
      case e: Throwable =>
        release(wait)
        throw e
    }
  }

  /** Gives up `wait`'s turn, or its place in the queue, waking the op whose turn it is then. */
  def release(wait: Wait): Unit = {
    val next = synchronized {
      val head = queue.peekFirst() eq wait
      queue.remove(wait)
      if head then queue.peekFirst() else null
    }
    if next != null then next.signal()
  }
}

/** The reference backend's selector poll: one daemon thread selecting on one selector. An op that would block registers
  * its channel and the readiness it needs (`register`), and parks; once the channel is ready, the poller wakes it, and
  * it tries again. The poller also closes the channels it has registered (`close`), so that a channel's descriptor is
  * gone, and its port free, when the close returns.
  */
private[reference] object Poller {
  private val selector = Selector.open()
  private val requests = new ConcurrentLinkedQueue[Request]()

  private sealed trait Request
  private final case class Register(channel: SelectableChannel, ops: Int, waiter: Wait) extends Request
  private final case class Close(channel: SelectableChannel, waiter: Wait) extends Request

  /** The waits registered on a channel, with the readiness each needs. */
  private final class Waiting {
    var waits: List[(Int, Wait)] = Nil
    def interest: Int = waits.foldLeft(0)(_ | _._1)
  }

  /** Wakes `wait` once `channel` is ready for `ops` (or has been closed). */
  def register(channel: SelectableChannel, ops: Int, wait: Wait): Unit = {
    requests.add(Register(channel, ops, wait))
    selector.wakeup(): Unit
  }

  /** Closes `channel` on the poller's thread, taking it off the selector first, and returns once it is closed. */
  def close(channel: SelectableChannel, wait: Wait): Unit = {
    wait.reset()
    requests.add(Close(channel, wait))
    selector.wakeup()
    wait.parkUntilSignalled()
  }

  private val thread = {
    val t = new Thread(() => loop(), "gears-io-reference-poller")
    t.setDaemon(true)
    t.start()
    t
  }

  private def loop(): Unit =
    while true do {
      selector.select()
      var request = requests.poll()
      while request != null do {
        handle(request)
        request = requests.poll()
      }
      val ready = selector.selectedKeys().iterator()
      while ready.hasNext() do {
        val key = ready.next()
        ready.remove()
        wake(key)
      }
    }

  private def handle(request: Request): Unit = request match {
    case Register(channel, ops, wait) =>
      try {
        val key = channel.keyFor(selector)
        val waiting = if key == null then new Waiting else key.attachment().asInstanceOf[Waiting]
        // Waits whose op has returned, by a cancel or a time limit, are dropped as others come.
        waiting.waits = (ops, wait) :: waiting.waits.filter((_, w) => !w.retired && (w ne wait))
        if key == null then channel.register(selector, waiting.interest, waiting): Unit
        else key.interestOps(waiting.interest): Unit
      } catch {
        case _: Exception => wait.signal() // closed, say: the op finds out as it tries again
      }
    case Close(channel, wait) =>
      try {
        val key = channel.keyFor(selector)
        if key != null then {
          key.cancel()
          selector.selectNow(): Unit // deregisters it, so that closing closes its descriptor now
        }
        channel.close()
      } finally wait.signal()
  }

  private def wake(key: SelectionKey): Unit = {
    val waiting = key.attachment().asInstanceOf[Waiting]
    try {
      val ready = key.readyOps()
      val (woken, left) = waiting.waits.partition((ops, _) => (ops & ready) != 0)
      waiting.waits = left
      key.interestOps(waiting.interest)
      woken.foreach(_._2.signal())
    } catch {
      case _: CancelledKeyException =>
        waiting.waits.foreach(_._2.signal())
        waiting.waits = Nil
    }
  }
}
