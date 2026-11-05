package gears.async.io.reference

import gears.async.Async
import gears.async.io.Received
import gears.async.io.Repeatable
import gears.async.io.Slot
import gears.async.io.testkit.ContractSuite

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** Regression checks for legal partial transfers, without relying on kernel chunk sizes. */
class ContractHelperSuite extends ContractSuite {
  val backend: ReferenceBackend.type = ReferenceBackend

  private def shortRead(buf: ByteBuffer, input: Array[Byte]): RefOp = new RefOp with Repeatable {
    private var offset = 0
    protected def body(ctx: Ctx)(using Async): Unit =
      if offset < input.length then {
        buf.put(input(offset))
        offset += 1
      }
  }

  test("readExactly repeats short reads, respects its target and restores the buffer limit") {
    running { io ?=>
      val buf = allocate(16)
      val read = shortRead(buf, "abcd".getBytes(StandardCharsets.UTF_8))
      readExactly(io, read, buf, 3)
      assertEquals(text(buf), "abc")
      assertEquals(buf.limit(), 16)
      readExactly(io, read, buf, 4)
      assertEquals(text(buf), "abcd")
    }
  }

  test("readExactly reports early EOF and restores the buffer limit") {
    running { io ?=>
      val buf = allocate(16)
      val read = shortRead(buf, Array[Byte](42))
      intercept[EOFException](readExactly(io, read, buf, 3))
      assertEquals(buf.position(), 1)
      assertEquals(buf.limit(), 16)
    }
  }

  test("pooled accumulation releases each short chunk and decodes UTF-8 across chunk boundaries") {
    running { io ?=>
      val expected = "a\u00e9\ud83d\ude42z"
      val input = expected.getBytes(StandardCharsets.UTF_8)
      val into = Slot[Received]()
      var offset = 0
      var released = 0
      val read = new RefOp with Repeatable {
        override protected def slots = List(into)
        protected def body(ctx: Ctx)(using Async): Unit = {
          val next = ByteBuffer.wrap(Array(input(offset)))
          offset += 1
          into.set(new Received {
            val buffer = next
            def release(): Unit = released += 1
          })
        }
      }
      assertEquals(readPooledExactly(io, read, into, input.length), expected)
      assertEquals(released, input.length)
      assert(into.isEmpty)
    }
  }
}
