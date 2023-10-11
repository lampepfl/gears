import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.3.1"

lazy val root =
  crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("."))
  .settings(Seq(
    name := "Gears",
    organization := "ch.epfl.lamp",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test
  ))
  .jvmSettings(Seq(
      javaOptions += "--version 21",
  ))

lazy val rootJVM = root.jvm
lazy val rootNative = root.native
