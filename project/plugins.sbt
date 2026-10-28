addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
// SCALA_NATIVE_NIGHTLY=1 builds against a Scala Native nightly instead, for benchmarking release builds: on 0.5.12 the
// GC can collect objects referenced only by interior pointers (scala-native#5080), which crashes multithreaded release
// builds of the gears demos. See bench/build.sc.
val scalaNativeNightly = sys.env.get("SCALA_NATIVE_NIGHTLY").contains("1")
resolvers ++= (if (scalaNativeNightly) Seq(Resolver.sonatypeCentralSnapshots) else Nil)
addSbtPlugin(
  "org.scala-native" % "sbt-scala-native" % (if (scalaNativeNightly) "0.5.13-20261015-c3918a9-SNAPSHOT" else "0.5.12")
)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
