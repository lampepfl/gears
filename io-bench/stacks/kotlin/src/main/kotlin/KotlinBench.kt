import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import java.io.EOFException
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val MessageSize = 64
private const val ChunkSize = 65536

val AllScenarios: List<String> = listOf(
    "tcp-pingpong",
    "tcp-concurrent",
    "tcp-stream",
    "tcp-connect",
    "tcp-realistic",
    "tcp-realistic-bulk",
    "tcp-realistic-gather",
    "work-realistic",
)

data class Config(
    val workers: Int = 1,
    val scenarios: List<String> = AllScenarios,
    val scale: Double = 1.0,
    val connections: Int = 64,
    val repeat: Int = 1,
) {
    fun n(count: Int): Int = maxOf(1, Math.floor(count * scale).toInt())
}

fun parse(args: Array<String>): Config {
    var config = Config()
    var at = 0
    while (at < args.size) {
        val name = args[at]
        val value = args.getOrNull(at + 1) ?: throw IllegalArgumentException("unknown or incomplete argument: $name")
        config = when (name) {
            "--workers" -> config.copy(workers = value.toInt())
            "--scenarios" -> config.copy(scenarios = value.split(',').filter { it.isNotEmpty() })
            "--scale" -> config.copy(scale = value.toDouble())
            "--connections" -> config.copy(connections = value.toInt())
            "--repeat" -> config.copy(repeat = value.toInt())
            else -> throw IllegalArgumentException("unknown or incomplete argument: $name")
        }
        at += 2
    }
    return config
}

/** One end of a connection: the socket, and the two channels ktor-network pumps its bytes through. */
class End(val socket: Socket) {
    val input: ByteReadChannel = socket.openReadChannel()
    val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)

    /** Writes the bytes and flushes: one write to the socket, if it takes them all. */
    suspend fun send(bytes: ByteArray, length: Int = bytes.size) {
        output.writeFully(bytes, 0, length)
        output.flush()
    }
}

/** A connection, as (client end, server end). */
class Pair(val client: End, val server: End)

/** The scenarios of `io-bench/stacks/README.md` on ktor-network: suspending reads and writes, and a coroutine for
  * each side of a connection.
  */
class Scenarios(private val selector: SelectorManager) {

    suspend fun run(config: Config) {
        repeat(config.repeat) {
            for (name in config.scenarios) scenario(config, name)
        }
    }

    private suspend fun scenario(config: Config, name: String) {
        when (name) {
            "tcp-pingpong" -> tcpEcho(name, 1, config.n(100_000))
            "tcp-concurrent" -> tcpEcho(name, config.connections, config.n(5_000))
            "tcp-stream" -> tcpStream(name, config.n(20_000))
            "tcp-connect" -> tcpConnect(name, config.n(1_000))
            "tcp-realistic" -> realistic(name, config.connections, config.n(2_000), ::serve, ::ask)
            "tcp-realistic-bulk" ->
                realistic(name, config.connections, config.n(2_000), { end, rounds -> serveTogether(end, rounds, false) }, ::askTogether)
            "tcp-realistic-gather" ->
                realistic(name, config.connections, config.n(2_000), { end, rounds -> serveTogether(end, rounds, true) }, ::askTogether)
            "work-realistic" -> work(name, config.connections, config.n(2_000))
            else -> Unit // a scenario this stack cannot do prints nothing
        }
    }

    private inline fun timed(name: String, ops: Long, work: () -> Unit) {
        val start = System.nanoTime()
        work()
        val seconds = (System.nanoTime() - start) / 1e9
        println(String.format(Locale.ROOT, "bench: %-20s %9d ops %8.3f s %9.3f us/op", name, ops, seconds, seconds * 1e6 / ops))
    }

    // ---- connections

    private suspend fun listen(): ServerSocket = aSocket(selector).tcp().bind("127.0.0.1", 0)

    private suspend fun connect(server: ServerSocket): Socket =
        aSocket(selector).tcp().connect(server.localAddress) { noDelay = true }

    /** `count` connections through one listener, made one after another. ktor sets `TCP_NODELAY` on what it accepts. */
    private suspend fun connected(count: Int, use: suspend (List<Pair>) -> Unit) {
        val server = listen()
        val pairs = ArrayList<Pair>(count)
        try {
            repeat(count) {
                val client = End(connect(server))
                pairs.add(Pair(client, End(server.accept())))
            }
            use(pairs)
        } finally {
            for (pair in pairs) {
                pair.client.socket.close()
                pair.server.socket.close()
            }
            server.close()
        }
    }

