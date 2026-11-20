package gears.async.io.node.host

import scala.scalajs.js

/** Node's failures, JavaScript errors, as the interface's: `IOException`s. */
private[node] object NodeErr {

  /** The failure an error of Node's is, with its message, which names the system's error and the call. */
  def io(error: Any): java.io.IOException = error match {
    case failure: java.io.IOException                    => failure
    case js.JavaScriptException(inner)                   => io(inner)
    case other if other == null || js.isUndefined(other) =>
      new java.io.IOException("Node reported a failure, and not what it was")
    case other =>
      val message = other.asInstanceOf[js.Dynamic].message
      new java.io.IOException(if js.typeOf(message) == "string" then message.asInstanceOf[String]
      else String.valueOf(other))
  }

  /** Whether a callback of Node's was given an error. */
  def failed(error: js.Dynamic): Boolean = error != null && !js.isUndefined(error)

  /** Runs a call of Node's that may throw a JavaScript error as one that throws an `IOException`. */
  inline def guard[T](inline body: T): T =
    try body
    catch case js.JavaScriptException(error) => throw io(error)
}
