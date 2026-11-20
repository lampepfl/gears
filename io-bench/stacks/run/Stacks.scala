//> using scala 3.7.4
//> using jvm 21+

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** Builds and runs the IP socket scenarios over the gears backends and the other stacks beside them (see
  * `../README.md`), and writes the figures as Markdown. Run from the root of the repository:
  *
  * {{{
  * scala-cli run io-bench/stacks/run -- --out io-bench/results/<name>.md
  * }}}
  *
  * Each stack at each number of threads is one process, which runs the scenarios `--repeat` times over: the first
  * `--warmups` are dropped, for the stacks that compile as they run, and the median of the rest is the figure.
  */
object Stacks {

  final case class Options(
      scenarios: Seq[String] = Seq(
        "tcp-pingpong",
        "tcp-concurrent",
        "tcp-stream",
        "tcp-connect",
        "udp-pingpong",
        "udp-concurrent",
        "udp-connected"
      ),
      stacks: Option[Seq[String]] = None,
      workers: Seq[Int] = Seq(1, 4),
      scale: Double = 1.0,
      repeat: Int = 5,
      warmups: Int = 2,
      out: String = "io-bench/results/stacks-latest.md"
  )

  private val usage =
    """Stacks [--stacks kqueue,nio-pool,nio-threads,gears-node,netty,loom,ox,kotlin,fs2-js,fs2-wasm,nio,fs2,go,node,tokio]
      |       [--scenarios a,b] [--workers 1,4]
      |       [--scale 1] [--repeat 5] [--warmups 2] [--out io-bench/results/stacks-latest.md]""".stripMargin

  private def parse(args: List[String], options: Options): Options = args match
    case Nil                            => options
    case "--stacks" :: value :: rest    => parse(rest, options.copy(stacks = Some(value.split(',').toSeq)))
    case "--scenarios" :: value :: rest => parse(rest, options.copy(scenarios = value.split(',').toSeq))
    case "--workers" :: value :: rest   => parse(rest, options.copy(workers = value.split(',').toSeq.map(_.toInt)))
    case "--scale" :: value :: rest     => parse(rest, options.copy(scale = value.toDouble))
    case "--repeat" :: value :: rest    => parse(rest, options.copy(repeat = value.toInt))
    case "--warmups" :: value :: rest   => parse(rest, options.copy(warmups = value.toInt))
    case "--out" :: value :: rest       => parse(rest, options.copy(out = value))
    case _ =>
      println(usage)
      sys.exit(if args.headOption.contains("--help") then 0 else 2)

  // ---- processes

  /** The longest any one process is waited for. */
  private inline val RunMinutes = 30L

  /** Runs `command` in `dir`, and gives its exit code and its output, standard error with it. */
  private def exec(dir: File, env: Map[String, String], command: String*): (Int, String) =
    execWithin(RunMinutes, dir, env, command*)

  /** Stops sbt's server for `root`. A server that is in a bad way keeps the command waiting, so it is given a minute. */
  private def stopSbt(root: File): Unit = execWithin(1L, root, Map.empty, "sbt", "-batch", "shutdown"): Unit

  private def execWithin(minutes: Long, dir: File, env: Map[String, String], command: String*): (Int, String) = {
    val builder = new ProcessBuilder(command*).directory(dir).redirectErrorStream(true)
    env.foreach((key, value) => builder.environment().put(key, value))
    val process = builder.start()
    // Read on a thread of its own, so that a process that hangs is given up on, with what it had printed.
    val output = new java.util.concurrent.CompletableFuture[String]
    val reader = new Thread(() => { output.complete(new String(process.getInputStream().readAllBytes())); () })
    reader.setDaemon(true)
    reader.start()
    if !process.waitFor(minutes, java.util.concurrent.TimeUnit.MINUTES) then
      process.descendants().forEach(child => { child.destroyForcibly(); () })
      process.destroyForcibly()
      System.err.println(s"gave up on ${command.mkString(" ")} after $minutes minutes")
    (process.waitFor(), output.get())
  }

