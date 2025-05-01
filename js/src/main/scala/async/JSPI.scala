package gears.async.js

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** A stub implementation of the [[js.async]]/[[js.await]] functions, which are not yet available natively on Scala 3.
  *
  * See how the stubs are compiled in `build.sbt`.
  */
private[async] object JSPI:
  @inline
  def async[A](computation: => A): js.Promise[A] =
    throw new Error("async stub")

  @inline
  def await[A](p: js.Promise[A]): A =
    throw new Error("await stub")
end JSPI
