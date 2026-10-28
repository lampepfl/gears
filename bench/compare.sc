#!/usr/bin/env -S scala shebang
//> using scala 3.9.0
//> using dep com.lihaoyi::ujson:4.4.1

/** Compares `iobench gears bench` across builds of the bench executable (`iokqueuedemos` before the bench had a
  * project of its own, which runs the same commands).
  *
  * Build the executables to compare with bench/build.sc, which links a commit in release-fast mode against the Scala
  * Native nightly (0.5.12's GC can crash multithreaded release builds), then compare them:
  *
  * {{{
  * bench/build.sc HEAD~ /tmp/iobench.before
  * bench/build.sc HEAD /tmp/iobench.after
  * bench/compare.sc before=/tmp/iobench.before after=/tmp/iobench.after raw=/tmp/iobench.after@raw
  * }}}
  *
  * A build runs the gears benchmark, `gears bench`; suffix its path with `@raw` to run the same scenarios written
  * against `KqueueLoop` directly (`bench`), as a reference: `raw=<path>@raw`; or with `@backend=<name>` to run
  * `gears bench --backend <name>`, for a build with another backend, or whose backends had other names.
  *
  * Each build runs `--runs` times, alternating between builds so drift in machine load affects them alike. The table
  * shows each scenario's median microseconds per operation, and each later build's change against the first. Arguments
  * after `--` go to `bench`, for example `-- --scenarios pingpong,pipelined --scale 0.5`. A run past `--timeout`
  * seconds has its threads dumped to bench/results/hangs (a JVM's with jcmd, virtual threads included, any other's with
  * macOS's `sample`) before it is killed, and counts as a failed run, which `--retries` reruns. A run that still fails
  * keeps the scenarios it finished before failing, and the comparison goes on: the table and the saved file are
  * written whatever fails, with the failures listed (`failed_runs`, and `incomplete` for those not recovered by a
  * retry), and the script then exits with 1.
  *
  * `--save` writes the results as JSON to bench/results/<date>-<labels>.json (ignored by git), and `--save-to FILE`
  * to FILE: every run's numbers and the medians, each build's provenance from build.sc (the <executable>.json beside
  * it), the arguments, the date, and the load average.
  *
  * Run it as an executable, or with `scala bench/compare.sc -- <arguments>`: it is a Scala CLI script.
  */

import java.nio.file.{Files, Path}
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

val Usage =
  """usage: compare.sc label=path[@raw|@backend=NAME]... [--runs N] [--retries N] [--timeout SECONDS]
    |                     [--save | --save-to FILE] [-- bench arguments]""".stripMargin

/** Where this script lives, which is where results and hang dumps go. */
val benchDir: Path = Path.of(scriptPath).toAbsolutePath.getParent
val results = benchDir.resolve("results")
val hangs = results.resolve("hangs")

/** ISO 8601 with the offset, to the second, as the saved files have always had it. */
def iso(time: OffsetDateTime): String = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

/** The 1, 5 and 15 minute load averages. */
def loadAverage(): Seq[Double] =
  val proc = Path.of("/proc/loadavg")
  val text =
    if Files.exists(proc) then Files.readString(proc)
    else new String(new ProcessBuilder("sysctl", "-n", "vm.loadavg").start().getInputStream.readAllBytes())
  text.replace("{", "").replace("}", "").trim.split("\\s+").take(3).toSeq.map(_.toDouble)

def median(values: Seq[Double]): Double =
  val sorted = values.sorted
  val n = sorted.size
  if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2

/** Runs `command`, returning its exit code, or None when it does not exit within `timeout` seconds. */
def run(command: Seq[String], out: Path, err: Path, timeout: Long): (Process, Option[Int]) =
  val process = new ProcessBuilder(command.asJava).redirectOutput(out.toFile).redirectError(err.toFile).start()
  if process.waitFor(timeout, TimeUnit.SECONDS) then (process, Some(process.exitValue())) else (process, None)

