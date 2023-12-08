package gears.async

trait AsyncOperations:
  def sleep(millis: Long)(using Async): Unit

object AsyncOperations:
  inline def sleep(millis: Long)(using AsyncOperations, Async): Unit =
    summon[AsyncOperations].sleep(millis)
