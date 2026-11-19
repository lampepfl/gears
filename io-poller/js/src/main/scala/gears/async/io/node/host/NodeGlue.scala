package gears.async.io.node.host

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.Uint8Array

/** The source of `NodeGlue`, as the module's address, a `data:` URL: there is no file to keep beside whatever links
  * the backend. An address ends at a `?` or a `#`, and a `%` escapes: the source has none of them.
  */
private[node] object NodeGlueModule {
  final val Address =
    "data:text/javascript," +
      // putFrom
      "export function putFrom(socket, memory, offset, count) { if (socket.writableLength > 0) return -1;" +
      " const chunk = Buffer.allocUnsafe(count); chunk.set(new Uint8Array(memory, offset, count));" +
      " socket.write(chunk); return count; }" +
      // sendFrom
      " export function sendFrom(socket, memory, offset, count, port, host, sent) {" +
      " const packet = Buffer.allocUnsafe(count); packet.set(new Uint8Array(memory, offset, count));" +
      " if (host === undefined) socket.send(packet, sent); else socket.send(packet, port, host, sent); }" +
      // put
      " export function put(socket, chunk) { socket.write(chunk); }" +
      // copyIn
      " export function copyIn(chunk, from, count, memory, offset) {" +
      " let source = chunk; if (from !== 0 || count !== chunk.length) source = chunk.subarray(from, from + count);" +
      " new Uint8Array(memory, offset, count).set(source); }" +
      // copyOut
      " export function copyOut(memory, offset, count, chunk, at) {" +
      " chunk.set(new Uint8Array(memory, offset, count), at); }" +
      // pour, spill: bytes 32 at a call, as eight words whose first byte is the lowest, whichever way this machine
      // keeps a word: every word is read and written through a `DataView`, told so
      " const scratch = new ArrayBuffer(32); const words = new DataView(scratch); const wordBytes = new Uint8Array(scratch);" +
      " export function pour(chunk, at, count, a, b, c, d, e, f, g, h) {" +
      " words.setInt32(0, a, true); words.setInt32(4, b, true); words.setInt32(8, c, true); words.setInt32(12, d, true);" +
      " words.setInt32(16, e, true); words.setInt32(20, f, true); words.setInt32(24, g, true); words.setInt32(28, h, true);" +
      " if (count === 32) chunk.set(wordBytes, at); else chunk.set(wordBytes.subarray(0, count), at); }" +
      " export function spill(chunk, from, count, into) {" +
      " const view = new DataView(chunk.buffer, chunk.byteOffset + from, count); let at = 0;" +
      " for (; count - at >= 32; at += 32) into(view.getInt32(at, true), view.getInt32(at + 4, true)," +
      " view.getInt32(at + 8, true), view.getInt32(at + 12, true), view.getInt32(at + 16, true)," +
      " view.getInt32(at + 20, true), view.getInt32(at + 24, true), view.getInt32(at + 28, true));" +
      " if (at < count) { wordBytes.fill(0); wordBytes.set(chunk.subarray(from + at, from + count));" +
      " into(words.getInt32(0, true), words.getInt32(4, true), words.getInt32(8, true), words.getInt32(12, true)," +
      " words.getInt32(16, true), words.getInt32(20, true), words.getInt32(24, true), words.getInt32(28, true)); } }" +
      // allocate
      " export function allocate(n) { return Buffer.allocUnsafe(n); }"
}

/** A module of JavaScript's own, imported as the program is loaded: each of its functions is one call out of
  * WebAssembly where the same written in Scala would be a call for every step, and a call across costs more than the
  * step it makes.
  */
@js.native
@JSImport(NodeGlueModule.Address, JSImport.Namespace)
private[node] object NodeGlue extends js.Object {

  /** Writes a copy of `count` bytes of `memory`, from `offset`, to `socket`, with no callback, and gives `count`; or
    * writes nothing and gives -1 if Node still holds bytes written before.
    */
  def putFrom(socket: js.Dynamic, memory: ArrayBuffer, offset: Int, count: Int): Int = js.native

  /** Sends a copy of `count` bytes of `memory`, from `offset`, as one packet from `socket`, to `port` at `host`, or to
    * the socket's peer if `host` is undefined: `sent` is called once Node has.
    */
  def sendFrom(
      socket: js.Dynamic,
      memory: ArrayBuffer,
      offset: Int,
      count: Int,
      port: Int,
      host: js.UndefOr[String],
      sent: js.Function1[js.Dynamic, Unit]
  ): Unit = js.native

  /** Writes `chunk` to `socket`, with no callback. */
  def put(socket: js.Dynamic, chunk: Uint8Array): Unit = js.native

  /** Copies `count` bytes of `chunk`, from `from`, into `memory` at `offset`. */
  def copyIn(chunk: Uint8Array, from: Int, count: Int, memory: ArrayBuffer, offset: Int): Unit = js.native

  /** Copies `count` bytes of `memory`, from `offset`, into `chunk` at `at`. */
  def copyOut(memory: ArrayBuffer, offset: Int, count: Int, chunk: Uint8Array, at: Int): Unit = js.native

  /** Writes the first `count` bytes, at most 32, of the eight words into `chunk` at `at`: a word's lowest byte first. */
  def pour(chunk: Uint8Array, at: Int, count: Int, a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int): Unit =
    js.native

  /** Hands `count` bytes of `chunk`, from `from`, to `into`, 32 at a call as eight words, a word's lowest byte first;
    * the last call has what is left, and zeros after.
    */
  def spill(
      chunk: Uint8Array,
      from: Int,
      count: Int,
      into: js.Function8[Int, Int, Int, Int, Int, Int, Int, Int, Unit]
  ): Unit = js.native

  /** A chunk of `n` bytes for Node to keep, not zeroed: a small one is a piece of a block Node allocates ahead, which
    * costs less than memory of its own.
    */
  def allocate(n: Int): Uint8Array = js.native
}
