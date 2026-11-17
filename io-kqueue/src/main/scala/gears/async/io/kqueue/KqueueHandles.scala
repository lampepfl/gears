package gears.async.io.kqueue

import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle

sealed trait KqueueHandle extends PosixKernelHandle, BaseHandle:
  type Source = KqueuePoller

sealed trait KqueueReadable extends KqueueHandle, BaseReadableHandle
sealed trait KqueueWritable extends KqueueHandle, BaseWritableHandle

final class PipeRead(fd: Int) extends PosixKernelHandle(fd) with KqueueReadable
final class PipeWrite(fd: Int) extends PosixKernelHandle(fd) with KqueueWritable

trait KqueuePipeOpener extends PosixPipeOpener[KqueueReadable, KqueueWritable]:
  final def makeReadableHandle(fd: Int): PipeRead = new PipeRead(fd)
  final def makeWritableHandle(fd: Int): PipeWrite = new PipeWrite(fd)
