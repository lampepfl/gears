package gears.async.io.posix

import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.NonBlockingReadOps
import gears.async.io.shareable.NonBlockingWriteOps

import java.nio.ByteBuffer

trait PosixReadOps extends NonBlockingReadOps:
  type ReadableHandle <: PosixKernelHandle & BaseReadableHandle
  final def _readHandle(handle: ReadableHandle, buf: ByteBuffer): Int =
    NonBlocking.read(handle.fd, buf)

trait PosixWriteOps extends NonBlockingWriteOps:
  type WriteableHandle <: PosixKernelHandle & BaseWritableHandle
  final def _writeHandle(handle: WriteableHandle, buf: ByteBuffer): Int =
    NonBlocking.write(handle.fd, buf)
