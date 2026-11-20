package gears.async.io
package testkit

import java.io.IOException
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Int8Array

/** The host's file system on Scala.js: Node's `fs`, by its synchronous calls. */
private[testkit] object PlatformFiles {
  @js.native
  @JSImport("node:fs", JSImport.Namespace)
  private object fs extends js.Object {
    def mkdtempSync(prefix: String): String = js.native
    def rmSync(path: String, options: js.Object): Unit = js.native
    def writeFileSync(path: String, data: Int8Array): Unit = js.native
    def readFileSync(path: String, encoding: String): String = js.native
    def lstatSync(path: String, options: js.Object): js.UndefOr[js.Object] = js.native
  }

  @js.native
  @JSImport("node:os", JSImport.Namespace)
  private object os extends js.Object {
    def tmpdir(): String = js.native
  }

  @js.native
  @JSImport("node:child_process", JSImport.Namespace)
  private object childProcess extends js.Object {
    def spawnSync(command: String, args: js.Array[String]): js.Dynamic = js.native
  }

  /** Runs a call of Node's, whose failures are JavaScript errors, as one that fails with an `IOException`. */
  private def io[T](body: => T): T =
    try body
    catch case js.JavaScriptException(error) => throw new IOException(String.valueOf(error))

  private val force = js.Dynamic.literal(recursive = true, force = true)

  def createTempDirectory(prefix: String): String = io(fs.mkdtempSync(s"${os.tmpdir()}/$prefix"))

  def deleteDirectory(dir: String): Unit = io(fs.rmSync(dir, force))

  def write(path: String, bytes: Array[Byte]): Unit = {
    val data = new Int8Array(bytes.length)
    var i = 0
    while i < bytes.length do {
      data(i) = bytes(i)
      i += 1
    }
    io(fs.writeFileSync(path, data))
  }

  def readString(path: String): String = io(fs.readFileSync(path, "utf8"))

  def exists(path: String): Boolean = io(fs.lstatSync(path, js.Dynamic.literal(throwIfNoEntry = false))).isDefined

  def deleteIfExists(path: String): Unit = io(fs.rmSync(path, force))

  def mkfifo(path: String): Int = {
    val status = childProcess.spawnSync("mkfifo", js.Array(path)).status
    if js.typeOf(status) == "number" then status.asInstanceOf[Int] else -1
  }

  /** Node does not tell an interface's MTU. */
  def loopbackMtu: Int = -1
}
