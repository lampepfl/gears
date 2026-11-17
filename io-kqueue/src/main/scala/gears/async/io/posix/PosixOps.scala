package gears.async.io.posix

import java.nio.ByteBuffer

/** A stream read's system calls, for an op on a handle `H` that has a descriptor: mixed into the op. */
trait PosixReadOps[H <: PosixKernelHandle]:
  final def _readHandle(handle: H, buf: ByteBuffer): Int =
    NonBlocking.read(handle.fd, buf)
  final def _readvHandle(handle: H, bufs: Array[ByteBuffer]): Int =
    NonBlocking.readv(handle.fd, bufs)

/** A stream write's system calls, as `PosixReadOps` has a read's. */
trait PosixWriteOps[H <: PosixKernelHandle]:
  final def _writeHandle(handle: H, buf: ByteBuffer): Int =
    NonBlocking.write(handle.fd, buf)
  final def _writevHandle(handle: H, bufs: Array[ByteBuffer]): Int =
    NonBlocking.writev(handle.fd, bufs)
