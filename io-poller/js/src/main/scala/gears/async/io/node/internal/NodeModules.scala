package gears.async.io.node.internal

import scala.scalajs.js

/** Node's globals that Scala.js has no facade for. */
private[node] object NodeGlobals {

  /** Calls `body` on the event loop's next turn, after the I/O events of this one, and returns what cancels it. */
  def setImmediate(body: js.Function0[Unit]): js.Dynamic = js.Dynamic.global.setImmediate(body)

  def clearImmediate(immediate: js.Dynamic): Unit = js.Dynamic.global.clearImmediate(immediate): Unit
}

/** Node's `net` module: stream sockets, IP and Unix, and what else Node reads and writes as one, by descriptor. */
@js.native
@js.annotation.JSImport("node:net", js.annotation.JSImport.Namespace)
private[node] object Net extends js.Object {
  def connect(options: js.Object): js.Dynamic = js.native
  def createServer(options: js.Object): js.Dynamic = js.native

  /** A stream over what `options` names: a descriptor, `fd`. */
  @js.native
  class Socket(options: js.Object) extends js.Object
}

/** Node's `os` module. */
@js.native
@js.annotation.JSImport("node:os", js.annotation.JSImport.Namespace)
private[node] object Os extends js.Object {
  def networkInterfaces(): js.Dictionary[js.Array[js.Dynamic]] = js.native
}

/** Node's `dgram` module: UDP sockets. */
@js.native
@js.annotation.JSImport("node:dgram", js.annotation.JSImport.Namespace)
private[node] object Dgram extends js.Object {
  def createSocket(options: js.Object): js.Dynamic = js.native
}

/** Node's `fs` module: files, by its calls that take a callback, and a few that return at once. */
@js.native
@js.annotation.JSImport("node:fs", js.annotation.JSImport.Namespace)
private[node] object Fs extends js.Object {
  def lstatSync(path: String, options: js.Object): js.UndefOr[js.Dynamic] = js.native
  def unlinkSync(path: String): Unit = js.native
  def renameSync(from: String, to: String): Unit = js.native

  val constants: js.Dynamic = js.native
  def open(path: String, flags: Int, done: js.Function2[js.Dynamic, js.Dynamic, Unit]): Unit = js.native
  def fstat(fd: Int, done: js.Function2[js.Dynamic, js.Dynamic, Unit]): Unit = js.native
  def read(
      fd: Int,
      into: js.typedarray.Uint8Array,
      offset: Int,
      length: Int,
      position: Double | Null,
      done: js.Function2[js.Dynamic, js.Dynamic, Unit]
  ): Unit = js.native
  def write(
      fd: Int,
      from: js.typedarray.Uint8Array,
      offset: Int,
      length: Int,
      position: Double,
      done: js.Function2[js.Dynamic, js.Dynamic, Unit]
  ): Unit = js.native
  def fsync(fd: Int, done: js.Function1[js.Dynamic, Unit]): Unit = js.native
  def close(fd: Int, done: js.Function1[js.Dynamic, Unit]): Unit = js.native
}

/** Node's `dns` module: name lookups by the system's resolver, on Node's own threads. */
@js.native
@js.annotation.JSImport("node:dns", js.annotation.JSImport.Namespace)
private[node] object Dns extends js.Object {
  def lookup(host: String, options: js.Object, done: js.Function2[js.Dynamic, js.Dynamic, Unit]): Unit = js.native
}
