package gears.async.io
package posix

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.sys.socket

/** The interface's socket options as the system's, set and read on a socket's descriptor. `ipv6` says whether the
  * socket is an IPv6 one, dual stack, whose don't-fragment option is IPv6's and covers what it sends to IPv4 peers too.
  */
object PosixSocketOptions {

  def set(fd: Int, ipv6: Boolean, option: SocketOption[?], value: Any): Unit = option match {
    case SocketOption.NoDelay =>
      val on = if value.asInstanceOf[Boolean] then 1 else 0
      PosixSockets.setIntOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_NODELAY, on, "TCP_NODELAY")
    case SocketOption.KeepAlive =>
      value.asInstanceOf[SocketOption.KeepAlive.Value] match {
        case SocketOption.KeepAlive.Off =>
          PosixSockets.setIntOption(fd, socket.SOL_SOCKET, socket.SO_KEEPALIVE, 0, "SO_KEEPALIVE")
        case SocketOption.KeepAlive.On(idle, interval, count) =>
          PosixSockets.setIntOption(fd, socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1, "SO_KEEPALIVE")
          PosixSockets.setIntOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPIDLE, idle, "TCP_KEEPIDLE")
          PosixSockets.setIntOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPINTVL, interval, "TCP_KEEPINTVL")
          PosixSockets.setIntOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPCNT, count, "TCP_KEEPCNT")
      }
    case SocketOption.ReceiveBuffer =>
      PosixSockets.setIntOption(fd, socket.SOL_SOCKET, socket.SO_RCVBUF, value.asInstanceOf[Int], "SO_RCVBUF")
    case SocketOption.SendBuffer =>
      PosixSockets.setIntOption(fd, socket.SOL_SOCKET, socket.SO_SNDBUF, value.asInstanceOf[Int], "SO_SNDBUF")
    case SocketOption.DontFragment =>
      val on = value.asInstanceOf[Boolean]
      if ipv6 then
        PosixSockets.setIntOption(
          fd,
          PosixSockets.IPPROTO_IPV6,
          PosixSockets.IPV6_DONTFRAG,
          if on then 1 else 0,
          "IPV6_DONTFRAG"
        )
      else if LinktimeInfo.isLinux then
        val mode = if on then PosixSockets.IP_PMTUDISC_DO else PosixSockets.IP_PMTUDISC_DONT
        PosixSockets.setIntOption(fd, PosixSockets.IPPROTO_IP, PosixSockets.IP_MTU_DISCOVER, mode, "IP_MTU_DISCOVER")
      else
        PosixSockets.setIntOption(
          fd,
          PosixSockets.IPPROTO_IP,
          PosixSockets.IP_DONTFRAG,
          if on then 1 else 0,
          "IP_DONTFRAG"
        )
    case SocketOption.ReusePort =>
      val on = if value.asInstanceOf[Boolean] then 1 else 0
      PosixSockets.setIntOption(fd, socket.SOL_SOCKET, PosixSockets.SO_REUSEPORT, on, "SO_REUSEPORT")
  }

  /** `option`'s value on `fd`, as the system has it. */
  def get(fd: Int, ipv6: Boolean, option: SocketOption[?]): Any = option match {
    case SocketOption.NoDelay =>
      PosixSockets.intOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_NODELAY, "TCP_NODELAY") != 0
    case SocketOption.KeepAlive =>
      if PosixSockets.intOption(fd, socket.SOL_SOCKET, socket.SO_KEEPALIVE, "SO_KEEPALIVE") == 0 then
        SocketOption.KeepAlive.Off
      else
        SocketOption.KeepAlive.On(
          PosixSockets.intOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPIDLE, "TCP_KEEPIDLE"),
          PosixSockets.intOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPINTVL, "TCP_KEEPINTVL"),
          PosixSockets.intOption(fd, PosixSockets.IPPROTO_TCP, PosixSockets.TCP_KEEPCNT, "TCP_KEEPCNT")
        )
    case SocketOption.ReceiveBuffer => PosixSockets.intOption(fd, socket.SOL_SOCKET, socket.SO_RCVBUF, "SO_RCVBUF")
    case SocketOption.SendBuffer    => PosixSockets.intOption(fd, socket.SOL_SOCKET, socket.SO_SNDBUF, "SO_SNDBUF")
    case SocketOption.DontFragment  =>
      if ipv6 then
        PosixSockets.intOption(fd, PosixSockets.IPPROTO_IPV6, PosixSockets.IPV6_DONTFRAG, "IPV6_DONTFRAG") != 0
      else if LinktimeInfo.isLinux then
        PosixSockets.intOption(fd, PosixSockets.IPPROTO_IP, PosixSockets.IP_MTU_DISCOVER, "IP_MTU_DISCOVER") ==
          PosixSockets.IP_PMTUDISC_DO
      else PosixSockets.intOption(fd, PosixSockets.IPPROTO_IP, PosixSockets.IP_DONTFRAG, "IP_DONTFRAG") != 0
    case SocketOption.ReusePort =>
      PosixSockets.intOption(fd, socket.SOL_SOCKET, PosixSockets.SO_REUSEPORT, "SO_REUSEPORT") != 0
  }
}
