package gears.async.io.kqueue

import gears.async.io.posix.PosixKernelHandle
import gears.async.io.posix.PosixPipeOpener
import gears.async.io.shareable.BaseHandle
import gears.async.io.shareable.BaseReadableHandle
import gears.async.io.shareable.BaseWritableHandle
import gears.async.io.shareable.HandleOwner

sealed trait KqueueHandle extends PosixKernelHandle, BaseHandle:
  type Source = KqueuePoller

sealed trait KqueueReadable extends KqueueHandle, BaseReadableHandle
sealed trait KqueueWritable extends KqueueHandle, BaseWritableHandle

final class PipeRead(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueReadable
final class PipeWrite(fd: Int, owner: HandleOwner) extends PosixKernelHandle(fd, owner) with KqueueWritable

trait KqueuePipeOpener extends PosixPipeOpener[KqueueReadable, KqueueWritable]:
  final def makeReadableHandle(fd: Int, owner: HandleOwner): PipeRead = new PipeRead(fd, owner)
  final def makeWritableHandle(fd: Int, owner: HandleOwner): PipeWrite = new PipeWrite(fd, owner)
