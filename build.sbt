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
    .jsSettings(jsWasmSettings)

// Emit ES modules with the Wasm backend, run on Node.js 26+, which supports Wasm and JSPI, including the nested async
// stack fix.
lazy val jsWasmSettings = Seq(
  scalaJSLinkerConfig := {
    scalaJSLinkerConfig.value
      .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
      .withWasmFeatures(_.withUseJSPI(true)) // enable js.async/js.await
      .withModuleKind(ModuleKind.ESModule) // required by the Wasm backend
  },
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

// gears' I/O interface lives in the core project, in `gears.async.io` (shared, so every platform has it), with what a
// backend shares in `gears.async.io.internal` and the runner, `Io.run`, per platform. Backends live in projects of
// their own, each in a subpackage of `gears.async.io`, which is what lets them reach what the interface keeps
// `private[io]`.

val munit = "org.scalameta" %% "munit" % "1.3.6"

val mainargs = "com.lihaoyi" %% "mainargs" % "0.7.8"

// What every backend is checked against, published beside the core so that a backend written elsewhere can check
// itself against the same contracts: the contract suites (`gears.async.io.testkit`), `BufferArena`, buffers each
// platform allocates, the gears demos (`gears.io.demos`) and `SimpleIO`, with `SimpleIOChecks`, in `gears.io.testkit`.
// The demos and `SimpleIO` live outside `gears.async`, so they see only the public `gears.async.io` interface. Each
// takes the `Io` and `Async` it runs with, and a backend's demo application is what starts their runs. Built for every platform
// the core is, though only the JVM has a backend to run them on yet.
lazy val ioTestkit =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("io-testkit"))
    .settings(
      name := "gears-io-testkit",
      publish / skip := false,
      versionScheme := Some("early-semver"),
      libraryDependencies ++= Seq(munit, mainargs),
      testFrameworks += MUnitFramework
    )
    .jvmSettings(scalacOptions += "-release:21")
    .nativeSettings(nativeConfig ~= { _.withMultithreading(true) })
    .jsSettings(jsWasmSettings)
    .dependsOn(root)

// The reference backend: the semantics of the interface on the JDK's channels, over a plain selector poll, with no
// regard for speed. It is what the contracts are checked against, not a backend to deploy.
lazy val ioReference =
  (project in file("io-reference"))
    .settings(
      publish / skip := true,
      scalacOptions += "-release:21",
      libraryDependencies += munit % Test,
      testFrameworks += MUnitFramework,
      Test / fork := true
    )
    .dependsOn(root.jvm, ioTestkit.jvm % Test)

// The reference backend's demo application: the testkit's gears demos over it, as `ioReferenceDemos/run <command>`.
lazy val ioReferenceDemos =
  (project in file("io-reference/demos"))
    .settings(
      publish / skip := true,
      scalacOptions += "-release:21",
      Compile / mainClass := Some("referencedemos.ReferenceDemos"),
      run / fork := true
    )
    .dependsOn(ioReference, ioTestkit.jvm)

// The backends that run a program on a pool of workers, each with a poller of its platform's (`io-poller`): on the
// JVM the JDK's channels over selectors (`gears.async.io.nio`), and on Scala Native the system's own event queue
// (`gears.async.io.eventpolling`), kqueue on macOS and FreeBSD and epoll on Linux. What the two share is
// `gears.async.io.shareable`: the workers, their timers and inboxes, handles and the ops that wait on them, with
// nothing of a platform's own. The native side's resources override
// `setjmp` and `longjmp` in every program that links it with `_setjmp` and `_longjmp`, which leave the signal mask
// alone (see setjmp_override.S).
lazy val ioPoller =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("io-poller"))
    .settings(
      name := "gears-io-poller",
      publish / skip := false,
      versionScheme := Some("early-semver"),
      scalacOptions += "-release:21",
      libraryDependencies += munit % Test,
      testFrameworks += MUnitFramework
    )
    .jvmSettings(
      Test / fork := true,
      // For `NioPoolBackend`, whose pool carries the virtual threads.
      Test / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
    .nativeSettings(
      // The tests of one poller's own, which name it and so link its system calls: only on a system that has them.
      Test / unmanagedSourceDirectories += {
        val linux = sys.props.getOrElse("os.name", "").toLowerCase.contains("linux")
        (Test / sourceDirectory).value / (if (linux) "scala-epoll" else "scala-kqueue")
      },
      Test / fork := false,
      Test / logBuffered := false,
      Test / logLevel := Level.Info,
      Test / testOptions += Tests.Argument(
        MUnitFramework,
        "--logger=sbt",
        "--log=info",
        "-F"
      ),
      // MUnit 1.3.6's JS/Native reporter omits stack traces; the test events still carry them.
      Test / testListeners += {
        val log = streams.value.log
        new TestReportListener {
          private def printFailure(failure: Throwable): Unit = {
            val trace = new java.io.StringWriter()
            failure.printStackTrace(new java.io.PrintWriter(trace))
            log.error(trace.toString)
          }

          override def startGroup(name: String): Unit = ()

          override def testEvent(event: TestEvent): Unit =
            event.detail.foreach { result =>
              if (result.status() == sbt.testing.Status.Failure || result.status() == sbt.testing.Status.Error)
                if (result.throwable().isDefined) printFailure(result.throwable().get)
            }

          override def endGroup(name: String, failure: Throwable): Unit = printFailure(failure)

          override def endGroup(name: String, result: sbt.protocol.testing.TestResult): Unit = ()
        }
      }
    )
    .dependsOn(root, ioTestkit % Test)

// The backends' demo applications: the testkit's gears demos over them, as `ioPollerDemosJVM/run <command>` and
// `ioPollerDemosNative/run <command>`.
lazy val ioPollerDemos =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("io-poller/demos"))
    .settings(
      publish / skip := true,
      scalacOptions += "-release:21",
      run / fork := true
    )
    .jvmSettings(
      Compile / mainClass := Some("niodemos.NioDemos"),
      // For `--backend nio-pool`.
      run / javaOptions += "--add-opens=java.base/java.lang=ALL-UNNAMED"
    )
    .nativeSettings(Compile / mainClass := Some("eventpollingdemos.EventPollingDemos"))
    .dependsOn(ioPoller, ioTestkit)

// The bench: scenarios over every capability (`io-bench/shared`), run over this branch's backends, the JVM's
// (`ioCapBenchJVM/run`) and the native one (`ioCapBenchNative/nativeLink`), and by `io-bench/compare` over another branch's too.
// A release build for Scala Native needs `SCALA_NATIVE_NIGHTLY=1` (see project/plugins.sbt).
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
      run / javaOptions ++= Seq("-Xms1g", "-Xmx1g", "--add-opens=java.base/java.lang=ALL-UNNAMED")
    )
    .nativeSettings(
      nativeConfig ~= { _.withMultithreading(true).withMode(scala.scalanative.build.Mode.releaseFast) }
    )
    .dependsOn(ioPoller)
