import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scalanative.build._

ThisBuild / scalaVersion := "3.3.3"

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        organization := "ch.epfl.lamp",
        version := "0.2.0-SNAPSHOT",
        testFrameworks += new TestFramework("munit.Framework")
      )
    )
    .jvmSettings(
      Seq(
        javaOptions += "--version 21",
        libraryDependencies += "org.scalameta" %% "munit" % "1.0.0-M11" % Test
      )
    )
    .nativeSettings(
      Seq(
        nativeConfig ~= { c =>
          c.withMultithreading(true)
        },
        libraryDependencies += "org.scalameta" %%% "munit" % "1.0.0-M10+44-b93292d5-SNAPSHOT" % Test
      )
    )
