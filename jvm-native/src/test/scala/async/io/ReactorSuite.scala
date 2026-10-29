package gears.async.io

import gears.async.io.internal.Completion
import gears.async.io.internal.Reactor
import gears.async.io.internal.ReactorBackend
import gears.async.io.internal.Submitter

import munit.FunSuite

class ReactorSuite extends FunSuite {

  /** A reactor that only records whether it was closed. */
  private final class ClosingReactor extends Reactor {
    type Op = gears.async.io.internal.ReactorOperation
    type Handle = AnyRef
    var closed = false
    def ops: Ops[Op, Handle] = throw new UnsupportedOperationException
    def submit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Unit = ()
    def trySubmit[C <: Op](op: C, completion: Completion[C], timeoutMillis: Int): Boolean = false
    def cancel(op: Op): Boolean = false
    def attachment(op: Op): AnyRef | Null = null
    def attach(op: Op, value: AnyRef | Null): Unit = ()
    def runOnce(block: Boolean): Unit = ()
    def wakeup(): Unit = ()
    def run(): Unit = ()
    def stop(): Unit = ()
    def close(): Unit = closed = true
  }

  test("scoped closes the reactor its factory opened, even when the body fails") {
    val reactor = new ClosingReactor
    given Reactor.Factory[Reactor] = () => reactor
    intercept[IllegalStateException](Reactor.scoped(_ => throw new IllegalStateException("boom")))
    assert(reactor.closed)
  }

  test("a completion ignores cancellation by default") {
    val completion: Completion[AnyRef] = _ => ()
    completion.onCancel(new Object)
  }

  test("a completion's failures propagate by default") {
    val completion: Completion[AnyRef] = _ => ()
    val failure = new RuntimeException("boom")
    val thrown = intercept[RuntimeException](completion.onFailure(new Object, failure))
    assert(thrown eq failure)
  }

  test("an op built by one reactor cannot be submitted to another") {
    val errors = compileErrors(
      "def cross(a: Reactor, b: Reactor): Unit = b.submit(a.ops.timer(1), _ => ())"
    )
    assert(errors.contains("a.Op"), errors)
  }

  test("an op built by a backend can be submitted to any reactor it opens, and not to another backend's") {
    assertEquals(
      compileErrors(
        "def move(b: ReactorBackend): Unit = { val t = b.ops.timer(1); b.open().submit(t, _ => ()); b.open().submit(t, _ => ()) }"
      ),
      ""
    )
    val errors =
      compileErrors("def cross(a: ReactorBackend, b: ReactorBackend): Unit = b.open().submit(a.ops.timer(1), _ => ())")
    assert(errors.contains("a.Op"), errors)
  }

  test("a backend is a factory for its reactors") {
    val reactor = new ClosingReactor
    given ReactorBackend = new ReactorBackend {
      type Op = gears.async.io.internal.ReactorOperation
      type Handle = AnyRef
      type Readable = AnyRef
      type Writable = AnyRef
      type Connection = AnyRef
      type Listener = AnyRef
      type Datagram = AnyRef
      type UnixDatagram = AnyRef
      type File = AnyRef
      def ops: Kinded[Ops[Op, Handle]] = throw new UnsupportedOperationException
      def attachment(op: Op): AnyRef | Null = null
      def attach(op: Op, value: AnyRef | Null): Unit = ()
      def open(): Reactor.Of[Op, Handle] = reactor
      def pool(bufferSize: Int, buffers: Int): BufferPool = throw new UnsupportedOperationException
    }
    Reactor.scoped(_ => ())
    assert(reactor.closed)
  }

  test("an op on the wrong kind of handle does not compile") {
    assert(
      compileErrors("def a(b: IpSockets)(l: b.Listener) = b.ops.read(l, (null: java.nio.ByteBuffer))").nonEmpty
    )
    assert(
      compileErrors(
        "def a(b: IpSockets)(d: b.BoundDatagram) = b.ops.write(d, (null: java.nio.ByteBuffer))"
      ).nonEmpty
    )
    assert(compileErrors("def a(b: IpSockets)(c: b.Connection) = b.ops.accept(c, Slot[b.Connection]())").nonEmpty)
    assert(compileErrors("def a(b: IpSockets)(l: b.Listener) = b.ops.shutdownOutput(l)").nonEmpty)
    assert(
      compileErrors("def a(b: IpSockets)(c: b.Connection) = b.ops.send(c, (null: java.nio.ByteBuffer))").nonEmpty
    )
    assert(
      compileErrors(
        "def a(b: UnixDatagrams)(d: b.UnixBoundDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer), IpAddress.IPv4(\"h\", 1))"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "def a(b: RegularFiles & IpSockets)(c: b.Connection) = b.ops.read(c, (null: java.nio.ByteBuffer), 0L)"
      ).nonEmpty
    )
    assert(compileErrors("def a(b: Pipes)(r: b.Readable) = b.ops.write(r, (null: java.nio.ByteBuffer))").nonEmpty)
    assert(compileErrors("def a(b: IpSockets)(c: b.Connection): b.Listener = c").nonEmpty)
    assertEquals(
      compileErrors(
        "def a(b: IpSockets & RegularFiles)(c: b.Connection, f: b.File) = { b.ops.read(c, (null: java.nio.ByteBuffer)); b.ops.read(f, (null: java.nio.ByteBuffer)) }"
      ),
      ""
    )
  }