  private def checked(dir: File, env: Map[String, String], command: String*): String = {
    val (code, output) = exec(dir, env, command*)
    if code != 0 then
      System.err.println(output.linesIterator.toSeq.takeRight(40).mkString("\n"))
      throw new RuntimeException(s"${command.mkString(" ")} failed in $dir with $code")
    output
  }

  private def first(dir: File, command: String*): String =
    exec(dir, Map.empty, command*)._2.linesIterator.nextOption().getOrElse("unknown").trim

  // ---- the stacks

  /** One stack: what its columns are called, how it is built, and the command that runs it on `workers` threads, to
    * which the bench's arguments are added. One with a single thread is run once, whatever `--workers` says.
    */
  final case class Stack(
      name: String,
      label: String,
      threaded: Boolean,
      build: () => Unit,
      command: () => Seq[String],
      dir: File,
      env: Map[String, String] = Map.empty,
      extraArgs: Seq[String] = Nil,
      version: () => String
  )

  /** For sbt, which is run as a JVM of its own (`--server`): as the thin client of a server already running, an IDE's
    * say, it would build with that server's environment, and so with Scala Native's release, saying nothing.
    */
  private val nightly = Map("SCALA_NATIVE_NIGHTLY" -> "1")

  /** The Scala Native the bench is linked with, as the build resolves it: checked to be a nightly, since the release is
    * what a build falls back to, saying nothing, when sbt is not given the environment that asks for one.
    */
  private def nativeVersion(root: File): String = {
    val listed = checked(root, nightly, "sbt", "--server", "-batch", "-no-colors", "show ioCapBenchNative/libraryDependencies")
    val version =
      """org\.scala-native:nativelib:([^\s,:]+)""".r.findFirstMatchIn(listed).map(_.group(1)).getOrElse("unknown")
    if !version.contains("SNAPSHOT") then
      throw new IllegalStateException(s"The bench would be linked with Scala Native $version, not a nightly")
    version
  }

  /** The release binary sbt linked for the bench. */
  private def nativeBinary(root: File): File = {
    val out = root.toPath.resolve("target/out")
    Files
      .walk(out)
      .iterator()
      .asScala
      .map(_.toFile)
      .filter(file => file.isFile() && file.canExecute() && file.getName().toLowerCase.startsWith("iocapbench"))
      .filter(file => !file.getName().contains(".") && !file.getName().endsWith("-test"))
      .maxByOption(_.lastModified())
      .getOrElse(throw new RuntimeException(s"no bench binary under $out"))
  }

  /** The versions a scala-cli project asks for, as its `using dep` lines have them. */
  private def deps(dir: File): String =
    Option(dir.listFiles()).toSeq.flatten
      .filter(_.getName().endsWith(".scala"))
      .flatMap(file => Files.readAllLines(file.toPath).asScala)
      .filter(line => line.startsWith("//> using dep"))
      .map(_.stripPrefix("//> using dep").trim.replace("\"", ""))
      .mkString(", ")

  private def tokioVersion(dir: File): String = {
    val lock = new File(dir, "Cargo.lock")
    if !lock.exists() then "unknown"
    else
      val lines = Files.readAllLines(lock.toPath).asScala.toSeq
      val at = lines.indexOf("name = \"tokio\"")
      if at < 0 then "unknown" else lines(at + 1).stripPrefix("version = ").replace("\"", "")
  }

  private def cargoBinary(dir: File): String = {
    val name = Files
      .readAllLines(new File(dir, "Cargo.toml").toPath)
      .asScala
      .collectFirst { case line if line.startsWith("name") => line.split('=')(1).trim.replace("\"", "") }
      .getOrElse(throw new RuntimeException("no name in Cargo.toml"))
    new File(dir, s"target/release/$name").getPath()
  }

