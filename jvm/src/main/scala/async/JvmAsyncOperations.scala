package gears.async

object JvmAsyncOperations extends AsyncOperations:

  private def jvmInterruptible[T](fn: => T)(using Async): T =
    val th = Thread.currentThread()
    cancellationScope(() => th.interrupt()):
      try fn
      catch case _: InterruptedException => throw new CancellationException()

  override def sleep(millis: Long)(using Async): Unit =
    jvmInterruptible(Thread.sleep(millis))
