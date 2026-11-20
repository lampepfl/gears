package gears.async.io
package node
package host

/** The interface's socket options as Node has them, which are few: Node sets what its own programs commonly set, and
  * reads almost none back from the system.
  *
  *   - `NoDelay` is `socket.setNoDelay`, on a connection, given as it opens or changed after; Node does not read it
  *     back, so its value is what the backend set, which it always sets.
  *   - `ReceiveBuffer` and `SendBuffer` are a datagram socket's alone, which Node sets and reads back from the system
  *     (`setRecvBufferSize` and the rest): it has no call for a stream socket's.
  *   - `KeepAlive` is not had: `socket.setKeepAlive` sets the idle time alone, not the interval nor the count.
  *   - `DontFragment` is not had: Node has no call for it.
  *   - `ReusePort` is not had: Node has it (`reusePort`) only on the systems that balance load over the sockets sharing
  *     a port, not macOS, and nothing tells which before a socket fails to bind.
  */
private[node] object Options {
  import SocketOption.*

  def supports(option: SocketOption[?], kind: Kind): Boolean =
    option.appliesTo(kind) && (option match {
      case NoDelay                    => true
      case ReceiveBuffer | SendBuffer => kind == Kind.BoundDatagram || kind == Kind.ConnectedDatagram
      case _                          => false
    })

  /** Whether `option` is read and changed on an open socket of kind `kind`. */
  def onOpen(option: SocketOption[?], kind: Kind): Boolean =
    SocketOption.onSocket(option, kind) && supports(option, kind)

  def canChange(option: SocketOption[?], kind: Kind): Boolean = option != ReusePort && onOpen(option, kind)

  /** Checks the options an opener of a socket of kind `kind` is given, as it is built. */
  def check(options: SocketOptions, kind: Kind): Unit =
    for option <- options.options do {
      if !option.appliesTo(kind) then
        throw new IllegalArgumentException(s"$option does not apply to a ${kind.description}")
      if !supports(option, kind) then
        throw new IllegalArgumentException(s"This backend has no socket option $option for a ${kind.description}")
    }
}
