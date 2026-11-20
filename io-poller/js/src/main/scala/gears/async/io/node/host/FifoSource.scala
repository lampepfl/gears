package gears.async.io.node.host

import gears.async.io.node.base.DoAfter
import gears.async.io.node.base.NodeSource
import gears.async.io.node.internal.Fs

import java.io.IOException
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** A FIFO's read end as a source, read by its descriptor with `fs.read`, which on a descriptor that does not block
  * returns at once with what the system has: bytes; nothing, the end, when no writer has the FIFO open; or that it
  * would block, when one has and nothing is written. So a read finds the end where the interface says it is, before the
  * first writer and after each, and goes on to the next writer's bytes.
  *
  * What Node does not give is word of when a FIFO that would block can be read: its streams, which wait for that, never
  * report a FIFO's end when its writer closes (probed on macOS, Node 26). While an op waits, the source therefore looks
  * again, after a millisecond at first and less often as it goes on waiting, up to `SlowestLook`: the one place this
  * backend polls. A look is a call of Node's that runs to its end, so what it finds after its read was cancelled stays
  * for the next read, no byte lost, and a close waits for it.
  */
private[node] final class FifoSource(fd: Int) extends ByteSource {
  private def timers = NodeSource
  import FifoSource.*

  private var found: Uint8Array | Null = null
  private var atEnd = false
  private var failed: IOException | Null = null
  private var inFlight = false
  private var wanted = false
  private var pause = 1
  private var again: DoAfter | Null = null
  private var closing: (() => Unit) | Null = null
  private var spare: Uint8Array | Null = null // a look's buffer that it found nothing for, for the next look

  def read(max: Int): Uint8Array | Null = {
    val all = found
    if all != null then
      if all.length <= max then {
        found = null
        all
      } else {
        found = all.subarray(max)
        all.subarray(0, max)
      }
    else if failed != null then throw failed.nn
    else if atEnd then {
      atEnd = false // the end of these writers: the next read is of whoever writes next
      ByteSource.Ended
    } else null
  }

  override def listening(on: Boolean): Unit = {
    wanted = on
    if on then { if !inFlight && again == null then look() }
    else {
      forget()
      pause = 1
    }
  }

  private def forget(): Unit = {
    val waiting = again
    again = null
    if waiting != null then {
      waiting.cancel()
      timers.removeTimer(waiting): Unit
    }
  }

  private def tell(): Unit = {
    val readable = onReadable
    if readable != null then readable()
  }

  private val looked: js.Function2[js.Dynamic, js.Dynamic, Unit] = (failure, read) => {
    inFlight = false
    val chunk = spare.nn
    val close = closing
    if close != null then closeNow(close)
    else if NodeErr.failed(failure) then
      if failure.code.asInstanceOf[Any] == "EAGAIN" then {
        if wanted then {
          val next = new DoAfter(System.nanoTime() + pause * 1_000_000L, lookAgain)
          again = next
          timers.addTimer(next)
          pause = math.min(pause * 2, SlowestLook)
        }
      } else {
        failed = NodeErr.io(failure)
        tell()
      }
    else {
      val count = read.asInstanceOf[Int]
      pause = 1
      if count > 0 then {
        found = chunk.subarray(0, count)
        spare = null
      } else atEnd = wanted // an end no read waits for is not kept: the next read looks for itself
      tell()
    }
  }

  private val lookAgain: () => Unit = () => {
    again = null
    if wanted && closing == null then look()
  }

  private def look(): Unit = {
    if spare == null then spare = new Uint8Array(Chunk)
    inFlight = true
    try Fs.read(fd, spare.nn, 0, Chunk, null, looked)
    catch {
      case js.JavaScriptException(failure) =>
        inFlight = false
        failed = NodeErr.io(failure)
        tell()
    }
  }

  private def closeNow(done: () => Unit): Unit = {
    val closed: js.Function1[js.Dynamic, Unit] = _ => done()
    Fs.close(fd, closed)
  }

  /** Stops looking, and closes the descriptor once no look is under way. */
  def close(done: () => Unit): Boolean = {
    wanted = false
    forget()
    if inFlight then closing = done else closeNow(done)
    false
  }
}

private[node] object FifoSource {

  /** The longest a waiting read goes between looks, in milliseconds. */
  inline val SlowestLook = 16

  /** The most one look reads. */
  inline val Chunk = 65536

  /** The flags a FIFO, or a file, opens with: for reading or for writing, and never blocking. */
  def flags(write: Boolean): Int = {
    val constants = Fs.constants
    (if write then constants.O_WRONLY else constants.O_RDONLY).asInstanceOf[Int] |
      constants.O_NONBLOCK.asInstanceOf[Int]
  }
}
