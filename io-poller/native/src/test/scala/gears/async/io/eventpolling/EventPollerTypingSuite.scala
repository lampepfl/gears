package gears.async.io
package eventpolling

/** What the native backend's types refuse as a program is compiled. */
class EventPollerTypingSuite extends munit.FunSuite {

  test("a file is no handle a poller listens for, nor one a waiting read is built on") {
    assert(
      compileErrors(
        "def listen(poller: gears.async.io.eventpolling.EventPoller, file: gears.async.io.eventpolling.EventPollingFile): Unit = poller.startListening(file, true)"
      ).nonEmpty
    )
    assert(
      compileErrors("def waits(file: gears.async.io.eventpolling.EventPollingFile) = file.readiness(true)").nonEmpty
    )
  }
}
