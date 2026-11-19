package gears.async.io
package testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** The host's file system on the JVM and Scala Native: `java.nio.file`. */
private[testkit] object PlatformFiles {
  def createTempDirectory(prefix: String): String = Files.createTempDirectory(prefix).toString

  def deleteDirectory(dir: String): Unit = {
    val path = Path.of(dir)
    if Files.exists(path) then {
      val entries = Files.list(path)
      try entries.forEach(Files.deleteIfExists(_): Unit)
      finally entries.close()
      Files.deleteIfExists(path): Unit
    }
  }

  def write(path: String, bytes: Array[Byte]): Unit = Files.write(Path.of(path), bytes): Unit

  def readString(path: String): String = new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8)

  def exists(path: String): Boolean = Files.exists(Path.of(path))

  def deleteIfExists(path: String): Unit = Files.deleteIfExists(Path.of(path)): Unit

  def mkfifo(path: String): Int = new ProcessBuilder("mkfifo", path).start().waitFor()

  def loopbackMtu: Int =
    scala.jdk.CollectionConverters
      .EnumerationHasAsScala(java.net.NetworkInterface.getNetworkInterfaces)
      .asScala
      .find(_.isLoopback)
      .map(_.getMTU)
      .getOrElse(-1)
}
