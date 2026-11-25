package gears.async.io.bench

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@js.annotation.JSImport("node:fs", js.annotation.JSImport.Namespace)
private object NodeFs extends js.Object {
  def mkdirSync(path: String, options: js.Object): js.Any = js.native
  def rmSync(path: String, options: js.Object): Unit = js.native
  def writeFileSync(path: String, data: Uint8Array): Unit = js.native
}

@js.native
@js.annotation.JSImport("node:child_process", js.annotation.JSImport.Namespace)
private object NodeChildProcess extends js.Object {
  def execFileSync(file: String, args: js.Array[String]): js.Any = js.native
}

/** What the bench asks of the system beside the backend, on Node.js: Node's own calls, the ones that return at once. */
object BenchFiles {
  def makeDirectory(path: String): Unit = { val _ = NodeFs.mkdirSync(path, js.Dynamic.literal(recursive = true)) }

  def remove(path: String): Unit = NodeFs.rmSync(path, js.Dynamic.literal(force = true, recursive = true))

  /** A file of `size` zero bytes. */
  def writeZeros(path: String, size: Int): Unit = NodeFs.writeFileSync(path, new Uint8Array(size))

  def makeFifo(path: String): Unit = { val _ = NodeChildProcess.execFileSync("mkfifo", js.Array(path)) }

  def environment(name: String): String | Null = {
    val value = js.Dynamic.global.process.env.selectDynamic(name)
    if js.isUndefined(value) then null else value.asInstanceOf[String]
  }
}
