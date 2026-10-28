package example

import mainargs.{ParserForMethods, arg, main}

object Main {
  @main
  def fileRead(@arg fifo: String): Unit = {
    KQueueExampleFileRead.run(fifo)
  }
  @main
  def fileWrite(@arg fifo: String, @arg(short = 'm') message: String): Unit = {
    KQueueExampleFileWrite.run(fifo, message)
  }
  @main
  def timer(): Unit = {
    KQueueExampleTimer.run()
  }
  @main
  def timers(@arg count: Int = 2000): Unit = {
    KQueueExampleTimers.run(count)
  }
  @main(name = "broken-pipe")
  def brokenPipe(@arg fifo: String): Unit = {
    KQueueExampleBrokenPipe.run(fifo)
  }
  @main
  def sockServe(@arg sock: String): Unit = {
    KQueueExampleServerSocket.run(sock)
  }
  @main
  def sock(@arg sock: String): Unit = {
    KQueueExampleSocket.run(sock)
  }
  @main(name = "sock4-serve")
  def sock4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999): Unit = {
    KQueueExampleServerSocket.run(KQueueExampleAddress.IPv4(host, port))
  }
  @main(name = "sock4")
  def sock4(@arg host: String = "127.0.0.1", @arg port: Int = 9999): Unit = {
    KQueueExampleSocket.run(KQueueExampleAddress.IPv4(host, port))
  }
  @main(name = "sock6-serve")
  def sock6Serve(@arg host: String = "::1", @arg port: Int = 9999): Unit = {
    KQueueExampleServerSocket.run(KQueueExampleAddress.IPv6(host, port))
  }
  @main(name = "sock6")
  def sock6(@arg host: String = "::1", @arg port: Int = 9999): Unit = {
    KQueueExampleSocket.run(KQueueExampleAddress.IPv6(host, port))
  }
  @main
  def kvServe(@arg sock: String): Unit = {
    KQueueExampleKeyValue.serve(KQueueExampleAddress.Unix(sock))
  }
  @main
  def kv(@arg sock: String, @arg count: Int = 1000): Unit = {
    KQueueExampleKeyValue.run(KQueueExampleAddress.Unix(sock), count)
  }
  @main(name = "kv4-serve")
  def kv4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999): Unit = {
    KQueueExampleKeyValue.serve(KQueueExampleAddress.IPv4(host, port))
  }
  @main(name = "kv4")
  def kv4(@arg host: String = "127.0.0.1", @arg port: Int = 9999, @arg count: Int = 1000): Unit = {
    KQueueExampleKeyValue.run(KQueueExampleAddress.IPv4(host, port), count)
  }
  @main(name = "kv6-serve")
  def kv6Serve(@arg host: String = "::1", @arg port: Int = 9999): Unit = {
    KQueueExampleKeyValue.serve(KQueueExampleAddress.IPv6(host, port))
  }
  @main(name = "kv6")
  def kv6(@arg host: String = "::1", @arg port: Int = 9999, @arg count: Int = 1000): Unit = {
    KQueueExampleKeyValue.run(KQueueExampleAddress.IPv6(host, port), count)
  }
  @main
  def datagramServe(@arg sock: String): Unit = {
    KQueueExampleDatagramServerSocket.run(KQueueExampleAddress.Unix(sock))
  }
  @main
  def datagram(
      @arg sock: String,
      @arg localSock: String,
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!"
  ): Unit = {
    KQueueExampleDatagramSocket.run(sock, localSock, message)
  }
  @main(name = "datagram4-serve")
  def datagram4Serve(@arg host: String = "127.0.0.1", @arg port: Int = 9999): Unit = {
    KQueueExampleDatagramServerSocket.run(KQueueExampleAddress.IPv4(host, port))
  }
  @main(name = "datagram4")
  def datagram4(
      @arg host: String = "127.0.0.1",
      @arg port: Int = 9999,
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!"
  ): Unit = {
    KQueueExampleDatagramSocket.run(KQueueExampleAddress.IPv4(host, port), message)
  }
  @main(name = "datagram6-serve")
  def datagram6Serve(@arg host: String = "::1", @arg port: Int = 9999): Unit = {
    KQueueExampleDatagramServerSocket.run(KQueueExampleAddress.IPv6(host, port))
  }
  @main(name = "datagram6")
  def datagram6(
      @arg host: String = "::1",
      @arg port: Int = 9999,
      @arg(short = 'm') message: String = "Hello from Scala Native KQueue Datagram Example!"
  ): Unit = {
    KQueueExampleDatagramSocket.run(KQueueExampleAddress.IPv6(host, port), message)
  }
  @main
  def fileHandles(@arg count: Int = 32): Unit = {
    KQueueExampleFileHandles.run(count)
  }
  @main
  def whois(
      @arg host: String = "whois.iana.org",
      @arg port: Int = 43,
      @arg query: String = "example.com"
  ): Unit = {
    KQueueExampleWhois.run(host, port, query)
  }
  @main
  def cancel(): Unit = {
    KQueueExampleCancel.run()
  }
  @main
  def resolve(@arg host: String = "localhost"): Unit = {
    KQueueExampleWhois.resolve(host)
  }
  @main
  def echo(@arg msg: String): Unit = {
    println(msg)
  }
  @main
  def targetInfo(): Unit = {
    (
      s"target.arch: ${scalanative.meta.LinktimeInfo.target.arch}",
      s"target.vendor: ${scalanative.meta.LinktimeInfo.target.vendor}",
      s"target.os: ${scalanative.meta.LinktimeInfo.target.os}",
      s"target.env: ${scalanative.meta.LinktimeInfo.target.env}",
      s"continuations: ${scalanative.meta.LinktimeInfo.isContinuationsSupported}",
      s"gc: ${scalanative.meta.LinktimeInfo.garbageCollector}",
      s"multithreaded: ${scalanative.meta.LinktimeInfo.isMultithreadingEnabled}",
      s"debug: ${scalanative.meta.LinktimeInfo.debugMode}",
      s"release: ${scalanative.meta.LinktimeInfo.releaseMode}"
    ).productIterator.foreach(println)
  }
  def main(args: Array[String]): Unit = {
    // `gears <command> ...` runs the gears mirror of a demo, from the testkit, on kqueue.
    if args.headOption.contains("gears") then GearsDemos.main(args.tail)
    else ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }
}
