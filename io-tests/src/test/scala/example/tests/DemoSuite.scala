package example.tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

import munit.FunSuite

/** Drives the native kqueue demo executable as a subprocess with the standard Java process API. */
abstract class DemoSuite extends FunSuite {

  /** Absolute path of the linked demo executable, passed in by the `ioDemosTests` sbt project. */
  lazy val binary: String =
    sys.props.get("kqueue.demo.binary").orElse(sys.env.get("KQUEUE_DEMO_BINARY")) match {
      case Some(path) => Paths.get(path).toAbsolutePath.toString
      case None       => fail("set -Dkqueue.demo.binary=<path to executable>, or run `sbt ioDemosTests/test`")
    }

  /** Absolute path of the linked bench executable, `iobench`, passed in by the `ioDemosTests` sbt project. */
  lazy val benchBinary: String =
    sys.props.get("gears.bench.binary").orElse(sys.env.get("GEARS_BENCH_BINARY")) match {
      case Some(path) => Paths.get(path).toAbsolutePath.toString
      case None       => fail("set -Dgears.bench.binary=<path to executable>, or run `sbt ioDemosTests/test`")
    }

  /** Arguments placed before every demo command; `Seq("gears")` runs the gears mirrors instead. */
  def commandPrefix: Seq[String] = Nil

  /** How a demo is started, before its command: the linked executable, or a JVM running the gears demos. */
  def launcher: Seq[String] = Seq(binary)

  /** Whether these are the gears demos, which mirror only some of the plain ones. */
  def gearsMirrors: Boolean = commandPrefix.nonEmpty

  /** Whether the backend has FIFOs and Unix datagram sockets, which NIO lacks, so its demos have no such commands. */
  def hasFifos: Boolean = true

  def hasUnixDatagrams: Boolean = true

  /** Whether a connected datagram socket can send an empty packet: the plain kqueue demos can, through the system; no
    * gears backend does, since the JDK's cannot (see `IpDatagramOps.send`).
    */
  def sendsEmptyConnectedDatagrams: Boolean = true

  /** Several demo runs per test, including the five-second timer and datagram timeout. */
  override def munitTimeout: Duration = 2.minutes

  /** Registers a test that only runs on macOS, where kqueue is available. */
  def demoTest(name: String)(body: => Any): Unit =
    test(name) {
      assume(sys.props.getOrElse("os.name", "").toLowerCase.contains("mac"), "the kqueue demos require macOS")
      body
    }

  /** Creates a temporary directory under `/tmp`, keeping Unix socket paths below Darwin's 104-byte limit. */
  def withTempDirectory[A](prefix: String)(body: Path => A): A = {
    val directory = Files.createTempDirectory(Paths.get("/tmp"), prefix)
    try body(directory)
    finally
      Files.walk(directory).sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
  }

  /** Runs the demo to completion and returns its combined stdout and stderr, requiring a zero exit code. */
  def run(args: String*): String = runExpecting(success = true, args)

  /** Runs the demo to completion and returns its combined stdout and stderr, requiring a non-zero exit code. */
  def runFailing(args: String*): String = runExpecting(success = false, args)

  /** A long-running demo process whose combined stdout and stderr are captured in a log file. */
  final class Demo(val args: Seq[String], process: Process, log: Path) {
    def isAlive: Boolean = process.isAlive
    def output: String = readLog(log)

    /** Polls the log until `expected` appears, failing if the process exits or `timeout` elapses first. */
    def waitFor(expected: String, timeout: FiniteDuration = 10.seconds): String = {
      val deadline = System.nanoTime() + timeout.toNanos
      var text = output
      while (!text.contains(expected)) {
        assert(process.isAlive, s"`${args.mkString(" ")}` exited before printing `$expected`:\n${tail(text)}")
        assert(System.nanoTime() < deadline, s"`${args.mkString(" ")}` never printed `$expected`:\n${tail(text)}")
        Thread.sleep(10)
        text = output
      }
      text
    }

    private[DemoSuite] def stop(): Unit = {
      if (process.isAlive) process.destroy()
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor(3, TimeUnit.SECONDS)
      }
    }
  }

  /** Starts the demo in the background and terminates it once `body` returns. */
  def withDemo[A](args: String*)(body: Demo => A): A = {
    val log = Files.createTempFile("gears-demo-", ".log")
    try {
      val demo = new Demo(args, start(log, args), log)
      try body(demo)
      finally demo.stop()
    } finally Files.deleteIfExists(log)
  }

  /** Starts a demo server and waits until it prints `ready` before running `body`. */
  def withServer[A](args: Seq[String], ready: String)(body: Demo => A): A =
    withDemo(args*) { server =>
      server.waitFor(ready)
      body(server)
    }

  private def start(log: Path, args: Seq[String]): Process =
    new ProcessBuilder((launcher ++ commandPrefix ++ args)*)
      .redirectErrorStream(true)
      .redirectOutput(log.toFile)
      .start()

  private def readLog(log: Path): String =
    new String(Files.readAllBytes(log), StandardCharsets.UTF_8)

  private def tail(text: String): String = text.takeRight(4096)

  private def runExpecting(success: Boolean, args: Seq[String]): String = {
    val log = Files.createTempFile("gears-demo-", ".log")
    try {
      val process = start(log, args)
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor()
        fail(s"`${args.mkString(" ")}` did not exit within 10 seconds:\n${tail(readLog(log))}")
      }
      val output = readLog(log)
      val expectation = if (success) "succeed" else "fail"
      assert(
        (process.exitValue() == 0) == success,
        s"expected `${args.mkString(" ")}` to $expectation, exit code ${process.exitValue()}:\n${tail(output)}"
      )
      output
    } finally Files.deleteIfExists(log)
  }
}
