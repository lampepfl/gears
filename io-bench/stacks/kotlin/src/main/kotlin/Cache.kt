// The session cache of `tcp-realistic` and its client, and the protocol they speak: `CapBench`'s (`io-bench/shared`),
// as `io-bench/stacks/loom/Cache.scala` has it, in Kotlin, so that the work this stack does is the work the others do.

const val LargestValue = 2048
const val Batch = 16
const val LargestBatch = Batch * LargestValue

private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()

/** RESP, the protocol of Redis: values written into arrays of bytes, and found in them. */
object Resp {
    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    fun text(from: ByteArray, at: Int, length: Int): String = String(from, at, length, Charsets.ISO_8859_1)

    /** Writes `text` at `at`: where it ends. */
    fun put(into: ByteArray, at: Int, text: ByteArray): Int {
        System.arraycopy(text, 0, into, at, text.size)
        return at + text.size
    }

    /** Writes `number` in decimal and the end of a line. */
    fun putLine(into: ByteArray, at: Int, number: Int): Int {
        val end = put(into, at, bytes(number.toString()))
        into[end] = CR
        into[end + 1] = LF
        return end + 2
    }

    /** Writes a bulk string: `$`, its length, a line's end, its bytes, a line's end. */
    fun putBulk(into: ByteArray, at: Int, from: ByteArray, start: Int, length: Int): Int {
        into[at] = '$'.code.toByte()
        val body = putLine(into, at + 1, length)
        System.arraycopy(from, start, into, body, length)
        into[body + length] = CR
        into[body + length + 1] = LF
        return body + length + 2
    }

    /** Where the line that begins at `at` ends, past its `\r\n`; -1 if it has not all come. */
    fun lineEnd(input: ByteArray, at: Int, until: Int): Int {
        var i = at
        while (i + 1 < until) {
            if (input[i] == CR && input[i + 1] == LF) return i + 2
            i += 1
        }
        return -1
    }

    /** The decimal number from `at` to the end of its line. */
    fun number(input: ByteArray, at: Int): Int {
        var i = at
        val negative = input[i] == '-'.code.toByte()
        if (negative) i += 1
        var value = 0
        while (input[i] != CR) {
            value = value * 10 + (input[i] - '0'.code)
            i += 1
        }
        return if (negative) -value else value
    }

    /** Where the value that begins at `at` ends; -1 if it has not all come. */
    fun end(input: ByteArray, at: Int, until: Int): Int {
        val line = lineEnd(input, at, until)
        if (line < 0) return -1
        return when (input[at].toInt().toChar()) {
            '$' -> {
                val length = number(input, at + 1)
                if (length < 0) line else if (line + length + 2 <= until) line + length + 2 else -1
            }
            '*' -> {
                var left = number(input, at + 1)
                var next = line
                while (left > 0 && next >= 0) {
                    next = end(input, next, until)
                    left -= 1
                }
                next
            }
            else -> line // a status, an error or an integer: its line
        }
    }
}

/** A command, an array of bulk strings, taken apart: `*<n>\r\n $<l>\r\n <name>\r\n $<l>\r\n <key>\r\n [ $<l>\r\n <value>\r\n ]`. */
class Command {
    var isSet = false
    var isGet = false
    var isIncr = false
    var key = ""
    var valueAt = 0
    var valueLength = 0

    /** Reads the command that begins at `from` in `request`. */
    fun read(request: ByteArray, from: Int) {
        val until = request.size
        val parts = Resp.number(request, from + 1)
        var next = Resp.lineEnd(request, from, until)
        val nameLength = Resp.number(request, next + 1)
        val name = Resp.lineEnd(request, next, until)
        next = name + nameLength + 2
        val keyLength = Resp.number(request, next + 1)
        val keyAt = Resp.lineEnd(request, next, until)
        key = Resp.text(request, keyAt, keyLength)
        next = keyAt + keyLength + 2
        isSet = nameLength == 3 && request[name] == 'S'.code.toByte() && parts == 3
        isGet = !isSet && nameLength == 3 && request[name] == 'G'.code.toByte()
        isIncr = !isSet && !isGet && nameLength == 4 && request[name] == 'I'.code.toByte()
        if (isSet) {
            valueLength = Resp.number(request, next + 1)
            valueAt = Resp.lineEnd(request, next, until)
        }
    }
}

/** The cache: what it keeps for one connection, and how it answers `SET`, `GET` and `INCR`. */
class CacheServer {
    private val values = HashMap<String, ByteArray>()
    private val command = Command()
    private val ok = Resp.bytes("+OK\r\n")
    private val nothing = Resp.bytes("$-1\r\n")
    private val unknown = Resp.bytes("-ERR unknown command\r\n")

