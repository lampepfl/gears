package gears.async.io.kqueue

import gears.async.io.BufferPool

class NativePool(val bufferSize: Int, val buffers: Int) extends BufferPool {

  override def available: Int = ???

  override def close(): Unit = ???

}
