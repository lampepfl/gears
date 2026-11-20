package gears.async

import language.experimental.captureChecking

import java.util.concurrent.locks.*

private[async] trait NumberedLockImpl:
  protected val numberedLock: Lock = ReentrantLock()
