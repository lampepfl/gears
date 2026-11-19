package gears.async.io
package eventpolling

import gears.async.io.shareable.TimerHeap.{TimerOp, TimerSource}
import gears.async.io.shareable.TimerHeapOp

/** For the ops these suites define: one is kept on any heap that takes ops of its own kind, `Self`. */
private[eventpolling] trait HeldOnOwnKind[Self <: TimerOp] extends TimerHeapOp { self: Self =>
  type Source = TimerSource { type Timer >: Self }
  final def _holdTimer(source: Source): Unit = source.addTimer(this)
}
