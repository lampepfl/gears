import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import scalanative.build._

ThisBuild / scalaVersion := "3.5.0-RC1-bin-SNAPSHOT"

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .settings(
      Seq(
        name := "Gears",
        organization := "ch.epfl.lamp",
        version := "0.2.0-SNAPSHOT",
        libraryDependencies ++= Seq(
          "org.scala-lang" %% "scala2-library-cc" % "3.5.0-RC1-bin-SNAPSHOT",
          "org.scalameta" %%% "munit" % "1.0.0-RC1" % Test
        ),
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
