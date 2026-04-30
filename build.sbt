import org.scalajs.jsenv.nodejs._
import org.scalajs.linker.interface.ESVersion
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanative.build._

ThisBuild / scalaVersion := "3.3.7"

publish / skip := true

val MUnitFramework = new TestFramework("munit.Framework")

inThisBuild(
  Seq(
    // publish settings
    organization := "ch.epfl.lamp",
    homepage := Some(url("https://lampepfl.github.io/gears")),
    licenses := List(License.Apache2),
    developers := List(
      Developer("natsukagami", "Natsu Kagami", "nki@fastmail.com", url("https://github.com/natsukagami"))
    )
  )
)

lazy val root =
  crossProject(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        versionScheme := Some("early-semver"),
        libraryDependencies += "org.scalameta" %%% "munit" % "1.3.0" % Test,
        testFrameworks += MUnitFramework
      )
    )
    .jvmSettings(
      Seq(
        javaOptions += "--version 21",
        Test / javaOptions ++= Seq(
          "--add-opens=java.base/java.lang=ALL-UNNAMED",
          "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
        )
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
        scalaVersion := "3.8.3",
        // Emit ES modules with the Wasm backend
        scalaJSLinkerConfig := {
          scalaJSLinkerConfig.value
            .withESFeatures(_.withESVersion(ESVersion.ES2017)) // enable async/await
            .withExperimentalUseWebAssembly(true) // use the Wasm backend
            .withModuleKind(ModuleKind.ESModule) // required by the Wasm backend
        },
        // Configure Node.js (at least v23) to support the required Wasm features
        jsEnv := {
          val config = NodeJSEnv
            .Config()
            .withArgs(
              List(
                "--experimental-wasm-exnref", // always required
                "--experimental-wasm-jspi", // required for js.async/js.await
                "--experimental-wasm-imported-strings", // optional (good for performance)
                // "--turboshaft-wasm" // optional, but significantly increases stability
                "--stack-size=204800"
              )
            )
          new NodeJSEnv(config)
        },
        // Skip until Node.js 26+, see lampepfl/gears#165
        Test / testOptions += Tests.Argument(MUnitFramework, "--exclude-tags=stress")
      )
    )
