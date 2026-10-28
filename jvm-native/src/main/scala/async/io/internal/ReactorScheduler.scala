package gears.async.io
package internal

import gears.async.Scheduler

/** A gears scheduler whose futures run on a `ReactorPool`'s workers, and so perform their ops on the workers' own
  * reactors (see `Pools.performOnReactor`): the pool itself, as on Scala Native, or a scheduler of virtual threads the
  * pool carries (`VirtualReactorScheduler`). The `OnPool` a program is given is made over it (`Pools.bound`).
  */
private[io] trait ReactorScheduler extends Scheduler {
  def backend: ReactorBackend

  /** The worker running the caller, whose reactor the caller may use directly. Every thread a program on this scheduler
    * runs on is a worker's, or the one that started the pool, so this throws only for a thread the program should never
    * have reached, such as one of its own making.
    */
  def currentWorker: ReactorWorker

  /** Keeps the caller on the thread `currentWorker` names until `unpin`, so that an op is submitted on the thread of
    * the worker it found: nothing to do where a caller cannot change thread, as on Scala Native; on the JVM, a virtual
    * thread carried by a worker is pinned to it (see `VirtualCarriers.pin`).
    */
  def pin(): Unit = ()

  def unpin(): Unit = ()
}
