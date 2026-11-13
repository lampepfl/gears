package gears.async.io.kqueue

import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.shareable.AbstractBaseHandle
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle

sealed trait KqueueHandle extends PosixKernelHandle, BaseHandle

sealed trait KqueueReadable extends KqueueHandle, BaseReadableHandle
sealed trait KqueueWritable extends KqueueHandle, BaseWritableHandle

sealed abstract class BaseKqueueHandle extends AbstractBaseHandle

final class PipeRead(fd: Int) extends PosixKernelHandle(fd) with KqueueReadable
final class PipeWrite(fd: Int) extends PosixKernelHandle(fd) with KqueueWritable

trait KqueuePipeOpener extends PosixPipeOpener:
  type PipeReadable = PipeRead
  type PipeWritable = PipeWrite

  final def makeReadableHandle(fd: Int): PipeReadable = new PipeRead(fd)
  final def makeWritableHandle(fd: Int): PipeWritable = new PipeWrite(fd)
