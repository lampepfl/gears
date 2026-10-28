# Handles that outlive their I/O object: a design note

A `Handle` names an open socket, pipe, FIFO or file by a number, and the number is reused once its handle is closed. So
a consumer still holding a closed handle can, without any error, reach whatever I/O object was opened under the number
since. This note records the problem, what protects against it today and what does not, and four proposals. Three change
what a handle is, so that a stale one fails cleanly as the program runs: a generation encoded in the handle, a number
that only grows and is never reused, or a reference to an object that cannot be forged. The fourth ties a handle to a
gears scope that capture checking enforces when compiling. Written on 2026-10-24; the capture checking findings come
from small programs compiled with Scala 3.9.0, `language.experimental.captureChecking` and `separationChecking`. On
2026-10-25 proposal 3 was built: handles are objects of each backend's own class (see "Proposal 3, as built"); the
sections before it describe the numbers handles were until then.

## The problem

A handle is an opaque `Int` (`gears.async.io.Handle`), whose meaning belongs to the backend:

- **kqueue** uses the POSIX descriptor itself, and the kernel gives each new descriptor the lowest number free.
- **NIO** and **Network.framework** use an index into a process-wide table (`gears.async.io.internal.HandleTable`),
  which reuses the most recently freed number first.

Both reuse a number as soon as it is free. Take two futures sharing a handle:

1. Future A and future B both hold handle 7, a connection.
2. A closes 7, then opens a new socket, which is given 7.
3. B runs, and reads or writes handle 7: the new socket, not the connection it was given.

Nothing fails. B's bytes go to, or come from, another device, and A's new socket sees traffic it never asked for.
The same holds for an op built on a handle (ops are records a program keeps and resubmits, `Repeatable`): one built on
7 before the close, and submitted again after it, acts on the new 7.

## What protects against it today, and what does not

Each backend makes the ops **already pending** when a handle is closed fail, and never complete on what reuses the
number:

- **kqueue** keeps a generation per descriptor (`gears.async.io.internal.DescriptorTable`), counting closes. A close
  ends the descriptor's generation before the number can be reused and tells every reactor, which fails the commands
  pending on it and forgets its registrations; an event that arrives for the old generation is recognised as stale.
- **NIO** looks a handle's entry up when a command is submitted, and a closed entry fails what is pending on it.
- **Network.framework** resolves a command's handle when it is submitted and keeps that handle object for its
  attempts, since "the number may be closed and reused meanwhile".

`Ops.close` states the rule this enforces: the ops pending on a handle fail with an `IOException` before the close
completes, and only then is the number free. What none of it covers is an op **submitted after** the number was
reused: it is looked up afresh and finds the new object. The interface leaves that to the program, as POSIX does: a
handle must not be used once it is closed, or while it is being closed (`KqueueHandles.close`). That is documented,
not enforced.

## Proposal 1: a generation in the handle, checked as the program runs

Make the handle a pair: the number and a count of how many times that number has been closed. Every op checks the
count against the table's, and a stale handle fails with "Not an open handle" instead of reaching what reuses the
number. It is the generational index of slot maps and arenas; Tokio packs a generation into its registration tokens
for the same reason, and `DescriptorTable` already keeps one per descriptor for the pending case.

- **Representation.** `Handle` becomes an opaque `Long`: the number in the low 20 bits, the generation in the high
  44. Still unboxed. Packing both into an `Int` would leave too few bits for one of them: a generation of 8 bits wraps
  after 256 closes of one number, which a busy server reaches in seconds.
