
// Appended by io-bench's Compare to the build of the branch compared against: the same bench, over its backends.
lazy val ioCapBench =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("io-bench"))
    .settings(
      publish / skip := true,
      scalacOptions += "-release:21",
      Compile / mainClass := Some("gears.async.io.bench.BenchMain")
    )
    .jvmSettings(
      run / fork := true,
      run / javaOptions ++= Seq(
        "-Xms1g",
        "-Xmx1g",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
      )
    )
    .nativeSettings(
      nativeConfig ~= { _.withMultithreading(true).withMode(scala.scalanative.build.Mode.releaseFast) }
    )
    .jvmConfigure(_.dependsOn(ioNio))
    .nativeConfigure(_.dependsOn(ioKqueue))