    // ---- the echo, the stream and the connects

    private suspend fun tcpEcho(name: String, count: Int, rounds: Int) =
        connected(count) { pairs ->
            timed(name, rounds.toLong() * count) {
                coroutineScope {
                    for (pair in pairs) {
                        launch {
                            val server = pair.server
                            val message = ByteArray(MessageSize)
                            repeat(rounds) {
                                server.input.readFully(message)
                                server.send(message)
                            }
                        }
                        launch {
                            val client = pair.client
                            val out = ByteArray(MessageSize)
                            val back = ByteArray(MessageSize)
                            repeat(rounds) {
                                client.send(out)
                                client.input.readFully(back)
                            }
                        }
                    }
                }
            }
        }

    private suspend fun tcpStream(name: String, chunks: Int) =
        connected(1) { pairs ->
            val pair = pairs[0]
            timed(name, chunks.toLong()) {
                coroutineScope {
                    launch {
                        val chunk = ByteArray(ChunkSize)
                        repeat(chunks) { pair.client.send(chunk) }
                        // Closing the channel ends ktor's writing coroutine, which shuts down the socket's output.
                        pair.client.output.flushAndClose()
                    }
                    launch {
                        val buf = ByteArray(ChunkSize)
                        while (pair.server.input.readAvailable(buf, 0, ChunkSize) >= 0) Unit
                    }
                }
            }
        }

    private suspend fun tcpConnect(name: String, count: Int) {
        val server = listen()
        try {
            timed(name, count.toLong()) {
                coroutineScope {
                    launch {
                        repeat(count) { server.accept().close() }
                    }
                    launch {
                        val buf = ByteArray(MessageSize)
                        repeat(count) {
                            val client = connect(server)
                            val input = client.openReadChannel()
                            while (input.readAvailable(buf, 0, MessageSize) >= 0) Unit
                            client.close()
                        }
                    }
                }
            }
        } finally {
            server.close()
        }
    }

    // ---- the session cache

    /** What is read from a connection that carries RESP values: an array, filled from the channel. */
    private class RespInput(private val channel: ByteReadChannel, size: Int) {
        val bytes = ByteArray(size)

        /** Reads once, whatever has come, at `have`: how many bytes there are then. */
        suspend fun more(have: Int): Int {
            val read = channel.readAvailable(bytes, have, bytes.size - have)
            if (read < 0) throw EOFException("The connection ended within a value")
            return have + read
        }

        /** Receives one value, however many reads it comes in: where it ends. */
        suspend fun receive(): Int {
            var have = 0
            var end = -1
            while (end < 0) {
                have = more(have)
                end = Resp.end(bytes, 0, have)
            }
            return end
        }
    }

    private suspend fun serve(end: End, rounds: Int) {
        val input = RespInput(end.input, LargestValue)
        val cache = CacheServer()
        val response = ByteArray(LargestValue)
        repeat(rounds) {
            input.receive()
            end.send(response, cache.handle(input.bytes, 0, response, 0))
        }
    }

    private suspend fun ask(end: End, id: Int, rounds: Int) {
        val input = RespInput(end.input, LargestValue)
        val user = CacheClient(id)
        val request = ByteArray(LargestValue)
        for (i in 0 until rounds) {
            end.send(request, user.request(i, request, 0))
            user.check(i, user.answer, input.bytes, 0, input.receive())
        }
    }

    /** The cache as `tcp-realistic-gather` keeps it: a value is an array of its own, and an answer is written to the
      * channel as its parts, a line for its length, the value's own array and a line's end, with no answer assembled
      * in between. The channel is flushed once for all the answers of a batch.
      */
    private class GatheringCache(private val out: ByteWriteChannel) {
        private val values = HashMap<String, ByteArray>()
        private val counts = HashMap<String, Int>()
        private val command = Command()
        private val scratch = ByteArray(16)
        private val ok = Resp.bytes("+OK\r\n")
        private val nothing = Resp.bytes("$-1\r\n")
        private val lineEnd = Resp.bytes("\r\n")
        private val unknown = Resp.bytes("-ERR unknown command\r\n")

        private suspend fun line(mark: Char, number: Int) {
            scratch[0] = mark.code.toByte()
            out.writeFully(scratch, 0, Resp.putLine(scratch, 1, number))
        }

