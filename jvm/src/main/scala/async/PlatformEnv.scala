package gears.async

import language.experimental.captureChecking

/** The environment the program was started in. */
private[async] object PlatformEnv:
  /** The value of the environment variable `name`, or null if it is not set. */
  def variable(name: String): String | Null = System.getenv(name)
