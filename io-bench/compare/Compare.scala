//> using scala 3.7
//> using jvm 21+

import java.io.File
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Runs the bench (`io-bench`) over this branch's backends and over another branch's, and writes the comparison as
  * Markdown: `scala-cli run io-bench/compare -- --help`, from the root of the repository.
  *
  * The other branch is checked out in a worktree, given this branch's bench sources with its own `BenchMain`s
  * (`io-bench/other`), and built there. The JVM's backends run under sbt (`ioCapBenchJVM/run`), each configuration in one
  * JVM that repeats the scenarios, of which the first repeats, the warm-up, are dropped. kqueue's run as release
  * binaries, each configuration several times over. A figure is the median of what is kept.
  *
  * sbt's server is restarted with `SCALA_NATIVE_NIGHTLY=1`, which the release builds need, and shut down at the end.
  */
object Compare {
  final case class Options(
      other: String = "origin/async-io-backends",
      platforms: Seq[String] = Seq("jvm", "native"),
      scenarios: Option[String] = None,
      jvmScale: Double = 0.5,
      jvmRepeats: Int = 5,
      jvmWarmups: Int = 2,
      nativeScale: Double = 1.0,
      nativeRounds: Int = 3,
      workers: Seq[Int] = Seq(1, 4),
      uring: Boolean = false,
      out: String = "io-bench/results/latest.md",
      work: String = "target/io-bench-compare"
  )

  private val usage =
    """Compare [--other <git ref>] [--platforms jvm,native] [--scenarios a,b] [--workers 1,4]
      |        [--jvm-scale 0.5] [--jvm-repeats 5] [--jvm-warmups 2] [--native-scale 1] [--native-rounds 3]
      |        [--uring] [--out io-bench/results/latest.md] [--work target/io-bench-compare]""".stripMargin

  private def parse(args: List[String], options: Options): Options = args match
    case Nil                              => options
    case "--other" :: value :: rest       => parse(rest, options.copy(other = value))
    case "--platforms" :: value :: rest   => parse(rest, options.copy(platforms = value.split(',').toSeq))
    case "--scenarios" :: value :: rest   => parse(rest, options.copy(scenarios = Some(value)))
    case "--workers" :: value :: rest     => parse(rest, options.copy(workers = value.split(',').toSeq.map(_.toInt)))
    case "--jvm-scale" :: value :: rest   => parse(rest, options.copy(jvmScale = value.toDouble))
    case "--jvm-repeats" :: value :: rest => parse(rest, options.copy(jvmRepeats = value.toInt))
    case "--jvm-warmups" :: value :: rest => parse(rest, options.copy(jvmWarmups = value.toInt))
    case "--native-scale" :: value :: rest  => parse(rest, options.copy(nativeScale = value.toDouble))
    case "--native-rounds" :: value :: rest => parse(rest, options.copy(nativeRounds = value.toInt))
    case "--uring" :: rest                => parse(rest, options.copy(uring = true))
    case "--out" :: value :: rest         => parse(rest, options.copy(out = value))
    case "--work" :: value :: rest        => parse(rest, options.copy(work = value))
    case _ =>
      println(usage)
      sys.exit(if args.headOption.contains("--help") then 0 else 2)

  // ---- processes

  private val nightly = Map("SCALA_NATIVE_NIGHTLY" -> "1")

  /** Runs `command` in `dir`, and gives its exit code and its output, standard error with it. */
  private def exec(dir: File, env: Map[String, String], command: String*): (Int, String) = {
    val builder = new ProcessBuilder(command*).directory(dir).redirectErrorStream(true)
    env.foreach((key, value) => builder.environment().put(key, value))
    val process = builder.start()
    // Read on a thread of its own, so that a process that hangs is given up on, with what it had printed.
    val output = new java.util.concurrent.CompletableFuture[String]
    val reader = new Thread(() => { output.complete(new String(process.getInputStream().readAllBytes())); () })
    reader.setDaemon(true)
    reader.start()
    if !process.waitFor(RunMinutes, java.util.concurrent.TimeUnit.MINUTES) then
      process.descendants().forEach(child => { child.destroyForcibly(); () })
      process.destroyForcibly()
      System.err.println(s"gave up on ${command.mkString(" ")} after $RunMinutes minutes")
    (process.waitFor(), output.get())
  }