/** Dumps the threads of a run that is past its time, returning where. */
def dumpHung(pid: Long, label: String): Path =
  Files.createDirectories(hangs)
  val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  val target = hangs.resolve(s"$label-$stamp.txt")
  def quietly(command: String*): Int =
    new ProcessBuilder(command.asJava).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start().waitFor()
  if quietly("jcmd", pid.toString, "VM.version") == 0 then
    quietly("jcmd", pid.toString, "Thread.dump_to_file", "-overwrite", target.toString)
  else quietly("sample", pid.toString, "3", "-file", target.toString)
  target

/** The scenarios a run printed, with their microseconds per operation: all of them, or those finished before it failed.
  */
def parse(out: Path): Map[String, Double] =
  // bench: <scenario> <ops> <seconds> <ops/s> <us/op>
  Files.readAllLines(out).asScala.map(_.trim.split("\\s+")).collect {
    case Array("bench:", scenario, _, _, _, us) if scenario != "scenario" => scenario -> us.toDouble
  }.toMap

/** One run of a build: its microseconds per operation by scenario. A run that fails after its retries is recorded in
  * `incomplete`, and gives the scenarios it finished.
  */
def runOnce(path: String, benchArgs: Seq[String], retries: Int, crashes: mutable.Buffer[String],
    incomplete: mutable.Buffer[String], timeout: Long, label: String): Map[String, Double] =
  val command =
    if path.endsWith("@raw") then Seq(path.stripSuffix("@raw"), "bench")
    else if path.contains("@backend=") then
      val at = path.indexOf("@backend=")
      Seq(path.substring(0, at), "gears", "bench", "--backend", path.substring(at + "@backend=".length))
    else Seq(path, "gears", "bench")
  val out = Files.createTempFile("compare", ".out")
  val err = Files.createTempFile("compare", ".err")
  try
    var attempt = 0
    var done = false
    var found = Map.empty[String, Double]
    while !done do
      val (process, exit) = run(command ++ benchArgs, out, err, timeout)
      val failure = exit match
        case Some(0)    => None
        case Some(code) => Some(s"exited with $code")
        case None       =>
          val dump = dumpHung(process.pid, label)
          process.descendants().forEach(_.destroyForcibly(): Unit)
          process.destroyForcibly().waitFor()
          Some(s"hung past $timeout s; threads in $dump")
      failure match
        case None =>
          done = true
          found = parse(out)
          if found.isEmpty then
            System.err.println(s"$path printed no bench results:\n${Files.readString(out)}")
            incomplete += s"$label: ${command.mkString(" ")} printed no bench results"
        case Some(failure) =>
          val stderr = Files.readString(err).trim
          crashes += s"${command.mkString(" ")} $failure: ${stderr.takeRight(300)}"
          if attempt == retries then
            done = true
            found = parse(out)
            System.err.println(
              s"${command.mkString(" ")} $failure, ${retries + 1} times; keeping the ${found.size} scenario(s) it " +
                s"finished; last output:\n${Files.readString(out)}$stderr")
            incomplete += s"$label: ${command.mkString(" ")} $failure; kept ${found.keys.toSeq.sorted.mkString(", ")}"
          else
            System.err.println(s"  $failure; retrying")
            attempt += 1
    found
  finally
    Files.deleteIfExists(out)
    Files.deleteIfExists(err)

/** The build.sc record beside an executable, if there is one. */
def provenance(path: String): ujson.Value =
  val record = Path.of(path.takeWhile(_ != '@') + ".json")
  if Files.exists(record) then ujson.read(Files.readString(record)) else ujson.Null

def benchmark(path: String): String =
  if path.endsWith("@raw") then "raw"
  else if path.contains("@backend=") then path.substring(path.indexOf("@backend=") + "@backend=".length)
  else "gears"

// The arguments: the builds and options, then `--` and the bench's.
val (ours, benchArgs) = args.indexOf("--") match
  case -1 => (args.toSeq, Seq.empty[String])
  case at => (args.take(at).toSeq, args.drop(at + 1).toSeq)