- **The generation wraps, so it needs most of the bits.** Closes fall on few numbers: kqueue's descriptors are the
  lowest free, and `HandleTable` reuses the most recently freed first, so a server of short connections closes the
  same handful of numbers over and over. A generation of `b` bits wraps after 2^b closes of one number:

  | Closes of one number a second | 32 bits wrap after | 44 bits wrap after |
  | --- | --- | --- |
  | 10,000 | 5 days | 56 years |
  | 100,000 | 12 hours | 5.6 years |
  | 1,000,000 | 72 minutes | 200 days |

  A wrap does not turn the check off: a stale handle passes only when its number has been closed an exact multiple of
  2^b times since it was good. But that makes the check a likelihood rather than a guarantee, and a half split of the
  `Long` (32 and 32) wraps within a day on a busy server. 44 bits outlast any run of one.
- **The number then has 20 bits**, a million handles. That is Linux's default ceiling on a process's descriptors
  (`fs.nr_open`) and the most the Native runner raises the open-file limit to when the hard limit is unlimited
  (`FileLimit`), but not a bound the kernel keeps: with a finite hard limit `FileLimit` raises to it, whatever it is.
  So the runner keeps the limit at or below 2^20, and an open given a descriptor above it fails, as `select` refuses a
  descriptor past `FD_SETSIZE`. A 24-bit number, 16 million handles, would leave 40 bits, which wrap in four months
  at 100,000 closes a second: too short.
- **Or retire a number before it wraps,** as slot maps do: once its generation reaches the last value, the number is
  never given out again. `HandleTable` can, since it chooses its numbers. kqueue cannot while a handle is the
  descriptor, since the kernel chooses which number an open reuses; it would have to map handles to descriptors, which
  is proposal 2's cost. Retiring is what makes the generation exact rather than very likely; with 44 bits it is
  insurance against a server that runs for decades.
- **Not to be confused with `DescriptorTable`'s generation**, 32 bits today, which only tells the events and ops of a
  descriptor from just before a close apart from those just after it, a window of microseconds: wrapping is harmless
  there.
- **Per backend.** kqueue compares with `DescriptorTable`'s generation, which it reads already. `HandleTable` keeps a
  generation per entry, incremented by `remove`. Network.framework and NIO check it where they look the entry up.
- **What changes around it.** `Handle.Opt` and `Handle.Slot` hold a `Long`; `acceptAll`'s `Array[Handle]` becomes an
  array of `Long`s; `KqueueHandles.handle(fd)`, for kqueue's own demos, needs the descriptor's current generation.
- **Cost.** One comparison per op, against a value the backends mostly load anyway, and 4 more bytes per handle.
- **What it gives.** Detection, not prevention: B's read in the example fails cleanly instead of reading the wrong
  device, and it fails on every backend the same way. It covers the low level (`Ops`, `perform`) as well as anything
  built on it, and code that switches off the compiler's checks.
- **What it does not.** It finds the mistake when the program runs, possibly in production, and only if the stale
  handle is used at all. It does not stop a future from holding a handle past its close. And unless a number is
  retired before it wraps, which kqueue cannot do, it finds it with a likelihood, if a very high one, rather than
  always.

## Proposal 2: a number that only grows, never reused

Make a handle an id from a counter that only grows, a `Long`, so that no two I/O objects are ever given the same one.
A closed handle's id is simply absent from the table afterwards, and a stale handle fails with "Not an open handle"
however many objects were opened since.

- **Representation.** `Handle` becomes an opaque `Long`, as in proposal 1, but all 64 bits are the id. A counter of
  63 bits does not wrap: at a billion opens a second it would take three centuries. Unlike proposal 1's generation,
  which counts the closes of one number, the counter counts every open of the process, and still never runs out.
- **The table.** Ids are not dense, so the table can no longer be an array indexed by the handle: it becomes a map
  from id to object, a concurrent hash map, or an array of slots whose entry also records the id it holds, which is
  proposal 1 again under another name.
- **Per backend.** kqueue's handles stop being descriptors: every op maps the id to its descriptor first, where today
  the handle is the descriptor and costs nothing to resolve. NIO and Network.framework trade an array index for a hash
  lookup.
- **What it gives.** The same detection as proposal 1, and simpler to reason about: an id names one object for the life
  of the process, with nothing to compare. A handle printed in a log means one thing.
