# gears.async.io: asynchronous I/O for gears

An interface for asynchronous I/O that gears programs write once and run on any backend, the contract suites every
backend is checked against, demos written against the interface alone, and a reference backend on the JDK that exists
to pass the contracts, not to be fast. A program performs ops, built by its backend, with the `Io` that `Io.run` gives
it. Futures, groups, `sleep` and `withTimeout` work as anywhere in gears, `sleep` on the backend's own timers.

## Where to start reading

1. **The public interface**, in `shared/src/main/scala/async/io/`: `Io.scala` (running a program and performing ops),
   `Backend.scala` (backends and their capabilities), `Ops.scala` (every op and the rules they share),
   `SocketOptions.scala`, `Addresses.scala`, `Deadline.scala` and `BufferPool.scala`. All a program sees.
2. **The contract suites**, in `io-testkit/`: what every backend must do, each check a program over the backend
   through `perform`. `OpsContract` checks what every backend has; the capability contracts (`IpStreamContract`,
   `IpDatagramContract`, `UnixStreamContract`, `FileContract` and the rest) what each capability adds;
   `FuturesContract` checks programs of several futures.
3. **The reference backend**, `io-reference/`: the semantics on the JDK's channels, over a plain selector poll.
4. **The higher level**, `gears.io.higherlevel` in `io-testkit/`: an API with result-returning openers and streams,
   written over the public interface alone, which shows what the interface is for.

## Layout

| where | holds | depends on |
| --- | --- | --- |
| `gears.async.io` (core, `shared/`) | the interface: `Io` and `Io.run`, `Backend` and its capabilities, `Ops`, `Slot`, addresses, socket options, `Deadline`, `BufferPool` | the rest of gears |
| `ioTestkit` (`io-testkit/`, JVM, Scala Native and Scala.js) | the contract suites, `BufferArena`, `higherlevel` (`gears.io.higherlevel`) with `HigherLevelChecks`, and the gears demos (`gears.io.demos`) with their command line, `DemoCommands` | core, munit, mainargs |
| `ioReference` (`io-reference/`, JVM) | the reference backend, `gears.async.io.reference.ReferenceBackend`, and its suites | core, the testkit's contracts |
| `ioReferenceDemos` (`io-reference/demos/`) | the demos over the reference backend: `ioReferenceDemos/run <command>` | the reference backend, the testkit |

The interface is in `shared/`, so it compiles on every platform gears does, and so does the testkit, though only the
JVM has a backend to run its contracts and demos on yet. A backend outside this repository is written in a package
under `gears.async.io`, since what a backend implements and programs do not see is `private[io]`: how it performs an
op (`Io.performWithin`) and how it runs a program (`Backend.run`, which `Io.run` calls). A handle is an object of its
backend's own class, so a program can neither forge one nor pass one backend's handle to another, whose `Handle` is a
different type.

## The concepts

- **A capability** is what a backend can do, a trait each backend mixes in and a program asks for by intersection:
  `IpStreams`, `IpDatagrams` (`IpSockets` is both), `UnixStreams`, `UnixDatagrams`, `RegularFiles`, `Pipes`, `Fifos`,
  `Resolving`, `Blocking`. Each narrows the backend's `Ops`; the base `Ops`, which every backend has, is read, write,
  the pooled read, timers and close. A capability is a `Backend` itself, so a helper asks for `IpStreams` or
  `IpStreams & RegularFiles`, and a backend without one, such as a browser's, is refused when compiling.
- **A handle's kind** is in its type, a subtype of the backend's `Handle`. Every backend has `Readable` and `Writable`
  streams; the other kinds are declared by the capability that opens them: `StreamSockets` a `Connection` (both, and
  shut down for writing) and a `Listener`, `IpDatagrams` a `BoundDatagram` and a `ConnectedDatagram`, `UnixDatagrams`
  their Unix ones, `RegularFiles` a `File`. So a backend without a capability has no such kind, and a program names it
  through the backend it asked for: `io.backend.Connection` for an `Io[IpStreams]`. Every handle is opened by an op of
  the capability that has it, which writes it into a slot; an op takes the kind it works on, so an op on the wrong kind
  does not compile.
- **`Io`** is where a program's ops run: `Io[B]` has a backend, `backend: B`, and adds only `perform`. `Io.run` gives
  it to the program; it is the program's gears `AsyncOperations` too. How it performs, and on which threads the
  program runs, is the backend's.

