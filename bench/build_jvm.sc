#!/usr/bin/env -S scala shebang
//> using scala 3.9.0
//> using dep com.lihaoyi::ujson:4.4.1
//> using file BenchTools.scala

/** Packages the JVM bench (bench/io-bench) from the working tree for bench/compare.sc: its jars are copied into <output>/lib, so
  * later builds do not change what is measured, and executables beside them run the bench scenarios, each taking
  * `gears bench <args>` as compare.sc passes them:
  *
  * {{{
  * <output>/nio-caller         NioOnCaller: no reactor, each op performed on the future's own virtual thread (the default)
  * <output>/nio-reactors       NioBackend: futures carried by the reactor pool
  * <output>/jdk-vthreads       no reactor: blocking JDK channels on virtual threads in structured task scopes
  * <output>/jdk-selector       no reactor: as jdk-vthreads, with non-blocking channels and a selector thread a side,
  *                             the least a design on Selector does (gearsbench.SelectorIo)
  * <output>/jdk-selector-here  no reactor: non-blocking channels, each waiting virtual thread selecting on the
  *                             channel's own selector, with no selector thread (gearsbench.SelectorHereIo)
  * <output>/cats-effect        the same scenarios with cats-effect and fs2 (bench/ce-bench), a selector per worker;
  *                             it runs as `<this> bench <args>`, so compare it as `<label>=<output>/cats-effect@raw`
  * }}}
  *
  * {{{
  * bench/build_jvm.sc <output>
  * bench/compare.sc nio-caller=<output>/nio-caller nio-reactors=<output>/nio-reactors jdk=<output>/jdk-vthreads \
  *   ce=<output>/cats-effect@raw
  * }}}
  *
  * Every run first runs each scenario at a fifth of its count, unreported (`--warmup`), so the JIT has compiled what is
  * measured. They run the `java` on the PATH, or $JAVA. Where they came from is written to <executable>.json beside
  * each, which compare.sc --save includes. The executables are two-line launchers that `exec` java, so that compare.sc
  * runs every build alike, as a program.
  */

import BenchTools.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions

if args.length != 1 then fail("usage: build_jvm.sc <output>")
val outputDir = Path.of(args(0)).toAbsolutePath
val repo = repository(scriptPath)
val home = sys.props("user.home")

/** Copies `project`'s runtime classpath into <output>/<directory>. */
def packaged(project: String, directory: String): Unit =
  output(Seq("sbt", "--client", s"$project/Compile/packageBin"), repo)
  val exported = output(Seq("sbt", "--client", s"export $project/Runtime/fullClasspath"), repo)
  val classpath = """List\((.*)\)""".r.findFirstMatchIn(exported).map(_.group(1))
    .getOrElse(fail(s"no classpath for $project:\n$exported"))
  val target = outputDir.resolve(directory)
  if Files.exists(target) then
    Files.walk(target).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
  Files.createDirectories(target)
  for entry <- classpath.split(",") do
    val path = entry.trim.takeWhile(_ != '>')
      .replace("${OUT}", s"$repo/target/out")
      .replace("${CSR_CACHE}", s"$home/Library/Caches/Coursier/v1")
    val source = Path.of(path)
    Files.copy(source, target.resolve(source.getFileName), StandardCopyOption.REPLACE_EXISTING)

packaged("ioBenchJVM", "lib")
packaged("ceBenchJVM", "lib-ce")

val javaBin = sys.env.get("JAVA").getOrElse {
  sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).map(Path.of(_, "java"))
    .find(Files.isExecutable(_)).map(_.toString).getOrElse(fail("no java on the PATH; set JAVA"))
}
val commit = output(Seq("git", "rev-parse", "HEAD"), repo).trim
val dirty = output(Seq("git", "status", "--porcelain"), repo).trim.nonEmpty
val jdk = run(Seq(javaBin, "-version")).out.linesIterator.next()

/** Writes the launcher `name`, whose shell line `main` runs java with `options`, and its record. */
def launcher(name: String, options: String, main: String, recorded: String): Unit =
  val file = outputDir.resolve(name)
  Files.writeString(file, s"#!/bin/sh\n$main\n")
  Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
  provenance(
    outputDir.resolve(s"$name.json"),
    "commit" -> commit,
    "dirty" -> dirty.toString,
    "jdk" -> jdk,
    "command" -> recorded,
    "java_options" -> options
  )

/** A gears bench launcher: compare.sc runs `<this> gears bench <args>`, of which it drops `gears bench`. */
def gears(name: String, options: String, command: String): Unit =
  launcher(name, options,
    s"""shift 2; exec "$javaBin" $options -cp "$outputDir/lib/*" gearsbench.Main $command --warmup "$$@"""",
    command)

val opens = "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
gears("nio-caller", s"$opens -Dgears.io.nio.backend=caller", "bench")
gears("nio-reactors", s"$opens -Dgears.io.nio.backend=reactors", "bench")
gears("jdk-vthreads", "--enable-preview", "jdk-bench")
gears("jdk-selector", "--enable-preview", "jdk-bench --selector")
gears("jdk-selector-here", "--enable-preview", "jdk-bench --selector-here")
// compare.sc runs `<this> bench <args>` for a build marked @raw.
launcher("cats-effect", "",
  s"""command=$$1; shift; exec "$javaBin" -cp "$outputDir/lib-ce/*" cebench.CeBench "$$command" --warmup "$$@"""",
  "bench")
println(s"built $outputDir: nio-caller nio-reactors jdk-vthreads jdk-selector jdk-selector-here cats-effect")
