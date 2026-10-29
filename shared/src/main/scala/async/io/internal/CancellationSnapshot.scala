package gears.async.io.internal

import gears.async.Cancellable

/** A reusable group member whose cancellation must name the use registered with the group. Captured under the group's
  * lock, before a concurrent unlink can release that use. Capturing must not invoke callbacks or acquire member locks.
  * Ordinary, non-reusable cancellables need no snapshot. This allocates only when cancellation is requested.
  */
private[async] trait CancellationSnapshot extends Cancellable {
  private[async] def snapshotCancellation(): Cancellable
}