    /** Answers the command that begins at `from` in `request` into `response` at `at`: where the answer ends. */
    fun handle(request: ByteArray, from: Int, response: ByteArray, at: Int): Int {
        command.read(request, from)
        val key = command.key
        return when {
            command.isSet -> {
                values[key] = request.copyOfRange(command.valueAt, command.valueAt + command.valueLength)
                Resp.put(response, at, ok)
            }
            command.isGet -> {
                val value = values[key]
                if (value == null) Resp.put(response, at, nothing) else Resp.putBulk(response, at, value, 0, value.size)
            }
            command.isIncr -> {
                val held = values[key]
                val count = (if (held == null) 0 else Resp.number(held, 0)) + 1
                values[key] = Resp.bytes("$count\r") // kept as Redis keeps a count, its digits
                response[at] = ':'.code.toByte()
                Resp.putLine(response, at + 1, count)
            }
            else -> Resp.put(response, at, unknown)
        }
    }
}

/** A client of the cache, for the user `id`: it keeps the user's sessions there, reads them back, and counts the
  * user's requests. What it asks follows from the request's number, and it knows what each answer must be.
  */
class CacheClient(private val id: Int) {
    private val kept = HashMap<String, ByteArray>()
    private var hits = 0
    private var expected: ByteArray? = null
    private val set = Resp.bytes("*3\r\n$3\r\nSET\r\n")
    private val get = Resp.bytes("*2\r\n$3\r\nGET\r\n")
    private val incr = Resp.bytes("*2\r\n$4\r\nINCR\r\n")
    private val ok = Resp.bytes("+OK\r\n")
    private val nothing = Resp.bytes("$-1\r\n")

    /** A session as a program would keep one: a document of JSON, some 500 bytes of it. */
    private fun session(i: Int): ByteArray {
        val text = StringBuilder(640)
        text.append("{\"user\":").append(id).append(",\"seq\":").append(i)
        text.append(",\"token\":\"").append(Integer.toHexString(id * 0x9e3779b1.toInt() + i)).append("-")
        text.append(Integer.toHexString(i * 0x85ebca6b.toInt() + id)).append("\",\"expires\":").append(1795000000L + i)
        text.append(",\"cart\":[")
        for (item in 0 until 8) {
            if (item > 0) text.append(',')
            text.append("{\"sku\":\"SKU-").append(100000 + (id * 131 + i * 17 + item * 7919) % 900000)
            text.append("\",\"qty\":").append(1 + (i + item) % 5).append(",\"price\":").append(199 + (i * 37 + item * 101) % 9800)
            text.append('}')
        }
        text.append("],\"locale\":\"en-GB\",\"theme\":\"").append(if (i % 2 == 0) "dark" else "light").append("\"}")
        return Resp.bytes(text.toString())
    }

    /** What the answer to the last request written must be. */
    val answer: ByteArray get() = expected!!

    /** Writes request `i` into `request` at `at`: where it ends. One in five keeps a session, one in five counts a
      * request, and the rest read a session back.
      */
    fun request(i: Int, request: ByteArray, at: Int): Int {
        if (i % 5 == 3) {
            hits += 1
            expected = Resp.bytes(":$hits\r\n")
            val key = Resp.bytes("hits:$id")
            return Resp.putBulk(request, Resp.put(request, at, incr), key, 0, key.size)
        }
        val name = "session:$id:${i * 7 % 50}"
        val key = Resp.bytes(name)
        if (i % 5 == 0) {
            val document = session(i)
            kept[name] = document
            expected = ok
            val value = Resp.putBulk(request, Resp.put(request, at, set), key, 0, key.size)
            return Resp.putBulk(request, value, document, 0, document.size)
        }
        val document = kept[name]
        expected =
            if (document == null) nothing
            else {
                val whole = ByteArray(document.size + 16)
                whole.copyOf(Resp.putBulk(whole, 0, document, 0, document.size))
            }
        return Resp.putBulk(request, Resp.put(request, at, get), key, 0, key.size)
    }

    /** Fails unless the bytes of `response` from `from` to `until` are `right`, the answer to request `i`. */
    fun check(i: Int, right: ByteArray, response: ByteArray, from: Int, until: Int) {
        var same = right.size == until - from
        var at = 0
        while (same && at < right.size) {
            same = right[at] == response[from + at]
            at += 1
        }
        if (!same) throw IllegalStateException("request $i of client $id was answered wrongly")
    }
}
