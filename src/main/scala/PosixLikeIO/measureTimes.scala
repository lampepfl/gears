import concurrent.Async
import PosixLikeIO.PIOHelper

import java.io.FileWriter
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

@main def measureTimesNew: Unit =

  // mkdir -p /tmp/FIO && sudo mount -t tmpfs -o size=8g tmpfs /tmp/FIO

  given ExecutionContext = ExecutionContext.global

  val dataAlmostJson = StringBuffer() // TEST:String -> PARAMETER:String -> METHOD:String -> TIMES:List[Double]
  dataAlmostJson.append("{")

  def measure[T](methodName: String, timesInner: Int = 100, timesOuter: Int = 100)(action: () => T): String =
    val times = ArrayBuffer[Double]()
    for (_ <- 1 to timesOuter)
      val timeStart = System.currentTimeMillis()
      for (_ <- 1 to timesInner)
        action()
      val timeEnd = System.currentTimeMillis()
      times += ((timeEnd - timeStart + 0.0) / timesInner)

    val ret = StringBuffer()
    ret.append("\"")
    ret.append(methodName)
    ret.append("\": [")
    for (t <- times)
      ret.append(t)
      ret.append(", ")
    ret.append("],\n")
    ret.toString


  val bigStringBuilder = new StringBuilder()
  for (_ <- 1 to 10 * 1024 * 1024) bigStringBuilder.append("abcd")
  val bigString = bigStringBuilder.toString()


  dataAlmostJson.append("\n\t\"Write 4B File\": {\n")
  {
    for (size <- Seq(4, 40*1024*1024))
      println("size " + size.toString)
      dataAlmostJson.append("\n\t\t\"Size " + size.toString + "\": {\n")
      {
        dataAlmostJson.append(measure("PosixLikeIO", timesInner = if size < 100 then 100 else 10): () =>
          Async.blocking:
            PIOHelper.withFile("/tmp/FIO/x.txt", StandardOpenOption.CREATE, StandardOpenOption.WRITE): f =>
              f.writeString(bigString.substring(0, size)).result
        )
        println("done 1")

        dataAlmostJson.append(measure("Java FileWriter", timesInner = if size < 100 then 100 else 10): () =>
          val writer = new FileWriter("/tmp/FIO/y.txt")
          writer.write(bigString.substring(0, size), 0, size)
          writer.close()
        )
        println("done 2")

        dataAlmostJson.append(measure("Java Files.write", timesInner = if size < 100 then 100 else 10): () =>
          Files.write(Paths.get("/tmp/FIO/z.txt"), bigString.substring(0, size).getBytes)
        )
        println("done 3")
      }
      dataAlmostJson.append("},\n")
  }
  dataAlmostJson.append("},\n")

  dataAlmostJson.append("}")
  println(dataAlmostJson.toString)
