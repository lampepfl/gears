import org.scalajs.jsenv.nodejs.*
import org.scalajs.linker.interface.ESVersion
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

scalaVersion := "3.9.0"

// The Scala Native nightly selected in project/plugins.sbt brings its libraries from the snapshot repository.
ThisBuild / resolvers ++= (if (sys.env.get("SCALA_NATIVE_NIGHTLY").contains("1")) Seq(Resolver.sonatypeCentralSnapshots)
                           else Nil)

LocalRootProject / publish / skip := true

val MUnitFramework = new TestFramework("munit.Framework")

// Common publishing settings for all platforms.
organization := "ch.epfl.lamp"
homepage := Some(uri("https://lampepfl.github.io/gears"))
licenses := List(License.Apache2)
developers := List(
  Developer("natsukagami", "Natsu Kagami", "nki@fastmail.com", uri("https://github.com/natsukagami"))
)

lazy val root =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        publish / skip := false,
        versionScheme := Some("early-semver"),
        libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
        testFrameworks += MUnitFramework
      )
    )
    .jvmSettings(
      Seq(
        scalacOptions += "-release:21"
      )
    )
    .nativeSettings(
      Seq(
        nativeConfig ~= { c =>
          c.withMultithreading(true)
        }
      )
    )
    .jsSettings(
      Seq(
        // Emit ES modules with the Wasm backend
        scalaJSLinkerConfig := {
          scalaJSLinkerConfig.value
            .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
            .withWasmFeatures(_.withUseJSPI(true)) // enable js.async/js.await
            .withModuleKind(ModuleKind.ESModule) // required by the Wasm backend
        },
        // Node.js 26+ supports Wasm and JSPI, including the nested async stack fix.
        jsEnv := Def.uncached {
          val config = NodeJSEnv
            .Config()
            .withArgs(
              List(
                "--stack-size=204800"
              )
            )
          new NodeJSEnv(config)
        }
      )
    )

// gears' I/O interface lives in the core project: the interface in `gears.async.io` (shared, so every platform has
// it), the runtime and the implementation backends share in `gears.async.io.internal` (jvm-native/, and the Posix
// implementation the Native backends share in native/), and the runner, `ReactorFutures`, per platform. The backends
// incubate in projects of their own below, each in a subpackage of `gears.async.io`, which is what lets them reach
// what the interface keeps `private[io]`.

val munit = "org.scalameta" %% "munit" % "1.3.6"

// Settings of the I/O libraries published beside the core, under its organization and versions: the testkit, so that
// a backend written elsewhere can check itself against the same contracts, and the backends.
def ioLibrary(artifact: String) = Seq(
  name := artifact,
  publish / skip := false,
  versionScheme := Some("early-semver")
)

val mainargs = "com.lihaoyi" %% "mainargs" % "0.7.8"

// The contract suites every backend runs (`ReactorContract` and the capability contracts, `FuturesContract`), and
// `BufferArena`, buffers allocated from an arena each platform provides: a Zone on Scala Native, foreign memory on
// the JVM. Also the gears demos and the higher level the interface is for (`gears.io.higherlevel`, with `HigherLevelChecks`,
// which every backend runs), written against `gears.async.io` alone, in packages outside `gears.async` so that they
// see only what a program sees: each demo takes the `Io` and `Async` it runs
// with, and their command line (`gears.io.demos.DemoCommands`) checks the capabilities each needs when it runs. A
// backend's demo application gives it the backends it offers, and is what starts their runs.
lazy val ioTestkit =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("io-testkit"))
    .settings(
      ioLibrary("gears-io-testkit"),
      libraryDependencies ++= Seq(munit, mainargs),
      testFrameworks += MUnitFramework
    )
    .jvmSettings(scalacOptions += "-release:21")
    .nativeSettings(nativeConfig ~= { _.withMultithreading(true) })
    .dependsOn(root)

// The kqueue backend (macOS, Scala Native). Its native resources override `setjmp` and `longjmp` in every program that
// links it with `_setjmp` and `_longjmp`, which leave the signal mask alone (see setjmp_override.S).
lazy val ioKqueue =
  (project in file("io-backends/kqueue"))
    .enablePlugins(ScalaNativePlugin)
    .settings(
      ioLibrary("gears-io-kqueue"),
      libraryDependencies += munit % Test,
      testFrameworks += MUnitFramework,
      // Its tests run reactors on several threads.
      nativeConfig ~= { _.withMultithreading(true) }
    )
    .dependsOn(root.native, ioTestkit.native % Test)

// The JVM's backends: NIO selectors over the JDK's selectable channels, and threads for what the JDK can only block
// on (`NioBackend`), or each op on the calling thread (`NioOnCaller`).
lazy val ioNio =
  (project in file("io-backends/nio"))
    .settings(
      ioLibrary("gears-io-nio"),
      scalacOptions += "-release:21",
      libraryDependencies += munit % Test,
      testFrameworks += MUnitFramework,
      Test / fork := true,
      // `Carriers.Reactors` gives virtual threads the pool as their scheduler, which the JDK keeps to itself, and pins
      // them to their worker while an op is submitted (see `VirtualCarriers`).
      Test / javaOptions ++= Seq(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
      )
    )
    .dependsOn(root.jvm, ioTestkit.jvm % Test)

