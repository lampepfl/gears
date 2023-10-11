package gears.async

trait SuspendSupport:
    this: AsyncSupport =>
    type Label[R]

    trait Suspension[-T, +R]:
        def resume(arg: T): R
        private[async] def resumeAsync(arg: T)(using Scheduler): Unit

    def boundary[R](body: Label[R] ?=> R): R
    private[async] def blockingBoundary[R](body: Label[Unit] ?=> R)(using Scheduler): R
    /** Should return immediately if resume is called from within body */
    def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T

trait SchedulerSupport:
    type Scheduler

    def execute(run: Runnable)(using Scheduler): Unit

trait AsyncSupport extends SuspendSupport with SchedulerSupport
object AsyncSupport:
    inline def apply()(using ac: AsyncSupport) = ac
