// What the bench's build scripts (build.sc, build_jvm.sc) share: running commands, and the provenance record each
// writes beside an executable, which compare.sc saves with its results.

import java.nio.file.{Files, Path}
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*

object BenchTools:

  /** What a command printed, with its exit code. */
  final case class Ran(exit: Int, out: String)

  /** Runs `command` in `dir` with `env` added, its output (stdout and stderr together) captured, or written to `log`. */
  def run(command: Seq[String], dir: Path = Path.of("."), env: Map[String, String] = Map.empty,
      log: Option[Path] = None, input: Option[Array[Byte]] = None): Ran =
    val builder = new ProcessBuilder(command.asJava).directory(dir.toFile).redirectErrorStream(true)
    builder.environment().putAll(env.asJava)
    log.foreach(l => builder.redirectOutput(l.toFile))
    val process = builder.start()
    input match
      case Some(bytes) =>
        val in = process.getOutputStream
        try in.write(bytes)
        finally in.close()
      case None => process.getOutputStream.close()
    val out = if log.isEmpty then new String(process.getInputStream.readAllBytes()) else ""
    Ran(process.waitFor(), out)

  /** Runs `command` and returns its output, exiting the script with it if the command fails. */
  def output(command: Seq[String], dir: Path = Path.of(".")): String =
    val ran = run(command, dir)
    if ran.exit != 0 then fail(s"${command.mkString(" ")} failed:\n${ran.out}")
    ran.out

  def fail(message: String): Nothing =
    System.err.println(message)
    sys.exit(1)

  /** ISO 8601 with the offset, to the second, as the records have always had it. */
  def now(): String = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

  /** Writes a build's provenance record to `file`: `fields` and when it was made (`built`). */
  def provenance(file: Path, fields: (String, ujson.Value)*): Unit =
    val record = ujson.Obj.from(fields :+ ("built" -> ujson.Str(now())))
    Files.writeString(file, ujson.write(record, indent = 2) + "\n")

  /** The repository holding the script at `scriptPath`. */
  def repository(scriptPath: String): Path =
    Path.of(output(Seq("git", "-C", Path.of(scriptPath).toAbsolutePath.getParent.toString, "rev-parse",
      "--show-toplevel")).trim)
