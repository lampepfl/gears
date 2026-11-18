# io-bench/stacks

The IP socket scenarios of `CapBench` (`../shared`), written again for other stacks, each the way that stack is
meant to be used, so that the gears backends can be set beside them.

| directory | stack |
|---|---|
| `netty/` | Netty, on its NIO transport |
| `nio/` | the JDK's channels and one `Selector`, by hand |
| `fs2/` | fs2-io on Cats Effect |
| `go/` | Go, goroutines over `net` |
| `node/` | Node.js, `net` and `dgram` |
| `tokio/` | Tokio on Rust |

`run/` has `Stacks`, which builds and runs them all with the gears backends and writes the table (see below).

## What every implementation does

Everything is in one process, over `127.0.0.1` with ports the system picks. TCP connections have `TCP_NODELAY` set,
on both ends, and every other option as the system has it. A message is 64 bytes; a chunk is 65536 bytes.

A scenario is timed from just before its work begins to just after its last future, task or callback has finished.
Setting up what the scenario's comment says is set up beforehand is not timed; closing is not timed unless it is the
work. `n(x)` is `max(1, floor(x * scale))`.

| scenario | ops counted | what it does |
|---|---|---|
| `tcp-pingpong` | `n(100000)` | One connection, made beforehand. The client writes a message and reads a whole message back, `ops` times over; the server reads a whole message and writes it back, as often. Each side reads until it has all 64 bytes, and writes until all are written. |
| `tcp-concurrent` | `n(5000) * connections` | As `tcp-pingpong`, over `connections` connections at once (made beforehand, one after another, through one listener), each doing `n(5000)` rounds. |
| `tcp-stream` | `n(20000)` | One connection, made beforehand. The client writes `ops` chunks and then shuts down its output; the server reads, into a buffer of one chunk, until the end of the stream. Done when both have finished. |
| `tcp-connect` | `n(1000)` | One listener, made beforehand. `ops` times over, one after another: the client connects, reads until the end of the stream (the server sends nothing), and closes; the server accepts and closes at once. |
| `udp-pingpong` | `n(100000)` | Two sockets, each bound to its own port, not connected. The client sends a message to the server's address and receives one, `ops` times over; the server receives a message and sends it to the client's address, as often. |
| `udp-concurrent` | `n(5000) * connections` | As `udp-pingpong`, over `connections` pairs of sockets at once, each doing `n(5000)` rounds. |
| `udp-connected` | `n(100000)` | As `udp-pingpong`, with each socket connected to the other, sending and receiving with no address. |

## The command line

Every implementation takes the same arguments, each optional, in any order:

```
--workers <n>       threads the stack runs its tasks on (default 1); ignored where the stack has one thread
--scenarios a,b,c   which to run, in the order given (default: all seven, in the order above)
--scale <x>         multiplies every count (default 1.0)
--connections <n>   pairs in the concurrent scenarios (default 64)
--repeat <n>        runs the scenarios that many times over, in one process (default 1)
```

and prints one line for each scenario run, in exactly this format (C's `printf` notation), and nothing else on
standard output:

```
bench: %-20s %9d ops %8.3f s %9.3f us/op\n
```

with the scenario's name, the ops counted, the seconds taken and the microseconds for each op. A scenario the stack
cannot do prints nothing. The process ends with status 0 once the last scenario has finished.

## Where the implementations differ

Each is written as its stack is meant to be used, so they are not line for line the same. What is known to differ:

- **Setting up the UDP sockets** is inside the timing for the gears backends, Go, Tokio, Netty and the JDK selector, as
  `CapBench` has it, and outside it for fs2-io, whose close of a datagram socket takes milliseconds, and Node.js. At
  scale 1 that is two binds against a hundred thousand round trips.
- **Netty** frames the messages with `FixedLengthFrameDecoder(64)`, allocates from its default (adaptive, pooled,
  direct) allocator, and asks for IPv4 sockets, where its default would be IPv6 sockets carrying the IPv4 address.
  In `tcp-stream` the server's receive buffer is fixed at one chunk, where Netty's default would grow to it.
- **fs2-io** copies every read and every datagram into a new array, as its interface gives them. Its datagram
  `write` builds its buffer when the `IO` is made, not when it runs (fs2 3.16.0), so each send is wrapped in
  `IO.defer`; without that the second send on is empty.
- **Node.js** reads into buffers Node allocates, of up to 64 KiB each, and has one thread.
- **Tokio**'s main thread only sets up and waits: with `--workers 1` the scenarios run on one worker thread beside it.
- **The JDK selector** loop registers a channel only when an op on it first would block, and has one thread.
- **Go** leaves `TCP_NODELAY` in `tcp-connect` to its `net` package, which sets it on every connection.

## Running one

Each directory's own `README.md` has its command. To run them all and write the table:

```
scala-cli run io-bench/stacks/run -- --out io-bench/results/<name>.md
```
