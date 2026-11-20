package gears.async.io
package testkit

/** A path on the host, as text: what `FileSystemFixture.withTempDir` gives a check, which names the files in it with
  * `resolve`. Its own type, not `java.nio.file.Path`, which Scala.js has not.
  */
final class HostPath(path: String) {

  /** The path of `name` in this directory. */
  def resolve(name: String): HostPath = new HostPath(s"$path/$name")

  override def toString: String = path
}

/** The host's file system, as the checks and the demos set it up and look at it, outside the backend under test: each
  * platform's own, `java.nio.file` on the JVM and Scala Native and Node's `fs` on Scala.js. Every call blocks, and
  * fails with an `IOException`.
  */
object HostFiles {

  /** A new, empty directory for temporary files. */
  def createTempDirectory(prefix: String): HostPath = new HostPath(PlatformFiles.createTempDirectory(prefix))

  /** Deletes the directory `dir` with the files in it, if it is there. */
  def deleteDirectory(dir: HostPath): Unit = PlatformFiles.deleteDirectory(dir.toString)

  /** Writes `bytes` as the whole of the file at `path`, creating it if it is not there. */
  def write(path: HostPath, bytes: Array[Byte]): Unit = PlatformFiles.write(path.toString, bytes)

  /** The whole of the file at `path`, as UTF-8. */
  def readString(path: HostPath): String = PlatformFiles.readString(path.toString)

  /** Whether there is a file of any kind at `path`. */
  def exists(path: HostPath): Boolean = PlatformFiles.exists(path.toString)

  /** Whether there is a file of any kind at `path`. */
  def exists(path: String): Boolean = PlatformFiles.exists(path)

  /** Deletes the file at `path`, if it is there. */
  def deleteIfExists(path: String): Unit = PlatformFiles.deleteIfExists(path)

  /** Makes a FIFO at `path` with the host's `mkfifo`, returning its exit status. */
  def mkfifo(path: HostPath): Int = PlatformFiles.mkfifo(path.toString)

  /** The loopback interface's MTU, or -1 if the platform does not tell it. */
  def loopbackMtu: Int = PlatformFiles.loopbackMtu
}
