import gears.async.default.{DefaultSupport, given_AsyncOperations, given_AsyncSupport, given_Scheduler}
import gears.async.{Async, AsyncOperations, AsyncSupport, Future}

class DefaultSupportCompatibility extends munit.FunSuite:
  test("existing named givens support blocking async operations") {
    val support: AsyncSupport = given_AsyncSupport
    val scheduler: DefaultSupport.Scheduler = given_Scheduler
    val operations: AsyncOperations = given_AsyncOperations
    assert(support eq DefaultSupport)
    assert(scheduler eq DefaultSupport)
    assert(operations eq DefaultSupport)

    val result = Async.fromSync:
      Future { 42 }.await
    assertEquals(result, 42)
  }
