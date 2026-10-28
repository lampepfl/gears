package example

import gears.async.io.internal.PosixErr.cError
import gears.async.io.internal.PosixSockets
import gears.async.io.internal.Sockets.Flavor
import gears.async.io.internal.Sockets.Transport
import gears.async.io.kqueue.KqueueHandles

import java.io.IOException
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.resource
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*

/** Opens many descriptors and prints their numbers, to check the assumption behind an fd-indexed handler table:
  * descriptors are small, dense integers, the kernel hands out the lowest free number, and the soft RLIMIT_NOFILE
  * bounds the table.
  */
object KQueueExampleFileHandles {
  def run(count: Int): Unit = {
    require(count > 0, "count must be positive")
    val limit = stackalloc[resource.rlimit]()
    if resource.getrlimit(resource.RLIMIT_NOFILE, limit) < 0 then
      throw new IOException(s"Failed to read RLIMIT_NOFILE: ${cError()}")
    println(s"RLIMIT_NOFILE: soft = ${limit._1}, hard = ${limit._2}")

    val opened = new Array[Int](count)
    var i = 0
    while i < count do {
      // Alternate kinds so the numbering is visibly shared by every descriptor type.
      opened(i) = i % 3 match {
        case 0 => PosixSockets.open(Flavor.Unix, Transport.Stream)
        case 1 => PosixSockets.open(Flavor.IPv4, Transport.Datagram)
        case _ => openDevNull()
      }
      i += 1
    }
    println(s"opened $count descriptors: ${opened.mkString(" ")}")
    report(opened)

    // Close every other descriptor, then open again: the kernel should refill the holes lowest-first.
    var closed = 0
    i = 1
    while i < count do {
      KqueueHandles.close(KqueueHandles.handle(opened(i)))
      closed += 1
      i += 2
    }
    val reopened = new Array[Int](closed)
    i = 0
    while i < closed do {
      reopened(i) = openDevNull()
      i += 1
    }
    println(s"closed $closed odd-indexed descriptors, then opened $closed more: ${reopened.mkString(" ")}")
    val all = opened.zipWithIndex.collect { case (fd, idx) if idx % 2 == 0 => fd } ++ reopened
    report(all)

    all.foreach(PosixSockets.close)
    println(s"closed all ${all.length} descriptors")
  }

  private def openDevNull(): Int = {
    val fd = Zone.acquire { implicit z => fcntl.open(c"/dev/null", fcntl.O_RDONLY) }
    if fd < 0 then throw new IOException(s"Failed to open /dev/null: ${cError()}")
    fd
  }

  private def report(fds: Array[Int]): Unit = {
    val max = fds.max
    val min = fds.min
    val distinct = fds.distinct.length
    println(s"  min fd = $min, max fd = $max, distinct = $distinct")
    println(s"  a table indexed by fd needs ${max + 1} slots for ${fds.length} live descriptors")
    val contiguous = fds.sorted.sliding(2).forall { case Array(a, b) => b == a + 1; case _ => true }
    println(s"  contiguous numbering: $contiguous")
  }
}
