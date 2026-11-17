package gears.async.io.posix

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

/** Opens a pipe as a backend's handles `R` and `W`, which it makes of the two descriptors: mixed into the op. */
trait PosixPipeOpener[R, W]:
  def makeReadableHandle(fd: Int): R
  def makeWritableHandle(fd: Int): W

  final def _pipe(): (R, W) =
    val (readFd, writeFd) = Descriptors.pipe()
    (makeReadableHandle(readFd), makeWritableHandle(writeFd))
