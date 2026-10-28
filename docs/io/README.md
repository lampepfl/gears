# gears.async.io: asynchronous I/O for gears

An interface for asynchronous I/O that gears programs write once and run on any backend, and two backends: kqueue on
Scala Native (macOS, and the parts of it Linux shares) and NIO on the JVM (any platform). A third, over Apple's
Network.framework, is built on kqueue's reactors and comes separately ([`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md)). A program performs ops, built by its backend, with the `Io` the runner gives it, and gears is gears around
them: futures, groups, cancellation, `sleep` and `withTimeout` all work, on the backend's own timers.

## Where to start reading

1. **The public interface**, in `shared/src/main/scala/async/io/`: `Io.scala` (running a program and performing ops),
   `Backend.scala` (backends and their capabilities), `Ops.scala` (every op and the rules they share),
   `SocketOptions.scala`, `Addresses.scala`, `Deadline.scala` and `BufferPool.scala`. About 1,000 lines, and all a
   program sees.
2. **The contract suites**, in `io-testkit/`: what every backend must do, checked the same way on each.
   `FuturesContract` checks programs through `perform`; `ReactorContract` and the per-capability contracts
   (`IpStreamContract`, `IpDatagramContract`, `UnixStreamContract` and the rest) check the backends underneath.
3. **One backend**: NIO (`io-backends/nio/`) is the shortest; kqueue (`io-backends/kqueue/`) the one measured most.
4. **The higher level**, `gears.io.higherlevel` in `io-testkit/`: an API with result-returning openers and streams, written
   over the public interface alone, which shows what the interface is for.
5. **[`interface-history.md`](interface-history.md)**, for why any of it is the shape it is.

## Layout

| where | holds | depends on |
| --- | --- | --- |
| `gears.async.io` (core, `shared/`) | the interface: `Io` and `Io.run`, `Backend` and its capabilities, `Ops`, `Slot`, addresses, socket options, `Deadline`, `BufferPool` | the rest of gears |
| `gears.async.io.internal` (core, `shared/`) | what runs a backend's ops, which the runtime and the backends share and programs do not use: `Submitter`, `Reactor`, `Completion`, a backend's kinds (`ReactorBackend`, `CallerBackend`), `Io`'s kinds (`OnPool`, `OnCaller`), `Turns`, the checks every backend makes (`Checks`) | the interface |
| `gears.async.io.internal` (core, `jvm-native/`, `jvm/`, `native/`, `js/`) | the runtime (`Runner`, `ReactorPool`, `ReactorWorker`, `RunQueue`, `ReactorScheduler`, `Pools`, `VirtualCarriers`), the runner's knobs (`ReactorRuns`, `TakePolicy`) and what the backends share (`Blockers`, `TimerHeap`; on Native, `NativePool`, `NativeBuffer` and the Posix sockets, files and resolver) | the interface |
| `ioKqueue`, `ioNio` (`io-backends/`) | the backends, in `gears.async.io.kqueue` and `.nio` | core |
| `ioTestkit` (`io-testkit/`) | the contract suites every backend runs, `BufferArena`, `higherlevel` (`gears.io.higherlevel`) with `HigherLevelChecks`, and the gears demos (`gears.io.demos`) with their command line, `DemoCommands`, each demo taking the `Io` and `Async` it runs with | core, munit, mainargs |
| `ioKqueueDemos`, `ioNioDemos` (`io-backends/*/demos/`) | each backend's demo application: the backends it offers the demos (`DemoBackends`) and what starts their runs (`DemoRunner`, with `Io.run`); kqueue's also has the plain kqueue demos | a backend, the testkit |
| `ioBench` (`bench/io-bench/`) | the bench (`gearsbench`): `GearsBench`'s scenarios over each platform's demo backends, kqueue's `move-bench` and `KqueueLoop` reference, the JDK's (`JdkBench`) | the demo applications |
| `ioDemosTests` (`io-tests/`) | JVM tests driving the demo and bench executables as subprocesses (macOS) | the demo applications, the bench |

