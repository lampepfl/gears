# io-bench/stacks/kotlin

The TCP scenarios of `../README.md` on Kotlin coroutines and [ktor-network](https://ktor.io/docs/server-sockets.html):
Kotlin 2.4.21, kotlinx-coroutines 1.11.0, ktor-network 3.6.1, on the JVM. Sockets from `aSocket(selector).tcp()`,
each with its `openReadChannel()` and `openWriteChannel(autoFlush = false)`, suspending reads and writes with a
`flush()` where a write is meant to go out, and a coroutine for each side of a connection. The session cache, its
client and RESP (`Cache.kt`) are `../loom/Cache.scala` in Kotlin.

## Threads

- **`--workers`** is the parallelism of `Dispatchers.IO`, set through the `kotlinx.coroutines.io.parallelism`
  property before the dispatcher is first used. The scenarios' coroutines run on `Dispatchers.IO`
  (`runBlocking(Dispatchers.IO)`), and so do the two coroutines ktor-network starts for every socket, which is not a
  choice: ktor names `Dispatchers.IO` for them itself, whatever dispatcher the caller is on. Those coroutines are the
  ones that call `read` and `write` on the NIO channel; the scenario's coroutine meets them at a `ByteChannel`. So no
  more than `workers` threads at once run everything but the selector. The scheduler behind the dispatcher may have
  more threads than that, parked, and which of them runs may change.
- **The selector** (`SelectorManager`, ktor's `ActorSelectorManager`) is one coroutine that blocks in
  `Selector.select`, so it is given a thread of its own, named `selector`, beside the workers. It only selects and
  resumes: what it wakes is dispatched to `Dispatchers.IO`.
- The main thread waits in `runBlocking`. ktor launches a socket's close on `Dispatchers.Default`.

## Building and running

From the root of the repository (needs `gradle`, and a JDK on the `PATH`; made with Gradle 9.9.1 and JDK 25):

```
gradle -q -p io-bench/stacks/kotlin installDist
io-bench/stacks/kotlin/build/install/kotlin-bench/bin/kotlin-bench --workers 4
```

The first build fetches the Kotlin plugin and the libraries from Maven Central; after that `gradle --offline` builds
it too. The launcher is a shell script that starts `java` with the jars in `build/install/kotlin-bench/lib`, and takes
JVM options from `JAVA_OPTS`.

The eight TCP and work scenarios are run: `tcp-pingpong`, `tcp-concurrent`, `tcp-stream`, `tcp-connect`,
`tcp-realistic`, `tcp-realistic-bulk`, `tcp-realistic-gather` and `work-realistic`. There are no UDP scenarios.

## Where it differs

- **Every read and write is copied once more than a blocking channel's**, and crosses coroutines: ktor's coroutine
  reads the socket into the `ByteChannel`'s buffer and the scenario's coroutine copies out of it into its array
  (`readAvailable`, `readFully`), and the other way for a write (`writeFully`, then `flush`, which hands the bytes to
  ktor's writing coroutine and returns; it does not wait for the socket to have taken them).
- **`tcp-realistic-gather`** writes an answer's parts to the `ByteWriteChannel` one after another, a line for the
  length, the stored value's own array, a line's end, and flushes once for all the answers a read brought. No answer
  is assembled in an array of the server's, but ktor-network has no gathering write: each part is copied into the
  channel's buffer. Counts are kept as numbers there, as the loom stack's gathering cache keeps them.
- **`tcp-stream`**: the client flushes after each chunk and then closes its write channel (`flushAndClose`), at which
  ktor's writing coroutine shuts down the socket's output. The channel holds up to 1 MiB before `flush` suspends.
- **`tcp-connect`**: the client opens a read channel on each connection to read to the end of the stream, which
  starts a ktor coroutine for it. `close` on a ktor socket is asynchronous (a coroutine that closes the NIO channel
  once the socket's coroutines have ended) and is not waited for.
- **`TCP_NODELAY`** is set by ktor on every socket it accepts, and is its default for a socket it connects; the
  client asks for it all the same.