  test(
    "a bound datagram socket sends to an address and hears from anyone; a connected one, to and from its peer alone"
  ) {
    assert(
      compileErrors("def a(b: IpSockets)(d: b.BoundDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer))").nonEmpty
    )
    assert(
      compileErrors(
        "def a(b: IpSockets)(d: b.ConnectedDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer), IpAddress.IPv4(\"h\", 1))"
      ).nonEmpty
    )
    assert(compileErrors("def a(b: IpSockets)(d: b.BoundDatagram) = b.ops.receive(d, null)").nonEmpty)
    assert(
      compileErrors(
        "def a(b: IpSockets)(d: b.ConnectedDatagram) = b.ops.receive(d, null, Slot[IpAddress]())"
      ).nonEmpty
    )
    assert(compileErrors("def a(b: IpSockets)(d: b.ConnectedDatagram): b.BoundDatagram = d").nonEmpty)
    assert(
      compileErrors(
        "def a(b: UnixDatagrams)(d: b.UnixConnectedDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer), null)"
      ).nonEmpty
    )
    assertEquals(
      compileErrors(
        "def a(b: IpSockets)(d: b.BoundDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer), IpAddress.IPv4(\"h\", 1))"
      ),
      ""
    )
    assertEquals(
      compileErrors("def a(b: IpSockets)(d: b.ConnectedDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer))"),
      ""
    )
    assertEquals(compileErrors("def a(b: IpSockets)(d: b.ConnectedDatagram) = b.ops.receive(d, null)"), "")
    assertEquals(
      compileErrors(
        "def a(b: UnixDatagrams)(d: b.UnixConnectedDatagram) = b.ops.send(d, (null: java.nio.ByteBuffer))"
      ),
      ""
    )
  }

  test("each address family and transport is a capability of its own: IP streams need not bring IP datagrams") {
    assert(
      compileErrors(
        "def d(b: IpStreams) = b.ops.bindDatagram(IpAddress.IPv4(\"h\", 1), Slot[b.BoundDatagram]())"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "def c(b: IpDatagrams) = b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]())"
      ).nonEmpty
    )
    assertEquals(
      compileErrors("def c(b: IpStreams) = b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]())"),
      ""
    )
    assertEquals(
      compileErrors(
        "def d(b: IpDatagrams) = b.ops.bindDatagram(IpAddress.IPv4(\"h\", 1), Slot[b.BoundDatagram]())"
      ),
      ""
    )
    assertEquals(
      compileErrors("def a(b: StreamSockets)(l: b.Listener) = b.ops.accept(l, Slot[b.Connection]())"),
      ""
    )
    assertEquals(compileErrors("def s(b: IpSockets): IpStreams & IpDatagrams = b"), "")
  }

  test("Unix and IP sockets are separate capabilities: a backend with one need not have the other") {
    assert(
      compileErrors(
        "def u(b: UnixStreams) = b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]())"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "def u(b: UnixDatagrams) = b.ops.bindDatagram(IpAddress.IPv4(\"h\", 1), Slot[b.BoundDatagram]())"
      ).nonEmpty
    )
    assert(compileErrors("def u(b: IpSockets) = b.ops.bindDatagram(UnixAddress(\"p\"), Slot())").nonEmpty)
    assertEquals(
      compileErrors(
        "def u(b: UnixStreams & IpSockets) = { b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]()); b.ops.connect(UnixAddress(\"p\"), Slot[b.Connection]()) }"
      ),
      ""
    )
  }

  test("only ops marked Repeatable promise they can be resubmitted") {
    assertEquals(
      compileErrors("def repeat(b: IpSockets)(h: b.Listener): Repeatable = b.ops.accept(h, Slot[b.Connection]())"),
      ""
    )
    assertEquals(compileErrors("def repeat(a: Reactor): Repeatable = a.ops.timer(1)"), "")
    assert(compileErrors("def repeat(b: Blocking): Repeatable = b.ops.blocking(() => ())").nonEmpty)
  }