- **What it costs.** A hash lookup on every op, and on kqueue a lookup where there was none. The generation of
  proposal 1 gets the same answer with an array index and one comparison; this is the version to prefer only if the
  lookup measures as cheap, or if ids that are never reused matter for their own sake, as in logs and traces.

## Proposal 3: a reference to an object, which cannot be forged

Make a handle an object rather than a number: a final class the backend creates when it opens something, holding what
the backend needs (the descriptor, the channel, the connection) and whether it is closed. A program can only get one
from an op that opened it, so it cannot be forged from an `Int`, and a new open makes a new object, so a stale handle
can never be confused with a new one: it is the closed object, and an op on it fails.

- **Representation.** `Handle` becomes a class, allocated once per open. `Handle.Opt` and `Handle.Slot` hold a
  reference, and `acceptAll`'s array is an array of references. Nothing is unboxed any more.
- **No table lookup.** The op reaches the descriptor, channel or connection straight from the handle: NIO and
  Network.framework no longer look an index up in `HandleTable`, which could go, and kqueue reads the descriptor from
  a field.
- **Forging and mixing.** Today `Handle.wrap` is `private[io]`, which keeps programs from making a handle from a
  number, but a cast still can, and a handle of one backend passed to another is just a number, which the other reads
  as one of its own. An object belongs to the backend that made it, so an op can refuse another backend's handle, and a
  cast to it fails.
- **Closing while in use.** A closed flag alone races with a use on another thread: kqueue could check the flag, the
  close run and its descriptor number be reused, and then the use reach the new descriptor. The object can count the
  ops using it and close the descriptor only once the last has left, as the JDK does for a channel a thread is blocked
  in (`preClose`, a `dup2` onto a dead socket, then the real close when the last thread leaves). That is a cost every
  proposal here would pay to make a close concurrent with use safe, not this one's alone.
- **Capture checking.** An object can be a capability class (`caps.SharedCapability`, or an exclusive one), which an
  opaque `Int` cannot: the backend could then say how a handle may be shared, by classifier, rather than only whether
  it may escape (see proposal 4).
