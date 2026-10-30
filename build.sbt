import org.scalajs.jsenv.nodejs.*
import org.scalajs.linker.interface.ESVersion
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

scalaVersion := "3.9.0"

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
// platform allocates, the gears demos (`gears.io.demos`) and the higher level the interface is for
// (`gears.io.higherlevel`, with `HigherLevelChecks`). The demos and the higher level are written against
// `gears.async.io` alone, in packages outside `gears.async`, so that they see only what a program sees: each takes the
// `Io` and `Async` it runs with, and a backend's demo application is what starts their runs. Built for every platform
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