The interface is in `shared/`, so it compiles on every platform gears does, Scala.js included, where `Io.run`
refuses until a backend exists there ([`hosts.md`](hosts.md)); the runtime runs on the JVM and Scala Native, which
have threads to drive reactors with.

The public interface is `Io` and `Backend`, with the ops, handles and options they use. Everything in
`gears.async.io.internal` is unsupported: submitting an op to a reactor directly is possible, for the runtime, the
backends, their contract suites and one demo of it (`ReactorServerSocket`), but no program needs to. What the build
does not enforce, it hides by package (`private[io]`), which `internal` and every backend's package are under, so a
backend outside this repository would be written in a package under `gears.async.io` too. A handle is an object of
its backend's own class, whose constructor is private to the backend, so a program can neither forge one nor pass one
backend's handle to another, whose `Handle` is a different type.

## The concepts

- **A capability** is what a backend can do, a trait each backend mixes in and a program asks for by intersection:
  `IpStreams`, `IpDatagrams` (`IpSockets` is both), `UnixStreams`, `UnixDatagrams`, `RegularFiles`, `Pipes`, `Fifos`,
  `Resolving`, `Blocking`. Each narrows the backend's `Ops`; the base `Ops`, which every backend has, is read, write,
  the pooled read, timers and close. A capability is a `Backend` itself, so a helper asks for `IpStreams` or
  `IpStreams & RegularFiles`, and a backend without one, such as a browser's, is refused when compiling.
- **A handle's kind** is in its type, a subtype of the backend's `Handle`: `Readable` and `Writable` streams, a
  `Connection` (both, and shut down for writing), a `Listener`, datagram sockets bound (`BoundDatagram`) or connected
  (`ConnectedDatagram`), each also Unix, and a `File`. Every handle is opened by an op of the capability that has it,
  which writes it into a slot; an op takes the kind it works on, so an op on the wrong kind does not compile.
- **`Io`** is where a program's ops run: `Io[B]` has a backend, `backend: B`, and adds only `perform`. The runner
  gives it to the program; it is the program's gears `AsyncOperations` too. How it performs depends on the backend,
  internally: over a backend with reactors, on a pool of workers driving them, one per processor; over one without,
  such as `NioOnCaller`, on the calling thread.

## A program

```scala
def echo(using io: Io[IpStreams])(client: io.Connection)(using Async): Unit = {
  val buf = ...
  val read = io.backend.ops.read(client, buf) // an op is a mutable record the caller owns, and reuses
  val write = io.backend.ops.write(client, buf)
  while { buf.clear(); io.perform(read); buf.position() > 0 } do {
    buf.flip()
    while buf.hasRemaining() do io.perform(write)
  }
}

Io.run(using NioBackend) { ... echo(client) ... }
```

`Io.run` returns what the program returns, as `Async.blocking` does, and gives it its `Io` as a context parameter
beside `Async.Spawn`. An op's type and a handle's are path-dependent on the `Io` (`io.Op`, `io.Connection`), so a
method taking a handle takes the `Io` first. Ops in flight together are futures performing them
(`Future(io.perform(op))`), raced, awaited and cancelled as futures are. A time limit is
`io.perform(op, 2.seconds)`.

## The rules every backend keeps

Each is stated on the interface where it applies, and checked by the contract suites on every backend:

- **Cancelling or timing out** an op stops it: an op that waits for its handle has done nothing, so a read that
  timed out took no bytes and leaves the connection usable; an opener leaves its slot empty and nothing open; once
  `perform` returns or throws, the op's buffers and slots are the caller's again (`Io.perform`).
- **Misuse fails as the op is built**, with an `IllegalArgumentException`, never ignored: an address that is not
  numeric, an option a backend lacks or that does not apply, an empty packet on a connected socket, a read into a
  buffer with no room or a read-only one. I/O failures are `IOException`s.
- **One op at a time on a stream's direction**: a second read, or write, pending is refused, since two would take or
  interleave the stream's bytes. A datagram socket's sends and receives and a listener's accepts, each whole, take
  turns instead, in the order they came (`Ops`).
