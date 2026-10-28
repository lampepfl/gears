package example.tests

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.concurrent.duration.*
import scala.util.Using

/** Checks timer expiry and FIFO readiness using the native demo executable. */
class KQueueDemoSuite extends DemoSuite {
  private val message = "posixlib-kqueue".getBytes(StandardCharsets.UTF_8)

  demoTest("five-second one-shot timer") {
    val started = System.nanoTime()
    val output = run("timer")
    val elapsed = (System.nanoTime() - started).nanos
    assert(output.contains("timer fired"), output)
    assert(elapsed >= 4.5.seconds, s"timer fired after ${elapsed.toMillis} ms:\n$output")
  }

  if (!gearsMirrors) // the reactor's own timers; the gears demos have no mirror of this one
    demoTest("many timers: fired in deadline order, cancelled ones never") {
      val output = run("timers")
      assert(output.contains("timers fired: 1333 of 1333"), output)
      assert(output.contains("timers fired in order: true"), output)
      assert(output.contains("cancelled timers fired: 0"), output)
    }

  if (!gearsMirrors) // KqueueHandles directly; the gears demos have no mirror of this one
    demoTest("writing to a pipe or FIFO whose reader has closed fails, instead of ending the process") {
      withFifo { fifo =>
        val output = run("broken-pipe", "--fifo", fifo.toString)
        assert(output.contains("pipe write after its reader closed: failed:"), output)
        assert(output.contains("fifo write after its reader closed: failed:"), output)
        assert(output.contains("Broken pipe"), output)
        assert(output.contains("still running"), output)
      }
    }

  demoTest("resolve localhost off the reactor thread") {
    val output = run("resolve", "--host", "localhost")
    assert(output.contains("IPv4 127.0.0.1") || output.contains("IPv6 ::1"), output)
    assert(output.contains("resolved localhost to"), output)
  }

  demoTest("cancel pending ops of each kind") {
    val output = run("cancel")
    val expected = Seq(
      "read cancelled: true",
      "blocking cancelled: true",
      "timer cancelled: true",
      "resubmitting the cancelled blocking task: refused",
      "cancel notifications: 3",
      "closing fails a pending read: true",
      "read after cancel: `hello`",
      "blocking interrupted: true",
      "a running blocking task had stopped before onCancel: true",
      "the cancelled task left its slot empty: true",
      "the leftover had stopped when notified: true",
      "cancelled completions run: 0"
    ) ++ (
      // The plain demo's leftover is stopped by closing the reactor; the gears mirror's by the end of its program,
      // which cancels the future performing it.
      if (gearsMirrors)
        Seq("the program's end notified the leftover: true", "blocking interrupted by the program's end: true")
      else Seq("close notified the leftover: true", "blocking interrupted by close: true")
    )
    for (line <- expected) assert(output.contains(line), output)
    assert(!output.contains("unexpected"), output)
  }

  if (hasFifos) demoTest("FIFO read readiness and EOF, each writer in turn reported once, with no spinning between") {
    withFifo { fifo =>
      withDemo("file-read", "--fifo", fifo.toString) { reader =>
        reader.waitFor("Event registered for file read.", 5.seconds)
        Thread.sleep(300) // the reader at the end of a FIFO no writer has opened, which it must not report, nor spin on
        Using.resource(new FileOutputStream(fifo.toFile))(_.write(message))
        reader.waitFor(s"File contents: `${new String(message, StandardCharsets.UTF_8)}`", 5.seconds)
        Thread.sleep(300)
        Using.resource(new FileOutputStream(fifo.toFile))(_.write("second".getBytes(StandardCharsets.UTF_8)))
        val output = reader.waitFor("File contents: `second`", 5.seconds)
        assert(!output.contains("Exception"), output.takeRight(4096))
        assertEquals(output.linesIterator.count(_.startsWith("End of file reached.")), 2, output.takeRight(4096))
        assert(output.linesIterator.size < 100, s"${output.linesIterator.size} lines: it spun")
      }
    }
  }

  if (hasFifos) demoTest("FIFO write readiness") {
    withFifo { fifo =>
      // The native writer sends on every writable event. Holding both ends open here lets its
      // non-blocking open find a reader without a race, and keeps the data queued after it stops.
      val pipe = new RandomAccessFile(fifo.toFile, "rw")
      try {
        withDemo("file-write", "--fifo", fifo.toString, "-m", new String(message, StandardCharsets.UTF_8)) { writer =>
          writer.waitFor(s"actually wrote ${message.length} bytes", 5.seconds)
        }
        // Our write end lets this open return at once; releasing it then leaves EOF after the queued data.
        Using.resource(new FileInputStream(fifo.toFile)) { in =>
          pipe.close()
          val received = new ByteArrayOutputStream
          val buffer = new Array[Byte](8192)
          var read = in.read(buffer)
          while (read != -1) {
            received.write(buffer, 0, read)
            read = in.read(buffer)
          }
          val bytes = received.toByteArray
          assert(bytes.nonEmpty && bytes.length % message.length == 0, bytes.take(100).toSeq)
          assertEquals(bytes.toSeq, Seq.fill(bytes.length / message.length)(message.toSeq).flatten)
        }
      } finally pipe.close()
    }
  }

  private def withFifo[A](body: Path => A): A =
    withTempDirectory("gears-kqueue-") { directory =>
      val fifo = directory.resolve("test.fifo")
      val mkfifo = new ProcessBuilder("mkfifo", fifo.toString).inheritIO().start()
      assertEquals(mkfifo.waitFor(), 0, s"mkfifo $fifo failed")
      body(fifo)
    }
}
