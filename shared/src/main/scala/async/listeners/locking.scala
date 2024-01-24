/** Package listeners provide some auxilliary methods to work with listeners. */
package gears.async.listeners

import gears.async._
import Listener.ListenerLock
import scala.annotation.tailrec

/** Two listeners being locked at the same time, while holding the same lock on their listener chains. This happens if
  * you attempt to lockBoth two listeners with a common downstream listener, e.g., two derived listeners of the same
  * race.
  */
case class ConflictingLocksException(
    listeners: (Listener[?], Listener[?])
) extends Exception

/** Attempt to lock both listeners belonging to possibly different sources at the same time. Lock orders are respected
  * by comparing numbers on every step.
  *
  * Returns [[Locked]] on success, or the listener that fails first.
  *
  * In the case that two locks sharing the same number is encountered, [[ConflictingLocksException]] is thrown with the
  * base listeners and conflicting listeners.
  */
def lockBoth[T, U](
    lt: Listener[T],
    lu: Listener[U]
): lt.type | lu.type | true =
  val lockT = if lt.lock == null then return (if lu.lockCompletely() then true else lu) else lt.lock
  val lockU = if lu.lock == null then return (if lt.lockCompletely() then true else lt) else lu.lock

  inline def doLock[T, U](lt: Listener[T], lu: Listener[U])(
      lockT: ListenerLock,
      lockU: ListenerLock
  ): lt.type | lu.type | true =
    // assert(lockT.number > lockU.number)
    if !lockT.lockSelf() then lt
    else if !lockU.lockSelf() then
      lockT.release()
      lu
    else true

  if lockT.selfNumber == lockU.selfNumber then throw ConflictingLocksException((lt, lu))
  else if lockT.selfNumber > lockU.selfNumber then doLock(lt, lu)(lockT, lockU)
  else doLock(lu, lt)(lockU, lockT)
end lockBoth
