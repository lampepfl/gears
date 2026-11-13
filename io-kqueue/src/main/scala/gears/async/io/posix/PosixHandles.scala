package gears.async.io.posix

import gears.async.io.shareable.PipeOpener

import _root_.gears.async.io.shareable.AbstractBaseHandle
import _root_.gears.async.io.shareable.BaseReadableHandle
import _root_.gears.async.io.shareable.BaseWritableHandle
import _root_.gears.async.io.shareable.HandleState

abstract class PosixKernelHandle(val fd: Int) extends AbstractBaseHandle:
  open() // construction is acquisition of the handle

  final def cleanup()(using ClosePermit): Boolean =
    Descriptors.closeHandle(fd)
    if !compareAndSetState(HandleState.ClosingState, HandleState.ClosedState) then
      throw new IllegalStateException("Concurrent cleanup detected")
    true

trait PosixPipeOpener extends PipeOpener:
  type PipeReadable <: PosixKernelHandle & BaseReadableHandle
  type PipeWritable <: PosixKernelHandle & BaseWritableHandle

  def makeReadableHandle(fd: Int): PipeReadable
  def makeWritableHandle(fd: Int): PipeWritable

  def pipe(): (read: PipeReadable, write: PipeWritable) =
    val (readFd, writeFd) = Descriptors.pipe()
    (makeReadableHandle(readFd), makeWritableHandle(writeFd))
