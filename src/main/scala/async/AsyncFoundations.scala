package async

import scala.util.Try

trait Suspension[-T, +R]:
    def resume(arg: T): R
    def resumeAsync(arg: T): Unit

type WaitSuspension = Suspension[Try[Unit], Unit]

trait SuspendFoundations:
    type Label[R]

    def boundary[R](body: Label[R] ?=> R): R
    def blockingBoundary[R](body: Label[Unit] ?=> R): R
    def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T

trait SchedulerFoundations:
    def execute(run: Runnable): Unit

    def sleep(millis: Long, cancelHandler: WaitSuspension => Unit = _ => {}): Unit

trait AsyncFoundations extends SuspendFoundations with SchedulerFoundations

val AsyncFoundations: AsyncFoundations = VThreadFoundations
