/** Package listeners provide some auxilliary methods to work with listeners. */
package gears.async.listeners

import gears.async._
import Listener.{Locked, ListenerLock, Gone, PartialLock, LockMarker, LockResult}
import scala.annotation.tailrec

/** Two listeners being locked at the same time, while holding the same lock on their listener chains. This happens if
  * you attempt to lockBoth two listeners with a common downstream listener, e.g., two derived listeners of the same
  * race.
  */
case class ConflictingLocksException(
    base: (Listener[?], Listener[?]),
    conflict: ((ListenerLock | PartialLock), (ListenerLock | PartialLock))
) extends Exception

/** Attempt to lock both listeners belonging to possibly different sources at the same time. Lock orders are respected
  * by comparing numbers on every step.
  *
  * Returns [[Locked]] on success, or the listener that fails first.
  *
  * In the case that two locks sharing the same number is encountered, [[ConflictingLocksException]] is thrown with the
  * base listeners and conflicting listeners.
  */
def lockBoth[T, U](st: Async.Source[T], su: Async.Source[U])(
    lt: Listener[T],
    lu: Listener[U]
): lt.type | lu.type | Locked.type =
  /* Step 1: weed out non-locking listeners */
  inline def lockedOr[V >: Locked.type](cause: lt.type | lu.type)(inline body: V) =
    if body == Locked then Locked else cause
  val tlt = lt.lock match
    case tl: ListenerLock => tl
    case null             => return lockedOr(lu) { lu.lockCompletely(su) }
  val tlu = lu.lock match
    case tl: ListenerLock => tl
    case null             => return lockedOr(lt) { lt.lockCompletely(st) }

  /* Attempts to advance locking one by one. */
  @tailrec
  def loop(mt: LockMarker, mu: LockMarker): lt.type | lu.type | Locked.type =
    inline def advanceSu(su: PartialLock): lt.type | lu.type | Locked.type = su.lockNext() match
      case Gone          => { tlt.releaseAll(mt); tlu.releaseAll(mu); lu }
      case v: LockMarker => loop(mt, v)
    (mt, mu) match
      case (Locked, Locked)          => Locked
      case (Locked, su: PartialLock) => advanceSu(su)
      case (st: PartialLock, su: PartialLock) if st.nextNumber == su.nextNumber =>
        tlt.releaseAll(mt); tlu.releaseAll(mu)
        throw ConflictingLocksException((lt, lu), (st, su))
      case (st: PartialLock, su: PartialLock) if st.nextNumber < su.nextNumber => advanceSu(su)
      case (st: PartialLock, _) =>
        st.lockNext() match
          case Gone          => { tlt.releaseAll(mt); tlu.releaseAll(mu); lt }
          case v: LockMarker => loop(v, mu)

  /* Attempt to lock the ListenerLock and advance until we start needing to lock the other one. */
  inline def lockUntilLessThan(other: ListenerLock)(src: Async.Source[?], tl: ListenerLock): LockResult =
    @tailrec def loop(v: LockMarker): LockResult =
      v match
        case Locked => Locked
        case v: PartialLock if v.nextNumber == other.selfNumber =>
          tl.releaseAll(v)
          throw ConflictingLocksException((lt, lu), if lt.lock == other then (other, v) else (v, other))
        case v: PartialLock if v.nextNumber < other.selfNumber => v
        case v: PartialLock =>
          v.lockNext() match
            case Gone          => tl.releaseAll(v); Gone
            case m: LockMarker => loop(m)
    tl.lockSelf(src) match
      case Gone          => Gone
      case m: LockMarker => loop(m)

  /* We have to do the first locking step manually. */
  if tlt.selfNumber == tlu.selfNumber then throw ConflictingLocksException((lt, lu), (tlt, tlu))
  else if tlt.selfNumber > tlu.selfNumber then
    val mt = lockUntilLessThan(tlu)(st, tlt) match
      case Gone          => return lt
      case v: LockMarker => v
    val mu = tlu.lockSelf(su) match
      case Gone          => { tlt.releaseAll(mt); return lu }
      case v: LockMarker => v
    loop(mt, mu)
  else
    val mu = lockUntilLessThan(tlt)(su, tlu) match
      case Gone          => return lu
      case v: LockMarker => v
    val mt = tlt.lockSelf(st) match
      case Gone          => { tlu.releaseAll(mu); return lt }
      case v: LockMarker => v
    loop(mt, mu)
end lockBoth
