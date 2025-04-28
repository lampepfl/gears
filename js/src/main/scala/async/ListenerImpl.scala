package gears.async

import gears.async.js.JSPI

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.*
import scala.concurrent.duration.*
import scala.scalajs.js

/** Assumes top-level await, probably will crash real hard. */
private[async] trait NumberedLockImpl:
  protected val numberedLock = new Lock:
    var locked = false
    val queue = scala.collection.mutable.Queue[() => Unit]()

    override def newCondition(): Condition =
      throw NotImplementedError()

    override def tryLock(): Boolean =
      if locked then false
      else
        locked = true
        true

    override def tryLock(time: Long, unit: TimeUnit): Boolean =
      throw NotImplementedError()

    override def lock(): Unit =
      while !tryLock() do
        val promise = js.Promise[Unit]: (resolve, _) =>
          queue += (() => resolve(()))
        JSPI.await(promise)

    override def unlock(): Unit =
      assert(locked, "unlocking an unlocked lock")
      locked = false
      if !queue.isEmpty then queue.dequeue()()

    override def lockInterruptibly(): Unit =
      throw NotImplementedError()
