import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanative.build._

ThisBuild / scalaVersion := "3.3.3"

publish / skip := true
// publishSigned / skip := true

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        versionScheme := Some("early-semver"),
        libraryDependencies += "org.scalameta" %%% "munit" % "1.0.0-RC1" % Test,
        testFrameworks += new TestFramework("munit.Framework"),

        // publish settings
        organization := "ch.epfl.lamp",
        homepage := Some(url("https://lampepfl.github.io/gears")),
        licenses := List(License.Apache2)
      )
    )
    .jvmSettings(
      Seq(
        javaOptions += "--version 21"
      )
    )
    .nativeSettings(
      Seq(
        nativeConfig ~= { c =>
          c.withMultithreading(true)
        }
      )
    )
