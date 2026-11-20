package gears.async.io
package uring

import scala.scalanative.posix.sys.socket
import scala.scalanative.unsafe.*

private[uring] object Linux {
  inline val SOCK_NONBLOCK = 0x800
  inline val SOCK_CLOEXEC = 0x80000
  inline val MSG_DONTWAIT = 0x40
  inline val MSG_NOSIGNAL = 0x4000
  inline val POLLIN = 0x001
  inline val POLLOUT = 0x004
  inline val ETIME = 62
}

@extern
private[uring] object LinuxCalls {
  def accept4(fd: CInt, address: Ptr[socket.sockaddr], addressLength: Ptr[socket.socklen_t], flags: CInt): CInt =
    extern
}
