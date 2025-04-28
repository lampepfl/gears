package gears.async

import java.util.concurrent.locks.*

private[async] trait NumberedLockImpl:
  protected val numberedLock: Lock = ReentrantLock()
