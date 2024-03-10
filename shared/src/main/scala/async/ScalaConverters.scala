package gears.async

import scala.concurrent.{Future as StdFuture, Promise as StdPromise}
import scala.concurrent.ExecutionContext
import scala.util.Try

/** Converters from Gears types to Scala API types and back. */
object ScalaConverters:
  extension [T](fut: StdFuture[T])
    /** Converts a [[StdFuture]] into a gears [[Future]]. Requires an [[ExecutionContext]], as the job of completing the
      * returned [[Future]] will be done through this context. Since [[StdFuture]] cannot be cancelled, the returned
      * [[Future]] will *not* clean up the pending job when cancelled.
      */
    def asGears(using ExecutionContext): Future[T] =
      Future.withResolver[T]: resolver =>
        fut.andThen(result => resolver.complete(result))

  extension [T](fut: Future[T])
    /** Converts a gears [[Future]] into a Scala [[StdFuture]]. Note that if [[fut]] is cancelled, the returned
      * [[StdFuture]] will also be completed with `Failure(CancellationException)`.
      */
    def asScala: StdFuture[T] =
      val p = StdPromise[T]()
      fut.onComplete(Listener((res, _) => p.complete(res)))
      p.future