var runs = 3
var retries = 0
var timeout = 600L
var saveDefault = false
var saveTo: Option[String] = None
val builds = mutable.ArrayBuffer[(String, String)]()
var rest = ours.toList
while rest.nonEmpty do
  rest match
    case "--runs" :: n :: tail    => runs = n.toInt; rest = tail
    case "--retries" :: n :: tail => retries = n.toInt; rest = tail
    case "--timeout" :: n :: tail => timeout = n.toLong; rest = tail
    case "--save" :: tail         => saveDefault = true; rest = tail
    case "--save-to" :: f :: tail => saveTo = Some(f); rest = tail
    case ("-h" | "--help") :: _   => println(Usage); sys.exit(0)
    case option :: _ if option.startsWith("-") =>
      System.err.println(s"unknown option $option\n$Usage"); sys.exit(2)
    case build :: tail =>
      builds += (build.split("=", 2) match
        case Array(label, path) => (label, path)
        case _                  => (build, build))
      rest = tail
    case Nil => ()
if builds.isEmpty then
  System.err.println(Usage)
  sys.exit(2)

val samples = builds.map((label, _) => label -> mutable.LinkedHashMap[String, mutable.ArrayBuffer[Double]]()).toMap
val crashes = mutable.ArrayBuffer[String]()
val incomplete = mutable.ArrayBuffer[String]()
val started = OffsetDateTime.now()
val loadBefore = loadAverage()
for run <- 1 to runs; (label, path) <- builds do
  System.err.println(s"run $run/$runs: $label")
  for (scenario, us) <- runOnce(path, benchArgs, retries, crashes, incomplete, timeout, label) do
    samples(label).getOrElseUpdate(scenario, mutable.ArrayBuffer()) += us

val scenarios = builds.flatMap((label, _) => samples(label).keys).distinct
val base = builds.head._1
val header = f"${"us/op (median)"}%-18s" + builds.map((label, _) => f"$label%22s").mkString
println(header)
println("-" * header.length)
for scenario <- scenarios do
  val baseMedian = samples(base).get(scenario).map(s => median(s.toSeq))
  val row = builds.map { (label, _) =>
    samples(label).get(scenario) match
      case None => f"${"-"}%22s" // e.g. the raw benchmark has no `mixed`
      case Some(values) =>
        val m = median(values.toSeq)
        baseMedian match
          case Some(b) if label != base => f"$m%13.2f (${(m - b) / b * 100}%+6.1f%%)"
          case _                        => f"$m%22.2f"
  }
  println(f"$scenario%-18s" + row.mkString)

val short = for (label, _) <- builds; (scenario, values) <- samples(label) if values.size < runs
  yield s"$label $scenario: ${values.size} of $runs"
if short.nonEmpty then
  println(s"\nmedians of fewer runs than asked, where a run failed before finishing them:")
  short.foreach(s => println(s"  $s"))
if crashes.nonEmpty then
  println(s"\n${crashes.size} run(s) failed:")
  crashes.foreach(c => println(s"  $c"))
if incomplete.nonEmpty then
  println(s"\n${incomplete.size} run(s) failed after their retries, with what they finished kept:")
  incomplete.foreach(c => println(s"  $c"))

if saveDefault || saveTo.nonEmpty then
  val file = saveTo.map(Path.of(_)).getOrElse(
    results.resolve(s"${started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-${builds.map(_._1).mkString("-")}.json"))
  Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
  val json = ujson.Obj(
    "started" -> iso(started),
    "finished" -> iso(OffsetDateTime.now()),
    "load_average" -> ujson.Obj("before" -> loadBefore, "after" -> loadAverage()),
    "runs" -> runs,
    "bench_args" -> benchArgs,
    "unit" -> "microseconds per operation",
    "builds" -> builds.map { (label, path) =>
      ujson.Obj(
        "label" -> label,
        "executable" -> path,
        "benchmark" -> benchmark(path),
        "build" -> provenance(path),
        "median" -> ujson.Obj.from(samples(label).map((s, v) => s -> ujson.Num(median(v.toSeq)))),
        "samples" -> ujson.Obj.from(samples(label).map((s, v) => s -> ujson.Arr.from(v.map(ujson.Num(_)))))
      )
    },
    "failed_runs" -> crashes,
    "incomplete" -> incomplete
  )
  Files.writeString(file, ujson.write(json, indent = 2) + "\n")
  println(s"\nsaved $file")

if incomplete.nonEmpty then sys.exit(1)