- **What it costs.** An allocation per open, which is small against the system call, and a reference to follow on each
  op, which replaces the table lookup NIO and Network.framework do today. kqueue pays both, having needed neither.
  Against an opaque `Long` (proposals 1 and 2), estimated from what the code does today, not measured:

  | | Opaque `Long` | Reference object |
  | --- | --- | --- |
  | Per open | nothing | one allocation of about 24 bytes (the descriptor or channel, a closed flag, the backend): a few ns, against an `accept` or `connect` of microseconds |
  | Per op, NIO and Network.framework | `HandleTable` lookup: a volatile read, a bounds check, an array read | none: the handle is the entry |
  | Per op, kqueue | none (the descriptor is in the handle) and a generation comparison | a field read, usually in cache; `DescriptorTable` stays, since events arrive by descriptor |
  | Open and close, NIO and Network.framework | `HandleTable`'s lock to add and remove | no table |
  | In generic code: `Future[Handle]`, `HigherLevel.pipe`'s `(Handle, Handle)`, `Option`, collections, `Slot[...]` | boxed to a `java.lang.Long` at every use; small `Int` descriptors hit the JVM's `Integer` cache today, a `Long` with a generation in its high bits never does | already a reference: no further allocation |
  | Memory per holder | 8 bytes | a 4-byte compressed reference on the JVM, plus the one object |
  | Garbage collection | none for the handle itself, but the boxes above | one object per connection, living as long as it: promoted on a long-lived server, cheap to collect for a short one |
  | Scala.js, which compiles the interface | `Long` is emulated, its arithmetic and comparisons slower than an `Int`'s | an ordinary reference |

  So the `Long` is cheaper only where a handle never enters generic code; once it does, it allocates per use where the
  object allocated once per open. Either way the difference is nanoseconds against system calls of microseconds (the
  bench's round trip is about 16 µs). `Handle.Opt` and `Handle.Slot`, unboxed today by their own special-casing,
  would hold a reference. Measuring it needs a connection-churn scenario on the bench (open, one round trip, close),
  run with numbers against objects: the per-op scenarios such as `pingpong` would show only kqueue's field read.

## Proposal 3, as built on 2026-10-25

`Backend` has an abstract `type Handle <: AnyRef`, as it has `type Op`, and the ops traits take it as a second type
parameter (`Ops[Op, H]`, `SocketOps[Op, H]`, and the rest), so a handle's type is its backend's: `io.Handle` in a
program, as `io.Op` is. Each backend implements it with a class of its own whose constructor is private to the
backend's package: `KqueueBackend.Handle`, holding the descriptor; `NioCapabilities.Handle`, shared by NIO's two
backends, holding the channel's entry; and the Network.framework backend, since it runs on kqueue's reactors
(2026-10-27, see [`nwframework.md`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/docs/io/nwframework.md)), kqueue's, a Network.framework object as its `foreign` one: its
kinds are opaque types of its own, so the two backends' kinds do not type-check as each other's, and kqueue's ops
refuse a foreign handle passed as a raw `Handle`, which only `close` takes. Slots of handles
are `Slot[H]`; `Handle.Slot`, `Handle.Opt` and the opaque `Handle` are gone. Each handle has a closed flag, so a second
close fails without touching a descriptor whose number may be another's by then.

- **Compile time.** A program can make a handle neither from a number nor from another object, nor take one's number,
  and a handle of one backend does not type-check as another's (`SlotAccessSuite`). A method taking a handle takes
  the `Io` first, since the handle's type is the `Io`'s (`def echo(using io: Io[IpSockets])(client: io.Handle)`), and
  the bench's `Connect` became `Connect[H]`, built with the `Io` whose handles it returns.
- **Runtime, NIO and Network.framework.** An op looks its handle's entry up when it is submitted or performed, and a
  closed handle fails it with "Not an open handle", so a stale handle reaches nothing. `HandleTable` is gone from
  NIO; Network.framework keeps it, keyed by an `Int` of its own, only for its C side, which tags each of its objects
  with that number so that an event finds its object.
- **Runtime, kqueue.** A command built on a handle carries it (`Command.handle`), and every use of the descriptor
  takes the handle for its length: a command's system call and its registration in the reactor, a file op's on its
  blocker thread, `readNow` and `writeNow`. Taking it fails once the handle is closed, so a stale handle fails its op,
  whether built before the close or after, rather than reaching what reuses the descriptor. Kqueue's own code still
  builds commands on bare descriptors, unchecked, as before.
- **A close concurrent with a use (kqueue), as Go closes.** The handle counts the uses in progress, in one atomic
  integer with its closed flag, as Go's `fdMutex` does. A close marks the handle closed, counting itself a use while
  it does, so that no use starts after, and removes a Unix path the socket bound; then, if no use is in progress, as
  nearly always, it closes the descriptor there and then, and the close op completes as an at-once op does; and if
  one is, it leaves the closing to the last use, which closes the descriptor on its own thread when it ends, a
  reactor's or a blocker's, and completes the close op as a promise is completed from another thread. Nobody waits:
  not the closing reactor, whatever the use in progress, a system call of microseconds or a file op's disk read.
  The reactors are told, and fail the commands pending on the descriptor, once it is closed, as before: telling them
  sooner, while a use is still in progress, could let that use register a filter the reactors then take for a
  reused number's. A use is held only around a system call, never while an op waits for readiness, and ends before
  its completion runs, so that a close never waits for an op that it alone would fail. `KqueueHandles.close`, for
  kqueue's own code with no reactor, waits for the last use, as Go's `Close` does on a non-blocking descriptor.
  `HandleCloseSuite` closes a pipe 200 times while a thread writes through it, checking that nothing reaches the
  pipes opened at once after, which the kernel gives the same numbers (it fails with the count taken out), and
  submits a close op while a use is in progress: the op stays pending while the reactor turns, the handle refusing
  uses and the descriptor still open, until the use ends, closes the descriptor, and the op completes. NIO's channels
  and Network.framework's connections are objects the runtime closes, with no number to reuse under a call in
  progress.
Measured against the commit before (release builds; [`bench/results/2026-10-25-handle-objects-*.json`](https://github.com/bishabosha/gears/tree/async-io-full-2026-10-28/bench/results)), by alternating
runs, the median of each, change from numbers to objects:

| Scenario | kqueue, 7 runs | kqueue, 9 runs, order swapped | NIO reactors | NIO on the caller | Network.framework |
| --- | --- | --- | --- | --- | --- |
| `connections` (open, one round trip, close) | +0.8% | -1.9% | -0.1% | -0.8% | -0.3% |
| `pingpong` | +4.6% | -1.4% | -2.6% | +1.4% | +0.1% |
| `pingpong-fresh-ops` (ops built per message) | +8.9% | -1.3% | -1.1% | +0.6% | -1.1% |
| `pipelined` | +5.8% | 0.0% | -1.9% | +0.9% | |
| `concurrent` | +8.7% | -2.6% | +3.2% | +0.7% | +0.3% |
| `timers` (no handles: a control) | +0.1% | -0.5% | | | |

The count of uses on kqueue, two atomic operations around each system call, measured against the commit before it
(9 alternating runs, [`bench/results/2026-10-25-handle-uses-kqueue.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/2026-10-25-handle-uses-kqueue.json)): `connections` +1.4%, `pingpong` +0.3%,
`pingpong-fresh-ops` 0.0%, `pipelined` -2.8%, `concurrent` +1.0%, `timers` +0.1%; the fastest runs equal to a hundredth
of a microsecond. Within the spread again.

The Go-style close, against the close that spun on the reactor until the uses ended (9 alternating runs under a load
average of 11 to 16, [`bench/results/2026-10-25-handle-goclose-kqueue.json`](https://github.com/bishabosha/gears/blob/async-io-full-2026-10-28/bench/results/2026-10-25-handle-goclose-kqueue.json)): `connections` +2.1% at the median and
its fastest run 3% faster, `pingpong` +0.5%, `pipelined` -1.4%, `concurrent` -6.5% at the median and its fastest
equal, `timers` +0.3%. Within the spread: a close that finds no use in progress, nearly every close, does what it did.

The first kqueue run's slowdowns were the build that ran second in each pair, under a load average of 7 to 11 (an
indexer running): its slow runs were bimodal, its fastest equal to the other build's (`pingpong` 6.98 µs against 6.94),
and with the order swapped the sign swapped too. On every backend the difference is within the runs' spread: an
allocation per open and a field read per op do not show against system calls of microseconds, as estimated above.
Network.framework ran over TCP at a tenth of the counts (`connections` at a fiftieth), since thousands of closed TCP
connections in TIME_WAIT run the loopback out of ports ("Can't assign requested address").

## Proposal 4: handles tied to a gears scope, enforced by capture checking

Make a handle impossible to use once its scope has closed it: the higher level opens a handle for a scope, the
scope closes it only once every future that may use it has finished, and the compiler rejects any use outside the
scope. While the scope is open the handle is open, so its number cannot be reused under anyone who holds it.

### What the compiler already does

Capture checking tracks a value whose type carries a capture set, `Handle^`, and rejects letting it outlive the scope
it belongs to. The opaque `Handle` can be tracked as it is: outside the file defining it, `Handle` is an abstract type,
and `Handle^` is tracked; inside, it is `Int`, which is pure, and writing `Handle^` there is an error. The backends get
the number with `caps.unsafe.unsafeAssumePure(h)`, the one place the checking is switched off, below the interface.

Checked with Scala 3.9.0:

| Program | Result |
| --- | --- |
| A scoped opener's handle returned out of its scope | rejected: the capability outlives its scope |
| A closure capturing the handle returned out of its scope | rejected |
| `close(consume h: Handle^)`, then a use of `h` | rejected: `h` was consumed |
| `close(h)` while another closure holding `h` is used afterwards | rejected |
| Opening, using, closing, or returning a handle (handing it on) | compiles |
| A future that uses `h` started, then `close(h)` | **compiles** |

### The gap: separation checking is not temporal

The last row is the problem this note is about. Separation checking tracks which names refer to a value, not when
another thread runs: once a closure capturing `h` has been handed to something that runs it later, nothing says the
close must wait for it. A future is exactly that.

Nor can the future take the handle over and close it itself: a function type cannot have a `consume` parameter, and a
closure cannot consume a value it captured ("argument to consume parameter refers to non-local value"). So closing
belongs to the scope that opened the handle, never to a future.

### Capturing the scope: spawners that say what their futures may capture

What closes the gap is to make a future's body capture only what its spawner allows, and to give a handle's scope a
spawner of its own that allows the handle, joined before the handle is closed:

```scala
final class Spawn[C^]                                       // stands in for gears' Async.Spawn
def future[C^](s: Spawn[{C}])(body: () ->{C} Unit): Unit   // a body may capture only what its spawner allows

def withConnection[T](using io: Io)(
    body: (h: Handle^) => (group: Spawn[{h, io}]) ->{h, io} T
): T                                                        // runs body, joins group, then closes h
```

The program's own spawner is a `Spawn[{io}]`, so a future started with it cannot capture `h`; the connection's
group is a `Spawn[{h, io}]`, so its futures can, and `withConnection` joins it before closing `h`. Checked:

| Program | Result |
| --- | --- |
| `future(group)(() => read(h))` inside `withConnection` | compiles |
| `future(outer)(() => read(h))` with the program's `Spawn[{io}]` | rejected: `h` cannot flow into `{io}` |
| `future(outer)(() => println("no handle"))` | compiles |

The capture set must be an **invariant** type parameter. Written as the spawner's own capture set, `Spawn^{C}`, it
widens: `Spawn^{io}` is a subtype of `Spawn^{h, io}`, and the compiler accepted the outer spawner's future.

Two shapes the scope would naturally take are not supported yet, and were curried around: a context function whose
second parameter depends on the first (`(h: Handle^) => Spawn[{h}] ?=> T`, "curried dependent context function
types are not supported"), and a function whose parameters depend on each other (`(h: Handle^, g: Spawn[{h}]) => T`).

### What it would take

- **gears.** `Async.Spawn`, or what `Future.apply` asks for, carries the invariant capture-set parameter, and a
  `Future`'s body is checked against it; `Async.group` gives a spawner whose set includes what the group's scope
  allows. This is a change to gears itself, not only to the I/O interface, and the larger part of the work.
- **The interface.** `Io` is a shared capability; handles are opened as fresh capabilities limited by it; an op
  captures its handle and buffer (`io.Op^{h, buf}`), so that a reusable op cannot outlive its handle either; `close`
  takes its handle as `consume`.
- **The higher level.** Scoped openers (`withConnection`, `withListener`, and a scope per accepted connection in a
  serve loop), each giving its body the handle and a group allowed to capture it.
- **Everything compiled with capture checking**: gears, the backends, the contract suites and the demos. A library
  compiled without it does not say what it captures, so the guarantee reaches only as far as code that is checked.

### What it gives, and what it does not

At compile time, for code over the higher level: a handle cannot be used after its scope closed it, cannot be closed
while one of its futures may still use it, and cannot escape into a future that outlives it. Hence its number cannot
be reused under anyone holding it. It does not cover the low level, where a program holds handles and ops directly,
nor code that switches the checking off; and a scope cannot close its handle early except in straight-line code that
owns it (`close(consume h)`).

## Comparison

| | 1. Generation | 2. Number that only grows | 3. Reference object | 4. Scopes and capture checking |
| --- | --- | --- | --- | --- |
| A stale handle | fails as it is used | fails as it is used | fails as it is used | does not compile |
| Wraps around | after 2^44 closes of one number, unless retired | no (2^63 ids) | no | no |
| Handles at once | 2^20, enforced by the runner | unbounded | unbounded | as today |
| Representation | opaque `Long` | opaque `Long` | a class, one per open | as today, tracked |
| Per op | a comparison | a hash lookup | a field read | nothing |
| Per open, and in generic code | nothing; a box per generic use | nothing; a box per generic use | one allocation; nothing more | as today |
| kqueue | compares with `DescriptorTable` | maps id to descriptor | reads the descriptor from the handle | as today |
| Forged or another backend's handle | read as one of its own | not found, or another's id | refused | as today |
| Covers the low level (`Ops`) | yes | yes | yes | no |
| Changes gears | no | no | no | yes: spawners' capture sets |

## Recommendation

Proposal 4 for programs, and proposal 3, now built, under it:

- **Proposal 4 at the higher level**, since it prevents the mistake when compiling, which is the safety wanted; it is
  the larger change, starting with gears' spawners.
- **Proposal 3 under it**, as a backstop for the low level and for code outside the compiler's checks. It was chosen
  over proposals 1 and 2 because it also refuses forged handles and another backend's when compiling, needs no bits
  split between a number and a generation, cannot wrap, removes the table lookup on NIO, and gives capture checking a
  class to classify; its cost, measured, is within noise on every backend, kqueue's count of uses included.

## Open questions

- Whether gears takes the invariant capture-set parameter on `Async.Spawn`, and how `Async.group`, `Future` and
  channels read with it; the first step is to try it on gears' own types in a branch.
- How `Slot[H]` and `acceptAll`'s `Array[H]` hold tracked handles: a slot that receives a fresh capability, and an
  array of them, need capture checking's support for collections of capabilities.
- Whether a close should interrupt a file op reading or writing the disk on a blocker thread, which now delays only
  the descriptor's close and the close op's completion, not the reactor: by `dup2`ing a closed descriptor over it,
  as the JDK closes a socket a thread is blocked in.

## Kinds of handle, as types (2026-10-26)

Proposal 3 made a handle its backend's type; it still did not say what kind of I/O object it named, so a stream op on
a listener, a datagram op on a stream, an IP send on a Unix datagram socket or `shutdownOutput` on a pipe compiled, and
each backend then did something of its own: Network.framework called its framework on a null connection, kqueue read a
stream's bytes as a datagram, NIO failed with an `IOException`. Now the kind is in the type. `Backend` declares
subtypes of its `Handle`, `Readable`, `Writable`, `Connection` (both), `Listener`, the datagram kinds (bound and
connected, IP and Unix, since 2026-10-27: see "Datagram sockets are bound or connected" in
[`interface-history.md`](interface-history.md)) and `File` (both), which the ops traits declare too and `Backend.Kinded` ties together; each opener writes its kind into
its slot (`connect` and `accept` a `Connection`, `pipe` a `Readable` and a `Writable`, the FIFO openers one each, now
two ops since the direction picks the type), and each op takes the kind it works on, so the wrong kind does not
compile (`ReactorSuite`). A program names them through its `Io` (`io.Connection`), as it names `io.Op`.

Each backend makes them opaque types over its one handle class (`KqueueKinds`, `NioKinds`, `NWFrameworkKinds`), so
that, with a backend of a known type, one kind is not another either, and nothing changes at runtime: there is no
kind field, no check and no allocation. Its own code converts a handle it opened into its kind there; kqueue-specific
code that opens descriptors itself has `KqueueHandles.connection` beside `KqueueHandles.handle`. A file keeps one type
for both directions, since a file is often opened for both, and a read of one opened only for writing still fails as
the system fails it. The runtime kind checks that were about to be added for the same misuse were not needed.

