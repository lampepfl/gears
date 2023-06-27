package measurements

import PosixLikeIO.PIOHelper
import concurrent.{Async, BufferedChannel, ChannelMultiplexer, Future, SyncChannel}

import java.io.{FileReader, FileWriter}
import java.nio.file.{Files, NoSuchFileException, Paths, StandardOpenOption}
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

case class TimeMeasurementResult(millisecondsPerOperation: Double, standardDeviation: Double)

def measureIterations[T](action: () => T): Int =
  val counter = AtomicInteger(0)

  val t1 = Thread.startVirtualThread: () =>
    try {
      while (true) {
        action()
        val r = counter.getAndIncrement()
      }
    } catch {
      case (_: InterruptedException) => ()
    }

  Thread.sleep(10*1000)
  counter.set(0)
  Thread.sleep(60*1000)
  t1.interrupt()
  counter.get()

@main def measureFutureOverhead(): Unit =
  given ExecutionContext = ExecutionContext.global

  val threadJoins = measureIterations: () =>
    val t = Thread.startVirtualThread: () =>
      var z = 1
    t.join()

  val futureJoins = measureIterations: () =>
    Async.blocking:
      val f = Future:
        var z = 1
      f.result


  println("Thread joins per second: " + (threadJoins / 60))
  println("Future joins per second: " + (futureJoins / 60))
  println("Overhead: " + ((threadJoins+0.0)/(futureJoins+0.0)))

  /*
  Linux:
    Thread joins per second: 292647
    Future joins per second: 86032
    Overhead: 3.401577460379452
  */

@main def measureRaceOverhead(): Unit =
  given ExecutionContext = ExecutionContext.global

  val c1: Double = measureIterations: () =>
    Async.blocking:
      Async.await(Async.race(Future{Thread.sleep(10)}, Future{Thread.sleep(100)}, Future{Thread.sleep(50)}))
      Async.await(Async.race(Future{Thread.sleep(50)}, Future{Thread.sleep(10)}, Future{Thread.sleep(100)}))
      Async.await(Async.race(Future{Thread.sleep(100)}, Future{Thread.sleep(50)}, Future{Thread.sleep(10)}))

  val c2: Double = measureIterations: () =>
    Async.blocking:
      val f11 = Future {Thread.sleep(10)}
      val f12 = Future {Thread.sleep(50)}
      val f13 = Future {Thread.sleep(100)}
      f11.result

      val f21 = Future {Thread.sleep(100)}
      val f22 = Future {Thread.sleep(10)}
      val f23 = Future {Thread.sleep(50)}
      f22.result

      val f31 = Future {Thread.sleep(50)}
      val f32 = Future {Thread.sleep(100)}
      val f33 = Future {Thread.sleep(10)}
      f33.result


  val c1_seconds_wasted_for_waits = c1 * 0.01
  val c1_per_second_adjusted = c1 / 3 / (60 - c1_seconds_wasted_for_waits)
  val c2_seconds_wasted_for_waits = c2 * 0.01
  val c2_per_second_adjusted = c1 / 3 / (60 - c2_seconds_wasted_for_waits)

  println("Raced futures awaited per second: " + c1_per_second_adjusted)
  println("Non-raced futures per second: " + c2_per_second_adjusted)
  println("Overhead: " + (c2_per_second_adjusted / c1_per_second_adjusted))

  /* Linux
  Raced futures awaited per second: 15.590345727332032
  Non-raced futures per second: 15.597976831457009
  Overhead: 1.0004894762604013
  */

