package gears.async

private[async] abstract class AsyncImpl:
  import Async.FromSync
  given blockingImpl: FromSync.BlockingWithLocks.type = FromSync.BlockingWithLocks
