package gears.async.io.internal

import java.util.ArrayDeque

/** Processes a fixed prefix of a thread-confined queue. Work appended by a consumer waits for a later batch, so a
  * self-replenishing queue cannot monopolize the reactor. Consumers must leave the rest of the prefix in the queue.
  * Inlined at the call site: no wrapper, iterator or consumer object is needed.
  */
private[io] object BoundedDrain {
  inline def snapshot[A](queue: ArrayDeque[A])(inline consume: A => Unit): Boolean = {
    val count = queue.size()
    val any = count > 0
    prefix(queue, count)(consume)
    any
  }

  /** `count` may have been captured earlier in the turn, as for close completions. */
  inline def prefix[A](queue: ArrayDeque[A], count: Int)(inline consume: A => Unit): Unit = {
    var remaining = count
    while remaining > 0 do {
      val next = queue.poll()
      remaining -= 1
      consume(next)
    }
  }
}
