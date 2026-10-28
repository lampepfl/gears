# gears

![GitHub Release](https://img.shields.io/github/v/release/lampepfl/gears)
![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/lampepfl/gears/ci.yml)
[![Homepage](https://img.shields.io/badge/website-homepage-brightgreen)](https://lampepfl.github.io/gears)
[![API Documentation Link](https://img.shields.io/badge/api-documentation-brightgreen)](https://lampepfl.github.io/gears/api)

An Experimental Asynchronous Programming Library for Scala 3. It aims to be:
- **Simple**: enables direct-style programming (suspending with `.await`, calling Async-functions directly) and comes with few simple concepts.
- **Structured**: allows an idiomatic way of structuring concurrent programs minimizing computation leaking (*structured concurrency*), while
  providing a toolbox for dealing with external, unstructured events.
- **Cross-platform**: Works on JVM >= 21, Scala Native and Scala.js with WAsm support.

> [!WARNING]  
> On V8 <14.2.75 (Node.js 24 and 25), there is a bug that causes stack overflows in nested async contexts, which are used extensively by Gears. Use Node.js 26+, or another runtime with Wasm 3.0 and JSPI support.
> See #165 for more details.

## Getting Started

The [Gears Book](https://natsukagami.github.io/gears-book) is a great way to getting started with programming using Gears.
It provides a tutorial, as well as a guided walkthrough of all concepts available within Gears.

### Adding `gears` to your dependencies

With `sbt` 2:
```scala
libraryDependencies += "ch.epfl.lamp" %% "gears" % "<version>"
```

For cross-platform dependencies with sbt 1, use `%%%`.

The I/O interface (`gears.async.io`) is part of `gears`; a backend to run it on is a library of its own, at the same
version: `gears-io-nio` on the JVM, and `gears-io-kqueue` on Scala Native on macOS. `gears-io-testkit` holds the
contract suites a backend is checked against, for a backend written elsewhere:
```scala
libraryDependencies += "ch.epfl.lamp" %% "gears-io-nio" % "<version>"
libraryDependencies += "ch.epfl.lamp" %% "gears-io-testkit" % "<version>" % Test
```

With `mill`:
```scala
def ivyDeps = Agg(
  // ... other dependencies
  ivy"ch.epfl.lamp::gears:<version>"
)
```

With `scala` (since 3.5.0) or `scala-cli`:
```scala
//> using dep "ch.epfl.lamp::gears:<version>"
```

## Setting up on an unpublished version of Gears

The build uses sbt 2 and Scala 3.9.0. You will need JDK >= 21 and [Scala Native](https://scala-native.org) set up, plus Node.js 26+ to run the Scala.js tests.
```bash
sbt publishLocal
```

## Contributing

We are happy to take **issues**, **pull requests** and **discussions**!

For a quick look at our development environment and workflow, check our the [contributing guide](./CONTRIBUTING.md).

### Related Projects

You might also be interested in:
- [**ox**](https://github.com/softwaremill/ox): Safe direct-style concurrency and resiliency for Scala on the JVM.

## License

Copyright 2024 LAMP, EPFL.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

See [LICENSE](./LICENSE) for more details.

## Async I/O (`gears.async.io`)

gears has an interface for asynchronous I/O in `gears.async.io`, on every platform: a backend
(`Backend`) builds ops (`Ops`) on the handles they name, with capabilities such as IP sockets,
files, pipes and name resolution mixed in, and a program performs those ops with an `Io` over
the backend, which is also its gears `AsyncOperations`. `Io.run` starts a program
over a backend on the JVM and on Scala Native: on a pool of workers, one per processor,
driving the backend's reactors, or for a backend without them, such as `NioOnCaller`, on the
calling thread. The runtime and what the backends share is in `gears.async.io.internal`. See
[docs/io/README.md](docs/io/README.md) for the design.

The backends incubate in projects of their own, each with the contract suites from `ioTestkit`:

| Project | Backend | Platform |
| --- | --- | --- |
| `ioKqueue` (`io-backends/kqueue`) | kqueue | Scala Native, macOS |
| `ioNio` (`io-backends/nio`) | NIO selectors, or each op on the calling thread | JVM |

`ioTestkit` holds the gears demos, written against the interface alone, each taking the `Io`
and `Async` it runs with, their command line, and `higherlevel` (`gears.io.higherlevel`), a result-returning and
streaming API over the interface. Each backend has a demo application that runs them,
choosing the backend and starting the run: `ioKqueueDemos`, which also holds the plain kqueue
demos, over the kqueue backend's own types with no gears, and `ioNioDemos`. The bench is
`ioBench` (`bench/io-bench`). The kqueue backend and its demos were ported
from [bishabosha/scala-native-async-io@6e8320d](https://github.com/bishabosha/scala-native-async-io/commit/6e8320d36c71f75c9daf85065f5c89729f0825c0),
and extended with Unix datagrams and IPv4/IPv6 sockets.

### The kqueue demos

```bash
sbt 'ioKqueueDemos/run timer'
sbt 'ioKqueueDemos/run target-info'
sbt 'ioKqueueDemos/run echo --msg hello'
```

For the FIFO demos, create a named pipe with `mkfifo /tmp/gears-demo.fifo`, then
run the reader and writer in separate terminals:

```bash
sbt 'ioKqueueDemos/run file-read --fifo /tmp/gears-demo.fifo'
sbt 'ioKqueueDemos/run file-write --fifo /tmp/gears-demo.fifo -m hello'
```

For Unix domain stream sockets, start the server first, then the client in another terminal:

```bash
sbt 'ioKqueueDemos/run sock-serve --sock /tmp/gears-demo.sock'
sbt 'ioKqueueDemos/run sock --sock /tmp/gears-demo.sock'
```

Unix domain datagrams preserve message boundaries and echo each packet to its
sender. The client binds its own path so the server can reply:

```bash
sbt 'ioKqueueDemos/run datagram-serve --sock /tmp/gears-datagram.sock'
sbt 'ioKqueueDemos/run datagram --sock /tmp/gears-datagram.sock --local-sock /tmp/gears-client.sock -m hello'
```

Use distinct client and server paths, and a different client path for each
concurrent client. Bound Unix paths are removed when their resource scope exits;
force-stopping a process can leave a path behind, which the next bind removes.

IPv4 (`AF_INET`) and IPv6 (`AF_INET6`) demos default to the numeric localhost
addresses `127.0.0.1` and `::1`. Start each server before its corresponding client:

| Transport | Address family | Server | Client |
| --- | --- | --- | --- |
| TCP stream | IPv4 | `sbt 'ioKqueueDemos/run sock4-serve --port 9999'` | `sbt 'ioKqueueDemos/run sock4 --port 9999'` |
| TCP stream | IPv6 | `sbt 'ioKqueueDemos/run sock6-serve --port 9999'` | `sbt 'ioKqueueDemos/run sock6 --port 9999'` |
| UDP datagram | IPv4 | `sbt 'ioKqueueDemos/run datagram4-serve --port 9999'` | `sbt 'ioKqueueDemos/run datagram4 --port 9999 -m hello'` |
| UDP datagram | IPv6 | `sbt 'ioKqueueDemos/run datagram6-serve --port 9999'` | `sbt 'ioKqueueDemos/run datagram6 --port 9999 -m hello'` |

`--host` overrides the numeric address; DNS names and IPv6 scope suffixes are not
resolved. Ports must be in `0..65535` (binding port `0` asks the OS to choose one).
UDP clients receive an ephemeral local port automatically. Datagram clients send
one packet, wait up to five seconds for socket readiness or a reply, and exit.
Empty datagrams are valid; there is no stream length header. The receive buffer is
64 KiB and truncation is reported as an error; the OS may impose smaller send limits.
Stream demos use a four-byte big-endian request length (up to 1 MiB) and close the
connection after replying.

The key-value demos are a pipelined request and response service in the style of
Redis, over the same length-framed protocol. The server keeps each connection open,
answers `SET key value`, `GET key`, and `INCR key` in order, and writes replies while
further commands are still arriving. The client pipelines a batch of `--count`
increments without waiting, reading the replies as they come back, so each socket has
a read and a write pending at the same time. `kv4` and `kv6` use IP addresses:

```bash
sbt 'ioKqueueDemos/run kv-serve --sock /tmp/gears-kv.sock'
sbt 'ioKqueueDemos/run kv --sock /tmp/gears-kv.sock --count 20000'
```

`whois` pulls a real reply off the internet with the WHOIS protocol on port 43. It
resolves the host, sends one query line, and prints the raw text until the server
closes the connection. Name resolution is the `Resolve` command: there is no
non-blocking `getaddrinfo`, so it runs on a worker thread and signals the reactor
through a pipe, the way libuv does. `resolve` runs just that step:

```bash
sbt 'ioKqueueDemos/run whois --host whois.iana.org --query example.com'
sbt 'ioKqueueDemos/run resolve --host localhost'
```

Every reactor demo also has a gears mirror in `io-testkit/shared/src/main/scala/demos` (`gears.io.demos`),
with the same commands: `ioKqueueDemos/run gears kv-serve --sock /tmp/gears-kv.sock` runs it
on kqueue through the kqueue demos' executable. The mirrors are written against the interface
alone, so the same code runs on the JVM over NIO, through NIO's demo application:
`ioNioDemos/run kv-serve --sock /tmp/gears-kv.sock`; a demo needing a capability NIO lacks,
FIFOs or Unix datagram sockets, fails saying so. With `--backend reactors` (or
`-Dgears.io.nio.backend=reactors` among its JVM options), the futures are carried by
the reactor pool instead of the JDK's virtual thread scheduler. Each step is an op performed
with `Io.perform`, which suspends the caller until the op finishes, so the code reads
sequentially; wrapping a call in a `Future` runs the op alongside other work. Reactors are
thread-owned: `Io.run` makes the calling thread a reactor thread, running the
program as a future on a pool of workers, each a thread alternating between its queued tasks
and turns of its own reactor's loop, blocking only when it has no task to run and none to take
from another worker. With one worker, the default, everything a program spawns stays on the
calling thread; with more, a worker out of work takes queued tasks from the others, and a
task's ops go to the reactor of whichever worker runs it.

`ioBenchNative/run gears bench` is a stress test to measure reactor changes against: an
echo server and its clients run on two reactor threads in one process, through five
scenarios that each stress a different cost (one message in flight; the same with a
timeout armed and cancelled per round trip; many connections at once; full-duplex
pipelining with a write per message; and blocking tasks completing on worker threads).
`ioBenchNative/run bench` runs the same scenarios written against `KqueueLoop` directly,
with no reactor, as a reference for what a hand-written loop costs.
On the JVM, `ioBenchJVM/run bench [--backend caller|reactors]` runs them over NIO.
`bench/compare.sc before=<old executable> after=<new executable>` runs saved
builds alternately and reports each scenario's median microseconds per operation and the
change (add `raw=<executable>@raw` for the reference). The bench's scripts (`build.sc`,
`build_jvm.sc`, `compare.sc`, `syscalls.sc`) are Scala CLI scripts, run by the `scala` command of Scala 3.5 or later.
`bench/build.sc <commit> <output>` builds an executable to compare: the commit
linked in release-fast mode against a Scala Native nightly, because 0.5.12's GC can crash
multithreaded release builds of the gears bench (scala-native#5080). Setting
`SCALA_NATIVE_NIGHTLY=1` when starting sbt builds against the nightly too; otherwise the
build uses Scala Native 0.5.12.

Stop the long-running servers with Ctrl+C. Use `sbt 'show ioKqueueDemos/nativeLink'`
to build and locate the executable for running directly.

The `ioDemosTests` project (`io-tests`) holds JVM munit suites that link the demo executable
and drive it as a subprocess, and the gears demos on the JVM and the bench executables likewise, with Java sockets and FIFOs. They check timer expiry,
FIFO read/write readiness, and all six socket combinations, including fragmented
stream requests, queued datagrams, empty packets, multiple senders, and invalid
addresses. Java has no Unix domain datagram sockets, so that case uses native
clients only. The suites skip themselves on other operating systems.

```bash
sbt ioDemosTests/test
```
