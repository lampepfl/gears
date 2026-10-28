package gears.async.io
package internal

import java.io.IOException
import scala.scalanative.posix.sys.socket
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.Zone

import Sockets.Flavor
import Sockets.Transport

/** Writing to a pipe or socket whose reader has gone fails with `EPIPE`, rather than raising `SIGPIPE`, which would end
  * the test process: on macOS through each descriptor's own setting, on Linux by ignoring the signal (`SigPipe`).
  */
class SigPipeSuite extends munit.FunSuite {

  test("a write to a pipe whose read end is closed fails with EPIPE") {
    Zone.acquire { implicit z =>
      val (in, out) = Descriptors.pipe()
      unistd.close(in)
      val one = NativeBuffer.allocate(1)
      val failure = intercept[IOException](NonBlocking.write(out, one))
      assert(failure.getMessage.contains("pipe"), failure.getMessage)
      unistd.close(out)
    }
  }

  test("a write to a socket whose peer is closed fails with EPIPE") {
    Zone.acquire { implicit z =>
      val path = s"/tmp/gears-sigpipe-${unistd.getpid()}.sock"
      val listener = PosixSockets.open(Flavor.Unix, Transport.Stream)
      try {
        PosixSockets.bindUnixAddr(listener, path, removeExisting = true)
        PosixSockets.listen(listener, 1)
        val client = PosixSockets.open(Flavor.Unix, Transport.Stream)
        PosixSockets.connectUnixAddr(client, path)
        val accepted = socket.accept(listener, null, null)
        assert(accepted >= 0, "accept failed")
        unistd.close(accepted)
        val one = NativeBuffer.allocate(1)
        val failure = intercept[IOException](NonBlocking.write(client, one))
        assert(failure.getMessage.contains("pipe"), failure.getMessage)
        unistd.close(client)
      } finally {
        unistd.close(listener)
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path)): Unit
      }
    }
  }
}