  test("sockets, files, pipes and name resolution are capabilities a backend has, not every backend") {
    assert(compileErrors("def c(b: Backend) = b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]())").nonEmpty)
    assert(compileErrors("def l(b: Backend) = b.ops.listen(IpAddress.IPv4(\"h\", 1), Slot[b.Listener]())").nonEmpty)
    assert(compileErrors("def p(b: Backend) = b.ops.pipe(Slot[b.Readable](), Slot[b.Writable]())").nonEmpty)
    assert(compileErrors("def u(b: IpSockets) = b.ops.connect(UnixAddress(\"p\"), Slot[b.Connection]())").nonEmpty)
    assert(compileErrors("def r(b: Backend) = b.ops.resolve(\"h\", Slot())").nonEmpty)
    assert(compileErrors("def f(b: Backend) = b.ops.open(\"p\", false, Slot[b.File]())").nonEmpty)
    assertEquals(
      compileErrors("def c(b: IpSockets) = b.ops.connect(IpAddress.IPv4(\"h\", 1), Slot[b.Connection]())"),
      ""
    )
    assertEquals(
      compileErrors("def l(b: IpSockets) = b.ops.listen(IpAddress.IPv4(\"h\", 1), Slot[b.Listener]())"),
      ""
    )
    assertEquals(
      compileErrors("def u(b: UnixStreams) = b.ops.connect(UnixAddress(\"p\"), Slot[b.Connection]())"),
      ""
    )
    assertEquals(
      compileErrors("def u(b: UnixStreams)(h: b.Listener) = b.ops.accept(h, Slot[b.Connection]())"),
      ""
    )
    assertEquals(compileErrors("def p(b: Pipes) = b.ops.pipe(Slot[b.Readable](), Slot[b.Writable]())"), "")
    assertEquals(compileErrors("def r(b: Resolving) = b.ops.resolve(\"h\", Slot())"), "")
    assertEquals(compileErrors("def f(b: RegularFiles) = b.ops.open(\"p\", false, Slot[b.File]())"), "")
    assertEquals(
      compileErrors("def s(b: RegularFiles)(h: b.File): Repeatable = b.ops.read(h, (null: java.nio.ByteBuffer), 0L)"),
      ""
    )
    assertEquals(
      compileErrors("def t(b: Backend)(h: b.Readable): Repeatable = b.ops.read(h, (null: java.nio.ByteBuffer))"),
      ""
    )
    assertEquals(
      compileErrors("def v(b: Backend)(h: b.Readable): Repeatable = b.ops.read(h, Array[java.nio.ByteBuffer]())"),
      ""
    )
    assertEquals(
      compileErrors("def w(b: Backend)(h: b.Writable): Repeatable = b.ops.write(h, Array[java.nio.ByteBuffer]())"),
      ""
    )
  }

  test("a reactor is a submitter with a loop; a submitter keeps its ops' attachments, a backend does not") {
    assertEquals(compileErrors("def s(r: Reactor): Submitter = r"), "")
    assertEquals(compileErrors("def sub(s: Submitter): Unit = s.submit(s.ops.timer(1), _ => ())"), "")
    assert(compileErrors("def turn(s: Submitter): Unit = s.runOnce(false)").nonEmpty)
    assertEquals(compileErrors("def a(s: Submitter)(op: s.Op) = s.attachment(op)"), "")
    assert(compileErrors("def a(b: ReactorBackend)(op: b.Op) = b.attachment(op)").nonEmpty)
    assert(compileErrors("def o(b: Backend) = b.open()").nonEmpty)
  }

  test("the public interface is Backend and Io: reactors, submissions and the kinds of Io are internal") {
    assert(compileErrors("def f(x: _root_.gears.async.io.Reactor) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.Submitter) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.Completion[Any]) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.OnPool[ReactorBackend]) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.OnCaller[CallerBackend]) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.ReactorBackend) = x").nonEmpty)
    assert(compileErrors("def f(x: _root_.gears.async.io.CallerBackend) = x").nonEmpty)
    assertEquals(compileErrors("def f(x: _root_.gears.async.io.internal.OnPool[ReactorBackend]) = x"), "")
  }

  test("blocking ops are a capability a backend has, not every backend; readiness is no backend's, only kqueue's own") {
    assert(compileErrors("def block(b: Backend) = b.ops.blocking(() => ())").nonEmpty)
    assertEquals(compileErrors("def block(b: Blocking) = b.ops.blocking(() => ())"), "")
    assert(compileErrors("def ready(b: ReactorBackend & Pipes)(h: b.Readable) = b.ops.ready(h, null)").nonEmpty)
    assert(compileErrors("def now(b: ReactorBackend & Pipes)(h: b.Readable) = b.ops.readNow(h, null)").nonEmpty)
  }

  test("a slot is empty until an implementation writes to it") {
    val slot = Slot[String]()
    intercept[IllegalStateException](slot.clear())
    slot.set("written")
    assertEquals(slot.clear(), "written")
    intercept[IllegalStateException](slot.clear()) // clearing takes the value out
  }

  test("a slot must be cleared before it is written again") {
    val slot = Slot[String]()
    slot.set("first")
    intercept[IllegalStateException](slot.set("second"))
    assertEquals(slot.clear(), "first")
    slot.set("second")
    assertEquals(slot.clear(), "second")
  }

  test("completions carry no result") {
    assert(compileErrors("val c: Completion[AnyRef] = (_, _) => ()").nonEmpty)
  }

  test("an op built by a reactor can be submitted to it") {
    val errors = compileErrors(
      "def same(a: Reactor): Unit = a.submit(a.ops.timer(1), _ => ())"
    )
    assertEquals(errors, "")
  }
}
