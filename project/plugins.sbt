addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
// A Scala Native nightly, from the snapshot repository. The release, 0.5.12, has artifacts for Scala 3.10.0 too, and
// the build compiles and links with it, but its test binaries abort (signal 6) as they start.
resolvers += Resolver.sonatypeCentralSnapshots
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13-20261119-b703b41-SNAPSHOT")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
