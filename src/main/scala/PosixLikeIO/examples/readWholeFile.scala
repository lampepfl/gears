package PosixLikeIO.examples

import gears.async.{Async, given}
import PosixLikeIO.PIOHelper

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import scala.concurrent.ExecutionContext


@main def readWholeFile(): Unit =
  given ExecutionContext = ExecutionContext.global
  Async.blocking:
      PIOHelper.withFile("/home/julian/Desktop/x.txt", StandardOpenOption.READ): f =>
        val b = ByteBuffer.allocate(1024)
        val retCode = f.read(b).result.get
        assert(retCode >= 0)
        val s = StandardCharsets.UTF_8.decode(b.slice(0, retCode)).toString()
        println("Read size with read(): " + retCode.toString())
        println("Data: " + s)


        println("Read with readString():")
        println(Async.await(f.readString(1000)).get)
