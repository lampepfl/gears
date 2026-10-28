package gears.async.io
package kqueue

import gears.async.io.internal.Interest
import gears.async.io.testkit.FifoContract
import gears.async.io.testkit.ResolveContract
import gears.async.io.testkit.SocketContract
import gears.async.io.testkit.UnixDatagramContract
import gears.async.io.testkit.UnixStreamContract

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class KqueueContractSuite
    extends KqueueReadinessChecks
    with SocketContract
    with UnixStreamContract
    with UnixDatagramContract
    with FifoContract
    with ResolveContract {
  val backend: KqueueBackend.type = KqueueBackend

  // Kqueue's own readiness on what its filters do not report, which `FifoWatcher`'s select does.

  test("ready on a regular file at its end completes, as select and poll say a file is always readable") {
    val dir = tempDir()
    try
      withReactor { r =>
        val path = dir.resolve("file.txt")
        Files.write(path, "x".getBytes(StandardCharsets.UTF_8))
        val file = open(r, path, write = false)
        try {
          val buf = allocate(8)
          assertEquals(run(r, backend.ops.read(file, buf)), null) // the one byte, leaving it at its end
          assertEquals(run(r, backend.ops.ready(file, Interest.Read)), null)
        } finally close(r, file)
      }
    finally deleteAll(dir)
  }

  test("ready waiting on a FIFO completes when its writer closes") {
    val dir = tempDir()
    try
      withReactor { r =>
        val fifo = dir.resolve("fifo")
        assertEquals(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor(), 0)
        val reader = openFifoForReading(r, fifo.toString)
        try {
          val writer = openFifoForWriting(r, fifo.toString)
          val ready = Outcome()
          r.submit(backend.ops.ready(reader, Interest.Read), ready)
          for _ <- 1 to 5 do r.runOnce(block = false)
          assert(!ready.finished, "ready with the writer open and nothing written")
          closeAlone(writer)
          turnUntil(r)(ready.finished)
          assertEquals(ready.completed, 1, String.valueOf(ready.failure))
          assertEquals(backend.ops.readNow(reader, allocate(8)), -1, "at its end")
        } finally closeAlone(reader)
      }
    finally deleteAll(dir)
  }
}
