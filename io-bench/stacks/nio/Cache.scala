/** The session cache of `tcp-realistic` and its client, and the protocol they speak: the same source as
  * `CapBench`'s (`io-bench/shared`), copied, so that the work this stack does is the work the gears backends do.
  */
object Cache {
  /** RESP, the protocol of Redis: values written into arrays of bytes, and found in them. */
  object Resp {
    private val Latin1 = java.nio.charset.StandardCharsets.ISO_8859_1

    def bytes(text: String): Array[Byte] = text.getBytes(Latin1)
    def text(from: Array[Byte], at: Int, length: Int): String = new String(from, at, length, Latin1)

    /** Writes `text` at `at`: where it ends. */
    def put(into: Array[Byte], at: Int, text: Array[Byte]): Int = {
      System.arraycopy(text, 0, into, at, text.length)
      at + text.length
    }

    /** Writes `number` in decimal and the end of a line. */
    def putLine(into: Array[Byte], at: Int, number: Int): Int = {
      var end = put(into, at, bytes(Integer.toString(number)))
      into(end) = '\r'
      into(end + 1) = '\n'
      end + 2
    }

    /** Writes a bulk string: `$`, its length, a line's end, its bytes, a line's end. */
    def putBulk(into: Array[Byte], at: Int, from: Array[Byte], start: Int, length: Int): Int = {
      into(at) = '$'
      val body = putLine(into, at + 1, length)
      System.arraycopy(from, start, into, body, length)
      into(body + length) = '\r'
      into(body + length + 1) = '\n'
      body + length + 2
    }

    /** Where the line that begins at `at` ends, past its `\r\n`; -1 if it has not all come. */
    def lineEnd(in: Array[Byte], at: Int, until: Int): Int = {
      var i = at
      while i + 1 < until do {
        if in(i) == '\r' && in(i + 1) == '\n' then return i + 2
        i += 1
      }
      -1
    }

    /** The decimal number from `at` to the end of its line. */
    def number(in: Array[Byte], at: Int): Int = {
      var i = at
      val negative = in(i) == '-'
      if negative then i += 1
      var value = 0
      while in(i) != '\r' do {
        value = value * 10 + (in(i) - '0')
        i += 1
      }
      if negative then -value else value
    }

    /** Where the value that begins at `at` ends; -1 if it has not all come. */
    def end(in: Array[Byte], at: Int, until: Int): Int = {
      val line = lineEnd(in, at, until)
      if line < 0 then -1
      else
        in(at) match {
          case '$' =>
            val length = number(in, at + 1)
            if length < 0 then line else if line + length + 2 <= until then line + length + 2 else -1
          case '*' =>
            var left = number(in, at + 1)
            var next = line
            while left > 0 && next >= 0 do {
              next = end(in, next, until)
              left -= 1
            }
            next
          case _ => line // a status, an error or an integer: its line
        }
    }
  }

  /** The cache: what it keeps for one connection, and how it answers `SET`, `GET` and `INCR`. */
  final class CacheServer(
      values: java.util.Map[String, Array[Byte]] = new java.util.HashMap[String, Array[Byte]]()
  ) {
    private val Ok = Resp.bytes("+OK\r\n")
    private val Nothing = Resp.bytes("$-1\r\n")
    private val Unknown = Resp.bytes("-ERR unknown command\r\n")

    /** Answers the command that begins at `from` in `request`, an array of bulk strings, into `response` at `at`:
      * where the answer ends.
      */
    def handle(request: Array[Byte], from: Int, response: Array[Byte], at: Int): Int = {
      // *<n>\r\n $<l>\r\n <name>\r\n $<l>\r\n <key>\r\n [ $<l>\r\n <value>\r\n ]
      val until = request.length
      val parts = Resp.number(request, from + 1)
      var next = Resp.lineEnd(request, from, until)
      val nameLength = Resp.number(request, next + 1)
      val name = Resp.lineEnd(request, next, until)
      next = name + nameLength + 2
      val keyLength = Resp.number(request, next + 1)
      val keyAt = Resp.lineEnd(request, next, until)
      val key = Resp.text(request, keyAt, keyLength)
      next = keyAt + keyLength + 2
      if nameLength == 3 && request(name) == 'S' && parts == 3 then {
        val length = Resp.number(request, next + 1)
        val valueAt = Resp.lineEnd(request, next, until)
        values.put(key, java.util.Arrays.copyOfRange(request, valueAt, valueAt + length))
        Resp.put(response, at, Ok)
      } else if nameLength == 3 && request(name) == 'G' then {
        val value = values.get(key)
        if value == null then Resp.put(response, at, Nothing) else Resp.putBulk(response, at, value, 0, value.length)
      } else if nameLength == 4 && request(name) == 'I' then {
        // Kept as Redis keeps a count, its digits; counted in one step, for a table that connections share.
        val kept = values.compute(
          key,
          (_, held) => Resp.bytes(Integer.toString((if held == null then 0 else Resp.number(held, 0)) + 1) + "\r")
        )
        response(at) = ':'
        Resp.putLine(response, at + 1, Resp.number(kept, 0))
      } else Resp.put(response, at, Unknown)
    }
  }