@main def measureRaceOverheadVsJava(): Unit =
  given ExecutionContext = ExecutionContext.global

  val c1: Double = measureIterations: () =>
    Async.blocking:
      Async.await(Async.race(Future{Thread.sleep(10)}, Future{Thread.sleep(100)}, Future{Thread.sleep(50)}))
      Async.await(Async.race(Future{Thread.sleep(50)}, Future{Thread.sleep(10)}, Future{Thread.sleep(100)}))
      Async.await(Async.race(Future{Thread.sleep(100)}, Future{Thread.sleep(50)}, Future{Thread.sleep(10)}))

  val c2: Double = measureIterations: () =>
    @volatile var i1 = true
    val f11 = Thread.startVirtualThread(() => { Thread.sleep(10); i1 = false })
    val f12 = Thread.startVirtualThread(() => { Thread.sleep(50); i1 = false })
    val f13 = Thread.startVirtualThread(() => { Thread.sleep(100); i1 = false })
    while (i1) ()

    @volatile var i2 = true
    val f21 = Thread.startVirtualThread(() => { Thread.sleep(100); i2 = false })
    val f22 = Thread.startVirtualThread(() => { Thread.sleep(10); i2 = false })
    val f23 = Thread.startVirtualThread(() => { Thread.sleep(50); i2 = false })
    while (i2) ()

    @volatile var i3 = true
    val f31 = Thread.startVirtualThread(() => { Thread.sleep(50); i3 = false })
    val f32 = Thread.startVirtualThread(() => { Thread.sleep(100); i3 = false })
    val f33 = Thread.startVirtualThread(() => { Thread.sleep(10); i3 = false })
    while (i3) ()

    f11.interrupt()
    f12.interrupt()
    f13.interrupt()
    f21.interrupt()
    f22.interrupt()
    f23.interrupt()
    f31.interrupt()
    f32.interrupt()
    f33.interrupt()

  val c1_seconds_wasted_for_waits = c1 * 0.01
  val c1_per_second_adjusted = c1 / 3 / (60 - c1_seconds_wasted_for_waits)
  val c2_seconds_wasted_for_waits = c2 * 0.01
  val c2_per_second_adjusted = c1 / 3 / (60 - c2_seconds_wasted_for_waits)

  println("Raced futures awaited per second: " + c1_per_second_adjusted)
  println("Java threads awaited per second: " + c2_per_second_adjusted)
  println("Overhead: " + (c2_per_second_adjusted / c1_per_second_adjusted))

  /* Linux
  Raced futures awaited per second: 15.411487529449996
  Java threads awaited per second: 15.671210243700953
  Overhead: 1.0168525402726147
  */

@main def channelsVsJava(): Unit =
  given ExecutionContext = ExecutionContext.global

  /*
    Java "channel" sends per second: 8691652
    SyncChannel sends per second: 319371.0
    BufferedChannel sends per second: 308286.0
    ChannelMultiplexer over SyncChannels sends per second: 155737.0
    ChannelMultiplexer over BufferedChannels sends per second: 151995.0
  */

  val sec = 20

  // java
  @volatile var shared: Long = 0
  @volatile var timeForWriting = true
  val t1 = Thread.startVirtualThread: () =>
    var i: Long = 0
    while (true) {
      while (!timeForWriting) ()
      shared = i
      timeForWriting = false
      i += 1
    }

  val t2 = Thread.startVirtualThread: () =>
    while (true) {
      while (timeForWriting) ()
      var z = shared
      timeForWriting = true
    }

  Thread.sleep(sec * 1000)
  t1.interrupt()
  t2.interrupt()
  val javaSendsPerSecond: Long = shared / sec
  println("Java \"channel\" sends per second: " + javaSendsPerSecond)

  var syncChannelSendsPerSecond = 0.0
  var bufferedChannelSendsPerSecond = 0.0
  var cmOverSyncSendsPerSecond = 0.0
  var cmOverBufferedSendsPerSecond = 0.0

  Async.blocking:
    val c = SyncChannel[Long]()
    val f1 = Future:
      var i: Long = 0
      while (true) {
        try {
          c.send(i)
        } catch {
          case (e: InterruptedException) => {
            syncChannelSendsPerSecond = i / sec
            throw e
          }
        }
        i += 1
      }
    val f2 = Future:
      while (true) {
        c.read()
      }

    Thread.sleep(sec*1000)
    f1.cancel()
    f2.cancel()
    Thread.sleep(500)
    println("SyncChannel sends per second: " + syncChannelSendsPerSecond)

  Async.blocking:
    val c = BufferedChannel[Long](1)
    val f1 = Future:
      var i: Long = 0
      while (true) {
        try {
          c.send(i)
        } catch {
          case (e: InterruptedException) => {
            bufferedChannelSendsPerSecond = i / sec
            throw e
          }
        }
        i += 1
      }
    val f2 = Future:
      while (true) {
        c.read()
      }

    Thread.sleep(sec * 1000)
    f1.cancel()
    f2.cancel()
    Thread.sleep(500)
    println("BufferedChannel sends per second: " + bufferedChannelSendsPerSecond)

  Async.blocking:
    val m = ChannelMultiplexer[Long]()
    val c = SyncChannel[Long]()
    val cr = SyncChannel[Try[Long]]()
    m.addPublisher(c)
    m.addSubscriber(cr)
    Thread.sleep(50)

    val f1 = Future:
      var i: Long = 0
      while (true) {
        try {
          c.send(i)
        } catch {
          case (e: InterruptedException) => {
            cmOverSyncSendsPerSecond = i / sec
            throw e
          }
        }
        i += 1
      }
    val f2 = Future:
      while (true) {
        cr.read()
      }

    Thread.sleep(sec * 1000)
    f1.cancel()
    f2.cancel()
    Thread.sleep(500)
    println("ChannelMultiplexer over SyncChannels sends per second: " + cmOverSyncSendsPerSecond)

  Async.blocking:
    val m = ChannelMultiplexer[Long]()
    val c = BufferedChannel[Long](1)
    val cr = BufferedChannel[Try[Long]](1)
    m.addPublisher(c)
    m.addSubscriber(cr)
    Thread.sleep(50)

    val f1 = Future:
      var i: Long = 0
      while (true) {
        try {
          c.send(i)
        } catch {
          case (e: InterruptedException) => {
            cmOverBufferedSendsPerSecond = i / sec
            throw e
          }
        }
        i += 1
      }
    val f2 = Future:
      while (true) {
        cr.read()
      }

    Thread.sleep(sec * 1000)
    f1.cancel()
    f2.cancel()
    Thread.sleep(500)
    println("ChannelMultiplexer over BufferedChannels sends per second: " + cmOverBufferedSendsPerSecond)

  /*
  SyncChannel sends per second: 389657.0
  BufferedChannel sends per second: 382243.0
  ChannelMultiplexer over SyncChannel sends per second: 204518.0
  ChannelMultiplexer over SyncChannel sends per second: 142378.0
  */