  /** The longest any one process is waited for. */
  private inline val RunMinutes = 30L

  private def checked(dir: File, env: Map[String, String], command: String*): String = {
    val (code, output) = exec(dir, env, command*)
    if code != 0 then
      System.err.println(output.linesIterator.toSeq.takeRight(40).mkString("\n"))
      throw new RuntimeException(s"${command.mkString(" ")} failed in $dir with $code")
    output
  }

  private def sbt(dir: File, command: String): String = checked(dir, nightly, "sbt", "--server", "-batch", "-no-colors", command)

  private def git(dir: File, args: String*): String = checked(dir, Map.empty, ("git" +: args)*).trim

  // ---- the two sides

  /** One side of the comparison: where it is built, and what it is called. */
  final case class Side(name: String, dir: File, commit: String)

  /** A backend run a certain way: its column's label, and the bench's arguments that choose it. */
  final case class Config(side: Side, label: String, backend: String, workers: Int)

  private def copyTree(from: Path, to: Path): Unit =
    Files.walk(from).iterator().asScala.foreach { source =>
      val target = to.resolve(from.relativize(source).toString)
      if Files.isDirectory(source) then Files.createDirectories(target)
      else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

  /** A worktree of `ref`, with this branch's bench in it and the other branch's `BenchMain`s in place of ours. */
  private def otherSide(root: File, options: Options): Side = {
    val dir = new File(root, options.work + "/other").getAbsoluteFile()
    if dir.exists() then exec(root, Map.empty, "git", "worktree", "remove", "--force", dir.getPath()): Unit
    dir.getParentFile().mkdirs()
    git(root, "worktree", "add", "--detach", dir.getPath(), options.other)
    val bench = root.toPath.resolve("io-bench")
    for part <- Seq("shared", "jvm", "native") do copyTree(bench.resolve(part), dir.toPath.resolve("io-bench").resolve(part))
    // The micro-benchmark of suspension is this branch's backend's own, by name: not the other's to build.
    Files.deleteIfExists(dir.toPath.resolve("io-bench/native/src/main/scala/gears/async/io/bench/SuspendBench.scala")): Unit
    // What this branch's bench shares between the JVM and Scala Native alone is, for a build of those two, shared.
    copyTree(bench.resolve("jvm-native"), dir.toPath.resolve("io-bench").resolve("shared"))
    // The same Scala Native nightly on both sides: the other branch's build is told this one's.
    val nightlyVersion = """0\.5\.\d+-\d+-[0-9a-f]+-SNAPSHOT""".r
    val plugins = "project/plugins.sbt"
    nightlyVersion.findFirstIn(Files.readString(root.toPath.resolve(plugins))).foreach { ours =>
      val theirs = dir.toPath.resolve(plugins)
      Files.writeString(theirs, nightlyVersion.replaceAllIn(Files.readString(theirs), ours))
    }
    val main = "src/main/scala/gears/async/io/bench/BenchMain.scala"
    for platform <- Seq("jvm", "native") do
      Files.copy(
        bench.resolve(s"other/$platform/BenchMain.scala"),
        dir.toPath.resolve(s"io-bench/$platform/$main"),
        StandardCopyOption.REPLACE_EXISTING
      )
    val build = dir.toPath.resolve("build.sbt")
    Files.writeString(build, Files.readString(build) + Files.readString(bench.resolve("other/build.sbt")))
    Side(options.other, dir, git(dir, "rev-parse", "--short", "HEAD"))
  }

  /** The Scala Native a side's bench is linked with, as its build resolves it: checked to be a nightly, since the
    * release is what a build falls back to, saying nothing, when sbt is not given the environment that asks for one.
    */
  private def nativeVersion(side: Side): String = {
    val listed = sbt(side.dir, "show ioCapBenchNative/libraryDependencies")
    val version = """org\.scala-native:nativelib:([^\s,:]+)""".r.findFirstMatchIn(listed).map(_.group(1)).getOrElse("unknown")
    if !version.contains("SNAPSHOT") then
      throw new IllegalStateException(s"${side.dir}: the bench would be linked with Scala Native $version, not a nightly")
    version
  }

  // ---- running

  private val line = """bench:\s+(\S+)\s+\d+ ops\s+[\d.]+ s\s+([\d.]+) us/op""".r

  /** The figures in a run's output, by scenario, in the order they were printed. */
  private def figures(output: String): Seq[(String, Double)] =
    line.findAllMatchIn(output).map(found => found.group(1) -> found.group(2).toDouble).toSeq

  private def median(values: Seq[Double]): Double = {
    val sorted = values.sorted
    if sorted.isEmpty then Double.NaN
    else if sorted.length % 2 == 1 then sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2
  }

  private def benchArgs(config: Config, scale: Double, repeat: Int, options: Options): Seq[String] =
    Seq("--backend", config.backend, "--workers", config.workers.toString, "--scale", scale.toString) ++
      Seq("--repeat", repeat.toString) ++ options.scenarios.toSeq.flatMap(Seq("--scenarios", _))

  /** One JVM that repeats the scenarios: the median of each one's figures after the warm-up. */
  private def runJvm(config: Config, options: Options): Map[String, Double] = {
    val args = benchArgs(config, options.jvmScale, options.jvmRepeats, options).mkString(" ")
    val (_, output) = exec(config.side.dir, nightly, "sbt", "--server", "-batch", "-no-colors", s"ioCapBenchJVM/run $args")
    figures(output).groupMap(_._1)(_._2).view.mapValues(all => median(all.drop(options.jvmWarmups))).toMap
  }

  /** The release binary sbt linked for `side`. */
  private def nativeBinary(side: Side): File = {
    val out = side.dir.toPath.resolve("target/out")
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

  /** The binary run `nativeRounds` times: the median of each scenario's figures. */
  private def runNative(config: Config, binary: File, options: Options): Map[String, Double] = {
    val args = benchArgs(config, options.nativeScale, 1, options)
    val env = Map("SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS" -> "0")
    val all = (1 to options.nativeRounds).flatMap { _ =>
      figures(exec(config.side.dir, env, (binary.getPath() +: args)*)._2)
    }
    all.groupMap(_._1)(_._2).view.mapValues(median).toMap
  }

  // ---- the report

  private def table(configs: Seq[Config], results: Map[Config, Map[String, Double]], order: Seq[String]): String = {
    val header = "| scenario | " + configs.map(_.label).mkString(" | ") + " |"
    val rule = "|---|" + configs.map(_ => "---:|").mkString
    val rows = order.filter(name => results.values.exists(_.contains(name))).map { name =>
      val cells = configs.map(config => results(config).get(name).fold("–")(value => f"$value%.2f"))
      s"| $name | ${cells.mkString(" | ")} |"
    }
    (header +: rule +: rows).mkString("\n")
  }

  private def first(dir: File, command: String*): String =
    exec(dir, Map.empty, command*)._2.linesIterator.nextOption().getOrElse("unknown").trim

  def main(args: Array[String]): Unit = {
    val options = parse(args.toList, Options())
    val root = new File(".").getAbsoluteFile().getParentFile()
    if !new File(root, "io-bench/shared").isDirectory() then
      throw new IllegalStateException("run from the root of the repository")
    val here = Side("this branch", root, git(root, "rev-parse", "--short", "HEAD"))
    val dirty = git(root, "status", "--porcelain", "--untracked-files=no").nonEmpty
    val other = otherSide(root, options)
    val sides = Seq(here, other)
    // The servers may be running without the nightly plugin: each side's is started afresh, and stopped at the end.
    sides.foreach(side => exec(side.dir, Map.empty, "sbt", "-batch", "shutdown"))
    val report = new StringBuilder
    val order = mutable.LinkedHashSet[String]()
    try {
      report ++= s"# io-bench: this branch against `${options.other}`\n\n"
      report ++= s"- this branch: `${here.commit}`${if dirty then ", with uncommitted changes" else ""}\n"
      report ++= s"- `${options.other}`: `${other.commit}`\n"
      report ++= s"- machine: ${first(root, "sysctl", "-n", "machdep.cpu.brand_string")}, ${first(root, "uname", "-sr")}\n"
      report ++= s"- date: ${java.time.LocalDate.now()}\n\n"
      report ++= "Microseconds per op, lower is better. A dash is a scenario the backend has no capability for.\n"

      if options.platforms.contains("jvm") then {
        println("jvm: building")
        sides.foreach(side => sbt(side.dir, "ioCapBenchJVM/compile"))
        val configs =
          Seq(Config(here, "this: threads", "threads", 4), Config(other, "other: caller", "caller", 4)) ++
            options.workers.flatMap { workers =>
              Seq(
                Config(here, s"this: pool, $workers", "pool", workers),
                Config(other, s"other: reactors, $workers", "reactors", workers)
              )
            }
        val results = configs.map { config =>
          println(s"jvm: ${config.label}")
          val figures = runJvm(config, options)
          config -> figures
        }.toMap
        val names = figures(exec(here.dir, nightly, "sbt", "--server", "-batch", "-no-colors", "ioCapBenchJVM/run --scale 0.001")._2).map(_._1)
        names.foreach(order += _)
        report ++= s"\n## JVM\n\n${first(root, "java", "-version")}. `threads` is `NioBackend` on four selector workers and "
        report ++= "`pool` is `NioPoolBackend`, with the number of workers; `caller` is the other branch's `NioOnCaller` "
        report ++= "and `reactors` its `NioBackend`. Each configuration is one JVM that runs the scenarios "
        report ++= s"${options.jvmRepeats} times at scale ${options.jvmScale}: the median of the last "
        report ++= s"${options.jvmRepeats - options.jvmWarmups}.\n\n"
        report ++= table(configs, results, names) + "\n"
      }

      if options.platforms.contains("native") then {
        println("native: building")
        val versions = sides.map(nativeVersion).distinct
        sides.foreach(side => sbt(side.dir, "ioCapBenchNative/nativeLink"))
        val binaries = sides.map(side => side -> nativeBinary(side)).toMap
        val configs = options.workers.flatMap { workers =>
          Seq(Config(here, s"this: kqueue, $workers", "kqueue", workers), Config(other, s"other: kqueue, $workers", "kqueue", workers)) ++
            (if options.uring then Seq(Config(here, s"this: uring, $workers", "uring", workers)) else Nil)
        }
        val results = configs.map { config =>
          println(s"native: ${config.label}")
          config -> runNative(config, binaries(config.side), options)
        }.toMap
        val names = figures(exec(here.dir, Map.empty, binaries(here).getPath(), "--scale", "0.001")._2).map(_._1)
        report ++= s"\n## Scala Native, kqueue\n\nRelease builds on Scala Native ${versions.mkString(" and ")}, as each build "
        report ++= "resolved it, with the number of workers. "
        report ++= s"Each configuration is run ${options.nativeRounds} times at scale ${options.nativeScale}: the median.\n\n"
        report ++= table(configs, results, names) + "\n"
      }
    } finally sides.foreach(side => exec(side.dir, Map.empty, "sbt", "-batch", "shutdown"))
    val out = new File(root, options.out)
    out.getParentFile().mkdirs()
    Files.writeString(out.toPath, report.toString)
    println(s"wrote ${options.out}")
  }
}
