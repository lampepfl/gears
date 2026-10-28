package cebench

import scala.scalanative.meta.LinktimeInfo

object Platform {

  /** How the executable was built, which `bench/build.sc` checks. */
  def targetInfo: Seq[String] =
    Seq(s"multithreaded: ${LinktimeInfo.isMultithreadingEnabled}", s"release: ${LinktimeInfo.releaseMode}")
}