/** Warmup for 10 seconds and benchmark for 60 seconds.
 */
def measureRunTimes[T](action: () => T): TimeMeasurementResult =

  var timesIn25Milliseconds: Long = 0
  {
    val minibenchmarkStart = System.nanoTime()
    while (System.nanoTime() - minibenchmarkStart < 25L * 1000 * 1000) {
      action()
      timesIn25Milliseconds += 1
    }
    assert(timesIn25Milliseconds >= 1)
  }

  val times = ArrayBuffer[Double]()

  {
    val warmupStart = System.currentTimeMillis()
    while (System.currentTimeMillis() - warmupStart < 2L * 1000)
      action()
  }

  System.err.println("Warming up completed.")

  val benchmarkingStart = System.nanoTime()
  var benchmarkingTimeStillNotPassed = true
  while (benchmarkingTimeStillNotPassed) {

    val start = System.nanoTime()
    for (_ <- 1L to timesIn25Milliseconds)
      action()
    val end = System.nanoTime()
    var nanoTimePerOperation: Double = (end - start + (0.0).toDouble) / timesIn25Milliseconds.toDouble
    times.append(nanoTimePerOperation)

    if (end - benchmarkingStart >= 10L * 1000 * 1000 * 1000)
      benchmarkingTimeStillNotPassed = false
  }

  var avg: Double = 0.0
  times.foreach(avg += _)
  avg /= times.length

  var stdev: Double = 0.0
  for (x <- times)
    stdev += (x - avg) * (x - avg)
  assert(times.length >= 2)
  stdev /= (times.length - 1)
  stdev = Math.sqrt(stdev)

  TimeMeasurementResult(avg / 1000 / 1000, stdev / 1000 / 1000)

@main def measureSomething(): Unit =

  val g = measureRunTimes: () =>
//    var t = 100100
//    t *= 321984834
//    t /= 1238433
//    t /= 1222
    Thread.sleep(11)
  println(g)

