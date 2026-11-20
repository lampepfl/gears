package gears.async.io
package testkit

import java.nio.file.Files
import java.nio.file.Path

/** Host filesystem setup used only by file, FIFO and Unix-domain socket checks. */
trait FileSystemFixture extends ContractSuite {

  /** Runs `body` with a temporary directory, deleted with what is in it once `body` returns. */
  protected def withTempDir[T](body: Path => T): T = {
    val dir = Files.createTempDirectory("gears-io")
    try body(dir)
    finally
      if Files.exists(dir) then {
        val entries = Files.list(dir)
        try entries.forEach(Files.deleteIfExists(_): Unit)
        finally entries.close()
        Files.deleteIfExists(dir): Unit
      }
  }

  protected def mkfifo(path: java.nio.file.Path)(using munit.Location): Unit =
    assertEquals(new ProcessBuilder("mkfifo", path.toString).start().waitFor(), 0)
}
