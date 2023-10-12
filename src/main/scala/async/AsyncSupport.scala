package gears.async

trait Scheduler:
    def execute(body: Runnable): Unit
    def schedule(delayMillis: Long, body: Runnable): Cancellable

trait AsyncSupport:
    type Label[R]

    trait Suspension[-T, +R]:
        def resume(arg: T): R
        private[async] def resumeAsync(arg: T)(using sched: Scheduler): Unit =
            sched.execute(() => resume(arg))

    def boundary[R](body: Label[R] ?=> R): R
    private[async] def scheduleBoundary(body: Label[Unit] ?=> Unit)(using sched: Scheduler): Unit =
        sched.execute(() => boundary(body))

    /** Should return immediately if resume is called from within body */
    def suspend[T, R](body: Suspension[T, R] => R)(using Label[R]): T

object AsyncSupport:
    inline def apply()(using ac: AsyncSupport) = ac