## A program

```scala
def echo(using io: Io[IpStreams])(client: io.backend.Connection)(using Async): Unit = {
  val buf = ...
  val read = io.backend.ops.read(client, buf) // an op is a mutable record the caller owns, and reuses
  val write = io.backend.ops.write(client, buf)
  while { buf.clear(); io.perform(read); buf.position() > 0 } do {
    buf.flip()
    while buf.hasRemaining() do io.perform(write)
  }
}

Io.run(using ReferenceBackend) { ... echo(client) ... }
```

`Io.run` returns what the program returns, as `Async.blocking` does, and gives it its `Io` as a context parameter
beside `Async.Spawn`. An op's type and a handle's are path-dependent on the `Io` (`io.Op`, `io.backend.Connection`), so
a method taking a handle takes the `Io` first. Ops in flight together are futures performing them
(`Future(io.perform(op))`), raced, awaited and cancelled as futures are. A time limit is `io.perform(op, 2.seconds)`.

## The rules every backend keeps

Each is stated on the interface where it applies, and checked by the contract suites on every backend:

- **Cancelling or timing out** an op stops it: an op that waits for its handle has done nothing, so a read that
  timed out took no bytes and leaves the connection usable; an opener leaves its slot empty and nothing open; once
  `perform` returns or throws, the op's buffers and slots are the caller's again (`Io.perform`).
- **Misuse fails as the op is built or performed**, with an `IllegalArgumentException`, never ignored: an address that
  is not numeric, an option a backend lacks or that does not apply, an empty packet on a connected socket, a read into
  a buffer with no room or a read-only one. I/O failures are `IOException`s.
- **One op at a time on a stream's direction**: a second read, or write, pending is refused, since two would take or
  interleave the stream's bytes. A datagram socket's sends and receives and a listener's accepts, each whole, take
  turns instead, in the order they came (`Ops`).
- **Any buffer** is taken, on the heap or not (`Ops`).
- **Options** are given as a socket opens, `NoDelay` on unless set off; what a backend supports, for each kind of
  socket, is asked of it when the program runs (`SocketOptionOps`).
- **Closing** fails the ops waiting on the handle and frees the port or path (`Ops.close`). The ops it ends, and any op
  performed on the handle after, a second close too, fail with a closed channel's failure, `ClosedChannelException`
  naming the handle, on every backend.

Left to a higher level, on purpose: ops, `perform` and slots are mutable records the caller owns and reuses, the
lowest level there is. A result-returning or streaming API wraps that; the interface makes sure it can be written once
over every backend.

## The reference backend

`ReferenceBackend` has what the JDK has: IP stream and datagram sockets, Unix stream sockets, regular files, pipes,
name resolution and blocking ops (no Unix datagram sockets, no FIFOs). A program runs on gears' virtual threads, and
each op is performed by the thread performing it: tried at once on a non-blocking channel and, if it would block,
waited for on one selector that one thread polls, then tried again. Timers park the caller; files are read and written
by the caller; blocking code and name lookups run on threads of their own. Nothing is tuned for speed: it is what the
contracts are checked against, and the plainest way to read the semantics as code.

## The higher level and the demos

`higherlevel`, in `ioTestkit`, is written over the public interface alone, in a package outside `gears.async`
(`gears.io.higherlevel`, the demos beside it in `gears.io.demos`), so that it sees only what a program sees. It has
result-returning openers, `read`, `writeAll`, `accept`, `resolve`, `closeGracefully`, and `chunks`, a stream over one
repeatable pooled read consumed as a gears channel, with the pool's window and the channel's backlog as back-pressure.
`HigherLevelChecks` runs it over every backend. Each demo takes the `Io` it runs with, over the capabilities it needs,
and its `Async`, and only a backend's demo application starts a run: `ioReferenceDemos/run <command>`.

## Running the suites

```
sbt ioReference/test        # the contracts, the futures contract and the higher level over the reference backend
sbt ioTestkitJVM/test       # the testkit's own tests
sbt 'ioTestkitNative/compile; ioTestkitJS/compile'
```

A backend written elsewhere depends on `gears-io-testkit` and extends the contracts of the capabilities it has, as
`io-reference/src/test` does.
