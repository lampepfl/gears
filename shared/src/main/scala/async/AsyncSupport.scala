package gears.async

import scala.concurrent.duration._

trait Suspension[-T, +R]:
  def resume(arg: T): R

trait SuspendSupport:
  type Label[R]
  type Suspension[-T, +R] <: gears.async.Suspension[T, R]

  def boundary[R](body: Label[R] ?=> R): R

  /** Should return immediately if resume is called from within body */
  def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T

/** Extends [[SuspendSupport]] with "asynchronous" boundary/resume functions, in the presence of a [[Scheduler]] */
trait AsyncSupport extends SuspendSupport:
  type Scheduler <: gears.async.Scheduler

  private[async] def resumeAsync[T, R](suspension: Suspension[T, R])(arg: T)(using s: Scheduler): Unit =
    s.execute(() => suspension.resume(arg))

  private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using s: Scheduler): Unit =
    s.execute(() => boundary(body))

trait Scheduler:
  def execute(body: Runnable): Unit
  def schedule(delay: FiniteDuration, body: Runnable): Cancellable

object AsyncSupport:
  inline def apply()(using ac: AsyncSupport) = ac
