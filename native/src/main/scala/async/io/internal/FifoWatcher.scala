package gears.async.io
package internal

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.scalanative.libc.errno
import scala.scalanative.posix.errno.EBADF
import scala.scalanative.posix.errno.EINTR
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Watches descriptors that kqueue cannot wait on, with `select`, on a thread of its own, started when the first is
  * watched: on macOS a FIFO whose writer closes is reported to neither kqueue nor `poll`, though `select` reports it
  * readable (as libuv's `uv__stream_try_select` works around it), and a regular file at its end never fires
  * `EVFILT_READ`, where `select` reports it readable always. A reactor with a read waiting on such a descriptor watches
  * it as well as registering its filter, and is told once `select` reports it readable, whichever comes first; a watch
  * is one-shot, each owner's replacing its last. A descriptor is forgotten before it is closed, so that the number is
  * not watched once reused.
  */
private[io] object FifoWatcher {

  // Guarded by `lock`: the watches, by descriptor and then by owner, and the thread's wake-up pipe once started.
  private val lock = new Object
  private val watches = mutable.HashMap.empty[Int, mutable.HashMap[AnyRef, () => Unit]]
  private var wakeRead = -1
  private var wakeWrite = -1
  private val started = new AtomicBoolean(false)

  /** Tells `owner`, by `onReadable` on the watcher's thread, once `fd` is readable as `select` reports it, replacing
    * `owner`'s watch of it if any. `onReadable` must not block: it hands the news to its reactor.
    */
  def watch(fd: Int, owner: AnyRef, onReadable: () => Unit): Unit = {
    if !started.get() then start()
    lock.synchronized {
      watches.getOrElseUpdate(fd, mutable.HashMap.empty).update(owner, onReadable)
      poke()
    }
  }

  /** Stops watching `fd`, before it is closed. Nothing is done if the watcher never started. */
  def forget(fd: Int): Unit =
    if started.get() then
      lock.synchronized {
        if watches.remove(fd).isDefined then poke()
      }

  private def start(): Unit = lock.synchronized {
    if !started.get() then {
      val ends = stackalloc[CInt](2)
      if unistd.pipe(ends) != 0 then throw new java.io.IOException(s"Failed to open the FIFO watcher's pipe: $errno")
      wakeRead = ends(0)
      wakeWrite = ends(1)
      for fd <- List(wakeRead, wakeWrite) do fcntl.fcntl(fd, fcntl.F_SETFL, fcntl.O_NONBLOCK): Unit
      val thread = new Thread(() => loop(), "gears-fifo-watcher")
      thread.setDaemon(true)
      thread.start()
      started.set(true)
    }
  }

  /** Wakes the thread to take the watches again. Under the lock; a full pipe already has it woken. */
  private def poke(): Unit = {
    val byte = stackalloc[Byte]()
    unistd.write(wakeWrite, byte, 1.toCSize): Unit
  }

  private def loop(): Unit = {
    var capacity = 16
    var fds = new Array[CInt](capacity)
    var ready = new Array[CInt](capacity)
    val drain = new Array[Byte](64)
    while true do {
      val count = lock.synchronized {
        if watches.size + 1 > capacity then {
          capacity = (watches.size + 1) * 2
          fds = new Array[CInt](capacity)
          ready = new Array[CInt](capacity)
        }
        fds(0) = wakeRead
        var i = 1
        for fd <- watches.keys do {
          fds(i) = fd
          i += 1
        }
        i
      }
      val n = FifoSelect.gears_select_readable(fds.at(0), count, ready.at(0))
      if n < 0 then {
        val code = errno.errno
        // A descriptor closed without being forgotten: drop those that are not open, rather than fail every wait.
        if code == EBADF then
          lock.synchronized {
            for fd <- watches.keys.toList if fcntl.fcntl(fd, fcntl.F_GETFD, 0) < 0 do watches.remove(fd)
          }
        else if code != EINTR then Thread.sleep(10) // not expected; no busy loop if it is
      } else {
        if ready(0) != 0 then while unistd.read(wakeRead, drain.at(0), drain.length.toCSize) > 0 do ()
        val told = lock.synchronized {
          val readable = mutable.ArrayBuffer.empty[() => Unit]
          var i = 1
          while i < count do {
            if ready(i) != 0 then watches.remove(fds(i)).foreach(readable ++= _.values)
            i += 1
          }
          readable
        }
        told.foreach(onReadable => onReadable())
      }
    }
  }
}

@extern
private object FifoSelect {
  @blocking // it waits, so a collection must not wait for it
  def gears_select_readable(fds: Ptr[CInt], count: CInt, ready: Ptr[CInt]): CInt = extern
}
