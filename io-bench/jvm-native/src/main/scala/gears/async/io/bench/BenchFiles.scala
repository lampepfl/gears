package gears.async.io.bench

/** What the bench asks of the system beside the backend, on the JVM and Scala Native: the JDK's. */
object BenchFiles {
  def makeDirectory(path: String): Unit = { val _ = new java.io.File(path).mkdirs() }

  def remove(path: String): Unit = { val _ = new java.io.File(path).delete() }

  /** A file of `size` zero bytes. */
  def writeZeros(path: String, size: Int): Unit = {
    val _ = java.nio.file.Files.write(java.nio.file.Paths.get(path), new Array[Byte](size))
  }

  def makeFifo(path: String): Unit =
    if new ProcessBuilder("mkfifo", path).start().waitFor() != 0 then throw new java.io.IOException(s"mkfifo $path")

  def environment(name: String): String | Null = System.getenv(name)
}
