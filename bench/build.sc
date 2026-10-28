#!/usr/bin/env -S scala shebang
//> using scala 3.9.0
//> using dep com.lihaoyi::ujson:4.4.1
//> using file BenchTools.scala

/** Builds the kqueue bench executable, `iobench` (bench/io-bench), to benchmark: a commit linked in release-fast mode against the Scala Native nightly
  * (see project/plugins.sbt), in a temporary worktree with its own sbt, so the working tree and its sbt server are left
  * alone.
  *
  * {{{
  * bench/build.sc <commit> <output>
  * }}}
  *
  * Then compare executables with bench/compare.sc. <commit> may be WORKTREE, for HEAD with the working tree's changes,
  * untracked files included (ignored ones are not). Where the executable came from is written beside it, to
  * <output>.json, which compare.sc --save includes in its results.
  *
  * It links with conditional yieldpoints (SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0): on macOS, the trap-based ones that
  * release builds use by default can deadlock a collection in multithreaded programs (see "Conditional and trap-based yieldpoints"
  * in docs/io/scala-native.md), and they were slower here anyway. YIELDPOINTS=trap links with trap-based ones instead.
  *
  * PROJECT=ceBench builds the cats-effect and fs2 version of the scenarios (bench/ce-bench) instead of ioBench. A commit
  * from before the bench had a project of its own builds `iokqueuedemos` (ioKqueueDemos), which ran the bench as
  * `iokqueuedemos gears bench`, and its plain version as `iokqueuedemos bench`, as `iobench` does now, so compare.sc
  * compares them alike.
  */

import BenchTools.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

if args.length != 2 then fail("usage: build.sc <commit> <output>")
val yieldpoints = sys.env.getOrElse("YIELDPOINTS", "conditional")
val trapBased = yieldpoints match
  case "conditional" => 0
  case "trap"        => 1
  case _             => fail("YIELDPOINTS must be conditional or trap")
val worktreeChanges = args(0) == "WORKTREE"
val commit = if worktreeChanges then "HEAD" else args(0)
val repo = repository(scriptPath)
// A commit from before ioBench builds its bench in ioKqueueDemos, unless PROJECT says otherwise.
val buildDefinition =
  if worktreeChanges then Files.readString(repo.resolve("build.sbt"))
  else output(Seq("git", "show", s"$commit:build.sbt"), repo)
val project = sys.env.getOrElse(
  "PROJECT",
  if buildDefinition.contains("lazy val ioBench") then "ioBench" else "ioKqueueDemos"
)
val module = project.toLowerCase
// ioBench and ceBench are cross projects, so their Scala Native sides are ioBenchNative and ceBenchNative, still linked
// into target/out/.../iobench and .../cebench. A `set` expression names a project by its val in the build, which a
// cross project's side has none of, so by id.
val (sbtProject, setProject) =
  if Set("ioBench", "ceBench")(project) then (s"${project}Native", s"LocalProject(\"${project}Native\")")
  else (project, project)
val outputFile = Path.of(args(1)).toAbsolutePath

def hex(bytes: Array[Byte]): String = bytes.map(b => f"$b%02x").mkString
def sha1(bytes: Array[Byte]): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

// A nested repository, such as another worktree, is listed as its directory, which is not brought over.
val untracked =
  output(Seq("git", "ls-files", "--others", "--exclude-standard", "-z"), repo).split('\u0000').toSeq
    .filter(f => f.nonEmpty && Files.isRegularFile(repo.resolve(f)))
val diff = if worktreeChanges then run(Seq("git", "diff", "HEAD", "--binary"), repo).out.getBytes else Array.emptyByteArray
// Identifies the working-tree changes built: the diff and the untracked files, with their contents.
val changesHash =
  if !worktreeChanges then None
  else
    val listing = untracked.map(f => s"${sha1(Files.readAllBytes(repo.resolve(f)))}  $f\n").mkString
    Some(sha1(diff ++ listing.getBytes).take(12))
