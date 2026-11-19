package gears.async.io.kqueue.internal

import scala.scalanative.posix.time
import scala.scalanative.unsafe.*

@extern
private[kqueue] object KqueueUnblocked {
  def kevent(
      kq: CInt,
      changelist: CVoidPtr,
      nchanges: CInt,
      eventlist: CVoidPtr,
      nevents: CInt,
      timeout: Ptr[time.timespec]
  ): CInt = extern
}
