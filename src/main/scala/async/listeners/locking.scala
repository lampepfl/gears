/** Package listeners provide some auxilliary methods to work with listeners. */
package gears.async.listeners

import gears.async._
import Listener.{Locked, ListenerLock, Gone, PartialLock, LockMarker}
import scala.annotation.tailrec

/** Attempt to lock both listeners belonging to possibly different sources at the same time.
  * Lock orders are respected by comparing numbers on every step.
  *
  * Returns `Locked` on success, or the listener that fails first.
  */
def lockBoth[T, U](st: Async.Source[T], su: Async.Source[U])(lt: Listener[T], lu: Listener[U]): lt.type | lu.type | Locked.type =
  /* Step 1: weed out non-locking listeners */
  inline def lockedOr[V >: Locked.type](cause: lt.type | lu.type)(inline body: V) = if body == Locked then Locked else cause
  val tlt = lt.lock match
    case tl: ListenerLock => tl
    case null => return lockedOr(lu) { lu.lockCompletely(su) }
  val tlu = lu.lock match
    case tl: ListenerLock => tl
    case null => return lockedOr(lt) { lt.lockCompletely(st) }

  /* Attempts to advance locking one by one. */
  @tailrec
  def loop(mt: LockMarker, mu: LockMarker): lt.type | lu.type | Locked.type =
    inline def advanceSu(su: PartialLock): lt.type | lu.type | Locked.type = su.lockNext() match
      case Gone => { lt.releaseAll(mt); lu.releaseAll(mu); lu }
      case v: LockMarker => loop(mt, v)
    (mt, mu) match
      case (Locked, Locked) => Locked
      case (Locked, su: PartialLock) => advanceSu(su)
      case (st: PartialLock, su: PartialLock) if st.nextNumber < su.nextNumber => advanceSu(su)
      case (st: PartialLock, _) => st.lockNext() match
        case Gone => { lt.releaseAll(mt); lu.releaseAll(mu); lt }
        case v: LockMarker => loop(v, mu)

  /* We have to do the first locking step manually. */
  if tlt.selfNumber > tlu.selfNumber then
    val mt = tlt.lockSelf(st) match
      case Gone => return lt
      case v: LockMarker => v
    val mu = tlu.lockSelf(st) match
      case Gone => { lt.releaseAll(mt); return lu }
      case v: LockMarker => v
    loop(mt, mu)
  else
    val mu = tlu.lockSelf(st) match
      case Gone => return lu
      case v: LockMarker => v
    val mt = tlt.lockSelf(st) match
      case Gone => { lu.releaseAll(mu); return lt }
      case v: LockMarker => v
    loop(mt, mu)
end lockBoth