@main def measureTimesNew: Unit =

  // mkdir -p /tmp/FIO && sudo mount -t tmpfs -o size=8g tmpfs /tmp/FIO

  /* Linux:
  {
    "File writing": {

      "Size 4": {
  "PosixLikeIO": [0.019330398100000007, 0.02539598958144832],
  "Java FileWriter": [0.009646040700000004, 0.0053465575899217625],
  "Java Files.writeString": [0.0060547288999999995, 0.0035444597483020052],
  },

      "Size 41943040": {
  "PosixLikeIO": [17.027633586999997, 0.7486469677380989],
  "Java FileWriter": [30.45175985000001, 4.189321550366144],
  "Java Files.writeString": [17.11834752, 2.6243451330927066],
  },
  },

    "File reading": {

      "Size 4": {
  "PosixLikeIO": [0.017123947300000008, 0.03542323168500447],
  "Java FileReeader": [0.005593237600000002, 0.001719272596104352],
  "Java Files.readString": [0.004321956800000001, 0.0024380564850612573],
  },

      "Size 41943040": {
  "PosixLikeIO": [28.739885630999982, 0.4682373710339424],
  "Java FileReeader": [12.521057180000003, 0.15858452136794252],
  "Java Files.readString": [19.780039794999997, 2.1086356124522125],
  },
  },
  }
  */

  given ExecutionContext = ExecutionContext.global

  val dataAlmostJson = StringBuffer() // TEST:String -> PARAMETER:String -> METHOD:String -> TIMES:List[Double]
  dataAlmostJson.append("{")

  def measure[T](methodName: String, timesInner: Int = 100, timesOuter: Int = 100)(action: () => T): String =
    val times = ArrayBuffer[Double]()
    for (_ <- 1 to timesOuter)
      val timeStart = System.nanoTime()
      for (_ <- 1 to timesInner)
        action()
      val timeEnd = System.nanoTime()
      times += ((timeEnd - timeStart + 0.0) / 1000 / 1000 / timesInner)

    var avg: Double = 0.0
    times.foreach(avg += _)
    avg /= times.length

    var stdev: Double = 0.0
    for (x <- times)
      stdev += (x - avg) * (x - avg)
    assert(times.length >= 2)
    stdev /= (times.length - 1)
    stdev = Math.sqrt(stdev)

    val ret = StringBuffer()
    ret.append("\"")
    ret.append(methodName)
    ret.append("\": [")
    ret.append(avg)
    ret.append(", ")
    ret.append(stdev)
    ret.append("],\n")
    ret.toString


  val bigStringBuilder = new StringBuilder()
  for (_ <- 1 to 10 * 1024 * 1024) bigStringBuilder.append("abcd")
  val bigString = bigStringBuilder.toString()

  def deleteFiles(): Unit =
    for (p <- Array("x", "y", "z"))
      try
        Files.delete(Paths.get("/tmp/FIO/" + p + ".txt"))
      catch case e: NoSuchFileException => ()

  deleteFiles()

  dataAlmostJson.append("\n\t\"File writing\": {\n")
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

        dataAlmostJson.append(measure("Java Files.writeString", timesInner = if size < 100 then 100 else 10): () =>
          Files.writeString(Paths.get("/tmp/FIO/z.txt"), bigString.substring(0, size))
        )
        println("done 3")
      }
      dataAlmostJson.append("},\n")
  }
  dataAlmostJson.append("},\n")

  dataAlmostJson.append("\n\t\"File reading\": {\n")
  {
    for (size <- Seq(4, 40 * 1024 * 1024))
      println("size " + size.toString)
      deleteFiles()
      Files.writeString(Paths.get("/tmp/FIO/x.txt"), bigString.substring(0, size))
      Files.writeString(Paths.get("/tmp/FIO/y.txt"), bigString.substring(0, size))
      Files.writeString(Paths.get("/tmp/FIO/z.txt"), bigString.substring(0, size))

      dataAlmostJson.append("\n\t\t\"Size " + size.toString + "\": {\n")
      {
        dataAlmostJson.append(measure("PosixLikeIO", timesInner = if size < 100 then 100 else 10): () =>
          Async.blocking:
            PIOHelper.withFile("/tmp/FIO/x.txt", StandardOpenOption.READ): f =>
              f.readString(size).result
        )
        println("done 1")

        val buffer = new Array[Char](size)
        dataAlmostJson.append(measure("Java FileReeader", timesInner = if size < 100 then 100 else 10): () =>
          val reader = new FileReader("/tmp/FIO/y.txt")
          reader.read(buffer)
          reader.close()
        )
        println("done 2")


        dataAlmostJson.append(measure("Java Files.readString", timesInner = if size < 100 then 100 else 10): () =>
          Files.readString(Paths.get("/tmp/FIO/z.txt"))
        )
        println("done 3")
      }
      dataAlmostJson.append("},\n")
  }
  dataAlmostJson.append("},\n")

  dataAlmostJson.append("}")
  println(dataAlmostJson.toString)

  /* Linux
  {
    "Write 4B File": {

      "Size 4": {
  "PosixLikeIO": [0.29, 0.06, 0.06, 0.05, 0.04, 0.05, 0.04, 0.04, 0.03, 0.03, 0.04, 0.04, 0.03, 0.03, 0.03, 0.03, 0.02, 0.02, 0.02, 0.02, 0.01, 0.02, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.02, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, 0.01, 0.02, 0.01, ],
  "Java FileWriter": [0.03, 0.03, 0.02, 0.02, 0.01, 0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.01, 0.01, 0.0, 0.01, 0.01, 0.05, 0.0, 0.01, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, ],
  "Java Files.write": [0.03, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.0, 0.01, 0.01, 0.0, 0.01, 0.0, 0.01, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.01, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.01, 0.0, 0.0, 0.01, 0.0, 0.0, 0.01, 0.01, 0.0, 0.01, 0.0, ],
  },

      "Size 41943040": {
  "PosixLikeIO": [24.2, 17.1, 17.1, 17.3, 18.1, 17.2, 17.1, 17.1, 17.0, 17.0, 17.2, 17.1, 17.0, 16.7, 17.2, 17.0, 16.9, 17.0, 17.1, 16.8, 16.9, 16.6, 16.7, 17.0, 16.7, 16.7, 17.0, 17.1, 17.0, 17.3, 17.2, 17.0, 17.2, 17.0, 17.1, 17.0, 17.0, 16.9, 17.2, 17.0, 16.9, 16.7, 17.1, 17.1, 16.8, 17.2, 17.3, 17.1, 17.0, 17.1, 17.3, 17.0, 17.1, 17.3, 17.1, 17.0, 17.1, 16.9, 17.2, 16.9, 17.0, 17.1, 17.2, 17.1, 17.1, 17.0, 17.0, 17.2, 17.0, 16.9, 17.7, 18.0, 18.3, 18.8, 19.0, 21.6, 19.9, 19.8, 19.8, 19.7, 19.6, 19.7, 19.9, 19.7, 19.6, 19.8, 20.1, 19.7, 19.8, 19.3, 19.7, 19.6, 19.5, 19.7, 19.4, 19.4, 19.5, 19.3, 19.9, 19.6, ],
  "Java FileWriter": [51.7, 47.0, 46.8, 46.9, 46.8, 46.8, 46.7, 46.4, 46.6, 46.4, 45.2, 45.5, 45.9, 45.2, 45.4, 45.2, 45.1, 44.9, 45.0, 44.9, 45.6, 45.2, 46.7, 57.8, 55.7, 65.3, 54.0, 48.6, 51.4, 50.9, 49.3, 47.6, 46.5, 45.2, 44.8, 44.6, 44.1, 44.5, 44.2, 44.3, 44.1, 44.3, 44.2, 44.3, 44.3, 44.3, 43.8, 44.2, 44.2, 44.2, 45.1, 44.3, 44.5, 44.2, 44.4, 43.9, 44.7, 44.7, 44.4, 44.4, 43.9, 44.6, 44.3, 32.1, 31.7, 31.9, 32.1, 31.5, 31.4, 31.5, 31.6, 31.5, 31.6, 31.4, 32.1, 34.0, 34.0, 32.5, 32.7, 32.2, 34.4, 33.6, 33.3, 32.9, 33.3, 33.3, 36.7, 45.3, 82.2, 85.4, 48.2, 37.3, 35.2, 39.1, 34.1, 37.6, 37.3, 34.8, 33.8, 33.2, ],
  "Java Files.write": [26.8, 33.4, 28.7, 27.4, 27.3, 29.0, 29.7, 33.1, 30.3, 33.4, 31.5, 34.0, 29.5, 27.2, 28.3, 33.6, 29.4, 27.5, 28.9, 29.7, 29.9, 35.0, 29.7, 27.6, 26.4, 27.8, 33.2, 29.8, 27.5, 26.1, 27.4, 30.3, 29.7, 35.3, 31.8, 27.5, 27.2, 29.6, 28.2, 26.9, 27.8, 27.4, 30.7, 29.1, 31.3, 29.6, 27.6, 26.0, 25.9, 25.2, 25.5, 25.0, 24.6, 24.4, 24.9, 24.6, 24.3, 24.4, 24.5, 24.5, 24.9, 24.7, 24.5, 24.6, 24.5, 24.7, 24.5, 25.7, 26.3, 25.6, 25.4, 25.2, 24.8, 24.5, 24.8, 24.7, 24.9, 24.4, 25.1, 24.4, 24.7, 24.5, 25.1, 24.6, 24.8, 24.4, 24.3, 24.6, 24.6, 24.4, 24.9, 24.5, 24.8, 24.8, 24.7, 24.6, 24.6, 24.4, 24.8, 24.8, ],
  },
  },
  }

  {
    "File writing": {

      "Size 4": {
  "PosixLikeIO": [0.0397784622, 0.08412340604573831],
  "Java FileWriter": [0.010826620499999997, 0.00979259772337624],
  "Java Files.write": [0.007529464599999997, 0.0028499973824777695],
  },

      "Size 41943040": {
  "PosixLikeIO": [16.846597593, 0.889024137544089],
  "Java FileWriter": [29.068105414999977, 3.766062167872921],
  "Java Files.write": [18.96376850600001, 0.20493288428568684],
  },
  },
  }
  */
