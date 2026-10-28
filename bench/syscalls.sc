#!/usr/bin/env -S scala shebang
//> using scala 3.9.0
//> using dep com.lihaoyi::ujson:4.4.1

/** Counts the system calls each bench scenario makes per operation, using lldb breakpoints, so it needs neither root
  * nor SIP disabled as dtruss does.
  *
  * {{{
  * bench/syscalls.sc [--save FILE] [--scenarios a,b] <executable>[@raw]
  * }}}
  *
  * Each scenario runs twice, at about 100 and 200 operations (more where a scenario works in batches), and the
  * difference divided by the extra operations is the count per operation, without connection setup and teardown. Calls
  * are counted on the client thread, the main thread, which runs the reactor under test; the bench's echo server and
  * any workers are counted apart, as `other`. `kevent` is split by what the call does: registration changes only, a
  * poll that returns at once, or a wait. Under the debugger the server answers slowly, so the split between polls and
  * waits leans to waits after spinning, and is only indicative; the other counts do not depend on timing.
  *
  * lldb runs in batch mode: each system call has a breakpoint that continues by itself after printing the thread
  * (`thread info`, whose first thread is the main one) and, for `kevent`, its arguments, which this counts.
  */

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

val Symbols = Seq("kevent", "read", "write", "__recvfrom", "__recvmsg", "__sendto", "__accept", "__connect", "__fcntl",
  "__ioctl", "close", "sigprocmask", "__sigaltstack", "__psynch_cvsignal")

/** Operations at scale 1, and how many to count with (the second run counts twice as many). */
val Scenarios = scala.collection.immutable.ListMap(
  "pingpong" -> (50_000, 100), "pingpong-fresh-ops" -> (50_000, 100), "pingpong-deadline" -> (50_000, 100),
  "concurrent" -> (100_000, 640), "connections" -> (20_000, 100), "pipelined" -> (200_000, 200),
  "blocking" -> (100_000, 100), "blocking-io" -> (50_000, 100), "timers" -> (200_000, 2000),
  "fairness" -> (200_000, 200), "mixed" -> (50_000, 100)
)
val GearsOnly = Set("pingpong-fresh-ops", "fairness", "mixed")

type Counts = Map[String, Map[String, Int]] // "client" and "other": name -> calls

/** Runs `command` under lldb and returns its system call counts. */
def count(command: Seq[String]): Counts =
  val lldb = mutable.ArrayBuffer("lldb", "--batch", "--no-lldbinit", "-o", s"target create \"${command.head}\"")
  for symbol <- Symbols do
    val commands = Seq("thread info") ++
      (if symbol == "kevent" then Seq("register read x2 x4 x5", "memory read -s 8 -c 2 -f x $x5") else Nil)
    lldb ++= Seq("-o", s"breakpoint set -n $symbol -s libsystem_kernel.dylib -G true " +
      commands.map(c => s"-C '$c'").mkString(" "))
  lldb ++= Seq("-o", s"process launch -o /dev/null -- ${command.tail.map(a => s"\"$a\"").mkString(" ")}")
  val process = new ProcessBuilder(lldb.asJava).redirectErrorStream(true).start()
  val counts = Map("client" -> mutable.Map[String, Int](), "other" -> mutable.Map[String, Int]())
  val Hit = """thread #(\d+): tid = \S+, \S+ libsystem_kernel\.dylib`(\w+).*stop reason = breakpoint.*""".r
  val Register = """\s*(x[245]) = (0x[0-9a-f]+).*""".r
  val Memory = """0x[0-9a-f]+: (0x[0-9a-f]+) (0x[0-9a-f]+).*""".r
  val Exited = """Process \d+ exited with status = (-?\d+).*""".r
  var exit: Option[Int] = None
  // A kevent hit's details come after its line, so it is counted once they have all been read.
  var pending: Option[(String, String)] = None // (thread kind, name)
  val registers = mutable.Map[String, Long]()
  var zeroTimeout = false
  def flush(): Unit = pending.foreach { (kind, name) =>
    val counted =
      if name != "kevent" then name
      else if registers.getOrElse("x4", 0L) == 0 then "kevent (changes)"
      else
        val now = registers.getOrElse("x5", 0L) != 0 && zeroTimeout
        (if now then "kevent (poll now)" else "kevent (wait)") + (if registers.getOrElse("x2", 0L) != 0 then " + changes" else "")
    counts(kind)(counted) = counts(kind).getOrElse(counted, 0) + 1
    pending = None
    registers.clear()
    zeroTimeout = false
  }
  for line <- scala.io.Source.fromInputStream(process.getInputStream).getLines() do
    line.trim match
      case Hit(index, name) =>
        flush()
        pending = Some((if index == "1" then "client" else "other", name))
      case Register(register, value) if pending.exists(_._2 == "kevent") =>
        registers(register) = java.lang.Long.parseUnsignedLong(value.drop(2), 16)
      case Memory(first, second) if pending.exists(_._2 == "kevent") =>
        zeroTimeout = java.lang.Long.parseUnsignedLong(first.drop(2), 16) == 0 &&
          java.lang.Long.parseUnsignedLong(second.drop(2), 16) == 0
      case Exited(status) => exit = Some(status.toInt)
      case _              => ()
  flush()
  process.waitFor()
  if !exit.contains(0) then
    System.err.println(s"${command.mkString(" ")} exited with ${exit.getOrElse("no status")}")
    sys.exit(1)
  counts.view.mapValues(_.toMap).toMap

