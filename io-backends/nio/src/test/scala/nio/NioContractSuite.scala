package gears.async.io
package nio

import gears.async.io.testkit.FileContract
import gears.async.io.testkit.ResolveContract
import gears.async.io.testkit.SocketContract
import gears.async.io.testkit.UnixStreamContract

class NioContractSuite extends SocketContract with UnixStreamContract with FileContract with ResolveContract {
  val backend: NioBackend.type = NioBackend
}