  private def stacks(root: File): Seq[Stack] = {
    val here = new File(root, "io-bench/stacks")
    def scalaCli(name: String, label: String, threaded: Boolean): Stack = {
      val dir = new File(here, name)
      Stack(
        name,
        label,
        threaded,
        () => { checked(root, Map.empty, "scala-cli", "compile", dir.getPath()); () },
        () => Seq("scala-cli", "run", dir.getPath(), "--"),
        root,
        version = () => Some(deps(dir)).filter(_.nonEmpty).getOrElse("`java.nio.channels`, with no library")
      )
    }
    Seq(
      Stack(
        "kqueue",
        "gears kqueue",
        threaded = true,
        () => {
          nativeVersion(root): Unit // checked before anything is linked
          checked(root, nightly, "sbt", "--server", "-batch", "-no-colors", "ioCapBenchNative/nativeLink")
          ()
        },
        () => Seq(nativeBinary(root).getPath()),
        root,
        env = Map("SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS" -> "0"),
        version = () => s"`EventPollingBackend`, Scala Native ${nativeVersion(root)}, release build"
      ),
      Stack(
        "nio-pool",
        "gears NIO pool",
        threaded = true,
        () => { checked(root, nightly, "sbt", "--server", "-batch", "-no-colors", "ioCapBenchJVM/compile"); () },
        // sbt forks the bench with the flag the pool backend needs; its own JVM only waits meanwhile.
        () => Seq("sbt", "--server", "-batch", "-no-colors"),
        root,
        env = nightly,
        extraArgs = Seq("--backend", "pool"),
        version = () => "`NioPoolBackend`, with `--add-opens java.base/java.lang=ALL-UNNAMED`"
      ),
      Stack(
        "nio-threads",
        "gears NIO threads",
        threaded = true,
        () => { checked(root, nightly, "sbt", "--server", "-batch", "-no-colors", "ioCapBenchJVM/compile"); () },
        () => Seq("sbt", "--server", "-batch", "-no-colors"),
        root,
        env = nightly,
        extraArgs = Seq("--backend", "threads"),
        version = () => "`NioBackend`, its futures on the JDK's virtual threads"
      ), {
        val linked = new File(root, "target/out/sjs1")
        def main(): File =
          Files
            .walk(linked.toPath)
            .iterator()
            .asScala
            .map(_.toFile)
            .filter(file => file.getName() == "main.js" && file.getParentFile().getName() == "iocapbench-opt")
            .maxByOption(_.lastModified())
            .getOrElse(throw new RuntimeException(s"no linked bench under $linked"))
        Stack(
          "gears-node",
          "gears on Node.js",
          threaded = false,
          () => { checked(root, nightly, "sbt", "--server", "-batch", "-no-colors", "ioCapBenchJS/fullLinkJS"); () },
          () => Seq("node", main().getPath()),
          root,
          version = () => "`NodeBackend`, Scala.js on WebAssembly with JSPI, Node.js " + first(root, "node", "--version")
        )
      },
      scalaCli("netty", "Netty", threaded = true),
      scalaCli("loom", "virtual threads", threaded = true),
      scalaCli("ox", "Ox", threaded = true), {
        val dir = new File(here, "kotlin")
        Stack(
          "kotlin",
          "Kotlin coroutines",
          threaded = true,
          () => { checked(dir, Map.empty, "gradle", "-q", "installDist"); () },
          () => Seq(new File(dir, "build/install/kotlin-bench/bin/kotlin-bench").getPath()),
          root,
          version = () => "ktor-network on kotlinx.coroutines (see `io-bench/stacks/kotlin`)"
        )
      }, {
        // fs2-io for Scala.js, packaged by scala-cli into the stack's own build directory, to JavaScript and to Wasm.
        val dir = new File(here, "fs2-js")
        def fs2Js(name: String, label: String, wasm: Boolean): Stack = {
          val out = new File(dir, s".scala-build/bench-$name")
          val target = if wasm then out else new File(out, "main.mjs")
          Stack(
            name,
            label,
            threaded = false,
            () => {
              out.mkdirs()
              val emit = if wasm then Seq("--js-emit-wasm") else Nil
              checked(
                root,
                Map.empty,
                (Seq("scala-cli", "--power", "package", dir.getPath(), "--js-mode", "release") ++ emit ++
                  Seq("-o", target.getPath(), "--force"))*
              )
              ()
            },
            () => Seq("node", (if wasm then new File(out, "main.js") else target).getPath()),
            root,
            version = () => deps(dir) + (if wasm then ", built to WebAssembly" else ", built to JavaScript")
          )
        }
        fs2Js("fs2-js", "fs2-io, Scala.js", wasm = false)
      }, {
        val dir = new File(here, "fs2-js")
        val out = new File(dir, ".scala-build/bench-fs2-wasm")
        Stack(
          "fs2-wasm",
          "fs2-io, Scala.js on Wasm",
          threaded = false,
          () => {
            out.mkdirs()
            checked(
              root,
              Map.empty,
              "scala-cli", "--power", "package", dir.getPath(), "--js-mode", "release", "--js-emit-wasm", "-o",
              out.getPath(), "--force"
            )
            ()
          },
          () => Seq("node", new File(out, "main.js").getPath()),
          root,
          version = () => deps(dir) + ", built to WebAssembly"
        )
      },
      scalaCli("nio", "JDK selector", threaded = false),
      scalaCli("fs2", "fs2-io", threaded = true), {
        val dir = new File(here, "go")
        Stack(
          "go",
          "Go",
          threaded = true,
          () => { checked(dir, Map.empty, "go", "build", "-o", "bench", "."); () },
          () => Seq(new File(dir, "bench").getPath()),
          root,
          version = () => first(root, "go", "version")
        )
      }, {
        val dir = new File(here, "node")
        Stack(
          "node",
          "Node.js",
          threaded = false,
          () => (),
          () => Seq("node", new File(dir, "bench.mjs").getPath()),
          root,
          version = () => "Node.js " + first(root, "node", "--version")
        )
      }, {
        val dir = new File(here, "tokio")
        Stack(
          "tokio",
          "Tokio",
          threaded = true,
          () => { checked(dir, Map.empty, "cargo", "build", "--release"); () },
          () => Seq(cargoBinary(dir)),
          root,
          version = () => s"tokio ${tokioVersion(dir)}, ${first(root, "rustc", "--version")}"
        )
      }
    )
  }