def perOperation(path: String, raw: Boolean, name: String): Map[String, Map[String, Double]] =
  val (full, ops) = Scenarios(name)
  val runs = Seq(ops, 2 * ops).map { n =>
    count(Seq(path) ++ (if raw then Nil else Seq("gears")) ++ Seq("bench", "--scale", (n.toDouble / full).toString,
      "--scenarios", name))
  }
  Seq("client", "other").map { kind =>
    val names = (runs(0)(kind).keySet ++ runs(1)(kind).keySet).toSeq.sorted
    kind -> names.map { k =>
      val diff = (runs(1)(kind).getOrElse(k, 0) - runs(0)(kind).getOrElse(k, 0)).toDouble / ops
      k -> BigDecimal(diff).setScale(2, BigDecimal.RoundingMode.HALF_EVEN).toDouble
    }.filter(_._2 != 0).toMap
  }.toMap

/** As Python's `{:g}` writes these, two decimals at most and no trailing zeros. */
def short(v: Double): String = java.math.BigDecimal.valueOf(v).stripTrailingZeros.toPlainString

var save: Option[String] = None
var chosen: Option[Seq[String]] = None
var executable: Option[String] = None
var rest = args.toList
while rest.nonEmpty do
  rest match
    case "--save" :: f :: tail      => save = Some(f); rest = tail
    case "--scenarios" :: s :: tail => chosen = Some(s.split(",").toSeq); rest = tail
    case e :: tail if !e.startsWith("-") && executable.isEmpty => executable = Some(e); rest = tail
    case other :: _ =>
      System.err.println(s"unexpected $other\nusage: syscalls.sc [--save FILE] [--scenarios a,b] <executable>[@raw]")
      sys.exit(2)
    case Nil => ()
val target = executable.getOrElse {
  System.err.println("usage: syscalls.sc [--save FILE] [--scenarios a,b] <executable>[@raw]"); sys.exit(2)
}
val raw = target.endsWith("@raw")
val path = target.stripSuffix("@raw")
val results = scala.collection.mutable.LinkedHashMap[String, Map[String, Map[String, Double]]]()
for name <- chosen.getOrElse(Scenarios.keys.toSeq) if !(raw && GearsOnly(name)) do
  results(name) = perOperation(path, raw, name)
  val client = results(name)("client").toSeq.sortBy(_._1)
  println(f"$name%-20s " + client.map((k, v) => s"$k ${short(v)}").mkString(", "))

save.foreach { file =>
  val record = Path.of(path + ".json")
  val build = if Files.exists(record) then ujson.read(Files.readString(record)) else ujson.Null
  def obj(m: Map[String, Double]) = ujson.Obj.from(m.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Num(v)))
  val json = ujson.Obj(
    "executable" -> path,
    "benchmark" -> (if raw then "raw" else "gears"),
    "build" -> build,
    "unit" -> "system calls per operation",
    "scenarios" -> ujson.Obj.from(results.map((s, r) => s -> ujson.Obj("client" -> obj(r("client")), "other" -> obj(r("other")))))
  )
  Files.writeString(Path.of(file), ujson.write(json, indent = 2) + "\n")
  println(s"saved $file")
}
