package gears.async

private[async] abstract class AsyncImpl:
  import Async.FromSync
  given blockingImpl(using support: AsyncSupport, scheduler: support.Scheduler): FromSync.BlockingWithLocks =
    FromSync.BlockingWithLocks()
