package gears.async.io
package internal

object Bracket {
  @FunctionalInterface
  trait FileOperation {
    def apply(fd: Int): Unit
  }

  def fileResource(fd: Int)(release: FileOperation)(use: FileOperation): Unit = {
    try {
      use(fd)
    } finally {
      // Clean up the resource
      release(fd)
    }
  }
}