  /** A client of the cache, for the user `id`: it keeps the user's sessions there, reads them back, and counts the
    * user's requests. What it asks follows from the request's number, and it knows what each answer must be.
    */
  final class CacheClient(id: Int) {
    private val kept = new java.util.HashMap[String, Array[Byte]]()
    private var hits = 0
    private var expected: Array[Byte] | Null = null
    private val Set = Resp.bytes("*3\r\n$3\r\nSET\r\n")
    private val Get = Resp.bytes("*2\r\n$3\r\nGET\r\n")
    private val Incr = Resp.bytes("*2\r\n$4\r\nINCR\r\n")
    private val Ok = Resp.bytes("+OK\r\n")
    private val Nothing = Resp.bytes("$-1\r\n")

    /** A session as a program would keep one: a document of JSON, some 500 bytes of it. */
    private def session(i: Int): Array[Byte] = {
      val text = new java.lang.StringBuilder(640)
      text.append("{\"user\":").append(id).append(",\"seq\":").append(i)
      text.append(",\"token\":\"").append(Integer.toHexString(id * 0x9e3779b1 + i)).append("-")
      text.append(Integer.toHexString(i * 0x85ebca6b + id)).append("\",\"expires\":").append(1795000000L + i)
      text.append(",\"cart\":[")
      var item = 0
      while item < 8 do {
        if item > 0 then text.append(',')
        text.append("{\"sku\":\"SKU-").append(100000 + (id * 131 + i * 17 + item * 7919) % 900000)
        text.append("\",\"qty\":").append(1 + (i + item) % 5).append(",\"price\":").append(199 + (i * 37 + item * 101) % 9800)
        text.append('}')
        item += 1
      }
      text.append("],\"locale\":\"en-GB\",\"theme\":\"").append(if i % 2 == 0 then "dark" else "light").append("\"}")
      Resp.bytes(text.toString)
    }

    /** What the answer to the last request written must be. */
    def answer: Array[Byte] = expected.nn

    /** Writes request `i` into `request` at `at`: where it ends. One in five keeps a session, one in five counts a
      * request, and the rest read a session back.
      */
    def request(i: Int, request: Array[Byte], at: Int): Int =
      if i % 5 == 3 then {
        hits += 1
        expected = Resp.bytes(":" + hits + "\r\n")
        val key = Resp.bytes("hits:" + id)
        Resp.putBulk(request, Resp.put(request, at, Incr), key, 0, key.length)
      } else {
        val name = "session:" + id + ":" + (i * 7 % 50)
        val key = Resp.bytes(name)
        if i % 5 == 0 then {
          val document = session(i)
          kept.put(name, document)
          expected = Ok
          val value = Resp.putBulk(request, Resp.put(request, at, Set), key, 0, key.length)
          Resp.putBulk(request, value, document, 0, document.length)
        } else {
          val document = kept.get(name)
          expected =
            if document == null then Nothing
            else {
              val whole = new Array[Byte](document.length + 16)
              java.util.Arrays.copyOf(whole, Resp.putBulk(whole, 0, document, 0, document.length))
            }
          Resp.putBulk(request, Resp.put(request, at, Get), key, 0, key.length)
        }
      }

    /** Fails unless the bytes of `response` from `from` to `until` are `right`, the answer to request `i`. */
    def check(i: Int, right: Array[Byte], response: Array[Byte], from: Int, until: Int): Unit = {
      var same = right.length == until - from
      var at = 0
      while same && at < right.length do {
        same = right(at) == response(from + at)
        at += 1
      }
      if !same then throw new IllegalStateException(s"request $i of client $id was answered wrongly")
    }
  }

  inline val LargestValue = 2048
  inline val Batch = 16
  inline val LargestBatch = Batch * LargestValue
}
