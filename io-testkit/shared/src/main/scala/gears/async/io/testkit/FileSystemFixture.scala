package gears.async.io
package testkit

/** Host filesystem setup used only by file, FIFO and Unix-domain socket checks. */
trait FileSystemFixture extends ContractSuite {

  /** Runs `body` with a temporary directory, deleted with what is in it once `body` returns. */
  protected def withTempDir[T](body: HostPath => T): T = {
    val dir = HostFiles.createTempDirectory("gears-io")
    try body(dir)
    finally HostFiles.deleteDirectory(dir)
  }

  protected def mkfifo(path: HostPath)(using munit.Location): Unit =
    assertEquals(HostFiles.mkfifo(path), 0)
}