  // ---- running

  /** The gears backends on the JVM, which sbt runs, forked with the flags the bench needs. */
  private val viaSbtRun = Set("nio-pool", "nio-threads")

  private val line = """bench:\s+(\S+)\s+\d+ ops\s+[\d.]+ s\s+([\d.]+) us/op""".r

  private def figures(output: String): Seq[(String, Double)] =
    line.findAllMatchIn(output).map(found => found.group(1) -> found.group(2).toDouble).toSeq

  private def median(values: Seq[Double]): Double = {
    val sorted = values.sorted
    if sorted.isEmpty then Double.NaN
    else if sorted.length % 2 == 1 then sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2
  }

  /** One process that repeats the scenarios: the median of each one's figures after the warm-up. */
  private def run(stack: Stack, workers: Int, options: Options): Map[String, Double] = {
    val args = stack.extraArgs ++ Seq(
      "--workers",
      workers.toString,
      "--scenarios",
      options.scenarios.mkString(","),
      "--scale",
      options.scale.toString,
      "--repeat",
      options.repeat.toString
    )
    val command =
      if viaSbtRun(stack.name) then stack.command() :+ s"ioCapBenchJVM/run ${args.mkString(" ")}"
      else stack.command() ++ args
    val (code, output) = exec(stack.dir, stack.env, command*)
    if code != 0 then System.err.println(s"${stack.label} on $workers ended with $code:\n${output.linesIterator.toSeq.takeRight(15).mkString("\n")}")
    figures(output).groupMap(_._1)(_._2).view.mapValues(all => median(all.drop(options.warmups))).toMap
  }

