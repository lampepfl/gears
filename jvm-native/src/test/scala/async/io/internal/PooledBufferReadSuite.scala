package gears.async.io.internal

import gears.async.io.{Received, Slot}

import java.io.IOException
import java.nio.ByteBuffer

class PooledBufferReadSuite extends munit.FunSuite {
  private class Pool extends FreeListPool(bufferSize = 8, buffers = 1) {
    type S = Slot
    fill(i => new Slot(i, ByteBuffer.allocate(bufferSize)))
    protected def freeMemory(): Unit = ()
  }

  test("would-block returns the borrowed buffer and publishes nothing") {
    val pool = new Pool
    val into = Slot[Received]()
    try {
      assertEquals(PooledBufferRead.attempt(pool, into)(_ => 0), 0)
      assert(into.isEmpty)
      assertEquals(pool.available, 1)
    } finally pool.close()
  }

  test("a throwing read returns its buffer and preserves the failure") {
    val pool = new Pool
    val into = Slot[Received]()
    val failure = new IOException("read failed")
    try {
      val observed = intercept[IOException] {
        PooledBufferRead.attempt(pool, into) { buffer =>
          buffer.put(1.toByte)
          throw failure
        }
      }
      assert(observed eq failure)
      assert(into.isEmpty)
      assertEquals(pool.available, 1)
      PooledBufferRead.attempt(pool, into) { buffer =>
        assertEquals(buffer.position(), 0)
        assertEquals(buffer.remaining(), pool.bufferSize)
        0
      }
    } finally pool.close()
  }

  test("a successful read transfers a flipped buffer until the consumer releases it") {
    val pool = new Pool
    val into = Slot[Received]()
    try {
      val count = PooledBufferRead.attempt(pool, into) { buffer =>
        buffer.put(1.toByte).put(2.toByte)
        2
      }
      assertEquals(count, 2)
      assertEquals(pool.available, 0)
      val received = into.clear()
      try {
        assertEquals(received.buffer.position(), 0)
        assertEquals(received.buffer.remaining(), 2)
        assertEquals(received.buffer.get(), 1.toByte)
        assertEquals(received.buffer.get(), 2.toByte)
      } finally received.release()
      assertEquals(pool.available, 1)
    } finally pool.close()
  }

  test("EOF transfers an empty buffer instead of retrying") {
    val pool = new Pool
    val into = Slot[Received]()
    try {
      assertEquals(PooledBufferRead.attempt(pool, into)(_ => -1), -1)
      val received = into.clear()
      try assertEquals(received.buffer.remaining(), 0)
      finally received.release()
    } finally pool.close()
  }

  test("pool exhaustion neither attempts the read nor publishes a buffer") {
    val pool = new Pool
    val held = pool.take().nn
    val into = Slot[Received]()
    var called = false
    try {
      intercept[IOException] {
        PooledBufferRead.attempt(pool, into) { _ => called = true; 1 }
      }
      assert(!called)
      assert(into.isEmpty)
      assertEquals(pool.available, 0)
    } finally {
      held.release()
      pool.close()
    }
  }

  test("failure to publish also returns the borrowed buffer") {
    val pool = new Pool
    val into = Slot[Received]()
    val existing = new Received {
      def buffer: ByteBuffer = ByteBuffer.allocate(0)
      def release(): Unit = ()
    }
    into.set(existing)
    try {
      intercept[IllegalStateException] {
        PooledBufferRead.attempt(pool, into) { buffer =>
          buffer.put(1.toByte)
          1
        }
      }
      assert(into.clear() eq existing)
      assertEquals(pool.available, 1)
    } finally pool.close()
  }
}