        suspend fun handle(request: ByteArray, from: Int) {
            command.read(request, from)
            val key = command.key
            when {
                command.isSet -> {
                    values[key] = request.copyOfRange(command.valueAt, command.valueAt + command.valueLength)
                    out.writeFully(ok)
                }
                command.isGet -> {
                    val value = values[key]
                    if (value == null) out.writeFully(nothing)
                    else {
                        line('$', value.size)
                        out.writeFully(value)
                        out.writeFully(lineEnd)
                    }
                }
                command.isIncr -> {
                    val now = (counts[key] ?: 0) + 1
                    counts[key] = now
                    line(':', now)
                }
                else -> out.writeFully(unknown)
            }
        }
    }

    private suspend fun serveTogether(end: End, rounds: Int, gathering: Boolean) {
        val input = RespInput(end.input, LargestBatch)
        val requests = input.bytes
        val cache = CacheServer()
        val gathered = GatheringCache(end.output)
        val responses = ByteArray(LargestBatch)
        var have = 0
        var answered = 0
        while (answered < rounds) {
            have = input.more(have)
            var from = 0
            var written = 0
            var next = Resp.end(requests, from, have)
            while (next >= 0) {
                if (gathering) gathered.handle(requests, from)
                else written = cache.handle(requests, from, responses, written)
                answered += 1
                from = next
                next = Resp.end(requests, from, have)
            }
            // What is left is the beginning of a request, the rest of which has yet to come.
            if (from > 0) {
                System.arraycopy(requests, from, requests, 0, have - from)
                have -= from
                if (gathering) end.output.flush() else end.send(responses, written)
            }
        }
    }

    private suspend fun askTogether(end: End, id: Int, rounds: Int) {
        val input = RespInput(end.input, LargestBatch)
        val responses = input.bytes
        val user = CacheClient(id)
        val requests = ByteArray(LargestBatch)
        val answers = arrayOfNulls<ByteArray>(Batch)
        var i = 0
        while (i < rounds) {
            val batch = minOf(Batch, rounds - i)
            var length = 0
            for (k in 0 until batch) {
                length = user.request(i + k, requests, length)
                answers[k] = user.answer
            }
            end.send(requests, length)
            var have = 0
            var from = 0
            var checked = 0
            while (checked < batch) {
                have = input.more(have)
                var next = Resp.end(responses, from, have)
                while (next >= 0 && checked < batch) {
                    user.check(i + checked, answers[checked]!!, responses, from, next)
                    checked += 1
                    from = next
                    next = Resp.end(responses, from, have)
                }
            }
            i += batch
        }
    }

    private suspend fun realistic(
        name: String,
        count: Int,
        rounds: Int,
        serving: suspend (End, Int) -> Unit,
        asking: suspend (End, Int, Int) -> Unit,
    ) =
        connected(count) { pairs ->
            timed(name, rounds.toLong() * count) {
                coroutineScope {
                    pairs.forEachIndexed { id, pair ->
                        launch { serving(pair.server, rounds) }
                        launch { asking(pair.client, id, rounds) }
                    }
                }
            }
        }

    /** The work of `tcp-realistic` with nothing between the two sides. */
    private fun work(name: String, count: Int, rounds: Int) =
        timed(name, rounds.toLong() * count) {
            val request = ByteArray(LargestValue)
            val response = ByteArray(LargestValue)
            for (id in 0 until count) {
                val cache = CacheServer()
                val user = CacheClient(id)
                for (i in 0 until rounds) {
                    user.request(i, request, 0)
                    user.check(i, user.answer, response, 0, cache.handle(request, 0, response, 0))
                }
            }
        }
}

fun main(args: Array<String>) {
    val config = parse(args)
    // ktor-network runs the coroutines that read and write its sockets on `Dispatchers.IO`, whatever the caller's
    // dispatcher is. So `--workers` is that dispatcher's parallelism, said before it is first used, and the
    // scenarios' own coroutines run on it too: `workers` threads for everything but the selector.
    System.setProperty("kotlinx.coroutines.io.parallelism", config.workers.toString())
    // The selector blocks its thread in `select`, so it has one of its own.
    val selecting = Executors.newSingleThreadExecutor { task -> Thread(task, "selector").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    val selector = SelectorManager(selecting)
    try {
        runBlocking(Dispatchers.IO) { Scenarios(selector).run(config) }
    } finally {
        selector.close()
        selecting.close()
    }
}