  private def table(columns: Seq[(String, Map[String, Double])], order: Seq[String]): String = {
    val header = "| scenario | " + columns.map(_._1).mkString(" | ") + " |"
    val rule = "|---|" + columns.map(_ => "---:|").mkString
    val rows = order.map { name =>
      val cells = columns.map((_, results) => results.get(name).filterNot(_.isNaN).fold("–")(value => f"$value%.2f"))
      s"| $name | ${cells.mkString(" | ")} |"
    }
    (header +: rule +: rows).mkString("\n")
  }

  def main(args: Array[String]): Unit = {
    val options = parse(args.toList, Options())
    val root = new File(".").getAbsoluteFile().getParentFile()
    if !new File(root, "io-bench/stacks").isDirectory() then
      throw new IllegalStateException("run from the root of the repository")
    val chosen = stacks(root).filter(stack => options.stacks.forall(_.contains(stack.name)))
    val commit = checked(root, Map.empty, "git", "rev-parse", "--short", "HEAD").trim
    val dirty = checked(root, Map.empty, "git", "status", "--porcelain", "--untracked-files=no").trim.nonEmpty
    // sbt's server may be running without the nightly plugin: it is started afresh, and stopped at the end.
    val usesSbt = chosen.exists(stack => stack.name == "kqueue" || stack.name == "gears-node" || viaSbtRun(stack.name))
    if usesSbt then stopSbt(root)
    val report = new StringBuilder
    try {
      chosen.foreach { stack =>
        println(s"building ${stack.label}")
        stack.build()
      }
      val results = (for
        stack <- chosen
        workers <- if stack.threaded then options.workers else Seq(1)
      yield {
        println(s"running ${stack.label} on $workers")
        (stack, workers) -> run(stack, workers, options)
      }).toMap
      report ++= "# io-bench: the IP socket scenarios across stacks\n\n"
      report ++= s"- gears: `$commit`${if dirty then ", with uncommitted changes" else ""}\n"
      report ++= s"- machine: ${first(root, "sysctl", "-n", "machdep.cpu.brand_string")}, ${first(root, "uname", "-sr")}\n"
      report ++= s"- JVM: ${first(root, "java", "-version")}\n"
      report ++= s"- date: ${java.time.LocalDate.now()}\n\n"
      report ++= "Microseconds per op, lower is better. A dash is a scenario the stack was not run for, or has no way to do. "
      report ++= s"Each column is one process that runs the scenarios ${options.repeat} times at scale ${options.scale}: "
      report ++= s"the median of the last ${options.repeat - options.warmups}. What each scenario does is in "
      report ++= "`io-bench/stacks/README.md`.\n"
      val counts = options.workers.filter(count => count == options.workers.head || chosen.exists(_.threaded))
      counts.zipWithIndex.foreach { (workers, index) =>
        // The stacks with one thread are in the first table only.
        val columns = chosen
          .filter(stack => stack.threaded || index == 0)
          .map(stack => stack.label -> results((stack, if stack.threaded then workers else 1)))
        val title =
          if index == 0 then s"$workers thread${if workers == 1 then "" else "s"}, and the stacks that have only one"
          else s"$workers threads"
        report ++= s"\n## $title\n\n${table(columns, options.scenarios)}\n"
      }
      report ++= "\n## The stacks\n\n"
      chosen.foreach(stack => report ++= s"- **${stack.label}**: ${stack.version()}\n")
    } finally if usesSbt then stopSbt(root)
    val out = new File(root, options.out)
    out.getParentFile().mkdirs()
    Files.writeString(out.toPath, report.toString)
    println(s"wrote ${options.out}")
  }
}
