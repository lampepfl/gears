import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanative.build._

ThisBuild / scalaVersion := "3.5.0-RC1-bin-SNAPSHOT"

publish / skip := true

inThisBuild(
  Seq(
    // publish settings
    organization := "ch.epfl.lamp",
    homepage := Some(url("https://lampepfl.github.io/gears")),
    licenses := List(License.Apache2),
    developers := List(
      Developer("natsukagami", "Natsu Kagami", "natsukagami@gmail.com", url("https://github.com/natsukagami"))
    )
  )
)

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        versionScheme := Some("early-semver"),
        organization := "ch.epfl.lamp",
        version := "0.2.0-SNAPSHOT",
        libraryDependencies += "org.scalameta" %%% "munit" % "1.0.0" % Test,
        libraryDependencies += "org.scala-lang" %% "scala2-library-cc-tasty-experimental" % "3.5.0-RC1-bin-SNAPSHOT",
        // scalacOptions ++= Seq("-Ycc-log", "-Yprint-debug"),
        testFrameworks += new TestFramework("munit.Framework")
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
