import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}
import scalanative.build._

ThisBuild / scalaVersion := "3.3.1"

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("."))
  .settings(Seq(
    name := "Gears",
    organization := "ch.epfl.lamp",
    version := "0.1.0-SNAPSHOT",
    testFrameworks += new TestFramework("munit.Framework")
  ))
  .jvmSettings(Seq(
    javaOptions += "--version 21",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0-M10" % Test
  ))
  .nativeSettings(Seq(
    nativeConfig ~= { c =>
      c.withMultithreadingSupport(true)
       .withGC(GC.boehm) // immix doesn't work yet
    },
      libraryDependencies += "org.scalameta" %%% "munit" % "1.0.0-M10+15-3940023e-SNAPSHOT" % Test
  ))
