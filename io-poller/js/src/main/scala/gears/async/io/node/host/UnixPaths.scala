package gears.async.io.node.host

import gears.async.io.node.internal.Fs

import java.io.IOException
import scala.scalajs.js

/** The socket files of Unix listeners. */
private[node] object UnixPaths {
  private val asBigInts = js.Dynamic.literal(throwIfNoEntry = false, bigint = true)
  private var renames = 0L

  /** What tells the file at `path` from any other, its device and inode, or null if there is none. */
  def identity(path: String): String | Null =
    try
      Fs.lstatSync(path, asBigInts).toOption match {
        case Some(stat) => s"${stat.dev}:${stat.ino}"
        case None       => null
      }
    catch case _: js.JavaScriptException => null

  /** Makes way for a listener at `path`: removes a socket file there, a server's that ran before or runs still, and
    * fails for any other kind of file, which is left.
    */
  def clear(path: String): Unit = NodeErr.guard {
    Fs.lstatSync(path, asBigInts).toOption match {
      case Some(stat) =>
        if !stat.isSocket().asInstanceOf[Boolean] then throw new IOException(s"$path exists and is not a socket")
        Fs.unlinkSync(path)
      case None => ()
    }
  }

  /** Closes a listener that bound `path` as the file `own`, with `close`. Node removes the path as it closes the
    * listener, whatever is there, and at once, within the call; so if the file there is no longer the listener's own,
    * but one a later listener bound in its place, it is moved aside for the close and moved back after, within this
    * turn of the event loop.
    */
  def closing(path: String, own: String | Null)(close: () => Unit): Unit = {
    val there = identity(path)
    if there == null || there == own then close()
    else {
      renames += 1
      val aside = s"$path.$renames.kept"
      val moved =
        try {
          Fs.renameSync(path, aside)
          true
        } catch case _: js.JavaScriptException => false
      try close()
      finally
        if moved then
          try Fs.renameSync(aside, path)
          catch case _: js.JavaScriptException => ()
    }
  }
}
