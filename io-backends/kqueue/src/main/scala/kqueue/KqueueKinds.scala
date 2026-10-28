package gears.async.io
package kqueue

import KqueueBackend.Handle

/** Kqueue's kinds of handle (see `Ops`): opaque types over its `Handle`, so that a program holding them cannot pass one
  * kind where another is asked for. The backend's openers make them from the handle they open, with the conversions
  * here.
  */
object KqueueKinds {
  opaque type Readable <: Handle = Handle
  opaque type Writable <: Handle = Handle
  opaque type Connection <: Readable & Writable = Handle
  opaque type Listener <: Handle = Handle
  opaque type BoundDatagram <: Handle = Handle
  opaque type ConnectedDatagram <: Handle = Handle
  opaque type UnixBoundDatagram <: Handle = Handle
  opaque type UnixConnectedDatagram <: Handle = Handle
  opaque type File <: Readable & Writable = Handle

  private[io] def readable(handle: Handle): Readable = handle
  private[io] def writable(handle: Handle): Writable = handle
  private[io] def connection(handle: Handle): Connection = handle
  private[io] def listener(handle: Handle): Listener = handle
  private[io] def boundDatagram(handle: Handle): BoundDatagram = handle
  private[io] def connectedDatagram(handle: Handle): ConnectedDatagram = handle
  private[io] def unixBoundDatagram(handle: Handle): UnixBoundDatagram = handle
  private[io] def unixConnectedDatagram(handle: Handle): UnixConnectedDatagram = handle
  private[io] def file(handle: Handle): File = handle
}
