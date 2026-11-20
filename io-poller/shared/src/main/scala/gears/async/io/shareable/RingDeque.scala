package gears.async.io.shareable

/** A queue with two ends, of whoever guards it: an array that is gone round, and doubled when it is full.
  *
  * In place of `java.util.ArrayDeque`, which on Scala.js 1.22.0 loses what it holds when it grows after something has
  * been taken from its head: the elements it held read back as null, though it still counts them.
  */
private[io] final class RingDeque[A <: AnyRef] {
  private var slots = new Array[AnyRef | Null](16)
  private var head = 0
  private var count = 0

  private inline def at(index: Int): Int = (head + index) & (slots.length - 1)

  def size: Int = count
  def isEmpty: Boolean = count == 0

  private def grow(): Unit = {
    val grown = new Array[AnyRef | Null](count * 2)
    var i = 0
    while i < count do {
      grown(i) = slots(at(i))
      i += 1
    }
    slots = grown
    head = 0
  }

  def addLast(element: A): Unit = {
    if count == slots.length then grow()
    slots(at(count)) = element
    count += 1
  }

  def addFirst(element: A): Unit = {
    if count == slots.length then grow()
    head = (head - 1) & (slots.length - 1)
    slots(head) = element
    count += 1
  }

  /** The first element, taken; null if there is none. */
  def pollFirst(): A | Null =
    if count == 0 then null
    else {
      val element = slots(head)
      slots(head) = null
      head = (head + 1) & (slots.length - 1)
      count -= 1
      element.asInstanceOf[A]
    }

  /** Takes out the first of the elements that is `element` itself: whether there was one. */
  def remove(element: A): Boolean = {
    var i = 0
    while i < count && (slots(at(i)) ne element) do i += 1
    if i == count then false
    else {
      while i < count - 1 do {
        slots(at(i)) = slots(at(i + 1))
        i += 1
      }
      slots(at(count - 1)) = null
      count -= 1
      true
    }
  }

  /** The elements, first to last, in an array `make` makes of their number. */
  def toArray(make: Int => Array[A]): Array[A] = {
    val out = make(count)
    var i = 0
    while i < count do {
      out(i) = slots(at(i)).asInstanceOf[A]
      i += 1
    }
    out
  }

  def clear(): Unit = {
    var i = 0
    while i < count do {
      slots(at(i)) = null
      i += 1
    }
    head = 0
    count = 0
  }
}