// The plain kqueue demos, over the kqueue backend's own types with no gears, as the executable `iokqueuedemos`, which
// also runs the testkit's gears demos over kqueue as `iokqueuedemos gears <command>`: kqueue's demo application.
// ported from https://github.com/bishabosha/scala-native-async-io/commit/6e8320d36c71f75c9daf85065f5c89729f0825c0
lazy val ioKqueueDemos =
  (project in file("io-backends/kqueue/demos"))
    .enablePlugins(ScalaNativePlugin)
    .settings(
      publish / skip := true,
      Compile / mainClass := Some("example.Main"),
      // The gears demos run futures on a pool of worker threads.
      nativeConfig ~= { _.withMultithreading(true) }
    )
    .dependsOn(ioKqueue, ioTestkit.native)

// NIO's demo application: the testkit's gears demos over NIO's backends, `NioOnCaller` (`--backend caller`, the
// default) and `NioBackend` (`reactors`), as `ioNioDemos/run <command>`.
lazy val ioNioDemos =
  (project in file("io-backends/nio/demos"))
    .settings(
      publish / skip := true,
      scalacOptions += "-release:21",
      Compile / mainClass := Some("niodemos.NioDemos"),
      run / fork := true,
      // So that `--backend reactors` can carry the futures on the reactor pool (see `VirtualCarriers`).
      run / javaOptions ++= Seq(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
      )
    )
    .dependsOn(ioNio, ioTestkit.jvm)

// The gears bench (`GearsBench`, see docs/io/bench.md), its scenarios written against the interface alone as the demos
// are, over the backends of each platform's demo application: on Scala Native the executable `iobench` (bench/build.sc),
// whose `iobench gears bench` runs them over kqueue, `iobench bench` the same scenarios against `KqueueLoop` directly,
// and `iobench gears move-bench` kqueue's moves between reactors; on the JVM (bench/build_jvm.sc) over NIO, with the
// same scenarios on the JDK alone (`jdk-bench`). The cats-effect version of the scenarios is `ceBench`, beside it.
lazy val ioBench =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("bench/io-bench"))
    .settings(
      publish / skip := true,
      Compile / mainClass := Some("gearsbench.Main")
    )
    .jvmSettings(
      run / fork := true,
      // So that `--backend reactors` can carry the futures on the reactor pool (see `VirtualCarriers`).
      run / javaOptions ++= Seq(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED"
      )
    )
    .nativeSettings(nativeConfig ~= { _.withMultithreading(true) })
    .jvmConfigure(_.dependsOn(ioNioDemos))
    .nativeConfigure(_.dependsOn(ioKqueueDemos))

// The bench scenarios written with cats-effect and fs2, to compare against; see bench/compare.sc. The same code on
// Scala Native (kqueue, through bench/build.sc) and on the JVM (its selector poller, through bench/build_jvm.sc).
lazy val ceBench =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("bench/ce-bench"))
    .settings(
      publish / skip := true,
      Compile / mainClass := Some("cebench.CeBench"),
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-effect" % "3.7.1",
        "co.fs2" %% "fs2-io" % "3.14.0",
        mainargs
      )
    )
    .nativeSettings(nativeConfig ~= { _.withMultithreading(true) })

// JVM tests that drive the demo applications as subprocesses (macOS only): the plain kqueue demos, the gears demos on
// Scala Native through them and on the JVM through NIO's, and the bench of each platform.
lazy val ioDemosTests =
  (project in file("io-tests"))
    .settings(
      publish / skip := true,
      libraryDependencies += munit % Test,
      testFrameworks += MUnitFramework,
      Test / fork := true,
      Test / javaOptions += {
        val binary = fileConverter.value.toPath((ioKqueueDemos / Compile / nativeLink).value)
        s"-Dkqueue.demo.binary=${binary.toAbsolutePath}"
      },
      Test / javaOptions += {
        val binary = fileConverter.value.toPath((ioBench.native / Compile / nativeLink).value)
        s"-Dgears.bench.binary=${binary.toAbsolutePath}"
      },
      // The same gears demos on the JVM, started as `java -cp <this> niodemos.NioDemos`.
      Test / javaOptions += {
        val converter = fileConverter.value
        val classpath = (ioNioDemos / Runtime / fullClasspath).value
          .map(entry => converter.toPath(entry.data).toAbsolutePath.toString)
          .mkString(java.io.File.pathSeparator)
        s"-Dgears.demo.jvm.classpath=$classpath"
      },
      // The bench on the JVM, started as `java -cp <this> gearsbench.Main`.
      Test / javaOptions += {
        val converter = fileConverter.value
        val classpath = (ioBench.jvm / Runtime / fullClasspath).value
          .map(entry => converter.toPath(entry.data).toAbsolutePath.toString)
          .mkString(java.io.File.pathSeparator)
        s"-Dgears.bench.jvm.classpath=$classpath"
      }
    )
