package gears.async.io
package reference

import gears.async.io.testkit.FileContract
import gears.async.io.testkit.FuturesContract
import gears.async.io.testkit.IpDatagramContract
import gears.async.io.testkit.IpStreamContract
import gears.async.io.testkit.OpsContract
import gears.async.io.testkit.ResolveContract
import gears.async.io.testkit.SocketContract
import gears.async.io.testkit.UnixStreamContract
import gears.io.higherlevel.HigherLevelChecks

/** The contracts of every capability the reference backend has. */
class ReferenceContractSuite
    extends OpsContract
    with IpStreamContract
    with UnixStreamContract
    with SocketContract
    with IpDatagramContract
    with FileContract
    with ResolveContract {
  val backend: ReferenceBackend.type = ReferenceBackend
}

class ReferenceFuturesSuite extends FuturesContract {
  val backend: ReferenceBackend.type = ReferenceBackend
}

class ReferenceHigherLevelSuite extends HigherLevelChecks {
  val backend: ReferenceBackend.type = ReferenceBackend
}
