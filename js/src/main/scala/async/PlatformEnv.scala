package gears.async

import language.experimental.captureChecking

import scala.scalajs.js

/** The environment the program was started in. */
private[async] object PlatformEnv:
  /** The value of the environment variable `name`, or null if it is not set: read from Node's `process.env`, as
    * Scala.js's `System.getenv` has no environment to read, and null where there is no `process`, as in a browser.
    */
  def variable(name: String): String | Null =
    val process = js.Dynamic.global.process
    if js.isUndefined(process) || js.isUndefined(process.env) then null
    else
      val value = process.env.selectDynamic(name)
      if js.isUndefined(value) || value == null then null else value.toString