- **Any buffer** is taken, on the heap or not; a backend whose system calls need native memory copies through its own
  (`Ops`).
- **Options** are given as a socket opens, `NoDelay` on unless set off; what a backend supports, for each kind of
  socket, is asked of it when the program runs (`SocketOptionOps`).
- **Closing** fails the ops waiting on the handle, completes at once, and frees the port or path (`Ops.close`). The
  ops it ends, and any op submitted on the handle after, a second close too, fail with a closed channel's failure,
  `ClosedChannelException` naming the handle, on every backend.

Left to a higher level, on purpose: ops, `perform` and slots are mutable records the caller owns and reuses, the
lowest level there is. A result-returning or streaming API wraps that; the interface makes sure it can be written once
over every backend.

## The higher level

`higherlevel`, in `ioTestkit`, is that API: written over the public interface alone, in a package outside
`gears.async` (`gears.io.higherlevel`, the demos beside it in `gears.io.demos`), so that it sees only what a program
sees, and run on every backend. It has result-returning openers, `read`, `writeAll`, `accept`, `resolve`,
`closeGracefully`, and `chunks`, a stream over one repeatable pooled read consumed as a gears channel, with the pool's
window and the channel's backlog as back-pressure. `HigherLevelChecks` runs it over every backend, and the demos open,
accept and resolve through it. The demos are written against the interface alone too: each takes the `Io` it runs
with, over the capabilities it needs, and its `Async`, and only a backend's demo application starts a run, with the
backend it chose (`ioKqueueDemos/run gears <command>`, `ioNioDemos/run <command> [--backend caller|reactors]`).

## Running the suites

```sh
sbt rootJVM/test rootNative/test   # gears core, with the interface's own checks and the runtime's
sbt ioNio/test ioTestkitJVM/test   # the NIO backend and the testkit's own suites, on any platform (JDK 27)
sbt ioKqueue/test ioTestkitNative/test ioDemosTests/test   # macOS only
```

The kqueue backend builds and runs on macOS only; CI runs them on a macOS runner and everything
else on Linux. The NIO pool needs `--add-opens` for the JDK's virtual-thread internals, which the build sets. The bench
is described in [`bench.md`](bench.md).

## Design notes

- [`interface-history.md`](interface-history.md): each decision about the interface, the alternative it replaced, and
  why.
- [`handles.md`](handles.md): handles outlive the I/O object they named, since numbers are reused: a generation, a
  number that only grows, or a reference object that cannot be forged, and handles tied to gears scopes.
- [`runtime.md`](runtime.md): the pool the reactor backends run on: workers, run queues, take policies, fairness,
  failures, and what macOS's per-process locks mean for scaling.
- [`kqueue.md`](kqueue.md): the kqueue backend: what each op costs in system calls, how it got there, and how it
  compares with cats-effect.
- [`jvm.md`](jvm.md): the JVM's backends, `NioOnCaller` and `NioBackend`: why each waits as it does, what was measured,
  and what was tried and dropped.
- [`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md): the Network.framework backend, which comes separately: kqueue's reactors with
  Network.framework for TCP and connected UDP off this host, as Apple advises, and kqueue's code for the rest.
- [`files.md`](files.md): file I/O, what macOS offers for it, and the shape the file ops take.
- [`hosts.md`](hosts.md): what each capability would be on the backends built and those discussed (io_uring, Node,
  Deno, Workers, a browser, WASI), and what that asks of the interface.
- [`missing-api.md`](missing-api.md): what TLS, QUIC, HTTP and WebSockets need of the interface: addresses, socket
  options, fine timers and vectored I/O, done; packet info, batched datagrams and `sendfile`, left for follow-ups.
- [`scala-native.md`](scala-native.md): costs only Scala Native can remove, the changes proposed to it, and the
  workarounds meanwhile.
- [`bench.md`](bench.md): the bench scenarios, the scripts that build and compare builds, and how to measure well.
