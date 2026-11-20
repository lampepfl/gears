package gears.async.io
package reference

import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions.*
import java.nio.channels.DatagramChannel
import java.nio.channels.NetworkChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

import jdk.net.ExtendedSocketOptions.*

/** The interface's socket options as the JDK's: what each kind of socket has, probed once on a socket of that kind,
  * reading them and setting them.
  */
private[reference] object Options {
  import SocketOption.*

  private def jdk(option: SocketOption[?]): List[java.net.SocketOption[?]] = option match {
    case NoDelay       => List(TCP_NODELAY)
    case KeepAlive     => List(SO_KEEPALIVE, TCP_KEEPIDLE, TCP_KEEPINTERVAL, TCP_KEEPCOUNT)
    case ReceiveBuffer => List(SO_RCVBUF)
    case SendBuffer    => List(SO_SNDBUF)
    case DontFragment  => List(IP_DONTFRAGMENT)
    case ReusePort     => List(SO_REUSEPORT)
  }

  /** The options the JDK has for each kind of socket the backend opens. */
  private lazy val probed: Map[Kind, Set[java.net.SocketOption[?]]] = {
    def of(open: => NetworkChannel): Set[java.net.SocketOption[?]] = {
      val channel = open
      try scala.jdk.CollectionConverters.SetHasAsScala(channel.supportedOptions()).asScala.toSet
      finally channel.close()
    }
    val connection = of(SocketChannel.open())
    val datagram = of(DatagramChannel.open())
    Map(
      Kind.Connection -> connection,
      Kind.Listener -> of(ServerSocketChannel.open()),
      Kind.BoundDatagram -> datagram,
      Kind.ConnectedDatagram -> datagram,
      Kind.UnixConnection -> of(SocketChannel.open(StandardProtocolFamily.UNIX)),
      Kind.UnixListener -> of(ServerSocketChannel.open(StandardProtocolFamily.UNIX)),
      Kind.UnixBoundDatagram -> Set.empty,
      Kind.UnixConnectedDatagram -> Set.empty
    )
  }

  /** The kind of socket that carries `option` for one of kind `kind`: a listener's connections carry those it gives
    * them.
    */
  private def carrier(option: SocketOption[?], kind: Kind): Kind = (option, kind) match {
    case (NoDelay | KeepAlive | SendBuffer, Kind.Listener) => Kind.Connection
    case (SendBuffer, Kind.UnixListener)                   => Kind.UnixConnection
    case _                                                 => kind
  }

  def supports(option: SocketOption[?], kind: Kind): Boolean =
    option.appliesTo(kind) && jdk(option).forall(probed(carrier(option, kind)).contains)

  /** Whether `option` is read and changed on an open socket of kind `kind`. */
  def onOpen(option: SocketOption[?], kind: Kind): Boolean =
    SocketOption.onSocket(option, kind) && jdk(option).forall(probed(kind).contains)

  def canChange(option: SocketOption[?], kind: Kind): Boolean = option != ReusePort && onOpen(option, kind)

  /** Checks the options an opener of a socket of kind `kind` is given, as it is built. */
  def check(options: SocketOptions, kind: Kind): Unit =
    for option <- options.options do {
      if !option.appliesTo(kind) then
        throw new IllegalArgumentException(s"$option does not apply to a ${kind.description}")
      if !supports(option, kind) then
        throw new IllegalArgumentException(s"This backend has no socket option $option for a ${kind.description} here")
    }

  /** Whether `channel` has `option` itself. */
  def on(channel: NetworkChannel, option: SocketOption[?]): Boolean =
    jdk(option).forall(channel.supportedOptions().contains)

  def set(channel: NetworkChannel, option: SocketOption[?], value: Any): Unit = option match {
    case NoDelay   => channel.setOption(TCP_NODELAY, value.asInstanceOf[Boolean]): Unit
    case KeepAlive =>
      value match {
        case KeepAlive.On(idle, interval, count) =>
          channel.setOption(SO_KEEPALIVE, true)
          channel.setOption(TCP_KEEPIDLE, idle)
          channel.setOption(TCP_KEEPINTERVAL, interval)
          channel.setOption(TCP_KEEPCOUNT, count): Unit
        case _ => channel.setOption(SO_KEEPALIVE, false): Unit
      }
    case ReceiveBuffer => channel.setOption(SO_RCVBUF, value.asInstanceOf[Int]): Unit
    case SendBuffer    => channel.setOption(SO_SNDBUF, value.asInstanceOf[Int]): Unit
    case DontFragment  => channel.setOption(IP_DONTFRAGMENT, value.asInstanceOf[Boolean]): Unit
    case ReusePort     => channel.setOption(SO_REUSEPORT, value.asInstanceOf[Boolean]): Unit
  }

  def get(channel: NetworkChannel, option: SocketOption[?]): Any = option match {
    case NoDelay   => channel.getOption(TCP_NODELAY).booleanValue
    case KeepAlive =>
      if !channel.getOption(SO_KEEPALIVE).booleanValue then KeepAlive.Off
      else
        KeepAlive.On(
          channel.getOption(TCP_KEEPIDLE).intValue,
          channel.getOption(TCP_KEEPINTERVAL).intValue,
          channel.getOption(TCP_KEEPCOUNT).intValue
        )
    case ReceiveBuffer => channel.getOption(SO_RCVBUF).intValue
    case SendBuffer    => channel.getOption(SO_SNDBUF).intValue
    case DontFragment  => channel.getOption(IP_DONTFRAGMENT).booleanValue
    case ReusePort     => channel.getOption(SO_REUSEPORT).booleanValue
  }
}