val commitSha = output(Seq("git", "rev-parse", s"$commit^{commit}"), repo).trim
val commitSubject = output(Seq("git", "log", "-1", "--format=%s", commitSha), repo).trim

val worktree = Files.createTempDirectory("kqueue-bench-build")
Files.delete(worktree) // `git worktree add` makes it
// Removed however the script ends, `fail` included, which exits without running `finally` blocks.
Runtime.getRuntime.addShutdownHook(new Thread(() => {
  if Files.exists(worktree) then
    run(Seq("sbt", "--client", "shutdown"), worktree)
    run(Seq("git", "worktree", "remove", "--force", worktree.toString), repo)
  ()
}))
locally {
  output(Seq("git", "worktree", "add", "--quiet", "--detach", worktree.toString, commit), repo)
  if worktreeChanges then
    // Bring over the working tree: changes to tracked files, then untracked files.
    val applied = run(Seq("git", "apply", "--allow-empty"), worktree, input = Some(diff))
    if applied.exit != 0 then fail(s"git apply failed:\n${applied.out}")
    for file <- untracked do
      val target = worktree.resolve(file)
      Files.createDirectories(target.getParent)
      Files.copy(repo.resolve(file), target, StandardCopyOption.REPLACE_EXISTING)
  // Commits from before the nightly switch existed get it from the working tree.
  Files.copy(repo.resolve("project/plugins.sbt"), worktree.resolve("project/plugins.sbt"),
    StandardCopyOption.REPLACE_EXISTING)
  val buildSbt = worktree.resolve("build.sbt")
  if !Files.readString(buildSbt).contains("SCALA_NATIVE_NIGHTLY") then
    Files.writeString(buildSbt, Files.readString(buildSbt) + "\nThisBuild / resolvers += Resolver.sonatypeCentralSnapshots\n")
  System.err.println(s"building ${output(Seq("git", "log", "--oneline", "-1"), worktree).trim} in release-fast " +
    s"against the Scala Native nightly, $yieldpoints yieldpoints...")
  val log = worktree.resolve("build.log")
  val built = run(
    Seq("sbt", "-batch",
      s"set $setProject / nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast)); " +
        s"show $sbtProject/libraryDependencies; $sbtProject/nativeLink"),
    worktree,
    Map("SCALA_NATIVE_NIGHTLY" -> "1", "SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS" -> trapBased.toString),
    log = Some(log)
  )
  val logLines = Files.readAllLines(log).asScala.toSeq
  if built.exit != 0 then fail(logLines.takeRight(40).mkString("\n"))
  val scalaNative = """nativelib:(0\.5\.13-\S*)""".r.findFirstMatchIn(logLines.mkString("\n")).map(_.group(1))
    .getOrElse(fail("the build did not use the Scala Native nightly"))
  val binary = worktree.resolve(s"target/out/native0.5/scala-3.9.0/$module/$module")
  if !run(Seq(binary.toString, "target-info")).out.contains("release: true") then
    fail("the executable is not a release build")
  // Trap-based yieldpoints poll a thread-local trap cell; conditional ones have none.
  val linked = if output(Seq("nm", binary.toString)).contains("scalanative_GC_yieldpoint_trap") then "trap" else "conditional"
  if linked != yieldpoints then fail(s"the executable has $linked yieldpoints, not $yieldpoints")
  Files.createDirectories(outputFile.getParent)
  Files.copy(binary, outputFile, StandardCopyOption.REPLACE_EXISTING)
  provenance(
    Path.of(s"$outputFile.json"),
    "project" -> project,
    "commit" -> commitSha,
    "subject" -> commitSubject,
    "worktree_changes" -> worktreeChanges,
    "worktree_changes_hash" -> changesHash.fold(ujson.Null)(ujson.Str(_)),
    "mode" -> "release-fast",
    "scala_native" -> scalaNative,
    "yieldpoints" -> yieldpoints
  )
  System.err.println(outputFile)
}
